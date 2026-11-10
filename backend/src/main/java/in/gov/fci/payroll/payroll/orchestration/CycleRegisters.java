package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.business.CycleGates;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The registers an Actual run produces when it completes, the data sources of gates G4 and G5
 * (SRS-15-18, SRS l.1523-1524; FRS V0.5 §6.11.4 Step 6 "Generates payslips and pre-disbursement
 * registers for the full computed cycle", UC-01 steps 4 and 8; DDL v4.0 {@code
 * cycle_control_total}, {@code cycle_statutory_status}). Written in the run's closing transaction
 * (it is {@code MANDATORY}) and audited in it (R-AUD), so a register never exists without the run
 * of record it describes.
 *
 * <ul>
 *   <li>G4, Salary Control Register (FRS V0.5 §6.21.8: each earning pay code, gross, each deduction
 *       pay code, total deductions, net pay): built from the run's signed component lines, classed
 *       by the component master's type (DDL {@code pay_component.component_type}; SRS-DM-6: the
 *       sign is on the row), never copied from the result header. A line whose sign or type
 *       disagrees with the header therefore shows as a difference on G4.
 *   <li>G4, disbursement register (the pre-disbursement register of Step 6): every CURRENT Actual
 *       result of the instance with its gross, deductions and net. OPEN-QUESTION (PENDING 05
 *       G4-DISB): the SRS names the register but not its content or population (members whose
 *       payment is held, members without bank details).
 *   <li>G5, statutory sub-process status: the run computes CPF / PF, income tax (TDS), Professional
 *       Tax and the NPS deduction for each member (UC-01 step 4; {@link StaffDeductionRunner}). A
 *       process is COMPLETE when its source took part in the run, with the members computed and
 *       those left uncomputed (the members the run could not compute, open COMPUTATION_BLOCKED); a
 *       process whose source is not registered is NOT_STARTED, never reported complete; a failed
 *       run leaves each process FAILED.
 * </ul>
 */
@Component
public class CycleRegisters {

    /** The actor of the system-produced rows in the audit trail (X-04 system actors). */
    static final String ACTOR = "SYSTEM";

    /** The run whose registers are written. Partition keys bound (Oracle review F6). */
    public record Run(
            long cycleId, long runId, long orgUnitId, LocalDate periodDate, String payrollType) {}

    /** One register total. */
    public record Total(
            String source, long members, BigDecimal gross, BigDecimal deductions, BigDecimal net) {
        String text() {
            return members
                    + " members, gross "
                    + gross
                    + ", deductions "
                    + deductions
                    + ", net "
                    + net;
        }
    }

    private static final String MODULE = "C-02";

    private final JdbcTemplate jdbc;
    private final StaffDeductionRunner deductions;
    private final AuditService audit;
    private final Clock clock;

    public CycleRegisters(
            JdbcTemplate jdbc, StaffDeductionRunner deductions, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.deductions = deductions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * FRS V0.5 §6.11.4 Step 6: the registers of a completed Actual run of record (after its older
     * results were superseded).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completed(Run run) {
        Timestamp now = Timestamp.from(clock.instant());
        List<AuditService.Entry> trail = new ArrayList<>();
        String why = "Produced by Actual run " + run.runId() + " (FRS 6.11.4 Step 6)";
        Total register = salaryControlRegister(run);
        for (Total total : List.of(register, disbursementRegister(run))) {
            String before = writeTotal(run, total, now);
            trail.add(
                    AuditService.Entry.change(
                                    "cycle_control_total",
                                    run.cycleId() + ":" + run.payrollType() + ":" + total.source(),
                                    "totals",
                                    before,
                                    total.text(),
                                    why)
                            .module(MODULE));
        }
        long computed = register.members();
        long uncomputed =
                count(
                        "SELECT COUNT(DISTINCT employee_id) FROM payroll_exception WHERE"
                            + " cycle_instance_id = ? AND exception_code = 'COMPUTATION_BLOCKED'"
                            + " AND exception_status = 'OPEN'",
                        run.cycleId());
        Set<String> registered = deductions.statutoryProcesses();
        for (String process : CycleGates.STATUTORY) {
            boolean ran = registered.contains(process);
            String before =
                    writeStatus(
                            run.cycleId(),
                            process,
                            ran ? "COMPLETE" : "NOT_STARTED",
                            ran ? computed : null,
                            ran ? uncomputed : null,
                            ran ? now : null);
            trail.add(statusEntry(run, process, before, ran ? "COMPLETE" : "NOT_STARTED", why));
        }
        audit.recordAll(ACTOR, trail);
    }

    /**
     * A failed Actual run: its statutory sub-processes did not complete (SRS-EH-6: a partial state
     * passes no gate). The totals of the run of record before it stay as they were.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void failed(Run run) {
        long computed = members(run);
        Set<String> registered = deductions.statutoryProcesses();
        String why = "Actual run " + run.runId() + " failed";
        List<AuditService.Entry> trail = new ArrayList<>();
        for (String process : CycleGates.STATUTORY) {
            boolean ran = registered.contains(process);
            String state = ran ? "FAILED" : "NOT_STARTED";
            String before =
                    writeStatus(run.cycleId(), process, state, ran ? computed : null, null, null);
            trail.add(statusEntry(run, process, before, state, why));
        }
        audit.recordAll(ACTOR, trail);
    }

    /**
     * Salary Control Register total: members of the run, earnings and deductions from the signed
     * lines by component type; net is the register's own earnings less deductions.
     */
    Total salaryControlRegister(Run run) {
        Date period = Date.valueOf(run.periodDate());
        long members = members(run);
        BigDecimal earnings = BigDecimal.ZERO;
        BigDecimal deducted = BigDecimal.ZERO;
        List<Map<String, Object>> byType =
                jdbc.queryForList(
                        "SELECT pc.component_type AS ctype, COALESCE(SUM(c.amount), 0) AS amount"
                                + " FROM payroll_result r JOIN payroll_result_component c ON"
                                + " c.payroll_result_id = r.payroll_result_id AND c.period_date ="
                                + " r.period_date AND c.org_unit_id = r.org_unit_id JOIN"
                                + " pay_component pc ON pc.component_id = c.component_id WHERE"
                                + " r.run_id = ? AND r.period_date = ? AND r.org_unit_id = ? AND"
                                + " r.payroll_type = ? GROUP BY pc.component_type",
                        run.runId(),
                        period,
                        run.orgUnitId(),
                        run.payrollType());
        for (Map<String, Object> row : byType) {
            BigDecimal amount = decimal(row.get("AMOUNT"));
            if ("EARNING".equals(row.get("CTYPE"))) {
                earnings = earnings.add(amount);
            } else {
                // DEDUCTION lines are written negative (SRS-DM-6): the register shows them
                // positive.
                deducted = deducted.add(amount.negate());
            }
        }
        return new Total(
                "SALARY_CONTROL_REGISTER",
                members,
                earnings,
                deducted,
                earnings.subtract(deducted));
    }

    /** Disbursement register total: every CURRENT Actual result of the instance. */
    Total disbursementRegister(Run run) {
        Map<String, Object> t =
                jdbc.queryForMap(
                        "SELECT COUNT(*) AS members, COALESCE(SUM(gross_earnings), 0) AS gross,"
                                + " COALESCE(SUM(total_deductions), 0) AS deductions,"
                                + " COALESCE(SUM(net_pay), 0) AS net FROM payroll_result WHERE"
                                + " cycle_instance_id = ? AND period_date = ? AND org_unit_id = ?"
                                + " AND payroll_type = ? AND run_type = 'ACTUAL' AND"
                                + " result_status = 'CURRENT'",
                        run.cycleId(),
                        Date.valueOf(run.periodDate()),
                        run.orgUnitId(),
                        run.payrollType());
        return new Total(
                "DISBURSEMENT_REGISTER",
                ((Number) t.get("MEMBERS")).longValue(),
                decimal(t.get("GROSS")),
                decimal(t.get("DEDUCTIONS")),
                decimal(t.get("NET")));
    }

    /** Upserts one total; returns its text before (null when there was none). */
    private String writeTotal(Run run, Total total, Timestamp now) {
        List<String> before =
                jdbc.query(
                        "SELECT member_count, gross_total, deduction_total, net_total FROM"
                                + " cycle_control_total WHERE cycle_instance_id = ? AND"
                                + " payroll_type = ? AND total_source = ?",
                        (rs, i) ->
                                new Total(
                                                total.source(),
                                                rs.getLong(1),
                                                rs.getBigDecimal(2),
                                                rs.getBigDecimal(3),
                                                rs.getBigDecimal(4))
                                        .text(),
                        run.cycleId(),
                        run.payrollType(),
                        total.source());
        if (before.isEmpty()) {
            jdbc.update(
                    "INSERT INTO cycle_control_total (cycle_instance_id, payroll_type,"
                            + " total_source, member_count, gross_total, deduction_total,"
                            + " net_total, computed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    run.cycleId(),
                    run.payrollType(),
                    total.source(),
                    total.members(),
                    total.gross(),
                    total.deductions(),
                    total.net(),
                    now);
            return null;
        }
        jdbc.update(
                "UPDATE cycle_control_total SET member_count = ?, gross_total = ?,"
                        + " deduction_total = ?, net_total = ?, computed_at = ? WHERE"
                        + " cycle_instance_id = ? AND payroll_type = ? AND total_source = ?",
                total.members(),
                total.gross(),
                total.deductions(),
                total.net(),
                now,
                run.cycleId(),
                run.payrollType(),
                total.source());
        return before.get(0);
    }

    /** Upserts one statutory process state; returns the state before (null when none). */
    private String writeStatus(
            long cycleId,
            String process,
            String state,
            Long computed,
            Long uncomputed,
            Timestamp completedAt) {
        List<String> before =
                jdbc.queryForList(
                        "SELECT process_state FROM cycle_statutory_status WHERE cycle_instance_id"
                                + " = ? AND statutory_process = ?",
                        String.class,
                        cycleId,
                        process);
        if (before.isEmpty()) {
            jdbc.update(
                    "INSERT INTO cycle_statutory_status (cycle_instance_id, statutory_process,"
                            + " process_state, members_computed, members_uncomputed,"
                            + " completed_at) VALUES (?, ?, ?, ?, ?, ?)",
                    cycleId,
                    process,
                    state,
                    computed,
                    uncomputed,
                    completedAt);
            return null;
        }
        jdbc.update(
                "UPDATE cycle_statutory_status SET process_state = ?, members_computed = ?,"
                        + " members_uncomputed = ?, completed_at = ? WHERE cycle_instance_id = ?"
                        + " AND statutory_process = ?",
                state,
                computed,
                uncomputed,
                completedAt,
                cycleId,
                process);
        return before.get(0);
    }

    private static AuditService.Entry statusEntry(
            Run run, String process, String before, String after, String why) {
        return AuditService.Entry.change(
                        "cycle_statutory_status",
                        run.cycleId() + ":" + process,
                        "process_state",
                        before,
                        after,
                        why)
                .module(MODULE);
    }

    /** The results the run wrote (its computed members). */
    private long members(Run run) {
        return count(
                "SELECT COUNT(*) FROM payroll_result WHERE run_id = ? AND period_date = ? AND"
                        + " org_unit_id = ? AND payroll_type = ?",
                run.runId(),
                Date.valueOf(run.periodDate()),
                run.orgUnitId(),
                run.payrollType());
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static BigDecimal decimal(Object value) {
        return (BigDecimal) value;
    }
}
