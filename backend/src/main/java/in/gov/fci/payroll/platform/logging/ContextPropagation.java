package in.gov.fci.payroll.platform.logging;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.util.ClassUtils;

/**
 * Carries the MDC and, when Spring Security is present, the security context from the submitting
 * thread to tasks that run on other threads (platform or virtual).
 */
public final class ContextPropagation {

    private static final boolean SECURITY_PRESENT =
            ClassUtils.isPresent(
                    "org.springframework.security.core.context.SecurityContextHolder",
                    ContextPropagation.class.getClassLoader());

    private ContextPropagation() {}

    public static Runnable wrap(Runnable task) {
        Snapshot snapshot = Snapshot.capture();
        return () ->
                snapshot.call(
                        () -> {
                            task.run();
                            return null;
                        });
    }

    public static <T> Callable<T> wrapCallable(Callable<T> task) {
        Snapshot snapshot = Snapshot.capture();
        return () -> snapshot.callChecked(task);
    }

    public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
        Snapshot snapshot = Snapshot.capture();
        return () -> snapshot.call(task);
    }

    /** A {@link TaskDecorator} for Spring-managed executors ({@code @Async}, schedulers). */
    public static TaskDecorator taskDecorator() {
        return ContextPropagation::wrap;
    }

    /**
     * An executor that runs each task on its own thread with the caller's context. Used, for
     * example, as the Resilience4j TimeLimiter executor. Platform threads (JDK 17 baseline: no
     * virtual threads), daemon, at most {@code maxConcurrent} at a time.
     */
    public static SimpleAsyncTaskExecutor contextExecutor(
            String threadNamePrefix, int maxConcurrent) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(threadNamePrefix);
        executor.setDaemon(true);
        executor.setConcurrencyLimit(maxConcurrent);
        executor.setTaskDecorator(taskDecorator());
        return executor;
    }

    private record Snapshot(Map<String, String> mdc, Object securityContext) {

        static Snapshot capture() {
            return new Snapshot(
                    MDC.getCopyOfContextMap(),
                    SECURITY_PRESENT ? SecurityContextSupport.capture() : null);
        }

        <T> T call(Supplier<T> task) {
            try {
                return callChecked(task::get);
            } catch (RuntimeException | Error ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        <T> T callChecked(Callable<T> task) throws Exception {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            Object previousSecurity = SECURITY_PRESENT ? SecurityContextSupport.capture() : null;
            apply(mdc, securityContext);
            try {
                return task.call();
            } finally {
                apply(previousMdc, previousSecurity);
            }
        }

        private static void apply(Map<String, String> mdc, Object securityContext) {
            if (mdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(mdc);
            }
            if (SECURITY_PRESENT) {
                SecurityContextSupport.restore(securityContext);
            }
        }
    }

    /** Isolated so that Spring Security classes load only when present on the classpath. */
    private static final class SecurityContextSupport {

        static Object capture() {
            return org.springframework.security.core.context.SecurityContextHolder.getContext();
        }

        static void restore(Object context) {
            if (context instanceof org.springframework.security.core.context.SecurityContext sc) {
                org.springframework.security.core.context.SecurityContextHolder.setContext(sc);
            } else {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }
    }
}
