package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.penalty.api.PenaltyPayEffects;
import in.gov.fci.payroll.penalty.business.PenaltyErrorCode;
import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.penalty.business.model.PenaltyRecoveryLine;
import in.gov.fci.payroll.penalty.business.model.PenaltyRevoke;
import in.gov.fci.payroll.penalty.orchestration.PenaltyChangeService.Revoke;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-06 revoke of a recovery order (SRS-14-16): every refusal before the order copy is kept; an
 * approved cancellation refunds what was recovered as the reversing arrear on I-08 under the
 * nature's recovery head (SRS-21-10, plan 13 phase 13g), and refuses without one (R-RULE).
 */
class PenaltyChangeServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 1);
    private static final PenaltyRevoke.Type FULL = PenaltyRevoke.Type.FULL;
    private static final PenaltyRevoke.Type PARTIAL = PenaltyRevoke.Type.PARTIAL;

    private final PenaltyRepositories.Orders orders = mock(PenaltyRepositories.Orders.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PenaltyOrder order = mock(PenaltyOrder.class);
    private final PenaltyRepositories.Lines lines = mock(PenaltyRepositories.Lines.class);
    private final RetroEvents retro = mock(RetroEvents.class);
    private final ReferenceData references = mock(ReferenceData.class);
    private final PenaltyRepositories.Revokes revokes = mock(PenaltyRepositories.Revokes.class);
    private final ExceptionWorklist worklist = mock(ExceptionWorklist.class);
    private final PenaltyPayEffects pay = mock(PenaltyPayEffects.class);
    private final PenaltyChangeService service =
            new PenaltyChangeService(
                    orders,
                    lines,
                    revokes,
                    mock(PenaltyQueryService.class),
                    mock(DocumentStore.class),
                    mock(MakerChecker.class),
                    worklist,
                    mock(AuditService.class),
                    retro,
                    pay,
                    references,
                    JsonMapper.builder().build(),
                    Clock.systemUTC());

    PenaltyChangeServiceTest() {
        when(orders.findById(1L)).thenReturn(Optional.of(order));
        when(order.status()).thenReturn(PenaltyOrder.Status.ACTIVE);
        when(order.getTotalRecoveryAmount()).thenReturn(new BigDecimal("1000"));
    }

    private static Revoke revoke(
            PenaltyRevoke.Type type, BigDecimal amount, String from, String to) {
        return new Revoke(type, amount, from, to, "R-1", DAY, "waived");
    }

    private void refused(ErrorCode code, Revoke r) {
        assertThatThrownBy(() -> service.revoke(context, 1L, r, null))
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    /** A PEN ORDER refusal of the revoke for exactly this cause (the causes share one code). */
    private void refusedBecause(String why, Revoke r) {
        assertThatThrownBy(() -> service.revoke(context, 1L, r, null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(PenaltyErrorCode.ORDER);
                            assertThat(ex.getMessageArgs()).containsExactly(why);
                        });
    }

    /**
     * R-SRC, SRS-14-1 (UI audit S1): an order applied from an HRMS penalty through I-21 stays
     * HRMS's to cancel. SRS-14-16 ("for all payrolls") and SRS-14-17 (an employee's stopped
     * increment; employee penalties only come from HRMS) keep Penalty Revoke open for it, so a
     * revoke of such an order reaches the request checks.
     */
    @Test
    void SRS_14_1_anOrderFromHrmsIsNeverCancelledInPayrollButIsWaivedThroughPenaltyRevoke() {
        when(order.getPenaltySource()).thenReturn("HRMS");
        refusedBecause("choose a full or a partial revoke", revoke(null, null, null, null));
        assertThatThrownBy(() -> service.cancel(context, 1L, "wrong order", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(PenaltyErrorCode.HRMS_ONLY));
        when(order.getPenaltySource()).thenReturn("hrms");
        refused(PenaltyErrorCode.ORDER, revoke(null, null, null, null));
        when(order.getPenaltySource()).thenReturn("ENT");
        refused(PenaltyErrorCode.ORDER, revoke(null, null, null, null));
        when(order.getPenaltySource()).thenReturn(null);
        refused(PenaltyErrorCode.ORDER_COPY, revoke(FULL, null, null, null));
    }

    @Test
    void SRS_14_16_aRevokeNamesItsTypeOrderReasonAndDay() {
        refused(PenaltyErrorCode.ORDER, null);
        refused(PenaltyErrorCode.ORDER, revoke(null, null, null, null));
        refused(PenaltyErrorCode.ORDER, new Revoke(FULL, null, null, null, " ", DAY, "w"));
        refused(
                PenaltyErrorCode.ORDER,
                new Revoke(FULL, null, null, null, "R", DAY, "x".repeat(1001)));
        refused(PenaltyErrorCode.ORDER, new Revoke(FULL, null, null, null, "R", null, "w"));
    }

    @Test
    void SRS_14_16_aFullRevokeHasNoAmountOrPeriodAndAPartialOneHasOneOfThem() {
        refused(PenaltyErrorCode.ORDER, revoke(FULL, BigDecimal.ONE, null, null));
        refused(PenaltyErrorCode.ORDER, revoke(FULL, null, "202604", null));
        refused(PenaltyErrorCode.ORDER, revoke(FULL, null, null, "202604"));
        refused(PenaltyErrorCode.ORDER_COPY, revoke(FULL, null, null, null));
        when(order.getTotalRecoveryAmount()).thenReturn(null);
        refused(PenaltyErrorCode.ORDER_COPY, revoke(FULL, null, null, null));
        refused(PenaltyErrorCode.ORDER, revoke(PARTIAL, null, null, null));
        refused(PenaltyErrorCode.ORDER, revoke(PARTIAL, BigDecimal.ONE, "202604", "202605"));
        refused(PenaltyErrorCode.ORDER, revoke(PARTIAL, BigDecimal.ONE, null, null));
    }

    @Test
    void SRS_14_16_aPartialAmountIsWithinTheOrderAndAPeriodRunsForward() {
        for (String bad : new String[] {"0", "-1", "1.005", "1000.01"}) {
            refused(PenaltyErrorCode.ORDER, revoke(PARTIAL, new BigDecimal(bad), null, null));
        }
        refused(PenaltyErrorCode.ORDER_COPY, revoke(PARTIAL, new BigDecimal("1000"), null, null));
        refused(PenaltyErrorCode.ORDER, revoke(PARTIAL, null, "2026-04", "202605"));
        refused(PenaltyErrorCode.ORDER, revoke(PARTIAL, null, "202604", null));
        refused(PenaltyErrorCode.ORDER, revoke(PARTIAL, null, "202605", "202604"));
        refused(PenaltyErrorCode.ORDER_COPY, revoke(PARTIAL, null, "202604", "202604"));
        when(order.status()).thenReturn(PenaltyOrder.Status.REVOKED);
        refused(PenaltyErrorCode.ORDER, revoke(PARTIAL, null, "202604", "202604"));
    }

    /**
     * US-GAP-234 (SRS-14-16, I-06 Penalty Revoke "Amount or Period"): an amount above the imposed
     * amount and a period sharing no month with the order's are refused; a period touching its
     * first or last month passes to the order copy; a Cancelled, fully Revoked or Closed
     * (COMPLETED) order is not revoked (Closed: CLR-65).
     */
    @Test
    void SRS_14_16_aWaiverIsWithinTheOrdersAmountAndPeriodAndOnlyOfAnOpenOrder() {
        when(order.getPeriodFrom()).thenReturn(LocalDate.of(2026, 5, 1));
        when(order.getPeriodTo()).thenReturn(LocalDate.of(2026, 7, 31));
        String outside = "the period is outside the order's period";
        // The amount boundary: the imposed amount itself passes to the order copy, a paisa more is
        // refused for its amount.
        refusedBecause(
                "the amount is above zero and within the order's amount",
                revoke(PARTIAL, new BigDecimal("1000.01"), null, null));
        refused(
                PenaltyErrorCode.ORDER_COPY,
                revoke(PARTIAL, new BigDecimal("1000.00"), null, null));
        refusedBecause(outside, revoke(PARTIAL, null, "202601", "202604"));
        refusedBecause(outside, revoke(PARTIAL, null, "202608", "202612"));
        refused(PenaltyErrorCode.ORDER_COPY, revoke(PARTIAL, null, "202601", "202605"));
        refused(PenaltyErrorCode.ORDER_COPY, revoke(PARTIAL, null, "202607", "202612"));
        refused(PenaltyErrorCode.ORDER_COPY, revoke(PARTIAL, null, "202606", "202606"));
        when(order.getPeriodTo()).thenReturn(null);
        refused(PenaltyErrorCode.ORDER_COPY, revoke(PARTIAL, null, "203001", "203012"));
        refusedBecause(outside, revoke(PARTIAL, null, "202601", "202604"));
        for (PenaltyOrder.Status closed :
                new PenaltyOrder.Status[] {
                    PenaltyOrder.Status.CANCELLED,
                    PenaltyOrder.Status.REVOKED,
                    PenaltyOrder.Status.COMPLETED
                }) {
            when(order.status()).thenReturn(closed);
            refusedBecause(
                    "the order is " + closed.name() + " and cannot change",
                    revoke(FULL, null, null, null));
        }
        for (PenaltyOrder.Status open :
                new PenaltyOrder.Status[] {
                    PenaltyOrder.Status.ACTIVE,
                    PenaltyOrder.Status.PARTLY_REVOKED,
                    PenaltyOrder.Status.PAUSED_FNF
                }) {
            when(order.status()).thenReturn(open);
            refused(PenaltyErrorCode.ORDER_COPY, revoke(FULL, null, null, null));
        }
    }

    private static PenaltyRecoveryLine line(
            String yymm, String recovered, PenaltyRecoveryLine.Status status) {
        PenaltyRecoveryLine l = mock(PenaltyRecoveryLine.class);
        when(l.getPayrollYymm()).thenReturn(yymm);
        when(l.getScheduledAmount()).thenReturn(new BigDecimal("1000"));
        when(l.getRecoveredAmount()).thenReturn(new BigDecimal(recovered));
        when(l.status()).thenReturn(status);
        return l;
    }

    private static ApprovalDecided cancelled() {
        return new ApprovalDecided(
                3L,
                "I-06",
                PenaltyChangeService.CANCEL,
                "1|c",
                ApprovalStatus.APPROVED,
                "imposed in error",
                null,
                "ddo@x",
                Instant.now());
    }

    @Test
    void SRS_21_10_aCancellationRefundsWhatWasRecoveredUnderTheRecoveryHead() {
        when(order.getId()).thenReturn(1L);
        when(order.getEmployeeId()).thenReturn(7L);
        when(order.getPenaltyRef()).thenReturn("PEN-1");
        when(order.getPenaltyNature()).thenReturn("RECOVERY");
        List<PenaltyRecoveryLine> schedule =
                List.of(
                        line("202608", "1000", PenaltyRecoveryLine.Status.RECOVERED),
                        line("202609", "0", PenaltyRecoveryLine.Status.PENDING));
        when(lines.findByOrderIdOrderByPayrollYymm(1L)).thenReturn(schedule);
        when(references.list(PenaltyChangeService.RECOVERY_HEADS))
                .thenReturn(
                        Optional.of(
                                new ReferenceData.ListView(
                                        "PENALTY_RECOVERY",
                                        "Recovery heads",
                                        "PENALTY",
                                        false,
                                        List.of(
                                                new ReferenceData.Value(
                                                        "RECOVERY",
                                                        "Recovery",
                                                        null,
                                                        1,
                                                        null,
                                                        null,
                                                        true),
                                                new ReferenceData.Value(
                                                        "RECOVERY",
                                                        "Recovery",
                                                        null,
                                                        2,
                                                        new BigDecimal("912"),
                                                        null,
                                                        true)))));
        service.on(cancelled());
        ArgumentCaptor<RetroEvents.Known> known = ArgumentCaptor.forClass(RetroEvents.Known.class);
        verify(retro).known(known.capture());
        assertThat(known.getValue().lines())
                .containsExactly(new RetroEvents.Line("202608", 912, new BigDecimal("1000")));
        assertThat(known.getValue().sourceKey()).isEqualTo("PEN-1/CANCEL");
        assertThat(known.getValue().eventCode()).isEqualTo("PENALTY");

        when(references.list(PenaltyChangeService.RECOVERY_HEADS)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.on(cancelled()))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(PenaltyErrorCode.NOT_CONFIGURED));
    }

    private static final PenaltyOrderService.Copy COPY =
            new PenaltyOrderService.Copy("ORDER", "R-1", DAY, "GM", "o.pdf", new byte[] {1});

    /** SRS-14-16 / SRS-G-30: the order copy has content; the reference and reason are printable. */
    @Test
    void SRS_14_16_aRevokeNeedsACopyWithContentAndPrintableText() {
        Revoke full = revoke(FULL, null, null, null);
        for (PenaltyOrderService.Copy empty :
                List.of(
                        new PenaltyOrderService.Copy("ORDER", "R", DAY, "GM", "o.pdf", null),
                        new PenaltyOrderService.Copy(
                                "ORDER", "R", DAY, "GM", "o.pdf", new byte[0]))) {
            assertThatThrownBy(() -> service.revoke(context, 1L, full, empty))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            ex ->
                                    assertThat(ex.getErrorCode())
                                            .isEqualTo(PenaltyErrorCode.ORDER_COPY));
        }
        refused(PenaltyErrorCode.ORDER, new Revoke(FULL, null, null, null, null, DAY, "w"));
        refused(PenaltyErrorCode.ORDER, new Revoke(FULL, null, null, null, "Réf", DAY, "w"));
        assertThatThrownBy(() -> service.cancel(context, 1L, null, COPY))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(PenaltyErrorCode.ORDER));
    }

    private ApprovalDecided onRevoke(ApprovalStatus outcome) {
        return new ApprovalDecided(
                4L,
                "I-06",
                PenaltyChangeService.REVOKE,
                "5",
                outcome,
                "no",
                null,
                "ddo@x",
                Instant.now());
    }

    /** R-MC: a revoke returned to the Maker is returned; one rejected is cancelled. */
    @Test
    void SRS_14_16_aRevokeNotApprovedIsReturnedOrCancelled() {
        PenaltyRevoke r = mock(PenaltyRevoke.class);
        when(revokes.findById(5L)).thenReturn(Optional.of(r));
        service.on(onRevoke(ApprovalStatus.RETURNED));
        verify(r).decided(PenaltyRevoke.Status.RETURNED);
        service.on(onRevoke(ApprovalStatus.REJECTED));
        verify(r).decided(PenaltyRevoke.Status.CANCELLED);
        verify(retro, never()).known(any());
    }

    /** R-MC: a cancellation not approved changes nothing. */
    @Test
    void SRS_14_20_aRejectedCancellationChangesNothing() {
        service.on(
                new ApprovalDecided(
                        3L,
                        "I-06",
                        PenaltyChangeService.CANCEL,
                        "1|c",
                        ApprovalStatus.REJECTED,
                        "no",
                        null,
                        "ddo@x",
                        Instant.now()));
        verify(order, never()).status(any());
        verify(retro, never()).known(any());
    }

    /**
     * SRS-21-10: the recovery head is the nature's own active value; a deferred month is still to
     * recover, so nothing of it is refunded.
     */
    @Test
    void SRS_21_10_theRecoveryHeadIsTheNaturesActiveValueAndADeferredMonthIsNotRefunded() {
        when(order.getId()).thenReturn(1L);
        when(order.getEmployeeId()).thenReturn(7L);
        when(order.getPenaltyRef()).thenReturn("PEN-1");
        when(order.getPenaltyNature()).thenReturn("RECOVERY");
        List<PenaltyRecoveryLine> schedule =
                List.of(
                        line("202608", "1000", PenaltyRecoveryLine.Status.RECOVERED),
                        line("202609", "0", PenaltyRecoveryLine.Status.DEFERRED));
        when(lines.findByOrderIdOrderByPayrollYymm(1L)).thenReturn(schedule);
        when(references.list(PenaltyChangeService.RECOVERY_HEADS))
                .thenReturn(
                        Optional.of(
                                new ReferenceData.ListView(
                                        "PENALTY_RECOVERY",
                                        "Recovery heads",
                                        "PENALTY",
                                        false,
                                        List.of(
                                                new ReferenceData.Value(
                                                        "FINE",
                                                        "Fine",
                                                        null,
                                                        1,
                                                        new BigDecimal("913"),
                                                        null,
                                                        true),
                                                new ReferenceData.Value(
                                                        "RECOVERY",
                                                        "Recovery",
                                                        null,
                                                        2,
                                                        new BigDecimal("900"),
                                                        null,
                                                        false),
                                                new ReferenceData.Value(
                                                        "RECOVERY",
                                                        "Recovery",
                                                        null,
                                                        3,
                                                        new BigDecimal("912"),
                                                        null,
                                                        true)))));
        service.on(cancelled());
        ArgumentCaptor<RetroEvents.Known> known = ArgumentCaptor.forClass(RetroEvents.Known.class);
        verify(retro).known(known.capture());
        assertThat(known.getValue().lines())
                .containsExactly(new RetroEvents.Line("202608", 912, new BigDecimal("1000")));
    }
}
