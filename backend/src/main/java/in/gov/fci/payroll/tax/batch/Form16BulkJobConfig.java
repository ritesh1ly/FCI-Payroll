package in.gov.fci.payroll.tax.batch;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.jobs.batch.listener.JobLifecycleListener;
import in.gov.fci.payroll.jobs.batch.listener.StepLoggingListener;
import in.gov.fci.payroll.jobs.batch.support.RequestParametersValidator;
import in.gov.fci.payroll.jobs.batch.support.ZipJobTasklet;
import in.gov.fci.payroll.tax.orchestration.Form16Service;
import java.time.LocalDateTime;
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
 * FORM16_BULK, the C-10 Bulk PDF as a background job (OD-149; SRS-NF-2, SRS-NF-7; SRS-7-9): one ZIP
 * of a paying unit's Form 16 certificates of a year whose Part B is generated, one PDF each ({@code
 * form16-<EIS>-<finYear>.pdf}, Part A first once merged), staff-number order, rendered at run time
 * ({@link Form16Service#certificatePdfForJob}); kept for the requester. A certificate that cannot
 * be printed (its Part A names another PAN, a year under the new Act) is left out and listed in the
 * manifest. Parameters: {@code locationId} (the paying unit), {@code finYear}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class Form16BulkJobConfig {

    public static final String JOB = "form16Bulk";
    static final String STEP = "form16BulkStep";

    @Bean
    Job form16BulkJob(
            JobRepository jobs, @Qualifier(STEP) Step step, JobLifecycleListener lifecycle) {
        return new JobBuilder(JOB, jobs)
                .validator(new RequestParametersValidator(List.of("locationId", "finYear")))
                .listener(lifecycle)
                .start(step)
                .build();
    }

    @Bean(STEP)
    Step form16BulkStep(
            JobRepository jobs,
            PlatformTransactionManager transactionManager,
            @Qualifier("form16BulkTasklet") ZipJobTasklet<Form16Service.BulkCertificate> tasklet,
            StepLoggingListener stepLogging) {
        return new StepBuilder(STEP, jobs)
                .tasklet(tasklet, transactionManager)
                .listener((StepExecutionListener) tasklet)
                .listener((StepExecutionListener) stepLogging)
                .build();
    }

    @Bean
    @StepScope
    ZipJobTasklet<Form16Service.BulkCertificate> form16BulkTasklet(
            Form16Service form16,
            DocumentStore documents,
            JdbcTemplate jdbc,
            @Value("${fci.api.pdf.bulk-max:1000}") int bulkMax,
            @Value("#{jobParameters['jobRequestId']}") Long requestId,
            @Value("#{jobParameters['locationId']}") String locationId,
            @Value("#{jobParameters['finYear']}") String finYear) {
        long unit = Long.parseLong(locationId);
        String actor = requester(jdbc, requestId);
        return new ZipJobTasklet<>(
                new ZipJobTasklet.Plan<>() {
                    @Override
                    public List<Form16Service.BulkCertificate> items() {
                        List<Form16Service.BulkCertificate> all =
                                form16.bulkCertificates(unit, finYear);
                        if (all.size() > bulkMax) {
                            throw new IllegalStateException(
                                    all.size()
                                            + " certificates; one file carries at most "
                                            + bulkMax
                                            + " (fci.api.pdf.bulk-max)");
                        }
                        return all;
                    }

                    @Override
                    public LocalDateTime entryTime() {
                        // The year end of the financial year (31 March), from the data.
                        int start = Integer.parseInt(finYear.substring(0, 4));
                        return LocalDateTime.of(start + 1, 3, 31, 0, 0);
                    }

                    @Override
                    public String name(Form16Service.BulkCertificate c) {
                        return "form16-" + c.eis() + "-" + finYear + ".pdf";
                    }

                    @Override
                    public byte[] render(Form16Service.BulkCertificate c) {
                        return form16.certificatePdfForJob(c.form16Id(), actor);
                    }

                    @Override
                    public void store(byte[] zip, int written, int leftOut) {
                        documents.storeGenerated(
                                actor,
                                Form16Service.OUTPUT_ENTITY,
                                String.valueOf(requestId),
                                "form16-" + finYear + ".zip",
                                zip);
                    }
                });
    }

    private static String requester(JdbcTemplate jdbc, Long requestId) {
        List<String> by =
                jdbc.queryForList(
                        "SELECT requested_by_login FROM batch_job_request WHERE job_request_id = ?",
                        String.class,
                        requestId);
        return by.isEmpty() || by.getFirst() == null ? "system" : by.getFirst();
    }
}
