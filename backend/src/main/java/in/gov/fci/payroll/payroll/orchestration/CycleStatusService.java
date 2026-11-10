package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-01 Home - Cycle Status (plan 05 phase 05i; SRS D-01, SRS-15-14, SRS-15-39, SRS-15-40): the
 * current payroll period of the user's scope at a glance, read only. The scope is the payroll cycle
 * unit of the office the user works in and every cycle unit below it, so a Headquarters user sees
 * the roll-up across Regions and a unit user its own instance.
 *
 * <p>What the SRS names and no module built yet supplies (Retro Events I-08, Interface Health X-03,
 * the labour engines, DPS booking slips, incentive norms, pending I-21 changes) is returned with
 * the module that will supply it and no figure, never a zero (plan D-19).
 */
@Service
@Loggable
public class CycleStatusService {

    private static final String SCREEN = "D-01";

    /**
     * Why a figure or an engine row has no value yet: the labour wage runs (Departmental, DPS and
     * NWNP) wait for FCI's wage rules (PENDING LBR-20d-2, LBR-20d-3, LBR-21d-1).
     */
    static final String AWAITING_FCI = "AWAITING_FCI";

    private static final List<String> TYPES =
            List.of("STAFF", "DEPT_LABOUR", "DPS_LABOUR", "NWNP_LABOUR");

    /**
     * A figure, or why it has none yet ({@code unavailable}, a reason code the screen names); never
     * an internal build reference (owner 2026-10-29).
     */
    public record Figure(Long count, String unavailable) {
        static Figure of(long n) {
            return new Figure(n, null);
        }
    }

    /** SRS D-01 KPIs of the run of record of every instance in scope. */
    public record Kpis(
            long payable,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            Figure exceptions,
            Figure pendingApproval,
            Figure retroEvents,
            Figure interfaceHealth) {}

    /** One row of Computation Status by Payroll Type. */
    public record TypeRow(
            String payrollType,
            Long population,
            Long computed,
            Long exceptions,
            String status,
            String unavailable) {}

    /** One condition of the Blocking Panel ("What is blocking the cycle"). */
    public record Blocker(String code, Figure figure, String fixOn) {}

    /** The cycle states of the scope, counted (Cycle Status Summary). */
    public record StatusView(
            String period,
            String scopeCode,
            String scopeLabel,
            boolean rollUp,
            int units,
            Map<String, Long> byStatus,
            Kpis kpis,
            List<TypeRow> types,
            List<Blocker> blockers) {}

    /** One cycle unit of the scope (paged). */
    public record UnitRow(
            String unitCode,
            String unitLabel,
            String level,
            String status,
            Long members,
            BigDecimal net,
            boolean ownUnit) {}

    private final NamedParameterJdbcTemplate named;
    private final OrgDirectory org;
    private final Authorization policy;
    private final PayrollPeriods periods;

    public CycleStatusService(
            NamedParameterJdbcTemplate named,
            OrgDirectory org,
            Authorization policy,
            PayrollPeriods periods) {
        this.named = named;
        this.org = org;
        this.policy = policy;
        this.periods = periods;
    }

    @Transactional(readOnly = true)
    public StatusView status(AccessContext context) {
        Scope scope = scope(context);
        String yymm = CycleKeys.yymm(scope.period());
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String s :
                List.of("NOT_INITIATED", "INITIATED", "FROZEN", "COMPUTED", "VERIFIED", "LOCKED")) {
            byStatus.put(s, 0L);
        }
        long initiated = 0;
        for (List<Long> units : SimulationEngine.chunks(scope.units())) {
            for (Map<String, Object> r :
                    named.queryForList(
                            "SELECT cycle_status, COUNT(*) AS n FROM payroll_cycle_instance WHERE"
                                    + " payroll_yymm = :yymm AND org_unit_id IN (:units) GROUP BY"
                                    + " cycle_status",
                            params(yymm, units))) {
                long n = number(r.get("N"));
                byStatus.merge((String) r.get("CYCLE_STATUS"), n, Long::sum);
                initiated += n;
            }
        }
        byStatus.put("NOT_INITIATED", scope.units().size() - initiated);
        long payable = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        long exceptions = 0;
        long floor = 0;
        long failedChecks = 0;
        long hrmsPending = 0;
        Map<String, long[]> engines = new LinkedHashMap<>();
        for (List<Long> units : SimulationEngine.chunks(scope.units())) {
            MapSqlParameterSource p = params(yymm, units);
            Map<String, Object> totals =
                    named.queryForMap(
                            "SELECT COUNT(*) AS n, SUM(r.gross_earnings) AS g,"
                                + " SUM(r.total_deductions) AS d, SUM(r.net_pay) AS t, SUM(CASE"
                                + " WHEN r.net_pay <= 0 THEN 1 ELSE 0 END) AS f FROM payroll_result"
                                + " r JOIN payroll_cycle_instance c ON r.run_id = c.current_run_id"
                                + " WHERE c.payroll_yymm = :yymm AND c.org_unit_id IN (:units)",
                            p);
            payable += number(totals.get("N"));
            gross = gross.add(amount(totals.get("G")));
            deductions = deductions.add(amount(totals.get("D")));
            net = net.add(amount(totals.get("T")));
            floor += number(totals.get("F"));
            exceptions +=
                    count(
                            "SELECT COUNT(*) FROM payroll_exception x JOIN payroll_cycle_instance c"
                                    + " ON c.cycle_instance_id = x.cycle_instance_id WHERE"
                                    + " c.payroll_yymm = :yymm AND c.org_unit_id IN (:units) AND"
                                    + " x.exception_status IN ('OPEN', 'IN_PROGRESS',"
                                    + " 'ROUTED_UPSTREAM')",
                            p);
            failedChecks +=
                    count(
                            "SELECT COUNT(*) FROM cycle_prepay_check_result r JOIN"
                                + " payroll_cycle_instance c ON c.cycle_instance_id ="
                                + " r.cycle_instance_id WHERE c.payroll_yymm = :yymm AND"
                                + " c.org_unit_id IN (:units) AND r.severity_at_eval = 'BLOCK' AND"
                                + " r.check_result = 'FAIL' AND r.evaluated_at = (SELECT"
                                + " MAX(x.evaluated_at) FROM cycle_prepay_check_result x WHERE"
                                + " x.cycle_instance_id = r.cycle_instance_id AND x.check_id ="
                                + " r.check_id)",
                            p);
            hrmsPending +=
                    count(
                            "SELECT COALESCE(SUM(v.pending_task_count), 0) FROM"
                                + " v_cycle_pending_hrms_change v WHERE v.payroll_yymm = :yymm AND"
                                + " v.org_unit_id IN (:units)",
                            p);
            named.query(
                    "SELECT s.payroll_type, s.engine_state, s.member_count FROM cycle_engine_status"
                            + " s JOIN payroll_cycle_instance c ON c.cycle_instance_id ="
                            + " s.cycle_instance_id WHERE c.payroll_yymm = :yymm AND c.org_unit_id"
                            + " IN (:units)",
                    p,
                    rs -> {
                        long[] e = engines.computeIfAbsent(rs.getString(1), k -> new long[6]);
                        e[0] += rs.getLong(3);
                        e[stateSlot(rs.getString(2))]++;
                    });
        }
        long pending = 0;
        long bank = 0;
        long dpsSlips = 0;
        long withoutNorms = 0;
        // An event of several offices is counted once (by its id), whatever chunk finds it.
        Set<Long> retro = new java.util.HashSet<>();
        java.sql.Date first = java.sql.Date.valueOf(scope.period().atDay(1));
        java.sql.Date last = java.sql.Date.valueOf(scope.period().atEndOfMonth());
        for (List<Long> locations : SimulationEngine.chunks(scope.locations())) {
            MapSqlParameterSource p =
                    new MapSqlParameterSource("locations", locations)
                            .addValue("yymm", yymm)
                            .addValue("first", first)
                            .addValue("last", last);
            // SRS-8-13 / I-08: back-dated events raised or computed, not yet decided, in scope. An
            // event has at most one member (arrear_event_member keyed by the event): event ->
            // member -> employee by key, never every employee of the scope per event (P1; C-01
            // status p50 909 ms at 25 region desks on the volume set).
            retro.addAll(
                    named.queryForList(
                            "SELECT a.arrear_event_id FROM arrear_event a WHERE a.event_status IN"
                                + " ('RAISED', 'COMPUTED') AND (a.scope_location_id IN (:locations)"
                                + " OR EXISTS (SELECT 1 FROM employee e WHERE e.employee_id ="
                                + " (SELECT m.employee_id FROM arrear_event_member m WHERE"
                                + " m.arrear_event_id = a.arrear_event_id) AND e.location_id IN"
                                + " (:locations)))",
                            p,
                            Long.class));
            // SRS-4-13 / I-02: DPS booking-slips of the month the Depot Manager has not approved.
            dpsSlips +=
                    count(
                            "SELECT COUNT(*) FROM work_slip w WHERE w.engine_code = 'DPS_LABOUR'"
                                    + " AND w.work_slip_date BETWEEN :first AND :last AND"
                                    + " w.slip_status IN ('DRAFT', 'SUBMITTED', 'REJECTED') AND"
                                    + " w.depot_location_id IN (:locations)",
                            p);
            // M-06 / SRS-3-4: Departmental depots with active gangs and no approved norm in force
            // at
            // the month end (a missing norm is not a zero norm: the incentive is not computed).
            withoutNorms +=
                    count(
                            "SELECT COUNT(DISTINCT g.depot_location_id) FROM labour_gang g WHERE"
                                + " g.payroll_type = 'DEPT_LABOUR' AND g.gang_status = 'ACTIVE' AND"
                                + " g.depot_location_id IN (:locations) AND NOT EXISTS (SELECT 1"
                                + " FROM labour_norm n LEFT JOIN approval_queue q ON q.approval_id"
                                + " = n.approval_id WHERE n.depot_location_id = g.depot_location_id"
                                + " AND n.engine_code = 'DEPT_LABOUR' AND n.effective_from <= :last"
                                + " AND (n.effective_to IS NULL OR n.effective_to >= :last) AND"
                                + " (n.approval_id IS NULL OR q.status = 'APPROVED'))",
                            p);
            pending +=
                    count(
                            "SELECT COUNT(*) FROM workflow_inbox_item w JOIN approval_queue q ON"
                                + " q.approval_id = w.approval_id WHERE w.item_status IN"
                                + " ('PENDING', 'IN_PROGRESS') AND q.status = 'PENDING_APPROVAL'"
                                + " AND w.org_scope_id IN (:locations)",
                            p);
            bank +=
                    count(
                            "SELECT COUNT(*) FROM employee e WHERE e.record_status = 'ACTIVE' AND"
                                    + " e.employment_status NOT IN ('SEPARATED', 'DISMISSED') AND"
                                    + " e.location_id IN (:locations) AND (e.bank_account_number IS"
                                    + " NULL OR e.bank_branch_id IS NULL)",
                            p);
        }
        List<TypeRow> types = new ArrayList<>();
        for (String type : TYPES) {
            long[] e = engines.get(type);
            if (!"STAFF".equals(type)) {
                types.add(new TypeRow(type, null, null, null, null, AWAITING_FCI));
            } else {
                types.add(
                        new TypeRow(
                                type,
                                e == null ? 0 : e[0],
                                payable,
                                exceptions,
                                engineStatus(e),
                                null));
            }
        }
        List<Blocker> blockers =
                List.of(
                        new Blocker("NET_PAY_FLOOR", Figure.of(floor), "C-02"),
                        new Blocker("BANK_DETAILS", Figure.of(bank), "M-01"),
                        new Blocker("DPS_BOOKING_SLIP", Figure.of(dpsSlips), "I-02"),
                        new Blocker("INCENTIVE_NORMS", Figure.of(withoutNorms), "M-06"),
                        new Blocker("PRE_PAYROLL_CHECK", Figure.of(failedChecks), "C-01"),
                        new Blocker("HRMS_CHANGE_PENDING", Figure.of(hrmsPending), "I-21"));
        return new StatusView(
                scope.period().toString(),
                scope.root().getCode(),
                scope.root().getName(),
                scope.units().size() > 1,
                scope.units().size(),
                byStatus,
                new Kpis(
                        payable,
                        gross,
                        deductions,
                        net,
                        Figure.of(exceptions),
                        Figure.of(pending),
                        Figure.of(retro.size()),
                        Figure.of(openInterfaceItems())),
                types,
                blockers);
    }

    /** The cycle units of the scope with their state this period, own unit first, then by code. */
    @Transactional(readOnly = true)
    public Paged<UnitRow> units(AccessContext context, PageSpec spec) {
        Scope scope = scope(context);
        List<OrgLocation> all = new ArrayList<>(org.locations(scope.units()));
        all.sort(
                (a, b) -> {
                    boolean ownA = a.getId().equals(scope.own());
                    boolean ownB = b.getId().equals(scope.own());
                    return ownA != ownB ? (ownA ? -1 : 1) : a.getCode().compareTo(b.getCode());
                });
        Paged<OrgLocation> page = Paged.slice(all, spec);
        if (page.items().isEmpty()) {
            return new Paged<>(List.of(), spec.page(), spec.size(), all.size());
        }
        String yymm = CycleKeys.yymm(scope.period());
        Map<Long, Object[]> state = new LinkedHashMap<>();
        named.query(
                "SELECT c.org_unit_id, c.cycle_status, (SELECT COUNT(*) FROM payroll_result r WHERE"
                    + " r.run_id = c.current_run_id) AS n, (SELECT SUM(r.net_pay) FROM"
                    + " payroll_result r WHERE r.run_id = c.current_run_id) AS t FROM"
                    + " payroll_cycle_instance c WHERE c.payroll_yymm = :yymm AND c.org_unit_id IN"
                    + " (:units)",
                params(yymm, page.items().stream().map(OrgLocation::getId).toList()),
                rs -> {
                    state.put(
                            rs.getLong(1),
                            new Object[] {rs.getString(2), rs.getLong(3), rs.getBigDecimal(4)});
                });
        return page.map(
                u -> {
                    Object[] s = state.get(u.getId());
                    return new UnitRow(
                            u.getCode(),
                            u.getName(),
                            CycleKeys.level(u.getType()),
                            s == null ? "NOT_INITIATED" : (String) s[0],
                            s == null ? null : (Long) s[1],
                            s == null ? null : (BigDecimal) s[2],
                            u.getId().equals(scope.own()));
                });
    }

    // ---------------------------------------------------------------- helpers

    private record Scope(
            OrgLocation root, Long own, YearMonth period, List<Long> units, List<Long> locations) {}

    /**
     * The user's office, its payroll cycle unit, every cycle unit below the office, and the
     * locations they pay.
     */
    private Scope scope(AccessContext context) {
        if (context.locationId() == null) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        policy.requireAt(context, SCREEN, "VIEW", context.locationId());
        OrgLocation office =
                org.location(context.locationId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        OrgLocation own =
                org.cycleUnitOf(office.getId())
                        .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT));
        Set<Long> units = new LinkedHashSet<>();
        units.add(own.getId());
        Set<Long> locations = new LinkedHashSet<>();
        for (Long id : org.subtree(office.getId())) {
            org.location(id).filter(OrgLocation::isCycleUnit).ifPresent(u -> units.add(u.getId()));
        }
        for (Long unit : units) {
            for (Long id : org.subtree(unit)) {
                org.cycleUnitOf(id)
                        .filter(u -> units.contains(u.getId()))
                        .ifPresent(u -> locations.add(id));
            }
        }
        OrgLocation root = units.size() > 1 ? office : own;
        return new Scope(
                root,
                own.getId(),
                periods.current(),
                units.stream().sorted().toList(),
                locations.stream().sorted().toList());
    }

    /**
     * STAFF engine roll-up over the instances in scope: the most urgent of FAILED, PARTIAL and
     * RUNNING; else COMPLETE or NOT_STARTED when all agree, PARTIAL when some completed and some
     * did not start.
     */
    static String engineStatus(long[] e) {
        if (e == null) {
            return "NOT_STARTED";
        }
        if (e[5] > 0) {
            return "FAILED";
        }
        if (e[4] > 0) {
            return "PARTIAL";
        }
        if (e[2] > 0) {
            return "RUNNING";
        }
        if (e[3] > 0 && e[1] > 0) {
            return "PARTIAL";
        }
        return e[3] > 0 ? "COMPLETE" : "NOT_STARTED";
    }

    static int stateSlot(String state) {
        return switch (state) {
            case "RUNNING" -> 2;
            case "COMPLETE" -> 3;
            case "PARTIAL" -> 4;
            case "FAILED" -> 5;
            default -> 1;
        };
    }

    /** X-03 interface health: exceptions still open on any interface (interfaces are HQ-wide). */
    private long openInterfaceItems() {
        return count(
                "SELECT COUNT(*) FROM interface_exception WHERE remediation_status IN ('OPEN',"
                        + " 'IN_PROGRESS')",
                new MapSqlParameterSource());
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long n = named.queryForObject(sql, params, Long.class);
        return CycleSupport.orZero(n);
    }

    private static MapSqlParameterSource params(String yymm, List<Long> units) {
        return new MapSqlParameterSource("yymm", yymm).addValue("units", units);
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static BigDecimal amount(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}
