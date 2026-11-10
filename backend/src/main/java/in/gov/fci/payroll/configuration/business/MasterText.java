package in.gov.fci.payroll.configuration.business;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.InputPatterns;
import java.util.List;

/**
 * Free text of a master field (R-ASCII: the database character set is US7ASCII, so codes and
 * English text are printable ASCII; Hindi lives only in {@code label_master.label_text}). Pure
 * rules.
 */
public final class MasterText {

    private MasterText() {}

    /**
     * The stripped value, required.
     *
     * @param field the field's message key, shown in the error
     * @throws FciException MASTER_INVALID_TEXT when blank, longer than {@code max} or not printable
     *     ASCII
     */
    public static String required(String field, String value, int max) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > max || !InputPatterns.isPrintableAscii(v)) {
            throw invalid(field, max);
        }
        return v;
    }

    /** The stripped value, or null when blank; otherwise as {@link #required}. */
    public static String optional(String field, String value, int max) {
        return value == null || value.isBlank() ? null : required(field, value, max);
    }

    /**
     * As {@link #required}, for multi-line text entered in a text area (a reason, a subject, a
     * source instrument): line breaks and tabs are kept (InputPatterns.isPrintableText).
     */
    public static String requiredText(String field, String value, int max) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > max || !InputPatterns.isPrintableText(v)) {
            throw invalid(field, max);
        }
        return v;
    }

    /** The stripped value, or null when blank; otherwise as {@link #requiredText}. */
    public static String optionalText(String field, String value, int max) {
        return value == null || value.isBlank() ? null : requiredText(field, value, max);
    }

    private static FciException invalid(String field, int max) {
        return new FciException(
                ApiErrorCode.MASTER_INVALID_TEXT, new Object[] {field, max}, List.of(), null);
    }
}
