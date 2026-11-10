package in.gov.fci.payroll.tax.batch;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
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
import in.gov.fci.payroll.tax.business.model.Form16Certificate;
import in.gov.fci.payroll.tax.business.model.ReturnDeductee;
import in.gov.fci.payroll.tax.orchestration.Form16Service;
import in.gov.fci.payroll.tax.orchestration.ReturnService;
import in.gov.fci.payroll.tax.orchestration.TaxComputationService;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
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
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
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
 * The Module 08 statutory outputs as background jobs (SRS-NF-2, SRS-NF-7: statutory outputs run as
 * background jobs after lock, split by office, committed unit by unit, restartable; submitted on
 * C-13 with the Checker's approval):
 *
 * <pre>
 * taxForm16PartB (TAX_FORM16_PART_B)  C-10 Part B of a paying unit's locked year
 * tax24QReturn   (TAX_24Q_RETURN)     C-11 the original 24Q of a locked quarter, DRAFT until done
 *   manager step: LocationPartitioner over the unit's locations (members per location)
 *     worker: TaxMemberReader -> PartBProcessor | DeducteeProcessor -> repository writer
 *     chunk, fault tolerant (retry transient DB errors), restartable from the last chunk
 * </pre>
 *
 * The per-member logic is the screens' ({@code Form16Service.partBOf}, {@code
 * ReturnService.deducteeOf}); {@link TaxRunListeners} opens and closes the run. A small population
 * the Maker selects stays on the screen (synchronous).
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class TaxJobsConfig {

    public static final String PART_B_JOB = "taxForm16PartB";
    public static final String RETURN_JOB = "tax24QReturn";
    static final String PART_B_MANAGER = "taxForm16PartBStep";
    static final String PART_B_WORKER = "taxForm16PartBWorker";
    static final String RETURN_MANAGER = "tax24QReturnStep";
    static final String RETURN_WORKER = "tax24QReturnWorker";

    // ---------------------------------------------------------------- jobs

    @Bean
    Job taxForm16PartBJob(
            JobRepository jobs,
            @Qualifier(PART_B_MANAGER) Step manager,
            JobLifecycleListener lifecycle,
            OrgDirectory org,
            JdbcTemplate jdbc,
            Form16Service form16) {
        return job(
                PART_B_JOB,
                jobs,
                manager,
                lifecycle,
                TaxRunListeners.partB(org, jdbc, form16),
                List.of("locationId", "finYear"));
    }

    @Bean
    Job tax24QReturnJob(
            JobRepository jobs,
            @Qualifier(RETURN_MANAGER) Step manager,
            JobLifecycleListener lifecycle,
            OrgDirectory org,
            JdbcTemplate jdbc,
            ReturnService returns) {
        return job(
                RETURN_JOB,
                jobs,
                manager,
                lifecycle,
                TaxRunListeners.quarterReturn(org, jdbc, returns),
                List.of("locationId", "finYear", "quarter"));
    }

    private static Job job(
            String name,
            JobRepository jobs,
            Step manager,
            JobLifecycleListener lifecycle,
            JobExecutionListener run,
            List<String> required) {
        return new JobBuilder(name, jobs)
                .validator(new RequestParametersValidator(required))
                .listener(lifecycle)
                .listener(run)
                .start(manager)
                .build();
    }

    // ---------------------------------------------------------------- manager steps

    @Bean(PART_B_MANAGER)
    Step taxForm16PartBStep(
            JobRepository jobs,
            @Qualifier("taxPartitioner") LocationPartitioner partitioner,
            @Qualifier(PART_B_WORKER) Step worker,
            @Qualifier("partitionExecutor") ThreadPoolTaskExecutor partitionExecutor,
            BatchProperties properties,
            StepLoggingListener stepLogging)
            throws Exception {
        return manager(
                PART_B_MANAGER,
                PART_B_WORKER,
                jobs,
                partitioner,
                worker,
                partitionExecutor,
                properties,
                stepLogging,
                "certificates written ",
                "not certified (reasons in the log, mismatches on X-05) ");
    }

    @Bean(RETURN_MANAGER)
    Step tax24QReturnStep(
            JobRepository jobs,
            @Qualifier("taxPartitioner") LocationPartitioner partitioner,
            @Qualifier(RETURN_WORKER) Step worker,
            @Qualifier("partitionExecutor") ThreadPoolTaskExecutor partitionExecutor,
            BatchProperties properties,
            StepLoggingListener stepLogging)
            throws Exception {
        return manager(
                RETURN_MANAGER,
                RETURN_WORKER,
                jobs,
                partitioner,
                worker,
                partitionExecutor,
                properties,
                stepLogging,
                "deductees written ",
                "without TDS in the quarter ");
    }

    private static Step manager(
            String name,
            String workerName,
            JobRepository jobs,
            LocationPartitioner partitioner,
            Step worker,
            ThreadPoolTaskExecutor partitionExecutor,
            BatchProperties properties,
            StepLoggingListener stepLogging,
            String written,
            String filtered)
            throws Exception {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(worker);
        handler.setTaskExecutor(partitionExecutor);
        handler.setGridSize(properties.partition().gridSize());
        handler.afterPropertiesSet();
        return PartitionedSteps.manager(name, jobs, workerName, partitioner)
                .partitionHandler(handler)
                .listener(stepLogging)
                .listener(summary(written, filtered))
                .build();
    }

    /** The paying unit's locations, partitioned by their members (one partitioner per run). */
    @Bean
    @StepScope
    LocationPartitioner taxPartitioner(
            OrgDirectory org,
            NamedParameterJdbcTemplate jdbc,
            @Value("#{jobExecutionContext['" + TaxJobKeys.UNIT_ID + "']}") Long unitId) {
        return new LocationPartitioner(
                TaxMemberReader.locationsOf(org, unitId),
                locations ->
                        LocationPartitioner.toMap(
                                jdbc.queryForList(
                                        TaxMemberReader.COUNT_SQL,
                                        Map.of("locations", locations))));
    }

    // ---------------------------------------------------------------- workers

    @Bean(PART_B_WORKER)
    Step taxForm16PartBWorker(
            JobRepository jobs,
            PlatformTransactionManager transactionManager,
            @Qualifier("taxMemberReader") JdbcPagingItemReader<TaxMemberReader.Row> reader,
            PartBProcessor partBProcessor,
            TaxRepositories.Form16s certificates,
            BatchProperties properties,
            StepLoggingListener stepLogging,
            ChunkProgressListener chunkProgress,
            ItemErrorListener itemErrors,
            SkipRecordingListener skips) {
        ItemWriter<Form16Certificate> writer = chunk -> chunk.forEach(certificates::save);
        return new StepBuilder(PART_B_WORKER, jobs)
                .<TaxMemberReader.Row, Form16Certificate>chunk(properties.chunk().size())
                .transactionManager(transactionManager)
                .reader(reader)
                .processor(partBProcessor)
                .writer(writer)
                .faultTolerant()
                .retryPolicy(FaultTolerancePolicy.retryPolicy(properties))
                .skip(FaultTolerancePolicy.SKIPPABLE)
                .skipLimit(properties.skip().limit())
                .skipListener(skips)
                .listener(chunkProgress)
                .listener((StepExecutionListener) stepLogging)
                .listener((ItemReadListener<Object>) itemErrors)
                .listener((ItemProcessListener<Object, Object>) itemErrors)
                .listener((ItemWriteListener<Object>) itemErrors)
                .listener((StepExecutionListener) partBProcessor)
                .build();
    }

    @Bean(RETURN_WORKER)
    Step tax24QReturnWorker(
            JobRepository jobs,
            PlatformTransactionManager transactionManager,
            @Qualifier("taxMemberReader") JdbcPagingItemReader<TaxMemberReader.Row> reader,
            DeducteeProcessor deducteeProcessor,
            TaxRepositories.Deductees deductees,
            BatchProperties properties,
            StepLoggingListener stepLogging,
            ChunkProgressListener chunkProgress,
            ItemErrorListener itemErrors,
            SkipRecordingListener skips) {
        ItemWriter<ReturnDeductee> writer = chunk -> chunk.forEach(deductees::save);
        return new StepBuilder(RETURN_WORKER, jobs)
                .<TaxMemberReader.Row, ReturnDeductee>chunk(properties.chunk().size())
                .transactionManager(transactionManager)
                .reader(reader)
                .processor(deducteeProcessor)
                .writer(writer)
                .faultTolerant()
                .retryPolicy(FaultTolerancePolicy.retryPolicy(properties))
                .skip(FaultTolerancePolicy.SKIPPABLE)
                .skipLimit(properties.skip().limit())
                .skipListener(skips)
                .listener(chunkProgress)
                .listener((StepExecutionListener) stepLogging)
                .listener((ItemReadListener<Object>) itemErrors)
                .listener((ItemProcessListener<Object, Object>) itemErrors)
                .listener((ItemWriteListener<Object>) itemErrors)
                .build();
    }

    // ---------------------------------------------------------------- step-scoped parts

    @Bean
    @StepScope
    JdbcPagingItemReader<TaxMemberReader.Row> taxMemberReader(
            DataSource dataSource,
            BatchProperties properties,
            @Value("#{stepExecutionContext['" + BatchKeys.LOCATIONS + "']}") String locations)
            throws Exception {
        return TaxMemberReader.create(
                "taxMemberReader",
                dataSource,
                LocationPartitioner.parse(locations),
                properties.chunk().pageSize());
    }

    /** One snapshot of the year-end rules per partition (TaxComputationService.Run). */
    @Bean
    @StepScope
    PartBProcessor partBProcessor(
            EmployeeDirectory employees,
            Form16Service form16,
            TaxRepositories.Form16s certificates,
            TaxComputationService computation,
            Clock clock,
            @Value("#{jobExecutionContext['" + TaxJobKeys.ACTOR + "']}") String actor,
            @Value("#{jobParameters['finYear']}") String finYear,
            @Value("#{jobExecutionContext['" + TaxJobKeys.YEAR_END + "']}") String yearEnd,
            @Value("#{jobExecutionContext['" + TaxJobKeys.VERSION_ID + "']}") Long versionId) {
        LocalDate end = LocalDate.parse(yearEnd);
        return new PartBProcessor(
                employees,
                form16,
                certificates,
                computation.ratesOn(end),
                clock,
                actor,
                finYear,
                end,
                versionId);
    }

    /** The opened return, rebuilt from the job's context, and the quarter-end rules snapshot. */
    @Bean
    @StepScope
    DeducteeProcessor deducteeProcessor(
            EmployeeDirectory employees,
            ReturnService returns,
            TaxComputationService computation,
            @Value("#{jobExecutionContext['" + TaxJobKeys.RETURN_ID + "']}") Long returnId,
            @Value("#{jobExecutionContext['" + TaxJobKeys.UNIT_ID + "']}") Long unitId,
            @Value("#{jobParameters['finYear']}") String finYear,
            @Value("#{jobParameters['quarter']}") String quarter,
            @Value("#{jobExecutionContext['" + TaxJobKeys.MONTHS + "']}") String months,
            @Value("#{jobExecutionContext['" + TaxJobKeys.QUARTER_END + "']}") String quarterEnd,
            @Value("#{jobExecutionContext['" + TaxJobKeys.CHALLAN_ID + "']}") Long challanId) {
        LocalDate end = LocalDate.parse(quarterEnd);
        return new DeducteeProcessor(
                employees,
                returns,
                new ReturnService.Opened(
                        returnId,
                        unitId,
                        finYear,
                        quarter,
                        Arrays.asList(months.split(",")),
                        end,
                        challanId),
                computation.ratesOn(end));
    }

    /** One line for the request (C-13), from the manager step's aggregated counts. */
    private static StepExecutionListener summary(String written, String filtered) {
        return new StepExecutionListener() {
            @Override
            public ExitStatus afterStep(StepExecution step) {
                step.getExecutionContext()
                        .putString(
                                BatchKeys.SUMMARY,
                                "members read "
                                        + step.getReadCount()
                                        + ", "
                                        + written
                                        + step.getWriteCount()
                                        + ", "
                                        + filtered
                                        + step.getFilterCount()
                                        + ", quarantined "
                                        + step.getSkipCount());
                return step.getExitStatus();
            }
        };
    }
}
