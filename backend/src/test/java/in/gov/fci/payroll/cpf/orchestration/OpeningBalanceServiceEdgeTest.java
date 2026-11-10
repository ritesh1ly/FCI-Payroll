package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.LedgerBalances;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader;
import in.gov.fci.payroll.cpf.business.model.OpeningBalanceCorrection;
import in.gov.fci.payroll.cpf.business.model.OpeningBalanceCorrection.Status;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.platform.approval.ApprovalAdvanced;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-22 opening-balance corrections beyond the happy path (SRS-6-43, SRS-6-44): a request that is
 * missing, a member out of scope, a reason not in the list, a correction without its evidence, and
 * the decisions: another module's or a decision for no correction changes nothing; a return or a
 * rejection closes with its reason; an approval is refused when the member was finally settled
 * meanwhile.
 */
class OpeningBalanceServiceEdgeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");
    private static final LocalDate AS_AT = LocalDate.of(2026, 4, 1);

    private final CpfRepositories.Corrections corrections = mock(CpfRepositories.Corrections.class);
    private final CpfRepositories.Headers headers = mock(CpfRepositories.Headers.class);
    private final SanctionRegister sanctions = mock(SanctionRegister.class);
    private final EmployeeDirectory directory = mock(EmployeeDirectory.class);
    private final ReferenceData references = mock(ReferenceData.class);
    private final Authorization policy = mock(Authorization.class);
    private final CpfLedgerService ledger = mock(CpfLedgerService.class);
    private final ExceptionWorklist exceptions = mock(ExceptionWorklist.class);

    private final OpeningBalanceService service =
            new OpeningBalanceService(
                    corrections,
                    headers,
                    ledger,
                    mock(CpfMembers.class),
                    sanctions,
                    mock(CpfSspSync.class),
                    exceptions,
                    directory,
                    references,
                    mock(DocumentStore.class),
                    mock(MakerChecker.class),
                    policy,
                    mock(AuditService.class),
                    JsonMapper.builder().build(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final AccessContext manager =
            new AccessContext(
                    3L,
                    "cpf.manager.north",
                    "Manager",
                    "s",
                    "P",
                    30L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(30L));

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static OpeningBalanceService.Request request(String reason) {
        return new OpeningBalanceService.Request(
                "E100", AS_AT, new BigDecimal("1100"), null, null, reason, AS_AT, "Clerical error");
    }

    private void memberInScope() {
        when(directory.memberByEis("E100"))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(
                                        9L, "E100", "Asha", 30L, "IN_SERVICE")));
        when(policy.canAt(manager, OpeningBalanceService.SCREEN, "CREATE", 30L)).thenReturn(true);
    }

    @Test
    void SRS_6_43_aCorrectionNeedsARequestAMemberInScopeAReasonFromTheListAndItsEvidence() {
        refused(() -> service.raise(manager, null, null, null), CommonErrorCode.VALIDATION_FAILED);
        when(directory.memberByEis("E100"))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(
                                        9L, "E100", "Asha", 40L, "IN_SERVICE")));
        refused(
                () -> service.raise(manager, request("CLERICAL_ERROR"), null, null),
                CommonErrorCode.NOT_FOUND);

        memberInScope();
        refused(
                () -> service.raise(manager, request("NOT_A_REASON"), null, null),
                CpfErrorCode.REASON_CODE);
        when(references.contains("OB_CORRECTION_REASON", "CLERICAL_ERROR")).thenReturn(true);
        // The member's 2026-27 ledger opened at 1,000 employee share.
        EmployeePfHeader ledger = mock(EmployeePfHeader.class);
        when(ledger.opening())
                .thenReturn(
                        new LedgerBalances(
                                new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO));
        when(headers.idsOf(eq(9L), eq("2026-27"), any())).thenReturn(List.of(5L));
        when(headers.findById(5L)).thenReturn(Optional.of(ledger));
        OpeningBalanceService.Evidence empty =
                new OpeningBalanceService.Evidence(
                        "OTHER", null, AS_AT, "CPF Division", "a.pdf", new byte[0]);
        for (OpeningBalanceService.Evidence missing :
                new OpeningBalanceService.Evidence[] {
                    null,
                    new OpeningBalanceService.Evidence(
                            "OTHER", null, AS_AT, "CPF Division", "a.pdf", null),
                    empty
                }) {
            refused(
                    () -> service.raise(manager, request("CLERICAL_ERROR"), missing, null),
                    CpfErrorCode.EVIDENCE_REQUIRED);
        }
    }

    @Test
    void SRS_6_44_aMemberFinallySettledOnTheLedgerIsNotCorrected() {
        memberInScope();
        when(references.contains("OB_CORRECTION_REASON", "CLERICAL_ERROR")).thenReturn(true);
        EmployeePfHeader settled = mock(EmployeePfHeader.class);
        when(settled.getMemberStatus())
                .thenReturn(EmployeePfHeader.MemberStatus.FINALLY_SETTLED.name());
        when(headers.findByEmployeeIdAndFundTypeOrderByFinYearDescPfHeaderIdDesc(eq(9L), any()))
                .thenReturn(List.of(settled));
        refused(
                () -> service.raise(manager, request("CLERICAL_ERROR"), null, null),
                CpfErrorCode.FINALLY_SETTLED);
    }

    @Test
    void
            SRS_6_44_aDecisionForAnotherModuleOrNoCorrectionChangesNothingAndARefusalClosesWithItsReason() {
        service.on(
                new ApprovalAdvanced(
                        90L, "I-04", OpeningBalanceService.ENTITY, "OB-1", 2, "agm", 7L, NOW));
        service.on(new ApprovalAdvanced(90L, "I-22", "pf_sanction", "OB-1", 2, "agm", 7L, NOW));
        service.on(decided("I-04", ApprovalStatus.APPROVED, "ok"));
        verify(corrections, never()).findByApprovalIdOrderByCorrectionId(any());

        when(corrections.findByApprovalIdOrderByCorrectionId(90L)).thenReturn(List.of());
        service.on(decided("I-22", ApprovalStatus.APPROVED, "ok"));

        OpeningBalanceCorrection row = mock(OpeningBalanceCorrection.class);
        when(row.getEmployeeId()).thenReturn(9L);
        when(corrections.findByApprovalIdOrderByCorrectionId(90L)).thenReturn(List.of(row));
        service.on(decided("I-22", ApprovalStatus.RETURNED, "Wrong evidence"));
        service.on(decided("I-22", ApprovalStatus.REJECTED, "Not needed"));
        verify(row).closed(Status.RETURNED, "Wrong evidence");
        verify(row).closed(Status.REJECTED, "Not needed");

        // Settled between the request and the approval: the approval applies nothing.
        when(sanctions.finallySettled(9L)).thenReturn(true);
        refused(
                () -> service.on(decided("I-22", ApprovalStatus.APPROVED, "ok")),
                CpfErrorCode.FINALLY_SETTLED);
    }

    private static ApprovalDecided decided(String module, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                90L,
                module,
                OpeningBalanceService.ENTITY,
                "OB-1",
                outcome,
                reason,
                "{}",
                "hq.cpf",
                NOW,
                7L);
    }

    /**
     * SRS-6-43: the register of a user without offices is empty; a request needs its as-at and
     * interest-effective dates; a year with a correction still with the CPF Division takes no
     * second one.
     */
    @Test
    void SRS_6_43_datesAreRequiredAndAYearUnderReviewTakesNoSecondCorrection() {
        AccessContext nowhere =
                new AccessContext(
                        3L, "x", "X", "s", "P", 30L, null, List.of(), Map.of(), Set.of(), Set.of(),
                        Set.of());
        assertThat(service.register(nowhere)).isEmpty();
        memberInScope();
        refused(
                () ->
                        service.raise(
                                manager,
                                new OpeningBalanceService.Request(
                                        "E100",
                                        null,
                                        BigDecimal.ONE,
                                        null,
                                        null,
                                        "CLERICAL_ERROR",
                                        AS_AT,
                                        "r"),
                                null,
                                null),
                in.gov.fci.payroll.platform.error.CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.raise(
                                manager,
                                new OpeningBalanceService.Request(
                                        "E100",
                                        AS_AT,
                                        BigDecimal.ONE,
                                        null,
                                        null,
                                        "CLERICAL_ERROR",
                                        null,
                                        "r"),
                                null,
                                null),
                in.gov.fci.payroll.platform.error.CommonErrorCode.VALIDATION_FAILED);
        when(references.contains("OB_CORRECTION_REASON", "CLERICAL_ERROR")).thenReturn(true);
        OpeningBalanceCorrection review = mock(OpeningBalanceCorrection.class);
        when(review.getCorrectionStatus()).thenReturn(Status.PENDING_HQ_CPF.name());
        when(corrections.findByEmployeeIdAndFinYearOrderByCorrectionId(eq(9L), any()))
                .thenReturn(List.of(review));
        refused(
                () -> service.raise(manager, request("CLERICAL_ERROR"), null, null),
                CpfErrorCode.CORRECTION_PENDING);
    }

    /**
     * SRS-6-33 / Q-072: an approved correction of a member with interest already credited since the
     * effective date is applied with its interest effect left open, and raised on X-05.
     */
    @Test
    void SRS_6_33_interestAlreadyCreditedIsRaisedOnX05AndLeftOpen() {
        OpeningBalanceCorrection row = mock(OpeningBalanceCorrection.class);
        when(row.getEmployeeId()).thenReturn(9L);
        when(row.getBalanceComponent()).thenReturn("EMPLOYEE");
        when(row.getOldAmount()).thenReturn(new BigDecimal("1000"));
        when(row.getNewAmount()).thenReturn(new BigDecimal("1100"));
        when(row.getCorrectionRef()).thenReturn("OB-1");
        when(row.getInterestEffectiveDate()).thenReturn(AS_AT);
        when(corrections.findByApprovalIdOrderByCorrectionId(90L)).thenReturn(List.of(row));
        when(ledger.interestCreditedFrom(9L, AS_AT)).thenReturn(new BigDecimal("12.50"));
        service.on(decided("I-22", ApprovalStatus.APPROVED, "ok"));
        verify(exceptions).raise(eq("hq.cpf"), any());
        verify(row).applied(eq(null), any());
    }

    /**
     * SRS-6-43: the member is found by exactly one of EIS, CPF code or UAN; a blank one is not
     * given, so a blank EIS beside a CPF code looks the member up by the CPF code.
     */
    @Test
    void SRS_6_43_aBlankIdentifierIsNotGivenAndTheOtherOneFindsTheMember() {
        refused(
                () -> service.current(manager, "  ", " ", null, AS_AT),
                CommonErrorCode.VALIDATION_FAILED);
        when(directory.memberByCpfCodeOrUan("CPF/DL/7101", null)).thenReturn(Optional.empty());
        refused(
                () -> service.current(manager, "  ", "CPF/DL/7101", null, AS_AT),
                CommonErrorCode.NOT_FOUND);
        verify(directory).memberByCpfCodeOrUan("CPF/DL/7101", null);
        verify(directory, never()).memberByEis(any());
    }

    /**
     * SRS-6-43 (I-22 "Member (CPF Code / UAN)"): exactly one identifier finds the member; two at
     * once is a validation failure, and a member found by CPF code or UAN but outside the user's
     * offices answers as not found, never as another office's record.
     */
    @Test
    void SRS_6_43_twoIdentifiersAreRefusedAndAMemberOfAnotherOfficeIsNotFound() {
        refused(
                () -> service.current(manager, "E100", "CPF/DL/7101", null, AS_AT),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.current(manager, "E100", null, "100200300400", AS_AT),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.current(manager, null, "CPF/DL/7101", "100200300400", AS_AT),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.current(manager, null, null, null, AS_AT),
                CommonErrorCode.VALIDATION_FAILED);

        EmployeeDirectory.Member elsewhere =
                new EmployeeDirectory.Member(9L, "E100", "Asha", 40L, "IN_SERVICE");
        when(directory.memberByCpfCodeOrUan("CPF/DL/7101", null))
                .thenReturn(Optional.of(elsewhere));
        when(directory.memberByCpfCodeOrUan(null, "100200300400")).thenReturn(Optional.empty());
        refused(
                () -> service.current(manager, null, "CPF/DL/7101", null, AS_AT),
                CommonErrorCode.NOT_FOUND);
        refused(
                () -> service.current(manager, null, null, "100200300400", AS_AT),
                CommonErrorCode.NOT_FOUND);
        verify(directory, never()).cpfKyc(any());
    }
}
