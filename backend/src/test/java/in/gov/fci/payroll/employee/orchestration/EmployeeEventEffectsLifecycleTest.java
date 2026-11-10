package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.business.model.LifecycleEvent;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

/**
 * I-17 / I-21 approved life-cycle event (SRS-16-18, SRS-G-36, plan 16 phase 16f-1): the approved
 * event sets the member's employment status, is audited with the approval and published; a pay-only
 * event sets no status; a separation or dismissal also publishes the separation; an event that is
 * rejected, of another entity or without a stored event changes nothing.
 */
class EmployeeEventEffectsLifecycleTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final Instant DECIDED = Instant.parse("2026-10-26T06:00:00Z");

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final EventRepositories.LifecycleEvents events =
            mock(EventRepositories.LifecycleEvents.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final AuditService audit = mock(AuditService.class);
    private final Employee employee = mock(Employee.class);
    private final EmployeeEventEffects effects =
            new EmployeeEventEffects(
                    employees,
                    events,
                    mock(EventRepositories.Transfers.class),
                    mock(EventRepositories.NumberHistory.class),
                    publisher,
                    audit,
                    new ObjectMapper(),
                    mock(TransferOrigin.class));

    EmployeeEventEffectsLifecycleTest() {
        when(employee.getId()).thenReturn(7L);
        when(employee.getEmpNum()).thenReturn("100041");
        when(employee.getEmploymentStatus()).thenReturn("IN_SERVICE");
        when(employees.findById(7L)).thenReturn(Optional.of(employee));
    }

    private static LifecycleEvent event(String type) {
        LifecycleEvent event = mock(LifecycleEvent.class);
        when(event.getEmployeeId()).thenReturn(7L);
        when(event.getEventId()).thenReturn(31L);
        when(event.getEventType()).thenReturn(type);
        when(event.getEventDate()).thenReturn(TODAY);
        when(event.getReason()).thenReturn("Order 12");
        return event;
    }

    private static ApprovalDecided decision(ApprovalStatus outcome, String entity) {
        return new ApprovalDecided(
                9L, "I-17", entity, "31", outcome, null, "{}", "checker", DECIDED);
    }

    @Test
    void SRS_16_18_anApprovedSuspensionSetsTheStatusAuditsAndPublishesTheApplication() {
        var stored = event("SUSPENSION");
        when(events.findByApprovalId(9L)).thenReturn(Optional.of(stored));

        effects.on(decision(ApprovalStatus.APPROVED, EmployeeEventService.EVENT_ENTITY));

        verify(employee).applyEmploymentStatus("SUSPENDED", DECIDED);
        verify(employees).save(employee);
        verify(employees).flush();
        verify(audit)
                .record(org.mockito.ArgumentMatchers.eq("checker"), any(AuditService.Entry.class));
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(published.capture());
        assertThat(published.getAllValues())
                .singleElement()
                .isInstanceOfSatisfying(
                        EmployeeEvents.EmployeeLifecycleApplied.class,
                        applied -> {
                            assertThat(applied.employeeId()).isEqualTo(7L);
                            assertThat(applied.eventType()).isEqualTo("SUSPENSION");
                            assertThat(applied.decidedBy()).isEqualTo("checker");
                        });
    }

    @Test
    void SRS_G_36_anApprovedSeparationOrDismissalAlsoPublishesTheSeparation() {
        for (String type : List.of("SEPARATION", "DISMISSAL")) {
            var stored = event(type);
            when(events.findByApprovalId(9L)).thenReturn(Optional.of(stored));
            effects.on(decision(ApprovalStatus.APPROVED, EmployeeEventService.EVENT_ENTITY));
        }

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(4)).publishEvent(published.capture());
        assertThat(published.getAllValues())
                .filteredOn(EmployeeEvents.EmployeeSeparated.class::isInstance)
                .hasSize(2);
        verify(employee).applyEmploymentStatus("SEPARATED", DECIDED);
        verify(employee).applyEmploymentStatus("DISMISSED", DECIDED);
    }

    @Test
    void SRS_16_18_aPayOnlyEventSetsNoStatusButIsStillPublished() {
        var stored = event("SALARY_STOP");
        when(events.findByApprovalId(9L)).thenReturn(Optional.of(stored));

        effects.on(decision(ApprovalStatus.APPROVED, EmployeeEventService.EVENT_ENTITY));

        verify(employee, never()).applyEmploymentStatus(any(), any());
        verify(employees, never()).save(any());
        verifyNoInteractions(audit);
        verify(publisher).publishEvent(any(Object.class));
    }

    @Test
    void SRS_16_18_anApprovedItemWithoutAStoredEventChangesNothing() {
        when(events.findByApprovalId(9L)).thenReturn(Optional.empty());

        effects.on(decision(ApprovalStatus.APPROVED, EmployeeEventService.EVENT_ENTITY));

        verifyNoInteractions(employees, audit, publisher);
    }

    @Test
    void SRS_16_18_aNotApprovedOrForeignDecisionChangesNothing() {
        var stored = event("SUSPENSION");
        when(events.findByApprovalId(9L)).thenReturn(Optional.of(stored));

        for (ApprovalStatus outcome :
                List.of(
                        ApprovalStatus.REJECTED,
                        ApprovalStatus.RETURNED,
                        ApprovalStatus.WITHDRAWN)) {
            effects.on(decision(outcome, EmployeeEventService.EVENT_ENTITY));
        }
        effects.on(decision(ApprovalStatus.APPROVED, "other_entity"));

        verifyNoInteractions(employees, audit, publisher);
    }

    @Test
    void SRS_16_18_anApprovedEventOfAnEmployeeThatIsGoneIsRefused() {
        var stored = event("SUSPENSION");
        when(events.findByApprovalId(9L)).thenReturn(Optional.of(stored));
        when(employees.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                effects.on(
                                        decision(
                                                ApprovalStatus.APPROVED,
                                                EmployeeEventService.EVENT_ENTITY)))
                .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(audit, publisher);
    }
}
