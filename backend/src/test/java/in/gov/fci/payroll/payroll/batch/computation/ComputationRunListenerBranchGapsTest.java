package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.orchestration.ComputationResumeCheck;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;

/**
 * SRS-15-26 / BR-CYC-011: a run is opened for an execution that starts, never for one the request
 * listener already stopped (a request no longer picked); a request carrying no parameter hash opens
 * its run with the placeholder hash "-".
 */
class ComputationRunListenerBranchGapsTest {

    private final CycleRuns cycles = mock(CycleRuns.class);
    private final PayConfiguration configuration = mock(PayConfiguration.class);
    private final StaffDeductionRunner deductions = mock(StaffDeductionRunner.class);
    private final JobRepository repository = mock(JobRepository.class);
    private final ComputationRunListener listener =
            new ComputationRunListener(
                    cycles,
                    configuration,
                    mock(ComputationResumeCheck.class),
                    deductions,
                    "payrollComputation",
                    CycleRuns.RunType.ACTUAL,
                    repository);

    private static JobExecution execution() {
        return new JobExecution(
                50L,
                new JobInstance(5L, "payrollComputation"),
                new JobParametersBuilder()
                        .addLong(BatchKeys.REQUEST_ID, 77L)
                        .addString("locationId", "40")
                        .addString("payPeriod", "2026-10")
                        .toJobParameters());
    }

    @Test
    void SRS_15_26_anExecutionAlreadyStoppedOpensNoRun() {
        JobExecution stopped = execution();
        stopped.setStatus(BatchStatus.STOPPING);
        listener.beforeJob(stopped);
        verifyNoInteractions(cycles, configuration, deductions, repository);
        assertThat(stopped.getExecutionContext().isEmpty()).isTrue();
    }

    @Test
    void BR_CYC_011_aRequestWithoutAParameterHashOpensItsRunWithThePlaceholder() {
        PayConfiguration.Snapshot snapshot = mock(PayConfiguration.Snapshot.class);
        when(configuration.staffConfiguration(LocalDate.of(2026, 10, 1))).thenReturn(snapshot);
        StaffDeductionRunner.Session session = mock(StaffDeductionRunner.Session.class);
        when(deductions.open(YearMonth.of(2026, 10))).thenReturn(session);
        when(session.versionSetHash(snapshot)).thenReturn("versions");
        CycleRuns.OpenRun run =
                new CycleRuns.OpenRun(
                        31L,
                        9L,
                        40L,
                        "202610",
                        LocalDate.of(2026, 10, 1),
                        CycleRuns.RunType.ACTUAL);
        when(cycles.open(
                        77L,
                        40L,
                        YearMonth.of(2026, 10),
                        CycleRuns.RunType.ACTUAL,
                        "-",
                        "versions"))
                .thenReturn(run);
        JobExecution started = execution();
        listener.beforeJob(started);
        verify(cycles)
                .open(77L, 40L, YearMonth.of(2026, 10), CycleRuns.RunType.ACTUAL, "-", "versions");
        verify(repository).updateExecutionContext(any(JobExecution.class));
        assertThat(ComputationKeys.get(started.getExecutionContext())).isEqualTo(run);
    }
}
