package in.gov.fci.payroll.loans.api;

/**
 * SRS-12-7 (US-GAP-198): the Payroll Checker returned an I-04 loan or advance sanction request;
 * published in the decision's transaction so the Payroll Maker is notified with the reason (eSeva
 * staff notices). The request stays in the Maker's queue for correction and resubmission.
 *
 * @param unitId the cycle unit of the member, whose Payroll Makers are told (OD-56)
 * @param approvalId the returned request's approval item
 */
public record LoanRequestReturned(
        long unitId,
        long approvalId,
        String eis,
        String loanType,
        String reason,
        String returnedBy) {}
