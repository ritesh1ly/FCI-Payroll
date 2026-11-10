package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.orchestration.OtherParameterService.ProposedParameter;
import in.gov.fci.payroll.configuration.orchestration.RateMasterService.LineView;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The value checks of two masters before any proposal is submitted (SRS-11-15 rate lines of M-04 /
 * M-22; SRS-1-66 other parameters of M-23). A rate line names a rate code and a value that is not
 * negative, a percentage at most 100, a pay pattern and city class from their lists, a slab whose
 * upper bound is above its lower one, and no two lines for the same key. A parameter carries at
 * least one value; amounts, ceilings and counts are not negative (a count at least one), a
 * percentage lies in 0 to 100, and a window time is a real HH:MM (24:00 for the end of the day).
 */
class MasterValueRulesTest {

    private static LineView line(
            String code,
            String value,
            String unit,
            String pattern,
            String city,
            String slabFrom,
            String slabTo) {
        return new LineView(
                7L,
                "CAT",
                pattern,
                slabFrom == null ? null : new BigDecimal(slabFrom),
                slabTo == null ? null : new BigDecimal(slabTo),
                city,
                code,
                value == null ? null : new BigDecimal(value),
                unit);
    }

    private static void refusedLines(LineView... lines) {
        assertThatThrownBy(() -> RateMasterService.validate(List.of(lines)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.MASTER_INVALID_LINES));
    }

    @Test
    void SRS_11_15_aRateLineNamesItsCodeAndAValueThatIsNotNegative() {
        assertThatCode(
                        () ->
                                RateMasterService.validate(
                                        List.of(
                                                line(
                                                        "HRA", "27", "PERCENT", "IDA", "X", "0",
                                                        "50000"),
                                                line("TA", "3600", "AMOUNT", " ", "", null, null))))
                .doesNotThrowAnyException();
        refusedLines(line(null, "27", "PERCENT", null, null, null, null));
        refusedLines(line("hra", "27", "PERCENT", null, null, null, null));
        refusedLines(line("HRA", null, "PERCENT", null, null, null, null));
        refusedLines(line("HRA", "-0.01", "AMOUNT", null, null, null, null));
    }

    /**
     * SRS-G-17 (US-GAP-107): "Hold rates, percentages and slab limits at the precision of the
     * governing order; never round them before use" - a rate of up to four decimals (the stored
     * precision) is kept as given; one that would have to be rounded to be stored is refused, never
     * rounded; trailing zeros are no extra precision.
     */
    @Test
    void SRS_G_17_aRateIsHeldAtItsPrecisionAndNeverRoundedBeforeUse() {
        for (String exact : new String[] {"8.3333", "12.50000", "9999999999.9999", "0.0001"}) {
            assertThatCode(
                            () ->
                                    RateMasterService.validate(
                                            List.of(
                                                    line(
                                                            "DA", exact, "AMOUNT", null, null, null,
                                                            null))))
                    .as(exact)
                    .doesNotThrowAnyException();
        }
        refusedLines(line("DA", "8.33333", "PERCENT", null, null, null, null));
        refusedLines(line("DA", "0.00001", "AMOUNT", null, null, null, null));
        refusedLines(line("DA", "10000000000", "AMOUNT", null, null, null, null));
    }

    @Test
    void SRS_11_15_aPercentageIsAtMostAHundredAndAnAmountIsNotCapped() {
        assertThatCode(
                        () ->
                                RateMasterService.validate(
                                        List.of(
                                                line(
                                                        "DA", "100", "PERCENT", null, null, null,
                                                        null))))
                .doesNotThrowAnyException();
        refusedLines(line("DA", "100.01", "PERCENT", null, null, null, null));
        assertThatCode(
                        () ->
                                RateMasterService.validate(
                                        List.of(
                                                line(
                                                        "CCA", "100.01", "AMOUNT", null, null, null,
                                                        null))))
                .doesNotThrowAnyException();
    }

    @Test
    void SRS_11_15_patternCityClassAndSlabComeFromTheirListsAndInOrder() {
        refusedLines(line("HRA", "27", "PERCENT", "XYZ", null, null, null));
        refusedLines(line("HRA", "27", "PERCENT", " IDA ", null, null, null));
        refusedLines(line("HRA", "27", "PERCENT", null, "W", null, null));
        refusedLines(line("HRA", "27", "PERCENT", null, null, "50000", "50000"));
        refusedLines(line("HRA", "27", "PERCENT", null, null, "50000", "40000"));
        // An open-ended slab, or one without a lower bound, needs no order.
        assertThatCode(
                        () ->
                                RateMasterService.validate(
                                        List.of(
                                                line(
                                                        "HRA", "27", "PERCENT", "CDA", "Y", "50000",
                                                        null),
                                                line(
                                                        "HRA", "24", "PERCENT", "FND", "Z", null,
                                                        "40000"))))
                .doesNotThrowAnyException();
    }

    @Test
    void SRS_11_15_twoLinesForTheSameKeyAreRefused() {
        LineView first = line("HRA", "27", "PERCENT", "IDA", "X", "0", "50000");
        LineView same = line("HRA", "30", "PERCENT", "IDA", "X", "0", "60000");
        refusedLines(first, same);
    }

    // ---------------------------------------------------------------- M-23

    private static ProposedParameter parameter(
            String numeric,
            String ceiling,
            String percent,
            String from,
            String to,
            Integer instalments) {
        return new ProposedParameter(
                LocalDate.of(2091, 1, 1),
                numeric == null ? null : new BigDecimal(numeric),
                ceiling == null ? null : new BigDecimal(ceiling),
                percent == null ? null : new BigDecimal(percent),
                from,
                to,
                instalments,
                null,
                false,
                "IT");
    }

    private static void refusedParameter(ProposedParameter p) {
        assertThatThrownBy(() -> OtherParameterService.validate(p))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    @Test
    void SRS_1_66_aParameterCarriesAtLeastOneValue() {
        refusedParameter(parameter(null, null, null, null, null, null));
        // A window end alone is no value, nor is an empty window start (the form's blank field).
        refusedParameter(parameter(null, null, null, null, "18:00", null));
        refusedParameter(parameter(null, null, null, "", null, null));
        refusedParameter(parameter(null, null, null, "  ", "", null));
        for (ProposedParameter one :
                Arrays.asList(
                        parameter("0", null, null, null, null, null),
                        parameter(null, "0", null, null, null, null),
                        parameter(null, null, "0", null, null, null),
                        parameter(null, null, "100", null, null, null),
                        parameter(null, null, null, "09:30", "18:00", null),
                        parameter(null, null, null, null, null, 1))) {
            assertThatCode(() -> OtherParameterService.validate(one)).doesNotThrowAnyException();
        }
    }

    @Test
    void SRS_1_66_amountsAndCountsAreNotNegativeAndAPercentageLiesInZeroToHundred() {
        refusedParameter(parameter("-1", null, null, null, null, null));
        refusedParameter(parameter(null, "-0.01", null, null, null, null));
        refusedParameter(parameter(null, null, "-1", null, null, null));
        refusedParameter(parameter(null, null, "100.01", null, null, null));
        refusedParameter(parameter(null, null, null, null, null, 0));
    }

    @Test
    void SRS_1_66_aWindowTimeIsARealTimeOfDayWith2400AsTheEndOfTheDay() {
        assertThatCode(
                        () ->
                                OtherParameterService.validate(
                                        parameter(null, null, null, "00:00", "24:00", null)))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                OtherParameterService.validate(
                                        parameter(null, null, null, "23:59", " ", null)))
                .as("a blank end is no end")
                .doesNotThrowAnyException();
        for (String bad : List.of("9:30", "24:30", "25:00", "12:60", "noon")) {
            refusedParameter(parameter(null, null, null, bad, null, null));
            refusedParameter(parameter(null, null, null, "09:00", bad, null));
        }
    }

    /** M-04 field table (SRS l.3653): Pay Pattern is mandatory on a staff rate line. */
    @Test
    void M04_fieldTable_aStaffRateLineNamesItsPayPatternBlankOrMissingIsRefused() {
        assertThatCode(
                        () ->
                                RateMasterService.requirePayPatterns(
                                        List.of(
                                                line(
                                                        "DA", "50", "PERCENT", "IDA", null, null,
                                                        null),
                                                line(
                                                        "DA", "48", "PERCENT", "CDA", null, null,
                                                        null))))
                .doesNotThrowAnyException();
        for (String blank : new String[] {null, "", "  "}) {
            assertThatThrownBy(
                            () ->
                                    RateMasterService.requirePayPatterns(
                                            List.of(
                                                    line(
                                                            "DA", "50", "PERCENT", "IDA", null,
                                                            null, null),
                                                    line(
                                                            "DA", "48", "PERCENT", blank, null,
                                                            null, null))))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                            .isEqualTo(ApiErrorCode.MASTER_INVALID_LINES));
        }
    }
}
