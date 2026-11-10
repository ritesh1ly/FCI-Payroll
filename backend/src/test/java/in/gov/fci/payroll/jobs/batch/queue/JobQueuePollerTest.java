package in.gov.fci.payroll.jobs.batch.queue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.batch.control.BatchHaltEnforcer;
import in.gov.fci.payroll.jobs.batch.control.ControlCommandProcessor;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.recovery.AbandonSweep;
import in.gov.fci.payroll.jobs.batch.recovery.CrashRecoveryService;
import in.gov.fci.payroll.jobs.batch.recovery.GracefulShutdown;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * One poll of the batch node (SRS-15-25): crash recovery first, then the pending control commands,
 * then new requests up to the free job slots and the claim batch. Nothing while the node shuts
 * down, and a failed poll is logged without stopping the next one.
 */
class JobQueuePollerTest {

    private final JobClaimer claimer = mock(JobClaimer.class);
    private final JobLaunchService launcher = mock(JobLaunchService.class);
    private final ControlCommandProcessor controls = mock(ControlCommandProcessor.class);
    private final CrashRecoveryService recovery = mock(CrashRecoveryService.class);
    private final AbandonSweep sweep = mock(AbandonSweep.class);
    private final BatchHaltEnforcer halt = mock(BatchHaltEnforcer.class);
    private final GracefulShutdown shutdown = mock(GracefulShutdown.class);
    private final BatchProperties properties = mock(BatchProperties.class);

    private final JobQueuePoller poller =
            new JobQueuePoller(
                    claimer, launcher, controls, recovery, sweep, halt, shutdown, properties);

    @BeforeEach
    void wire() {
        BatchProperties.Poller settings = mock(BatchProperties.Poller.class);
        when(settings.maxConcurrentJobs()).thenReturn(4);
        when(settings.claimBatchSize()).thenReturn(2);
        when(properties.poller()).thenReturn(settings);
        when(shutdown.isAccepting()).thenReturn(true);
    }

    @Test
    void SRS_15_25_aPollRecoversThenAppliesCommandsThenClaimsUpToTheFreeSlotsAndTheBatch() {
        when(launcher.freeSlots(4)).thenReturn(3);
        BatchJobRequest first = mock(BatchJobRequest.class);
        BatchJobRequest second = mock(BatchJobRequest.class);
        when(claimer.claimPending(2, 3, 2)).thenReturn(List.of(first, second));

        poller.poll();

        InOrder order = inOrder(recovery, controls, claimer, launcher);
        order.verify(recovery).recoverAbandoned();
        order.verify(controls).processPending();
        order.verify(claimer).claimPending(2, 3, 2);
        order.verify(launcher).launch(first, JobStatus.PENDING);
        order.verify(launcher).launch(second, JobStatus.PENDING);
    }

    @Test
    void SRS_15_25_aNodeWithoutAFreeSlotClaimsNothing() {
        when(launcher.freeSlots(4)).thenReturn(0);
        poller.poll();
        verify(controls).processPending();
        verify(claimer, never()).claimPending(anyInt(), anyInt(), anyInt());
    }

    @Test
    void SRS_15_25_aNodeShuttingDownTakesNoWork() {
        when(shutdown.isAccepting()).thenReturn(false);
        poller.poll();
        verify(recovery, never()).recoverAbandoned();
        verify(controls, never()).processPending();
        verify(claimer, never()).claimPending(anyInt(), anyInt(), anyInt());
    }

    @Test
    void SRS_15_25_aFailedPollIsLoggedAndTheNextOneRuns() {
        when(recovery.recoverAbandoned()).thenThrow(new IllegalStateException("database away"));
        poller.poll();
        verify(controls, never()).processPending();
        verify(launcher, never()).launch(any(), any());
    }

    @Test
    void SRS_15_27_expiredJobsAreAbandonedAfterRecoveryAndBeforeTheCommands() {
        when(launcher.freeSlots(4)).thenReturn(1);
        poller.poll();
        InOrder order = inOrder(recovery, sweep, controls, halt, claimer);
        order.verify(recovery).recoverAbandoned();
        order.verify(sweep).sweep();
        order.verify(controls).processPending();
        order.verify(halt).enforce();
        order.verify(claimer).claimPending(1, 3, 2);
    }

    @Test
    void SRS_15_25_whileHaltedTheNodeStopsItsJobsAppliesCommandsAndClaimsNothing() {
        when(halt.enforce()).thenReturn(true);
        when(launcher.freeSlots(4)).thenReturn(3);
        poller.poll();
        verify(sweep).sweep();
        verify(controls).processPending();
        verify(claimer, never()).claimPending(anyInt(), anyInt(), anyInt());
        verify(launcher, never()).launch(any(), any());
    }

    // Deep review DR-17: the last slot of a node stays for priority work.
    @Test
    void SRS_NF_11_bulkWorkNeverTakesTheLastSlotOfANodeWithMoreThanOne() {
        when(launcher.freeSlots(4)).thenReturn(2);
        when(claimer.runningLowPriority()).thenReturn(2);
        poller.poll();
        verify(claimer).claimPending(2, 1, 2);
        when(claimer.runningLowPriority()).thenReturn(3);
        poller.poll();
        verify(claimer).claimPending(2, 0, 2);
        when(claimer.runningLowPriority()).thenReturn(5);
        poller.poll();
        verify(claimer, times(2)).claimPending(2, 0, 2);
    }

    @Test
    void SRS_NF_11_aSingleSlotNodeStillRunsBulkWork() {
        BatchProperties.Poller one = mock(BatchProperties.Poller.class);
        when(one.maxConcurrentJobs()).thenReturn(1);
        when(one.claimBatchSize()).thenReturn(10);
        when(properties.poller()).thenReturn(one);
        when(launcher.freeSlots(1)).thenReturn(1);
        poller.poll();
        verify(claimer).claimPending(1, 1, 10);
        verify(claimer, never()).runningLowPriority();
    }
}
