package in.gov.fci.payroll.jobs.api;

/**
 * SRS-NF-2 / SRS-NF-5 (deep review DR-18): a background job requested by a user has ended, so the
 * requester can be told without keeping C-13 open. Published on the batch node in the transaction
 * that records the outcome (the job's end, or crash recovery failing an abandoned attempt); a
 * scheduled job, with no requesting user, publishes nothing.
 *
 * @param jobRequestId {@code batch_job_request.job_request_id}
 * @param jobType the job type ({@code batch_job_request.job_name})
 * @param status COMPLETED, FAILED or STOPPED
 * @param requestedBy the requesting user ({@code app_user.user_id})
 * @param payPeriod the period the job ran for (YYYY-MM), or null
 * @param read members / records read
 * @param written written
 * @param skipped skipped (the failure log on C-13)
 * @param error the cause of a failure, or null
 */
public record JobEnded(
        long jobRequestId,
        String jobType,
        String status,
        long requestedBy,
        String payPeriod,
        long read,
        long written,
        long skipped,
        String error) {}
