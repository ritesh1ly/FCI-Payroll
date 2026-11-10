package in.gov.fci.payroll.tax.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.tax.business.model.OutputDefinition;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The year the payroll engines hand to the tax computation (SRS-7-12, SRS-7-19, SRS-7-27): its
 * totals, the months still projected over which the TDS due is spread, and the actual view only
 * when every month is locked.
 */
class TaxableEarningsTest {

    private static TaxableEarnings.Month month(String yymm, boolean actual, String nps) {
        return new TaxableEarnings.Month(
                yymm,
                actual,
                new BigDecimal("1000"),
                new BigDecimal("80"),
                new BigDecimal(nps),
                List.of());
    }

    @Test
    void SRS_7_12_theTdsDueIsSpreadOverTheProjectedMonthsAtLeastOne() {
        TaxableEarnings.Year part =
                new TaxableEarnings.Year(
                        List.of(
                                month("202504", true, "10"),
                                month("202505", false, "15"),
                                month("202506", false, "0")));
        assertThat(part.remainingMonths()).isEqualTo(2);
        assertThat(part.allActual()).isFalse();
        assertThat(part.employerNps()).isEqualByComparingTo("25");
        assertThat(part.gross()).isEqualByComparingTo("3000");
        assertThat(part.tdsDeducted()).isEqualByComparingTo("240");

        TaxableEarnings.Year locked =
                new TaxableEarnings.Year(
                        List.of(month("202504", true, "0"), month("202505", true, "0")));
        assertThat(locked.remainingMonths()).as("never zero months").isEqualTo(1);
        assertThat(locked.allActual()).isTrue();
    }

    @Test
    void BR_MIS_002_aDefinitionIsActiveOnlyWhenFlaggedSo() {
        OutputDefinition d =
                org.mockito.Mockito.mock(
                        OutputDefinition.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(d, "activeFlag", "Y");
        assertThat(d.isActive()).isTrue();
        ReflectionTestUtils.setField(d, "activeFlag", "N");
        assertThat(d.isActive()).isFalse();
    }
}
