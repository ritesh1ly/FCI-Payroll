package in.gov.fci.payroll.platform.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link PrintableAscii}: space to tilde, plus tab, carriage return and line feed. */
public class PrintableAsciiValidator implements ConstraintValidator<PrintableAscii, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.chars().allMatch(PrintableAsciiValidator::plain);
    }

    /** A character a US7ASCII text column keeps as typed. */
    static boolean plain(int c) {
        return (c >= 0x20 && c <= 0x7E) || c == '\t' || c == '\r' || c == '\n';
    }
}
