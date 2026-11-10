package in.gov.fci.payroll.payroll.batch.cycle;

import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.NetPayFloorChecked;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.payroll.orchestration.CycleLeases;
import in.gov.fci.payroll.payroll.orchestration.CycleRegisters;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The batch side of the payroll cycle (target DDL v4.0 S08): finds the cycle instance of a (period,
 * unit), opens and closes the computation run, records its work units, and supersedes or purges
 * older results. JDBC on purpose: the batch writes many rows and its SQL stays portable (Oracle /
 * H2).
 *
 * <ul>
 *   <li>a SIMULATION (dry run) may run in any state but LOCKED and changes no state; it initiates
 *       the cycle instance if there is none yet (the period must be in the payroll calendar); only
 *       the latest simulation's rows are kept (v4.0: simulation rows are transient);
 *   <li>an ACTUAL computation needs an instance FROZEN (or COMPUTED: a re-computation); when it
 *       completes, older ACTUAL results are SUPERSEDED and the instance is COMPUTED with this run
 *       of record; a failed ACTUAL run's results are QUARANTINED.
 * </ul>
 */
@Component
@Profile("batch")
public class CycleRuns {

    public enum RunType {
        ACTUAL,
        SIMULATION
    }

    /** The resolved cycle and the run opened for a job execution. */
    public record OpenRun(
            long runId,
            long cycleId,
            long orgUnitId,
            String yymm,
            LocalDate periodDate,
            RunType runType) {}

    /**
     * SRS-15-30 / plan 05 D-9: the OPEN returns of the cycle that name an earlier run than the one
     * completing (parameters: the completing run, the cycle, the completing run). Earlier is by the
     * cycle's run number of the same run type (uk_computation_run), never by run identity (Oracle
     * review F2).
     */
    static final String RECOMPUTED_RETURNS =
            "UPDATE cycle_return SET return_status = 'RECOMPUTED', recompute_run_id = ? WHERE"
                    + " cycle_instance_id = ? AND return_status = 'OPEN' AND run_id IN (SELECT"
                    + " o.run_id FROM computation_run o JOIN computation_run n ON"
                    + " n.cycle_instance_id = o.cycle_instance_id AND n.run_type = o.run_type"
                    + " WHERE n.run_id = ? AND o.run_no < n.run_no)";

    /** The payroll type this engine computes (the C-02 staff engine; labour: modules 20-22). */
    static final String PAYROLL_TYPE = "STAFF";

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final Clock clock;
    private final NetPayFloors floors;
    private final ApplicationEventPublisher events;
    private final CycleLeases leases;
    private final CycleRegisters registers;

    public CycleRuns(
            JdbcTemplate jdbc,
            OrgDirectory org,
            Clock clock,
            NetPayFloors floors,
            CycleLeases leases,
            ApplicationEventPublisher events,
            CycleRegisters registers) {
        this.registers = registers;
        this.events = events;
        this.jdbc = jdbc;
        this.org = org;
        this.clock = clock;
        this.floors = floors;
        this.leases = leases;
    }

    /**
     * The cycle unit of a requested location (the unit itself or its nearest cycle-unit ancestor).
     */
    public OrgLocation cycleUnit(long locationId) {
        return org.cycleUnitOf(locationId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No payroll cycle unit above location " + locationId));
    }

    /**
     * Opens the run of a job execution: resolves (or, for a simulation, initiates) the instance.
     */
    @Transactional
    public OpenRun open(
            long requestId,
            long locationId,
            YearMonth period,
            RunType type,
            String parameterHash,
            String versionSetHash) {
        OrgLocation unit = cycleUnit(locationId);
        String yymm = CycleKeys.yymm(period);
        String requester =
                jdbc.queryForObject(
                        "SELECT requested_by_login FROM batch_job_request WHERE job_request_id = ?",
                        String.class,
                        requestId);
        List<Map<String, Object>> found =
                jdbc.queryForList(
                        "SELECT cycle_instance_id, cycle_status FROM payroll_cycle_instance WHERE"
                                + " payroll_yymm = ? AND org_unit_id = ?",
                        yymm,
                        unit.getId());
        long cycleId;
        String status;
        if (found.isEmpty()) {
            if (type == RunType.ACTUAL) {
                throw new IllegalStateException(
                        "CYCLE_NOT_INITIATED: initiate and freeze the "
                                + period
                                + " cycle of "
                                + unit.getCode()
                                + " on C-02 before the computation");
            }
            cycleId = initiate(unit, period, truncate(requester));
            status = "INITIATED";
        } else {
            cycleId = ((Number) found.get(0).get("CYCLE_INSTANCE_ID")).longValue();
            status = String.valueOf(found.get(0).get("CYCLE_STATUS"));
        }
        if ("LOCKED".equals(status)) {
            throw new IllegalStateException(
                    "CYCLE_LOCKED: the " + period + " cycle of " + unit.getCode() + " is locked");
        }
        if (type == RunType.ACTUAL && !("FROZEN".equals(status) || "COMPUTED".equals(status))) {
            throw new IllegalStateException(
                    "CYCLE_NOT_FROZEN: the "
                            + period
                            + " cycle of "
                            + unit.getCode()
                            + " is "
                            + status
                            + "; an ACTUAL computation needs it FROZEN (or COMPUTED to"
                            + " re-compute)");
        }
        Integer last =
                jdbc.queryForObject(
                        "SELECT MAX(run_no) FROM computation_run WHERE cycle_instance_id = ? AND"
                                + " run_type = ?",
                        Integer.class,
                        cycleId,
                        type.name());
        int runNo = last == null ? 1 : last + 1;
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                c -> {
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT INTO computation_run (cycle_instance_id, run_type,"
                                        + " run_no, payroll_type, run_trigger, population_scope,"
                                        + " start_mode, run_status, started_by, started_at,"
                                        + " parameter_hash, parameters_json, rule_version_set_hash)"
                                        + " VALUES (?, ?, ?, 'STAFF', 'ON_DEMAND', 'FULL', 'NEW',"
                                        + " 'RUNNING', ?, ?, ?, ?, ?)",
                                    new String[] {"run_id"});
                    ps.setLong(1, cycleId);
                    ps.setString(2, type.name());
                    ps.setInt(3, runNo);
                    ps.setString(4, truncate(requester));
                    ps.setTimestamp(5, Timestamp.from(clock.instant()));
                    ps.setString(6, parameterHash);
                    ps.setString(
                            7,
                            "{\"jobRequestId\":"
                                    + requestId
                                    + ",\"locationId\":"
                                    + locationId
                                    + ",\"payPeriod\":\""
                                    + period
                                    + "\"}");
                    ps.setString(8, versionSetHash);
                    return ps;
                },
                key);
        long runId = GeneratedKeys.id(key);
        // SRS-15-28: the run holds the instance's lease until it closes; a freeze, lock or another
        // computation is refused meanwhile, naming it. Held by another action: the run fails here,
        // before any member is read (the insert above rolls back with it).
        lease(cycleId, truncate(requester), runId, period, unit);
        if (type == RunType.SIMULATION) {
            purgeSimulations(cycleId, runId);
        }
        return new OpenRun(runId, cycleId, unit.getId(), yymm, period.atDay(1), type);
    }

    /**
     * A restart continues its run: it takes the instance's lease again (or keeps it, when the
     * failure never released it). Refused while another action holds the lease.
     */
    @Transactional
    public void retakeLease(OpenRun run) {
        String holder =
                jdbc.queryForObject(
                        "SELECT started_by FROM computation_run WHERE run_id = ?",
                        String.class,
                        run.runId());
        lease(
                run.cycleId(),
                holder,
                run.runId(),
                YearMonth.from(run.periodDate()),
                cycleUnit(run.orgUnitId()));
    }

    /**
     * Takes (or keeps) the run's lease; refused, the job fails like the other state refusals, with
     * a message that names the holder and since when. C-13 shows the deepest cause, so the
     * FciException is not kept as one; the lease itself is untouched.
     */
    private void lease(
            long cycleId, String holder, long runId, YearMonth period, OrgLocation unit) {
        try {
            leases.acquireForRun(cycleId, holder, runId);
        } catch (FciException held) {
            Object[] args = held.getMessageArgs();
            throw new IllegalStateException(
                    "CYCLE_LEASE_HELD: the "
                            + period
                            + " cycle of "
                            + unit.getCode()
                            + " is held by "
                            + args[1]
                            + " ("
                            + args[0]
                            + " since "
                            + args[2]
                            + "); wait until it ends, or request a release on C-02");
        }
    }

    /** Closes a run: status, counts; an ACTUAL run of record supersedes the older ones. */
    @Transactional
    public void close(OpenRun run, boolean completed, long eligible) {
        close(run, completed, eligible, false);
    }

    /**
     * As {@link #close(OpenRun, boolean, long)}; {@code resumed} when this execution continues the
     * run after a failure (BR-CYC-012). A resumed execution reads only the members its predecessors
     * did not commit, so its count alone is not the run's eligible population: G0 compares the
     * run's whole result count with its whole eligible population (SRS l.1519; found by BMAD
     * TR-070, test batch B 2026-11-04 - a resumed run never reconciled). A failed execution adds
     * its committed count to the one recorded before; a completed one counts every member computed
     * or left uncomputed (open COMPUTATION_BLOCKED).
     */
    @Transactional
    public void close(OpenRun run, boolean completed, long eligible, boolean resumed) {
        leases.releaseRun(run.cycleId(), run.runId());
        if (completed && resumed) {
            // A completed run read every member once overall, but its executions' counts overlap
            // (a rolled-back chunk is read and skipped again on resume): the eligible population
            // is every member computed plus every member the run could not compute (still open on
            // X-05), as the single-execution count would give.
            Long computed =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM payroll_result WHERE run_id = ?",
                            Long.class,
                            run.runId());
            Long blocked =
                    jdbc.queryForObject(
                            "SELECT COUNT(DISTINCT employee_id) FROM payroll_exception WHERE"
                                    + " cycle_instance_id = ? AND exception_code ="
                                    + " 'COMPUTATION_BLOCKED' AND exception_status = 'OPEN'",
                            Long.class,
                            run.cycleId());
            eligible = (computed == null ? 0 : computed) + (blocked == null ? 0 : blocked);
        } else if (resumed) {
            Long before =
                    jdbc.queryForObject(
                            "SELECT eligible_count FROM computation_run WHERE run_id = ?",
                            Long.class,
                            run.runId());
            eligible += before == null ? 0 : before;
        }
        Integer results =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_result WHERE run_id = ?",
                        Integer.class,
                        run.runId());
        Integer committed =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM computation_work_unit WHERE run_id = ? AND"
                                + " unit_status = 'COMMITTED'",
                        Integer.class,
                        run.runId());
        Integer failed =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM computation_work_unit WHERE run_id = ? AND"
                                + " unit_status = 'FAILED'",
                        Integer.class,
                        run.runId());
        Integer total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM computation_work_unit WHERE run_id = ?",
                        Integer.class,
                        run.runId());
        jdbc.update(
                "UPDATE computation_run SET run_status = ?, completed_at = ?, result_count = ?,"
                    + " eligible_count = ?, unit_total = ?, unit_committed = ?, unit_failed = ?,"
                    + " failure_class = ?, row_version = row_version + 1 WHERE run_id = ?",
                completed ? "COMPLETE" : "FAILED",
                Timestamp.from(clock.instant()),
                results,
                eligible,
                total,
                committed,
                failed,
                completed ? null : "INFRASTRUCTURE",
                run.runId());
        if (run.runType() != RunType.ACTUAL) {
            return;
        }
        engineStatus(run, completed, results);
        if (!completed) {
            jdbc.update(
                    "UPDATE payroll_result SET result_status = 'QUARANTINED' WHERE run_id = ? AND"
                            + " is_locked = 'N'",
                    run.runId());
            // G5 (SRS-15-18, SRS-EH-6): the run's statutory sub-processes did not complete.
            registers.failed(registersOf(run));
            return;
        }
        // BR-CYC-012: a run resumed after a failure keeps the units it committed before it, and
        // those results were QUARANTINED when it failed (SRS-15-27). Complete now, the run is no
        // longer failed or partial: every result of the run of record is CURRENT, so the lock
        // and the duplicate-payment checks (which read CURRENT results) see all of its members.
        jdbc.update(
                "UPDATE payroll_result SET result_status = 'CURRENT' WHERE run_id = ? AND"
                        + " result_status = 'QUARANTINED' AND is_locked = 'N'",
                run.runId());
        jdbc.update(
                "UPDATE payroll_result SET result_status = 'SUPERSEDED' WHERE cycle_instance_id = ?"
                    + " AND run_type = 'ACTUAL' AND run_id <> ? AND result_status = 'CURRENT' AND"
                    + " is_locked = 'N'",
                run.cycleId(),
                run.runId());
        jdbc.update(
                "UPDATE computation_run SET run_status = 'SUPERSEDED' WHERE cycle_instance_id = ?"
                        + " AND run_type = 'ACTUAL' AND run_id <> ? AND run_status = 'COMPLETE'",
                run.cycleId(),
                run.runId());
        // SRS-15-30 / plan 05 D-9: a fresh computation completed after a Checker's return answers
        // it; the cycle can be verified again (COMPUTED below). "After" is by the cycle's ACTUAL
        // run number (uk_computation_run), never by run identity: identities are cached per
        // instance and NOORDER (Oracle review F2), so a later run can carry a lower id. A return
        // is refused while a run holds the cycle's lease, so every OPEN return names an older run.
        jdbc.update(RECOMPUTED_RETURNS, run.runId(), run.cycleId(), run.runId());
        controlTotal(run);
        // G4 / G5 (SRS-15-18; FRS V0.5 6.11.4 Step 6): the Salary Control Register, the
        // disbursement register and the statutory sub-process status of the run of record, in
        // this transaction.
        registers.completed(registersOf(run));
        quarantine(run);
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'COMPUTED', current_run_id = ?,"
                    + " row_version = row_version + 1 WHERE cycle_instance_id = ? AND cycle_status"
                    + " IN ('FROZEN', 'COMPUTED')",
                run.runId(),
                run.cycleId());
    }

    private static CycleRegisters.Run registersOf(OpenRun run) {
        return new CycleRegisters.Run(
                run.cycleId(), run.runId(), run.orgUnitId(), run.periodDate(), PAYROLL_TYPE);
    }

    /**
     * Gate G1's source (SRS-15-18): the state of this engine for the instance (COMPLETE or FAILED,
     * the member count, a re-run counted). One row per instance and payroll type.
     */
    void engineStatus(OpenRun run, boolean completed, long members) {
        Timestamp now = Timestamp.from(clock.instant());
        String state = completed ? "COMPLETE" : "FAILED";
        String detail =
                completed ? null : "Run " + run.runId() + " did not complete every work unit";
        int updated =
                jdbc.update(
                        "UPDATE cycle_engine_status SET engine_state = ?, member_count = ?,"
                            + " completed_at = ?, failure_detail = ?, rerun_count = rerun_count + 1"
                            + " WHERE cycle_instance_id = ? AND payroll_type = ?",
                        state,
                        members,
                        now,
                        detail,
                        run.cycleId(),
                        PAYROLL_TYPE);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO cycle_engine_status (cycle_instance_id, payroll_type,"
                            + " engine_state, member_count, started_at, completed_at,"
                            + " failure_detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    run.cycleId(),
                    PAYROLL_TYPE,
                    state,
                    members,
                    now,
                    now,
                    detail);
        }
    }

    /**
     * Gate G4's first source (SRS-15-18): the totals of this run's results (PAYROLL_RESULT). The
     * Salary Control Register and disbursement register totals are written next by {@link
     * CycleRegisters}.
     */
    void controlTotal(OpenRun run) {
        Map<String, Object> t =
                jdbc.queryForMap(
                        "SELECT COUNT(*) AS members, COALESCE(SUM(gross_earnings), 0) AS gross,"
                                + " COALESCE(SUM(total_deductions), 0) AS deductions,"
                                + " COALESCE(SUM(net_pay), 0) AS net FROM payroll_result WHERE"
                                + " run_id = ?",
                        run.runId());
        Timestamp now = Timestamp.from(clock.instant());
        int updated =
                jdbc.update(
                        "UPDATE cycle_control_total SET member_count = ?, gross_total = ?,"
                                + " deduction_total = ?, net_total = ?, computed_at = ? WHERE"
                                + " cycle_instance_id = ? AND payroll_type = ? AND total_source ="
                                + " 'PAYROLL_RESULT'",
                        t.get("MEMBERS"),
                        t.get("GROSS"),
                        t.get("DEDUCTIONS"),
                        t.get("NET"),
                        now,
                        run.cycleId(),
                        PAYROLL_TYPE);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO cycle_control_total (cycle_instance_id, payroll_type,"
                            + " total_source, member_count, gross_total, deduction_total,"
                            + " net_total, computed_at) VALUES (?, ?, 'PAYROLL_RESULT', ?, ?, ?,"
                            + " ?, ?)",
                    run.cycleId(),
                    PAYROLL_TYPE,
                    t.get("MEMBERS"),
                    t.get("GROSS"),
                    t.get("DEDUCTIONS"),
                    t.get("NET"),
                    now);
        }
    }

    /**
     * Gate G6's source (SRS-15-13, CYC-19): every result of this run with a negative or zero net,
     * or below the M-14 floor in force, is put in the quarantine queue for review (again UNREVIEWED
     * when a re-computation still produces it). Never deleted. With no floor version in force the
     * cycle's records are held on C-08 until one is activated ({@link NetPayFloorChecked}, SRS
     * l.2028).
     */
    void quarantine(OpenRun run) {
        Optional<NetPayFloors.Floor> floor = floors.inForce(PAYROLL_TYPE, run.periodDate());
        events.publishEvent(
                new NetPayFloorChecked(
                        run.cycleId(),
                        run.runId(),
                        PAYROLL_TYPE,
                        run.periodDate(),
                        floor.isPresent()));
        jdbc.query(
                "SELECT employee_id, gross_earnings, net_pay FROM payroll_result WHERE run_id = ?",
                rs -> {
                    String reason =
                            quarantineReason(rs.getBigDecimal(3), rs.getBigDecimal(2), floor);
                    if (reason != null) {
                        quarantineMember(run.cycleId(), rs.getLong(1), reason, rs.getBigDecimal(3));
                    }
                },
                run.runId());
    }

    /** SRS-15-13: NEGATIVE_NET, ZERO_NET, FLOOR_BREACH (M-14 floor in force), else none. */
    static String quarantineReason(
            BigDecimal net, BigDecimal gross, Optional<NetPayFloors.Floor> floor) {
        if (net.signum() < 0) {
            return "NEGATIVE_NET";
        }
        if (net.signum() == 0) {
            return "ZERO_NET";
        }
        return floor.isPresent() && floor.get().breachedBy(net, gross) ? "FLOOR_BREACH" : null;
    }

    private void quarantineMember(long cycleId, long employee, String reason, BigDecimal net) {
        int updated =
                jdbc.update(
                        "UPDATE payroll_quarantine SET computed_net_pay = ?, review_status ="
                                + " 'UNREVIEWED', reviewed_by = NULL, reviewed_at = NULL,"
                                + " review_reason = NULL WHERE cycle_instance_id = ? AND"
                                + " employee_id = ? AND quarantine_reason = ?",
                        net,
                        cycleId,
                        employee,
                        reason);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO payroll_quarantine (cycle_instance_id, employee_id,"
                            + " quarantine_reason, computed_net_pay) VALUES (?, ?, ?, ?)",
                    cycleId,
                    employee,
                    reason,
                    net);
        }
    }

    /**
     * One work unit per partition (v4.0 computation_work_unit); {@code attempt} counts restarts.
     * SRS-15-26 (batch campaign 2026-10-30, R3): a partition restarted after its node was lost
     * before its first commit has no unit id in its saved context; its unit is then found by the
     * natural key (run, unit number) and claimed again, never inserted twice.
     */
    @Transactional
    public long claimUnit(
            long runId, int unitNo, long orgUnitId, int planned, String worker, Long existing) {
        Timestamp now = Timestamp.from(clock.instant());
        leases.heartbeatRun(runId);
        Long unit = existing != null ? existing : unitOf(runId, unitNo);
        if (unit != null) {
            jdbc.update(
                    "UPDATE computation_work_unit SET unit_status = 'CLAIMED', worker_id = ?,"
                            + " claimed_at = ?, heartbeat_at = ?, attempt_count = attempt_count + 1"
                            + " WHERE work_unit_id = ?",
                    worker,
                    now,
                    now,
                    unit);
            return unit;
        }
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                c -> {
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT INTO computation_work_unit (run_id, unit_no,"
                                        + " org_unit_id, member_count, unit_status, worker_id,"
                                        + " claimed_at, heartbeat_at, attempt_count) VALUES (?, ?,"
                                        + " ?, ?, 'CLAIMED', ?, ?, ?, 1)",
                                    new String[] {"work_unit_id"});
                    ps.setLong(1, runId);
                    ps.setInt(2, unitNo);
                    ps.setLong(3, orgUnitId);
                    ps.setInt(4, planned);
                    ps.setString(5, worker);
                    ps.setTimestamp(6, now);
                    ps.setTimestamp(7, now);
                    return ps;
                },
                key);
        return GeneratedKeys.id(key);
    }

    /** The work unit of {@code unitNo} in the run, if a previous attempt created it. */
    private Long unitOf(long runId, int unitNo) {
        return jdbc
                .query(
                        "SELECT work_unit_id FROM computation_work_unit WHERE run_id = ? AND"
                                + " unit_no = ?",
                        (rs, i) -> rs.getLong(1),
                        runId,
                        unitNo)
                .stream()
                .findFirst()
                .orElse(null);
    }

    @Transactional
    public void finishUnit(long workUnitId, boolean committed, long results, String failure) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                "UPDATE computation_work_unit SET unit_status = ?, committed_at = ?, heartbeat_at ="
                        + " ?, results_committed = ?, failure_detail = ? WHERE work_unit_id = ?",
                committed ? "COMMITTED" : "FAILED",
                committed ? now : null,
                now,
                results,
                failure == null
                        ? null
                        : (failure.length() <= 2000 ? failure : failure.substring(0, 2000)),
                workUnitId);
    }

    private long initiate(OrgLocation unit, YearMonth period, String by) {
        String yymm = CycleKeys.yymm(period);
        List<Long> calendar =
                jdbc.queryForList(
                        "SELECT calendar_id FROM payroll_calendar WHERE payroll_yymm = ?",
                        Long.class,
                        yymm);
        if (calendar.isEmpty()) {
            throw new IllegalStateException(
                    "NO_PAYROLL_CALENDAR: " + period + " is not in the payroll calendar (M-10)");
        }
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                c -> {
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id,"
                                        + " org_unit_level, fin_year, calendar_id, cycle_status,"
                                        + " trigger_type, initiated_by, initiated_at) VALUES (?, ?,"
                                        + " ?, ?, ?, 'INITIATED', 'ON_DEMAND', ?, ?)",
                                    new String[] {"cycle_instance_id"});
                    ps.setString(1, yymm);
                    ps.setLong(2, unit.getId());
                    ps.setString(3, CycleKeys.level(unit.getType()));
                    ps.setString(4, CycleKeys.finYear(period));
                    ps.setLong(5, calendar.get(0));
                    ps.setString(6, by);
                    ps.setTimestamp(7, Timestamp.from(clock.instant()));
                    return ps;
                },
                key);
        return GeneratedKeys.id(key);
    }

    private void purgeSimulations(long cycleId, long keepRunId) {
        jdbc.update(
                "DELETE FROM payroll_result_component WHERE run_type = 'SIMULATION' AND is_locked ="
                    + " 'N' AND payroll_result_id IN (SELECT payroll_result_id FROM payroll_result"
                    + " WHERE cycle_instance_id = ? AND run_type = 'SIMULATION' AND run_id <> ?)",
                cycleId,
                keepRunId);
        jdbc.update(
                "DELETE FROM payroll_result WHERE cycle_instance_id = ? AND run_type = 'SIMULATION'"
                        + " AND run_id <> ? AND is_locked = 'N'",
                cycleId,
                keepRunId);
    }

    /**
     * A requester login (batch_job_request.requested_by_login, up to 150, NOT NULL) cut to the 50
     * of computation_run.started_by and payroll_cycle_instance.initiated_by.
     */
    private static String truncate(String login) {
        return login.length() <= 50 ? login : login.substring(0, 50);
    }
}
