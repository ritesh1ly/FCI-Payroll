package in.gov.fci.payroll.platform.maintenance;

import java.time.Instant;

/**
 * A module's periodic housekeeping task (expire, flag, purge). Every module registers its own; the
 * batch instance's housekeeping job runs them all, so the online instance runs no scheduled task.
 */
public interface Housekeeping {

    /** A short name for the log, e.g. {@code expiredOtps}. */
    String name();

    /** Does the work as of {@code now} in the caller's transaction; returns the rows affected. */
    int run(Instant now);
}
