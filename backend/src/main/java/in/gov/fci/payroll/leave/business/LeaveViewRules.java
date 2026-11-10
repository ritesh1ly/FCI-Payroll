package in.gov.fci.payroll.leave.business;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Set;

/**
 * The I-11 Leave Data View columns worked out from an applied leave transaction (SRS I-11 field
 * table L4192-L4205; SRS-LV-33; US-GAP-59): the rule its pay effect applies and the cycle it
 * applies in.
 */
public final class LeaveViewRules {

    /** The wage effects that reduce the month's pay (BR-LEAVE-007). */
    private static final Set<String> PAY_REDUCING = Set.of("FULL_DEDUCTION", "HALF_PAY");

    private LeaveViewRules() {}

    /**
     * SRS I-11 "Pay effect ... citing the rule applied (BR-LEAVE-007, BR-LEAVE-010)": a back-dated
     * event is settled through the retrospective engine (BR-LEAVE-010); a full deduction or half
     * pay of an open month is the LWP reduction (BR-LEAVE-007); any other effect cites neither.
     */
    public static String ruleOf(String wageEffect, boolean backDated) {
        if (backDated) {
            return "BR-LEAVE-010";
        }
        return wageEffect != null && PAY_REDUCING.contains(wageEffect) ? "BR-LEAVE-007" : null;
    }

    /**
     * SRS I-11 "Applied in": a back-dated event applies in the open cycle at its approval
     * (BR-LEAVE-006): the month it was approved in, or the next one when that month's cycle was
     * locked at or before the approval.
     *
     * @param approvedIn the month of the approval (India)
     * @param lockedAt when that month's cycle of the member's unit was locked; null if it is not
     * @param approvedAt the approval
     */
    public static YearMonth appliedIn(YearMonth approvedIn, Instant lockedAt, Instant approvedAt) {
        return lockedAt != null && !lockedAt.isAfter(approvedAt)
                ? approvedIn.plusMonths(1)
                : approvedIn;
    }
}
