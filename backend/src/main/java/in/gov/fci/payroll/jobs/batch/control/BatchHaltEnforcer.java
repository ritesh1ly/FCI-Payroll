package in.gov.fci.payroll.jobs.batch.control;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.JobStopper;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The batch node's side of the global halt (plan 05j step j4). Each poll, while a halt is engaged:
 * every job this node runs is told to stop at its next chunk boundary (STOP intent, so it ends
 * STOPPED with its committed work kept and may be restarted after the release, under the resume
 * rule), and the poller launches nothing new. A job already stopping is left alone.
 *
 * <p>Why the chunk boundary and not a killed thread: there the run is consistent (SRS-15-24: a work
 * unit is either fully computed or not), and it is reached within one chunk.
 */
@Component
@Profile("batch")
public class BatchHaltEnforcer {

    private static final Logger log = LoggerFactory.getLogger(BatchHaltEnforcer.class);

    static final String STOP = "STOP";

    private final JobRepositories.Halts halts;
    private final JobRepositories.JobRequests requests;
    private final JobLaunchService launcher;
    private final JobStopper stopper;
    private final JobJournal journal;
    private final BatchInstance instance;
    private final TransactionTemplate tx;

    public BatchHaltEnforcer(
            JobRepositories.Halts halts,
            JobRepositories.JobRequests requests,
            JobLaunchService launcher,
            JobStopper stopper,
            JobJournal journal,
            BatchInstance instance,
            TransactionTemplate tx) {
        this.halts = halts;
        this.requests = requests;
        this.launcher = launcher;
        this.stopper = stopper;
        this.journal = journal;
        this.instance = instance;
        this.tx = tx;
    }

    /** True while halted (the caller then launches nothing); stops this node's running jobs. */
    public boolean enforce() {
        if (!halts.existsByReleasedAtIsNull()) {
            return false;
        }
        // The intent first, in its own transaction; the stop signal after it, outside (JobStopper).
        List<BatchJobRequest> toStop =
                tx.execute(
                        s -> {
                            List<BatchJobRequest> marked = new ArrayList<>();
                            for (BatchJobRequest r : requests.findAllById(launcher.running())) {
                                if (r.getStatus() == JobStatus.RUNNING
                                        && r.getStopIntent() == null) {
                                    r.requestStop(STOP);
                                    requests.save(r);
                                    journal.record(
                                            r.getId(),
                                            JobJournal.Event.HALTED,
                                            instance.id(),
                                            JobStatus.RUNNING,
                                            null,
                                            r.getJobExecutionId(),
                                            "Batch processing halted: stops after the current"
                                                    + " chunk");
                                    marked.add(r);
                                }
                            }
                            return marked;
                        });
        for (BatchJobRequest r : toStop == null ? List.<BatchJobRequest>of() : toStop) {
            boolean signalled = stopper.stop(r.getJobExecutionId());
            log.warn("event=BATCH_HALT_STOP jobRequestId={} signalled={}", r.getId(), signalled);
        }
        return true;
    }
}
