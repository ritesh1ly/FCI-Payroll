package in.gov.fci.payroll.platform.logging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method parameter, record component or field whose value must never be logged. The ESER
 * aspect renders it as {@code ***}. Records holding secrets should also override {@code
 * toString()}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.PARAMETER,
    ElementType.RECORD_COMPONENT,
    ElementType.FIELD,
    ElementType.METHOD
})
public @interface Sensitive {}
