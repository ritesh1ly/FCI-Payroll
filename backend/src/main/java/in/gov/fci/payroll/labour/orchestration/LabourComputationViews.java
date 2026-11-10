package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.labour.business.AttendanceRules;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of C-03, C-04, C-05 and C-06 as Wireframes v6.1 draws them (OD-121; DDL
 * labour_attendance_month, work_slip, labour_norm, labour_min_wage, labour_incentive_worksheet).
 *
 * <p>The wage and incentive runs of these screens are not built: their rules wait for FCI (PENDING
 * LBR-20d-2, LBR-20d-3, LBR-21d-1, NWNP-22d-1). So nothing here computes a wage or an incentive. A
 * screen shows the inputs a run would read (gang, labour, days, bags, norms and rates in force) and
 * a figure of a run only when a run has stored one (payroll_result, labour_incentive_worksheet);
 * BR-CYC-011..017 and the rules of {@code IncentiveRules}, {@code DpsWageRules} and {@code
 * NwnpWageRules} are untouched. The row Status says what stands in the way of a run: the labels are
 * the M-16 list LABOUR_GANG_STATUS.
 *
 * <p>Every read is limited to the depots of the session's offices (R-RBAC data scope). A period
 * without slips shows the unit's latest earlier period that has some (as C-02 does).
 */
@Service
@Loggable
public class LabourComputationViews {

    /** The drawn screen of each engine (DDL work_slip.engine_code). */
    private static final Map<String, String> SCREEN =
            Map.of("DEPT_LABOUR", "C-03", "DPS_LABOUR", "C-04", "NWNP_LABOUR", "C-05");

    private static final String INCENTIVE_SCREEN = "C-06";
    private static final String VIEW = "VIEW";

    /** A gang of the drawn table: not the Ancillary Pool (SRS-2-46: left out of gang outputs). */
    private static final String GANGS =
            " FROM labour_gang g JOIN org_location l ON l.location_id = g.depot_location_id WHERE"
                    + " (:all = 'Y' OR l.location_id IN (:locations) OR l.parent_location_id IN"
                    + " (:locations)) AND g.gang_type = 'HANDLING' AND g.payroll_type IN (:types)"
                    + " AND g.effective_from <= :end AND (g.effective_to IS NULL OR g.effective_to"
                    + " >= :start)";

    /** The members of gang g during the period. */
    private static final String MEMBERS =
            " labour_gang_membership m WHERE m.gang_id = g.gang_id AND m.effective_from <= :end AND"
                    + " (m.effective_to IS NULL OR m.effective_to >= :start)";

    private static final String SLIPS =
            " FROM work_slip w WHERE w.gang_id = g.gang_id AND w.work_slip_date BETWEEN :start AND"
                    + " :end AND w.slip_status NOT IN ('CANCELLED', 'REJECTED')";

    private static final String ROW =
            "SELECT g.gang_id, g.gang_code, g.gang_name, l.location_code, l.location_name, (SELECT"
                    + " COUNT(*) FROM"
                    + MEMBERS
                    + ") labour, (SELECT COUNT(*) FROM"
                    + MEMBERS
                    + " AND m.gang_role = 'SARDAR') sardar, (SELECT COUNT(*) FROM"
                    + MEMBERS
                    + " AND m.gang_role = 'MANDAL') mandal, (SELECT COUNT(*) FROM"
                    + MEMBERS
                    + " AND m.gang_role = 'HANDLING') handling, (SELECT COUNT(*) FROM"
                    + MEMBERS
                    + " AND m.gang_role = 'ANCILLARY') ancillary, (SELECT SUM(a.present_days) FROM"
                    + " labour_attendance_month a WHERE a.payroll_yymm = :yymm AND a.employee_id IN"
                    + " (SELECT m.employee_id FROM"
                    + MEMBERS
                    + ")) present_days, (SELECT COUNT(*) FROM labour_attendance_month a WHERE"
                    + " a.payroll_yymm = :yymm AND a.present_days > 0 AND a.employee_id IN (SELECT"
                    + " m.employee_id FROM"
                    + MEMBERS
                    + ")) present_labour, (SELECT COUNT(*)"
                    + SLIPS
                    + ") slips, (SELECT SUM(r.number_of_bags) FROM work_slip_row r WHERE"
                    + " r.work_slip_id IN (SELECT w.work_slip_id"
                    + SLIPS
                    + ")) bags, (SELECT SUM((CAST(SUBSTR(r.end_time, 1, 2) AS INTEGER) * 60 +"
                    + " CAST(SUBSTR(r.end_time, 4, 2) AS INTEGER) - CAST(SUBSTR(r.start_time, 1, 2)"
                    + " AS INTEGER) * 60 - CAST(SUBSTR(r.start_time, 4, 2) AS INTEGER)) / 60) FROM"
                    + " work_slip_row r WHERE r.pbnw_reason IS NOT NULL AND r.work_slip_id IN"
                    + " (SELECT w.work_slip_id"
                    + SLIPS
                    + ")) no_work_hours, (SELECT MIN(n.min_labour_number) FROM labour_norm n WHERE"
                    + " n.depot_location_id = g.depot_location_id AND n.engine_code ="
                    + " g.payroll_type AND n.effective_from <= :end AND (n.effective_to IS NULL OR"
                    + " n.effective_to >= :end)) mln, (SELECT SUM(s.incentive_amount) FROM"
                    + " labour_incentive_worksheet s WHERE s.gang_id = g.gang_id AND"
                    + " s.payroll_yymm = :yymm) incentive"
                    + GANGS
                    + " ORDER BY g.gang_code OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY";

    /** A rate in force on the last day of the period for one role of a labour payroll. */
    private static final String RATES =
            "SELECT w.gang_role, MIN(w.min_guaranteed_wage) mgw, MIN(w.interim_relief) ir,"
                    + " MIN(w.goi_rate) goi, MIN(w.basic_wage) basic, MIN(w.vda) vda FROM"
                    + " labour_min_wage w WHERE w.engine_code = :engine AND w.effective_from <="
                    + " :end AND (w.effective_to IS NULL OR w.effective_to >= :end) GROUP BY"
                    + " w.gang_role ORDER BY w.gang_role";

    /** What the top of a computation screen shows; a value no run stored is null. */
    public record Summary(
            String period,
            long population,
            long depots,
            long gangs,
            long computed,
            long notReady,
            BigDecimal wage,
            List<Rate> rates) {}

    /** The rate in force for a gang role (M-05); a value not configured is null. */
    public record Rate(
            String role,
            BigDecimal mgw,
            BigDecimal interimRelief,
            BigDecimal goiRate,
            BigDecimal basicWage,
            BigDecimal vda) {}

    /** A row of a Gang-wise Result: inputs only until a run stores its figures. */
    public record GangRow(
            long gangId,
            String gang,
            String gangName,
            String depot,
            long labour,
            long sardar,
            long mandal,
            long handling,
            long ancillary,
            BigDecimal presentDays,
            long presentLabour,
            long slips,
            BigDecimal bags,
            BigDecimal noWorkHours,
            Long mln,
            BigDecimal incentive,
            String status) {}

    /** The tiles of C-06. */
    public record IncentiveSummary(
            String period,
            long depots,
            long depotsWithNorms,
            long gangsComputed,
            long recomputations,
            BigDecimal value) {}

    /** A whole-gang re-computation (DDL labour_incentive_recompute, FRS §8.11.7). */
    public record Recomputation(
            long id,
            String gang,
            String period,
            String reason,
            BigDecimal original,
            BigDecimal revised) {}

    /** Incentive Basic beside Salary Basic for one labour (DDL labour_incentive_basic, DR-8). */
    public record IncentiveBasic(
            long id,
            String labourCode,
            String name,
            BigDecimal salaryBasic,
            BigDecimal incentiveBasic,
            java.time.LocalDate effectiveFrom) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;

    public LabourComputationViews(NamedParameterJdbcTemplate named, Authorization policy) {
        this.named = named;
        this.policy = policy;
    }

    /** C-03 / C-04 / C-05 tiles and the rates of the calculation trace. */
    @Transactional(readOnly = true)
    public Summary summary(AccessContext context, String engine, YearMonth period) {
        policy.require(context, SCREEN.get(engine), VIEW);
        List<String> types = List.of(engine);
        YearMonth shown = shownPeriod(context, types, period);
        MapSqlParameterSource p = params(context, types, shown);
        long population =
                count(
                        "SELECT COUNT(*) FROM labour_profile x JOIN org_location l ON l.location_id"
                                + " = x.depot_location_id WHERE (:all = 'Y' OR l.location_id IN"
                                + " (:locations) OR l.parent_location_id IN (:locations)) AND"
                                + " x.payroll_type IN (:types) AND x.labour_status = 'ACTIVE'",
                        p);
        long depots =
                count(
                        "SELECT COUNT(DISTINCT x.depot_location_id) FROM labour_profile x JOIN"
                                + " org_location l ON l.location_id = x.depot_location_id WHERE"
                                + " (:all = 'Y' OR l.location_id IN (:locations) OR"
                                + " l.parent_location_id IN (:locations)) AND x.payroll_type IN"
                                + " (:types) AND x.labour_status = 'ACTIVE'",
                        p);
        long gangs = count("SELECT COUNT(*)" + GANGS, p);
        long computed =
                count(
                        "SELECT COUNT(*) FROM payroll_result r JOIN labour_profile x ON"
                                + " x.employee_id = r.employee_id JOIN org_location l ON"
                                + " l.location_id = x.depot_location_id WHERE (:all = 'Y' OR"
                                + " l.location_id IN (:locations) OR l.parent_location_id IN"
                                + " (:locations)) AND r.payroll_type IN (:types) AND"
                                + " r.payroll_yymm = :yymm",
                        p);
        BigDecimal wage =
                named.queryForObject(
                        "SELECT SUM(r.gross_earnings) FROM payroll_result r JOIN labour_profile x"
                                + " ON x.employee_id = r.employee_id JOIN org_location l ON"
                                + " l.location_id = x.depot_location_id WHERE (:all = 'Y' OR"
                                + " l.location_id IN (:locations) OR l.parent_location_id IN"
                                + " (:locations)) AND r.payroll_type IN (:types) AND"
                                + " r.payroll_yymm = :yymm",
                        p,
                        BigDecimal.class);
        long ready =
                count(
                        "SELECT COUNT(*)"
                                + GANGS
                                + " AND EXISTS (SELECT 1"
                                + SLIPS
                                + ") AND EXISTS (SELECT 1 FROM"
                                + MEMBERS
                                + " AND m.gang_role = 'SARDAR') AND EXISTS (SELECT 1 FROM"
                                + MEMBERS
                                + " AND m.gang_role = 'MANDAL')",
                        p);
        List<Rate> rates =
                named.query(
                        RATES,
                        p.addValue("engine", engine),
                        (rs, i) ->
                                new Rate(
                                        rs.getString("gang_role"),
                                        rs.getBigDecimal("mgw"),
                                        rs.getBigDecimal("ir"),
                                        rs.getBigDecimal("goi"),
                                        rs.getBigDecimal("basic"),
                                        rs.getBigDecimal("vda")));
        return new Summary(
                shown.toString(), population, depots, gangs, computed, gangs - ready, wage, rates);
    }

    /** C-03 / C-04 / C-05: one page of the Gang-wise Result, by gang code. */
    @Transactional(readOnly = true)
    public Paged<GangRow> gangs(
            AccessContext context, String engine, YearMonth period, PageSpec spec) {
        policy.require(context, SCREEN.get(engine), VIEW);
        List<String> types = List.of(engine);
        return page(context, types, shownPeriod(context, types, period), spec);
    }

    /** C-06 tiles: norms loaded, gangs computed, re-computations, value of the period. */
    @Transactional(readOnly = true)
    public IncentiveSummary incentiveSummary(AccessContext context, YearMonth period) {
        policy.require(context, INCENTIVE_SCREEN, VIEW);
        List<String> types = List.of("DEPT_LABOUR", "DPS_LABOUR");
        YearMonth shown = shownPeriod(context, types, period);
        MapSqlParameterSource p = params(context, types, shown);
        long depots = count("SELECT COUNT(DISTINCT g.depot_location_id)" + GANGS, p);
        long withNorms =
                count(
                        "SELECT COUNT(DISTINCT g.depot_location_id)"
                                + GANGS
                                + " AND EXISTS (SELECT 1 FROM labour_norm n WHERE"
                                + " n.depot_location_id = g.depot_location_id AND n.engine_code ="
                                + " g.payroll_type AND n.effective_from <= :end AND"
                                + " (n.effective_to IS NULL OR n.effective_to >= :end))",
                        p);
        long computed =
                count(
                        "SELECT COUNT(DISTINCT g.gang_id)"
                                + GANGS
                                + " AND EXISTS (SELECT 1 FROM labour_incentive_worksheet s WHERE"
                                + " s.gang_id = g.gang_id AND s.payroll_yymm = :yymm)",
                        p);
        long recomputed =
                count(
                        "SELECT COUNT(*) FROM labour_incentive_recompute c WHERE c.gang_id IN"
                                + " (SELECT g.gang_id"
                                + GANGS
                                + ")",
                        p);
        BigDecimal value =
                named.queryForObject(
                        "SELECT SUM(s.incentive_amount) FROM labour_incentive_worksheet s WHERE"
                                + " s.payroll_yymm = :yymm AND s.gang_id IN (SELECT g.gang_id"
                                + GANGS
                                + ")",
                        p,
                        BigDecimal.class);
        return new IncentiveSummary(
                shown.toString(), depots, withNorms, computed, recomputed, value);
    }

    /** C-06: Incentive — gang-wise, one page. */
    @Transactional(readOnly = true)
    public Paged<GangRow> incentiveGangs(AccessContext context, YearMonth period, PageSpec spec) {
        policy.require(context, INCENTIVE_SCREEN, VIEW);
        List<String> types = List.of("DEPT_LABOUR", "DPS_LABOUR");
        return page(context, types, shownPeriod(context, types, period), spec);
    }

    /** C-06: the re-computations stored for the depots of the session, latest first. */
    @Transactional(readOnly = true)
    public Paged<Recomputation> recomputations(AccessContext context, PageSpec spec) {
        policy.require(context, INCENTIVE_SCREEN, VIEW);
        MapSqlParameterSource p = OfficeScope.of(context, policy.isAtHeadquarters(context));
        String from =
                " FROM labour_incentive_recompute c JOIN labour_gang g ON g.gang_id = c.gang_id"
                        + " JOIN org_location l ON l.location_id = g.depot_location_id WHERE (:all"
                        + " = 'Y' OR l.location_id IN (:locations) OR l.parent_location_id IN"
                        + " (:locations))";
        long total = count("SELECT COUNT(*)" + from, p);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Recomputation> rows =
                named.query(
                        "SELECT c.recompute_id, g.gang_code, c.payroll_yymm, c.recompute_reason,"
                                + " c.original_total, c.revised_total"
                                + from
                                + " ORDER BY c.payroll_yymm DESC, c.recompute_id DESC OFFSET"
                                + " :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new Recomputation(
                                        rs.getLong("recompute_id"),
                                        rs.getString("gang_code"),
                                        rs.getString("payroll_yymm"),
                                        rs.getString("recompute_reason"),
                                        rs.getBigDecimal("original_total"),
                                        rs.getBigDecimal("revised_total")));
        return new Paged<>(rows, spec.page(), spec.size(), total);
    }

    /** C-06: Incentive Basic vs Salary Basic by labour, the version in force (SRS-2-51, DR-8). */
    @Transactional(readOnly = true)
    public Paged<IncentiveBasic> basics(AccessContext context, PageSpec spec) {
        policy.require(context, INCENTIVE_SCREEN, VIEW);
        MapSqlParameterSource p = OfficeScope.of(context, policy.isAtHeadquarters(context));
        String from =
                " FROM labour_incentive_basic b JOIN labour_profile x ON x.employee_id ="
                        + " b.employee_id JOIN employee e ON e.employee_id = b.employee_id JOIN"
                        + " org_location l ON l.location_id = x.depot_location_id WHERE (:all ="
                        + " 'Y' OR l.location_id IN (:locations) OR l.parent_location_id IN"
                        + " (:locations)) AND b.effective_to IS NULL";
        long total = count("SELECT COUNT(*)" + from, p);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<IncentiveBasic> rows =
                named.query(
                        "SELECT b.incentive_basic_id, x.labour_number, e.first_name, e.last_name,"
                                + " e.current_basic, b.incentive_basic, b.effective_from"
                                + from
                                + " ORDER BY x.labour_number OFFSET :offset ROWS FETCH NEXT :size"
                                + " ROWS ONLY",
                        p,
                        (rs, i) ->
                                new IncentiveBasic(
                                        rs.getLong("incentive_basic_id"),
                                        rs.getString("labour_number"),
                                        name(rs.getString("first_name"), rs.getString("last_name")),
                                        rs.getBigDecimal("current_basic"),
                                        rs.getBigDecimal("incentive_basic"),
                                        rs.getDate("effective_from").toLocalDate()));
        return new Paged<>(rows, spec.page(), spec.size(), total);
    }

    private Paged<GangRow> page(
            AccessContext context, List<String> types, YearMonth shown, PageSpec spec) {
        MapSqlParameterSource p = params(context, types, shown);
        long total = count("SELECT COUNT(*)" + GANGS, p);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        boolean incentive = types.size() > 1;
        List<GangRow> rows =
                named.query(
                        ROW,
                        p,
                        (rs, i) -> {
                            long sardar = rs.getLong("sardar");
                            long mandal = rs.getLong("mandal");
                            long slips = rs.getLong("slips");
                            long mln = rs.getLong("mln");
                            Long norm = rs.wasNull() ? null : mln;
                            BigDecimal paid = rs.getBigDecimal("incentive");
                            return new GangRow(
                                    rs.getLong("gang_id"),
                                    rs.getString("gang_code"),
                                    rs.getString("gang_name"),
                                    rs.getString("location_code")
                                            + " - "
                                            + rs.getString("location_name"),
                                    rs.getLong("labour"),
                                    sardar,
                                    mandal,
                                    rs.getLong("handling"),
                                    rs.getLong("ancillary"),
                                    rs.getBigDecimal("present_days"),
                                    rs.getLong("present_labour"),
                                    slips,
                                    rs.getBigDecimal("bags"),
                                    rs.getBigDecimal("no_work_hours"),
                                    norm,
                                    paid,
                                    status(types, sardar, mandal, slips, norm, paid, incentive));
                        });
        return new Paged<>(rows, spec.page(), spec.size(), total);
    }

    /** What stands in the way of a run for the gang (LABOUR_GANG_STATUS), first that applies. */
    static String status(
            List<String> types,
            long sardar,
            long mandal,
            long slips,
            Long norm,
            BigDecimal paid,
            boolean incentive) {
        if (sardar == 0 || mandal == 0) {
            return types.contains("NWNP_LABOUR") ? "GANG_INCOMPLETE" : "NO_MANDAL";
        }
        if (slips == 0) {
            return "WORK_SLIP_MISSING";
        }
        if (incentive && norm == null) {
            return "NORM_MISSING";
        }
        return paid != null ? "COMPUTED" : "NOT_COMPUTED";
    }

    /**
     * The requested period when it has slips of the payroll types in the session's depots, else the
     * latest earlier one that has, else the requested one.
     */
    private YearMonth shownPeriod(AccessContext context, List<String> types, YearMonth period) {
        MapSqlParameterSource p = params(context, types, period);
        Date latest =
                named.queryForObject(
                        "SELECT MAX(w.work_slip_date) FROM work_slip w JOIN labour_gang g ON"
                                + " g.gang_id = w.gang_id JOIN org_location l ON l.location_id ="
                                + " g.depot_location_id WHERE (:all = 'Y' OR l.location_id IN"
                                + " (:locations) OR l.parent_location_id IN (:locations)) AND"
                                + " g.payroll_type IN (:types) AND w.work_slip_date <= :end",
                        p,
                        Date.class);
        return latest == null ? period : YearMonth.from(latest.toLocalDate());
    }

    private MapSqlParameterSource params(
            AccessContext context, List<String> types, YearMonth period) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context))
                .addValue("types", types)
                .addValue("start", Date.valueOf(period.atDay(1)))
                .addValue("end", Date.valueOf(period.atEndOfMonth()))
                .addValue("yymm", AttendanceRules.yymm(period));
    }

    private long count(String sql, MapSqlParameterSource p) {
        return RowCounts.of(named.queryForObject(sql, p, Long.class));
    }

    private static String name(String first, String last) {
        return last == null ? first : first + " " + last;
    }
}
