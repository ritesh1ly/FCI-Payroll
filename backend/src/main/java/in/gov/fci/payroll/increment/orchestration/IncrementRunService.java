package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.increment.business.IncrementErrorCode;
import in.gov.fci.payroll.increment.business.IncrementRules;
import in.gov.fci.payroll.increment.business.IncrementRules.Flag;
import in.gov.fci.payroll.penalty.api.IncrementStops;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * I-05 annual increment run (plan 17 phase 17c; SRS-13-1, SRS-13-5, SRS-13-8, SRS-13-21, SRS-13-23,
 * SRS-13-4; D17-3, D17-4). The month's Increment Due list is every staff member of the session's
 * offices whose next increment due date ({@code employee_increment_due}) falls in or before the
 * month, with the revised Basic proposed for the actual due date: the next stage of the scale
 * version in force, or - with no stages - the band's increment rule in the rule engine; a member
 * barred by an I-06 order, above the scale maximum, not on a stage, without a scale or without a
 * configured rule is flagged and never incremented. A member at the scale maximum is evaluated for
 * the stagnation increment on the due date (BR-INCR-STAG-001, 007; SRS-13-14, the test shared with
 * the individual case): when eligible the run proposes it, with the test that fired; otherwise the
 * member stays flagged above the maximum with the reason. Establishment / HR submits the members it
 * selects, one approval item each (a member already waiting is refused by the queue); on the
 * Payroll Checker's approval the increment is recorded ({@code pay_increment_cycle}), the Basic
 * event written from the due date (an arrear when it is in a past month) and the next due date set
 * a year on. A rejection changes nothing.
 */
@Service
@Loggable
public class IncrementRunService {

    public static final String SCREEN = "I-05";
    public static final String ACTION = "I05_INCREMENT";
    static final String ENTITY = "pay_increment_cycle";

    /** The flags that stop an increment (STAGES_NOT_CONFIGURED only warns, D17-3). */
    static final Set<Flag> BLOCKING =
            Set.of(
                    Flag.BARRED,
                    Flag.ABOVE_MAXIMUM,
                    Flag.NOT_A_STAGE,
                    Flag.NOT_CONFIGURED,
                    Flag.NO_SCALE);

    /** One member of the due list with the proposal. */
    public record DueRow(
            long employeeId,
            String eis,
            String name,
            String office,
            LocalDate dueDate,
            BigDecimal oldBasic,
            BigDecimal revisedBasic,
            List<String> flags,
            List<String> barredBy,
            boolean pending,
            String incrementType,
            String stagnation,
            String stagnationTest,
            String payScaleCode,
            LocalDate nextDueDate) {}

    /** The outcome of a submission: items sent, members refused with why. */
    public record Submitted(List<Long> approvalIds, List<Refused> refused) {}

    public record Refused(String eis, String reason) {}

    private static final String DUE =
            " FROM employee e JOIN employee_increment_due d ON d.employee_id = e.employee_id JOIN"
                    + " org_location l ON l.location_id = e.location_id WHERE e.record_status ="
                    + " 'ACTIVE' AND e.employment_status = 'IN_SERVICE' AND e.emp_lbr_flag = 'N'"
                    + " AND d.next_due_date <= :end AND (:all = 'Y' OR e.location_id IN"
                    + " (:locations))";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final Scales scales;
    private final RuleEngine rules;
    private final IncrementStops stops;
    private final Stagnation stagnation;
    private final BasicPayService basic;
    private final ObjectMapper json;
    private final TransactionTemplate atomic;

    public IncrementRunService(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            MakerChecker approvals,
            Scales scales,
            RuleEngine rules,
            IncrementStops stops,
            Stagnation stagnation,
            BasicPayService basic,
            ObjectMapper json,
            PlatformTransactionManager transactions) {
        this.named = named;
        this.policy = policy;
        this.approvals = approvals;
        this.scales = scales;
        this.rules = rules;
        this.stops = stops;
        this.stagnation = stagnation;
        this.basic = basic;
        this.json = json;
        this.atomic = new TransactionTemplate(transactions);
    }

    /**
     * SRS-13-21: the Increment Due list of the month, one page, each with its proposal. Not
     * transactional: each rule evaluation runs on its own, so a member whose rule is not configured
     * is flagged without affecting the others.
     */
    public Paged<DueRow> due(AccessContext context, YearMonth month, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p =
                scoped(context).addValue("end", Date.valueOf(month.atEndOfMonth()));
        Long total = named.queryForObject("SELECT COUNT(*)" + DUE, p, Long.class);
        p.addValue("offset", (long) page.page() * page.size()).addValue("size", page.size());
        List<Map<String, Object>> rows =
                named.queryForList(
                        "SELECT e.employee_id, e.emp_num, e.first_name, e.last_name,"
                                + " e.location_id, e.band_id, e.current_basic, d.next_due_date,"
                                + " l.location_code, l.location_name"
                                + DUE
                                + " ORDER BY d.next_due_date, e.emp_num, e.employee_id OFFSET"
                                + " :offset ROWS FETCH"
                                + " NEXT :size ROWS ONLY",
                        p);
        // P2: one scale lookup per band and due date of the page, one pending check for the page.
        Map<String, Optional<Scales.Scale>> scalesOfPage = new HashMap<>();
        Set<String> waiting = pending(rows);
        List<DueRow> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("EMPLOYEE_ID")).longValue();
            LocalDate due = Dates.local(r.get("NEXT_DUE_DATE"));
            Number band = (Number) r.get("BAND_ID");
            Optional<Scales.Scale> scale =
                    band == null
                            ? Optional.empty()
                            : scalesOfPage.computeIfAbsent(
                                    band + "|" + due, k -> scales.of(band.longValue(), due));
            Proposal pr =
                    propose(
                            id,
                            (BigDecimal) r.get("CURRENT_BASIC"),
                            due,
                            (Number) r.get("LOCATION_ID"),
                            scale);
            String first = (String) r.get("FIRST_NAME");
            String last = (String) r.get("LAST_NAME");
            out.add(
                    new DueRow(
                            id,
                            (String) r.get("EMP_NUM"),
                            ((first == null ? "" : first) + " " + (last == null ? "" : last))
                                    .strip(),
                            r.get("LOCATION_CODE") + " - " + r.get("LOCATION_NAME"),
                            due,
                            (BigDecimal) r.get("CURRENT_BASIC"),
                            pr.revised(),
                            pr.flags().stream().map(Enum::name).toList(),
                            pr.barredBy(),
                            waiting.contains(id + ":" + IncrementRules.duePeriod(due)),
                            pr.type(),
                            pr.stagnation() == null ? null : pr.stagnation().name(),
                            pr.test(),
                            // I-05 l.4001-4002: the HRMS pay scale of the member and the next
                            // increment due date once this one is applied (SRS-13-1).
                            scale.map(Scales.Scale::bandCode).orElse(null),
                            IncrementRules.nextDue(due)));
        }
        return new Paged<>(out, page.page(), page.size(), RowCounts.of(total));
    }

    /**
     * SRS-13-14 / I-05: Establishment / HR submits the selected members, one item each. A member
     * flagged, already incremented or awaiting the Checker is reported and nothing is written for
     * them; the accepted ones are submitted together or not at all.
     */
    public Submitted submit(AccessContext context, List<Long> employeeIds, String remark) {
        policy.require(context, SCREEN, "SUBMIT");
        if (employeeIds == null || employeeIds.isEmpty() || remark == null || remark.isBlank()) {
            throw new FciException(
                    IncrementErrorCode.ENTRY,
                    new Object[] {"select members and give a remark"},
                    List.of(),
                    null);
        }
        // All or nothing: every member is checked first (reads only); the accepted ones are then
        // submitted in one transaction, so a failure leaves no approval behind.
        MapSqlParameterSource scope = scoped(context);
        List<Submission> accepted = new ArrayList<>();
        List<Refused> refused = new ArrayList<>();
        for (Long id : employeeIds) {
            List<Map<String, Object>> found =
                    named.queryForList(
                            "SELECT e.emp_num, e.location_id, e.current_basic, d.next_due_date"
                                    + DUE.replace(" AND d.next_due_date <= :end", "")
                                    + " AND e.employee_id = :id",
                            new MapSqlParameterSource(scope.getValues()).addValue("id", id));
            if (found.isEmpty()) {
                throw new FciException(CommonErrorCode.NOT_FOUND);
            }
            Map<String, Object> m = found.getFirst();
            String eis = (String) m.get("EMP_NUM");
            LocalDate due = Dates.local(m.get("NEXT_DUE_DATE"));
            BigDecimal old = (BigDecimal) m.get("CURRENT_BASIC");
            Proposal pr = propose(id, old, due, (Number) m.get("LOCATION_ID"));
            List<String> blocking =
                    pr.flags().stream().filter(BLOCKING::contains).map(Enum::name).toList();
            if (!blocking.isEmpty()) {
                refused.add(new Refused(eis, String.join(", ", blocking)));
                continue;
            }
            String period = IncrementRules.duePeriod(due);
            if (exists(id, period, pr.type())) {
                refused.add(new Refused(eis, "INC-409-DUPLICATE " + period));
                continue;
            }
            if (pending(id, due)) {
                refused.add(new Refused(eis, "INC-409-STATE " + period));
                continue;
            }
            String payload =
                    json.writeValueAsString(
                            Map.of(
                                    "employeeId", id,
                                    "due", due.toString(),
                                    "old", old.toPlainString(),
                                    "revised", pr.revised().toPlainString(),
                                    "period", period,
                                    "type", pr.type(),
                                    // SRS-13-19 (US-GAP-52): the test that fired a stagnation
                                    // increment, shown to the Checker and kept with the event.
                                    "test", pr.test() == null ? "" : pr.test()));
            accepted.add(
                    new Submission(
                                    ACTION,
                                    SCREEN,
                                    "SUBMIT",
                                    ENTITY,
                                    id + ":" + period,
                                    ActionType.UPDATE,
                                    ((Number) m.get("LOCATION_ID")).longValue(),
                                    payload,
                                    due,
                                    remark.strip())
                            .withImpact(pr.revised().subtract(old)));
        }
        List<Long> sent =
                atomic.execute(
                        s ->
                                accepted.stream()
                                        .map(
                                                x ->
                                                        approvals
                                                                .submit(context, x, (OtpProof) null)
                                                                .id())
                                        .toList());
        return new Submitted(sent, refused);
    }

    /** SRS-13-4 / 13-14: the Checker's decision; a rejection changes nothing. */
    @EventListener
    public void on(ApprovalDecided d) {
        if (!d.concerns(ENTITY) || !d.approved()) {
            return;
        }
        JsonNode p = json.readTree(d.payload());
        long employee = p.path("employeeId").asLong();
        LocalDate due = LocalDate.parse(p.path("due").asString());
        String period = p.path("period").asString();
        BigDecimal old = new BigDecimal(p.path("old").asString());
        BigDecimal revised = new BigDecimal(p.path("revised").asString());
        // Items submitted before the stagnation offer carry no type: they are annual increments.
        String type = p.has("type") ? p.path("type").asString() : "ANNUAL";
        if (exists(employee, period, type)) {
            throw new FciException(
                    IncrementErrorCode.DUPLICATE, new Object[] {employee, period}, List.of(), null);
        }
        record(
                employee,
                due,
                period,
                type,
                old,
                revised,
                d.approvalId(),
                d.checkerLoginId(),
                "STAGNATION".equals(type) ? "STAGNATION_INCREMENT" : "INCREMENT",
                p.path("test").asString(""));
    }

    /**
     * Records an approved increment of any type and its Basic event; the next due date a year on
     * (an annual or stagnation increment). Shared with the individual cases (17d / 17e).
     */
    long record(
            long employee,
            LocalDate due,
            String period,
            String type,
            BigDecimal old,
            BigDecimal revised,
            Long approvalId,
            String by,
            String eventType) {
        return record(employee, due, period, type, old, revised, approvalId, by, eventType, "");
    }

    /**
     * As {@link #record(long, LocalDate, String, String, BigDecimal, BigDecimal, Long, String,
     * String)}, keeping the eligibility test of a stagnation auto-trigger with the Basic event
     * (SRS-13-19).
     */
    long record(
            long employee,
            LocalDate due,
            String period,
            String type,
            BigDecimal old,
            BigDecimal revised,
            Long approvalId,
            String by,
            String eventType,
            String test) {
        MapSqlParameterSource row =
                new MapSqlParameterSource("employee", employee)
                        .addValue("period", period)
                        .addValue("type", type)
                        .addValue("due", Date.valueOf(due))
                        .addValue("next", Date.valueOf(IncrementRules.nextDue(due)))
                        .addValue("old", old)
                        .addValue("revised", revised)
                        .addValue("approval", approvalId);
        named.update(
                "INSERT INTO pay_increment_cycle (employee_id, due_period, increment_type,"
                        + " increment_due_date, next_due_date, effective_date, old_basic,"
                        + " revised_basic, approval_id) VALUES (:employee, :period, :type, :due,"
                        + " :next, :due, :old, :revised, :approval)",
                row);
        Long id =
                named.queryForObject(
                        "SELECT increment_id FROM pay_increment_cycle WHERE employee_id = :employee"
                                + " AND due_period = :period AND increment_type = :type",
                        row,
                        Long.class);
        BasicPayService.Applied applied =
                basic.apply(
                        new BasicPayService.Change(
                                employee,
                                revised,
                                due,
                                eventType,
                                "CALC",
                                IncrementRules.reason(type, period, test),
                                null,
                                null,
                                null,
                                approvalId,
                                by,
                                "INCREMENT",
                                ENTITY,
                                String.valueOf(id)));
        named.update(
                "UPDATE pay_increment_cycle SET basic_history_id = :history WHERE increment_id ="
                        + " :id",
                new MapSqlParameterSource("history", applied.historyId()).addValue("id", id));
        if ("ANNUAL".equals(type) || "STAGNATION".equals(type)) {
            int changed =
                    named.update(
                            "UPDATE employee_increment_due SET next_due_date = :next, source_code ="
                                    + " 'CALC', hrms_change_task_id = NULL, updated_by = :by,"
                                    + " updated_at = CURRENT_TIMESTAMP WHERE employee_id ="
                                    + " :employee",
                            row.addValue("by", by));
            if (changed == 0) {
                named.update(
                        "INSERT INTO employee_increment_due (employee_id, next_due_date,"
                                + " source_code, updated_by, updated_at) VALUES (:employee, :next,"
                                + " 'CALC', :by, CURRENT_TIMESTAMP)",
                        row);
            }
        }
        return id == null ? -1 : id;
    }

    // ---------------------------------------------------------------- proposal

    /**
     * The proposal: the revised Basic, its flags, the orders barring it, the increment type (ANNUAL
     * or STAGNATION) and, for a member at the scale maximum, the stagnation verdict and its test.
     */
    record Proposal(
            BigDecimal revised,
            List<Flag> flags,
            List<String> barredBy,
            String type,
            Stagnation.Verdict stagnation,
            String test) {

        Proposal(BigDecimal revised, List<Flag> flags, List<String> barredBy) {
            this(revised, flags, barredBy, "ANNUAL", null, null);
        }
    }

    /** The revised Basic for the due date and its flags. */
    Proposal propose(long employee, BigDecimal old, LocalDate due, Number location) {
        return propose(employee, old, due, location, scales.ofMember(employee, due));
    }

    /** The proposal with the member's scale already looked up (the due list shares them, P2). */
    private Proposal propose(
            long employee,
            BigDecimal old,
            LocalDate due,
            Number location,
            Optional<Scales.Scale> scale) {
        List<Flag> flags = new ArrayList<>();
        List<String> barredBy = stops.stopping(employee, due);
        if (!barredBy.isEmpty()) {
            flags.add(Flag.BARRED);
        }
        if (scale.isEmpty() || old == null) {
            flags.add(Flag.NO_SCALE);
            return new Proposal(old, flags, barredBy);
        }
        Scales.Scale s = scale.get();
        BigDecimal revised = old;
        if (!s.stages().isEmpty()) {
            Optional<BigDecimal> next = IncrementRules.nextStageAbove(old, s.stages());
            if (next.isEmpty()) {
                flags.add(Flag.ABOVE_MAXIMUM);
            } else {
                revised = next.get();
            }
            IncrementRules.stage(old, s.stages()).ifPresent(flags::add);
        } else {
            flags.add(Flag.STAGES_NOT_CONFIGURED);
            Optional<BigDecimal> amount = increment(s, old, due, location);
            if (amount.isEmpty()) {
                flags.add(Flag.NOT_CONFIGURED);
            } else {
                revised = old.add(amount.get());
            }
        }
        IncrementRules.scale(revised, s.max(), false)
                .filter(f -> !flags.contains(f))
                .ifPresent(flags::add);
        if (s.max() != null && old.compareTo(s.max()) >= 0) {
            return atMaximum(employee, old, due, s, flags, barredBy, revised);
        }
        return new Proposal(revised, flags, barredBy);
    }

    /**
     * BR-INCR-STAG-001 / 007: a member at the scale maximum is evaluated on the due date. Eligible:
     * the stagnation increment is proposed in place of the annual one (a Basic above the maximum
     * after an earlier one is not a stage, and that is expected). Not eligible: the member stays
     * flagged above the maximum, with the reason.
     */
    private Proposal atMaximum(
            long employee,
            BigDecimal old,
            LocalDate due,
            Scales.Scale s,
            List<Flag> flags,
            List<String> barredBy,
            BigDecimal revised) {
        Stagnation.Outcome o = stagnation.evaluate(employee, old, s, due);
        if (!o.eligible()) {
            if (!flags.contains(Flag.ABOVE_MAXIMUM)) {
                flags.add(Flag.ABOVE_MAXIMUM);
            }
            return new Proposal(revised, flags, barredBy, "ANNUAL", o.verdict(), null);
        }
        flags.removeAll(List.of(Flag.ABOVE_MAXIMUM, Flag.NOT_A_STAGE, Flag.NOT_CONFIGURED));
        flags.add(Flag.STAGNATION);
        return new Proposal(o.revised(), flags, barredBy, "STAGNATION", o.verdict(), o.test());
    }

    /** D17-4: the increment amount from the band's rule (empty when it is not configured). */
    private Optional<BigDecimal> increment(
            Scales.Scale s, BigDecimal old, LocalDate due, Number location) {
        if (s.incrementRuleKey() == null) {
            return Optional.empty();
        }
        try {
            return rules.evaluate(
                    s.incrementRuleKey(),
                    due,
                    new RuleEngine.Context("STAFF", location == null ? null : location.longValue()),
                    Map.of("BASIC", old));
        } catch (FciException ex) {
            return Optional.empty();
        }
    }

    boolean exists(long employee, String period, String type) {
        Long n =
                named.queryForObject(
                        "SELECT COUNT(*) FROM pay_increment_cycle WHERE employee_id = :employee AND"
                                + " due_period = :period AND increment_type = :type",
                        new MapSqlParameterSource("employee", employee)
                                .addValue("period", period)
                                .addValue("type", type),
                        Long.class);
        return RowCounts.of(n) > 0;
    }

    /** The page's members awaiting the Checker, as {@code employee:period} keys (one query). */
    private Set<String> pending(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Set.of();
        }
        List<String> keys =
                rows.stream()
                        .map(
                                r ->
                                        ((Number) r.get("EMPLOYEE_ID")).longValue()
                                                + ":"
                                                + IncrementRules.duePeriod(
                                                        Dates.local(r.get("NEXT_DUE_DATE"))))
                        .toList();
        return new java.util.HashSet<>(
                named.queryForList(
                        "SELECT entity_key FROM approval_queue WHERE entity_name = :entity AND"
                                + " entity_key IN (:keys) AND status IN ('PENDING_APPROVAL',"
                                + " 'PENDING_ATTACHMENT')",
                        new MapSqlParameterSource("entity", ENTITY).addValue("keys", keys),
                        String.class));
    }

    /** Whether the member's increment of {@code due} is with the Checker (I-05 worklist). */
    boolean waiting(long employee, LocalDate due) {
        return pending(employee, due);
    }

    private boolean pending(long employee, LocalDate due) {
        Long n =
                named.queryForObject(
                        "SELECT COUNT(*) FROM approval_queue WHERE entity_name = :entity AND"
                                + " entity_key = :key AND status IN ('PENDING_APPROVAL',"
                                + " 'PENDING_ATTACHMENT')",
                        new MapSqlParameterSource("entity", ENTITY)
                                .addValue("key", employee + ":" + IncrementRules.duePeriod(due)),
                        Long.class);
        return RowCounts.of(n) > 0;
    }

    private MapSqlParameterSource scoped(AccessContext context) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context));
    }
}
