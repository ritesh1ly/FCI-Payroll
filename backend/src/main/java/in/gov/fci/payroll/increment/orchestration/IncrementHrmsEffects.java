package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.increment.business.IncrementErrorCode;
import in.gov.fci.payroll.increment.business.IncrementRules;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The I-21 Basic Pay and increment-date fields (plan 17 phase 17a; SRS-13-20, SRS-1-33, SRS-1-61,
 * SRS-13-24; D17-1, D17-7). A Basic Pay received from HRMS, once its task is approved, is a Basic
 * event of the history from its effective date (event HRMS_CHANGE), through the one Basic writer -
 * so a back-dated value raises its arrear; an increment date received from HRMS becomes the
 * member's next increment due date ({@code employee_increment_due}, source HRMS), which the I-05
 * increment run reads. A Basic that is not a stage of the member's scale on its date is refused at
 * receipt and at approval (SRS-1-79, SRS-13-23, BR-INCR-008; US-GAP-9).
 */
@Component
public class IncrementHrmsEffects implements HrmsTaskEffects {

    public static final String BASIC_PAY = "BASIC_PAY";
    public static final String INCREMENT_DATE = "INCREMENT_DATE";

    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final BasicPayService basic;
    private final Scales scales;

    public IncrementHrmsEffects(JdbcTemplate jdbc, BasicPayService basic, Scales scales) {
        this.jdbc = jdbc;
        this.basic = basic;
        this.scales = scales;
    }

    @Override
    public Set<String> fields() {
        return Set.of(BASIC_PAY, INCREMENT_DATE);
    }

    @Override
    public void check(String fieldCode, long employeeId, String newValue) {
        if (BASIC_PAY.equals(fieldCode)) {
            onStage(employeeId, amount(newValue), LocalDate.now(IST));
        } else {
            date(newValue);
        }
    }

    @Override
    public void apply(Task task, String checker, long approvalId, Instant decidedAt) {
        if (BASIC_PAY.equals(task.fieldCode())) {
            LocalDate effective =
                    task.effectiveDate() == null
                            ? decidedAt.atZone(IST).toLocalDate()
                            : task.effectiveDate();
            BigDecimal amount = amount(task.newValue());
            onStage(task.employeeId(), amount, effective);
            basic.apply(
                    new BasicPayService.Change(
                            task.employeeId(),
                            amount,
                            effective,
                            "HRMS_CHANGE",
                            "HRMS",
                            "Basic Pay received from HRMS, task " + task.taskNo(),
                            null,
                            task.hrmsRequestId(),
                            task.taskId(),
                            approvalId,
                            checker,
                            "HRMS_CHANGE",
                            "hrms_change_task",
                            task.taskNo()));
            return;
        }
        LocalDate due = date(task.newValue());
        int changed =
                jdbc.update(
                        "UPDATE employee_increment_due SET next_due_date = ?, source_code ="
                                + " 'HRMS', hrms_change_task_id = ?, updated_by = ?, updated_at = ?"
                                + " WHERE employee_id = ?",
                        Date.valueOf(due),
                        task.taskId(),
                        checker,
                        Timestamp.from(decidedAt),
                        task.employeeId());
        if (changed == 0) {
            jdbc.update(
                    "INSERT INTO employee_increment_due (employee_id, next_due_date, source_code,"
                            + " hrms_change_task_id, updated_by, updated_at) VALUES (?, ?, 'HRMS',"
                            + " ?, ?, ?)",
                    task.employeeId(),
                    Date.valueOf(due),
                    task.taskId(),
                    checker,
                    Timestamp.from(decidedAt));
        }
    }

    /**
     * SRS-1-79 / SRS-13-23: the Basic must be a stage of the member's scale on the day. A scale
     * with no stages configured cannot be checked here (D17-3); whether it blocks too waits for the
     * owner's decision on US-GAP-9 (PENDING), as the run-time block does.
     */
    private void onStage(long employeeId, BigDecimal amount, LocalDate day) {
        scales.ofMember(employeeId, day)
                .filter(s -> !s.stages().isEmpty())
                .flatMap(s -> IncrementRules.stage(amount, s.stages()).map(f -> s))
                .ifPresent(
                        s -> {
                            throw entry(
                                    "Basic Pay "
                                            + amount.toPlainString()
                                            + " is not a stage of pay scale "
                                            + s.bandCode()
                                            + " on "
                                            + day
                                            + " (SRS-13-23)");
                        });
    }

    private static BigDecimal amount(String value) {
        try {
            BigDecimal v = new BigDecimal(value == null ? "" : value.strip());
            if (v.signum() <= 0 || v.scale() > 2) {
                throw entry("Basic Pay must be a positive amount in rupees and paise");
            }
            return v;
        } catch (NumberFormatException ex) {
            throw entry("Basic Pay must be an amount");
        }
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value == null ? "" : value.strip());
        } catch (DateTimeParseException ex) {
            throw entry("the increment date must be a date (YYYY-MM-DD)");
        }
    }

    private static FciException entry(String why) {
        return new FciException(IncrementErrorCode.ENTRY, new Object[] {why}, List.of(), null);
    }
}
