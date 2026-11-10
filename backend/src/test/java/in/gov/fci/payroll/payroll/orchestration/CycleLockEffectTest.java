package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SRS-15-11, BR-CYC-005: the approved lock of a cycle that is no longer VERIFIED is refused (the
 * decision rolls back); a rejection or another entity changes nothing.
 */
class CycleLockEffectTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final CycleGateService gates = mock(CycleGateService.class);
    private final CycleLeases leases = mock(CycleLeases.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CycleLockEffect effect = new CycleLockEffect(jdbc, audit, gates, leases, events);

    private static ApprovalDecided decided(String entity, ApprovalStatus outcome) {
        return new ApprovalDecided(
                80L,
                "C-02",
                entity,
                "51",
                outcome,
                "",
                "{}",
                "ddo.chennai",
                Instant.parse("2026-10-26T06:00:00Z"));
    }

    @Test
    void BR_CYC_005_aCycleNoLongerVerifiedIsNotLocked() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> effect.on(decided(CycleService.ENTITY, ApprovalStatus.APPROVED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer VERIFIED");
    }

    @Test
    void SRS_15_11_aRejectionOrAnotherEntityChangesNothing() {
        effect.on(decided(CycleService.ENTITY, ApprovalStatus.REJECTED));
        effect.on(decided("mdp_transaction", ApprovalStatus.APPROVED));
        verifyNoInteractions(jdbc, audit, gates, leases, events);
    }

    @Test
    void SRS_15_11_aLockedCycleIsAnnouncedToTheModulesOfTheMonth() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
        when(jdbc.queryForMap(anyString(), any()))
                .thenReturn(Map.of("ORG_UNIT_ID", 300L, "PAYROLL_YYMM", "202610"));
        effect.on(decided(CycleService.ENTITY, ApprovalStatus.APPROVED));
        verify(events)
                .publishEvent(
                        new CycleLocked(
                                51L,
                                300L,
                                "202610",
                                "ddo.chennai",
                                Instant.parse("2026-10-26T06:00:00Z")));
    }

    /**
     * BR-AUDIT-005 and Oracle review F6: the lock binds the partition keys of the instance's
     * results (the month's first day and the instance's unit), so it touches one partition and one
     * hash subpartition instead of scanning every month of payroll_result.
     */
    @Test
    void BR_AUDIT_005_theLockTouchesOnlyTheMonthAndUnitPartitionOfTheResults() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
        when(jdbc.queryForMap(anyString(), any()))
                .thenReturn(Map.of("ORG_UNIT_ID", 300L, "PAYROLL_YYMM", "202610"));
        effect.on(decided(CycleService.ENTITY, ApprovalStatus.APPROVED));
        java.sql.Date october = java.sql.Date.valueOf("2026-10-01");
        verify(jdbc)
                .update(
                        org.mockito.ArgumentMatchers.startsWith(
                                "UPDATE payroll_result SET is_locked = 'Y' WHERE period_date = ?"
                                        + " AND org_unit_id = ? AND cycle_instance_id = ?"),
                        org.mockito.ArgumentMatchers.eq(october),
                        org.mockito.ArgumentMatchers.eq(300L),
                        org.mockito.ArgumentMatchers.eq(51L));
        verify(jdbc)
                .update(
                        org.mockito.ArgumentMatchers.startsWith(
                                "UPDATE payroll_result_component SET is_locked = 'Y' WHERE"
                                        + " cycle_instance_id = ? AND period_date = ? AND"
                                        + " org_unit_id = ?"),
                        org.mockito.ArgumentMatchers.eq(51L),
                        org.mockito.ArgumentMatchers.eq(october),
                        org.mockito.ArgumentMatchers.eq(300L),
                        org.mockito.ArgumentMatchers.eq(october),
                        org.mockito.ArgumentMatchers.eq(300L),
                        org.mockito.ArgumentMatchers.eq(51L));
    }
}
