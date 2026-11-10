package in.gov.fci.payroll.platform.logging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies the ESER logging pattern (Entry → Success → Exception → Return) to a public method, or to
 * every public method of a type. See ADR-0012.
 *
 * <ul>
 *   <li><b>Entry</b> — {@link #level()} with the method name and sanitised arguments.
 *   <li><b>Success</b> — INFO with the business outcome and the duration in ms.
 *   <li><b>Exception</b> — not logged here; the duration is put in the MDC and the exception is
 *       rethrown so the handling boundary logs it exactly once.
 *   <li><b>Return</b> — DEBUG with a summary (type, size, id); never the payload.
 * </ul>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Loggable {

    /** Level of the Entry line. Use DEBUG for internal methods. */
    Level level() default Level.INFO;

    /** Whether arguments are rendered in the Entry line. */
    boolean logArgs() default true;

    /** Whether a summary of the return value is logged at DEBUG. */
    boolean logResult() default true;

    /** Business outcome label for the Success line; defaults to the method name. */
    String outcome() default "";

    enum Level {
        DEBUG,
        INFO
    }
}
