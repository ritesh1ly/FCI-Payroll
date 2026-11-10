package in.gov.fci.payroll.jobs.batch.scheduled;

import in.gov.fci.payroll.jobs.api.InterfaceGuard;
import in.gov.fci.payroll.jobs.batch.listener.JobLifecycleListener;
import in.gov.fci.payroll.jobs.batch.listener.StepLoggingListener;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.batch.support.RequestParametersValidator;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import java.util.List;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The two cron-triggered jobs (queued by {@code ScheduledJobs}, run by the poller like any
 * request):
 *
 * <ul>
 *   <li>INTERFACE_PULL: the nightly HRMS pull through {@link ResilientHrmsGateway} (the interface's
 *       settings in force: time limit, retries, breaker; refused while not configured). Without an
 *       HRMS client the step records that nothing was pulled rather than pretending;
 *   <li>MONTH_END_CLOSE: a read-only readiness check (ACTIVE employees, approvals pending). Locking
 *       the period stays a governed C-01 action of a human (Checker, OTP).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class ScheduledJobsConfig {

    @Bean
    @ConditionalOnMissingBean(HrmsInterfaceClient.class)
    HrmsInterfaceClient hrmsInterfaceClient(BatchProperties properties) {
        String source = properties.interfaces().hrmsSource();
        return period ->
                new HrmsInterfaceClient.PullResult(
                        0,
                        0,
                        0,
                        source.isBlank()
                                ? "No HRMS interface configured (fci.batch.interfaces.hrms-source,"
                                        + " OQ-8); nothing pulled"
                                : "HRMS interface "
                                        + source
                                        + " not yet implemented (OQ-8); nothing pulled");
    }

    @Bean
    ResilientHrmsGateway resilientHrmsGateway(
            HrmsInterfaceClient client, InterfaceGuard guard, java.time.Clock clock) {
        return new ResilientHrmsGateway(client, guard, clock);
    }

    @Bean
    Job interfacePullJob(
            JobRepository jobs, Step interfacePullStep, JobLifecycleListener lifecycle) {
        return new JobBuilder("interfacePull", jobs)
                .validator(new RequestParametersValidator(List.of("payPeriod")))
                .listener(lifecycle)
                .start(interfacePullStep)
                .build();
    }

    @Bean
    Step interfacePullStep(
            JobRepository jobs,
            PlatformTransactionManager transactionManager,
            ResilientHrmsGateway hrms,
            StepLoggingListener logging) {
        return new StepBuilder("interfacePullStep", jobs)
                .tasklet(
                        (contribution, chunk) -> {
                            String period =
                                    (String)
                                            chunk.getStepContext()
                                                    .getJobParameters()
                                                    .get("payPeriod");
                            HrmsInterfaceClient.PullResult result = hrms.pull(period);
                            contribution.incrementReadCount();
                            chunk.getStepContext()
                                    .getStepExecution()
                                    .getExecutionContext()
                                    .putString(
                                            BatchKeys.SUMMARY,
                                            "Received "
                                                    + result.received()
                                                    + ", accepted "
                                                    + result.accepted()
                                                    + ", rejected "
                                                    + result.rejected()
                                                    + ". "
                                                    + result.note());
                            return RepeatStatus.FINISHED;
                        },
                        transactionManager)
                .listener(logging)
                .build();
    }

    @Bean
    Job monthEndCloseJob(
            JobRepository jobs, Step monthEndReadinessStep, JobLifecycleListener lifecycle) {
        return new JobBuilder("monthEndClose", jobs)
                .validator(new RequestParametersValidator(List.of("payPeriod")))
                .listener(lifecycle)
                .start(monthEndReadinessStep)
                .build();
    }

    @Bean
    Step monthEndReadinessStep(
            JobRepository jobs,
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc,
            StepLoggingListener logging) {
        return new StepBuilder("monthEndReadinessStep", jobs)
                .tasklet(
                        (contribution, chunk) -> {
                            Integer employees =
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM employee WHERE record_status ="
                                                    + " 'ACTIVE'",
                                            Integer.class);
                            Integer pending =
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM approval_queue WHERE status ="
                                                    + " 'PENDING_APPROVAL'",
                                            Integer.class);
                            chunk.getStepContext()
                                    .getStepExecution()
                                    .getExecutionContext()
                                    .putString(
                                            BatchKeys.SUMMARY,
                                            "Readiness: "
                                                    + employees
                                                    + " active employees, "
                                                    + pending
                                                    + " approvals pending");
                            return RepeatStatus.FINISHED;
                        },
                        transactionManager)
                .listener(logging)
                .build();
    }
}
