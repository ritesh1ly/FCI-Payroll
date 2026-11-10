package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.LabourRateRules;
import in.gov.fci.payroll.labour.business.LabourRateRules.Rate;
import in.gov.fci.payroll.labour.business.LabourRateRules.Settings;
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
 * M-05 Labour Rate Tables (plan 21 phase 21c; SRS-4-11, SRS-4-2, SRS-3-13; DDL {@code
 * labour_min_wage}; D21-4): the designation-wise Minimum Guaranteed Wage day rates of a labour
 * payroll as effective-dated sets, for all depots or one (OQ-14 (v0.4) open), with the Interim
 * Relief, the GoI rate and its source and the comparison grain as settings. Only Headquarters sets
 * labour rates: the Rule Administrator proposes a set dated after the latest one and the Rule
 * Approver approves it (route M05_LABOUR_RATE); approved, it is in force from its date and the set
 * before ends the day before; a rejected set is listed and never used. The DPS payroll first
 * (module 21); the Departmental Labour sets hold the designation-wise Basic wage and DA instead
 * (SRS-3-13, US-GAP-61; DDL {@code basic_wage}, {@code vda}), unseeded until FCI gives the values
 * (OQ-80); the NWNP rates have their own sets ({@link NwnpRateService}).
 */
@Service
@Loggable
public class LabourRateService {

    static final String SCREEN = "M-05";
    static final String ENTITY = "labour_min_wage";
    static final String ROUTE = "M05_LABOUR_RATE";
    private static final Set<String> ENGINES = Set.of("DPS_LABOUR", "DEPT_LABOUR");

    /** SRS-3-13 (US-GAP-61): the Departmental Labour sets hold the Basic wage and DA. */
    static final String DEPT = "DEPT_LABOUR";

    /**
     * One designation's rates of a set: the Minimum Guaranteed Wage day rate (DPS), or the Basic
     * wage and DA (Departmental).
     */
    public record DayRate(
            long designationId,
            String designation,
            String gangRole,
            BigDecimal mgw,
            BigDecimal basicWage,
            BigDecimal da) {}

    /** A rate set as M-05 lists it. */
    public record RateSet(
            long id,
            String payrollType,
            Long depotId,
            String depot,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            BigDecimal interimRelief,
            BigDecimal goiRate,
            String goiRateSource,
            String comparisonGrain,
            String approvalState,
            List<DayRate> rates) {}

    /** A designation the set gives a rate to. */
    public record Designation(long id, String code, String name, String gangRole) {}

    /** A proposed set. */
    public record Proposal(
            String payrollType,
            Long depotLocationId,
            LocalDate effectiveFrom,
            List<Rate> rates,
            BigDecimal interimRelief,
            BigDecimal goiRate,
            String goiRateSource,
            String comparisonGrain,
            String reason) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final OrgDirectory org;
    private final MakerChecker approvals;
    private final AuditService audit;

    public LabourRateService(
            JdbcTemplate jdbc,
            Authorization policy,
            OrgDirectory org,
            MakerChecker approvals,
            AuditService audit) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.org = org;
        this.approvals = approvals;
        this.audit = audit;
    }

    /** M-05: the payroll type's rate sets, newest first; paged. */
    @Transactional(readOnly = true)
    public Paged<RateSet> page(AccessContext context, String payrollType, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        String engine = engine(payrollType);
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT DISTINCT location_id, effective_from FROM"
                                + " labour_min_wage WHERE engine_code = ?) s",
                        Long.class,
                        engine);
        List<RateSet> rows =
                jdbc.query(
                        "SELECT MIN(w.min_wage_id) AS id, w.location_id, w.effective_from,"
                            + " MAX(w.effective_to) AS effective_to, MAX(w.version_no) AS"
                            + " version_no, MAX(w.interim_relief) AS interim_relief,"
                            + " MAX(w.goi_rate) AS goi_rate, MAX(w.goi_rate_source) AS"
                            + " goi_rate_source, MAX(w.comparison_grain) AS comparison_grain,"
                            + " MAX(q.status) AS status FROM labour_min_wage w LEFT JOIN"
                            + " approval_queue q ON q.approval_id = w.approval_id WHERE"
                            + " w.engine_code = ? GROUP BY w.location_id, w.effective_from ORDER BY"
                            + " w.effective_from DESC, MIN(w.min_wage_id) DESC OFFSET ? ROWS FETCH"
                            + " NEXT ? ROWS ONLY",
                        (rs, i) -> {
                            long location = rs.getLong("location_id");
                            Long depot = rs.wasNull() ? null : location;
                            java.sql.Date to = rs.getDate("effective_to");
                            LocalDate from = rs.getDate("effective_from").toLocalDate();
                            String state = rs.getString("status");
                            return new RateSet(
                                    rs.getLong("id"),
                                    engine,
                                    depot,
                                    depot == null
                                            ? null
                                            : org.location(depot)
                                                    .map(l -> l.getCode() + " - " + l.getName())
                                                    .orElse(null),
                                    rs.getInt("version_no"),
                                    from,
                                    to == null ? null : to.toLocalDate(),
                                    rs.getBigDecimal("interim_relief"),
                                    rs.getBigDecimal("goi_rate"),
                                    rs.getString("goi_rate_source"),
                                    rs.getString("comparison_grain"),
                                    state == null ? "APPROVED" : state,
                                    rates(engine, depot, from));
                        },
                        engine,
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** M-05: the designations of the payroll type, each needing its day rate. */
    @Transactional(readOnly = true)
    public List<Designation> designations(AccessContext context, String payrollType) {
        policy.require(context, SCREEN, "VIEW");
        return designationsOf(engine(payrollType));
    }

    /** M-05: a new rate set for the Rule Approver (SRS-4-11). */
    @Transactional
    public SubmittedApproval propose(AccessContext context, Proposal p) {
        if (p == null) {
            throw invalid("PROPOSAL");
        }
        String engine = engine(p.payrollType());
        OrgLocation hq = org.headquarters().orElseThrow(() -> invalid("HEADQUARTERS"));
        Long depot = p.depotLocationId();
        if (depot != null
                && org.location(depot).filter(l -> "DEPOT".equals(l.getType().name())).isEmpty()) {
            throw invalid("DEPOT");
        }
        policy.requireAt(context, SCREEN, "CREATE", depot == null ? hq.getId() : depot);
        Set<Long> designations = new HashSet<>();
        designationsOf(engine).forEach(d -> designations.add(d.id()));
        Settings settings =
                new Settings(
                        p.interimRelief(), p.goiRate(), p.goiRateSource(), p.comparisonGrain());
        (DEPT.equals(engine)
                        ? LabourRateRules.deptDefect(p.rates(), designations, settings, p.reason())
                        : LabourRateRules.defect(p.rates(), designations, settings, p.reason()))
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        long key = depot == null ? -1L : depot;
        Integer pending =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_min_wage w JOIN approval_queue q ON"
                                + " q.approval_id = w.approval_id WHERE w.engine_code = ? AND"
                                + " COALESCE(w.location_id, -1) = ? AND q.status ="
                                + " 'PENDING_APPROVAL'",
                        Integer.class,
                        engine,
                        key);
        if (pending != null && pending > 0) {
            throw invalid("PENDING");
        }
        LocalDate latest =
                jdbc.queryForObject(
                        "SELECT MAX(w.effective_from) FROM labour_min_wage w LEFT JOIN"
                                + " approval_queue q ON q.approval_id = w.approval_id WHERE"
                                + " w.engine_code = ? AND COALESCE(w.location_id, -1) = ? AND"
                                + " (q.status IS NULL OR q.status = 'APPROVED')",
                        LocalDate.class,
                        engine,
                        key);
        LabourRateRules.effectiveDefect(p.effectiveFrom(), latest)
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        Integer version =
                jdbc.queryForObject(
                        "SELECT MAX(version_no) FROM labour_min_wage WHERE engine_code = ? AND"
                                + " COALESCE(location_id, -1) = ?",
                        Integer.class,
                        engine,
                        key);
        int next = (version == null ? 0 : version) + 1;
        Map<Long, String> roles = new java.util.HashMap<>();
        designationsOf(engine).forEach(d -> roles.put(d.id(), d.gangRole()));
        String source =
                p.goiRateSource() == null || p.goiRateSource().isBlank()
                        ? null
                        : p.goiRateSource().strip();
        for (Rate r : p.rates()) {
            jdbc.update(
                    "INSERT INTO labour_min_wage (engine_code, designation_id, gang_role,"
                            + " location_id, min_guaranteed_wage, basic_wage, vda,"
                            + " interim_relief, goi_rate, goi_rate_source, comparison_grain,"
                            + " version_no, effective_from) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?, ?, ?)",
                    engine,
                    r.designationId(),
                    roles.get(r.designationId()),
                    depot,
                    DEPT.equals(engine) ? null : r.mgw(),
                    DEPT.equals(engine) ? r.basicWage() : null,
                    DEPT.equals(engine) ? r.da() : null,
                    p.interimRelief(),
                    p.goiRate(),
                    source,
                    p.comparisonGrain(),
                    next,
                    Date.valueOf(p.effectiveFrom()));
        }
        Long first =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT MIN(min_wage_id) FROM labour_min_wage WHERE engine_code = ?"
                                    + " AND COALESCE(location_id, -1) = ? AND effective_from = ?",
                                Long.class,
                                engine,
                                key,
                                Date.valueOf(p.effectiveFrom())),
                        "The rate set just written is not found");
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                ROUTE,
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                String.valueOf(first),
                                ActionType.CREATE,
                                hq.getId(),
                                null,
                                p.effectiveFrom(),
                                p.reason().strip()),
                        null);
        jdbc.update(
                "UPDATE labour_min_wage SET approval_id = ? WHERE engine_code = ? AND"
                        + " COALESCE(location_id, -1) = ? AND effective_from = ?",
                item.id(),
                engine,
                key,
                Date.valueOf(p.effectiveFrom()));
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(first),
                                "approval_state",
                                null,
                                "PENDING_APPROVAL",
                                engine + " rates from " + p.effectiveFrom())
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
        List<Map<String, Object>> set =
                jdbc.queryForList(
                        "SELECT engine_code, location_id, effective_from FROM labour_min_wage"
                                + " WHERE approval_id = ?",
                        decision.approvalId());
        if (set.isEmpty()) {
            return;
        }
        String state = decision.approved() ? "APPROVED" : "REJECTED";
        if (decision.approved()) {
            Map<String, Object> s = set.get(0);
            LocalDate from =
                    new java.sql.Date(((java.util.Date) s.get("EFFECTIVE_FROM")).getTime())
                            .toLocalDate();
            Object location = s.get("LOCATION_ID");
            jdbc.update(
                    "UPDATE labour_min_wage SET effective_to = ? WHERE engine_code = ? AND"
                            + " COALESCE(location_id, -1) = ? AND effective_to IS NULL AND"
                            + " effective_from < ? AND (approval_id IS NULL OR approval_id IN"
                            + " (SELECT approval_id FROM approval_queue WHERE status ="
                            + " 'APPROVED'))",
                    Date.valueOf(from.minusDays(1)),
                    s.get("ENGINE_CODE"),
                    location == null ? -1L : ((Number) location).longValue(),
                    Date.valueOf(from));
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                decision.entityKey(),
                                "approval_state",
                                "PENDING_APPROVAL",
                                state,
                                decision.reason() == null ? state : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    private List<Designation> designationsOf(String engine) {
        return jdbc.query(
                "SELECT designation_id, designation_code, designation_name, gang_role_code FROM"
                        + " org_designation WHERE designation_family = ? ORDER BY"
                        + " designation_code",
                (rs, i) ->
                        new Designation(
                                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                engine);
    }

    private List<DayRate> rates(String engine, Long depot, LocalDate from) {
        return jdbc.query(
                "SELECT w.designation_id, d.designation_code, d.designation_name, w.gang_role,"
                        + " w.min_guaranteed_wage, w.basic_wage, w.vda FROM labour_min_wage w"
                        + " LEFT JOIN"
                        + " org_designation d ON d.designation_id = w.designation_id WHERE"
                        + " w.engine_code = ? AND COALESCE(w.location_id, -1) = ? AND"
                        + " w.effective_from = ? ORDER BY d.designation_code",
                (rs, i) ->
                        new DayRate(
                                rs.getLong(1),
                                rs.getString(2) + " - " + rs.getString(3),
                                rs.getString(4),
                                rs.getBigDecimal(5),
                                rs.getBigDecimal(6),
                                rs.getBigDecimal(7)),
                engine,
                java.util.Objects.requireNonNullElse(depot, -1L),
                Date.valueOf(from));
    }

    private static String engine(String payrollType) {
        if (payrollType == null || !ENGINES.contains(payrollType)) {
            throw invalid("PAYROLL_TYPE");
        }
        return payrollType;
    }

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.RATE, new Object[] {defect}, List.of(), null);
    }
}
