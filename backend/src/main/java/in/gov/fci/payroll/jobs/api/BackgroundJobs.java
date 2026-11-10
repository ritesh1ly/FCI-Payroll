package in.gov.fci.payroll.jobs.api;

import in.gov.fci.payroll.platform.security.AccessContext;
import java.util.Map;

/**
 * Queues a job on a user's behalf for a use case that is itself authorised elsewhere and changes
 * nothing that needs a checker (plan 05 D-21: a C-09 simulation above the interactive limit). The
 * request enters the C-13 queue PENDING, without an approval, with the requester's login and
 * office, and is shown, paused, resumed and abandoned on C-13 like any other.
 */
public interface BackgroundJobs {

    /** A job a module queues itself (never offered on the C-13 submit form). */
    enum Kind {
        /** C-09 General (Simulation) Run above the interactive limit (SRS-15-7). */
        SIMULATION_RUN,
        /** O-01 Bulk PDF: a ZIP of the unit's payslips of a locked month (OD-149). */
        PAYSLIP_BULK,
        /** C-10 Bulk PDF: a ZIP of the unit's Form 16 certificates of a year (OD-149). */
        FORM16_BULK
    }

    /**
     * A queued job as the module that queued it sees it (its own output's download, OD-149).
     *
     * @param status AWAITING_APPROVAL, PENDING, PICKED, RUNNING, COMPLETED, FAILED, STOPPED,
     *     PAUSED, ABANDONED, REJECTED
     * @param requestedByLogin the requesting user's login
     * @param locationId the office the job runs for
     * @param params the job's parameters as queued
     * @param written items written; skipped: items left out (a failed member)
     */
    record Job(
            long id,
            Kind kind,
            String status,
            String requestedByLogin,
            Long locationId,
            Map<String, String> params,
            long written,
            long skipped,
            String summary) {

        /** Ended well: the output can be downloaded. */
        public boolean completed() {
            return "COMPLETED".equals(status);
        }
    }

    /**
     * The job {@code id} if it is of {@code kind}; empty otherwise (another kind reads as none).
     */
    java.util.Optional<Job> find(Kind kind, long id);

    /** The newest job of {@code kind} the user queued with exactly these parameters, if any. */
    java.util.Optional<Job> latest(Kind kind, String login, Map<String, String> params);

    /**
     * Queues the job.
     *
     * @param locationId the office the job runs for (C-13 data scope)
     * @param params the job's parameters (validated by the caller; recorded as given)
     * @return the request id ({@code batch_job_request.job_request_id})
     */
    long enqueue(AccessContext context, Kind kind, long locationId, Map<String, String> params);
}
