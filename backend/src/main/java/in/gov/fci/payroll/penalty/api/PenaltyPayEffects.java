package in.gov.fci.payroll.penalty.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a penalty order does to a member's Basic, carried out by the increment module, the one
 * writer of Basic Pay (SRS-14-8, SRS-14-13, SRS-14-17, SRS-13-26, SRS-21-10, SRS-DM-20;
 * BR-EMPR-021; D12-10). The penalty module owns this port and calls it in the transaction of the
 * approval; the increment module implements it, so the dependency stays increment to {@code
 * penalty::api}.
 */
public interface PenaltyPayEffects {

    /**
     * A waived or cancelled increment stop.
     *
     * @param from the first day of the stop that no longer applies
     * @param to the last day, or null when the stop was open
     * @param orderReference the revoke or cancellation order, kept with the restored event
     */
    record Waived(
            long employeeId,
            String penaltyRef,
            String orderReference,
            LocalDate from,
            LocalDate to,
            long approvalId,
            String by) {}

    /**
     * What a waiver restored.
     *
     * @param incrementId the first {@code pay_increment_cycle} row restored, or null when none
     * @param count how many due dates were restored
     * @param unresolved what still needs a person (null when nothing does): the text of the X-05
     *     entry
     */
    record Restored(Long incrementId, int count, String unresolved) {}

    /**
     * A Basic-reduction order to apply.
     *
     * @param basic the Basic the order sets
     * @param sourceCode HRMS for an order received through I-21, else ENT (R-SRC)
     */
    record Reduction(
            long employeeId,
            String penaltyRef,
            String orderReference,
            LocalDate effectiveFrom,
            BigDecimal basic,
            String sourceCode,
            String hrmsRequestId,
            Long hrmsTaskId,
            long approvalId,
            String by) {}

    /**
     * SRS-14-17: restores the increment the order stopped, from its original due date, and raises
     * the arrear through the Basic history. A separate Basic-reduction order is left as it stands.
     */
    Restored restoreIncrement(Waived waived);

    /**
     * Why the order's reduced Basic cannot be taken (SRS-13-23), or null when it can.
     *
     * @return the reason, in words, for the refusal of the HRMS task at intake
     */
    String reductionDefect(long employeeId, LocalDate effectiveFrom, BigDecimal basic);

    /** SRS-14-8, SRS-13-26: the Basic event "reduction under a penalty order". */
    void reduceBasic(Reduction reduction);

    /**
     * SRS-14-17: a Basic-reduction order ends on its own revoke: the Basic before it applies again
     * from the order's date.
     *
     * @param order the reduction order's reference, as its Basic event holds it
     * @return what still needs a person, or null
     */
    String restoreBasic(Waived waived, String order);
}
