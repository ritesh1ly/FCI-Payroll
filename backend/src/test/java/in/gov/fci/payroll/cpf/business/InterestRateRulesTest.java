package in.gov.fci.payroll.cpf.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.cpf.business.model.PfInterestRate.Source;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SRS-6-11, BR-CPF-033 and the SRS M-19 field table: the rules of a PF interest rate. */
class InterestRateRulesTest {

    private static final InterestRateRules.FinYear FY_2026 = InterestRateRules.finYear("2026-27");

    private static String code(Runnable call) {
        try {
            call.run();
        } catch (FciException e) {
            return e.getErrorCode().code();
        }
        return null;
    }

    @Test
    void BR_CPF_033_aFinancialYearRunsFromAprilToMarch() {
        assertThat(FY_2026.start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(FY_2026.end()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(FY_2026.code()).isEqualTo("2026-27");
        // The century turns: 2099-00.
        assertThat(InterestRateRules.finYear("2099-00").end()).isEqualTo(LocalDate.of(2100, 3, 31));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-28", "2026-26", "2026/27", "26-27", "2026-2027", "", " 2026-27"})
    void BR_CPF_033_aFinancialYearOutsideTheCurrentSystemFormatIsRefused(String code) {
        assertThat(code(() -> InterestRateRules.finYear(code))).isEqualTo("CPF-422-FIN-YEAR");
    }

    @Test
    void BR_CPF_033_aMissingFinancialYearIsRefused() {
        assertThat(code(() -> InterestRateRules.finYear(null))).isEqualTo("CPF-422-FIN-YEAR");
    }

    @Test
    void BR_CPF_033_theYearContainsItsFirstAndLastDayOnly() {
        assertThat(FY_2026.contains(LocalDate.of(2026, 4, 1))).isTrue();
        assertThat(FY_2026.contains(LocalDate.of(2027, 3, 31))).isTrue();
        assertThat(FY_2026.contains(LocalDate.of(2026, 3, 31))).isFalse();
        assertThat(FY_2026.contains(LocalDate.of(2027, 4, 1))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.01", "9.50", "8.65", "100", "100.00", "9.5"})
    void SRS_6_11_aRateIsAPercentageFrom0To100WithTwoDecimals(String rate) {
        assertThatCode(() -> InterestRateRules.requireRate(new BigDecimal(rate)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "100.01", "9.505", "101"})
    void SRS_6_11_aRateOutsideItsRangeOrPrecisionIsRefused(String rate) {
        assertThat(code(() -> InterestRateRules.requireRate(new BigDecimal(rate))))
                .isEqualTo("CPF-422-RATE");
    }

    @Test
    void SRS_6_11_aMissingRateIsRefused() {
        assertThat(code(() -> InterestRateRules.requireRate(null))).isEqualTo("CPF-422-RATE");
    }

    @Test
    void BR_CPF_033_aRateAppliesWithinItsYearAndEndsNotBeforeItStarts() {
        LocalDate first = LocalDate.of(2026, 4, 1);
        LocalDate last = LocalDate.of(2027, 3, 31);
        assertThatCode(() -> InterestRateRules.requirePeriod(FY_2026, first, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> InterestRateRules.requirePeriod(FY_2026, first, last))
                .doesNotThrowAnyException();
        assertThatCode(() -> InterestRateRules.requirePeriod(FY_2026, last, last))
                .doesNotThrowAnyException();
        assertThat(code(() -> InterestRateRules.requirePeriod(FY_2026, null, null)))
                .isEqualTo("CPF-422-PERIOD");
        assertThat(code(() -> InterestRateRules.requirePeriod(FY_2026, first.minusDays(1), null)))
                .isEqualTo("CPF-422-PERIOD");
        assertThat(code(() -> InterestRateRules.requirePeriod(FY_2026, last.plusDays(1), null)))
                .isEqualTo("CPF-422-PERIOD");
        assertThat(code(() -> InterestRateRules.requirePeriod(FY_2026, last, last.minusDays(1))))
                .isEqualTo("CPF-422-PERIOD");
        assertThat(code(() -> InterestRateRules.requirePeriod(FY_2026, first, last.plusDays(1))))
                .isEqualTo("CPF-422-PERIOD");
    }

    @Test
    void SRS_6_11_bonusInterestIsDeclaredOverTheTrustRateOnly() {
        assertThatCode(() -> InterestRateRules.requireBonus(Source.FCI_TRUST, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> InterestRateRules.requireBonus(Source.RPFC, null))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                InterestRateRules.requireBonus(
                                        Source.FCI_TRUST, new BigDecimal("2.50")))
                .doesNotThrowAnyException();
        assertThat(code(() -> InterestRateRules.requireBonus(Source.RPFC, new BigDecimal("2.50"))))
                .isEqualTo("CPF-422-BONUS");
        assertThat(
                        code(
                                () ->
                                        InterestRateRules.requireBonus(
                                                Source.FCI_TRUST, new BigDecimal("100.01"))))
                .isEqualTo("CPF-422-RATE");
    }

    @Test
    void SRS_6_11_theFciRateIsForActiveAndDeputedMembersTheEpfoRateForInactiveOnes() {
        assertThat(InterestRateRules.population(Source.FCI_TRUST)).isEqualTo("ACTIVE_AND_DEPUTED");
        assertThat(InterestRateRules.population(Source.RPFC)).isEqualTo("INACTIVE");
    }

    @Test
    void BR_CPF_033_aNewRateStartsAfterTheApprovedRateItReplaces() {
        LocalDate approved = LocalDate.of(2026, 4, 1);
        assertThatCode(() -> InterestRateRules.requireAfter(approved, Optional.empty()))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                InterestRateRules.requireAfter(
                                        approved.plusDays(1), Optional.of(approved)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> InterestRateRules.requireAfter(approved, Optional.of(approved)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode().code()).isEqualTo("CPF-409-RATE-ORDER");
                            assertThat(e.getMessageArgs()).containsExactly("2026-04-01");
                        });
        assertThat(
                        code(
                                () ->
                                        InterestRateRules.requireAfter(
                                                approved.minusDays(1), Optional.of(approved))))
                .isEqualTo("CPF-409-RATE-ORDER");
    }

    @Test
    void BR_CPF_033_aSeriesIsOneYearFundAndSource() {
        assertThat(InterestRateRules.seriesKey("2026-27", "CPF", Source.RPFC))
                .isEqualTo("2026-27|CPF|RPFC");
        assertThat(InterestRateRules.FUNDS).containsExactly("CPF", "PF", "VPF", "GPF");
    }
}
