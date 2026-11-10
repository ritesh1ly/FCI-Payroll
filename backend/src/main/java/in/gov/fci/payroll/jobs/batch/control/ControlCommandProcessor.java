package in.gov.fci.payroll.jobs.batch.control;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.queue.JobClaimer;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.JobAbandoner;
import in.gov.fci.payroll.jobs.batch.support.JobStopper;
import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the Stop / Pause / Resume / Restart commands the online instance recorded. Each poll
 * locks the pending commands ({@code SKIP LOCKED}, so one node applies each), re-checks the request
 * state (it may have changed since the command was requested) and records APPLIED or REJECTED.
 *
 * <ul>
 *   <li>STOP of a PENDING or PAUSED request: STOPPED at once (nothing is running);
 *   <li>STOP / PAUSE of a RUNNING request: the intent is recorded and {@link JobStopper#stop} sets
 *       the execution STOPPING in the shared job repository; the node running it (any node) stops
 *       at the next chunk boundary, and its job listener turns the request STOPPED or PAUSED;
 *   <li>RESUME / RESTART: the request is PICKED by this node and relaunched; Spring Batch restarts
 *       it from the last committed chunk. Within the restart window only; a full node waits for the
 *       next poll (the command stays PENDING). Refused while batch processing is halted (plan 05j
 *       step j4: the halt is released first, then each job is restarted deliberately);
 *   <li>ABANDON (recorded only by an approved abandon, plan 05j step j2): the request, its
 *       execution and its computation run end for good ({@link JobAbandoner}).
 * </ul>
 */
@Component
@Profile("batch")
public class ControlCommandProcessor {

    private static final Logger log = LoggerFactory.getLogger(ControlCommandProcessor.class);

    private record Launch(BatchJobRequest request, JobStatus previous) {}

    private final JobClaimer claimer;
    private final JobRepositories.Controls controls;
    private final JobRepositories.JobRequests requests;
    private final JobStopper stopper;
    private final JobAbandoner abandoner;
    private final JobRepositories.Halts halts;
    private final JobLaunchService launcher;
    private final JobRequestService service;
    private final JobJournal journal;
    private final BatchInstance instance;
    private final BatchProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ControlCommandProcessor(
            JobClaimer claimer,
            JobRepositories.Controls controls,
            JobRepositories.JobRequests requests,
            JobStopper stopper,
            JobAbandoner abandoner,
            JobRepositories.Halts halts,
            JobLaunchService launcher,
            JobRequestService service,
            JobJournal journal,
            BatchInstance instance,
            BatchProperties properties,
            TransactionTemplate tx,
            Clock clock) {
        this.claimer = claimer;
        this.controls = controls;
        this.requests = requests;
        this.stopper = stopper;
        this.abandoner = abandoner;
        this.halts = halts;
        this.launcher = launcher;
        this.service = service;
        this.journal = journal;
        this.instance = instance;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    /** One pass: decisions in one transaction, launches (Resume / Restart) after it commits. */
    public void processPending() {
        // TransactionTemplate#execute is @Nullable by contract; the callback never returns null.
        List<Launch> launches =
                Objects.requireNonNullElse(
                        tx.execute(
                                s -> {
                                    List<Launch> toLaunch = new ArrayList<>();
                                    int slots =
                                            launcher.freeSlots(
                                                    properties.poller().maxConcurrentJobs());
                                    for (Long id :
                                            claimer.lockPendingControls(
                                                    properties.poller().claimBatchSize())) {
                                        BatchJobControl control =
                                                controls.findById(id).orElse(null);
                                        if (control == null
                                                || control.getStatus()
                                                        != BatchJobControl.Status.PENDING) {
                                            continue;
                                        }
                                        BatchJobRequest request =
                                                requests.findById(control.getJobRequestId())
                                                        .orElse(null);
                                        if (request == null) {
                                            reject(control, null, "Request not found");
                                            continue;
                                        }
                                        Launch launch = apply(control, request, slots);
                                        if (launch != null) {
                                            toLaunch.add(launch);
                                            slots--;
                                        }
                                    }
                                    return toLaunch;
                                }),
                        List.of());
        for (Launch launch : launches) {
            launcher.launch(launch.request(), launch.previous());
        }
    }

    private Launch apply(BatchJobControl control, BatchJobRequest request, int freeSlots) {
        ControlCommand command = control.getCommand();
        JobStatus status = request.getStatus();
        if (status == JobStatus.PICKED) {
            return null; // starting right now: decide on the next poll
        }
        if (!command.isValidFor(status)) {
            reject(control, request, command + " is not possible while the job is " + status);
            return null;
        }
        // A switch expression: a new command does not compile until it is handled here.
        return switch (command) {
            case STOP, PAUSE -> stopOrPause(control, request, command, status);
            case RESUME, RESTART -> resumeOrRestart(control, request, command, status, freeSlots);
            case ABANDON -> abandon(control, request, status);
        };
    }

    /** STOP / PAUSE: a running job stops at its next chunk; a waiting one is stopped at once. */
    private Launch stopOrPause(
            BatchJobControl control,
            BatchJobRequest request,
            ControlCommand command,
            JobStatus status) {
        if (status == JobStatus.RUNNING) {
            try {
                request.requestStop(command.name());
                requests.save(request);
                if (!stopper.stop(request.getJobExecutionId())) {
                    throw new JobExecutionNotRunningException(
                            "Execution " + request.getJobExecutionId() + " not running");
                }
                applied(
                        control,
                        request,
                        status,
                        null,
                        command == ControlCommand.PAUSE
                                ? "Pause signalled: stops after the current chunk, resumable"
                                : "Stop signalled: stops after the current chunk");
            } catch (JobExecutionNotRunningException notRunning) {
                request.requestStop(null);
                requests.save(request);
                reject(control, request, "The execution is no longer running");
            }
        } else {
            // PENDING (cancel) or PAUSED (stop for good): nothing runs, so it is immediate.
            request.cancel(clock.instant());
            requests.save(request);
            applied(
                    control,
                    request,
                    status,
                    JobStatus.STOPPED,
                    status == JobStatus.PENDING
                            ? "Cancelled before start"
                            : "Stopped while paused");
            journal.record(
                    request.getId(),
                    JobJournal.Event.CANCELLED,
                    control.getRequestedByLogin(),
                    status,
                    JobStatus.STOPPED,
                    request.getJobExecutionId(),
                    control.getReason());
        }
        return null;
    }

    /**
     * RESUME / RESTART within the restart window: picked by this node and relaunched from the last
     * committed chunk; with no free job thread the command waits for the next poll.
     */
    private Launch resumeOrRestart(
            BatchJobControl control,
            BatchJobRequest request,
            ControlCommand command,
            JobStatus status,
            int freeSlots) {
        if (halts.existsByReleasedAtIsNull()) {
            reject(control, request, "Batch processing is halted: release the halt first");
            return null;
        }
        if (!service.withinRestartWindow(request)) {
            reject(
                    control,
                    request,
                    "Restart window of " + properties.restartWindow().toHours() + " h has passed");
            return null;
        }
        if (freeSlots <= 0) {
            return null; // stays PENDING until a job thread is free
        }
        request.pick(instance.id(), clock.instant());
        requests.save(request);
        applied(
                control,
                request,
                status,
                JobStatus.PICKED,
                command == ControlCommand.RESUME
                        ? "Resuming from the last committed chunk"
                        : "Restarting from the last committed chunk");
        return new Launch(request, status);
    }

    /** ABANDON, as approved by the checker: the job ends for good; nothing is launched. */
    private Launch abandon(BatchJobControl control, BatchJobRequest request, JobStatus status) {
        String detail = "ABANDON by " + control.getRequestedByLogin() + ": " + control.getReason();
        if (abandoner.abandon(request, control.getRequestedByLogin(), truncate(detail))) {
            control.applied(instance.id(), "Abandoned for good", clock.instant());
            controls.save(control);
            log.info(
                    "event=JOB_CONTROL_APPLIED jobRequestId={} command=ABANDON from={}",
                    request.getId(),
                    status);
        } else {
            reject(control, request, "The execution is running: stop it first");
        }
        return null;
    }

    /** batch_job_audit.detail holds 500 characters. */
    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private void applied(
            BatchJobControl control,
            BatchJobRequest request,
            JobStatus from,
            JobStatus to,
            String message) {
        control.applied(instance.id(), message, clock.instant());
        controls.save(control);
        journal.record(
                request.getId(),
                JobJournal.Event.CONTROL_APPLIED,
                instance.id(),
                from,
                to,
                request.getJobExecutionId(),
                control.getCommand() + " by " + control.getRequestedByLogin() + ": " + message);
        log.info(
                "event=JOB_CONTROL_APPLIED jobRequestId={} command={} from={}",
                request.getId(),
                control.getCommand(),
                from);
    }

    private void reject(BatchJobControl control, BatchJobRequest request, String message) {
        control.rejected(instance.id(), message, clock.instant());
        controls.save(control);
        if (request != null) {
            journal.record(
                    request.getId(),
                    JobJournal.Event.CONTROL_REJECTED,
                    instance.id(),
                    request.getStatus(),
                    null,
                    request.getJobExecutionId(),
                    control.getCommand() + ": " + message);
        }
        log.info(
                "event=JOB_CONTROL_REJECTED controlId={} command={} reason=\"{}\"",
                control.getId(),
                control.getCommand(),
                message);
    }
}
