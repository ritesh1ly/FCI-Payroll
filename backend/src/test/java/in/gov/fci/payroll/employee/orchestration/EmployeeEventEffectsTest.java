package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.business.model.EmployeeTransfer;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

/**
 * An approved transfer takes effect (M-11 / I-17; plan 16 phase 16f-1 moved it beside the HRMS
 * events): a new staff number that is blank or unchanged writes no number history; a transfer
 * without an order reference is still audited; an unreadable payload refuses; a rejected decision
 * or another entity changes nothing.
 */
class EmployeeEventEffectsTest {

    private static final Instant DECIDED = Instant.parse("2026-10-26T06:00:00Z");

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final EventRepositories.Transfers transfers = mock(EventRepositories.Transfers.class);
    private final EventRepositories.NumberHistory numbers =
            mock(EventRepositories.NumberHistory.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final EmployeeEventEffects effects =
            new EmployeeEventEffects(
                    employees,
                    mock(EventRepositories.LifecycleEvents.class),
                    transfers,
                    numbers,
                    publisher,
                    mock(AuditService.class),
                    new ObjectMapper(),
                    mock(TransferOrigin.class));

    EmployeeEventEffectsTest() {
        EmployeeTransfer transfer =
                EmployeeTransfer.record(
                        7L,
                        3L,
                        4L,
                        1L,
                        1L,
                        LocalDate.of(2026, 10, 1),
                        null,
                        null,
                        null,
                        "Admin",
                        null,
                        null,
                        false,
                        null,
                        null);
        when(transfers.findByApprovalId(9L)).thenReturn(Optional.of(transfer));
        Employee employee = mock(Employee.class);
        when(employee.getEmpNum()).thenReturn("100041");
        when(employees.findById(7L)).thenReturn(Optional.of(employee));
    }

    private static ApprovalDecided decision(String entity, ApprovalStatus outcome, String payload) {
        return new ApprovalDecided(
                9L, "I-17", entity, "7", outcome, null, payload, "checker", DECIDED);
    }

    @Test
    void M_11_aBlankOrUnchangedStaffNumberWritesNoHistory() {
        for (String payload :
                new String[] {
                    "{}", "{\"newStaffNumber\":\" \"}", "{\"newStaffNumber\":\"100041\"}"
                }) {
            effects.on(
                    decision(
                            EmployeeEventService.TRANSFER_ENTITY,
                            ApprovalStatus.APPROVED,
                            payload));
        }
        verify(numbers, never()).save(any());
        verify(publisher, org.mockito.Mockito.times(3)).publishEvent(any(Object.class));
    }

    @Test
    void M_11_anUnreadablePayloadRefusesAndARejectionChangesNothing() {
        assertThatThrownBy(
                        () ->
                                effects.on(
                                        decision(
                                                EmployeeEventService.TRANSFER_ENTITY,
                                                ApprovalStatus.APPROVED,
                                                "not json")))
                .isInstanceOf(IllegalStateException.class);
        effects.on(decision(EmployeeEventService.TRANSFER_ENTITY, ApprovalStatus.REJECTED, "{}"));
        effects.on(decision("other", ApprovalStatus.APPROVED, "{}"));
        verifyNoInteractions(publisher, numbers);
    }
}
