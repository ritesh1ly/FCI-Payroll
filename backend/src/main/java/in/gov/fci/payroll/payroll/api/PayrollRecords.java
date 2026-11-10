package in.gov.fci.payroll.payroll.api;

import in.gov.fci.payroll.platform.persistence.RowCounts;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only lookups of payroll records for other modules (target DDL v4.0 S09 {@code
 * payroll_result}). A "Payroll ID" on the screens (X-06, I-20, I-23; SRS-G-11) is a {@code
 * payroll_result_id}: one member's computed result of one run.
 */
@Component
public class PayrollRecords {

    /**
     * One payroll result.
     *
     * @param runType ACTUAL (a run of record) or SIMULATION (a dry run)
     * @param resultStatus CURRENT, SUPERSEDED or QUARANTINED
     */
    public record Result(
            Long payrollResultId,
            Long employeeId,
            Long cycleInstanceId,
            Long orgUnitId,
            String payrollYymm,
            String runType,
            String resultStatus,
            BigDecimal netPay,
            boolean locked) {}

    /**
     * A payroll cycle instance (target DDL v4.0 S08) of one cycle unit and month.
     *
     * @param cycleStatus INITIATED, FROZEN, COMPUTED, VERIFIED or LOCKED (forward only)
     */
    public record Cycle(
            Long cycleInstanceId, Long orgUnitId, String payrollYymm, String cycleStatus) {
        public boolean isLocked() {
            return "LOCKED".equals(cycleStatus);
        }
    }

    private final JdbcTemplate jdbc;

    public PayrollRecords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The result with this Payroll ID, if any. */
    @Transactional(readOnly = true)
    public Optional<Result> result(Long payrollResultId) {
        if (payrollResultId == null) {
            return Optional.empty();
        }
        List<Result> rows =
                jdbc.query(
                        "SELECT payroll_result_id, employee_id, cycle_instance_id, org_unit_id,"
                                + " payroll_yymm, run_type, result_status, net_pay, is_locked FROM"
                                + " payroll_result WHERE payroll_result_id = ?",
                        (rs, i) ->
                                new Result(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getObject(3, Long.class),
                                        rs.getLong(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getBigDecimal(8),
                                        "Y".equals(rs.getString(9))),
                        payrollResultId);
        return rows.stream().findFirst();
    }

    /**
     * The staff cycle instance of a cycle unit for a month (YYYYMM), if it has been initiated. The
     * caller resolves the unit first ({@code OrgDirectory.cycleUnitOf}: OD-11).
     */
    @Transactional(readOnly = true)
    public Optional<Cycle> cycle(Long cycleUnitId, String payrollYymm) {
        return jdbc
                .query(
                        "SELECT cycle_instance_id, org_unit_id, payroll_yymm, cycle_status FROM"
                            + " payroll_cycle_instance WHERE org_unit_id = ? AND payroll_yymm = ?",
                        (rs, i) ->
                                new Cycle(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getString(3),
                                        rs.getString(4)),
                        cycleUnitId,
                        payrollYymm)
                .stream()
                .findFirst();
    }

    /**
     * Whether the member's salary for the month was paid by a regular run: a CURRENT result of an
     * ACTUAL run (a dry run pays nothing). Used by the I-20 / I-23 duplicate-payment checks.
     */
    @Transactional(readOnly = true)
    public boolean paidInRegularRun(Long employeeId, String payrollYymm) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_result WHERE employee_id = ? AND payroll_yymm"
                                + " = ? AND run_type = 'ACTUAL' AND result_status = 'CURRENT'",
                        Integer.class,
                        employeeId,
                        payrollYymm);
        return RowCounts.of(n) > 0;
    }

    /** One month paid by a regular run: the month (YYYYMM) and its net pay. */
    public record PaidMonth(String payrollYymm, BigDecimal netPay) {}

    /**
     * The months after {@code month} the member was paid by a regular run (a CURRENT result of an
     * ACTUAL run), oldest first: for the excess wages of a separated member (SRS-10-13,
     * BR-FNF-001).
     */
    @Transactional(readOnly = true)
    public List<PaidMonth> paidAfter(long employeeId, java.time.YearMonth month) {
        // period_date (the partition key) is the first day of the month: a range on it prunes.
        return jdbc.query(
                "SELECT payroll_yymm, net_pay FROM payroll_result WHERE employee_id = ? AND"
                        + " period_date > ? AND run_type = 'ACTUAL' AND result_status = 'CURRENT'"
                        + " ORDER BY period_date, payroll_result_id",
                (rs, i) -> new PaidMonth(rs.getString(1), rs.getBigDecimal(2)),
                employeeId,
                Date.valueOf(month.atDay(1)));
    }

    /**
     * The earliest period of the member computed by a run of record that starts on or after {@code
     * from}, if any (UC M-11 transfer origin, SRS l.1321; OD-51): an ACTUAL result (a simulation is
     * never a computation, BR-CYC-007) that still stands (not SUPERSEDED by a returned or un-frozen
     * run).
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> firstActualPeriodFrom(long employeeId, LocalDate from) {
        // period_date (the partition key) is the first day of the month: a range on it prunes.
        List<Date> rows =
                jdbc.queryForList(
                        "SELECT MIN(period_date) FROM payroll_result WHERE employee_id = ? AND"
                                + " period_date >= ? AND run_type = 'ACTUAL' AND result_status <>"
                                + " 'SUPERSEDED'",
                        Date.class,
                        employeeId,
                        Date.valueOf(from));
        return rows.isEmpty() || rows.get(0) == null
                ? Optional.empty()
                : Optional.of(rows.get(0).toLocalDate());
    }
}
