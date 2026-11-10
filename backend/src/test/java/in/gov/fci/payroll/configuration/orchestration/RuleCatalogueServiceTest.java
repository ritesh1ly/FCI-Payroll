package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

/**
 * The M-08 branches the API cannot reach with the demo users, or that need a state the demo data
 * does not have (SRS-19-4, SRS-19-14, R-RBAC): authorisation before any read or lock, Headquarters
 * only, a first version needing CREATE, the checks shared by submission and approval, and decisions
 * that are not M-08's. The flows are {@code RuleCatalogueIT}.
 */
class RuleCatalogueServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    private final ConfigurationRepositories.Rules rules =
            mock(ConfigurationRepositories.Rules.class);
    private final ConfigurationRepositories.RuleVersions versions =
            mock(ConfigurationRepositories.RuleVersions.class);
    private final ConfigurationRepositories.RuleSlabBands bands =
            mock(ConfigurationRepositories.RuleSlabBands.class);
    private final RuleProposalChecks checks = mock(RuleProposalChecks.class);
    private final RuleEngine engine = mock(RuleEngine.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext admin = mock(AccessContext.class);

    private final RuleCatalogueService service =
            new RuleCatalogueService(
                    rules,
                    versions,
                    bands,
                    mock(ConfigurationRepositories.Circulars.class),
                    checks,
                    engine,
                    settings,
                    mock(OrgDirectory.class),
                    statuses,
                    approvals,
                    policy,
                    JsonMapper.builder().build(),
                    Clock.fixed(Instant.parse("2026-10-23T05:00:00Z"), ZoneOffset.UTC));

    private static final RuleCatalogueService.VersionRequest REQUEST =
            new RuleCatalogueService.VersionRequest(
                    TODAY, null, BigDecimal.ONE, null, null, null, 7L, "Order 3");

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private static RuleDefinition rule(String key, String type, boolean active) {
        RuleDefinition r = mock(RuleDefinition.class);
        when(r.getId()).thenReturn(5L);
        when(r.getKey()).thenReturn(key);
        when(r.getTypeCode()).thenReturn(type);
        when(r.getDomain()).thenReturn(RuleDefinition.Domain.COMPUTATION);
        when(r.isActive()).thenReturn(active);
        return r;
    }

    // ---------------------------------------------------------------- SRS-19-14 access

    @Test
    void SRS_19_14_aProposalIsAuthorisedBeforeTheRuleIsReadOrLocked() {
        assertThatThrownBy(() -> service.propose(admin, "DA.IDA.RATE", REQUEST, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(CommonErrorCode.ACCESS_DENIED));
        verifyNoInteractions(rules, versions, approvals, checks);
    }

    @Test
    void SRS_19_14_aRuleAdministratorAwayFromHeadquartersProposesNothing() {
        when(policy.can(admin, "M-08", "CREATE")).thenReturn(true);
        assertThatThrownBy(() -> service.propose(admin, "DA.IDA.RATE", REQUEST, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.HQ_ONLY));
        assertThatThrownBy(
                        () ->
                                service.end(
                                        admin,
                                        "DA.IDA.RATE",
                                        new RuleCatalogueService.EndRequest(TODAY, "Withdrawn"),
                                        null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.HQ_ONLY));
        verifyNoInteractions(rules, versions, approvals);
    }

    @Test
    void SRS_19_14_aMissingRequestIsRefusedOnceAuthorised() {
        when(policy.can(admin, "M-08", "EDIT")).thenReturn(true);
        when(policy.isAtHeadquarters(admin)).thenReturn(true);
        assertThatThrownBy(() -> service.propose(admin, "DA.IDA.RATE", null, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.end(admin, "DA.IDA.RATE", null, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(rules, versions, approvals);
    }

    @Test
    void SRS_19_4_theFirstVersionOfARuleNeedsCreateEvenForAnEditor() {
        when(policy.can(admin, "M-08", "EDIT")).thenReturn(true);
        when(policy.isAtHeadquarters(admin)).thenReturn(true);
        RuleDefinition first = rule("CPF.SANCTION.LIMIT", "CEILING", true);
        when(rules.findByKey("CPF.SANCTION.LIMIT")).thenReturn(Optional.of(first));
        when(rules.lockById(5L)).thenReturn(Optional.of(first));
        when(versions.findByRuleIdOrderByVersionNoDesc(5L)).thenReturn(List.of());
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(admin, "M-08", "CREATE");
        assertThatThrownBy(() -> service.propose(admin, "CPF.SANCTION.LIMIT", REQUEST, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(CommonErrorCode.ACCESS_DENIED));
        verifyNoInteractions(approvals, checks);
    }

    @Test
    void SRS_19_1_aRuleNoLongerInUseIsListedButNotMaintained() {
        when(policy.can(admin, "M-08", "EDIT")).thenReturn(true);
        when(policy.isAtHeadquarters(admin)).thenReturn(true);
        RuleDefinition retired = rule("OLD.RATE", "RATE", false);
        when(rules.findByKey("OLD.RATE")).thenReturn(Optional.of(retired));
        assertThatThrownBy(() -> service.propose(admin, "OLD.RATE", REQUEST, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_READ_ONLY));
        verify(rules, never()).lockById(any());
    }

    @Test
    void SRS_6_47_aRegisterShowsItsOwnRulesOnItsOwnViewAndM19TheContributionRates() {
        when(settings.today()).thenReturn(TODAY);
        when(statuses.pending(anyString())).thenReturn(List.of());
        RuleDefinition eps = rule("CPF.EPS.RATE", "RATE", true);
        when(eps.getOwningModule()).thenReturn("M-18");
        RuleDefinition employee = rule("CPF.RATE.EMPLOYEE", "RATE", true);
        when(employee.getId()).thenReturn(6L);
        when(employee.getOwningModule()).thenReturn("M-18");
        RuleDefinition vpf = rule("CPF.VPF.MAX", "CEILING", true);
        when(vpf.getId()).thenReturn(7L);
        when(vpf.getOwningModule()).thenReturn("M-19");
        when(rules.findByDomainInOrderByKey(any())).thenReturn(List.of(eps, employee, vpf));

        assertThat(service.register(admin, "M-18"))
                .extracting(RuleCatalogueService.RuleRow::key)
                .containsExactly("CPF.EPS.RATE", "CPF.RATE.EMPLOYEE");
        assertThat(service.register(admin, "M-19"))
                .extracting(RuleCatalogueService.RuleRow::key)
                .containsExactly("CPF.RATE.EMPLOYEE", "CPF.VPF.MAX");
        verify(policy).require(admin, "M-18", "VIEW");
        verify(policy).require(admin, "M-19", "VIEW");
        verify(policy, never()).require(admin, "M-08", "VIEW");
    }

    @Test
    void SRS_19_21_anEmptyCatalogueReadsNoVersionsAndCountsNothing() {
        when(settings.today()).thenReturn(TODAY);
        when(rules.findByDomainInOrderByKey(any())).thenReturn(List.of());
        when(statuses.pending(anyString())).thenReturn(List.of());
        RuleCatalogueService.Catalogue catalogue = service.catalogue(admin);
        assertThat(catalogue.rules()).isEmpty();
        assertThat(catalogue.summary())
                .isEqualTo(new RuleCatalogueService.Summary(0, java.util.Map.of(), 0, 0, 0));
        // An empty IN list never reaches the database.
        verify(versions, never()).findByRuleIdInAndState(any(), any());
        verify(bands, never()).findByRuleVersionIdIn(any());
        assertThat(service.pending(admin)).isEmpty();
    }

    // ---------------------------------------------------------------- the shared checks

    private final ConfigurationRepositories.Rules checkRules =
            mock(ConfigurationRepositories.Rules.class);
    private final RuleEngine checkEngine = mock(RuleEngine.class);
    private final Circulars circulars = mock(Circulars.class);
    private final RuleProposalChecks realChecks =
            new RuleProposalChecks(checkRules, checkEngine, circulars);

    private static RuleCatalogueService.VersionProposal proposal(
            BigDecimal value, String formula, String rounding, Long circular) {
        return new RuleCatalogueService.VersionProposal(
                "K",
                "RATE",
                TODAY,
                null,
                value,
                formula,
                null,
                rounding,
                circular,
                "admin",
                Instant.EPOCH);
    }

    @Test
    void SRS_19_1_aTypeWithoutAValueShapeIsNeverCheckedAsAVersion() {
        RuleDefinition rounding = rule("COMPONENT.ROUNDING", "ROUNDING", true);
        assertThatThrownBy(
                        () ->
                                realChecks.check(
                                        rounding,
                                        List.of(),
                                        proposal(BigDecimal.ONE, null, null, 7L)))
                .satisfies(
                        t -> {
                            assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_READ_ONLY);
                            assertThat(((FciException) t).getMessageArgs())
                                    .containsExactly("COMPONENT.ROUNDING");
                        });
        verifyNoInteractions(circulars);
    }

    @Test
    void SRS_19_1_aRoundingSettingNamesAnActiveRoundingRule() {
        RuleDefinition rate = rule("DA.IDA.RATE", "RATE", true);
        RuleDefinition activeRounding = rule("COMPONENT.ROUNDING", "ROUNDING", true);
        RuleDefinition inactiveRounding = rule("OLD.ROUNDING", "ROUNDING", false);
        when(checkRules.findByKey("COMPONENT.ROUNDING")).thenReturn(Optional.of(activeRounding));
        when(checkRules.findByKey("OLD.ROUNDING")).thenReturn(Optional.of(inactiveRounding));
        when(checkRules.findByKey("DA.IDA.RATE")).thenReturn(Optional.of(rate));
        when(checkRules.findByKey("NOWHERE")).thenReturn(Optional.empty());
        for (String refused : List.of("OLD.ROUNDING", "DA.IDA.RATE", "NOWHERE")) {
            assertThatThrownBy(
                            () ->
                                    realChecks.check(
                                            rate,
                                            List.of(),
                                            proposal(BigDecimal.ONE, null, refused, 7L)))
                    .as(refused)
                    .satisfies(
                            t -> {
                                assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_VALUE_INVALID);
                                assertThat(((FciException) t).getMessageArgs()[0])
                                        .isEqualTo("no active ROUNDING rule " + refused);
                            });
        }
        verifyNoInteractions(circulars);
        realChecks.check(rate, List.of(), proposal(BigDecimal.ONE, null, "COMPONENT.ROUNDING", 7L));
        verify(circulars).requireActive(7L);
        // A scalar rule never reaches the formula grammar.
        verifyNoInteractions(checkEngine);
    }

    @Test
    void SRS_19_17_aVersionWithoutACircularIsRefusedBeforeTheMasterIsAsked() {
        RuleDefinition rate = rule("DA.IDA.RATE", "RATE", true);
        assertThatThrownBy(
                        () ->
                                realChecks.check(
                                        rate,
                                        List.of(),
                                        proposal(BigDecimal.ONE, null, null, null)))
                .satisfies(
                        t -> {
                            assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_VALUE_INVALID);
                            assertThat(((FciException) t).getMessageArgs()[0])
                                    .isEqualTo("the linked circular or order is mandatory");
                        });
        verifyNoInteractions(circulars);
    }

    @Test
    void SRS_19_15_aFormulaIsCheckedForTheKindOfResultItsTypeNeeds() {
        realChecks.check(
                rule("TA.FORMULA", "FORMULA", true),
                List.of(),
                proposal(null, "BASIC * 2", null, 7L));
        verify(checkEngine).checkFormula("BASIC * 2", false);
        realChecks.check(
                rule("CEA.ELIGIBLE", "ELIGIBILITY", true),
                List.of(),
                proposal(null, "BASIC > 1", null, 7L));
        verify(checkEngine).checkFormula("BASIC > 1", true);
    }

    @Test
    void SRS_19_5_aProposalWithoutBandsCarriesAnEmptyListNotNull() {
        assertThat(proposal(BigDecimal.ONE, null, null, 7L).bands()).isEmpty();
        List<RuleCatalogueService.Band> mutable = new java.util.ArrayList<>();
        mutable.add(
                new RuleCatalogueService.Band(
                        1, BigDecimal.ZERO, null, BigDecimal.ONE, null, null));
        RuleCatalogueService.VersionProposal p =
                new RuleCatalogueService.VersionProposal(
                        "K",
                        "SLAB",
                        TODAY,
                        null,
                        null,
                        null,
                        mutable,
                        null,
                        7L,
                        "admin",
                        Instant.EPOCH);
        mutable.clear();
        // A copy: what the approver sees cannot change after submission.
        assertThat(p.bands()).hasSize(1);
    }

    @Test
    void SRS_19_4_aStoredProposalStillReadsAfterAFieldIsRetired() {
        // The API refuses unknown fields; a payload stored by an earlier release must still read.
        tools.jackson.databind.ObjectMapper strict =
                JsonMapper.builder()
                        .enable(
                                tools.jackson.databind.DeserializationFeature
                                        .FAIL_ON_UNKNOWN_PROPERTIES)
                        .build();
        String stored =
                "{\"ruleKey\":\"K\",\"ruleType\":\"RATE\",\"effectiveFrom\":\"2026-10-23\","
                        + "\"value\":5,\"circularId\":7,\"proposedBy\":\"admin\","
                        + "\"proposedAt\":\"2026-10-23T05:00:00Z\",\"retiredField\":\"x\"}";
        RuleCatalogueService.VersionProposal p =
                strict.readValue(stored, RuleCatalogueService.VersionProposal.class);
        assertThat(p.value()).isEqualByComparingTo("5");
        assertThat(p.bands()).isEmpty();
        RuleCatalogueService.EndProposal end =
                strict.readValue(
                        "{\"ruleKey\":\"K\",\"versionId\":3,\"versionNo\":1,\"endOn\":\"2026-12-31\","
                            + "\"proposedBy\":\"admin\",\"proposedAt\":\"2026-10-23T05:00:00Z\","
                            + "\"retiredField\":1}",
                        RuleCatalogueService.EndProposal.class);
        assertThat(end.endOn()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    // ---------------------------------------------------------------- the decision listener

    @Test
    void SRS_19_4_onlyAnApprovedM08DecisionIsApplied() {
        RuleCatalogueService catalogue = mock(RuleCatalogueService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        RuleCatalogueApprovalEffect effect =
                new RuleCatalogueApprovalEffect(
                        catalogue,
                        checks,
                        versions,
                        bands,
                        circulars,
                        mock(MasterAudit.class),
                        settings,
                        events,
                        JsonMapper.builder().build());
        Instant at = Instant.EPOCH;
        // A-07 shares the table: its decisions are not M-08's.
        effect.on(
                new ApprovalDecided(
                        1L,
                        "A-07",
                        "rule_version",
                        "SESSION_IDLE",
                        ApprovalStatus.APPROVED,
                        null,
                        "{}",
                        "c",
                        at));
        // Returned or rejected: nothing was written, nothing is undone.
        effect.on(
                new ApprovalDecided(
                        2L,
                        "M-08",
                        "rule_version",
                        "K",
                        ApprovalStatus.RETURNED,
                        "fix",
                        "{}",
                        "c",
                        at));
        effect.on(
                new ApprovalDecided(
                        3L,
                        "M-08",
                        "rule_version_end",
                        "K",
                        ApprovalStatus.REJECTED,
                        "no",
                        "{}",
                        "c",
                        at));
        // An M-08 item of another kind is not a rule version.
        effect.on(
                new ApprovalDecided(
                        4L,
                        "M-08",
                        "rule_simulation",
                        "K",
                        ApprovalStatus.APPROVED,
                        null,
                        "{}",
                        "c",
                        at));
        verifyNoInteractions(catalogue, versions, bands, circulars, events);
    }

    private static RuleVersion approvedV(int no, LocalDate from, LocalDate to) {
        return RuleVersion.approved(
                1L, no, from, to, BigDecimal.ONE, null, null, 7L, "admin", 10L + no, Instant.EPOCH);
    }

    @Test
    void FRS_24_9_aVersionNamesTheApprovedVersionItSupersededTheDayBeforeItStarted() {
        LocalDate start = LocalDate.of(2026, 4, 1);
        RuleVersion v1 = approvedV(1, LocalDate.of(2025, 4, 1), null);
        RuleVersion v2 = approvedV(2, start, null);
        v1.endBefore(start);
        // v2 superseded v1 (ended the day before v2 starts); the first version superseded none.
        assertThat(RuleCatalogueService.supersedes(v2, List.of(v2, v1))).isEqualTo(1);
        assertThat(RuleCatalogueService.supersedes(v1, List.of(v2, v1))).isNull();
        // After an end without successor the gap is not closed by the next version: none.
        RuleVersion ended = approvedV(1, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 1, 31));
        RuleVersion later = approvedV(2, start, null);
        assertThat(RuleCatalogueService.supersedes(later, List.of(later, ended))).isNull();
        // Boundary: ending two days before is not "the day before"; ending on the start is not
        // either (never both in force).
        RuleVersion twoBefore = approvedV(1, LocalDate.of(2025, 4, 1), start.minusDays(2));
        RuleVersion onStart = approvedV(1, LocalDate.of(2025, 4, 1), start);
        assertThat(RuleCatalogueService.supersedes(later, List.of(later, twoBefore))).isNull();
        assertThat(RuleCatalogueService.supersedes(later, List.of(later, onStart))).isNull();
        // A later-numbered version is never the one superseded, and an unapproved one supersedes
        // nothing.
        RuleVersion v3 = approvedV(3, LocalDate.of(2025, 4, 1), start.minusDays(1));
        assertThat(RuleCatalogueService.supersedes(v2, List.of(v3, v2))).isNull();
        RuleVersion pending =
                RuleVersion.propose(1L, 4, start, BigDecimal.ONE, "admin", 99L, Instant.EPOCH);
        assertThat(RuleCatalogueService.supersedes(pending, List.of(pending, v1))).isNull();
        // Of two candidates the higher-numbered approved one is named.
        RuleVersion a = approvedV(1, LocalDate.of(2024, 4, 1), start.minusDays(1));
        RuleVersion b = approvedV(2, LocalDate.of(2025, 4, 1), start.minusDays(1));
        RuleVersion c = approvedV(3, start, null);
        assertThat(RuleCatalogueService.supersedes(c, List.of(c, a, b))).isEqualTo(2);
        assertThat(RuleCatalogueService.supersedes(c, List.of(c, b, a))).isEqualTo(2);
    }
}
