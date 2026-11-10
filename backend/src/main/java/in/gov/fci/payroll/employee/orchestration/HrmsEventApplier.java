package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.business.EventRules;
import in.gov.fci.payroll.employee.business.model.LifecycleEvent;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A life-cycle event received from HRMS takes effect through its approved I-21 task (plan 16 phase
 * 16f-1; SRS-1-63: "Receive employee suspension, absconding, revocation and reinstatement events
 * from HRMS … Each event opens an I-21 task … takes effect only after Checker approval"; SRS-1-64,
 * SRS-1-77): the approval records the {@code employee_lifecycle_event} (event_source HRMS, linked
 * to the task, the HRMS request and the approval) and applies it as an approved I-17 event does
 * (employment status, separation events). The engine then reads it: subsistence from the
 * suspension, no pay after absconding or separation.
 *
 * <p>OPEN-QUESTION (D16-30, PENDING 16-hrms-event-values): the interface contract of these values
 * is not given (module 23); a SUSPENSION carries the subsistence percentage of the order (0-100),
 * the other events carry no value. An event dated after today is refused at approval, as on I-17
 * (no deferred status change yet).
 */
@Component
public class HrmsEventApplier {

    /** The I-21 fields that are life-cycle events, not employee columns. */
    static final Set<String> EVENT_FIELDS =
            Set.of("SUSPENSION", "ABSCONDING", "REVOCATION", "SEPARATION");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final EventRepositories.LifecycleEvents events;
    private final EmployeeEventEffects effects;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public HrmsEventApplier(
            EventRepositories.LifecycleEvents events,
            EmployeeEventEffects effects,
            JdbcTemplate jdbc,
            AuditService audit) {
        this.events = events;
        this.effects = effects;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    static boolean handles(String fieldCode) {
        return EVENT_FIELDS.contains(fieldCode);
    }

    /** Records and applies the event of an approved task, in the decision's transaction. */
    void apply(Map<String, Object> task, String fieldCode, ApprovalDecided decision) {
        LocalDate date = HrmsChangeService.date(task.get("EFFECTIVE_DATE"));
        if (date == null
                || date.isAfter(
                        LocalDate.ofInstant(
                                decision.decidedAt(), java.time.ZoneId.of("Asia/Kolkata")))) {
            throw new FciException(
                    ApiErrorCode.I21_VALUE,
                    new Object[] {fieldCode + " dated after today"},
                    List.of(),
                    null);
        }
        // I-17 Effective Date (SRS l.3877, US-GAP-277): an HRMS event never precedes the joining
        // date; refused at approval, the task stays with the Checker to reject back to HRMS.
        long member = ((Number) task.get("EMPLOYEE_ID")).longValue();
        EventRules.requireNotBeforeJoining(
                date,
                jdbc
                        .queryForList(
                                "SELECT comp_joining_date FROM employee WHERE employee_id = ?",
                                java.sql.Date.class,
                                member)
                        .stream()
                        .filter(java.util.Objects::nonNull)
                        .map(java.sql.Date::toLocalDate)
                        .findFirst()
                        .orElse(null));
        BigDecimal subsistence = null;
        if ("SUSPENSION".equals(fieldCode)) {
            subsistence = percent((String) task.get("NEW_VALUE"));
        }
        String orderNo = (String) task.get("ORDER_NO");
        LifecycleEvent event =
                events.save(
                        LifecycleEvent.fromHrms(
                                ((Number) task.get("EMPLOYEE_ID")).longValue(),
                                fieldCode,
                                date,
                                "HRMS change " + task.get("TASK_NO"),
                                subsistence,
                                orderNo,
                                (String) task.get("HRMS_REQUEST_ID"),
                                ((Number) task.get("HRMS_CHANGE_TASK_ID")).longValue(),
                                decision.approvalId(),
                                decision.checkerLoginId(),
                                decision.decidedAt()));
        if (!EventRules.EVENT_TYPES.contains(event.getEventType())) {
            throw new IllegalStateException("Not an event type: " + fieldCode);
        }
        effects.applyApproved(
                event,
                decision.checkerLoginId(),
                decision.decidedAt(),
                decision.approvalId(),
                "I-21");
        if ("ABSCONDING".equals(fieldCode)) {
            // SRS-1-64 / SRS-1-7: pay stops from the approved absconding: the engine holds the
            // payment of a member flagged absconding (PAYMENT_HELD on X-05).
            long employeeId = ((Number) task.get("EMPLOYEE_ID")).longValue();
            jdbc.update(
                    "UPDATE employee SET absconding_flag = 'Y', row_version = row_version + 1"
                            + " WHERE employee_id = ?",
                    employeeId);
            audit.record(
                    decision.checkerLoginId(),
                    AuditService.Entry.change(
                                    "employee",
                                    jdbc.queryForObject(
                                            "SELECT emp_num FROM employee WHERE employee_id = ?",
                                            String.class,
                                            employeeId),
                                    "absconding_flag",
                                    "N",
                                    "Y",
                                    "HRMS change " + task.get("TASK_NO"))
                            .approval(decision.approvalId())
                            .module("I-21"));
        }
    }

    /** SRS-1-64: the subsistence percentage of the order, 0-100. */
    private static BigDecimal percent(String value) {
        try {
            BigDecimal pct = new BigDecimal(value == null ? "" : value.strip());
            if (pct.signum() < 0 || pct.compareTo(HUNDRED) > 0) {
                throw new NumberFormatException(value);
            }
            return pct;
        } catch (NumberFormatException bad) {
            throw new FciException(
                    ApiErrorCode.I21_VALUE,
                    new Object[] {"SUSPENSION subsistence percentage " + value},
                    List.of(),
                    null);
        }
    }
}
