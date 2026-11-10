package in.gov.fci.payroll.payroll.api;

import java.util.List;

/**
 * Members computed in an Actual run whose payment is no longer held (plan 16 phase 16d; SRS-1-7,
 * SRS-1-41): the hold ended (SALARY_RELEASE on I-17, the M-01 flag cleared, absconding revoked)
 * since the run that held them. Published once per written chunk, in the results' transaction, so
 * the X-05 worklist closes an open PAYMENT_HELD with one query per chunk, never per member.
 */
public record MembersPaymentReleased(long cycleInstanceId, List<Long> employeeIds) {

    public MembersPaymentReleased {
        employeeIds = List.copyOf(employeeIds);
    }
}
