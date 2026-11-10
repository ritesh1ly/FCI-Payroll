package in.gov.fci.payroll.platform.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * R-ASCII at the request boundary: the database character set is US7ASCII, so free text is
 * printable ASCII (space to tilde), with tab and line breaks allowed for multi-line text. Anything
 * else (Hindi, symbols, control characters) is refused with 400 instead of being stored as "?" by
 * the database. Hindi exists only as labels (label_master, the i18n bundles). Null is valid:
 * {@code @NotBlank} decides whether a value is required.
 *
 * <p>Every {@code String} of a request record carries this, a {@code @Pattern}, or a recorded
 * exemption ({@code ModularStructureTest.everyRequestTextIsPrintableAscii}).
 */
@Documented
@Constraint(validatedBy = PrintableAsciiValidator.class)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, TYPE_USE, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface PrintableAscii {

    String message() default "Use English letters, digits and punctuation only.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
