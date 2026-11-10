package in.gov.fci.payroll.nps.orchestration;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.nps.api.NpsReceipts;
import in.gov.fci.payroll.nps.business.NpsRules;
import in.gov.fci.payroll.nps.business.NpsRules.Mismatch;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.payroll.api.StatutoryPayCodes;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A back-dated NPS change received from HRMS (plan 18 phase 18d; SRS-9-23, SRS-G-45, SRS-21-1;
 * D18-11): the record of a locked month comes as an I-21 task (field {@code NPS_BACK_DATED}, the
 * value the HRMS record as JSON, read-only) and, approved, is stored as a BACK_DATED record with
 * the difference to what the month settled as the arrear or recovery on I-08 (plan 13 phase 13g):
 * less NPS than was deducted is refunded, more is recovered. Payroll never recalculates the amounts
 * (BR-PEN-002); the employer amount is recorded only (it leaves net pay unchanged, BR-PEN-004).
 *
 * <p>Value: {@code {"payrollYymm":"202607","npsMember":true,"pran":"110012345678",
 * "employeeAmount":5400,"employerAmount":7560}}. A month still open is refused (its monthly record
 * replaces the earlier one, NpsReceipts); so is a flag / amount mismatch, which a back-dated record
 * never carries.
 *
 * <p>SRS-9-23 (US-GAP-291, OD-55): a change with pay effect to the record of a month still open
 * comes as field {@code NPS_CHANGE} (same value, opened by {@link NpsIntakeService}); approved, it
 * replaces the month's MONTHLY record, every changed value audited with the approval, and the task
 * linked to the record. If the month has locked meanwhile, the approved change is settled as a
 * back-dated one (the difference on I-08).
 *
 * <p>Wave C review: the value also carries the effective date as HRMS sent it ({@code
 * effectiveFrom}, null kept) and the record it was made against ({@code basis}); a task whose month
 * received a newer record meanwhile is refused at approval (NPS-409-STALE), the intake having
 * closed it as SUPERSEDED already. The approved record raises or clears its X-05 mismatch like any
 * other ({@link NpsExceptions}); the task shows no month-by-month pay impact (it is the month's own
 * record).
 */
@Component
public class NpsHrmsEffects implements HrmsTaskEffects {

    static final String FIELD = "NPS_BACK_DATED";

    /** US-GAP-291: a change with pay effect to the record of a month still open. */
    public static final String CHANGE_FIELD = "NPS_CHANGE";

    /** The value's key of the record a change was made against (NpsIntakeService.basis). */
    static final String BASIS = "basis";

    /**
     * The record as HRMS sent it.
     *
     * @param effectiveFrom from when the values apply, as HRMS sent it (null when it sent none)
     * @param basis NPS_CHANGE only: the record the change was made against, or null
     */
    record Value(
            String payrollYymm,
            boolean npsMember,
            String pran,
            BigDecimal employeeAmount,
            BigDecimal employerAmount,
            LocalDate effectiveFrom,
            String basis) {

        YearMonth month() {
            return NpsRules.month(payrollYymm).orElseThrow();
        }
    }

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final RetroEvents retro;
    private final AuditService audit;
    private final ObjectMapper json;
    private final EmployeeDirectory employees;
    private final NpsExceptions exceptions;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public NpsHrmsEffects(
            JdbcTemplate jdbc,
            OrgDirectory org,
            PayrollRecords payroll,
            RetroEvents retro,
            AuditService audit,
            ObjectMapper json,
            EmployeeDirectory employees,
            NpsExceptions exceptions) {
        this.jdbc = jdbc;
        this.org = org;
        this.payroll = payroll;
        this.retro = retro;
        this.audit = audit;
        this.json = json;
        this.employees = employees;
        this.exceptions = exceptions;
    }

    @Override
    public Set<String> fields() {
        return Set.of(FIELD, CHANGE_FIELD);
    }

    /** Wave C review: an NPS change is the month's own record, not a change from a date on. */
    @Override
    public boolean showsPayImpact(String fieldCode) {
        return !CHANGE_FIELD.equals(fieldCode);
    }

    @Override
    public void check(String fieldCode, long employeeId, String newValue) {
        Value v = read(newValue);
        if (CHANGE_FIELD.equals(fieldCode)) {
            // US-GAP-291: a month already locked takes the back-dated route instead.
            if (cycleOf(employeeId, v.month()).filter(PayrollRecords.Cycle::isLocked).isPresent()) {
                throw invalid("LOCKED");
            }
            monthly(employeeId, v.payrollYymm());
            return;
        }
        lockedCycle(employeeId, v.month());
    }

    @Override
    public Optional<String> preview(String fieldCode, long employeeId, String newValue) {
        if (!CHANGE_FIELD.equals(fieldCode)) {
            return Optional.empty();
        }
        Value v = read(newValue);
        Map<String, Object> before = NpsIntakeService.current(jdbc, employeeId, v.payrollYymm());
        if (before == null) {
            return Optional.empty();
        }
        return Optional.of(
                "NPS of "
                        + v.payrollYymm()
                        + ": flag "
                        + before.get("HRMS_NPS_FLAG")
                        + " -> "
                        + (v.npsMember() ? "Y" : "N")
                        + ", employee "
                        + amount(before.get("EMPLOYEE_AMOUNT"))
                        + " -> "
                        + amount(v.employeeAmount())
                        + ", employer "
                        + amount(before.get("EMPLOYER_AMOUNT"))
                        + " -> "
                        + amount(v.employerAmount()));
    }

    @Override
    public void apply(Task task, String checker, long approvalId, Instant decidedAt) {
        Value v = read(task.newValue());
        YearMonth month = v.month();
        if (CHANGE_FIELD.equals(task.fieldCode())) {
            requireCurrent(task, v);
            Optional<PayrollRecords.Cycle> cycle = cycleOf(task.employeeId(), month);
            if (cycle.filter(PayrollRecords.Cycle::isLocked).isEmpty()) {
                replaceMonthly(
                        task,
                        v,
                        cycle.map(PayrollRecords.Cycle::cycleInstanceId).orElse(null),
                        checker,
                        approvalId,
                        decidedAt);
                return;
            }
        }
        long cycleId = lockedCycle(task.employeeId(), month);
        BigDecimal settled = settled(task.employeeId(), v.payrollYymm());
        BigDecimal now = v.npsMember() ? v.employeeAmount() : BigDecimal.ZERO;
        BigDecimal difference = settled.subtract(now);
        if (difference.signum() == 0) {
            audit.record(
                    checker,
                    AuditService.Entry.change(
                                    NpsIntakeService.ENTITY,
                                    task.employeeId() + "/" + v.payrollYymm(),
                                    "employee_amount",
                                    settled.toPlainString(),
                                    now.toPlainString(),
                                    "Back-dated NPS change " + task.taskNo() + ": no difference")
                            .approval(approvalId)
                            .module("I-14"));
            return;
        }
        int code =
                StatutoryPayCodes.of(jdbc, "NPS", month.atDay(1))
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                ApiErrorCode.NPS_RECEIPT,
                                                new Object[] {
                                                    "NPS pay code not configured (M-16"
                                                            + " STATUTORY_DEDUCTION NPS)"
                                                },
                                                List.of(),
                                                null));
        long eventId =
                retro.known(
                        new RetroEvents.Known(
                                task.employeeId(),
                                "OTHER",
                                "NPS_BACKDATED",
                                NpsIntakeService.ENTITY,
                                task.taskNo(),
                                "Back-dated NPS of "
                                        + v.payrollYymm()
                                        + " from HRMS ("
                                        + task.hrmsRequestId()
                                        + ")",
                                checker,
                                List.of(new RetroEvents.Line(v.payrollYymm(), code, difference))));
        jdbc.update(
                "UPDATE nps_deduction SET apply_status = 'SUPERSEDED' WHERE employee_id = ? AND"
                        + " payroll_yymm = ? AND record_type = 'BACK_DATED' AND apply_status ="
                        + " 'APPLIED'",
                task.employeeId(),
                v.payrollYymm());
        jdbc.update(
                "INSERT INTO nps_deduction (employee_id, cycle_instance_id, payroll_yymm,"
                    + " record_type, hrms_nps_flag, pran_reference, employee_amount,"
                    + " employer_amount, hrms_effective_from, hrms_received_at, deducted_amount,"
                    + " mismatch_status, apply_status, arrear_event_id, hrms_change_task_id,"
                    + " period_date) VALUES (?, ?, ?, 'BACK_DATED', ?, ?, ?, ?, ?, ?, ?,"
                    + " 'CONSISTENT', 'APPLIED', ?, ?, ?)",
                task.employeeId(),
                cycleId,
                v.payrollYymm(),
                v.npsMember() ? "Y" : "N",
                v.pran(),
                v.employeeAmount(),
                v.employerAmount(),
                task.effectiveDate() == null ? null : Date.valueOf(task.effectiveDate()),
                Timestamp.from(decidedAt),
                v.employeeAmount() == null ? BigDecimal.ZERO : v.employeeAmount(),
                eventId,
                task.taskId(),
                Date.valueOf(month.atDay(1)));
        audit.record(
                checker,
                AuditService.Entry.change(
                                NpsIntakeService.ENTITY,
                                task.employeeId() + "/" + v.payrollYymm(),
                                "employee_amount",
                                settled.toPlainString(),
                                now.toPlainString(),
                                "Back-dated NPS change "
                                        + task.taskNo()
                                        + ": arrear event "
                                        + eventId
                                        + " of "
                                        + difference.toPlainString())
                        .approval(approvalId)
                        .module("I-14"));
    }

    /**
     * US-GAP-291: the approved change replaces the values of the month's MONTHLY record
     * (consistent: the value of a task never carries a mismatch), linked to its task, each changed
     * value audited with the approval (R-AUD).
     */
    private void replaceMonthly(
            Task task, Value v, Long cycleId, String checker, long approvalId, Instant decidedAt) {
        Map<String, Object> before = monthly(task.employeeId(), v.payrollYymm());
        Mismatch previous = Mismatch.valueOf((String) before.get("MISMATCH_STATUS"));
        NpsReceipts.Receipt r =
                new NpsReceipts.Receipt(
                        employees.eisOf(task.employeeId()).orElseThrow(),
                        v.payrollYymm(),
                        v.npsMember(),
                        v.pran(),
                        v.employeeAmount(),
                        v.employerAmount(),
                        v.effectiveFrom());
        Mismatch status =
                NpsRules.status(v.npsMember(), v.employeeAmount(), v.employerAmount(), previous);
        long id = ((Number) before.get("NPS_DEDUCTION_ID")).longValue();
        NpsIntakeService.update(jdbc, id, cycleId, r, status, Timestamp.from(decidedAt));
        jdbc.update(
                "UPDATE nps_deduction SET hrms_change_task_id = ? WHERE nps_deduction_id = ? AND"
                        + " period_date = ?",
                task.taskId(),
                id,
                Date.valueOf(v.month().atDay(1)));
        audit.recordAll(
                checker,
                NpsIntakeService.changes(
                                before,
                                r,
                                status,
                                "NPS change approved through I-21 task " + task.taskNo())
                        .stream()
                        .map(entry -> entry.approval(approvalId))
                        .toList());
        // Wave C review: the approved record raises or clears the month's X-05 mismatch as the
        // intake does (SRS-9-1a).
        exceptions.raiseOrClear(checker, id, task.employeeId(), cycleId, status, v.payrollYymm());
    }

    /**
     * Wave D review 2 (SRS-9-23, SRS-G-21): a stale NPS change is refused before the Checker's OTP
     * is asked; {@link #apply} checks again in the decision.
     */
    @Override
    public void beforeApproval(Task task) {
        if (CHANGE_FIELD.equals(task.fieldCode())) {
            requireCurrent(task, read(task.newValue()));
        }
    }

    /**
     * Wave C review (SRS-9-23): the task is approved only over the record it was made against; a
     * newer record of the month (another change, a PRAN change, a mismatch or its correction)
     * refuses it (NPS-409-STALE).
     *
     * <p>Wave D review 2: a task opened before the value carried its basis (US-GAP-291 as first
     * built) is compared by time instead: it stands while the month's record was last received no
     * later than the task, i.e. no newer record came meanwhile.
     */
    private void requireCurrent(Task task, Value v) {
        Map<String, Object> row =
                NpsIntakeService.current(jdbc, task.employeeId(), v.payrollYymm());
        boolean current =
                row != null
                        && (v.basis() == null
                                ? notNewerThanTask(row, task.taskId())
                                : v.basis().equals(NpsIntakeService.basis(row)));
        if (!current) {
            throw new FciException(
                    ApiErrorCode.NPS_STALE, new Object[] {v.payrollYymm()}, List.of(), null);
        }
    }

    /** A legacy task (no basis): the record was last received no later than the task was. */
    private boolean notNewerThanTask(Map<String, Object> row, long taskId) {
        Object at = row.get("HRMS_RECEIVED_AT");
        java.time.LocalDateTime record =
                switch (at) {
                    case null -> null;
                    case Timestamp ts -> ts.toLocalDateTime();
                    case java.time.LocalDateTime ldt -> ldt;
                    default -> java.time.LocalDateTime.parse(at.toString().replace(' ', 'T'));
                };
        List<Timestamp> task =
                jdbc.queryForList(
                        "SELECT received_at FROM hrms_change_task WHERE hrms_change_task_id = ?",
                        Timestamp.class,
                        taskId);
        if (task.isEmpty() || task.getFirst() == null) {
            return false;
        }
        return record == null || !record.isAfter(task.getFirst().toLocalDateTime());
    }

    /**
     * Wave C review: whether a waiting task's value is the record HRMS sent again (same month,
     * flag, PRAN, amounts and effective date): a resend opens no second task.
     */
    static boolean sameRecord(ObjectMapper json, String taskValue, NpsReceipts.Receipt r) {
        JsonNode n = tree(json, taskValue);
        if (n == null) {
            return false;
        }
        try {
            return Objects.equals(text(n, "payrollYymm"), r.payrollYymm())
                    && n.path("npsMember").asBoolean() == r.npsMember()
                    && Objects.equals(text(n, "pran"), r.pran())
                    && sameAmount(amount(n, "employeeAmount"), r.employeeAmount())
                    && sameAmount(amount(n, "employerAmount"), r.employerAmount())
                    && Objects.equals(
                            text(n, "effectiveFrom"),
                            r.effectiveFrom() == null ? null : r.effectiveFrom().toString());
        } catch (NumberFormatException notAnAmount) {
            return false;
        }
    }

    /** The month (YYYYMM) of a task's value, or null when it is not readable. */
    static String monthOf(ObjectMapper json, String taskValue) {
        JsonNode n = tree(json, taskValue);
        return n == null ? null : text(n, "payrollYymm");
    }

    private static JsonNode tree(ObjectMapper json, String value) {
        try {
            JsonNode n = value == null ? null : json.readTree(value);
            return n != null && n.isObject() ? n : null;
        } catch (JacksonException notJson) {
            return null;
        }
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** The month's MONTHLY record; a change without one is refused (NO_RECORD). */
    private Map<String, Object> monthly(long employeeId, String yymm) {
        Map<String, Object> row = NpsIntakeService.current(jdbc, employeeId, yymm);
        if (row == null) {
            throw invalid("NO_RECORD");
        }
        return row;
    }

    private static String amount(Object value) {
        return value == null ? "-" : ((BigDecimal) value).stripTrailingZeros().toPlainString();
    }

    /**
     * What the month has settled so far: the latest applied back-dated record, else the monthly
     * record's deducted amount, else nothing.
     */
    private BigDecimal settled(long employeeId, String yymm) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT record_type, employee_amount, deducted_amount FROM nps_deduction"
                                + " WHERE employee_id = ? AND payroll_yymm = ? AND apply_status ="
                                + " 'APPLIED' ORDER BY CASE record_type WHEN 'BACK_DATED' THEN 0"
                                + " ELSE 1 END, nps_deduction_id DESC",
                        employeeId,
                        yymm);
        if (rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Object amount = rows.getFirst().get("DEDUCTED_AMOUNT");
        return amount == null ? BigDecimal.ZERO : (BigDecimal) amount;
    }

    /** The member's cycle instance of the month; a month not locked is not back-dated. */
    private long lockedCycle(long employeeId, YearMonth month) {
        return cycleOf(employeeId, month)
                .filter(PayrollRecords.Cycle::isLocked)
                .map(PayrollRecords.Cycle::cycleInstanceId)
                .orElseThrow(() -> invalid("NOT_LOCKED"));
    }

    /** The member's cycle instance of the month, if initiated. */
    private Optional<PayrollRecords.Cycle> cycleOf(long employeeId, YearMonth month) {
        String yymm = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        return employees
                .locationOf(employeeId)
                .flatMap(org::cycleUnitOf)
                .flatMap(unit -> payroll.cycle(unit.getId(), yymm));
    }

    private Value read(String value) {
        JsonNode n;
        try {
            n = value == null ? null : json.readTree(value);
        } catch (JacksonException ex) {
            throw invalid("VALUE");
        }
        if (n == null || !n.isObject()) {
            throw invalid("VALUE");
        }
        Value v;
        try {
            v =
                    new Value(
                            text(n, "payrollYymm"),
                            n.path("npsMember").asBoolean(),
                            text(n, "pran"),
                            amount(n, "employeeAmount"),
                            amount(n, "employerAmount"),
                            effectiveFrom(n),
                            text(n, BASIS));
        } catch (NumberFormatException ex) {
            throw invalid("AMOUNT");
        }
        NpsRules.defect(
                        "I-21",
                        v.payrollYymm(),
                        v.employeeAmount(),
                        v.employerAmount(),
                        v.pran(),
                        v.effectiveFrom())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        Mismatch status =
                NpsRules.status(v.npsMember(), v.employeeAmount(), v.employerAmount(), null);
        if (status != Mismatch.CONSISTENT) {
            throw invalid(status.name());
        }
        return v;
    }

    /** The effective date of the value, or null; a value that is not a date is refused. */
    private static LocalDate effectiveFrom(JsonNode n) {
        String day = text(n, "effectiveFrom");
        try {
            return day == null ? null : LocalDate.parse(day);
        } catch (DateTimeParseException notADate) {
            throw invalid("EFFECTIVE_DATE");
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static BigDecimal amount(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asString());
    }

    private static FciException invalid(String defect) {
        return new FciException(ApiErrorCode.NPS_RECEIPT, new Object[] {defect}, List.of(), null);
    }
}
