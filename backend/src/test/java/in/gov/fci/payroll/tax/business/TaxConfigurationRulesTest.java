package in.gov.fci.payroll.tax.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** SRS-7-2, SRS-7-4, SRS-7-20, BR-TAX-002, BR-TAX-006: the rules of the M-18 tax masters. */
class TaxConfigurationRulesTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);

    private static String code(Runnable call) {
        try {
            call.run();
        } catch (FciException e) {
            return e.getErrorCode().code();
        }
        return null;
    }

    /** The message arguments of the refusal; fails when nothing is refused. */
    private static Object[] args(Runnable call) {
        FciException e = catchThrowableOfType(FciException.class, call::run);
        assertThat(e).isNotNull();
        assertThat(e.getErrorCode()).isEqualTo(TaxErrorCode.SECTION_ORDER);
        return e.getMessageArgs();
    }

    @Test
    void SRS_7_20_aSectionIsNamedAsTheActWritesIt() {
        for (String ok : new String[] {"80C", "80CCD(1B)", "24(b)", "10(13A)", "80TTA", "192"}) {
            assertThat(code(() -> TaxConfigurationRules.requireSectionCode(ok))).as(ok).isNull();
        }
        for (String bad :
                new String[] {
                    "C80",
                    "80c",
                    "80C ",
                    "80CCD(1B",
                    "80CCD()",
                    "1234",
                    "80ABCDEF",
                    "",
                    null,
                    "1" + "(" + "A".repeat(5) + ")",
                    "80CCDEF(1B)" + "X".repeat(10)
                }) {
            assertThat(code(() -> TaxConfigurationRules.requireSectionCode(bad)))
                    .as(String.valueOf(bad))
                    .isEqualTo("TAX-422-SECTION-CODE");
        }
    }

    @Test
    void BR_TAX_002_aLimitIsRupeesAndPaiseNeverNegativeOrNone() {
        for (String ok : new String[] {"0", "150000", "150000.50", "9999999999.99"}) {
            assertThat(code(() -> TaxConfigurationRules.requireLimit(new BigDecimal(ok))))
                    .as(ok)
                    .isNull();
        }
        assertThat(code(() -> TaxConfigurationRules.requireLimit(null))).isNull();
        for (String bad : new String[] {"-0.01", "1.005", "10000000000.00"}) {
            assertThat(code(() -> TaxConfigurationRules.requireLimit(new BigDecimal(bad))))
                    .as(bad)
                    .isEqualTo("TAX-422-SECTION-LIMIT");
        }
    }

    @Test
    void SRS_7_2_aSectionAppliesUnderTheOldTheNewOrBothRegimes() {
        Arrays.asList("OLD", "NEW", "BOTH")
                .forEach(
                        r ->
                                assertThat(code(() -> TaxConfigurationRules.requireRegime(r)))
                                        .isNull());
        Arrays.asList("old", "ANY", "", null)
                .forEach(
                        r ->
                                assertThat(code(() -> TaxConfigurationRules.requireRegime(r)))
                                        .as(String.valueOf(r))
                                        .isEqualTo("TAX-422-SECTION-REGIME"));
    }

    @Test
    void SRS_7_20_aNewVersionStartsAfterTheLatestVersion() {
        assertThat(code(() -> TaxConfigurationRules.requireAfter("80C", APRIL, Optional.empty())))
                .isNull();
        assertThat(
                        code(
                                () ->
                                        TaxConfigurationRules.requireAfter(
                                                "80C", APRIL, Optional.of(APRIL.minusDays(1)))))
                .isNull();
        assertThat(args(() -> TaxConfigurationRules.requireAfter("80C", APRIL, Optional.of(APRIL))))
                .containsExactly("80C", "2026-04-01");
        assertThat(
                        code(
                                () ->
                                        TaxConfigurationRules.requireAfter(
                                                "80C", APRIL, Optional.of(APRIL.plusDays(1)))))
                .isEqualTo("TAX-409-SECTION-ORDER");
        assertThat(args(() -> TaxConfigurationRules.requireAfter("80C", null, Optional.empty())))
                .containsExactly("80C", "-");
    }

    @Test
    void SRS_7_4_aPtStateHasItsCodeNameAndAnOrderedWindow() {
        assertThat(code(() -> TaxConfigurationRules.requireState("MH", "Maharashtra", APRIL, null)))
                .isNull();
        assertThat(
                        code(
                                () ->
                                        TaxConfigurationRules.requireState(
                                                "WB", "West Bengal", APRIL, APRIL)))
                .isNull();
        String[][] bad = {
            {null, "Name"},
            {"M", "Name"},
            {"mh", "Name"},
            {"ABCDEFGHIJK", "Name"},
            {"MH", null},
            {"MH", " "}
        };
        for (String[] b : bad) {
            assertThat(code(() -> TaxConfigurationRules.requireState(b[0], b[1], APRIL, null)))
                    .as(Arrays.toString(b))
                    .isEqualTo("TAX-422-PT-STATE");
        }
        assertThat(code(() -> TaxConfigurationRules.requireState("MH", "Maharashtra", null, null)))
                .isEqualTo("TAX-422-PT-STATE");
        assertThat(
                        code(
                                () ->
                                        TaxConfigurationRules.requireState(
                                                "MH", "Maharashtra", APRIL, APRIL.minusDays(1))))
                .isEqualTo("TAX-422-PT-STATE");
    }

    @Test
    void BR_TAX_006_aStateWherePtAppliesUsesThePtSlabRule() {
        assertThat(TaxConfigurationRules.slabKeyFor(true)).isEqualTo("PT.SLAB");
        assertThat(TaxConfigurationRules.slabKeyFor(false)).isNull();
    }
}
