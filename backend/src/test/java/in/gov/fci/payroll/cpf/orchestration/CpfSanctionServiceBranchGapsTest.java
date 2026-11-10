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
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.SanctionRules.Stage;
import in.gov.fci.payroll.cpf.business.model.CpfClaim;
import in.gov.fci.payroll.cpf.business.model.CpfPurposeDocument;
import in.gov.fci.payroll.cpf.business.model.CpfWithdrawalPurpose;
import in.gov.fci.payroll.cpf.business.model.PfSanction;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.Decision;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.Document;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.EarlierWithdrawal;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.Request;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.SanctionView;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.MakerChecker;
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
import org.springframework.data.domain.PageImpl;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-01 / I-04 CPF withdrawals at the branch edges (SRS-6-5, SRS-6-14, SRS-6-24, SRS-6-2, SRS-6-55,
 * SRS l.2279): earlier withdrawals with and without a purpose; the No Caste Dispute certificate at
 * tier 1 only at its stage and only where the purpose asks for it, never twice in the missing list;
 * a purpose taken fewer times than allowed; a part-final limit not capped as an advance; the
 * register reading each member once, or the directory where the page lookup has none.
 */
class CpfSanctionServiceBranchGapsTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final EmployeeDirectory.Member MEMBER =
            new EmployeeDirectory.Member(9L, "104882", "Rakesh Sharma", 300L, "IN_SERVICE");
    private static final BigDecimal FIFTY_THOUSAND = new BigDecimal("50000");

    private final Authorization policy = mock(Authorization.class);
    private final CpfRepositories.Sanctions sanctions = mock(CpfRepositories.Sanctions.class);
    private final CpfRepositories.SanctionTiers tiers = mock(CpfRepositories.SanctionTiers.class);
    private final CpfRepositories.Claims claims = mock(CpfRepositories.Claims.class);
    private final CpfRepositories.Purposes purposes = mock(CpfRepositories.Purposes.class);
    private final CpfRepositories.PurposeDocuments checklists =
            mock(CpfRepositories.PurposeDocuments.class);
    private final EmployeeDirectory directory = mock(EmployeeDirectory.class);
    private final CpfZones zones = mock(CpfZones.class);
    private final RuleEngine engine = mock(RuleEngine.class);
    private final ReferenceData references = mock(ReferenceData.class);
    private final DocumentStore documents = mock(DocumentStore.class);

    private final CpfSanctionService service =
            new CpfSanctionService(
                    policy,
                    sanctions,
                    tiers,
                    claims,
                    mock(CpfRepositories.ClaimHistory.class),
                    purposes,
                    checklists,
                    mock(CpfRepositories.Headers.class),
                    directory,
                    mock(CpfMembers.class),
                    zones,
                    engine,
                    references,
                    mock(MakerChecker.class),
                    documents,
                    mock(AuditService.class),
                    JsonMapper.builder().build(),
                    mock(org.springframework.context.ApplicationEventPublisher.class),
                    mock(CpfSspSync.class),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(in.gov.fci.payroll.platform.approval.ApprovalWithdrawals.class));

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

    CpfSanctionServiceBranchGapsTest() {
        when(directory.member(9L)).thenReturn(Optional.of(MEMBER));
        when(tiers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static AccessContext member() {
        return new AccessContext(
                2L,
                "member@x",
                "Member",
                "s",
                "E",
                300L,
                9L,
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
        when(p.getMaxOccurrences()).thenReturn((Integer) null);
        when(p.inForceOn(TODAY)).thenReturn(true);
        when(purposes.findById(id)).thenReturn(Optional.of(p));
        when(purposes.findByPurposeCodeOrderByEffectiveFromDesc(code)).thenReturn(List.of(p));
        return p;
    }

    private void eligibility() {
        when(directory.cpfEligibility(9L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.CpfEligibility(
                                        9L,
                                        LocalDate.of(2015, 1, 1),
                                        LocalDate.of(2040, 1, 1),
                                        null,
                                        true,
                                        true)));
    }

    private void claim(long sanctionId) {
        CpfClaim c = mock(CpfClaim.class);
        when(c.getClaimId()).thenReturn(sanctionId * 10);
        when(c.status()).thenReturn(CpfClaim.Status.UNDER_REVIEW);
        when(c.getSubmittedAt()).thenReturn(NOW);
        when(claims.findBySanctionId(sanctionId)).thenReturn(Optional.of(c));
    }

    private void rule(String key, String value) {
        RuleEngine.Resolved resolved = mock(RuleEngine.Resolved.class);
        when(resolved.scalar()).thenReturn(new BigDecimal(value));
        when(engine.resolve(eq(key), eq(TODAY), any())).thenReturn(Optional.of(resolved));
    }

    private static Document certificate(String type) {
        return new Document(type, "NCD/1", TODAY, "Tehsildar", "ncd.pdf", new byte[] {1});
    }

    // ---------------------------------------------------------------- earlier withdrawals

    /** SRS l.2279 (BR-CPF-010): the earlier withdrawals that count, with or without a purpose. */
    @Test
    void SRS_6_5_earlierWithdrawalsShowTheirPurposeOrNoneAndSkipTheEndedOnes() {
        sanction(50L, Stage.UNIT_VERIFICATION, "ADVANCE", 5L);
        purpose(5L, "MARRIAGE", "ADVANCE", "CPF.LIMIT.MGW.MARRIAGE");
        PfSanction self = sanctions.findById(50L).orElseThrow();
        PfSanction migrated = mock(PfSanction.class);
        when(migrated.getSanctionId()).thenReturn(41L);
        when(migrated.stage()).thenReturn(Stage.PAID);
        when(migrated.getSanctionType()).thenReturn("PART_FINAL");
        when(migrated.getPurposeId()).thenReturn(null);
        PfSanction marriage = mock(PfSanction.class);
        when(marriage.getSanctionId()).thenReturn(42L);
        when(marriage.stage()).thenReturn(Stage.SANCTIONED);
        when(marriage.getSanctionType()).thenReturn("ADVANCE");
        when(marriage.getPurposeId()).thenReturn(5L);
        when(marriage.getSystemSanctionNumber()).thenReturn("10/2026/NA11/00042");
        PfSanction rejected = mock(PfSanction.class);
        when(rejected.getSanctionId()).thenReturn(43L);
        when(rejected.stage()).thenReturn(Stage.REJECTED);
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L))
                .thenReturn(List.of(self, rejected, marriage, migrated));

        List<EarlierWithdrawal> earlier = service.get(unit, 50L).earlierWithdrawals();

        assertThat(earlier)
                .extracting(EarlierWithdrawal::id, EarlierWithdrawal::purposeCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(42L, "MARRIAGE"),
                        org.assertj.core.groups.Tuple.tuple(41L, null));
    }

    // ---------------------------------------------------------------- tier 1 certificate

    /** SRS-6-14 (US-GAP-28): tier 1 attaches only at unit verification. */
    @Test
    void SRS_6_14_theCertificateIsAttachedOnlyAtUnitVerification() {
        sanction(51L, Stage.ZONAL_HQ_VALIDATION, "PART_FINAL", 6L);
        refused(
                () ->
                        service.attachAtUnit(
                                unit, 51L, certificate(CpfSanctionService.NO_CASTE_DISPUTE)),
                CpfErrorCode.SANCTION_STAGE);
        verify(documents, never()).attach(any(), any());
    }

    /**
     * SRS-6-14 (US-GAP-28): only the No Caste Dispute certificate, and only where the purpose asks
     * for it; then it is attached to the request.
     */
    @Test
    void SRS_6_14_onlyTheCertificateAndOnlyWhereThePurposeRequiresIt() {
        sanction(52L, Stage.UNIT_VERIFICATION, "PART_FINAL", 6L);
        CpfWithdrawalPurpose house = purpose(6L, "HOUSE", "PART_FINAL", "CPF.LIMIT.MGW.HOUSE");
        when(house.isRequiresNoCasteDispute()).thenReturn(false);
        refused(
                () ->
                        service.attachAtUnit(
                                unit, 52L, certificate(CpfSanctionService.NO_CASTE_DISPUTE)),
                CpfErrorCode.DOCUMENTS_MISSING);
        when(house.isRequiresNoCasteDispute()).thenReturn(true);
        refused(
                () -> service.attachAtUnit(unit, 52L, certificate("SALE_DEED")),
                CpfErrorCode.DOCUMENTS_MISSING);
        verify(documents, never()).attach(any(), any());

        SanctionView view =
                service.attachAtUnit(unit, 52L, certificate(CpfSanctionService.NO_CASTE_DISPUTE));

        verify(documents).attach(eq("cpf.unit.delhi"), any());
        assertThat(view.id()).isEqualTo(52L);
    }

    /**
     * SRS-6-14 (US-GAP-28): a certificate already on the purpose's checklist is named once among
     * the missing documents, not again for the purpose's flag.
     */
    @Test
    void SRS_6_14_aCertificateOnTheChecklistIsNamedOnceWhenMissing() {
        sanction(53L, Stage.UNIT_VERIFICATION, "PART_FINAL", 6L);
        CpfWithdrawalPurpose house = purpose(6L, "HOUSE", "PART_FINAL", "CPF.LIMIT.MGW.HOUSE");
        when(house.isRequiresNoCasteDispute()).thenReturn(true);
        CpfPurposeDocument item = mock(CpfPurposeDocument.class);
        when(item.getDocumentTypeCode()).thenReturn(CpfSanctionService.NO_CASTE_DISPUTE);
        when(item.isMandatory()).thenReturn(true);
        when(checklists.findByPurposeIdOrderByPurposeDocId(6L)).thenReturn(List.of(item));
        eligibility();
        assertThat(argsOf(() -> service.verify(unit, 53L, Decision.FORWARD, null)))
                .containsExactly(CpfSanctionService.NO_CASTE_DISPUTE);
    }

    // ---------------------------------------------------------------- limits

    /** SRS-6-5: a purpose taken fewer times than its maximum may be requested again. */
    @Test
    void SRS_6_5_aPurposeTakenFewerTimesThanItsMaximumIsAccepted() {
        eligibility();
        CpfWithdrawalPurpose finalSettlement = purpose(7L, "FINAL_SETTLEMENT", "FINAL", null);
        when(finalSettlement.getMaxOccurrences()).thenReturn(2);
        PfSanction earlier = mock(PfSanction.class);
        when(earlier.getSanctionId()).thenReturn(40L);
        when(earlier.getPurposeId()).thenReturn(7L);
        when(earlier.stage()).thenReturn(Stage.SANCTIONED);
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L)).thenReturn(List.of(earlier));
        when(zones.of(300L)).thenReturn(new CpfZones.Zone(1L, "NA11"));
        when(sanctions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(claims.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SanctionView view =
                service.request(
                        member(),
                        new Request("FINAL_SETTLEMENT", FIFTY_THOUSAND, null, null),
                        List.of());

        assertThat(view.type()).isEqualTo("FINAL");
        assertThat(view.stage()).isEqualTo(Stage.UNIT_VERIFICATION.name());
    }

    /**
     * SRS-6-24 / SRS-6-2: a part-final withdrawal is limited by its MGW multiple only; the advance
     * cap does not apply, so with the limit configured the recommendation goes on to the sanction
     * number format (not configured here).
     */
    @Test
    void SRS_6_24_aPartFinalCeilingIsNotCappedAsAnAdvance() {
        PfSanction s = sanction(54L, Stage.ZONAL_HQ_VALIDATION, "PART_FINAL", 6L);
        when(s.getRequestedAmount()).thenReturn(FIFTY_THOUSAND);
        purpose(6L, "HOUSE", "PART_FINAL", "CPF.LIMIT.MGW.HOUSE");
        claim(54L);
        eligibility();
        rule("CPF.LIMIT.MGW.HOUSE", "36");
        rule(CpfSanctionService.MGW_RULE, "15000");
        rule(CpfSanctionService.MIN_YEARS_RULE, "5");
        rule(CpfSanctionService.MAX_COUNT_RULE, "6");
        refused(
                () -> service.recommend(unit, 54L, Decision.FORWARD, "ok", null),
                CpfErrorCode.SANCTION_NUMBER_NOT_CONFIGURED);
        verify(engine, never()).resolve(eq(CpfSanctionService.ADVANCE_CAP_RULE), any(), any());
    }

    // ---------------------------------------------------------------- register

    /** SRS-6-55: the register reads its members once; one the lookup misses is read alone. */
    @Test
    void SRS_6_55_theRegisterUsesThePageLookupAndFallsBackForAMissingMember() {
        PfSanction known = sanction(55L, Stage.PAID, "ADVANCE", null);
        PfSanction other = sanction(56L, Stage.PAID, "ADVANCE", null);
        when(other.getEmployeeId()).thenReturn(8L);
        when(directory.member(8L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(
                                        8L, "104883", "Sunita Rao", 300L, "IN_SERVICE")));
        when(directory.members(any()))
                .thenReturn(
                        Map.of(
                                9L,
                                new EmployeeDirectory.Member(
                                        9L, "104882", "Rakesh Sharma (page)", 300L, "IN_SERVICE")));
        when(sanctions.inScope(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(known, other)));

        List<SanctionView> rows = service.register(unit, null, new PageSpec(0, 20)).items();

        assertThat(rows)
                .extracting(SanctionView::eis, SanctionView::memberName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("104882", "Rakesh Sharma (page)"),
                        org.assertj.core.groups.Tuple.tuple("104883", "Sunita Rao"));
        verify(directory, never()).member(9L);
    }

    // ---------------------------------------------------------------- the 90 % withdrawal

    private void retiringOn(LocalDate retirement) {
        when(directory.cpfEligibility(9L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.CpfEligibility(
                                        9L,
                                        LocalDate.of(2015, 1, 1),
                                        retirement,
                                        null,
                                        true,
                                        true)));
    }

    private Throwable ninetyRequest() {
        purpose(
                8L,
                "NINETY_PERCENT",
                "PART_FINAL",
                in.gov.fci.payroll.cpf.business.WithdrawalLimit.BEFORE_RETIREMENT.ruleKey());
        rule(CpfSanctionService.WINDOW_RULE, "1");
        when(zones.of(300L)).thenReturn(new CpfZones.Zone(1L, "NA11"));
        when(sanctions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(claims.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        try {
            service.request(
                    member(), new Request("NINETY_PERCENT", FIFTY_THOUSAND, null, null), List.of());
            return null;
        } catch (RuntimeException ex) {
            return ex;
        }
    }

    /**
     * SRS-6-5 / BR-CPF-011 (Q-07-7): the 90 % withdrawal is asked only within the configured years
     * before retirement, on its own window; before the window or after retirement it is refused.
     */
    @Test
    void BR_CPF_011_theNinetyPercentWithdrawalIsRefusedBeforeTheWindowAndAfterRetirement() {
        retiringOn(TODAY.plusYears(1).plusDays(1));
        assertThat(ninetyRequest())
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(CpfErrorCode.RETIREMENT_WINDOW);
                            assertThat(e.getMessageArgs()).containsExactly(1);
                        });

        retiringOn(TODAY.minusDays(1));
        assertThat(ninetyRequest())
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CpfErrorCode.RETIREMENT_WINDOW));
        verify(sanctions, never()).save(any());
    }

    /** BR-CPF-011: the first day of the window and the retirement day itself are inside it. */
    @Test
    void BR_CPF_011_theFirstDayOfTheWindowAndTheRetirementDayAreInsideIt() {
        for (LocalDate retirement : new LocalDate[] {TODAY.plusYears(1), TODAY}) {
            retiringOn(retirement);
            Throwable outcome = ninetyRequest();
            if (outcome instanceof FciException e) {
                assertThat(e.getErrorCode())
                        .as(retirement.toString())
                        .isNotIn(CpfErrorCode.RETIREMENT_WINDOW, CpfErrorCode.ELIGIBILITY_DATA);
            }
        }
    }

    /** BR-CPF-011: a member without a retirement date cannot be placed in the window. */
    @Test
    void BR_CPF_011_aMemberWithoutARetirementDateIsRefusedAsMissingData() {
        retiringOn(null);
        assertThat(ninetyRequest())
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CpfErrorCode.ELIGIBILITY_DATA));
        verify(sanctions, never()).save(any());
    }

    @Test
    void SRS_6_4_theTileCountsTheRequestsWaitingAtATierByTypeAndAnEmptyScopeHasNone() {
        when(sanctions.pendingByType(Set.of(300L)))
                .thenReturn(
                        List.of(
                                new Object[] {"ADVANCE", 84L},
                                new Object[] {"PART_FINAL", 44L},
                                new Object[] {"FINAL", 1L}));
        assertThat(service.pending(unit))
                .isEqualTo(new CpfSanctionService.PendingSummary(129, 84, 44, 1));
        AccessContext none =
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
        assertThat(service.pending(none))
                .isEqualTo(new CpfSanctionService.PendingSummary(0, 0, 0, 0));
        when(sanctions.pendingByType(Set.of(300L))).thenReturn(List.of());
        assertThat(service.pending(unit).pending()).isZero();
    }

    @Test
    void BR_CPF_029_theRegisterNamesTheLimitRuleOnceAndFallsBackToItsKey() {
        purpose(6L, "HOUSE", "PART_FINAL", "CPF.LIMIT.MGW.HOUSE");
        purpose(7L, "PLOT", "PART_FINAL", "CPF.LIMIT.MGW.PLOT");
        purpose(8L, "ILLNESS", "ADVANCE", null);
        when(purposes.ruleName("CPF.LIMIT.MGW.HOUSE")).thenReturn("House limit (x MGW)");
        PfSanction first = sanction(61L, Stage.ZONAL_HQ_VALIDATION, "PART_FINAL", 6L);
        PfSanction second = sanction(62L, Stage.ZONAL_HQ_VALIDATION, "PART_FINAL", 6L);
        PfSanction unnamed = sanction(63L, Stage.ZONAL_HQ_VALIDATION, "PART_FINAL", 7L);
        PfSanction none = sanction(64L, Stage.ZONAL_HQ_VALIDATION, "ADVANCE", 8L);
        when(sanctions.inScope(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(first, second, unnamed, none)));
        when(directory.members(any())).thenReturn(Map.of(9L, MEMBER));
        List<SanctionView> rows = service.register(unit, null, new PageSpec(0, 20)).items();
        assertThat(rows)
                .extracting(SanctionView::limitRule)
                .containsExactly(
                        "House limit (x MGW)", "House limit (x MGW)", "CPF.LIMIT.MGW.PLOT", null);
        org.mockito.Mockito.verify(purposes, org.mockito.Mockito.times(1))
                .ruleName("CPF.LIMIT.MGW.HOUSE");
        assertThat(service.get(unit, 61L).limitRule()).isEqualTo("House limit (x MGW)");
    }
}
