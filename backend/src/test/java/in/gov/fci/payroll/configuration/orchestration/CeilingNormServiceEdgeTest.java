package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.AllowanceCeilingNorm;
import in.gov.fci.payroll.configuration.business.model.CircularOrderLink;
import in.gov.fci.payroll.configuration.orchestration.CeilingNormService.NormRow;
import in.gov.fci.payroll.configuration.orchestration.CeilingNormService.NormVersion;
import in.gov.fci.payroll.configuration.orchestration.CeilingNormService.ProposedNorm;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M-15 ceiling norms beyond the happy path (SRS-16-14): a claim type the reference list no longer
 * carries, a version without an approval, what a proposal must carry and the defaults it takes,
 * where it is routed from outside Headquarters, and the approval that ends the version in force (or
 * finds none to end) and is applied only for this master.
 */
class CeilingNormServiceEdgeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final LocalDate FROM = LocalDate.of(2027, 4, 1);
    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final ConfigurationRepositories.CeilingNorms norms =
            mock(ConfigurationRepositories.CeilingNorms.class);
    private final ReferenceData reference = mock(ReferenceData.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final MasterAudit audit = mock(MasterAudit.class);
    private final Circulars circulars = mock(Circulars.class);
    private final ConfigurationRepositories.CircularLinks links =
            mock(ConfigurationRepositories.CircularLinks.class);

    private final CeilingNormService service =
            new CeilingNormService(
                    norms, reference, statuses, approvals, policy, settings, audit, circulars,
                    links);

    private final AccessContext headquarters = context(1L);
    private final AccessContext zonal = context(300L);

    CeilingNormServiceEdgeTest() {
        when(settings.today()).thenReturn(TODAY);
        when(policy.isAtHeadquarters(headquarters)).thenReturn(true);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
        when(norms.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(statuses.of(any())).thenReturn(Map.of());
        when(reference.contains("CLAIM_TYPE", "LABOUR_TA")).thenReturn(true);
        when(reference.contains("ALLOWANCE_PERIODICITY", "MONTHLY")).thenReturn(true);
        when(reference.contains("ELIGIBILITY_CATEGORY", "DEMO_ALL")).thenReturn(true);
        when(reference.contains("CEILING_BASIS", "PER_DAY")).thenReturn(true);
    }

    private static AccessContext context(Long locationId) {
        return new AccessContext(
                2L,
                "admin@x",
                "Admin",
                "s",
                "P",
                locationId,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(locationId));
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

    private static AllowanceCeilingNorm norm(
            String claimType, int versionNo, LocalDate from, Long approvalId) {
        return AllowanceCeilingNorm.propose(
                claimType,
                null,
                new BigDecimal("500"),
                "MONTHLY",
                "DEMO_ALL",
                "BOTH",
                versionNo,
                from,
                true,
                approvalId);
    }

    private static ProposedNorm request(String basis, String eligibility, String scope) {
        return new ProposedNorm(
                FROM, basis, new BigDecimal("900"), "MONTHLY", eligibility, scope, true, "r", 41L);
    }

    @Test
    void
            SRS_16_14_aClaimTypeTheListNoLongerCarriesStillListsAndAVersionWithoutApprovalHasNoStatus() {
        when(reference.list("CLAIM_TYPE"))
                .thenReturn(
                        Optional.of(
                                new ReferenceData.ListView(
                                        "CLAIM_TYPE",
                                        "Claim type",
                                        "M-15",
                                        false,
                                        List.of(
                                                new ReferenceData.Value(
                                                        "LABOUR_TA",
                                                        "Labour TA",
                                                        null,
                                                        1,
                                                        null,
                                                        null,
                                                        true)))));
        AllowanceCeilingNorm food = norm("FOOD_TA", 1, LocalDate.of(2026, 4, 1), 5L);
        AllowanceCeilingNorm orphan = norm("FOOD_TA", 0, LocalDate.of(2025, 4, 1), null);
        when(norms.findAllByOrderByClaimTypeAscVersionNoDesc()).thenReturn(List.of(food));
        when(norms.findByClaimTypeOrderByVersionNoDesc("FOOD_TA"))
                .thenReturn(List.of(food, orphan));
        when(statuses.of(any())).thenReturn(Map.of(5L, ApprovalStatus.APPROVED));

        List<NormRow> rows = service.list(headquarters);
        assertThat(rows)
                .extracting(NormRow::claimType, NormRow::name)
                .containsExactly(tuple("LABOUR_TA", "Labour TA"), tuple("FOOD_TA", "FOOD_TA"));
        assertThat(rows.get(0).current()).isNull();
        assertThat(rows.get(1).current().ceilingValue()).isEqualByComparingTo("500");
        assertThat(service.history(headquarters, "FOOD_TA"))
                .extracting(NormVersion::versionNo, NormVersion::status)
                .containsExactly(tuple(1, "APPROVED"), tuple(0, "NONE"));
    }

    @Test
    void SRS_16_14_aProposalNeedsADateAKnownBasisAndEligibilityAndCoversBothMembersUnlessSaid() {
        refused(
                () -> service.propose(headquarters, "LABOUR_TA", null, null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.propose(
                                headquarters,
                                "LABOUR_TA",
                                new ProposedNorm(
                                        null,
                                        null,
                                        BigDecimal.ONE,
                                        "MONTHLY",
                                        "DEMO_ALL",
                                        null,
                                        true,
                                        "r",
                                        41L),
                                null),
                CommonErrorCode.VALIDATION_FAILED);
        assertThat(
                        argsOf(
                                () ->
                                        service.propose(
                                                headquarters,
                                                "LABOUR_TA",
                                                request("BOGUS", "DEMO_ALL", null),
                                                null)))
                .containsExactly("CEILING_BASIS", "BOGUS");
        // SRS l.1421: a DDL value M-16 does not list is refused (OD-123, M16-CONSUMERS).
        assertThat(
                        argsOf(
                                () ->
                                        service.propose(
                                                headquarters,
                                                "LABOUR_TA",
                                                new ProposedNorm(
                                                        FROM,
                                                        null,
                                                        new BigDecimal("900"),
                                                        "QUARTERLY",
                                                        "DEMO_ALL",
                                                        null,
                                                        true,
                                                        "r",
                                                        41L),
                                                null)))
                .containsExactly("ALLOWANCE_PERIODICITY", "QUARTERLY");
        assertThat(
                        argsOf(
                                () ->
                                        service.propose(
                                                headquarters,
                                                "LABOUR_TA",
                                                request(null, null, "LABOUR"),
                                                null)))
                .containsExactly("ELIGIBILITY_CATEGORY", "");
        refused(
                () ->
                        service.propose(
                                headquarters, "LABOUR_TA", request(null, null, "LABOUR"), null),
                ApiErrorCode.MASTER_UNKNOWN_VALUE);

        NormVersion both =
                service.propose(
                        headquarters, "LABOUR_TA", request("PER_DAY", "DEMO_ALL", "  "), null);
        assertThat(both.memberScope()).isEqualTo("BOTH");
        assertThat(both.ceilingBasis()).isEqualTo("PER_DAY");
        assertThat(both.status()).isEqualTo("PENDING_APPROVAL");
        NormVersion labour =
                service.propose(zonal, "LABOUR_TA", request(null, "DEMO_ALL", "LABOUR"), null);
        assertThat(labour.memberScope()).isEqualTo("LABOUR");
        assertThat(labour.ceilingBasis()).isNull();
        // Routed to Headquarters' queue from there, to no office from elsewhere.
        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals, times(2)).submit(any(), submitted.capture(), isNull());
        assertThat(submitted.getAllValues())
                .extracting(Submission::locationId)
                .containsExactly(1L, null);
    }

    @Test
    void SRS_G_38_aNormRevisionIsLinkedToItsActiveCircularOrNotSubmitted() {
        // US-GAP-211: without an Active M-25 circular nothing is submitted or saved.
        doThrow(new FciException(ApiErrorCode.CIRCULAR_NOT_ACTIVE))
                .when(circulars)
                .requireActive(99L);
        ProposedNorm withoutActive =
                new ProposedNorm(
                        FROM,
                        null,
                        new BigDecimal("900"),
                        "MONTHLY",
                        "DEMO_ALL",
                        null,
                        true,
                        "r",
                        99L);
        refused(
                () -> service.propose(headquarters, "LABOUR_TA", withoutActive, null),
                ApiErrorCode.CIRCULAR_NOT_ACTIVE);
        verify(approvals, never()).submit(any(), any(), any());
        verify(norms, never()).save(any());

        // With it, the version is linked as an M-15 master entry and shows its circular.
        NormVersion linked =
                service.propose(headquarters, "LABOUR_TA", request(null, "DEMO_ALL", null), null);
        verify(circulars).requireActive(41L);
        verify(circulars)
                .link(
                        eq("admin@x"),
                        eq(41L),
                        eq(Circulars.LinkType.MASTER_ENTRY),
                        eq(CeilingNormService.ENTITY),
                        any(),
                        eq("M-15"));
        assertThat(linked.circularOrderId()).isEqualTo(41L);
    }

    @Test
    void SRS_G_38_theHistoryShowsTheCircularOfEachVersionAndNoneForACancelledLink() {
        AllowanceCeilingNorm first = norm("LABOUR_TA", 1, LocalDate.of(2026, 4, 1), 5L);
        AllowanceCeilingNorm second = norm("LABOUR_TA", 2, FROM, 6L);
        org.springframework.test.util.ReflectionTestUtils.setField(first, "ceilingNormId", 11L);
        org.springframework.test.util.ReflectionTestUtils.setField(second, "ceilingNormId", 12L);
        when(norms.findByClaimTypeOrderByVersionNoDesc("LABOUR_TA"))
                .thenReturn(List.of(second, first));
        CircularOrderLink active =
                CircularOrderLink.link(
                        41L,
                        CircularOrderLink.Type.MASTER_ENTRY,
                        CeilingNormService.ENTITY,
                        "11",
                        "M-15",
                        "admin@x",
                        NOW);
        CircularOrderLink cancelled =
                CircularOrderLink.link(
                        42L,
                        CircularOrderLink.Type.MASTER_ENTRY,
                        CeilingNormService.ENTITY,
                        "12",
                        "M-15",
                        "admin@x",
                        NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(
                cancelled, "linkStatus", "CANCELLED");
        when(links.findByLinkedEntity(CeilingNormService.ENTITY))
                .thenReturn(List.of(active, cancelled));
        List<NormVersion> history = service.history(headquarters, "LABOUR_TA");
        assertThat(history).extracting(NormVersion::circularOrderId).containsExactly(null, 41L);
    }

    @Test
    void SRS_16_14_anApprovalEndsTheVersionInForceOrFindsNoneAndIsAppliedOnlyForThisMaster() {
        AllowanceCeilingNorm current = norm("LABOUR_TA", 1, LocalDate.of(2026, 4, 1), 5L);
        AllowanceCeilingNorm proposed = norm("LABOUR_TA", 2, FROM, 90L);
        when(norms.findByApprovalId(90L)).thenReturn(Optional.of(proposed));
        when(norms.findByClaimTypeOrderByVersionNoDesc("LABOUR_TA"))
                .thenReturn(List.of(proposed, current));
        when(statuses.of(any()))
                .thenReturn(Map.of(5L, ApprovalStatus.APPROVED, 90L, ApprovalStatus.APPROVED));
        ApprovalDecided approved =
                decided(CeilingNormService.ENTITY, ApprovalStatus.APPROVED, 90L, "ok");

        service.on(approved);
        assertThat(current.getEffectiveTo()).isEqualTo(LocalDate.of(2027, 3, 31));
        verify(audit)
                .applied(
                        eq(approved),
                        eq(CeilingNormService.ENTITY),
                        eq("LABOUR_TA"),
                        eq("M-15"),
                        eq(FROM),
                        notNull(),
                        notNull(),
                        eq(List.of(LocalDate.of(2027, 3, 31))),
                        eq("ok"));

        when(norms.findByClaimTypeOrderByVersionNoDesc("LABOUR_TA")).thenReturn(List.of(proposed));
        service.on(approved);
        verify(audit)
                .applied(
                        eq(approved),
                        eq(CeilingNormService.ENTITY),
                        eq("LABOUR_TA"),
                        eq("M-15"),
                        eq(FROM),
                        isNull(),
                        notNull(),
                        eq(List.of()),
                        eq("ok"));

        service.on(decided("net_pay_floor_rule", ApprovalStatus.APPROVED, 91L, "ok"));
        service.on(decided(CeilingNormService.ENTITY, ApprovalStatus.REJECTED, 92L, "no"));
        verify(norms, never()).findByApprovalId(91L);
        // Wave D review 6: a rejected item without a norm cancels nothing.
        verify(circulars, never())
                .cancelLinks(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * Wave D review 6 (SRS-G-38): a norm version rejected or returned keeps no Active M-25 link; an
     * approved one keeps it.
     */
    @Test
    void SRS_G_38_aRejectedOrReturnedNormCancelsItsCircularLink() {
        AllowanceCeilingNorm proposed = norm("LABOUR_TA", 2, FROM, 93L);
        org.springframework.test.util.ReflectionTestUtils.setField(proposed, "ceilingNormId", 44L);
        when(norms.findByApprovalId(93L)).thenReturn(Optional.of(proposed));
        service.on(decided(CeilingNormService.ENTITY, ApprovalStatus.REJECTED, 93L, "no"));
        verify(circulars)
                .cancelLinks(
                        "checker.hq",
                        CeilingNormService.ENTITY,
                        "44",
                        "M-15",
                        "Norm version rejected");
        service.on(decided(CeilingNormService.ENTITY, ApprovalStatus.RETURNED, 93L, "fix"));
        verify(circulars)
                .cancelLinks(
                        "checker.hq",
                        CeilingNormService.ENTITY,
                        "44",
                        "M-15",
                        "Norm version returned");
        when(norms.findByClaimTypeOrderByVersionNoDesc("LABOUR_TA")).thenReturn(List.of(proposed));
        when(statuses.of(any())).thenReturn(Map.of(93L, ApprovalStatus.APPROVED));
        service.on(decided(CeilingNormService.ENTITY, ApprovalStatus.APPROVED, 93L, "ok"));
        verify(circulars, org.mockito.Mockito.times(2))
                .cancelLinks(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private static ApprovalDecided decided(
            String entity, ApprovalStatus outcome, long id, String reason) {
        return new ApprovalDecided(
                id, "M-15", entity, "LABOUR_TA", outcome, reason, "{}", "checker.hq", NOW, 7L);
    }

    /** The drawn M-15 tiles: a type without a norm counts in none; no pending, no percentage. */
    @org.junit.jupiter.api.Test
    void SRS_16_14_theTilesCountTheNormsAndAnEmptyCatalogueReadsZero() {
        when(norms.findAllByOrderByClaimTypeAscVersionNoDesc()).thenReturn(List.of());
        CeilingNormService.NormCounts counts = service.counts(headquarters);
        org.assertj.core.api.Assertions.assertThat(counts.claimTypes()).isZero();
        org.assertj.core.api.Assertions.assertThat(counts.percentageRules()).isZero();
        org.assertj.core.api.Assertions.assertThat(counts.pendingApproval()).isZero();
    }
}
