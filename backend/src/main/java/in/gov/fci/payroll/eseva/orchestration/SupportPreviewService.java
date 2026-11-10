package in.gov.fci.payroll.eseva.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.eseva.business.EsevaErrorCode;
import in.gov.fci.payroll.eseva.business.EsevaRules;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.logging.SensitiveDataMasker;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff support preview of a member's eSeva view on E-01 and E-02 (plan 14 phase 14b; SRS-17-6,
 * SRS-17-8, SRS-17-9, SRS-17-10, SRS l.496, l.5499; OD-135; KB ES-3; D14-5). The right is E-02
 * SUPPORT or the staff VIEW of E-01; an eSeva member also holds E-01 VIEW, for the menu, and is
 * refused here: a member holds no staff group. The payroll staff user names the member and the
 * reason; the member must be in the user's data scope (else the record reads as not found and the
 * attempt is a security event, SRS-17-9). Each look opens a STAFF {@code ess_session} with submit
 * actions disabled (DDL {@code chk_ess_support_readonly}, {@code chk_ess_support_reason}) and an
 * audit entry shown on X-04 (SRS-17-8), and returns a read-only snapshot with PAN, Aadhaar, bank
 * account, mobile and e-mail masked (SRS-17-10). No endpoint of any module accepts a submission in
 * a staff session: every submit path is the member's own {@code /mine} (SRS-17-6).
 *
 * <p>FRS V0.5 l.6958, l.6979 (US-GAP-261, OD): the session keeps its opening and closing timestamps
 * - the staff user closes the preview ({@link #close}, closed explicitly), and a session left open
 * is closed when the same user opens the next one (not explicitly).
 */
@Service
@Loggable
public class SupportPreviewService {

    /** The member as the preview shows them (masked; HRMS data read-only). */
    public record Member(
            String eis,
            String name,
            String office,
            String employeeType,
            String employmentStatus,
            String panMasked,
            String aadhaarMasked,
            String bankAccountMasked,
            String mobileMasked,
            String emailMasked) {}

    /** One locked month of the member (SRS-17-1: committed results only). */
    public record Month(String yymm, BigDecimal gross, BigDecimal deductions, BigDecimal net) {}

    /** One submission of the member from the log. */
    public record Submission(String type, String value, String status, Instant submittedAt) {}

    /**
     * The snapshot.
     *
     * @param submitEnabled always false (SRS-17-6), so the screen disables every submit action
     */
    public record Preview(
            long sessionId,
            Instant openedAt,
            boolean submitEnabled,
            Member member,
            List<Month> payslipMonths,
            List<Submission> submissions) {}

    /** The latest locked months shown (a preview, not the register). */
    static final int MONTHS = 12;

    /** The latest submissions shown. */
    static final int SUBMISSIONS = 20;

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final AuditService audit;
    private final Clock clock;

    public SupportPreviewService(
            JdbcTemplate jdbc, Authorization policy, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /** E-02: one recorded look at the member's eSeva view. */
    @Transactional
    public Preview preview(AccessContext context, String eis, String reason) {
        String[] right = right(context);
        String why =
                EsevaRules.reason(reason)
                        .orElseThrow(() -> new FciException(EsevaErrorCode.REASON));
        // Employee numbers are unique within a zone (uk_employee_emp_num): the member is the one
        // in the user's scope; with none, the scope check answers not found and logs the attempt.
        List<Map<String, Object>> found =
                jdbc.queryForList(
                        "SELECT e.employee_id, e.emp_num, e.first_name, e.last_name, e.location_id,"
                                + " l.location_code, l.location_name, e.employee_type,"
                                + " e.employment_status, e.pan_number, e.aadhaar_number,"
                                + " e.bank_account_number, e.mobile_number, e.email_address FROM"
                                + " employee e LEFT JOIN org_location l ON l.location_id ="
                                + " e.location_id WHERE e.emp_num = ? ORDER BY e.employee_id",
                        eis == null ? "" : eis.strip());
        if (found.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        Map<String, Object> e =
                found.stream()
                        .filter(r -> policy.canAt(context, right[0], right[1], location(r)))
                        .findFirst()
                        .orElse(found.getFirst());
        long employeeId = ((Number) e.get("EMPLOYEE_ID")).longValue();
        policy.requireRecordAt(context, right[0], right[1], location(e));
        if (Long.valueOf(employeeId).equals(context.employeeId())) {
            throw new FciException(EsevaErrorCode.OWN_RECORD);
        }
        Instant now = clock.instant();
        // A look left open by this user ends when the next one opens (closed, not explicitly).
        jdbc.update(
                "UPDATE ess_session SET closed_at = ?, closed_explicitly = 'N' WHERE session_owner"
                        + " = 'STAFF' AND opened_by_staff_user_id = ? AND closed_at IS NULL",
                Timestamp.from(now),
                context.userId());
        long session = session(context, employeeId, why, now);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "ess_session",
                                String.valueOf(session),
                                "session_owner",
                                null,
                                "STAFF",
                                "Support preview of eSeva of " + e.get("EMP_NUM") + ": " + why)
                        .module(SubmissionLogService.SCREEN));
        return new Preview(
                session, now, false, member(e), months(employeeId), submissions(employeeId));
    }

    /**
     * FRS V0.5 l.6958 (US-GAP-261): the staff user closes their own open support preview; the
     * closing timestamp is recorded and audited. Another user's session, or one already closed,
     * reads as not found.
     */
    @Transactional
    public Instant close(AccessContext context, long sessionId) {
        right(context);
        Instant now = clock.instant();
        int closed =
                jdbc.update(
                        "UPDATE ess_session SET closed_at = ?, closed_explicitly = 'Y' WHERE"
                                + " session_id = ? AND session_owner = 'STAFF' AND"
                                + " opened_by_staff_user_id = ? AND closed_at IS NULL",
                        Timestamp.from(now),
                        sessionId,
                        context.userId());
        if (closed == 0) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "ess_session",
                                String.valueOf(sessionId),
                                "closed_at",
                                null,
                                now.toString(),
                                "Support preview closed")
                        .module(SubmissionLogService.SCREEN));
        return now;
    }

    /** The staff right the session looks with: E-02 SUPPORT, else the staff VIEW of E-01. */
    private String[] right(AccessContext context) {
        if (context.groups().isEmpty()) {
            // An eSeva session: E-01 VIEW opens the member's own screen, never a preview.
            policy.require(context, SubmissionLogService.SCREEN, "SUPPORT");
        }
        if (policy.can(context, SubmissionLogService.SCREEN, "SUPPORT")) {
            return new String[] {SubmissionLogService.SCREEN, "SUPPORT"};
        }
        policy.require(context, "E-01", "VIEW");
        return new String[] {"E-01", "VIEW"};
    }

    private static Long location(Map<String, Object> e) {
        Object location = e.get("LOCATION_ID");
        return location == null ? null : ((Number) location).longValue();
    }

    private long session(AccessContext context, long employeeId, String why, Instant now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        Timestamp at = Timestamp.from(now);
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO ess_session (employee_id, session_owner,"
                                            + " opened_by_staff_user_id, access_reason,"
                                            + " submit_actions_enabled, opened_at, closed_at,"
                                            + " closed_explicitly, pii_masked) VALUES (?, 'STAFF',"
                                            + " ?, ?, 'N', ?, NULL, 'N', 'Y')",
                                    new String[] {"session_id"});
                    ps.setLong(1, employeeId);
                    ps.setObject(2, context.userId(), Types.BIGINT);
                    ps.setString(3, why);
                    ps.setTimestamp(4, at);
                    return ps;
                },
                key);
        Number id = key.getKey();
        if (id == null) {
            throw new IllegalStateException("The support session just written has no id");
        }
        jdbc.update(
                "UPDATE ess_session SET session_reference = ? WHERE session_id = ?",
                "ESS-SUP-" + id.longValue(),
                id.longValue());
        return id.longValue();
    }

    private static Member member(Map<String, Object> e) {
        String last = (String) e.get("LAST_NAME");
        String first = (String) e.get("FIRST_NAME");
        Object code = e.get("LOCATION_CODE");
        return new Member(
                (String) e.get("EMP_NUM"),
                last == null || last.isBlank() ? first : first + " " + last,
                code == null ? null : code + " - " + e.get("LOCATION_NAME"),
                (String) e.get("EMPLOYEE_TYPE"),
                (String) e.get("EMPLOYMENT_STATUS"),
                EsevaRules.maskPan((String) e.get("PAN_NUMBER")),
                EsevaRules.maskTail((String) e.get("AADHAAR_NUMBER")),
                EsevaRules.maskTail((String) e.get("BANK_ACCOUNT_NUMBER")),
                EsevaRules.maskMobile((String) e.get("MOBILE_NUMBER")),
                SensitiveDataMasker.maskEmail((String) e.get("EMAIL_ADDRESS")));
    }

    private List<Month> months(long employeeId) {
        return jdbc.query(
                "SELECT r.payroll_yymm, r.gross_earnings, r.total_deductions, r.net_pay FROM"
                        + " payroll_result r WHERE r.employee_id = ? AND r.run_type = 'ACTUAL' AND"
                        + " r.result_status = 'CURRENT' AND r.is_locked = 'Y' ORDER BY"
                        + " r.payroll_yymm DESC FETCH FIRST "
                        + MONTHS
                        + " ROWS ONLY",
                (rs, i) ->
                        new Month(
                                rs.getString(1),
                                rs.getBigDecimal(2),
                                rs.getBigDecimal(3),
                                rs.getBigDecimal(4)),
                employeeId);
    }

    private List<Submission> submissions(long employeeId) {
        return jdbc.query(
                "SELECT submission_type, submitted_value, submission_status, submitted_at FROM"
                        + " ess_submission WHERE employee_id = ? ORDER BY submission_id DESC FETCH"
                        + " FIRST "
                        + SUBMISSIONS
                        + " ROWS ONLY",
                (rs, i) ->
                        new Submission(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getTimestamp(4).toInstant()),
                employeeId);
    }
}
