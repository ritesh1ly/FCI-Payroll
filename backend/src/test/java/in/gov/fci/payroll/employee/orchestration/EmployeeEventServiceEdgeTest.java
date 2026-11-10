package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.business.model.EmployeeTransfer;
import in.gov.fci.payroll.employee.business.model.LifecycleEvent;
import in.gov.fci.payroll.employee.orchestration.EmployeeEventService.EventView;
import in.gov.fci.payroll.employee.orchestration.EmployeeEventService.NewTransfer;
import in.gov.fci.payroll.employee.orchestration.EmployeeEventService.TransferView;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.CurrentAccess;
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

/**
 * I-17 lifecycle events and M-11 transfers beyond the happy path (SRS-G-37, SRS-22-8): the reason
 * every record needs, a transfer without a destination, a new staff number that is malformed,
 * already another's or the member's own, a session with no office in scope, a record whose member
 * or office is gone from the master, and blank references stored as none.
 */
class EmployeeEventServiceEdgeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final EventRepositories.LifecycleEvents events =
            mock(EventRepositories.LifecycleEvents.class);
    private final EventRepositories.Transfers transfers = mock(EventRepositories.Transfers.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final OrgDirectory org = mock(OrgDirectory.class);

    private final EmployeeEventService service =
            new EmployeeEventService(
                    employees,
                    events,
                    transfers,
                    statuses,
                    approvals,
                    mock(Authorization.class),
                    contexts,
                    org,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(in.gov.fci.payroll.document.api.DocumentStore.class),
                    mock(in.gov.fci.payroll.audit.api.AuditService.class),
                    mock(TransferOrigin.class));

    private final AccessContext maker = context(Set.of(300L));

    EmployeeEventServiceEdgeTest() {
        when(contexts.today()).thenReturn(TODAY);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transfers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // As the real reader: a HashMap, so a record without an approval looks up null.
        when(statuses.of(any())).thenAnswer(invocation -> new java.util.HashMap<>());
    }

    private static AccessContext context(Set<Long> dataScope) {
        return new AccessContext(
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
                dataScope);
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private Employee employee(long id, String eis, String status) {
        Employee e = mock(Employee.class);
        when(e.getId()).thenReturn(id);
        when(e.getEmpNum()).thenReturn(eis);
        when(e.fullName()).thenReturn("Asha Verma");
        when(e.getLocationId()).thenReturn(300L);
        when(e.getEmploymentStatus()).thenReturn(status);
        when(employees.findByEmpNum(eis)).thenReturn(Optional.of(e));
        when(employees.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    private OrgLocation office(long id, String code) {
        OrgLocation l = mock(OrgLocation.class);
        when(l.getId()).thenReturn(id);
        when(l.getCode()).thenReturn(code);
        when(l.getZoneId()).thenReturn(1L);
        when(l.displayLabel()).thenReturn(code + " office");
        when(l.isActiveOn(any())).thenReturn(true);
        when(org.byCode(code)).thenReturn(Optional.of(l));
        when(org.location(id)).thenReturn(Optional.of(l));
        return l;
    }

    private static NewTransfer transfer(String to, String newNumber, String reason) {
        return new NewTransfer(
                to, TODAY, TODAY, TODAY, null, "ORD/7", "  ", newNumber, false, null, null, reason,
                true, null, null);
    }

    /** SRS-1-63: the history of an unknown or missing number is no member's. */
    @Test
    void SRS_1_63_theHistoryOfAnUnknownOrMissingNumberIsNoMember() {
        refused(() -> service.events(maker, null), CommonErrorCode.NOT_FOUND);
        refused(() -> service.events(maker, "E999"), CommonErrorCode.NOT_FOUND);
    }

    /** M-11 field table, US-GAP-27: an inactive unit is neither offered nor accepted. */
    @Test
    void SRS_16_18_anInactiveDestinationIsNotOfferedNorAccepted() {
        employee(9L, "E100", "IN_SERVICE");
        OrgLocation closed = office(302L, "OLD01");
        when(closed.isActiveOn(any())).thenReturn(false);
        OrgLocation open = office(301L, "KL001");
        when(open.getType())
                .thenReturn(in.gov.fci.payroll.organisation.business.model.LocationType.DO);
        when(closed.getType())
                .thenReturn(in.gov.fci.payroll.organisation.business.model.LocationType.DO);
        when(org.all()).thenReturn(Set.of(301L, 302L));
        when(org.locations(any())).thenReturn(List.of(closed, open));
        assertThat(service.destinations(maker))
                .extracting(EmployeeEventService.Destination::code)
                .containsExactly("KL001");
        refused(
                () -> service.recordTransfer(maker, "E100", transfer("OLD01", null, "r"), null),
                ApiErrorCode.M11_DESTINATION);
    }

    /** SRS-16-18 / SRS-G-31, US-GAP-26: no reference, or no copy unless HRMS holds the order. */
    @Test
    void SRS_16_18_aTransferWithoutItsOrderIsRefused() {
        employee(9L, "E100", "IN_SERVICE");
        office(301L, "KL001");
        refused(
                () ->
                        service.recordTransfer(
                                maker,
                                "E100",
                                new NewTransfer(
                                        "KL001", TODAY, TODAY, TODAY, null, " ", null, null, false,
                                        null, null, "r", true, null, null),
                                null),
                ApiErrorCode.M11_ORDER);
        for (String key : new String[] {null, "OTHER:x", "PROVISIONAL:abc"}) {
            refused(
                    () ->
                            service.recordTransfer(
                                    maker,
                                    "E100",
                                    new NewTransfer(
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
                                            "r",
                                            false,
                                            key == null ? null : 5L,
                                            key),
                                    null),
                    ApiErrorCode.M11_ORDER);
        }
    }

    @Test
    void aTransferNeedsADestinationAndAWellFormedFreeStaffNumberAndAReason() {
        Employee asha = employee(9L, "E100", "IN_SERVICE");
        office(301L, "KL001");
        refused(
                () -> service.recordTransfer(maker, "E100", transfer(null, null, "r"), null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.recordTransfer(maker, "E100", transfer("KL001", "x!", "r"), null),
                CommonErrorCode.VALIDATION_FAILED);
        Employee other = employee(10L, "E200", "IN_SERVICE");
        refused(
                () -> service.recordTransfer(maker, "E100", transfer("KL001", "E200", "r"), null),
                ApiErrorCode.EMPLOYEE_NUMBER_TAKEN);
        refused(
                () -> service.recordTransfer(maker, "E100", transfer("KL001", "E300", null), null),
                ApiErrorCode.REASON_REQUIRED);
        refused(
                () -> service.recordTransfer(maker, "E100", transfer("KL001", "E300", " "), null),
                ApiErrorCode.REASON_REQUIRED);

        // The member's own number is not "taken"; a new number travels in the payload for the
        // checker.
        TransferView same =
                service.recordTransfer(maker, "E100", transfer("KL001", " E100 ", "Posted"), null);
        TransferView renumbered =
                service.recordTransfer(maker, "E100", transfer("KL001", "E300", "Posted"), null);
        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals, times(2)).submit(eq(maker), submitted.capture(), isNull());
        assertThat(submitted.getAllValues().get(0).payload())
                .contains("\"newStaffNumber\":\"E100\"");
        assertThat(submitted.getAllValues().get(1).payload())
                .contains("\"newStaffNumber\":\"E300\"");
        assertThat(same.lpcReference()).isNull();
        assertThat(same.toLocation()).isEqualTo("KL001 office");
        assertThat(renumbered.eis()).isEqualTo("E100");
        assertThat(asha.getId()).isEqualTo(9L);
        assertThat(other.getId()).isEqualTo(10L);
    }

    @Test
    void
            SRS_22_8_aSessionWithoutAnOfficeListsNothingAndAMemberOrOfficeGoneFromTheMasterShowsBlank() {
        AccessContext nobody = context(Set.of());
        when(events.findRecentInScope(eq(Set.of(-1L)), any())).thenReturn(Page.empty());
        when(transfers.findRecentInScope(eq(Set.of(-1L)), any())).thenReturn(Page.empty());
        assertThat(service.recentEvents(nobody, new PageSpec(0, 20)).total()).isZero();
        assertThat(service.recentTransfers(nobody, new PageSpec(0, 20)).total()).isZero();

        LifecycleEvent orphanEvent =
                LifecycleEvent.record(
                        99L, "HOLD", TODAY, null, "r", null, null, null, null, "maker.delhi", NOW);
        EmployeeTransfer orphanTransfer =
                EmployeeTransfer.record(
                        99L, null, null, null, null, TODAY, TODAY, TODAY, null, "r", null, null,
                        false, null, null);
        when(events.findRecentInScope(eq(Set.of(300L)), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(orphanEvent)));
        when(transfers.findRecentInScope(eq(Set.of(300L)), any()))
                .thenReturn(
                        new org.springframework.data.domain.PageImpl<>(List.of(orphanTransfer)));
        EventView event = service.recentEvents(maker, new PageSpec(0, 20)).items().get(0);
        assertThat(event.eis()).isNull();
        assertThat(event.employeeName()).isNull();
        TransferView transfer = service.recentTransfers(maker, new PageSpec(0, 20)).items().get(0);
        assertThat(transfer.eis()).isNull();
        assertThat(transfer.fromLocation()).isNull();
        assertThat(transfer.toLocation()).isNull();
        assertThat(transfer.status()).isEqualTo("NONE");
    }
}
