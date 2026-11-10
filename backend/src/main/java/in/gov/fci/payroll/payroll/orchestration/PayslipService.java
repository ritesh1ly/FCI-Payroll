package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.persistence.StaffNumberInForce;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payslip in eSeva (plan 16 phase 16e-3; SRS-1-10, SRS-17-1, SRS-17-7): the member's own months
 * whose result is locked (no provisional payslip for an open period), and one month's earnings and
 * deductions detail, with PAN and bank account masked (R-PII). Only the session's own employee
 * record; another member's month does not exist for this member. The component set follows the
 * member's employee type (BR-ESEVA-005, FRS l.6974, DDL {@code payslip.layout_variant}): a staff
 * member reads STAFF results, a labour member the results of the own labour payroll, and the two
 * layouts are never merged. O-01 and the staff support preview read through {@link #ofStaff} and
 * {@link #ofPreview}, whose scope is checked here.
 *
 * <p>OPEN-QUESTION (PENDING 16-payslip-delivery): the downloadable payslip (its prescribed layout)
 * waits for LIB-1.
 */
@Service
@Loggable
public class PayslipService {

    private static final String O_01 = "O-01";

    /** One locked month of the member. */
    public record MonthRow(String yymm, BigDecimal gross, BigDecimal deductions, BigDecimal net) {}

    /** One line of the payslip, its amount as shown (a deduction positive). */
    public record Line(int payCode, String name, String group, BigDecimal amount) {}

    /** One month's payslip. */
    public record Payslip(
            String yymm,
            String eis,
            String name,
            String designation,
            String office,
            String payPattern,
            String panMasked,
            String bankAccountMasked,
            String pran,
            List<Line> earnings,
            List<Line> deductions,
            BigDecimal gross,
            BigDecimal totalDeductions,
            BigDecimal net,
            String layoutVariant,
            boolean provisional) {}

    /** BR-ESEVA-005: the payroll type that matches the member's own employee type. */
    private static final String OWN_TYPE =
            " AND r.payroll_type = (SELECT e.employee_type FROM employee e WHERE e.employee_id ="
                    + " r.employee_id)";

    private static final String ACTUAL =
            " FROM payroll_result r WHERE r.employee_id = ?"
                    + OWN_TYPE
                    + " AND r.run_type = 'ACTUAL' AND r.result_status = 'CURRENT'";

    private static final String LOCKED = ACTUAL + " AND r.is_locked = 'Y'";

    private final JdbcTemplate jdbc;
    private final Authorization policy;

    public PayslipService(JdbcTemplate jdbc, Authorization policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    /** SRS-17-1: the member's locked months, newest first, paged. */
    @Transactional(readOnly = true)
    public Paged<MonthRow> months(AccessContext context, PageSpec spec) {
        long self = self(context);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + LOCKED, Long.class, self);
        List<MonthRow> rows =
                jdbc.query(
                        "SELECT r.payroll_yymm, r.gross_earnings, r.total_deductions, r.net_pay"
                                + LOCKED
                                + " ORDER BY r.payroll_yymm DESC, r.payroll_result_id DESC OFFSET ?"
                                + " ROWS FETCH NEXT ? ROWS ONLY",
                        (rs, i) ->
                                new MonthRow(
                                        rs.getString(1),
                                        rs.getBigDecimal(2),
                                        rs.getBigDecimal(3),
                                        rs.getBigDecimal(4)),
                        self,
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** SRS-1-10, SRS-17-7: one locked month's payslip of the member. */
    @Transactional(readOnly = true)
    public Payslip payslip(AccessContext context, String yymm) {
        return read(self(context), yymm, LOCKED);
    }

    /**
     * O-01: one committed month of a member in the session's scope, for the Preview. Before the
     * lock it is provisional (SRS-18-23, drawn "(provisional)"); a member outside the scope reads
     * as not found and the attempt is a security event (SRS-G-2).
     */
    @Transactional(readOnly = true)
    public Payslip ofStaff(AccessContext context, String eis, String yymm) {
        List<Map<String, Object>> found =
                jdbc.queryForList(
                        "SELECT e.employee_id, e.location_id FROM employee e WHERE e.emp_num = ?"
                                + " ORDER BY e.employee_id",
                        eis);
        if (found.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        Map<String, Object> e =
                found.stream()
                        .filter(r -> policy.canAt(context, O_01, "VIEW", location(r)))
                        .findFirst()
                        .orElse(found.getFirst());
        policy.requireRecordAt(context, O_01, "VIEW", location(e));
        return read(((Number) e.get("EMPLOYEE_ID")).longValue(), yymm, ACTUAL);
    }

    /**
     * E-01 support preview (SRS-17-6, l.496): a locked month of the member of the user's own open
     * session; another user's session, or a closed one, reads as not found.
     */
    @Transactional(readOnly = true)
    public Payslip ofPreview(AccessContext context, long sessionId, String yymm) {
        List<Long> member =
                jdbc.queryForList(
                        "SELECT employee_id FROM ess_session WHERE session_id = ? AND session_owner"
                            + " = 'STAFF' AND opened_by_staff_user_id = ? AND closed_at IS NULL",
                        Long.class,
                        sessionId,
                        context.userId());
        if (member.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return read(member.getFirst(), yymm, LOCKED);
    }

    /**
     * OD-149 (the O-01 bulk job on the batch node, the e-mail dispatcher): the locked month of an
     * employee, read for a job that already resolved its unit and scope; the same read as the
     * screens, so the PDF is the same document. In its own read-only transaction (SRS-NF-7: a
     * member that cannot be read stops only itself; joining the job's transaction, its refusal
     * would mark the whole chunk for rollback).
     */
    @Transactional(
            readOnly = true,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Payslip lockedOf(long employeeId, String yymm) {
        return read(employeeId, yymm, LOCKED);
    }

    /** One member of a bulk file: the record id and the staff number its file is named by. */
    public record BulkMember(long employeeId, String eis) {}

    /**
     * OD-149, SRS l.3389 (O-01 Bulk PDF): the members of a paying unit with a payslip for the
     * locked month at the level (STAFF, or the labour levels), in staff-number order (a fixed
     * order: the same request gives the same file); a held or suppressed member has none, as on the
     * register.
     */
    @Transactional(readOnly = true)
    public List<BulkMember> bulkMembers(long unitId, String yymm, boolean staff) {
        return jdbc.query(
                "SELECT r.employee_id, e.emp_num FROM payroll_result r JOIN employee e ON"
                    + " e.employee_id = r.employee_id WHERE r.org_unit_id = ? AND r.period_date = ?"
                    + " AND r.payroll_yymm = ? AND r.run_type = 'ACTUAL' AND r.result_status ="
                    + " 'CURRENT' AND r.is_locked = 'Y' AND ((? = 'Y' AND r.payroll_type = 'STAFF')"
                    + " OR (? = 'N' AND r.payroll_type <> 'STAFF')) AND NOT EXISTS (SELECT 1 FROM"
                    + " payslip s WHERE s.cycle_instance_id = r.cycle_instance_id AND s.employee_id"
                    + " = r.employee_id AND s.suppressed_flag = 'Y') AND NOT EXISTS (SELECT 1 FROM"
                    + " payroll_exception x WHERE x.employee_id = r.employee_id AND"
                    + " x.cycle_instance_id = r.cycle_instance_id AND x.exception_code ="
                    + " 'PAYMENT_HELD' AND x.resolved_at IS NULL) ORDER BY e.emp_num,"
                    + " r.employee_id",
                (rs, i) -> new BulkMember(rs.getLong(1), rs.getString(2)),
                unitId,
                firstDay(yymm),
                yymm,
                staff ? "Y" : "N",
                staff ? "Y" : "N");
    }

    /**
     * SRS l.3389: whether every cycle of the unit's month is LOCKED (a month with no cycle is not).
     */
    @Transactional(readOnly = true)
    public boolean monthLocked(long unitId, String yymm) {
        Long[] counts =
                jdbc.queryForObject(
                        "SELECT COUNT(*), SUM(CASE WHEN cycle_status = 'LOCKED' THEN 1 ELSE 0 END)"
                                + " FROM payroll_cycle_instance WHERE org_unit_id = ? AND"
                                + " payroll_yymm = ?",
                        (rs, i) -> new Long[] {rs.getLong(1), rs.getLong(2)},
                        unitId,
                        yymm);
        return counts != null && counts[0] > 0 && counts[0].equals(counts[1]);
    }

    /**
     * OD-149 (E-01 bulk): the member's own locked months from {@code from} to {@code to} (YYYYMM,
     * inclusive), oldest first; an unlocked month or another member's is simply not among them (no
     * existence leak).
     */
    @Transactional(readOnly = true)
    public List<String> lockedMonths(AccessContext context, String from, String to) {
        long self = self(context);
        return jdbc.queryForList(
                "SELECT DISTINCT r.payroll_yymm"
                        + LOCKED
                        + " AND r.payroll_yymm BETWEEN ? AND ?"
                        + " ORDER BY r.payroll_yymm",
                String.class,
                self,
                from,
                to);
    }

    private static Long location(Map<String, Object> e) {
        Object location = e.get("LOCATION_ID");
        return location == null ? null : ((Number) location).longValue();
    }

    private Payslip read(long self, String yymm, String committed) {
        // Oracle review F8 (1,000 members on salary day): the month's partition key is bound, so
        // the result and its lines are read from one partition (the lines from one hash
        // subpartition too: the result's unit), not probed in every month of the history.
        Date period = firstDay(yymm);
        Map<String, Object> result =
                jdbc
                        .queryForList(
                                "SELECT r.payroll_result_id, r.org_unit_id, r.gross_earnings,"
                                        + " r.total_deductions, r.net_pay, r.payroll_type,"
                                        + " r.is_locked"
                                        + committed
                                        + " AND r.period_date = ? AND r.payroll_yymm = ?",
                                self,
                                period,
                                yymm)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        Map<String, Object> e =
                jdbc.queryForMap(
                        // SRS-16-7 (OD-67): the staff number in force at the month's end, so a
                        // month before a transfer keeps the number it was paid under.
                        "SELECT "
                                + StaffNumberInForce.OF_EMPLOYEE_E
                                + " AS emp_num, e.first_name, e.last_name, e.pay_pattern,"
                                + " e.pan_number, e.bank_account_number, d.designation_name,"
                                + " l.location_name, COALESCE(n.pran_reference, e.pran_number) AS"
                                + " pran FROM employee e LEFT JOIN org_designation d ON"
                                + " d.designation_id = e.designation_id LEFT JOIN org_location l ON"
                                + " l.location_id = e.location_id LEFT JOIN nps_deduction n ON"
                                + " n.employee_id = e.employee_id AND n.payroll_yymm = ? AND"
                                + " n.record_type = 'MONTHLY' WHERE e.employee_id = ?",
                        StaffNumberInForce.dayOf(YearMonth.from(period.toLocalDate())),
                        yymm,
                        self);
        List<Line> earnings = new ArrayList<>();
        List<Line> deductions = new ArrayList<>();
        jdbc.query(
                "SELECT c.legacy_pay_code, c.component_name, c.payslip_group, SUM(rc.amount) FROM"
                        + " payroll_result_component rc JOIN pay_component c ON c.component_id ="
                        + " rc.component_id WHERE rc.payroll_result_id = ? AND rc.period_date = ?"
                        + " AND rc.org_unit_id = ? GROUP BY c.legacy_pay_code, c.component_name,"
                        + " c.payslip_group ORDER BY c.legacy_pay_code",
                rs -> {
                    BigDecimal amount = rs.getBigDecimal(4);
                    Line line =
                            new Line(rs.getInt(1), rs.getString(2), rs.getString(3), amount.abs());
                    // SRS-DM-6: the sign is on the row; a negative line is a deduction.
                    (amount.signum() < 0 ? deductions : earnings).add(line);
                },
                ((Number) result.get("PAYROLL_RESULT_ID")).longValue(),
                period,
                ((Number) result.get("ORG_UNIT_ID")).longValue());
        return new Payslip(
                yymm,
                (String) e.get("EMP_NUM"),
                fullName((String) e.get("FIRST_NAME"), (String) e.get("LAST_NAME")),
                (String) e.get("DESIGNATION_NAME"),
                (String) e.get("LOCATION_NAME"),
                (String) e.get("PAY_PATTERN"),
                maskPan((String) e.get("PAN_NUMBER")),
                maskAccount((String) e.get("BANK_ACCOUNT_NUMBER")),
                // SRS-9-2: the PRAN as a reference, the month's as HRMS sent it (else the
                // master's).
                (String) e.get("PRAN"),
                earnings,
                deductions,
                (BigDecimal) result.get("GROSS_EARNINGS"),
                (BigDecimal) result.get("TOTAL_DEDUCTIONS"),
                (BigDecimal) result.get("NET_PAY"),
                "STAFF".equals(result.get("PAYROLL_TYPE")) ? "STAFF" : "LABOUR",
                !"Y".equals(String.valueOf(result.get("IS_LOCKED")).strip()));
    }

    /** The partition key of a month (its first day); a month that is not YYYYMM has no payslip. */
    static Date firstDay(String yymm) {
        try {
            return Date.valueOf(
                    YearMonth.parse(yymm == null ? "" : yymm, DateTimeFormatter.ofPattern("uuuuMM"))
                            .atDay(1));
        } catch (DateTimeParseException ex) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
    }

    /** The session's own employee record; a user without one has no payslip. */
    private static long self(AccessContext context) {
        if (context.employeeId() == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {"E-01", "SELF"}, List.of(), null);
        }
        return context.employeeId();
    }

    /** The name as printed: the first name alone when there is no surname. */
    static String fullName(String first, String last) {
        return last == null || last.isBlank() ? first : first + " " + last;
    }

    /**
     * R-PII (BR-SEC-001, SRS l.754, l.802; BMAD TR-035): PAN by its last 4 characters only, through
     * the one platform mask.
     */
    static String maskPan(String pan) {
        return in.gov.fci.payroll.platform.logging.PiiMasks.pan(pan);
    }

    /** R-PII: a bank account by its last four digits only. */
    static String maskAccount(String account) {
        return in.gov.fci.payroll.platform.logging.PiiMasks.lastFour(account);
    }
}
