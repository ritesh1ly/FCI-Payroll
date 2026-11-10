package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.business.CalendarRules;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.payroll.business.RunOrderRules;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * M-10 Payroll Calendar & Cycle Setup (plan 05 phase 05f; SRS-15-33, SRS M-10, SRS-15-1): the
 * Administrator proposes a financial year's periods and the Engine Run Order, the Payroll Checker
 * approves (routes M10_EDIT, M10_REORDER); {@link PayrollCalendarEffect} applies them. A proposal
 * lives in its approval item until then (DDL {@code uk_payroll_calendar} holds one row per month),
 * so a returned or rejected proposal leaves the calendar as it was.
 */
@Service
@Loggable
public class PayrollCalendarService {

    /** Approval entity of a calendar proposal (key: the financial year). */
    public static final String CALENDAR_ENTITY = "payroll_calendar";

    /** Approval entity of a run-order proposal. */
    public static final String RUN_ORDER_ENTITY = "cycle_run_order";

    /** The approval key of the run order (one order for the whole system). */
    public static final String RUN_ORDER_KEY = "RUN_ORDER";

    private static final String SCREEN = "M-10";

    private static final long NO_UNIT = -1L;

    /**
     * A period as M-10 shows it.
     *
     * @param initiated a cycle was initiated for it at some office: its dates are fixed (D-12)
     * @param cycleStatus the state of the cycle at the session's unit (null: not initiated there,
     *     or no unit), the drawn Status column (SRS-15-12)
     * @param windowOpen the instance's supplementary or MDP window is open, a flag beside the
     *     status, never a step of it (SRS-15-12)
     */
    public record PeriodView(
            CalendarRules.Period period,
            boolean initiated,
            String cycleStatus,
            boolean windowOpen) {}

    /** A financial year's periods and its waiting proposal (null when none). */
    public record YearView(String finYear, List<PeriodView> periods, Long pendingApprovalId) {}

    /**
     * A step of the Engine Run Order (DDL {@code cycle_run_order}); {@code status} the engine state
     * of its payroll type in the session's unit and current period (SRS l.1637; DDL {@code
     * cycle_engine_status}), null for a step without an engine or before the engine has a row.
     */
    public record StepView(
            int sequence,
            String code,
            String name,
            String dependsOn,
            String ownerRole,
            String payrollType,
            String status) {}

    /** The run order and its waiting proposal (null when none). */
    public record RunOrderView(List<StepView> steps, Long pendingApprovalId) {}

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final ApprovalStatuses approvalStatuses;
    private final AuditService audit;
    private final PayrollPeriods periods;
    private final ObjectMapper json;

    public PayrollCalendarService(
            JdbcTemplate jdbc,
            OrgDirectory org,
            Authorization policy,
            MakerChecker approvals,
            ApprovalStatuses approvalStatuses,
            AuditService audit,
            PayrollPeriods periods,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.org = org;
        this.policy = policy;
        this.approvals = approvals;
        this.approvalStatuses = approvalStatuses;
        this.audit = audit;
        this.periods = periods;
        this.json = json;
    }

    // ---------------------------------------------------------------- the calendar

    /** The financial years M-10 offers: those with periods, the current one and the next. */
    @Transactional(readOnly = true)
    public List<String> years(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        TreeSet<String> years =
                new TreeSet<>(
                        jdbc.queryForList(
                                "SELECT DISTINCT fin_year FROM payroll_calendar", String.class));
        YearMonth current = periods.current();
        years.add(CycleKeys.finYear(current));
        years.add(CycleKeys.finYear(current.plusYears(1)));
        return List.copyOf(years.descendingSet());
    }

    @Transactional(readOnly = true)
    public YearView year(AccessContext context, String finYear) {
        policy.require(context, SCREEN, "VIEW");
        long unit = unitId(context);
        List<PeriodView> rows =
                jdbc.query(
                        "SELECT c.payroll_yymm, c.period_start_date, c.period_end_date,"
                                + " c.input_cutoff_date, c.compute_by_date, c.verify_by_date,"
                                + " c.lock_target_date, c.disbursement_date, c.supplementary_from,"
                                + " c.supplementary_to, c.mdp_window_from, c.mdp_window_to, (SELECT"
                                + " COUNT(*) FROM payroll_cycle_instance i WHERE i.payroll_yymm ="
                                + " c.payroll_yymm), u.cycle_status, u.supplementary_open,"
                                + " u.mdp_window_open FROM payroll_calendar c LEFT JOIN"
                                + " payroll_cycle_instance u ON u.payroll_yymm = c.payroll_yymm"
                                + " AND u.org_unit_id = ? WHERE c.fin_year = ? ORDER BY"
                                + " c.payroll_yymm",
                        (rs, i) ->
                                new PeriodView(
                                        new CalendarRules.Period(
                                                YearMonth.of(
                                                        Integer.parseInt(
                                                                rs.getString(1).substring(0, 4)),
                                                        Integer.parseInt(
                                                                rs.getString(1).substring(4))),
                                                date(rs.getDate(2)),
                                                date(rs.getDate(3)),
                                                date(rs.getDate(4)),
                                                date(rs.getDate(5)),
                                                date(rs.getDate(6)),
                                                date(rs.getDate(7)),
                                                date(rs.getDate(8)),
                                                date(rs.getDate(9)),
                                                date(rs.getDate(10)),
                                                date(rs.getDate(11)),
                                                date(rs.getDate(12))),
                                        rs.getLong(13) > 0,
                                        rs.getString(14),
                                        "Y".equals(rs.getString(15))
                                                || "Y".equals(rs.getString(16))),
                        unit,
                        finYear);
        return new YearView(finYear, rows, pending(CALENDAR_ENTITY, finYear));
    }

    /**
     * SRS-15-33 / SRS M-10: proposes the periods of {@code finYear} for the Checker's approval.
     *
     * @throws FciException CAL-422-PERIOD naming the month and the defect, CAL-409-IN-USE for a
     *     period a cycle was initiated for (D-12)
     */
    @Transactional
    public long propose(
            AccessContext context,
            String finYear,
            List<CalendarRules.Period> proposed,
            String reason) {
        policy.requireAt(context, SCREEN, "EDIT", context.locationId());
        List<CalendarRules.Period> approved = new ArrayList<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT payroll_yymm, period_start_date, period_end_date FROM"
                                + " payroll_calendar")) {
            String yymm = (String) row.get("PAYROLL_YYMM");
            approved.add(
                    new CalendarRules.Period(
                            YearMonth.of(
                                    Integer.parseInt(yymm.substring(0, 4)),
                                    Integer.parseInt(yymm.substring(4))),
                            date(row.get("PERIOD_START_DATE")),
                            date(row.get("PERIOD_END_DATE")),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
        }
        CalendarRules.defect(finYear, proposed, approved)
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    ApiErrorCode.CALENDAR_PERIOD,
                                    new Object[] {d.month().toString(), d.code()},
                                    List.of(),
                                    null);
                        });
        for (CalendarRules.Period p : proposed) {
            requireNotInUse(p.month());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CalendarRules.Period p : proposed) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", p.month().toString());
            row.put("start", text(p.start()));
            row.put("end", text(p.end()));
            row.put("freezeTarget", text(p.freezeTarget()));
            row.put("computeBy", text(p.computeBy()));
            row.put("verifyBy", text(p.verifyBy()));
            row.put("lockTarget", text(p.lockTarget()));
            row.put("disbursement", text(p.disbursement()));
            row.put("supplementaryFrom", text(p.supplementaryFrom()));
            row.put("supplementaryTo", text(p.supplementaryTo()));
            row.put("mdpFrom", text(p.mdpFrom()));
            row.put("mdpTo", text(p.mdpTo()));
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("finYear", finYear);
        payload.put("periods", rows);
        payload.put("reason", reason);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "EDIT",
                                CALENDAR_ENTITY,
                                finYear,
                                ActionType.UPDATE,
                                context.locationId(),
                                json.writeValueAsString(payload),
                                proposed.get(0).start(),
                                reason),
                        null);
        audit.record(
                login(context),
                AuditService.Entry.change(
                                CALENDAR_ENTITY,
                                finYear,
                                "proposal",
                                null,
                                "PENDING_APPROVAL",
                                reason)
                        .approval(item.id())
                        .module(SCREEN));
        return item.id();
    }

    /** D-12: the dates of a period are fixed once a cycle was initiated for it at any office. */
    void requireNotInUse(YearMonth month) {
        Long instances =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = ?",
                        Long.class,
                        CycleKeys.yymm(month));
        if (RowCounts.of(instances) > 0) {
            throw new FciException(
                    ApiErrorCode.CALENDAR_IN_USE, new Object[] {month.toString()}, List.of(), null);
        }
    }

    // ---------------------------------------------------------------- the run order

    @Transactional(readOnly = true)
    public RunOrderView runOrder(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        Map<String, String> engines = engineStates(context);
        List<StepView> steps =
                steps().stream()
                        .map(
                                s ->
                                        new StepView(
                                                s.sequence(),
                                                s.code(),
                                                s.name(),
                                                s.dependsOn(),
                                                s.ownerRole(),
                                                s.payrollType(),
                                                engines.get(s.payrollType())))
                        .toList();
        return new RunOrderView(steps, pending(RUN_ORDER_ENTITY, RUN_ORDER_KEY));
    }

    /**
     * SRS-15-1 / SRS-15-33 (D-13): proposes a new order of the active steps for the Checker.
     *
     * @throws FciException CAL-422-ORDER naming the step and the step it must follow
     */
    @Transactional
    public long proposeOrder(AccessContext context, List<String> order, String reason) {
        policy.requireAt(context, SCREEN, "REORDER", context.locationId());
        List<RunOrderRules.Step> steps =
                steps().stream().map(s -> new RunOrderRules.Step(s.code(), s.dependsOn())).toList();
        RunOrderRules.defect(order, steps)
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    ApiErrorCode.RUN_ORDER,
                                    new Object[] {
                                        d.code(),
                                        d.step(),
                                        d.dependsOn() == null ? "" : d.dependsOn()
                                    },
                                    List.of(),
                                    null);
                        });
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order", order);
        payload.put("reason", reason);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "REORDER",
                                RUN_ORDER_ENTITY,
                                RUN_ORDER_KEY,
                                ActionType.UPDATE,
                                context.locationId(),
                                json.writeValueAsString(payload),
                                periods.current().atDay(1),
                                reason),
                        null);
        audit.record(
                login(context),
                AuditService.Entry.change(
                                RUN_ORDER_ENTITY,
                                RUN_ORDER_KEY,
                                "proposal",
                                null,
                                "PENDING_APPROVAL",
                                reason)
                        .approval(item.id())
                        .module(SCREEN));
        return item.id();
    }

    /** The active steps in their order. */
    List<StepView> steps() {
        return jdbc.query(
                "SELECT sequence_no, engine_step_code, step_name, depends_on_step, owner_role_code,"
                        + " payroll_type FROM cycle_run_order WHERE is_active = 'Y' ORDER BY"
                        + " sequence_no, engine_step_code",
                (rs, i) ->
                        new StepView(
                                rs.getInt(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                null));
    }

    /** The engine states of the session's unit in the current period, by payroll type. */
    private Map<String, String> engineStates(AccessContext context) {
        Map<String, String> states = new LinkedHashMap<>();
        jdbc.query(
                "SELECT s.payroll_type, s.engine_state FROM cycle_engine_status s JOIN"
                        + " payroll_cycle_instance c ON c.cycle_instance_id = s.cycle_instance_id"
                        + " WHERE c.org_unit_id = ? AND c.payroll_yymm = ?",
                rs -> {
                    states.put(rs.getString(1), rs.getString(2));
                },
                unitId(context),
                CycleKeys.yymm(periods.current()));
        return states;
    }

    /** The session's cycle unit, or an id no row has when the session has none (M-10 is read). */
    private long unitId(AccessContext context) {
        return context.locationId() == null
                ? NO_UNIT
                : org.cycleUnitOf(context.locationId()).map(OrgLocation::getId).orElse(NO_UNIT);
    }

    // ---------------------------------------------------------------- helpers

    private Long pending(String entity, String key) {
        return CycleSupport.pending(jdbc, approvalStatuses, entity, key);
    }

    /**
     * A DATE column as JDBC hands it over: java.sql.Date, java.time, or a timestamp (H2's Oracle
     * mode stores DATE as TIMESTAMP).
     */
    static LocalDate date(Object value) {
        return CycleSupport.date(value);
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String login(AccessContext context) {
        return CycleSupport.login(context);
    }
}
