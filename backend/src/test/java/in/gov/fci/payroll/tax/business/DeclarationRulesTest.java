package in.gov.fci.payroll.tax.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.tax.business.DeclarationRules.Line;
import in.gov.fci.payroll.tax.business.model.ItDeclaration.Kind;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SRS-7-1, SRS-7-14, SRS-7-16, SRS-7-20, SRS-7-24, BR-TAX-001, BR-TAX-002: the rules of a member's
 * income-tax declaration.
 */
class DeclarationRulesTest {

    private static String code(Runnable call) {
        try {
            call.run();
        } catch (FciException e) {
            return e.getErrorCode().code();
        }
        return null;
    }

    private static BigDecimal rs(String v) {
        return new BigDecimal(v);
    }

    @Test
    void SRS_7_1_theFinancialYearRunsFromAprilToMarch() {
        assertThat(DeclarationRules.finYearOf(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(DeclarationRules.finYearOf(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(DeclarationRules.finYearOf(LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
        assertThat(DeclarationRules.finYearOf(LocalDate.of(2099, 12, 1))).isEqualTo("2099-00");
    }

    // BR-ESEVA-003, SRS-17-5: a frozen regime cannot be changed through self-service.
    // SC-08-0040: a frozen regime takes no further change for the year.
    // SC-14-0004, SC-14-0018: a frozen regime is not changed through self-service.
    @Test
    void BR_TAX_001_aRegimeIsRequiredAndAFrozenOneStaysAsItIs() {
        assertThat(DeclarationRules.requireRegime(Regime.NEW, Optional.empty(), "2026-27"))
                .isEqualTo(Regime.NEW);
        assertThat(code(() -> DeclarationRules.requireRegime(null, Optional.empty(), "2026-27")))
                .isEqualTo("TAX-422-REGIME-REQUIRED");
        // Frozen: the same choice or none keeps it; another choice is refused.
        assertThat(DeclarationRules.requireRegime(null, Optional.of(Regime.OLD), "2026-27"))
                .isEqualTo(Regime.OLD);
        assertThat(DeclarationRules.requireRegime(Regime.OLD, Optional.of(Regime.OLD), "2026-27"))
                .isEqualTo(Regime.OLD);
        assertThat(
                        code(
                                () ->
                                        DeclarationRules.requireRegime(
                                                Regime.NEW, Optional.of(Regime.OLD), "2026-27")))
                .isEqualTo("TAX-409-REGIME-FROZEN");
    }

    @Test
    void SRS_7_16_aDeclaredAmountIsRupeesAndPaiseAboveZero() {
        for (String ok : new String[] {"0.01", "150000", "999999999999.99"}) {
            assertThat(code(() -> DeclarationRules.requireAmount(rs(ok)))).as(ok).isNull();
        }
        for (String bad : new String[] {"0", "-1", "1.005", "1000000000000.00"}) {
            assertThat(code(() -> DeclarationRules.requireAmount(rs(bad))))
                    .as(bad)
                    .isEqualTo("TAX-422-DECLARATION-AMOUNT");
        }
        assertThat(code(() -> DeclarationRules.requireAmount(null)))
                .isEqualTo("TAX-422-DECLARATION-AMOUNT");
    }

    @Test
    void SRS_7_20_aSectionAppliesWhenInForceAndUnderTheRegime() {
        assertThat(
                        code(
                                () ->
                                        DeclarationRules.requireSection(
                                                "80C", Optional.of("OLD"), Regime.OLD)))
                .isNull();
        assertThat(
                        code(
                                () ->
                                        DeclarationRules.requireSection(
                                                "80C", Optional.of("BOTH"), Regime.NEW)))
                .isNull();
        assertThat(
                        code(
                                () ->
                                        DeclarationRules.requireSection(
                                                "80C", Optional.of("OLD"), Regime.NEW)))
                .isEqualTo("TAX-422-DECLARATION-SECTION");
        assertThat(
                        code(
                                () ->
                                        DeclarationRules.requireSection(
                                                "80C", Optional.of("NEW"), Regime.OLD)))
                .isEqualTo("TAX-422-DECLARATION-SECTION");
        assertThat(code(() -> DeclarationRules.requireSection("80Z", Optional.empty(), Regime.OLD)))
                .isEqualTo("TAX-422-DECLARATION-SECTION");
        assertThat(
                        catchThrowableOfType(
                                        FciException.class,
                                        () ->
                                                DeclarationRules.requireSection(
                                                        "80C", Optional.of("OLD"), Regime.NEW))
                                .getMessageArgs())
                .containsExactly("80C", "new");
    }

    @Test
    void SRS_7_24_oneLinePerSectionAndKindInARound() {
        DeclarationRules.requireDistinct(
                List.of(
                        new Line(Kind.INVESTMENT, "80C", rs("1")),
                        new Line(Kind.HRA_RENT, "80C", rs("1")),
                        new Line(Kind.INVESTMENT, "80D", rs("1"))));
        assertThat(
                        code(
                                () ->
                                        DeclarationRules.requireDistinct(
                                                List.of(
                                                        new Line(Kind.INVESTMENT, "80C", rs("1")),
                                                        new Line(
                                                                Kind.INVESTMENT, "80C", rs("2"))))))
                .isEqualTo("TAX-422-DECLARATION-DUPLICATE");
    }

    @Test
    void BR_TAX_002_theAllowedAmountIsTheLowerOfDeclaredAndLimitAndACapIsShown() {
        assertThat(DeclarationRules.allowed(rs("160000"), rs("150000")))
                .isEqualByComparingTo("150000");
        assertThat(DeclarationRules.allowed(rs("150000"), rs("150000")))
                .isEqualByComparingTo("150000");
        assertThat(DeclarationRules.allowed(rs("100"), rs("150000"))).isEqualByComparingTo("100");
        assertThat(DeclarationRules.allowed(rs("9999999"), null)).isEqualByComparingTo("9999999");
        assertThat(DeclarationRules.capped(rs("160000"), rs("150000"))).isTrue();
        assertThat(DeclarationRules.capped(rs("150000"), rs("150000"))).isFalse();
    }

    @Test
    void SRS_7_24_theMakerVerifiesFromZeroUpToTheAllowedAmount() {
        for (String ok : new String[] {"0", "100.50", "150000"}) {
            assertThat(code(() -> DeclarationRules.requireVerified(rs(ok), rs("150000"))))
                    .as(ok)
                    .isNull();
        }
        for (String bad : new String[] {"-0.01", "150000.01", "1.005"}) {
            assertThat(code(() -> DeclarationRules.requireVerified(rs(bad), rs("150000"))))
                    .as(bad)
                    .isEqualTo("TAX-422-VERIFIED-AMOUNT");
        }
        assertThat(code(() -> DeclarationRules.requireVerified(null, rs("1"))))
                .isEqualTo("TAX-422-VERIFIED-AMOUNT");
    }

    @Test
    void SRS_7_14_aLineWithoutProofIsReversedOnlyAfterAConfiguredDeadline() {
        LocalDate deadline = LocalDate.of(2027, 1, 31);
        assertThat(DeclarationRules.reversible(false, Optional.of(deadline), deadline)).isFalse();
        assertThat(DeclarationRules.reversible(false, Optional.of(deadline), deadline.plusDays(1)))
                .isTrue();
        assertThat(DeclarationRules.reversible(true, Optional.of(deadline), deadline.plusDays(1)))
                .isFalse();
        // No deadline configured: nothing is reversed on an assumed date.
        assertThat(DeclarationRules.reversible(false, Optional.empty(), deadline.plusYears(1)))
                .isFalse();
    }
}
