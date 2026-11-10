package in.gov.fci.payroll.configuration.api;

import in.gov.fci.payroll.configuration.business.RuleFormula;
import in.gov.fci.payroll.configuration.business.RuleVersionStatus;
import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.business.model.RuleExpressionGrammar;
import in.gov.fci.payroll.configuration.business.model.RuleSlabBand;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.cache.VersionedCache;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Dynamic Rule & Parameter Engine (Module 06; SRS-19-1 … 21, FRS BR-RULE-001 … 008; KB {@code
 * 03-modules/06-rule-engine} §6) as the engine modules (07, 08, 16, 20-22) use it. Read-only: rules
 * change only on M-08 through an approved version (SRS-19-4).
 *
 * <ul>
 *   <li>Point in time (SRS-19-3, SRS-19-13, KB RE-7): every call names the day; the version in
 *       force on that day is used, never the current one for a closed period.
 *   <li>Never a default (R-RULE, DDL "a seeded guess is worse than an empty value"): a rule that
 *       applies but has no approved version in force is refused with RULE-422-NOT-CONFIGURED, so
 *       the caller holds the record (C-08).
 *   <li>Scope (KB RE-10): a rule scoped to payroll types or to an office (and the offices below it)
 *       does not apply elsewhere — {@link #resolve} answers empty there.
 *   <li>Formulas only through the bounded grammar (SRS-19-15); slab bands as checked sets
 *       (SRS-19-5).
 * </ul>
 *
 * <p><b>Two ways in.</b> A run (an Actual or General Run) takes a {@link #snapshot} at its start
 * and computes every member with it: one read, no rule change during the run reaches it, and its
 * hash is the run's {@code rule_version_set_hash} (BR-CYC-011 … 017). A single question (a screen,
 * a check) uses {@link #resolve}, {@link #band}, {@link #evaluate} or {@link #condition}, which
 * read that one rule; both ways share the same rules ({@link RuleSet}).
 *
 * <p>The SECURITY domain (A-07 settings, an FCILekha extension) is not served here: see {@link
 * SystemParameters}.
 *
 * <p><b>Cache</b> (region {@value #REGION}). Rules change only through an approved M-08 decision,
 * yet the engines ask for them per member (CPF deduction, tax): the immutable {@link RuleSet}s are
 * held per day (a snapshot) and per rule and day (a single question), with the grammar. Every
 * {@link RuleVersionChanged} marks the region changed in the approving transaction, so this
 * instance reloads at once and every other one within {@code fci.cache.check-interval}. {@link
 * #pin} (the start of a run) always reads afresh and replaces the day's snapshot, so a run never
 * pins a version set older than the database (BR-CYC-011).
 */
@Component
public class RuleEngine {

    /**
     * Who asks: the payroll type of the engine and the member's office (null = not known, no scope
     * applies).
     */
    public record Context(String payrollType, Long locationId) {

        /** A context without payroll type or office: only rules with no scope apply. */
        public static Context unscoped() {
            return new Context(null, null);
        }
    }

    /** One slab band (SRS-19-5): covers {@code from <= x < to}; {@code to} null = "and above". */
    public record Band(
            int order,
            BigDecimal from,
            BigDecimal to,
            BigDecimal rateOrAmount,
            BigDecimal step,
            String qualifier) {}

    /**
     * The version of a rule in force on a day.
     *
     * @param scalar the rule value, for Rate / Ceiling / Floor rules (null when the version holds
     *     bands or a formula)
     * @param blocked a statutory-output definition that must not generate until its layout is
     *     loaded (KB RE-16)
     */
    public record Resolved(
            String ruleKey,
            String ruleType,
            Long versionId,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            BigDecimal scalar,
            List<Band> bands,
            String formula,
            String roundingRuleKey,
            boolean blocked) {

        public Resolved {
            bands = bands == null ? List.of() : List.copyOf(bands);
        }
    }

    /** One rule version pinned for a run. */
    public record Pinned(String ruleKey, Long versionId, int versionNo) {}

    /**
     * The computation rule versions in force on a day, with their hash: an Actual Run records the
     * hash ({@code computation_run.rule_version_set_hash}) and resumes only while it is unchanged
     * (BR-CYC-011 … 017).
     */
    public record VersionSet(LocalDate asOf, List<Pinned> versions, String hash) {}

    /** The formula vocabulary in force (M-08 shows it beside the formula field). */
    public record Vocabulary(
            List<String> operators, Map<String, Integer> functions, List<String> variables) {}

    private final ConfigurationRepositories.Rules rules;
    private final ConfigurationRepositories.RuleVersions versions;
    private final ConfigurationRepositories.RuleSlabBands bands;
    private final ConfigurationRepositories.Grammar grammar;
    private final OrgDirectory org;
    private final VersionedCache<LocalDate, RuleSet> snapshots;
    private final VersionedCache<SingleKey, RuleSet> singles;
    private final VersionedCache<Boolean, RuleFormula.Grammar> grammars;

    /** The cache region of the rules, their versions, bands and the grammar. */
    public static final String REGION = "RULE";

    private record SingleKey(String ruleKey, LocalDate asOf) {}

    public RuleEngine(
            ConfigurationRepositories.Rules rules,
            ConfigurationRepositories.RuleVersions versions,
            ConfigurationRepositories.RuleSlabBands bands,
            ConfigurationRepositories.Grammar grammar,
            OrgDirectory org,
            CacheRegions regions) {
        this.rules = rules;
        this.versions = versions;
        this.bands = bands;
        this.grammar = grammar;
        this.org = org;
        // Days: a run's period end, the business day, a few back-dated days; rules: ~200 keys.
        this.snapshots = VersionedCache.of(REGION, regions, 64);
        this.singles = VersionedCache.of(REGION, regions, 4_096);
        this.grammars = VersionedCache.of(REGION, regions, 1);
    }

    /**
     * An approved or ended version (published in the approving transaction): every instance drops
     * its rules and reloads.
     */
    @EventListener
    public void on(RuleVersionChanged changed) {
        snapshots.changed("rule:" + changed.ruleKey());
    }

    // ---------------------------------------------------------------- a run

    /**
     * Every active COMPUTATION rule with the version in force on {@code asOf}, read once and held
     * in memory for a run (BR-CYC-011): the grammar is taken with it, so nothing that changes
     * during the run reaches the run.
     */
    @Transactional(readOnly = true)
    public RuleSet snapshot(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return snapshots.get(asOf, this::load);
    }

    private RuleSet load(LocalDate asOf) {
        List<RuleDefinition> computation =
                rules.findByDomain(RuleDefinition.Domain.COMPUTATION).stream()
                        .filter(RuleDefinition::isActive)
                        .toList();
        RuleFormula.Grammar taken = currentGrammar();
        return new RuleSet(asOf, entries(computation, asOf), () -> taken, org::subtree);
    }

    /**
     * The COMPUTATION rule versions in force on {@code asOf}, hashed over their keys and version
     * ids in key order: the same rules and versions always give the same hash (BR-CYC-011).
     */
    @Transactional(readOnly = true)
    public VersionSet pin(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        // Read afresh (another instance's approval may not have reached this one's version check
        // yet) and kept: the members of the run then compute with exactly the pinned set.
        long version = snapshots.version();
        RuleSet fresh = load(asOf);
        snapshots.put(asOf, version, fresh);
        return fresh.versionSet();
    }

    // ---------------------------------------------------------------- a single question

    /**
     * The version of {@code ruleKey} in force on {@code asOf} for {@code ctx}; empty when the
     * rule's scope does not cover the context. Refused (RULE-422-NOT-CONFIGURED) when the rule is
     * unknown, inactive, of the SECURITY domain, or applies but has no approved version in force on
     * that day.
     */
    @Transactional(readOnly = true)
    public Optional<Resolved> resolve(String ruleKey, LocalDate asOf, Context ctx) {
        return single(ruleKey, asOf).resolve(ruleKey, ctx);
    }

    /**
     * The band of a Slab rule covering {@code amount}; empty when the rule does not apply or no
     * band covers it.
     */
    @Transactional(readOnly = true)
    public Optional<Band> band(
            String ruleKey, LocalDate asOf, Context ctx, String qualifier, BigDecimal amount) {
        return single(ruleKey, asOf).band(ruleKey, ctx, qualifier, amount);
    }

    /**
     * The value of a Formula rule for {@code values}; empty when the rule does not apply. A version
     * without a formula, a formula outside the grammar or one that cannot be evaluated is refused
     * (RULE-422-FORMULA[-EVAL]).
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> evaluate(
            String ruleKey, LocalDate asOf, Context ctx, Map<String, BigDecimal> values) {
        return single(ruleKey, asOf).evaluate(ruleKey, ctx, values);
    }

    /** As {@link #evaluate} for an Eligibility or Applicability formula. */
    @Transactional(readOnly = true)
    public Optional<Boolean> condition(
            String ruleKey, LocalDate asOf, Context ctx, Map<String, BigDecimal> values) {
        return single(ruleKey, asOf).condition(ruleKey, ctx, values);
    }

    // ---------------------------------------------------------------- checks (M-08 submit)

    /**
     * Checks a formula against the grammar in force (refused naming the fault) and that it yields
     * what the rule type needs: a condition for an Eligibility or Applicability rule, a number for
     * a Formula rule (SRS-19-1, SRS-19-15).
     *
     * @return the variables the formula reads
     */
    @Transactional(readOnly = true)
    public Set<String> checkFormula(String formula, boolean condition) {
        RuleFormula parsed = RuleFormula.parse(formula, currentGrammar());
        if (parsed.yieldsCondition() != condition) {
            throw new FciException(
                    ApiErrorCode.RULE_FORMULA_INVALID,
                    new Object[] {
                        condition
                                ? "it yields a number where a condition is needed"
                                : "it yields a condition where a number is needed"
                    },
                    List.of(),
                    null);
        }
        return parsed.variables();
    }

    /** The permitted operators, functions (with arity; null = any) and variables, sorted. */
    @Transactional(readOnly = true)
    public Vocabulary vocabulary() {
        RuleFormula.Grammar g = currentGrammar();
        return new Vocabulary(
                g.operators().stream().sorted().toList(),
                new java.util.TreeMap<>(g.functions()),
                g.variables().stream().sorted().toList());
    }

    // ---------------------------------------------------------------- helpers

    /** One rule, as a set of one: unknown, inactive or SECURITY is refused here. */
    private RuleSet single(String ruleKey, LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(ruleKey, "ruleKey");
        // A refused rule is not cached (the loader throws): it is asked again next time.
        return singles.get(new SingleKey(ruleKey, asOf), k -> loadSingle(k.ruleKey(), k.asOf()));
    }

    private RuleSet loadSingle(String ruleKey, LocalDate asOf) {
        RuleDefinition rule =
                rules.findByKey(ruleKey)
                        .filter(
                                r ->
                                        r.isActive()
                                                && r.getDomain() != RuleDefinition.Domain.SECURITY)
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                ApiErrorCode.RULE_NOT_CONFIGURED,
                                                new Object[] {ruleKey, asOf},
                                                List.of(),
                                                null));
        // The grammar is read only if a formula is evaluated.
        return new RuleSet(asOf, entries(List.of(rule), asOf), this::currentGrammar, org::subtree);
    }

    /** Each rule with the approved version in force on {@code asOf} (null when none). */
    private List<RuleSet.Entry> entries(Collection<RuleDefinition> defs, LocalDate asOf) {
        if (defs.isEmpty()) {
            return List.of();
        }
        Map<Long, RuleVersion> inForce = new HashMap<>();
        for (RuleVersion v :
                versions.findByRuleIdInAndState(
                        defs.stream().map(RuleDefinition::getId).toList(),
                        RuleVersion.State.APPROVED)) {
            // trg_rule_version_temporal: at most one approved version is in force on a day.
            if (RuleVersionStatus.inForce(
                    "APPROVED", v.getEffectiveFrom(), v.getEffectiveTo(), asOf)) {
                inForce.put(v.getRuleId(), v);
            }
        }
        Map<Long, List<Band>> slab = new HashMap<>();
        if (!inForce.isEmpty()) {
            for (RuleSlabBand b :
                    bands.findByRuleVersionIdIn(
                            inForce.values().stream().map(RuleVersion::getId).toList())) {
                slab.computeIfAbsent(b.getRuleVersionId(), k -> new ArrayList<>())
                        .add(RuleSet.band(b.band()));
            }
        }
        List<RuleSet.Entry> out = new ArrayList<>();
        for (RuleDefinition r : defs) {
            RuleVersion v = inForce.get(r.getId());
            out.add(
                    new RuleSet.Entry(
                            r.getKey(),
                            r.getPayrollTypeScope(),
                            r.getOrgScopeLocationId(),
                            v == null
                                    ? null
                                    : resolved(r, v, slab.getOrDefault(v.getId(), List.of()))));
        }
        return out;
    }

    private static Resolved resolved(RuleDefinition r, RuleVersion v, List<Band> own) {
        return new Resolved(
                r.getKey(),
                r.getTypeCode(),
                v.getId(),
                v.getVersionNo(),
                v.getEffectiveFrom(),
                v.getEffectiveTo(),
                v.getScalarValue(),
                own.stream()
                        .sorted(
                                Comparator.comparing(
                                                Band::qualifier,
                                                Comparator.nullsFirst(Comparator.naturalOrder()))
                                        .thenComparingInt(Band::order))
                        .toList(),
                v.getFormulaExpression(),
                v.getRoundingRuleKey(),
                v.isBlocked());
    }

    private RuleFormula.Grammar currentGrammar() {
        return grammars.get(Boolean.TRUE, k -> loadGrammar());
    }

    private RuleFormula.Grammar loadGrammar() {
        Set<String> operators = new HashSet<>();
        Map<String, Integer> functions = new HashMap<>();
        Set<String> variables = new HashSet<>();
        for (RuleExpressionGrammar g : grammar.findByPermitted(Boolean.TRUE)) {
            switch (g.getTokenType()) {
                case OPERATOR -> operators.add(g.getTokenValue());
                case FUNCTION -> functions.put(g.getTokenValue(), g.getArity());
                case VARIABLE -> variables.add(g.getTokenValue());
                case LITERAL_TYPE -> {
                    // NUMBER: numeric literals are always part of the grammar.
                }
            }
        }
        // Shared by every cached rule set: never changed after this (an arity may be null = any).
        return new RuleFormula.Grammar(
                Set.copyOf(operators),
                java.util.Collections.unmodifiableMap(functions),
                Set.copyOf(variables));
    }
}
