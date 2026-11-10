package in.gov.fci.payroll.configuration.business;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Slab bands of a rule version (SRS-19-5, BR-RULE-003, KB RE-3; DDL {@code rule_slab_band}): within
 * each band set — one per {@code band_qualifier} (a category, a city class, a bag-weight band; none
 * = one set) — the bands, in {@code band_order}, must be contiguous and must not overlap: each band
 * starts exactly where the one before it ends, and only the last may be open-ended ({@code
 * range_to} NULL = "and above"). Contiguity is a property of the whole set, so it is checked here
 * on submit; a row-level trigger cannot see the set (DDL comment).
 *
 * <p>A band covers {@code range_from <= x < range_to}: the boundary value belongs to the band it
 * starts.
 */
public final class SlabBands {

    /** One band as the rule version holds it. */
    public record Band(
            int order,
            BigDecimal from,
            BigDecimal to,
            BigDecimal rateOrAmount,
            BigDecimal step,
            String qualifier) {}

    /** {@code rule_slab_band.band_order} is NUMBER(4). */
    public static final int MAX_ORDER = 9999;

    private SlabBands() {}

    /**
     * Refused (RULE-422-SLAB) naming the first fault: empty set, gap, overlap, or an open band
     * before the last.
     */
    public static void validate(List<Band> bands) {
        if (bands == null || bands.isEmpty()) {
            throw invalid("a slab rule needs at least one band");
        }
        // DDL rule_slab_band: band_order NUMBER(4), unique within the version across every
        // qualifier (uk_rule_slab_band): checked here, so a set never fails at approval.
        Set<Integer> orders = new HashSet<>();
        for (Band b : bands) {
            if (b.order() < 1 || b.order() > MAX_ORDER) {
                throw invalid("band order " + b.order() + " is outside 1 to " + MAX_ORDER);
            }
            if (!orders.add(b.order())) {
                throw invalid("band order " + b.order() + " is used twice");
            }
        }
        for (Map.Entry<String, List<Band>> set : sets(bands).entrySet()) {
            String label = set.getKey().isEmpty() ? "" : " (" + set.getKey() + ")";
            List<Band> ordered = set.getValue();
            for (int i = 0; i < ordered.size(); i++) {
                Band b = ordered.get(i);
                if (b.from() == null || b.rateOrAmount() == null) {
                    throw invalid(
                            "band " + b.order() + label + " needs a start and a rate or amount");
                }
                if (b.to() != null && b.to().compareTo(b.from()) <= 0) {
                    throw invalid("band " + b.order() + label + " ends at or before its start");
                }
                if (i == 0) {
                    continue;
                }
                Band prev = ordered.get(i - 1);
                if (prev.to() == null) {
                    throw invalid(
                            "band "
                                    + prev.order()
                                    + label
                                    + " is open-ended but is not the last band");
                }
                int c = b.from().compareTo(prev.to());
                if (c > 0) {
                    throw invalid(
                            "gap between band " + prev.order() + " and band " + b.order() + label);
                }
                if (c < 0) {
                    throw invalid("band " + b.order() + " overlaps band " + prev.order() + label);
                }
            }
        }
    }

    /** The band of {@code qualifier} that covers {@code amount}, if any. */
    public static Optional<Band> bandFor(List<Band> bands, String qualifier, BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        List<Band> set = sets(bands).get(qualifier == null ? "" : qualifier);
        if (set == null) {
            return Optional.empty();
        }
        return set.stream()
                .filter(
                        b ->
                                b.from().compareTo(amount) <= 0
                                        && (b.to() == null || amount.compareTo(b.to()) < 0))
                .findFirst();
    }

    private static Map<String, List<Band>> sets(List<Band> bands) {
        Map<String, List<Band>> out = new TreeMap<>();
        for (Band b : bands) {
            out.computeIfAbsent(b.qualifier() == null ? "" : b.qualifier(), k -> new ArrayList<>())
                    .add(b);
        }
        out.values().forEach(list -> list.sort(Comparator.comparingInt(Band::order)));
        return out;
    }

    private static FciException invalid(String why) {
        return new FciException(
                ApiErrorCode.RULE_SLAB_INVALID, new Object[] {why}, List.of(), null);
    }
}
