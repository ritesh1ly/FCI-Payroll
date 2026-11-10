package in.gov.fci.payroll.tax.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration.Type;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SRS-7-11, SRS-7-20, SRS-7-25, BR-TAX-015: the rules of an I-18 entry and the transport rebate.
 */
class SavingsRulesTest {

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
    void SRS_7_20_externalSavingsNameASectionInForceAndOtherIncomeItsHead() {
        assertThat(code(() -> SavingsRules.requireKind(Type.EXTERNAL_SAVINGS, "80C", true, null)))
                .isNull();
        assertThat(code(() -> SavingsRules.requireKind(Type.EXTERNAL_SAVINGS, "80C", false, null)))
                .isEqualTo("TAX-422-ENTRY-KIND");
        assertThat(code(() -> SavingsRules.requireKind(Type.EXTERNAL_SAVINGS, null, true, null)))
                .isEqualTo("TAX-422-ENTRY-KIND");
        // SRS-7-11: vehicle perquisites, honorarium, NSC interest are heads of other income.
        assertThat(
                        code(
                                () ->
                                        SavingsRules.requireKind(
                                                Type.OTHER_INCOME, null, false, "Honorarium")))
                .isNull();
        assertThat(
                        code(
                                () ->
                                        SavingsRules.requireKind(
                                                Type.OTHER_INCOME, null, false, "H".repeat(60))))
                .isNull();
        for (String bad : new String[] {null, " ", "H".repeat(61), "मानदेय"}) {
            assertThat(code(() -> SavingsRules.requireKind(Type.OTHER_INCOME, null, false, bad)))
                    .as(String.valueOf(bad))
                    .isEqualTo("TAX-422-ENTRY-KIND");
        }
        assertThat(
                        code(
                                () ->
                                        SavingsRules.requireKind(
                                                Type.OTHER_INCOME, "80C", true, "Honorarium")))
                .isEqualTo("TAX-422-ENTRY-KIND");
    }

    @Test
    void SRS_7_25_theAmountIsAboveZeroAndTheTdsFromZero() {
        assertThat(code(() -> SavingsRules.requireAmounts(rs("0.01"), rs("0")))).isNull();
        assertThat(code(() -> SavingsRules.requireAmounts(rs("999999999999.99"), rs("10.50"))))
                .isNull();
        String[][] bad = {
            {"0", "0"},
            {"-1", "0"},
            {"1.005", "0"},
            {"1000000000000.00", "0"},
            {"1", "-0.01"},
            {"1", "0.001"}
        };
        for (String[] b : bad) {
            assertThat(code(() -> SavingsRules.requireAmounts(rs(b[0]), rs(b[1]))))
                    .as(String.join(",", b))
                    .isEqualTo("TAX-422-ENTRY-AMOUNT");
        }
        assertThat(code(() -> SavingsRules.requireAmounts(null, rs("0"))))
                .isEqualTo("TAX-422-ENTRY-AMOUNT");
        assertThat(code(() -> SavingsRules.requireAmounts(rs("1"), null)))
                .isEqualTo("TAX-422-ENTRY-AMOUNT");
    }

    @Test
    void SRS_7_25_entriesAreForTheCurrentYear() {
        assertThat(code(() -> SavingsRules.requireYear("2026-27", "2026-27"))).isNull();
        assertThat(code(() -> SavingsRules.requireYear("2025-26", "2026-27")))
                .isEqualTo("TAX-422-ENTRY-YEAR");
    }

    @Test
    void BR_TAX_015_aYearRunsFromAprilToMarch() {
        assertThat(SavingsRules.monthsOf("2026-27"))
                .hasSize(12)
                .startsWith("202604")
                .endsWith("202703")
                .contains("202612", "202701");
    }

    @Test
    void BR_TAX_015_theRebateIsOneRowPerMonthOfTheYearNotAlreadyApproved() {
        Map<String, BigDecimal> ok = new LinkedHashMap<>();
        ok.put("202604", rs("1600"));
        ok.put("202703", rs("1600.50"));
        assertThat(code(() -> SavingsRules.requireRebate("2026-27", ok, Set.of("202605"))))
                .isNull();
        assertThat(code(() -> SavingsRules.requireRebate("2026-27", ok, Set.of("202604"))))
                .isEqualTo("TAX-422-REBATE-MONTHS");
        assertThat(code(() -> SavingsRules.requireRebate("2026-27", Map.of(), Set.of())))
                .isEqualTo("TAX-422-REBATE-MONTHS");
        assertThat(code(() -> SavingsRules.requireRebate("2026-27", null, Set.of())))
                .isEqualTo("TAX-422-REBATE-MONTHS");
        for (Map.Entry<String, String> bad :
                List.of(
                        Map.entry("202603", "1"),
                        Map.entry("202704", "1"),
                        Map.entry("2026-4", "1"),
                        Map.entry("202604", "0"),
                        Map.entry("202604", "1.005"),
                        Map.entry("202604", "10000000000.00"))) {
            Map<String, BigDecimal> m = Map.of(bad.getKey(), rs(bad.getValue()));
            assertThat(code(() -> SavingsRules.requireRebate("2026-27", m, Set.of())))
                    .as(bad.toString())
                    .isEqualTo("TAX-422-REBATE-MONTHS");
        }
        Map<String, BigDecimal> nullMonth = new java.util.HashMap<>();
        nullMonth.put(null, rs("1"));
        assertThat(code(() -> SavingsRules.requireRebate("2026-27", nullMonth, Set.of())))
                .isEqualTo("TAX-422-REBATE-MONTHS");
        Map<String, BigDecimal> nullAmount = new java.util.HashMap<>();
        nullAmount.put("202604", null);
        assertThat(code(() -> SavingsRules.requireRebate("2026-27", nullAmount, Set.of())))
                .isEqualTo("TAX-422-REBATE-MONTHS");
    }
}
