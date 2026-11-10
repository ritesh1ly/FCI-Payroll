package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.labour.business.NormRules.Norms;
import in.gov.fci.payroll.labour.business.NormRules.Slab;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Depot incentive norms (plan 20 phase 20d; SRS-3-6, SRS-3-12): the norms, the contiguous slab
 * bands of each bag-weight table, the effective date after the depot's latest set.
 */
class NormRulesTest {

    private static Slab band1(int order, long from, Long to, String rate) {
        return new Slab("BAND_1", order, from, to, new BigDecimal(rate));
    }

    /** SRS-3-6 Band 1: 30-bag steps 8 / 15 / 35 / 50 percent. */
    private static List<Slab> band1() {
        return List.of(
                band1(1, 1, 30L, "8"),
                band1(2, 31, 60L, "15"),
                band1(3, 61, 90L, "35"),
                band1(4, 91, null, "50"));
    }

    @Test
    void SRS_3_12_theNormsAreBagsAndTheHandlingNormHasItsMlnAndMlsw() {
        Norms ok = new Norms(250L, 20L, 0L, 10L, new BigDecimal("8.5"));
        assertThat(NormRules.normsDefect(ok)).isEmpty();
        assertThat(NormRules.normsDefect(new Norms(null, 20L, 0L, 10L, BigDecimal.ONE)))
                .contains("NORM");
        assertThat(NormRules.normsDefect(new Norms(250L, -1L, 0L, 10L, BigDecimal.ONE)))
                .contains("NORM");
        assertThat(NormRules.normsDefect(new Norms(250L, 20L, 1_000_000L, 10L, BigDecimal.ONE)))
                .contains("NORM");
        assertThat(NormRules.normsDefect(new Norms(250L, 20L, 0L, 0L, BigDecimal.ONE)))
                .contains("MLN");
        assertThat(NormRules.normsDefect(new Norms(250L, 20L, 0L, null, BigDecimal.ONE)))
                .contains("MLN");
        assertThat(NormRules.normsDefect(new Norms(250L, 20L, 0L, 10L, BigDecimal.ZERO)))
                .contains("MLSW");
        assertThat(NormRules.normsDefect(new Norms(250L, 20L, 0L, 10L, null))).contains("MLSW");
    }

    // SC-20-0019, SC-20-0024: overlapping or non-contiguous slab bands are rejected.
    @Test
    void SRS_3_6_slabBandsAreContiguousAndDoNotOverlap() {
        assertThat(NormRules.slabsDefect(band1())).isEmpty();
        List<Slab> both = new java.util.ArrayList<>(band1());
        both.add(new Slab("BAND_2", 1, 1, 20L, new BigDecimal("8")));
        both.add(new Slab("BAND_2", 2, 21, null, new BigDecimal("15.25")));
        assertThat(NormRules.slabsDefect(both)).isEmpty();
        assertThat(NormRules.slabsDefect(List.of())).contains("SLAB_EMPTY");
        assertThat(NormRules.slabsDefect(null)).contains("SLAB_EMPTY");
        assertThat(NormRules.slabsDefect(List.of(new Slab("BAND_3", 1, 1, null, BigDecimal.ONE))))
                .contains("SLAB_TABLE");
        assertThat(NormRules.slabsDefect(List.of(band1(1, 2, 30L, "8")))).contains("SLAB_START");
        assertThat(NormRules.slabsDefect(List.of(band1(1, 1, 30L, "8"), band1(2, 32, null, "15"))))
                .as("a gap")
                .contains("SLAB_GAP");
        assertThat(NormRules.slabsDefect(List.of(band1(1, 1, 30L, "8"), band1(2, 25, null, "15"))))
                .as("an overlap")
                .contains("SLAB_GAP");
        assertThat(NormRules.slabsDefect(List.of(band1(1, 1, null, "8"), band1(2, 31, null, "15"))))
                .contains("SLAB_OPEN");
        assertThat(NormRules.slabsDefect(List.of(band1(1, 1, 0L, "8")))).contains("SLAB_RANGE");
        assertThat(NormRules.slabsDefect(List.of(band1(1, 1, null, "100.5"))))
                .contains("SLAB_RATE");
        assertThat(NormRules.slabsDefect(List.of(band1(1, 1, null, "-1")))).contains("SLAB_RATE");
        assertThat(NormRules.slabsDefect(List.of(band1(1, 1, null, "8.125"))))
                .contains("SLAB_RATE");
        assertThat(NormRules.slabsDefect(List.of(new Slab("BAND_1", 1, 1, null, null))))
                .contains("SLAB_RATE");
        assertThat(NormRules.slabsDefect(List.of(band1(2, 31, null, "15"), band1(1, 1, 30L, "8"))))
                .as("bands are read in their order")
                .isEmpty();
    }

    // SC-20-0020: an effective date before the depot's latest is rejected.
    @Test
    void SRS_3_12_aNewSetStartsAfterTheDepotsLatest() {
        LocalDate today = LocalDate.of(2026, 10, 28);
        assertThat(NormRules.effectiveDefect(today, null)).isEmpty();
        assertThat(NormRules.effectiveDefect(today.plusDays(1), today)).isEmpty();
        assertThat(NormRules.effectiveDefect(null, null)).contains("EFFECTIVE_FROM");
        assertThat(NormRules.effectiveDefect(today, today)).contains("EFFECTIVE_BEFORE_LATEST");
        assertThat(NormRules.effectiveDefect(today, today.plusDays(5)))
                .contains("EFFECTIVE_BEFORE_LATEST");
    }

    // UI #77 / US-20-22 AC4: a revision of the depot's norms may start in the past; M-06 refuses
    // only a date not after the depot's latest set (the effective-dated norms of SRS-3-12). BMAD
    // TR-051: the code 137 recovery of a retrospective revision is not built (PENDING LBR-20d-2).
    @Test
    void SRS_3_12_aRetrospectiveSetAfterTheLatestIsAccepted() {
        LocalDate latest = LocalDate.of(2026, 4, 1);
        assertThat(NormRules.effectiveDefect(LocalDate.of(2026, 9, 1), latest)).isEmpty();
        assertThat(NormRules.effectiveDefect(latest.plusDays(1), latest)).isEmpty();
        assertThat(NormRules.effectiveDefect(latest, latest)).contains("EFFECTIVE_BEFORE_LATEST");
        assertThat(NormRules.effectiveDefect(latest.minusDays(1), latest))
                .contains("EFFECTIVE_BEFORE_LATEST");
        assertThat(NormRules.effectiveDefect(LocalDate.of(2020, 1, 1), null))
                .as("the first set of a depot")
                .isEmpty();
    }

    @Test
    void SRS_3_12_theBoundsOfEachNormAndRateAreAccepted() {
        assertThat(NormRules.normsDefect(new Norms(0L, 0L, NormRules.MAX_NORM, 1L, BigDecimal.ONE)))
                .isEmpty();
        assertThat(
                        NormRules.slabsDefect(
                                List.of(
                                        band1(1, 1, 1L, "0"),
                                        band1(2, 2, 2L, "100"),
                                        band1(3, 3, null, "100.00"))))
                .as("a one-bag band, a 0 % and a 100 % rate")
                .isEmpty();
        assertThat(NormRules.slabsDefect(List.of(band1(1, 1, null, "100.01"))))
                .contains("SLAB_RATE");
        assertThat(NormRules.slabsDefect(List.of(band1(1, 1, null, "-0.01"))))
                .contains("SLAB_RATE");
    }
}
