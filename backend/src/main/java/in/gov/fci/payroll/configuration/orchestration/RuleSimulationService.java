package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleImpactPricing;
import in.gov.fci.payroll.configuration.api.RuleSet;
import in.gov.fci.payroll.configuration.api.RuleVersionChanged;
import in.gov.fci.payroll.configuration.api.SimulationPopulation;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.PayrollType;
import in.gov.fci.payroll.configuration.business.RuleScope;
import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.orchestration.RuleCatalogueService.Pending;
import in.gov.fci.payroll.configuration.orchestration.RuleCatalogueService.VersionProposal;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * M-08 impact simulation before activation (plan 06 phase 06e; SRS-19-4, BR-RULE-005, KB RE-6; DDL
 * {@code rule_simulation}): the Rule Administrator or the Rule Approver tests the version of a rule
 * awaiting approval on a sample population, by payroll type and office, before it is approved.
 *
 * <ul>
 *   <li>The sample is the members a payroll run would read ({@link SimulationPopulation}), at most
 *       the A-07 maximum selection of a simulation (SIMULATION_MAX_SELECTION, unseeded: OQ-17).
 *   <li>Each member's rule value is resolved as in force on the proposal's start and as proposed,
 *       in the member's scope (payroll type, office: RE-10); members are summarised by payroll type
 *       and office (plan 06 D-12). The pay amounts follow when the engines price the rule (modules
 *       20-22; PENDING 06-impact).
 *   <li>The run is kept in {@code rule_simulation} with its input hash; it is linked to the version
 *       when that is approved (D-7). Opening it again says when the inputs have changed since.
 *   <li>Nothing is written to a rule or a payroll; per-member results are not kept
 *       (results_retained 'N').
 * </ul>
 */
@Service
@Loggable
public class RuleSimulationService {

    public static final String ENTITY = "rule_simulation";

    /**
     * {@code population_scope} prefix of an M-08 simulation: M08;approvalId;ruleKey;type;office.
     */
    static final String SCOPE = "M08;";

    /** The groups an impact summary keeps, the largest first. */
    static final int GROUPS = 50;

    static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    /** Members of one payroll type at one office, with the rule's value now and as proposed. */
    public record Group(
            String payrollType,
            long officeId,
            String officeName,
            long members,
            String current,
            String proposed,
            boolean changes) {}

    /** What {@code impact_summary} holds. */
    public record Impact(
            long sampled,
            long inScope,
            long changed,
            List<Group> groups,
            RuleImpactPricing.Priced priced) {

        public Impact {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }

        /**
         * The rule's values only: a rule the engines do not price, or a run kept before pricing.
         */
        public Impact(long sampled, long inScope, long changed, List<Group> groups) {
            this(sampled, inScope, changed, groups, null);
        }

        Impact priced(RuleImpactPricing.Priced amounts) {
            return new Impact(sampled, inScope, changed, groups, amounts);
        }
    }

    /** One simulation as M-08 shows it. */
    public record Simulation(
            long id,
            Long approvalId,
            String ruleKey,
            String payrollType,
            Long officeId,
            String requestedBy,
            Instant requestedAt,
            long population,
            Impact impact,
            Long ruleVersionId,
            boolean inputsChanged) {}

    /** An office the sample can be narrowed to. */
    public record Office(long id, String code, String name, String type) {}

    /** What the requester narrows the sample to (null = every). */
    public record Request(String payrollType, Long officeId) {}

    private record Run(String hash, long population, Impact impact) {}

    private final RuleCatalogueService catalogue;
    private final RuleEngine engine;
    private final SystemParameters settings;
    private final ObjectProvider<SimulationPopulation> populations;
    private final OrgDirectory org;
    private final Authorization policy;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final ObjectProvider<RuleImpactPricing> pricer;

    @SuppressWarnings("java:S107") // one collaborator per concern, as the other master services
    public RuleSimulationService(
            RuleCatalogueService catalogue,
            RuleEngine engine,
            SystemParameters settings,
            ObjectProvider<SimulationPopulation> populations,
            OrgDirectory org,
            Authorization policy,
            AuditService audit,
            JdbcTemplate jdbc,
            ObjectMapper json,
            Clock clock,
            ObjectProvider<RuleImpactPricing> pricer) {
        this.pricer = pricer;
        this.catalogue = catalogue;
        this.engine = engine;
        this.settings = settings;
        this.populations = populations;
        this.org = org;
        this.policy = policy;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** SRS-19-4: simulates the pending version of {@code key} and keeps the run. */
    @Transactional
    public Simulation simulate(AccessContext context, String key, Request request) {
        requireSimulator(context);
        Request scope = request == null ? new Request(null, null) : request;
        if (scope.payrollType() != null && PayrollType.of(scope.payrollType()) == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (scope.officeId() != null && org.location(scope.officeId()).isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        Pending<VersionProposal> pending = proposal(key);
        Run run = run(catalogue.listedDefinition(key), pending, scope);
        Impact impact = run.impact().priced(amounts(key, pending.change(), scope));
        Instant now = clock.instant();
        String requestedBy = cut(context.loginId());
        String populationScope = scope(pending.approvalId(), key, scope);
        // Oracle review F2: the generated key of this INSERT, never MAX(id) read back.
        long id =
                GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO rule_simulation (requested_by, requested_at, population_scope,"
                                + " population_count, input_hash, impact_summary,"
                                + " results_retained) VALUES (?, ?, ?, ?, ?, ?, 'N')",
                        "simulation_id",
                        requestedBy,
                        Timestamp.from(now),
                        populationScope,
                        run.population(),
                        run.hash(),
                        json.writeValueAsString(impact));
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id),
                                "simulation",
                                null,
                                populationScope + " population " + run.population(),
                                "M-08 impact simulation of " + key)
                        .module(RuleCatalogueService.MODULE));
        return new Simulation(
                id,
                pending.approvalId(),
                key,
                scope.payrollType(),
                scope.officeId(),
                requestedBy,
                now,
                run.population(),
                impact,
                null,
                false);
    }

    /**
     * BR-RULE-006: the amounts, where the engines can price the rule. A gap in the engines (a rate
     * set not configured for the month) leaves the run with the rule's values, never with guessed
     * amounts: it does not refuse the simulation of the rule itself.
     */
    private RuleImpactPricing.Priced amounts(String key, VersionProposal p, Request scope) {
        RuleImpactPricing pricing = pricer.getIfAvailable();
        SimulationPopulation source = populations.getIfAvailable();
        Long limit = settings.configured(SystemParameters.Code.SIMULATION_MAX_SELECTION).orElse(0L);
        if (pricing == null || source == null || p.value() == null) {
            return null;
        }
        try {
            return pricing.price(
                            key,
                            p.value(),
                            p.effectiveFrom(),
                            source.sample(
                                            p.effectiveFrom(),
                                            scope.payrollType(),
                                            scope.officeId(),
                                            (int) Math.min(Integer.MAX_VALUE, limit))
                                    .members())
                    .orElse(null);
        } catch (FciException gap) {
            return null;
        }
    }

    /** The offices in force today, for the sample's office (those who may simulate). */
    @Transactional(readOnly = true)
    public List<Office> offices(AccessContext context) {
        requireSimulator(context);
        LocalDate today = settings.today();
        return org.locations(org.all()).stream()
                .filter(l -> l.isActiveOn(today))
                .sorted(Comparator.comparing(OrgLocation::getCode))
                .map(l -> new Office(l.getId(), l.getCode(), l.getName(), l.getType().name()))
                .toList();
    }

    private void requireSimulator(AccessContext context) {
        if (!policy.can(context, RuleCatalogueService.MODULE, "CREATE")
                && !policy.can(context, RuleCatalogueService.MODULE, "APPROVE")) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_ONLY);
        }
    }

    /**
     * The simulations of the version of {@code key} awaiting approval, newest first, each saying
     * whether its inputs have changed since (the rule, the population or the proposal).
     */
    @Transactional(readOnly = true)
    public List<Simulation> simulations(AccessContext context, String key) {
        policy.require(context, RuleCatalogueService.MODULE, "VIEW");
        Optional<Pending<VersionProposal>> pending = catalogue.pendingProposal(key);
        if (pending.isEmpty()) {
            return List.of();
        }
        RuleDefinition rule = catalogue.listedDefinition(key);
        Map<String, Run> now = new LinkedHashMap<>();
        List<Simulation> out = new ArrayList<>();
        jdbc.query(
                "SELECT simulation_id, requested_by, requested_at, population_scope,"
                    + " population_count, input_hash, impact_summary, rule_version_id FROM"
                    + " rule_simulation WHERE population_scope LIKE ? ORDER BY simulation_id DESC",
                rs -> {
                    String populationScope = rs.getString(4);
                    String[] parts = populationScope.split(";", -1);
                    Request scope =
                            new Request(
                                    "ALL".equals(parts[3]) ? null : parts[3],
                                    "ALL".equals(parts[4]) ? null : Long.valueOf(parts[4]));
                    Run current =
                            now.computeIfAbsent(
                                    populationScope, s -> run(rule, pending.get(), scope));
                    long versionId = rs.getLong(8);
                    Long linked = rs.wasNull() ? null : versionId;
                    out.add(
                            new Simulation(
                                    rs.getLong(1),
                                    pending.get().approvalId(),
                                    key,
                                    scope.payrollType(),
                                    scope.officeId(),
                                    rs.getString(2),
                                    rs.getTimestamp(3).toInstant(),
                                    rs.getLong(5),
                                    json.readValue(rs.getString(7), Impact.class),
                                    linked,
                                    !current.hash().equals(rs.getString(6))));
                },
                SCOPE + pending.get().approvalId() + ";" + key + ";%");
        return out;
    }

    /** D-7: the simulations of an approved version are kept with it. */
    @EventListener
    public void on(RuleVersionChanged changed) {
        if (changed.kind() != RuleVersionChanged.Kind.APPROVED || changed.versionId() == null) {
            return;
        }
        Long approvalId =
                jdbc.queryForObject(
                        "SELECT approval_id FROM rule_version WHERE rule_version_id = ?",
                        Long.class,
                        changed.versionId());
        if (approvalId != null) {
            jdbc.update(
                    "UPDATE rule_simulation SET rule_version_id = ? WHERE rule_version_id IS NULL"
                            + " AND population_scope LIKE ?",
                    changed.versionId(),
                    SCOPE + approvalId + ";" + changed.ruleKey() + ";%");
        }
    }

    // ---------------------------------------------------------------- the run

    private Pending<VersionProposal> proposal(String key) {
        return catalogue
                .pendingProposal(key)
                .orElseThrow(
                        () ->
                                new FciException(
                                        ApiErrorCode.RULE_NO_PROPOSAL,
                                        new Object[] {key},
                                        List.of(),
                                        null));
    }

    private Run run(RuleDefinition rule, Pending<VersionProposal> pending, Request scope) {
        long limit =
                settings.configured(SystemParameters.Code.SIMULATION_MAX_SELECTION)
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                ApiErrorCode.SIMULATION_NOT_CONFIGURED,
                                                new Object[] {"SIMULATION_MAX_SELECTION"},
                                                List.of(),
                                                null));
        VersionProposal p = pending.change();
        LocalDate day = p.effectiveFrom();
        SimulationPopulation source = populations.getIfAvailable();
        SimulationPopulation.Sample sample =
                source == null
                        ? new SimulationPopulation.Sample(0, List.of())
                        : source.sample(
                                day,
                                scope.payrollType(),
                                scope.officeId(),
                                (int) Math.min(Integer.MAX_VALUE, limit));
        Map<String, List<SimulationPopulation.Member>> byGroup =
                sample.members().stream()
                        .collect(
                                Collectors.groupingBy(
                                        m -> m.payrollType() + ";" + m.locationId(),
                                        LinkedHashMap::new,
                                        Collectors.toList()));
        String proposed = describe(p);
        // One snapshot of the rules in force on the start: a plain object, so a "not configured"
        // answer caught below never marks the request's transaction for rollback (playbook).
        RuleSet inForce = engine.snapshot(day);
        List<Group> groups = new ArrayList<>();
        StringBuilder inputs =
                new StringBuilder(pending.approvalId() + "|" + json.writeValueAsString(p));
        long inScope = 0;
        long changed = 0;
        for (List<SimulationPopulation.Member> members : byGroup.values()) {
            SimulationPopulation.Member first = members.get(0);
            RuleEngine.Context ctx =
                    new RuleEngine.Context(first.payrollType(), first.locationId());
            String current = current(inForce, rule.getKey(), ctx);
            boolean covers =
                    RuleScope.covers(
                            rule.getPayrollTypeScope(),
                            rule.getOrgScopeLocationId(),
                            first.payrollType(),
                            first.locationId(),
                            org::subtree);
            String after = covers ? proposed : NOT_APPLICABLE;
            boolean changes = !current.equals(after);
            if (covers) {
                inScope += members.size();
            }
            if (changes) {
                changed += members.size();
            }
            groups.add(
                    new Group(
                            first.payrollType(),
                            first.locationId(),
                            org.location(first.locationId()).map(OrgLocation::getName).orElse(null),
                            members.size(),
                            current,
                            after,
                            changes));
            inputs.append('|')
                    .append(first.payrollType())
                    .append(';')
                    .append(first.locationId())
                    .append(';')
                    .append(members.size())
                    .append(';')
                    .append(current);
            members.forEach(m -> inputs.append(',').append(m.employeeId()));
        }
        groups.sort(
                Comparator.comparingLong(Group::members)
                        .reversed()
                        .thenComparing(Group::payrollType)
                        .thenComparingLong(Group::officeId));
        inputs.append("|population=").append(sample.total());
        return new Run(
                sha256(inputs.toString()),
                sample.total(),
                new Impact(
                        sample.members().size(),
                        inScope,
                        changed,
                        groups.subList(0, Math.min(GROUPS, groups.size()))));
    }

    /** The value in force for the context, or why there is none. */
    private static String current(RuleSet inForce, String key, RuleEngine.Context ctx) {
        try {
            return inForce.resolve(key, ctx)
                    .map(RuleSimulationService::describe)
                    .orElse(NOT_APPLICABLE);
        } catch (FciException ex) {
            if (ex.getErrorCode() == ApiErrorCode.RULE_NOT_CONFIGURED) {
                return NOT_CONFIGURED;
            }
            throw ex;
        }
    }

    static String describe(RuleEngine.Resolved r) {
        return describe(
                r.scalar(),
                r.formula(),
                r.bands().stream()
                        .map(b -> band(b.from(), b.to(), b.rateOrAmount(), b.qualifier()))
                        .toList());
    }

    static String describe(VersionProposal p) {
        return describe(
                p.value(),
                p.formula(),
                p.bands().stream()
                        .map(b -> band(b.from(), b.to(), b.rateOrAmount(), b.qualifier()))
                        .toList());
    }

    /** One text per value shape: a number, a formula or the slab bands. */
    static String describe(BigDecimal value, String formula, List<String> bands) {
        if (!bands.isEmpty()) {
            return "SLAB " + String.join("; ", bands);
        }
        if (formula != null && !formula.isBlank()) {
            return "FORMULA " + formula.strip();
        }
        return value == null ? NOT_CONFIGURED : value.stripTrailingZeros().toPlainString();
    }

    static String band(BigDecimal from, BigDecimal to, BigDecimal rate, String qualifier) {
        return (qualifier == null ? "" : qualifier + " ")
                + plain(from)
                + "-"
                + (to == null ? "" : plain(to))
                + ": "
                + plain(rate);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    static String scope(Long approvalId, String key, Request scope) {
        return SCOPE
                + approvalId
                + ";"
                + key
                + ";"
                + (scope.payrollType() == null ? "ALL" : scope.payrollType())
                + ";"
                + (scope.officeId() == null ? "ALL" : scope.officeId());
    }

    private static String cut(String login) {
        return login.substring(0, Math.min(50, login.length()));
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
