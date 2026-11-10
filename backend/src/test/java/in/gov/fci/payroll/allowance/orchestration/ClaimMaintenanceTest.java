package in.gov.fci.payroll.allowance.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.allowance.business.AllowanceErrorCode;
import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.allowance.business.model.ReimbursementClaim;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-03 maintenance of an approved medical claim (plan 10 D10-9, D10-10; SRS-11-19, SRS-11-21): only
 * an employee medical claim that is approved or paid, an IOGA's amount and date, and the Checker's
 * decision on another item left alone.
 */
class ClaimMaintenanceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final ClaimRules.Period PERIOD =
            new ClaimRules.Period(TODAY.minusDays(30), TODAY.minusDays(20));
    private static final ClaimRules.Adjudication FULL =
            new ClaimRules.Adjudication(BigDecimal.TEN, BigDecimal.TEN, false);

    private final AllowanceRepositories.Claims claims = mock(AllowanceRepositories.Claims.class);
    private final ClaimCatalogue catalogue = mock(ClaimCatalogue.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final AccessContext context = mock(AccessContext.class);
    private final ClaimMaintenance service =
            new ClaimMaintenance(
                    claims,
                    mock(ClaimQueryService.class),
                    catalogue,
                    approvals,
                    mock(Authorization.class),
                    mock(AuditService.class),
                    JsonMapper.builder().build(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    ClaimMaintenanceTest() {
        when(catalogue.member(9L))
                .thenReturn(
                        new ClaimCatalogue.MemberFacts(
                                9L, "E9", "A", "STAFF", "IN_SERVICE", null, 5L));
    }

    private static ReimbursementClaim hrms(String type) {
        return ReimbursementClaim.fromHrms(
                9L, type, PERIOD, BigDecimal.TEN, 1L, FULL, "HR-1", 40L, null, null, 81L);
    }

    private void claim(long id, ReimbursementClaim c) {
        when(claims.findById(id)).thenReturn(Optional.of(c));
    }

    private static void refused(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    void SRS_11_19_onlyAnApprovedOrPaidEmployeeMedicalClaimIsMaintained() {
        when(claims.findById(1L)).thenReturn(Optional.empty());
        refused(CommonErrorCode.NOT_FOUND, () -> service.confirmTaxable(context, 1L, true));
        ReimbursementClaim labour =
                ReimbursementClaim.labour(9L, "MEDICAL_LABOUR", PERIOD, BigDecimal.TEN);
        labour.approved();
        claim(2L, labour);
        refused(AllowanceErrorCode.STATE, () -> service.confirmTaxable(context, 2L, true));
        claim(3L, hrms("NEWSPAPER"));
        refused(AllowanceErrorCode.STATE, () -> service.confirmTaxable(context, 3L, true));
        ReimbursementClaim rejected = hrms("MEDICAL");
        rejected.rejected();
        claim(4L, rejected);
        refused(AllowanceErrorCode.STATE, () -> service.confirmTaxable(context, 4L, true));
    }

    @Test
    void SRS_11_19_theTaxableFlagIsConfirmedOverAnEarlierAnswer() {
        ReimbursementClaim paid = hrms("MEDICAL");
        paid.paid();
        claim(5L, paid);
        service.confirmTaxable(context, 5L, true);
        assertThat(paid.getTaxable()).isTrue();
        assertThat(paid.getTaxableConfirmedAt()).isEqualTo(NOW);
        service.confirmTaxable(context, 5L, false);
        assertThat(paid.getTaxable()).isFalse();
        service.confirmTaxable(context, 5L, true);
        verify(claims, org.mockito.Mockito.times(3)).flush();
    }

    @Test
    void SRS_11_21_anIogaHasAPositiveAmountInPaiseAndADayNotInTheFuture() {
        claim(6L, hrms("MEDICAL"));
        for (BigDecimal bad :
                new BigDecimal[] {
                    null, BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("1.001")
                }) {
            refused(
                    AllowanceErrorCode.CLAIM,
                    () -> service.recordIoga(context, 6L, "I-1", TODAY, bad, "DO-X", null));
        }
        refused(
                AllowanceErrorCode.CLAIM,
                () -> service.recordIoga(context, 6L, "I-1", null, BigDecimal.TEN, "DO-X", null));
        refused(
                AllowanceErrorCode.CLAIM,
                () ->
                        service.recordIoga(
                                context,
                                6L,
                                "I-1",
                                TODAY.plusDays(1),
                                BigDecimal.TEN,
                                "DO-X",
                                null));
        service.recordIoga(context, 6L, "I-1", TODAY, new BigDecimal("10.50"), "DO-X", " ");
        service.recordIoga(context, 6L, "I-1", TODAY, BigDecimal.TEN, "DO-X", " from the DO ");
        verify(approvals, org.mockito.Mockito.times(2)).submit(any(), any(), any());
    }

    @Test
    void SRS_11_21_onlyAnApprovedIogaIsRecorded() {
        service.on(
                new ApprovalDecided(
                        1L, "I-03", "other", "6", ApprovalStatus.APPROVED, "r", "{}", "ddo", NOW));
        service.on(
                new ApprovalDecided(
                        1L,
                        "I-03",
                        ClaimMaintenance.IOGA,
                        "6",
                        ApprovalStatus.REJECTED,
                        "r",
                        "{}",
                        "ddo",
                        NOW));
        verify(claims, never()).findById(any());
        ReimbursementClaim c = hrms("MEDICAL");
        claim(6L, c);
        service.on(
                new ApprovalDecided(
                        2L,
                        "I-03",
                        ClaimMaintenance.IOGA,
                        "6",
                        ApprovalStatus.APPROVED,
                        "r",
                        "{\"claimId\":6,\"number\":\"I-1\",\"date\":\"2026-10-20\",\"amount\":\"10.50\","
                            + "\"originatingOffice\":\"DO-X\"}",
                        "ddo",
                        NOW));
        assertThat(c.getIogaNumber()).isEqualTo("I-1");
        assertThat(c.getIogaAmount()).isEqualByComparingTo("10.50");
        verify(claims).save(c);
    }
}
