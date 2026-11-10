package in.gov.fci.payroll.jobs.business.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a batch job request (docs/BATCH_DESIGN.md, state diagram):
 *
 * <pre>
 * AWAITING_APPROVAL -> PENDING           (a Payroll Checker approves the maker's submission)
 * AWAITING_APPROVAL -> REJECTED          (the checker rejects or returns it; final)
 * PENDING -> PICKED -> RUNNING -> COMPLETED | FAILED | STOPPED | PAUSED
 * PENDING -> STOPPED                     (cancelled before it started)
 * PAUSED  -> PICKED                      (resume: restart from the last committed chunk)
 * FAILED | STOPPED -> PICKED             (restart, within the restart window)
 * PICKED | RUNNING -> FAILED             (crash recovery: the node died)
 * FAILED | STOPPED | PAUSED -> ABANDONED (an approved Abandon, or past the restart window; final)
 * </pre>
 */
public enum JobStatus {
    /** Submitted by a maker; not claimable by any batch node until a checker approves (C-13). */
    AWAITING_APPROVAL,
    /** The checker rejected or returned the submission; nothing ran. */
    REJECTED,
    PENDING,
    PICKED,
    RUNNING,
    COMPLETED,
    FAILED,
    STOPPED,
    PAUSED,
    /**
     * Final (SRS-15-27): never resumed, restarted or counted open; its results stay for the
     * investigation, never to be verified, locked or posted.
     */
    ABANDONED;

    /** Open: counts for the duplicate guard (one open request per job and parameters). */
    public static final Set<JobStatus> OPEN =
            EnumSet.of(AWAITING_APPROVAL, PENDING, PICKED, RUNNING, PAUSED);

    /** A batch node owns the request right now. */
    public static final Set<JobStatus> ACTIVE = EnumSet.of(PICKED, RUNNING);

    public boolean isOpen() {
        return OPEN.contains(this);
    }
}
