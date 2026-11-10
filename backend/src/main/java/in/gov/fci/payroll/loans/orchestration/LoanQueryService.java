package in.gov.fci.payroll.loans.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-04 Loans &amp; Advances view (plan 09 phases 09b / 09f; SRS-12-1, SRS-12-7, SRS-12-20,
 * SRS-12-23): the loans of the user's cycle unit (every unit at Headquarters) with principal and
 * interest shown separately, one loan with its schedule versions (superseded ones kept), the loan
 * types, and the sanction requests waiting for the Checker or returned to the Maker with the
 * reason, listed apart from the loans.
 */
@Service
@Loggable
public class LoanQueryService {

    /**
     * The business day is India's (the JVM and the database run on UTC): bound, never CURRENT_DATE.
     */
    private static final java.time.ZoneId IST_ZONE = java.time.ZoneId.of("Asia/Kolkata");

    /** One loan as I-04 lists it. */
    public record LoanRow(
            long id,
            String loanNumber,
            String eis,
            String employee,
            String loanType,
            BigDecimal sanctioned,
            String recoveryStart,
            int principalInstalments,
            BigDecimal instalment,
            int paidInstalments,
            BigDecimal principalOutstanding,
            BigDecimal interestAmount,
            BigDecimal interestOutstanding,
            String status,
            String source,
            boolean separated,
            boolean scheduleReconciled) {}

    /**
     * The loan tiles of I-04 (SRS-12-1, SRS-12-3): the open loans of the unit, the instalments of
     * the month from the schedules in force, and the HRMS loan records rejected at intake.
     *
     * @param yymm the month of the recovery figures: the month asked for, or the latest month
     *     before it that holds instalments (null when none does)
     * @param failingInterface the legacy object with the most open rejections (null when none)
     */
    public record Summary(
            String yymm,
            long activeLoans,
            BigDecimal outstanding,
            BigDecimal recoveryPrincipal,
            BigDecimal recoveryInterest,
            long importFailures,
            String failingInterface) {}

    /**
     * One advance paid out and its invoice (BR-AUDIT-009, BR-AUDIT-010; DDL advance_settlement):
     * the invoice status is read from the linked {@code ap_invoice_staging} row.
     *
     * @param invoiceStatus NOT_RAISED, RAISED, SENT_TO_APPS, CANCEL_REQUESTED or CANCELLED_IN_APPS
     */
    public record Disbursement(
            long id,
            String entry,
            String eis,
            String employee,
            String paymentType,
            String source,
            BigDecimal amount,
            String invoiceNumber,
            String invoiceStatus,
            LocalDate sentToApps,
            boolean appsCancelled) {}

    /** One schedule version and its lines. */
    public record Version(
            int versionNo,
            String reason,
            LocalDate effectiveFrom,
            Instant supersededAt,
            List<Line> lines) {}

    /** One instalment line. */
    public record Line(int number, String payrollYymm, BigDecimal principal, BigDecimal interest) {}

    /**
     * A loan with its schedule versions, newest first, and the next instalment of the recovery
     * tracker (SRS-12-23): the first line of the version in force whose month is not yet recovered
     * (no ledger row); null when the loan is not being recovered (closed, foreclosed, stopped,
     * settled) or every line is recovered.
     */
    public record Detail(LoanRow loan, List<Version> versions, Line nextInstalment) {}

    /** A sanction request in its approval item (SRS-12-7). */
    public record Request(
            long approvalId,
            String eis,
            String employee,
            String loanType,
            BigDecimal amount,
            String status,
            String submittedBy,
            Instant submittedAt,
            String returnReason) {}

    /** A loan type the Maker may set up (SRS-12-17). */
    public record LoanType(
            String code, String name, boolean interestBearing, Integer maxInstalments) {}

    private static final String LOAN_FROM =
            " FROM loan_header h JOIN employee e ON e.employee_id = h.employee_id JOIN"
                    + " loan_type_master t ON t.loan_type_id = h.loan_type_id";

    private static final String LOAN_WHERE =
            " WHERE (:all = 'Y' OR e.location_id IN (:locations)) AND (:eis IS NULL OR e.emp_num ="
                    + " :eis) AND (:status IS NULL OR h.loan_status = :status)";

    private static final String LOAN_SELECT =
            "SELECT h.loan_id, h.loan_number, e.emp_num, e.first_name, e.last_name,"
                    + " t.loan_type_code, h.sanctioned_amount, h.recovery_start_yymm,"
                    + " h.num_principal_instalments, h.instalment_amount, h.paid_instalments,"
                    + " h.principal_outstanding, h.interest_amount, h.interest_outstanding,"
                    + " h.loan_status, h.loan_source, e.separation_date, (SELECT"
                    + " SUM(l.principal_amount) FROM loan_schedule_version v JOIN"
                    + " loan_recovery_schedule l ON l.schedule_version_id ="
                    + " v.schedule_version_id WHERE v.loan_id = h.loan_id AND v.superseded_at IS"
                    + " NULL) AS scheduled_principal";

    /** The interfaces that bring loan events from HRMS (SRS section 11, X-03 registry). */
    private static final String LOAN_FAILURES =
            " FROM interface_exception x JOIN interface_registry r ON r.interface_id ="
                    + " x.interface_id LEFT JOIN intf_staging_txn s ON s.staging_id = x.staging_id"
                    + " LEFT JOIN employee e ON e.employee_id = s.employee_id WHERE"
                    + " r.interface_code IN ('HRMS_LOAN_ADVANCE_HDR', 'HRMS_LOAN_FORECLOSURE',"
                    + " 'HRMS_LOAN_RESCHEDULE') AND x.remediation_status IN ('OPEN', 'IN_PROGRESS')"
                    + " AND (:all = 'Y' OR e.location_id IN (:locations))";

    private static final String DISBURSEMENT_FROM =
            " FROM advance_settlement a JOIN employee e ON e.employee_id = a.employee_id LEFT JOIN"
                    + " loan_header h ON h.hrms_request_id = a.hrms_request_id OR h.approval_id ="
                    + " a.approval_id LEFT JOIN loan_type_master t ON t.loan_type_id ="
                    + " h.loan_type_id LEFT JOIN ap_invoice_staging i ON i.invoice_staging_id ="
                    + " a.ap_invoice_id WHERE (:all = 'Y' OR e.location_id IN (:locations))";

    private final NamedParameterJdbcTemplate named;
    private final OrgDirectory org;
    private final Authorization policy;

    public LoanQueryService(
            NamedParameterJdbcTemplate named, OrgDirectory org, Authorization policy) {
        this.named = named;
        this.org = org;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public Paged<LoanRow> loans(AccessContext context, String eis, String status, PageSpec spec) {
        MapSqlParameterSource params =
                scoped(scope(context))
                        .addValue("eis", blank(eis), Types.VARCHAR)
                        .addValue("status", blank(status), Types.VARCHAR);
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*)" + LOAN_FROM + LOAN_WHERE, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<LoanRow> rows =
                named.query(
                        LOAN_SELECT
                                + LOAN_FROM
                                + LOAN_WHERE
                                + " ORDER BY h.loan_id DESC OFFSET :offset ROWS FETCH NEXT :size"
                                + " ROWS ONLY",
                        params,
                        (rs, i) -> loan(rs));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    @Transactional(readOnly = true)
    public Detail detail(AccessContext context, long loanId) {
        MapSqlParameterSource params =
                scoped(scope(context))
                        .addValue("eis", null, Types.VARCHAR)
                        .addValue("status", null, Types.VARCHAR)
                        .addValue("id", loanId);
        LoanRow loan =
                named
                        .query(
                                LOAN_SELECT + LOAN_FROM + LOAN_WHERE + " AND h.loan_id = :id",
                                params,
                                (rs, i) -> loan(rs))
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        List<Version> versions =
                named.query(
                        "SELECT schedule_version_id, version_no, reason, effective_from,"
                                + " superseded_at FROM loan_schedule_version WHERE loan_id = :id"
                                + " ORDER BY version_no DESC",
                        new MapSqlParameterSource("id", loanId),
                        (rs, i) -> {
                            Timestamp superseded = rs.getTimestamp("superseded_at");
                            return new Version(
                                    rs.getInt("version_no"),
                                    rs.getString("reason"),
                                    rs.getDate("effective_from").toLocalDate(),
                                    superseded == null ? null : superseded.toInstant(),
                                    lines(rs.getLong("schedule_version_id")));
                        });
        return new Detail(loan, versions, next(loan));
    }

    /** SRS-12-23: the instalment the next locked month recovers, for a loan under recovery. */
    private Line next(LoanRow loan) {
        if (!"ACTIVE".equals(loan.status()) && !"RESCHEDULED".equals(loan.status())) {
            return null;
        }
        return named
                .query(
                        "SELECT l.instalment_number, l.payroll_yymm, l.principal_amount,"
                                + " l.interest_amount FROM loan_schedule_version v JOIN"
                                + " loan_recovery_schedule l ON l.schedule_version_id ="
                                + " v.schedule_version_id WHERE v.loan_id = :id AND"
                                + " v.superseded_at IS NULL AND NOT EXISTS (SELECT 1 FROM"
                                + " loan_monthly_balance b WHERE b.loan_id = v.loan_id AND"
                                + " b.payroll_yymm = l.payroll_yymm) ORDER BY l.payroll_yymm,"
                                + " l.instalment_number FETCH FIRST 1 ROWS ONLY",
                        new MapSqlParameterSource("id", loan.id()),
                        (rs, i) ->
                                new Line(
                                        rs.getInt(1),
                                        rs.getString(2),
                                        rs.getBigDecimal(3),
                                        rs.getBigDecimal(4)))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * The loan tiles (SRS-12-1, SRS-12-3; API-09-1): a month without instalments shows the latest
     * month before it that has them.
     */
    @Transactional(readOnly = true)
    public Summary summary(AccessContext context, String payrollYymm) {
        MapSqlParameterSource params =
                scoped(scope(context))
                        .addValue("yymm", payrollYymm)
                        .addValue("eis", null, Types.VARCHAR)
                        .addValue("status", null, Types.VARCHAR);
        String open = " AND h.loan_status IN " + LoanSanctionService.OPEN_STATUSES;
        String recovering = " AND h.loan_status IN ('ACTIVE', 'RESCHEDULED')";
        Map<String, Object> loans =
                named.queryForMap(
                        "SELECT COUNT(*) AS n, COALESCE(SUM(h.principal_outstanding +"
                                + " h.interest_outstanding), 0) AS total"
                                + LOAN_FROM
                                + LOAN_WHERE
                                + open,
                        params);
        String lines =
                LOAN_FROM
                        + " JOIN loan_schedule_version v ON v.loan_id = h.loan_id AND"
                        + " v.superseded_at IS NULL JOIN loan_recovery_schedule l ON"
                        + " l.schedule_version_id = v.schedule_version_id"
                        + LOAN_WHERE
                        + recovering;
        String shown =
                named.queryForObject(
                        "SELECT MAX(l.payroll_yymm)" + lines + " AND l.payroll_yymm <= :yymm",
                        params,
                        String.class);
        Map<String, Object> recovery =
                named.queryForMap(
                        "SELECT COALESCE(SUM(l.principal_amount), 0) AS principal,"
                                + " COALESCE(SUM(l.interest_amount), 0) AS interest"
                                + lines
                                + " AND l.payroll_yymm = :shown",
                        params.addValue("shown", shown, Types.VARCHAR));
        List<Map<String, Object>> failures =
                named.queryForList(
                        "SELECT r.staging_object, COUNT(*) AS n"
                                + LOAN_FAILURES
                                + " GROUP BY r.staging_object ORDER BY 2 DESC, 1",
                        params);
        return new Summary(
                shown,
                ((Number) loans.get("N")).longValue(),
                (BigDecimal) loans.get("TOTAL"),
                (BigDecimal) recovery.get("PRINCIPAL"),
                (BigDecimal) recovery.get("INTEREST"),
                failures.stream().mapToLong(f -> ((Number) f.get("N")).longValue()).sum(),
                failures.isEmpty() ? null : (String) failures.get(0).get("STAGING_OBJECT"));
    }

    /** BR-AUDIT-010: the advances paid out with their invoice, newest first; paged. */
    @Transactional(readOnly = true)
    public Paged<Disbursement> disbursements(AccessContext context, PageSpec spec) {
        MapSqlParameterSource params = scoped(scope(context));
        Long total =
                named.queryForObject("SELECT COUNT(*)" + DISBURSEMENT_FROM, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Disbursement> rows =
                named.query(
                        "SELECT a.settlement_id, h.loan_number, a.advance_type, h.loan_source,"
                                + " e.emp_num, e.first_name, e.last_name, t.loan_type_name,"
                                + " a.amount, i.invoice_number, i.sent_to_apps_at,"
                                + " i.apps_cancel_status"
                                + DISBURSEMENT_FROM
                                + " ORDER BY a.settlement_id DESC OFFSET :offset ROWS FETCH NEXT"
                                + " :size ROWS ONLY",
                        params,
                        (rs, i) -> disbursement(rs));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    private static Disbursement disbursement(ResultSet rs) throws SQLException {
        String number = rs.getString("invoice_number");
        Timestamp sent = rs.getTimestamp("sent_to_apps_at");
        String cancel = rs.getString("apps_cancel_status");
        String typeName = rs.getString("loan_type_name");
        long id = rs.getLong("settlement_id");
        String loanNumber = rs.getString("loan_number");
        return new Disbursement(
                id,
                loanNumber == null ? "ADV-" + id : loanNumber,
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                typeName == null ? rs.getString("advance_type") : typeName,
                rs.getString("loan_source"),
                rs.getBigDecimal("amount"),
                number,
                invoiceStatus(number, sent, cancel),
                sent == null ? null : sent.toInstant().atZone(IST_ZONE).toLocalDate(),
                "CONFIRMED".equals(cancel));
    }

    /** DDL invoice_status, read from the linked invoice row (BR-AUDIT-010). */
    static String invoiceStatus(String number, Timestamp sent, String appsCancel) {
        if (number == null) {
            return "NOT_RAISED";
        }
        if ("CONFIRMED".equals(appsCancel)) {
            return "CANCELLED_IN_APPS";
        }
        if ("REQUESTED".equals(appsCancel)) {
            return "CANCEL_REQUESTED";
        }
        return sent == null ? "RAISED" : "SENT_TO_APPS";
    }

    /** SRS-12-7: the requests waiting for the Checker or returned to the Maker, newest first. */
    @Transactional(readOnly = true)
    public Paged<Request> requests(AccessContext context, PageSpec spec) {
        MapSqlParameterSource params =
                scoped(scope(context)).addValue("entity", LoanSanctionService.ENTITY);
        String from =
                // Oracle review F10: an equi-join on the member id the key starts with
                // ("<employee_id>|..."), probed by the employee primary key, instead of a LIKE
                // that compared every request with every employee. The CASE converts only this
                // entity's keys that carry the separator, so no other key can fail the number.
                " FROM approval_queue q JOIN employee e ON e.employee_id = CASE WHEN"
                        + " q.entity_name = :entity AND INSTR(q.entity_key, '|') > 1 THEN"
                        + " CAST(SUBSTR(q.entity_key, 1, INSTR(q.entity_key, '|') - 1) AS"
                        + " NUMBER(19)) END WHERE q.entity_name = :entity AND q.status IN"
                        + " ('PENDING_APPROVAL', 'PENDING_ATTACHMENT', 'RETURNED') AND (:all = 'Y'"
                        + " OR e.location_id IN (:locations))";
        Long total = named.queryForObject("SELECT COUNT(*)" + from, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Request> rows =
                named.query(
                        "SELECT q.approval_id, e.emp_num, e.first_name, e.last_name, q.entity_key,"
                                + " q.impact_value, q.status, q.submitted_by, q.submitted_at,"
                                + " q.return_reason"
                                + from
                                + " ORDER BY q.approval_id DESC OFFSET :offset ROWS FETCH NEXT"
                                + " :size ROWS ONLY",
                        params,
                        (rs, i) -> {
                            String[] key = rs.getString("entity_key").split("\\|");
                            Timestamp at = rs.getTimestamp("submitted_at");
                            return new Request(
                                    rs.getLong("approval_id"),
                                    rs.getString("emp_num"),
                                    name(rs.getString("first_name"), rs.getString("last_name")),
                                    key.length > 1 ? key[1] : null,
                                    rs.getBigDecimal("impact_value"),
                                    rs.getString("status"),
                                    rs.getString("submitted_by"),
                                    at == null ? null : at.toInstant(),
                                    rs.getString("return_reason"));
                        });
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** SRS-12-17: the types the Maker may set up today (staff, not held in HRMS: D09-1). */
    @Transactional(readOnly = true)
    public List<LoanType> types() {
        return named.query(
                "SELECT loan_type_code, loan_type_name, is_interest_bearing, max_instalments FROM"
                    + " loan_type_master WHERE payroll_type_scope = 'STAFF' AND effective_from <="
                    + " :today AND (effective_to IS NULL OR effective_to >= :today) ORDER BY"
                    + " loan_type_name",
                new MapSqlParameterSource(
                        "today", java.sql.Date.valueOf(java.time.LocalDate.now(IST_ZONE))),
                (rs, i) ->
                        new LoanType(
                                rs.getString(1),
                                rs.getString(2),
                                "Y".equals(rs.getString(3)),
                                rs.getObject(4, Integer.class)));
    }

    private static final String REPORT_FROM =
            " FROM loan_monthly_balance b JOIN loan_header h ON h.loan_id = b.loan_id JOIN"
                    + " loan_type_master t ON t.loan_type_id = h.loan_type_id JOIN employee e ON"
                    + " e.employee_id = b.employee_id LEFT JOIN org_location l ON l.location_id ="
                    + " b.location_id WHERE b.payroll_yymm BETWEEN :from AND :to AND (:all = 'Y' OR"
                    + " b.location_id IN (:locations))";

    /** The report's size for the months (checked against the export limit first). */
    @Transactional(readOnly = true)
    public long reportSize(AccessContext context, String fromYymm, String toYymm) {
        Long n =
                named.queryForObject(
                        "SELECT COUNT(*)" + REPORT_FROM,
                        reportParams(context, fromYymm, toYymm),
                        Long.class);
        return RowCounts.of(n);
    }

    /**
     * SRS-12-26: the Loan Recovery &amp; Outstanding Report: every loan's ledger row of the months
     * (opening, recovered principal and interest, closing), with the office it was recovered at and
     * the IOGA narration of a transfer, so a transferred loan reconciles across both units.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void report(AccessContext context, String fromYymm, String toYymm, ExportSink sink)
            throws IOException {
        MapSqlParameterSource params = reportParams(context, fromYymm, toYymm);
        Long expected = named.queryForObject("SELECT COUNT(*)" + REPORT_FROM, params, Long.class);
        sink.header(
                "office",
                "month",
                "eis",
                "employee",
                "loan_number",
                "loan_type",
                "opening_principal",
                "principal_recovered",
                "interest_recovered",
                "closing_principal",
                "closing_interest",
                "transfer_narration");
        try {
            named.query(
                    "SELECT l.location_name, b.payroll_yymm, e.emp_num, e.first_name, e.last_name,"
                            + " h.loan_number, t.loan_type_code, b.opening_principal,"
                            + " b.principal_paid, b.interest_paid, b.closing_principal,"
                            + " b.closing_interest, b.transfer_narration"
                            + REPORT_FROM
                            + " ORDER BY l.location_name, b.payroll_yymm, e.emp_num, h.loan_number",
                    params,
                    rs -> {
                        try {
                            sink.row(
                                    nz(rs.getString(1)),
                                    rs.getString(2),
                                    rs.getString(3),
                                    name(rs.getString(4), rs.getString(5)),
                                    rs.getString(6),
                                    rs.getString(7),
                                    rs.getBigDecimal(8),
                                    rs.getBigDecimal(9),
                                    rs.getBigDecimal(10),
                                    rs.getBigDecimal(11),
                                    rs.getBigDecimal(12),
                                    nz(rs.getString(13)));
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                    });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    private MapSqlParameterSource reportParams(
            AccessContext context, String fromYymm, String toYymm) {
        if (!YYMM.matcher(fromYymm == null ? "" : fromYymm).matches()
                || !YYMM.matcher(toYymm == null ? "" : toYymm).matches()
                || fromYymm.compareTo(toYymm) > 0) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        return scoped(scope(context)).addValue("from", fromYymm).addValue("to", toYymm);
    }

    private static final java.util.regex.Pattern YYMM =
            java.util.regex.Pattern.compile("^\\d{4}(0[1-9]|1[0-2])$");

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    // ---------------------------------------------------------------- helpers

    private List<Line> lines(long versionId) {
        return named.query(
                "SELECT instalment_number, payroll_yymm, principal_amount, interest_amount FROM"
                        + " loan_recovery_schedule WHERE schedule_version_id = :v ORDER BY"
                        + " instalment_number",
                new MapSqlParameterSource("v", versionId),
                (rs, i) ->
                        new Line(
                                rs.getInt(1),
                                rs.getString(2),
                                rs.getBigDecimal(3),
                                rs.getBigDecimal(4)));
    }

    /** The cycle unit the user sees (null at Headquarters: every unit), with I-04 VIEW there. */
    Long scope(AccessContext context) {
        if (context.locationId() == null) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        if (policy.isAtHeadquarters(context)) {
            policy.requireAt(context, LoanSanctionService.SCREEN, "VIEW", context.locationId());
            return null;
        }
        OrgLocation unit =
                org.cycleUnitOf(context.locationId())
                        .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT));
        policy.requireAt(context, LoanSanctionService.SCREEN, "VIEW", unit.getId());
        return unit.getId();
    }

    private MapSqlParameterSource scoped(Long unit) {
        Set<Long> locations = unit == null ? Set.of(-1L) : org.subtree(unit);
        return new MapSqlParameterSource()
                .addValue("all", unit == null ? "Y" : "N", Types.VARCHAR)
                .addValue("locations", locations);
    }

    private static LoanRow loan(ResultSet rs) throws SQLException {
        return new LoanRow(
                rs.getLong("loan_id"),
                rs.getString("loan_number"),
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                rs.getString("loan_type_code"),
                rs.getBigDecimal("sanctioned_amount"),
                rs.getString("recovery_start_yymm"),
                rs.getInt("num_principal_instalments"),
                rs.getBigDecimal("instalment_amount"),
                rs.getInt("paid_instalments"),
                rs.getBigDecimal("principal_outstanding"),
                rs.getBigDecimal("interest_amount"),
                rs.getBigDecimal("interest_outstanding"),
                rs.getString("loan_status"),
                rs.getString("loan_source"),
                rs.getDate("separation_date") != null,
                reconciled(rs.getBigDecimal("sanctioned_amount"), rs));
    }

    /** SRS-12-1: the principal instalments of the schedule in force add up to the loan. */
    private static boolean reconciled(BigDecimal sanctioned, ResultSet rs) throws SQLException {
        BigDecimal scheduled = rs.getBigDecimal("scheduled_principal");
        return scheduled == null || scheduled.compareTo(sanctioned) == 0;
    }

    private static String name(String first, String last) {
        return last == null || last.isBlank() ? first : first + " " + last;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
