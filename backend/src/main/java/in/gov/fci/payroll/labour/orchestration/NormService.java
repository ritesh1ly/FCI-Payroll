package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.NormRules;
import in.gov.fci.payroll.labour.business.NormRules.Norms;
import in.gov.fci.payroll.labour.business.NormRules.Slab;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
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
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-06 Incentive Norms - Handling / Height / Lead (plan 20 phase 20d; SRS-3-6, SRS-3-12; M-06 use
 * case "Maintain Depot Incentive Norms"). Only Headquarters sets norms: the Rule Administrator
 * enters a depot's Handling, Height and Lead norms, the Handling Norm's MLN and MLSW and the
 * above-norm slab bands of each bag-weight table from the depot's union MoU, effective after the
 * depot's latest set; the Rule Approver approves it (route M06_NORM). Approved, the set is in force
 * from its date and the depot's previous set ends the day before; a rejected set is never used. The
 * Labour Dealing Hand (own depot), the Payroll Maker and Checker and Finance view. A depot without
 * an approved set has no norm: the incentive is refused, never computed against zero.
 */
@Service
@Loggable
public class NormService {

    static final String SCREEN = "M-06";
    static final String ENTITY = "labour_norm";
    static final String ROUTE = "M06_NORM";

    /**
     * A depot's norm set as M-06 lists it; {@code depotCode} keys the depot's History record
     * (SRS-20-29).
     */
    public record NormSet(
            long id,
            String depotCode,
            String depot,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long handling,
            long height,
            long lead,
            long minLabourNumber,
            BigDecimal minSlabWeight,
            String mouReference,
            String approvalState,
            List<Slab> slabs,
            String mouCircular) {}

    /** A depot the session may choose. */
    public record Depot(long id, String code, String name) {}

    /** A proposed set. */
    public record Proposal(
            Long depotLocationId,
            LocalDate effectiveFrom,
            Norms norms,
            String mouReference,
            List<Slab> slabs,
            String reason,
            Long mouCircularId) {}

    private static final String FROM =
            " FROM labour_norm n JOIN org_location l ON l.location_id = n.depot_location_id LEFT"
                + " JOIN approval_queue q ON q.approval_id = n.approval_id WHERE n.engine_code ="
                + " 'DEPT_LABOUR' AND (:all = 'Y' OR l.location_id IN (:locations)) AND (:depot IS"
                + " NULL OR n.depot_location_id = :depot)";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final OrgDirectory org;
    private final LabourDepots depots;
    private final MakerChecker approvals;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final Circulars circulars;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public NormService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            Authorization policy,
            OrgDirectory org,
            LabourDepots depots,
            MakerChecker approvals,
            SystemParameters parameters,
            AuditService audit,
            Circulars circulars) {
        this.circulars = circulars;
        this.jdbc = jdbc;
        this.named = named;
        this.policy = policy;
        this.org = org;
        this.depots = depots;
        this.approvals = approvals;
        this.parameters = parameters;
        this.audit = audit;
    }

    /** M-06: the norm sets of the session's depots, newest first; paged. */
    @Transactional(readOnly = true)
    public Paged<NormSet> page(AccessContext context, Long depot, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p =
                OfficeScope.of(context, policy.isAtHeadquarters(context))
                        .addValue("depot", depot, Types.BIGINT);
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<NormSet> rows =
                named.query(
                        "SELECT n.norm_id, l.location_code, l.location_name, n.version_no,"
                            + " n.effective_from, n.effective_to, n.handling_norm, n.height_norm,"
                            + " n.lead_norm, n.min_labour_number, n.min_labour_slab_weight,"
                            + " n.mou_reference, q.status, (SELECT MAX(c.circular_number) FROM"
                            + " circular_order_link k JOIN circular_order c ON c.circular_order_id"
                            + " = k.circular_order_id WHERE k.linked_entity = 'labour_norm' AND"
                            + " k.linked_key = TO_CHAR(n.norm_id) AND k.link_status = 'ACTIVE') AS"
                            + " mou_circular"
                                + FROM
                                + " ORDER BY l.location_code, n.effective_from DESC, n.norm_id"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) -> {
                            java.sql.Date to = rs.getDate("effective_to");
                            String state = rs.getString("status");
                            return new NormSet(
                                    rs.getLong("norm_id"),
                                    rs.getString("location_code"),
                                    rs.getString("location_code")
                                            + " - "
                                            + rs.getString("location_name"),
                                    rs.getInt("version_no"),
                                    rs.getDate("effective_from").toLocalDate(),
                                    to == null ? null : to.toLocalDate(),
                                    rs.getLong("handling_norm"),
                                    rs.getLong("height_norm"),
                                    rs.getLong("lead_norm"),
                                    rs.getLong("min_labour_number"),
                                    rs.getBigDecimal("min_labour_slab_weight"),
                                    rs.getString("mou_reference"),
                                    state == null ? "APPROVED" : state,
                                    slabs(rs.getLong("norm_id")),
                                    rs.getString("mou_circular"));
                        });
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** M-06: the depots of the session's offices (all of them at Headquarters). */
    @Transactional(readOnly = true)
    public List<Depot> depots(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        return depots.of(context, policy.isAtHeadquarters(context), parameters.today()).stream()
                .map(l -> new Depot(l.getId(), l.getCode(), l.getName()))
                .toList();
    }

    /** M-06: a new norm set of a depot for the Rule Approver (SRS-3-12). */
    @Transactional
    public SubmittedApproval propose(AccessContext context, Proposal p) {
        if (p == null || p.depotLocationId() == null) {
            throw invalid("DEPOT");
        }
        OrgLocation depot =
                org.location(p.depotLocationId())
                        .filter(l -> "DEPOT".equals(l.getType().name()))
                        .orElseThrow(() -> invalid("DEPOT"));
        policy.requireAt(context, SCREEN, "CREATE", depot.getId());
        if (p.norms() == null) {
            throw invalid("NORM");
        }
        NormRules.normsDefect(p.norms())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        NormRules.slabsDefect(p.slabs())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        if (p.reason() == null || p.reason().isBlank() || p.reason().strip().length() > 500) {
            throw invalid("REASON");
        }
        // SRS M-06 use case: "Actor links the MoU from M-25" (precondition: the depot's union MoU
        // order is held in the Circular / Order Master); only an Active circular (SRS-G-38).
        if (p.mouCircularId() == null) {
            throw invalid("MOU");
        }
        circulars.requireActive(p.mouCircularId());
        Integer pending =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_norm n JOIN approval_queue q ON q.approval_id"
                            + " = n.approval_id WHERE n.depot_location_id = ? AND n.engine_code ="
                            + " 'DEPT_LABOUR' AND q.status = 'PENDING_APPROVAL'",
                        Integer.class,
                        depot.getId());
        if (pending != null && pending > 0) {
            throw invalid("PENDING");
        }
        LocalDate latest =
                jdbc.queryForObject(
                        "SELECT MAX(n.effective_from) FROM labour_norm n LEFT JOIN approval_queue q"
                            + " ON q.approval_id = n.approval_id WHERE n.depot_location_id = ? AND"
                            + " n.engine_code = 'DEPT_LABOUR' AND (q.status IS NULL OR q.status ="
                            + " 'APPROVED')",
                        LocalDate.class,
                        depot.getId());
        NormRules.effectiveDefect(p.effectiveFrom(), latest)
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        Integer version =
                jdbc.queryForObject(
                        "SELECT MAX(version_no) FROM labour_norm WHERE depot_location_id = ? AND"
                                + " engine_code = 'DEPT_LABOUR'",
                        Integer.class,
                        depot.getId());
        Norms n = p.norms();
        jdbc.update(
                "INSERT INTO labour_norm (depot_location_id, engine_code, mou_reference,"
                    + " min_labour_number, min_labour_slab_weight, handling_norm, height_norm,"
                    + " lead_norm, version_no, effective_from, is_seeded) VALUES (?, 'DEPT_LABOUR',"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, 'Y')",
                depot.getId(),
                p.mouReference() == null || p.mouReference().isBlank()
                        ? null
                        : p.mouReference().strip(),
                n.minLabourNumber(),
                n.minSlabWeight(),
                n.handling(),
                n.height(),
                n.lead(),
                (version == null ? 0 : version) + 1,
                Date.valueOf(p.effectiveFrom()));
        Long normId =
                jdbc.queryForObject(
                        "SELECT norm_id FROM labour_norm WHERE depot_location_id = ? AND"
                                + " engine_code = 'DEPT_LABOUR' AND effective_from = ?",
                        Long.class,
                        depot.getId(),
                        Date.valueOf(p.effectiveFrom()));
        if (normId == null) {
            throw new IllegalStateException("The norm set just written is not found");
        }
        for (Slab s : p.slabs()) {
            jdbc.update(
                    "INSERT INTO labour_above_norm_slab (norm_id, slab_table_ref, band_order,"
                            + " range_from, range_to, slab_rate_percent) VALUES (?, ?, ?, ?, ?, ?)",
                    normId,
                    s.table(),
                    s.order(),
                    s.from(),
                    s.to(),
                    s.ratePercent());
        }
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                ROUTE,
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                String.valueOf(normId),
                                ActionType.CREATE,
                                depot.getId(),
                                "{\"circularId\":" + p.mouCircularId() + "}",
                                p.effectiveFrom(),
                                p.reason().strip()),
                        null);
        jdbc.update("UPDATE labour_norm SET approval_id = ? WHERE norm_id = ?", item.id(), normId);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(normId),
                                "approval_state",
                                null,
                                "PENDING_APPROVAL",
                                depot.getCode() + " norms from " + p.effectiveFrom())
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(p.effectiveFrom()));
        return item;
    }

    /** The Rule Approver's decision: approved, in force from its date; the set before ends. */
    @EventListener
    @Transactional
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        long normId = Long.parseLong(decision.entityKey());
        Map<String, Object> n =
                jdbc.queryForMap(
                        "SELECT depot_location_id, effective_from FROM labour_norm WHERE norm_id ="
                                + " ?",
                        normId);
        String state = decision.approved() ? "APPROVED" : "REJECTED";
        Matcher mou = MOU.matcher(decision.payload() == null ? "" : decision.payload());
        if (decision.approved() && mou.find()) {
            // SRS-G-38: the MoU circular is linked to the approved norm set, kept for ever.
            circulars.link(
                    decision.checkerLoginId(),
                    Long.valueOf(mou.group(1)),
                    Circulars.LinkType.MASTER_ENTRY,
                    ENTITY,
                    String.valueOf(normId),
                    SCREEN);
        }
        if (decision.approved()) {
            LocalDate from =
                    new java.sql.Date(((java.util.Date) n.get("EFFECTIVE_FROM")).getTime())
                            .toLocalDate();
            jdbc.update(
                    "UPDATE labour_norm SET effective_to = ? WHERE depot_location_id = ? AND"
                        + " engine_code = 'DEPT_LABOUR' AND norm_id <> ? AND effective_to IS NULL"
                        + " AND effective_from < ? AND (approval_id IS NULL OR approval_id IN"
                        + " (SELECT approval_id FROM approval_queue WHERE status = 'APPROVED'))",
                    Date.valueOf(from.minusDays(1)),
                    n.get("DEPOT_LOCATION_ID"),
                    normId,
                    Date.valueOf(from));
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(normId),
                                "approval_state",
                                "PENDING_APPROVAL",
                                state,
                                decision.reason() == null ? state : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    private List<Slab> slabs(long normId) {
        return jdbc.query(
                "SELECT slab_table_ref, band_order, range_from, range_to, slab_rate_percent FROM"
                        + " labour_above_norm_slab WHERE norm_id = ? ORDER BY slab_table_ref,"
                        + " band_order",
                (rs, i) -> {
                    // wasNull() reports the last column read: asked right after range_to, whose
                    // null is the open top band.
                    long to = rs.getLong("range_to");
                    Long upper = rs.wasNull() ? null : to;
                    return new Slab(
                            rs.getString("slab_table_ref"),
                            rs.getInt("band_order"),
                            rs.getLong("range_from"),
                            upper,
                            rs.getBigDecimal("slab_rate_percent"));
                },
                normId);
    }

    /** The MoU circular a proposal names, in its submission payload ({@code {"circularId":n}}). */
    private static final Pattern MOU = Pattern.compile("\"circularId\":(\\d+)");

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.NORM, new Object[] {defect}, List.of(), null);
    }
}
