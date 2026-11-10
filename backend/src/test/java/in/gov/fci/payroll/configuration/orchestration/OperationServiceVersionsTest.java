package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.OperationMaster;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M-27 Operation Master versions (SRS-G-41, SRS-G-38): a proposal off Headquarters names no office,
 * and an allowance pay code named on a non-Dusting operation must still be an active M-07
 * component; an end-date acts on the approved open version only (never a closed or a pending one);
 * a first version approved closes nothing; an approved end-date with no open version left ends
 * nothing; a version without an approval shows no status.
 */
class OperationServiceVersionsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    private final ConfigurationRepositories.Operations operations =
            mock(ConfigurationRepositories.Operations.class);
    private final ConfigurationRepositories.PayComponents components =
            mock(ConfigurationRepositories.PayComponents.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final MasterAudit audit = mock(MasterAudit.class);
    private final AccessContext context = mock(AccessContext.class);
    private final Map<Long, ApprovalStatus> decided = new HashMap<>();
    private final OperationService service =
            new OperationService(
                    operations,
                    components,
                    mock(Circulars.class),
                    statuses,
                    approvals,
                    mock(Authorization.class),
                    settings,
                    audit);

    OperationServiceVersionsTest() {
        when(settings.today()).thenReturn(DAY);
        when(context.locationId()).thenReturn(41L);
        when(statuses.of(any())).thenReturn(decided);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(9L, ApprovalStatus.PENDING_APPROVAL, null));
        when(operations.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private OperationMaster version(int no, Long approval, ApprovalStatus s, LocalDate to) {
        OperationMaster o = mock(OperationMaster.class);
        when(o.getOperationCode()).thenReturn("OP1");
        when(o.getOperationName()).thenReturn("Wagon to Platform");
        when(o.getVersionNo()).thenReturn(no);
        when(o.getApprovalId()).thenReturn(approval);
        when(o.getEffectiveFrom()).thenReturn(LocalDate.of(2026, 4, no));
        when(o.getEffectiveTo()).thenReturn(to);
        if (approval != null) {
            decided.put(approval, s);
        }
        return o;
    }

    private static OperationService.ProposedOperation proposal(Integer payCode) {
        return new OperationService.ProposedOperation(
                DAY.plusDays(10),
                "Wagon to Platform",
                true,
                false,
                false,
                false,
                null,
                "TIME_BASED",
                false,
                payCode,
                false,
                1L,
                "Revised norm");
    }

    @Test
    void SRS_G_41_aProposalOffHeadquartersNamesNoOfficeAndANamedPayCodeMustBeActive() {
        PayComponent active = mock(PayComponent.class);
        when(active.isActiveOn(DAY)).thenReturn(true);
        when(active.getComponentId()).thenReturn(77L);
        when(components.findByLegacyPayCode(912)).thenReturn(Optional.of(active));
        service.propose(context, "OP1", proposal(912), null);
        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals).submit(any(), submitted.capture(), any());
        assertThat(submitted.getValue().locationId()).isNull();
        ArgumentCaptor<OperationMaster> saved = ArgumentCaptor.captor();
        verify(operations).save(saved.capture());
        assertThat(saved.getValue().getAllowanceComponentId()).isEqualTo(77L);

        when(components.findByLegacyPayCode(913)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.propose(context, "OP1", proposal(913), null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.MASTER_COMPONENT_INVALID));
    }

    @Test
    void SRS_G_41_anEndDateActsOnTheApprovedOpenVersionOnly() {
        OperationMaster pending = version(3, 13L, ApprovalStatus.PENDING_APPROVAL, null);
        OperationMaster closed = version(2, 12L, ApprovalStatus.APPROVED, DAY.minusDays(1));
        OperationMaster open = version(1, 11L, ApprovalStatus.APPROVED, null);
        when(operations.findByOperationCodeOrderByVersionNoDesc("OP1"))
                .thenReturn(List.of(pending, closed, open));
        // The last day is checked against the open version (1 April), not the pending one.
        assertThatThrownBy(
                        () -> service.endDate(context, "OP1", LocalDate.of(2026, 3, 31), "r", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getMessageArgs())
                                        .containsExactly(LocalDate.of(2026, 4, 1)));
    }

    private static ApprovalDecided approved(String entity, String key, String payload) {
        return new ApprovalDecided(
                9L,
                "M-27",
                entity,
                key,
                ApprovalStatus.APPROVED,
                null,
                payload,
                "rule.approver@x",
                Instant.parse("2026-10-29T06:00:00Z"));
    }

    @Test
    void SRS_G_41_aFirstVersionApprovedClosesNothing() {
        OperationMaster first = version(1, 9L, ApprovalStatus.APPROVED, null);
        when(operations.findByApprovalId(9L)).thenReturn(Optional.of(first));
        when(operations.findByOperationCodeOrderByVersionNoDesc("OP1")).thenReturn(List.of(first));
        service.on(approved(OperationService.ENTITY, "OP1", null));
        verify(audit)
                .applied(
                        any(),
                        eq(OperationService.ENTITY),
                        eq("OP1"),
                        anyString(),
                        any(),
                        isNull(),
                        any(),
                        eq(List.of()),
                        isNull());
    }

    @Test
    void SRS_G_41_anApprovedEndDateWithNoOpenVersionLeftEndsNothing() {
        OperationMaster closed = version(1, 11L, ApprovalStatus.APPROVED, DAY);
        OperationMaster pending = version(2, 12L, ApprovalStatus.PENDING_APPROVAL, null);
        when(operations.findByOperationCodeOrderByVersionNoDesc("OP1"))
                .thenReturn(List.of(pending, closed));
        service.on(approved(OperationService.END_DATE, "OP1", "{\"lastDay\":\"2026-11-30\"}"));
        verify(closed, never()).endBefore(any());
        verify(pending, never()).endBefore(any());
        verify(operations, never()).save(any());
    }

    @Test
    void SRS_G_41_aVersionWithoutAnApprovalShowsNoStatus() {
        OperationMaster seeded = version(1, null, null, null);
        when(operations.findByOperationCodeOrderByVersionNoDesc("OP1")).thenReturn(List.of(seeded));
        assertThat(service.history(context, "OP1").get(0).status()).isEqualTo("NONE");
    }
}
