package in.gov.fci.payroll.leave.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M-20 Leave Encashment Parameters decided by the Checker (SRS-LV-14; plan 19 D19-10): a returned
 * or rejected version is never confirmed, so it is never used in a valuation; another entity's
 * decision is not this effect's.
 */
class LeaveParameterEffectTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final in.gov.fci.payroll.configuration.api.Circulars circulars =
            mock(in.gov.fci.payroll.configuration.api.Circulars.class);
    private final LeaveParameterEffect effect = new LeaveParameterEffect(jdbc, audit, circulars);

    private static ApprovalDecided decided(String entity, ApprovalStatus outcome) {
        return new ApprovalDecided(
                71L,
                "M-20",
                entity,
                "LV_DIVISOR",
                outcome,
                "Divisor to be confirmed by the circular",
                null,
                "checker.hq",
                Instant.parse("2026-10-28T05:00:00Z"),
                null);
    }

    // SC-19-0036: a returned version goes back with the remark and is never used.
    @Test
    void SRS_LV_14_aReturnedOrRejectedVersionIsNeverConfirmed() {
        effect.on(decided(LeaveParameterService.ENTITY, ApprovalStatus.RETURNED));
        effect.on(decided(LeaveParameterService.ENTITY, ApprovalStatus.REJECTED));
        effect.on(decided("leave_encashment", ApprovalStatus.APPROVED));
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void SRS_LV_14_anApprovalWithoutItsVersionChangesNothing() {
        org.mockito.Mockito.when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(java.util.List.of());
        effect.on(decided(LeaveParameterService.ENTITY, ApprovalStatus.APPROVED));
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never())
                .update(anyString(), any(Object[].class));
        verifyNoInteractions(audit);
    }

    @Test
    void SRS_LV_14_theCircularOfAnApprovedVersionIsLinkedAndNoneWithoutOne() {
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("LEAVE_PAY_PARAMETER_ID", 5L);
        row.put("PARAMETER_CODE", "LWP_DIVISOR");
        row.put("LEAVE_TYPE_CODE", null);
        row.put("EFFECTIVE_FROM", java.sql.Date.valueOf("2026-11-01"));
        row.put("NUMERIC_VALUE", java.math.BigDecimal.TEN);
        org.mockito.Mockito.when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(java.util.List.of(row));
        effect.on(
                new ApprovalDecided(
                        71L,
                        "M-20",
                        LeaveParameterService.ENTITY,
                        "LWP_DIVISOR|2026-11-01",
                        ApprovalStatus.APPROVED,
                        null,
                        "{\"circularId\":12}",
                        "checker.hq",
                        Instant.parse("2026-10-28T05:00:00Z"),
                        null));
        org.mockito.Mockito.verify(circulars)
                .link(
                        "checker.hq",
                        12L,
                        in.gov.fci.payroll.configuration.api.Circulars.LinkType.RULE_VERSION,
                        LeaveParameterService.ENTITY,
                        "5",
                        "M-20");
        org.mockito.Mockito.clearInvocations(circulars);
        effect.on(decided(LeaveParameterService.ENTITY, ApprovalStatus.APPROVED));
        effect.on(
                new ApprovalDecided(
                        72L,
                        "M-20",
                        LeaveParameterService.ENTITY,
                        "LWP_DIVISOR|2026-11-01",
                        ApprovalStatus.APPROVED,
                        null,
                        "{}",
                        "checker.hq",
                        Instant.parse("2026-10-28T05:00:00Z"),
                        null));
        verifyNoInteractions(circulars);
    }

    /** SRS-LV-14 (branch gap): a version with neither a number nor a text audits no new value. */
    @Test
    void SRS_LV_14_aVersionWithoutAValueAuditsNoNewValue() {
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("LEAVE_PAY_PARAMETER_ID", 6L);
        row.put("PARAMETER_CODE", "LWP_DIVISOR");
        row.put("LEAVE_TYPE_CODE", null);
        row.put("EFFECTIVE_FROM", java.sql.Date.valueOf("2026-11-01"));
        row.put("NUMERIC_VALUE", null);
        row.put("TEXT_VALUE", null);
        org.mockito.Mockito.when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(java.util.List.of(row));
        effect.on(decided(LeaveParameterService.ENTITY, ApprovalStatus.APPROVED));
        org.mockito.ArgumentCaptor<AuditService.Entry> entry =
                org.mockito.ArgumentCaptor.forClass(AuditService.Entry.class);
        org.mockito.Mockito.verify(audit)
                .record(org.mockito.ArgumentMatchers.eq("checker.hq"), entry.capture());
        org.assertj.core.api.Assertions.assertThat(entry.getValue().newValue()).isNull();
        org.assertj.core.api.Assertions.assertThat(entry.getValue().reason())
                .isEqualTo("Version approved");
    }
}
