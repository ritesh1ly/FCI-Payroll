package in.gov.fci.payroll.general.api;

import in.gov.fci.payroll.platform.security.AccessContext;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * I-23 Supplementary Payment (Post-Lock) for the modules that compute an amount for a month-end
 * locked period: arrears, PLI, OTA, encashments, reimbursements, CPF / EPS, court cases, recoveries
 * (BR-CYC-010: "every payment type can be paid here"). The amount comes from the originating
 * override or event (I-23 field table: CALC), never typed on the screen.
 *
 * <p>The request is recorded with the same checks as the screen (locked period, excluded pay code,
 * member, duplicate payment) and then follows the screen's flow: the Payroll Maker submits it, the
 * Payroll Checker approves with OTP.
 */
public interface SupplementaryPayments {

    /**
     * The order or approval note that authorises the payment (SRS-G-16; mandatory, DDL {@code
     * chk_supp_locked_cycle}).
     */
    record Order(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /**
     * A payment to record.
     *
     * @param payCode the M-07 pay code paid or recovered, or null when the payment spans several
     *     (e.g. pending salary)
     * @param amount computed by the originating module; negative for a RECOVERY
     */
    record NewPayment(
            Long employeeId,
            String forPeriodYymm,
            String paymentType,
            Integer payCode,
            BigDecimal amount,
            LocalDate applicationDate,
            LocalDate sanctionDate,
            String sanctionOrderRef,
            Order order) {}

    /**
     * Records the request as the acting Maker.
     *
     * @return the payment id ({@code supplementary_payment.payment_id})
     */
    Long raise(AccessContext maker, NewPayment payment);
}
