package in.gov.fci.payroll.payroll.batch.computation;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.jobs.batch.listener.ChunkProgressListener;
import in.gov.fci.payroll.jobs.batch.listener.ItemErrorListener;
import in.gov.fci.payroll.jobs.batch.listener.JobLifecycleListener;
import in.gov.fci.payroll.jobs.batch.listener.SkipRecordingListener;
import in.gov.fci.payroll.jobs.batch.listener.StepLoggingListener;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.batch.support.FaultTolerancePolicy;
import in.gov.fci.payroll.jobs.batch.support.LocationPartitioner;
import in.gov.fci.payroll.jobs.batch.support.PartitionedSteps;
import in.gov.fci.payroll.jobs.batch.support.RequestParametersValidator;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.orchestration.ComputationResumeCheck;
import in.gov.fci.payroll.payroll.orchestration.CycleLeases;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ItemProcessListener;
import org.springframework.batch.core.listener.ItemReadListener;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The staff payroll computation on the v4.0 cycle model (C-02, C-13): two jobs over one partitioned
 * step.
 *
 * <pre>
 * payrollDryRun       (PAYROLL_DRY_RUN)      computation_run SIMULATION: no cycle state changes
 * payrollComputation  (PAYROLL_COMPUTATION)  computation_run ACTUAL: cycle FROZEN -> COMPUTED
 *   payrollComputationStep (manager)  LocationPartitioner over the cycle unit's locations
 *     payrollComputationWorker:partitionN  one computation_work_unit each
 *       MemberReader -> ComputationProcessor (PayRules) -> ResultWriter
 *       chunk, fault tolerant (retry transient DB errors, skip member-data gaps), restartable
 * </pre>
 *
 * The run is opened and closed by {@link ComputationRunListener}; each partition is a work unit
 * ({@link WorkUnitListener}). Parameters: jobRequestId, locationId (any location: the run is on its
 * cycle unit), payPeriod. Rates are those in force on the first day of the payroll month.
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class ComputationJobConfig {

    public static final String DRY_RUN_JOB = "payrollDryRun";
    public static final String COMPUTATION_JOB = "payrollComputation";
    public static final String MANAGER = "payrollComputationStep";
    public static final String WORKER = "payrollComputationWorker";

    @Bean
    Job payrollDryRunJob(
            JobRepository jobs,
            @Qualifier(MANAGER) Step manager,
            JobLifecycleListener lifecycle,
            CycleRuns cycles,
            PayConfiguration configuration,
            ComputationResumeCheck resume,
            in.gov.fci.payroll.payroll.api.StaffDeductionRunner deductions) {
        return job(
                DRY_RUN_JOB,
                jobs,
                manager,
                lifecycle,
                new ComputationRunListener(
                        cycles,
                        configuration,
                        resume,
                        deductions,
                        "PAYROLL_DRY_RUN",
                        CycleRuns.RunType.SIMULATION,
                        jobs));
    }

    @Bean
    Job payrollComputationJob(
            JobRepository jobs,
            @Qualifier(MANAGER) Step manager,
            JobLifecycleListener lifecycle,
            CycleRuns cycles,
            PayConfiguration configuration,
            ComputationResumeCheck resume,
            in.gov.fci.payroll.payroll.api.StaffDeductionRunner deductions) {
        return job(
                COMPUTATION_JOB,
                jobs,
                manager,
                lifecycle,
                new ComputationRunListener(
                        cycles,
                        configuration,
                        resume,
                        deductions,
                        "PAYROLL_COMPUTATION",
                        CycleRuns.RunType.ACTUAL,
                        jobs));
    }

    private static Job job(
            String name,
            JobRepository jobs,
            Step manager,
            JobLifecycleListener lifecycle,
            ComputationRunListener run) {
        return new JobBuilder(name, jobs)
                .validator(new RequestParametersValidator(List.of("locationId", "payPeriod")))
                .listener(lifecycle)
                .listener(run)
                .start(manager)
                .build();
    }

    @Bean(MANAGER)
    Step payrollComputationStep(
            JobRepository jobs,
            LocationPartitioner computationPartitioner,
            @Qualifier(WORKER) Step worker,
            @Qualifier("partitionExecutor") ThreadPoolTaskExecutor partitionExecutor,
            BatchProperties properties,
            StepLoggingListener stepLogging)
            throws Exception {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(worker);
        handler.setTaskExecutor(partitionExecutor);
        handler.setGridSize(properties.partition().gridSize());
        handler.afterPropertiesSet();
        return PartitionedSteps.manager(MANAGER, jobs, WORKER, computationPartitioner)
                .partitionHandler(handler)
                .listener(stepLogging)
                .listener(summary())
                .build();
    }

    @Bean(WORKER)
    Step payrollComputationWorker(
            JobRepository jobs,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<MemberReader.MemberRow> computationReader,
            ComputationProcessor computationProcessor,
            ResultWriter computationWriter,
            CycleRuns cycles,
            BatchProperties properties,
            StepLoggingListener stepLogging,
            ChunkProgressListener chunkProgress,
            ItemErrorListener itemErrors,
            SkipRecordingListener skips,
            BlockedMemberListener blockedMembers) {
        return new StepBuilder(WORKER, jobs)
                .<MemberReader.MemberRow, ComputationProcessor.Computed>chunk(
                        properties.chunk().size())
                .transactionManager(transactionManager)
                .reader(computationReader)
                .processor(computationProcessor)
                .writer(computationWriter)
                .faultTolerant()
                .retryPolicy(FaultTolerancePolicy.retryPolicy(properties))
                .skip(FaultTolerancePolicy.SKIPPABLE)
                .skipLimit(properties.skip().limit())
                .skipListener(skips)
                .skipListener(blockedMembers)
                .listener(chunkProgress)
                .listener(new WorkUnitListener(cycles))
                .listener((StepExecutionListener) stepLogging)
                .listener((ItemReadListener<Object>) itemErrors)
                .listener((ItemProcessListener<Object, Object>) itemErrors)
                .listener((ItemWriteListener<Object>) itemErrors)
                .build();
    }

    /** Partitions the cycle unit's location subtree by eligible members. */
    @Bean
    @StepScope
    LocationPartitioner computationPartitioner(
            OrgDirectory org,
            CycleRuns cycles,
            NamedParameterJdbcTemplate jdbc,
            @Value("#{jobParameters['locationId']}") String locationId,
            @Value("#{jobParameters['payPeriod']}") String period) {
        Long unit = cycles.cycleUnit(Long.parseLong(locationId)).getId();
        String yymm = MemberReader.yymm(java.time.YearMonth.parse(period).atDay(1));
        return new LocationPartitioner(
                org.subtree(unit),
                locations ->
                        LocationPartitioner.toMap(
                                jdbc.queryForList(
                                        MemberReader.countSql(),
                                        Map.of("locations", locations, "yymm", yymm))));
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<MemberReader.MemberRow> computationReader(
            DataSource dataSource,
            BatchProperties properties,
            @Value("#{stepExecutionContext['" + BatchKeys.LOCATIONS + "']}") String locations,
            @Value("#{jobParameters['payPeriod']}") String period)
            throws Exception {
        return MemberReader.create(
                "computationReader",
                dataSource,
                LocationPartitioner.parse(locations),
                java.time.YearMonth.parse(period).atDay(1),
                properties.chunk().pageSize());
    }

    @Bean
    @StepScope
    ComputationProcessor computationProcessor(
            PayConfiguration configuration,
            in.gov.fci.payroll.payroll.api.StaffDeductionRunner deductions,
            @Value("#{jobParameters['payPeriod']}") String period) {
        java.time.YearMonth month = java.time.YearMonth.parse(period);
        return new ComputationProcessor(
                configuration.staffConfiguration(month.atDay(1)), deductions.open(month));
    }

    @Bean
    @StepScope
    ResultWriter computationWriter(
            JdbcTemplate jdbc,
            Clock clock,
            @Value("#{jobExecutionContext['" + ComputationKeys.RUN_ID + "']}") Long runId,
            @Value("#{jobExecutionContext['" + ComputationKeys.CYCLE_ID + "']}") Long cycleId,
            @Value("#{jobExecutionContext['" + ComputationKeys.ORG_UNIT + "']}") Long orgUnit,
            @Value("#{jobExecutionContext['" + ComputationKeys.YYMM + "']}") String yymm,
            @Value("#{jobExecutionContext['" + ComputationKeys.PERIOD_DATE + "']}")
                    String periodDate,
            @Value("#{jobExecutionContext['" + ComputationKeys.RUN_TYPE + "']}") String runType,
            @Value("#{stepExecutionContext['" + ComputationKeys.WORK_UNIT_ID + "']}")
                    Long workUnitId,
            CycleLeases leases,
            org.springframework.context.ApplicationEventPublisher events) {
        return new ResultWriter(
                jdbc,
                new CycleRuns.OpenRun(
                        runId,
                        cycleId,
                        orgUnit,
                        yymm,
                        LocalDate.parse(periodDate),
                        CycleRuns.RunType.valueOf(runType)),
                workUnitId,
                clock,
                leases,
                events);
    }

    /** UC-01 alternate flow: a skipped member of an Actual run is flagged on X-05 (gate G2). */
    @Bean
    @StepScope
    BlockedMemberListener blockedMemberListener(
            @Value("#{jobExecutionContext['" + ComputationKeys.RUN_ID + "']}") Long runId,
            @Value("#{jobExecutionContext['" + ComputationKeys.CYCLE_ID + "']}") Long cycleId,
            @Value("#{jobExecutionContext['" + ComputationKeys.ORG_UNIT + "']}") Long orgUnit,
            @Value("#{jobExecutionContext['" + ComputationKeys.YYMM + "']}") String yymm,
            @Value("#{jobExecutionContext['" + ComputationKeys.PERIOD_DATE + "']}")
                    String periodDate,
            @Value("#{jobExecutionContext['" + ComputationKeys.RUN_TYPE + "']}") String runType,
            org.springframework.context.ApplicationEventPublisher events) {
        return new BlockedMemberListener(
                new CycleRuns.OpenRun(
                        runId,
                        cycleId,
                        orgUnit,
                        yymm,
                        LocalDate.parse(periodDate),
                        CycleRuns.RunType.valueOf(runType)),
                events);
    }

    /** One line for the request (C-13), from the manager step's aggregated counts. */
    static StepExecutionListener summary() {
        return new StepExecutionListener() {
            @Override
            public ExitStatus afterStep(StepExecution step) {
                String run =
                        step.getJobExecution()
                                        .getExecutionContext()
                                        .containsKey(ComputationKeys.RUN_ID)
                                ? step.getJobExecution()
                                                .getExecutionContext()
                                                .getString(ComputationKeys.RUN_TYPE)
                                        + " run "
                                        + step.getJobExecution()
                                                .getExecutionContext()
                                                .getLong(ComputationKeys.RUN_ID)
                                        + ": "
                                : "";
                step.getExecutionContext()
                        .putString(
                                BatchKeys.SUMMARY,
                                run
                                        + "members read "
                                        + step.getReadCount()
                                        + ", results "
                                        + step.getWriteCount()
                                        + ", not paid (salary stop / hold) "
                                        + step.getFilterCount()
                                        + ", quarantined (data or"
                                        + " configuration gap) "
                                        + step.getSkipCount()
                                        + " (provisional rules: Basic, DA, HRA, CCA; no"
                                        + " deductions)");
                return step.getExitStatus();
            }
        };
    }
}
