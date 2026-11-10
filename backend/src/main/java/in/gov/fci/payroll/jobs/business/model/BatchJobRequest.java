package in.gov.fci.payroll.jobs.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;

/**
 * One row of {@code batch_job_request} (BATCH_JOB_REQUEST): the hand-off between the online
 * instance, which only inserts PENDING rows and control commands, and the batch instances, which
 * claim, run and finish them. Transitions are methods here so that every path (poller, listener,
 * control processor, crash recovery) follows the same state machine ({@link JobStatus}).
 */
@Entity
@Table(name = "batch_job_request")
public class BatchJobRequest {

    public enum Source {
        UI,
        SCHEDULE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_request_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_name", nullable = false, length = 30)
    private JobType jobType;

    @Column(name = "job_params", nullable = false, length = 4000)
    private String params;

    @Column(name = "param_hash", nullable = false, length = 64)
    private String paramHash;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_status", nullable = false, length = 20)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_source", nullable = false, length = 10)
    private Source source;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_by_login", nullable = false, length = 150)
    private String requestedByLogin;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "pay_period", length = 7)
    private String payPeriod;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "claimed_by", length = 100)
    private String claimedBy;

    @Column(name = "picked_at")
    private Instant pickedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "job_execution_id")
    private Long jobExecutionId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "stop_intent", length = 5)
    private String stopIntent;

    @Column(name = "read_count", nullable = false)
    private long readCount;

    @Column(name = "write_count", nullable = false)
    private long writeCount;

    @Column(name = "skip_count", nullable = false)
    private long skipCount;

    @Column(name = "result_summary", length = 500)
    private String resultSummary;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long version;

    protected BatchJobRequest() {}

    public static BatchJobRequest submit(
            JobType type,
            String params,
            String paramHash,
            int priority,
            Source source,
            Long requestedBy,
            String requestedByLogin,
            Long locationId,
            String payPeriod,
            Instant now) {
        BatchJobRequest request = new BatchJobRequest();
        request.jobType = type;
        request.params = params;
        request.paramHash = paramHash;
        request.priority = priority;
        request.status = JobStatus.PENDING;
        request.source = source;
        request.requestedBy = requestedBy;
        request.requestedByLogin = requestedByLogin;
        request.locationId = locationId;
        request.payPeriod = payPeriod;
        request.requestedAt = now;
        return request;
    }

    /**
     * Maker-checker: a UI submission waits for a Payroll Checker. Batch nodes claim only PENDING
     * rows, so nothing can run before {@link #approved}.
     */
    public void awaitApproval(Long approvalId) {
        if (status != JobStatus.PENDING || this.approvalId != null) {
            throw new IllegalStateException("Only a new request can await approval");
        }
        this.status = JobStatus.AWAITING_APPROVAL;
        this.approvalId = approvalId;
    }

    /** The checker approved: the request enters the queue. */
    public void approved() {
        if (status != JobStatus.AWAITING_APPROVAL) {
            throw new IllegalStateException("Cannot approve a " + status + " request");
        }
        this.status = JobStatus.PENDING;
    }

    /** The checker rejected or returned it: final, nothing ran. */
    public void rejected(String reason, Instant now) {
        if (status != JobStatus.AWAITING_APPROVAL) {
            throw new IllegalStateException("Cannot reject a " + status + " request");
        }
        this.status = JobStatus.REJECTED;
        this.errorMessage = truncate(reason, 1000);
        this.finishedAt = now;
    }

    /** A batch node takes the request for a first run or a resume/restart. */
    public void pick(String instance, Instant now) {
        if (status != JobStatus.PENDING
                && status != JobStatus.PAUSED
                && status != JobStatus.FAILED
                && status != JobStatus.STOPPED) {
            throw new IllegalStateException("Cannot pick a " + status + " request");
        }
        this.status = JobStatus.PICKED;
        this.claimedBy = instance;
        this.pickedAt = now;
        this.finishedAt = null;
        this.errorMessage = null;
        this.stopIntent = null;
    }

    /** Back to the queue: the launch was refused (lock held elsewhere), nothing ran. */
    public void unpick(JobStatus previous) {
        this.status = previous;
        this.claimedBy = null;
    }

    public void started(long executionId, Instant now) {
        this.status = JobStatus.RUNNING;
        this.jobExecutionId = executionId;
        this.attemptNo++;
        if (startedAt == null) {
            this.startedAt = now;
        }
    }

    public void requestStop(String intent) {
        this.stopIntent = intent;
    }

    /** End of an attempt, from the job listener. */
    public void finished(
            JobStatus outcome,
            long read,
            long written,
            long skipped,
            String summary,
            String error,
            Instant now) {
        this.status = outcome;
        this.readCount = read;
        this.writeCount = written;
        this.skipCount = skipped;
        this.resultSummary = truncate(summary, 500);
        this.errorMessage = truncate(error, 1000);
        this.finishedAt = now;
        this.stopIntent = null;
    }

    /** Cancelled before it ever started (STOP of a PENDING request), or Stop of a PAUSED one. */
    public void cancel(Instant now) {
        this.status = JobStatus.STOPPED;
        this.finishedAt = now;
    }

    /**
     * Abandoned for good (approved, or past the restart window). The failure message stays as it
     * was (the reason is the journal's).
     */
    public void abandon(Instant now) {
        this.status = JobStatus.ABANDONED;
        this.finishedAt = finishedAt == null ? now : finishedAt;
        this.stopIntent = null;
    }

    /** The node that ran it died; found by crash recovery. */
    public void recoverAsFailed(String message, Instant now) {
        this.status = JobStatus.FAILED;
        this.errorMessage = truncate(message, 1000);
        this.finishedAt = now;
        this.stopIntent = null;
    }

    public Duration duration(Instant now) {
        if (startedAt == null) {
            return null;
        }
        return Duration.between(startedAt, finishedAt != null ? finishedAt : now);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public JobType getJobType() {
        return jobType;
    }

    public String getParams() {
        return params;
    }

    public String getParamHash() {
        return paramHash;
    }

    public int getPriority() {
        return priority;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Source getSource() {
        return source;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public String getRequestedByLogin() {
        return requestedByLogin;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public Long getLocationId() {
        return locationId;
    }

    public String getPayPeriod() {
        return payPeriod;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Instant getPickedAt() {
        return pickedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getJobExecutionId() {
        return jobExecutionId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public String getStopIntent() {
        return stopIntent;
    }

    public long getReadCount() {
        return readCount;
    }

    public long getWriteCount() {
        return writeCount;
    }

    public long getSkipCount() {
        return skipCount;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
