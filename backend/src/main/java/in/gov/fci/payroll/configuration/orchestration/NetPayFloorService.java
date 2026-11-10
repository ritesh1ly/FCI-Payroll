package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.FieldChanges;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.MasterImages;
import in.gov.fci.payroll.configuration.business.MasterText;
import in.gov.fci.payroll.configuration.business.PayrollType;
import in.gov.fci.payroll.configuration.business.VersionChain;
import in.gov.fci.payroll.configuration.business.model.NetPayFloorRule;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * M-14 Penalty &amp; Recovery Rule Configuration (SRS-16-13; DDL v4.0 {@code net_pay_floor_rule},
 * {@code pay_component.supp_excluded_flag}): the net-pay floor of each payroll type,
 * effective-dated with no overlap, and the pay codes the supplementary run skips. The HQ Rule
 * Administrator proposes (routes M14_CREATE, M14_EDIT), a Payroll Checker approves; nothing is in
 * force until then. No floor is seeded: a type without an approved floor is shown as not
 * configured, and an unconfirmed one as such (values are settings seeded from the current system,
 * DDL comment). The engines apply the floor and quarantine a payslip below it to C-08 (modules 05 /
 * 16).
 */
@Service
@Loggable
public class NetPayFloorService {

    static final String SCREEN = "M-14";
    public static final String ENTITY = "net_pay_floor_rule";

    /** The approval entity of an exclusion-code change; its key is constant (one list). */
    public static final String EXCLUSIONS = "supp_exclusion_codes";

    static final String EXCLUSIONS_KEY = "SUPPLEMENTARY";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record FloorVersion(
            Long id,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String floorBasis,
            BigDecimal floorValue,
            BigDecimal deductionCeilingPct,
            String sourceInstrument,
            boolean confirmed,
            String status,
            Long approvalId) {}

    public record FloorRow(String payrollType, FloorVersion current, FloorVersion pending) {}

    public record ProposedFloor(
            LocalDate effectiveFrom,
            String floorBasis,
            BigDecimal floorValue,
            BigDecimal deductionCeilingPct,
            String sourceInstrument,
            boolean confirmed,
            String reason) {}

    /** A catalogue entry as the exclusion list shows it. */
    public record ExclusionRow(Integer payCode, String name, String type, boolean excluded) {}

    /** The exclusion list, with the change awaiting the checker if any. */
    public record Exclusions(
            List<ExclusionRow> components, List<Integer> pending, Long approvalId) {}

    /** The proposed exclusion list travels in the approval item until approved. */
    public record ExclusionProposal(List<Integer> payCodes) {}

    private final ConfigurationRepositories.NetPayFloors floors;
    private final ConfigurationRepositories.PayComponents components;
    private final ApprovalStatuses statuses;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final SystemParameters settings;
    private final MasterAudit masterAudit;
    private final AuditService audit;
    private final ObjectMapper json;
    private final NamedParameterJdbcTemplate named;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public NetPayFloorService(
            ConfigurationRepositories.NetPayFloors floors,
            ConfigurationRepositories.PayComponents components,
            ApprovalStatuses statuses,
            MakerChecker approvals,
            Authorization policy,
            SystemParameters settings,
            MasterAudit masterAudit,
            AuditService audit,
            ObjectMapper json,
            NamedParameterJdbcTemplate named) {
        this.named = named;
        this.floors = floors;
        this.components = components;
        this.statuses = statuses;
        this.approvals = approvals;
        this.policy = policy;
        this.settings = settings;
        this.masterAudit = masterAudit;
        this.audit = audit;
        this.json = json;
    }

    /**
     * The drawn tile Min-Net Guard Triggered (I-06) of M-14 (API-04-14): the recoveries allowed
     * below a floor and the requests waiting on the floor route, at the session's offices.
     */
    @Transactional(readOnly = true)
    public long guardTriggered(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = OfficeScope.of(context, policy.isAtHeadquarters(context));
        String scope = " (:all = 'Y' OR e.location_id IN (:locations))";
        return RowCounts.of(
                named.queryForObject(
                        "SELECT (SELECT COUNT(*) FROM net_pay_floor_override f JOIN employee e ON"
                                + " e.employee_id = f.employee_id WHERE"
                                + scope
                                + ") + (SELECT COUNT(*) FROM approval_queue q JOIN"
                                + " approval_workflow_action w ON w.workflow_action_id ="
                                + " q.workflow_action_id JOIN employee e ON e.employee_id = CASE"
                                + " WHEN q.entity_name = 'penalty_order_request' AND"
                                + " INSTR(q.entity_key, '|') > 1 THEN CAST(SUBSTR(q.entity_key, 1,"
                                + " INSTR(q.entity_key, '|') - 1) AS NUMBER(19)) END WHERE"
                                + " w.action_code = 'I06_FLOOR' AND q.status IN"
                                + " ('PENDING_APPROVAL', 'PENDING_ATTACHMENT') AND"
                                + scope
                                + ") FROM dual",
                        p,
                        Long.class));
    }

    /** SRS-16-13: one row per payroll type, with the floor in force today and any pending one. */
    @Transactional(readOnly = true)
    public List<FloorRow> list(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        LocalDate today = settings.today();
        List<FloorRow> rows = new ArrayList<>();
        for (PayrollType type : PayrollType.values()) {
            VersionChain<NetPayFloorRule> chain = chain(type.name());
            rows.add(
                    new FloorRow(
                            type.name(),
                            chain.inForceOn(today).map(r -> view(r, chain)).orElse(null),
                            chain.pending().map(r -> view(r, chain)).orElse(null)));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<FloorVersion> history(AccessContext context, String payrollType) {
        policy.require(context, SCREEN, "VIEW");
        String type = type(payrollType);
        List<NetPayFloorRule> versions = floors.findByPayrollTypeOrderByEffectiveFromDesc(type);
        VersionChain<NetPayFloorRule> chain = chain(versions);
        return versions.stream().map(r -> view(r, chain)).toList();
    }

    /**
     * SRS-16-13: proposes a floor version for one payroll type; rejected when it starts on or
     * before an approved version (no overlap) or on a date another version holds.
     */
    @Transactional
    public FloorVersion propose(
            AccessContext context, String payrollType, ProposedFloor request, OtpProof otp) {
        String type = type(payrollType);
        if (request == null || request.effectiveFrom() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        NetPayFloorRule.Basis basis = validate(request);
        String instrument =
                MasterText.optionalText("m14.sourceInstrument", request.sourceInstrument(), 300);
        List<NetPayFloorRule> versions = floors.findByPayrollTypeOrderByEffectiveFromDesc(type);
        chain(versions).requireFreeDate(request.effectiveFrom());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                type,
                                ActionType.CREATE,
                                hqLocation(context),
                                "{\"payrollType\":\""
                                        + type
                                        + "\",\"from\":\""
                                        + request.effectiveFrom()
                                        + "\"}",
                                request.effectiveFrom(),
                                request.reason()),
                        otp);
        NetPayFloorRule saved =
                floors.save(
                        NetPayFloorRule.propose(
                                type,
                                basis,
                                basis == NetPayFloorRule.Basis.NONE ? null : request.floorValue(),
                                request.deductionCeilingPct(),
                                instrument,
                                request.confirmed(),
                                request.effectiveFrom(),
                                item.id()));
        return view(saved, new VersionChain<>(List.of(saved), Map.of(item.id(), item.status())));
    }

    /**
     * SRS-16-13: a basis from the DDL list; an amount or percentage of zero or more (a percentage
     * at most 100) unless the basis is NONE; the combined-deduction ceiling, if given, 0 … 100.
     */
    static NetPayFloorRule.Basis validate(ProposedFloor p) {
        NetPayFloorRule.Basis basis = basis(p.floorBasis());
        BigDecimal value = p.floorValue();
        boolean valueOk =
                basis == NetPayFloorRule.Basis.NONE
                        || (value != null
                                && value.signum() >= 0
                                && value.scale() <= 2
                                && value.precision() - value.scale() <= 10
                                && (basis != NetPayFloorRule.Basis.PERCENT_OF_GROSS
                                        || value.compareTo(HUNDRED) <= 0));
        BigDecimal ceiling = p.deductionCeilingPct();
        boolean ceilingOk =
                ceiling == null
                        || (ceiling.signum() >= 0
                                && ceiling.compareTo(HUNDRED) <= 0
                                && ceiling.scale() <= 4);
        if (!valueOk || !ceilingOk) {
            throw new FciException(ApiErrorCode.MASTER_INVALID_FLOOR);
        }
        return basis;
    }

    private static NetPayFloorRule.Basis basis(String code) {
        for (NetPayFloorRule.Basis b : NetPayFloorRule.Basis.values()) {
            if (b.name().equals(code)) {
                return b;
            }
        }
        throw new FciException(ApiErrorCode.MASTER_INVALID_FLOOR);
    }

    /**
     * SRS-16-13: the pay codes of the component catalogue and which the supplementary run skips.
     */
    @Transactional(readOnly = true)
    public Exclusions exclusions(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        LocalDate today = settings.today();
        List<ExclusionRow> rows =
                components.findAllByOrderByLegacyPayCode().stream()
                        .filter(c -> c.isActiveOn(today))
                        .map(
                                c ->
                                        new ExclusionRow(
                                                c.getLegacyPayCode(),
                                                c.getComponentName(),
                                                c.getComponentType(),
                                                c.isSuppExcludedFlag()))
                        .toList();
        List<ApprovalStatuses.Pending> pending = statuses.pending(EXCLUSIONS);
        if (pending.isEmpty()) {
            return new Exclusions(rows, null, null);
        }
        ApprovalStatuses.Pending newest = pending.get(0);
        return new Exclusions(rows, read(newest.payload()).payCodes(), newest.approvalId());
    }

    /**
     * SRS-16-13: proposes the whole exclusion list; every code must be an active M-07 catalogue
     * entry (MASTER_COMPONENT_INVALID). Takes effect on approval.
     */
    @Transactional
    public Exclusions proposeExclusions(
            AccessContext context, List<Integer> payCodes, String reason, OtpProof otp) {
        policy.require(context, SCREEN, "EDIT");
        if (payCodes == null || payCodes.stream().anyMatch(java.util.Objects::isNull)) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        Set<Integer> codes = new TreeSet<>(payCodes);
        requireCatalogued(codes, settings.today());
        String payload = json.writeValueAsString(new ExclusionProposal(List.copyOf(codes)));
        approvals.submit(
                context,
                new Submission(
                        SCREEN,
                        "EDIT",
                        EXCLUSIONS,
                        EXCLUSIONS_KEY,
                        ActionType.UPDATE,
                        hqLocation(context),
                        payload,
                        null,
                        reason),
                otp);
        return exclusions(context);
    }

    private void requireCatalogued(Set<Integer> codes, LocalDate day) {
        Map<Integer, PayComponent> found = new LinkedHashMap<>();
        components.findByLegacyPayCodeIn(codes).forEach(c -> found.put(c.getLegacyPayCode(), c));
        for (Integer code : codes) {
            PayComponent c = found.get(code);
            if (c == null || !c.isActiveOn(day)) {
                throw new FciException(
                        ApiErrorCode.MASTER_COMPONENT_INVALID,
                        new Object[] {code},
                        List.of(),
                        null);
            }
        }
    }

    /**
     * The checker's final decision. An approved floor ends the version it replaces the day before
     * (SRS-16-13: one active version per type per date); an approved exclusion list sets each
     * component's flag. Audited in the decision's transaction (SRS-20-2).
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (decision.concerns(ENTITY) && decision.approved()) {
            floors.findByApprovalId(decision.approvalId()).ifPresent(r -> applyFloor(decision, r));
        } else if (decision.concerns(EXCLUSIONS) && decision.approved()) {
            applyExclusions(decision);
        }
    }

    private void applyFloor(ApprovalDecided decision, NetPayFloorRule proposed) {
        List<NetPayFloorRule> closed =
                chain(proposed.getPayrollType()).closeBefore(proposed, floors::save);
        floors.flush();
        masterAudit.applied(
                decision,
                ENTITY,
                proposed.getPayrollType(),
                SCREEN,
                proposed.getEffectiveFrom(),
                closed.isEmpty() ? null : MasterImages.netPayFloor(closed.get(0)),
                MasterImages.netPayFloor(proposed),
                closed.stream().map(NetPayFloorRule::getEffectiveTo).toList(),
                decision.reason());
    }

    private void applyExclusions(ApprovalDecided decision) {
        Set<Integer> wanted = new TreeSet<>(read(decision.payload()).payCodes());
        String why = decision.reason() == null ? "Approved" : decision.reason();
        List<AuditService.Entry> entries = new ArrayList<>();
        for (PayComponent c : components.findAllByOrderByLegacyPayCode()) {
            boolean excluded = wanted.contains(c.getLegacyPayCode());
            if (c.isSuppExcludedFlag() != excluded) {
                Map<String, String> before = Map.of("supp_excluded_flag", yn(!excluded));
                c.setSuppExcluded(excluded);
                components.save(c);
                entries.addAll(
                        FieldChanges.between(
                                "pay_component",
                                String.valueOf(c.getLegacyPayCode()),
                                before,
                                Map.of("supp_excluded_flag", yn(excluded)),
                                why));
            }
        }
        audit.recordAll(
                decision.checkerLoginId(),
                entries.stream()
                        .map(e -> e.approval(decision.approvalId()).module(SCREEN))
                        .toList());
    }

    private ExclusionProposal read(String payload) {
        try {
            return json.readValue(payload, ExclusionProposal.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("unreadable exclusion proposal", ex);
        }
    }

    private static String type(String code) {
        PayrollType type = PayrollType.of(code);
        if (type == null) {
            throw new FciException(ApiErrorCode.MASTER_NOT_IN_CATALOGUE);
        }
        return type.name();
    }

    private Long hqLocation(AccessContext context) {
        return policy.isAtHeadquarters(context) ? context.locationId() : null;
    }

    private VersionChain<NetPayFloorRule> chain(String type) {
        return chain(floors.findByPayrollTypeOrderByEffectiveFromDesc(type));
    }

    private VersionChain<NetPayFloorRule> chain(List<NetPayFloorRule> versions) {
        return new VersionChain<>(versions, statuses.of(VersionChain.approvalIds(versions)));
    }

    private static String yn(boolean value) {
        return value ? "Y" : "N";
    }

    private static FloorVersion view(NetPayFloorRule r, VersionChain<NetPayFloorRule> chain) {
        var s = chain.statusOf(r);
        return new FloorVersion(
                r.getFloorRuleId(),
                r.getEffectiveFrom(),
                r.getEffectiveTo(),
                r.getFloorBasis(),
                r.getFloorValue(),
                r.getDeductionCeilingPct(),
                r.getSourceInstrument(),
                r.isIsConfirmed(),
                s == null ? "NONE" : s.name(),
                r.getApprovalId());
    }
}
