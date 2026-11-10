package in.gov.fci.payroll.platform.web;

import in.gov.fci.payroll.platform.export.ExportProperties;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The export pool (executor framework): every {@code StreamingResponseBody} download runs on these
 * threads, so large downloads never hold Tomcat's request threads. Bounded: {@code
 * fci.export.threads} at once, {@code fci.export.queue-capacity} waiting, beyond that 503
 * (EXPORT_BUSY); each download may stream for {@code fci.export.timeout}. The log context
 * (correlation id, user) is carried onto the export thread. The pool is a Spring bean, so running
 * downloads finish within the graceful shutdown phase.
 */
@Configuration(proxyBeanMethods = false)
@Profile("online")
public class ExportAsyncConfig implements WebMvcConfigurer {

    static final String EXECUTOR = "exportExecutor";

    private final ThreadPoolTaskExecutor executor;
    private final ExportProperties properties;

    public ExportAsyncConfig(
            @Qualifier(EXECUTOR) ThreadPoolTaskExecutor executor, ExportProperties properties) {
        this.executor = executor;
        this.properties = properties;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(properties.timeout().toMillis());
    }

    /** The pool itself: a separate configuration, so the MVC configurer above receives the bean. */
    @Configuration(proxyBeanMethods = false)
    @Profile("online")
    static class Pool {

        @Bean(EXECUTOR)
        ThreadPoolTaskExecutor exportExecutor(ExportProperties properties) {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setThreadNamePrefix("export-");
            executor.setCorePoolSize(properties.threads());
            executor.setMaxPoolSize(properties.threads());
            executor.setQueueCapacity(properties.queueCapacity());
            executor.setTaskDecorator(withLogContext());
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationSeconds(20);
            return executor;
        }
    }

    /**
     * Copies the request thread's MDC (correlation id, user) to the export thread, and restores the
     * export thread's own context afterwards.
     */
    static TaskDecorator withLogContext() {
        return task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(context);
                }
                try {
                    task.run();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
    }
}
