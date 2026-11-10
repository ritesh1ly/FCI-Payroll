package in.gov.fci.payroll.configuration.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.MasterText;
import in.gov.fci.payroll.configuration.business.RuleProposals;
import in.gov.fci.payroll.configuration.business.RuleValueShape;
import in.gov.fci.payroll.configuration.business.RuleVersionStatus;
import in.gov.fci.payroll.configuration.business.SlabBands;
import in.gov.fci.payroll.configuration.business.model.CircularOrder;
import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.business.model.RuleSlabBand;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * M-08 Rule / Parameter Umbrella Catalogue (SRS-19-1, SRS-19-4 … 6, SRS-19-11, SRS-19-12,
 * SRS-19-14, SRS-19-17, SRS-19-21; FRS V0.5 BR-RULE-001 … 005; KB RE-1 … RE-5, RE-8, RE-9, RE-14):
 * the rules of the COMPUTATION, STATUTORY_OUTPUT and REPORT domains (plan 06 D-2; A-07 keeps the
 * settings) with their versions and derived status, and the pending approvals of M-08, M-14 and
 * M-15.
 *
 * <p><b>Proposals.</b> The Rule Administrator at Headquarters proposes a version (effective-from
 * date, the value its type takes, the Active circular it rests on) or an explicit end date; the
 * Rule Approver, never the proposer, decides it in the approval inbox (workflow actions M08_CREATE
 * / M08_EDIT, HQ, two levels). A proposal lives only in its approval item ({@code
 * approval_queue.payload}, the DDL's "proposed new state, JSON") until it is approved; only then is
 * the version written ({@link RuleCatalogueApprovalEffect}). So a returned proposal leaves no row
 * and never holds its effective date (uk_rule_version_eff covers every row; plan 06 D-7).
 *
 * <p><b>Access</b> (SRS-19-14, R-RBAC): VIEW to read (Rule Administrator, Rule Approver, Payroll
 * Checker, Finance); CREATE for a rule's first version and EDIT afterwards (Rule Administrator);
 * every check is made before the rule is read or locked, and again by the workflow on submission. A
 * key M-08 does not list (unknown, an A-07 setting) answers not found, whoever asks.
 */
@Service
@Loggable
public class RuleCatalogueService {

    public static final String MODULE = "M-08";

    /** The two rates of M-18 that M-19's CPF / VPF Parameters table draws. */
    private static final Set<String> CONTRIBUTION_RATES =
            Set.of("CPF.RATE.EMPLOYEE", "CPF.RATE.EMPLOYER");

    /** A new version of a rule ({@code rule_version}). */
    public static final String ENTITY = "rule_version";

    /** An explicit end date with no successor. */
    public static final String END_ENTITY = "rule_version_end";

    /** The domains M-08 lists (plan 06 D-2). */
    static final Set<RuleDefinition.Domain> DOMAINS =
            EnumSet.of(
                    RuleDefinition.Domain.COMPUTATION,
                    RuleDefinition.Domain.STATUTORY_OUTPUT,
                    RuleDefinition.Domain.REPORT);

    /** A-07 maintains the SETTING rules, also those of the COMPUTATION domain. */
    static final String SETTING = "SETTING";

    static final int REASON_MAX = 500;

    public record Band(
            int order,
            BigDecimal from,
            BigDecimal to,
            BigDecimal rateOrAmount,
            BigDecimal step,
            String qualifier) {}

    /**
     * One version; {@code status} is derived as at today (SRS-19-11, CF-28). A pending proposal has
     * no {@code id} yet: it is written only on approval. FRS §24.9: each version records its author
     * ({@code createdBy}), its approver ({@code approvedBy}, the Rule Approver who decided its
     * approval item; null for a pending proposal and for a version seeded without one), its
     * effective-from date and the version it supersedes ({@code supersedesVersionNo}: the approved
     * version its approval ended the day before it started; null when it superseded none).
     */
    public record VersionView(
            Long id,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            BigDecimal value,
            String formula,
            List<Band> bands,
            String roundingRuleKey,
            Long circularId,
            String circularNumber,
            boolean blocked,
            String blockedReason,
            String createdBy,
            Instant createdAt,
            Long approvalId,
            String approvedBy,
            Integer supersedesVersionNo) {}

    /** An end date awaiting the Rule Approver. */
    public record PendingEnd(
            Long approvalId,
            int versionNo,
            LocalDate endOn,
            String requestedBy,
            Instant requestedAt) {}

    /**
     * One rule; {@code owningModule} is the screen that shows it as its own register (M-18
     * statutory rates, M-19 CPF parameters; DDL {@code rule_definition.owning_module}), or null.
     */
    public record RuleRow(
            String key,
            String type,
            String domain,
            String name,
            String description,
            String payrollTypeScope,
            Long officeScopeId,
            String officeScopeName,
            String owningModule,
            boolean orderRequired,
            boolean active,
            boolean maintained,
            String valueShape,
            VersionView inForce,
            VersionView pending,
            PendingEnd pendingEnd,
            int versions) {}

    /**
     * The wireframe tiles: rules by type, rule changes pending, M-14 / M-15 changes pending, and
     * active rules with no version in force ("seed values missing": never filled with a guess,
     * R-RULE).
     */
    public record Summary(
            int rules,
            Map<String, Integer> byType,
            int pendingRuleChanges,
            int pendingOtherChanges,
            int notConfigured) {}

    public record Catalogue(LocalDate asOf, Summary summary, List<RuleRow> rules) {}

    /** One change awaiting its decision, on M-08, M-14 or M-15 (SRS-19-21). */
    public record PendingItem(
            String screen,
            String entity,
            String key,
            Long approvalId,
            String submittedBy,
            Instant submittedAt) {}

    public record VersionRequest(
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            BigDecimal value,
            String formula,
            List<Band> bands,
            String roundingRuleKey,
            Long circularId,
            String reason) {}

    public record EndRequest(LocalDate endOn, String reason) {}

    /**
     * A version proposal as its approval item carries it (the approver sees exactly what will be
     * written); built only from validated fields, never from client JSON. Read back tolerantly: a
     * pending payload written by an earlier release still reads after a field is retired.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VersionProposal(
            String ruleKey,
            String ruleType,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            BigDecimal value,
            String formula,
            List<Band> bands,
            String roundingRuleKey,
            Long circularId,
            String proposedBy,
            Instant proposedAt) {

        /**
         * A version without bands carries an empty list, never null (one place, not every reader).
         */
        public VersionProposal {
            bands = bands == null ? List.of() : List.copyOf(bands);
        }
    }

    /** An end-date proposal as its approval item carries it (read back tolerantly, as above). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndProposal(
            String ruleKey,
            Long versionId,
            int versionNo,
            LocalDate endOn,
            String proposedBy,
            Instant proposedAt) {}

    private final ConfigurationRepositories.Rules rules;
    private final ConfigurationRepositories.RuleVersions versions;
    private final ConfigurationRepositories.RuleSlabBands bands;
    private final ConfigurationRepositories.Circulars circularRows;
    private final RuleProposalChecks checks;
    private final RuleEngine engine;
    private final SystemParameters settings;
    private final OrgDirectory org;
    private final ApprovalStatuses statuses;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern, as the other master services
    public RuleCatalogueService(
            ConfigurationRepositories.Rules rules,
            ConfigurationRepositories.RuleVersions versions,
            ConfigurationRepositories.RuleSlabBands bands,
            ConfigurationRepositories.Circulars circularRows,
            RuleProposalChecks checks,
            RuleEngine engine,
            SystemParameters settings,
            OrgDirectory org,
            ApprovalStatuses statuses,
            MakerChecker approvals,
            Authorization policy,
            ObjectMapper json,
            Clock clock) {
        this.rules = rules;
        this.versions = versions;
        this.bands = bands;
        this.circularRows = circularRows;
        this.checks = checks;
        this.engine = engine;
        this.settings = settings;
        this.org = org;
        this.statuses = statuses;
        this.approvals = approvals;
        this.policy = policy;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /** SRS-19-1 / SRS-19-21: every rule M-08 lists, with today's version and any pending change. */
    @Transactional(readOnly = true)
    public Catalogue catalogue(AccessContext context) {
        policy.require(context, MODULE, "VIEW");
        return catalogue();
    }

    /**
     * The rules a register screen shows as its own (M-18 SRS-6-47, M-19 SRS M-19), for a reader of
     * that screen: the rows of {@code rule_definition.owning_module}, and on M-19 the two CPF
     * contribution rates its parameter table draws (held on M-18). Read only, on the screen's VIEW:
     * M-08 VIEW is not needed and proposals stay on M-08's rights.
     */
    @Transactional(readOnly = true)
    public List<RuleRow> register(AccessContext context, String screen) {
        policy.require(context, screen, "VIEW");
        return catalogue().rules().stream()
                .filter(
                        r ->
                                screen.equals(r.owningModule())
                                        || ("M-19".equals(screen)
                                                && CONTRIBUTION_RATES.contains(r.key())))
                .toList();
    }

    private Catalogue catalogue() {
        LocalDate today = settings.today();
        List<RuleDefinition> listed = listed();
        Map<Long, List<RuleVersion>> byRule = new HashMap<>();
        for (RuleDefinition r : listed) {
            byRule.put(r.getId(), new ArrayList<>());
        }
        if (!byRule.isEmpty()) {
            for (RuleVersion.State s : RuleVersion.State.values()) {
                for (RuleVersion v : versions.findByRuleIdInAndState(byRule.keySet(), s)) {
                    byRule.get(v.getRuleId()).add(v);
                }
            }
        }
        List<RuleVersion> all = byRule.values().stream().flatMap(List::stream).toList();
        Map<String, Pending<VersionProposal>> proposals = pendingVersions(keys(listed));
        Map<String, Pending<EndProposal>> ends = pendingEnds(keys(listed));
        Map<Long, List<SlabBands.Band>> slab = bandsOf(all);
        Map<Long, String> numbers =
                circularNumbers(
                        all.stream().map(RuleVersion::getCircularOrderId).toList(),
                        proposals.values().stream().map(p -> p.change().circularId()).toList());
        Map<Long, String> deciders = deciders(all);
        List<RuleRow> rows = new ArrayList<>();
        Map<String, Integer> byType = new TreeMap<>();
        int pendingRules = proposals.size() + ends.size();
        int notConfigured = 0;
        for (RuleDefinition r : listed) {
            List<RuleVersion> own = byRule.get(r.getId());
            Optional<RuleVersion> inForce =
                    own.stream().filter(v -> v.isInForceOn(today)).findFirst();
            byType.merge(r.getTypeCode(), 1, Integer::sum);
            if (r.isActive() && inForce.isEmpty()) {
                notConfigured++;
            }
            rows.add(
                    new RuleRow(
                            r.getKey(),
                            r.getTypeCode(),
                            r.getDomain().name(),
                            r.getName(),
                            r.getDescription(),
                            r.getPayrollTypeScope(),
                            r.getOrgScopeLocationId(),
                            officeName(r.getOrgScopeLocationId()),
                            r.getOwningModule(),
                            r.isOrderRequired(),
                            r.isActive(),
                            maintained(r),
                            RuleValueShape.of(r.getTypeCode()).map(Enum::name).orElse(null),
                            inForce.map(v -> view(v, own, slab, numbers, deciders, today))
                                    .orElse(null),
                            Optional.ofNullable(proposals.get(r.getKey()))
                                    .map(p -> view(p, nextVersionNo(own), numbers))
                                    .orElse(null),
                            Optional.ofNullable(ends.get(r.getKey()))
                                    .map(RuleCatalogueService::view)
                                    .orElse(null),
                            own.size()));
        }
        int pendingOther =
                statuses.pending(NetPayFloorService.ENTITY).size()
                        + statuses.pending(NetPayFloorService.EXCLUSIONS).size()
                        + statuses.pending(CeilingNormService.ENTITY).size()
                        // Wireframe tile "Pending Approval - M-14/M-15/M-20"; SRS-19-21 names M-14
                        // and M-15, and the Cross-Domain report stays as the SRS says.
                        + statuses.pending("leave_pay_parameter").size();
        return new Catalogue(
                today,
                new Summary(rows.size(), byType, pendingRules, pendingOther, notConfigured),
                rows);
    }

    /**
     * Every version of a rule, the pending proposal first and then newest first (SRS-19-3: all are
     * kept for point-in-time recomputation).
     */
    @Transactional(readOnly = true)
    public List<VersionView> versions(AccessContext context, String key) {
        policy.require(context, MODULE, "VIEW");
        RuleDefinition rule = listedRule(key);
        LocalDate today = settings.today();
        List<RuleVersion> own = versions.findByRuleIdOrderByVersionNoDesc(rule.getId());
        Optional<Pending<VersionProposal>> proposal =
                Optional.ofNullable(pendingVersions(Set.of(rule.getKey())).get(rule.getKey()));
        Map<Long, List<SlabBands.Band>> slab = bandsOf(own);
        Map<Long, String> numbers =
                circularNumbers(
                        own.stream().map(RuleVersion::getCircularOrderId).toList(),
                        proposal.map(p -> List.of(p.change().circularId())).orElse(List.of()));
        Map<Long, String> deciders = deciders(own);
        List<VersionView> out = new ArrayList<>();
        proposal.ifPresent(p -> out.add(view(p, nextVersionNo(own), numbers)));
        own.forEach(v -> out.add(view(v, own, slab, numbers, deciders, today)));
        return out;
    }

    /** SRS-19-15: what a formula may use, shown beside the formula field. */
    @Transactional(readOnly = true)
    public RuleEngine.Vocabulary vocabulary(AccessContext context) {
        policy.require(context, MODULE, "VIEW");
        return engine.vocabulary();
    }

    /** SRS-19-21: the changes awaiting a decision on M-08, M-14 and M-15, newest first. */
    @Transactional(readOnly = true)
    public List<PendingItem> pending(AccessContext context) {
        policy.require(context, MODULE, "VIEW");
        Set<String> listedKeys = keys(listed());
        List<PendingItem> out = new ArrayList<>();
        add(out, MODULE, ENTITY, listedKeys);
        add(out, MODULE, END_ENTITY, listedKeys);
        add(out, "M-14", NetPayFloorService.ENTITY, null);
        add(out, "M-14", NetPayFloorService.EXCLUSIONS, null);
        add(out, "M-15", CeilingNormService.ENTITY, null);
        out.sort(
                Comparator.comparing(
                                PendingItem::submittedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(
                                PendingItem::approvalId,
                                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    // ---------------------------------------------------------------- on-demand reports

    /** Rows of the Rule Catalogue Summary (the size check before streaming). */
    @Transactional(readOnly = true)
    public long catalogueSize(AccessContext context) {
        policy.require(context, MODULE, "EXPORT");
        return catalogue(context).rules().size();
    }

    /**
     * SRS-19-21 / FRS V0.5 §24 outputs: the Rule Catalogue Summary report on demand, "all rules in
     * the catalogue, by type and status": each rule M-08 lists, ordered by type then key, with its
     * active flag, the version in force today and its derived status (SRS-19-11), and any change
     * awaiting the Rule Approver.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void exportCatalogue(AccessContext context, ExportSink sink) throws IOException {
        policy.require(context, MODULE, "EXPORT");
        Catalogue c = catalogue(context);
        sink.header(
                "rule_type",
                "rule_key",
                "name",
                "domain",
                "payroll_type_scope",
                "office_scope",
                "owning_module",
                "active",
                "version_in_force",
                "in_force_from",
                "in_force_to",
                "in_force_status",
                "pending_version",
                "pending_end_on",
                "versions",
                "as_of");
        List<RuleRow> rows =
                c.rules().stream()
                        .sorted(Comparator.comparing(RuleRow::type).thenComparing(RuleRow::key))
                        .toList();
        for (RuleRow r : rows) {
            VersionView v = r.inForce();
            sink.row(
                    r.type(),
                    r.key(),
                    r.name(),
                    r.domain(),
                    r.payrollTypeScope(),
                    r.officeScopeName(),
                    r.owningModule(),
                    r.active() ? "Y" : "N",
                    v == null ? null : v.versionNo(),
                    v == null ? null : v.effectiveFrom(),
                    v == null ? null : v.effectiveTo(),
                    v == null ? null : v.status(),
                    r.pending() == null ? null : r.pending().versionNo(),
                    r.pendingEnd() == null ? null : r.pendingEnd().endOn(),
                    r.versions(),
                    c.asOf());
        }
        ExportSink.requireComplete(rows.size(), sink);
    }

    /** The domains the Pending Approval — Cross-Domain report counts (SRS-19-21), in order. */
    private static final List<String[]> PENDING_DOMAINS =
            List.of(
                    new String[] {MODULE, ENTITY},
                    new String[] {MODULE, END_ENTITY},
                    new String[] {"M-14", NetPayFloorService.ENTITY},
                    new String[] {"M-14", NetPayFloorService.EXCLUSIONS},
                    new String[] {"M-15", CeilingNormService.ENTITY});

    /** Rows of the Pending Approval — Cross-Domain report: one per domain. */
    public long pendingCountsSize() {
        return PENDING_DOMAINS.size();
    }

    /**
     * SRS-19-21 / FRS V0.5 §24 outputs: the Pending Approval — Cross-Domain report on demand, "live
     * pending-approval counts across M-08/M-14/M-15 for the Rule Approver": one row per kind of
     * change, a zero included, so every domain is visible.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void exportPendingCounts(AccessContext context, ExportSink sink) throws IOException {
        policy.require(context, MODULE, "EXPORT");
        List<PendingItem> pending = pending(context);
        sink.header("screen", "change", "pending", "as_of");
        Instant now = clock.instant();
        for (String[] d : PENDING_DOMAINS) {
            sink.row(
                    d[0],
                    d[1],
                    pending.stream()
                            .filter(p -> p.screen().equals(d[0]) && p.entity().equals(d[1]))
                            .count(),
                    now);
        }
        ExportSink.requireComplete(PENDING_DOMAINS.size(), sink);
    }

    // ---------------------------------------------------------------- proposing

    /**
     * Proposes a new version (SRS-19-4, SRS-19-12): the first version of a rule that has none is a
     * CREATE, any later one an EDIT; one pending change per rule; checked by {@link
     * RuleProposalChecks}. In force only after the Rule Approver approves it; it supersedes the
     * version in force from its effective-from date.
     */
    @Transactional
    public VersionView propose(
            AccessContext context, String key, VersionRequest request, OtpProof otp) {
        // Authorise before the rule is read or locked: CREATE or EDIT, at Headquarters.
        if (!policy.can(context, MODULE, "CREATE") && !policy.can(context, MODULE, "EDIT")) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        requireHeadquarters(context);
        if (request == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        RuleDefinition rule = lockMaintained(key);
        List<RuleVersion> history = versions.findByRuleIdOrderByVersionNoDesc(rule.getId());
        String action = history.isEmpty() ? "CREATE" : "EDIT";
        policy.require(context, MODULE, action);
        requireNothingPending(rule);
        // R-ASCII: text is stored in a US7ASCII database.
        String reason = MasterText.requiredText("common.reason", request.reason(), REASON_MAX);
        VersionProposal proposal =
                new VersionProposal(
                        rule.getKey(),
                        rule.getTypeCode(),
                        request.effectiveFrom(),
                        request.effectiveTo(),
                        request.value(),
                        MasterText.optional(
                                "rule.formula", request.formula(), RuleProposals.FORMULA_MAX),
                        request.bands(),
                        request.roundingRuleKey() == null || request.roundingRuleKey().isBlank()
                                ? null
                                : request.roundingRuleKey().strip(),
                        request.circularId(),
                        context.loginId(),
                        clock.instant());
        checks.check(rule, history, proposal);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                MODULE,
                                action,
                                ENTITY,
                                rule.getKey(),
                                history.isEmpty() ? ActionType.CREATE : ActionType.UPDATE,
                                context.locationId(),
                                json.writeValueAsString(proposal),
                                proposal.effectiveFrom(),
                                reason),
                        otp);
        return view(
                new Pending<>(item.id(), proposal),
                nextVersionNo(history),
                circularNumbers(List.of(), List.of(proposal.circularId())));
    }

    /**
     * Proposes an explicit end date for the latest approved version, with no successor (SRS-19-6,
     * KB RE-4): legitimate, and recorded as such when approved.
     */
    @Transactional
    public PendingEnd end(AccessContext context, String key, EndRequest request, OtpProof otp) {
        policy.require(context, MODULE, "EDIT");
        requireHeadquarters(context);
        if (request == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        RuleDefinition rule = lockMaintained(key);
        List<RuleVersion> history = versions.findByRuleIdOrderByVersionNoDesc(rule.getId());
        requireNothingPending(rule);
        String reason = MasterText.requiredText("common.reason", request.reason(), REASON_MAX);
        RuleProposals.Dated latest = RuleProposals.requireEnd(request.endOn(), dated(history));
        EndProposal proposal =
                new EndProposal(
                        rule.getKey(),
                        latest.id(),
                        latest.versionNo(),
                        request.endOn(),
                        context.loginId(),
                        clock.instant());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                MODULE,
                                "EDIT",
                                END_ENTITY,
                                rule.getKey(),
                                ActionType.UPDATE,
                                context.locationId(),
                                json.writeValueAsString(proposal),
                                request.endOn(),
                                reason),
                        otp);
        return view(new Pending<>(item.id(), proposal));
    }

    // ---------------------------------------------------------------- shared with the effect

    /** The rule M-08 maintains and its version awaiting approval, for a simulation (06e). */
    @Transactional(readOnly = true)
    public Optional<Pending<VersionProposal>> pendingProposal(String key) {
        RuleDefinition rule = listedRule(key);
        if (!maintained(rule)) {
            throw new FciException(
                    ApiErrorCode.RULE_READ_ONLY, new Object[] {rule.getKey()}, List.of(), null);
        }
        return Optional.ofNullable(pendingVersions(Set.of(rule.getKey())).get(rule.getKey()));
    }

    /** The rule as M-08 lists it (any other key is not found). */
    @Transactional(readOnly = true)
    public RuleDefinition listedDefinition(String key) {
        return listedRule(key);
    }

    /**
     * A rule M-08 maintains, locked for the rest of the transaction (KB RE-2); also used by the
     * approval effect. Public: called through the service's proxy.
     */
    public RuleDefinition lockMaintained(String key) {
        RuleDefinition rule = listedRule(key);
        if (!maintained(rule)) {
            throw new FciException(
                    ApiErrorCode.RULE_READ_ONLY, new Object[] {rule.getKey()}, List.of(), null);
        }
        return rules.lockById(rule.getId()).orElseThrow();
    }

    /**
     * M-08 maintains a rule still in use, of the COMPUTATION domain, whose type has a value shape
     * (the seven SRS-19-1 types); the others are listed read-only.
     */
    static boolean maintained(RuleDefinition r) {
        return r.isActive()
                && r.getDomain() == RuleDefinition.Domain.COMPUTATION
                && RuleValueShape.of(r.getTypeCode()).isPresent();
    }

    static List<RuleProposals.Dated> dated(List<RuleVersion> history) {
        return history.stream()
                .map(
                        v ->
                                new RuleProposals.Dated(
                                        v.getId(),
                                        v.getVersionNo(),
                                        v.getEffectiveFrom(),
                                        v.getEffectiveTo(),
                                        v.getState() == RuleVersion.State.APPROVED))
                .toList();
    }

    static int nextVersionNo(List<RuleVersion> history) {
        return history.stream().mapToInt(RuleVersion::getVersionNo).max().orElse(0) + 1;
    }

    static SlabBands.Band pure(Band b) {
        return new SlabBands.Band(
                b.order(), b.from(), b.to(), b.rateOrAmount(), b.step(), b.qualifier());
    }

    // ---------------------------------------------------------------- helpers

    /** A change awaiting its decision: the approval item and what it proposes. */
    public record Pending<T>(Long approvalId, T change) {}

    private List<RuleDefinition> listed() {
        return rules.findByDomainInOrderByKey(DOMAINS).stream()
                .filter(r -> !SETTING.equals(r.getTypeCode()))
                .toList();
    }

    /** A rule M-08 lists; any other key (unknown, a setting) is not found, whoever asks. */
    private RuleDefinition listedRule(String key) {
        return rules.findByKey(key)
                .filter(r -> DOMAINS.contains(r.getDomain()) && !SETTING.equals(r.getTypeCode()))
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private static Set<String> keys(Collection<RuleDefinition> defs) {
        return defs.stream().map(RuleDefinition::getKey).collect(Collectors.toSet());
    }

    /** M-08 is an HQ screen (KB scope); the workflow routes M08_* at HQ too. */
    private void requireHeadquarters(AccessContext context) {
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_ONLY);
        }
    }

    /** One pending change per rule, a version or an end (checked under the rule's lock). */
    private void requireNothingPending(RuleDefinition rule) {
        Set<String> key = Set.of(rule.getKey());
        if (!pendingVersions(key).isEmpty() || !pendingEnds(key).isEmpty()) {
            throw new FciException(ApiErrorCode.ALREADY_PENDING);
        }
    }

    /** The pending version proposals of {@code keys} (A-07 items of the same table are skipped). */
    private Map<String, Pending<VersionProposal>> pendingVersions(Set<String> keys) {
        Map<String, Pending<VersionProposal>> out = new HashMap<>();
        for (ApprovalStatuses.Pending p : statuses.pending(ENTITY)) {
            if (keys.contains(p.entityKey())) {
                out.putIfAbsent(
                        p.entityKey(),
                        new Pending<>(
                                p.approvalId(),
                                json.readValue(p.payload(), VersionProposal.class)));
            }
        }
        return out;
    }

    private Map<String, Pending<EndProposal>> pendingEnds(Set<String> keys) {
        Map<String, Pending<EndProposal>> out = new HashMap<>();
        for (ApprovalStatuses.Pending p : statuses.pending(END_ENTITY)) {
            if (keys.contains(p.entityKey())) {
                out.putIfAbsent(
                        p.entityKey(),
                        new Pending<>(
                                p.approvalId(), json.readValue(p.payload(), EndProposal.class)));
            }
        }
        return out;
    }

    /**
     * @param keys the keys listed on M-08, or null for a screen whose items all belong to it
     */
    private void add(List<PendingItem> out, String screen, String entity, Set<String> keys) {
        for (ApprovalStatuses.Pending p : statuses.pending(entity)) {
            if (keys == null || keys.contains(p.entityKey())) {
                out.add(
                        new PendingItem(
                                screen,
                                entity,
                                p.entityKey(),
                                p.approvalId(),
                                p.submittedBy(),
                                p.submittedAt()));
            }
        }
    }

    private Map<Long, List<SlabBands.Band>> bandsOf(List<RuleVersion> list) {
        Map<Long, List<SlabBands.Band>> out = new HashMap<>();
        if (list.isEmpty()) {
            return out;
        }
        for (RuleSlabBand b :
                bands.findByRuleVersionIdIn(list.stream().map(RuleVersion::getId).toList())) {
            out.computeIfAbsent(b.getRuleVersionId(), k -> new ArrayList<>()).add(b.band());
        }
        return out;
    }

    /** The numbers of the circulars named by the versions and by the pending proposals. */
    private Map<Long, String> circularNumbers(List<Long> ofVersions, List<Long> ofProposals) {
        List<Long> ids =
                java.util.stream.Stream.concat(ofVersions.stream(), ofProposals.stream())
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        Map<Long, String> out = new HashMap<>();
        if (!ids.isEmpty()) {
            for (CircularOrder c : circularRows.findByCircularOrderIdIn(ids)) {
                out.put(c.getCircularOrderId(), c.getCircularNumber());
            }
        }
        return out;
    }

    private String officeName(Long id) {
        return id == null ? null : org.location(id).map(l -> l.getName()).orElse(null);
    }

    /** FRS §24.9: the Rule Approver who decided each version's approval item. */
    private Map<Long, String> deciders(List<RuleVersion> list) {
        List<Long> ids =
                list.stream().map(RuleVersion::getApprovalId).filter(Objects::nonNull).toList();
        return ids.isEmpty() ? Map.of() : statuses.deciders(ids);
    }

    /**
     * FRS §24.9: the version {@code v} supersedes, as its approval left it: the approved earlier
     * version that ends the day before {@code v} starts (RuleCatalogueApprovalEffect ends every
     * approved version still in force on the new start the day before). Null for a version that is
     * not approved or that followed an end without successor (the gap is not closed by it).
     */
    static Integer supersedes(RuleVersion v, List<RuleVersion> history) {
        if (v.getState() != RuleVersion.State.APPROVED) {
            return null;
        }
        LocalDate dayBefore = v.getEffectiveFrom().minusDays(1);
        Integer found = null;
        for (RuleVersion w : history) {
            if (w.getState() == RuleVersion.State.APPROVED
                    && w.getVersionNo() < v.getVersionNo()
                    && dayBefore.equals(w.getEffectiveTo())
                    && (found == null || w.getVersionNo() > found)) {
                found = w.getVersionNo();
            }
        }
        return found;
    }

    private static VersionView view(
            RuleVersion v,
            List<RuleVersion> history,
            Map<Long, List<SlabBands.Band>> slab,
            Map<Long, String> numbers,
            Map<Long, String> deciders,
            LocalDate today) {
        return new VersionView(
                v.getId(),
                v.getVersionNo(),
                v.getEffectiveFrom(),
                v.getEffectiveTo(),
                RuleVersionStatus.of(
                                v.getState().name(),
                                v.getEffectiveFrom(),
                                v.getEffectiveTo(),
                                today)
                        .name(),
                v.getScalarValue(),
                v.getFormulaExpression(),
                sorted(
                        slab.getOrDefault(v.getId(), List.of()).stream()
                                .map(
                                        b ->
                                                new Band(
                                                        b.order(),
                                                        b.from(),
                                                        b.to(),
                                                        b.rateOrAmount(),
                                                        b.step(),
                                                        b.qualifier()))
                                .toList()),
                v.getRoundingRuleKey(),
                v.getCircularOrderId(),
                numbers.get(v.getCircularOrderId()),
                v.isBlocked(),
                v.getBlockedReason(),
                v.getCreatedBy(),
                v.getCreatedAt(),
                v.getApprovalId(),
                v.getApprovalId() == null ? null : deciders.get(v.getApprovalId()),
                supersedes(v, history));
    }

    /** A pending proposal, numbered as it will be written. */
    private static VersionView view(
            Pending<VersionProposal> p, int versionNo, Map<Long, String> numbers) {
        VersionProposal c = p.change();
        return new VersionView(
                null,
                versionNo,
                c.effectiveFrom(),
                c.effectiveTo(),
                RuleVersionStatus.PENDING_APPROVAL.name(),
                c.value(),
                c.formula(),
                sorted(c.bands()),
                c.roundingRuleKey(),
                c.circularId(),
                numbers.get(c.circularId()),
                false,
                null,
                c.proposedBy(),
                c.proposedAt(),
                p.approvalId(),
                null,
                null);
    }

    private static PendingEnd view(Pending<EndProposal> p) {
        EndProposal c = p.change();
        return new PendingEnd(
                p.approvalId(), c.versionNo(), c.endOn(), c.proposedBy(), c.proposedAt());
    }

    /** Bands by qualifier (none first), then by order. */
    private static List<Band> sorted(List<Band> list) {
        return list.stream()
                .sorted(
                        Comparator.comparing(
                                        Band::qualifier,
                                        Comparator.nullsFirst(Comparator.naturalOrder()))
                                .thenComparingInt(Band::order))
                .toList();
    }
}
