package in.gov.fci.payroll.penalty.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The order, schedule, revoke and over-and-above states of plan 12 (SRS-14-1 … 14-20). */
class PenaltyModelTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 1);

    @Test
    void SRS_14_1_SRS_DM_20_anOrderEnteredOnI06IsEntAndOneFromHrmsKeepsItsTask() {
        PenaltyOrder entered =
                PenaltyOrder.approved(
                        "P-1",
                        7L,
                        "O-1",
                        "RECOVERY",
                        BigDecimal.TEN,
                        2,
                        DAY,
                        DAY.plusMonths(1),
                        DAY,
                        true,
                        null,
                        null,
                        "x".repeat(60),
                        81L);
        assertThat(entered.getPenaltySource()).isEqualTo("ENT");
        assertThat(entered.getHrmsChangeTaskId()).isNull();
        assertThat(entered.getApprovedBy()).hasSize(50);
        assertThat(entered.status()).isEqualTo(PenaltyOrder.Status.ACTIVE);
        assertThat(entered.isBackdated()).isTrue();
        assertThat(entered.getApprovalId()).isEqualTo(81L);
        PenaltyOrder hrms =
                PenaltyOrder.approved(
                        "P-2", 7L, "O-2", "CENSURE", null, null, DAY, null, DAY, false, 40L, "HR-1",
                        "ddo", 82L);
        assertThat(hrms.getPenaltySource()).isEqualTo("HRMS");
        assertThat(hrms.getHrmsChangeTaskId()).isEqualTo(40L);
        assertThat(hrms.getApprovedBy()).isEqualTo("ddo");
        PenaltyOrder anonymous =
                PenaltyOrder.approved(
                        "P-3",
                        7L,
                        "O-3",
                        "RECOVERY",
                        BigDecimal.ONE,
                        1,
                        DAY,
                        DAY,
                        DAY,
                        false,
                        null,
                        null,
                        null,
                        83L);
        assertThat(anonymous.getApprovedBy()).isNull();
        anonymous.status(PenaltyOrder.Status.REVOKED);
        assertThat(anonymous.status()).isEqualTo(PenaltyOrder.Status.REVOKED);
    }

    @Test
    void SRS_14_11_aMonthIsRecoveredInFullOrInPartWaivedOrDeferred() {
        PenaltyRecoveryLine line = PenaltyRecoveryLine.pending(1L, "202604", new BigDecimal("100"));
        assertThat(line.status()).isEqualTo(PenaltyRecoveryLine.Status.PENDING);
        assertThat(line.getRecoveredAmount()).isEqualByComparingTo("0");
        line.recovered(new BigDecimal("99.99"));
        assertThat(line.status()).isEqualTo(PenaltyRecoveryLine.Status.PARTIAL);
        line.recovered(new BigDecimal("100"));
        assertThat(line.status()).isEqualTo(PenaltyRecoveryLine.Status.RECOVERED);
        line.recovered(new BigDecimal("100.01"));
        assertThat(line.status()).isEqualTo(PenaltyRecoveryLine.Status.RECOVERED);
        line.reduced(new BigDecimal("40"));
        assertThat(line.getScheduledAmount()).isEqualByComparingTo("40");
        line.waived();
        assertThat(line.status()).isEqualTo(PenaltyRecoveryLine.Status.WAIVED);
        line.deferred("x".repeat(600));
        assertThat(line.status()).isEqualTo(PenaltyRecoveryLine.Status.DEFERRED);
        assertThat(line.getDeferralReason()).hasSize(500);
        line.deferred(null);
        assertThat(line.getDeferralReason()).isNull();
        line.deferred("F&F");
        assertThat(line.getDeferralReason()).isEqualTo("F&F");
    }

    @Test
    void SRS_14_16_aRevokeIsPendingUntilTheCheckerDecides() {
        Instant now = Instant.parse("2026-04-02T05:00:00Z");
        PenaltyRevoke r =
                PenaltyRevoke.submitted(
                        1L,
                        PenaltyRevoke.Type.PARTIAL,
                        "R-1",
                        DAY,
                        "waived",
                        BigDecimal.TEN,
                        null,
                        null,
                        now);
        assertThat(r.type()).isEqualTo(PenaltyRevoke.Type.PARTIAL);
        assertThat(r.status()).isEqualTo(PenaltyRevoke.Status.PENDING);
        assertThat(r.isIncrementRestored()).isFalse();
        assertThat(r.getCreatedAt()).isEqualTo(now);
        r.approval(81L);
        assertThat(r.getApprovalId()).isEqualTo(81L);
        r.decided(PenaltyRevoke.Status.RETURNED);
        assertThat(r.status()).isEqualTo(PenaltyRevoke.Status.RETURNED);
    }

    @Test
    void SRS_14_2_anOverAndAbovePaymentIsSubmittedNotRaisedAndThenDecided() {
        OverAndAbovePayment p =
                OverAndAbovePayment.submitted(
                        7L, 3L, true, "202604", BigDecimal.TEN, DAY, "S-1", DAY.plusDays(1));
        assertThat(p.status()).isEqualTo(OverAndAbovePayment.Status.SUBMITTED);
        assertThat(p.getInvoiceStatus()).isEqualTo("NOT_RAISED");
        assertThat(p.isTaxable()).isTrue();
        p.status(OverAndAbovePayment.Status.APPROVED);
        assertThat(p.status()).isEqualTo(OverAndAbovePayment.Status.APPROVED);
    }

    /** SRS-14-15: the Penalty ID is set once the row has its number, and may be given again. */
    @Test
    void SRS_14_15_thePenaltyIdIsSetOnceTheRowHasItsNumber() {
        PenaltyOrder order =
                PenaltyOrder.approved(
                        "TMP",
                        7L,
                        "O-9",
                        "RECOVERY",
                        BigDecimal.TEN,
                        2,
                        DAY,
                        DAY,
                        DAY,
                        false,
                        null,
                        null,
                        "maker",
                        90L);
        assertThat(order.getPenaltyRef()).isEqualTo("TMP");

        order.reference("PEN-2026-00012");
        assertThat(order.getPenaltyRef()).isEqualTo("PEN-2026-00012");

        order.reference(null);
        assertThat(order.getPenaltyRef()).isNull();
        order.period(DAY.plusMonths(3));
        assertThat(order.getPeriodTo()).isEqualTo(DAY.plusMonths(3));
    }
}
