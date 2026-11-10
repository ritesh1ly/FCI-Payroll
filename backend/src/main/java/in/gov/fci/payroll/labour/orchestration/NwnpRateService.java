package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.LabourRateRules;
import in.gov.fci.payroll.labour.business.LabourRateRules.Rate;
import in.gov.fci.payroll.labour.business.LabourRateRules.Settings;
import in.gov.fci.payroll.labour.business.NwnpRateRules;
import in.gov.fci.payroll.labour.business.NwnpRateRules.Line;
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
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-05 Labour Rate Tables ▸ NWNP (plan 22 phase 22b; SRS-5-1, SRS-5-2, SRS-5-3, SRS-5-11; DDL
 * {@code rate_set}, {@code nwnp_sor_rate}, {@code labour_min_wage}; D22-5, D22-6): a depot's
 * effective-dated ASOR or SOR rate set holds every NWNP operation's Piece of Rate against its Unit
 * of Measure and every NWNP designation's Minimum Guaranteed Wage, all mandatory. Only Headquarters
 * sets labour rates: the Rule Administrator proposes a set dated after the latest one of the depot
 * and version, the Rule Approver approves it (route M05_LABOUR_RATE); approved, the set applies
 * from its date and the set before ends the day before (set-level rules on {@code rate_set}); a
 * rejected set is listed and never used.
 */
@Service
@Loggable
public class NwnpRateService {

    static final String SCREEN = "M-05";
    static final String ENTITY = "nwnp_rate_set";
    static final String ROUTE = "M05_LABOUR_RATE";
    private static final String NWNP = "NWNP_LABOUR";

    /** One operation's rate of a set. */
    public record RateLine(
            String operationCode,
            String operation,
            BigDecimal pieceOfRate,
            String unitOfMeasure,
            String unitLabel) {}

    /** One designation's MGW of a set. */
    public record Mgw(long designationId, String designation, String gangRole, BigDecimal mgw) {}

    /** A depot's NWNP rate set as M-05 lists it. */
    public record RateSet(
            long id,
            String code,
            String depot,
            String version,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String approvalState,
            List<RateLine> lines,
            List<Mgw> mgw) {}

    /** An NWNP operation (NWNP Labour Operation Info) and its M-27 operation. */
    public record Operation(String code, String name, String m27) {}

    /** A Unit of Measure of the list (code and label). */
    public record Unit(String code, String label) {}

    /** The choices of the NWNP form. */
    public record Options(
            List<Operation> operations,
            List<LabourRateService.Designation> designations,
            List<Unit> unitsOfMeasure,
            List<LabourRateService.Designation> depots) {}

    /** A proposed set. */
    public record Proposal(
            Long depotLocationId,
            String version,
            LocalDate effectiveFrom,
            List<Line> lines,
            List<Rate> mgw,
            String reason) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final OrgDirectory org;
    private final MakerChecker approvals;
    private final SystemParameters parameters;
    private final AuditService audit;

    public NwnpRateService(
            JdbcTemplate jdbc,
            Authorization policy,
            OrgDirectory org,
            MakerChecker approvals,
            SystemParameters parameters,
            AuditService audit) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.org = org;
        this.approvals = approvals;
        this.parameters = parameters;
        this.audit = audit;
    }

    /** M-05 ▸ NWNP: the depots' rate sets, newest first; paged. */
    @Transactional(readOnly = true)
    public Paged<RateSet> page(AccessContext context, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rate_set WHERE payroll_type = ?", Long.class, NWNP);
        List<RateSet> rows =
                jdbc.query(
                        "SELECT s.rate_set_id, s.rate_set_code, s.version_no, s.effective_from,"
                                + " s.effective_to, s.approval_state, l.location_code,"
                                + " l.location_name FROM rate_set s JOIN org_location l ON"
                                + " l.location_id = s.location_id WHERE s.payroll_type = ? ORDER BY"
                                + " s.effective_from DESC, s.rate_set_id DESC OFFSET ? ROWS FETCH"
                                + " NEXT ? ROWS ONLY",
                        (rs, i) -> {
                            long id = rs.getLong("rate_set_id");
                            String code = rs.getString("rate_set_code");
                            java.sql.Date to = rs.getDate("effective_to");
                            return new RateSet(
                                    id,
                                    code,
                                    rs.getString("location_code")
                                            + " - "
                                            + rs.getString("location_name"),
                                    code.substring(code.lastIndexOf('-') + 1),
                                    rs.getInt("version_no"),
                                    rs.getDate("effective_from").toLocalDate(),
                                    to == null ? null : to.toLocalDate(),
                                    rs.getString("approval_state"),
                                    lines(id),
                                    mgw(id));
                        },
                        NWNP,
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** M-05 ▸ NWNP: the operations, designations, units of measure and depots to choose. */
    @Transactional(readOnly = true)
    public Options options(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        LocalDate today = parameters.today();
        List<LabourRateService.Designation> depots =
                org.locations(org.all()).stream()
                        .filter(l -> "DEPOT".equals(l.getType().name()) && l.isActiveOn(today))
                        .sorted(java.util.Comparator.comparing(OrgLocation::getCode))
                        .map(
                                l ->
                                        new LabourRateService.Designation(
                                                l.getId(), l.getCode(), l.getName(), null))
                        .toList();
        return new Options(operations(), designations(), units(), depots);
    }

    /** M-05 ▸ NWNP: a new rate set of a depot for the Rule Approver (SRS-5-2, SRS-5-3). */
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
        Set<String> operations = new HashSet<>();
        operations().forEach(o -> operations.add(o.code()));
        Set<String> units = new HashSet<>();
        units().forEach(u -> units.add(u.code()));
        NwnpRateRules.defect(p.version(), p.lines(), operations, units)
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        Set<Long> designations = new HashSet<>();
        designations().forEach(d -> designations.add(d.id()));
        LabourRateRules.defect(
                        p.mgw(), designations, new Settings(null, null, null, null), p.reason())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        String code = NwnpRateRules.setCode(depot.getCode(), p.version());
        Integer pending =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rate_set WHERE rate_set_code = ? AND approval_state ="
                                + " 'PENDING_APPROVAL'",
                        Integer.class,
                        code);
        if (pending != null && pending > 0) {
            throw invalid("PENDING");
        }
        LocalDate latest =
                jdbc.queryForObject(
                        "SELECT MAX(effective_from) FROM rate_set WHERE rate_set_code = ? AND"
                                + " approval_state = 'APPROVED'",
                        LocalDate.class,
                        code);
        LabourRateRules.effectiveDefect(p.effectiveFrom(), latest)
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        Integer version =
                jdbc.queryForObject(
                        "SELECT MAX(version_no) FROM rate_set WHERE rate_set_code = ?",
                        Integer.class,
                        code);
        Date from = Date.valueOf(p.effectiveFrom());
        jdbc.update(
                "INSERT INTO rate_set (rate_set_code, rate_set_name, payroll_type, location_id,"
                        + " version_no, effective_from, approval_state) VALUES (?, ?, ?, ?, ?, ?,"
                        + " 'PENDING_APPROVAL')",
                code,
                "NWNP " + p.version() + " rates of " + depot.getCode(),
                NWNP,
                depot.getId(),
                (version == null ? 0 : version) + 1,
                from);
        Long setId =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT rate_set_id FROM rate_set WHERE rate_set_code = ? AND"
                                        + " effective_from = ?",
                                Long.class,
                                code,
                                from),
                        "The rate set just written is not found");
        for (Line l : p.lines()) {
            jdbc.update(
                    "INSERT INTO nwnp_sor_rate (rate_set_id, location_id, operation_code,"
                        + " rate_version, rate_per_unit, unit_of_measure, effective_from) VALUES"
                        + " (?, ?, ?, ?, ?, ?, ?)",
                    setId,
                    depot.getId(),
                    l.operationCode(),
                    p.version(),
                    l.pieceOfRate(),
                    l.unitOfMeasure(),
                    from);
        }
        Map<Long, String> roles = new HashMap<>();
        designations().forEach(d -> roles.put(d.id(), d.gangRole()));
        for (Rate r : p.mgw()) {
            jdbc.update(
                    "INSERT INTO labour_min_wage (engine_code, rate_set_id, designation_id,"
                        + " gang_role, location_id, min_guaranteed_wage, mgw_basis, version_no,"
                        + " effective_from) VALUES (?, ?, ?, ?, ?, ?, 'ASOR_DESIGNATION', ?, ?)",
                    NWNP,
                    setId,
                    r.designationId(),
                    roles.get(r.designationId()),
                    depot.getId(),
                    r.mgw(),
                    (version == null ? 0 : version) + 1,
                    from);
        }
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                ROUTE,
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                String.valueOf(setId),
                                ActionType.CREATE,
                                depot.getId(),
                                null,
                                p.effectiveFrom(),
                                p.reason().strip()),
                        null);
        jdbc.update("UPDATE rate_set SET approval_id = ? WHERE rate_set_id = ?", item.id(), setId);
        jdbc.update(
                "UPDATE labour_min_wage SET approval_id = ? WHERE rate_set_id = ?",
                item.id(),
                setId);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(setId),
                                "approval_state",
                                null,
                                "PENDING_APPROVAL",
                                code + " from " + p.effectiveFrom())
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(p.effectiveFrom()));
        return item;
    }

    /**
     * The Rule Approver's decision: approved, the set applies from its date and the one before
     * ends.
     */
    @EventListener
    @Transactional
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        long setId = Long.parseLong(decision.entityKey());
        List<Map<String, Object>> set =
                jdbc.queryForList(
                        "SELECT rate_set_code, effective_from, approval_state FROM rate_set WHERE"
                                + " rate_set_id = ?",
                        setId);
        if (set.isEmpty() || !"PENDING_APPROVAL".equals(set.get(0).get("APPROVAL_STATE"))) {
            return;
        }
        String state = decision.approved() ? "APPROVED" : "REJECTED";
        jdbc.update("UPDATE rate_set SET approval_state = ? WHERE rate_set_id = ?", state, setId);
        if (decision.approved()) {
            LocalDate from =
                    new java.sql.Date(((java.util.Date) set.get(0).get("EFFECTIVE_FROM")).getTime())
                            .toLocalDate();
            Date end = Date.valueOf(from.minusDays(1));
            List<Long> before =
                    jdbc.queryForList(
                            "SELECT rate_set_id FROM rate_set WHERE rate_set_code = ? AND"
                                    + " approval_state = 'APPROVED' AND effective_to IS NULL AND"
                                    + " rate_set_id <> ? AND effective_from < ?",
                            Long.class,
                            set.get(0).get("RATE_SET_CODE"),
                            setId,
                            Date.valueOf(from));
            for (Long old : before) {
                jdbc.update("UPDATE rate_set SET effective_to = ? WHERE rate_set_id = ?", end, old);
                jdbc.update(
                        "UPDATE nwnp_sor_rate SET effective_to = ? WHERE rate_set_id = ?",
                        end,
                        old);
                jdbc.update(
                        "UPDATE labour_min_wage SET effective_to = ? WHERE rate_set_id = ?",
                        end,
                        old);
            }
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(setId),
                                "approval_state",
                                "PENDING_APPROVAL",
                                state,
                                decision.reason() == null ? state : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    private List<Operation> operations() {
        return jdbc.query(
                "SELECT n.operation_code, n.operation_name, o.operation_code AS m27 FROM"
                        + " nwnp_operation n LEFT JOIN operation_master o ON o.operation_id ="
                        + " n.operation_id ORDER BY n.operation_code",
                (rs, i) -> new Operation(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    private List<LabourRateService.Designation> designations() {
        return jdbc.query(
                "SELECT designation_id, designation_code, designation_name, gang_role_code FROM"
                        + " org_designation WHERE designation_family = ? ORDER BY designation_code",
                (rs, i) ->
                        new LabourRateService.Designation(
                                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                NWNP);
    }

    private List<Unit> units() {
        Date today = Date.valueOf(parameters.today());
        return jdbc.query(
                "SELECT v.value_code, v.value_label_en FROM reference_value v JOIN reference_list l"
                    + " ON l.list_id = v.list_id WHERE l.list_code = 'NWNP_UOM' AND"
                    + " v.effective_from <= ? AND (v.effective_to IS NULL OR v.effective_to >= ?)"
                    + " ORDER BY v.sort_order, v.value_code",
                (rs, i) -> new Unit(rs.getString(1), rs.getString(2)),
                today,
                today);
    }

    private List<RateLine> lines(long setId) {
        return jdbc.query(
                "SELECT r.operation_code, n.operation_name, r.rate_per_unit, r.unit_of_measure,"
                        + " (SELECT MAX(v.value_label_en) FROM reference_value v JOIN"
                        + " reference_list l ON l.list_id = v.list_id WHERE l.list_code ="
                        + " 'NWNP_UOM' AND v.value_code = r.unit_of_measure) FROM nwnp_sor_rate r"
                        + " JOIN nwnp_operation n ON n.operation_code = r.operation_code WHERE"
                        + " r.rate_set_id = ? ORDER BY r.operation_code",
                (rs, i) ->
                        new RateLine(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getBigDecimal(3),
                                rs.getString(4),
                                rs.getString(5)),
                setId);
    }

    private List<Mgw> mgw(long setId) {
        return jdbc.query(
                "SELECT w.designation_id, d.designation_code, d.designation_name, w.gang_role,"
                        + " w.min_guaranteed_wage FROM labour_min_wage w JOIN org_designation d ON"
                        + " d.designation_id = w.designation_id WHERE w.rate_set_id = ? ORDER BY"
                        + " d.designation_code",
                (rs, i) ->
                        new Mgw(
                                rs.getLong(1),
                                rs.getString(2) + " - " + rs.getString(3),
                                rs.getString(4),
                                rs.getBigDecimal(5)),
                setId);
    }

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.RATE, new Object[] {defect}, List.of(), null);
    }
}
