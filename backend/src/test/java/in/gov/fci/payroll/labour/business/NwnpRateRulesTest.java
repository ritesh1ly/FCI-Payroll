package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.labour.business.NwnpRateRules.Line;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M-05 ▸ NWNP rate lines (plan 22 phase 22b; SRS-5-1, SRS-5-3, M-05 field table): each NWNP
 * operation once, its Piece of Rate held against a mandatory Unit of Measure.
 */
class NwnpRateRulesTest {

    private static final Set<String> OPS = Set.of("W2P", "PBNW");
    private static final Set<String> UNITS = Set.of("PER_100_BAGS", "PER_HOUR");

    private static Line line(String op, String rate, String unit) {
        return new Line(op, rate == null ? null : new BigDecimal(rate), unit);
    }

    // SC-22-0003, SC-22-0018, SC-22-0019
    @Test
    void SRS_5_3_eachOperationHasAPieceOfRateHeldAgainstAUnitOfMeasure() {
        List<Line> ok =
                List.of(line("W2P", "12.50", "PER_100_BAGS"), line("PBNW", "40", "PER_HOUR"));
        assertThat(NwnpRateRules.defect("ASOR", ok, OPS, UNITS)).isEmpty();
        assertThat(NwnpRateRules.defect("SOR", ok, OPS, UNITS)).isEmpty();
        assertThat(NwnpRateRules.defect("PIECE", ok, OPS, UNITS)).contains("VERSION");
        assertThat(NwnpRateRules.defect(null, ok, OPS, UNITS)).contains("VERSION");
        assertThat(NwnpRateRules.defect("ASOR", List.of(), OPS, UNITS)).contains("LINES");
        assertThat(NwnpRateRules.defect("ASOR", null, OPS, UNITS)).contains("LINES");
        assertThat(NwnpRateRules.defect("ASOR", List.of(line("T2P", "1", "PER_HOUR")), OPS, UNITS))
                .contains("OPERATION");
        assertThat(
                        NwnpRateRules.defect(
                                "ASOR",
                                List.of(line("W2P", "1", "PER_HOUR"), line("W2P", "2", "PER_HOUR")),
                                OPS,
                                UNITS))
                .contains("OPERATION");
        assertThat(NwnpRateRules.defect("ASOR", Arrays.asList((Line) null), OPS, UNITS))
                .contains("OPERATION");
        assertThat(NwnpRateRules.defect("ASOR", List.of(line(null, "1", "PER_HOUR")), OPS, UNITS))
                .contains("OPERATION");
        assertThat(NwnpRateRules.defect("ASOR", List.of(line("W2P", null, "PER_HOUR")), OPS, UNITS))
                .contains("PIECE_OF_RATE");
        assertThat(NwnpRateRules.defect("ASOR", List.of(line("W2P", "0", "PER_HOUR")), OPS, UNITS))
                .contains("PIECE_OF_RATE");
        assertThat(
                        NwnpRateRules.defect(
                                "ASOR", List.of(line("W2P", "1.005", "PER_HOUR")), OPS, UNITS))
                .contains("PIECE_OF_RATE");
        assertThat(
                        NwnpRateRules.defect(
                                "ASOR",
                                List.of(line("W2P", "10000000000", "PER_HOUR")),
                                OPS,
                                UNITS))
                .contains("PIECE_OF_RATE");
        assertThat(
                        NwnpRateRules.defect(
                                "ASOR",
                                List.of(line("W2P", "9999999999.99", "PER_HOUR")),
                                OPS,
                                UNITS))
                .isEmpty();
        assertThat(NwnpRateRules.defect("ASOR", List.of(line("W2P", "1", null)), OPS, UNITS))
                .as("the Unit of Measure is mandatory")
                .contains("UNIT_OF_MEASURE");
        assertThat(NwnpRateRules.defect("ASOR", List.of(line("W2P", "1", "PER_TONNE")), OPS, UNITS))
                .contains("UNIT_OF_MEASURE");
    }

    @Test
    void SRS_5_2_oneChainOfSetsPerDepotAndVersion() {
        assertThat(NwnpRateRules.setCode("DP-DL01", "ASOR")).isEqualTo("NWNP-DP-DL01-ASOR");
        assertThat(NwnpRateRules.setCode("DP-DL01", "SOR")).isEqualTo("NWNP-DP-DL01-SOR");
    }
}
