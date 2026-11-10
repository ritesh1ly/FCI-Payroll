package in.gov.fci.payroll.eseva.orchestration;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The capacity of the member e-mail channel, held in the database so that every batch instance
 * shares it (OD-148; SRS-17-12, SRS-INT-16; PENDING MAIL-2, MAIL-4; extension tables V082_63):
 *
 * <ul>
 *   <li><b>Rate:</b> a token per e-mail in each configured window (minute, hour, day: {@code
 *       mail_channel_config}), taken by one atomic UPDATE ({@code used < limit}) in its own
 *       committed transaction; a full window answers false and the e-mail is deferred, never
 *       failed.
 *   <li><b>Connections:</b> at most {@code max_connections} senders at once, each holding a leased
 *       slot ({@code mail_send_slot}); a slot whose holder died is free again after the claim
 *       lease.
 *   <li><b>Pause:</b> after rejected credentials the channel pauses until a time ({@code
 *       mail_channel_state}), so no e-mail spends its retries on a configuration fault.
 * </ul>
 *
 * The limits are FCI IT's (the mail server's capacity is not in the SRS): without a configuration
 * row in force the channel sends nothing.
 */
@Component
public class MailChannel {

    /** The channel, the same code as its interface (V082_61). */
    public static final String CHANNEL = DocumentMailDispatch.INTERFACE;

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** What a window kind limits. */
    enum Window {
        MINUTE,
        HOUR,
        DAY
    }

    /** The channel's capacity settings in force. */
    public record Config(
            Integer perMinute,
            Integer perHour,
            Integer perDay,
            int maxConnections,
            int leaseSeconds,
            long maxAttachmentBytes,
            String oversizeAction,
            int jitterPct,
            int authPauseSeconds) {

        Integer limit(Window w) {
            return switch (w) {
                case MINUTE -> perMinute;
                case HOUR -> perHour;
                case DAY -> perDay;
            };
        }

        /** Whether an oversize message goes without its PDF (else it fails). */
        public boolean withoutAttachmentWhenTooLarge() {
            return "WITHOUT_ATTACHMENT".equals(oversizeAction);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate own;

    public MailChannel(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The settings in force on the IST day of {@code now}, if FCI IT has set them. */
    public Optional<Config> config(Instant now) {
        Date today = Date.valueOf(LocalDate.ofInstant(now, IST));
        return jdbc
                .query(
                        "SELECT per_minute, per_hour, per_day, max_connections,"
                                + " claim_lease_seconds, max_attachment_bytes, oversize_action,"
                                + " jitter_pct, auth_pause_seconds FROM mail_channel_config WHERE"
                                + " channel_code = ? AND effective_from <= ? AND (effective_to IS"
                                + " NULL OR effective_to >= ?) ORDER BY effective_from DESC",
                        (rs, i) ->
                                new Config(
                                        rs.getObject(1, Integer.class),
                                        rs.getObject(2, Integer.class),
                                        rs.getObject(3, Integer.class),
                                        rs.getInt(4),
                                        rs.getInt(5),
                                        rs.getLong(6),
                                        rs.getString(7),
                                        rs.getInt(8),
                                        rs.getInt(9)),
                        CHANNEL,
                        today,
                        today)
                .stream()
                .findFirst();
    }

    /** The start of the window of {@code kind} holding {@code now} (a day is the IST day). */
    static Instant windowStart(Window kind, Instant now) {
        return switch (kind) {
            case MINUTE -> now.truncatedTo(ChronoUnit.MINUTES);
            case HOUR -> now.truncatedTo(ChronoUnit.HOURS);
            case DAY -> LocalDate.ofInstant(now, IST).atStartOfDay(IST).toInstant();
        };
    }

    /**
     * Takes one token in every configured window, atomically and committed; false (nothing taken)
     * when any window is full.
     */
    public boolean takeToken(Config c, Instant now) {
        Boolean taken =
                own.execute(
                        tx -> {
                            for (Window w : Window.values()) {
                                Integer limit = c.limit(w);
                                if (limit != null && !take(w, limit, now)) {
                                    tx.setRollbackOnly();
                                    return false;
                                }
                            }
                            return true;
                        });
        return Boolean.TRUE.equals(taken);
    }

    private boolean take(Window w, int limit, Instant now) {
        Timestamp start = Timestamp.from(windowStart(w, now));
        ensureWindow(w, start);
        // A new window: the first token of it resets the count (only one instance can move it).
        int reset =
                jdbc.update(
                        "UPDATE mail_rate_window SET window_start = ?, used = 1 WHERE channel_code"
                                + " = ? AND window_kind = ? AND window_start < ?",
                        start,
                        CHANNEL,
                        w.name(),
                        start);
        if (reset == 1) {
            return true;
        }
        return jdbc.update(
                        "UPDATE mail_rate_window SET used = used + 1 WHERE channel_code = ? AND"
                                + " window_kind = ? AND window_start = ? AND used < ?",
                        CHANNEL,
                        w.name(),
                        start,
                        limit)
                == 1;
    }

    private void ensureWindow(Window w, Timestamp start) {
        try {
            jdbc.update(
                    "INSERT INTO mail_rate_window (channel_code, window_kind, window_start, used)"
                            + " SELECT ?, ?, ?, 0 FROM dual WHERE NOT EXISTS (SELECT 1 FROM"
                            + " mail_rate_window WHERE channel_code = ? AND window_kind = ?)",
                    CHANNEL,
                    w.name(),
                    start,
                    CHANNEL,
                    w.name());
        } catch (DuplicateKeyException another) {
            // Another instance created it at the same moment: use theirs.
        }
    }

    /**
     * Whether a token is likely free in every window (a read, no token taken), so a full window
     * stops the run before members are claimed and rendered for nothing.
     */
    public boolean hasCapacity(Config c, Instant now) {
        for (Window w : Window.values()) {
            Integer limit = c.limit(w);
            if (limit == null) {
                continue;
            }
            Long used =
                    jdbc
                            .queryForList(
                                    "SELECT used FROM mail_rate_window WHERE channel_code = ? AND"
                                            + " window_kind = ? AND window_start = ?",
                                    Long.class,
                                    CHANNEL,
                                    w.name(),
                                    Timestamp.from(windowStart(w, now)))
                            .stream()
                            .findFirst()
                            .orElse(0L);
            if (used >= limit) {
                return false;
            }
        }
        return true;
    }

    /** The tokens used in each configured window now (for the channel status). */
    public Map<String, Long> usage(Config c, Instant now) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Window w : Window.values()) {
            if (c.limit(w) == null) {
                continue;
            }
            out.put(
                    w.name(),
                    jdbc
                            .queryForList(
                                    "SELECT used FROM mail_rate_window WHERE channel_code = ? AND"
                                            + " window_kind = ? AND window_start = ?",
                                    Long.class,
                                    CHANNEL,
                                    w.name(),
                                    Timestamp.from(windowStart(w, now)))
                            .stream()
                            .findFirst()
                            .orElse(0L));
        }
        return out;
    }

    /**
     * Leases up to {@code wanted} connection slots of the {@code max_connections} for {@code
     * holder}, each until the claim lease ends; the slot numbers taken (none: every slot is busy).
     */
    public List<Integer> acquireSlots(String holder, int wanted, Config c, Instant now) {
        List<Integer> taken = new ArrayList<>();
        Timestamp until = Timestamp.from(now.plusSeconds(c.leaseSeconds()));
        for (int slot = 1; slot <= c.maxConnections() && taken.size() < wanted; slot++) {
            int n = slot;
            Integer got =
                    own.execute(
                            tx -> {
                                try {
                                    jdbc.update(
                                            "INSERT INTO mail_send_slot (channel_code, slot_no)"
                                                    + " SELECT ?, ? FROM dual WHERE NOT EXISTS"
                                                    + " (SELECT 1 FROM mail_send_slot WHERE"
                                                    + " channel_code = ? AND slot_no = ?)",
                                            CHANNEL,
                                            n,
                                            CHANNEL,
                                            n);
                                } catch (DuplicateKeyException another) {
                                    // created by another instance at the same moment
                                }
                                return jdbc.update(
                                        "UPDATE mail_send_slot SET holder = ?, lease_until = ?"
                                                + " WHERE channel_code = ? AND slot_no = ? AND"
                                                + " (holder IS NULL OR lease_until < ?)",
                                        holder,
                                        until,
                                        CHANNEL,
                                        n,
                                        Timestamp.from(now));
                            });
            if (got != null && got == 1) {
                taken.add(n);
            }
        }
        return taken;
    }

    /** Frees the slots of {@code holder}. */
    public void releaseSlots(String holder) {
        own.executeWithoutResult(
                tx ->
                        jdbc.update(
                                "UPDATE mail_send_slot SET holder = NULL, lease_until = NULL WHERE"
                                        + " channel_code = ? AND holder = ?",
                                CHANNEL,
                                holder));
    }

    /** The end of a pause after rejected credentials, if the channel is paused at {@code now}. */
    public Optional<Instant> pausedUntil(Instant now) {
        return jdbc
                .queryForList(
                        "SELECT paused_until FROM mail_channel_state WHERE channel_code = ? AND"
                                + " paused_until > ?",
                        Timestamp.class,
                        CHANNEL,
                        Timestamp.from(now))
                .stream()
                .findFirst()
                .map(Timestamp::toInstant);
    }

    /** Pauses the channel until {@code until} for {@code reason} (identifiers only). */
    public void pause(Instant until, String reason, Instant now) {
        own.executeWithoutResult(
                tx -> {
                    int updated =
                            jdbc.update(
                                    "UPDATE mail_channel_state SET paused_until = ?, pause_reason"
                                            + " = ?, changed_at = ? WHERE channel_code = ?",
                                    Timestamp.from(until),
                                    reason,
                                    Timestamp.from(now),
                                    CHANNEL);
                    if (updated == 0) {
                        jdbc.update(
                                "INSERT INTO mail_channel_state (channel_code, paused_until,"
                                        + " pause_reason, changed_at) VALUES (?, ?, ?, ?)",
                                CHANNEL,
                                Timestamp.from(until),
                                reason,
                                Timestamp.from(now));
                    }
                });
    }
}
