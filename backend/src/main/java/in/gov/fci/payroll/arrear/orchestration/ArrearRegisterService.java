package in.gov.fci.payroll.arrear.orchestration;

import in.gov.fci.payroll.arrear.business.ArrearErrorCode;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
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
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-08 Arrears &amp; Retrospective Processing (plan 13 phase 13a; SRS-8-8, SRS-8-14, SRS-8-19,
 * SRS-8-21; SRS I-08 field table). The arrear events of the session's offices (all at
 * Headquarters), each with its totals; an event's members with their differentials; the Maker
 * submits a COMPUTED event and the Payroll Checker of the unit approves it (Approve &amp; Merge,
 * route {@value #ACTION}): the event and its details become APPROVED and post into the next open
 * cycle - the current month, or the next when the current one is locked - never a closed period. A
 * rejected or returned event is REJECTED and its details REVERSED (never deleted).
 */
@Service
@Loggable
public class ArrearRegisterService {

    public static final String SCREEN = "I-08";
    public static final String ACTION = "I08_SUBMIT";
    static final String ENTITY = "arrear_event";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** One event of the register. */
    public record EventRow(
            long id,
            String eventCode,
            String eventName,
            String reference,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String office,
            String status,
            String postedToYymm,
            long members,
            BigDecimal arrears,
            BigDecimal recoveries,
            String raisedBy,
            LocalDateTime raisedAt,
            String source,
            boolean singleMember,
            boolean stated,
            boolean beyondWindow,
            String hqException) {}

    /** One member's differential of a month and component. */
    public record DetailRow(
            String eis,
            String name,
            String yymm,
            int payCode,
            String component,
            BigDecimal original,
            BigDecimal recomputed,
            BigDecimal arrear,
            String status) {}

    /** The register's filters; each may be null. */
    public record Filter(String status, String eventCode) {}

    private static final String FROM =
            " FROM arrear_event e JOIN arrear_event_type t ON t.event_type_id = e.event_type_id"
                    + " LEFT JOIN org_location l ON l.location_id = e.scope_location_id";

    private static final String WHERE =
            " WHERE (:all = 'Y' OR e.scope_location_id IN (:locations)) AND (:status IS NULL OR"
                    + " e.event_status = :status) AND (:code IS NULL OR t.event_code = :code)";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final AuditService audit;
    private final Clock clock;

    public ArrearRegisterService(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            MakerChecker approvals,
            OrgDirectory org,
            PayrollRecords payroll,
            AuditService audit,
            Clock clock) {
        this.named = named;
        this.policy = policy;
        this.approvals = approvals;
        this.org = org;
        this.payroll = payroll;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Paged<EventRow> events(AccessContext context, Filter filter, PageSpec page) {
        MapSqlParameterSource p = scoped(context, "VIEW", filter);
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, p, Long.class);
        p.addValue("offset", (long) page.page() * page.size()).addValue("size", page.size());
        List<EventRow> rows =
                named.query(
                        "SELECT e.arrear_event_id, t.event_code, t.event_name, e.event_reference,"
                            + " e.effective_from, e.effective_to, l.location_code, l.location_name,"
                            + " e.event_status, e.posted_to_yymm, e.raised_by, e.raised_at, (SELECT"
                            + " COUNT(DISTINCT d.employee_id) FROM arrear_detail d WHERE"
                            + " d.arrear_event_id = e.arrear_event_id) AS members, (SELECT"
                            + " COALESCE(SUM(CASE WHEN d.arrear_amount > 0 THEN d.arrear_amount"
                            + " ELSE 0 END), 0) FROM arrear_detail d WHERE d.arrear_event_id ="
                            + " e.arrear_event_id) AS arrears, (SELECT COALESCE(SUM(CASE WHEN"
                            + " d.arrear_amount < 0 THEN -d.arrear_amount ELSE 0 END), 0) FROM"
                            + " arrear_detail d WHERE d.arrear_event_id = e.arrear_event_id) AS"
                            + " recoveries, e.source_module, CASE WHEN EXISTS (SELECT 1 FROM"
                            + " arrear_event_member x WHERE x.arrear_event_id = e.arrear_event_id)"
                            + " THEN 'Y' ELSE 'N' END AS single_member, CASE WHEN EXISTS (SELECT 1"
                            + " FROM arrear_detail s WHERE s.arrear_event_id = e.arrear_event_id"
                            + " AND s.arrear_status = 'CALCULATED' AND s.recomputed_amount IS NULL)"
                            + " THEN 'Y' ELSE 'N' END AS stated, e.beyond_window_flag, CASE WHEN"
                            + " e.hq_exception_approval_id IS NOT NULL THEN 'APPROVED' WHEN EXISTS"
                            + " (SELECT 1 FROM approval_queue q WHERE q.entity_name ="
                            + " 'arrear_event_hq_exception' AND q.entity_key ="
                            + " TO_CHAR(e.arrear_event_id) AND q.status = 'PENDING_APPROVAL') THEN"
                            + " 'PENDING' END AS hq_exception"
                                + FROM
                                + WHERE
                                + " ORDER BY e.arrear_event_id DESC OFFSET :offset ROWS FETCH NEXT"
                                + " :size ROWS ONLY",
                        p,
                        (rs, i) -> {
                            String code = rs.getString("location_code");
                            java.sql.Date to = rs.getDate("effective_to");
                            return new EventRow(
                                    rs.getLong("arrear_event_id"),
                                    rs.getString("event_code"),
                                    rs.getString("event_name"),
                                    rs.getString("event_reference"),
                                    rs.getDate("effective_from").toLocalDate(),
                                    to == null ? null : to.toLocalDate(),
                                    code == null
                                            ? null
                                            : code + " - " + rs.getString("location_name"),
                                    rs.getString("event_status"),
                                    rs.getString("posted_to_yymm"),
                                    rs.getLong("members"),
                                    rs.getBigDecimal("arrears"),
                                    rs.getBigDecimal("recoveries"),
                                    rs.getString("raised_by"),
                                    rs.getTimestamp("raised_at").toLocalDateTime(),
                                    rs.getString("source_module"),
                                    "Y".equals(rs.getString("single_member")),
                                    "Y".equals(rs.getString("stated")),
                                    "Y".equals(rs.getString("beyond_window_flag")),
                                    rs.getString("hq_exception"));
                        });
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }

    /** The event's differentials, member by member and month by month, one page. */
    @Transactional(readOnly = true)
    public Paged<DetailRow> details(AccessContext context, long eventId, PageSpec page) {
        requireEvent(context, eventId, "VIEW");
        MapSqlParameterSource p = new MapSqlParameterSource("id", eventId);
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*) FROM arrear_detail WHERE arrear_event_id = :id",
                        p,
                        Long.class);
        p.addValue("offset", (long) page.page() * page.size()).addValue("size", page.size());
        List<DetailRow> rows =
                named.query(
                        "SELECT m.emp_num, m.first_name, m.last_name, d.differential_yymm,"
                            + " c.legacy_pay_code, c.component_name, d.original_amount,"
                            + " d.recomputed_amount, d.arrear_amount, d.arrear_status FROM"
                            + " arrear_detail d JOIN employee m ON m.employee_id = d.employee_id"
                            + " JOIN pay_component c ON c.component_id = d.component_id WHERE"
                            + " d.arrear_event_id = :id ORDER BY m.emp_num, d.differential_yymm,"
                            + " c.legacy_pay_code, d.arrear_detail_id OFFSET :offset ROWS FETCH"
                            + " NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new DetailRow(
                                        rs.getString(1),
                                        ((rs.getString(2) == null ? "" : rs.getString(2))
                                                        + " "
                                                        + (rs.getString(3) == null
                                                                ? ""
                                                                : rs.getString(3)))
                                                .strip(),
                                        rs.getString(4),
                                        rs.getInt(5),
                                        rs.getString(6),
                                        rs.getBigDecimal(7),
                                        rs.getBigDecimal(8),
                                        rs.getBigDecimal(9),
                                        rs.getString(10)));
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }

    /**
     * SRS-8-14: the Maker submits a computed event for the Payroll Checker's Approve &amp; Merge.
     */
    @Transactional
    public SubmittedApproval submit(AccessContext context, long eventId, String remark) {
        Map<String, Object> e = requireEvent(context, eventId, "SUBMIT");
        if (!"COMPUTED".equals(e.get("EVENT_STATUS"))) {
            throw new FciException(
                    ArrearErrorCode.STATE,
                    new Object[] {e.get("EVENT_STATUS"), "COMPUTED"},
                    List.of(),
                    null);
        }
        if (remark == null || remark.isBlank()) {
            throw new FciException(ArrearErrorCode.REMARK);
        }
        Long location =
                e.get("SCOPE_LOCATION_ID") == null
                        ? null
                        : ((Number) e.get("SCOPE_LOCATION_ID")).longValue();
        BigDecimal net =
                named.queryForObject(
                        "SELECT COALESCE(SUM(arrear_amount), 0) FROM arrear_detail WHERE"
                                + " arrear_event_id = :id",
                        new MapSqlParameterSource("id", eventId),
                        BigDecimal.class);
        return approvals.submit(
                context,
                new Submission(
                                ACTION,
                                SCREEN,
                                "SUBMIT",
                                ENTITY,
                                String.valueOf(eventId),
                                ActionType.UPDATE,
                                location,
                                null,
                                new java.sql.Date(
                                                ((java.util.Date) e.get("EFFECTIVE_FROM"))
                                                        .getTime())
                                        .toLocalDate(),
                                remark.strip())
                        .withImpact(net),
                (OtpProof) null);
    }

    /**
     * SRS-8-8 / 8-14: the Checker's decision. Approved: the event and its details APPROVED, posted
     * to the next open cycle of its unit. Rejected or returned: REJECTED, details REVERSED.
     */
    @EventListener
    public void on(ApprovalDecided d) {
        if (!d.concerns(ENTITY)) {
            return;
        }
        long eventId = Long.parseLong(d.entityKey());
        MapSqlParameterSource p =
                new MapSqlParameterSource("id", eventId).addValue("approval", d.approvalId());
        if (d.approved()) {
            Map<String, Object> e =
                    named.queryForMap(
                            "SELECT scope_location_id FROM arrear_event WHERE arrear_event_id ="
                                    + " :id",
                            p);
            Object location = e.get("SCOPE_LOCATION_ID");
            String posted = nextOpen(location == null ? null : ((Number) location).longValue());
            named.update(
                    "UPDATE arrear_event SET event_status = 'APPROVED', approval_id = :approval,"
                            + " posted_to_yymm = :posted WHERE arrear_event_id = :id",
                    p.addValue("posted", posted));
            named.update(
                    "UPDATE arrear_detail SET arrear_status = 'APPROVED' WHERE arrear_event_id ="
                            + " :id AND arrear_status = 'CALCULATED'",
                    p);
        } else {
            named.update(
                    "UPDATE arrear_event SET event_status = 'REJECTED', approval_id = :approval"
                            + " WHERE arrear_event_id = :id",
                    p);
            named.update(
                    "UPDATE arrear_detail SET arrear_status = 'REVERSED' WHERE arrear_event_id ="
                            + " :id AND arrear_status = 'CALCULATED'",
                    p);
        }
        audit.record(
                d.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                d.entityKey(),
                                "event_status",
                                "COMPUTED",
                                d.approved() ? "APPROVED" : "REJECTED",
                                d.approved() ? "Approve & Merge" : "Not merged: " + d.reason())
                        .approval(d.approvalId())
                        .module(SCREEN));
    }

    private static final String REGISTER =
            " FROM arrear_detail d JOIN arrear_event e ON e.arrear_event_id = d.arrear_event_id"
                + " JOIN arrear_event_type t ON t.event_type_id = e.event_type_id JOIN employee m"
                + " ON m.employee_id = d.employee_id JOIN pay_component c ON c.component_id ="
                + " d.component_id LEFT JOIN org_location l ON l.location_id = e.scope_location_id"
                    + WHERE;

    /** SRS-G-13: the rows the Arrears Register will carry (checked against the export limit). */
    @Transactional(readOnly = true)
    public long registerSize(AccessContext context, Filter filter) {
        Long n =
                named.queryForObject(
                        "SELECT COUNT(*)" + REGISTER,
                        scoped(context, "EXPORT", filter),
                        Long.class);
        return RowCounts.of(n);
    }

    /**
     * SRS-8-21: the Arrears Register - event, arrear period, the month generated and the month
     * paid, payroll type and component, member by member - in one read-only snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void register(
            AccessContext context,
            Filter filter,
            in.gov.fci.payroll.platform.export.ExportSink sink)
            throws java.io.IOException {
        MapSqlParameterSource p = scoped(context, "EXPORT", filter);
        Long expected = named.queryForObject("SELECT COUNT(*)" + REGISTER, p, Long.class);
        sink.header(
                "event",
                "event_type",
                "reference",
                "office",
                "arrear_period",
                "generated_in",
                "paid_in",
                "payroll_type",
                "eis",
                "component",
                "arrear",
                "status");
        named.query(
                "SELECT e.arrear_event_id, t.event_code, e.event_reference, l.location_code,"
                        + " d.differential_yymm, e.raised_at, CASE WHEN d.arrear_status = 'POSTED'"
                        + " THEN e.posted_to_yymm END AS paid_in, e.scope_payroll_type, m.emp_num,"
                        + " c.legacy_pay_code, c.component_name, d.arrear_amount, d.arrear_status"
                        + REGISTER
                        + " ORDER BY e.arrear_event_id, m.emp_num, d.differential_yymm",
                p,
                rs -> {
                    try {
                        sink.row(
                                rs.getLong(1),
                                rs.getString(2),
                                nz(rs.getString(3)),
                                nz(rs.getString(4)),
                                rs.getString(5),
                                rs.getTimestamp(6).toLocalDateTime().toLocalDate().toString(),
                                nz(rs.getString(7)),
                                nz(rs.getString(8)),
                                rs.getString(9),
                                rs.getInt(10) + " - " + rs.getString(11),
                                rs.getBigDecimal(12),
                                rs.getString(13));
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                });
        in.gov.fci.payroll.platform.export.ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    // ---------------------------------------------------------------- helpers

    /** The unit's open month: the current one, or the next when the current one is locked. */
    String nextOpen(Long locationId) {
        YearMonth current = YearMonth.now(clock.withZone(IST));
        String yymm = String.format("%04d%02d", current.getYear(), current.getMonthValue());
        Long unit =
                locationId == null
                        ? null
                        : org.cycleUnitOf(locationId).map(OrgLocation::getId).orElse(null);
        boolean locked =
                unit != null
                        && payroll.cycle(unit, yymm)
                                .filter(PayrollRecords.Cycle::isLocked)
                                .isPresent();
        YearMonth open = locked ? current.plusMonths(1) : current;
        return String.format("%04d%02d", open.getYear(), open.getMonthValue());
    }

    Map<String, Object> requireEvent(AccessContext context, long eventId, String action) {
        MapSqlParameterSource p =
                scoped(context, action, new Filter(null, null)).addValue("id", eventId);
        List<Map<String, Object>> rows =
                named.queryForList(
                        "SELECT e.arrear_event_id, e.event_status, e.scope_location_id,"
                                + " e.effective_from, e.beyond_window_flag,"
                                + " e.hq_exception_approval_id"
                                + FROM
                                + WHERE
                                + " AND e.arrear_event_id = :id",
                        p);
        if (rows.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return rows.getFirst();
    }

    private MapSqlParameterSource scoped(AccessContext context, String action, Filter filter) {
        policy.require(context, SCREEN, action);
        return OfficeScope.of(context, policy.isAtHeadquarters(context))
                .addValue("status", blank(filter.status()), Types.VARCHAR)
                .addValue("code", blank(filter.eventCode()), Types.VARCHAR);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
