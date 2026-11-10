package in.gov.fci.payroll.platform.error;

/**
 * A single field-level problem inside a validation error.
 *
 * @param field the field or parameter name (never the rejected value)
 * @param code machine-readable constraint code, e.g. {@code NotBlank}
 * @param message user-safe, localised message
 */
public record Violation(String field, String code, String message) {}
