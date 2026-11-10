package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.employee.business.EventRules;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.business.model.EmployeeNumberHistory;
import in.gov.fci.payroll.employee.business.model.LifecycleEvent;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The checker's final decision on an I-17 event or an M-11 transfer, applied in the decision's
 * transaction. An approved event sets the employment status (SEPARATION and DISMISSAL also publish
 * {@link EmployeeEvents.EmployeeSeparated}: iam deactivates the account in the same act); an
 * approved transfer moves the employee to the new unit, reissues the staff number if one was given
 * (the old number is kept in employee_number_history) and publishes {@link
 * EmployeeEvents.EmployeeTransferred} (iam withdraws the rights held at the old unit). The CPF
 * Member ID and parent zone never change. A rejected or returned record stays, inert.
 */
@Component
public class EmployeeEventEffects {

    private final EmployeeRepository employees;
    private final EventRepositories.LifecycleEvents events;
    private final EventRepositories.Transfers transfers;
    private final EventRepositories.NumberHistory numbers;
    private final ApplicationEventPublisher publisher;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransferOrigin origin;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public EmployeeEventEffects(
            EmployeeRepository employees,
            EventRepositories.LifecycleEvents events,
            EventRepositories.Transfers transfers,
            EventRepositories.NumberHistory numbers,
            ApplicationEventPublisher publisher,
            AuditService audit,
            ObjectMapper json,
            TransferOrigin origin) {
        this.origin = origin;
        this.employees = employees;
        this.events = events;
        this.transfers = transfers;
        this.numbers = numbers;
        this.publisher = publisher;
        this.audit = audit;
        this.json = json;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.approved()) {
            return;
        }
        if (decision.concerns(EmployeeEventService.EVENT_ENTITY)) {
            events.findByApprovalId(decision.approvalId())
                    .ifPresent(
                            event ->
                                    applyApproved(
                                            event,
                                            decision.checkerLoginId(),
                                            decision.decidedAt(),
                                            decision.approvalId(),
                                            "I-17"));
        } else if (decision.concerns(EmployeeEventService.TRANSFER_ENTITY)) {
            transfers
                    .findByApprovalId(decision.approvalId())
                    .ifPresent(
                            transfer -> {
                                // UC M-11 (SRS l.1323): "On approval ... the system rechecks the
                                // origin"; a refusal keeps the decision pending.
                                origin.requireClear(
                                        transfer.getEmployeeId(), transfer.getTransferDate());
                                Employee employee =
                                        employees.findById(transfer.getEmployeeId()).orElseThrow();
                                String oldNumber = employee.getEmpNum();
                                String newNumber = newStaffNumber(decision.payload());
                                employee.applyTransfer(
                                        transfer.getToLocationId(),
                                        newNumber,
                                        decision.decidedAt());
                                employees.save(employee);
                                // Written now: a JDBC write of the member later in this
                                // transaction bumps row_version (Oracle review F5).
                                employees.flush();
                                if (newNumber != null && !newNumber.equals(oldNumber)) {
                                    numbers.save(
                                            EmployeeNumberHistory.of(
                                                    employee.getId(),
                                                    oldNumber,
                                                    newNumber,
                                                    transfer.getFromLocationId(),
                                                    transfer.getToLocationId(),
                                                    transfer.getTransferDate(),
                                                    "TRANSFER",
                                                    transfer.getReason(),
                                                    decision.approvalId()));
                                }
                                audit.record(
                                        decision.checkerLoginId(),
                                        AuditService.Entry.change(
                                                        "employee",
                                                        employee.getEmpNum(),
                                                        "location_id",
                                                        String.valueOf(
                                                                transfer.getFromLocationId()),
                                                        String.valueOf(transfer.getToLocationId()),
                                                        "Transfer "
                                                                + (transfer.getOrderReference()
                                                                                == null
                                                                        ? ""
                                                                        : transfer
                                                                                .getOrderReference()))
                                                .approval(decision.approvalId())
                                                .module("M-11"));
                                publisher.publishEvent(
                                        new EmployeeEvents.EmployeeTransferred(
                                                employee.getId(),
                                                transfer.getTransferId(),
                                                transfer.getFromLocationId(),
                                                transfer.getToLocationId(),
                                                transfer.getTransferDate(),
                                                transfer.getOrderReference(),
                                                decision.checkerLoginId()));
                            });
        }
    }

    private String newStaffNumber(String payload) {
        try {
            String value = json.readTree(payload).path("newStaffNumber").asString(null);
            return value == null || value.isBlank() ? null : value;
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unreadable transfer payload", ex);
        }
    }

    /**
     * An approved life-cycle event takes effect (I-17, or I-21 for an event received from HRMS,
     * plan 16 phase 16f-1): the employment status it sets, then the events other modules listen to.
     */
    void applyApproved(
            LifecycleEvent event,
            String checker,
            java.time.Instant at,
            Long approvalId,
            String module) {
        Employee employee = employees.findById(event.getEmployeeId()).orElseThrow();
        EventRules.statusAfter(event.getEventType())
                .ifPresent(
                        status -> {
                            String before = employee.getEmploymentStatus();
                            employee.applyEmploymentStatus(status, at);
                            employees.save(employee);
                            // Written now: an I-21 absconding sets its flag by JDBC next, which
                            // bumps row_version (Oracle review F5).
                            employees.flush();
                            audit.record(
                                    checker,
                                    AuditService.Entry.change(
                                                    "employee",
                                                    employee.getEmpNum(),
                                                    "employment_status",
                                                    before,
                                                    status,
                                                    event.getEventType() + ": " + event.getReason())
                                            .approval(approvalId)
                                            .module(module));
                        });
        publisher.publishEvent(
                new EmployeeEvents.EmployeeLifecycleApplied(
                        employee.getId(),
                        event.getEventId(),
                        event.getEventType(),
                        event.getEventDate(),
                        event.getEndDate(),
                        checker));
        if (EventRules.ENDS_SERVICE.contains(event.getEventType())) {
            publisher.publishEvent(
                    new EmployeeEvents.EmployeeSeparated(
                            employee.getId(),
                            event.getEventId(),
                            event.getEventType(),
                            event.getEventDate(),
                            event.getReason(),
                            checker));
        }
    }
}
