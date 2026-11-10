package in.gov.fci.payroll.general.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The I-23 request rules and the DDL checks they mirror (SRS-G-33, SRS-G-34, BR-CYC-010). */
class SupplementaryPaymentTest {

    private static final LocalDate APPLIED = LocalDate.of(2026, 8, 10);
    private static final Instant NOW = Instant.parse("2026-10-21T05:30:00Z");

    private static SupplementaryPayment payment(String type, String amount) {
        return SupplementaryPayment.record(
                7L,
                3L,
                "202607",
                type,
                null,
                new BigDecimal(amount),
                APPLIED,
                null,
                null,
                11L,
                true,
                false);
    }

    private static SupplementaryPayment passed() {
        SupplementaryPayment p = payment("SALARY_WAGE", "52000.00");
        p.duplicateChecked(false, null);
        return p;
    }

    private static void assertCode(Runnable action, GeneralErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    void srsG33_aRecordedRequestStartsRecordedPendingCheckAndNotInvoiced() {
        SupplementaryPayment p = payment("SALARY_WAGE", "52000.00");
        assertThat(p.getStatus()).isEqualTo(SupplementaryPayment.Status.RECORDED);
        assertThat(p.getDuplicateCheck()).isEqualTo(SupplementaryPayment.DuplicateCheck.PENDING);
        assertThat(p.getInvoiceStatus()).isEqualTo("NOT_RAISED");
        assertThat(p.getOrderAttachmentId()).isEqualTo(11L);
        assertThat(p.isTaxable()).isTrue();
        assertThat(p.isEpsApplicable()).isFalse();
        assertThat(p.duplicateCheckPassed()).isFalse();
    }

    @Test
    void brCyc010_everyEnumeratedTypeIsAcceptedAndNothingElse() {
        assertThat(SupplementaryPayment.TYPES).hasSize(19);
        for (String type : SupplementaryPayment.TYPES) {
            String amount = SupplementaryPayment.RECOVERY.equals(type) ? "-10" : "10";
            assertThat(payment(type, amount).getPaymentType()).isEqualTo(type);
        }
        assertCode(() -> payment("BONUS", "10"), GeneralErrorCode.SUPPLEMENTARY_INPUT);
    }

    @Test
    void i23_aRecoveryIsNegativeAndAPaymentPositiveNeverZero() {
        assertCode(() -> payment("RECOVERY", "10"), GeneralErrorCode.SUPPLEMENTARY_INPUT);
        assertCode(() -> payment("OTA", "-10"), GeneralErrorCode.SUPPLEMENTARY_INPUT);
        assertCode(() -> payment("OTA", "0"), GeneralErrorCode.SUPPLEMENTARY_INPUT);
        assertCode(
                () ->
                        SupplementaryPayment.record(
                                7L, 3L, "202607", "OTA", null, null, APPLIED, null, null, 11L, true,
                                false),
                GeneralErrorCode.SUPPLEMENTARY_INPUT);
    }

    @Test
    void chk_supp_sanction_after_appl_sanctionStrictlyAfterApplication() {
        assertCode(
                () ->
                        SupplementaryPayment.record(
                                7L,
                                3L,
                                "202607",
                                "OTA",
                                null,
                                BigDecimal.TEN,
                                APPLIED,
                                APPLIED,
                                null,
                                11L,
                                true,
                                false),
                GeneralErrorCode.SUPPLEMENTARY_SANCTION_DATE);
        assertCode(
                () ->
                        SupplementaryPayment.record(
                                7L,
                                3L,
                                "202607",
                                "OTA",
                                null,
                                BigDecimal.TEN,
                                null,
                                null,
                                null,
                                11L,
                                true,
                                false),
                GeneralErrorCode.SUPPLEMENTARY_SANCTION_DATE);
        SupplementaryPayment ok =
                SupplementaryPayment.record(
                        7L,
                        3L,
                        "202607",
                        "OTA",
                        null,
                        BigDecimal.TEN,
                        APPLIED,
                        APPLIED.plusDays(1),
                        "S/1",
                        11L,
                        true,
                        false);
        assertThat(ok.getSanctionDate()).isEqualTo(APPLIED.plusDays(1));
        assertThat(ok.getSanctionOrderRef()).isEqualTo("S/1");
    }

    @Test
    void chk_supp_dup_passed_aBlockedRequestCannotBeSubmittedOrApproved() {
        SupplementaryPayment p = payment("SALARY_WAGE", "52000.00");
        p.duplicateChecked(true, 99L);
        assertThat(p.getDuplicateOfPaymentId()).isEqualTo(99L);
        assertCode(() -> p.submit(5L), GeneralErrorCode.SUPPLEMENTARY_DUPLICATE);

        SupplementaryPayment q = passed();
        q.submit(5L);
        q.duplicateChecked(true, null); // found at approval
        assertCode(() -> q.approve(77L), GeneralErrorCode.SUPPLEMENTARY_DUPLICATE);
    }

    @Test
    void chk_supp_post_lock_otp_approvalKeepsTheOtpChallenge() {
        SupplementaryPayment p = passed();
        p.submit(5L);
        assertThat(p.getApprovalId()).isEqualTo(5L);
        assertThatThrownBy(() -> p.approve(null)).isInstanceOf(IllegalStateException.class);
        p.approve(77L);
        assertThat(p.getStatus()).isEqualTo(SupplementaryPayment.Status.APPROVED);
        assertThat(p.getOtpChallengeId()).isEqualTo(77L);
    }

    @Test
    void srsG33_returnedGoesBackToTheMakerRejectedIsFinal() {
        SupplementaryPayment p = passed();
        p.submit(5L);
        p.returned();
        assertThat(p.getStatus()).isEqualTo(SupplementaryPayment.Status.RECORDED);
        p.submit(6L);
        p.rejected();
        assertThat(p.getStatus()).isEqualTo(SupplementaryPayment.Status.REJECTED);
        assertCode(() -> p.submit(7L), GeneralErrorCode.SUPPLEMENTARY_STATUS);
        assertCode(p::returned, GeneralErrorCode.SUPPLEMENTARY_STATUS);
        assertCode(p::rejected, GeneralErrorCode.SUPPLEMENTARY_STATUS);
        assertCode(() -> p.approve(1L), GeneralErrorCode.SUPPLEMENTARY_STATUS);
    }

    @Test
    void srsG37_cancelKeepsTheRowNotWhilePendingAndNotTwice() {
        SupplementaryPayment pending = passed();
        pending.submit(5L);
        assertCode(() -> pending.cancel("x", NOW), GeneralErrorCode.SUPPLEMENTARY_STATUS);

        SupplementaryPayment p = passed();
        p.cancel("Raised in error", NOW);
        assertThat(p.getStatus()).isEqualTo(SupplementaryPayment.Status.CANCELLED);
        assertThat(p.getCancelReason()).isEqualTo("Raised in error");
        assertThat(p.getCancelledAt()).isEqualTo(NOW);
        assertCode(() -> p.cancel("again", NOW), GeneralErrorCode.SUPPLEMENTARY_STATUS);

        SupplementaryPayment released = passed();
        ReflectionTestUtils.setField(released, "status", SupplementaryPayment.Status.RELEASED);
        assertCode(() -> released.cancel("late", NOW), GeneralErrorCode.SUPPLEMENTARY_STATUS);
    }

    @Test
    void chk_supp_cancel_apps_afterSendingOnlyWithAppsConfirmation() {
        SupplementaryPayment p = passed();
        p.submit(5L);
        p.approve(77L);
        ReflectionTestUtils.setField(p, "invoiceStatus", "SENT_TO_APPS");
        assertCode(() -> p.cancel("late", NOW), GeneralErrorCode.SUPPLEMENTARY_INVOICE_SENT);
        ReflectionTestUtils.setField(p, "appsCancelStatus", "REQUESTED");
        assertCode(() -> p.cancel("late", NOW), GeneralErrorCode.SUPPLEMENTARY_INVOICE_SENT);
        ReflectionTestUtils.setField(p, "appsCancelStatus", "CONFIRMED");
        p.cancel("APPS confirmed", NOW);
        assertThat(p.getStatus()).isEqualTo(SupplementaryPayment.Status.CANCELLED);
    }

    // SRS-G-37 (SRS v0.5 l.551, BMAD TR-002): an invoice RAISED but not yet sent to APPS does not
    // hold the entry; it cancels freely, with no APPS confirmation.
    @Test
    void SRS_G_37_anInvoiceRaisedButNotSentCancelsFreely() {
        SupplementaryPayment p = passed();
        p.submit(5L);
        p.approve(77L);
        ReflectionTestUtils.setField(p, "invoiceStatus", "RAISED");
        p.cancel("before sending", NOW);
        assertThat(p.getStatus()).isEqualTo(SupplementaryPayment.Status.CANCELLED);
        assertThat(p.getCancelReason()).isEqualTo("before sending");
    }

    // NEW-G1-03 (US-01-06 AC3): the refusal states the Invoice Sent to APPS Date; the boundary
    // 18:29:59 UTC is still the same day in India.
    @Test
    void SRS_EH_12_theRefusedCancelStatesTheInvoiceSentToAppsDate() {
        SupplementaryPayment p = passed();
        p.submit(5L);
        p.approve(77L);
        ReflectionTestUtils.setField(p, "invoiceStatus", "SENT_TO_APPS");
        ReflectionTestUtils.setField(
                p, "invoiceSentAt", java.time.Instant.parse("2026-10-30T18:29:59Z"));
        assertThatThrownBy(() -> p.cancel("late", NOW))
                .isInstanceOfSatisfying(
                        in.gov.fci.payroll.platform.error.FciException.class,
                        ex -> assertThat(ex.getMessageArgs()).containsExactly("30-10-2026"));
    }
}
