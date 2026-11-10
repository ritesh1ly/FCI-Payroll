package in.gov.fci.payroll.arrear.orchestration;

import in.gov.fci.payroll.arrear.business.ArrearErrorCode;
import in.gov.fci.payroll.arrear.business.ArrearRules;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-08 Arrears &amp; Retrospective Events as Wireframes v6.1 draws it (OD-121; SRS-8-8, SRS-8-14,
 * SRS-8-21, FRS §6.9.3, §6.19): the four tiles over the offices in scope (open events and those
 * awaiting approval; arrears payable and recoveries with the members they reach; the statutory
 * effect, computed in the next cycle run, so reported from there), the period-wise differential of
 * one event (every month of the span with its original, revised and component differences), the
 * retro controls (the Headquarters settings of A-07 and the rules of §6.9.3), the event types the
 * Maker may raise (arrear_event_type, a database list, OD-123) and the Maker's own retro event:
 * raised RAISED for the office and span (SRS-8-8; held beyond the back-date limit until a
 * Headquarters exception, SRS-8-6), computed by its engine and merged only after Approve &amp;
 * Merge. Figures come from arrear_event and arrear_detail over the whole scope, never from one
 * page. The drawn "Order copy - attach &amp; store" card (FRS §6.4.3, A-41) keeps the event's
 * orders in document_attachment under the event (entity arrear_event), never in a column of its
 * own.
 */
@Service
@Loggable
public class ArrearOverviewService {

    public static final String SCREEN = ArrearRegisterService.SCREEN;

    /** The tiles of I-08. {@code statutory} is null until the next cycle run reports it. */
    public record Summary(
            long openEvents,
            long awaitingApproval,
            BigDecimal arrears,
            long arrearMembers,
            BigDecimal recoveries,
            long recoveryMembers,
            BigDecimal statutory) {}

    /** One period of an event: the sums over its members and components. */
    public record PeriodRow(
            String yymm,
            BigDecimal original,
            BigDecimal revised,
            BigDecimal basicDiff,
            BigDecimal daDiff,
            BigDecimal hraDiff,
            BigDecimal netArrear) {}

    /** The retro controls: a setting is null while Headquarters has not configured it (R-RULE). */
    public record Controls(Long maxRetroMonths, Long postRetirementMonths) {}

    public record EventType(String code, String name, String category) {}

    /** The Maker's new retro event (I-08 "New Retro Event"). */
    public record NewEvent(
            String eventCode,
            String reference,
            String payrollType,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long circularId) {}

    private static final String OPEN = "('RAISED', 'COMPUTED', 'APPROVED')";
    // The offices of the session (OfficeScope: :all at Headquarters, :locations elsewhere).
    private static final String SCOPE = " (:all = 'Y' OR e.scope_location_id IN (:locations))";

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final DocumentStore documents;

    public ArrearOverviewService(
            NamedParameterJdbcTemplate named,
            JdbcTemplate jdbc,
            Authorization policy,
            SystemParameters parameters,
            AuditService audit,
            DocumentStore documents) {
        this.named = named;
        this.jdbc = jdbc;
        this.policy = policy;
        this.parameters = parameters;
        this.audit = audit;
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public Summary summary(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = scope(context);
        long open =
                count(
                        "SELECT COUNT(*) FROM arrear_event e WHERE e.event_status IN "
                                + OPEN
                                + " AND"
                                + SCOPE,
                        p);
        long awaiting =
                count(
                        "SELECT COUNT(*) FROM arrear_event e WHERE e.event_status = 'COMPUTED' AND"
                            + " EXISTS (SELECT 1 FROM approval_queue q WHERE q.entity_name ="
                            + " 'arrear_event' AND q.entity_key = TO_CHAR(e.arrear_event_id) AND"
                            + " q.status = 'PENDING_APPROVAL') AND"
                                + SCOPE,
                        p);
        BigDecimal arrears = sum("d.arrear_amount > 0", "d.arrear_amount", p);
        long arrearMembers = members("d.arrear_amount > 0", p);
        BigDecimal recoveries = sum("d.arrear_amount < 0", "-d.arrear_amount", p);
        long recoveryMembers = members("d.arrear_amount < 0", p);
        return new Summary(
                open, awaiting, arrears, arrearMembers, recoveries, recoveryMembers, null);
    }

    /** Every month of the event's span with its sums (SRS-8-13: the differential per period). */
    @Transactional(readOnly = true)
    public List<PeriodRow> periods(AccessContext context, long eventId) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = scope(context).addValue("id", eventId);
        requireInScope(eventId, p);
        // The component roles by the M-07 name (Basic Pay, Dearness Allowance ..., House Rent
        // Allowance ...): the catalogue carries no role flag yet (PENDING I08-2).
        return named.query(
                "SELECT d.differential_yymm AS yymm, COALESCE(SUM(d.original_amount), 0) AS"
                    + " original, COALESCE(SUM(d.recomputed_amount), 0) AS revised,"
                    + " COALESCE(SUM(CASE WHEN c.component_name LIKE 'Basic Pay%' THEN"
                    + " d.arrear_amount ELSE 0 END), 0) AS basic_diff, COALESCE(SUM(CASE WHEN"
                    + " c.component_name LIKE 'Dearness Allowance%' THEN d.arrear_amount ELSE 0"
                    + " END), 0) AS da_diff, COALESCE(SUM(CASE WHEN c.component_name LIKE 'House"
                    + " Rent Allowance%' THEN d.arrear_amount ELSE 0 END), 0) AS hra_diff,"
                    + " COALESCE(SUM(d.arrear_amount), 0) AS net FROM arrear_detail d JOIN"
                    + " pay_component c ON c.component_id = d.component_id WHERE d.arrear_event_id"
                    + " = :id GROUP BY d.differential_yymm ORDER BY d.differential_yymm",
                p,
                (rs, i) ->
                        new PeriodRow(
                                rs.getString("yymm"),
                                rs.getBigDecimal("original"),
                                rs.getBigDecimal("revised"),
                                rs.getBigDecimal("basic_diff"),
                                rs.getBigDecimal("da_diff"),
                                rs.getBigDecimal("hra_diff"),
                                rs.getBigDecimal("net")));
    }

    /** The Headquarters settings the retro controls card shows; null = not configured (R-RULE). */
    @Transactional(readOnly = true)
    public Controls controls(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        OptionalLong window = parameters.maxRetroMonths();
        // No A-07 setting names the post-retirement retro window yet (CLR, PENDING I08-4).
        return new Controls(window.isPresent() ? window.getAsLong() : null, null);
    }

    /**
     * The event types a Maker may raise by hand (the drawn dropdown): the arrear_event_type rows
     * the M-16 list ARREAR_MANUAL_EVENT_TYPE names today. Every other type has a source of its own
     * (HRA from HRMS, leave through I-21, DA from its job, I-24, I-25, I-06 ...: SRS-8-11,
     * BR-LEAVE-003) and is never keyed here; the list is unseeded until FCI names the types
     * (PENDING I08-3, R-RULE).
     */
    @Transactional(readOnly = true)
    public List<EventType> types(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        return manualTypes();
    }

    private List<EventType> manualTypes() {
        java.sql.Date today = java.sql.Date.valueOf(parameters.today());
        return jdbc.query(
                "SELECT t.event_code, t.event_name, t.event_category FROM arrear_event_type t JOIN"
                    + " reference_value v ON v.value_code = t.event_code JOIN reference_list l ON"
                    + " l.list_id = v.list_id WHERE l.list_code = 'ARREAR_MANUAL_EVENT_TYPE' AND"
                    + " v.effective_from <= ? AND (v.effective_to IS NULL OR v.effective_to >= ?)"
                    + " ORDER BY v.sort_order, t.event_name",
                (rs, i) ->
                        new EventType(
                                rs.getString("event_code"),
                                rs.getString("event_name"),
                                rs.getString("event_category")),
                today,
                today);
    }

    /**
     * The Maker raises a retro event for the office of the session and the span (SRS-8-8; the
     * wireframe's "New Retro Event"): RAISED, its engine computes it, Approve &amp; Merge posts it.
     * An event starting before the back-date limit is held (beyond_window_flag, SRS-8-6); a span
     * overlapping an open event of the same type and office is refused (SRS-8-15).
     */
    @Transactional
    public long create(AccessContext context, NewEvent n) {
        policy.require(context, SCREEN, "SUBMIT");
        if (context.locationId() == null) {
            throw new FciException(
                    ArrearErrorCode.STATE, new Object[] {"no office"}, List.of(), null);
        }
        if (n.effectiveTo() != null && n.effectiveTo().isBefore(n.effectiveFrom())) {
            throw new FciException(ArrearErrorCode.STATE, new Object[] {"span"}, List.of(), null);
        }
        // Only a type the list names may be keyed (SRS-8-11, BR-LEAVE-003): an empty list is "not
        // configured" (R-RULE), any other type is refused and nothing is created.
        List<EventType> manual = manualTypes();
        if (manual.isEmpty()) {
            throw new FciException(
                    ArrearErrorCode.NOT_CONFIGURED,
                    new Object[] {"ARREAR_MANUAL_EVENT_TYPE"},
                    List.of(),
                    null);
        }
        if (manual.stream().noneMatch(m -> m.code().equals(n.eventCode()))) {
            throw new FciException(
                    ArrearErrorCode.STATE,
                    new Object[] {n.eventCode(), "a manual type"},
                    List.of(),
                    null);
        }
        List<Long> types =
                jdbc.queryForList(
                        "SELECT event_type_id FROM arrear_event_type WHERE event_code = ?",
                        Long.class,
                        n.eventCode());
        YearMonth from = YearMonth.from(n.effectiveFrom());
        YearMonth to = n.effectiveTo() == null ? from : YearMonth.from(n.effectiveTo());
        List<Long> open =
                jdbc.queryForList(
                        "SELECT e.arrear_event_id FROM arrear_event e WHERE e.event_type_id = ? AND"
                                + " e.scope_location_id = ? AND e.event_status IN "
                                + OPEN
                                + " AND COALESCE(e.scope_from_yymm, '000000') <= ? AND"
                                + " COALESCE(e.scope_to_yymm, '999999') >= ?",
                        Long.class,
                        types.getFirst(),
                        context.locationId(),
                        yymm(to),
                        yymm(from));
        if (!open.isEmpty()) {
            throw new FciException(
                    ArrearErrorCode.STATE,
                    new Object[] {"overlaps event " + open.getFirst()},
                    List.of(),
                    null);
        }
        YearMonth current = YearMonth.from(parameters.today());
        boolean beyond =
                from.isBefore(current)
                        && ArrearRules.beyondWindow(n.effectiveFrom(), current, maxRetroMonths());
        long id =
                GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO arrear_event (event_type_id, event_reference, effective_from,"
                            + " effective_to, scope_payroll_type, scope_location_id,"
                            + " scope_from_yymm, scope_to_yymm, source_module, circular_order_id,"
                            + " beyond_window_flag, event_status, raised_by) VALUES (?, ?, ?, ?, ?,"
                            + " ?, ?, ?, 'I-08', ?, ?, 'RAISED', ?)",
                        "arrear_event_id",
                        types.getFirst(),
                        n.reference().strip(),
                        Date.valueOf(n.effectiveFrom()),
                        n.effectiveTo() == null ? null : Date.valueOf(n.effectiveTo()),
                        n.payrollType(),
                        context.locationId(),
                        yymm(from),
                        yymm(to),
                        n.circularId(),
                        beyond ? "Y" : "N",
                        context.loginId());
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ArrearRegisterService.ENTITY,
                                String.valueOf(id),
                                "event_status",
                                null,
                                "RAISED",
                                "Retro event "
                                        + n.eventCode()
                                        + " "
                                        + from
                                        + (n.effectiveTo() == null ? "" : " to " + to)
                                        + " raised on I-08: "
                                        + n.reference().strip()
                                        + (beyond ? " (beyond the back-date limit, SRS-8-6)" : ""))
                        .module(SCREEN));
        return id;
    }

    /** The order particulars of the drawn card (SRS-G-30): the file is the multipart part. */
    public record NewOrder(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingOffice,
            String fileName,
            byte[] content) {}

    /**
     * Attaches an order copy to the event (I-08 "Order copy - attach &amp; store", FRS §6.4.3,
     * A-41): the Maker, while the event is still open to changes (RAISED or COMPUTED); the file is
     * validated, virus-scanned and stored by the document module (PDF / JPG / PNG, SRS-G-30).
     */
    @Transactional
    public DocumentStore.AttachmentView attachOrder(
            AccessContext context, long eventId, NewOrder o) {
        policy.require(context, SCREEN, "SUBMIT");
        requireInScope(eventId, scope(context).addValue("id", eventId));
        String status =
                jdbc.queryForObject(
                        "SELECT event_status FROM arrear_event WHERE arrear_event_id = ?",
                        String.class,
                        eventId);
        if (!"RAISED".equals(status) && !"COMPUTED".equals(status)) {
            throw new FciException(ArrearErrorCode.STATE, new Object[] {status}, List.of(), null);
        }
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        ArrearRegisterService.ENTITY,
                        String.valueOf(eventId),
                        o.documentType(),
                        o.reference(),
                        o.orderDate(),
                        o.issuingOffice(),
                        o.fileName(),
                        o.content(),
                        false));
    }

    /** The orders stored with the event, oldest first (the drawn "Stored order" table). */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, long eventId) {
        policy.require(context, SCREEN, "VIEW");
        requireInScope(eventId, scope(context).addValue("id", eventId));
        return documents.active(ArrearRegisterService.ENTITY, String.valueOf(eventId));
    }

    /** One stored order of the event (read-only; the access is logged by the document module). */
    @Transactional
    public DocumentStore.Content order(
            AccessContext context, String sourceIp, long eventId, long attachmentId) {
        policy.require(context, SCREEN, "VIEW");
        requireInScope(eventId, scope(context).addValue("id", eventId));
        return documents.read(
                context.loginId(),
                sourceIp,
                ArrearRegisterService.ENTITY,
                String.valueOf(eventId),
                attachmentId);
    }

    /** An event outside the session's offices reads as not found (no existence leak, R-RBAC). */
    private void requireInScope(long eventId, MapSqlParameterSource p) {
        if (count(
                        "SELECT COUNT(*) FROM arrear_event e WHERE e.arrear_event_id = :id AND"
                                + SCOPE,
                        p)
                == 0) {
            throw new FciException(
                    in.gov.fci.payroll.platform.error.CommonErrorCode.NOT_FOUND,
                    new Object[] {"arrear_event", eventId},
                    List.of(),
                    null);
        }
    }

    private int maxRetroMonths() {
        return (int)
                parameters
                        .maxRetroMonths()
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                ArrearErrorCode.NOT_CONFIGURED,
                                                new Object[] {"MAX_RETRO_PERIOD"},
                                                List.of(),
                                                null));
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private MapSqlParameterSource scope(AccessContext context) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context));
    }

    private long count(String sql, MapSqlParameterSource p) {
        Long n = named.queryForObject(sql, p, Long.class);
        return n == null ? 0 : n;
    }

    private BigDecimal sum(String where, String expr, MapSqlParameterSource p) {
        BigDecimal v =
                named.queryForObject(
                        "SELECT COALESCE(SUM("
                                + expr
                                + "), 0) FROM arrear_detail d JOIN arrear_event e ON"
                                + " e.arrear_event_id = d.arrear_event_id WHERE e.event_status IN "
                                + OPEN
                                + " AND "
                                + where
                                + " AND"
                                + SCOPE,
                        p,
                        BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    private long members(String where, MapSqlParameterSource p) {
        return count(
                "SELECT COUNT(DISTINCT d.employee_id) FROM arrear_detail d JOIN arrear_event e ON"
                        + " e.arrear_event_id = d.arrear_event_id WHERE e.event_status IN "
                        + OPEN
                        + " AND "
                        + where
                        + " AND"
                        + SCOPE,
                p);
    }
}
