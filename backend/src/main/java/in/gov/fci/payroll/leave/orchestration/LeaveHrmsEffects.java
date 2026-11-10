package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.leave.business.LeaveErrorCode;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * An approved leave task (plan 19 phase 19c; SRS-LV-26, SRS-LV-10, SRS-LV-30; D19-2, D19-5): the
 * leave event HRMS sanctioned becomes a {@code leave_transaction} with the pay effect M-20 holds
 * for its type. An event of the open period is applied in the current cycle; one whose period
 * starts before the open month is back-dated ({@code is_back_dated = 'Y'}), settled as an arrear or
 * recovery by the retrospective engine (module 13), never by changing a locked month. A type whose
 * pay effect is not configured is recorded HELD_UNDEFINED and raised on X-05 as a blocking entry:
 * never a zero deduction (DDL {@code leave_transaction} comment).
 */
@Component
public class LeaveHrmsEffects implements HrmsTaskEffects {

    /** The I-21 field of a leave event (field group LEAVE). */
    public static final String FIELD = "LEAVE_EVENT";

    /** The I-21 field of an in-service encashment sanction (field group LEAVE). */
    public static final String ENCASHMENT_FIELD = "LEAVE_ENCASHMENT";

    /** The X-05 code of an applied event whose type has no pay effect configured. */
    public static final String UNDEFINED = "LEAVE_PAY_EFFECT_UNDEFINED";

    static final String HELD_UNDEFINED = "HELD_UNDEFINED";
    private static final String CONVERSION = "CONVERSION";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final LeaveParameters parameters;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final ExceptionWorklist worklist;
    private final AuditService audit;
    private final ObjectMapper json;
    private final LeaveEncashmentService encashments;
    private final RetroEvents retro;

    public LeaveHrmsEffects(
            JdbcTemplate jdbc,
            LeaveParameters parameters,
            EmployeeDirectory employees,
            OrgDirectory org,
            PayrollRecords payroll,
            ExceptionWorklist worklist,
            AuditService audit,
            ObjectMapper json,
            LeaveEncashmentService encashments,
            RetroEvents retro) {
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.employees = employees;
        this.org = org;
        this.payroll = payroll;
        this.worklist = worklist;
        this.audit = audit;
        this.json = json;
        this.encashments = encashments;
        this.retro = retro;
    }

    @Override
    public Set<String> fields() {
        return Set.of(FIELD, ENCASHMENT_FIELD);
    }

    @Override
    public void check(String fieldCode, long employeeId, String newValue) {
        if (ENCASHMENT_FIELD.equals(fieldCode)) {
            encashment(newValue);
            return;
        }
        Value v = value(newValue);
        application(v.applicationId(), employeeId);
        if (CONVERSION.equals(v.eventType())) {
            earlier(v, employeeId);
        }
    }

    @Override
    public void apply(Task task, String checker, long approvalId, Instant decidedAt) {
        if (ENCASHMENT_FIELD.equals(task.fieldCode())) {
            encash(task, checker, approvalId);
            return;
        }
        Value v = value(task.newValue());
        application(v.applicationId(), task.employeeId());
        YearMonth open = openPeriod(task.employeeId(), YearMonth.from(decidedAt.atZone(IST)));
        // SRS-LV-18, BR-LEAVE-010 (US-GAP-8): a conversion first cancels the earlier sanction's pay
        // effect (an LWP converted to paid leave then raises the arrear, the reverse a recovery).
        LocalDate settleFrom = v.from();
        if (CONVERSION.equals(v.eventType())) {
            for (Map<String, Object> e : earlier(v, task.employeeId())) {
                LocalDate from = LeaveParameterService.toLocalDate(e.get("PERIOD_FROM"));
                if (from.isBefore(settleFrom)) {
                    settleFrom = from;
                }
                reverse(task, v, e, from, open, checker, approvalId);
            }
        }
        boolean backDated = settleFrom.isBefore(open.atDay(1));
        Optional<String> effect = parameters.payEffect(v.leaveType(), v.from());
        String wageEffect = effect.orElse(HELD_UNDEFINED);
        jdbc.update(
                "INSERT INTO leave_transaction (employee_id, application_id, leave_type_code,"
                        + " period_from, period_to, days, wage_effect_code, leave_event_type,"
                        + " is_back_dated, hrms_sanction_ref, hrms_change_task_id, source_code,"
                        + " period_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INTF', ?)",
                task.employeeId(),
                v.applicationId(),
                v.leaveType(),
                Date.valueOf(v.from()),
                Date.valueOf(v.to()),
                v.days(),
                wageEffect,
                v.eventType(),
                backDated ? "Y" : "N",
                v.sanctionRef(),
                task.taskId(),
                Date.valueOf(v.from().withDayOfMonth(1)));
        audit.record(
                checker,
                AuditService.Entry.change(
                                "leave_transaction",
                                task.taskNo(),
                                "wage_effect_code",
                                null,
                                wageEffect,
                                "Leave "
                                        + v.eventType()
                                        + " "
                                        + v.leaveType()
                                        + " "
                                        + v.from()
                                        + " to "
                                        + v.to()
                                        + ", sanction "
                                        + v.sanctionRef()
                                        + (backDated
                                                ? ", back-dated: settled from " + open
                                                : ", applied in " + open))
                        .approval(approvalId)
                        .module(LeaveIntakeService.MODULE));
        if (backDated && effect.isPresent()) {
            // SRS-LV-10 / SRS-21-7 (plan 13 phase 13b): the locked months' share is settled by the
            // one retrospective mechanism; this module never computes the arrear.
            // OPEN-QUESTION CLR-131 (BMAD TR-046): SRS-LV-10 "Require a remark" - whose remark and
            // what happens without it are not stated; traced by sanction reference and task.
            retro.raise(
                    new RetroEvents.Raise(
                            task.employeeId(),
                            settleFrom,
                            "LEAVE",
                            "LEAVE",
                            "leave_transaction",
                            task.taskNo(),
                            "Back-dated leave "
                                    + v.eventType()
                                    + " "
                                    + v.leaveType()
                                    + " "
                                    + v.from()
                                    + " to "
                                    + v.to(),
                            checker));
        }
        if (effect.isEmpty()) {
            worklist.raise(
                    checker,
                    new ExceptionWorklist.NewEntry(
                            null,
                            task.employeeId(),
                            LeaveIntakeService.MODULE,
                            UNDEFINED,
                            "BLOCKING",
                            "G2",
                            null,
                            "leave_transaction",
                            "wage_effect_code",
                            "RULE",
                            "M-20",
                            "Leave type "
                                    + v.leaveType()
                                    + " has no pay effect on M-20 ("
                                    + v.from()
                                    + " to "
                                    + v.to()
                                    + ")"));
        }
    }

    /**
     * SRS-LV-34 / LV-16 / LV-17: an approved in-service encashment, valued at the pay on its date,
     * capped by the M-20 limits (the cap shown in its valuation basis), recorded and audited.
     */
    private void encash(Task task, String checker, long approvalId) {
        Sanction s = encashment(task.newValue());
        long id =
                encashments.recordInService(
                        task.employeeId(), s.leaveType(), s.days(), s.date(), approvalId);
        audit.record(
                checker,
                AuditService.Entry.change(
                                "leave_encashment",
                                String.valueOf(id),
                                "days_encashed",
                                null,
                                s.days().toPlainString(),
                                "In-service encashment "
                                        + s.leaveType()
                                        + ", sanction "
                                        + s.sanctionRef())
                        .effective(s.date())
                        .approval(approvalId)
                        .module(LeaveIntakeService.MODULE));
    }

    /**
     * SRS-LV-17 (US-GAP-58): the capped days and the amount the encashment would be recorded at,
     * shown to the Checker; a limit or rate not configured is said, never guessed.
     */
    @Override
    public Optional<String> preview(String fieldCode, long employeeId, String newValue) {
        if (!ENCASHMENT_FIELD.equals(fieldCode)) {
            return Optional.empty();
        }
        Sanction s = encashment(newValue);
        try {
            LeaveEncashmentService.InService v =
                    encashments.valueInService(employeeId, s.leaveType(), s.days(), s.date());
            return Optional.of(
                    v.days().days().toPlainString()
                            + " days"
                            + (v.days().capped() ? " (capped)" : "")
                            + ", amount "
                            + v.amount().toPlainString()
                            + ": "
                            + v.basis());
        } catch (FciException refused) {
            return Optional.of("Not computable: " + refused.getMessage());
        }
    }

    /** The encashment sanction a task carries. */
    record Sanction(String sanctionRef, String leaveType, BigDecimal days, LocalDate date) {}

    private Sanction encashment(String newValue) {
        JsonNode v;
        try {
            v = json.readTree(newValue == null ? "" : newValue);
        } catch (JacksonException ex) {
            throw invalid("the encashment sanction is not readable");
        }
        try {
            return new Sanction(
                    v.path("sanctionRef").asString(""),
                    v.path("leaveType").asString(""),
                    new BigDecimal(v.path("days").asString("")),
                    LocalDate.parse(v.path("encashmentDate").asString("")));
        } catch (DateTimeParseException | NumberFormatException ex) {
            throw invalid("the encashment sanction's days or date are not readable");
        }
    }

    /**
     * The earlier sanction's applied transactions a conversion acts on (not cancellations); none is
     * refused, so the conversion is never applied on top of a leave Payroll never received.
     */
    private List<Map<String, Object>> earlier(Value v, long employeeId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT leave_type_code, period_from, period_to, days, wage_effect_code"
                                + " FROM leave_transaction WHERE employee_id = ? AND"
                                + " hrms_sanction_ref = ? AND leave_event_type <> 'CANCELLATION'",
                        employeeId,
                        v.earlierSanctionRef());
        if (rows.isEmpty()) {
            throw invalid(
                    "the earlier sanction "
                            + v.earlierSanctionRef()
                            + " the conversion acts on has not been applied");
        }
        return rows;
    }

    /** The cancelling mirror of an earlier transaction, under the conversion's task (audited). */
    private void reverse(
            Task task,
            Value v,
            Map<String, Object> e,
            LocalDate from,
            YearMonth open,
            String checker,
            long approvalId) {
        LocalDate to = LeaveParameterService.toLocalDate(e.get("PERIOD_TO"));
        String wageEffect = (String) e.get("WAGE_EFFECT_CODE");
        jdbc.update(
                "INSERT INTO leave_transaction (employee_id, application_id, leave_type_code,"
                        + " period_from, period_to, days, wage_effect_code, leave_event_type,"
                        + " is_back_dated, hrms_sanction_ref, hrms_change_task_id, source_code,"
                        + " period_date) VALUES (?, ?, ?, ?, ?, ?, ?, 'CANCELLATION', ?, ?, ?,"
                        + " 'INTF', ?)",
                task.employeeId(),
                v.applicationId(),
                e.get("LEAVE_TYPE_CODE"),
                Date.valueOf(from),
                Date.valueOf(to),
                e.get("DAYS"),
                wageEffect,
                from.isBefore(open.atDay(1)) ? "Y" : "N",
                v.sanctionRef(),
                task.taskId(),
                Date.valueOf(from.withDayOfMonth(1)));
        audit.record(
                checker,
                AuditService.Entry.change(
                                "leave_transaction",
                                task.taskNo(),
                                "leave_event_type",
                                wageEffect,
                                "CANCELLATION",
                                "Converted by sanction "
                                        + v.sanctionRef()
                                        + ": earlier sanction "
                                        + v.earlierSanctionRef()
                                        + " "
                                        + e.get("LEAVE_TYPE_CODE")
                                        + " "
                                        + from
                                        + " to "
                                        + to
                                        + " reversed")
                        .approval(approvalId)
                        .module(LeaveIntakeService.MODULE));
    }

    /** The received event the task carries. */
    record Value(
            long applicationId,
            String leaveType,
            LocalDate from,
            LocalDate to,
            BigDecimal days,
            String eventType,
            String sanctionRef,
            String earlierSanctionRef) {}

    private Value value(String newValue) {
        JsonNode v;
        try {
            v = json.readTree(newValue == null ? "" : newValue);
        } catch (JacksonException ex) {
            throw invalid("the leave record is not readable");
        }
        if (!v.path("applicationId").canConvertToLong()) {
            throw invalid("the leave record names no received sanction");
        }
        try {
            return new Value(
                    v.path("applicationId").asLong(),
                    v.path("leaveType").asString(""),
                    LocalDate.parse(v.path("from").asString("")),
                    LocalDate.parse(v.path("to").asString("")),
                    new BigDecimal(v.path("days").asString("")),
                    v.path("eventType").asString("ADDITION"),
                    v.path("sanctionRef").asString(""),
                    v.path("earlierSanctionRef").asString(""));
        } catch (DateTimeParseException | NumberFormatException ex) {
            throw invalid("the leave record's period or days are not readable");
        }
    }

    /** The received sanction of the task, of the task's member. */
    private void application(long applicationId, long employeeId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT employee_id FROM leave_application WHERE application_id = ?",
                        applicationId);
        if (rows.isEmpty()
                || ((Number) rows.getFirst().get("EMPLOYEE_ID")).longValue() != employeeId) {
            throw invalid("the received sanction is not this member's");
        }
    }

    /** The member's open month: the current one, or the next when the current is locked. */
    private YearMonth openPeriod(long employeeId, YearMonth current) {
        Long unit =
                employees
                        .member(employeeId)
                        .flatMap(m -> org.cycleUnitOf(m.locationId()))
                        .map(OrgLocation::getId)
                        .orElse(null);
        String yymm = String.format("%04d%02d", current.getYear(), current.getMonthValue());
        return unit != null
                        && payroll.cycle(unit, yymm)
                                .filter(PayrollRecords.Cycle::isLocked)
                                .isPresent()
                ? current.plusMonths(1)
                : current;
    }

    private static FciException invalid(String why) {
        return new FciException(LeaveErrorCode.EVENT, new Object[] {why}, List.of(), null);
    }
}
