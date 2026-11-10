package in.gov.fci.payroll.jobs.persistence;

import in.gov.fci.payroll.jobs.business.model.BatchHalt;
import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/** The batch job queue and its control commands (V010, V013). */
public final class JobRepositories {

    private JobRepositories() {}

    public interface JobRequests
            extends Repository<BatchJobRequest, Long>, JpaSpecificationExecutor<BatchJobRequest> {
        BatchJobRequest save(BatchJobRequest request);

        Optional<BatchJobRequest> findById(Long id);

        List<BatchJobRequest> findAllById(Iterable<Long> ids);

        /** Duplicate guard: an open request for the same job and parameters. */
        boolean existsByJobTypeAndParamHashAndStatusIn(
                JobType type, String paramHash, Collection<JobStatus> statuses);

        /** OD-149: the newest request of a user for a job and its parameters. */
        Optional<BatchJobRequest> findFirstByJobTypeAndParamHashAndRequestedByLoginOrderByIdDesc(
                JobType type, String paramHash, String requestedByLogin);

        /** Requests a batch node owns right now (crash recovery, shutdown). */
        List<BatchJobRequest> findByStatusIn(Collection<JobStatus> statuses);

        /** SRS-15-27: failed or stopped requests that ended before {@code cutoff}, oldest first. */
        List<BatchJobRequest> findTop50ByStatusInAndFinishedAtBeforeOrderByFinishedAt(
                Collection<JobStatus> statuses, Instant cutoff);
    }

    public interface Controls extends Repository<BatchJobControl, Long> {
        BatchJobControl save(BatchJobControl control);

        Optional<BatchJobControl> findById(Long id);

        List<BatchJobControl> findByJobRequestIdOrderByIdDesc(Long jobRequestId);

        boolean existsByJobRequestIdAndStatus(Long jobRequestId, BatchJobControl.Status status);
    }

    /** The global halt (V038_1): at most one engaged row, the rest history. */
    public interface Halts extends Repository<BatchHalt, Long> {
        BatchHalt save(BatchHalt halt);

        Optional<BatchHalt> findById(Long id);

        /** The engaged halt, newest first (one in practice). */
        List<BatchHalt> findByReleasedAtIsNullOrderByIdDesc();

        boolean existsByReleasedAtIsNull();
    }
}
