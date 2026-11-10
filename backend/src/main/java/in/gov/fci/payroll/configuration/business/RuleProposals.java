package in.gov.fci.payroll.configuration.business;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The checks on an M-08 rule version before it is submitted and again before it is applied
 * (SRS-19-1, SRS-19-5, SRS-19-6, SRS-19-12; FRS V0.5 BR-RULE-001 … 004; KB RE-1 … RE-4): the value
 * fits the rule's {@link RuleValueShape}, the dates do not conflict with another version, and an
 * explicit end date falls inside the version it ends. Pure rules over rows already read; the
 * formula grammar is checked by the engine, which knows the grammar in force.
 */
public final class RuleProposals {

    /** {@code rule_version.formula_expression} is VARCHAR2(4000). */
    public static final int FORMULA_MAX = RuleFormula.MAX_LENGTH;

    /** SRS M-08 "Rule Value(s)": Numeric (12,2) per value. */
    static final int INTEGER_DIGITS = 10;

    static final int DECIMALS = 2;

    /** What a proposal carries as its value; exactly the part its shape needs is filled. */
    public record Value(BigDecimal scalar, String formula, List<SlabBands.Band> bands) {

        boolean hasScalar() {
            return scalar != null;
        }

        boolean hasFormula() {
            return formula != null && !formula.isBlank();
        }

        boolean hasBands() {
            return bands != null && !bands.isEmpty();
        }
    }

    /**
     * A version of the rule as the date checks read it ({@code id} null for one not yet written).
     */
    public record Dated(Long id, int versionNo, LocalDate from, LocalDate to, boolean approved) {}

    private RuleProposals() {}

    /**
     * SRS-19-1 / SRS-19-5: a SCALAR rule carries one number, a SLAB rule its bands (contiguous, not
     * overlapping, per qualifier), a formula rule its formula; nothing else, so a version never
     * holds a value the engine would silently ignore. Every number fits Numeric (12,2).
     */
    public static void requireValue(RuleValueShape shape, Value value) {
        Objects.requireNonNull(shape, "shape");
        Value v = value == null ? new Value(null, null, null) : value;
        // The parts the proposal fills must be exactly the one the shape holds its value in.
        Set<RuleValueShape.Part> given = EnumSet.noneOf(RuleValueShape.Part.class);
        if (v.hasScalar()) {
            given.add(RuleValueShape.Part.NUMBER);
        }
        if (v.hasBands()) {
            given.add(RuleValueShape.Part.BANDS);
        }
        if (v.hasFormula()) {
            given.add(RuleValueShape.Part.FORMULA);
        }
        if (!given.equals(EnumSet.of(shape.part()))) {
            throw invalid("this rule takes " + shape.part().described() + " and nothing else");
        }
        if (shape.part() == RuleValueShape.Part.NUMBER) {
            requireAmount("the value", v.scalar());
        } else if (shape.part() == RuleValueShape.Part.BANDS) {
            for (SlabBands.Band b : v.bands()) {
                requireAmount("band " + b.order() + " from", b.from());
                requireAmount("band " + b.order() + " to", b.to());
                requireAmount("band " + b.order() + " rate or amount", b.rateOrAmount());
                requireAmount("band " + b.order() + " step", b.step());
            }
            SlabBands.validate(v.bands());
        }
    }

    /**
     * At most 10 digits before the decimal point and 2 after it; null passes (a missing value is
     * the caller's check). Trailing zeros do not count: 12.500 is 12.5.
     */
    static void requireAmount(String what, BigDecimal amount) {
        if (amount == null) {
            return;
        }
        BigDecimal plain = amount.stripTrailingZeros();
        int decimals = Math.max(plain.scale(), 0);
        int integerDigits = plain.precision() - plain.scale();
        if (decimals > DECIMALS || integerDigits > INTEGER_DIGITS) {
            throw invalid(what + " exceeds 10 digits and 2 decimals");
        }
    }

    /**
     * SRS-19-12 / RE-1 / RE-2: the effective-from date is given; a new version starts after every
     * approved version (the one it supersedes is ended the day before on approval) and on a day no
     * other version of the rule holds (uk_rule_version_eff); an effective-to date is not earlier
     * than the effective-from date (SRS-19-6). The conflicting version is named.
     */
    public static void requireFreeDates(LocalDate from, LocalDate to, List<Dated> versions) {
        if (from == null) {
            throw invalid("the effective-from date is mandatory");
        }
        if (to != null && to.isBefore(from)) {
            throw invalid("the effective-to date is earlier than the effective-from date");
        }
        Optional<Dated> latest = latestApproved(versions);
        if (latest.isPresent() && !from.isAfter(latest.get().from())) {
            throw new FciException(
                    ApiErrorCode.RULE_VERSION_CONFLICT,
                    new Object[] {latest.get().versionNo(), latest.get().from()},
                    List.of(),
                    null);
        }
        for (Dated v : versions) {
            if (v.from().equals(from)) {
                throw new FciException(
                        ApiErrorCode.MASTER_DATE_TAKEN, new Object[] {from}, List.of(), null);
            }
        }
    }

    /**
     * SRS-19-6 / RE-4: the version ended is the latest approved one (the one with no successor),
     * and its last day is not before its first.
     *
     * @return the version to end
     */
    public static Dated requireEnd(LocalDate last, List<Dated> versions) {
        if (last == null) {
            throw invalid("the end date is mandatory");
        }
        Dated latest =
                latestApproved(versions)
                        .orElseThrow(() -> invalid("the rule has no approved version to end"));
        if (last.isBefore(latest.from())) {
            throw new FciException(
                    ApiErrorCode.RULE_END_INVALID, new Object[] {latest.from()}, List.of(), null);
        }
        return latest;
    }

    private static Optional<Dated> latestApproved(List<Dated> versions) {
        return versions.stream().filter(Dated::approved).max(Comparator.comparing(Dated::from));
    }

    private static FciException invalid(String why) {
        return new FciException(
                ApiErrorCode.RULE_VALUE_INVALID, new Object[] {why}, List.of(), null);
    }
}
