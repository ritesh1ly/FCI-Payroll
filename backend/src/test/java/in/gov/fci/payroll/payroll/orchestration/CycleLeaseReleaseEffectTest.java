package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-15-28 / BR-CYC-015, SRS-G-21 (plan 05 D-6): the approved force-release releases the lease the
 * request named, with the requester's reason, the decider, the approval and the OTP, and audits the
 * outcome; any other decision, or another entity's, does nothing; a route that asked no OTP at the
 * deciding level fails the decision rather than writing a log row the DDL refuses.
 */
class CycleLeaseReleaseEffectTest {

    private static final Instant ACQUIRED = Instant.parse("2026-10-25T06:30:00Z");

    private final CycleLeases leases = mock(CycleLeases.class);
    private final AuditService audit = mock(AuditService.class);
    private final CycleLeaseReleaseEffect effect =
            new CycleLeaseReleaseEffect(leases, audit, JsonMapper.builder().build());

    private static ApprovalDecided decided(
            ApprovalStatus outcome, String entity, String reason, String checker, Long otp) {
        return new ApprovalDecided(
                90L,
                "C-02",
                entity,
                "41",
                outcome,
                "",
                "{\"cycleInstanceId\":41,\"action\":\"COMPUTE\",\"heldBy\":\"run.owner\","
                        + "\"acquiredAt\":\""
                        + ACQUIRED
                        + "\",\"reason\":\""
                        + reason
                        + "\"}",
                checker,
                ACQUIRED.plusSeconds(600),
                otp);
    }

    private AuditService.Entry audited(String by) {
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq(by), entry.capture());
        return entry.getValue();
    }

    @Test
    void SRS_15_28_theApprovedReleaseFreesTheNamedLeaseWithTheRequestersReasonAndOtp() {
        when(leases.forceRelease(41L, "run.owner", ACQUIRED, "checker.b", "Run stuck", 90L, 5L))
                .thenReturn(true);
        effect.on(
                decided(
                        ApprovalStatus.APPROVED,
                        CycleService.LEASE_ENTITY,
                        "Run stuck",
                        "checker.b",
                        5L));
        AuditService.Entry entry = audited("checker.b");
        assertThat(entry.entityName()).isEqualTo("cycle_lease");
        assertThat(entry.entityKey()).isEqualTo("41");
        assertThat(entry.oldValue()).isEqualTo("COMPUTE by run.owner");
        assertThat(entry.newValue()).isEqualTo("FORCE_RELEASED");
        assertThat(entry.reason()).isEqualTo("Run stuck");
        assertThat(entry.approvalId()).isEqualTo(90L);
        assertThat(entry.moduleCode()).isEqualTo("C-02");
    }

    @Test
    void SRS_15_28_aLeaseGoneOrRetakenIsLeftAloneAndTheOutcomeAudited() {
        String longLogin = "c".repeat(60);
        effect.on(decided(ApprovalStatus.APPROVED, CycleService.LEASE_ENTITY, "", longLogin, 5L));
        // The log column holds 50 characters; an empty reason gets the default one.
        verify(leases)
                .forceRelease(41L, "run.owner", ACQUIRED, "c".repeat(50), "Force release", 90L, 5L);
        assertThat(audited(longLogin).newValue()).isEqualTo("ALREADY_RELEASED");
    }

    @Test
    void BR_CYC_015_otherDecisionsAndOtherEntitiesDoNothing() {
        effect.on(
                decided(
                        ApprovalStatus.REJECTED,
                        CycleService.LEASE_ENTITY,
                        "Run stuck",
                        "checker.b",
                        5L));
        effect.on(decided(ApprovalStatus.APPROVED, "payroll_cycle_instance", "x", "checker.b", 5L));
        verifyNoInteractions(leases, audit);
    }

    @Test
    void SRS_G_21_aRouteWithoutTheDecidersOtpFailsTheDecision() {
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decided(
                                                ApprovalStatus.APPROVED,
                                                CycleService.LEASE_ENTITY,
                                                "Run stuck",
                                                "checker.b",
                                                null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("C02_LEASE_RELEASE");
        verifyNoInteractions(audit);
        verify(leases, org.mockito.Mockito.never())
                .forceRelease(
                        anyLong(),
                        anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyLong());
    }
}
