package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.penalty.business.model.PenaltyRevoke;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an approved Penalty Revoke or cancellation does to the Basic (SRS-14-8, SRS-14-16,
 * SRS-14-17, SRS-21-10; BR-EMPR-021). Before this, a revoked increment stop wrote only a pending
 * marker on X-05 and the increment never came back.
 */
class PenaltyRevokePayEffectsTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private final PenaltyRepositories.Orders orders = mock(PenaltyRepositories.Orders.class);
    private final PenaltyRepositories.Lines lines = mock(PenaltyRepositories.Lines.class);
    private final PenaltyRepositories.Revokes revokes = mock(PenaltyRepositories.Revokes.class);
    private final ExceptionWorklist worklist = mock(ExceptionWorklist.class);
    private final PenaltyPayEffects pay = mock(PenaltyPayEffects.class);
    private final PenaltyOrder order = mock(PenaltyOrder.class);
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
                    mock(RetroEvents.class),
                    pay,
                    mock(ReferenceData.class),
                    JsonMapper.builder().build(),
                    Clock.systemUTC());

    PenaltyRevokePayEffectsTest() {
        when(orders.findById(1L)).thenReturn(Optional.of(order));
        when(order.getId()).thenReturn(1L);
        when(order.getEmployeeId()).thenReturn(9L);
        when(order.getPenaltyRef()).thenReturn("PEN-2026-00033");
        when(order.getOrderReference()).thenReturn("VIG/44");
        when(order.getPeriodFrom()).thenReturn(FROM);
        when(order.getPeriodTo()).thenReturn(TO);
        when(order.status()).thenReturn(PenaltyOrder.Status.REVOKED);
        when(lines.findByOrderIdOrderByPayrollYymm(1L)).thenReturn(List.of());
    }

    private PenaltyRevoke revoke(PenaltyRevoke.Type type, LocalDate from, LocalDate to) {
        PenaltyRevoke r = mock(PenaltyRevoke.class);
        when(r.getOrderId()).thenReturn(1L);
        when(r.type()).thenReturn(type);
        when(r.getRevokeOrderRef()).thenReturn("REV/1");
        when(r.getWaivedPeriodFrom()).thenReturn(from);
        when(r.getWaivedPeriodTo()).thenReturn(to);
        when(revokes.findById(5L)).thenReturn(Optional.of(r));
        return r;
    }

    private static ApprovalDecided decided(String entity, String key) {
        return new ApprovalDecided(
                44L,
                "I-06",
                entity,
                key,
                ApprovalStatus.APPROVED,
                "ok",
                null,
                "ddo@x",
                Instant.now());
    }

    private PenaltyPayEffects.Waived waivedOf() {
        ArgumentCaptor<PenaltyPayEffects.Waived> w =
                ArgumentCaptor.forClass(PenaltyPayEffects.Waived.class);
        verify(pay).restoreIncrement(w.capture());
        return w.getValue();
    }

    @Test
    void SRS_14_17_aFullRevokeRestoresTheStoppedIncrementInTheDecisionsTransaction() {
        when(order.getPenaltyNature()).thenReturn("INCREMENT_STOP");
        PenaltyRevoke r = revoke(PenaltyRevoke.Type.FULL, null, null);
        when(pay.restoreIncrement(any())).thenReturn(new PenaltyPayEffects.Restored(77L, 1, null));

        service.on(decided(PenaltyChangeService.REVOKE, "5"));

        // The stop's new status must reach the database before the run re-reads it through SQL.
        InOrder sequence = inOrder(orders, pay);
        sequence.verify(orders).flush();
        sequence.verify(pay).restoreIncrement(any());
        assertThat(waivedOf())
                .isEqualTo(
                        new PenaltyPayEffects.Waived(
                                9L, "PEN-2026-00033", "REV/1", FROM, TO, 44L, "ddo@x"));
        verify(r).restored(77L);
        verify(worklist, never()).raise(any(), any());
    }

    @Test
    void SRS_14_17_aPartialRevokeRestoresOnlyWithinTheWaivedPeriod() {
        when(order.getPenaltyNature()).thenReturn("INCREMENT_STOP");
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        PenaltyRevoke r = revoke(PenaltyRevoke.Type.PARTIAL, from, to);
        when(r.getWaivedAmount()).thenReturn(null);
        when(pay.restoreIncrement(any())).thenReturn(new PenaltyPayEffects.Restored(null, 0, null));

        service.on(decided(PenaltyChangeService.REVOKE, "5"));

        assertThat(waivedOf().from()).isEqualTo(from);
        assertThat(waivedOf().to()).isEqualTo(to);
        verify(r, never()).restored(any());
    }

    @Test
    void SRS_14_17_whatCannotBeRestoredByItselfGoesToX05WithTheReason() {
        when(order.getPenaltyNature()).thenReturn("INCREMENT_STOP");
        revoke(PenaltyRevoke.Type.FULL, null, null);
        when(pay.restoreIncrement(any()))
                .thenReturn(new PenaltyPayEffects.Restored(null, 0, "The increment due: NO_SCALE"));

        service.on(decided(PenaltyChangeService.REVOKE, "5"));

        ArgumentCaptor<ExceptionWorklist.NewEntry> entry =
                ArgumentCaptor.forClass(ExceptionWorklist.NewEntry.class);
        verify(worklist).raise(eq("ddo@x"), entry.capture());
        assertThat(entry.getValue().exceptionCode())
                .isEqualTo(PenaltyChangeService.INCREMENT_PENDING);
        assertThat(entry.getValue().detail())
                .isEqualTo("PEN-2026-00033: The increment due: NO_SCALE");
    }

    @Test
    void SRS_21_10_aCancellationOfAnIncrementStopRestoresTheIncrementToo() {
        when(order.getPenaltyNature()).thenReturn("INCREMENT_STOP");
        when(pay.restoreIncrement(any())).thenReturn(new PenaltyPayEffects.Restored(70L, 1, null));

        service.on(decided(PenaltyChangeService.CANCEL, "1|c"));

        verify(orders).flush();
        assertThat(waivedOf().orderReference()).isEqualTo("cancelled");
    }

    @Test
    void SRS_14_17_aBasicReductionEndsOnItsOwnFullRevoke() {
        when(order.getPenaltyNature()).thenReturn("BASIC_REDUCTION");
        revoke(PenaltyRevoke.Type.FULL, null, null);
        when(pay.restoreBasic(any(), eq("VIG/44"))).thenReturn(null);

        service.on(decided(PenaltyChangeService.REVOKE, "5"));

        verify(pay).restoreBasic(any(), eq("VIG/44"));
        verify(pay, never()).restoreIncrement(any());
        verify(worklist, never()).raise(any(), any());
    }

    @Test
    void SRS_14_17_laterBasicEventsAfterARestoredReductionGoToX05() {
        when(order.getPenaltyNature()).thenReturn("BASIC_REDUCTION");
        revoke(PenaltyRevoke.Type.FULL, null, null);
        when(pay.restoreBasic(any(), eq("VIG/44"))).thenReturn("refix them on I-05.");

        service.on(decided(PenaltyChangeService.REVOKE, "5"));

        verify(worklist).raise(eq("ddo@x"), any());
    }

    @Test
    void SRS_14_17_aPartialWaiverOfABasicReductionIsNotGuessed() {
        when(order.getPenaltyNature()).thenReturn("BASIC_REDUCTION");
        revoke(PenaltyRevoke.Type.PARTIAL, FROM, TO);

        service.on(decided(PenaltyChangeService.REVOKE, "5"));

        verify(pay, never()).restoreBasic(any(), any());
        verify(worklist).raise(eq("ddo@x"), any());
    }

    @Test
    void SRS_14_16_aRecoveryRevokeTouchesNoBasic() {
        when(order.getPenaltyNature()).thenReturn("RECOVERY");
        when(order.getTotalRecoveryAmount()).thenReturn(new BigDecimal("1000"));
        revoke(PenaltyRevoke.Type.FULL, null, null);

        service.on(decided(PenaltyChangeService.REVOKE, "5"));

        verify(pay, never()).restoreIncrement(any());
        verify(pay, never()).restoreBasic(any(), any());
        verify(orders, never()).flush();
    }
}
