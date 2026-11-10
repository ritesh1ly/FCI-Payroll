package in.gov.fci.payroll.payroll.batch.computation;

import in.gov.fci.payroll.payroll.api.MemberPaymentHeld;
import in.gov.fci.payroll.payroll.api.MembersComputed;
import in.gov.fci.payroll.payroll.api.MembersPaymentReleased;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.business.PayRules;
import in.gov.fci.payroll.payroll.orchestration.CycleLeases;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * Writes a chunk of results: one {@code payroll_result} per member and its signed {@code
 * payroll_result_component} lines (with the rate and basis used, BR-RULE-007), in the chunk's
 * transaction, so a failed chunk leaves nothing and a restart writes it once. One writer per
 * partition (step scope): it knows its run, cycle and work unit. A member of an Actual run whose
 * payment is held is published ({@link MemberPaymentHeld}, SRS-1-7 / SRS-1-41) in the same
 * transaction, for the X-05 worklist.
 */
public class ResultWriter implements ItemWriter<ComputationProcessor.Computed> {

    private static final String RESULT =
            "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id, payroll_type,"
                + " payroll_yymm, period_date, run_type, run_id, work_unit_id, gross_earnings,"
                + " total_deductions, net_pay, payable_days, result_status, computed_at, is_locked)"
                + " VALUES (?, ?, ?, 'STAFF', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CURRENT', ?, 'N')";

    private static final String LINE =
            "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                + " org_unit_id, employee_id, component_id, amount, rate_applied, basis_amount,"
                + " run_type, is_locked, payroll_yymm, period_date, legacy_pay_code) VALUES (?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, 'N', ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final CycleRuns.OpenRun run;
    private final long workUnitId;
    private final Clock clock;
    private final CycleLeases leases;
    private final ApplicationEventPublisher events;

    public ResultWriter(
            JdbcTemplate jdbc,
            CycleRuns.OpenRun run,
            long workUnitId,
            Clock clock,
            CycleLeases leases,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.run = run;
        this.workUnitId = workUnitId;
        this.clock = clock;
        this.leases = leases;
        this.events = events;
    }

    @Override
    public void write(Chunk<? extends ComputationProcessor.Computed> chunk) {
        // SRS-15-28: the run is active; its lease on the instance does not expire meanwhile.
        leases.heartbeatRun(run.runId());
        Date period = Date.valueOf(run.periodDate());
        Timestamp now = Timestamp.from(clock.instant());
        int days = run.periodDate().lengthOfMonth();
        List<Long> released = new ArrayList<>();
        List<Long> computed = new ArrayList<>();
        for (ComputationProcessor.Computed item : chunk.getItems()) {
            PayRules.Outcome o = item.outcome();
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(
                    c -> {
                        PreparedStatement ps =
                                c.prepareStatement(RESULT, new String[] {"payroll_result_id"});
                        ps.setLong(1, run.cycleId());
                        ps.setLong(2, run.orgUnitId());
                        ps.setLong(3, item.member().employeeId());
                        ps.setString(4, run.yymm());
                        ps.setDate(5, period);
                        ps.setString(6, run.runType().name());
                        ps.setLong(7, run.runId());
                        ps.setLong(8, workUnitId);
                        ps.setBigDecimal(9, o.gross());
                        ps.setBigDecimal(10, o.deductions());
                        ps.setBigDecimal(11, o.net());
                        ps.setInt(12, days);
                        ps.setTimestamp(13, now);
                        return ps;
                    },
                    key);
            long resultId = GeneratedKeys.id(key);
            if (run.runType() == CycleRuns.RunType.ACTUAL) {
                computed.add(item.member().employeeId());
                if (o.paymentHold() != null) {
                    events.publishEvent(
                            new MemberPaymentHeld(
                                    run.cycleId(), item.member().employeeId(), o.paymentHold()));
                } else {
                    released.add(item.member().employeeId());
                }
            }
            List<PayRules.Line> lines = o.lines();
            jdbc.batchUpdate(
                    LINE,
                    lines,
                    lines.size(),
                    (ps, line) -> {
                        ps.setLong(1, resultId);
                        ps.setLong(2, run.cycleId());
                        ps.setLong(3, run.orgUnitId());
                        ps.setLong(4, item.member().employeeId());
                        ps.setLong(5, line.componentId());
                        ps.setBigDecimal(6, line.amount());
                        if (line.rate() == null) {
                            ps.setNull(7, Types.NUMERIC);
                        } else {
                            ps.setBigDecimal(7, line.rate());
                        }
                        if (line.basis() == null) {
                            ps.setNull(8, Types.NUMERIC);
                        } else {
                            ps.setBigDecimal(8, line.basis());
                        }
                        ps.setString(9, run.runType().name());
                        ps.setString(10, run.yymm());
                        ps.setDate(11, period);
                        ps.setInt(12, line.payCode());
                    });
        }
        if (!released.isEmpty()) {
            events.publishEvent(new MembersPaymentReleased(run.cycleId(), released));
        }
        if (!computed.isEmpty()) {
            // UC-01 / X-05: an earlier "computation blocked" of these members no longer holds.
            events.publishEvent(new MembersComputed(run.cycleId(), run.runId(), computed));
        }
    }
}
