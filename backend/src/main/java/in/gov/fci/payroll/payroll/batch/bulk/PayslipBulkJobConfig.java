package in.gov.fci.payroll.payroll.batch.bulk;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.jobs.batch.listener.JobLifecycleListener;
import in.gov.fci.payroll.jobs.batch.listener.StepLoggingListener;
import in.gov.fci.payroll.jobs.batch.support.RequestParametersValidator;
import in.gov.fci.payroll.jobs.batch.support.ZipJobTasklet;
import in.gov.fci.payroll.payroll.orchestration.PayslipPdf;
import in.gov.fci.payroll.payroll.orchestration.PayslipPdfService;
import in.gov.fci.payroll.payroll.orchestration.PayslipService;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * PAYSLIP_BULK, the O-01 Bulk PDF as a background job (OD-149; SRS-NF-2; SRS l.3381-3392: a locked
 * period only, reproducible): one ZIP of the paying unit's payslips of the month at the level, one
 * PDF per member ({@code payslip-<EIS>-<YYYYMM>.pdf}, staff-number order), rendered at run time
 * from the locked results ({@link PayslipService#lockedOf}), kept for the requester (the document
 * store, owner {@value PayslipPdfService#OUTPUT_ENTITY} / the request id). Parameters: {@code
 * locationId} (the paying unit), {@code payPeriod} (YYYY-MM), {@code level} (STAFF or a labour
 * level). An open month fails the job; more members than {@code fci.api.pdf.bulk-max} fail it too
 * (narrow the level).
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class PayslipBulkJobConfig {

    public static final String JOB = "payslipBulk";
    static final String STEP = "payslipBulkStep";

    @Bean
    Job payslipBulkJob(
            JobRepository jobs, @Qualifier(STEP) Step step, JobLifecycleListener lifecycle) {
        return new JobBuilder(JOB, jobs)
                .validator(
                        new RequestParametersValidator(List.of("locationId", "payPeriod", "level")))
                .listener(lifecycle)
                .start(step)
                .build();
    }

    @Bean(STEP)
    Step payslipBulkStep(
            JobRepository jobs,
            PlatformTransactionManager transactionManager,
            @Qualifier("payslipBulkTasklet") ZipJobTasklet<PayslipService.BulkMember> tasklet,
            StepLoggingListener stepLogging) {
        return new StepBuilder(STEP, jobs)
                .tasklet(tasklet, transactionManager)
                .listener((StepExecutionListener) tasklet)
                .listener((StepExecutionListener) stepLogging)
                .build();
    }

    @Bean
    @StepScope
    ZipJobTasklet<PayslipService.BulkMember> payslipBulkTasklet(
            PayslipService payslips,
            DocumentStore documents,
            JdbcTemplate jdbc,
            @Value("${fci.api.pdf.bulk-max:1000}") int bulkMax,
            @Value("#{jobParameters['jobRequestId']}") Long requestId,
            @Value("#{jobParameters['locationId']}") String locationId,
            @Value("#{jobParameters['payPeriod']}") String payPeriod,
            @Value("#{jobParameters['level']}") String level) {
        long unit = Long.parseLong(locationId);
        String yymm = payPeriod.replace("-", "");
        return new ZipJobTasklet<>(
                new ZipJobTasklet.Plan<>() {
                    @Override
                    public List<PayslipService.BulkMember> items() {
                        if (!payslips.monthLocked(unit, yymm)) {
                            throw new IllegalStateException(
                                    "SRS l.3389: the month " + payPeriod + " is not locked");
                        }
                        List<PayslipService.BulkMember> members =
                                payslips.bulkMembers(unit, yymm, "STAFF".equals(level));
                        if (members.size() > bulkMax) {
                            throw new IllegalStateException(
                                    members.size()
                                            + " payslips; one file carries at most "
                                            + bulkMax
                                            + " (fci.api.pdf.bulk-max)");
                        }
                        return members;
                    }

                    @Override
                    public LocalDateTime entryTime() {
                        return YearMonth.parse(payPeriod).atDay(1).atStartOfDay();
                    }

                    @Override
                    public String name(PayslipService.BulkMember m) {
                        return "payslip-" + m.eis() + "-" + yymm + ".pdf";
                    }

                    @Override
                    public byte[] render(PayslipService.BulkMember m) {
                        return PayslipPdf.of(payslips.lockedOf(m.employeeId(), yymm));
                    }

                    @Override
                    public void store(byte[] zip, int written, int leftOut) {
                        documents.storeGenerated(
                                requester(jdbc, requestId),
                                PayslipPdfService.OUTPUT_ENTITY,
                                String.valueOf(requestId),
                                "payslips-" + yymm + ".zip",
                                zip);
                    }
                });
    }

    /** The requesting user (the store's audit names them); "system" when none is recorded. */
    static String requester(JdbcTemplate jdbc, Long requestId) {
        List<String> by =
                jdbc.queryForList(
                        "SELECT requested_by_login FROM batch_job_request WHERE job_request_id = ?",
                        String.class,
                        requestId);
        return by.isEmpty() || by.getFirst() == null ? "system" : by.getFirst();
    }
}
