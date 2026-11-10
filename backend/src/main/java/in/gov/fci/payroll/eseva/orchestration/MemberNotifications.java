package in.gov.fci.payroll.eseva.orchestration;

import in.gov.fci.payroll.eseva.business.EsevaRules;
import in.gov.fci.payroll.loans.api.LoanInstalmentChanged;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member notifications of eSeva (plan 14 phase 14e; SRS-17-12, SRS-17-1, CF-12, KB ES-7; DDL {@code
 * notification}, {@code notification_template}; D14-8). In the transaction of the change:
 *
 * <ul>
 *   <li>every status of a self-service submission (submitted, in approval, actioned, rejected) is
 *       an in-app notice the member sees on E-01, and an e-mail to the member's registered address
 *       queued in FCI's wording (template ESEVA_STATUS);
 * </ul>
 *
 * <p>The payslip e-mailed at the month-end lock (SRS-17-1) and Form 16 on release are queued by
 * {@link DocumentMails} (OD-148: with the PDF, retried by the MEMBER_EMAIL settings, once per
 * member and period).
 *
 * <p>The e-mails go out from the queue on the batch instance ({@link NotificationDispatch}); a
 * failed e-mail is marked FAILED and never blocks the change (SRS-17-12). Without the template, or
 * without a registered e-mail, the e-mail is SUPPRESSED with the reason: the wording is FCI's (FRS
 * v0.4 §13.2), never a text of this code.
 */
@Service
@Loggable
public class MemberNotifications {

    static final String STATUS_TEMPLATE = "ESEVA_STATUS";
    static final String LOAN_TEMPLATE = "LOAN_INSTALMENT";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** One in-app notice of the member. */
    public record Notice(
            long id,
            String type,
            String relatedEntity,
            String relatedKey,
            String message,
            boolean read,
            Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final Clock clock;

    public MemberNotifications(JdbcTemplate jdbc, Authorization policy, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * SRS-17-12: the submission's new status, in the caller's transaction.
     *
     * @param type the submission type (DDL {@code ess_submission.submission_type})
     * @param status SUBMITTED, ROUTED, ACTIONED or REJECTED
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(
            long employeeId, long submissionId, String type, String status, String summary) {
        notify(
                employeeId,
                "ESEVA_" + status,
                "ess_submission",
                String.valueOf(submissionId),
                type + ": " + (summary == null ? "" : summary),
                STATUS_TEMPLATE,
                Map.of(
                        "type",
                        type,
                        "status",
                        status,
                        "reference",
                        "#" + submissionId,
                        "summary",
                        summary == null ? "" : summary));
    }

    /**
     * SRS-12-10 / BR-LOAN-009 (PENDING 09-notify): a foreclosure or reschedule changed the member's
     * instalment, so net pay, from a month; the member is told in eSeva and by e-mail.
     */
    @EventListener
    @Transactional
    public void on(LoanInstalmentChanged changed) {
        String loan = changed.loanNumber() == null ? "" : changed.loanNumber();
        String change = changed.change() == null ? "" : changed.change();
        String from = changed.fromYymm() == null ? "" : changed.fromYymm();
        String instalment =
                changed.newInstalment() == null ? "" : changed.newInstalment().toPlainString();
        notify(
                changed.employeeId(),
                "LOAN_INSTALMENT",
                "loan_header",
                loan,
                loan + " " + change + ": instalment " + instalment + " from " + from,
                LOAN_TEMPLATE,
                Map.of("loan", loan, "change", change, "instalment", instalment, "from", from));
    }

    /** The in-app notice (always) and the e-mail in FCI's wording (queued or suppressed). */
    private void notify(
            long employeeId,
            String type,
            String entity,
            String key,
            String summary,
            String templateCode,
            Map<String, String> values) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                "INSERT INTO notification (recipient_employee_id, channel, notification_type,"
                        + " related_entity_name, related_entity_key, message, delivery_status,"
                        + " created_at, sent_at) VALUES (?, 'IN_APP', ?, ?, ?, ?, 'DELIVERED', ?,"
                        + " ?)",
                employeeId,
                type,
                entity,
                key,
                EsevaRules.value(summary),
                now,
                now);
        Optional<Map<String, Object>> template = template(templateCode);
        String email =
                jdbc
                        .queryForList(
                                "SELECT email_address FROM employee WHERE employee_id = ?",
                                String.class,
                                employeeId)
                        .stream()
                        // A member without an e-mail ID reads as a NULL row: findFirst() would
                        // throw.
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null);
        String why =
                template.isEmpty()
                        ? "Template " + templateCode + " not configured"
                        : email == null || email.isBlank() ? "No registered e-mail" : null;
        String body =
                template.map(t -> EsevaRules.render((String) t.get("BODY_EN"), values))
                        .orElse(null);
        jdbc.update(
                "INSERT INTO notification (recipient_employee_id, template_id, channel,"
                        + " notification_type, related_entity_name, related_entity_key, message,"
                        + " delivery_status, delivery_detail, created_at) VALUES (?, ?, 'EMAIL', ?,"
                        + " ?, ?, ?, ?, ?, ?)",
                employeeId,
                template.map(t -> t.get("TEMPLATE_ID")).orElse(null),
                type,
                entity,
                key,
                body == null ? null : EsevaRules.message(body),
                why == null ? "QUEUED" : "SUPPRESSED",
                why,
                now);
    }

    /** E-01: the member's in-app notices, newest first, paged. */
    @Transactional(readOnly = true)
    public Paged<Notice> mine(AccessContext context, PageSpec spec) {
        long me = self(context);
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM notification WHERE recipient_employee_id = ? AND"
                                + " channel = 'IN_APP'",
                        Long.class,
                        me);
        List<Notice> rows =
                jdbc.query(
                        "SELECT notification_id, notification_type, related_entity_name,"
                                + " related_entity_key, message, is_read_flag, created_at FROM"
                                + " notification WHERE recipient_employee_id = ? AND channel ="
                                + " 'IN_APP' ORDER BY created_at DESC, notification_id DESC OFFSET"
                                + " ? ROWS FETCH NEXT ? ROWS ONLY",
                        (rs, i) ->
                                new Notice(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        "Y".equals(rs.getString(6)),
                                        rs.getTimestamp(7).toInstant()),
                        me,
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** E-01: the member marks an own notice read (another member's reads as not found). */
    @Transactional
    public void read(AccessContext context, long id) {
        long me = self(context);
        int updated =
                jdbc.update(
                        "UPDATE notification SET is_read_flag = 'Y' WHERE notification_id = ? AND"
                                + " recipient_employee_id = ? AND channel = 'IN_APP'",
                        id,
                        me);
        if (updated == 0) {
            // SRS-17-9: a notice that exists and is another member's reads as not found, and the
            // attempt is logged; a notice that does not exist is no attempt on anyone.
            Long theirs =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM notification WHERE notification_id = ?",
                            Long.class,
                            id);
            if (theirs != null && theirs > 0) {
                policy.recordForeignAttempt(context, "E-01", "SELF", "notification", id);
            }
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
    }

    /** The template of the channel EMAIL in force today, if FCI has configured it. */
    private Optional<Map<String, Object>> template(String code) {
        Date today = Date.valueOf(LocalDate.now(clock.withZone(IST)));
        return jdbc
                .queryForList(
                        "SELECT template_id, subject_en, body_en FROM notification_template WHERE"
                                + " template_code = ? AND channel = 'EMAIL' AND effective_from <= ?"
                                + " AND (effective_to IS NULL OR effective_to >= ?) ORDER BY"
                                + " effective_from DESC",
                        code,
                        today,
                        today)
                .stream()
                .findFirst();
    }

    private long self(AccessContext context) {
        policy.require(context, "E-01", "SELF");
        if (context.employeeId() == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {"E-01", "SELF"}, List.of(), null);
        }
        return context.employeeId();
    }
}
