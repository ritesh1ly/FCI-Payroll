package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.SanctionRules.Stage;
import in.gov.fci.payroll.cpf.business.SspGateway;
import in.gov.fci.payroll.cpf.business.WithdrawalLimit;
import in.gov.fci.payroll.cpf.business.model.CpfClaim;
import in.gov.fci.payroll.cpf.business.model.CpfClaimHistory;
import in.gov.fci.payroll.cpf.business.model.CpfPurposeDocument;
import in.gov.fci.payroll.cpf.business.model.CpfSanctionTier;
import in.gov.fci.payroll.cpf.business.model.CpfWithdrawalPurpose;
import in.gov.fci.payroll.cpf.business.model.PfSanction;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.Decision;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.Document;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.Request;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.SanctionView;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-01 / I-04 CPF withdrawals beyond the happy paths (SRS-6-4, SRS-6-14, SRS-6-26, SRS-6-55,
 * BR-CPF-039): the member's session guards, a purpose that is not offered, the stages at which a
 * request may be withdrawn or resubmitted, the unit's checks of UAN, Aadhaar and documents, the
 * same user acting twice, the limits that are not configured, the Competent Authority's three
 * outcomes, the sanction number's format, and what a request shows without a purpose, claim or KYC.
 */
class CpfSanctionServiceEdgeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final EmployeeDirectory.Member MEMBER =
            new EmployeeDirectory.Member(9L, "104882", "Rakesh Sharma", 300L, "IN_SERVICE");
    private static final BigDecimal FIFTY_THOUSAND = new BigDecimal("50000");

    private final Authorization policy = mock(Authorization.class);
    private final CpfRepositories.Sanctions sanctions = mock(CpfRepositories.Sanctions.class);
    private final CpfRepositories.SanctionTiers tiers = mock(CpfRepositories.SanctionTiers.class);
    private final CpfRepositories.Claims claims = mock(CpfRepositories.Claims.class);
    private final CpfRepositories.ClaimHistory claimHistory =
            mock(CpfRepositories.ClaimHistory.class);
    private final CpfRepositories.Purposes purposes = mock(CpfRepositories.Purposes.class);
    private final CpfRepositories.PurposeDocuments checklists =
            mock(CpfRepositories.PurposeDocuments.class);
    private final CpfRepositories.Headers headers = mock(CpfRepositories.Headers.class);
    private final EmployeeDirectory directory = mock(EmployeeDirectory.class);
    private final CpfZones zones = mock(CpfZones.class);
    private final RuleEngine engine = mock(RuleEngine.class);
    private final ReferenceData references = mock(ReferenceData.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AuditService audit = mock(AuditService.class);
    private final CpfSspSync ssp = mock(CpfSspSync.class);

    private final CpfSanctionService service =
            new CpfSanctionService(
                    policy,
                    sanctions,
                    tiers,
                    claims,
                    claimHistory,
                    purposes,
                    checklists,
                    headers,
                    directory,
                    mock(CpfMembers.class),
                    zones,
                    engine,
                    references,
                    mock(MakerChecker.class),
                    documents,
                    audit,
                    JsonMapper.builder().build(),
                    mock(org.springframework.context.ApplicationEventPublisher.class),
                    ssp,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(in.gov.fci.payroll.platform.approval.ApprovalWithdrawals.class));

    /** The Unit CPF Administrator of office 300. */
    private final AccessContext unit =
            new AccessContext(
                    3L,
                    "cpf.unit.delhi",
                    "Unit",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(300L));

    CpfSanctionServiceEdgeTest() {
        when(directory.member(9L)).thenReturn(Optional.of(MEMBER));
        when(tiers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** An eSeva member session of employee record {@code employeeId} (none for a staff user). */
    private static AccessContext member(Long employeeId) {
        return new AccessContext(
                2L,
                "member@x",
                "Member",
                "s",
                "E",
                300L,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static Object[] argsOf(ThrowingCallable call) {
        try {
            call.call();
        } catch (FciException e) {
            return e.getMessageArgs();
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        throw new AssertionError("expected a refusal");
    }

    private PfSanction sanction(long id, Stage stage, String type, Long purposeId) {
        PfSanction s = mock(PfSanction.class);
        when(s.getSanctionId()).thenReturn(id);
        when(s.getEmployeeId()).thenReturn(9L);
        when(s.stage()).thenReturn(stage);
        when(s.getSanctionType()).thenReturn(type);
        when(s.getPurposeId()).thenReturn(purposeId);
        when(sanctions.lockById(id)).thenReturn(Optional.of(s));
        when(sanctions.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private CpfWithdrawalPurpose purpose(long id, String code, String category, String limitRule) {
        CpfWithdrawalPurpose p = mock(CpfWithdrawalPurpose.class);
        when(p.getPurposeId()).thenReturn(id);
        when(p.getPurposeCode()).thenReturn(code);
        when(p.getPurposeName()).thenReturn(code + " purpose");
        when(p.getWithdrawalCategory()).thenReturn(category);
        when(p.getSanctionLimitRuleKey()).thenReturn(limitRule);
        // Unbounded unless a test says otherwise (a mocked Integer would read 0, not null).
        when(p.getMaxOccurrences()).thenReturn((Integer) null);
        when(p.inForceOn(TODAY)).thenReturn(true);
        when(purposes.findById(id)).thenReturn(Optional.of(p));
        when(purposes.findByPurposeCodeOrderByEffectiveFromDesc(code)).thenReturn(List.of(p));
        return p;
    }

    private static CpfPurposeDocument checklistItem(String type) {
        CpfPurposeDocument d = mock(CpfPurposeDocument.class);
        when(d.getDocumentTypeCode()).thenReturn(type);
        when(d.isMandatory()).thenReturn(true);
        return d;
    }

    private void eligibility(boolean uan, boolean kyc) {
        when(directory.cpfEligibility(9L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.CpfEligibility(
                                        9L,
                                        LocalDate.of(2015, 1, 1),
                                        LocalDate.of(2040, 1, 1),
                                        null,
                                        uan,
                                        kyc)));
    }

    private CpfClaim claim(long sanctionId, CpfClaim.Status status) {
        CpfClaim c = mock(CpfClaim.class);
        when(c.getClaimId()).thenReturn(sanctionId * 10);
        when(c.status()).thenReturn(status);
        when(c.getSubmittedAt()).thenReturn(NOW);
        when(claims.findBySanctionId(sanctionId)).thenReturn(Optional.of(c));
        return c;
    }

    private void rule(String key, String value) {
        RuleEngine.Resolved resolved = mock(RuleEngine.Resolved.class);
        when(resolved.scalar()).thenReturn(new BigDecimal(value));
        when(engine.resolve(eq(key), eq(TODAY), any())).thenReturn(Optional.of(resolved));
    }

    private static Request request(String purposeCode) {
        return new Request(purposeCode, FIFTY_THOUSAND, null, null);
    }

    // ---------------------------------------------------------------- the member

    @Test
    void SRS_6_14_aSessionWithoutAnEmployeeOrWithoutARequestIsRefusedOnEveryMemberCall() {
        AccessContext nobody = member(null);
        refused(
                () -> service.request(nobody, request("MARRIAGE"), List.of()),
                ApiErrorCode.PERMISSION_DENIED);
        refused(() -> service.request(member(9L), null, List.of()), ApiErrorCode.PERMISSION_DENIED);
        refused(() -> service.purposes(nobody), ApiErrorCode.PERMISSION_DENIED);
        refused(() -> service.mine(nobody), ApiErrorCode.PERMISSION_DENIED);
        // Another member's request, or no member at all, is simply not found.
        sanction(41L, Stage.UNIT_VERIFICATION, "ADVANCE", 5L);
        refused(() -> service.withdraw(nobody, 41L, "no longer needed"), CommonErrorCode.NOT_FOUND);
        refused(
                () -> service.withdraw(member(8L), 41L, "no longer needed"),
                CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_6_14_aPurposeNotOfferedTodayOrNoneAtAllIsRefused() {
        refused(
                () -> service.request(member(9L), request(null), List.of()),
                CpfErrorCode.PURPOSE_NOT_OFFERED);
        refused(
                () -> service.request(member(9L), request("UNKNOWN"), List.of()),
                CpfErrorCode.PURPOSE_NOT_OFFERED);
    }

    @Test
    void SRS_6_14_aRequestWithoutAttachmentsIsCheckedAgainstTheChecklist() {
        purpose(5L, "MARRIAGE", "ADVANCE", "CPF.LIMIT.MGW.MARRIAGE");
        CpfPurposeDocument certificate = checklistItem("CERTIFICATE");
        when(checklists.findByPurposeIdOrderByPurposeDocId(5L)).thenReturn(List.of(certificate));
        assertThat(argsOf(() -> service.request(member(9L), request("MARRIAGE"), null)))
                .containsExactly("CERTIFICATE");
        refused(
                () -> service.request(member(9L), request("MARRIAGE"), null),
                CpfErrorCode.DOCUMENTS_MISSING);
    }

    @Test
    void BR_CPF_011_aPartFinalRequestNeedsItsConfiguredYearsOrRetirementWindow() {
        eligibility(true, true);
        purpose(6L, "HOUSE", "PART_FINAL", "CPF.LIMIT.MGW.HOUSE");
        purpose(8L, "NINETY", "PART_FINAL", WithdrawalLimit.BEFORE_RETIREMENT.ruleKey());
        assertThat(argsOf(() -> service.request(member(9L), request("HOUSE"), List.of())))
                .containsExactly(CpfSanctionService.MIN_YEARS_RULE);
        assertThat(argsOf(() -> service.request(member(9L), request("NINETY"), List.of())))
                .containsExactly(CpfSanctionService.WINDOW_RULE);
        refused(
                () -> service.request(member(9L), request("HOUSE"), List.of()),
                CpfErrorCode.LIMIT_NOT_CONFIGURED);
    }

    @Test
    void SRS_6_5_aPurposeTakenAsOftenAsAllowedIsRefusedAndAFinalRequestHasNoConditionsOfItsOwn() {
        eligibility(true, true);
        CpfWithdrawalPurpose finalSettlement = purpose(7L, "FINAL_SETTLEMENT", "FINAL", null);
        PfSanction earlier = mock(PfSanction.class);
        when(earlier.getSanctionId()).thenReturn(40L);
        when(earlier.getPurposeId()).thenReturn(7L);
        when(earlier.stage()).thenReturn(Stage.SANCTIONED);
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L)).thenReturn(List.of(earlier));
        when(finalSettlement.getMaxOccurrences()).thenReturn(1);
        assertThat(
                        argsOf(
                                () ->
                                        service.request(
                                                member(9L),
                                                request("FINAL_SETTLEMENT"),
                                                List.of())))
                .containsExactly(1);

        when(finalSettlement.getMaxOccurrences()).thenReturn((Integer) null);
        when(zones.of(300L)).thenReturn(new CpfZones.Zone(1L, "NA11"));
        when(sanctions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(claims.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SanctionView view =
                service.request(
                        member(9L),
                        request("FINAL_SETTLEMENT"),
                        List.of(
                                new Document(
                                        "CERTIFICATE",
                                        "C/1",
                                        TODAY,
                                        "Registrar",
                                        "card.pdf",
                                        new byte[] {1})));
        assertThat(view.type()).isEqualTo("FINAL");
        assertThat(view.purposeCode()).isEqualTo("FINAL_SETTLEMENT");
        assertThat(view.stage()).isEqualTo(Stage.UNIT_VERIFICATION.name());
        assertThat(view.requested()).isEqualByComparingTo(FIFTY_THOUSAND);
        assertThat(view.uan()).isNull();
        verify(documents).attach(eq("member@x"), any());
    }

    @Test
    void SRS_12_9_aRequestIsWithdrawnOnlyWhileItIsWithTheChainAndWithAReasonKeptToItsLength() {
        sanction(41L, Stage.SANCTIONED, "ADVANCE", 5L);
        refused(() -> service.withdraw(member(9L), 41L, "late"), CpfErrorCode.SANCTION_STAGE);
        PfSanction open = sanction(42L, Stage.UNIT_VERIFICATION, "ADVANCE", 5L);
        refused(() -> service.withdraw(member(9L), 42L, "   "), ApiErrorCode.REASON_REQUIRED);
        refused(() -> service.withdraw(member(9L), 42L, null), ApiErrorCode.REASON_REQUIRED);
        purpose(5L, "MARRIAGE", "ADVANCE", "CPF.LIMIT.MGW.MARRIAGE");
        CpfClaim claim = claim(42L, CpfClaim.Status.SUBMITTED);

        service.withdraw(member(9L), 42L, " " + "x".repeat(1200) + " ");
        service.withdraw(member(9L), 42L, "No longer needed");

        verify(open).cancelled("x".repeat(1200), NOW);
        verify(claim, times(2)).status(CpfClaim.Status.WITHDRAWN, NOW);
        ArgumentCaptor<CpfClaimHistory> history = ArgumentCaptor.captor();
        verify(claimHistory, times(2)).save(history.capture());
        assertThat(history.getAllValues().get(0).getRemark()).hasSize(1000);
        assertThat(history.getAllValues().get(1).getRemark()).isEqualTo("No longer needed");
    }

    /**
     * US-GAP-166 (SRS-6-26, SRS l.2258): the member sees the capped amount once tier 2 has capped
     * it and may still withdraw before the sanction; a decision on the withdrawn request changes
     * nothing.
     */
    @Test
    void SRS_6_26_aCappedRequestIsShownToTheMemberWhoMayWithdrawItBeforeTheSanction() {
        PfSanction capped = sanction(44L, Stage.COMPETENT_AUTHORITY, "ADVANCE", 5L);
        when(capped.getRequestedAmount()).thenReturn(FIFTY_THOUSAND);
        when(capped.getComputedCeilingAmount()).thenReturn(new BigDecimal("6000.00"));
        when(capped.getSanctionedAmount()).thenReturn(new BigDecimal("6000.00"));
        when(capped.isWasCapped()).thenReturn(true);
        purpose(5L, "MARRIAGE", "ADVANCE", "CPF.LIMIT.MGW.MARRIAGE");
        claim(44L, CpfClaim.Status.UNDER_REVIEW);
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L)).thenReturn(List.of(capped));
        when(directory.member(9L)).thenReturn(Optional.of(MEMBER));

        SanctionView seen = service.mine(member(9L)).getFirst();
        assertThat(seen.requested()).isEqualByComparingTo("50000");
        assertThat(seen.sanctioned()).isEqualByComparingTo("6000.00");
        assertThat(seen.capped()).isTrue();

        service.withdraw(member(9L), 44L, "The capped amount does not meet my need");
        verify(capped).cancelled("The capped amount does not meet my need", NOW);
        when(capped.stage()).thenReturn(Stage.CANCELLED);
        when(sanctions.findByApprovalId(90L)).thenReturn(Optional.of(capped));
        service.on(decided("I-04", CpfSanctionService.ENTITY, ApprovalStatus.APPROVED, "ok"));
        verify(capped, never()).sanctioned(any(), any(), any(), anyBooleanArg());
    }

    private static boolean anyBooleanArg() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    @Test
    void SRS_12_9_aReturnedRequestAloneIsResubmittedAndOnlyWithARequest() {
        sanction(43L, Stage.UNIT_VERIFICATION, "ADVANCE", 5L);
        refused(
                () -> service.resubmit(member(9L), 43L, request("MARRIAGE")),
                CpfErrorCode.SANCTION_STAGE);
        PfSanction returned = sanction(44L, Stage.RETURNED, "ADVANCE", 5L);
        refused(() -> service.resubmit(member(9L), 44L, null), CpfErrorCode.SANCTION_STAGE);
        purpose(5L, "MARRIAGE", "ADVANCE", "CPF.LIMIT.MGW.MARRIAGE");
        CpfClaim claim = claim(44L, CpfClaim.Status.RETURNED);

        SanctionView view =
                service.resubmit(
                        member(9L),
                        44L,
                        new Request("MARRIAGE", new BigDecimal("60000"), "  ", null));

        verify(returned).resubmitted(new BigDecimal("60000"));
        // Blank particulars are stored as none.
        verify(claim).resubmitted(new BigDecimal("60000"), null, null);
        verify(claim).status(CpfClaim.Status.SUBMITTED, NOW);
        assertThat(view.purposeName()).isEqualTo("MARRIAGE purpose");
    }

    // ---------------------------------------------------------------- the register

    @Test
    void SRS_6_55_theRegisterTakesABlankStageAndAnEmptyScopeAsNothingToList() {
        AccessContext noScope =
                new AccessContext(
                        3L,
                        "cpf.unit.delhi",
                        "Unit",
                        "s",
                        "P",
                        300L,
                        null,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of());
        assertThat(service.register(noScope, "  ", new PageSpec(0, 20)).total()).isZero();
        verify(sanctions, never()).inScope(any(), any(), any());

        when(sanctions.inScope(any(), any(), any())).thenReturn(Page.empty());
        assertThat(service.register(unit, null, new PageSpec(0, 20)).total()).isZero();
        assertThat(service.register(unit, "SANCTIONED", new PageSpec(0, 20)).total()).isZero();
        verify(sanctions).inScope(eq(Set.of(300L)), eq(null), any());
        verify(sanctions).inScope(eq(Set.of(300L)), eq("SANCTIONED"), any());
        assertThatThrownBy(() -> service.register(unit, "NOWHERE", new PageSpec(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SRS_6_55_aBlankStageFilterListsEveryStageOfTheScope() {
        when(sanctions.inScope(any(), any(), any())).thenReturn(Page.empty());
        assertThat(service.register(unit, "   ", new PageSpec(0, 20)).total()).isZero();
        verify(sanctions).inScope(eq(Set.of(300L)), eq(null), any());
    }

    @Test
    void SRS_6_14_aMemberOrRequestThatDoesNotExistIsNotFound() {
        // A session whose employee record is not in the directory (left HRMS since sign-in).
        when(directory.member(7L)).thenReturn(Optional.empty());
        refused(
                () -> service.request(member(7L), request("MARRIAGE"), List.of()),
                CommonErrorCode.NOT_FOUND);
        refused(() -> service.mine(member(7L)), CommonErrorCode.NOT_FOUND);
        // An unknown request id, at every entry that names one.
        when(sanctions.findById(404L)).thenReturn(Optional.empty());
        when(sanctions.lockById(404L)).thenReturn(Optional.empty());
        refused(() -> service.get(unit, 404L), CommonErrorCode.NOT_FOUND);
        refused(() -> service.document(unit, "10.0.0.1", 404L, 1L), CommonErrorCode.NOT_FOUND);
        refused(
                () -> service.verify(unit, 404L, Decision.FORWARD, "Checked"),
                CommonErrorCode.NOT_FOUND);
        verifyNoInteractions(documents);
    }

    @Test
    void SRS_6_51_aRequestWithoutAPurposeClaimOrKycShowsNoneOfThem() {
        sanction(47L, Stage.PAID, "ADVANCE", null);
        SanctionView view = service.get(unit, 47L);
        assertThat(view.purposeCode()).isNull();
        assertThat(view.purposeName()).isNull();
        assertThat(view.propertyAddress()).isNull();
        assertThat(view.propertyDescription()).isNull();
        assertThat(view.uan()).isNull();
        assertThat(view.aadhaarMasked()).isNull();
        assertThat(view.eis()).isEqualTo("104882");
        verify(purposes, never()).findById(any());
    }

    // ---------------------------------------------------------------- tier 1 and 2

    @Test
    void SRS_6_4_theUnitForwardsOnlyAMemberWithUanAndAadhaarOnRecordAndEveryDocument() {
        sanction(41L, Stage.UNIT_VERIFICATION, "ADVANCE", 5L);
        purpose(5L, "MARRIAGE", "ADVANCE", "CPF.LIMIT.MGW.MARRIAGE");
        eligibility(false, true);
        assertThat(argsOf(() -> service.verify(unit, 41L, Decision.FORWARD, null)))
                .containsExactly("uan_number");
        eligibility(true, false);
        assertThat(argsOf(() -> service.verify(unit, 41L, Decision.FORWARD, null)))
                .containsExactly("aadhaar_number");
        eligibility(true, true);
        CpfPurposeDocument certificate = checklistItem("CERTIFICATE");
        when(checklists.findByPurposeIdOrderByPurposeDocId(5L)).thenReturn(List.of(certificate));
        refused(
                () -> service.verify(unit, 41L, Decision.FORWARD, null),
                CpfErrorCode.DOCUMENTS_MISSING);
    }

    @Test
    void SRS_6_4_theSameUserNeverActsTwiceInOnePass() {
        sanction(41L, Stage.UNIT_VERIFICATION, "ADVANCE", 5L);
        CpfSanctionTier mine = mock(CpfSanctionTier.class);
        when(mine.getPassNo()).thenReturn(1);
        when(mine.getActedBy()).thenReturn("cpf.unit.delhi");
        when(mine.getTierAction()).thenReturn("FORWARDED");
        when(tiers.findBySanctionIdOrderByTierActionIdAsc(41L)).thenReturn(List.of(mine));
        refused(
                () -> service.verify(unit, 41L, Decision.FORWARD, null),
                CpfErrorCode.TIER_SAME_USER);
        // A colleague's action in the same pass does not stand in the way.
        when(mine.getActedBy()).thenReturn("cpf.unit.other");
        eligibility(false, true);
        refused(
                () -> service.verify(unit, 41L, Decision.FORWARD, null),
                CpfErrorCode.ELIGIBILITY_DATA);
    }

    @Test
    void
            SRS_6_48_BR_CPF_014_BR_CPF_015_aFinalPaymentIsNotRecommendedUntilItsInterestMethodIsConfigured() {
        sanction(45L, Stage.ZONAL_HQ_VALIDATION, "FINAL", 7L);
        purpose(7L, "FINAL_SETTLEMENT", "FINAL", null);
        refused(
                () -> service.recommend(unit, 45L, Decision.FORWARD, "ok", null),
                CpfErrorCode.FINAL_INTEREST_NOT_CONFIGURED);
    }

    @Test
    void
            SRS_6_24_SRS_6_36_BR_CPF_029_aLimitWithoutItsMultiplierItsMgwItsPayOrTheAdvanceCapIsNotConfigured() {
        sanction(46L, Stage.ZONAL_HQ_VALIDATION, "ADVANCE", 5L);
        purpose(5L, "MARRIAGE", "ADVANCE", "CPF.LIMIT.MGW.MARRIAGE");
        claim(46L, CpfClaim.Status.UNDER_REVIEW);
        eligibility(true, true);
        ThrowingCallable recommend =
                () -> service.recommend(unit, 46L, Decision.FORWARD, "ok", null);

        assertThat(argsOf(recommend)).containsExactly("CPF.LIMIT.MGW.MARRIAGE");
        rule("CPF.LIMIT.MGW.MARRIAGE", "6");
        assertThat(argsOf(recommend)).containsExactly(CpfSanctionService.MGW_RULE);
        rule(CpfSanctionService.MGW_RULE, "15000");
        assertThat(argsOf(recommend)).containsExactly(CpfSanctionService.ADVANCE_CAP_RULE);
        refused(recommend, CpfErrorCode.LIMIT_NOT_CONFIGURED);

        // A pay-based limit waits for the pay the engines do not compute yet (D-15).
        sanction(48L, Stage.ZONAL_HQ_VALIDATION, "PART_FINAL", 9L);
        purpose(9L, "RENOVATION", "PART_FINAL", WithdrawalLimit.RENOVATION.ruleKey());
        claim(48L, CpfClaim.Status.UNDER_REVIEW);
        rule(WithdrawalLimit.RENOVATION.ruleKey(), "12");
        rule(CpfSanctionService.MIN_YEARS_RULE, "5");
        rule(CpfSanctionService.MAX_COUNT_RULE, "6");
        assertThat(argsOf(() -> service.recommend(unit, 48L, Decision.FORWARD, "ok", null)))
                .containsExactly("pay (SRS-6-24)");
    }

    /** The changes the requests queued for the CPF SSP. */
    private List<SspGateway.Change> queued() {
        ArgumentCaptor<SspGateway.Change> changes = ArgumentCaptor.captor();
        verify(ssp, org.mockito.Mockito.atLeast(0)).queue(changes.capture());
        return changes.getAllValues();
    }

    @Test
    // US-GAP-165 (SRS l.2260).
    void SRS_6_4_aTier1ReturnGoesToTheMemberWithItsReasonQueuedForTheCpfSsp() {
        PfSanction s = sanction(41L, Stage.UNIT_VERIFICATION, "ADVANCE", 5L);
        purpose(5L, "MARRIAGE", "ADVANCE", "CPF.LIMIT.MGW.MARRIAGE");
        CpfClaim claim = claim(41L, CpfClaim.Status.SUBMITTED);
        refused(
                () -> service.verify(unit, 41L, Decision.RETURN, " "),
                ApiErrorCode.REASON_REQUIRED);
        assertThat(queued()).isEmpty();

        service.verify(unit, 41L, Decision.RETURN, "KYC not on record");

        verify(s).returnedToMember();
        verify(claim).status(CpfClaim.Status.RETURNED, NOW);
        assertThat(queued())
                .containsExactly(
                        new SspGateway.Change(
                                9L,
                                CpfSanctionService.SSP_REQUEST,
                                List.of("workflow_stage", "reason")));
    }

    @Test
    // US-GAP-164 (FRS V0.5 §13.4 Step 3, Exception Handling).
    void SRS_6_4_onlyTier2RejectsAndItsReasonIsKeptForTheMemberAndTheCpfSsp() {
        // Tier 1 returns, never rejects (FRS V0.5 §13.4 Step 2).
        PfSanction atUnit = sanction(41L, Stage.UNIT_VERIFICATION, "ADVANCE", 5L);
        refused(
                () -> service.verify(unit, 41L, Decision.REJECT, "not eligible"),
                CpfErrorCode.TIER1_REJECT);
        verify(atUnit, never()).rejected();

        PfSanction s = sanction(42L, Stage.ZONAL_HQ_VALIDATION, "PART_FINAL", 5L);
        purpose(5L, "HOUSE", "PART_FINAL", "CPF.LIMIT.MGW.HOUSE");
        CpfClaim claim = claim(42L, CpfClaim.Status.UNDER_REVIEW);
        refused(
                () -> service.recommend(unit, 42L, Decision.REJECT, null, null),
                ApiErrorCode.REASON_REQUIRED);
        verify(s, never()).rejected();

        service.recommend(unit, 42L, Decision.REJECT, " Fewer than 5 years of membership ", null);

        verify(s).rejected();
        verify(claim).status(CpfClaim.Status.REJECTED, NOW);
        ArgumentCaptor<CpfSanctionTier> row = ArgumentCaptor.captor();
        verify(tiers).save(row.capture());
        assertThat(row.getValue().getTierNo()).isEqualTo(2);
        assertThat(row.getValue().getTierAction()).isEqualTo("REJECTED");
        assertThat(row.getValue().getRemark()).isEqualTo("Fewer than 5 years of membership");
        // The member's stage and the reason are what the CPF SSP receives, once.
        assertThat(queued())
                .containsExactly(
                        new SspGateway.Change(
                                9L,
                                CpfSanctionService.SSP_REQUEST,
                                List.of("workflow_stage", "reason")));
        // A rejection raises no approval item: nothing goes to the Competent Authority.
        verify(s, never())
                .validated(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    @Test
    // US-GAP-167 (FRS V0.5 §13 Audit Process).
    void SRS_6_4_everyTierActionAuditsThePurposeCategoryAndTheDocumentsVerified() {
        sanction(41L, Stage.UNIT_VERIFICATION, "ADVANCE", 5L);
        purpose(5L, "MARRIAGE", "ADVANCE", "CPF.LIMIT.MGW.MARRIAGE");
        claim(41L, CpfClaim.Status.SUBMITTED);
        when(documents.active(CpfSanctionService.DOCUMENTS, "41"))
                .thenReturn(List.of(attachment("NO_CASTE_DISPUTE"), attachment("CERTIFICATE")));

        service.verify(unit, 41L, Decision.RETURN, "Sale deed unreadable");

        ArgumentCaptor<java.util.Collection<AuditService.Entry>> facts = ArgumentCaptor.captor();
        verify(audit).recordAll(eq("cpf.unit.delhi"), facts.capture());
        String why = "Returned to the member: Sale deed unreadable";
        assertThat(facts.getValue())
                .extracting(
                        AuditService.Entry::fieldName,
                        AuditService.Entry::newValue,
                        AuditService.Entry::reason,
                        AuditService.Entry::moduleCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "purpose_category", "ADVANCE", why, "I-04"),
                        org.assertj.core.groups.Tuple.tuple(
                                "documents_verified", "CERTIFICATE,NO_CASTE_DISPUTE", why, "I-04"));
    }

    @Test
    // US-GAP-167 (FRS V0.5 §13 Audit Process).
    void SRS_6_4_aDecisionWithoutAPurposeOrDocumentsAuditsNoneWithItsApproval() {
        PfSanction waiting = sanction(43L, Stage.COMPETENT_AUTHORITY, "ADVANCE", null);
        when(sanctions.findByApprovalId(90L)).thenReturn(Optional.of(waiting));
        claim(43L, CpfClaim.Status.UNDER_REVIEW);

        service.on(decided("I-04", CpfSanctionService.ENTITY, ApprovalStatus.REJECTED, null));

        ArgumentCaptor<java.util.Collection<AuditService.Entry>> facts = ArgumentCaptor.captor();
        verify(audit).recordAll(eq("ca.north"), facts.capture());
        assertThat(facts.getValue())
                .extracting(AuditService.Entry::newValue, AuditService.Entry::approvalId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(null, 90L),
                        org.assertj.core.groups.Tuple.tuple("none", 90L));
        // A tier-3 decision is the Competent Authority's in the inbox: nothing queued here.
        assertThat(queued()).isEmpty();
    }

    private static DocumentStore.AttachmentView attachment(String type) {
        return new DocumentStore.AttachmentView(
                1L,
                CpfSanctionService.DOCUMENTS,
                "41",
                type,
                type,
                null,
                null,
                null,
                type + ".pdf",
                "application/pdf",
                10L,
                "x",
                "ACTIVE",
                "member@x",
                NOW);
    }

    // ---------------------------------------------------------------- tier 3

    private static ApprovalDecided decided(
            String module, String entity, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                90L, module, entity, "41", outcome, reason, null, "ca.north", NOW, 7L);
    }

    @Test
    void SRS_6_26_aDecisionIsAppliedOnlyToItsOwnSanctionWaitingAtTheCompetentAuthority() {
        service.on(decided("X-02", CpfSanctionService.ENTITY, ApprovalStatus.APPROVED, "ok"));
        service.on(decided("I-04", "off_cycle_payment", ApprovalStatus.APPROVED, "ok"));
        verify(sanctions, never()).findByApprovalId(any());
        service.on(decided("I-04", CpfSanctionService.ENTITY, ApprovalStatus.APPROVED, "ok"));
        PfSanction paid = sanction(41L, Stage.PAID, "ADVANCE", 5L);
        when(sanctions.findByApprovalId(90L)).thenReturn(Optional.of(paid));
        service.on(decided("I-04", CpfSanctionService.ENTITY, ApprovalStatus.APPROVED, "ok"));
        verifyNoInteractions(tiers);
        verify(paid, never()).sanctioned(any(), any(), any(), eq(true));
    }

    @Test
    void SRS_6_26_aRejectionWithoutAReasonIsRecordedAsDecidedAndAReturnGoesBackToTheUnit() {
        PfSanction waiting = sanction(41L, Stage.COMPETENT_AUTHORITY, "ADVANCE", 5L);
        when(sanctions.findByApprovalId(90L)).thenReturn(Optional.of(waiting));
        CpfClaim claim = claim(41L, CpfClaim.Status.UNDER_REVIEW);

        service.on(decided("I-04", CpfSanctionService.ENTITY, ApprovalStatus.REJECTED, null));
        service.on(decided("I-04", CpfSanctionService.ENTITY, ApprovalStatus.RETURNED, "docs"));

        verify(waiting).rejected();
        verify(waiting).backToUnit();
        verify(claim).status(CpfClaim.Status.REJECTED, NOW);
        verify(claim).status(CpfClaim.Status.DOCS_PENDING, NOW);
        ArgumentCaptor<CpfClaimHistory> history = ArgumentCaptor.captor();
        verify(claimHistory, times(2)).save(history.capture());
        assertThat(history.getAllValues())
                .extracting(CpfClaimHistory::getRemark)
                .containsExactly(null, "docs");
        ArgumentCaptor<AuditService.Entry> entries = ArgumentCaptor.captor();
        verify(audit, times(2)).record(eq("ca.north"), entries.capture());
        assertThat(entries.getAllValues())
                .extracting(AuditService.Entry::reason)
                .containsExactly("Decided", "docs");
    }

    @Test
    void SRS_6_55_aSanctionIsNumberedInItsZoneAndBlockedWhenTheLedgerCannotPayIt() {
        PfSanction waiting = sanction(41L, Stage.COMPETENT_AUTHORITY, "ADVANCE", 5L);
        when(waiting.getSanctionedAmount()).thenReturn(new BigDecimal("6000"));
        when(waiting.getSanctionZoneId()).thenReturn(1L);
        when(sanctions.findByApprovalId(90L)).thenReturn(Optional.of(waiting));
        when(zones.of(300L)).thenReturn(new CpfZones.Zone(1L, "NA11"));
        when(sanctions.numberedInZone(1L)).thenReturn(41L);
        ApprovalDecided approved =
                decided("I-04", CpfSanctionService.ENTITY, ApprovalStatus.APPROVED, "ok");

        // The number's format is configuration: without its separator or width nothing is issued.
        numberFormat(null, new BigDecimal("5"));
        refused(() -> service.on(approved), CpfErrorCode.SANCTION_NUMBER_NOT_CONFIGURED);
        numberFormat("/", null);
        refused(() -> service.on(approved), CpfErrorCode.SANCTION_NUMBER_NOT_CONFIGURED);
        verify(waiting, never()).sanctioned(any(), any(), any(), eq(true));

        numberFormat("/", new BigDecimal("5"));
        service.on(approved);
        // An empty ledger cannot pay 6,000: sanctioned, but the payment is blocked.
        verify(waiting).sanctioned("10/2026/NA11/00042", TODAY, 300L, true);
    }

    /** The CPF_SANCTION_NUMBER_FORMAT list: a SEPARATOR label and a numeric SEQUENCE_WIDTH. */
    private void numberFormat(String separator, BigDecimal width) {
        List<ReferenceData.Value> values =
                List.of(
                        new ReferenceData.Value(
                                separator == null ? "OTHER" : "SEPARATOR",
                                separator == null ? "x" : separator,
                                null,
                                1,
                                null,
                                null,
                                true),
                        new ReferenceData.Value(
                                "SEQUENCE_WIDTH", "width", null, 2, width, null, true));
        when(references.list(CpfSanctionService.NUMBER_FORMAT))
                .thenReturn(
                        Optional.of(
                                new ReferenceData.ListView(
                                        CpfSanctionService.NUMBER_FORMAT,
                                        "Sanction number",
                                        "M-16",
                                        true,
                                        values)));
    }
}
