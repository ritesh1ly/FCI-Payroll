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
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.batch.reader.ActiveEmployeeReader;
import in.gov.fci.payroll.payroll.batch.reader.EmployeeRow;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * HRA_ARREAR (plan 13 phase 13c; SRS-8-10, SRS-8-11, SRS-8-19): a back-dated HRA rate version, or a
 * location's city reclassification, settled through the arrear mechanism - never a manual entry.
 * The same run as DA_ARREAR: one {@code arrear_event} (HRA_REVISION) per run over the cycle unit
 * and range, and one {@code arrear_detail} per member, month and HRA line whose differential is not
 * zero: the HRA % in force for that month (as approved now) for the member's city class of that
 * month ({@code city_type_location_map}, so a reclassification counts from its date) applied to the
 * basis the ACTUAL result used, minus the HRA that result paid. A range overlapping an open HRA
 * event of the unit is refused (SRS-8-15).
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class HraArrearJobConfig {

    public static final String JOB = "hraArrear";

    /**
     * The HRA % of the member's city class in the month, under the rule of the member's pay pattern
     * (SRS-1-3: CDA staff under their own rule).
     */
    static DaArrearProcessor.Rate hra(NamedParameterJdbcTemplate named) {
        return new DaArrearProcessor.Rate() {
            @Override
            public int payCode() {
                return PayRules.HRA;
            }

            @Override
            public String label() {
                return "HRA";
            }

            @Override
            public Optional<BigDecimal> percent(
                    PayConfiguration.Snapshot snapshot, EmployeeRow member, YearMonth month) {
                return MemberReader.member(named, member.employeeId(), month.atDay(1))
                        .flatMap(m -> snapshot.hra(m.payPattern(), m.cityClass()));
            }
        };
    }

    @Bean
    Job hraArrearJob(
            JobRepository jobs,
            Step hraArrearStep,
            JobLifecycleListener lifecycle,
            ArrearEvents events,
            PayrollPeriods periods) {
        return new JobBuilder(JOB, jobs)
                .validator(
                        new RequestParametersValidator(
                                List.of("locationId", "fromPeriod", "toPeriod")))
                .listener(lifecycle)
                .listener(new ArrearEventListener(events, periods::current, "HRA_REVISION", "HRA"))
                .start(hraArrearStep)
                .build();
    }

    @Bean
    Step hraArrearStep(
            JobRepository jobs,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<EmployeeRow> hraArrearReader,
            DaArrearProcessor hraArrearProcessor,
            JdbcTemplate jdbc,
            BatchProperties properties,
            StepLoggingListener stepLogging,
            ChunkProgressListener chunkProgress,
            ItemErrorListener itemErrors,
            SkipRecordingListener skips) {
        return new StepBuilder("hraArrearStep", jobs)
                .<EmployeeRow, DaArrearProcessor.MemberArrear>chunk(properties.chunk().size())
                .transactionManager(transactionManager)
                .reader(hraArrearReader)
                .processor(hraArrearProcessor)
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
                .listener((StepExecutionListener) hraArrearProcessor)
                .build();
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<EmployeeRow> hraArrearReader(
            DataSource dataSource,
            OrgDirectory org,
            CycleRuns cycles,
            BatchProperties properties,
            @Value("#{jobParameters['locationId']}") String locationId)
            throws Exception {
        Long unit = cycles.cycleUnit(Long.parseLong(locationId)).getId();
        return ActiveEmployeeReader.create(
                "hraArrearReader", dataSource, org.subtree(unit), properties.chunk().pageSize());
    }

    @Bean
    @StepScope
    DaArrearProcessor hraArrearProcessor(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            PayConfiguration configuration,
            @Value("#{jobExecutionContext['" + DaArrearJobConfig.EVENT_ID + "']}") Long eventId,
            @Value("#{jobParameters['fromPeriod']}") String fromPeriod,
            @Value("#{jobParameters['toPeriod']}") String toPeriod) {
        return new DaArrearProcessor(
                jdbc,
                configuration,
                hra(named),
                eventId,
                YearMonth.parse(fromPeriod),
                YearMonth.parse(toPeriod));
    }
}
