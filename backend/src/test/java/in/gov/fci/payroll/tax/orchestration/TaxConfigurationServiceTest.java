package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ActionType;
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
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.ItSection;
import in.gov.fci.payroll.tax.business.model.PtState;
import in.gov.fci.payroll.tax.orchestration.TaxConfigurationService.SectionProposal;
import in.gov.fci.payroll.tax.orchestration.TaxConfigurationService.StateProposal;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * M-18 tax masters beyond the integration path (SRS-7-2, SRS-7-4, SRS-7-20; BR-TAX-006): who may
 * propose and where, what a proposal must carry, and the decisions the module ignores or checks
 * again before it writes.
 */
class TaxConfigurationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");
    private static final LocalDate APRIL = LocalDate.of(2027, 4, 1);

    private final TaxRepositories.Sections sections = mock(TaxRepositories.Sections.class);
    private final TaxRepositories.PtStates states = mock(TaxRepositories.PtStates.class);
    private final PtStates cached = mock(PtStates.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private final TaxConfigurationService service =
            new TaxConfigurationService(
                    sections,
                    states,
                    cached,
                    approvals,
                    statuses,
                    policy,
                    audit,
                    json,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final AccessContext headquarters = context(1L);
    private final AccessContext zonal = context(300L);

    TaxConfigurationServiceTest() {
        when(policy.isAtHeadquarters(headquarters)).thenReturn(true);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
        when(statuses.pending(any())).thenReturn(List.of());
    }

    private static AccessContext context(Long locationId) {
        return new AccessContext(
                2L,
                "ra.hq",
                "Rule Administrator",
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

    private static SectionProposal section(String description) {
        return new SectionProposal("80C", description, null, "OLD", APRIL, null, null);
    }

    private static StateProposal state(boolean applicable) {
        return new StateProposal(
                "MH", "Maharashtra", applicable, " ", null, APRIL, null, null, null);
    }

    private ApprovalDecided decided(
            String module, String entity, ApprovalStatus outcome, Object p) {
        return new ApprovalDecided(
                90L,
                module,
                entity,
                "MH",
                outcome,
                null,
                p == null ? null : json.writeValueAsString(p),
                "approver.hq",
                NOW);
    }

    @Test
    void SRS_7_20_aSectionIsProposedOnlyAtHeadquartersAndWithADescription() {
        refused(
                () -> service.proposeSection(zonal, section("Life insurance"), "r", null),
                ApiErrorCode.HQ_ONLY);
        refused(
                () -> service.proposeSection(headquarters, section(null), "r", null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.proposeSection(headquarters, section("  "), "r", null),
                CommonErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_7_20_aSectionProposalIsRoutedOnItsOwnWorkflowActionStampedAndStripped() {
        var pending = service.proposeSection(headquarters, section(" Life insurance "), "r", null);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(headquarters), sent.capture(), any());
        assertThat(sent.getValue().workflowAction()).isEqualTo("M18_SECTION");
        assertThat(sent.getValue().entityName()).isEqualTo("it_section_master");
        assertThat(pending.approvalId()).isEqualTo(77L);
        assertThat(pending.proposal().description()).isEqualTo("Life insurance");
        assertThat(pending.proposal().proposedBy()).isEqualTo("ra.hq");
        assertThat(pending.proposal().proposedAt()).isEqualTo(NOW);
    }

    @Test
    void SRS_7_26_oneOpenProposalPerSectionOrState() {
        when(statuses.pending("it_section_master"))
                .thenReturn(
                        List.of(
                                new ApprovalStatuses.Pending(5L, "80D", "{}", "x", NOW),
                                new ApprovalStatuses.Pending(6L, "80C", "{}", "x", NOW)));
        assertThatThrownBy(() -> service.proposeSection(headquarters, section("Life"), "r", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ApiErrorCode.REFERENCE_CHANGE_PENDING);
                            assertThat(e.getMessageArgs()).containsExactly("80C", 6L);
                        });
    }

    @Test
    void SRS_7_4_aStateIsCreatedOnceAndEditedOnlyWhenItExists() {
        when(states.findByStateCode("MH")).thenReturn(Optional.empty());
        refused(
                () -> service.proposeState(headquarters, true, state(true), "r", null),
                CommonErrorCode.NOT_FOUND);
        refused(
                () -> service.proposeState(zonal, false, state(true), "r", null),
                ApiErrorCode.HQ_ONLY);
        var created = service.proposeState(headquarters, false, state(true), "r", null);
        assertThat(created.proposal().registrationNumber()).isNull();

        when(states.findByStateCode("MH")).thenReturn(Optional.of(PtState.of("MH")));
        refused(
                () -> service.proposeState(headquarters, false, state(true), "r", null),
                ApiErrorCode.REFERENCE_CODE_TAKEN);
        service.proposeState(headquarters, true, state(false), "r", null);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals, org.mockito.Mockito.times(2)).submit(any(), sent.capture(), any());
        assertThat(sent.getAllValues().get(0).actionType()).isEqualTo(ActionType.CREATE);
        assertThat(sent.getAllValues().get(1).actionType()).isEqualTo(ActionType.UPDATE);
        assertThat(sent.getAllValues().get(1).actionCode()).isEqualTo("EDIT");
        assertThat(sent.getAllValues().get(1).workflowAction()).isEqualTo("M18_PT_STATE");
        // The edit of an unknown state and the edit sent each checked EDIT first.
        verify(policy, org.mockito.Mockito.times(2)).require(headquarters, "M-18", "EDIT");
    }

    @Test
    void SRS_G_1_otherScreensOtherEntitiesAndRejectionsWriteNothing() {
        service.on(decided("M-19", "it_section_master", ApprovalStatus.APPROVED, null));
        service.on(decided("M-18", "it_section_master", ApprovalStatus.REJECTED, null));
        service.on(decided("M-18", "rule_version", ApprovalStatus.APPROVED, null));
        verifyNoInteractions(sections, states, audit);
    }

    @Test
    void SRS_7_20_anApprovalIsCheckedAgainstAVersionApprovedMeanwhile() {
        ItSection later = ItSection.approved("80C", "Life", null, "OLD", APRIL.plusYears(1));
        when(sections.findBySectionCodeOrderByEffectiveFromDesc("80C")).thenReturn(List.of(later));
        refused(
                () ->
                        service.on(
                                decided(
                                        "M-18",
                                        "it_section_master",
                                        ApprovalStatus.APPROVED,
                                        section("Life"))),
                TaxErrorCode.SECTION_ORDER);
        verify(sections, never()).save(any());
    }

    @Test
    void SRS_7_20_anApprovedVersionEndsOnlyTheVersionsStillOpenOnItsStart() {
        ItSection closed = ItSection.approved("80C", "Life", null, "OLD", APRIL.minusYears(2));
        closed.endBefore(APRIL.minusYears(1));
        ItSection open =
                ItSection.approved(
                        "80C", "Life", new BigDecimal("150000"), "OLD", APRIL.minusYears(1));
        when(sections.findBySectionCodeOrderByEffectiveFromDesc("80C"))
                .thenReturn(List.of(open, closed));
        SectionProposal limited =
                new SectionProposal(
                        "80C", "Life", new BigDecimal("200000.5"), "NEW", APRIL, "a", NOW);
        service.on(decided("M-18", "it_section_master", ApprovalStatus.APPROVED, limited));
        assertThat(open.getEffectiveTo()).isEqualTo(APRIL.minusDays(1));
        assertThat(closed.getEffectiveTo()).isEqualTo(APRIL.minusYears(1).minusDays(1));
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("approver.hq"), entry.capture());
        assertThat(entry.getValue().newValue()).isEqualTo("2027-04-01 limit 200000.5; regime NEW");
        assertThat(entry.getValue().reason()).isEqualTo("M-18 approved");
        assertThat(entry.getValue().approvalId()).isEqualTo(90L);
    }

    @Test
    void BR_TAX_006_anApprovedStateCarriesThePtSlabKeyOnlyWherePtApplies() {
        when(states.findByStateCode("MH")).thenReturn(Optional.empty());
        ArgumentCaptor<PtState> saved = ArgumentCaptor.forClass(PtState.class);
        service.on(decided("M-18", "pt_state_master", ApprovalStatus.APPROVED, state(true)));
        verify(states).save(saved.capture());
        verify(cached).changed("approver.hq");
        assertThat(saved.getValue().getSlabRuleKey()).isEqualTo("PT.SLAB");
        assertThat(saved.getValue().inForceOn(APRIL)).isTrue();
        assertThat(saved.getValue().inForceOn(APRIL.minusDays(1))).isFalse();
        verify(audit).recordAll(eq("approver.hq"), anyList());

        PtState existing = saved.getValue();
        when(states.findByStateCode("MH")).thenReturn(Optional.of(existing));
        service.on(decided("M-18", "pt_state_master", ApprovalStatus.APPROVED, state(false)));
        assertThat(existing.getSlabRuleKey()).isNull();
        assertThat(existing.isPtApplicable()).isFalse();
        assertThat(existing.image()).containsEntry("pt_applicable_flag", "N");
    }

    /**
     * SRS-7-20: a version already ended on or after the new start (its end on the start day, the
     * boundary) still overlaps and ends the day before; the Rule Approver's own reason is audited.
     */
    @Test
    void SRS_7_20_aVersionEndingOnTheNewStartIsEndedTheDayBeforeWithTheApproversReason() {
        ItSection endsOnStart = ItSection.approved("80C", "Life", null, "OLD", APRIL.minusYears(1));
        endsOnStart.endBefore(APRIL.plusDays(1));
        assertThat(endsOnStart.getEffectiveTo()).isEqualTo(APRIL);
        when(sections.findBySectionCodeOrderByEffectiveFromDesc("80C"))
                .thenReturn(List.of(endsOnStart));
        service.on(
                new ApprovalDecided(
                        91L,
                        "M-18",
                        "it_section_master",
                        "80C",
                        ApprovalStatus.APPROVED,
                        "Finance Act 2027",
                        json.writeValueAsString(section("Life")),
                        "approver.hq",
                        NOW));
        assertThat(endsOnStart.getEffectiveTo()).isEqualTo(APRIL.minusDays(1));
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("approver.hq"), entry.capture());
        assertThat(entry.getValue().reason()).isEqualTo("Finance Act 2027");
        assertThat(entry.getValue().newValue()).isEqualTo("2027-04-01 limit none; regime OLD");
    }
}
