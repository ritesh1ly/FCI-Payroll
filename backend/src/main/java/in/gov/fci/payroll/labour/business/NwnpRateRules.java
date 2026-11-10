package in.gov.fci.payroll.labour.business;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * M-05 ▸ NWNP rate lines (plan 22 phase 22b; SRS-5-1, SRS-5-3, M-05 field table; DDL {@code
 * nwnp_sor_rate}): each NWNP operation of the set at most once, its Piece of Rate (12,2, above
 * zero) held against a mandatory Unit of Measure from the list. Pure functions: no state, no I/O.
 */
public final class NwnpRateRules {

    /** DDL {@code nwnp_sor_rate.rate_version}. */
    public static final Set<String> VERSIONS = Set.of("SOR", "ASOR");

    /** One operation's rate as entered. */
    public record Line(String operationCode, BigDecimal pieceOfRate, String unitOfMeasure) {}

    private NwnpRateRules() {}

    /**
     * The first defect of the lines, or empty: VERSION, LINES (none), OPERATION (unknown or twice),
     * PIECE_OF_RATE, UNIT_OF_MEASURE (missing or not in the list).
     */
    public static Optional<String> defect(
            String version, List<Line> lines, Set<String> operations, Set<String> units) {
        if (version == null || !VERSIONS.contains(version)) {
            return Optional.of("VERSION");
        }
        if (lines == null || lines.isEmpty()) {
            return Optional.of("LINES");
        }
        Set<String> seen = new HashSet<>();
        for (Line l : lines) {
            if (l == null
                    || l.operationCode() == null
                    || !operations.contains(l.operationCode())
                    || !seen.add(l.operationCode())) {
                return Optional.of("OPERATION");
            }
            BigDecimal rate = l.pieceOfRate();
            if (rate == null
                    || rate.signum() <= 0
                    || rate.compareTo(LabourRateRules.MAX_AMOUNT) > 0
                    || rate.stripTrailingZeros().scale() > 2) {
                return Optional.of("PIECE_OF_RATE");
            }
            if (l.unitOfMeasure() == null || !units.contains(l.unitOfMeasure())) {
                return Optional.of("UNIT_OF_MEASURE");
            }
        }
        return Optional.empty();
    }

    /** The rate_set code of a depot's NWNP set of a version: one chain of versions per both. */
    public static String setCode(String depotCode, String version) {
        return "NWNP-" + depotCode + "-" + version;
    }
}
