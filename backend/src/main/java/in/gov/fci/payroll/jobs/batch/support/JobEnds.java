package in.gov.fci.payroll.jobs.batch.support;

import in.gov.fci.payroll.jobs.api.JobEnded;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * SRS-NF-2 / SRS-NF-5 (DR-18): the {@link JobEnded} notice of a request that has just ended, when a
 * user asked for it. Paused is not an end (the job resumes); a scheduled job has no requester.
 */
public final class JobEnds {

    /** The outcomes the requester is told of. */
    static final Set<JobStatus> ENDS =
            EnumSet.of(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.STOPPED);

    private JobEnds() {}

    public static Optional<JobEnded> of(BatchJobRequest request) {
        if (request.getRequestedBy() == null || !ENDS.contains(request.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(
                new JobEnded(
                        request.getId(),
                        request.getJobType().name(),
                        request.getStatus().name(),
                        request.getRequestedBy(),
                        request.getPayPeriod(),
                        request.getReadCount(),
                        request.getWriteCount(),
                        request.getSkipCount(),
                        request.getErrorMessage()));
    }
}
