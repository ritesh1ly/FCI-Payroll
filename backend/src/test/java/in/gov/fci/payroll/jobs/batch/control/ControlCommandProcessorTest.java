package in.gov.fci.payroll.jobs.batch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.queue.JobClaimer;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.JobAbandoner;
import in.gov.fci.payroll.jobs.batch.support.JobStopper;
import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applying the Stop / Pause / Resume / Restart commands recorded on C-13 (SRS-15-25: keep the
 * committed work, resume or restart the rest). Each command is re-checked against the request as it
 * is now: a command already decided, or one whose request is gone, is never applied; a stop of a
 * running job is signalled, and rejected if the execution has already ended; a stop of a waiting
 * job is immediate; a resume or restart runs only within the restart window and with a free job
 * thread, else it waits for the next poll.
 */
class ControlCommandProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");

    private final JobClaimer claimer = mock(JobClaimer.class);
    private final JobRepositories.Controls controls = mock(JobRepositories.Controls.class);
    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobStopper stopper = mock(JobStopper.class);
    private final JobAbandoner abandoner = mock(JobAbandoner.class);
    private final JobRepositories.Halts halts = mock(JobRepositories.Halts.class);
    private final JobLaunchService launcher = mock(JobLaunchService.class);
    private final JobRequestService service = mock(JobRequestService.class);
    private final JobJournal journal = mock(JobJournal.class);
    private final BatchInstance instance = mock(BatchInstance.class);
    private final BatchProperties properties = mock(BatchProperties.class);

    private final ControlCommandProcessor processor =
            new ControlCommandProcessor(
                    claimer,
                    controls,
                    requests,
                    stopper,
                    abandoner,
                    halts,
                    launcher,
                    service,
                    journal,
                    instance,
                    properties,
                    new TransactionTemplate(mock(PlatformTransactionManager.class)),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final List<Long> pending = new ArrayList<>();
    private long nextId = 1;

    @BeforeEach
    void wire() {
        BatchProperties.Poller poller = mock(BatchProperties.Poller.class);
        when(poller.maxConcurrentJobs()).thenReturn(2);
        when(poller.claimBatchSize()).thenReturn(10);
        when(properties.poller()).thenReturn(poller);
        // The configured period (CLR-132, BMAD TR-079): a test value, never the 72 h default.
        when(properties.restartWindow()).thenReturn(Duration.ofHours(5));
        when(instance.id()).thenReturn("node-1");
        when(claimer.lockPendingControls(10)).thenReturn(pending);
    }

    /** A request in {@code status} as the queue holds it. */
    private BatchJobRequest request(JobStatus status) {
        BatchJobRequest r =
                BatchJobRequest.submit(
                        JobType.PAYROLL_DRY_RUN,
                        "{}",
                        "hash",
                        5,
                        BatchJobRequest.Source.UI,
                        2L,
                        "maker@x",
                        10L,
                        "2026-09",
                        NOW.minusSeconds(3600));
        long id = nextId++;
        ReflectionTestUtils.setField(r, "id", id);
        if (status != JobStatus.PENDING) {
            r.pick("node-2", NOW);
            r.started(70 + id, NOW);
            if (status != JobStatus.RUNNING) {
                ReflectionTestUtils.setField(r, "status", status);
            }
        }
        when(requests.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    /** A PENDING command on {@code requestId}, locked by this pass. */
    private BatchJobControl command(Long requestId, ControlCommand command) {
        BatchJobControl c =
                BatchJobControl.request(requestId, command, 2L, "maker@x", "Reason", NOW);
        long id = 100 + pending.size();
        ReflectionTestUtils.setField(c, "id", id);
        pending.add(id);
        when(controls.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void SRS_15_25_aCommandAlreadyDecidedOrForARequestThatIsGoneIsNeverApplied() {
        pending.add(1L); // no longer there
        BatchJobControl decided = command(request(JobStatus.RUNNING).getId(), ControlCommand.STOP);
        decided.applied("node-2", "done", NOW);
        BatchJobControl orphan = command(999L, ControlCommand.STOP);

        processor.processPending();

        assertThat(decided.getStatus()).isEqualTo(BatchJobControl.Status.APPLIED);
        assertThat(decided.getOutcomeMessage()).as("untouched").isEqualTo("done");
        assertThat(orphan.getStatus()).isEqualTo(BatchJobControl.Status.REJECTED);
        assertThat(orphan.getOutcomeMessage()).isEqualTo("Request not found");
        verify(stopper, never()).stop(any());
        verify(journal, never()).record(any(), any(), any(), any(), any(), any(), any());
        verify(launcher, never()).launch(any(), any());
    }

    @Test
    void SRS_15_25_aStopOrPauseOfARunningJobIsSignalledAndRejectedOnceItHasEnded() {
        BatchJobRequest stopped = request(JobStatus.RUNNING);
        BatchJobRequest paused = request(JobStatus.RUNNING);
        BatchJobRequest ended = request(JobStatus.RUNNING);
        BatchJobControl stop = command(stopped.getId(), ControlCommand.STOP);
        BatchJobControl pause = command(paused.getId(), ControlCommand.PAUSE);
        BatchJobControl late = command(ended.getId(), ControlCommand.PAUSE);
        when(stopper.stop(stopped.getJobExecutionId())).thenReturn(true);
        when(stopper.stop(paused.getJobExecutionId())).thenReturn(true);
        when(stopper.stop(ended.getJobExecutionId())).thenReturn(false);

        processor.processPending();

        assertThat(stopped.getStopIntent()).isEqualTo("STOP");
        assertThat(stop.getOutcomeMessage())
                .isEqualTo("Stop signalled: stops after the current chunk");
        assertThat(paused.getStopIntent()).isEqualTo("PAUSE");
        assertThat(pause.getOutcomeMessage())
                .isEqualTo("Pause signalled: stops after the current chunk, resumable");
        assertThat(late.getStatus()).isEqualTo(BatchJobControl.Status.REJECTED);
        assertThat(late.getOutcomeMessage()).isEqualTo("The execution is no longer running");
        assertThat(ended.getStopIntent()).as("the intent is withdrawn").isNull();
        assertThat(ended.getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void SRS_15_25_aStopOfAWaitingJobIsImmediate() {
        BatchJobRequest queued = request(JobStatus.PENDING);
        BatchJobRequest paused = request(JobStatus.PAUSED);
        BatchJobControl cancel = command(queued.getId(), ControlCommand.STOP);
        BatchJobControl stop = command(paused.getId(), ControlCommand.STOP);

        processor.processPending();

        assertThat(queued.getStatus()).isEqualTo(JobStatus.STOPPED);
        assertThat(cancel.getOutcomeMessage()).isEqualTo("Cancelled before start");
        assertThat(paused.getStatus()).isEqualTo(JobStatus.STOPPED);
        assertThat(stop.getOutcomeMessage()).isEqualTo("Stopped while paused");
        verify(journal)
                .record(
                        eq(queued.getId()),
                        eq(JobJournal.Event.CANCELLED),
                        eq("maker@x"),
                        eq(JobStatus.PENDING),
                        eq(JobStatus.STOPPED),
                        any(),
                        eq("Reason"));
        verify(stopper, never()).stop(any());
    }

    @Test
    void SRS_15_25_aResumeOrRestartRunsWithinTheWindowWhileAJobThreadIsFree() {
        when(launcher.freeSlots(2)).thenReturn(1);
        BatchJobRequest old = request(JobStatus.PAUSED);
        BatchJobRequest failed = request(JobStatus.FAILED);
        BatchJobRequest paused = request(JobStatus.PAUSED);
        BatchJobControl expired = command(old.getId(), ControlCommand.RESUME);
        BatchJobControl restart = command(failed.getId(), ControlCommand.RESTART);
        BatchJobControl waiting = command(paused.getId(), ControlCommand.RESUME);
        when(service.withinRestartWindow(old)).thenReturn(false);
        when(service.withinRestartWindow(failed)).thenReturn(true);
        when(service.withinRestartWindow(paused)).thenReturn(true);

        processor.processPending();

        assertThat(expired.getOutcomeMessage()).isEqualTo("Restart window of 5 h has passed");
        assertThat(failed.getStatus()).isEqualTo(JobStatus.PICKED);
        assertThat(restart.getOutcomeMessage())
                .isEqualTo("Restarting from the last committed chunk");
        verify(launcher).launch(failed, JobStatus.FAILED);
        assertThat(waiting.getStatus())
                .as("no free thread left: it waits for the next poll")
                .isEqualTo(BatchJobControl.Status.PENDING);
        assertThat(paused.getStatus()).isEqualTo(JobStatus.PAUSED);
    }

    @Test
    void SRS_15_25_aResumeNamesItselfAndAJobStartingNowIsDecidedOnTheNextPoll() {
        when(launcher.freeSlots(2)).thenReturn(2);
        BatchJobRequest paused = request(JobStatus.PAUSED);
        BatchJobRequest starting = request(JobStatus.PICKED);
        BatchJobRequest queued = request(JobStatus.PENDING);
        BatchJobControl resume = command(paused.getId(), ControlCommand.RESUME);
        BatchJobControl early = command(starting.getId(), ControlCommand.STOP);
        BatchJobControl invalid = command(queued.getId(), ControlCommand.PAUSE);
        when(service.withinRestartWindow(paused)).thenReturn(true);

        processor.processPending();

        assertThat(resume.getOutcomeMessage()).isEqualTo("Resuming from the last committed chunk");
        verify(launcher).launch(paused, JobStatus.PAUSED);
        assertThat(early.getStatus()).isEqualTo(BatchJobControl.Status.PENDING);
        assertThat(invalid.getOutcomeMessage())
                .isEqualTo("PAUSE is not possible while the job is PENDING");
    }

    @Test
    void SRS_15_27_anApprovedAbandonEndsTheJobForGoodAndLaunchesNothing() {
        BatchJobRequest failed = request(JobStatus.FAILED);
        BatchJobControl abandon = command(failed.getId(), ControlCommand.ABANDON);
        when(abandoner.abandon(failed, "maker@x", "ABANDON by maker@x: Reason")).thenReturn(true);

        processor.processPending();

        verify(abandoner).abandon(failed, "maker@x", "ABANDON by maker@x: Reason");
        assertThat(abandon.getStatus()).isEqualTo(BatchJobControl.Status.APPLIED);
        assertThat(abandon.getOutcomeMessage()).isEqualTo("Abandoned for good");
        verify(launcher, never()).launch(any(), any());
    }

    @Test
    void SRS_15_27_anAbandonOfAJobWhoseExecutionStillRunsIsRejected() {
        BatchJobRequest paused = request(JobStatus.PAUSED);
        BatchJobControl abandon = command(paused.getId(), ControlCommand.ABANDON);
        when(abandoner.abandon(paused, "maker@x", "ABANDON by maker@x: Reason")).thenReturn(false);

        processor.processPending();

        assertThat(abandon.getStatus()).isEqualTo(BatchJobControl.Status.REJECTED);
        assertThat(abandon.getOutcomeMessage())
                .isEqualTo("The execution is running: stop it first");
        assertThat(paused.getStatus()).isEqualTo(JobStatus.PAUSED);
    }

    @Test
    void OD_02_whileBatchProcessingIsHaltedNoResumeOrRestartIsApplied() {
        when(halts.existsByReleasedAtIsNull()).thenReturn(true);
        when(launcher.freeSlots(2)).thenReturn(2);
        BatchJobRequest failed = request(JobStatus.FAILED);
        BatchJobRequest paused = request(JobStatus.PAUSED);
        BatchJobControl restart = command(failed.getId(), ControlCommand.RESTART);
        BatchJobControl resume = command(paused.getId(), ControlCommand.RESUME);
        when(service.withinRestartWindow(any())).thenReturn(true);

        processor.processPending();

        for (BatchJobControl refused : List.of(restart, resume)) {
            assertThat(refused.getStatus()).isEqualTo(BatchJobControl.Status.REJECTED);
            assertThat(refused.getOutcomeMessage())
                    .isEqualTo("Batch processing is halted: release the halt first");
        }
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(paused.getStatus()).isEqualTo(JobStatus.PAUSED);
        verify(launcher, never()).launch(any(), any());
    }

    /** SRS-15-27: the abandon's journal detail is cut to the 500 of batch_job_audit.detail. */
    @Test
    void SRS_15_27_theAbandonDetailIsCutToItsColumn() {
        BatchJobRequest failed = request(JobStatus.FAILED);
        BatchJobControl abandon =
                BatchJobControl.request(
                        failed.getId(),
                        ControlCommand.ABANDON,
                        2L,
                        "maker@x",
                        "r".repeat(500),
                        NOW);
        ReflectionTestUtils.setField(abandon, "id", 150L);
        pending.add(150L);
        when(controls.findById(150L)).thenReturn(Optional.of(abandon));
        String detail = ("ABANDON by maker@x: " + "r".repeat(500)).substring(0, 500);
        when(abandoner.abandon(failed, "maker@x", detail)).thenReturn(true);

        processor.processPending();

        verify(abandoner).abandon(failed, "maker@x", detail);
        assertThat(abandon.getStatus()).isEqualTo(BatchJobControl.Status.APPLIED);
    }
}
