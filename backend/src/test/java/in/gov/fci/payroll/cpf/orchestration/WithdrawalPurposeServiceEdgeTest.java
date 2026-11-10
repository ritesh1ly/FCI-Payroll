package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.model.CpfWithdrawalPurpose;
import in.gov.fci.payroll.cpf.orchestration.WithdrawalPurposeService.DocumentView;
import in.gov.fci.payroll.cpf.orchestration.WithdrawalPurposeService.Pending;
import in.gov.fci.payroll.cpf.orchestration.WithdrawalPurposeService.Proposal;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-19 withdrawal purposes beyond the happy path (SRS-6-5, SRS-6-14, SRS-6-39): who may propose and
 * what a proposal must carry, a checklist without lines, with an empty line or a line without a
 * tier, and the approval that ends every open version before the new one and records the checker's
 * reason or a default.
 */
class WithdrawalPurposeServiceEdgeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2027, 4, 1);
    private static final String KEY = "CPF.LIMIT.MGW.MARRIAGE";

    private final CpfRepositories.Purposes purposes = mock(CpfRepositories.Purposes.class);
    private final CpfRepositories.PurposeDocuments documents =
            mock(CpfRepositories.PurposeDocuments.class);
    private final DocumentStore store = mock(DocumentStore.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private final WithdrawalPurposeService service =
            new WithdrawalPurposeService(
                    purposes,
                    documents,
                    store,
                    approvals,
                    mock(ApprovalStatuses.class),
                    policy,
                    audit,
                    json,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final AccessContext headquarters = context(1L, "ra.hq");
    private final AccessContext zonal = context(300L, "ra.north");

    WithdrawalPurposeServiceEdgeTest() {
        when(policy.isAtHeadquarters(headquarters)).thenReturn(true);
        when(store.types())
                .thenReturn(
                        List.of(new DocumentStore.TypeView("CERTIFICATE", "Certificate", false)));
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
    }

    private static AccessContext context(Long locationId, String login) {
        return new AccessContext(
                2L,
                login,
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

    private static Proposal proposal(
            String name, String category, String limit, List<DocumentView> docs) {
        return new Proposal(
                "MARRIAGE", name, category, limit, 2, true, false, FROM, true, docs, null, null);
    }

    private static CpfWithdrawalPurpose version(LocalDate from, LocalDate to) {
        CpfWithdrawalPurpose v = mock(CpfWithdrawalPurpose.class);
        when(v.getEffectiveFrom()).thenReturn(from);
        when(v.getEffectiveTo()).thenReturn(to);
        return v;
    }

    @Test
    void SRS_6_39_aProposalNeedsHeadquartersARequestANameAndACategory() {
        refused(
                () ->
                        service.propose(
                                zonal, proposal("Marriage", "ADVANCE", KEY, List.of()), "r", null),
                ApiErrorCode.HQ_ONLY);
        refused(
                () -> service.propose(headquarters, null, "r", null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.propose(
                                headquarters, proposal(null, "ADVANCE", KEY, List.of()), "r", null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.propose(
                                headquarters, proposal("  ", "ADVANCE", KEY, List.of()), "r", null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.propose(
                                headquarters,
                                proposal("Marriage", null, KEY, List.of()),
                                "r",
                                null),
                CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void
            SRS_6_14_aChecklistWithoutLinesIsEmptyAnEmptyLineIsRefusedAndALineWithoutATierIsTheUnits() {
        Pending none =
                service.propose(
                        headquarters, proposal("Marriage", "ADVANCE", KEY, null), "r", null);
        assertThat(none.approvalId()).isEqualTo(77L);
        assertThat(none.proposal().documents()).isEmpty();
        assertThat(none.proposal().proposedBy()).isEqualTo("ra.hq");

        List<DocumentView> withEmptyLine = new ArrayList<>();
        withEmptyLine.add(null);
        refused(
                () ->
                        service.propose(
                                headquarters,
                                proposal("Marriage", "ADVANCE", KEY, withEmptyLine),
                                "r",
                                null),
                CpfErrorCode.PURPOSE_DOCUMENT);

        Pending defaulted =
                service.propose(
                        headquarters,
                        proposal(
                                "Marriage",
                                "ADVANCE",
                                KEY,
                                List.of(new DocumentView("CERTIFICATE", true, null))),
                        "r",
                        null);
        assertThat(defaulted.proposal().documents())
                .extracting(DocumentView::tier)
                .containsExactly("UNIT_CPF_ADMIN");
    }

    @Test
    void
            SRS_6_39_anApprovedVersionEndsEveryOpenVersionBeforeItAndRecordsTheCheckersReasonOrADefault() {
        Proposal p =
                new Proposal(
                        "MARRIAGE",
                        "Marriage",
                        "ADVANCE",
                        KEY,
                        2,
                        true,
                        false,
                        FROM,
                        false,
                        List.of(new DocumentView("CERTIFICATE", true, "UNIT_CPF_ADMIN")),
                        "ra.hq",
                        NOW);
        String payload = json.writeValueAsString(p);
        CpfWithdrawalPurpose later = version(LocalDate.of(2026, 6, 1), LocalDate.of(2027, 12, 31));
        CpfWithdrawalPurpose open = version(LocalDate.of(2026, 4, 1), null);
        CpfWithdrawalPurpose ended = version(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        when(purposes.findByPurposeCodeOrderByEffectiveFromDesc("MARRIAGE"))
                .thenReturn(List.of(later, open, ended));
        when(purposes.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.on(
                new ApprovalDecided(
                        90L,
                        "M-19",
                        WithdrawalPurposeService.ENTITY,
                        "MARRIAGE",
                        ApprovalStatus.APPROVED,
                        null,
                        payload,
                        "approver.hq",
                        NOW,
                        7L));

        verify(later).endBefore(FROM);
        verify(open).endBefore(FROM);
        verify(ended, never()).endBefore(any());
        verify(documents).save(any());
        ArgumentCaptor<AuditService.Entry> entry = ArgumentCaptor.captor();
        verify(audit).record(eq("approver.hq"), entry.capture());
        assertThat(entry.getValue().reason()).isEqualTo("M-19 purpose version");
        assertThat(entry.getValue().oldValue()).isEqualTo("2026-06-01");
        assertThat(entry.getValue().newValue()).contains("checklist 1", "confirmed N");

        // Another screen's decision, or a refusal, changes nothing.
        service.on(
                new ApprovalDecided(
                        91L,
                        "M-20",
                        WithdrawalPurposeService.ENTITY,
                        "MARRIAGE",
                        ApprovalStatus.APPROVED,
                        "ok",
                        payload,
                        "approver.hq",
                        NOW,
                        null));
        service.on(
                new ApprovalDecided(
                        92L,
                        "M-19",
                        WithdrawalPurposeService.ENTITY,
                        "MARRIAGE",
                        ApprovalStatus.REJECTED,
                        "no",
                        payload,
                        "approver.hq",
                        NOW,
                        null));
        verify(purposes, times(1)).findByPurposeCodeOrderByEffectiveFromDesc("MARRIAGE");
    }

    /**
     * SRS-6-39: the first version of a purpose, approved with the Rule Approver's reason, is
     * audited with that reason and no earlier version.
     */
    @Test
    void SRS_6_39_aFirstVersionApprovedWithAReasonIsAuditedWithIt() {
        Proposal p =
                new Proposal(
                        "PLOT",
                        "Purchase of a plot",
                        "PART_FINAL",
                        null,
                        null,
                        false,
                        true,
                        FROM,
                        true,
                        List.of(),
                        "ra.hq",
                        NOW);
        when(purposes.findByPurposeCodeOrderByEffectiveFromDesc("PLOT")).thenReturn(List.of());
        when(purposes.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.on(
                new ApprovalDecided(
                        93L,
                        "M-19",
                        WithdrawalPurposeService.ENTITY,
                        "PLOT",
                        ApprovalStatus.APPROVED,
                        "Approved per circular 12",
                        json.writeValueAsString(p),
                        "approver.hq",
                        NOW,
                        7L));

        verify(documents, never()).save(any());
        ArgumentCaptor<AuditService.Entry> entry = ArgumentCaptor.captor();
        verify(audit).record(eq("approver.hq"), entry.capture());
        assertThat(entry.getValue().reason()).isEqualTo("Approved per circular 12");
        assertThat(entry.getValue().oldValue()).isNull();
        assertThat(entry.getValue().newValue()).contains("checklist 0", "confirmed Y");
    }

    /** SRS-6-39: a category that is not one of the defined categories is refused, not guessed. */
    @Test
    void SRS_6_39_anUnknownCategoryCodeIsAValidationFailureAndNothingIsSubmitted() {
        for (String unknown : new String[] {"BOGUS", "advance", " ADVANCE", ""}) {
            refused(
                    () ->
                            service.propose(
                                    headquarters,
                                    proposal("Marriage", unknown, KEY, List.of()),
                                    "r",
                                    null),
                    CommonErrorCode.VALIDATION_FAILED);
        }
        verify(approvals, never()).submit(any(), any(), any());
    }

    /** SRS-6-14: a checklist line whose approval tier is not a defined tier is refused. */
    @Test
    void SRS_6_14_anUnknownTierOnAChecklistLineIsAValidationFailureAndNothingIsSubmitted() {
        for (String unknown : new String[] {"EVERYONE", "unit_cpf_admin", ""}) {
            refused(
                    () ->
                            service.propose(
                                    headquarters,
                                    proposal(
                                            "Marriage",
                                            "ADVANCE",
                                            KEY,
                                            List.of(
                                                    new DocumentView(
                                                            "CERTIFICATE", true, unknown))),
                                    "r",
                                    null),
                    CommonErrorCode.VALIDATION_FAILED);
        }
        verify(approvals, never()).submit(any(), any(), any());
    }
}
