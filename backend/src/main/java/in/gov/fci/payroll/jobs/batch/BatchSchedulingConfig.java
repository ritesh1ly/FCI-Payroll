package in.gov.fci.payroll.jobs.batch;

import in.gov.fci.payroll.jobs.batch.recovery.GracefulShutdown;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.platform.logging.ContextPropagation;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executors and the scheduler of the batch profile. {@code @EnableScheduling} lives ONLY here, so
 * the online instance has no scheduler, no poller and no scheduled jobs.
 *
 * <ul>
 *   <li>{@code jobExecutor}: one thread per running job; its size is the node's concurrency limit
 *       ({@code fci.batch.poller.max-concurrent-jobs});
 *   <li>{@code partitionExecutor}: the worker threads of partitioned steps ({@code
 *       fci.batch.partition.pool-size}), shared by all running jobs.
 * </ul>
 *
 * Both copy the MDC (request and execution id) into their threads, so every log line of a job
 * carries its ids. Spring Batch jobs never start on their own ({@code
 * spring.batch.job.enabled=false}).
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class BatchSchedulingConfig {

    /**
     * Reads of the job repository for launch, recovery and C-13 (Spring Batch 6: no JobExplorer).
     */
    @Bean
    BatchHistory batchHistory(
            JobRepository repository,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return BatchHistory.of(repository, transactionManager);
    }

    @Bean
    ThreadPoolTaskExecutor jobExecutor(BatchProperties properties) {
        return pool("fci-job-", properties.poller().maxConcurrentJobs(), properties);
    }

    @Bean
    ThreadPoolTaskExecutor partitionExecutor(BatchProperties properties) {
        return pool("fci-part-", properties.partition().poolSize(), properties);
    }

    private static ThreadPoolTaskExecutor pool(
            String prefix, int size, BatchProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        // The poller never hands over more jobs than free threads; the queue only absorbs restarts.
        executor.setQueueCapacity(size * 4);
        executor.setTaskDecorator(ContextPropagation.taskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) properties.shutdownTimeout().toSeconds());
        // Stops after GracefulShutdown has asked the running jobs to pause.
        executor.setPhase(GracefulShutdown.EXECUTOR_PHASE);
        return executor;
    }

    /**
     * The scheduler; off in tests ({@code fci.batch.poller.enabled=false}) so they drive jobs
     * directly.
     */
    @Configuration(proxyBeanMethods = false)
    @Profile("batch")
    @ConditionalOnProperty(
            name = "fci.batch.poller.enabled",
            havingValue = "true",
            matchIfMissing = true)
    @EnableScheduling
    static class Scheduling {}
}
