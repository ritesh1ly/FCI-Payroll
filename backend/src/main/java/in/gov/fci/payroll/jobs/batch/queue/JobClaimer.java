package in.gov.fci.payroll.jobs.batch.queue;

import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims work for this node with {@code SELECT ... FOR UPDATE SKIP LOCKED}: rows another node has
 * locked are skipped instead of waited for, so several batch nodes can poll the same queue and each
 * row is taken by exactly one. The row limit is applied through JDBC {@code maxRows}, not {@code
 * FETCH FIRST}, because Oracle refuses {@code FETCH FIRST} together with {@code FOR UPDATE}; with
 * SKIP LOCKED Oracle locks rows as they are fetched. Works the same on the interim H2 database.
 *
 * <p>Order: highest priority first (9 .. 1), then oldest. SRS-NF-11 (DR-17): a low-priority job
 * (bulk outputs, simulations) is taken only within its allowance, so a node's last free slot stays
 * for priority work; the low-priority rows passed over stay PENDING for a later poll.
 */
@Component
@Profile("batch")
public class JobClaimer {

    private static final String PENDING_REQUESTS =
            "SELECT job_request_id FROM batch_job_request WHERE job_status = 'PENDING' ORDER BY"
                    + " priority DESC, requested_at, job_request_id FOR UPDATE SKIP LOCKED";

    /** The low-priority job names, as SQL literals (DR-17). */
    private static final Set<String> LOW_PRIORITY_NAMES =
            Arrays.stream(JobType.values())
                    .filter(JobType::isLowPriority)
                    .map(JobType::name)
                    .collect(Collectors.toUnmodifiableSet());

    private static final String PENDING_CONTROLS =
            "SELECT control_id FROM batch_job_control WHERE control_status = 'PENDING'"
                    + " ORDER BY requested_at, control_id FOR UPDATE SKIP LOCKED";

    private final JdbcTemplate jdbc;
    private final JobRepositories.JobRequests requests;
    private final JobJournal journal;
    private final BatchInstance instance;
    private final Clock clock;

    public JobClaimer(
            JdbcTemplate jdbc,
            JobRepositories.JobRequests requests,
            JobJournal journal,
            BatchInstance instance,
            Clock clock) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.journal = journal;
        this.instance = instance;
        this.clock = clock;
    }

    /** Takes up to {@code max} PENDING requests, priority work and bulk work alike. */
    @Transactional
    public List<BatchJobRequest> claimPending(int max) {
        return claimPending(max, max, max);
    }

    /**
     * Takes up to {@code max} PENDING requests and marks them PICKED for this node; at most {@code
     * lowAllowed} of them low-priority (DR-17). Up to {@code scan} rows are locked and looked at.
     */
    @Transactional
    public List<BatchJobRequest> claimPending(int max, int lowAllowed, int scan) {
        List<Long> ids = lockIds(PENDING_REQUESTS, Math.max(max, scan));
        Map<Long, BatchJobRequest> byId = new HashMap<>();
        requests.findAllById(ids).forEach(r -> byId.put(r.getId(), r));
        List<BatchJobRequest> claimed = new ArrayList<>();
        for (BatchJobRequest request :
                choose(ids.stream().map(byId::get).toList(), max, lowAllowed)) {
            request.pick(instance.id(), clock.instant());
            claimed.add(requests.save(request));
            journal.record(
                    request.getId(),
                    JobJournal.Event.PICKED,
                    instance.id(),
                    JobStatus.PENDING,
                    JobStatus.PICKED,
                    null,
                    null);
        }
        return claimed;
    }

    /**
     * DR-17: from the locked rows in queue order, the PENDING ones to take: at most {@code max}, of
     * which at most {@code lowAllowed} low-priority; a passed-over row stays PENDING.
     */
    static List<BatchJobRequest> choose(List<BatchJobRequest> inOrder, int max, int lowAllowed) {
        List<BatchJobRequest> chosen = new ArrayList<>();
        int low = 0;
        for (BatchJobRequest request : inOrder) {
            if (chosen.size() >= max) {
                break;
            }
            if (request == null || request.getStatus() != JobStatus.PENDING) {
                continue;
            }
            if (request.getJobType().isLowPriority()) {
                if (low >= lowAllowed) {
                    continue;
                }
                low++;
            }
            chosen.add(request);
        }
        return chosen;
    }

    /**
     * The low-priority jobs this node has picked or is running (DR-17). A node holds a handful at
     * most: their names are read with a fixed query and judged by {@link JobType#isLowPriority()}.
     */
    public int runningLowPriority() {
        return (int)
                jdbc
                        .queryForList(
                                "SELECT job_name FROM batch_job_request WHERE claimed_by = ? AND"
                                        + " job_status IN ('PICKED', 'RUNNING')",
                                String.class,
                                instance.id())
                        .stream()
                        .filter(LOW_PRIORITY_NAMES::contains)
                        .count();
    }

    /**
     * Locks up to {@code max} PENDING control commands for the caller's transaction; the caller
     * applies them in that transaction, so another node cannot apply the same command.
     */
    @Transactional
    public List<Long> lockPendingControls(int max) {
        return lockIds(PENDING_CONTROLS, max);
    }

    private List<Long> lockIds(String sql, int max) {
        JdbcTemplate limited =
                new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        limited.setMaxRows(max);
        return limited.queryForList(sql, Long.class);
    }
}
