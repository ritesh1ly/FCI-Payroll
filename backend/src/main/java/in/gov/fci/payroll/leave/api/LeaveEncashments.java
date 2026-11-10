package in.gov.fci.payroll.leave.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Leave encashment at separation, for Full &amp; Final Settlement (plan 19 phase 19e; SRS-10-16,
 * SRS-LV-16, SRS-LV-17, SRS-LV-31; BR-FNF-009, BR-LEAVE-008): the encashable balance HRMS sent,
 * limited to the M-20 aggregate ceiling, valued at the pay on the separation date (the M-20 pay
 * elements / divisor x days). Never defaulted: a parameter, a balance or a pay that is not there
 * refuses with "not configured" (R-RULE), and the settlement is not finalised on partial data
 * (SRS-10-28).
 */
public interface LeaveEncashments {

    /**
     * The valuation the settlement shows and records.
     *
     * @param balanceDays the encashable balance received from HRMS (all encashable types)
     * @param encashedDays the days valued: the balance, at most the ceiling
     * @param capped whether the ceiling limited them
     * @param pay the monthly pay elements on the date (M-20 ENCASH_PAY_ELEMENTS)
     * @param divisor the M-20 encashment divisor
     * @param ratePerDay pay / divisor, to the paisa
     * @param amount days x pay / divisor, rounded once to the rupee
     * @param payCode the pay code it is posted under (M-20 ENCASH_PAY_CODES, the first)
     * @param basis the valuation basis as the statement prints it
     */
    record Valuation(
            BigDecimal balanceDays,
            BigDecimal encashedDays,
            boolean capped,
            BigDecimal pay,
            BigDecimal divisor,
            BigDecimal ratePerDay,
            BigDecimal amount,
            int payCode,
            String basis) {}

    /** BR-FNF-009: the leave encashment of a member separated on {@code separationDate}. */
    Valuation atSeparation(long employeeId, LocalDate separationDate);
}
