package in.gov.fci.payroll.jobs.batch.lock;

import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "The same job never runs twice at once", across every batch node ({@code batch_job_lock}, in the
 * manner of the target DDL's {@code cycle_lease}). The key is the job name and the parameter hash,
 * so two different requests with the same parameters cannot overlap either.
 *
 * <ul>
 *   <li>acquire = INSERT; it fails while another attempt holds the key;
 *   <li>the holder refreshes {@code heartbeat_at} after every chunk;
 *   <li>a lock whose heartbeat is older than {@code fci.batch.lock.stale-after} belongs to a dead
 *       node and is taken over (atomic UPDATE on the stale heartbeat);
 *   <li>release = DELETE when the attempt ends.
 * </ul>
 *
 * Every method runs in its own transaction: a lock must be visible to other nodes at once.
 */
@Service
@Profile("batch")
public class JobLockService {

    private final JdbcTemplate jdbc;
    private final BatchInstance instance;
    private final BatchProperties properties;
    private final Clock clock;

    public JobLockService(
            JdbcTemplate jdbc, BatchInstance instance, BatchProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.instance = instance;
        this.properties = properties;
        this.clock = clock;
    }

    public static String key(String jobName, String paramHash) {
        return jobName + "|" + paramHash;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acquire(String key, Long requestId) {
        Timestamp now = Timestamp.from(clock.instant());
        try {
            jdbc.update(
                    "INSERT INTO batch_job_lock (lock_key, job_request_id, held_by, acquired_at,"
                            + " heartbeat_at) VALUES (?, ?, ?, ?, ?)",
                    key,
                    requestId,
                    instance.id(),
                    now,
                    now);
            return true;
        } catch (DuplicateKeyException held) {
            Timestamp staleBefore =
                    Timestamp.from(clock.instant().minus(properties.lock().staleAfter()));
            int taken =
                    jdbc.update(
                            "UPDATE batch_job_lock SET job_request_id = ?, held_by = ?, acquired_at"
                                + " = ?, heartbeat_at = ? WHERE lock_key = ? AND heartbeat_at < ?",
                            requestId,
                            instance.id(),
                            now,
                            now,
                            key,
                            staleBefore);
            return taken == 1;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeat(Long requestId) {
        jdbc.update(
                "UPDATE batch_job_lock SET heartbeat_at = ? WHERE job_request_id = ? AND held_by ="
                        + " ?",
                Timestamp.from(clock.instant()),
                requestId,
                instance.id());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long requestId) {
        jdbc.update("DELETE FROM batch_job_lock WHERE job_request_id = ?", requestId);
    }

    /** No lock, or a heartbeat older than the stale limit: the request's node is gone. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isStale(Long requestId) {
        List<Timestamp> beats =
                jdbc.queryForList(
                        "SELECT heartbeat_at FROM batch_job_lock WHERE job_request_id = ?",
                        Timestamp.class,
                        requestId);
        if (beats.isEmpty()) {
            return true;
        }
        Instant staleBefore = clock.instant().minus(properties.lock().staleAfter());
        return beats.get(0).toInstant().isBefore(staleBefore);
    }
}
