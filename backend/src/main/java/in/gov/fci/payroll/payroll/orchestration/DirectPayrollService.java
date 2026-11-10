package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.payroll.business.MdpRules;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * I-19 Maintain Direct Payroll (plan 05 phase 05g; SRS-15-2, SRS-15-19, SRS-15-22, SRS-G-1,
 * SRS-G-37, BR-CYC-018): the post-lock transaction register for bulk correction of the permitted
 * deduction heads, no payment through it.
 *
 * <p>The Payroll Maker captures an entry for a member of the locked run, in the MDP window of the
 * period (M-10), for a head of the list MDP_PERMITTED_COMPONENT, with a deduction amount and a
 * reason; the capture opens its approval at once (DDL status ENTERED: "Pending Checker"). An
 * amendment or cancellation goes through the Checker as well (routes I19_EDIT, I19_CANCEL): a
 * returned entry is corrected in place (AMENDED), an approved one is never edited, its amendment
 * cancels it and records a new approved entry on approval ({@link DirectPayrollEffect}). Nothing is
 * deleted; the register, with cancelled entries, is the MDP Audit Report.
 */
@Service
@Loggable
public class DirectPayrollService {

    /** Approval entity of an MDP entry (key: the {@code mdp_id}). */
    public static final String ENTITY = "mdp_transaction";

    private static final String SCREEN = "I-19";

    /** The register's filters (each optional). */
    public record Filter(YearMonth period, String status, Integer payCode, String eis) {}

    /**
     * One entry of the register / MDP Audit Report.
     *
     * @param reference MDP-{yymm}-{mdp id}, unique within the period
     */
    public record Entry(
            long id,
            String reference,
            String period,
            String eis,
            String member,
            int payCode,
            String head,
            BigDecimal amount,
            String status,
            String reason,
            String enteredBy,
            Instant enteredAt,
            String checkedBy,
            Instant decidedAt,
            Long pendingApprovalId) {}

    /** A permitted head. */
    public record Head(int payCode, String name) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final OrgDirectory org;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final ApprovalStatuses approvalStatuses;
    private final AuditService audit;
    private final SystemParameters parameters;
    private final ObjectMapper json;
    private final DocumentStore documents;

    public DirectPayrollService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            OrgDirectory org,
            Authorization policy,
            MakerChecker approvals,
            ApprovalStatuses approvalStatuses,
            AuditService audit,
            SystemParameters parameters,
            ObjectMapper json,
            DocumentStore documents) {
        this.jdbc = jdbc;
        this.named = named;
        this.org = org;
        this.policy = policy;
        this.approvals = approvals;
        this.approvalStatuses = approvalStatuses;
        this.audit = audit;
        this.parameters = parameters;
        this.json = json;
        this.documents = documents;
    }

    /**
     * The particulars of an MDP authority order (the drawn order card, SRS-G-30); the file is the
     * part.
     */
    public record NewOrder(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingOffice,
            String fileName,
            byte[] content) {}

    /**
     * Attaches the MDP authority order to an entry of the unit (I-19 "Order copy - attach &amp;
     * store", FRS §6.4.3, A-41): the Maker, while the entry is ENTERED or AMENDED; the document
     * module validates, scans and stores it (PDF / JPG / PNG). An entry of another unit reads as
     * not found.
     */
    @Transactional
    public DocumentStore.AttachmentView attachOrder(AccessContext context, long mdpId, NewOrder o) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "CREATE", unit.getId());
        String status = statusInUnit(unit, mdpId);
        if (!"ENTERED".equals(status) && !"AMENDED".equals(status)) {
            throw new FciException(ApiErrorCode.MDP_STATE, new Object[] {status}, List.of(), null);
        }
        return documents.attach(
                login(context),
                new DocumentStore.NewAttachment(
                        ENTITY,
                        String.valueOf(mdpId),
                        o.documentType(),
                        o.reference(),
                        o.orderDate(),
                        o.issuingOffice(),
                        o.fileName(),
                        o.content(),
                        false));
    }

    /** The orders stored with an entry of the unit, oldest first. */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, long mdpId) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        statusInUnit(unit, mdpId);
        return documents.active(ENTITY, String.valueOf(mdpId));
    }

    /** One stored order of an entry of the unit (read-only; the access is logged). */
    @Transactional
    public DocumentStore.Content order(
            AccessContext context, String sourceIp, long mdpId, long attachmentId) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        statusInUnit(unit, mdpId);
        return documents.read(
                login(context), sourceIp, ENTITY, String.valueOf(mdpId), attachmentId);
    }

    /**
     * The status of an entry of the unit; another unit's entry is not found (no existence leak).
     */
    private String statusInUnit(OrgLocation unit, long mdpId) {
        return jdbc
                .queryForList(
                        "SELECT m.mdp_status FROM mdp_transaction m JOIN payroll_cycle_instance c"
                            + " ON c.cycle_instance_id = m.cycle_instance_id WHERE m.mdp_id = ? AND"
                            + " c.org_unit_id = ?",
                        String.class,
                        mdpId,
                        unit.getId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /**
     * The four tiles of I-19 as Wireframes v6.1 draws them (OD-121; SRS-15-2, SRS-15-19,
     * BR-CYC-010): the window (the unit's newest period whose MDP window is open today, else its
     * newest locked period, with the cycle's lock time), the entries of that period (approved or
     * posted / waiting for the Checker), the permitted heads and the value of the entries that are
     * not cancelled. {@code approvedValue} is the register's "Approved total". {@code period} is
     * null when the unit has no locked period yet.
     */
    public record Summary(
            String period,
            boolean windowOpen,
            Instant lockedAt,
            long approved,
            long pending,
            int heads,
            BigDecimal value,
            BigDecimal approvedValue) {}

    @Transactional(readOnly = true)
    public Summary summary(AccessContext context) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        List<String> open = openPeriods(context);
        String period =
                open.isEmpty()
                        ? jdbc
                                .queryForList(
                                        "SELECT MAX(payroll_yymm) FROM payroll_cycle_instance WHERE"
                                                + " org_unit_id = ? AND cycle_status = 'LOCKED'",
                                        String.class,
                                        unit.getId())
                                .stream()
                                .filter(java.util.Objects::nonNull)
                                .map(y -> y.substring(0, 4) + "-" + y.substring(4))
                                .findFirst()
                                .orElse(null)
                        : open.getFirst();
        int heads = permittedHeads().size();
        if (period == null) {
            return new Summary(null, false, null, 0, 0, heads, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        String yymm = period.replace("-", "");
        List<Timestamp> locked =
                jdbc.queryForList(
                        "SELECT locked_at FROM payroll_cycle_instance WHERE org_unit_id = ? AND"
                                + " payroll_yymm = ?",
                        Timestamp.class,
                        unit.getId(),
                        yymm);
        Map<String, Object> sums =
                jdbc.queryForMap(
                        "SELECT COALESCE(SUM(CASE WHEN m.mdp_status IN ('APPROVED', 'POSTED') THEN"
                            + " 1 ELSE 0 END), 0) AS approved, COALESCE(SUM(CASE WHEN m.mdp_status"
                            + " IN ('ENTERED', 'AMENDED') THEN 1 ELSE 0 END), 0) AS pending,"
                            + " COALESCE(SUM(CASE WHEN m.mdp_status <> 'CANCELLED' THEN m.amount"
                            + " ELSE 0 END), 0) AS total_value, COALESCE(SUM(CASE WHEN m.mdp_status"
                            + " IN ('APPROVED', 'POSTED') THEN m.amount ELSE 0 END), 0) AS"
                            + " approved_value FROM mdp_transaction m JOIN payroll_cycle_instance c"
                            + " ON c.cycle_instance_id = m.cycle_instance_id WHERE c.org_unit_id ="
                            + " ? AND c.payroll_yymm = ?",
                        unit.getId(),
                        yymm);
        Timestamp at = locked.isEmpty() ? null : locked.getFirst();
        return new Summary(
                period,
                !open.isEmpty(),
                at == null ? null : at.toInstant(),
                ((Number) sums.get("APPROVED")).longValue(),
                ((Number) sums.get("PENDING")).longValue(),
                heads,
                new BigDecimal(sums.get("TOTAL_VALUE").toString()),
                new BigDecimal(sums.get("APPROVED_VALUE").toString()));
    }

    // ---------------------------------------------------------------- reads

    /** The heads I-19 permits today (list MDP_PERMITTED_COMPONENT). */
    @Transactional(readOnly = true)
    public List<Head> heads(AccessContext context) {
        policy.requireAt(context, SCREEN, "VIEW", unit(context).getId());
        return permittedHeads();
    }

    /** SRS-15-2: the unit's locked periods whose MDP window is open today, newest first. */
    @Transactional(readOnly = true)
    public List<String> openPeriods(AccessContext context) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        LocalDate today = parameters.today();
        return jdbc
                .queryForList(
                        "SELECT c.payroll_yymm FROM payroll_cycle_instance c JOIN payroll_calendar"
                            + " k ON k.payroll_yymm = c.payroll_yymm WHERE c.org_unit_id = ? AND"
                            + " c.cycle_status = 'LOCKED' AND k.mdp_window_from <= ? AND"
                            + " k.mdp_window_to >= ? ORDER BY c.payroll_yymm DESC",
                        String.class,
                        unit.getId(),
                        java.sql.Date.valueOf(today),
                        java.sql.Date.valueOf(today))
                .stream()
                .map(y -> y.substring(0, 4) + "-" + y.substring(4))
                .toList();
    }

    @Transactional(readOnly = true)
    public Paged<Entry> register(AccessContext context, Filter filter, PageSpec spec) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "VIEW", unit.getId());
        MapSqlParameterSource params = params(unit, filter);
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, params, Long.class);
        params.addValue("offset", (long) spec.page() * spec.size());
        params.addValue("size", spec.size());
        List<Entry> rows =
                named.query(
                        SELECT
                                + FROM
                                + WHERE
                                + " ORDER BY m.mdp_id DESC OFFSET :offset ROWS FETCH NEXT :size"
                                + " ROWS ONLY",
                        params,
                        (rs, i) -> entry(rs));
        return new Paged<>(withPending(rows), spec.page(), spec.size(), CycleSupport.orZero(total));
    }

    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, Filter filter) {
        return register(context, filter, new PageSpec(0, 1)).total();
    }

    /**
     * SRS-15-19, SRS-15-22: the MDP Audit Report (FRS Annexure A.4.7): every entry of the filter,
     * cancelled ones included, with Maker and Checker, employee number, entry and approval date and
     * time, in one snapshot; the rows written are checked against the snapshot's count.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, Filter filter, ExportSink sink) throws IOException {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "EXPORT", unit.getId());
        MapSqlParameterSource params = params(unit, filter);
        Long expected = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, params, Long.class);
        sink.header(
                "mdp_reference",
                "period",
                "employee_number",
                "member",
                "pay_code",
                "head",
                "amount",
                "status",
                "reason",
                "maker",
                "entered_at",
                "checker",
                "decided_at");
        CycleSupport.exporting(
                () ->
                        named.query(
                                SELECT + FROM + WHERE + " ORDER BY m.mdp_id DESC",
                                params,
                                rs -> {
                                    Entry e = entry(rs);
                                    CycleSupport.row(
                                            sink,
                                            e.reference(),
                                            e.period(),
                                            e.eis(),
                                            e.member(),
                                            e.payCode(),
                                            e.head(),
                                            e.amount(),
                                            e.status(),
                                            e.reason(),
                                            e.enteredBy(),
                                            e.enteredAt(),
                                            e.checkedBy(),
                                            e.decidedAt());
                                }));
        ExportSink.requireComplete(CycleSupport.orZero(expected), sink);
    }

    // ---------------------------------------------------------------- changes

    /**
     * SRS-15-19: captures an entry and sends it to the Checker.
     *
     * @return the approval item
     */
    @Transactional
    public long capture(
            AccessContext context,
            YearMonth period,
            String eis,
            int payCode,
            BigDecimal amount,
            String reason) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "CREATE", unit.getId());
        long cycleId = openCycle(unit, period);
        long componentId = permittedComponent(payCode);
        requireAmount(amount);
        Long member =
                jdbc
                        .queryForList(
                                "SELECT r.employee_id FROM payroll_result r JOIN employee e ON"
                                        + " e.employee_id = r.employee_id WHERE r.cycle_instance_id"
                                        + " = ? AND r.run_type = 'ACTUAL' AND r.is_locked = 'Y'"
                                        + " AND e.emp_num = ?",
                                Long.class,
                                cycleId,
                                eis.strip())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(ApiErrorCode.MDP_MEMBER));
        String me = login(context);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO mdp_transaction (cycle_instance_id, employee_id,"
                                            + " component_id, amount, mdp_status, reason,"
                                            + " entered_by) VALUES (?, ?, ?, ?, 'ENTERED', ?, ?)",
                                    new String[] {"mdp_id"});
                    ps.setLong(1, cycleId);
                    ps.setLong(2, member);
                    ps.setLong(3, componentId);
                    ps.setBigDecimal(4, amount);
                    ps.setString(5, reason);
                    ps.setString(6, me);
                    return ps;
                },
                key);
        long mdpId = GeneratedKeys.id(key);
        long approval =
                submit(
                        context,
                        unit,
                        "CREATE",
                        mdpId,
                        period,
                        payload("CREATE", mdpId, amount, reason));
        jdbc.update("UPDATE mdp_transaction SET approval_id = ? WHERE mdp_id = ?", approval, mdpId);
        record(me, mdpId, "mdp_status", null, "ENTERED", reason, approval);
        return approval;
    }

    /**
     * SRS-G-1 / SRS-G-37: amends an entry. A returned entry (ENTERED / AMENDED) is corrected and
     * sent again; an approved one is never edited: its amendment is decided by the Checker.
     */
    @Transactional
    public long amend(AccessContext context, long mdpId, BigDecimal amount, String reason) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "EDIT", unit.getId());
        Map<String, Object> entry = changeable(unit, mdpId);
        YearMonth period = period(entry);
        openCycle(unit, period);
        requireAmount(amount);
        String status = (String) entry.get("MDP_STATUS");
        String me = login(context);
        if (!MdpRules.Status.APPROVED.name().equals(status)) {
            jdbc.update(
                    "UPDATE mdp_transaction SET amount = ?, reason = ?, mdp_status = 'AMENDED'"
                            + " WHERE mdp_id = ?",
                    amount,
                    reason,
                    mdpId);
            record(
                    me,
                    mdpId,
                    "amount",
                    text(entry.get("AMOUNT")),
                    amount.toPlainString(),
                    reason,
                    null);
        }
        long approval =
                submit(
                        context,
                        unit,
                        "EDIT",
                        mdpId,
                        period,
                        payload("AMEND", mdpId, amount, reason));
        if (!MdpRules.Status.APPROVED.name().equals(status)) {
            jdbc.update(
                    "UPDATE mdp_transaction SET approval_id = ? WHERE mdp_id = ?", approval, mdpId);
        }
        return approval;
    }

    /** SRS-G-37: asks for the cancellation of an entry (decided by the Checker). */
    @Transactional
    public long cancel(AccessContext context, long mdpId, String reason) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, SCREEN, "CANCEL", unit.getId());
        Map<String, Object> entry = changeable(unit, mdpId);
        return submit(
                context,
                unit,
                "CANCEL",
                mdpId,
                period(entry),
                payload("CANCEL", mdpId, null, reason));
    }

    // ---------------------------------------------------------------- helpers

    private static final String SELECT =
            "SELECT m.mdp_id, c.payroll_yymm, e.emp_num, e.first_name, e.last_name,"
                    + " p.legacy_pay_code, p.component_name, m.amount, m.mdp_status, m.reason,"
                    + " m.entered_by, m.entered_at, a.decided_by, a.decided_at";

    private static final String FROM =
            " FROM mdp_transaction m JOIN payroll_cycle_instance c ON c.cycle_instance_id ="
                    + " m.cycle_instance_id JOIN employee e ON e.employee_id = m.employee_id JOIN"
                    + " pay_component p ON p.component_id = m.component_id LEFT JOIN approval_queue"
                    + " a ON a.approval_id = m.approval_id AND a.status IN ('APPROVED',"
                    + " 'REJECTED')";

    /** The filters: each one applies only when set (a typed null bind is accepted by Oracle). */
    private static final String WHERE =
            " WHERE c.org_unit_id = :unit AND (:period IS NULL OR c.payroll_yymm = :period) AND"
                    + " (:status IS NULL OR m.mdp_status = :status) AND (:payCode IS NULL OR"
                    + " p.legacy_pay_code = :payCode) AND (:eis IS NULL OR e.emp_num = :eis)";

    private static MapSqlParameterSource params(OrgLocation unit, Filter filter) {
        return new MapSqlParameterSource()
                .addValue("unit", unit.getId())
                .addValue(
                        "period",
                        filter.period() == null ? null : CycleKeys.yymm(filter.period()),
                        Types.VARCHAR)
                .addValue("status", filter.status(), Types.VARCHAR)
                .addValue("payCode", filter.payCode(), Types.INTEGER)
                .addValue(
                        "eis",
                        // The controller refuses a blank employee number (400).
                        filter.eis() == null ? null : filter.eis().strip(),
                        Types.VARCHAR);
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        String yymm = rs.getString(2);
        Timestamp entered = rs.getTimestamp(12);
        Timestamp decided = rs.getTimestamp(14);
        return new Entry(
                rs.getLong(1),
                "MDP-" + yymm + "-" + rs.getLong(1),
                yymm.substring(0, 4) + "-" + yymm.substring(4),
                rs.getString(3),
                (rs.getString(4) + " " + rs.getString(5)).strip(),
                rs.getInt(6),
                rs.getString(7),
                rs.getBigDecimal(8),
                rs.getString(9),
                rs.getString(10),
                rs.getString(11),
                entered == null ? null : entered.toInstant(),
                rs.getString(13),
                decided == null ? null : decided.toInstant(),
                null);
    }

    /** The waiting decision of each entry of the page (one query for the page). */
    private List<Entry> withPending(List<Entry> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, Long> latest = new LinkedHashMap<>();
        named.query(
                "SELECT entity_key, MAX(approval_id) FROM approval_queue WHERE entity_name ="
                        + " :entity AND entity_key IN (:keys) GROUP BY entity_key",
                new MapSqlParameterSource()
                        .addValue("entity", ENTITY)
                        .addValue("keys", rows.stream().map(r -> String.valueOf(r.id())).toList()),
                rs -> {
                    latest.put(rs.getString(1), rs.getLong(2));
                });
        Map<Long, ApprovalStatus> statuses = approvalStatuses.of(List.copyOf(latest.values()));
        List<Entry> out = new ArrayList<>();
        for (Entry e : rows) {
            Long item = latest.get(String.valueOf(e.id()));
            ApprovalStatus s = item == null ? null : statuses.get(item);
            boolean pending = s != null && s.name().startsWith("PENDING");
            out.add(
                    new Entry(
                            e.id(),
                            e.reference(),
                            e.period(),
                            e.eis(),
                            e.member(),
                            e.payCode(),
                            e.head(),
                            e.amount(),
                            e.status(),
                            e.reason(),
                            e.enteredBy(),
                            e.enteredAt(),
                            e.checkedBy(),
                            e.decidedAt(),
                            pending ? item : null));
        }
        return out;
    }

    private Long pending(long mdpId) {
        return CycleSupport.pending(jdbc, approvalStatuses, ENTITY, String.valueOf(mdpId));
    }

    /** An entry of the unit that may be amended or cancelled now. */
    private Map<String, Object> changeable(OrgLocation unit, long mdpId) {
        Map<String, Object> entry =
                jdbc
                        .queryForList(
                                "SELECT m.mdp_id, m.mdp_status, m.amount, c.payroll_yymm FROM"
                                        + " mdp_transaction m JOIN payroll_cycle_instance c ON"
                                        + " c.cycle_instance_id = m.cycle_instance_id WHERE"
                                        + " m.mdp_id = ? AND c.org_unit_id = ?",
                                mdpId,
                                unit.getId())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        MdpRules.changeDefect(
                        MdpRules.Status.valueOf((String) entry.get("MDP_STATUS")),
                        pending(mdpId) != null)
                .ifPresent(
                        defect -> {
                            throw new FciException(
                                    ApiErrorCode.MDP_STATE, new Object[] {defect}, List.of(), null);
                        });
        return entry;
    }

    /** SRS-15-2: the cycle of the period is LOCKED at the unit and its MDP window is open today. */
    private long openCycle(OrgLocation unit, YearMonth period) {
        String yymm = CycleKeys.yymm(period);
        List<Map<String, Object>> found =
                jdbc.queryForList(
                        "SELECT c.cycle_instance_id, k.mdp_window_from, k.mdp_window_to FROM"
                                + " payroll_cycle_instance c JOIN payroll_calendar k ON"
                                + " k.payroll_yymm = c.payroll_yymm WHERE c.payroll_yymm = ? AND"
                                + " c.org_unit_id = ? AND c.cycle_status = 'LOCKED'",
                        yymm,
                        unit.getId());
        if (found.isEmpty()
                || !MdpRules.windowOpen(
                        parameters.today(),
                        date(found.get(0).get("MDP_WINDOW_FROM")),
                        date(found.get(0).get("MDP_WINDOW_TO")))) {
            throw new FciException(
                    ApiErrorCode.MDP_WINDOW, new Object[] {period.toString()}, List.of(), null);
        }
        return ((Number) found.get(0).get("CYCLE_INSTANCE_ID")).longValue();
    }

    private List<Head> permittedHeads() {
        LocalDate today = parameters.today();
        return jdbc.query(
                "SELECT p.legacy_pay_code, p.component_name FROM reference_value v JOIN"
                    + " reference_list l ON l.list_id = v.list_id JOIN pay_component p ON"
                    + " CAST(p.legacy_pay_code AS VARCHAR(10)) = v.value_code WHERE l.list_code ="
                    + " 'MDP_PERMITTED_COMPONENT' AND v.effective_from <= ? AND (v.effective_to IS"
                    + " NULL OR v.effective_to >= ?) AND p.component_type = 'DEDUCTION' ORDER BY"
                    + " v.sort_order, p.legacy_pay_code",
                (rs, i) -> new Head(rs.getInt(1), rs.getString(2)),
                java.sql.Date.valueOf(today),
                java.sql.Date.valueOf(today));
    }

    /** BR-CYC-018: the component of a permitted head; any other head is refused. */
    private long permittedComponent(int payCode) {
        List<Head> heads = permittedHeads();
        if (heads.isEmpty()) {
            throw new FciException(ApiErrorCode.MDP_NOT_CONFIGURED);
        }
        if (heads.stream().noneMatch(h -> h.payCode() == payCode)) {
            throw new FciException(ApiErrorCode.MDP_HEAD);
        }
        // A head listed on M-16 whose pay code is not on M-07 is not a usable head.
        return jdbc
                .queryForList(
                        "SELECT component_id FROM pay_component WHERE legacy_pay_code = ?",
                        Long.class,
                        payCode)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(ApiErrorCode.MDP_HEAD));
    }

    private static void requireAmount(BigDecimal amount) {
        MdpRules.amountDefect(amount)
                .ifPresent(
                        defect -> {
                            throw new FciException(
                                    ApiErrorCode.MDP_AMOUNT,
                                    new Object[] {defect},
                                    List.of(),
                                    null);
                        });
    }

    private long submit(
            AccessContext context,
            OrgLocation unit,
            String action,
            long mdpId,
            YearMonth period,
            String payload) {
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                action,
                                ENTITY,
                                String.valueOf(mdpId),
                                "CREATE".equals(action) ? ActionType.CREATE : ActionType.UPDATE,
                                unit.getId(),
                                payload,
                                period.atEndOfMonth(),
                                json.readTree(payload).path("reason").asString()),
                        null);
        return item.id();
    }

    private String payload(String op, long mdpId, BigDecimal amount, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("op", op);
        p.put("mdpId", mdpId);
        p.put("amount", amount == null ? null : amount.toPlainString());
        p.put("reason", reason);
        return json.writeValueAsString(p);
    }

    private void record(
            String by,
            long mdpId,
            String field,
            String old,
            String now,
            String reason,
            Long approval) {
        AuditService.Entry entry =
                AuditService.Entry.change(ENTITY, String.valueOf(mdpId), field, old, now, reason)
                        .module(SCREEN);
        audit.record(by, approval == null ? entry : entry.approval(approval));
    }

    private static YearMonth period(Map<String, Object> entry) {
        String yymm = (String) entry.get("PAYROLL_YYMM");
        return YearMonth.of(
                Integer.parseInt(yymm.substring(0, 4)), Integer.parseInt(yymm.substring(4)));
    }

    /**
     * A DATE column as JDBC hands it over: java.sql.Date, java.time, or a timestamp (H2's Oracle
     * mode stores DATE as TIMESTAMP).
     */
    static LocalDate date(Object value) {
        return CycleSupport.date(value);
    }

    private static String text(Object value) {
        return CycleSupport.text(value);
    }

    private OrgLocation unit(AccessContext context) {
        return CycleSupport.unit(org, context);
    }

    private static String login(AccessContext context) {
        return CycleSupport.login(context);
    }
}
