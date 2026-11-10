package in.gov.fci.payroll.audit.business;

/**
 * X-04 Status / Outcome of one native audit row (SRS-G-2, FAP-13: Submitted, Approved, Returned,
 * Rejected, Posted or Failed), read from what the row records: a submission, a checker's decision
 * at a level or at the end, a refused attempt, or a change applied to the record.
 */
public final class AuditOutcome {

    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED = "APPROVED";
    public static final String RETURNED = "RETURNED";
    public static final String REJECTED = "REJECTED";
    public static final String POSTED = "POSTED";
    public static final String FAILED = "FAILED";

    private AuditOutcome() {}

    /**
     * The row's outcome. Rows written by the approval engine: a submission ({@code
     * PENDING_APPROVAL}), an approval at a level below the last ({@code LEVEL}), the final decision
     * ({@code DECISION}, its new value). A refusal is recorded as {@code FAILURE…}. Any other row
     * is a change applied to the record: Posted.
     */
    public static String of(String fieldName, String newValue) {
        if (newValue != null && newValue.startsWith("FAILURE")) {
            return FAILED;
        }
        if ("DECISION".equals(fieldName)) {
            return switch (newValue == null ? "" : newValue) {
                case APPROVED -> APPROVED;
                case REJECTED -> REJECTED;
                case RETURNED -> RETURNED;
                // A withdrawal or any later decision kind is shown as recorded, never relabelled.
                default -> newValue;
            };
        }
        if ("LEVEL".equals(fieldName)) {
            return APPROVED;
        }
        if ("PENDING_APPROVAL".equals(newValue)) {
            return SUBMITTED;
        }
        return POSTED;
    }
}
