package in.gov.fci.payroll.penalty.orchestration;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The read side of I-06 as Wireframes v6.1 draws it (OD-121; SRS-14-1, SRS-14-2, SRS-14-10,
 * SRS-14-15, SRS-14-19; DDL penalty_order, over_and_above_payment, net_pay_floor_override): the
 * tiles and panel figures, the Order Register and the Min-Net Guard of one request. Every read is
 * limited to the session's unit, as the orders are ({@link PenaltyQueryService#scope}). API-12-3.
 *
 * <p>The drawn register holds orders, over-and-above payments and requests still with the Checker
 * in one list, so it is one query over the three. The tables have no Reason column on an order: the
 * text is the approval item's, as the Checker read it (WF-1310).
 */
@Service
@Loggable
public class PenaltyRegisterService {

    /** I-06 tiles and the panel figures. */
    public record Counts(
            long orders,
            BigDecimal outstanding,
            long payments,
            long paymentsAwaiting,
            BigDecimal paymentsAwaitingAmount,
            long minNetGuard,
            long incentiveRecovery,
            long carriedToFnf) {}

    /**
     * One row of the Order Register.
     *
     * @param kind ORDER, PAYMENT or REQUEST (still with the Checker)
     * @param id the order, payment or approval id
     * @param reference the Penalty ID or, for a payment, the sanction reference
     * @param source HRMS or ENT
     * @param nature the order's nature, OVER_ABOVE for a payment, null for a request
     * @param status the order, payment or approval status
     * @param belowFloor the request took the floor route (SRS-14-10)
     */
    public record RegisterRow(
            String kind,
            long id,
            String reference,
            String eis,
            String employee,
            String source,
            String nature,
            String reason,
            BigDecimal amount,
            Integer instalments,
            BigDecimal perPeriod,
            String approvedBy,
            String status,
            boolean belowFloor) {}

    /** SRS-14-10, SRS l.2995: the Min-Net Guard of a request waiting on the floor route. */
    public record MinNetGuard(
            long approvalId,
            String reference,
            String eis,
            String employee,
            BigDecimal gross,
            BigDecimal existingDeductions,
            BigDecimal proposedInstalment,
            BigDecimal resultingNet,
            BigDecimal floor) {}

    private static final String PENDING_STATUSES = "('PENDING_APPROVAL', 'PENDING_ATTACHMENT')";

    private static final String IN_SCOPE = "(:all = 'Y' OR e.location_id IN (:locations))";

    /** The request's member: the key is {@code <employee id>|<Penalty ID>}. */
    private static final String REQUEST_FROM =
            " FROM approval_queue q JOIN employee e ON e.employee_id = CASE WHEN q.entity_name ="
                    + " :entity AND INSTR(q.entity_key, '|') > 1 THEN CAST(SUBSTR(q.entity_key, 1,"
                    + " INSTR(q.entity_key, '|') - 1) AS NUMBER(19)) END JOIN"
                    + " approval_workflow_action w ON w.workflow_action_id = q.workflow_action_id"
                    + " WHERE q.entity_name = :entity AND q.status IN "
                    + PENDING_STATUSES
                    + " AND "
                    + IN_SCOPE;

    private static final String REGISTER =
            " FROM (SELECT 0 AS rank_no, 'REQUEST' AS kind, q.approval_id AS row_id, SUBSTR("
                    + "q.entity_key, INSTR(q.entity_key, '|') + 1) AS reference, e.emp_num,"
                    + " e.first_name, e.last_name, 'ENT' AS source_code, CAST(NULL AS"
                    + " VARCHAR2(40)) AS nature, q.decision_reason AS reason, q.impact_value AS"
                    + " amount, CAST(NULL AS NUMBER(4)) AS instalments, CAST(NULL AS NUMBER(14,2))"
                    + " AS per_period, CAST(NULL AS VARCHAR2(50)) AS approved_by, q.status AS"
                    + " row_status, CASE WHEN w.action_code = 'I06_FLOOR' THEN 1 ELSE 0 END AS"
                    + " below_floor"
                    + REQUEST_FROM
                    + " UNION ALL SELECT 1, 'ORDER', o.order_id, o.penalty_ref, e.emp_num,"
                    + " e.first_name, e.last_name, o.penalty_source, o.penalty_nature,"
                    + " a.decision_reason, o.total_recovery_amount, o.instalment_count, (SELECT"
                    + " MIN(s.scheduled_amount) FROM penalty_recovery_schedule s WHERE s.order_id ="
                    + " o.order_id), o.approved_by, o.order_status, 0 FROM penalty_order o JOIN"
                    + " employee e ON e.employee_id = o.employee_id LEFT JOIN approval_queue a ON"
                    + " a.approval_id = o.approval_id WHERE "
                    + IN_SCOPE
                    + " UNION ALL SELECT 2, 'PAYMENT', p.over_above_id, p.sanction_reference,"
                    + " e.emp_num, e.first_name, e.last_name, 'ENT', 'OVER_ABOVE',"
                    + " a.decision_reason, p.amount, 1, p.amount, a.decided_by, p.payment_status, 0"
                    + " FROM over_and_above_payment p JOIN employee e ON e.employee_id ="
                    + " p.employee_id LEFT JOIN approval_queue a ON a.approval_id = p.approval_id"
                    + " WHERE "
                    + IN_SCOPE
                    + ") r";

    private final NamedParameterJdbcTemplate named;
    private final PenaltyQueryService queries;
    private final OrgDirectory org;
    private final DocumentStore documents;
    private final ObjectMapper json;

    public PenaltyRegisterService(
            NamedParameterJdbcTemplate named,
            PenaltyQueryService queries,
            OrgDirectory org,
            DocumentStore documents,
            ObjectMapper json) {
        this.named = named;
        this.queries = queries;
        this.org = org;
        this.documents = documents;
        this.json = json;
    }

    /** The drawn tiles and panel figures of the session's unit. */
    @Transactional(readOnly = true)
    public Counts counts(AccessContext context) {
        MapSqlParameterSource p = scoped(context).addValue("entity", PenaltyOrderService.ENTITY);
        Map<String, Object> orders =
                named.queryForMap(
                        "SELECT COUNT(*) AS n, COALESCE(SUM((SELECT COALESCE(SUM("
                                + "s.scheduled_amount - s.recovered_amount), 0) FROM"
                                + " penalty_recovery_schedule s WHERE s.order_id = o.order_id AND"
                                + " s.schedule_status IN ('PENDING', 'PARTIAL', 'DEFERRED'))), 0)"
                                + " AS outstanding, COALESCE(SUM(CASE WHEN o.order_status ="
                                + " 'PAUSED_FNF' THEN 1 ELSE 0 END), 0) AS fnf FROM penalty_order o"
                                + " JOIN employee e ON e.employee_id = o.employee_id WHERE"
                                + " o.order_status IN ('ACTIVE', 'PARTLY_REVOKED', 'PAUSED_FNF')"
                                + " AND "
                                + IN_SCOPE,
                        p);
        Map<String, Object> payments =
                named.queryForMap(
                        "SELECT COUNT(*) AS n, COALESCE(SUM(CASE WHEN p.payment_status ="
                                + " 'SUBMITTED' THEN 1 ELSE 0 END), 0) AS waiting, COALESCE(SUM("
                                + "CASE WHEN p.payment_status = 'SUBMITTED' THEN p.amount ELSE 0"
                                + " END), 0) AS waiting_amount FROM over_and_above_payment p JOIN"
                                + " employee e ON e.employee_id = p.employee_id WHERE "
                                + IN_SCOPE,
                        p);
        return new Counts(
                count(orders.get("N")),
                (BigDecimal) orders.get("OUTSTANDING"),
                count(payments.get("N")),
                count(payments.get("WAITING")),
                (BigDecimal) payments.get("WAITING_AMOUNT"),
                count(
                        named.queryForObject(
                                "SELECT (SELECT COUNT(*)"
                                        + REQUEST_FROM
                                        + " AND w.action_code = 'I06_FLOOR') + (SELECT COUNT(*)"
                                        + " FROM net_pay_floor_override f JOIN employee e ON"
                                        + " e.employee_id = f.employee_id WHERE "
                                        + IN_SCOPE
                                        + ") FROM dual",
                                p,
                                Long.class)),
                // OPEN-QUESTION (PENDING I06-137): the pack draws this tile; no SRS rule of I-06
                // names code 137. It counts what the arrear engine holds under the code (SRS-21-4).
                count(
                        named.queryForObject(
                                "SELECT COUNT(*) FROM arrear_detail d JOIN pay_component c ON"
                                        + " c.component_id = d.component_id JOIN employee e ON"
                                        + " e.employee_id = d.employee_id WHERE c.legacy_pay_code ="
                                        + " 137 AND "
                                        + IN_SCOPE,
                                p,
                                Long.class)),
                count(orders.get("FNF")));
    }

    /** The Order Register of the session's unit, requests first, then newest first. */
    @Transactional(readOnly = true)
    public Paged<RegisterRow> register(AccessContext context, PageSpec spec) {
        MapSqlParameterSource p = scoped(context).addValue("entity", PenaltyOrderService.ENTITY);
        Long total = named.queryForObject("SELECT COUNT(*)" + REGISTER, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<RegisterRow> rows =
                named.query(
                        "SELECT r.*"
                                + REGISTER
                                + " ORDER BY r.rank_no, r.row_id DESC OFFSET :offset ROWS FETCH"
                                + " NEXT :size ROWS ONLY",
                        p,
                        (rs, i) -> {
                            int instalments = rs.getInt("instalments");
                            return new RegisterRow(
                                    rs.getString("kind"),
                                    rs.getLong("row_id"),
                                    rs.getString("reference"),
                                    rs.getString("emp_num"),
                                    PenaltyQueryService.name(
                                            rs.getString("first_name"), rs.getString("last_name")),
                                    rs.getString("source_code"),
                                    rs.getString("nature"),
                                    rs.getString("reason"),
                                    rs.getBigDecimal("amount"),
                                    instalments == 0 ? null : instalments,
                                    rs.getBigDecimal("per_period"),
                                    rs.getString("approved_by"),
                                    rs.getString("row_status"),
                                    rs.getInt("below_floor") == 1);
                        });
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /**
     * The Min-Net Guard of the given request, or of the latest one waiting on the floor route. The
     * figures are those the Maker's check saved in the approval item, and the gross and the
     * deductions of the member's latest pay; empty when no request is waiting.
     */
    @Transactional(readOnly = true)
    public Optional<MinNetGuard> guard(AccessContext context, Long approvalId) {
        MapSqlParameterSource p =
                scoped(context)
                        .addValue("entity", PenaltyOrderService.ENTITY)
                        .addValue("id", approvalId, Types.NUMERIC);
        List<Map<String, Object>> found =
                named.queryForList(
                        "SELECT q.approval_id, q.payload, e.employee_id, e.emp_num, e.first_name,"
                                + " e.last_name"
                                + REQUEST_FROM
                                + " AND w.action_code = 'I06_FLOOR' AND (:id IS NULL OR"
                                + " q.approval_id = :id) ORDER BY q.approval_id DESC FETCH FIRST 1"
                                + " ROWS ONLY",
                        p);
        if (found.isEmpty()) {
            if (approvalId != null) {
                throw new FciException(CommonErrorCode.NOT_FOUND);
            }
            return Optional.empty();
        }
        Map<String, Object> row = found.getFirst();
        JsonNode payload = json.readTree(String.valueOf(row.get("PAYLOAD")));
        long employee = ((Number) row.get("EMPLOYEE_ID")).longValue();
        List<Map<String, Object>> latest =
                named.queryForList(
                        "SELECT gross_earnings, total_deductions FROM payroll_result WHERE"
                                + " employee_id = :employee AND run_type = 'ACTUAL' AND"
                                + " result_status = 'CURRENT' ORDER BY payroll_yymm DESC FETCH"
                                + " FIRST 1 ROWS ONLY",
                        new MapSqlParameterSource("employee", employee));
        BigDecimal net = payload.path("latestNet").decimalValue();
        BigDecimal after = payload.path("netAfter").decimalValue();
        return Optional.of(
                new MinNetGuard(
                        ((Number) row.get("APPROVAL_ID")).longValue(),
                        payload.path("penaltyRef").asString(),
                        (String) row.get("EMP_NUM"),
                        PenaltyQueryService.name(
                                (String) row.get("FIRST_NAME"), (String) row.get("LAST_NAME")),
                        latest.isEmpty()
                                ? null
                                : (BigDecimal) latest.getFirst().get("GROSS_EARNINGS"),
                        latest.isEmpty()
                                ? null
                                : (BigDecimal) latest.getFirst().get("TOTAL_DEDUCTIONS"),
                        net.subtract(after),
                        after,
                        payload.hasNonNull("floor") ? payload.path("floor").decimalValue() : null));
    }

    /** SRS-14-12, SRS-G-31: the order copy of an order of the unit (404 outside it). */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orderCopies(AccessContext context, long orderId) {
        return documents.active(PenaltyOrderService.ENTITY, copyKey(context, orderId));
    }

    /** One stored order copy, as a file of the order (never another record's). */
    @Transactional
    public DocumentStore.Content orderCopy(
            AccessContext context, String sourceIp, long orderId, long attachmentId) {
        return documents.read(
                context.loginId(),
                sourceIp,
                PenaltyOrderService.ENTITY,
                copyKey(context, orderId),
                attachmentId);
    }

    private String copyKey(AccessContext context, long orderId) {
        PenaltyQueryService.OrderRow order = queries.detail(context, orderId).order();
        Long employee =
                named.queryForObject(
                        "SELECT employee_id FROM penalty_order WHERE order_id = :id",
                        new MapSqlParameterSource("id", orderId),
                        Long.class);
        return employee + "|" + order.penaltyRef();
    }

    private MapSqlParameterSource scoped(AccessContext context) {
        Long unit = queries.scope(context);
        Set<Long> locations = unit == null ? Set.of(-1L) : org.subtree(unit);
        return new MapSqlParameterSource()
                .addValue("all", unit == null ? "Y" : "N", Types.VARCHAR)
                .addValue("locations", locations);
    }

    private static long count(Object n) {
        return n == null ? 0 : ((Number) n).longValue();
    }
}
