package in.gov.fci.payroll.jobs.api;

import in.gov.fci.payroll.platform.error.ErrorCode;
import java.util.Optional;

/**
 * A module's veto on continuing a request from where it stopped (C-13 Resume after a pause, Restart
 * after a failure or a stop: Spring Batch relaunches the same execution from its last committed
 * chunk). SRS-15-26 / BR-CYC-013: a computation run is not resumed when the rates, slabs or rule
 * versions it uses, or its parameters, changed after it started; only a full restart (a new
 * request, computed from the start) is offered.
 *
 * <p>Asked by the online instance when the command is recorded, and by the batch instance again
 * just before the relaunch (something may have been activated in between). Implementations answer
 * for the job types they own and stay silent for the rest.
 */
public interface ResumeCheck {

    /**
     * Why the request may not continue from where it stopped, or empty.
     *
     * @param jobRequestId the request ({@code batch_job_request.job_request_id})
     * @param jobName its job type ({@code batch_job_request.job_name})
     * @param parameterHash its parameter hash as recorded at submission
     */
    Optional<ErrorCode> refusal(long jobRequestId, String jobName, String parameterHash);
}
