package in.gov.fci.payroll.platform.logging;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;

/**
 * Implements the ESER pattern for {@link Loggable} methods. Runs outside transactions and method
 * security so that the measured duration covers the whole call.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class EserLoggingAspect {

    private static final int MAX_RECORD_DEPTH = 2;

    private final ResultSummarizer resultSummarizer;
    private final int maxValueLength;

    public EserLoggingAspect(ResultSummarizer resultSummarizer, int maxValueLength) {
        this.resultSummarizer = resultSummarizer;
        this.maxValueLength = maxValueLength;
    }

    @Around(
            "execution(public * *(..)) &&"
                    + " (@annotation(in.gov.fci.payroll.platform.logging.Loggable) ||"
                    + " @within(in.gov.fci.payroll.platform.logging.Loggable))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> targetClass =
                joinPoint.getTarget() != null
                        ? AopUtils.getTargetClass(joinPoint.getTarget())
                        : signature.getDeclaringType();
        Method method = AopUtils.getMostSpecificMethod(signature.getMethod(), targetClass);
        Loggable loggable = resolveAnnotation(method, targetClass);
        if (loggable == null) {
            return joinPoint.proceed();
        }
        Logger log = LoggerFactory.getLogger(targetClass);
        String methodName = targetClass.getSimpleName() + "." + method.getName();
        boolean debugOnly = loggable.level() == Loggable.Level.DEBUG;

        if (isEnabled(log, debugOnly)) {
            String args = loggable.logArgs() ? renderArguments(method, joinPoint.getArgs()) : "{}";
            write(log, debugOnly, "event=ENTRY method={} args={}", methodName, args);
        }

        long start = System.nanoTime();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable ex) {
            // Exception phase: logged once by the handling boundary, which reads durationMs.
            MDC.put(MdcKeys.DURATION_MS, Long.toString(elapsedMillis(start)));
            throw ex;
        }

        long durationMs = elapsedMillis(start);
        if (isEnabled(log, debugOnly)) {
            String outcome = loggable.outcome().isEmpty() ? method.getName() : loggable.outcome();
            write(
                    log,
                    debugOnly,
                    "event=SUCCESS method={} outcome={} durationMs={}",
                    methodName,
                    outcome,
                    durationMs);
        }
        if (loggable.logResult() && log.isDebugEnabled()) {
            log.debug(
                    "event=RETURN method={} summary={}",
                    methodName,
                    resultSummarizer.summarize(result));
        }
        return result;
    }

    String renderArguments(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return "{}";
        }
        java.lang.reflect.Parameter[] parameters = method.getParameters();
        Annotation[][] annotations = method.getParameterAnnotations();
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            String name = i < parameters.length ? parameters[i].getName() : "arg" + i;
            boolean sensitive =
                    LogSanitizer.isSensitiveName(name)
                            || (i < annotations.length && hasSensitive(annotations[i]));
            out.append(LogSanitizer.encodeControlCharacters(name))
                    .append('=')
                    .append(sensitive ? LogSanitizer.REDACTED : renderValue(args[i], 0));
        }
        return out.append('}').toString();
    }

    String renderValue(Object value, int depth) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof UUID
                || value instanceof Temporal) {
            return LogSanitizer.sanitize(value, maxValueLength);
        }
        if (value instanceof Collection<?> collection) {
            return "[size=" + collection.size() + "]";
        }
        if (value instanceof Map<?, ?> map) {
            return "{size=" + map.size() + "}";
        }
        if (value.getClass().isArray()) {
            return "[length=" + java.lang.reflect.Array.getLength(value) + "]";
        }
        if (value instanceof Record record && depth < MAX_RECORD_DEPTH) {
            return renderRecord(record, depth);
        }
        // Never call toString() on arbitrary objects: it may expose secrets or PII.
        return value.getClass().getSimpleName();
    }

    private String renderRecord(Record record, int depth) {
        StringBuilder out = new StringBuilder(record.getClass().getSimpleName()).append('{');
        RecordComponent[] components = record.getClass().getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            if (i > 0) {
                out.append(", ");
            }
            out.append(component.getName()).append('=');
            if (component.isAnnotationPresent(Sensitive.class)
                    || LogSanitizer.isSensitiveName(component.getName())) {
                out.append(LogSanitizer.REDACTED);
                continue;
            }
            try {
                out.append(renderValue(component.getAccessor().invoke(record), depth + 1));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                out.append("?");
            }
        }
        return out.append('}').toString();
    }

    private static boolean hasSensitive(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType() == Sensitive.class) {
                return true;
            }
        }
        return false;
    }

    private static Loggable resolveAnnotation(Method method, Class<?> targetClass) {
        Loggable onMethod = AnnotatedElementUtils.findMergedAnnotation(method, Loggable.class);
        if (onMethod != null) {
            return onMethod;
        }
        Loggable onType = AnnotatedElementUtils.findMergedAnnotation(targetClass, Loggable.class);
        if (onType != null) {
            return onType;
        }
        return AnnotatedElementUtils.findMergedAnnotation(
                method.getDeclaringClass(), Loggable.class);
    }

    private static boolean isEnabled(Logger log, boolean debugOnly) {
        return debugOnly ? log.isDebugEnabled() : log.isInfoEnabled();
    }

    private static void write(Logger log, boolean debugOnly, String format, Object... args) {
        if (debugOnly) {
            log.debug(format, args);
        } else {
            log.info(format, args);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
