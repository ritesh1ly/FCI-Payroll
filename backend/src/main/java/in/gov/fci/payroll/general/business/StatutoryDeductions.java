package in.gov.fci.payroll.general.business;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Statutory deductions due on an I-23 supplementary payment: TDS, CPF / EPS including higher
 * pension, PT (I-23 "Apply statutory deductions due"). A port: the rules belong to modules 08
 * (income tax, PT) and 07 (CPF / EPS), which supply the implementation together; the tax effect of
 * a payment depends on the year's projection from the payroll engines (module 16, plan 08 D-4).
 *
 * <p>Decision D-5: until then the shipped implementation answers "not configured" and the request
 * cannot be submitted - nothing is guessed. A RECOVERY never asks (a recovery deducts nothing).
 */
public interface StatutoryDeductions {

    /** What the deduction rules need to know about the payment. */
    record Payment(
            Long employeeId,
            String forPeriodYymm,
            String paymentType,
            Long componentId,
            BigDecimal amount,
            boolean taxable,
            boolean epsApplicable) {}

    /**
     * The total statutory deductions due on the payment.
     *
     * @return empty when the rules are not configured
     */
    Optional<BigDecimal> due(Payment payment);
}
