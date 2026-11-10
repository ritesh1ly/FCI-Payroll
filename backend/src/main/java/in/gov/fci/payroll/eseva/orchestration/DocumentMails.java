package in.gov.fci.payroll.eseva.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.notification.MemberDocumentsReleased;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox of the e-mailed member documents (OD-148; SRS-17-1 l.3250, O-01 l.3390, SRS-17-12; DDL
 * {@code notification}; extension {@code document_mail}, V082_60).
 *
 * <ul>
 *   <li>At the month-end lock ({@link CycleLocked}, in the lock's transaction), one payslip e-mail
 *       per member of the cycle with a locked result of the member's own type, in two set-based
 *       statements (the lock does not wait on 50,000 members). Once only: a member, document and
 *       period already recorded is never queued again (unique key), so a re-run or a repeated event
 *       sends nothing twice. Never on the Payroll Maker's bulk generation or a member's download.
 *   <li>At the release of Form 16 ({@link MemberDocumentsReleased}, in the release's transaction),
 *       one e-mail per released certificate (PENDING F16-5).
 *   <li>A member without a registered e-mail is recorded SUPPRESSED with the reason, never failed
 *       silently, and is listed for the Payroll Maker ({@link #unit}); a member whose payment is
 *       held (no payslip until it is released, as on O-01) stays queued with the reason and is sent
 *       once the hold ends ({@link DocumentMailDispatch}).
 * </ul>
 *
 * The e-mails go out later from the batch instance ({@link DocumentMailDispatch}); nothing here
 * waits on the mail server, so the lock and the release are never blocked (SRS-17-12).
 */
@Service
@Loggable
public class DocumentMails {

    public static final String PAYSLIP = "PAYSLIP";
    public static final String FORM16 = "FORM16";

    static final String NO_EMAIL = "No registered e-mail";
    static final String HELD = "Payment held: sent once the hold ends";

    /**
     * The screen of the Payroll Maker that lists each document's e-mails (wireframe gap WF-2002).
     */
    static String screenOf(String documentType) {
        return FORM16.equals(documentType) ? "C-10" : "C-02";
    }

    /** One e-mail as the Payroll Maker sees it (no address, R-PII). */
    public record Row(
            long id,
            String eis,
            String name,
            String documentType,
            String periodKey,
            String status,
            String detail,
            int attempts,
            boolean bounced,
            Instant lastAttemptAt,
            Instant sentAt) {}

    /** The e-mails of one office, document and period: the counts by status and one page. */
    public record UnitView(Map<String, Long> counts, Paged<Row> page) {}

    /** One of the member's own e-mailed documents (E-01). */
    public record Mine(
            String documentType,
            String periodKey,
            String status,
            String detail,
            Instant queuedAt,
            Instant sentAt) {}

    private static final String MEMBERS_OF_CYCLE =
            " FROM payroll_result r JOIN employee e ON e.employee_id = r.employee_id WHERE"
                    + " r.cycle_instance_id = ? AND r.run_type = 'ACTUAL' AND r.result_status ="
                    + " 'CURRENT' AND r.is_locked = 'Y' AND r.payroll_type = e.employee_type AND"
                    + " NOT EXISTS (SELECT 1 FROM document_mail d WHERE d.employee_id ="
                    + " r.employee_id AND d.document_type = 'PAYSLIP' AND d.period_key = ?)";

    /** SRS-17-1: the e-mail of each member of the locked cycle, its status decided now. */
    private static final String PAYSLIPS_RECORDED =
            "INSERT INTO notification (recipient_employee_id, channel, notification_type,"
                + " related_entity_name, related_entity_key, delivery_status, delivery_detail,"
                + " created_at) SELECT r.employee_id, 'EMAIL', 'PAYSLIP', 'payroll_cycle_instance',"
                + " ?, CASE WHEN COALESCE(LENGTH(TRIM(e.email_address)), 0) = 0 THEN 'SUPPRESSED'"
                + " ELSE 'QUEUED' END, CASE WHEN COALESCE(LENGTH(TRIM(e.email_address)), 0) = 0"
                + " THEN '"
                    + NO_EMAIL
                    + "' WHEN r.net_pay < 0 OR EXISTS (SELECT 1 FROM payroll_exception x WHERE"
                    + " x.employee_id = r.employee_id AND x.cycle_instance_id ="
                    + " r.cycle_instance_id AND x.exception_code = 'PAYMENT_HELD' AND"
                    + " x.resolved_at IS NULL) THEN '"
                    + HELD
                    + "' END, ?"
                    + MEMBERS_OF_CYCLE;

    /** The outbox row of each e-mail just recorded (same transaction). */
    private static final String OUTBOX_OF_RECORDED =
            "INSERT INTO document_mail (notification_id, notification_created_at, employee_id,"
                    + " document_type, period_key, source_key, org_unit_id, attempt_count,"
                    + " created_at) SELECT n.notification_id, n.created_at,"
                    + " n.recipient_employee_id, ?, ?, ?, ?, 0, n.created_at FROM notification n"
                    + " WHERE n.created_at = ? AND n.channel = 'EMAIL' AND n.notification_type = ?"
                    + " AND n.related_entity_name = ? AND n.related_entity_key = ? AND NOT EXISTS"
                    + " (SELECT 1 FROM document_mail d WHERE d.notification_id ="
                    + " n.notification_id AND d.notification_created_at = n.created_at)";

    /**
     * X-03: the member e-mail channel as a whole (SRS-INT-6, SRS-INT-16; OD-148): whether its
     * capacity is set, a pause after rejected credentials, the tokens used and allowed in each
     * window, the connections held, and the outbox by status, failure class and bounces. Counts
     * only: no member, no address.
     */
    public record ChannelView(
            boolean configured,
            Instant pausedUntil,
            String pauseReason,
            Map<String, Long> windowUsed,
            Map<String, Integer> windowLimit,
            long connectionsHeld,
            Map<String, Long> byStatus,
            Map<String, Long> byFailureClass,
            long bounced) {}

    /** One office's e-mails of a document and period (constant SQL, bind parameters only). */
    private static final String UNIT_FROM =
            " FROM document_mail d JOIN notification n ON n.notification_id ="
                    + " d.notification_id AND n.created_at = d.notification_created_at JOIN"
                    + " employee e ON e.employee_id = d.employee_id WHERE d.org_unit_id = ?"
                    + " AND d.document_type = ? AND d.period_key = ?";

    /** The same, narrowed to one status when the bound status is not null. */
    private static final String UNIT_FILTERED =
            UNIT_FROM + " AND (? IS NULL OR n.delivery_status = ?)";

    /** The member's own e-mails. */
    private static final String MINE_FROM =
            " FROM document_mail d JOIN notification n ON n.notification_id ="
                    + " d.notification_id AND n.created_at = d.notification_created_at WHERE"
                    + " d.employee_id = ?";

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final AuditService audit;
    private final Clock clock;
    private final MailChannel channel;

    public DocumentMails(
            JdbcTemplate jdbc,
            Authorization policy,
            AuditService audit,
            Clock clock,
            MailChannel channel) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
        this.channel = channel;
    }

    /** X-03 VIEW: the channel's state and the outbox counts (no personal data). */
    @Transactional(readOnly = true)
    public ChannelView channel(AccessContext context) {
        policy.require(context, "X-03", "VIEW");
        Instant now = clock.instant();
        Optional<MailChannel.Config> c = channel.config(now);
        Map<String, Integer> limits = new LinkedHashMap<>();
        c.ifPresent(
                k -> {
                    for (MailChannel.Window w : MailChannel.Window.values()) {
                        if (k.limit(w) != null) {
                            limits.put(w.name(), k.limit(w));
                        }
                    }
                });
        Map<String, Object> pause =
                jdbc
                        .queryForList(
                                "SELECT paused_until, pause_reason FROM mail_channel_state WHERE"
                                        + " channel_code = ? AND paused_until > ?",
                                MailChannel.CHANNEL,
                                Timestamp.from(now))
                        .stream()
                        .findFirst()
                        .orElse(Map.of());
        Long held =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mail_send_slot WHERE channel_code = ? AND holder IS"
                                + " NOT NULL AND lease_until >= ?",
                        Long.class,
                        MailChannel.CHANNEL,
                        Timestamp.from(now));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.query(
                "SELECT n.delivery_status, COUNT(*) FROM document_mail d JOIN notification n ON"
                        + " n.notification_id = d.notification_id AND n.created_at ="
                        + " d.notification_created_at GROUP BY n.delivery_status ORDER BY"
                        + " n.delivery_status",
                (ResultSet rs) -> {
                    byStatus.put(rs.getString(1), rs.getLong(2));
                });
        Map<String, Long> byClass = new LinkedHashMap<>();
        jdbc.query(
                "SELECT failure_class, COUNT(*) FROM document_mail WHERE failure_class IS NOT NULL"
                        + " GROUP BY failure_class ORDER BY failure_class",
                (ResultSet rs) -> {
                    byClass.put(rs.getString(1), rs.getLong(2));
                });
        Long bounced =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM document_mail WHERE bounced_flag = 'Y'", Long.class);
        Timestamp until = (Timestamp) pause.get("PAUSED_UNTIL");
        return new ChannelView(
                c.isPresent(),
                until == null ? null : until.toInstant(),
                (String) pause.get("PAUSE_REASON"),
                c.map(k -> channel.usage(k, now)).orElse(Map.of()),
                Map.copyOf(limits),
                held == null ? 0 : held,
                Map.copyOf(byStatus),
                Map.copyOf(byClass),
                bounced == null ? 0 : bounced);
    }

    /** SRS-17-1 / O-01 l.3390: the month is locked; each member's payslip e-mail is queued. */
    @EventListener
    @Transactional
    public void on(CycleLocked locked) {
        // Millisecond precision: the second statement finds the first one's rows by this instant.
        Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
        String cycle = String.valueOf(locked.cycleInstanceId());
        jdbc.update(PAYSLIPS_RECORDED, cycle, now, locked.cycleInstanceId(), locked.payrollYymm());
        // Source key = the month: one payslip e-mail per member and locked period (O-01 l.3390),
        // also when two cycles of the month list the member; the cycle is the notification's key.
        jdbc.update(
                OUTBOX_OF_RECORDED,
                PAYSLIP,
                locked.payrollYymm(),
                locked.payrollYymm(),
                locked.orgUnitId(),
                now,
                PAYSLIP,
                "payroll_cycle_instance",
                cycle);
        audited(locked.lockedBy(), PAYSLIP, locked.orgUnitId(), locked.payrollYymm(), now, "C-02");
    }

    /** OD-148 / PENDING F16-5: Form 16 released; each certificate's e-mail is queued. */
    @EventListener
    @Transactional
    public void on(MemberDocumentsReleased released) {
        Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
        String period = null;
        for (MemberDocumentsReleased.Item item : released.items()) {
            period = item.periodKey();
            Long already =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM document_mail WHERE employee_id = ? AND"
                                    + " document_type = ? AND period_key = ? AND source_key = ?",
                            Long.class,
                            item.employeeId(),
                            released.documentType(),
                            item.periodKey(),
                            item.sourceKey());
            if (already != null && already > 0) {
                continue;
            }
            String email =
                    jdbc
                            .queryForList(
                                    "SELECT email_address FROM employee WHERE employee_id = ?",
                                    String.class,
                                    item.employeeId())
                            .stream()
                            // A member without an e-mail ID reads as a NULL row: findFirst() would
                            // throw.
                            .filter(java.util.Objects::nonNull)
                            .findFirst()
                            .orElse(null);
            boolean none = email == null || email.isBlank();
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(
                    connection -> {
                        PreparedStatement ps =
                                connection.prepareStatement(
                                        "INSERT INTO notification (recipient_employee_id, channel,"
                                                + " notification_type, related_entity_name,"
                                                + " related_entity_key, delivery_status,"
                                                + " delivery_detail, created_at) VALUES (?,"
                                                + " 'EMAIL', ?, ?, ?, ?, ?, ?)",
                                        new String[] {"notification_id"});
                        ps.setLong(1, item.employeeId());
                        ps.setString(2, released.documentType());
                        ps.setString(3, entityOf(released.documentType()));
                        ps.setString(4, item.sourceKey());
                        ps.setString(5, none ? "SUPPRESSED" : "QUEUED");
                        ps.setString(6, none ? NO_EMAIL : null);
                        ps.setTimestamp(7, now);
                        return ps;
                    },
                    key);
            long notification = java.util.Objects.requireNonNull(key.getKey()).longValue();
            jdbc.update(
                    "INSERT INTO document_mail (notification_id, notification_created_at,"
                            + " employee_id, document_type, period_key, source_key, org_unit_id,"
                            + " attempt_count, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)",
                    notification,
                    now,
                    item.employeeId(),
                    released.documentType(),
                    item.periodKey(),
                    item.sourceKey(),
                    released.orgUnitId(),
                    now);
        }
        if (period != null) {
            audited(
                    released.actor(),
                    released.documentType(),
                    released.orgUnitId(),
                    period,
                    now,
                    screenOf(released.documentType()));
        }
    }

    private static String entityOf(String documentType) {
        return FORM16.equals(documentType) ? "form16" : "payroll_cycle_instance";
    }

    /** R-AUD: what the lock or release queued, in its transaction (counts only, no member data). */
    private void audited(
            String actor, String type, long unit, String period, Timestamp at, String screen) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(
                "SELECT n.delivery_status, COUNT(*) FROM document_mail d JOIN notification n ON"
                        + " n.notification_id = d.notification_id AND n.created_at ="
                        + " d.notification_created_at WHERE d.created_at = ? AND d.document_type ="
                        + " ? AND d.org_unit_id = ? AND d.period_key = ? GROUP BY"
                        + " n.delivery_status ORDER BY n.delivery_status",
                (ResultSet rs) -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                },
                at,
                type,
                unit,
                period);
        if (counts.isEmpty()) {
            return;
        }
        audit.record(
                actor,
                AuditService.Entry.change(
                                "document_mail",
                                type + "|" + unit + "|" + period,
                                "delivery_status",
                                null,
                                counts.toString(),
                                "E-mails queued (OD-148)")
                        .module(screen));
    }

    /**
     * The Payroll Maker's list of one office's e-mails of a document and period (C-02 VIEW for the
     * payslip, C-10 VIEW for Form 16, at that office: another office is refused and logged), the
     * members without an e-mail ID among them, newest problems first.
     *
     * @param status QUEUED, SENT, FAILED or SUPPRESSED; null: all
     */
    @Transactional(readOnly = true)
    public UnitView unit(
            AccessContext context,
            String documentType,
            long orgUnitId,
            String periodKey,
            String status,
            PageSpec spec) {
        policy.requireAt(context, screenOf(documentType), "VIEW", orgUnitId);
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(
                "SELECT n.delivery_status, COUNT(*)"
                        + UNIT_FROM
                        + " GROUP BY n.delivery_status ORDER BY n.delivery_status",
                (ResultSet rs) -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                },
                orgUnitId,
                documentType,
                periodKey);
        Object[] filter = {orgUnitId, documentType, periodKey, status, status};
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + UNIT_FILTERED, Long.class, filter);
        List<Row> rows =
                jdbc.query(
                        "SELECT d.document_mail_id, e.emp_num, e.first_name, e.last_name,"
                                + " d.document_type, d.period_key, n.delivery_status,"
                                + " n.delivery_detail, d.attempt_count, d.bounced_flag,"
                                + " d.last_attempt_at, n.sent_at"
                                + UNIT_FILTERED
                                + " ORDER BY CASE n.delivery_status WHEN 'FAILED' THEN 0 WHEN"
                                + " 'SUPPRESSED' THEN 1 WHEN 'QUEUED' THEN 2 ELSE 3 END,"
                                + " e.emp_num, d.document_mail_id OFFSET ? ROWS FETCH NEXT ? ROWS"
                                + " ONLY",
                        (rs, i) ->
                                new Row(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        name(rs.getString(3), rs.getString(4)),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getString(8),
                                        rs.getInt(9),
                                        "Y".equals(rs.getString(10)),
                                        instant(rs, 11),
                                        instant(rs, 12)),
                        orgUnitId,
                        documentType,
                        periodKey,
                        status,
                        status,
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new UnitView(
                Map.copyOf(counts),
                new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total)));
    }

    /**
     * E-01 (SRS-17-12: the status stays visible in eSeva): the member's own e-mails, newest first.
     */
    @Transactional(readOnly = true)
    public Paged<Mine> mine(AccessContext context, PageSpec spec) {
        policy.require(context, "E-01", "SELF");
        if (context.employeeId() == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {"E-01", "SELF"}, List.of(), null);
        }
        long me = context.employeeId();
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + MINE_FROM, Long.class, me);
        List<Mine> rows =
                jdbc.query(
                        "SELECT d.document_type, d.period_key, n.delivery_status,"
                                + " n.delivery_detail, d.created_at, n.sent_at"
                                + MINE_FROM
                                + " ORDER BY d.created_at DESC, d.document_mail_id DESC OFFSET ?"
                                + " ROWS FETCH NEXT ? ROWS ONLY",
                        (rs, i) ->
                                new Mine(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        instant(rs, 5),
                                        instant(rs, 6)),
                        me,
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static String name(String first, String last) {
        return last == null || last.isBlank() ? first : first + " " + last;
    }
}
