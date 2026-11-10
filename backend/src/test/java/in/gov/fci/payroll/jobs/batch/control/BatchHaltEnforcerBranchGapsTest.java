package in.gov.fci.payroll.jobs.batch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.JobStopper;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OD-02 global halt: while halted the poller launches nothing, even when the transaction that marks
 * the running jobs hands back no list (nothing is signalled then).
 */
class BatchHaltEnforcerBranchGapsTest {

    @Test
    void OD_02_whileHaltedAMarkingThatReturnsNoListSignalsNothing() {
        JobRepositories.Halts halts = mock(JobRepositories.Halts.class);
        when(halts.existsByReleasedAtIsNull()).thenReturn(true);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenReturn(null);
        JobStopper stopper = mock(JobStopper.class);
        BatchHaltEnforcer enforcer =
                new BatchHaltEnforcer(
                        halts,
                        mock(JobRepositories.JobRequests.class),
                        mock(JobLaunchService.class),
                        stopper,
                        mock(JobJournal.class),
                        mock(BatchInstance.class),
                        tx);
        assertThat(enforcer.enforce()).isTrue();
        Mockito.verifyNoInteractions(stopper);
    }
}
