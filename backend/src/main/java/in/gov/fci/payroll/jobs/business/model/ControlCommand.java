package in.gov.fci.payroll.jobs.business.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Control operations on a request and the states each is valid in. Pause is a cooperative stop (the
 * running step finishes its chunk) that Resume continues from the last committed chunk; Stop ends
 * the request (a stopped request can still be restarted within the restart window).
 */
public enum ControlCommand {
    STOP(EnumSet.of(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.PAUSED)),
    PAUSE(EnumSet.of(JobStatus.RUNNING)),
    RESUME(EnumSet.of(JobStatus.PAUSED)),
    RESTART(EnumSet.of(JobStatus.FAILED, JobStatus.STOPPED)),
    /**
     * Ends a job for good (SRS-15-27, plan 05j). Recorded only by an approved abandon request (C-13
     * maker-checker) or by the batch node itself past the restart window, never as a direct
     * command.
     */
    ABANDON(EnumSet.of(JobStatus.FAILED, JobStatus.STOPPED, JobStatus.PAUSED));

    private final Set<JobStatus> validFrom;

    ControlCommand(Set<JobStatus> validFrom) {
        this.validFrom = validFrom;
    }

    public boolean isValidFor(JobStatus status) {
        return validFrom.contains(status);
    }

    public Set<JobStatus> validFrom() {
        return validFrom;
    }

    /** Abandon is approved first; the others are recorded at once. */
    public boolean needsApproval() {
        return this == ABANDON;
    }

    /** Resume and Restart start a new attempt (and are bound by the restart window). */
    public boolean startsAttempt() {
        return this == RESUME || this == RESTART;
    }
}
