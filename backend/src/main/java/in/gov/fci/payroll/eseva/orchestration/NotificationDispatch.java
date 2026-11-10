package in.gov.fci.payroll.eseva.orchestration;

import in.gov.fci.payroll.eseva.business.EsevaRules;
import in.gov.fci.payroll.platform.maintenance.Housekeeping;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends the queued member e-mails (plan 14 phase 14e; SRS-17-12, SRS-17-1; D14-8), oldest first,
 * {@code fci.api.notification.batch} at a time on each housekeeping run of the batch instance, so
 * heavy mail never slows the online instance or a payroll run (SRS-NF-6). The subject and body are
 * FCI's template; a payslip e-mail is filled from the locked result ({month}, {gross},
 * {deductions}, {net}). A failure marks the row FAILED with the reason and the others go on
 * (SRS-17-12: never blocking); the member still sees the status at the next sign-in. The e-mailed
 * documents (payslip, Form 16: rows with a {@code document_mail} outbox row) are sent by {@link
 * DocumentMailDispatch} with their PDF and retries (OD-148).
 */
@Component
public class NotificationDispatch implements Housekeeping {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatch.class);

    private final JdbcTemplate jdbc;
    private final NotificationSender sender;
    private final int batch;

    public NotificationDispatch(
            JdbcTemplate jdbc,
            NotificationSender sender,
            @Value("${fci.api.notification.batch:200}") int batch) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.batch = batch;
    }

    @Override
    public String name() {
        return "memberEmails";
    }

    @Override
    public int run(Instant now) {
        List<Map<String, Object>> queued =
                jdbc.queryForList(
                        "SELECT n.notification_id, n.notification_type, n.related_entity_key,"
                                + " n.message, t.subject_en, t.body_en, e.email_address,"
                                + " e.first_name, e.last_name FROM notification n JOIN"
                                + " notification_template t ON t.template_id = n.template_id JOIN"
                                + " employee e ON e.employee_id = n.recipient_employee_id WHERE"
                                + " n.delivery_status = 'QUEUED' AND n.channel = 'EMAIL' AND NOT"
                                + " EXISTS (SELECT 1 FROM document_mail d WHERE d.notification_id"
                                + " = n.notification_id AND d.notification_created_at ="
                                + " n.created_at) ORDER BY n.created_at, n.notification_id FETCH"
                                + " FIRST ? ROWS ONLY",
                        batch);
        int sent = 0;
        for (Map<String, Object> n : queued) {
            long id = ((Number) n.get("NOTIFICATION_ID")).longValue();
            String last = (String) n.get("LAST_NAME");
            String name =
                    last == null || last.isBlank()
                            ? (String) n.get("FIRST_NAME")
                            : n.get("FIRST_NAME") + " " + last;
            try {
                String body =
                        "PAYSLIP".equals(n.get("NOTIFICATION_TYPE"))
                                ? payslip(
                                        (String) n.get("BODY_EN"),
                                        (String) n.get("RELATED_ENTITY_KEY"),
                                        name)
                                : (String) n.get("MESSAGE");
                sender.sendMessage(
                        (String) n.get("EMAIL_ADDRESS"),
                        name,
                        EsevaRules.render((String) n.get("SUBJECT_EN"), Map.of("name", name)),
                        EsevaRules.render(body, Map.of("name", name)));
                jdbc.update(
                        "UPDATE notification SET delivery_status = 'SENT', sent_at = ? WHERE"
                                + " notification_id = ?",
                        Timestamp.from(now),
                        id);
                sent++;
            } catch (RuntimeException failed) {
                LOG.warn(
                        "event=NOTIFICATION_FAILED id={} reason={}",
                        id,
                        failed.getClass().getSimpleName());
                jdbc.update(
                        "UPDATE notification SET delivery_status = 'FAILED', delivery_detail = ?"
                                + " WHERE notification_id = ?",
                        EsevaRules.value(
                                failed.getClass().getSimpleName()
                                        + (failed.getMessage() == null
                                                ? ""
                                                : ": " + failed.getMessage())),
                        id);
            }
        }
        return sent;
    }

    /** SRS-17-1: the payslip e-mail filled from the locked result it names. */
    private String payslip(String template, String resultKey, String name) {
        Map<String, Object> r =
                jdbc.queryForMap(
                        "SELECT payroll_yymm, gross_earnings, total_deductions, net_pay FROM"
                                + " payroll_result WHERE payroll_result_id = ?",
                        Long.parseLong(resultKey));
        String yymm = (String) r.get("PAYROLL_YYMM");
        return EsevaRules.render(
                template,
                Map.of(
                        "name", name,
                        "month", yymm.substring(4) + "/" + yymm.substring(0, 4),
                        "gross", String.valueOf(r.get("GROSS_EARNINGS")),
                        "deductions", String.valueOf(r.get("TOTAL_DEDUCTIONS")),
                        "net", String.valueOf(r.get("NET_PAY"))));
    }
}
