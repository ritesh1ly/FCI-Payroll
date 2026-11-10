package in.gov.fci.payroll;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.eseva.orchestration.DocumentMailDispatch;
import in.gov.fci.payroll.eseva.orchestration.MailChannel;
import in.gov.fci.payroll.integration.api.InterfaceBreakers;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.platform.i18n.MessageBundles;
import in.gov.fci.payroll.platform.notification.MailAttachment;
import in.gov.fci.payroll.platform.notification.MemberDocuments;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fixtures of the document e-mail ITs (OD-148): two members of one office (one with an e-mail ID,
 * one without), locked months in 1998, a scripted mail server, a dispatcher built with the real
 * outbox, settings, breaker and channel. Each test starts with the channel fresh (breaker closed,
 * windows empty, no pause, no slot held, capacity unlimited) and the e-mails of earlier tests
 * parked, so only its own e-mails are due.
 */
abstract class DocumentMailSupport extends ApiTestSupport {

    /** One e-mail the stand-in mail server received. */
    record Sent(
            String email,
            String subject,
            String body,
            MailAttachment attachment,
            String messageId) {}

    /** A mail server stand-in: records, or fails as scripted; tracks concurrent connections. */
    static class Server implements NotificationSender {
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger open = new AtomicInteger();
        final AtomicInteger maxOpen = new AtomicInteger();
        volatile RuntimeException failure;
        volatile Function<MailAttachment, RuntimeException> refuse = a -> null;
        volatile long holdMillis;

        @Override
        public void sendOtp(String e, String n, String p, String c, int m) {}

        @Override
        public void sendAccountLocked(String e, String n) {}

        @Override
        public void sendDocument(
                String email,
                String name,
                String subject,
                String body,
                MailAttachment file,
                String messageId) {
            int now = open.incrementAndGet();
            maxOpen.accumulateAndGet(now, Math::max);
            try {
                if (holdMillis > 0) {
                    Thread.sleep(holdMillis);
                }
                if (failure != null) {
                    throw failure;
                }
                RuntimeException refused = refuse.apply(file);
                if (refused != null) {
                    throw refused;
                }
                sent.add(new Sent(email, subject, body, file, messageId));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                open.decrementAndGet();
            }
        }

        List<Sent> about(String marker) {
            return sent.stream()
                    .filter(
                            s ->
                                    s.attachment() != null
                                                    && s.attachment().fileName().contains(marker)
                                            || s.subject().contains(marker))
                    .toList();
        }
    }

    @Autowired protected ApplicationEventPublisher events;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected InterfaceSettings settings;
    @Autowired protected InterfaceBreakers breakers;
    @Autowired protected MailChannel channel;
    @Autowired protected AuditService audit;
    @Autowired protected MessageBundles messages;
    @Autowired protected List<MemberDocuments> documents;

    protected long self;
    protected long other;
    protected long unit;
    private String otherEmail;
    private String selfEmail;
    private Map<String, Object> config;

    @BeforeEach
    void members() {
        self =
                jdbc.queryForObject(
                        "SELECT employee_id FROM app_user WHERE login_id ="
                                + " 'emp.rsharma@fci-demo.test'",
                        Long.class);
        unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = ?", Long.class, self);
        other =
                jdbc.queryForObject(
                        "SELECT MIN(employee_id) FROM employee WHERE location_id = ? AND"
                                + " employee_id <> ? AND employee_type = (SELECT employee_type"
                                + " FROM employee WHERE employee_id = ?)",
                        Long.class,
                        unit,
                        self,
                        self);
        otherEmail = email(other);
        selfEmail = email(self);
        jdbc.update("UPDATE employee SET email_address = NULL WHERE employee_id = ?", other);
        if (selfEmail == null) {
            jdbc.update(
                    "UPDATE employee SET email_address = 'emp.rsharma@fci-demo.test' WHERE"
                            + " employee_id = ?",
                    self);
        }
        // Only this test's e-mails are due; the channel starts fresh.
        jdbc.update(
                "UPDATE document_mail SET next_attempt_at = TIMESTAMP '9999-01-01 00:00:00',"
                        + " claim_until = NULL, claimed_by = NULL");
        jdbc.update(
                "UPDATE interface_breaker_state SET breaker_state = 'CLOSED',"
                        + " consecutive_failures = 0, opened_at = NULL, next_probe_at = NULL WHERE"
                        + " interface_id = (SELECT interface_id FROM interface_registry WHERE"
                        + " interface_code = 'MEMBER_EMAIL')");
        jdbc.update("UPDATE mail_rate_window SET used = 0");
        jdbc.update("UPDATE mail_send_slot SET holder = NULL, lease_until = NULL");
        jdbc.update("UPDATE mail_channel_state SET paused_until = NULL");
        config =
                jdbc.queryForMap(
                        "SELECT per_minute, per_hour, per_day, max_connections,"
                                + " max_attachment_bytes, oversize_action FROM mail_channel_config"
                                + " WHERE channel_code = 'MEMBER_EMAIL'");
        capacity(null, null, null, 4);
    }

    @AfterEach
    void restore() {
        jdbc.update(
                "UPDATE employee SET email_address = ? WHERE employee_id = ?", otherEmail, other);
        jdbc.update("UPDATE employee SET email_address = ? WHERE employee_id = ?", selfEmail, self);
        jdbc.update(
                "UPDATE mail_channel_config SET per_minute = ?, per_hour = ?, per_day = ?,"
                        + " max_connections = ?, max_attachment_bytes = ?, oversize_action = ?"
                        + " WHERE channel_code = 'MEMBER_EMAIL'",
                config.get("PER_MINUTE"),
                config.get("PER_HOUR"),
                config.get("PER_DAY"),
                config.get("MAX_CONNECTIONS"),
                config.get("MAX_ATTACHMENT_BYTES"),
                config.get("OVERSIZE_ACTION"));
        locale("en");
    }

    protected String email(long employee) {
        return jdbc.queryForObject(
                "SELECT email_address FROM employee WHERE employee_id = ?", String.class, employee);
    }

    /** The channel's capacity for this test (null: no cap in that window). */
    protected void capacity(Integer perMinute, Integer perHour, Integer perDay, int connections) {
        jdbc.update(
                "UPDATE mail_channel_config SET per_minute = ?, per_hour = ?, per_day = ?,"
                        + " max_connections = ? WHERE channel_code = 'MEMBER_EMAIL'",
                perMinute,
                perHour,
                perDay,
                connections);
    }

    protected void locale(String locale) {
        jdbc.update(
                "UPDATE app_user_preference SET preferred_locale = ? WHERE user_id = (SELECT"
                        + " user_id FROM app_user WHERE login_id = 'emp.rsharma@fci-demo.test')",
                locale);
    }

    protected DocumentMailDispatch dispatch(
            NotificationSender server,
            boolean attachment,
            boolean password,
            String rule,
            int parallelism,
            List<MemberDocuments> sources) {
        return new DocumentMailDispatch(
                jdbc,
                server,
                settings,
                breakers,
                channel,
                audit,
                messages,
                sources,
                transactions,
                true,
                10_000,
                attachment,
                password,
                rule,
                parallelism,
                Duration.ofHours(24),
                Duration.ofMinutes(15));
    }

    protected DocumentMailDispatch dispatch(
            NotificationSender server, boolean attachment, boolean password, String rule) {
        return dispatch(server, attachment, password, rule, 1, documents);
    }

    protected DocumentMailDispatch dispatch(NotificationSender server) {
        return dispatch(server, true, false, "");
    }

    /** Runs {@code work} while FCI's PAYSLIP template is out of force (restored after). */
    protected void withoutTemplate(Runnable work) {
        jdbc.update(
                "UPDATE notification_template SET effective_to = DATE '2000-01-01' WHERE"
                        + " template_code = 'PAYSLIP' AND channel = 'EMAIL' AND effective_to IS"
                        + " NULL");
        try {
            work.run();
        } finally {
            jdbc.update(
                    "UPDATE notification_template SET effective_to = NULL WHERE template_code ="
                            + " 'PAYSLIP' AND channel = 'EMAIL' AND effective_to = DATE"
                            + " '2000-01-01'");
        }
    }

    /** A cycle of {@code month} at the unit with a locked result of both members; the cycle id. */
    protected long lockedMonth(YearMonth month) {
        return lockedMonth(month, unit, new long[] {self, other});
    }

    protected long lockedMonth(YearMonth month, long office, long[] members) {
        String yymm = month.toString().replace("-", "");
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES (?, ?, 'DO', '1997-98',"
                        + " 'LOCKED', 'it.fixture')",
                yymm,
                office);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        yymm,
                        office);
        for (long employee : members) {
            jdbc.update(
                    "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                            + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                            + " total_deductions, net_pay, result_status, is_locked) SELECT ?, ?,"
                            + " employee_id, employee_type, ?, ?, 'ACTUAL', 45000, 5400, 39600,"
                            + " 'CURRENT', 'Y' FROM employee WHERE employee_id = ?",
                    cycle,
                    office,
                    yymm,
                    Date.valueOf(month.atDay(1)),
                    employee);
            long result =
                    jdbc.queryForObject(
                            "SELECT MAX(payroll_result_id) FROM payroll_result WHERE employee_id ="
                                    + " ? AND payroll_yymm = ? AND cycle_instance_id = ?",
                            Long.class,
                            employee,
                            yymm,
                            cycle);
            jdbc.update(
                    "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type, is_locked,"
                        + " payroll_yymm, period_date, legacy_pay_code) SELECT ?, ?, ?, ?,"
                        + " c.component_id, ?, 'ACTUAL', 'Y', ?, ?, c.legacy_pay_code FROM"
                        + " pay_component c WHERE c.legacy_pay_code = 101",
                    result,
                    cycle,
                    office,
                    employee,
                    new BigDecimal("45000"),
                    yymm,
                    Date.valueOf(month.atDay(1)));
        }
        return cycle;
    }

    /** The lock's event, in a transaction as the lock publishes it. */
    protected void lock(long cycle, YearMonth month) {
        lock(cycle, month, unit);
    }

    protected void lock(long cycle, YearMonth month, long office) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx ->
                                events.publishEvent(
                                        new CycleLocked(
                                                cycle,
                                                office,
                                                month.toString().replace("-", ""),
                                                "it.checker",
                                                Instant.now())));
    }

    protected Map<String, Object> row(long employee, String type, String period) {
        return jdbc.queryForMap(
                "SELECT n.delivery_status, n.delivery_detail, n.sent_at, d.attempt_count,"
                        + " d.bounced_flag, d.attachment_name, d.attachment_bytes,"
                        + " d.failure_class, d.without_attachment, d.message_id, d.claim_until,"
                        + " d.next_attempt_at FROM document_mail d JOIN notification n ON"
                        + " n.notification_id = d.notification_id AND n.created_at ="
                        + " d.notification_created_at WHERE d.employee_id = ? AND d.document_type"
                        + " = ? AND d.period_key = ?",
                employee,
                type,
                period);
    }

    protected long count(String type, String period) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_mail WHERE document_type = ? AND period_key = ? AND"
                        + " employee_id IN (?, ?)",
                Long.class,
                type,
                period,
                self,
                other);
    }
}
