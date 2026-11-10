package in.gov.fci.payroll.penalty.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.penalty.business.model.PenaltyRecoveryLine;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Penalty instalments when a staff cycle locks (SRS-14-11; plan 12 D12-8): a member of no office or
 * of another unit is left alone; a member separated before the month defers only the instalments
 * still pending; a computed member recovers only this month's pending instalment, and the order is
 * not completed while another month is still due.
 */
class PenaltyLockEffectTest {

    private static final CycleLocked LOCKED =
            new CycleLocked(9L, 40L, "202610", "ddo@x", Instant.parse("2026-10-29T06:00:00Z"));

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final PenaltyRepositories.Orders orders = mock(PenaltyRepositories.Orders.class);
    private final PenaltyRepositories.Lines lines = mock(PenaltyRepositories.Lines.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PenaltyLockEffect effect =
            new PenaltyLockEffect(orders, lines, org, mock(AuditService.class), db.jdbc);

    private void unitOf(long location, long unit) {
        OrgLocation u = mock(OrgLocation.class);
        when(u.getId()).thenReturn(unit);
        when(org.cycleUnitOf(location)).thenReturn(Optional.of(u));
    }

    private PenaltyOrder order(long id) {
        PenaltyOrder o = mock(PenaltyOrder.class);
        when(o.getId()).thenReturn(id);
        when(o.getPenaltyRef()).thenReturn("PEN-" + id);
        when(o.status()).thenReturn(PenaltyOrder.Status.ACTIVE);
        when(orders.findById(id)).thenReturn(Optional.of(o));
        return o;
    }

    private static PenaltyRecoveryLine line(String yymm, PenaltyRecoveryLine.Status status) {
        PenaltyRecoveryLine l = mock(PenaltyRecoveryLine.class);
        when(l.getPayrollYymm()).thenReturn(yymm);
        when(l.status()).thenReturn(status);
        when(l.getScheduledAmount()).thenReturn(BigDecimal.TEN);
        return l;
    }

    @Test
    void SRS_14_11_aMemberOfNoOfficeOrOfAnotherUnitIsLeftAlone() {
        unitOf(55L, 41L);
        db.rows(
                "FROM penalty_order o",
                row("order_id", 1L, "location_id", null, "separation_date", null, "computed", 1L),
                row("order_id", 2L, "location_id", 55L, "separation_date", null, "computed", 1L));
        effect.on(LOCKED);
        verifyNoInteractions(orders, lines);
    }

    @Test
    void SRS_14_11_aSeparatedMemberDefersOnlyThePendingInstalments() {
        unitOf(50L, 40L);
        order(3L);
        PenaltyRecoveryLine paid = line("202609", PenaltyRecoveryLine.Status.RECOVERED);
        PenaltyRecoveryLine due = line("202610", PenaltyRecoveryLine.Status.PENDING);
        when(lines.findByOrderIdOrderByPayrollYymm(3L)).thenReturn(List.of(paid, due));
        db.rows(
                "FROM penalty_order o",
                row(
                        "order_id", 3L,
                        "location_id", 50L,
                        "separation_date", Date.valueOf(LocalDate.of(2026, 9, 30)),
                        "computed", 0L));
        effect.on(LOCKED);
        verify(due).deferred(PenaltyLockEffect.FNF);
        verify(paid, never()).deferred(any());
    }

    @Test
    void SRS_14_11_aComputedMemberRecoversOnlyThisMonthsPendingInstalment() {
        unitOf(50L, 40L);
        PenaltyOrder o = order(4L);
        PenaltyRecoveryLine recoveredEarlier = line("202610", PenaltyRecoveryLine.Status.RECOVERED);
        PenaltyRecoveryLine thisMonth = line("202610", PenaltyRecoveryLine.Status.PENDING);
        PenaltyRecoveryLine nextMonth = line("202611", PenaltyRecoveryLine.Status.PENDING);
        when(lines.findByOrderIdOrderByPayrollYymm(4L))
                .thenReturn(List.of(recoveredEarlier, thisMonth, nextMonth));
        db.rows(
                "FROM penalty_order o",
                row(
                        "order_id", 4L,
                        "location_id", 50L,
                        "separation_date", Date.valueOf(LocalDate.of(2026, 10, 15)),
                        "computed", 1L));
        effect.on(LOCKED);
        verify(thisMonth).recovered(BigDecimal.TEN);
        verify(nextMonth, never()).recovered(any());
        verify(recoveredEarlier, never()).recovered(any());
        verify(o, never()).status(PenaltyOrder.Status.COMPLETED);
    }
}
