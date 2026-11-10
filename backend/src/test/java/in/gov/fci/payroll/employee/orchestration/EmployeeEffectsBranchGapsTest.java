package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The employee module's approval effects on the decisions that are not theirs or that name what
 * they do not know (R-MC: an effect acts only on its own approved item; SRS-1-11, SRS-16-18,
 * SRS-1-63): another entity's decision changes no employee, an approved event item whose event is
 * gone records nothing, a control flag outside the catalogue is refused.
 */
class EmployeeEffectsBranchGapsTest {

    private static final Instant DECIDED = Instant.parse("2026-10-26T06:00:00Z");

    private static ApprovalDecided decision(String entity, String key, String payload) {
        return new ApprovalDecided(
                9L,
                "M-01",
                entity,
                key,
                ApprovalStatus.APPROVED,
                null,
                payload,
                "checker",
                DECIDED);
    }

    /** SRS-1-11: the M-01 change-pending marker is cleared only by the employee entity's item. */
    @Test
    void SRS_1_11_anotherEntitysDecisionLeavesTheEmployeeChangePendingMarkerAlone() {
        EmployeeRepository employees = mock(EmployeeRepository.class);
        EmployeeApprovalEffect effect = new EmployeeApprovalEffect(employees);
        effect.on(decision("employee_transfer", "100041", "{}"));
        verifyNoInteractions(employees);

        Employee member = mock(Employee.class);
        when(employees.findByEmpNum("100041")).thenReturn(Optional.of(member));
        effect.on(decision(EmployeeApprovalEffect.ENTITY, "100041", "{}"));
        verify(member).clearChangePending(DECIDED);
        verify(employees).save(member);
    }

    /**
     * SRS-1-63 / SRS-16-18: an approved I-17 item whose event is not found records nothing, and an
     * approved item of an entity that is neither an event nor a transfer is not theirs.
     */
    @Test
    void SRS_1_63_anApprovedItemOfAnotherEntityOrAMissingEventChangesNothing() {
        EmployeeRepository employees = mock(EmployeeRepository.class);
        EventRepositories.LifecycleEvents events = mock(EventRepositories.LifecycleEvents.class);
        EventRepositories.Transfers transfers = mock(EventRepositories.Transfers.class);
        EmployeeEventEffects effects =
                new EmployeeEventEffects(
                        employees,
                        events,
                        transfers,
                        mock(EventRepositories.NumberHistory.class),
                        mock(ApplicationEventPublisher.class),
                        mock(AuditService.class),
                        new ObjectMapper(),
                        mock(TransferOrigin.class));
        when(events.findByApprovalId(9L)).thenReturn(Optional.empty());
        effects.on(decision(EmployeeEventService.EVENT_ENTITY, "5", "{}"));
        verify(events).findByApprovalId(9L);
        verify(transfers, never()).findByApprovalId(any());

        effects.on(decision("loan_request", "5", "{}"));
        verifyNoInteractions(transfers, employees);
    }

    /** SRS-1-11: a control flag code outside the catalogue is never written to the employee. */
    @Test
    void SRS_1_11_anApprovedControlFlagOutsideTheCatalogueIsRefused() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmployeeControlFlagEffect effect =
                new EmployeeControlFlagEffect(jdbc, mock(AuditService.class), new ObjectMapper());
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decision(
                                                EmployeeControlFlagService.ENTITY,
                                                "7|NOPE",
                                                "{\"employeeId\":7,\"flagCode\":\"NOPE\","
                                                        + "\"value\":\"Y\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOPE");
        verifyNoInteractions(jdbc);
    }

    /** SRS-20-29: an employee without an office shows the location as none in its history image. */
    @Test
    void SRS_20_29_anEmployeeWithoutAnOfficeShowsNoLocationInItsImage() {
        Employee e = mock(Employee.class);
        when(e.getLocationId()).thenReturn(null);
        assertThat(EmployeeHistory.image(e)).containsEntry("location_id", null);
        when(e.getLocationId()).thenReturn(300L);
        assertThat(EmployeeHistory.image(e)).containsEntry("location_id", "300");
    }
}
