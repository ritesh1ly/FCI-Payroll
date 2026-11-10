package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The exclusive lease on a payroll cycle instance (SRS-15-28, BR-CYC-015; DDL {@code cycle_lease},
 * {@code cycle_lease_log}): a computation, freeze, un-freeze, undo, return or lock takes it, and
 * every other action on the same instance is refused naming the holder and since when. Instances of
 * different units never block one another: the lease is per instance.
 *
 * <p>The lease row is a transient lock token (DDL comment: acquire = INSERT, release = DELETE in
 * the transaction that completes the action); its history is the append-only {@code
 * cycle_lease_log}. A lease whose heartbeat is older than {@code fci.cycle.lease.expire-after} is
 * taken over by the next acquirer and logged EXPIRED (plan 05 D-7); a lease that will not expire (a
 * stuck run that still beats) is force-released through the X-01 route with OTP ({@link
 * #forceRelease}). Every method joins the caller's transaction.
 */
@Component
public class CycleLeases {

    /** The DDL's {@code lease_action} values. */
    public enum Action {
        COMPUTE,
        FREEZE,
        UNFREEZE,
        UNDO,
        LOCK,
        RETURN,
        CLOSE
    }

    /**
     * The lease as the screen shows it.
     *
     * @param expired the heartbeat is older than the expiry: the next acquirer takes it over
     */
    public record Lease(
            long cycleId,
            String action,
            String heldBy,
            Instant acquiredAt,
            Instant heartbeatAt,
            Long runId,
            boolean expired) {}

    private static final DateTimeFormatter SINCE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'IST'")
                    .withZone(ZoneId.of("Asia/Kolkata"));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration expireAfter;

    public CycleLeases(
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${fci.cycle.lease.expire-after:5m}") Duration expireAfter) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.expireAfter = expireAfter;
    }

    /** The lease held on the instance, if any (expired or not). */
    public Optional<Lease> current(long cycleId) {
        List<Lease> rows =
                jdbc.query(
                        "SELECT lease_action, held_by, acquired_at, heartbeat_at, run_id FROM"
                                + " cycle_lease WHERE cycle_instance_id = ?",
                        (rs, i) -> {
                            Instant beat = rs.getTimestamp(4).toInstant();
                            return new Lease(
                                    cycleId,
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getTimestamp(3).toInstant(),
                                    beat,
                                    rs.getObject(5, Long.class),
                                    beat.plus(expireAfter).isBefore(clock.instant()));
                        },
                        cycleId);
        return rows.stream().findFirst();
    }

    /**
     * Refuses an action while another one holds the lease (an expired lease does not hold it).
     *
     * @throws FciException {@code CYC-409-LEASE} naming the action, holder and since when
     */
    public void requireFree(long cycleId) {
        current(cycleId)
                .filter(l -> !l.expired())
                .ifPresent(
                        l -> {
                            throw held(l);
                        });
    }

    /**
     * Takes the lease for {@code action}, or refuses naming the holder. An expired lease is taken
     * over (logged EXPIRED). Two acquirers racing for a free lease: the primary key decides, the
     * other is refused the same way.
     *
     * @param runId the computation run that holds it (COMPUTE), else null
     */
    public void acquire(long cycleId, Action action, String holder, Long runId) {
        Optional<Lease> held = current(cycleId);
        if (held.isPresent()) {
            if (!held.get().expired()) {
                throw held(held.get());
            }
            jdbc.update(
                    "DELETE FROM cycle_lease WHERE cycle_instance_id = ? AND held_by = ? AND"
                            + " acquired_at = ?",
                    cycleId,
                    held.get().heldBy(),
                    Timestamp.from(held.get().acquiredAt()));
            log(
                    cycleId,
                    held.get().action(),
                    held.get().heldBy(),
                    "EXPIRED",
                    null,
                    null,
                    null,
                    null);
        }
        Timestamp now = Timestamp.from(clock.instant());
        try {
            jdbc.update(
                    "INSERT INTO cycle_lease (cycle_instance_id, lease_action, held_by,"
                            + " acquired_at, heartbeat_at, run_id) VALUES (?, ?, ?, ?, ?, ?)",
                    cycleId,
                    action.name(),
                    holder,
                    now,
                    now,
                    runId);
        } catch (DuplicateKeyException race) {
            throw current(cycleId).map(CycleLeases::held).orElseThrow(() -> race);
        }
        log(cycleId, action.name(), holder, "ACQUIRED", null, null, null, null);
    }

    /** Renews the holder's lease (the user or the run is still active). */
    public void heartbeat(long cycleId, String holder) {
        jdbc.update(
                "UPDATE cycle_lease SET heartbeat_at = ? WHERE cycle_instance_id = ? AND held_by"
                        + " = ?",
                Timestamp.from(clock.instant()),
                cycleId,
                holder);
    }

    /** Renews the lease a computation run holds (each claimed work unit and committed chunk). */
    public void heartbeatRun(long runId) {
        jdbc.update(
                "UPDATE cycle_lease SET heartbeat_at = ? WHERE run_id = ?",
                Timestamp.from(clock.instant()),
                runId);
    }

    /**
     * Takes the COMPUTE lease for a run, or keeps it when the run still holds it (a restart after a
     * crash that never released it). Refuses, naming the holder, while another action holds it.
     */
    public void acquireForRun(long cycleId, String holder, long runId) {
        Optional<Lease> held = current(cycleId);
        if (held.isPresent() && Long.valueOf(runId).equals(held.get().runId())) {
            heartbeatRun(runId);
            return;
        }
        acquire(cycleId, Action.COMPUTE, holder, runId);
    }

    /** Ends the lease a computation run holds (completed, failed or stopped). */
    public void releaseRun(long cycleId, long runId) {
        current(cycleId)
                .filter(l -> Long.valueOf(runId).equals(l.runId()))
                .ifPresent(l -> release(cycleId, l.heldBy()));
    }

    /** Ends the holder's lease (the action completed or failed); nothing if it is not theirs. */
    public void release(long cycleId, String holder) {
        Optional<Lease> held = current(cycleId).filter(l -> l.heldBy().equals(holder));
        if (held.isEmpty()) {
            return;
        }
        jdbc.update(
                "DELETE FROM cycle_lease WHERE cycle_instance_id = ? AND held_by = ?",
                cycleId,
                holder);
        log(cycleId, held.get().action(), holder, "RELEASED", holder, null, null, null);
    }

    /**
     * SRS-15-28: force-release by the X-01 authority with a reason and OTP. Releases only the lease
     * that was held when the release was requested (same holder, same acquisition): a lease taken
     * since then is not touched.
     *
     * @return whether that lease was still held and is now released
     */
    public boolean forceRelease(
            long cycleId,
            String heldBy,
            Instant acquiredAt,
            String releasedBy,
            String reason,
            long approvalId,
            long otpChallengeId) {
        Optional<Lease> held =
                current(cycleId)
                        .filter(
                                l ->
                                        l.heldBy().equals(heldBy)
                                                && l.acquiredAt().equals(acquiredAt));
        if (held.isEmpty()) {
            return false;
        }
        jdbc.update(
                "DELETE FROM cycle_lease WHERE cycle_instance_id = ? AND held_by = ? AND"
                        + " acquired_at = ?",
                cycleId,
                heldBy,
                Timestamp.from(acquiredAt));
        log(
                cycleId,
                held.get().action(),
                heldBy,
                "FORCE_RELEASED",
                releasedBy,
                reason,
                approvalId,
                otpChallengeId);
        return true;
    }

    private void log(
            long cycleId,
            String action,
            String heldBy,
            String event,
            String releasedBy,
            String reason,
            Long approvalId,
            Long otpChallengeId) {
        jdbc.update(
                "INSERT INTO cycle_lease_log (cycle_instance_id, lease_action, held_by,"
                        + " event_type, event_at, released_by, force_release_reason,"
                        + " approval_id, otp_challenge_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                cycleId,
                action,
                heldBy,
                event,
                Timestamp.from(clock.instant()),
                releasedBy,
                reason,
                approvalId,
                otpChallengeId);
    }

    private static FciException held(Lease l) {
        return new FciException(
                ApiErrorCode.CYCLE_LEASE_HELD,
                new Object[] {l.action(), l.heldBy(), SINCE.format(l.acquiredAt())},
                List.of(),
                null);
    }
}
