package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O-01 Payslip (SRS l.3378-3407, l.5490; SRS-17-1, SRS-17-7; BR-ESEVA-004, BR-ESEVA-005; DDL {@code
 * payslip}; API-15-4 in PENDING: the sources give no contract): the bulk view of the staff in the
 * session's office scope. Members see their own payslip in eSeva, never this view.
 *
 * <p>The register reads committed ACTUAL results only, never a simulation (SRS-18-5, DDL comment on
 * {@code payslip}). Bulk generation is the Payroll Maker's, for a period locked at the user's
 * offices; a period not locked is refused (SRS l.3389). It writes one {@code payslip} row per
 * locked member, published to eSeva at once because the period is locked (SRS l.3384), and adding
 * the same period again writes nothing: the payslip stays reproducible (SRS l.3392).
 *
 * <p>OPEN-QUESTION (PENDING 17-o01): the sources do not say who suppresses a payslip, when, or what
 * "approve: checker" means here: {@code suppressed_flag} is shown and never set; the PDF, the
 * payslip layout and the gang pack wait for LIB-1 and 15-layouts.
 */
@Service
@Loggable
public class PayslipRegisterService {

    static final String SCREEN = "O-01";
    private static final String STAFF = "STAFF";

    /** The drawn levels; labour by depot and by gang read the same results. */
    public enum Level {
        STAFF,
        LABOUR_DEPOT,
        LABOUR_GANG
    }

    /**
     * A month the session's offices have a cycle for, with its state; {@code results} says whether
     * committed results exist in the scope, so the screen can open on a month with something to
     * show.
     */
    public record Period(String yymm, boolean locked, boolean results) {}

    /** The tiles of a month. {@code lastPublished} null: nothing published yet. */
    public record Summary(
            String yymm,
            boolean locked,
            long toGenerate,
            long published,
            long suppressed,
            String lastPublished,
            Instant lastPublishedAt,
            long lastPublishedCount) {}

    /** A row of the register; PII free. */
    public record Row(
            String eis,
            String name,
            String payrollType,
            String payPattern,
            BigDecimal net,
            String status,
            boolean published) {}

    /** What one generation wrote. */
    public record Generated(String yymm, long written, long alreadyGenerated) {}

    private static final String RESULTS =
            " FROM payroll_result r JOIN employee e ON e.employee_id = r.employee_id WHERE"
                + " r.period_date = :day AND r.payroll_yymm = :yymm AND r.run_type = 'ACTUAL' AND"
                + " r.result_status = 'CURRENT' AND (:all = 'Y' OR r.org_unit_id IN (:locations))"
                + " AND ((:level = 'STAFF' AND r.payroll_type = 'STAFF') OR (:level <> 'STAFF' AND"
                + " r.payroll_type <> 'STAFF'))";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final AuditService audit;
    private final Clock clock;

    public PayslipRegisterService(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            AuditService audit,
            Clock clock) {
        this.named = named;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /** The Period dropdown (OD-123: from the cycles, never a fixed list), newest first. */
    @Transactional(readOnly = true)
    public List<Period> periods(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        return named.query(
                "SELECT g.yymm, g.all_locked, CASE WHEN EXISTS (SELECT 1 FROM payroll_result r"
                        + " WHERE r.payroll_yymm = g.yymm AND r.run_type = 'ACTUAL' AND"
                        + " r.result_status = 'CURRENT' AND (:all = 'Y' OR r.org_unit_id IN"
                        + " (:locations))) THEN 1 ELSE 0 END FROM (SELECT c.payroll_yymm AS yymm,"
                        + " MIN(CASE WHEN c.cycle_status = 'LOCKED' THEN 1 ELSE 0 END) AS"
                        + " all_locked FROM payroll_cycle_instance c WHERE (:all = 'Y' OR"
                        + " c.org_unit_id IN (:locations)) GROUP BY c.payroll_yymm ORDER BY"
                        + " c.payroll_yymm DESC FETCH FIRST 24 ROWS ONLY) g ORDER BY g.yymm DESC",
                scope(context),
                (rs, i) -> new Period(rs.getString(1), rs.getInt(2) == 1, rs.getInt(3) == 1));
    }

    /** The four tiles of {@code yymm} at {@code level}. */
    @Transactional(readOnly = true)
    public Summary summary(AccessContext context, String yymm, Level level) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = month(context, yymm, level);
        long results = count("SELECT COUNT(*)" + RESULTS, p);
        long published =
                count(
                        "SELECT COUNT(*) FROM payslip s WHERE s.payroll_yymm = :yymm AND"
                                + " s.published_to_ess = 'Y' AND s.suppressed_flag = 'N' AND (:all"
                                + " = 'Y' OR s.org_unit_id IN (:locations)) AND ((:level = 'STAFF'"
                                + " AND s.layout_variant = 'STAFF') OR (:level <> 'STAFF' AND"
                                + " s.layout_variant = 'LABOUR'))",
                        p);
        long suppressed =
                count(
                        "SELECT COUNT(*) FROM payslip s WHERE s.payroll_yymm = :yymm AND"
                                + " s.suppressed_flag = 'Y' AND (:all = 'Y' OR s.org_unit_id IN"
                                + " (:locations))",
                        p);
        List<Object[]> last =
                named.query(
                        "SELECT s.payroll_yymm, MAX(s.published_at), COUNT(*) FROM payslip s WHERE"
                                + " s.published_to_ess = 'Y' AND (:all = 'Y' OR s.org_unit_id IN"
                                + " (:locations)) GROUP BY s.payroll_yymm ORDER BY s.payroll_yymm"
                                + " DESC FETCH FIRST 1 ROWS ONLY",
                        p,
                        (rs, i) ->
                                new Object[] {rs.getString(1), rs.getTimestamp(2), rs.getLong(3)});
        Object[] newest = last.isEmpty() ? new Object[] {null, null, 0L} : last.getFirst();
        Timestamp at = (Timestamp) newest[1];
        return new Summary(
                yymm,
                locked(p),
                Math.max(0, results - published - suppressed),
                published,
                suppressed,
                (String) newest[0],
                at == null ? null : at.toInstant(),
                (Long) newest[2]);
    }

    /** The register of {@code yymm}, paged on the server; {@code text} searches EIS and name. */
    @Transactional(readOnly = true)
    public Paged<Row> register(
            AccessContext context, String yymm, Level level, String text, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        String needle = text == null ? "" : text.strip().toUpperCase(Locale.ROOT);
        MapSqlParameterSource p =
                month(context, yymm, level)
                        .addValue("text", needle.isEmpty() ? null : needle, Types.VARCHAR)
                        .addValue("like", "%" + needle + "%");
        String where =
                RESULTS
                        + " AND (:text IS NULL OR e.emp_num = :text OR UPPER(e.first_name || ' ' ||"
                        + " COALESCE(e.last_name, '')) LIKE :like)";
        long total = count("SELECT COUNT(*)" + where, p);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Row> rows =
                named.query(
                        "SELECT e.emp_num, e.first_name, e.last_name, r.payroll_type,"
                                + " e.pay_pattern, r.net_pay, r.is_locked, (SELECT"
                                + " MAX(s.published_to_ess) FROM payslip s WHERE"
                                + " s.cycle_instance_id = r.cycle_instance_id AND s.employee_id ="
                                + " r.employee_id) AS published, (SELECT MAX(s.suppressed_flag)"
                                + " FROM payslip s WHERE s.cycle_instance_id ="
                                + " r.cycle_instance_id AND s.employee_id = r.employee_id) AS"
                                + " suppressed, (SELECT COUNT(*) FROM payroll_exception x WHERE"
                                + " x.employee_id = r.employee_id AND x.cycle_instance_id ="
                                + " r.cycle_instance_id AND x.exception_code = 'PAYMENT_HELD' AND"
                                + " x.resolved_at IS NULL) AS held"
                                + where
                                + " ORDER BY e.emp_num OFFSET :offset ROWS FETCH NEXT :size ROWS"
                                + " ONLY",
                        p,
                        PayslipRegisterService::row);
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /**
     * The Payroll Maker's bulk generation (SRS l.3381, l.3389): {@code payslip} rows for the locked
     * results of {@code yymm} at the user's offices. Refused when no cycle of the month is locked;
     * a month partly locked generates what is locked.
     */
    @Transactional
    public Generated generate(AccessContext context, String yymm, Level level) {
        policy.require(context, SCREEN, "GENERATE");
        MapSqlParameterSource p = month(context, yymm, level);
        if (!anyLocked(p)) {
            throw new FciException(
                    ApiErrorCode.PAYSLIP_NOT_LOCKED, new Object[] {yymm}, List.of(), null);
        }
        p.addValue("at", Timestamp.from(clock.instant()));
        long already =
                count(
                        "SELECT COUNT(*) FROM payslip s WHERE s.payroll_yymm = :yymm AND (:all ="
                                + " 'Y' OR s.org_unit_id IN (:locations)) AND ((:level = 'STAFF'"
                                + " AND s.layout_variant = 'STAFF') OR (:level <> 'STAFF' AND"
                                + " s.layout_variant = 'LABOUR'))",
                        p);
        int written =
                named.update(
                        "INSERT INTO payslip (payroll_result_id, result_period_date, org_unit_id,"
                                + " cycle_instance_id, employee_id, payroll_yymm, layout_variant,"
                                + " net_pay, generated_at, published_to_ess, published_at,"
                                + " suppressed_flag) SELECT r.payroll_result_id, r.period_date,"
                                + " r.org_unit_id, r.cycle_instance_id, r.employee_id,"
                                + " r.payroll_yymm, CASE WHEN r.payroll_type = 'STAFF' THEN"
                                + " 'STAFF' ELSE 'LABOUR' END, r.net_pay, :at, 'Y', :at, 'N' FROM"
                                + " payroll_result r JOIN payroll_cycle_instance c ON"
                                + " c.cycle_instance_id = r.cycle_instance_id WHERE r.period_date ="
                                + " :day AND r.payroll_yymm = :yymm AND r.run_type = 'ACTUAL' AND"
                                + " r.result_status = 'CURRENT' AND r.is_locked = 'Y' AND"
                                + " c.cycle_status = 'LOCKED' AND (:all = 'Y' OR r.org_unit_id IN"
                                + " (:locations)) AND ((:level = 'STAFF' AND r.payroll_type ="
                                + " 'STAFF') OR (:level <> 'STAFF' AND r.payroll_type <> 'STAFF'))"
                                + " AND NOT EXISTS (SELECT 1 FROM payslip s WHERE"
                                + " s.cycle_instance_id = r.cycle_instance_id AND s.employee_id ="
                                + " r.employee_id)",
                        p);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "payslip",
                                yymm + "|" + level.name(),
                                "published_to_ess",
                                null,
                                "Y",
                                "Payslips generated in bulk: " + written)
                        .module(SCREEN));
        return new Generated(yymm, written, already);
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        String last = rs.getString("last_name");
        String first = rs.getString("first_name");
        String published = rs.getString("published");
        return new Row(
                rs.getString("emp_num"),
                last == null || last.isBlank() ? first : first + " " + last,
                rs.getString("payroll_type"),
                rs.getString("pay_pattern"),
                rs.getBigDecimal("net_pay"),
                status(
                        rs.getBigDecimal("net_pay"),
                        "Y".equals(rs.getString("is_locked")),
                        published != null,
                        "Y".equals(rs.getString("suppressed")),
                        rs.getLong("held") > 0),
                "Y".equals(published));
    }

    /**
     * The status of a register row, first that applies: Suppressed; Held (an open PAYMENT_HELD, or
     * a negative net, resolved on C-08: "No payslip until the negative net is resolved"); Ready (a
     * payslip was generated); Awaiting generation (locked); Provisional (not locked).
     */
    static String status(
            BigDecimal net, boolean locked, boolean generated, boolean suppressed, boolean held) {
        if (suppressed) {
            return "SUPPRESSED";
        }
        if (held || net.signum() < 0) {
            return "HELD";
        }
        if (generated) {
            return "READY";
        }
        return locked ? "AWAITING_GENERATION" : "PROVISIONAL";
    }

    private boolean locked(MapSqlParameterSource p) {
        Long open =
                named.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance c WHERE c.payroll_yymm = :yymm"
                                + " AND c.cycle_status <> 'LOCKED' AND (:all = 'Y' OR c.org_unit_id"
                                + " IN (:locations))",
                        p,
                        Long.class);
        return RowCounts.of(open) == 0 && anyLocked(p);
    }

    private boolean anyLocked(MapSqlParameterSource p) {
        Long locked =
                named.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance c WHERE c.payroll_yymm = :yymm"
                                + " AND c.cycle_status = 'LOCKED' AND (:all = 'Y' OR c.org_unit_id"
                                + " IN (:locations))",
                        p,
                        Long.class);
        return RowCounts.of(locked) > 0;
    }

    private MapSqlParameterSource month(AccessContext context, String yymm, Level level) {
        Date day = PayslipService.firstDay(yymm);
        return scope(context)
                .addValue("yymm", yymm)
                .addValue("day", day)
                .addValue("level", level == null ? STAFF : level.name());
    }

    private MapSqlParameterSource scope(AccessContext context) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context));
    }

    private long count(String sql, MapSqlParameterSource p) {
        return RowCounts.of(named.queryForObject(sql, p, Long.class));
    }
}
