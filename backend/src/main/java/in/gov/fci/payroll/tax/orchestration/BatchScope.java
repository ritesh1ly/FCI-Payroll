package in.gov.fci.payroll.tax.orchestration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/**
 * The persistence settings of a loop over every member of a paying unit (Form 16 Part B, a Form
 * 24Q, its validation). Profiled 2026-10-25 (docs/perf/module-08-profile.md): by default every
 * query of the loop auto-flushes, and Hibernate dirty-checks every entity loaded so far, so the
 * loop grew with the square of the members and held all of them in memory. In a loop, queries no
 * longer flush (the loop never reads its own pending writes back), and every {@value #EVERY}
 * members the pending writes go to the database and the loaded entities are dropped.
 */
@Component
class BatchScope {

    static final int EVERY = 200;

    @PersistenceContext private EntityManager em;

    /** From here to the end of the transaction, queries do not flush. */
    void begin() {
        em.setFlushMode(FlushModeType.COMMIT);
    }

    /**
     * Writes the pending changes now. A query that must see the loop's own writes (the totals read
     * back after it) calls this first, since queries no longer flush after {@link #begin()}.
     */
    void flush() {
        em.flush();
    }

    /**
     * After {@code done} members: every {@value #EVERY}, write and drop the loaded entities. An
     * entity the caller still holds is detached afterwards; saving it again merges it.
     */
    void checkpoint(int done) {
        if (done > 0 && done % EVERY == 0) {
            em.flush();
            em.clear();
        }
    }
}
