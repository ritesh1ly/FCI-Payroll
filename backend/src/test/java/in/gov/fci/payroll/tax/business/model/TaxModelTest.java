package in.gov.fci.payroll.tax.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** SRS-7-2, SRS-7-4: the windows of a section version and a PT state, and the audited image. */
class TaxModelTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final LocalDate MARCH = LocalDate.of(2027, 3, 31);

    @Test
    void SRS_7_2_aSectionVersionIsInForceFromItsStartToTheDayBeforeItsSuccessor() {
        ItSection s = ItSection.approved("80C", "Life", new BigDecimal("150000"), "OLD", APRIL);
        assertThat(s.inForceOn(APRIL.minusDays(1))).isFalse();
        assertThat(s.inForceOn(APRIL)).isTrue();
        assertThat(s.inForceOn(MARCH.plusYears(5))).isTrue();
        s.endBefore(MARCH.plusDays(1));
        assertThat(s.getEffectiveTo()).isEqualTo(MARCH);
        assertThat(s.inForceOn(MARCH)).isTrue();
        assertThat(s.inForceOn(MARCH.plusDays(1))).isFalse();
        assertThat(s.getMaxDeductionAmount()).isEqualByComparingTo("150000");
        assertThat(s.getSectionDescription()).isEqualTo("Life");
    }

    @Test
    void SRS_7_4_aStateIsInForceInItsWindowAndItsImageNamesEachField() {
        PtState s = PtState.of("MH");
        s.apply("Maharashtra", true, "PTR-1", "PT.SLAB", "MONTHLY", APRIL, MARCH);
        assertThat(s.inForceOn(APRIL.minusDays(1))).isFalse();
        assertThat(s.inForceOn(APRIL)).isTrue();
        assertThat(s.inForceOn(MARCH)).isTrue();
        assertThat(s.inForceOn(MARCH.plusDays(1))).isFalse();
        assertThat(s.image())
                .containsEntry("state_name", "Maharashtra")
                .containsEntry("pt_applicable_flag", "Y")
                .containsEntry("effective_from", "2026-04-01")
                .containsEntry("effective_to", "2027-03-31");
        s.apply("Maharashtra", false, null, null, null, APRIL, null);
        assertThat(s.inForceOn(MARCH.plusYears(3))).isTrue();
        assertThat(s.image())
                .containsEntry("pt_applicable_flag", "N")
                .containsEntry("effective_to", null)
                .containsEntry("slab_rule_key", null);
    }
}
