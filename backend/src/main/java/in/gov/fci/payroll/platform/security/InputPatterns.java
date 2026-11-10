package in.gov.fci.payroll.platform.security;

/**
 * Allow-list regular expressions for every identifier accepted from clients. Use with {@code
 * jakarta.validation.constraints.Pattern} so that validation is declarative and auditable.
 */
public final class InputPatterns {

    /** Login name: lower-case letters, digits, dot, underscore, hyphen; 3–32 characters. */
    public static final String USERNAME = "^[a-z0-9._-]{3,32}$";

    /** FCI employee number. */
    public static final String EMPLOYEE_ID = "^[A-Z0-9]{4,20}$";

    /** Office identifier used in materialised paths; contains no regex metacharacters. */
    public static final String OFFICE_ID = "^[A-Z0-9-]{2,20}$";

    /** Role code, e.g. {@code REGIONAL_ADMIN}. */
    public static final String ROLE_CODE = "^[A-Z][A-Z0-9_]{1,49}$";

    /** Permission code in {@code RESOURCE:ACTION} form, e.g. {@code USER:CREATE}. */
    public static final String PERMISSION_CODE = "^[A-Z][A-Z_]{1,30}:[A-Z][A-Z_]{1,30}$";

    /** Indian mobile number in E.164 form. */
    public static final String MOBILE_IN = "^\\+91[6-9][0-9]{9}$";

    /** MongoDB ObjectId in hex form. */
    public static final String OBJECT_ID = "^[0-9a-f]{24}$";

    /** UUID (any version), used for idempotency keys. */
    public static final String UUID =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    /**
     * Free-text search term: letters and combining marks of any script (Devanagari vowel signs are
     * marks, not letters), digits, space and a few separators.
     */
    public static final String SEARCH_TEXT = "^[\\p{L}\\p{M}\\p{N} ._@-]{1,64}$";

    /** Display name: letters (any script), marks, space, dot, apostrophe and hyphen. */
    public static final String PERSON_NAME = "^[\\p{L}\\p{M} .'-]{1,100}$";

    /** Sort expression: {@code field} or {@code field,asc|desc}. */
    public static final String SORT = "^[A-Za-z][A-Za-z0-9]{0,39}(,(asc|desc|ASC|DESC))?$";

    /** Six-digit TOTP code. */
    public static final String OTP = "^[0-9]{6}$";

    private InputPatterns() {}

    /**
     * R-ASCII: the database character set is US7ASCII, so free text is printable ASCII only (space
     * to tilde, no control characters). Null is not printable text.
     */
    public static boolean isPrintableAscii(String value) {
        return value != null && value.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
    }

    /**
     * R-ASCII for multi-line free text (a reason, remark, subject or description entered in a text
     * area): printable ASCII with tab and line breaks, exactly what {@code @PrintableAscii} accepts
     * at the request boundary. Null is not printable text.
     */
    public static boolean isPrintableText(String value) {
        return value != null
                && value.chars()
                        .allMatch(
                                c ->
                                        (c >= 0x20 && c <= 0x7E)
                                                || c == '\t'
                                                || c == '\r'
                                                || c == '\n');
    }
}
