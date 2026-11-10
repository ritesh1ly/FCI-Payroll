package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PreLockCheck;
import in.gov.fci.payroll.payroll.business.CycleGates;
import in.gov.fci.payroll.payroll.business.CycleGates.Gate;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.InputPatterns;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-01 Payroll Cycle Control, gates and pre-lock checks (SRS-15-11, SRS-15-14 … 18, SRS-15-39,
 * SRS-15-42; DDL v4.0 {@code cycle_gate_evaluation}, {@code pre_payroll_check}, {@code
 * cycle_prepay_check_result}): each gate of the session's cycle instance is evaluated from its own
 * source and recorded; the Checker accepts G2 / G5 / G6 with a reason (G6 per member on the
 * quarantine queue); a failed Warn check may be acknowledged, a Block check never. The lock is
 * refused naming every gate that holds it (plan 05 D-1: gates enforced; a gate without data is not
 * met). Every acceptance is audited and entered on the Gate Override &amp; Undo / Reversal Register
 * (SRS-15-17).
 */
@Service
@Loggable
public class CycleGateService {

    static final String SCREEN = "C-01";
    static final String ENTITY = "cycle_gate_evaluation";
    static final int MAX_REASON = 1000;

    /**
     * A gate as the panel shows it; {@code acceptable}: the Checker may accept it now (G2 / G5
     * unmet and not yet accepted, G2 only without blocking exceptions or failed Block checks).
     */
    public record GateView(
            String code,
            boolean met,
            boolean overridable,
            boolean acceptable,
            String detail,
            String acceptedBy,
            Instant acceptedAt,
            String acceptedReason) {}

    /** A pre-lock check as the panel shows it (the latest evaluation). */
    public record CheckView(
            Long checkId,
            String code,
            String name,
            String appliesTo,
            String severity,
            String result,
            long failures,
            String detail,
            String correctionScreen,
            String acknowledgedBy,
            String acknowledgeReason) {}

    /** The panel: gates, checks, and the gates that hold the lock. */
    public record GatesView(
            Long cycleId,
            String status,
            List<GateView> gates,
            List<CheckView> checks,
            List<String> blocking,
            boolean lockable) {}

    /** A quarantined member (G6), accepted or corrected per member. */
    public record QuarantineView(
            Long id,
            Long employeeId,
            String empNum,
            String name,
            String reason,
            java.math.BigDecimal net,
            String reviewStatus,
            String reviewedBy,
            String reviewReason) {}

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final Authorization policy;
    private final AuditService audit;
    private final NetPayFloors floors;
    private final Clock clock;
    private final Map<String, PreLockCheck> checks;

    public CycleGateService(
            JdbcTemplate jdbc,
            OrgDirectory org,
            Authorization policy,
            AuditService audit,
            NetPayFloors floors,
            Clock clock,
            ObjectProvider<PreLockCheck> checks) {
        this.jdbc = jdbc;
        this.org = org;
        this.policy = policy;
        this.audit = audit;
        this.floors = floors;
        this.clock = clock;
        this.checks =
                checks.orderedStream()
                        .collect(Collectors.toUnmodifiableMap(PreLockCheck::ruleKey, c -> c));
    }

    /** SRS-15-18: evaluates and records every gate and check of the period's instance. */
    @Transactional
    public GatesView gates(AccessContext context, YearMonth period) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        Map<String, Object> cycle = instance(period, unit);
        return evaluate(((Number) cycle.get("CYCLE_INSTANCE_ID")).longValue());
    }

    /**
     * SRS-15-11: refuses the lock of an instance naming every gate that holds it (CYC-422-GATES).
     * Called on the lock request and again when the Checker approves it.
     */
    @Transactional
    public void requireLockable(long cycleId) {
        GatesView view = evaluate(cycleId);
        if (!view.lockable()) {
            throw new FciException(
                    ApiErrorCode.CYCLE_GATES_UNMET,
                    new Object[] {String.join(", ", view.blocking())},
                    List.of(),
                    null);
        }
    }

    /**
     * SRS-15-16: the Checker accepts an unmet G2 (non-blocking exceptions only) or G5 with a
     * reason; G6 is accepted per member ({@link #reviewMember}); a hard gate never.
     */
    @Transactional
    public GatesView accept(AccessContext context, YearMonth period, String gate, String reason) {
        return accept(context, period, gate, reason, null);
    }

    /**
     * As {@link #accept(AccessContext, YearMonth, String, String)}, naming the members a G5
     * acceptance covers (SRS l.1524 "where a named member is legitimately excluded"; BMAD TR-010):
     * each an EIS of a member of the cycle's offices; the names are recorded with the reason.
     */
    @Transactional
    public GatesView accept(
            AccessContext context,
            YearMonth period,
            String gate,
            String reason,
            List<String> members) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "APPROVE", unit.getId());
        if (!CycleGates.CODES.contains(gate) || CycleGates.HARD.contains(gate)) {
            throw new FciException(
                    ApiErrorCode.CYCLE_GATE_HARD, new Object[] {gate}, List.of(), null);
        }
        if ("G6".equals(gate)) {
            // SRS l.1525 / SRS-15-16 (BMAD TR-007): G6 is overridable per member only.
            throw new FciException(
                    ApiErrorCode.CYCLE_GATE_PER_MEMBER, new Object[] {gate}, List.of(), null);
        }
        String why = reason(reason);
        Map<String, Object> cycle = instance(period, unit);
        long cycleId = ((Number) cycle.get("CYCLE_INSTANCE_ID")).longValue();
        requireOpen(cycle);
        GatesView view = evaluate(cycleId);
        GateView g =
                view.gates().stream().filter(x -> x.code().equals(gate)).findFirst().orElseThrow();
        if (g.met() || g.acceptedBy() != null) {
            throw new FciException(
                    ApiErrorCode.CYCLE_GATE_MET, new Object[] {gate}, List.of(), null);
        }
        if ("G2".equals(gate)
                && !CycleGates.g2Acceptable(
                        blockingExceptions(cycleId), failedBlockChecks(cycleId))) {
            throw new FciException(
                    ApiErrorCode.CYCLE_GATE_HARD, new Object[] {gate}, List.of(), null);
        }
        String me = login(context);
        String maker = maker(cycle);
        if (me.equals(maker)) {
            throw new FciException(ApiErrorCode.MAKER_CHECKER);
        }
        if ("G5".equals(gate)) {
            why = excludedMembers(unit, cycleId, members) + why;
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                "UPDATE cycle_gate_evaluation SET accepted_by = ?, accepted_at = ?, accepted_reason"
                        + " = ? WHERE cycle_instance_id = ? AND gate_code = ?",
                me,
                now,
                why,
                cycleId,
                gate);
        Long evalId =
                jdbc.queryForObject(
                        "SELECT gate_eval_id FROM cycle_gate_evaluation WHERE cycle_instance_id = ?"
                                + " AND gate_code = ?",
                        Long.class,
                        cycleId,
                        gate);
        // SRS-15-17: every accepted unmet gate on the Gate Override & Undo / Reversal Register.
        jdbc.update(
                "INSERT INTO undo_reversal_register (cycle_instance_id, register_type,"
                        + " transaction_entity, transaction_key, payroll_yymm, stage_at_undo,"
                        + " reason, requested_by, authorised_by, gate_eval_id, executed_at)"
                        + " VALUES (?, 'GATE_OVERRIDE', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                cycleId,
                ENTITY,
                gate,
                cycle.get("PAYROLL_YYMM"),
                cycle.get("CYCLE_STATUS"),
                why,
                maker,
                me,
                evalId,
                now);
        audit.record(
                me,
                AuditService.Entry.change(
                                ENTITY, cycleId + ":" + gate, "accepted_by", null, me, why)
                        .module(SCREEN));
        return evaluate(cycleId);
    }

    /** SRS-15-39: a failed Warn check is acknowledged with a reason; a Block check never. */
    @Transactional
    public GatesView acknowledge(
            AccessContext context, YearMonth period, long checkId, String reason) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "APPROVE", unit.getId());
        String why = reason(reason);
        Map<String, Object> cycle = instance(period, unit);
        long cycleId = ((Number) cycle.get("CYCLE_INSTANCE_ID")).longValue();
        requireOpen(cycle);
        evaluate(cycleId);
        List<Map<String, Object>> latest =
                jdbc.queryForList(
                        "SELECT check_result_id, severity_at_eval, check_result FROM"
                            + " cycle_prepay_check_result WHERE cycle_instance_id = ? AND check_id"
                            + " = ? ORDER BY evaluated_at DESC, check_result_id DESC",
                        cycleId,
                        checkId);
        if (latest.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        Map<String, Object> row = latest.get(0);
        if (!"WARN".equals(row.get("SEVERITY_AT_EVAL"))
                || !"FAIL".equals(row.get("CHECK_RESULT"))) {
            throw new FciException(ApiErrorCode.CYCLE_CHECK_NOT_ACKNOWLEDGEABLE);
        }
        String me = login(context);
        jdbc.update(
                "UPDATE cycle_prepay_check_result SET acknowledged_by = ?, acknowledged_at = ?,"
                        + " acknowledge_reason = ? WHERE check_result_id = ?",
                me,
                Timestamp.from(clock.instant()),
                why,
                row.get("CHECK_RESULT_ID"));
        audit.record(
                me,
                AuditService.Entry.change(
                                "cycle_prepay_check_result",
                                cycleId + ":" + checkId,
                                "acknowledged_by",
                                null,
                                me,
                                why)
                        .module(SCREEN));
        return evaluate(cycleId);
    }

    /** SRS-15-13, SRS-15-16 G6: the quarantine queue of the period's instance. */
    @Transactional(readOnly = true)
    public Paged<QuarantineView> quarantine(
            AccessContext context, YearMonth period, PageSpec spec) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        long cycleId = ((Number) instance(period, unit).get("CYCLE_INSTANCE_ID")).longValue();
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_quarantine WHERE cycle_instance_id = ?",
                        Long.class,
                        cycleId);
        List<QuarantineView> rows =
                jdbc.query(
                        "SELECT q.quarantine_id, q.employee_id, e.emp_num, e.first_name,"
                                + " e.last_name, q.quarantine_reason, q.computed_net_pay,"
                                + " q.review_status, q.reviewed_by, q.review_reason FROM"
                                + " payroll_quarantine q JOIN employee e ON e.employee_id ="
                                + " q.employee_id WHERE q.cycle_instance_id = ? ORDER BY e.emp_num,"
                                + " q.quarantine_reason, q.quarantine_id OFFSET ? ROWS FETCH NEXT ?"
                                + " ROWS ONLY",
                        (rs, i) ->
                                new QuarantineView(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getString(3),
                                        (rs.getString(4)
                                                        + " "
                                                        + (rs.getString(5) == null
                                                                ? ""
                                                                : rs.getString(5)))
                                                .strip(),
                                        rs.getString(6),
                                        rs.getBigDecimal(7),
                                        rs.getString(8),
                                        rs.getString(9),
                                        rs.getString(10)),
                        cycleId,
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(rows, spec.page(), spec.size(), CycleSupport.orZero(total));
    }

    /**
     * SRS-15-16 G6 per member: the Checker accepts a quarantined case (or records it corrected)
     * with a reason.
     */
    @Transactional
    public GatesView reviewMember(
            AccessContext context,
            YearMonth period,
            long quarantineId,
            boolean accepted,
            String reason) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "APPROVE", unit.getId());
        String why = reason(reason);
        Map<String, Object> cycle = instance(period, unit);
        long cycleId = ((Number) cycle.get("CYCLE_INSTANCE_ID")).longValue();
        requireOpen(cycle);
        String me = login(context);
        if (me.equals(maker(cycle))) {
            throw new FciException(ApiErrorCode.MAKER_CHECKER);
        }
        String status = accepted ? "ACCEPTED" : "CORRECTED";
        int changed =
                jdbc.update(
                        "UPDATE payroll_quarantine SET review_status = ?, reviewed_by = ?,"
                                + " reviewed_at = ?, review_reason = ? WHERE quarantine_id = ? AND"
                                + " cycle_instance_id = ? AND review_status = 'UNREVIEWED'",
                        status,
                        me,
                        Timestamp.from(clock.instant()),
                        why,
                        quarantineId,
                        cycleId);
        if (changed == 0) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        audit.record(
                me,
                AuditService.Entry.change(
                                "payroll_quarantine",
                                String.valueOf(quarantineId),
                                "review_status",
                                "UNREVIEWED",
                                status,
                                why)
                        .module(SCREEN));
        return evaluate(cycleId);
    }

    // ---------------------------------------------------------------- evaluation

    /** Evaluates and records the gates and checks of one instance. */
    GatesView evaluate(long cycleId) {
        Map<String, Object> cycle =
                jdbc.queryForMap(
                        "SELECT cycle_instance_id, payroll_yymm, cycle_status, current_run_id"
                                + " FROM payroll_cycle_instance WHERE cycle_instance_id = ?",
                        cycleId);
        Timestamp now = Timestamp.from(clock.instant());
        List<CheckView> checks = evaluateChecks(cycleId, now);
        List<Gate> gates = new ArrayList<>();
        gates.add(g0(cycle));
        gates.add(CycleGates.g1(engineStates(cycleId)));
        gates.add(
                CycleGates.g2(
                        blockingExceptions(cycleId),
                        otherExceptions(cycleId),
                        failedBlockChecks(cycleId)));
        gates.add(CycleGates.g3(pendingApprovals(cycleId)));
        gates.add(CycleGates.g4(totals(cycleId)));
        gates.add(CycleGates.g5(statutory(cycleId)));
        gates.add(
                CycleGates.g6(
                        count(
                                "SELECT COUNT(*) FROM payroll_quarantine WHERE cycle_instance_id ="
                                        + " ? AND review_status = 'UNREVIEWED'",
                                cycleId),
                        floors.inForce("STAFF", periodDate((String) cycle.get("PAYROLL_YYMM")))
                                .map(NetPayFloors.Floor::confirmed)
                                .orElse(false)));
        Map<String, Map<String, Object>> stored = new HashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT gate_code, accepted_by, accepted_at, accepted_reason FROM"
                                + " cycle_gate_evaluation WHERE cycle_instance_id = ?",
                        cycleId)) {
            stored.put((String) row.get("GATE_CODE"), row);
        }
        Object runId = cycle.get("CURRENT_RUN_ID");
        List<GateView> views = new ArrayList<>();
        Set<String> accepted = new HashSet<>();
        for (Gate g : gates) {
            Map<String, Object> s = stored.get(g.code());
            // An acceptance stands while the gate stays unmet; a met gate needs none.
            boolean keep = s != null && s.get("ACCEPTED_BY") != null && !g.met();
            if (s == null) {
                jdbc.update(
                        "INSERT INTO cycle_gate_evaluation (cycle_instance_id, gate_code, is_met,"
                                + " is_overridable, run_id, evaluated_at, evaluation_detail)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                        cycleId,
                        g.code(),
                        yn(g.met()),
                        yn(g.overridable()),
                        runId,
                        now,
                        clip(g.detail()));
            } else {
                jdbc.update(
                        "UPDATE cycle_gate_evaluation SET is_met = ?, run_id = ?, evaluated_at = ?,"
                                + " evaluation_detail = ?, accepted_by = ?, accepted_at = ?,"
                                + " accepted_reason = ? WHERE cycle_instance_id = ? AND gate_code ="
                                + " ?",
                        yn(g.met()),
                        runId,
                        now,
                        clip(g.detail()),
                        keep ? s.get("ACCEPTED_BY") : null,
                        keep ? s.get("ACCEPTED_AT") : null,
                        keep ? s.get("ACCEPTED_REASON") : null,
                        cycleId,
                        g.code());
            }
            if (keep) {
                accepted.add(g.code());
            }
            boolean acceptable =
                    !g.met()
                            && !keep
                            && ("G5".equals(g.code())
                                    || ("G2".equals(g.code())
                                            && CycleGates.g2Acceptable(
                                                    blockingExceptions(cycleId),
                                                    failedBlockChecks(cycleId))));
            views.add(
                    new GateView(
                            g.code(),
                            g.met(),
                            g.overridable(),
                            acceptable,
                            g.detail(),
                            keep ? (String) s.get("ACCEPTED_BY") : null,
                            keep ? ((Timestamp) s.get("ACCEPTED_AT")).toInstant() : null,
                            keep ? (String) s.get("ACCEPTED_REASON") : null));
        }
        List<String> blocking = new ArrayList<>(CycleGates.blocking(gates, accepted));
        // SRS-15-39: a failed Warn check holds the lock until it is acknowledged.
        checks.stream()
                .filter(
                        c ->
                                "WARN".equals(c.severity())
                                        && "FAIL".equals(c.result())
                                        && c.acknowledgedBy() == null)
                .forEach(c -> blocking.add(c.code()));
        return new GatesView(
                cycleId,
                (String) cycle.get("CYCLE_STATUS"),
                views,
                checks,
                blocking,
                blocking.isEmpty());
    }

    private Gate g0(Map<String, Object> cycle) {
        Object runId = cycle.get("CURRENT_RUN_ID");
        if (runId == null) {
            return CycleGates.g0(null, null, 0, 0, null, null);
        }
        long run = ((Number) runId).longValue();
        Map<String, Object> r =
                jdbc.queryForMap(
                        "SELECT run_status, result_count, eligible_count FROM computation_run WHERE"
                                + " run_id = ?",
                        run);
        return CycleGates.g0(
                run,
                (String) r.get("RUN_STATUS"),
                count("SELECT COUNT(*) FROM computation_work_unit WHERE run_id = ?", run),
                count(
                        "SELECT COUNT(*) FROM computation_work_unit WHERE run_id = ? AND"
                                + " unit_status = 'COMMITTED'",
                        run),
                r.get("RESULT_COUNT") == null ? null : ((Number) r.get("RESULT_COUNT")).longValue(),
                r.get("ELIGIBLE_COUNT") == null
                        ? null
                        : ((Number) r.get("ELIGIBLE_COUNT")).longValue());
    }

    Map<String, String> engineStates(long cycleId) {
        Map<String, String> states = new LinkedHashMap<>();
        jdbc.query(
                "SELECT payroll_type, engine_state FROM cycle_engine_status WHERE"
                        + " cycle_instance_id = ?",
                rs -> {
                    states.put(rs.getString(1), rs.getString(2));
                },
                cycleId);
        return states;
    }

    long blockingExceptions(long cycleId) {
        return count(
                "SELECT COUNT(*) FROM payroll_exception WHERE cycle_instance_id = ? AND severity ="
                        + " 'BLOCKING' AND exception_status IN ('OPEN', 'IN_PROGRESS',"
                        + " 'ROUTED_UPSTREAM')",
                cycleId);
    }

    private long otherExceptions(long cycleId) {
        return count(
                "SELECT COUNT(*) FROM payroll_exception WHERE cycle_instance_id = ? AND severity <>"
                        + " 'BLOCKING' AND exception_status IN ('OPEN', 'IN_PROGRESS',"
                        + " 'ROUTED_UPSTREAM')",
                cycleId);
    }

    private long failedBlockChecks(long cycleId) {
        return count(
                "SELECT COUNT(*) FROM cycle_prepay_check_result r WHERE r.cycle_instance_id = ? AND"
                    + " r.severity_at_eval = 'BLOCK' AND r.check_result = 'FAIL' AND r.evaluated_at"
                    + " = (SELECT MAX(x.evaluated_at) FROM cycle_prepay_check_result x WHERE"
                    + " x.cycle_instance_id = r.cycle_instance_id AND x.check_id = r.check_id)",
                cycleId);
    }

    /**
     * G3 (SRS v0.5 §6.7.2: "nothing is left unactioned in the Approvals Inbox for this instance"):
     * open items decided at the instance's unit or an office it hosts whose effective date falls in
     * the payroll period, other than this instance's own lock request. Items of other periods or
     * without a date (access, configuration) belong to no instance (plan 05 D-4).
     */
    private long pendingApprovals(long cycleId) {
        // OPEN-QUESTION: plan 05 D-4 - "for this instance" read as the unit's items dated in the
        // period.
        return count(
                "SELECT COUNT(*) FROM workflow_inbox_item w JOIN approval_queue q ON q.approval_id"
                    + " = w.approval_id JOIN payroll_cycle_instance ci ON ci.cycle_instance_id = ?"
                    + " WHERE w.item_status IN ('PENDING', 'IN_PROGRESS') AND q.status ="
                    + " 'PENDING_APPROVAL' AND (w.org_scope_id = ci.org_unit_id OR w.org_scope_id"
                    + " IN (SELECT location_id FROM org_location WHERE cycle_host_location_id ="
                    + " ci.org_unit_id)) AND q.effective_date >= ? AND q.effective_date <= ? AND"
                    + " NOT (q.entity_name = 'payroll_cycle_instance' AND q.entity_key ="
                    + " CAST(ci.cycle_instance_id AS VARCHAR(20)))",
                cycleId,
                java.sql.Date.valueOf(periodDateOf(cycleId)),
                java.sql.Date.valueOf(periodDateOf(cycleId).plusMonths(1).minusDays(1)));
    }

    private LocalDate periodDateOf(long cycleId) {
        return periodDate(
                jdbc.queryForObject(
                        "SELECT payroll_yymm FROM payroll_cycle_instance WHERE cycle_instance_id ="
                                + " ?",
                        String.class,
                        cycleId));
    }

    private Map<String, List<CycleGates.Total>> totals(long cycleId) {
        Map<String, List<CycleGates.Total>> byType = new LinkedHashMap<>();
        jdbc.query(
                "SELECT COALESCE(payroll_type, 'ALL'), total_source, member_count, gross_total,"
                        + " deduction_total, net_total FROM cycle_control_total WHERE"
                        + " cycle_instance_id = ?",
                rs -> {
                    byType.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(
                                    new CycleGates.Total(
                                            rs.getString(2),
                                            rs.getLong(3),
                                            rs.getBigDecimal(4),
                                            rs.getBigDecimal(5),
                                            rs.getBigDecimal(6)));
                },
                cycleId);
        return byType;
    }

    private List<CycleGates.Statutory> statutory(long cycleId) {
        return jdbc.query(
                "SELECT statutory_process, process_state, members_uncomputed FROM"
                        + " cycle_statutory_status WHERE cycle_instance_id = ?",
                (rs, i) -> {
                    // wasNull() reports the last column read, so it is asked right after column 3.
                    long uncomputed = rs.getLong(3);
                    Long reported = rs.wasNull() ? null : uncomputed;
                    return new CycleGates.Statutory(rs.getString(1), rs.getString(2), reported);
                },
                cycleId);
    }

    /**
     * SRS-15-39: evaluates every active pre-lock check that applies to the instance (ALL and STAFF
     * always; a labour check when a labour engine ran for it) and records the result. A check
     * without an evaluator yet fails as "not available" (plan 05 D-2). An unchanged result keeps
     * its row, so the acknowledgement of a failed Warn check stands until the failure changes.
     */
    private List<CheckView> evaluateChecks(long cycleId, Timestamp now) {
        Set<String> types = engineStates(cycleId).keySet();
        boolean labour = types.stream().anyMatch(t -> t.endsWith("_LABOUR"));
        List<CheckView> out = new ArrayList<>();
        for (Map<String, Object> c :
                jdbc.queryForList(
                        "SELECT check_id, check_code, check_name, applies_to, severity,"
                            + " evaluation_rule_key, correction_screen FROM pre_payroll_check WHERE"
                            + " check_stage = 'PRE_LOCK' AND is_active = 'Y' AND effective_from <="
                            + " ? AND (effective_to IS NULL OR effective_to >= ?) ORDER BY"
                            + " check_code",
                        now,
                        now)) {
            String applies = (String) c.get("APPLIES_TO");
            boolean applicable =
                    "ALL".equals(applies)
                            || "STAFF".equals(applies)
                            || ("LABOUR".equals(applies) && labour)
                            || types.contains(applies);
            if (!applicable) {
                continue;
            }
            long checkId = ((Number) c.get("CHECK_ID")).longValue();
            String severity = (String) c.get("SEVERITY");
            Outcome o = run((String) c.get("EVALUATION_RULE_KEY"), cycleId);
            String result = o.failures() == 0 ? "PASS" : "FAIL";
            Map<String, Object> previous =
                    jdbc
                            .queryForList(
                                    "SELECT check_result, failure_count, acknowledged_by,"
                                        + " acknowledged_at, acknowledge_reason FROM"
                                        + " cycle_prepay_check_result WHERE cycle_instance_id = ?"
                                        + " AND check_id = ? ORDER BY evaluated_at DESC,"
                                        + " check_result_id DESC",
                                    cycleId,
                                    checkId)
                            .stream()
                            .findFirst()
                            .orElse(null);
            // An unchanged result keeps its row (and its acknowledgement); a change is a new row.
            boolean unchanged =
                    previous != null
                            && result.equals(previous.get("CHECK_RESULT"))
                            && ((Number) previous.get("FAILURE_COUNT")).longValue() == o.failures();
            boolean carry = unchanged && previous.get("ACKNOWLEDGED_BY") != null;
            if (!unchanged) {
                jdbc.update(
                        "INSERT INTO cycle_prepay_check_result (cycle_instance_id, check_id,"
                                + " severity_at_eval, check_result, failure_count, evaluated_at,"
                                + " evaluation_detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        cycleId,
                        checkId,
                        severity,
                        result,
                        o.failures(),
                        now,
                        clip(o.detail()));
            }
            out.add(
                    new CheckView(
                            checkId,
                            (String) c.get("CHECK_CODE"),
                            (String) c.get("CHECK_NAME"),
                            applies,
                            severity,
                            result,
                            o.failures(),
                            o.detail(),
                            (String) c.get("CORRECTION_SCREEN"),
                            carry ? (String) previous.get("ACKNOWLEDGED_BY") : null,
                            carry ? (String) previous.get("ACKNOWLEDGE_REASON") : null));
        }
        return out;
    }

    /** One evaluation: the failing record count and what to show. */
    record Outcome(long failures, String detail) {}

    /** The evaluators built so far; every other check is "not available" and fails. */
    private Outcome run(String ruleKey, long cycleId) {
        if ("NET_PAY_NOT_BELOW_MINIMUM".equals(ruleKey)) {
            long n =
                    count(
                            "SELECT COUNT(*) FROM payroll_quarantine WHERE cycle_instance_id = ?"
                                    + " AND review_status = 'UNREVIEWED'",
                            cycleId);
            return new Outcome(n, n + " members below the permitted minimum not reviewed (C-08)");
        }
        if ("HRMS_FALLBACK_NOT_APPROVED".equals(ruleKey)) {
            long n =
                    count(
                            "SELECT COUNT(*) FROM v_cycle_hrms_fallback_status WHERE"
                                    + " cycle_instance_id = ? AND blocks_lock = 'Y'",
                            cycleId);
            return new Outcome(n, n + " enabled fallback uploads not approved (X-07)");
        }
        if ("HRMS_CHANGE_PENDING".equals(ruleKey)) {
            // SRS-1-69 / SRS-15-40: a pay-affecting change received before the freeze still pending
            // on
            // I-21 holds the lock; one received after it is paid next month and does not.
            long n =
                    count(
                            "SELECT COALESCE(SUM(pending_task_count), 0) FROM"
                                    + " v_cycle_pending_hrms_change WHERE cycle_instance_id = ?",
                            cycleId);
            return new Outcome(n, n + " HRMS changes received before the freeze pending on I-21");
        }
        PreLockCheck owner = checks.get(ruleKey);
        if (owner != null) {
            // Plan 18 phase 18a: a check evaluated by the module that owns its data.
            Map<String, Object> c =
                    jdbc.queryForMap(
                            "SELECT org_unit_id, payroll_yymm FROM payroll_cycle_instance WHERE"
                                    + " cycle_instance_id = ?",
                            cycleId);
            PreLockCheck.Outcome o =
                    owner.evaluate(
                            new PreLockCheck.Cycle(
                                    cycleId,
                                    ((Number) c.get("ORG_UNIT_ID")).longValue(),
                                    (String) c.get("PAYROLL_YYMM")));
            return new Outcome(o.failures(), o.detail());
        }
        return new Outcome(
                1, "Not available: this check is evaluated once its source module is built");
    }

    // ---------------------------------------------------------------- helpers

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return CycleSupport.orZero(n);
    }

    private OrgLocation unit(AccessContext context) {
        return CycleSupport.unit(org, context);
    }

    private Map<String, Object> instance(YearMonth period, OrgLocation unit) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT cycle_instance_id, payroll_yymm, cycle_status, initiated_by,"
                                + " frozen_by FROM payroll_cycle_instance WHERE payroll_yymm = ?"
                                + " AND org_unit_id = ?",
                        period.format(DateTimeFormatter.ofPattern("yyyyMM")),
                        unit.getId());
        if (rows.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private static void requireOpen(Map<String, Object> cycle) {
        if ("LOCKED".equals(cycle.get("CYCLE_STATUS"))) {
            throw new FciException(
                    ApiErrorCode.CYCLE_STATE, new Object[] {"LOCKED"}, List.of(), null);
        }
    }

    /**
     * BMAD TR-010: the G5 acceptance's named members, checked (distinct EIS numbers of staff
     * records of the cycle unit's offices, as many as remain uncomputed, every process complete)
     * and written ahead of the reason ("Excluded members A, B: ").
     */
    private String excludedMembers(OrgLocation unit, long cycleId, List<String> members) {
        List<String> named =
                members == null
                        ? List.of()
                        : members.stream()
                                .filter(java.util.Objects::nonNull)
                                .map(String::strip)
                                .filter(m -> !m.isEmpty())
                                .distinct()
                                .toList();
        CycleGates.g5Exclusion(statutory(cycleId), named.size())
                .ifPresent(
                        defect -> {
                            throw new FciException(
                                    ApiErrorCode.CYCLE_GATE_MEMBERS,
                                    new Object[] {"G5", defect},
                                    List.of(),
                                    null);
                        });
        java.util.Set<Long> offices = org.subtree(unit.getId());
        for (String eis : named) {
            boolean inScope =
                    jdbc
                            .queryForList(
                                    "SELECT location_id FROM employee WHERE emp_num = ? AND"
                                            + " employee_type = 'STAFF'",
                                    Long.class,
                                    eis)
                            .stream()
                            .anyMatch(offices::contains);
            if (!inScope) {
                throw new FciException(
                        ApiErrorCode.CYCLE_GATE_MEMBERS,
                        new Object[] {"G5", "NOT_A_MEMBER:" + eis},
                        List.of(),
                        null);
            }
        }
        return "Excluded members " + String.join(", ", named) + ": ";
    }

    /** The maker of the cycle: who froze it (else who initiated it). */
    private static String maker(Map<String, Object> cycle) {
        Object frozen = cycle.get("FROZEN_BY");
        return (String) (frozen != null ? frozen : cycle.get("INITIATED_BY"));
    }

    /** SRS-G-29: a non-empty, printable-ASCII reason of at most {@link #MAX_REASON} characters. */
    static String reason(String reason) {
        String r = reason == null ? "" : reason.strip();
        if (r.isEmpty()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        if (r.length() > MAX_REASON || !InputPatterns.isPrintableText(r)) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        return r;
    }

    private static String login(AccessContext context) {
        return CycleSupport.login(context);
    }

    private static LocalDate periodDate(String yymm) {
        return YearMonth.parse(yymm, DateTimeFormatter.ofPattern("yyyyMM")).atDay(1);
    }

    private static String yn(boolean b) {
        return b ? "Y" : "N";
    }

    static String clip(String s) {
        return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
