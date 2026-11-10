package in.gov.fci.payroll.cpf.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The employer's CPF share and its pension split (plan 07 phase 07i part 2; SRS-6-1, SRS-6-7,
 * BR-CPF-017, SRS-G-20, CF-07). Rates are fixtures, not FCI's.
 */
class EmployerShareTest {

    private static EmployerShare share(String base, String employer, String eps, String pohw) {
        return EmployerShare.of(
                new BigDecimal(base),
                new BigDecimal(employer),
                new BigDecimal(eps),
                new BigDecimal(pohw));
    }

    // SC-01-0015: the one EPS / Higher-EPS rounding rule, whichever engine computed the wage.
    @Test
    void SRS_6_7_thePensionPartsAreKeptToThePaiseAndRoundedOnceOnTheirTotal() {
        EmployerShare s = share("45000", "12", "8.33", "1.16");
        assertThat(s.employer()).isEqualByComparingTo("5400");
        assertThat(s.eps()).isEqualByComparingTo("3748.50");
        assertThat(s.pohw()).isEqualByComparingTo("522.00");
        assertThat(s.rounding()).isEqualByComparingTo("0.50");
        assertThat(s.pension()).isEqualByComparingTo("4271");
        assertThat(s.splitExceeds()).isFalse();

        EmployerShare odd = share("15001", "12", "8.33", "1.16");
        assertThat(odd.employer()).as("1800.12 -> 1800").isEqualByComparingTo("1800");
        assertThat(odd.eps()).isEqualByComparingTo("1249.58");
        assertThat(odd.pohw()).isEqualByComparingTo("174.01");
        assertThat(odd.rounding())
                .as("1423.59 -> 1424, not 1250 + 174 rounded separately")
                .isEqualByComparingTo("0.41");
    }

    @Test
    void SRS_G_20_aRoundingDownIsNegative() {
        EmployerShare s = share("15003", "12", "8.33", "1.16");
        // 9.49 % of 15003 = 1423.7847 -> 1424; parts 1249.75 + 174.03 = 1423.78
        assertThat(s.rounding()).isEqualByComparingTo("0.22");
        EmployerShare down = share("15010", "12", "8.33", "1.16");
        // 9.49 % of 15010 = 1424.449 -> 1424; parts 1250.33 + 174.12 = 1424.45
        assertThat(down.rounding()).isEqualByComparingTo("-0.45");
    }

    @Test
    void SRS_6_7_aSplitAboveTheEmployerShareCannotHold() {
        assertThat(share("30000", "12", "10", "5").splitExceeds()).isTrue();
        assertThat(share("30000", "12", "12", "0").splitExceeds())
                .as("equal to the share is a full split")
                .isFalse();
        assertThat(share("0", "12", "8.33", "1.16").pension()).isEqualByComparingTo("0");
    }

    @Test
    void SRS_1_76_epsStopsFromTheMonthOfThe58thBirthday() {
        // SRS-1-59: the EPS cut-off (age 58) recalculated by a DOB change stops EPS from that
        // month.
        java.time.LocalDate birth = java.time.LocalDate.of(1973, 5, 31);
        assertThat(EmployerShare.epsStopped(birth, 58, java.time.YearMonth.of(2031, 4))).isFalse();
        assertThat(EmployerShare.epsStopped(birth, 58, java.time.YearMonth.of(2031, 5))).isTrue();
        assertThat(EmployerShare.epsStopped(birth, 58, java.time.YearMonth.of(2031, 6))).isTrue();
    }

    @Test
    void SRS_1_76_withoutPensionKeepsTheEmployerContribution() {
        EmployerShare share =
                EmployerShare.of(
                                new java.math.BigDecimal("30000"),
                                new java.math.BigDecimal("12"),
                                new java.math.BigDecimal("8.33"),
                                new java.math.BigDecimal("1.16"))
                        .withoutPension();
        assertThat(share.employer()).isEqualByComparingTo("3600");
        assertThat(share.pension()).isEqualByComparingTo("0");
        assertThat(share.splitExceeds()).isFalse();
    }
}
