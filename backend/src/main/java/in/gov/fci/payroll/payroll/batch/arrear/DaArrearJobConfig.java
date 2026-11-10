package in.gov.fci.payroll.payroll.batch.arrear;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.jobs.batch.listener.ChunkProgressListener;
import in.gov.fci.payroll.jobs.batch.listener.ItemErrorListener;
import in.gov.fci.payroll.jobs.batch.listener.JobLifecycleListener;
import in.gov.fci.payroll.jobs.batch.listener.SkipRecordingListener;
import in.gov.fci.payroll.jobs.batch.listener.StepLoggingListener;
import in.gov.fci.payroll.jobs.batch.support.FaultTolerancePolicy;
import in.gov.fci.payroll.jobs.batch.support.RequestParametersValidator;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.batch.reader.ActiveEmployeeReader;
import in.gov.fci.payroll.payroll.batch.reader.EmployeeRow;
import java.time.YearMonth;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ItemProcessListener;
import org.springframework.batch.core.listener.ItemReadListener;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * DA_ARREAR on the v4.0 arrear model (S13): one {@code arrear_event} (DA_REVISION) per run over the
 * cycle unit and period range, and one {@code arrear_detail} per member, month and DA line whose
 * differential is not zero: the DA % in force for that month (as approved now) applied to the basis
 * the ACTUAL result used, minus the DA that result paid (BR-ARR-DA-002/003: per period,
 * reproducible). A month without an ACTUAL result is not invented (v4.0 UC-02 E1: fail cleanly): it
 * is counted and reported. The event is COMPUTED when the job completes and targets the next open
 * cycle; approval and posting (I-08) come with that screen.
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class DaArrearJobConfig {

    public static final String JOB = "daArrear";
    static final String EVENT_ID = "fci.arrearEventId";

    @Bean
    Job daArrearJob(
            JobRepository jobs,
            Step daArrearStep,
            JobLifecycleListener lifecycle,
            ArrearEvents events,
            PayrollPeriods periods) {
        return new JobBuilder(JOB, jobs)
                .validator(
                        new RequestParametersValidator(
                                List.of("locationId", "fromPeriod", "toPeriod")))
                .listener(lifecycle)
                .listener(new ArrearEventListener(events, periods::current))
                .start(daArrearStep)
                .build();
    }

    @Bean
    Step daArrearStep(
            JobRepository jobs,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<EmployeeRow> daArrearReader,
            DaArrearProcessor daArrearProcessor,
            JdbcTemplate jdbc,
            BatchProperties properties,
            StepLoggingListener stepLogging,
            ChunkProgressListener chunkProgress,
            ItemErrorListener itemErrors,
            SkipRecordingListener skips) {
        return new StepBuilder("daArrearStep", jobs)
                .<EmployeeRow, DaArrearProcessor.MemberArrear>chunk(properties.chunk().size())
                .transactionManager(transactionManager)
                .reader(daArrearReader)
                .processor(daArrearProcessor)
                .writer(new ArrearDetailWriter(jdbc))
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
                .listener((StepExecutionListener) daArrearProcessor)
                .build();
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<EmployeeRow> daArrearReader(
            DataSource dataSource,
            OrgDirectory org,
            CycleRuns cycles,
            BatchProperties properties,
            @Value("#{jobParameters['locationId']}") String locationId)
            throws Exception {
        Long unit = cycles.cycleUnit(Long.parseLong(locationId)).getId();
        return ActiveEmployeeReader.create(
                "daArrearReader", dataSource, org.subtree(unit), properties.chunk().pageSize());
    }

    @Bean
    @StepScope
    DaArrearProcessor daArrearProcessor(
            JdbcTemplate jdbc,
            PayConfiguration configuration,
            @Value("#{jobExecutionContext['" + EVENT_ID + "']}") Long eventId,
            @Value("#{jobParameters['fromPeriod']}") String fromPeriod,
            @Value("#{jobParameters['toPeriod']}") String toPeriod) {
        return new DaArrearProcessor(
                jdbc,
                configuration,
                eventId,
                YearMonth.parse(fromPeriod),
                YearMonth.parse(toPeriod));
    }
}
