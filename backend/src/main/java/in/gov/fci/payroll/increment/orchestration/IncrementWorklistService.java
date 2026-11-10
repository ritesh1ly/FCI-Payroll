package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.increment.business.IncrementRules;
import in.gov.fci.payroll.increment.business.IncrementRules.Flag;
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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The read side of I-05 as Wireframes v6.1 draws it (OD-121; SRS-13-4, SRS-13-5, SRS-13-9,
 * SRS-13-19, SRS-13-21; DDL pay_increment_cycle, pay_fixation_event, hrms_change_task). The
 * Increment / Fixation Worklist puts one line per item of the month in one list: a member due (with
 * the run's proposal), an increment or fixation decided in the month, an open HRMS task on the
 * Basic or the increment date, and a case with the Checker. A case carries its member in the
 * approval payload, so those few lines come first and the rest pages in the database. API-17-2.
 *
 * <p>Labour stay out of this list as they stay out of the run: the due-date basis waits for FCI
 * (SRS-2-54, OQ-9), and SRS-2-54 corrects a labourer's date in the labour master (WF-1304).
 */
@Service
@Loggable
public class IncrementWorklistService {

    private static final String SCREEN = "I-05";
    private static final String CASE = "pay_fixation_case";

    /** The drawn tiles and rule figures of the month. */
    public record Counts(
            long due,
            long annual,
            long stagnation,
            long fixations,
            long retrospective,
            Long latestArrearEvent,
            long atMaximum,
            long duplicates) {}

    /**
     * One line of the worklist.
     *
     * @param kind DUE, APPLIED, FIXATION, HRMS_TASK or CASE
     * @param key what identifies the line within its kind
     * @param type ANNUAL, STAGNATION, a case type, or the task's field code
     * @param incrementPct the band's rule value where the scale has one, never derived from Basic
     * @param status DUE, BARRED, FLAGGED, CHECKER, RETRO, APPROVED, DUPLICATE or HRMS_TASK
     * @param flags the run's flags of a DUE line
     * @param approvalId the approval item, where there is one
     */
    public record Row(
            String kind,
            String key,
            long employeeId,
            String eis,
            String employee,
            String scale,
            String type,
            BigDecimal presentBasic,
            BigDecimal incrementPct,
            BigDecimal revisedBasic,
            LocalDate effective,
            boolean atMaximum,
            Long arrearEvent,
            BigDecimal arrearAmount,
            String status,
            List<String> flags,
            List<String> barredBy,
            String orderReference,
            Long approvalId) {}

    private static final String MEMBER_DUE =
            " FROM employee e JOIN employee_increment_due d ON d.employee_id = e.employee_id LEFT"
                    + " JOIN pay_scale_band b ON b.band_id = e.band_id WHERE e.record_status ="
                    + " 'ACTIVE' AND e.employment_status = 'IN_SERVICE' AND e.emp_lbr_flag = 'N'"
                    + " AND d.next_due_date <= :end AND "
                    + "(:all = 'Y' OR e.location_id IN (:locations))";

    private static final String AT_MAXIMUM =
            " EXISTS (SELECT 1 FROM pay_scale_band_version v WHERE v.band_id = e.band_id AND"
                    + " v.effective_from <= :end AND (v.effective_to IS NULL OR v.effective_to >="
                    + " :end) AND v.max_basic IS NOT NULL AND e.current_basic >= v.max_basic)";

    /** Open tasks on the Basic or the increment date (SRS-13-20, the Payroll Maker's I-21). */
    private static final String TASK_OPEN =
            " k.field_code IN ('BASIC_PAY', 'INCREMENT_DATE') AND t.task_status IN"
                    + " ('PENDING_ORDER_DETAILS', 'PENDING_CHECKER', 'RETURNED_TO_MAKER')";

    private static final String IN_SCOPE = "(:all = 'Y' OR e.location_id IN (:locations))";

    private static final String WORK =
            " FROM (SELECT 1 AS rank_no, 'DUE' AS kind, e.employee_id AS row_key, e.employee_id,"
                    + " e.emp_num, e.first_name, e.last_name, e.band_id, b.band_code,"
                    + " e.location_id, CAST(NULL AS VARCHAR2(40)) AS row_type, e.current_basic AS"
                    + " present_basic, CAST(NULL AS NUMBER(14,2)) AS revised_basic, d.next_due_date"
                    + " AS effective, CAST(NULL AS VARCHAR2(100)) AS order_reference, CAST(NULL AS"
                    + " NUMBER(19)) AS arrear_event, CAST(NULL AS NUMBER(19)) AS approval_id"
                    + MEMBER_DUE
                    + " UNION ALL SELECT 0, 'HRMS_TASK', t.hrms_change_task_id, e.employee_id,"
                    + " e.emp_num, e.first_name, e.last_name, e.band_id, b.band_code,"
                    + " e.location_id, k.field_code, e.current_basic, CASE WHEN k.field_code ="
                    + " 'BASIC_PAY' AND t.new_value IS NOT NULL THEN CAST(t.new_value AS"
                    + " NUMBER(14,2)) END, t.effective_date, t.hrms_request_id, t.arrear_event_id,"
                    + " t.approval_id FROM hrms_change_task t JOIN hrms_pay_effect_field k ON"
                    + " k.pay_effect_field_id = t.pay_effect_field_id JOIN employee e ON"
                    + " e.employee_id = t.employee_id LEFT JOIN pay_scale_band b ON b.band_id ="
                    + " e.band_id WHERE"
                    + TASK_OPEN
                    + " AND "
                    + IN_SCOPE
                    + " UNION ALL SELECT 2, 'APPLIED', c.increment_id, e.employee_id, e.emp_num,"
                    + " e.first_name, e.last_name, e.band_id, b.band_code, e.location_id,"
                    + " c.increment_type, c.old_basic, c.revised_basic, c.effective_date,"
                    + " CAST(NULL AS VARCHAR2(100)), (SELECT MAX(a.arrear_event_id) FROM"
                    + " arrear_event a WHERE a.source_entity = 'pay_increment_cycle' AND"
                    + " a.source_key = CAST(c.increment_id AS VARCHAR2(40))), c.approval_id FROM"
                    + " pay_increment_cycle c JOIN employee e ON e.employee_id = c.employee_id LEFT"
                    + " JOIN pay_scale_band b ON b.band_id = e.band_id LEFT JOIN approval_queue q"
                    + " ON q.approval_id = c.approval_id WHERE COALESCE(CAST(q.decided_at AS DATE),"
                    + " c.effective_date) BETWEEN :start AND :stop AND "
                    + IN_SCOPE
                    + " UNION ALL SELECT 3, 'FIXATION', f.fixation_event_id, e.employee_id,"
                    + " e.emp_num, e.first_name, e.last_name, e.band_id, b.band_code,"
                    + " e.location_id, f.event_basis, f.old_basic, f.revised_basic,"
                    + " f.fixation_date, f.order_reference, f.arrear_event_id, f.approval_id FROM"
                    + " pay_fixation_event f JOIN employee e ON e.employee_id = f.employee_id LEFT"
                    + " JOIN pay_scale_band b ON b.band_id = e.band_id LEFT JOIN approval_queue q"
                    + " ON q.approval_id = f.approval_id WHERE f.is_historical_only = 'N' AND"
                    + " COALESCE(CAST(q.decided_at AS DATE), f.fixation_date) BETWEEN :start AND"
                    + " :stop AND "
                    + IN_SCOPE
                    + ") w";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final IncrementRunService run;
    private final Scales scales;
    private final RuleEngine rules;
    private final DocumentStore documents;
    private final ObjectMapper json;

    public IncrementWorklistService(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            IncrementRunService run,
            Scales scales,
            RuleEngine rules,
            DocumentStore documents,
            ObjectMapper json) {
        this.named = named;
        this.policy = policy;
        this.run = run;
        this.scales = scales;
        this.rules = rules;
        this.documents = documents;
        this.json = json;
    }

    /** The drawn tiles and the figures of the Rules & guards panel. */
    public Counts counts(AccessContext context, YearMonth month) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = scope(context, month);
        long due = count("SELECT COUNT(*)" + MEMBER_DUE, p);
        long stagnation = count("SELECT COUNT(*)" + MEMBER_DUE + " AND" + AT_MAXIMUM, p);
        return new Counts(
                due,
                due - stagnation,
                stagnation,
                count(
                        "SELECT COUNT(*) FROM hrms_change_task t JOIN hrms_pay_effect_field k ON"
                                + " k.pay_effect_field_id = t.pay_effect_field_id JOIN employee e"
                                + " ON e.employee_id = t.employee_id WHERE"
                                + TASK_OPEN
                                + " AND "
                                + IN_SCOPE,
                        p),
                count(
                        "SELECT COUNT(*) FROM arrear_event a JOIN arrear_event_member m ON"
                                + " m.arrear_event_id = a.arrear_event_id JOIN employee e ON"
                                + " e.employee_id = m.employee_id WHERE a.source_module ="
                                + " 'INCREMENT' AND a.raised_at BETWEEN :start AND :stop AND "
                                + IN_SCOPE,
                        p),
                named.queryForObject(
                        "SELECT MAX(a.arrear_event_id) FROM arrear_event a JOIN"
                                + " arrear_event_member m ON m.arrear_event_id = a.arrear_event_id"
                                + " JOIN employee e ON e.employee_id = m.employee_id WHERE"
                                + " a.source_module = 'INCREMENT' AND "
                                + IN_SCOPE,
                        p,
                        Long.class),
                count(
                        "SELECT COUNT(*) FROM employee e WHERE e.record_status = 'ACTIVE' AND"
                                + " e.employment_status = 'IN_SERVICE' AND e.emp_lbr_flag = 'N' AND"
                                + AT_MAXIMUM
                                + " AND "
                                + IN_SCOPE,
                        p),
                count(
                        "SELECT COUNT(*)"
                                + MEMBER_DUE
                                + " AND EXISTS (SELECT 1 FROM pay_increment_cycle c WHERE"
                                + " c.employee_id = e.employee_id AND c.increment_due_date ="
                                + " d.next_due_date)",
                        p));
    }

    /** The Increment / Fixation Worklist of the month, one page. */
    public Paged<Row> worklist(AccessContext context, YearMonth month, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = scope(context, month);
        List<Row> cases = cases(p);
        long offset = (long) page.page() * page.size();
        List<Row> out = new ArrayList<>();
        for (int i = (int) Math.min(offset, cases.size());
                i < cases.size() && out.size() < page.size();
                i++) {
            out.add(cases.get(i));
        }
        Long total = named.queryForObject("SELECT COUNT(*)" + WORK, p, Long.class);
        if (out.size() < page.size()) {
            p.addValue("offset", Math.max(0, offset - cases.size()))
                    .addValue("size", page.size() - out.size());
            named.queryForList(
                            "SELECT w.*"
                                    + WORK
                                    + " ORDER BY w.rank_no, w.effective DESC, w.row_key OFFSET"
                                    + " :offset ROWS FETCH NEXT :size ROWS ONLY",
                            p)
                    .forEach(r -> out.add(row(r, month)));
        }
        return new Paged<>(out, page.page(), page.size(), RowCounts.of(total) + cases.size());
    }

    /** SRS-13-18, SRS-G-31: the orders stored with a case the session may see. */
    public List<DocumentStore.AttachmentView> orders(AccessContext context, long approvalId) {
        return documents.active(CASE, caseKey(context, approvalId, "VIEW"));
    }

    /** One stored order of a case, as a file of that case (never another record's). */
    public DocumentStore.Content orderFile(
            AccessContext context, String sourceIp, long approvalId, long attachmentId) {
        return documents.read(
                context.loginId(),
                sourceIp,
                CASE,
                caseKey(context, approvalId, "VIEW"),
                attachmentId);
    }

    /** The case's key once its member proves to be in the session's scope (404 otherwise). */
    private String caseKey(AccessContext context, long approvalId, String action) {
        policy.require(context, SCREEN, action);
        List<Map<String, Object>> items =
                named.queryForList(
                        "SELECT entity_key, payload FROM approval_queue WHERE approval_id = :id AND"
                                + " entity_name = :entity",
                        new MapSqlParameterSource("id", approvalId).addValue("entity", CASE));
        if (items.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        JsonNode payload = json.readTree(String.valueOf(items.getFirst().get("PAYLOAD")));
        MapSqlParameterSource p =
                OfficeScope.of(context, policy.isAtHeadquarters(context))
                        .addValue("employee", payload.path("employeeId").asLong());
        Long inScope =
                named.queryForObject(
                        "SELECT COUNT(*) FROM employee e WHERE e.employee_id = :employee AND "
                                + IN_SCOPE,
                        p,
                        Long.class);
        if (RowCounts.of(inScope) == 0) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return (String) items.getFirst().get("ENTITY_KEY");
    }

    /** Cases with the Checker: the member is read from the payload, then scoped in the database. */
    private List<Row> cases(MapSqlParameterSource scope) {
        List<Row> out = new ArrayList<>();
        named.queryForList(
                        "SELECT approval_id, entity_key, payload, effective_date FROM"
                                + " approval_queue WHERE entity_name = :entity AND status IN"
                                + " ('PENDING_APPROVAL', 'PENDING_ATTACHMENT') ORDER BY approval_id"
                                + " DESC",
                        new MapSqlParameterSource("entity", CASE))
                .forEach(
                        q -> {
                            JsonNode p = json.readTree(String.valueOf(q.get("PAYLOAD")));
                            long employee = p.path("employeeId").asLong();
                            List<Map<String, Object>> member =
                                    named.queryForList(
                                            "SELECT e.emp_num, e.first_name, e.last_name,"
                                                    + " b.band_code FROM employee e LEFT JOIN"
                                                    + " pay_scale_band b ON b.band_id = e.band_id"
                                                    + " WHERE e.employee_id = :employee AND "
                                                    + IN_SCOPE,
                                            new MapSqlParameterSource(scope.getValues())
                                                    .addValue("employee", employee));
                            if (!member.isEmpty()) {
                                out.add(caseRow(q, p, employee, member.getFirst()));
                            }
                        });
        return out;
    }

    private static Row caseRow(
            Map<String, Object> q, JsonNode p, long employee, Map<String, Object> m) {
        return new Row(
                "CASE",
                String.valueOf(q.get("ENTITY_KEY")),
                employee,
                (String) m.get("EMP_NUM"),
                name(m),
                (String) m.get("BAND_CODE"),
                p.path("caseType").asString(),
                decimal(p.path("old").asString("")),
                null,
                decimal(p.path("revised").asString("")),
                Dates.local(q.get("EFFECTIVE_DATE")),
                false,
                null,
                null,
                "CHECKER",
                List.of(),
                List.of(),
                p.path("order").asString(null),
                ((Number) q.get("APPROVAL_ID")).longValue());
    }

    private Row row(Map<String, Object> r, YearMonth month) {
        String kind = (String) r.get("KIND");
        long employee = ((Number) r.get("EMPLOYEE_ID")).longValue();
        LocalDate effective = Dates.local(r.get("EFFECTIVE"));
        BigDecimal present = (BigDecimal) r.get("PRESENT_BASIC");
        Optional<Scales.Scale> scale =
                r.get("BAND_ID") == null
                        ? Optional.empty()
                        : scales.of(((Number) r.get("BAND_ID")).longValue(), effective);
        boolean atMaximum =
                present != null
                        && scale.map(s -> s.max() != null && present.compareTo(s.max()) >= 0)
                                .orElse(false);
        Long arrear =
                r.get("ARREAR_EVENT") == null ? null : ((Number) r.get("ARREAR_EVENT")).longValue();
        BigDecimal percent =
                scale.map(s -> percent(s, effective, r.get("LOCATION_ID"))).orElse(null);
        if (!"DUE".equals(kind)) {
            return new Row(
                    kind,
                    String.valueOf(r.get("ROW_KEY")),
                    employee,
                    (String) r.get("EMP_NUM"),
                    name(r),
                    (String) r.get("BAND_CODE"),
                    (String) r.get("ROW_TYPE"),
                    present,
                    percent,
                    (BigDecimal) r.get("REVISED_BASIC"),
                    effective,
                    atMaximum,
                    arrear,
                    arrear == null ? null : arrearAmount(arrear),
                    "HRMS_TASK".equals(kind)
                            ? "HRMS_TASK"
                            : effective.isBefore(month.atDay(1)) ? "RETRO" : "APPROVED",
                    List.of(),
                    List.of(),
                    (String) r.get("ORDER_REFERENCE"),
                    r.get("APPROVAL_ID") == null
                            ? null
                            : ((Number) r.get("APPROVAL_ID")).longValue());
        }
        IncrementRunService.Proposal pr =
                run.propose(employee, present, effective, (Number) r.get("LOCATION_ID"));
        String period = IncrementRules.duePeriod(effective);
        return new Row(
                kind,
                String.valueOf(employee),
                employee,
                (String) r.get("EMP_NUM"),
                name(r),
                (String) r.get("BAND_CODE"),
                pr.type(),
                present,
                percent,
                pr.revised(),
                effective,
                atMaximum,
                null,
                null,
                dueStatus(
                        pr,
                        run.exists(employee, period, pr.type()),
                        effective,
                        month,
                        run,
                        employee),
                pr.flags().stream().map(Enum::name).toList(),
                pr.barredBy(),
                null,
                null);
    }

    private static String dueStatus(
            IncrementRunService.Proposal pr,
            boolean duplicate,
            LocalDate due,
            YearMonth month,
            IncrementRunService run,
            long employee) {
        if (duplicate) {
            return "DUPLICATE";
        }
        if (pr.flags().contains(Flag.BARRED)) {
            return "BARRED";
        }
        if (pr.flags().stream().anyMatch(IncrementRunService.BLOCKING::contains)) {
            return "FLAGGED";
        }
        if (run.waiting(employee, due)) {
            return "CHECKER";
        }
        return due.isBefore(month.atDay(1)) ? "RETRO" : "DUE";
    }

    /** The band's rule value as the rule engine holds it, never derived from a Basic difference. */
    private BigDecimal percent(Scales.Scale s, LocalDate day, Object location) {
        if (s.incrementRuleKey() == null) {
            return null;
        }
        try {
            return rules.resolve(
                            s.incrementRuleKey(),
                            day,
                            new RuleEngine.Context(
                                    "STAFF",
                                    location == null ? null : ((Number) location).longValue()))
                    .map(RuleEngine.Resolved::scalar)
                    .orElse(null);
        } catch (FciException notConfigured) {
            return null;
        }
    }

    private BigDecimal arrearAmount(long event) {
        return named.queryForObject(
                "SELECT SUM(arrear_amount) FROM arrear_detail WHERE arrear_event_id = :event",
                new MapSqlParameterSource("event", event),
                BigDecimal.class);
    }

    private MapSqlParameterSource scope(AccessContext context, YearMonth month) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context))
                .addValue("end", Date.valueOf(month.atEndOfMonth()))
                .addValue("start", Timestamp.valueOf(month.atDay(1).atStartOfDay()))
                .addValue("stop", Timestamp.valueOf(month.atEndOfMonth().atTime(23, 59, 59)));
    }

    private long count(String sql, MapSqlParameterSource p) {
        return RowCounts.of(named.queryForObject(sql, p, Long.class));
    }

    private static String name(Map<String, Object> r) {
        Object first = r.get("FIRST_NAME");
        Object last = r.get("LAST_NAME");
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
    }

    private static BigDecimal decimal(String v) {
        return v.isEmpty() ? null : new BigDecimal(v);
    }
}
