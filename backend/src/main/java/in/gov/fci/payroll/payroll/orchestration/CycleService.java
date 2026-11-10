package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.api.SecondFactor;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-02 Employee / Staff Payroll Computation on the v4.0 cycle model (S08, S09): the payroll cycle
 * of the session's cycle unit for a period, its runs and results, and the state transitions, each
 * with its own permission at that unit and its separation of duties:
 *
 * <ul>
 *   <li>initiate (C-02 CREATE, maker): INITIATED; the period must be in the payroll calendar (M-10)
 *       and not after the current payroll period;
 *   <li>freeze (C-02 SUBMIT, maker, OTP CYCLE_FREEZE_LOCK): FROZEN; HRMS changes after it move to
 *       the next month;
 *   <li>compute: the C-13 job PAYROLL_COMPUTATION (approved launch), FROZEN to COMPUTED;
 *   <li>verify (C-02 APPROVE, checker): COMPUTED to VERIFIED, never by whoever froze the cycle or
 *       started the run of record;
 *   <li>lock (C-02 LOCK, maker; workflow action C02_LOCK with the OTP, approved by a Payroll
 *       Checker of the unit who is not the maker): only when the gates G0 - G6 allow it (C-01,
 *       {@link CycleGateService}, checked at the request and again at the approval); LOCKED, every
 *       current result immutable ({@link CycleLockEffect}).
 * </ul>
 *
 * The cycle only moves forward (trg_cycle_status_forward_only); Headquarters never locks a Region's
 * payroll (workflow). No instance acts on behalf of another (BR-CYC-004): every action is on the
 * session's own unit.
 */
@Service
@Loggable
public class CycleService {

    public static final String ENTITY = "payroll_cycle_instance";

    public record RunView(
            long id,
            String type,
            int runNo,
            String status,
            String startedBy,
            Instant startedAt,
            Instant completedAt,
            Long eligible,
            long results,
            Integer units,
            int unitsCommitted,
            int unitsFailed) {}

    public record ComponentTotal(int payCode, String name, BigDecimal amount) {}

    public record Totals(
            long members,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            List<ComponentTotal> components) {}

    public record CycleView(
            String period,
            String unitCode,
            String unitLabel,
            String unitLevel,
            boolean inCalendar,
            LocalDate computeBy,
            LocalDate lockTarget,
            Long id,
            String status,
            String initiatedBy,
            Instant initiatedAt,
            String frozenBy,
            Instant frozenAt,
            String verifiedBy,
            Instant verifiedAt,
            String lockedBy,
            Instant lockedAt,
            Long currentRunId,
            List<RunView> runs,
            Long resultsRunId,
            String resultsRunType,
            Totals totals,
            Long lockApprovalId,
            String lockApprovalStatus,
            // SRS-15-12: independent flags beside the status, never a step of the status sequence.
            boolean postedToEbs,
            boolean supplementaryOpen,
            // SRS-15-28: the action holding the instance's lease (null when free), and a pending
            // force-release request.
            CycleLeases.Lease lease,
            Long leaseReleaseApprovalId,
            // SRS-15-30 / SRS-15-31: the Checker's open return (null when none) and a waiting
            // un-freeze request.
            CycleCorrections.ReturnView openReturn,
            Long unfreezeApprovalId) {}

    public record Line(
            int payCode,
            String name,
            String type,
            BigDecimal amount,
            BigDecimal rate,
            BigDecimal basis) {}

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final Authorization policy;
    private final SecondFactor secondFactor;
    private final MakerChecker approvals;
    private final ApprovalStatuses approvalStatuses;
    private final AuditService audit;
    private final Clock clock;
    private final PayrollPeriods periods;
    private final CycleGateService gates;
    private final CycleLeases leases;
    private final CycleCorrections corrections;
    private final PreRunChecks preRun;

    public CycleService(
            JdbcTemplate jdbc,
            OrgDirectory org,
            Authorization policy,
            SecondFactor secondFactor,
            MakerChecker approvals,
            ApprovalStatuses approvalStatuses,
            AuditService audit,
            Clock clock,
            PayrollPeriods periods,
            CycleGateService gates,
            CycleLeases leases,
            CycleCorrections corrections,
            PreRunChecks preRun) {
        this.jdbc = jdbc;
        this.org = org;
        this.policy = policy;
        this.secondFactor = secondFactor;
        this.approvals = approvals;
        this.approvalStatuses = approvalStatuses;
        this.audit = audit;
        this.clock = clock;
        this.periods = periods;
        this.gates = gates;
        this.leases = leases;
        this.corrections = corrections;
        this.preRun = preRun;
    }

    @Transactional(readOnly = true)
    public CycleView cycle(AccessContext context, YearMonth period) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "VIEW", unit.getId());
        return view(period, unit);
    }

    /** SRS-15-23: the initiation checks P1-P5 of the user's cycle unit for {@code period}. */
    @Transactional(readOnly = true)
    public List<PreRunChecks.Check> preRunChecks(AccessContext context, YearMonth period) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "VIEW", unit.getId());
        return preRun.evaluate(unit.getId(), period);
    }

    /**
     * Initiates the period: refused while a pre-run check P1-P5 is not clear (SRS-15-23). The
     * prior-period lock rule of SRS-15-33 waits (PENDING Q-05-2).
     */
    @Transactional
    public CycleView initiate(AccessContext context, YearMonth period) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "CREATE", unit.getId());
        if (instance(period, unit).isPresent()) {
            throw new FciException(
                    ApiErrorCode.CYCLE_STATE, new Object[] {"INITIATED"}, List.of(), null);
        }
        List<Long> calendar =
                jdbc.queryForList(
                        "SELECT calendar_id FROM payroll_calendar WHERE payroll_yymm = ?",
                        Long.class,
                        CycleKeys.yymm(period));
        // Not ahead of the current payroll period: no computation (C-13) could run for it yet.
        if (calendar.isEmpty() || period.isAfter(periods.current())) {
            throw new FciException(
                    ApiErrorCode.CYCLE_NO_CALENDAR,
                    new Object[] {period.toString()},
                    List.of(),
                    null);
        }
        requirePriorLocked(period, unit);
        List<String> blocking = PreRunChecks.blocking(preRun.evaluate(unit.getId(), period));
        if (!blocking.isEmpty()) {
            throw new FciException(
                    ApiErrorCode.CYCLE_PRE_RUN,
                    new Object[] {String.join(", ", blocking)},
                    List.of(),
                    null);
        }
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, calendar_id, cycle_status, trigger_type, initiated_by,"
                        + " initiated_at) VALUES (?, ?, ?, ?, ?, 'INITIATED', 'ON_DEMAND', ?, ?)",
                CycleKeys.yymm(period),
                unit.getId(),
                CycleKeys.level(unit.getType()),
                CycleKeys.finYear(period),
                calendar.get(0),
                login(context),
                now());
        record(context, period, unit, null, "INITIATED", "Cycle initiated");
        return view(period, unit);
    }

    // All or nothing: the OTP challenge and a wrong attempt are kept by OtpService's own
    // transaction; any refusal here rolls back the freeze and the lease.
    @Transactional
    public CycleView freeze(AccessContext context, YearMonth period, OtpProof otp) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "SUBMIT", unit.getId());
        Map<String, Object> cycle = require(period, unit, "INITIATED");
        Long challenge = requireOtp(context, cycle, otp);
        // SRS-15-28: not while a computation or another action holds the instance.
        leases.acquire(id(cycle), CycleLeases.Action.FREEZE, login(context), null);
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'FROZEN', frozen_by = ?,"
                    + " frozen_at = ?, freeze_otp_challenge_id = ?, row_version = row_version + 1"
                    + " WHERE cycle_instance_id = ? AND cycle_status = 'INITIATED'",
                login(context),
                now(),
                challenge,
                id(cycle));
        record(
                context,
                period,
                unit,
                "INITIATED",
                "FROZEN",
                "Cycle frozen (OTP " + challenge + ")");
        leases.release(id(cycle), login(context));
        return view(period, unit);
    }

    /**
     * The Checker verifies the computed results. SRS-15-28: not while another action holds the
     * cycle's lease (CYC-409-LEASE); SRS l.1734: not while an ACTUAL computation of the cycle is
     * still running, whose completion would supersede the results verified (CYC-409-RUNNING).
     */
    @Transactional
    public CycleView verify(AccessContext context, YearMonth period) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "APPROVE", unit.getId());
        Map<String, Object> cycle = require(period, unit, "COMPUTED");
        leases.requireFree(id(cycle));
        requireNoRunRunning(id(cycle));
        Object runId = cycle.get("CURRENT_RUN_ID");
        String startedBy =
                runId == null
                        ? null
                        : jdbc.queryForObject(
                                "SELECT started_by FROM computation_run WHERE run_id = ?",
                                String.class,
                                ((Number) runId).longValue());
        String me = login(context);
        if (me.equals(startedBy) || me.equals(cycle.get("FROZEN_BY"))) {
            throw new FciException(ApiErrorCode.MAKER_CHECKER);
        }
        // Guarded again in the UPDATE: a run started between the check and here leaves the cycle
        // COMPUTED (0 rows), refused below rather than verified under it.
        int verified =
                jdbc.update(
                        "UPDATE payroll_cycle_instance SET cycle_status = 'VERIFIED', verified_by ="
                            + " ?, verified_at = ?, row_version = row_version + 1 WHERE"
                            + " cycle_instance_id = ? AND cycle_status = 'COMPUTED' AND NOT EXISTS"
                            + " (SELECT 1 FROM computation_run r WHERE r.cycle_instance_id = ? AND"
                            + " r.run_type = 'ACTUAL' AND r.run_status = 'RUNNING')",
                        me,
                        now(),
                        id(cycle),
                        id(cycle));
        if (verified == 0) {
            requireNoRunRunning(id(cycle));
            throw new FciException(
                    ApiErrorCode.CYCLE_STATE, new Object[] {"VERIFIED"}, List.of(), null);
        }
        record(
                context,
                period,
                unit,
                "COMPUTED",
                "VERIFIED",
                "Results verified (run " + runId + ")");
        return view(period, unit);
    }

    /**
     * SRS l.1734: refuses while an ACTUAL computation of the cycle is RUNNING (CYC-409-RUNNING).
     */
    private void requireNoRunRunning(long cycleId) {
        List<Integer> running =
                jdbc.queryForList(
                        "SELECT run_no FROM computation_run WHERE cycle_instance_id = ? AND"
                                + " run_type = 'ACTUAL' AND run_status = 'RUNNING' ORDER BY run_no,"
                                + " run_id",
                        Integer.class,
                        cycleId);
        if (!running.isEmpty()) {
            throw new FciException(
                    ApiErrorCode.CYCLE_RUN_RUNNING, new Object[] {running.get(0)}, List.of(), null);
        }
    }

    /** Requests the lock (maker); it happens when a Payroll Checker of the unit approves it. */
    @Transactional
    public CycleView requestLock(
            AccessContext context, YearMonth period, String reason, OtpProof otp) {
        OrgLocation unit = unit(context);
        // SRS §6.7 / BR-CYC-004: Headquarters never locks a unit's payroll. Refused before the
        // state and the gates, so the answer names the rule rather than an unmet gate (the
        // approval engine refuses it again on the LOCK action: defence in depth).
        if (policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_MAY_NOT_LOCK);
        }
        Map<String, Object> cycle = require(period, unit, "VERIFIED");
        leases.requireFree(id(cycle));
        // SRS-15-31: not while an un-freeze request of the cycle waits (it would reopen the lock).
        corrections.requireNoUnfreezePending(id(cycle));
        // SRS-15-11: refused naming every gate that holds the lock (plan 05 D-1).
        gates.requireLockable(id(cycle));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "C-02",
                                "LOCK",
                                ENTITY,
                                String.valueOf(id(cycle)),
                                ActionType.UPDATE,
                                unit.getId(),
                                "{\"cycleInstanceId\":"
                                        + id(cycle)
                                        + ",\"period\":\""
                                        + period
                                        + "\",\"unit\":\""
                                        + unit.getCode()
                                        + "\"}",
                                period.atEndOfMonth(),
                                reason),
                        otp);
        audit.record(
                login(context),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id(cycle)),
                                "lock_request",
                                null,
                                "PENDING_APPROVAL",
                                reason == null ? "Lock requested" : reason)
                        .approval(item.id())
                        .module("C-02"));
        return view(period, unit);
    }

    /**
     * The approval entity of a lease force-release (not the cycle's: the lock effect ignores it).
     */
    public static final String LEASE_ENTITY = "cycle_lease";

    /**
     * SRS-15-28: asks the authority configured in X-01 (route {@code C02_LEASE_RELEASE}, plan 05
     * D-6) to force-release the lease held on the instance, with a reason; the decision needs the
     * OTP {@code CYCLE_UNFREEZE_STUCK_RELEASE} (SRS-G-21). The request names the lease held now:
     * only that lease is released ({@link CycleLeaseReleaseEffect}). Refused when nothing holds the
     * lease, and (by the approval engine) while a request for the instance waits.
     */
    @Transactional
    public CycleView requestLeaseRelease(AccessContext context, YearMonth period, String reason) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "LEASE_RELEASE", unit.getId());
        Map<String, Object> cycle =
                instance(period, unit)
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                ApiErrorCode.CYCLE_STATE,
                                                new Object[] {"NONE"},
                                                List.of(),
                                                null));
        CycleLeases.Lease lease =
                leases.current(id(cycle))
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                ApiErrorCode.CYCLE_STATE,
                                                new Object[] {"NOT_LEASED"},
                                                List.of(),
                                                null));
        approvals.submit(
                context,
                new Submission(
                        "C-02",
                        "LEASE_RELEASE",
                        LEASE_ENTITY,
                        String.valueOf(id(cycle)),
                        ActionType.UPDATE,
                        unit.getId(),
                        "{\"cycleInstanceId\":"
                                + id(cycle)
                                + ",\"action\":\""
                                + lease.action()
                                + "\",\"heldBy\":\""
                                + lease.heldBy()
                                + "\",\"acquiredAt\":\""
                                + lease.acquiredAt()
                                + "\",\"reason\":\""
                                + jsonText(reason)
                                + "\"}",
                        period.atEndOfMonth(),
                        reason),
                null);
        return view(period, unit);
    }

    /** A JSON string body: backslashes and quotes escaped (the reason is printable ASCII). */
    private static String jsonText(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** The open force-release request of the instance, if any. */
    private Long pendingLeaseRelease(long cycleId) {
        return CycleSupport.pending(jdbc, approvalStatuses, LEASE_ENTITY, String.valueOf(cycleId));
    }

    @Transactional(readOnly = true)
    public List<Line> lines(AccessContext context, YearMonth period, long resultId) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "VIEW", unit.getId());
        // Only a result of this unit's cycle of the period: any other id answers as unknown, so
        // another office's results cannot be enumerated.
        boolean mine =
                !jdbc.queryForList(
                                "SELECT r.payroll_result_id FROM payroll_result r JOIN"
                                        + " payroll_cycle_instance c ON c.cycle_instance_id ="
                                        + " r.cycle_instance_id WHERE r.payroll_result_id = ? AND"
                                        + " c.org_unit_id = ? AND c.payroll_yymm = ?",
                                Long.class,
                                resultId,
                                unit.getId(),
                                CycleKeys.yymm(period))
                        .isEmpty();
        if (!mine) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return jdbc.query(
                "SELECT c.legacy_pay_code, p.component_name, p.component_type, c.amount,"
                        + " c.rate_applied, c.basis_amount FROM payroll_result_component c JOIN"
                        + " pay_component p ON p.component_id = c.component_id WHERE"
                        + " c.payroll_result_id = ? ORDER BY c.legacy_pay_code",
                (rs, i) ->
                        new Line(
                                rs.getInt(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getBigDecimal(4),
                                rs.getBigDecimal(5),
                                rs.getBigDecimal(6)),
                resultId);
    }

    // ---------------------------------------------------------------- views and helpers

    private CycleView view(YearMonth period, OrgLocation unit) {
        Map<String, Object> calendar =
                jdbc
                        .queryForList(
                                "SELECT compute_by_date, lock_target_date FROM payroll_calendar"
                                        + " WHERE payroll_yymm = ?",
                                CycleKeys.yymm(period))
                        .stream()
                        .findFirst()
                        .orElse(null);
        Optional<Map<String, Object>> found = instance(period, unit);
        List<RunView> runs = List.of();
        Long resultsRun = null;
        String resultsType = null;
        Totals totals = new Totals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        Long lockApproval = null;
        String lockStatus = null;
        if (found.isPresent()) {
            long cycleId = id(found.get());
            runs =
                    jdbc.query(
                            "SELECT run_id, run_type, run_no, run_status, started_by, started_at,"
                                    + " completed_at, eligible_count, result_count, unit_total,"
                                    + " unit_committed, unit_failed FROM computation_run WHERE"
                                    + " cycle_instance_id = ? ORDER BY run_id DESC",
                            (rs, i) ->
                                    new RunView(
                                            rs.getLong(1),
                                            rs.getString(2),
                                            rs.getInt(3),
                                            rs.getString(4),
                                            rs.getString(5),
                                            instant(rs.getTimestamp(6)),
                                            instant(rs.getTimestamp(7)),
                                            rs.getObject(8, Long.class),
                                            rs.getLong(9),
                                            rs.getObject(10, Integer.class),
                                            rs.getInt(11),
                                            rs.getInt(12)),
                            cycleId);
            Optional<long[]> run = resultsRun(period, unit);
            if (run.isPresent()) {
                resultsRun = run.get()[0];
                resultsType = run.get()[1] == 1 ? "ACTUAL" : "SIMULATION";
                totals = totals(resultsRun);
            }
            List<Long> lockItems =
                    jdbc.queryForList(
                            "SELECT approval_id FROM approval_queue WHERE entity_name = ? AND"
                                    + " entity_key = ? ORDER BY approval_id DESC",
                            Long.class,
                            ENTITY,
                            String.valueOf(cycleId));
            if (!lockItems.isEmpty()) {
                lockApproval = lockItems.get(0);
                ApprovalStatus s = approvalStatuses.of(List.of(lockApproval)).get(lockApproval);
                lockStatus = s == null ? null : s.name();
            }
        }
        CycleLeases.Lease lease = null;
        Long leaseRelease = null;
        CycleCorrections.ReturnView openReturn = null;
        Long unfreeze = null;
        if (found.isPresent()) {
            lease = leases.current(id(found.get())).orElse(null);
            leaseRelease = pendingLeaseRelease(id(found.get()));
            openReturn = corrections.openReturn(id(found.get())).orElse(null);
            unfreeze = corrections.pendingUnfreeze(id(found.get()));
        }
        Map<String, Object> c = found.orElse(Map.of());
        // A return keeps the earlier verification on record (audit); it is shown only while it
        // stands: on a verified or locked cycle.
        boolean verified =
                "VERIFIED".equals(c.get("CYCLE_STATUS")) || "LOCKED".equals(c.get("CYCLE_STATUS"));
        return new CycleView(
                period.toString(),
                unit.getCode(),
                unit.displayLabel(),
                CycleKeys.level(unit.getType()),
                calendar != null,
                calendar == null ? null : date(calendar.get("COMPUTE_BY_DATE")),
                calendar == null ? null : date(calendar.get("LOCK_TARGET_DATE")),
                found.map(CycleService::id).orElse(null),
                (String) c.get("CYCLE_STATUS"),
                (String) c.get("INITIATED_BY"),
                ts(c.get("INITIATED_AT")),
                (String) c.get("FROZEN_BY"),
                ts(c.get("FROZEN_AT")),
                verified ? (String) c.get("VERIFIED_BY") : null,
                verified ? ts(c.get("VERIFIED_AT")) : null,
                (String) c.get("LOCKED_BY"),
                ts(c.get("LOCKED_AT")),
                c.get("CURRENT_RUN_ID") == null
                        ? null
                        : ((Number) c.get("CURRENT_RUN_ID")).longValue(),
                runs,
                resultsRun,
                resultsType,
                totals,
                lockApproval,
                lockStatus,
                "Y".equals(c.get("POSTED_TO_EBS_FLAG")),
                "Y".equals(c.get("SUPPLEMENTARY_OPEN")),
                lease,
                leaseRelease,
                openReturn,
                unfreeze);
    }

    /**
     * The run whose results the screen shows: the run of record (ACTUAL), else the latest
     * simulation.
     */
    private Optional<long[]> resultsRun(YearMonth period, OrgLocation unit) {
        Optional<Map<String, Object>> found = instance(period, unit);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Object current = found.get().get("CURRENT_RUN_ID");
        if (current != null) {
            return Optional.of(new long[] {((Number) current).longValue(), 1});
        }
        List<Long> sims =
                jdbc.queryForList(
                        "SELECT run_id FROM computation_run WHERE cycle_instance_id = ? AND"
                            + " run_type = 'SIMULATION' AND run_status = 'COMPLETE' ORDER BY run_id"
                            + " DESC",
                        Long.class,
                        id(found.get()));
        return sims.isEmpty() ? Optional.empty() : Optional.of(new long[] {sims.get(0), 0});
    }

    private Totals totals(long runId) {
        Map<String, Object> sum =
                jdbc.queryForMap(
                        "SELECT COUNT(*) AS members, COALESCE(SUM(gross_earnings), 0) AS gross,"
                            + " COALESCE(SUM(total_deductions), 0) AS ded, COALESCE(SUM(net_pay),"
                            + " 0) AS net FROM payroll_result WHERE run_id = ?",
                        runId);
        List<ComponentTotal> components =
                jdbc.query(
                        "SELECT c.legacy_pay_code, p.component_name, SUM(c.amount) FROM"
                            + " payroll_result_component c JOIN payroll_result r ON"
                            + " r.payroll_result_id = c.payroll_result_id JOIN pay_component p ON"
                            + " p.component_id = c.component_id WHERE r.run_id = ? GROUP BY"
                            + " c.legacy_pay_code, p.component_name ORDER BY c.legacy_pay_code",
                        (rs, i) ->
                                new ComponentTotal(
                                        rs.getInt(1), rs.getString(2), rs.getBigDecimal(3)),
                        runId);
        return new Totals(
                ((Number) sum.get("MEMBERS")).longValue(),
                (BigDecimal) sum.get("GROSS"),
                (BigDecimal) sum.get("DED"),
                (BigDecimal) sum.get("NET"),
                components);
    }

    /**
     * SRS-15-33, KB CYC-4 (SRS L1617, L1720): the prior period of the instance is locked, or this
     * is the instance's first cycle (no earlier period at all). The payroll period is sequential: a
     * missing prior month after earlier ones is a skipped, so unlocked, period (plan 05 D-25).
     */
    private void requirePriorLocked(YearMonth period, OrgLocation unit) {
        YearMonth prior = period.minusMonths(1);
        Optional<Map<String, Object>> previous = instance(prior, unit);
        boolean locked =
                previous.map(c -> "LOCKED".equals(c.get("CYCLE_STATUS")))
                        .orElseGet(
                                () -> {
                                    Integer earlier =
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM payroll_cycle_instance"
                                                            + " WHERE org_unit_id = ? AND"
                                                            + " payroll_yymm < ?",
                                                    Integer.class,
                                                    unit.getId(),
                                                    CycleKeys.yymm(prior));
                                    return earlier == null || earlier == 0;
                                });
        if (!locked) {
            throw new FciException(
                    ApiErrorCode.CYCLE_PRIOR_NOT_LOCKED,
                    new Object[] {prior.toString()},
                    List.of(),
                    null);
        }
    }

    private Optional<Map<String, Object>> instance(YearMonth period, OrgLocation unit) {
        return jdbc
                .queryForList(
                        "SELECT * FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                                + " org_unit_id = ?",
                        CycleKeys.yymm(period),
                        unit.getId())
                .stream()
                .findFirst();
    }

    private Map<String, Object> require(YearMonth period, OrgLocation unit, String status) {
        Map<String, Object> cycle =
                instance(period, unit)
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                ApiErrorCode.CYCLE_STATE,
                                                new Object[] {"NONE"},
                                                List.of(),
                                                null));
        if (!status.equals(cycle.get("CYCLE_STATUS"))) {
            throw new FciException(
                    ApiErrorCode.CYCLE_STATE,
                    new Object[] {cycle.get("CYCLE_STATUS")},
                    List.of(),
                    null);
        }
        return cycle;
    }

    private Long requireOtp(AccessContext context, Map<String, Object> cycle, OtpProof otp) {
        if (!secondFactor.required()) {
            return secondFactor.confirmWithoutCode(
                    context.userId(),
                    OtpGatedAction.CYCLE_FREEZE_LOCK,
                    ENTITY,
                    String.valueOf(id(cycle)));
        }
        if (otp == null || otp.challengeId() == null || otp.code() == null) {
            Long challenge =
                    secondFactor.issue(
                            context.userId(),
                            OtpGatedAction.CYCLE_FREEZE_LOCK,
                            ENTITY,
                            String.valueOf(id(cycle)));
            throw new FciException(
                    ApiErrorCode.OTP_REQUIRED, new Object[] {challenge}, List.of(), null);
        }
        return secondFactor.verify(
                context.userId(),
                OtpGatedAction.CYCLE_FREEZE_LOCK,
                otp.challengeId(),
                otp.code(),
                ENTITY,
                String.valueOf(id(cycle)));
    }

    /**
     * One payroll period of the top-bar selector.
     *
     * @param status the cycle state at the session's paying unit (INITIATED ... LOCKED), or null
     *     when no cycle was initiated or the session has no office (eSeva)
     * @param current the calendar's current period
     */
    public record PeriodView(String period, String finYear, String status, boolean current) {}

    /**
     * The approved calendar periods (M-10), newest first, each with the state of its cycle at the
     * session's paying unit. Back-dated periods are listed: their cycles stay viewable.
     */
    @Transactional(readOnly = true)
    public List<PeriodView> periods(AccessContext context) {
        Long unit =
                context.locationId() == null
                        ? null
                        : org.cycleUnitOf(context.locationId())
                                .map(OrgLocation::getId)
                                .orElse(null);
        Map<String, String> states = new java.util.HashMap<>();
        if (unit != null) {
            jdbc.query(
                    "SELECT payroll_yymm, cycle_status FROM payroll_cycle_instance WHERE"
                            + " org_unit_id = ?",
                    rs -> {
                        states.put(rs.getString(1), rs.getString(2));
                    },
                    unit);
        }
        YearMonth current = periods.current();
        return periods.all().stream()
                .map(
                        p ->
                                new PeriodView(
                                        p.period().toString(),
                                        p.finYear(),
                                        states.get(CycleKeys.yymm(p.period())),
                                        p.period().equals(current)))
                .toList();
    }

    private OrgLocation unit(AccessContext context) {
        return CycleSupport.unit(org, context);
    }

    private void record(
            AccessContext context,
            YearMonth period,
            OrgLocation unit,
            String from,
            String to,
            String reason) {
        audit.record(
                login(context),
                AuditService.Entry.change(
                                ENTITY,
                                unit.getCode() + "|" + period,
                                "cycle_status",
                                from,
                                to,
                                reason)
                        .module("C-02"));
    }

    private static long id(Map<String, Object> cycle) {
        return ((Number) cycle.get("CYCLE_INSTANCE_ID")).longValue();
    }

    private String login(AccessContext context) {
        String login = context.loginId();
        return login.length() <= 50 ? login : login.substring(0, 50);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static Instant ts(Object value) {
        return value instanceof Timestamp t ? t.toInstant() : null;
    }

    private static Instant instant(Timestamp t) {
        return CycleSupport.instant(t);
    }

    static LocalDate date(Object value) {
        return CycleSupport.date(value);
    }
}
