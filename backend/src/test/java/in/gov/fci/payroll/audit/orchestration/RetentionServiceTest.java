package in.gov.fci.payroll.audit.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.business.model.RetentionClass;
import in.gov.fci.payroll.audit.persistence.AuditRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A-07 retention periods (SRS-20-5): a proposal names its class; a decision of another entity or
 * not approved changes nothing; an approved one without a reason is audited as "Approved".
 */
class RetentionServiceTest {

    private final AuditRepositories.RetentionClasses classes =
            mock(AuditRepositories.RetentionClasses.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final RetentionService service =
            new RetentionService(
                    classes,
                    mock(MakerChecker.class),
                    mock(ApprovalStatuses.class),
                    policy,
                    audit,
                    JsonMapper.builder().build());

    @Test
    void SRS_20_5_aProposalNamesItsClass() {
        AccessContext hq = mock(AccessContext.class);
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        for (RetentionService.ChangeRequest bad :
                java.util.Arrays.asList(
                        null, new RetentionService.ChangeRequest(null, 12, false, "Act", "r"))) {
            assertThatThrownBy(() -> service.propose(hq, bad, null))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            ex ->
                                    org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                            .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        }
    }

    private static ApprovalDecided decided(String entity, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                5L,
                "A-07",
                entity,
                "PAYROLL",
                outcome,
                reason,
                "{\"months\":120,\"indefinite\":false,\"basis\":\"Record Rules 2026\"}",
                "checker@x",
                Instant.parse("2026-10-29T06:00:00Z"));
    }

    @Test
    void SRS_20_5_onlyAnApprovedPeriodIsAppliedAndWithoutAReasonIsApproved() {
        service.on(decided("other_entity", ApprovalStatus.APPROVED, null));
        service.on(decided(RetentionService.ENTITY, ApprovalStatus.REJECTED, "no"));
        verifyNoInteractions(classes, audit);

        RetentionClass payroll = mock(RetentionClass.class);
        when(payroll.getCode()).thenReturn("PAYROLL");
        Map<String, String> before = Map.of("retention_months", "84");
        Map<String, String> after = Map.of("retention_months", "120");
        when(payroll.image()).thenReturn(before).thenReturn(after);
        when(classes.findByCode("PAYROLL")).thenReturn(Optional.of(payroll));
        service.on(decided(RetentionService.ENTITY, ApprovalStatus.APPROVED, null));
        verify(payroll).setPeriod(120, false, "Record Rules 2026");
        verify(audit)
                .recordAll(
                        eq("checker@x"),
                        argThat(
                                (List<AuditService.Entry> entries) ->
                                        entries.stream()
                                                        .allMatch(
                                                                e -> "Approved".equals(e.reason()))
                                                && !entries.isEmpty()));
        verify(classes).save(any());
        verify(audit, org.mockito.Mockito.never()).record(anyString(), any());
    }

    /** SRS-20-5: a stored proposal that cannot be read changes nothing and names the failure. */
    @Test
    void SRS_20_5_anUnreadableStoredProposalChangesNothing() {
        RetentionClass payroll = mock(RetentionClass.class);
        when(classes.findByCode("PAYROLL")).thenReturn(Optional.of(payroll));
        ApprovalDecided broken =
                new ApprovalDecided(
                        5L,
                        "A-07",
                        RetentionService.ENTITY,
                        "PAYROLL",
                        ApprovalStatus.APPROVED,
                        null,
                        "{bad",
                        "checker@x",
                        Instant.parse("2026-10-29T06:00:00Z"));

        assertThatThrownBy(() -> service.on(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unreadable retention proposal")
                .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
        verify(classes, org.mockito.Mockito.never()).save(any());
        verifyNoInteractions(audit);
    }

    /** SRS-20-5: an approved period of a class that no longer exists is refused, not skipped. */
    @Test
    void SRS_20_5_anApprovedPeriodOfAnUnknownClassIsRefused() {
        when(classes.findByCode("PAYROLL")).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                RetentionService.ENTITY,
                                                ApprovalStatus.APPROVED,
                                                null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("retention class gone");
        verifyNoInteractions(audit);
    }

    /** SRS-20-5: every outcome other than approved leaves the class untouched. */
    @Test
    void SRS_20_5_everyNonApprovedOutcomeLeavesTheClassUntouched() {
        for (ApprovalStatus outcome :
                List.of(
                        ApprovalStatus.REJECTED,
                        ApprovalStatus.RETURNED,
                        ApprovalStatus.WITHDRAWN,
                        ApprovalStatus.PENDING_APPROVAL)) {
            service.on(decided(RetentionService.ENTITY, outcome, "x"));
        }
        verifyNoInteractions(classes, audit);
    }
}
