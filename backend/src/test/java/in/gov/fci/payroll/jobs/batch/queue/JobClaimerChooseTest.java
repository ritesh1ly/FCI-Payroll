package in.gov.fci.payroll.jobs.batch.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-NF-11 / SRS-NF-14 (deep review DR-17): the queue takes priority work first by its type, and
 * bulk work (statutory outputs, dry runs, simulations) only within its allowance, so a node's last
 * free slot waits for the Actual Run; rows no longer PENDING are passed over.
 */
class JobClaimerChooseTest {

    private static BatchJobRequest job(JobType type, JobStatus status) {
        BatchJobRequest r = mock(BatchJobRequest.class);
        when(r.getJobType()).thenReturn(type);
        when(r.getStatus()).thenReturn(status);
        return r;
    }

    @Test
    void SRS_NF_11_theActualRunAndTheCloseComeFirstSimulationsLast() {
        assertThat(JobType.PAYROLL_COMPUTATION.priority()).isEqualTo(9);
        assertThat(JobType.MONTH_END_CLOSE.priority()).isEqualTo(9);
        assertThat(JobType.INTERFACE_PULL.priority())
                .isGreaterThan(JobType.DA_ARREAR.priority())
                .isLessThan(JobType.PAYROLL_COMPUTATION.priority());
        assertThat(JobType.HRA_ARREAR.priority()).isEqualTo(JobType.DA_ARREAR.priority());
        assertThat(JobType.TAX_24Q_RETURN.priority())
                .isEqualTo(JobType.TAX_FORM16_PART_B.priority())
                .isGreaterThan(JobType.PAYROLL_DRY_RUN.priority());
        assertThat(JobType.SIMULATION_RUN.priority()).isEqualTo(1);
        assertThat(Arrays.stream(JobType.values()).filter(JobType::isLowPriority))
                .containsExactlyInAnyOrder(
                        JobType.TAX_FORM16_PART_B,
                        JobType.TAX_24Q_RETURN,
                        JobType.PAYROLL_DRY_RUN,
                        JobType.SIMULATION_RUN,
                        // OD-149: the bulk download jobs are bulk reports (SRS-NF-11: queued behind
                        // the Actual Run, the month-end close and the other priority work).
                        JobType.PAYSLIP_BULK,
                        JobType.FORM16_BULK);
        assertThat(JobType.PAYSLIP_BULK.priority())
                .isEqualTo(JobType.FORM16_BULK.priority())
                .isLessThan(JobType.TAX_FORM16_PART_B.priority())
                .isGreaterThan(JobType.SIMULATION_RUN.priority());
    }

    @Test
    void SRS_NF_11_bulkWorkIsTakenOnlyWithinItsAllowance() {
        BatchJobRequest simulation = job(JobType.SIMULATION_RUN, JobStatus.PENDING);
        BatchJobRequest form16 = job(JobType.TAX_FORM16_PART_B, JobStatus.PENDING);
        BatchJobRequest actual = job(JobType.PAYROLL_COMPUTATION, JobStatus.PENDING);
        BatchJobRequest arrear = job(JobType.DA_ARREAR, JobStatus.PENDING);
        assertThat(JobClaimer.choose(List.of(simulation, form16, actual), 3, 1))
                .as("one bulk job, then priority work")
                .containsExactly(simulation, actual);
        assertThat(JobClaimer.choose(List.of(simulation, form16), 2, 0))
                .as("no bulk allowance: nothing")
                .isEmpty();
        assertThat(JobClaimer.choose(List.of(actual, arrear, simulation), 2, 2))
                .as("at most the free slots")
                .containsExactly(actual, arrear);
        assertThat(JobClaimer.choose(List.of(simulation, form16), 2, 2)).hasSize(2);
        assertThat(JobClaimer.choose(List.of(), 2, 2)).isEmpty();
    }

    @Test
    void SRS_15_25_aRowNoLongerPendingOrGoneIsPassedOver() {
        BatchJobRequest picked = job(JobType.PAYROLL_COMPUTATION, JobStatus.PICKED);
        BatchJobRequest actual = job(JobType.PAYROLL_COMPUTATION, JobStatus.PENDING);
        assertThat(JobClaimer.choose(Arrays.asList(null, picked, actual), 1, 0))
                .containsExactly(actual);
    }
}
