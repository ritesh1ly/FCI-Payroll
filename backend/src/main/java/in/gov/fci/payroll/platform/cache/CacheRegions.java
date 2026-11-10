package in.gov.fci.payroll.platform.cache;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The versions of the master-data cache regions ({@code cache_region}), shared by every instance
 * through the database, so an approved change reaches all of them without a message broker:
 *
 * <ul>
 *   <li>{@link #changed} increments a region's version in the caller's transaction (the approval
 *       that changes the data), so the new version is visible exactly when the data is;
 *   <li>{@link #version} answers from memory and re-reads all versions with one query at most every
 *       {@code fci.cache.check-interval}, and only while caches are read (no scheduler: the online
 *       instance runs none); this instance re-reads at once after its own change commits.
 * </ul>
 *
 * If the database cannot be asked, the last known versions stand and the next read tries again; the
 * caches' {@code max-age} still bounds how old an entry can be.
 */
@Component
public class CacheRegions {

    private static final Logger log = LoggerFactory.getLogger(CacheRegions.class);

    private final JdbcTemplate jdbc;
    private final CacheProperties properties;
    private final Clock clock;
    private final ReentrantLock refreshing = new ReentrantLock();
    private volatile Map<String, Long> versions = Map.of();
    private volatile Instant nextCheck = Instant.MIN;
    // The transaction resource holding the regions changed by the current transaction.
    private final Object pendingKey = new Object();

    public CacheRegions(JdbcTemplate jdbc, CacheProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public CacheProperties properties() {
        return properties;
    }

    /** The latest known version of {@code region} (0 if it was never changed). */
    public long version(String region) {
        if (!clock.instant().isBefore(nextCheck) && refreshing.tryLock()) {
            try {
                refresh();
            } finally {
                refreshing.unlock();
            }
        }
        return versions.getOrDefault(region, 0L);
    }

    /**
     * Marks {@code region} changed: in the current transaction if there is one (commits with the
     * data), else at once. Every instance drops its entries of the region on its next check.
     */
    public void changed(String region, String changedBy) {
        Instant now = clock.instant();
        int updated =
                jdbc.update(
                        "UPDATE cache_region SET version_no = version_no + 1, changed_at = ?,"
                                + " changed_by = ? WHERE region_code = ?",
                        java.sql.Timestamp.from(now),
                        changedBy,
                        region);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO cache_region (region_code, version_no, changed_at, changed_by)"
                            + " VALUES (?, 1, ?, ?)",
                    region,
                    java.sql.Timestamp.from(now),
                    changedBy);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            pendingInTransaction().add(region);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            nextCheck = Instant.MIN;
                        }
                    });
        } else {
            nextCheck = Instant.MIN;
        }
    }

    /**
     * Whether the current transaction has changed {@code region} and not yet committed: what it
     * reads of the region is its own uncommitted data, which must not be cached (a rollback would
     * leave it served under the unchanged version).
     */
    public boolean changedInCurrentTransaction(String region) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        Object pending = TransactionSynchronizationManager.getResource(pendingKey);
        return pending instanceof Set<?> set && set.contains(region);
    }

    /** The regions this transaction changed; unbound when it completes, commit or rollback. */
    @SuppressWarnings("unchecked")
    private Set<String> pendingInTransaction() {
        Object bound = TransactionSynchronizationManager.getResource(pendingKey);
        if (bound != null) {
            return (Set<String>) bound;
        }
        Set<String> pending = new HashSet<>();
        TransactionSynchronizationManager.bindResource(pendingKey, pending);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(pendingKey);
                    }
                });
        return pending;
    }

    private void refresh() {
        Map<String, Long> read = new HashMap<>();
        try {
            jdbc.query(
                    "SELECT region_code, version_no FROM cache_region",
                    rs -> {
                        read.put(rs.getString(1), rs.getLong(2));
                    });
        } catch (DataAccessException ex) {
            log.warn(
                    "event=CACHE_CHECK_FAILED message=\"cache versions not readable; keeping the"
                            + " last known\" error=\"{}\"",
                    ex.getMessage());
            nextCheck = clock.instant().plus(properties.checkInterval());
            return;
        }
        Map<String, Long> before = versions;
        read.forEach(
                (region, version) -> {
                    Long old = before.get(region);
                    if (old != null && !old.equals(version)) {
                        log.info(
                                "event=CACHE_REGION_CHANGED region={} from={} to={}",
                                region,
                                old,
                                version);
                    }
                });
        versions = Map.copyOf(read);
        nextCheck = clock.instant().plus(properties.checkInterval());
    }
}
