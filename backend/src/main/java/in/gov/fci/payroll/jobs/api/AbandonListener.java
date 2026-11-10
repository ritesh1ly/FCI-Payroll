package in.gov.fci.payroll.jobs.api;

/**
 * Told when a job is abandoned for good (plan 05j: an approved Abandon, or past the restart window;
 * SRS-15-27). The module that owns what the job produced closes it: the payroll module marks the
 * job's computation run ABANDONED, its results staying QUARANTINED for the investigation. Called on
 * the batch node, in the transaction that abandons the request.
 */
public interface AbandonListener {

    /**
     * @param jobRequestId the abandoned request ({@code batch_job_request.job_request_id})
     * @param jobName its job type ({@code batch_job_request.job_name})
     */
    void abandoned(long jobRequestId, String jobName);
}
