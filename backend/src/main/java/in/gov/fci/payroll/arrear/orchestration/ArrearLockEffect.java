package in.gov.fci.payroll.arrear.orchestration;

import in.gov.fci.payroll.arrear.business.ArrearRules;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The lock of a staff cycle (plan 13 phase 13a; SRS-8-8, SRS-8-17; D13-2, D13-3): the approved
 * differentials of each member paid in it become POSTED, and an event with none left APPROVED is
 * POSTED. A recovery the net-pay floor did not let through in full is carried: a new detail of the
 * same event for the rest (never an edit of a posted one, R-DEL), recovered in the next open cycle
 * and visible to the settlement.
 */
@Component
public class ArrearLockEffect {

    private final JdbcTemplate jdbc;

    public ArrearLockEffect(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    public void on(CycleLocked locked) {
        List<Long> members =
                jdbc.queryForList(
                        "SELECT DISTINCT r.employee_id FROM payroll_result r WHERE"
                            + " r.cycle_instance_id = ? AND r.run_type = 'ACTUAL' AND"
                            + " r.result_status = 'CURRENT' AND EXISTS (SELECT 1 FROM arrear_detail"
                            + " d JOIN arrear_event e ON e.arrear_event_id = d.arrear_event_id"
                            + " WHERE d.employee_id = r.employee_id AND d.arrear_status ="
                            + " 'APPROVED' AND e.event_status = 'APPROVED' AND e.posted_to_yymm <="
                            + " ?)",
                        Long.class,
                        locked.cycleInstanceId(),
                        locked.payrollYymm());
        Integer recoveryCode =
                jdbc
                        .queryForList(
                                "SELECT v.numeric_attribute FROM reference_value v JOIN"
                                        + " reference_list l ON l.list_id = v.list_id WHERE"
                                        + " l.list_code = 'ARREAR_PAY_CODE' AND v.value_code ="
                                        + " 'RECOVERY'",
                                BigDecimal.class)
                        .stream()
                        .findFirst()
                        .map(BigDecimal::intValueExact)
                        .orElse(null);
        for (Long member : members) {
            post(locked, member, recoveryCode);
        }
        jdbc.update(
                "UPDATE arrear_event SET event_status = 'POSTED' WHERE event_status = 'APPROVED'"
                    + " AND posted_to_yymm <= ? AND NOT EXISTS (SELECT 1 FROM arrear_detail d WHERE"
                    + " d.arrear_event_id = arrear_event.arrear_event_id AND d.arrear_status ="
                    + " 'APPROVED')",
                locked.payrollYymm());
    }

    private void post(CycleLocked locked, long employeeId, Integer recoveryCode) {
        List<Map<String, Object>> due =
                jdbc.queryForList(
                        "SELECT d.arrear_detail_id, d.arrear_event_id, d.component_id,"
                                + " d.arrear_amount, c.legacy_pay_code FROM arrear_detail d JOIN"
                                + " arrear_event e ON e.arrear_event_id = d.arrear_event_id JOIN"
                                + " pay_component c ON c.component_id = d.component_id WHERE"
                                + " d.employee_id = ? AND d.arrear_status = 'APPROVED' AND"
                                + " e.event_status = 'APPROVED' AND e.posted_to_yymm <= ? ORDER BY"
                                + " d.arrear_detail_id",
                        employeeId,
                        locked.payrollYymm());
        ArrearRules.Split split =
                ArrearRules.split(
                        due.stream()
                                .map(
                                        r ->
                                                new ArrearRules.Differential(
                                                        ((Number) r.get("LEGACY_PAY_CODE"))
                                                                .intValue(),
                                                        (BigDecimal) r.get("ARREAR_AMOUNT")))
                                .toList());
        BigDecimal recovered = recovered(locked, employeeId, recoveryCode);
        for (Map<String, Object> r : due) {
            jdbc.update(
                    "UPDATE arrear_detail SET arrear_status = 'POSTED' WHERE arrear_detail_id = ?",
                    r.get("ARREAR_DETAIL_ID"));
        }
        BigDecimal rest = split.recovery().subtract(recovered);
        if (rest.signum() > 0) {
            Map<String, Object> first =
                    due.stream()
                            .filter(r -> ((BigDecimal) r.get("ARREAR_AMOUNT")).signum() < 0)
                            .findFirst()
                            .orElse(due.getFirst());
            YearMonth month =
                    YearMonth.of(
                            Integer.parseInt(locked.payrollYymm().substring(0, 4)),
                            Integer.parseInt(locked.payrollYymm().substring(4)));
            jdbc.update(
                    "INSERT INTO arrear_detail (arrear_event_id, employee_id, component_id,"
                        + " differential_yymm, arrear_amount, arrear_status, period_date) VALUES"
                        + " (?, ?, ?, ?, ?, 'APPROVED', ?)",
                    first.get("ARREAR_EVENT_ID"),
                    employeeId,
                    first.get("COMPONENT_ID"),
                    locked.payrollYymm(),
                    rest.negate(),
                    Date.valueOf(month.atDay(1)));
        }
    }

    /** The arrear recovery the member's result of the cycle carries (a positive amount). */
    private BigDecimal recovered(CycleLocked locked, long employeeId, Integer recoveryCode) {
        if (recoveryCode == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(rc.amount), 0) FROM payroll_result r JOIN"
                            + " payroll_result_component rc ON rc.payroll_result_id ="
                            + " r.payroll_result_id JOIN pay_component c ON c.component_id ="
                            + " rc.component_id WHERE r.cycle_instance_id = ? AND r.employee_id = ?"
                            + " AND r.run_type = 'ACTUAL' AND r.result_status = 'CURRENT' AND"
                            + " c.legacy_pay_code = ?",
                        BigDecimal.class,
                        locked.cycleInstanceId(),
                        employeeId,
                        recoveryCode);
        return sum == null ? BigDecimal.ZERO : sum.abs();
    }
}
