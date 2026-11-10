package in.gov.fci.payroll.jobs.batch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.JobStopper;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 05j step j4 (SRS-15-24, SRS-15-25): while halted, each job this node runs is told to stop at
 * its next chunk, once; a job already stopping or not yet running is left alone; not halted, the
 * enforcer does nothing.
 */
class BatchHaltEnforcerTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final JobRepositories.Halts halts = mock(JobRepositories.Halts.class);
    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobLaunchService launcher = mock(JobLaunchService.class);
    private final JobStopper stopper = mock(JobStopper.class);
    private final JobJournal journal = mock(JobJournal.class);
    private final BatchInstance instance = mock(BatchInstance.class);
    private final BatchHaltEnforcer enforcer =
            new BatchHaltEnforcer(
                    halts,
                    requests,
                    launcher,
                    stopper,
                    journal,
                    instance,
                    new TransactionTemplate(mock(PlatformTransactionManager.class)));

    @BeforeEach
    void node() {
        when(instance.id()).thenReturn("node-1");
    }

    private static BatchJobRequest request(long id, JobStatus status) {
        BatchJobRequest r =
                BatchJobRequest.submit(
                        JobType.PAYROLL_DRY_RUN,
                        "{}",
                        "hash-" + id,
                        5,
                        BatchJobRequest.Source.UI,
                        2L,
                        "maker@x",
                        10L,
                        "2026-09",
                        NOW.minusSeconds(600));
        ReflectionTestUtils.setField(r, "id", id);
        r.pick("node-1", NOW.minusSeconds(60));
        if (status == JobStatus.RUNNING) {
            r.started(70 + id, NOW.minusSeconds(60));
        }
        return r;
    }

    // BMAD TR-067: the global halt is owner decision OD-02 (no SRS / FRS / DDL text), not
    // SRS-15-24/25.
    @Test
    void OD_02_notHaltedNothingHappens() {
        assertThat(enforcer.enforce()).isFalse();
        verifyNoInteractions(launcher, stopper, requests, journal);
    }

    @Test
    void OD_02_haltedEveryRunningJobIsStoppedAtItsNextChunkOnce() {
        when(halts.existsByReleasedAtIsNull()).thenReturn(true);
        BatchJobRequest running = request(1, JobStatus.RUNNING);
        BatchJobRequest pausing = request(2, JobStatus.RUNNING);
        pausing.requestStop("PAUSE");
        BatchJobRequest starting = request(3, JobStatus.PICKED);
        when(launcher.running()).thenReturn(Set.of(1L, 2L, 3L));
        when(requests.findAllById(any())).thenReturn(List.of(running, pausing, starting));
        when(stopper.stop(71L)).thenReturn(true);

        assertThat(enforcer.enforce()).isTrue();

        assertThat(running.getStopIntent())
                .as("stops for good, restartable later")
                .isEqualTo("STOP");
        verify(requests).save(running);
        verify(journal)
                .record(
                        1L,
                        JobJournal.Event.HALTED,
                        "node-1",
                        JobStatus.RUNNING,
                        null,
                        71L,
                        "Batch processing halted: stops after the current chunk");
        verify(stopper).stop(71L);
        // Already stopping: its intent stands; not yet running: nothing to stop.
        assertThat(pausing.getStopIntent()).isEqualTo("PAUSE");
        verify(stopper, never()).stop(72L);
        verify(requests, never()).save(pausing);
        verify(requests, never()).save(starting);
        assertThat(starting.getStopIntent()).isNull();
    }

    @Test
    void OD_02_haltedWithNothingRunningStillLaunchesNothing() {
        when(halts.existsByReleasedAtIsNull()).thenReturn(true);
        when(launcher.running()).thenReturn(Set.of());
        when(requests.findAllById(any())).thenReturn(List.of());
        assertThat(enforcer.enforce()).isTrue();
        verifyNoInteractions(stopper, journal);
    }
}
