package in.gov.fci.payroll.eseva.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.eseva.business.DocumentMailRules;
import in.gov.fci.payroll.eseva.business.EsevaRules;
import in.gov.fci.payroll.integration.api.InterfaceBreakers;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.PdfRenderGate;
import in.gov.fci.payroll.platform.i18n.MessageBundles;
import in.gov.fci.payroll.platform.maintenance.Housekeeping;
import in.gov.fci.payroll.platform.notification.DocumentFailedException;
import in.gov.fci.payroll.platform.notification.DocumentNotReadyException;
import in.gov.fci.payroll.platform.notification.MailAttachment;
import in.gov.fci.payroll.platform.notification.MailFailures;
import in.gov.fci.payroll.platform.notification.MemberDocuments;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import in.gov.fci.payroll.platform.notification.PdfProtection;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the queued member documents (OD-148; SRS-17-1 l.3250, SRS-17-12 l.3260, SRS l.3763,
 * SRS-INT-6, SRS-INT-16; HLD l.255 notification adapter; PENDING MAIL-1, MAIL-2, MAIL-4) on each
 * housekeeping run of the batch instances, so mail never slows the online instance, the lock or the
 * release.
 *
 * <ul>
 *   <li><b>Settings, not code (R-INT):</b> retries, back-off and breaker are the MEMBER_EMAIL
 *       interface settings (X-03); the server's capacity (rate per minute / hour / day, concurrent
 *       connections, claim lease, attachment cap, jitter, pause after rejected credentials) is
 *       {@code mail_channel_config} ({@link MailChannel}). Without either nothing is sent and the
 *       e-mails stay queued.
 *   <li><b>Capacity:</b> each send takes a token of every window, shared by all instances in the
 *       database; a full window ends the run and the rest wait (deferred, never failed). At most
 *       {@code max_connections} senders at once, {@code parallelism} per instance.
 *   <li><b>Once per e-mail:</b> each e-mail is claimed with a lease before the server is called; an
 *       e-mail whose sender died is claimable again after the lease; every attempt carries the
 *       e-mail's own Message-ID, so a receiving server can drop a repeat.
 *   <li><b>Failure classes ({@link MailFailures}):</b> transient (4xx, time-out, refused, TLS):
 *       retried with back-off and jitter, counted on the breaker, which pauses all sending while it
 *       is open; recipient (550 / 551 / 553 / 554): failed and bounced at once; message too large
 *       (552, or above the attachment cap): sent again without the PDF or failed, by the setting;
 *       rejected credentials: the channel pauses, alerted on X-03, no e-mail spends its attempt; a
 *       document that cannot be produced: failed with the reason, the queue goes on.
 *   <li><b>Content:</b> the member's current registered address (SRS l.3763: HRMS-sourced, a change
 *       applies automatically), checked before sending; the wording of FCI's template, else the
 *       message keys, in the member's language; the PDF the member downloads, rendered now by its
 *       module, attached and password-protected per MAIL-1. A payslip whose payment is held is kept
 *       queued and sent once the hold ends.
 * </ul>
 */
@Component
public class DocumentMailDispatch implements Housekeeping {

    /** DDL {@code interface_registry.interface_code} of the member e-mail channel (V082_61). */
    public static final String INTERFACE = "MEMBER_EMAIL";

    private static final Logger LOG = LoggerFactory.getLogger(DocumentMailDispatch.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The mail settings of MAIL-1 and the run's size (technical settings). */
    public record Options(
            boolean enabled,
            int batch,
            boolean attachment,
            boolean passwordEnabled,
            String passwordRule,
            int parallelism,
            Duration heldRecheck,
            Duration notReadyRecheck) {

        public Options(
                boolean enabled,
                int batch,
                boolean attachment,
                boolean passwordEnabled,
                String passwordRule) {
            this(
                    enabled,
                    batch,
                    attachment,
                    passwordEnabled,
                    passwordRule,
                    1,
                    Duration.ofHours(24),
                    Duration.ofMinutes(15));
        }
    }

    /** The outcome of one e-mail. */
    enum Outcome {
        SENT,
        RETRY,
        FAILED,
        SUPPRESSED,
        DEFERRED,
        SKIPPED,
        QUOTA,
        AUTH
    }

    /** One e-mail due, as read from the outbox. */
    record Due(
            long id,
            long notification,
            Timestamp created,
            long employeeId,
            String type,
            String period,
            String source,
            String related,
            int attempts,
            boolean withoutAttachment,
            String messageId) {}

    private final JdbcTemplate jdbc;
    private final NotificationSender sender;
    private final InterfaceSettings settings;
    private final InterfaceBreakers breakers;
    private final MailChannel channel;
    private final AuditService audit;
    private final MessageBundles messages;
    private final Map<String, MemberDocuments> documents;
    private final TransactionTemplate own;
    private final Options options;
    private final DoubleSupplier random;
    private final String instance;

    @Autowired
    public DocumentMailDispatch(
            JdbcTemplate jdbc,
            NotificationSender sender,
            InterfaceSettings settings,
            InterfaceBreakers breakers,
            MailChannel channel,
            AuditService audit,
            MessageBundles messages,
            List<MemberDocuments> documents,
            PlatformTransactionManager transactions,
            @Value("${fci.api.document-mail.enabled:true}") boolean enabled,
            @Value("${fci.api.document-mail.batch:200}") int batch,
            @Value("${fci.api.document-mail.attachment:true}") boolean attachment,
            @Value("${fci.api.document-mail.password.enabled:false}") boolean passwordEnabled,
            @Value("${fci.api.document-mail.password.rule:}") String passwordRule,
            @Value("${fci.api.document-mail.parallelism:1}") int parallelism,
            @Value("${fci.api.document-mail.held-recheck:PT24H}") Duration heldRecheck,
            @Value("${fci.api.document-mail.not-ready-recheck:PT15M}") Duration notReadyRecheck) {
        this(
                jdbc,
                sender,
                settings,
                breakers,
                channel,
                audit,
                messages,
                documents,
                transactions,
                new Options(
                        enabled,
                        batch,
                        attachment,
                        passwordEnabled,
                        passwordRule,
                        parallelism,
                        heldRecheck,
                        notReadyRecheck),
                new SecureRandom()::nextDouble);
    }

    DocumentMailDispatch(
            JdbcTemplate jdbc,
            NotificationSender sender,
            InterfaceSettings settings,
            InterfaceBreakers breakers,
            MailChannel channel,
            AuditService audit,
            MessageBundles messages,
            List<MemberDocuments> documents,
            PlatformTransactionManager transactions,
            Options options,
            DoubleSupplier random) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.settings = settings;
        this.breakers = breakers;
        this.channel = channel;
        this.audit = audit;
        this.messages = messages;
        this.documents =
                documents.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        MemberDocuments::documentType, Function.identity()));
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.options = options;
        this.random = random;
        this.instance = "batch-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public String name() {
        return "documentEmails";
    }

    @Override
    public int run(Instant now) {
        if (!options.enabled()) {
            return 0;
        }
        Optional<InterfaceSettings.Settings> configured = settings.inForce(INTERFACE);
        Optional<MailChannel.Config> capacity = channel.config(now);
        if (configured.isEmpty() || capacity.isEmpty()) {
            LOG.info("event=DOCUMENT_MAIL_NOT_CONFIGURED interface={}", INTERFACE);
            return 0;
        }
        if (options.passwordEnabled() && !DocumentMailRules.validRule(options.passwordRule())) {
            // MAIL-1: protection is on but its rule is missing or unusable: nothing goes out
            // unprotected; the e-mails stay queued until the setting is corrected.
            LOG.warn("event=DOCUMENT_MAIL_PASSWORD_RULE_NOT_CONFIGURED");
            return 0;
        }
        Optional<Instant> paused = channel.pausedUntil(now);
        if (paused.isPresent()) {
            LOG.info("event=DOCUMENT_MAIL_PAUSED until={}", paused.get());
            return 0;
        }
        if (!breakers.admit(INTERFACE)) {
            LOG.info("event=DOCUMENT_MAIL_BREAKER_OPEN interface={}", INTERFACE);
            return 0;
        }
        MailChannel.Config c = capacity.get();
        if (!channel.hasCapacity(c, now)) {
            LOG.info("event=DOCUMENT_MAIL_QUOTA_REACHED");
            return 0;
        }
        String holder = instance + "-" + UUID.randomUUID().toString().substring(0, 8);
        List<Integer> slots =
                channel.acquireSlots(holder, Math.max(1, options.parallelism()), c, now);
        if (slots.isEmpty()) {
            LOG.info("event=DOCUMENT_MAIL_NO_FREE_CONNECTION max={}", c.maxConnections());
            return 0;
        }
        try {
            return drain(due(now), configured.get(), c, now, holder, slots.size());
        } finally {
            channel.releaseSlots(holder);
        }
    }

    /** The e-mails due now, oldest first, one batch (ids and keys only: memory stays bounded). */
    private ConcurrentLinkedQueue<Due> due(Instant now) {
        return new ConcurrentLinkedQueue<>(
                jdbc.query(
                        "SELECT d.document_mail_id, d.notification_id, d.notification_created_at,"
                                + " d.employee_id, d.document_type, d.period_key, d.source_key,"
                                + " n.related_entity_key, d.attempt_count, d.without_attachment,"
                                + " d.message_id FROM document_mail d JOIN notification n ON"
                                + " n.notification_id = d.notification_id AND n.created_at ="
                                + " d.notification_created_at WHERE n.delivery_status = 'QUEUED'"
                                + " AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= ?) AND"
                                + " (d.claim_until IS NULL OR d.claim_until < ?) ORDER BY"
                                + " d.document_mail_id FETCH FIRST ? ROWS ONLY",
                        (rs, i) ->
                                new Due(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getTimestamp(3),
                                        rs.getLong(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getString(8),
                                        rs.getInt(9),
                                        "Y".equals(rs.getString(10)),
                                        rs.getString(11)),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        Math.max(1, options.batch())));
    }

    /** {@code workers} senders take e-mails from the queue until it is empty or a stop applies. */
    private int drain(
            ConcurrentLinkedQueue<Due> queue,
            InterfaceSettings.Settings s,
            MailChannel.Config c,
            Instant now,
            String holder,
            int workers) {
        AtomicInteger sent = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean healthy = new AtomicBoolean();
        Runnable worker =
                () -> {
                    Due item;
                    while (!stop.get() && (item = queue.poll()) != null) {
                        Outcome outcome = deliverSafely(item, s, c, now, holder);
                        switch (outcome) {
                            case SENT -> {
                                sent.incrementAndGet();
                                if (healthy.compareAndSet(false, true)) {
                                    breakers.succeeded(INTERFACE);
                                }
                            }
                            case QUOTA, AUTH -> stop.set(true);
                            case RETRY -> {
                                healthy.set(false);
                                if (!breakers.admit(INTERFACE)) {
                                    // SRS-INT-6: the server is down; the rest wait for the probe.
                                    stop.set(true);
                                }
                            }
                            default -> {
                                // FAILED, SUPPRESSED, DEFERRED, SKIPPED: the queue goes on
                            }
                        }
                    }
                };
        if (workers == 1) {
            worker.run();
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            for (int i = 0; i < workers; i++) {
                pool.execute(worker);
            }
            pool.shutdown();
            try {
                if (!pool.awaitTermination(c.leaseSeconds(), TimeUnit.SECONDS)) {
                    stop.set(true);
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop.set(true);
                pool.shutdownNow();
            }
        }
        return sent.get();
    }

    /** One e-mail; never throws (a failure of the outbox itself leaves it to the lease). */
    Outcome deliverSafely(
            Due item, InterfaceSettings.Settings s, MailChannel.Config c, Instant now, String by) {
        try {
            return deliver(item, s, c, now, by);
        } catch (RuntimeException outbox) {
            LOG.warn(
                    "event=DOCUMENT_MAIL_OUTBOX_ERROR id={} reason={}",
                    item.id(),
                    outbox.getClass().getSimpleName());
            return Outcome.SKIPPED;
        }
    }

    /** One e-mail: claimed, checked, rendered, sent within the capacity and recorded. */
    Outcome deliver(
            Due item, InterfaceSettings.Settings s, MailChannel.Config c, Instant now, String by) {
        int attempt = item.attempts() + 1;
        String messageId =
                item.messageId() != null
                        ? item.messageId()
                        : "<document-mail-" + item.id() + "@fcilekha.fci.gov.in>";
        if (!claim(item, attempt, now, now.plusSeconds(c.leaseSeconds()), by, messageId)) {
            return Outcome.SKIPPED;
        }
        Map<String, Object> m = member(item.employeeId());
        String email = m == null ? null : (String) m.get("EMAIL_ADDRESS");
        if (email == null || email.isBlank()) {
            return finish(item, Outcome.SUPPRESSED, DocumentMails.NO_EMAIL, null, false);
        }
        if (!DocumentMailRules.validAddress(email.strip())) {
            return finish(item, Outcome.FAILED, "Invalid e-mail address", "ADDRESS", false);
        }
        if (DocumentMails.PAYSLIP.equals(item.type()) && held(item)) {
            // No payslip while the payment is held (O-01 register: Held): kept queued, checked
            // again later, sent once the hold ends; the attempt is not spent.
            return defer(item, now.plus(options.heldRecheck()), DocumentMails.HELD);
        }
        Optional<MemberDocuments.Rendered> rendered;
        try {
            MemberDocuments source = documents.get(item.type());
            rendered =
                    source == null
                            ? Optional.empty()
                            : source.render(item.employeeId(), item.period(), item.source());
        } catch (PdfRenderGate.Busy busy) {
            // OD-144 bulkhead: every render slot of this instance is taken; the e-mail waits (its
            // attempt not spent) and the run ends, so mail never crowds out the renders.
            defer(item, now.plusSeconds(busy.retryAfterSeconds()), "Waiting for a render slot");
            return Outcome.QUOTA;
        } catch (DocumentNotReadyException notYet) {
            // SRS-NF-5 / NF-7: the batch has not stored the PDF yet: wait, the attempt not spent.
            return defer(item, now.plus(options.notReadyRecheck()), "Document not generated yet");
        } catch (DocumentFailedException generation) {
            return finish(
                    item,
                    Outcome.FAILED,
                    DocumentMailRules.detail("Document generation failed: ", generation),
                    "RENDER",
                    false);
        } catch (FciException refused) {
            // A business refusal (e.g. the certificate's form is not notified) never succeeds on
            // a retry: failed with its reason, the queue goes on.
            return finish(
                    item,
                    Outcome.FAILED,
                    DocumentMailRules.detail("Document not produced: ", refused),
                    "RENDER",
                    false);
        } catch (RuntimeException broken) {
            // A poison e-mail (its PDF cannot be rendered): failed with the reason, the queue goes
            // on; the Payroll Maker sees it, the member still downloads on E-01.
            return finish(
                    item,
                    Outcome.FAILED,
                    DocumentMailRules.detail("Document not produced: ", broken),
                    "RENDER",
                    false);
        }
        if (rendered.isEmpty()) {
            return finish(item, Outcome.SUPPRESSED, "Document not available", null, false);
        }
        boolean attach = options.attachment() && !item.withoutAttachment();
        MailAttachment file = attach ? rendered.get().attachment() : null;
        if (file != null && options.passwordEnabled()) {
            Optional<String> password = password(item.employeeId());
            if (password.isEmpty()) {
                return finish(
                        item,
                        Outcome.FAILED,
                        "Password data missing for the configured rule",
                        "PERMANENT",
                        false);
            }
            file =
                    MailAttachment.pdf(
                            file.fileName(), PdfProtection.protect(file.content(), password.get()));
        }
        if (file != null && file.size() > c.maxAttachmentBytes()) {
            if (!c.withoutAttachmentWhenTooLarge()) {
                return finish(
                        item, Outcome.FAILED, "Attachment above the size limit", "SIZE", false);
            }
            file = null;
        }
        if (!channel.takeToken(c, now)) {
            unclaim(item, by);
            return Outcome.QUOTA;
        }
        String name = DocumentMails.name((String) m.get("FIRST_NAME"), (String) m.get("LAST_NAME"));
        String locale = "hi".equals(m.get("LOCALE")) ? "hi" : "en";
        Map<String, String> values = new HashMap<>(rendered.get().values());
        values.put("name", name == null ? "" : name);
        String[] wording = wording(item.type(), locale, file != null, now);
        try {
            sender.sendDocument(
                    email.strip(),
                    name,
                    EsevaRules.render(wording[0], values),
                    EsevaRules.render(wording[1], values),
                    file,
                    messageId);
        } catch (RuntimeException failed) {
            return failed(item, s, c, now, attempt, failed, file != null, by);
        }
        MailAttachment attached = file;
        own.executeWithoutResult(
                tx -> {
                    jdbc.update(
                            "UPDATE notification SET delivery_status = 'SENT', sent_at = ?,"
                                    + " delivery_detail = ? WHERE notification_id = ? AND"
                                    + " created_at = ?",
                            Timestamp.from(now),
                            attached == null && attach ? "Sent without the PDF (size limit)" : null,
                            item.notification(),
                            item.created());
                    jdbc.update(
                            "UPDATE document_mail SET next_attempt_at = NULL, claimed_by = NULL,"
                                    + " claim_until = NULL, failure_class = NULL, attachment_name"
                                    + " = ?, attachment_bytes = ? WHERE document_mail_id = ?",
                            attached == null ? null : attached.fileName(),
                            attached == null ? null : attached.size(),
                            item.id());
                });
        return Outcome.SENT;
    }

    /** The next step after the server refused the e-mail, by the failure's class. */
    private Outcome failed(
            Due item,
            InterfaceSettings.Settings s,
            MailChannel.Config c,
            Instant now,
            int attempt,
            RuntimeException failed,
            boolean attached,
            String by) {
        MailFailures.Failure f = MailFailures.classify(failed);
        LOG.warn(
                "event=DOCUMENT_MAIL_FAILED id={} attempt={} class={} code={}",
                item.id(),
                attempt,
                f.kind(),
                f.code());
        return switch (f.kind()) {
            case RECIPIENT -> {
                own.executeWithoutResult(
                        tx ->
                                jdbc.update(
                                        "UPDATE document_mail SET bounced_flag = 'Y' WHERE"
                                                + " document_mail_id = ?",
                                        item.id()));
                yield finish(
                        item,
                        Outcome.FAILED,
                        DocumentMailRules.detail("Bounced (" + f.code() + "): ", failed),
                        "RECIPIENT",
                        true);
            }
            case PERMANENT ->
                    finish(
                            item,
                            Outcome.FAILED,
                            DocumentMailRules.detail("Refused (" + f.code() + "): ", failed),
                            "PERMANENT",
                            false);
            case SIZE -> {
                if (attached && c.withoutAttachmentWhenTooLarge()) {
                    // 552: the next attempt goes at once, without the PDF (MAIL-4).
                    own.executeWithoutResult(
                            tx -> {
                                jdbc.update(
                                        "UPDATE document_mail SET without_attachment = 'Y',"
                                                + " next_attempt_at = ?, claimed_by = NULL,"
                                                + " claim_until = NULL, failure_class = 'SIZE'"
                                                + " WHERE document_mail_id = ?",
                                        Timestamp.from(now),
                                        item.id());
                                note(item, "Too large (552): sent next without the PDF");
                            });
                    yield Outcome.DEFERRED;
                }
                yield finish(
                        item,
                        Outcome.FAILED,
                        DocumentMailRules.detail("Too large (" + f.code() + "): ", failed),
                        "SIZE",
                        false);
            }
            case AUTH -> {
                // A configuration fault of the channel: no e-mail spends its attempt; the channel
                // pauses, X-03 shows the failure, the probe after the pause resumes it.
                unclaim(item, by);
                Instant until = now.plusSeconds(c.authPauseSeconds());
                channel.pause(until, "Credentials rejected (" + f.code() + ")", now);
                breakers.failed(INTERFACE, "Mail server rejected the credentials");
                own.executeWithoutResult(
                        tx ->
                                audit.record(
                                        "SYSTEM",
                                        AuditService.Entry.change(
                                                        "mail_channel_state",
                                                        MailChannel.CHANNEL,
                                                        "paused_until",
                                                        null,
                                                        until.toString(),
                                                        "Mail server rejected the credentials")
                                                .module("X-03")));
                yield Outcome.AUTH;
            }
            case TRANSIENT -> {
                breakers.failed(
                        INTERFACE,
                        "Mail server unavailable ("
                                + (f.code() == 0 ? "no reply" : f.code())
                                + ")");
                yield retryOrFail(item, s, c, now, attempt, failed, "TRANSIENT", true);
            }
        };
    }

    /** Retried after the back-off with jitter, or failed after the last retry. */
    private Outcome retryOrFail(
            Due item,
            InterfaceSettings.Settings s,
            MailChannel.Config c,
            Instant now,
            int attempt,
            RuntimeException failed,
            String failureClass,
            boolean server) {
        if (DocumentMailRules.givenUp(attempt, s.retryCount())) {
            finish(
                    item,
                    Outcome.FAILED,
                    DocumentMailRules.detail("Failed after " + attempt + " attempt(s): ", failed),
                    failureClass,
                    false);
            return server ? Outcome.RETRY : Outcome.FAILED;
        }
        Instant next =
                now.plusSeconds(
                        DocumentMailRules.retryDelaySeconds(
                                s.backoffStrategy(),
                                s.backoffSeconds(),
                                attempt,
                                c.jitterPct(),
                                random.getAsDouble()));
        String detail =
                DocumentMailRules.detail(
                        "Attempt " + attempt + " failed, next after " + next + ": ", failed);
        own.executeWithoutResult(
                tx -> {
                    jdbc.update(
                            "UPDATE document_mail SET next_attempt_at = ?, claimed_by = NULL,"
                                    + " claim_until = NULL, failure_class = ? WHERE"
                                    + " document_mail_id = ?",
                            Timestamp.from(next),
                            failureClass,
                            item.id());
                    note(item, detail);
                });
        return server ? Outcome.RETRY : Outcome.FAILED;
    }

    /**
     * Counts the attempt and leases the e-mail to this sender (committed) before the server is
     * called; false when another sender holds it or took it first.
     */
    private boolean claim(
            Due item, int attempt, Instant now, Instant until, String by, String messageId) {
        Integer claimed =
                own.execute(
                        tx ->
                                jdbc.update(
                                        "UPDATE document_mail SET attempt_count = ?,"
                                                + " last_attempt_at = ?, claimed_by = ?,"
                                                + " claim_until = ?, message_id = ? WHERE"
                                                + " document_mail_id = ? AND attempt_count = ? AND"
                                                + " (claim_until IS NULL OR claim_until < ?)",
                                        attempt,
                                        Timestamp.from(now),
                                        by,
                                        Timestamp.from(until),
                                        messageId,
                                        item.id(),
                                        item.attempts(),
                                        Timestamp.from(now)));
        return claimed != null && claimed == 1;
    }

    /**
     * Gives the e-mail back untouched (its attempt not spent): capacity or channel, not its own.
     */
    private void unclaim(Due item, String by) {
        own.executeWithoutResult(
                tx ->
                        jdbc.update(
                                "UPDATE document_mail SET attempt_count = ?, claimed_by = NULL,"
                                        + " claim_until = NULL WHERE document_mail_id = ? AND"
                                        + " claimed_by = ?",
                                item.attempts(),
                                item.id(),
                                by));
    }

    /** Kept queued until {@code until}, the attempt not spent. */
    private Outcome defer(Due item, Instant until, String detail) {
        own.executeWithoutResult(
                tx -> {
                    jdbc.update(
                            "UPDATE document_mail SET attempt_count = ?, next_attempt_at = ?,"
                                    + " claimed_by = NULL, claim_until = NULL WHERE"
                                    + " document_mail_id = ?",
                            item.attempts(),
                            Timestamp.from(until),
                            item.id());
                    note(item, detail);
                });
        return Outcome.DEFERRED;
    }

    private void note(Due item, String detail) {
        jdbc.update(
                "UPDATE notification SET delivery_detail = ? WHERE notification_id = ? AND"
                        + " created_at = ?",
                detail,
                item.notification(),
                item.created());
    }

    /** A final status (FAILED or SUPPRESSED) with its reason and class. */
    private Outcome finish(
            Due item, Outcome outcome, String detail, String failureClass, boolean bounced) {
        own.executeWithoutResult(
                tx -> {
                    jdbc.update(
                            "UPDATE notification SET delivery_status = ?, delivery_detail = ? WHERE"
                                    + " notification_id = ? AND created_at = ?",
                            outcome.name(),
                            detail,
                            item.notification(),
                            item.created());
                    jdbc.update(
                            "UPDATE document_mail SET next_attempt_at = NULL, claimed_by = NULL,"
                                    + " claim_until = NULL, failure_class = ? WHERE"
                                    + " document_mail_id = ?",
                            failureClass,
                            item.id());
                });
        LOG.info(
                "event=DOCUMENT_MAIL_FINAL id={} status={} class={} bounced={}",
                item.id(),
                outcome,
                failureClass,
                bounced);
        return outcome;
    }

    /** The member's current address, name and language (SRS l.3763: the value at sending). */
    private Map<String, Object> member(long employeeId) {
        return jdbc
                .queryForList(
                        "SELECT e.email_address, e.first_name, e.last_name, (SELECT"
                                + " MAX(p.preferred_locale) FROM app_user u JOIN"
                                + " app_user_preference p ON p.user_id = u.user_id WHERE"
                                + " u.employee_id = e.employee_id) AS locale FROM employee e WHERE"
                                + " e.employee_id = ?",
                        employeeId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether the payslip's payment is held now (as the O-01 register: an open PAYMENT_HELD of the
     * cycle, a negative net, or a suppressed payslip).
     */
    private boolean held(Due item) {
        long cycle;
        try {
            cycle = Long.parseLong(item.related());
        } catch (NumberFormatException e) {
            return false;
        }
        Long held =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_result r WHERE r.cycle_instance_id = ? AND"
                            + " r.employee_id = ? AND r.run_type = 'ACTUAL' AND r.result_status ="
                            + " 'CURRENT' AND (r.net_pay < 0 OR EXISTS (SELECT 1 FROM"
                            + " payroll_exception x WHERE x.employee_id = r.employee_id AND"
                            + " x.cycle_instance_id = r.cycle_instance_id AND x.exception_code ="
                            + " 'PAYMENT_HELD' AND x.resolved_at IS NULL) OR EXISTS (SELECT 1 FROM"
                            + " payslip s WHERE s.cycle_instance_id = r.cycle_instance_id AND"
                            + " s.employee_id = r.employee_id AND s.suppressed_flag = 'Y'))",
                        Long.class,
                        cycle,
                        item.employeeId());
        return held != null && held > 0;
    }

    /** MAIL-1: the member's password by the configured rule (read only when protection is on). */
    private Optional<String> password(long employeeId) {
        Map<String, Object> e =
                jdbc.queryForMap(
                        "SELECT pan_number, birth_date, emp_num FROM employee WHERE employee_id ="
                                + " ?",
                        employeeId);
        Object birth = e.get("BIRTH_DATE");
        LocalDate birthDate =
                birth instanceof Date d
                        ? d.toLocalDate()
                        : birth instanceof Timestamp t ? t.toLocalDateTime().toLocalDate() : null;
        return DocumentMailRules.password(
                options.passwordRule(),
                (String) e.get("PAN_NUMBER"),
                birthDate,
                (String) e.get("EMP_NUM"));
    }

    /**
     * The subject and body: FCI's template in force today when configured (SRS O-8: FCI approves
     * every e-mail's content; wording held as configuration), else the message keys of the member's
     * language.
     */
    private String[] wording(String type, String locale, boolean attached, Instant now) {
        Date today = Date.valueOf(LocalDate.ofInstant(now, IST));
        Optional<Map<String, Object>> template =
                jdbc
                        .queryForList(
                                "SELECT subject_en, body_en, subject_hi, body_hi FROM"
                                        + " notification_template WHERE template_code = ? AND"
                                        + " channel = 'EMAIL' AND effective_from <= ? AND"
                                        + " (effective_to IS NULL OR effective_to >= ?) ORDER BY"
                                        + " effective_from DESC",
                                type,
                                today,
                                today)
                        .stream()
                        .findFirst();
        if (template.isPresent()) {
            Map<String, Object> t = template.get();
            boolean hindi =
                    "hi".equals(locale) && t.get("SUBJECT_HI") != null && t.get("BODY_HI") != null;
            return new String[] {
                (String) t.get(hindi ? "SUBJECT_HI" : "SUBJECT_EN"),
                (String) t.get(hindi ? "BODY_HI" : "BODY_EN")
            };
        }
        Map<String, String> bundle = messages.messages(locale);
        String key = "mail." + type.toLowerCase(java.util.Locale.ROOT);
        return new String[] {
            bundle.get(key + ".subject"),
            bundle.get(key + (attached ? ".body" : ".bodyNoAttachment"))
        };
    }
}
