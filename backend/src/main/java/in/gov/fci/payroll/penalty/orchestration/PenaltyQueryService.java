package in.gov.fci.payroll.penalty.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-06 register (plan 12 phases 12b / 12g; SRS-14-1, SRS-14-11, SRS-14-15, PN-17): the orders of
 * the user's cycle unit (every unit at Headquarters) with what is recovered and outstanding, one
 * order with its schedule month by month, and the requests waiting for the Checker or returned to
 * the Maker with the reason, listed apart from the orders.
 */
@Service
@Loggable
public class PenaltyQueryService {

    /** One order as I-06 lists it. */
    public record OrderRow(
            long id,
            String penaltyRef,
            String eis,
            String employee,
            String nature,
            String orderReference,
            String source,
            BigDecimal amount,
            Integer instalments,
            LocalDate periodFrom,
            LocalDate periodTo,
            BigDecimal recovered,
            BigDecimal outstanding,
            String status,
            boolean backdated,
            // SRS-14-19: what the revokes waived of the schedule.
            BigDecimal waived) {}

    /** One month of the schedule. */
    public record Line(
            String payrollYymm,
            BigDecimal scheduled,
            BigDecimal recovered,
            String status,
            String reason) {}

    /** A Penalty Revoke of the order (SRS-14-16). */
    public record RevokeRow(
            long id,
            String type,
            BigDecimal amount,
            LocalDate periodFrom,
            LocalDate periodTo,
            String orderReference,
            String reason,
            String status) {}

    public record Detail(OrderRow order, List<Line> schedule, List<RevokeRow> revokes) {}

    /**
     * A request waiting for a decision or returned (PN-17), with the Penalty ID assigned when it
     * was recorded (SRS-14-15, US-GAP-229; null for a request recorded before V076_1).
     */
    public record Request(
            long approvalId,
            String penaltyRef,
            String eis,
            String employee,
            BigDecimal amount,
            String status,
            String submittedBy,
            Instant submittedAt,
            String returnReason,
            String summary) {}

    private static final String ORDER_SELECT =
            "SELECT o.order_id, o.penalty_ref, e.emp_num, e.first_name, e.last_name,"
                    + " o.penalty_nature, o.order_reference, o.penalty_source,"
                    + " o.total_recovery_amount, o.instalment_count, o.period_from, o.period_to,"
                    + " o.order_status, o.is_backdated, (SELECT COALESCE(SUM(s.recovered_amount),"
                    + " 0) FROM penalty_recovery_schedule s WHERE s.order_id = o.order_id) AS"
                    + " recovered, (SELECT COALESCE(SUM(s.scheduled_amount -"
                    + " s.recovered_amount), 0) FROM penalty_recovery_schedule s WHERE s.order_id"
                    + " = o.order_id AND s.schedule_status IN ('PENDING', 'PARTIAL', 'DEFERRED'))"
                    + " AS outstanding, (SELECT COALESCE(SUM(s.scheduled_amount), 0) FROM"
                    + " penalty_recovery_schedule s WHERE s.order_id = o.order_id AND"
                    + " s.schedule_status = 'WAIVED') AS waived";

    private static final String ORDER_FROM =
            " FROM penalty_order o JOIN employee e ON e.employee_id = o.employee_id";

    private static final String ORDER_WHERE =
            " WHERE (:all = 'Y' OR e.location_id IN (:locations)) AND (:eis IS NULL OR e.emp_num ="
                    + " :eis) AND (:status IS NULL OR o.order_status = :status)";

    private final NamedParameterJdbcTemplate named;
    private final OrgDirectory org;
    private final Authorization policy;

    public PenaltyQueryService(
            NamedParameterJdbcTemplate named, OrgDirectory org, Authorization policy) {
        this.named = named;
        this.org = org;
        this.policy = policy;
    }

    /** The orders of the user's unit, newest first, a page at a time. */
    @Transactional(readOnly = true)
    public Paged<OrderRow> orders(AccessContext context, String eis, String status, PageSpec spec) {
        MapSqlParameterSource params =
                scoped(scope(context))
                        .addValue("eis", blank(eis), Types.VARCHAR)
                        .addValue("status", blank(status), Types.VARCHAR);
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*)" + ORDER_FROM + ORDER_WHERE, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<OrderRow> rows =
                named.query(
                        ORDER_SELECT
                                + ORDER_FROM
                                + ORDER_WHERE
                                + " ORDER BY o.order_id DESC OFFSET :offset ROWS FETCH NEXT :size"
                                + " ROWS ONLY",
                        params,
                        (rs, i) -> order(rs));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** One order of the user's unit with its schedule (404 outside it). */
    @Transactional(readOnly = true)
    public Detail detail(AccessContext context, long orderId) {
        MapSqlParameterSource params =
                scoped(scope(context))
                        .addValue("eis", null, Types.VARCHAR)
                        .addValue("status", null, Types.VARCHAR)
                        .addValue("id", orderId);
        OrderRow order =
                named
                        .query(
                                ORDER_SELECT + ORDER_FROM + ORDER_WHERE + " AND o.order_id = :id",
                                params,
                                (rs, i) -> order(rs))
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        List<Line> lines =
                named.query(
                        "SELECT payroll_yymm, scheduled_amount, recovered_amount, schedule_status,"
                                + " deferral_reason FROM penalty_recovery_schedule WHERE order_id"
                                + " = :id ORDER BY payroll_yymm",
                        new MapSqlParameterSource("id", orderId),
                        (rs, i) ->
                                new Line(
                                        rs.getString(1),
                                        rs.getBigDecimal(2),
                                        rs.getBigDecimal(3),
                                        rs.getString(4),
                                        rs.getString(5)));
        List<RevokeRow> revokes =
                named.query(
                        "SELECT penalty_revoke_id, revoke_type, waived_amount, waived_period_from,"
                            + " waived_period_to, revoke_order_ref, revoke_reason, revoke_status"
                            + " FROM penalty_revoke WHERE order_id = :id ORDER BY penalty_revoke_id"
                            + " DESC",
                        new MapSqlParameterSource("id", orderId),
                        (rs, i) -> {
                            java.sql.Date from = rs.getDate(4);
                            java.sql.Date to = rs.getDate(5);
                            return new RevokeRow(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    rs.getBigDecimal(3),
                                    from == null ? null : from.toLocalDate(),
                                    to == null ? null : to.toLocalDate(),
                                    rs.getString(6),
                                    rs.getString(7),
                                    rs.getString(8));
                        });
        return new Detail(order, lines, revokes);
    }

    /** PN-17: the requests waiting for a decision or returned to the Maker, newest first. */
    @Transactional(readOnly = true)
    public Paged<Request> requests(AccessContext context, PageSpec spec) {
        MapSqlParameterSource params =
                scoped(scope(context)).addValue("entity", PenaltyOrderService.ENTITY);
        String from =
                // Oracle review F10: an equi-join on the member id the key starts with
                // ("<employee_id>|..."), probed by the employee primary key, instead of a LIKE
                // that compared every request with every employee. The CASE converts only this
                // entity's keys that carry the separator, so no other key can fail the number.
                " FROM approval_queue q JOIN employee e ON e.employee_id = CASE WHEN"
                        + " q.entity_name = :entity AND INSTR(q.entity_key, '|') > 1 THEN"
                        + " CAST(SUBSTR(q.entity_key, 1, INSTR(q.entity_key, '|') - 1) AS"
                        + " NUMBER(19)) END WHERE q.entity_name = :entity AND q.status IN"
                        + " ('PENDING_APPROVAL', 'PENDING_ATTACHMENT', 'RETURNED') AND (:all = 'Y'"
                        + " OR e.location_id IN (:locations))";
        Long total = named.queryForObject("SELECT COUNT(*)" + from, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Request> rows =
                named.query(
                        "SELECT q.approval_id, CASE WHEN SUBSTR(q.entity_key,"
                                + " INSTR(q.entity_key, '|') + 1, 4) = 'PEN-' THEN"
                                + " SUBSTR(q.entity_key, INSTR(q.entity_key, '|') + 1) END AS"
                                + " penalty_ref, e.emp_num, e.first_name, e.last_name,"
                                + " q.impact_value, q.status, q.submitted_by, q.submitted_at,"
                                + " q.return_reason, q.decision_reason"
                                + from
                                + " ORDER BY q.approval_id DESC OFFSET :offset ROWS FETCH NEXT"
                                + " :size ROWS ONLY",
                        params,
                        (rs, i) -> {
                            Timestamp at = rs.getTimestamp("submitted_at");
                            return new Request(
                                    rs.getLong("approval_id"),
                                    rs.getString("penalty_ref"),
                                    rs.getString("emp_num"),
                                    name(rs.getString("first_name"), rs.getString("last_name")),
                                    rs.getBigDecimal("impact_value"),
                                    rs.getString("status"),
                                    rs.getString("submitted_by"),
                                    at == null ? null : at.toInstant(),
                                    rs.getString("return_reason"),
                                    rs.getString("decision_reason"));
                        });
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    private static final String REPORT_FROM =
            " FROM penalty_order o JOIN employee e ON e.employee_id = o.employee_id JOIN"
                    + " penalty_recovery_schedule s ON s.order_id = o.order_id WHERE s.payroll_yymm"
                    + " BETWEEN :from AND :to AND (:all = 'Y' OR e.location_id IN (:locations))";

    private static final java.util.regex.Pattern YYMM =
            java.util.regex.Pattern.compile("^\\d{4}(0[1-9]|1[0-2])$");

    /** The size of the report for the months (checked against the export limit first). */
    @Transactional(readOnly = true)
    public long reportSize(AccessContext context, String fromYymm, String toYymm) {
        Long n =
                named.queryForObject(
                        "SELECT COUNT(DISTINCT o.order_id)" + REPORT_FROM,
                        reportParams(context, fromYymm, toYymm),
                        Long.class);
        return RowCounts.of(n);
    }

    /**
     * SRS-14-19: the Penalty Report for the months: by Penalty ID and member, the type, amount and
     * period of each order, and of its months in the range what was imposed (scheduled), recovered,
     * waived and is outstanding.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void report(AccessContext context, String fromYymm, String toYymm, ExportSink sink)
            throws java.io.IOException {
        MapSqlParameterSource params = reportParams(context, fromYymm, toYymm);
        Long expected =
                named.queryForObject(
                        "SELECT COUNT(DISTINCT o.order_id)" + REPORT_FROM, params, Long.class);
        sink.header(
                "penalty_id",
                "eis",
                "employee",
                "type",
                "order_reference",
                "amount",
                "period_from",
                "period_to",
                "status",
                "imposed",
                "recovered",
                "waived",
                "outstanding");
        try {
            named.query(
                    "SELECT o.penalty_ref, e.emp_num, e.first_name, e.last_name, o.penalty_nature,"
                        + " o.order_reference, o.total_recovery_amount, o.period_from, o.period_to,"
                        + " o.order_status, SUM(s.scheduled_amount), SUM(s.recovered_amount),"
                        + " SUM(CASE WHEN s.schedule_status = 'WAIVED' THEN s.scheduled_amount ELSE"
                        + " 0 END), SUM(CASE WHEN s.schedule_status IN ('PENDING', 'PARTIAL',"
                        + " 'DEFERRED') THEN s.scheduled_amount - s.recovered_amount ELSE 0 END)"
                            + REPORT_FROM
                            + " GROUP BY o.order_id, o.penalty_ref, e.emp_num, e.first_name,"
                            + " e.last_name, o.penalty_nature, o.order_reference,"
                            + " o.total_recovery_amount, o.period_from, o.period_to, o.order_status"
                            + " ORDER BY o.penalty_ref",
                    params,
                    rs -> {
                        try {
                            java.sql.Date from = rs.getDate(8);
                            java.sql.Date to = rs.getDate(9);
                            sink.row(
                                    rs.getString(1),
                                    rs.getString(2),
                                    name(rs.getString(3), rs.getString(4)),
                                    rs.getString(5),
                                    rs.getString(6),
                                    rs.getBigDecimal(7),
                                    from == null ? "" : from.toLocalDate().toString(),
                                    to == null ? "" : to.toLocalDate().toString(),
                                    rs.getString(10),
                                    rs.getBigDecimal(11),
                                    rs.getBigDecimal(12),
                                    rs.getBigDecimal(13),
                                    rs.getBigDecimal(14));
                        } catch (java.io.IOException failed) {
                            throw new java.io.UncheckedIOException(failed);
                        }
                    });
        } catch (java.io.UncheckedIOException failed) {
            throw failed.getCause();
        }
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    private MapSqlParameterSource reportParams(
            AccessContext context, String fromYymm, String toYymm) {
        if (!YYMM.matcher(fromYymm == null ? "" : fromYymm).matches()
                || !YYMM.matcher(toYymm == null ? "" : toYymm).matches()
                || fromYymm.compareTo(toYymm) > 0) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        return scoped(scope(context)).addValue("from", fromYymm).addValue("to", toYymm);
    }

    /** The cycle unit the user sees (null at Headquarters: every unit), with I-06 VIEW there. */
    Long scope(AccessContext context) {
        if (context.locationId() == null) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        if (policy.isAtHeadquarters(context)) {
            policy.requireAt(context, PenaltyOrderService.SCREEN, "VIEW", context.locationId());
            return null;
        }
        OrgLocation unit =
                org.cycleUnitOf(context.locationId())
                        .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT));
        policy.requireAt(context, PenaltyOrderService.SCREEN, "VIEW", unit.getId());
        return unit.getId();
    }

    private MapSqlParameterSource scoped(Long unit) {
        Set<Long> locations = unit == null ? Set.of(-1L) : org.subtree(unit);
        return new MapSqlParameterSource()
                .addValue("all", unit == null ? "Y" : "N", Types.VARCHAR)
                .addValue("locations", locations);
    }

    private static OrderRow order(ResultSet rs) throws SQLException {
        int instalments = rs.getInt("instalment_count");
        boolean noInstalments = rs.wasNull();
        java.sql.Date to = rs.getDate("period_to");
        java.sql.Date from = rs.getDate("period_from");
        return new OrderRow(
                rs.getLong("order_id"),
                rs.getString("penalty_ref"),
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                rs.getString("penalty_nature"),
                rs.getString("order_reference"),
                rs.getString("penalty_source"),
                rs.getBigDecimal("total_recovery_amount"),
                noInstalments ? null : instalments,
                from == null ? null : from.toLocalDate(),
                to == null ? null : to.toLocalDate(),
                rs.getBigDecimal("recovered"),
                rs.getBigDecimal("outstanding"),
                rs.getString("order_status"),
                "Y".equals(rs.getString("is_backdated")),
                rs.getBigDecimal("waived"));
    }

    static String name(String first, String last) {
        return last == null || last.isBlank() ? first : first + " " + last;
    }

    static String blank(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
