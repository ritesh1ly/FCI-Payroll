package in.gov.fci.payroll.employee.business;

/**
 * The M-01 Pay Status (SRS l.3738, US-GAP-275): a System field that "includes Active, Hold,
 * Suspended, Deputation and Separated". Pure: it is derived from what Payroll already holds, the
 * employment status an approved I-17 / I-21 event set and the M-01 Hold flag, and is never entered.
 *
 * <p>OD-53 (Awaiting FCI): the SRS lists the values but not how they combine. Built: the employment
 * status first (Separated for SEPARATED or DISMISSED, Suspended, Deputation for ON_DEPUTATION, and
 * Absconding - an employment status of its own, SRS-1-64, which the list "includes" does not
 * exclude), then Hold while the member is in service with the Hold flag set, else Active.
 */
public final class PayStatusRules {

    /** The Pay Status values (codes shown with their label, {@code m01.payStatus.<code>}). */
    public enum PayStatus {
        ACTIVE,
        HOLD,
        SUSPENDED,
        DEPUTATION,
        SEPARATED,
        ABSCONDING
    }

    private PayStatusRules() {}

    /** The Pay Status of a member with {@code employmentStatus} and the Hold flag. */
    public static PayStatus of(String employmentStatus, boolean holdFlag) {
        if (employmentStatus != null) {
            switch (employmentStatus) {
                case "SEPARATED", "DISMISSED" -> {
                    return PayStatus.SEPARATED;
                }
                case "SUSPENDED" -> {
                    return PayStatus.SUSPENDED;
                }
                case "ON_DEPUTATION" -> {
                    return PayStatus.DEPUTATION;
                }
                case "ABSCONDING" -> {
                    return PayStatus.ABSCONDING;
                }
                default -> {
                    // IN_SERVICE (or a value not on the list): Hold or Active below.
                }
            }
        }
        return holdFlag ? PayStatus.HOLD : PayStatus.ACTIVE;
    }
}
