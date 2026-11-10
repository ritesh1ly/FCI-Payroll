package in.gov.fci.payroll.jobs.batch;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.FciPayrollApplication;
import in.gov.fci.payroll.jobs.batch.queue.JobQueuePoller;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The online instance cannot run batch work: no job beans, no poller, no scheduler and no Spring
 * Batch launch machinery at all (Spring Batch 6: JobOperator is the launcher); only the read-only
 * BatchHistory the monitor needs.
 */
@SpringBootTest(classes = FciPayrollApplication.class)
@ActiveProfiles({"local", "demo", "online"})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:online-${random.uuid};MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "fci.common.management-port=0"
        })
class OnlineProfileRunsNoBatchIT {

    @Autowired private ApplicationContext context;

    @Test
    void onlineHasNoLaunchMachineryJobsPollerOrScheduler() {
        for (Class<?> forbidden :
                new Class<?>[] {
                    JobOperator.class,
                    JobRepository.class,
                    Job.class,
                    JobQueuePoller.class,
                    ScheduledAnnotationBeanPostProcessor.class
                }) {
            assertThat(context.getBeanNamesForType(forbidden))
                    .as(forbidden.getSimpleName())
                    .isEmpty();
        }
        assertThat(context.getBeanNamesForType(BatchHistory.class))
                .as("read-only monitor")
                .hasSize(1);
    }
}
