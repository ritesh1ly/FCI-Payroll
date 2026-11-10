package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.CycleReturned;
import in.gov.fci.payroll.payroll.business.CycleCorrectionRules;
import in.gov.fci.payroll.payroll.business.CycleCorrectionRules.ReturnScope;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The two corrections of a payroll cycle before month-end lock (plan 05 phase 05d), on C-02.
 *
 * <ul>
 *   <li><b>Checker return</b> (SRS-15-30, BR-CYC-016): the Payroll Checker of the unit returns a
 *       computed or verified cycle with a reason and a scope. It reverses approval state only: the
 *       run of record and its results are SUPERSEDED (never deleted or changed), the cycle is
 *       FROZEN again ({@code trg_cycle_status_forward_only} admits it with the OPEN {@code
 *       cycle_return}) and needs a fresh computation before it can be verified; the next Actual run
 *       marks the return RECOMPUTED (plan 05 D-9, in {@code CycleRuns}). A return raised in error
 *       is withdrawn explicitly while nothing was done on it, restoring what it reversed.
 *   <li><b>Un-freeze request</b> (SRS-15-31, BR-CYC-017): the Payroll Maker asks with a reason;
 *       route {@code C02_UNFREEZE} (plan 05 D-8) decides it, the last level with the OTP {@code
 *       CYCLE_UNFREEZE_STUCK_RELEASE}; {@link CycleUnfreezeEffect} applies it.
 * </ul>
 *
 * <p>Neither is possible while another action holds the instance's lease (SRS-15-28: "never during
 * a run"), while the lock request waits, or while an un-freeze request waits: they would race.
 */
@Service
@Loggable
public class CycleCorrections {

    /** The most members one return names (an Oracle IN list holds 1,000). */
    public static final int MAX_MEMBERS = 1000;

    /** Approval entity of an un-freeze request (not the cycle's: the lock effect ignores it). */
    public static final String UNFREEZE_ENTITY = "cycle_unfreeze";

    /** The return entity in the audit trail. */
    public static final String RETURN_ENTITY = "cycle_return";

    /**
     * The OPEN return of a cycle, as C-02 shows it to the Maker and the Checker.
     *
     * @param members the returned members' EIS numbers (MEMBERS), else empty
     */
    public record ReturnView(
            long id,
            String scope,
            String orgUnitCode,
            List<String> members,
            String fromStatus,
            String reason,
            String returnedBy,
            Instant returnedAt,
            long runId) {}

    /** SRS-15-30: what the Checker returns, and why. */
    public record ReturnRequest(
            ReturnScope scope, String orgUnitCode, List<String> members, String reason) {}

    /**
     * SRS-15-30: the cycle's ACTUAL runs numbered after the returned one (parameters: the cycle,
     * the returned run). By run number (uk_computation_run), never by run identity: identities are
     * cached and NOORDER (Oracle review F2), so a later run can carry a lower id.
     */
    static final String RUNS_SINCE =
            "SELECT COUNT(*) FROM computation_run o JOIN computation_run r ON r.cycle_instance_id ="
                    + " o.cycle_instance_id AND r.run_type = o.run_type WHERE o.cycle_instance_id"
                    + " = ? AND o.run_type = 'ACTUAL' AND r.run_id = ? AND o.run_no > r.run_no";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final OrgDirectory org;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final ApprovalStatuses approvalStatuses;
    private final AuditService audit;
    private final Clock clock;
    private final CycleLeases leases;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    public CycleCorrections(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            OrgDirectory org,
            Authorization policy,
            MakerChecker approvals,
            ApprovalStatuses approvalStatuses,
            AuditService audit,
            Clock clock,
            CycleLeases leases,
            ApplicationEventPublisher events,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.named = named;
        this.org = org;
        this.policy = policy;
        this.approvals = approvals;
        this.approvalStatuses = approvalStatuses;
        this.audit = audit;
        this.clock = clock;
        this.leases = leases;
        this.events = events;
        this.json = json;
    }

    // ---------------------------------------------------------------- the Checker's return

    /**
     * SRS-15-30: returns the computed or verified cycle of {@code period} at the Checker's unit.
     *
     * @throws FciException CYC-409-STATE (not computed or verified), CYC-422-SCOPE (scope detail
     *     missing, an office not beneath the unit, a member without a result in the run),
     *     CYC-409-PENDING (a lock or un-freeze request waits), CYC-409-LEASE (a run is going on)
     */
    @Transactional
    public void returnCycle(AccessContext context, YearMonth period, ReturnRequest request) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "RETURN", unit.getId());
        Map<String, Object> cycle = instance(period, unit);
        String status = (String) cycle.get("CYCLE_STATUS");
        Object currentRun = cycle.get("CURRENT_RUN_ID");
        if (!CycleCorrectionRules.returnable(status) || currentRun == null) {
            throw state(status);
        }
        CycleCorrectionRules.scopeDefect(request.scope(), request.orgUnitCode(), request.members())
                .ifPresent(
                        defect -> {
                            throw scope(defect);
                        });
        long cycleId = id(cycle);
        long runId = ((Number) currentRun).longValue();
        requireNothingPending(cycleId);
        Long officeId = null;
        String memberList = null;
        if (request.scope() == ReturnScope.ORG_UNIT) {
            officeId = officeBeneath(unit, request.orgUnitCode());
        } else if (request.scope() == ReturnScope.MEMBERS) {
            memberList = members(runId, request.members());
        }
        String me = login(context);
        leases.acquire(cycleId, CycleLeases.Action.RETURN, me, null);
        Timestamp now = Timestamp.from(clock.instant());
        KeyHolder key = new GeneratedKeyHolder();
        Long office = officeId;
        String list = memberList;
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO cycle_return (cycle_instance_id, run_id,"
                                        + " return_scope, scope_org_unit_id, scope_member_list,"
                                        + " from_status, reason, returned_by, returned_at) VALUES"
                                        + " (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    new String[] {"return_id"});
                    ps.setLong(1, cycleId);
                    ps.setLong(2, runId);
                    ps.setString(3, request.scope().name());
                    if (office == null) {
                        ps.setNull(4, Types.BIGINT);
                    } else {
                        ps.setLong(4, office);
                    }
                    ps.setString(5, list);
                    ps.setString(6, status);
                    ps.setString(7, request.reason());
                    ps.setString(8, me);
                    ps.setTimestamp(9, now);
                    return ps;
                },
                key);
        long returnId = GeneratedKeys.id(key);
        // Approval state only (SRS-15-30): the run and its results stay, SUPERSEDED.
        jdbc.update(
                "UPDATE computation_run SET run_status = 'SUPERSEDED', superseded_by_return_id = ?,"
                        + " row_version = row_version + 1 WHERE run_id = ? AND run_status ="
                        + " 'COMPLETE'",
                returnId,
                runId);
        jdbc.update(
                "UPDATE payroll_result SET result_status = 'SUPERSEDED' WHERE run_id = ? AND"
                        + " result_status = 'CURRENT' AND is_locked = 'N'",
                runId);
        moveStatus(cycleId, status, "FROZEN", null);
        audit.record(
                me,
                AuditService.Entry.change(
                                RETURN_ENTITY,
                                String.valueOf(returnId),
                                "return_status",
                                null,
                                "OPEN",
                                request.reason())
                        .module("C-02"));
        auditStatus(
                me,
                unit,
                period,
                status,
                "FROZEN",
                "Returned by the Checker ("
                        + request.scope()
                        + ", return "
                        + returnId
                        + "): "
                        + request.reason());
        leases.release(cycleId, me);
        events.publishEvent(
                new CycleReturned(
                        returnId,
                        cycleId,
                        period.toString(),
                        unit.getCode(),
                        request.scope().name(),
                        request.reason(),
                        me,
                        now.toInstant()));
    }

    /**
     * SRS-15-30: withdraws a return raised in error, restoring what it reversed: the run of record
     * and its results, and the status the cycle was returned from.
     *
     * @throws FciException CYC-409-RETURN when it can no longer be withdrawn, NOT_FOUND for a
     *     return of another cycle
     */
    @Transactional
    public void withdrawReturn(
            AccessContext context, YearMonth period, long returnId, String reason) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "RETURN", unit.getId());
        Map<String, Object> cycle = instance(period, unit);
        long cycleId = id(cycle);
        Map<String, Object> ret =
                jdbc
                        .queryForList(
                                "SELECT run_id, from_status, return_status, returned_at FROM"
                                        + " cycle_return WHERE return_id = ? AND"
                                        + " cycle_instance_id = ?",
                                returnId,
                                cycleId)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        // Ordered by the cycle's ACTUAL run number and by the application clock only: identities
        // are cached and NOORDER (Oracle review F2), so a later run can carry a lower id; and the
        // database clock may run in another time zone than the application (a Tomcat in UTC
        // beside an H2 server in IST), so a time it wrote is never compared with one the
        // application wrote.
        long returnedRun = ((Number) ret.get("RUN_ID")).longValue();
        boolean runSince = count(RUNS_SINCE, cycleId, returnedRun) > 0;
        // Un-frozen since: the cycle went to INITIATED (the status check names it) or was frozen
        // again after the return.
        Timestamp returnedAt = (Timestamp) ret.get("RETURNED_AT");
        Timestamp frozenAt = (Timestamp) cycle.get("FROZEN_AT");
        boolean unfrozenSince = frozenAt != null && frozenAt.after(returnedAt);
        CycleCorrectionRules.withdrawalDefect(
                        (String) ret.get("RETURN_STATUS"),
                        (String) cycle.get("CYCLE_STATUS"),
                        runSince,
                        unfrozenSince)
                .ifPresent(
                        defect -> {
                            throw new FciException(
                                    ApiErrorCode.CYCLE_RETURN_CLOSED,
                                    new Object[] {defect},
                                    List.of(),
                                    null);
                        });
        String me = login(context);
        leases.acquire(cycleId, CycleLeases.Action.RETURN, me, null);
        long runId = returnedRun;
        String from = (String) ret.get("FROM_STATUS");
        jdbc.update(
                "UPDATE cycle_return SET return_status = 'WITHDRAWN', withdrawn_by = ?,"
                        + " withdrawn_at = ?, withdrawal_reason = ? WHERE return_id = ? AND"
                        + " return_status = 'OPEN'",
                me,
                Timestamp.from(clock.instant()),
                reason,
                returnId);
        jdbc.update(
                "UPDATE computation_run SET run_status = 'COMPLETE', superseded_by_return_id ="
                        + " NULL, row_version = row_version + 1 WHERE run_id = ? AND"
                        + " superseded_by_return_id = ?",
                runId,
                returnId);
        jdbc.update(
                "UPDATE payroll_result SET result_status = 'CURRENT' WHERE run_id = ? AND"
                        + " result_status = 'SUPERSEDED' AND is_locked = 'N'",
                runId);
        moveStatus(cycleId, "FROZEN", from, runId);
        audit.record(
                me,
                AuditService.Entry.change(
                                RETURN_ENTITY,
                                String.valueOf(returnId),
                                "return_status",
                                "OPEN",
                                "WITHDRAWN",
                                reason)
                        .module("C-02"));
        auditStatus(
                me, unit, period, "FROZEN", from, "Return " + returnId + " withdrawn: " + reason);
        leases.release(cycleId, me);
    }

    /** The OPEN return of the instance, if any. */
    @Transactional(readOnly = true)
    public Optional<ReturnView> openReturn(long cycleId) {
        return jdbc
                .query(
                        "SELECT r.return_id, r.return_scope, o.location_code,"
                                + " r.scope_member_list, r.from_status, r.reason, r.returned_by,"
                                + " r.returned_at, r.run_id FROM cycle_return r LEFT JOIN"
                                + " org_location o ON o.location_id = r.scope_org_unit_id WHERE"
                                + " r.cycle_instance_id = ? AND r.return_status = 'OPEN' ORDER BY"
                                + " r.return_id DESC",
                        (rs, i) ->
                                new ReturnView(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        eisOf(rs.getString(4)),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getTimestamp(8).toInstant(),
                                        rs.getLong(9)),
                        cycleId)
                .stream()
                .findFirst();
    }

    // ---------------------------------------------------------------- the Maker's un-freeze
    // request

    /**
     * SRS-15-31: asks for the un-freeze of the cycle of {@code period} at the Maker's unit, with a
     * reason; route {@code C02_UNFREEZE} decides it (plan 05 D-8).
     *
     * @throws FciException CYC-409-STATE (not frozen, computed or verified: never after lock),
     *     CYC-409-LEASE (a run is going on), CYC-409-PENDING (a lock or un-freeze request waits)
     */
    @Transactional
    public void requestUnfreeze(AccessContext context, YearMonth period, String reason) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "UNFREEZE", unit.getId());
        Map<String, Object> cycle = instance(period, unit);
        String status = (String) cycle.get("CYCLE_STATUS");
        if (!CycleCorrectionRules.unfreezable(status)) {
            throw state(status);
        }
        long cycleId = id(cycle);
        leases.requireFree(cycleId);
        requireNothingPending(cycleId);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "C-02",
                                "UNFREEZE",
                                UNFREEZE_ENTITY,
                                String.valueOf(cycleId),
                                ActionType.UPDATE,
                                unit.getId(),
                                json.writeValueAsString(
                                        Map.of(
                                                "cycleInstanceId",
                                                cycleId,
                                                "period",
                                                period.toString(),
                                                "unit",
                                                unit.getCode(),
                                                "fromStatus",
                                                status,
                                                "reason",
                                                reason)),
                                period.atEndOfMonth(),
                                reason),
                        null);
        audit.record(
                login(context),
                AuditService.Entry.change(
                                UNFREEZE_ENTITY,
                                String.valueOf(cycleId),
                                "unfreeze_request",
                                null,
                                "PENDING_APPROVAL",
                                reason)
                        .approval(item.id())
                        .module("C-02"));
    }

    /** The waiting un-freeze request of the instance, if any. */
    @Transactional(readOnly = true)
    public Long pendingUnfreeze(long cycleId) {
        return pending(UNFREEZE_ENTITY, cycleId);
    }

    /**
     * SRS-15-31 with SRS-15-11: the lock request waits for a pending un-freeze to be decided (the
     * two would race: one locks what the other reopens).
     */
    public void requireNoUnfreezePending(long cycleId) {
        if (pending(UNFREEZE_ENTITY, cycleId) != null) {
            throw pendingError("UNFREEZE");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void requireNothingPending(long cycleId) {
        if (pending(CycleService.ENTITY, cycleId) != null) {
            throw pendingError("LOCK");
        }
        requireNoUnfreezePending(cycleId);
    }

    private Long pending(String entity, long cycleId) {
        return CycleSupport.pending(jdbc, approvalStatuses, entity, String.valueOf(cycleId));
    }

    /** ORG_UNIT: an office whose cycle unit is this instance's unit, not the unit itself. */
    private Long officeBeneath(OrgLocation unit, String code) {
        OrgLocation office =
                org.byCode(code.strip()).orElseThrow(() -> scope("ORG_UNIT_NOT_BENEATH"));
        boolean beneath =
                !office.getId().equals(unit.getId())
                        && org.cycleUnitOf(office.getId())
                                .map(u -> u.getId().equals(unit.getId()))
                                .orElse(false);
        if (!beneath) {
            throw scope("ORG_UNIT_NOT_BENEATH");
        }
        return office.getId();
    }

    /**
     * MEMBERS: the employee ids (DDL: a JSON array of {@code employee_id}) of the named EIS
     * numbers, each with a result in the returned run.
     */
    private String members(long runId, List<String> eis) {
        // One query for the list (at most MAX_MEMBERS, within Oracle's IN-list limit), bound.
        List<Long> ids =
                named.queryForList(
                        "SELECT r.employee_id FROM payroll_result r JOIN employee e ON"
                                + " e.employee_id = r.employee_id WHERE r.run_id = :run AND"
                                + " e.emp_num IN (:eis) ORDER BY r.employee_id",
                        Map.of("run", runId, "eis", eis.stream().map(String::strip).toList()),
                        Long.class);
        if (ids.size() != eis.size()) {
            throw scope("MEMBER_WITHOUT_RESULT");
        }
        return json.writeValueAsString(ids);
    }

    /** The EIS numbers of a stored member list, for the screen. */
    private List<String> eisOf(String memberList) {
        if (memberList == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        // A MEMBERS return names at least one member (scopeDefect), so a stored list is never
        // empty.
        for (JsonNode id : json.readTree(memberList)) {
            ids.add(id.asLong());
        }
        return named.queryForList(
                "SELECT emp_num FROM employee WHERE employee_id IN (:ids) ORDER BY emp_num",
                Map.of("ids", ids),
                String.class);
    }

    /** Moves the status; the run of record goes with it (null = none). */
    private void moveStatus(long cycleId, String from, String to, Long currentRun) {
        int moved =
                jdbc.update(
                        "UPDATE payroll_cycle_instance SET cycle_status = ?, current_run_id = ?,"
                                + " row_version = row_version + 1 WHERE cycle_instance_id = ? AND"
                                + " cycle_status = ?",
                        to,
                        currentRun,
                        cycleId,
                        from);
        if (moved != 1) {
            throw state(from);
        }
    }

    private void auditStatus(
            String by, OrgLocation unit, YearMonth period, String from, String to, String reason) {
        audit.record(
                by,
                AuditService.Entry.change(
                                CycleService.ENTITY,
                                unit.getCode() + "|" + period,
                                "cycle_status",
                                from,
                                to,
                                reason)
                        .module("C-02"));
    }

    private Map<String, Object> instance(YearMonth period, OrgLocation unit) {
        return jdbc
                .queryForList(
                        "SELECT * FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                                + " org_unit_id = ?",
                        CycleKeys.yymm(period),
                        unit.getId())
                .stream()
                .findFirst()
                .orElseThrow(() -> state("NONE"));
    }

    private OrgLocation unit(AccessContext context) {
        return CycleSupport.unit(org, context);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return CycleSupport.orZero(n);
    }

    private static FciException state(Object status) {
        return new FciException(ApiErrorCode.CYCLE_STATE, new Object[] {status}, List.of(), null);
    }

    private static FciException scope(String defect) {
        return new FciException(
                ApiErrorCode.CYCLE_RETURN_SCOPE, new Object[] {defect}, List.of(), null);
    }

    private static FciException pendingError(String what) {
        return new FciException(
                ApiErrorCode.CYCLE_REQUEST_PENDING, new Object[] {what}, List.of(), null);
    }

    private static long id(Map<String, Object> cycle) {
        return ((Number) cycle.get("CYCLE_INSTANCE_ID")).longValue();
    }

    private static String login(AccessContext context) {
        return CycleSupport.login(context);
    }
}
