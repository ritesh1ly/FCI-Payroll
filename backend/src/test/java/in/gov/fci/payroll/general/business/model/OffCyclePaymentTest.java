package in.gov.fci.payroll.general.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The I-20 case rules (SRS-G-10) and the DDL checks they mirror (chk_off_cycle_dup, _cancel). */
class OffCyclePaymentTest {

    private static final Instant NOW = Instant.parse("2026-10-21T05:30:00Z");

    private static OffCyclePayment captured() {
        return OffCyclePayment.capture(
                104882L,
                null,
                "LATE_JOINER",
                "202609",
                "202610",
                new BigDecimal("41250.00"),
                "HQ/PAY/2026/17");
    }

    private static void assertCode(Runnable action, GeneralErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    void srsG10_aCapturedCaseIsActiveNotInvoicedAndUnchecked() {
        OffCyclePayment c = captured();
        assertThat(c.getRecordStatus()).isEqualTo(OffCyclePayment.ACTIVE);
        assertThat(c.getInvoiceStatus()).isEqualTo("NOT_RAISED");
        assertThat(c.isDuplicateCheckRun()).isFalse();
        assertThat(c.duplicateCheckPassed()).isFalse();
        // "Processing Month: not set equal to Salary Month by default"
        assertThat(c.getProcessingYymm()).isNotEqualTo(c.getSalaryYymm());
    }

    @Test
    void chk_off_cycle_dup_theCheckRecordsPassOrTheFailureReason() {
        OffCyclePayment c = captured();
        c.duplicateCheck(null, NOW);
        assertThat(c.duplicateCheckPassed()).isTrue();
        assertThat(c.getDuplicateCheckResult()).isEqualTo("PASS");
        assertThat(c.getDuplicateCheckAt()).isEqualTo(NOW);

        c.duplicateCheck("salary for 202609 already paid in the regular run", NOW);
        assertThat(c.duplicateCheckPassed()).isFalse();
        assertThat(c.getDuplicateCheckResult()).startsWith("FAIL: ");
    }

    @Test
    void srsG10_approvalExcludesTheSalaryMonthFromTheRegularRun() {
        OffCyclePayment c = captured();
        c.duplicateCheck(null, NOW);
        c.submitted(51L);
        c.approved();
        assertThat(c.getExcludedFromYymm()).isEqualTo("202609");
        assertThat(c.getApprovalId()).isEqualTo(51L);
    }

    @Test
    void srsG10_noRoleCanApproveACaseWhoseDuplicateCheckFailed() {
        OffCyclePayment c = captured();
        c.duplicateCheck("another off-cycle case covers 202609", NOW);
        assertCode(c::approved, GeneralErrorCode.OFFCYCLE_DUPLICATE);
        assertThat(c.getExcludedFromYymm()).isNull();

        OffCyclePayment unchecked = captured();
        assertCode(unchecked::approved, GeneralErrorCode.OFFCYCLE_DUPLICATE);
    }

    @Test
    void chk_off_cycle_cancel_cancelKeepsTheRowWithItsReason() {
        OffCyclePayment c = captured();
        c.cancel("Order withdrawn by the competent authority");
        assertThat(c.getRecordStatus()).isEqualTo(OffCyclePayment.CANCELLED);
        assertThat(c.getCancelReason()).isEqualTo("Order withdrawn by the competent authority");
        // A cancelled case is settled: no second cancel, no approval.
        assertCode(() -> c.cancel("again"), GeneralErrorCode.OFFCYCLE_STATUS);
        assertCode(() -> c.submitted(9L), GeneralErrorCode.OFFCYCLE_STATUS);
    }

    @Test
    void SRS_G_10_aCaseCancelledWhileAwaitingTheCheckerCannotBeApproved() {
        // The duplicate check passed and the case went to the Checker; the Maker then cancelled
        // it. A late approval must not exclude the salary month from the regular run.
        OffCyclePayment c = captured();
        c.duplicateCheck(null, NOW);
        c.submitted(51L);
        c.cancel("Order withdrawn by the competent authority");
        assertCode(c::approved, GeneralErrorCode.OFFCYCLE_STATUS);
        assertThat(c.getExcludedFromYymm()).isNull();
        assertThat(c.getRecordStatus()).isEqualTo(OffCyclePayment.CANCELLED);
    }

    @Test
    void srsG37_afterTheInvoiceIsSentCancelWaitsForAppsConfirmation() {
        OffCyclePayment c = captured();
        ReflectionTestUtils.setField(c, "invoiceStatus", "SENT_TO_APPS");
        assertCode(() -> c.cancel("late"), GeneralErrorCode.OFFCYCLE_INVOICE_SENT);
        ReflectionTestUtils.setField(c, "invoiceStatus", "CANCEL_REQUESTED");
        assertCode(() -> c.cancel("late"), GeneralErrorCode.OFFCYCLE_INVOICE_SENT);
        ReflectionTestUtils.setField(c, "invoiceStatus", "CANCELLED_IN_APPS");
        c.cancel("APPS confirmed the invoice cancelled");
        assertThat(c.getRecordStatus()).isEqualTo(OffCyclePayment.CANCELLED);
    }

    // SRS-G-37 (SRS v0.5 l.551, BMAD TR-002): an invoice RAISED but not yet sent to APPS does not
    // hold the entry; it cancels freely, with no APPS confirmation.
    @Test
    void SRS_G_37_anInvoiceRaisedButNotSentCancelsFreely() {
        OffCyclePayment c = captured();
        ReflectionTestUtils.setField(c, "invoiceStatus", "RAISED");
        c.cancel("before sending");
        assertThat(c.getRecordStatus()).isEqualTo(OffCyclePayment.CANCELLED);
        assertThat(c.getCancelReason()).isEqualTo("before sending");
    }

    // NEW-G1-03 (US-01-06 AC3, E2E-36): the refusal states the Invoice Sent to APPS Date (India's
    // day of the instant: 18:45 UTC is the next day in India) and "-" when none is recorded.
    @Test
    void SRS_EH_12_theRefusedCancelStatesTheInvoiceSentToAppsDate() {
        OffCyclePayment c = captured();
        ReflectionTestUtils.setField(c, "invoiceStatus", "SENT_TO_APPS");
        ReflectionTestUtils.setField(
                c, "invoiceSentToAppsDate", java.time.Instant.parse("2026-10-30T18:45:00Z"));
        assertThatThrownBy(() -> c.cancel("late"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getMessageArgs()).containsExactly("31-10-2026"));
        ReflectionTestUtils.setField(c, "invoiceSentToAppsDate", null);
        assertThatThrownBy(() -> c.cancel("late"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getMessageArgs()).containsExactly("-"));
        assertThat(c.getRecordStatus()).isNotEqualTo(OffCyclePayment.CANCELLED);
    }
}
