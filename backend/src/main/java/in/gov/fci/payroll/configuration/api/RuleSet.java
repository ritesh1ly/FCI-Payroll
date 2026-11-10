package in.gov.fci.payroll.configuration.api;

import in.gov.fci.payroll.configuration.api.RuleEngine.Band;
import in.gov.fci.payroll.configuration.api.RuleEngine.Context;
import in.gov.fci.payroll.configuration.api.RuleEngine.Pinned;
import in.gov.fci.payroll.configuration.api.RuleEngine.Resolved;
import in.gov.fci.payroll.configuration.api.RuleEngine.VersionSet;
import in.gov.fci.payroll.configuration.business.RuleFormula;
import in.gov.fci.payroll.configuration.business.RuleScope;
import in.gov.fci.payroll.configuration.business.SlabBands;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The rule versions in force on one day, held in memory: what an engine run computes with
 * (BR-CYC-011 … 017; SRS-19-3, SRS-19-13; KB RE-7). Taken once at the start of a run ({@link
 * RuleEngine#snapshot}), it never changes: a version approved during the run does not reach it, so
 * the same inputs and the same set always give the same result, and its {@link #hash()} is the
 * run's {@code rule_version_set_hash}. Each rule is checked as {@link RuleEngine#resolve} does:
 * outside its scope it answers empty (KB RE-10); unknown, or in scope with no version in force, it
 * is refused (RULE-422-NOT-CONFIGURED, R-RULE: never a default).
 *
 * <p>Immutable and safe to share between the threads of one run; a formula is parsed once, on first
 * use, against the grammar taken with the set.
 */
public final class RuleSet {

    /**
     * One rule as the set holds it.
     *
     * @param inForce the version in force on the set's day, or null when there is none
     */
    record Entry(String key, String payrollTypeScope, Long officeScope, Resolved inForce) {}

    private final LocalDate asOf;
    private final Map<String, Entry> entries;
    private final Supplier<RuleFormula.Grammar> grammar;
    private final Function<Long, Set<Long>> subtree;
    private final Map<Long, RuleFormula> formulas = new ConcurrentHashMap<>();
    private final List<Pinned> pinned;
    private final String hash;

    /**
     * @param grammar the formula grammar (an engine run passes the grammar read with the set, so a
     *     grammar change during the run cannot reach it either)
     * @param subtree the offices under an office, itself included
     */
    RuleSet(
            LocalDate asOf,
            Collection<Entry> entries,
            Supplier<RuleFormula.Grammar> grammar,
            Function<Long, Set<Long>> subtree) {
        this.asOf = Objects.requireNonNull(asOf, "asOf");
        this.entries = entries.stream().collect(Collectors.toUnmodifiableMap(Entry::key, e -> e));
        this.grammar = Objects.requireNonNull(grammar, "grammar");
        this.subtree = Objects.requireNonNull(subtree, "subtree");
        this.pinned =
                entries.stream()
                        .filter(e -> e.inForce() != null)
                        .map(
                                e ->
                                        new Pinned(
                                                e.key(),
                                                e.inForce().versionId(),
                                                e.inForce().versionNo()))
                        .sorted(Comparator.comparing(Pinned::ruleKey))
                        .toList();
        // Canonical text, one line per version in key order: the same versions, the same hash.
        this.hash =
                SecureTokens.sha256Hex(
                        pinned.stream()
                                .map(p -> p.ruleKey() + "|" + p.versionId() + "|" + p.versionNo())
                                .collect(Collectors.joining("\n")));
    }

    /** The day the versions are in force on. */
    public LocalDate asOf() {
        return asOf;
    }

    /** {@code computation_run.rule_version_set_hash} (SHA-256 of the versions, key order). */
    public String hash() {
        return hash;
    }

    /** The versions held, in key order. */
    public List<Pinned> versions() {
        return pinned;
    }

    public VersionSet versionSet() {
        return new VersionSet(asOf, pinned, hash);
    }

    /**
     * The version of {@code ruleKey} for {@code ctx}; empty when the rule's scope does not cover
     * it; refused when the rule is not in the set or has no version in force.
     */
    public Optional<Resolved> resolve(String ruleKey, Context ctx) {
        Objects.requireNonNull(ctx, "ctx");
        Entry entry = entries.get(ruleKey);
        if (entry == null) {
            throw notConfigured(ruleKey);
        }
        if (!RuleScope.covers(
                entry.payrollTypeScope(),
                entry.officeScope(),
                ctx.payrollType(),
                ctx.locationId(),
                subtree)) {
            return Optional.empty();
        }
        if (entry.inForce() == null) {
            throw notConfigured(ruleKey);
        }
        return Optional.of(entry.inForce());
    }

    /**
     * The band of a Slab rule covering {@code amount} for {@code qualifier}; empty when the rule
     * does not apply or no band covers it.
     */
    public Optional<Band> band(String ruleKey, Context ctx, String qualifier, BigDecimal amount) {
        return resolve(ruleKey, ctx)
                .flatMap(
                        r ->
                                SlabBands.bandFor(
                                                r.bands().stream().map(RuleSet::pure).toList(),
                                                qualifier,
                                                amount)
                                        .map(RuleSet::band));
    }

    /** The value of a Formula rule for {@code values}; empty when the rule does not apply. */
    public Optional<BigDecimal> evaluate(
            String ruleKey, Context ctx, Map<String, BigDecimal> values) {
        return resolve(ruleKey, ctx).map(r -> formula(r).number(values));
    }

    /** The condition of an Eligibility or Applicability rule; empty when it does not apply. */
    public Optional<Boolean> condition(
            String ruleKey, Context ctx, Map<String, BigDecimal> values) {
        return resolve(ruleKey, ctx).map(r -> formula(r).condition(values));
    }

    /** The version's formula, parsed once (refused: RULE-422-FORMULA when it holds none). */
    private RuleFormula formula(Resolved r) {
        if (r.formula() == null || r.formula().isBlank()) {
            throw new FciException(
                    ApiErrorCode.RULE_FORMULA_INVALID,
                    new Object[] {
                        "rule " + r.ruleKey() + " version " + r.versionNo() + " holds no formula"
                    },
                    List.of(),
                    null);
        }
        return formulas.computeIfAbsent(
                r.versionId(), id -> RuleFormula.parse(r.formula(), grammar.get()));
    }

    private FciException notConfigured(String ruleKey) {
        return new FciException(
                ApiErrorCode.RULE_NOT_CONFIGURED, new Object[] {ruleKey, asOf}, List.of(), null);
    }

    static SlabBands.Band pure(Band b) {
        return new SlabBands.Band(
                b.order(), b.from(), b.to(), b.rateOrAmount(), b.step(), b.qualifier());
    }

    static Band band(SlabBands.Band b) {
        return new Band(b.order(), b.from(), b.to(), b.rateOrAmount(), b.step(), b.qualifier());
    }
}
