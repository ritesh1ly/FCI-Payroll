package in.gov.fci.payroll.payroll.batch.simulation;

import in.gov.fci.payroll.jobs.batch.listener.JobLifecycleListener;
import in.gov.fci.payroll.jobs.batch.listener.StepLoggingListener;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.batch.support.RequestParametersValidator;
import in.gov.fci.payroll.payroll.orchestration.SimulationEngine;
import in.gov.fci.payroll.payroll.orchestration.SimulationRecords;
import java.util.List;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * SIMULATION_RUN (plan 05 phase 05i, D-21; SRS-15-7): a C-09 simulation above the interactive
 * limit, one tasklet step. It computes the run record's request with {@link SimulationEngine} (no
 * lease, no cycle instance, nothing written but the record), publishing the members done after each
 * page in its own transaction so C-09 shows the progress, and keeps the summary on the record; a
 * failure is written on the record with its reason. A restart computes from the start: there is
 * nothing partial to continue.
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class SimulationJobConfig {

    public static final String JOB = "simulationRun";

    @Bean
    Job simulationRunJob(
            JobRepository jobs, Step simulationRunStep, JobLifecycleListener lifecycle) {
        return new JobBuilder(JOB, jobs)
                .validator(
                        new RequestParametersValidator(
                                List.of("simulationId", "locationId", "payPeriod")))
                .listener(lifecycle)
                .start(simulationRunStep)
                .build();
    }

    @Bean
    Step simulationRunStep(
            JobRepository jobs,
            PlatformTransactionManager transactionManager,
            SimulationEngine engine,
            SimulationRecords records,
            StepLoggingListener logging) {
        return new StepBuilder("simulationRunStep", jobs)
                .tasklet(
                        (contribution, chunk) -> {
                            long id =
                                    Long.parseLong(
                                            (String)
                                                    chunk.getStepContext()
                                                            .getJobParameters()
                                                            .get("simulationId"));
                            SimulationRecords.Doc doc = run(engine, records, id);
                            contribution.incrementReadCount();
                            chunk.getStepContext()
                                    .getStepExecution()
                                    .getExecutionContext()
                                    .putString(
                                            BatchKeys.SUMMARY,
                                            "Simulation SIM-"
                                                    + id
                                                    + ": "
                                                    + doc.done()
                                                    + " members read (never posted)");
                            return RepeatStatus.FINISHED;
                        },
                        transactionManager)
                .listener(logging)
                .build();
    }

    /** Runs the record's request; the record says RUNNING, its progress, then the outcome. */
    public static SimulationRecords.Doc run(
            SimulationEngine engine, SimulationRecords records, long id) {
        SimulationRecords.Doc doc =
                records.find(id)
                        .map(SimulationRecords.Row::doc)
                        .orElseThrow(
                                () -> new IllegalStateException("No simulation record SIM-" + id));
        records.running(id, doc);
        try {
            SimulationEngine.Outcome outcome =
                    engine.run(
                            doc.request(),
                            false,
                            done -> records.progress(id, doc.progressed(done)));
            records.complete(id, doc, outcome);
            return doc.progressed(outcome.population());
        } catch (RuntimeException failed) {
            records.fail(id, doc, String.valueOf(failed.getMessage()));
            throw failed;
        }
    }
}
