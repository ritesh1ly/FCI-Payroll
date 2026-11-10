package in.gov.fci.payroll.jobs.config;

import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch on the ONLINE instance: read only. The online profile excludes Spring Batch's
 * auto-configurations (application.yaml), so there is no JobOperator and no JobRepository bean
 * here, and no job can be started on this instance even by mistake. The only Spring Batch bean is
 * the read-only {@link BatchHistory} over the shared job repository tables, which C-13 uses to show
 * step-level progress of jobs that run on the batch instances; the repository behind it is private
 * to that bean and offers only reads.
 *
 * <p>{@link #onlineModeGuard} enforces this at start-up: if any launch machinery or a task
 * scheduler is present on the online instance, it refuses to start.
 */
@Configuration(proxyBeanMethods = false)
@Profile("online")
public class OnlineBatchReadOnlyConfig {

    @Bean
    BatchHistory batchHistory(DataSource dataSource, PlatformTransactionManager transactionManager)
            throws Exception {
        JdbcJobRepositoryFactoryBean factory = new JdbcJobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTransactionManager(transactionManager);
        factory.afterPropertiesSet();
        // Not a bean: only the reads of BatchHistory reach it (onlineModeGuard checks the beans).
        return BatchHistory.of(factory.getObject(), transactionManager);
    }

    @Bean
    static SmartInitializingSingleton onlineModeGuard(
            ConfigurableListableBeanFactory beans, org.springframework.core.env.Environment env) {
        return () -> {
            List<String> forbidden = new ArrayList<>();
            List<Class<?>> types = new ArrayList<>(List.of(JobOperator.class, JobRepository.class));
            // Resilience runs (profile chaos-monkey, local demo stacks only): Chaos Monkey
            // schedules
            // its own assaults. Every @Scheduled bean of the application is @Profile("batch"), so
            // no batch work can start here.
            if (!env.matchesProfiles("chaos-monkey")) {
                types.add(ScheduledAnnotationBeanPostProcessor.class);
            }
            for (Class<?> type : types) {
                if (beans.getBeanNamesForType(type, true, false).length > 0) {
                    forbidden.add(type.getSimpleName());
                }
            }
            if (!forbidden.isEmpty()) {
                throw new IllegalStateException(
                        "The online instance must not run batch jobs or schedules, but has "
                                + forbidden
                                + ". Batch work belongs to the instance with profile 'batch'.");
            }
        };
    }
}
