package in.gov.fci.payroll.employee.orchestration;

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
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.business.model.LifecycleEvent;
import in.gov.fci.payroll.employee.orchestration.EmployeeEventService.EventView;
import in.gov.fci.payroll.employee.orchestration.EmployeeEventService.NewTransfer;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

/**
 * M-11 transfer order copies and I-17 event statuses at their edges (SRS-16-18, SRS-G-31,
 * SRS-1-63): a copy named without its provisional key, or stored by another user, or another file,
 * is no copy (the refusal audited); an event waiting for the Checker shows its approval status.
 */
class EmployeeEventServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");
    private static final String KEY = "PROVISIONAL:7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final EventRepositories.LifecycleEvents events =
            mock(EventRepositories.LifecycleEvents.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AuditService audit = mock(AuditService.class);

    private final EmployeeEventService service =
            new EmployeeEventService(
                    employees,
                    events,
                    mock(EventRepositories.Transfers.class),
                    statuses,
                    approvals,
                    mock(Authorization.class),
                    contexts,
                    org,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    documents,
                    audit,
                    mock(TransferOrigin.class));

    private final AccessContext maker =
            new AccessContext(
                    3L,
                    "maker.delhi",
                    "Maker",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(300L));

    EmployeeEventServiceBranchGapsTest() {
        when(contexts.today()).thenReturn(TODAY);
        Employee e = mock(Employee.class);
        when(e.getId()).thenReturn(9L);
        when(e.getEmpNum()).thenReturn("E100");
        when(e.fullName()).thenReturn("Asha Verma");
        when(e.getLocationId()).thenReturn(300L);
        when(e.getEmploymentStatus()).thenReturn("IN_SERVICE");
        when(employees.findByEmpNum("E100")).thenReturn(Optional.of(e));
        when(employees.findById(9L)).thenReturn(Optional.of(e));
        OrgLocation l = mock(OrgLocation.class);
        when(l.getId()).thenReturn(301L);
        when(l.getCode()).thenReturn("KL001");
        when(l.getZoneId()).thenReturn(1L);
        when(l.displayLabel()).thenReturn("KL001 office");
        when(l.isActiveOn(any())).thenReturn(true);
        when(org.byCode("KL001")).thenReturn(Optional.of(l));
        when(org.location(301L)).thenReturn(Optional.of(l));
    }

    private static NewTransfer transfer(Long attachmentId, String key) {
        return new NewTransfer(
                "KL001",
                TODAY,
                TODAY,
                TODAY,
                null,
                "ORD/7",
                null,
                null,
                false,
                null,
                null,
                "Posted",
                false,
                attachmentId,
                key);
    }

    private static DocumentStore.AttachmentView copy(long id, String by) {
        return new DocumentStore.AttachmentView(
                id,
                EmployeeEventService.TRANSFER_ENTITY,
                KEY,
                "OTHER",
                "Other document",
                "ORD/7",
                TODAY,
                "GM (P)",
                "order.pdf",
                "application/pdf",
                10L,
                "abc",
                "ACTIVE",
                by,
                NOW);
    }

    private void copyMissing(NewTransfer request) {
        assertThatThrownBy(() -> service.recordTransfer(maker, "E100", request, null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.M11_ORDER);
                            assertThat(e.getMessageArgs()).containsExactly("COPY_MISSING");
                        });
    }

    /** SRS-16-18 / SRS-G-31: the copy is the one this user stored under the provisional key. */
    @Test
    void SRS_16_18_aCopyWithoutItsKeyOrOfAnotherUserOrAnotherFileIsNoCopy() {
        copyMissing(transfer(5L, null));
        when(documents.active(EmployeeEventService.TRANSFER_ENTITY, KEY))
                .thenReturn(List.of(copy(5L, "maker.other"), copy(6L, "maker.delhi")));
        copyMissing(transfer(5L, KEY));
        verify(approvals, never()).submit(any(), any(), any());
        verify(audit, times(2))
                .submissionBlocked(
                        eq("maker.delhi"),
                        eq(EmployeeEventService.TRANSFER_ENTITY),
                        eq("E100"),
                        eq("M-11"),
                        eq("transfer order copy"));
    }

    /** SRS-1-63: an event with its approval item shows the item's status. */
    @Test
    void SRS_1_63_anEventWaitingForTheCheckerShowsItsApprovalStatus() {
        LifecycleEvent waiting =
                LifecycleEvent.record(
                        9L,
                        "HOLD",
                        TODAY,
                        null,
                        "Order 4",
                        null,
                        null,
                        null,
                        null,
                        "maker.delhi",
                        NOW);
        waiting.attachApproval(77L);
        Map<Long, ApprovalStatus> found = new HashMap<>();
        found.put(77L, ApprovalStatus.PENDING_APPROVAL);
        when(statuses.of(any())).thenReturn(found);
        when(events.findRecentInScope(eq(Set.of(300L)), any()))
                .thenReturn(new PageImpl<>(List.of(waiting)));
        EventView view = service.recentEvents(maker, new PageSpec(0, 20)).items().getFirst();
        assertThat(view.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(view.approvalId()).isEqualTo(77L);
        assertThat(view.eis()).isEqualTo("E100");
    }
}
