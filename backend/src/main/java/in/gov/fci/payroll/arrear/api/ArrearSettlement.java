package in.gov.fci.payroll.arrear.api;

import java.math.BigDecimal;

/**
 * The arrears of a separated member, routed to the settlement case (SRS-8-16, E2 l.3084; SRS-10-13)
 * and paid in it: the settlement reads the amount here and tells the owning module when it is paid,
 * so that no later cycle's lock posts the same differential again (DDL {@code arrear_status}).
 */
public interface ArrearSettlement {

    /** The member's approved differentials not yet paid; zero when there are none. */
    BigDecimal approvedTotal(long employeeId);

    /**
     * The settlement was released: the member's approved differentials are PAID and an event with
     * none left approved is POSTED, in the release's transaction (R-AUD).
     *
     * @param actor the Checker who approved the release
     * @param approvalId the release's approval
     */
    void paid(long employeeId, String actor, long approvalId);
}
