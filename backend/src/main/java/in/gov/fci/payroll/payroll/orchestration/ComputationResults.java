package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.StatutoryPayCodes;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.payroll.business.PayRules;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.StaffNumberInForce;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of C-02 Employee / Staff Payroll Computation as Wireframes v6.1 draws it (OD-121;
 * SRS l.3686-3692, FRS §7.4): the Employee-wise Result with its Pattern and Status filters and list
 * search, the four tiles and the totals line, the calculation trace of one member and the PLI
 * payments with their invoice status (BR-AUDIT-009, BR-AUDIT-010). Nothing here computes or writes:
 * every figure is what the run stored, so a re-read gives the same answer (BR-CYC-011..017), and an
 * HRMS-sourced value is shown as the run took it, never fetched live.
 *
 * <p>Status of a row (the drawn pills), first that applies: an open PAYMENT_HELD exception of the
 * cycle (HELD); an unreviewed quarantine reason (G6); an open blocking exception (G2); a result the
 * run quarantined; otherwise COMPUTED. The labels are the M-16 list C02_RESULT_STATUS.
 *
 * <p>The FND pattern follows CDA (BR "FND (follows CDA)", PayConfiguration), so "CDA only" and the
 * CDA tile include it. Every read is limited to the session's cycle unit: another office's id
 * answers as unknown.
 */
@Service
@Loggable
public class ComputationResults {

    private static final String SCREEN = "C-02";

    /** Open worklist states: an accepted or resolved exception no longer colours the row. */
    private static final String OPEN = "exception_status IN ('OPEN', 'IN_PROGRESS')";

    /** The Status code of a row. */
    private static final String STATUS =
            "COALESCE(h.code, q.code, b.code, CASE r.result_status WHEN 'QUARANTINED' THEN"
                    + " 'QUARANTINED' END, 'COMPUTED')";

    /** The drawn Status filter's group of a row. */
    private static final String GROUP =
            "CASE WHEN h.code IS NOT NULL THEN 'HELD' WHEN q.code IS NOT NULL OR b.code IS NOT NULL"
                    + " OR r.result_status = 'QUARANTINED' THEN 'EXCEPTION' ELSE 'COMPUTED' END";

    /** One aggregate per kind, so a page does not probe the worklist once per member. */
    private static final String FROM =
            " FROM payroll_result r JOIN employee e ON e.employee_id = r.employee_id LEFT JOIN"
                    + " org_grade g ON g.grade_id = e.grade_id LEFT JOIN (SELECT employee_id,"
                    + " MIN(exception_code) code FROM payroll_exception WHERE cycle_instance_id = ?"
                    + " AND exception_code = 'PAYMENT_HELD' AND "
                    + OPEN
                    + " GROUP BY employee_id) h ON h.employee_id = r.employee_id LEFT JOIN (SELECT"
                    + " employee_id, MIN(quarantine_reason) code FROM payroll_quarantine WHERE"
                    + " cycle_instance_id = ? AND review_status = 'UNREVIEWED' GROUP BY"
                    + " employee_id) q ON q.employee_id = r.employee_id LEFT JOIN (SELECT"
                    + " employee_id, MIN(exception_code) code FROM payroll_exception WHERE"
                    + " cycle_instance_id = ? AND severity = 'BLOCKING' AND "
                    + OPEN
                    + " GROUP BY employee_id) b ON b.employee_id = r.employee_id";

    private static final String RUN =
            " WHERE r.run_id = ? AND r.period_date = ? AND r.org_unit_id = ?";

    /** Typed binds, one constant text: an absent filter is NULL. */
    private static final String FILTERED =
            RUN
                    + " AND (? IS NULL OR e.pay_pattern = ? OR (? = 'CDA' AND e.pay_pattern ="
                    + " 'FND')) AND (? IS NULL OR "
                    + GROUP
                    + " = ?) AND (? IS NULL OR e.emp_num LIKE ? OR UPPER(e.first_name || ' ' ||"
                    + " e.last_name) LIKE ?)";

    private static final int EXPORT_BATCH = 1000;

    /** The class in force on the period's first day (the class the HRA was computed on). */
    private static final String CITY_CLASS =
            "COALESCE((SELECT m.city_class FROM city_type_location_map m WHERE m.location_id ="
                    + " e.location_id AND m.effective_from = (SELECT MAX(m2.effective_from) FROM"
                    + " city_type_location_map m2 WHERE m2.location_id = e.location_id AND"
                    + " m2.effective_from <= ?)), (SELECT l.city_class FROM org_location l WHERE"
                    + " l.location_id = e.location_id))";

    /** The filters of the drawn toolbar; each may be null (absent). */
    public record Filter(String payPattern, String status, String search) {}

    /** The run C-02 shows: the period it belongs to, and whether it is the run of record. */
    public record ResultsPeriod(String period, String runType) {}

    /** A row of the Employee-wise Result (SRS l.3689-3692, FRS §7.4.1). */
    public record ResultRow(
            long resultId,
            String eis,
            String name,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            String status,
            boolean locked,
            String payPattern,
            BigDecimal basic,
            BigDecimal da,
            String cityClass,
            String scale,
            BigDecimal hra,
            BigDecimal other,
            String rowStatus,
            String statusGroup) {}

    /** A pattern tile: the members computed and how many of them carry an exception. */
    public record PatternTile(long members, long exceptions) {}

    /** The totals line under the table: the rows of the filter, not just the page. */
    public record Shown(long members, BigDecimal gross, BigDecimal deductions, BigDecimal net) {}

    /** The four tiles (whole run) and the totals line (the filter). */
    public record Summary(
            PatternTile ida,
            PatternTile cda,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            long held,
            Shown shown) {}

    /** One component line of the trace, with what produced it (BR-RULE-007). */
    public record TraceLine(
            int payCode,
            String name,
            String head,
            BigDecimal amount,
            BigDecimal rate,
            BigDecimal basis,
            String rule,
            Integer ruleVersion) {}

    /** The accommodation in force in the period (lease_accommodation_detail, I-03). */
    public record Accommodation(String type, BigDecimal amount, boolean hraSuppressed) {}

    /** The calculation trace of one member: FRS §7.4; every money column traces to its source. */
    public record Trace(
            long resultId,
            String eis,
            String name,
            String period,
            String payPattern,
            String cityClass,
            boolean stagnationCase,
            Accommodation accommodation,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            List<TraceLine> lines) {}

    /** A PLI transaction of the unit (pli_transaction, DDL v4.0 S19; BR-AUDIT-009 / 010). */
    public record PliPayment(
            long id,
            String entry,
            String eis,
            String member,
            String type,
            String finYear,
            BigDecimal amount,
            Long invoiceNo,
            String invoiceStatus,
            Instant sentToAppsAt,
            String appsCancelStatus) {}

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final Authorization policy;

    public ComputationResults(JdbcTemplate jdbc, OrgDirectory org, Authorization policy) {
        this.jdbc = jdbc;
        this.org = org;
        this.policy = policy;
    }

    /**
     * The period the result sections show: {@code period} when it has a run; otherwise the unit's
     * latest earlier period with a run of record, so the screen is not blank between two cycles
     * (the cycle actions keep working on {@code period}). Empty when the unit has no run at all.
     */
    @Transactional(readOnly = true)
    public Optional<ResultsPeriod> resultsPeriod(AccessContext context, YearMonth period) {
        OrgLocation unit = unit(context, "VIEW");
        Optional<Run> own = run(period, unit);
        if (own.isPresent()) {
            return own.map(r -> new ResultsPeriod(period.toString(), r.type()));
        }
        return jdbc
                .queryForList(
                        "SELECT c.payroll_yymm FROM payroll_cycle_instance c WHERE c.org_unit_id ="
                                + " ? AND c.current_run_id IS NOT NULL AND c.payroll_yymm < ?"
                                + " ORDER BY c.payroll_yymm DESC FETCH FIRST 1 ROWS ONLY",
                        String.class,
                        unit.getId(),
                        CycleKeys.yymm(period))
                .stream()
                .findFirst()
                .map(yymm -> new ResultsPeriod(periodOf(yymm), "ACTUAL"));
    }

    /** One page of the Employee-wise Result, in EIS order. */
    @Transactional(readOnly = true)
    public Paged<ResultRow> results(
            AccessContext context, YearMonth period, Filter filter, PageSpec spec) {
        OrgLocation unit = unit(context, "VIEW");
        Optional<Run> run = run(period, unit);
        if (run.isEmpty()) {
            return new Paged<>(List.of(), spec.page(), spec.size(), 0);
        }
        List<Object> where = binds(run.get(), period, unit, filter);
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*)" + FROM + FILTERED, Long.class, where.toArray());
        return new Paged<>(
                rows(period, where, spec.page() * (long) spec.size(), spec.size()),
                spec.page(),
                spec.size(),
                CycleSupport.orZero(total));
    }

    /** The tiles of the whole run and the totals of the filter. */
    @Transactional(readOnly = true)
    public Summary summary(AccessContext context, YearMonth period, Filter filter) {
        OrgLocation unit = unit(context, "VIEW");
        Optional<Run> run = run(period, unit);
        if (run.isEmpty()) {
            Shown none = new Shown(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            return new Summary(
                    new PatternTile(0, 0),
                    new PatternTile(0, 0),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    none);
        }
        List<Object> all = new ArrayList<>(scope(run.get(), period, unit));
        long[] ida = {0, 0};
        long[] cda = {0, 0};
        long[] held = {0};
        BigDecimal[] money = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.query(
                "SELECT e.pay_pattern, "
                        + GROUP
                        + " grp, COUNT(*), COALESCE(SUM(r.gross_earnings), 0),"
                        + " COALESCE(SUM(r.total_deductions), 0), COALESCE(SUM(r.net_pay), 0)"
                        + FROM
                        + RUN
                        + " GROUP BY e.pay_pattern, "
                        + GROUP,
                rs -> {
                    long count = rs.getLong(3);
                    long[] tile = "IDA".equals(rs.getString(1)) ? ida : cda;
                    tile[0] += count;
                    String group = rs.getString(2);
                    if ("EXCEPTION".equals(group)) {
                        tile[1] += count;
                    } else if ("HELD".equals(group)) {
                        held[0] += count;
                    }
                    money[0] = money[0].add(rs.getBigDecimal(4));
                    money[1] = money[1].add(rs.getBigDecimal(5));
                    money[2] = money[2].add(rs.getBigDecimal(6));
                },
                all.toArray());
        Shown shown =
                jdbc.queryForObject(
                        "SELECT COUNT(*), COALESCE(SUM(r.gross_earnings), 0),"
                                + " COALESCE(SUM(r.total_deductions), 0), COALESCE(SUM(r.net_pay),"
                                + " 0)"
                                + FROM
                                + FILTERED,
                        (rs, i) ->
                                new Shown(
                                        rs.getLong(1),
                                        rs.getBigDecimal(2),
                                        rs.getBigDecimal(3),
                                        rs.getBigDecimal(4)),
                        binds(run.get(), period, unit, filter).toArray());
        return new Summary(
                new PatternTile(ida[0], ida[1]),
                new PatternTile(cda[0], cda[1]),
                money[0],
                money[1],
                money[2],
                held[0],
                shown);
    }

    /** The calculation trace of a result of this unit's cycle of the period. */
    @Transactional(readOnly = true)
    public Trace trace(AccessContext context, YearMonth period, long resultId) {
        OrgLocation unit = unit(context, "VIEW");
        List<Trace> found =
                jdbc.query(
                        "SELECT r.gross_earnings, r.total_deductions, r.net_pay, e.emp_num,"
                                + " e.first_name, e.last_name, e.pay_pattern,"
                                + " e.is_stagnation_case, e.employee_id, "
                                + CITY_CLASS
                                + " FROM payroll_result r JOIN payroll_cycle_instance c ON"
                                + " c.cycle_instance_id = r.cycle_instance_id JOIN employee e ON"
                                + " e.employee_id = r.employee_id WHERE r.payroll_result_id = ?"
                                + " AND c.org_unit_id = ? AND c.payroll_yymm = ?",
                        (rs, i) -> trace(rs, period, resultId),
                        java.sql.Date.valueOf(period.atDay(1)),
                        resultId,
                        unit.getId(),
                        CycleKeys.yymm(period));
        if (found.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return found.getFirst();
    }

    /** The unit's PLI transactions of the period's financial year, newest entry first. */
    @Transactional(readOnly = true)
    public Paged<PliPayment> pliPayments(AccessContext context, YearMonth period, PageSpec spec) {
        OrgLocation unit = unit(context, "VIEW");
        String finYear = CycleKeys.finYear(period);
        String from =
                " FROM pli_transaction t JOIN pli_policy p ON p.pli_policy_id = t.pli_policy_id"
                        + " JOIN employee e ON e.employee_id = t.employee_id LEFT JOIN org_location"
                        + " l ON l.location_id = e.location_id WHERE p.fin_year = ? AND"
                        + " (e.location_id = ? OR l.cycle_host_location_id = ?)";
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*)" + from, Long.class, finYear, unit.getId(), unit.getId());
        List<PliPayment> rows =
                jdbc.query(
                        "SELECT t.pli_txn_id, e.emp_num, e.first_name, e.last_name, t.txn_type,"
                                + " t.computed_amount, t.ap_invoice_id, t.invoice_status,"
                                + " t.invoice_sent_at, t.apps_cancel_status"
                                + from
                                + " ORDER BY t.pli_txn_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                        (rs, i) -> pli(rs, finYear),
                        finYear,
                        unit.getId(),
                        unit.getId(),
                        spec.page() * (long) spec.size(),
                        spec.size());
        return new Paged<>(rows, spec.page(), spec.size(), CycleSupport.orZero(total));
    }

    /** How many rows the export of this filter carries (SRS-G-13). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, YearMonth period, Filter filter) {
        OrgLocation unit = unit(context, "EXPORT");
        Optional<Run> run = run(period, unit);
        if (run.isEmpty()) {
            return 0;
        }
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*)" + FROM + FILTERED,
                        Long.class,
                        binds(run.get(), period, unit, filter).toArray());
        return CycleSupport.orZero(total);
    }

    /**
     * The filtered Employee-wise Result as an export (SRS-G-13): every row, one read-only snapshot,
     * the rows written checked against the rows counted. No PAN or bank data is on the row.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, YearMonth period, Filter filter, ExportSink sink)
            throws IOException {
        OrgLocation unit = unit(context, "EXPORT");
        sink.header(
                "EIS",
                "Name",
                "Pattern",
                "Scale",
                "Basic",
                "DA",
                "HRA",
                "Other",
                "Gross",
                "Deductions",
                "Net Pay",
                "Status");
        Optional<Run> run = run(period, unit);
        if (run.isEmpty()) {
            return;
        }
        List<Object> where = binds(run.get(), period, unit, filter);
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*)" + FROM + FILTERED, Long.class, where.toArray());
        long expected = CycleSupport.orZero(total);
        long offset = 0;
        while (offset < expected) {
            List<ResultRow> rows = rows(period, where, offset, EXPORT_BATCH);
            if (rows.isEmpty()) {
                break;
            }
            for (ResultRow r : rows) {
                sink.row(
                        r.eis(),
                        r.name(),
                        r.payPattern(),
                        r.scale(),
                        r.basic(),
                        r.da(),
                        r.hra(),
                        r.other(),
                        r.gross(),
                        r.deductions(),
                        r.net(),
                        r.rowStatus());
            }
            offset += rows.size();
        }
        ExportSink.requireComplete(expected, sink);
    }

    private OrgLocation unit(AccessContext context, String action) {
        OrgLocation unit = CycleSupport.unit(org, context);
        policy.requireAt(context, SCREEN, action, unit.getId());
        return unit;
    }

    private record Run(long id, long cycleId, String type) {}

    /** The run of record, or the latest finished dry run before there is one. */
    private Optional<Run> run(YearMonth period, OrgLocation unit) {
        List<Map<String, Object>> cycles =
                jdbc.queryForList(
                        "SELECT cycle_instance_id, current_run_id FROM payroll_cycle_instance WHERE"
                                + " payroll_yymm = ? AND org_unit_id = ?",
                        CycleKeys.yymm(period),
                        unit.getId());
        if (cycles.isEmpty()) {
            return Optional.empty();
        }
        long cycleId = ((Number) cycles.getFirst().get("CYCLE_INSTANCE_ID")).longValue();
        Object current = cycles.getFirst().get("CURRENT_RUN_ID");
        if (current != null) {
            return Optional.of(new Run(((Number) current).longValue(), cycleId, "ACTUAL"));
        }
        return jdbc
                .queryForList(
                        "SELECT run_id FROM computation_run WHERE cycle_instance_id = ? AND"
                                + " run_type = 'SIMULATION' AND run_status = 'COMPLETE' ORDER BY"
                                + " run_id DESC",
                        Long.class,
                        cycleId)
                .stream()
                .findFirst()
                .map(id -> new Run(id, cycleId, "SIMULATION"));
    }

    /** The binds of FROM + RUN. */
    private static List<Object> scope(Run run, YearMonth period, OrgLocation unit) {
        return List.of(
                run.cycleId(),
                run.cycleId(),
                run.cycleId(),
                run.id(),
                java.sql.Date.valueOf(period.atDay(1)),
                unit.getId());
    }

    /** The binds of FROM + FILTERED. */
    private static List<Object> binds(Run run, YearMonth period, OrgLocation unit, Filter filter) {
        List<Object> all = new ArrayList<>(scope(run, period, unit));
        SqlParameterValue pattern = text(filter.payPattern());
        SqlParameterValue status = text(filter.status());
        String needle = filter.search() == null ? "" : filter.search().strip();
        SqlParameterValue search =
                text(needle.isEmpty() ? null : "%" + needle.toUpperCase(Locale.ROOT) + "%");
        all.addAll(List.of(pattern, pattern, pattern, status, status, search, search, search));
        return all;
    }

    private static SqlParameterValue text(String value) {
        return new SqlParameterValue(java.sql.Types.VARCHAR, value);
    }

    private List<ResultRow> rows(YearMonth period, List<Object> where, long offset, int size) {
        List<Object> binds = new ArrayList<>();
        binds.add(StaffNumberInForce.dayOf(period));
        binds.add(java.sql.Date.valueOf(period.atDay(1)));
        binds.addAll(where);
        binds.add(offset);
        binds.add(size);
        return jdbc.query(
                // SRS-16-7 (OD-67): the staff number in force at the period's end.
                "SELECT r.payroll_result_id, "
                        + StaffNumberInForce.OF_EMPLOYEE_E
                        + ", e.first_name, e.last_name, r.gross_earnings, r.total_deductions,"
                        + " r.net_pay, r.result_status, r.is_locked, e.pay_pattern, "
                        + amount(PayRules.BASIC)
                        + ", "
                        + amount(PayRules.DA)
                        + ", "
                        + CITY_CLASS
                        + ", g.grade_code, "
                        + amount(PayRules.HRA)
                        + ", "
                        + STATUS
                        + ", "
                        + GROUP
                        + FROM
                        + FILTERED
                        + " ORDER BY e.emp_num, r.payroll_result_id OFFSET ? ROWS FETCH NEXT ?"
                        + " ROWS ONLY",
                (rs, i) -> row(rs),
                binds.toArray());
    }

    private static String amount(int payCode) {
        return "(SELECT SUM(c.amount) FROM payroll_result_component c WHERE c.payroll_result_id ="
                + " r.payroll_result_id AND c.period_date = r.period_date AND c.org_unit_id ="
                + " r.org_unit_id AND c.legacy_pay_code = "
                + payCode
                + ")";
    }

    private static ResultRow row(ResultSet rs) throws SQLException {
        BigDecimal gross = rs.getBigDecimal(5);
        BigDecimal basic = rs.getBigDecimal(11);
        BigDecimal da = rs.getBigDecimal(12);
        BigDecimal hra = rs.getBigDecimal(15);
        return new ResultRow(
                rs.getLong(1),
                rs.getString(2),
                (rs.getString(3) + " " + rs.getString(4)).strip(),
                gross,
                rs.getBigDecimal(6),
                rs.getBigDecimal(7),
                rs.getString(8),
                "Y".equals(rs.getString(9)),
                rs.getString(10),
                basic,
                da,
                rs.getString(13),
                rs.getString(14),
                hra,
                gross.subtract(orZero(basic)).subtract(orZero(da)).subtract(orZero(hra)),
                rs.getString(16),
                rs.getString(17));
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Trace trace(ResultSet rs, YearMonth period, long resultId) throws SQLException {
        long employeeId = rs.getLong(9);
        return new Trace(
                resultId,
                rs.getString(4),
                (rs.getString(5) + " " + rs.getString(6)).strip(),
                period.toString(),
                rs.getString(7),
                rs.getString(10),
                "Y".equals(rs.getString(8)),
                accommodation(employeeId, period),
                rs.getBigDecimal(1),
                rs.getBigDecimal(2),
                rs.getBigDecimal(3),
                lines(resultId, period));
    }

    /**
     * The record in force on the period's first day, if any: the day the engine reads it. Whether
     * it suppresses HRA is the engine's own test; a record received after a result was locked may
     * disagree with that result, so the screen says what the result's lines did (SRS-1-3).
     */
    private Accommodation accommodation(long employeeId, YearMonth period) {
        java.sql.Date day = java.sql.Date.valueOf(period.atDay(1));
        return jdbc
                .query(
                        "SELECT accommodation_type, lease_amount, hra_suppressed_flag FROM"
                                + " lease_accommodation_detail WHERE employee_id = ? AND"
                                + " effective_from <= ? AND (effective_to IS NULL OR effective_to"
                                + " >= ?) ORDER BY effective_from DESC FETCH FIRST 1 ROWS ONLY",
                        (rs, i) ->
                                new Accommodation(
                                        rs.getString(1),
                                        rs.getBigDecimal(2),
                                        PayRules.hraSuppressed(
                                                rs.getString(1), "Y".equals(rs.getString(3)))),
                        employeeId,
                        day,
                        day)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private List<TraceLine> lines(long resultId, YearMonth period) {
        LocalDate day = period.atDay(1);
        Map<Integer, String> statutory = new java.util.HashMap<>();
        for (String head : List.of("CPF", "VPF", "TDS", "PT")) {
            StatutoryPayCodes.of(jdbc, head, day).ifPresent(code -> statutory.put(code, head));
        }
        return jdbc.query(
                "SELECT c.legacy_pay_code, p.component_name, p.component_type, c.amount,"
                        + " c.rate_applied, c.basis_amount, d.rule_key, v.version_no FROM"
                        + " payroll_result_component c JOIN pay_component p ON p.component_id ="
                        + " c.component_id LEFT JOIN rule_version v ON v.rule_version_id ="
                        + " c.rule_version_id LEFT JOIN rule_definition d ON d.rule_id = v.rule_id"
                        + " WHERE c.payroll_result_id = ? ORDER BY c.legacy_pay_code",
                (rs, i) -> {
                    int code = rs.getInt(1);
                    return new TraceLine(
                            code,
                            rs.getString(2),
                            head(code, rs.getString(3), statutory),
                            rs.getBigDecimal(4),
                            rs.getBigDecimal(5),
                            rs.getBigDecimal(6),
                            rs.getString(7),
                            rs.getObject(8, Integer.class));
                },
                resultId);
    }

    /** Which drawn trace row a component line belongs to. */
    private static String head(int code, String type, Map<Integer, String> statutory) {
        return switch (code) {
            case PayRules.BASIC -> "BASIC";
            case PayRules.DA -> "DA";
            case PayRules.HRA -> "HRA";
            default -> statutory.getOrDefault(code, type);
        };
    }

    private static PliPayment pli(ResultSet rs, String finYear) throws SQLException {
        long id = rs.getLong(1);
        Timestamp sent = rs.getTimestamp(9);
        return new PliPayment(
                id,
                "PLI-" + finYear.substring(0, 4) + "-" + String.format(Locale.ROOT, "%04d", id),
                rs.getString(2),
                (rs.getString(3) + " " + rs.getString(4)).strip(),
                rs.getString(5),
                finYear,
                rs.getBigDecimal(6),
                rs.getObject(7, Long.class),
                rs.getString(8),
                sent == null ? null : sent.toInstant(),
                rs.getString(10));
    }

    private static String periodOf(String yymm) {
        return yymm.substring(0, 4) + "-" + yymm.substring(4);
    }
}
