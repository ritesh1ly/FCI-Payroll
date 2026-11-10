package in.gov.fci.payroll.labour.business;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Depot incentive norm rules (plan 20 phase 20d; SRS-3-6, SRS-3-12; M-06 use case "Maintain Depot
 * Incentive Norms"). Pure functions: no state, no I/O.
 */
public final class NormRules {

    /** The two above-norm slab tables, one per bag-weight band (SRS-3-6). */
    public static final List<String> SLAB_TABLES = List.of("BAND_1", "BAND_2");

    /** M-06: norms are numbers of bags, up to six digits. */
    public static final long MAX_NORM = 999_999;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** One band of a slab table: bags from, to (open on the last band), the rate in percent. */
    public record Slab(String table, int order, long from, Long to, BigDecimal ratePercent) {}

    /** A depot's norm set as entered (bags; MLN and MLSW of the Handling Norm). */
    public record Norms(
            Long handling,
            Long height,
            Long lead,
            Long minLabourNumber,
            BigDecimal minSlabWeight) {}

    private NormRules() {}

    /**
     * M-06: what is wrong with the norms: NORM (each of Handling, Height and Lead a whole number of
     * bags, 0 to 999999), MLN (1 or more), MLSW (more than 0).
     */
    public static Optional<String> normsDefect(Norms n) {
        for (Long norm : new Long[] {n.handling(), n.height(), n.lead()}) {
            if (norm == null || norm < 0 || norm > MAX_NORM) {
                return Optional.of("NORM");
            }
        }
        if (n.minLabourNumber() == null || n.minLabourNumber() < 1) {
            return Optional.of("MLN");
        }
        if (n.minSlabWeight() == null || n.minSlabWeight().signum() <= 0) {
            return Optional.of("MLSW");
        }
        return Optional.empty();
    }

    /**
     * M-06 (alternate flow): slab bands are contiguous and do not overlap. Each table of {@link
     * #SLAB_TABLES} present starts at bag 1, each band starts the bag after the band before ends,
     * only the last band is open, and each rate is 0 to 100 percent with at most two decimals.
     * Answers SLAB_EMPTY, SLAB_TABLE, SLAB_START, SLAB_GAP (a gap or an overlap), SLAB_OPEN,
     * SLAB_RANGE or SLAB_RATE.
     */
    public static Optional<String> slabsDefect(List<Slab> slabs) {
        if (slabs == null || slabs.isEmpty()) {
            return Optional.of("SLAB_EMPTY");
        }
        Map<String, List<Slab>> tables = new TreeMap<>();
        for (Slab s : slabs) {
            if (!SLAB_TABLES.contains(s.table())) {
                return Optional.of("SLAB_TABLE");
            }
            tables.computeIfAbsent(s.table(), k -> new java.util.ArrayList<>()).add(s);
        }
        for (List<Slab> table : tables.values()) {
            table.sort(Comparator.comparingInt(Slab::order));
            long next = 1;
            for (int i = 0; i < table.size(); i++) {
                Slab s = table.get(i);
                if (s.ratePercent() == null
                        || s.ratePercent().signum() < 0
                        || s.ratePercent().compareTo(HUNDRED) > 0
                        || s.ratePercent().stripTrailingZeros().scale() > 2) {
                    return Optional.of("SLAB_RATE");
                }
                if (i == 0 && s.from() != 1) {
                    return Optional.of("SLAB_START");
                }
                if (s.from() != next) {
                    return Optional.of("SLAB_GAP");
                }
                if (s.to() == null) {
                    if (i != table.size() - 1) {
                        return Optional.of("SLAB_OPEN");
                    }
                } else {
                    if (s.to() < s.from()) {
                        return Optional.of("SLAB_RANGE");
                    }
                    next = s.to() + 1;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * M-06 (alternate flow, UC l.4258): a new norm set starts after the latest effective date of
     * the depot's sets. It may be retrospective (SRS-3-10 l.4237: a revision of the norms with an
     * earlier effect, the over-recovered incentive recovered under code 137 with C-06), so a date
     * before today is accepted; the first set of a depot takes any date.
     */
    public static Optional<String> effectiveDefect(LocalDate from, LocalDate latest) {
        if (from == null) {
            return Optional.of("EFFECTIVE_FROM");
        }
        if (latest != null && !from.isAfter(latest)) {
            return Optional.of("EFFECTIVE_BEFORE_LATEST");
        }
        return Optional.empty();
    }
}
