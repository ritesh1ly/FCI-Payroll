package in.gov.fci.payroll.reporting.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.i18n.MessageBundles;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.reporting.business.ReportErrorCode;
import in.gov.fci.payroll.reporting.business.ReportRules;
import in.gov.fci.payroll.reporting.business.ReportRules.Level;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Component-wise Summary (plan 15 phase 15b; SRS-18-28, MI-15, SRS-18-5, SRS-18-8, SRS-18-18,
 * SRS-18-23; D15-4): the earning and deduction totals of a month by office and payroll type, at a
 * hierarchy level (the cycle unit, its region, its zone, or all India), within the user's offices.
 * Committed ACTUAL results only, never a simulation (SRS-18-5); final when every cycle read is
 * locked, else provisional and marked so (SRS-18-23); the earnings and deductions reconcile to the
 * posted gross and deductions or the release is halted (SRS-18-8). Each export is recorded in
 * {@code report_output} and {@code report_export_log} (SRS-18-18).
 */
@Service
@Loggable
public class ComponentSummaryService {

    /** The catalogue key of this output (V051_2). */
    public static final String KEY = "COMPONENT_WISE_SUMMARY";

    /** One summary line. */
    public record Line(
            String levelCode,
            String levelName,
            String payrollType,
            int payCode,
            String component,
            String componentType,
            BigDecimal amount,
            long members) {}

    /** SRS-18-12 (a): whose results the summary reads (separated by the month's end). */
    public enum MemberStatus {
        CURRENT,
        SEPARATED,
        BOTH
    }

    /**
     * SRS-18-12 (a), O-04 parameters (US-GAP-37): an office of the user's Access Group scope (the
     * office and the offices under it), a payroll type, a category (the designation's {@code
     * category_code}) and the member status; each may be left out (null: every one; member status
     * BOTH).
     */
    public record Filters(
            Long officeId,
            String payrollType,
            String category,
            MemberStatus memberStatus,
            String fromEis,
            String toEis,
            Sort sort) {

        public static final Filters NONE = new Filters(null, null, null, MemberStatus.BOTH);

        public Filters {
            memberStatus = memberStatus == null ? MemberStatus.BOTH : memberStatus;
            fromEis = fromEis == null || fromEis.isBlank() ? null : fromEis.strip();
            toEis = toEis == null || toEis.isBlank() ? null : toEis.strip();
            sort = sort == null ? Sort.PAY_CODE : sort;
        }

        /** The filters without a member range, in the pay-code order. */
        public Filters(
                Long officeId, String payrollType, String category, MemberStatus memberStatus) {
            this(officeId, payrollType, category, memberStatus, null, null, null);
        }
    }

    /**
     * SRS-18-12 (US-GAP-265): the order of the lines on screen and in the export - by payroll type
     * and pay code (the report standard), or by amount, largest first, within each office.
     */
    public enum Sort {
        PAY_CODE,
        AMOUNT
    }

    /** An office O-04 offers: "code - name" (UI-DESC). */
    public record Office(long id, String label) {}

    /**
     * SRS-18-12 (a): what O-04 offers to choose from for a month: the offices of the user's Access
     * Group scope that hold payroll (Headquarters, zonal, regional and district offices: results
     * are kept by cycle unit, so a depot is reported with its office), the payroll types with
     * results in those offices and the designation categories held (reference list CATEGORY).
     */
    public record Options(
            List<Office> offices, List<String> payrollTypes, List<String> categories) {}

    /** The summary of a month: lines, finality and the reconciliation. */
    public record Summary(
            String yymm,
            String level,
            boolean provisional,
            BigDecimal earnings,
            BigDecimal deductions,
            BigDecimal postedGross,
            BigDecimal postedDeductions,
            boolean controlTotalsMatch,
            List<Line> lines,
            boolean verified) {}

    private static final String RESULTS =
            " FROM payroll_result r JOIN payroll_result_component rc ON rc.payroll_result_id ="
                    + " r.payroll_result_id JOIN pay_component c ON c.component_id ="
                    + " rc.component_id JOIN org_location l ON l.location_id = r.org_unit_id";

    private static final String WHERE =
            " WHERE r.payroll_yymm = :yymm AND r.run_type = 'ACTUAL' AND r.result_status ="
                + " 'CURRENT' AND (:all = 'Y' OR r.org_unit_id IN (:locations)) AND (:office = 'N'"
                + " OR r.org_unit_id IN (:offices)) AND (:type IS NULL OR r.payroll_type = :type)"
                + " AND (:status = 'BOTH' OR EXISTS (SELECT 1 FROM employee em WHERE em.employee_id"
                + " = r.employee_id AND ((:status = 'CURRENT' AND (em.separation_date IS NULL OR"
                + " em.separation_date > :monthEnd)) OR (:status = 'SEPARATED' AND"
                + " em.separation_date <= :monthEnd)))) AND (:category IS NULL OR EXISTS (SELECT 1"
                + " FROM employee ec JOIN org_designation d ON d.designation_id = ec.designation_id"
                + " WHERE ec.employee_id = r.employee_id AND d.category_code = :category))"
                    // SRS-18-12 (US-GAP-265): a member range (from / to employee number), each end
                    // optional; the posted totals read the same members, so they still reconcile.
                    + " AND (:fromEis IS NULL OR EXISTS (SELECT 1 FROM employee ef WHERE"
                    + " ef.employee_id = r.employee_id AND ef.emp_num >= :fromEis)) AND (:toEis IS"
                    + " NULL OR EXISTS (SELECT 1 FROM employee et WHERE et.employee_id ="
                    + " r.employee_id AND et.emp_num <= :toEis))";

    private static final String MEASURES =
            " r.payroll_type, c.legacy_pay_code, c.component_name, c.component_type,"
                    + " SUM(rc.amount) AS amount, COUNT(DISTINCT r.employee_id) AS members";

    private static final String BY_UNIT =
            "SELECT l.location_code AS level_code, l.location_name AS level_name,"
                    + MEASURES
                    + RESULTS
                    + WHERE
                    + " GROUP BY l.location_code, l.location_name, r.payroll_type,"
                    + " c.legacy_pay_code, c.component_name, c.component_type ORDER BY"
                    + " l.location_code, r.payroll_type, c.component_type DESC, c.legacy_pay_code";

    private static final String BY_REGION =
            "SELECT g.location_code AS level_code, g.location_name AS level_name,"
                    + MEASURES
                    + RESULTS
                    + " JOIN org_location g ON g.location_id = CASE WHEN l.location_type = 'DO'"
                    + " THEN l.parent_location_id ELSE l.location_id END"
                    + WHERE
                    + " GROUP BY g.location_code, g.location_name, r.payroll_type,"
                    + " c.legacy_pay_code, c.component_name, c.component_type ORDER BY"
                    + " g.location_code, r.payroll_type, c.component_type DESC, c.legacy_pay_code";

    private static final String BY_ZONE =
            "SELECT z.zone_code AS level_code, z.zone_name AS level_name,"
                    + MEASURES
                    + RESULTS
                    + " JOIN org_zone z ON z.zone_id = l.zone_id"
                    + WHERE
                    + " GROUP BY z.zone_code, z.zone_name, r.payroll_type, c.legacy_pay_code,"
                    + " c.component_name, c.component_type ORDER BY z.zone_code, r.payroll_type,"
                    + " c.component_type DESC, c.legacy_pay_code";

    private static final String ALL =
            "SELECT 'ALL' AS level_code, 'All India' AS level_name,"
                    + MEASURES
                    + RESULTS
                    + WHERE
                    + " GROUP BY r.payroll_type, c.legacy_pay_code, c.component_name,"
                    + " c.component_type ORDER BY r.payroll_type, c.component_type DESC,"
                    + " c.legacy_pay_code";

    private static final String POSTED =
            "SELECT COALESCE(SUM(r.gross_earnings), 0) AS gross, COALESCE(SUM(r.total_deductions),"
                + " 0) AS deductions, COUNT(DISTINCT r.cycle_instance_id) AS cycles, COUNT(DISTINCT"
                + " CASE WHEN ci.cycle_status = 'LOCKED' THEN r.cycle_instance_id END) AS locked,"
                + " COUNT(DISTINCT CASE WHEN ci.cycle_status IN ('VERIFIED', 'LOCKED') THEN"
                + " r.cycle_instance_id END) AS verified FROM payroll_result r JOIN"
                + " payroll_cycle_instance ci ON ci.cycle_instance_id = r.cycle_instance_id"
                    + WHERE;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final OrgDirectory org;
    private final MessageBundles bundles;
    private final Clock clock;

    public ComponentSummaryService(
            NamedParameterJdbcTemplate named,
            JdbcTemplate jdbc,
            Authorization policy,
            OrgDirectory org,
            MessageBundles bundles,
            Clock clock) {
        this.named = named;
        this.jdbc = jdbc;
        this.policy = policy;
        this.org = org;
        this.bundles = bundles;
        this.clock = clock;
    }

    /**
     * O-04: the summary on screen (VIEW). FRS V0.5 §23.9 Audit Process: "every report generation
     * records the report definition version, the parameters supplied, the requesting user and the
     * period", an unlocked period marked so: an on-screen generation is recorded in report_output
     * like an export (without an export log), under the version in force for the month. With no
     * approved version in force there is no version to record, and the screen shows the month as it
     * did; the export of it is refused as Blocked (SRS-18-24).
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Summary summary(AccessContext context, String yymm, Level level) {
        return summary(context, yymm, level, Filters.NONE);
    }

    /** O-04: the payroll types and categories to choose from (SRS-18-12 (a), US-GAP-37). */
    @Transactional(readOnly = true)
    public Options options(AccessContext context, String yymm) {
        policy.require(context, ReportRules.HERE, "VIEW");
        if (!ReportRules.isYymm(yymm)) {
            throw new FciException(
                    ReportErrorCode.PARAMETERS, new Object[] {"month"}, List.of(), null);
        }
        MapSqlParameterSource p =
                OfficeScope.of(context, policy.isAtHeadquarters(context)).addValue("yymm", yymm);
        return new Options(
                named.query(
                        "SELECT location_id, location_code, location_name FROM org_location WHERE"
                                + " location_type IN ('HQ', 'ZO', 'RO', 'DO') AND (:all = 'Y' OR"
                                + " location_id IN (:locations)) ORDER BY location_code,"
                                + " location_id",
                        p,
                        (rs, i) ->
                                new Office(
                                        rs.getLong(1), rs.getString(2) + " - " + rs.getString(3))),
                named.queryForList(
                        "SELECT DISTINCT r.payroll_type FROM payroll_result r WHERE r.payroll_yymm"
                                + " = :yymm AND r.run_type = 'ACTUAL' AND r.result_status ="
                                + " 'CURRENT' AND (:all = 'Y' OR r.org_unit_id IN (:locations))"
                                + " ORDER BY r.payroll_type",
                        p,
                        String.class),
                named.queryForList(
                        "SELECT DISTINCT category_code FROM org_designation WHERE category_code IS"
                                + " NOT NULL ORDER BY category_code",
                        p,
                        String.class));
    }

    /** The latest locked month of the session's offices (YYYYMM), or none. */
    public record LatestLocked(String yymm) {}

    /**
     * O-04 (SRS l.3471 precondition "locked and posted", l.3501 Period): the screen opens on the
     * latest month locked in the session's offices; none when no month is locked yet.
     */
    @Transactional(readOnly = true)
    public LatestLocked latestLocked(AccessContext context) {
        policy.require(context, ReportRules.HERE, "VIEW");
        MapSqlParameterSource p = OfficeScope.of(context, policy.isAtHeadquarters(context));
        List<String> found =
                named.queryForList(
                        "SELECT MAX(payroll_yymm) FROM payroll_cycle_instance WHERE cycle_status ="
                                + " 'LOCKED' AND (:all = 'Y' OR org_unit_id IN (:locations))",
                        p,
                        String.class);
        return new LatestLocked(found.isEmpty() ? null : found.getFirst());
    }

    /** O-04: the summary on screen for the parameters chosen (SRS-18-12 (a), US-GAP-37). */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Summary summary(AccessContext context, String yymm, Level level, Filters filters) {
        Summary s = compute(context, "VIEW", yymm, level, filters);
        versionFor(s)
                .ifPresent(
                        v ->
                                output(
                                        context,
                                        s,
                                        filters,
                                        ((Number) v.get("REPORT_VERSION_ID")).longValue()));
        return s;
    }

    /**
     * SRS-18-8: refuses the release before any byte is streamed when no version is in force, the
     * version needs the month locked, or the control totals do not match the posted payroll.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void requireReleasable(AccessContext context, String yymm, Level level) {
        requireReleasable(context, yymm, level, Filters.NONE);
    }

    /** As above, for the parameters chosen. */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void requireReleasable(
            AccessContext context, String yymm, Level level, Filters filters) {
        Summary s = compute(context, "EXPORT", yymm, level, filters);
        versionInForce(s);
        // FRS V0.5 l.7130: a provisional (pre-lock) report needs its cycles verified first.
        if (s.provisional() && !s.verified()) {
            throw new FciException(
                    ReportErrorCode.BLOCKED,
                    new Object[] {KEY, "the month " + s.yymm() + " must be verified first"},
                    List.of(),
                    null);
        }
        mismatch(s);
    }

    /**
     * O-04 Export (EXPORT): the summary as a file, marked Provisional before the lock; refused when
     * the control totals do not match the posted payroll (SRS-18-8). Recorded with its export.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void export(
            AccessContext context, String yymm, Level level, String format, ExportSink sink)
            throws IOException {
        export(context, yymm, level, Filters.NONE, "en", format, sink);
    }

    /**
     * O-04 Export to the common report standard (SRS-18-12 (c), (d); SRS-G-40; US-GAP-37): the
     * report header (name, period, scope, run date-time and user) above the column titles, in the
     * language chosen (English or Hindi); the lines sorted by office, payroll type and code with a
     * sub-total for each payroll type of an office and for each office, then the grand total.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    @SuppressWarnings("java:S107") // the parameters of the report standard
    public void export(
            AccessContext context,
            String yymm,
            Level level,
            Filters filters,
            String language,
            String format,
            ExportSink sink)
            throws IOException {
        Summary s = compute(context, "EXPORT", yymm, level, filters);
        long version = versionInForce(s);
        mismatch(s);
        record(context, s, filters, format, version);
        Labels l =
                new Labels(
                        bundles.messages(
                                MessageBundles.LOCALES.contains(language) ? language : "en"));
        sink.title(header(context, s, filters, l));
        // SRS-18-23: every line says whether the month is final, so no page is read as final early.
        String status = s.provisional() ? "PROVISIONAL" : "FINAL";
        sink.header(
                l.get("o04.sum.col.status", "status"),
                l.get("o04.sum.col.levelCode", "level_code"),
                l.get("o04.sum.col.level", "level"),
                l.get("o04.sum.col.payrollType", "payroll_type"),
                l.get("o04.sum.col.payCode", "pay_code"),
                l.get("o04.sum.col.component", "component"),
                l.get("o04.sum.col.type", "type"),
                l.get("o04.sum.col.amount", "amount"),
                l.get("o04.sum.col.members", "members"));
        List<Line> lines = s.lines();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            sink.row(
                    status,
                    line.levelCode(),
                    line.levelName(),
                    line.payrollType(),
                    line.payCode(),
                    line.component(),
                    line.componentType(),
                    line.amount(),
                    line.members());
            Line next = i + 1 < lines.size() ? lines.get(i + 1) : null;
            boolean levelEnds = next == null || !next.levelCode().equals(line.levelCode());
            if (levelEnds || !java.util.Objects.equals(next.payrollType(), line.payrollType())) {
                subtotal(
                        sink,
                        status,
                        line,
                        line.payrollType(),
                        lines.stream()
                                .filter(
                                        x ->
                                                x.levelCode().equals(line.levelCode())
                                                        && java.util.Objects.equals(
                                                                x.payrollType(),
                                                                line.payrollType()))
                                .toList(),
                        l);
            }
            if (levelEnds) {
                subtotal(
                        sink,
                        status,
                        line,
                        "",
                        lines.stream().filter(x -> x.levelCode().equals(line.levelCode())).toList(),
                        l);
            }
        }
        String total = l.get("o04.sum.total", "TOTAL");
        sink.row(
                status,
                total,
                "",
                "",
                "",
                l.get("o04.sum.earnings", "Earnings"),
                "EARNING",
                s.earnings(),
                "");
        sink.row(
                status,
                total,
                "",
                "",
                "",
                l.get("o04.sum.deductions", "Deductions"),
                "DEDUCTION",
                s.deductions(),
                "");
    }

    /** SRS-18-12 (d): the earnings and deductions sub-totals of a group of lines. */
    private static void subtotal(
            ExportSink sink, String status, Line at, String payrollType, List<Line> group, Labels l)
            throws IOException {
        String label = l.get("o04.sum.subtotal", "Sub-total");
        sink.row(
                status,
                at.levelCode(),
                at.levelName(),
                payrollType,
                "",
                label + " " + l.get("o04.sum.earnings", "Earnings"),
                "EARNING",
                total(group, "EARNING"),
                "");
        sink.row(
                status,
                at.levelCode(),
                at.levelName(),
                payrollType,
                "",
                label + " " + l.get("o04.sum.deductions", "Deductions"),
                "DEDUCTION",
                total(group, "DEDUCTION"),
                "");
    }

    /** SRS-18-12 (c): report name, period, scope, run date-time and user, one line each. */
    private String[] header(AccessContext context, Summary s, Filters f, Labels l) {
        String scope =
                f.officeId() == null
                        ? l.get("o04.sum.allOffices", "All offices in your Access Group scope")
                        : org.location(f.officeId())
                                .map(o -> o.getCode() + " - " + o.getName())
                                .orElse(String.valueOf(f.officeId()));
        StringBuilder selection = new StringBuilder(scope);
        selection.append("; ").append(l.get("o04.level." + s.level(), s.level()));
        if (f.payrollType() != null) {
            selection
                    .append("; ")
                    .append(l.get("o04.sum.payrollType", "Payroll type"))
                    .append(" ")
                    .append(f.payrollType());
        }
        if (f.category() != null) {
            selection
                    .append("; ")
                    .append(l.get("o04.sum.category", "Category"))
                    .append(" ")
                    .append(f.category());
        }
        selection
                .append("; ")
                .append(
                        l.get(
                                "o04.sum.members." + f.memberStatus().name(),
                                f.memberStatus().name()));
        java.time.YearMonth month =
                java.time.YearMonth.parse(
                        s.yymm(), java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        return new String[] {
            l.get("o04.sum.title", "Component-wise Summary"),
            l.get("o04.sum.period", "Period")
                    + ": "
                    + String.format("%02d-%04d", month.getMonthValue(), month.getYear()),
            l.get("o04.sum.scope", "Scope") + ": " + selection,
            l.get("o04.sum.run", "Run")
                    + ": "
                    + java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
                            .format(clock.instant().atZone(IST))
                    + " IST",
            l.get("o04.sum.user", "User") + ": " + context.loginId()
        };
    }

    /** The labels of the language chosen, each with its English fallback (SRS-G-40). */
    private record Labels(Map<String, String> bundle) {

        String get(String key, String fallback) {
            String text = bundle.get(key);
            return text == null || text.isBlank() ? fallback : text;
        }
    }

    private static void mismatch(Summary s) {
        if (!s.controlTotalsMatch()) {
            throw new FciException(
                    ReportErrorCode.CONTROL_TOTALS,
                    new Object[] {
                        "Component-wise Summary " + s.yymm(),
                        s.earnings().toPlainString() + " / " + s.deductions().toPlainString(),
                        s.postedGross().toPlainString()
                                + " / "
                                + s.postedDeductions().toPlainString()
                    },
                    List.of(),
                    null);
        }
    }

    private Summary compute(
            AccessContext context, String action, String yymm, Level level, Filters filters) {
        policy.require(context, ReportRules.HERE, action);
        if (!ReportRules.isYymm(yymm) || level == null) {
            throw new FciException(
                    ReportErrorCode.PARAMETERS, new Object[] {"month, level"}, List.of(), null);
        }
        Filters f = filters == null ? Filters.NONE : filters;
        if (f.officeId() != null) {
            // SRS-18-12 (a): an office within the user's Access Group scope.
            policy.requireAt(context, ReportRules.HERE, action, f.officeId());
        }
        java.time.LocalDate monthEnd =
                java.time.YearMonth.parse(
                                yymm, java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
                        .atEndOfMonth();
        java.util.Set<Long> offices =
                f.officeId() == null ? java.util.Set.of() : org.subtree(f.officeId());
        MapSqlParameterSource p =
                OfficeScope.of(context, policy.isAtHeadquarters(context))
                        .addValue("yymm", yymm)
                        .addValue("office", f.officeId() == null ? "N" : "Y")
                        .addValue("offices", offices.isEmpty() ? List.of(-1L) : offices)
                        .addValue("type", f.payrollType(), java.sql.Types.VARCHAR)
                        .addValue("category", f.category(), java.sql.Types.VARCHAR)
                        .addValue("fromEis", f.fromEis(), java.sql.Types.VARCHAR)
                        .addValue("toEis", f.toEis(), java.sql.Types.VARCHAR)
                        .addValue("status", f.memberStatus().name())
                        .addValue("monthEnd", java.sql.Date.valueOf(monthEnd));
        String sql =
                switch (level) {
                    case UNIT -> BY_UNIT;
                    case REGION -> BY_REGION;
                    case ZONE -> BY_ZONE;
                    case ALL -> ALL;
                };
        List<Line> lines =
                named.query(
                        sql,
                        p,
                        (rs, i) ->
                                new Line(
                                        rs.getString("level_code"),
                                        rs.getString("level_name"),
                                        rs.getString("payroll_type"),
                                        rs.getInt("legacy_pay_code"),
                                        rs.getString("component_name"),
                                        rs.getString("component_type"),
                                        rs.getBigDecimal("amount"),
                                        rs.getLong("members")));
        if (f.sort() == Sort.AMOUNT) {
            // SRS-18-12: by amount within each office (largest first), then the report order.
            List<Line> sorted = new java.util.ArrayList<>(lines);
            sorted.sort(
                    java.util.Comparator.comparing(Line::levelCode)
                            .thenComparing(Line::amount, java.util.Comparator.reverseOrder()));
            lines = sorted;
        }
        Map<String, Object> posted = named.queryForMap(POSTED, p);
        BigDecimal earnings = total(lines, "EARNING");
        BigDecimal deductions = total(lines, "DEDUCTION");
        BigDecimal gross = (BigDecimal) posted.get("GROSS");
        BigDecimal postedDeductions = (BigDecimal) posted.get("DEDUCTIONS");
        boolean provisional =
                ReportRules.provisional(
                        ((Number) posted.get("CYCLES")).longValue(),
                        ((Number) posted.get("LOCKED")).longValue());
        boolean match =
                ReportRules.matches(earnings, gross, BigDecimal.ZERO)
                        && ReportRules.matches(deductions, postedDeductions, BigDecimal.ZERO);
        return new Summary(
                yymm,
                level.name(),
                provisional,
                earnings,
                deductions,
                gross,
                postedDeductions,
                match,
                lines,
                ReportRules.verified(
                        ((Number) posted.get("CYCLES")).longValue(),
                        posted.get("VERIFIED") == null
                                ? ((Number) posted.get("CYCLES")).longValue()
                                : ((Number) posted.get("VERIFIED")).longValue()));
    }

    private static BigDecimal total(List<Line> lines, String type) {
        return lines.stream()
                .filter(l -> type.equals(l.componentType()))
                .map(Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * BR-MIS-002, SRS-G-12, SRS-18-24 (US-GAP-35): the approved, non-blocked version in force on
     * the reported month's first day, never simply the newest; none refuses as Blocked. SRS-18-9 /
     * M-12 Period-Lock (US-GAP-36): a version that needs the period locked refuses before the lock.
     */
    private long versionInForce(Summary s) {
        Map<String, Object> v =
                versionFor(s)
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                ReportErrorCode.BLOCKED,
                                                new Object[] {
                                                    KEY,
                                                    "no approved version in force for " + s.yymm()
                                                },
                                                List.of(),
                                                null));
        if ("Y".equals(v.get("PERIOD_LOCK_REQUIRED")) && s.provisional()) {
            throw new FciException(
                    ReportErrorCode.BLOCKED,
                    new Object[] {KEY, "the month " + s.yymm() + " must be locked first"},
                    List.of(),
                    null);
        }
        return ((Number) v.get("REPORT_VERSION_ID")).longValue();
    }

    /** The approved, non-blocked version in force for the month (BR-MIS-002), if any. */
    private java.util.Optional<Map<String, Object>> versionFor(Summary s) {
        java.time.LocalDate first =
                java.time.YearMonth.parse(
                                s.yymm(), java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
                        .atDay(1);
        List<Map<String, Object>> versions =
                jdbc.queryForList(
                        "SELECT v.report_version_id, v.period_lock_required FROM"
                                + " report_definition_version v JOIN report_definition d ON"
                                + " d.report_def_id = v.report_def_id WHERE d.report_key = ? AND"
                                + " v.approval_state = 'APPROVED' AND v.is_blocked = 'N' AND"
                                + " v.effective_from <= ? AND (v.effective_to IS NULL OR"
                                + " v.effective_to >= ?) ORDER BY v.effective_from DESC",
                        KEY,
                        java.sql.Date.valueOf(first),
                        java.sql.Date.valueOf(first));
        return versions.stream().findFirst();
    }

    /** SRS-18-18: the run in report_output and its export in report_export_log. */
    private void record(AccessContext context, Summary s, Filters f, String format, long version) {
        Long output = output(context, s, f, version);
        jdbc.update(
                "INSERT INTO report_export_log (report_output_id, exported_by, export_format,"
                        + " exported_at) VALUES (?, ?, ?, ?)",
                output,
                context.loginId(),
                "XLSX".equals(format) ? "EXCEL" : "CSV",
                Timestamp.from(clock.instant()));
    }

    /**
     * FRS V0.5 §23.9: one generation in report_output (version, parameters, user, period; an
     * unlocked month is provisional and not locked); returns its id.
     */
    private Long output(AccessContext context, Summary s, Filters f, long version) {
        String parameters =
                "{\"yymm\":\""
                        + s.yymm()
                        + "\",\"level\":\""
                        + s.level()
                        + "\""
                        + (f.officeId() == null ? "" : ",\"office\":" + f.officeId())
                        + (f.payrollType() == null
                                ? ""
                                : ",\"payrollType\":\"" + f.payrollType() + "\"")
                        + (f.category() == null ? "" : ",\"category\":\"" + f.category() + "\"")
                        // The default (every member) is left out: the parameters of a run
                        // without a choice read as they always did.
                        + (f.memberStatus() == MemberStatus.BOTH
                                ? ""
                                : ",\"memberStatus\":\"" + f.memberStatus().name() + "\"")
                        + "}";
        Timestamp now = Timestamp.from(clock.instant());
        // Oracle review F2: the generated key of this INSERT, never MAX(id) read back.
        return GeneratedKeys.insert(
                jdbc,
                "INSERT INTO report_output (report_version_id, report_key, payroll_yymm,"
                        + " parameters_supplied, parameter_hash, requested_by, generated_at,"
                        + " run_type, period_was_locked, is_provisional, output_status,"
                        + " control_totals_matched) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTUAL', ?, ?,"
                        + " 'GENERATED', ?)",
                "report_output_id",
                version,
                KEY,
                s.yymm(),
                parameters,
                sha256(parameters),
                context.loginId(),
                now,
                s.provisional() ? "N" : "Y",
                s.provisional() ? "Y" : "N",
                s.controlTotalsMatch() ? "Y" : "N");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
