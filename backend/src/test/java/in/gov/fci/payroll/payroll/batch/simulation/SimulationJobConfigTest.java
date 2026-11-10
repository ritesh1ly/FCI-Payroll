package in.gov.fci.payroll.payroll.batch.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.batch.listener.JobLifecycleListener;
import in.gov.fci.payroll.jobs.batch.listener.StepLoggingListener;
import in.gov.fci.payroll.payroll.business.SimulationRules;
import in.gov.fci.payroll.payroll.orchestration.SimulationAbandonment;
import in.gov.fci.payroll.payroll.orchestration.SimulationEngine;
import in.gov.fci.payroll.payroll.orchestration.SimulationRecords;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * SIMULATION_RUN (plan 05 phase 05i, D-21; SRS-15-7, SRS-15-27): the job marks its record RUNNING,
 * publishes progress, keeps the outcome, records a failure with its reason; an abandoned request
 * closes only its own record.
 */
class SimulationJobConfigTest {

    private static final SimulationRecords.Doc DOC =
            new SimulationRecords.Doc(
                    SimulationRecords.Status.QUEUED,
                    SimulationRules.Mode.BACKGROUND,
                    "2026-10",
                    7L,
                    "DH001",
                    SimulationRules.Population.OFFICE,
                    null,
                    SimulationRules.WhatIf.AS_IS,
                    null,
                    null,
                    99L,
                    0,
                    null,
                    null,
                    null,
                    null);

    private static SimulationRecords.Row row() {
        return new SimulationRecords.Row(
                5, "maker", Instant.EPOCH, "C09;202610;DH001;", 6, "PENDING", DOC);
    }

    @Test
    void SRS_15_7_theJobComputesTheRecordWithProgressAndKeepsTheOutcome() {
        SimulationEngine engine = mock(SimulationEngine.class);
        SimulationRecords records = mock(SimulationRecords.class);
        when(records.find(5)).thenReturn(Optional.of(row()));
        SimulationEngine.Outcome outcome =
                new SimulationEngine.Outcome(6, null, null, "hash", "versions", List.of());
        when(engine.run(any(), anyBoolean(), any()))
                .thenAnswer(
                        call -> {
                            ((LongConsumer) call.getArgument(2)).accept(6);
                            return outcome;
                        });
        SimulationRecords.Doc done = SimulationJobConfig.run(engine, records, 5);
        assertThat(done.done()).isEqualTo(6);
        verify(records).running(5, DOC);
        verify(records).progress(eq(5L), any());
        verify(records).complete(5, DOC, outcome);
        verify(engine).run(eq(DOC.request()), eq(false), any());
    }

    @Test
    void SRS_15_7_aFailureIsWrittenOnTheRecordAndTheJobFails() {
        SimulationEngine engine = mock(SimulationEngine.class);
        SimulationRecords records = mock(SimulationRecords.class);
        when(records.find(5)).thenReturn(Optional.of(row()));
        when(engine.run(any(), anyBoolean(), any())).thenThrow(new IllegalStateException("boom"));
        assertThatThrownBy(() -> SimulationJobConfig.run(engine, records, 5)).hasMessage("boom");
        verify(records).fail(5, DOC, "boom");
        verify(records, never()).complete(anyLong(), any(), any());
    }

    @Test
    void SRS_15_7_aMissingRecordFailsTheJob() {
        SimulationRecords records = mock(SimulationRecords.class);
        when(records.find(5)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> SimulationJobConfig.run(mock(SimulationEngine.class), records, 5))
                .hasMessageContaining("SIM-5");
    }

    @Test
    void SRS_15_27_anAbandonedSimulationRequestClosesItsRecordOnly() {
        SimulationRecords records = mock(SimulationRecords.class);
        SimulationAbandonment abandonment = new SimulationAbandonment(records);
        abandonment.abandoned(99, "PAYROLL_COMPUTATION");
        verify(records, never()).abandoned(anyLong());
        abandonment.abandoned(99, "SIMULATION_RUN");
        verify(records).abandoned(99);
    }

    @Test
    void SRS_15_7_aRecordDescribesItsRequestAndItsScopeKey() {
        assertThat(DOC.request().unitId()).isEqualTo(7);
        assertThat(DOC.request().period().toString()).isEqualTo("2026-10");
        assertThat(DOC.progressed(3).status()).isEqualTo(SimulationRecords.Status.RUNNING);
        assertThat(SimulationRecords.prefix("202610", "DH001")).isEqualTo("C09;202610;DH001;");
    }

    @Test
    void SRS_15_7_theJobRunsOneTaskletThatComputesTheRecordNamedByItsParameter() throws Exception {
        SimulationJobConfig config = new SimulationJobConfig();
        JobRepository repository = mock(JobRepository.class);
        Job job =
                config.simulationRunJob(
                        repository,
                        mock(org.springframework.batch.core.step.Step.class),
                        mock(JobLifecycleListener.class));
        assertThat(job.getName()).isEqualTo(SimulationJobConfig.JOB);

        SimulationEngine engine = mock(SimulationEngine.class);
        SimulationRecords records = mock(SimulationRecords.class);
        when(records.find(5)).thenReturn(Optional.of(row()));
        when(engine.run(any(), anyBoolean(), any()))
                .thenReturn(new SimulationEngine.Outcome(6, null, null, "h", "v", List.of()));
        TaskletStep step =
                (TaskletStep)
                        config.simulationRunStep(
                                repository,
                                mock(PlatformTransactionManager.class),
                                engine,
                                records,
                                mock(StepLoggingListener.class));
        ChunkContext chunk = mock(ChunkContext.class, Answers.RETURNS_DEEP_STUBS);
        when(chunk.getStepContext().getJobParameters()).thenReturn(Map.of("simulationId", "5"));
        ExecutionContext context = new ExecutionContext();
        when(chunk.getStepContext().getStepExecution().getExecutionContext()).thenReturn(context);
        StepContribution contribution = mock(StepContribution.class);
        assertThat(step.getTasklet().execute(contribution, chunk)).isEqualTo(RepeatStatus.FINISHED);
        verify(contribution).incrementReadCount();
        assertThat(context.getString(in.gov.fci.payroll.jobs.batch.support.BatchKeys.SUMMARY))
                .isEqualTo("Simulation SIM-5: 6 members read (never posted)");
    }
}
