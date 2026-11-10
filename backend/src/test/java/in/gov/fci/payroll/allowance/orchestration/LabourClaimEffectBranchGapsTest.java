package in.gov.fci.payroll.allowance.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.allowance.business.model.ReimbursementClaim;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The Payroll Checker's decision on a labour claim (SRS-11-13, plan 10 D10-7): approved, it is
 * APPROVED; returned, it goes back to the Maker (SUBMITTED); rejected, it is REJECTED; a decision
 * on another entity changes no claim.
 */
class LabourClaimEffectBranchGapsTest {

    private final AllowanceRepositories.Claims claims = mock(AllowanceRepositories.Claims.class);
    private final AuditService audit = mock(AuditService.class);
    private final LabourClaimEffect effect = new LabourClaimEffect(claims, audit);

    private static ApprovalDecided decision(String entity, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                31L,
                LabourClaimService.SCREEN,
                entity,
                "5",
                outcome,
                reason,
                "{}",
                "checker@fci-demo.test",
                Instant.now());
    }

    private ReimbursementClaim claim() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        ReimbursementClaim c =
                ReimbursementClaim.labour(
                        7L,
                        "LABOUR_TA",
                        new ClaimRules.Period(today.minusDays(9), today.minusDays(2)),
                        new BigDecimal("400"));
        when(claims.findByApprovalId(31L)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void SRS_11_13_eachOutcomeMovesTheClaim() {
        ReimbursementClaim approved = claim();
        effect.on(decision(LabourClaimService.ENTITY, ApprovalStatus.APPROVED, null));
        assertThat(approved.getStatus()).isEqualTo("APPROVED");

        ReimbursementClaim returned = claim();
        effect.on(decision(LabourClaimService.ENTITY, ApprovalStatus.RETURNED, "Bill missing"));
        assertThat(returned.getStatus()).isEqualTo("SUBMITTED");

        ReimbursementClaim rejected = claim();
        effect.on(decision(LabourClaimService.ENTITY, ApprovalStatus.REJECTED, "Not entitled"));
        assertThat(rejected.getStatus()).isEqualTo("REJECTED");

        verify(claims, times(3)).save(any());
        verify(audit, times(3)).record(eq("checker@fci-demo.test"), any());
    }

    @Test
    void SRS_11_13_aDecisionOnAnotherEntityChangesNoClaim() {
        effect.on(decision("cafeteria_election", ApprovalStatus.APPROVED, null));
        verify(claims, never()).findByApprovalId(anyLong());
    }
}
