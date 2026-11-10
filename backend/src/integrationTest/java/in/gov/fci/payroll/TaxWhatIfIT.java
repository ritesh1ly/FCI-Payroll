package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The what-if Income Tax Forecast (plan 08 phase 08-C09; SRS-7-28): the Payroll Maker (C-09 RUN) or
 * the employee (E-01) varies the regime, a declaration or the income; the forecast is computed
 * again beside the actual one, the run record is kept and nothing actual changes. The year is 40
 * years back with its own test rates (TaxYearFixtures), as in TaxComputationIT.
 */
class TaxWhatIfIT extends ApiTestSupport {

    private static final String YEAR = TaxYearFixtures.yearBack(40);

    private Long old;
    private Long newer;

    @BeforeEach
    void year() {
        TaxYearFixtures.rates(jdbc, YEAR);
        old = TaxYearFixtures.member(jdbc, "870001");
        newer = TaxYearFixtures.member(jdbc, "870002");
        TaxYearFixtures.regime(jdbc, old, YEAR, "O");
        TaxYearFixtures.regime(jdbc, newer, YEAR, "N");
        ItTaxPorts.YEARS.put(old + "|" + YEAR, ItTaxPorts.year(YEAR, "100000", "8000", true));
        ItTaxPorts.YEARS.put(newer + "|" + YEAR, ItTaxPorts.year(YEAR, "50000", "0", false));
    }

    @AfterEach
    void clear() {
        ItTaxPorts.YEARS.clear();
    }

    private Session maker() throws Exception {
        return login("tax.maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private MvcResult whatIf(Session s, String eis, Object body) throws Exception {
        return mvc.perform(
                        userPost(s, "/v1/tax/forecasts/" + eis + "/what-ifs?finYear=" + YEAR, body))
                .andReturn();
    }

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(r);
    }

    private int runs(String eis) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM rule_simulation WHERE population_scope = ? AND"
                        + " results_retained = 'N'",
                Integer.class,
                "TAX;" + eis + ";" + YEAR);
    }

    @Test
    void SRS_7_28_anotherRegimeIsForecastBesideTheActualAndOnlyTheRunIsKept() throws Exception {
        int before = runs("870001");
        JsonNode r = ok(whatIf(maker(), "870001", Map.of("regime", "NEW")));
        assertThat(r.path("runId").asLong()).isPositive();
        assertThat(r.path("actual").path("inputs").path("regime").asString()).isEqualTo("OLD");
        assertThat(r.path("actual").path("result").path("annualTax").decimalValue())
                .isEqualByComparingTo("179400");
        assertThat(r.path("whatIf").path("inputs").path("regime").asString()).isEqualTo("NEW");
        // New slabs on 12,00,000 (TaxYearFixtures): not the old regime's 1,79,400.
        assertThat(r.path("whatIf").path("result").path("annualTax").decimalValue())
                .isNotEqualByComparingTo("179400");
        assertThat(runs("870001")).isEqualTo(before + 1);
        String regime =
                jdbc.queryForObject(
                        "SELECT regime_code FROM it_regime_detail WHERE employee_id = ? AND"
                                + " fin_year = ?",
                        String.class,
                        old,
                        YEAR);
        assertThat(regime).as("the actual regime is never changed").isEqualTo("O");
    }

    @Test
    void SRS_7_28_moreIncomeRaisesTheForecast() throws Exception {
        JsonNode r =
                ok(
                        whatIf(
                                maker(),
                                "870002",
                                Map.of("otherIncome", 100000, "salaryChange", -50000)));
        // New slabs: 6,00,000 -> 6,50,000.
        assertThat(r.path("whatIf").path("result").path("taxableIncome").decimalValue())
                .isEqualByComparingTo("650000");
        assertThat(r.path("actual").path("result").path("taxableIncome").decimalValue())
                .isEqualByComparingTo("600000");
        assertThat(r.path("whatIf").path("inputs").path("otherIncome").toString())
                .contains("WHAT-IF OTHER INCOME", "WHAT-IF SALARY");
    }

    @Test
    void SRS_7_28_aChangeTheForecastCannotTakeIsRefused() throws Exception {
        Session maker = maker();
        int before = runs("870001");
        for (Object body :
                List.of(
                        Map.of(),
                        Map.of("claims", List.of(Map.of("sectionCode", "80C", "amount", 1000))),
                        Map.of("claims", List.of(Map.of("sectionCode", "80 C", "amount", 1000))),
                        Map.of("otherIncome", -1),
                        Map.of("otherIncome", new java.math.BigDecimal("1.005")))) {
            MvcResult r = whatIf(maker, "870001", body);
            assertThat(r.getResponse().getStatus()).as(body.toString()).isEqualTo(422);
            assertThat(body(r).path("errorCode").asString()).isEqualTo("TAX-422-WHAT-IF");
        }
        assertThat(runs("870001")).as("a refused change keeps no run").isEqualTo(before);
    }

    @Test
    void SRS_7_28_theEmployeeRunsItOnTheirOwnYearOnly() throws Exception {
        Session member = login("tax.old@fci-demo.test", "EMPLOYEE_ESEVA", null);
        JsonNode r =
                ok(
                        mvc.perform(
                                        userPost(
                                                member,
                                                "/v1/tax/forecasts/mine/what-ifs?finYear=" + YEAR,
                                                Map.of("salaryChange", -100000)))
                                .andReturn());
        assertThat(r.path("whatIf").path("eis").asString()).isEqualTo("870001");
        assertThat(r.path("whatIf").path("result").path("taxableIncome").decimalValue())
                .isLessThan(r.path("actual").path("result").path("taxableIncome").decimalValue());
        assertThat(whatIf(member, "870002", Map.of("regime", "OLD")).getResponse().getStatus())
                .as("not another member's, and not through the desk's endpoint")
                .isEqualTo(403);
    }

    @Test
    void SRS_22_21_onlyTheDeskOfTheMembersOfficeRunsIt() throws Exception {
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(whatIf(kolkata, "870001", Map.of("regime", "NEW")).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(whatIf(maker(), "999999", Map.of("regime", "NEW")).getResponse().getStatus())
                .isEqualTo(404);
        Session checker = login("tax.checker.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(whatIf(checker, "870001", Map.of("regime", "NEW")).getResponse().getStatus())
                .isEqualTo(200);
    }
}
