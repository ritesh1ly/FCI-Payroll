package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.tax.api.TaxDeductions;
import in.gov.fci.payroll.tax.business.DeclarationRules;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * C-07 Statutory Computation (docs/plan/08-income-tax.md phase 08e; SRS-7-1 … SRS-7-4, SRS-7-12,
 * SRS-7-13, SRS-7-19, SRS-7-26, SRS-7-27; BR-TAX-001 … 006, BR-TAX-014): the forecast of a member's
 * year from the approved inputs, the earnings of payroll (the IT stand-in of the earnings port) and
 * the year's rates; the month's TDS and PT for the engines (tax.api). The year is 40 years back
 * with its own test rates (TaxYearFixtures); today's year has none (OQ-4).
 */
class TaxComputationIT extends ApiTestSupport {

    private static final String YEAR = TaxYearFixtures.yearBack(40);
    private static final String TODAY_YEAR =
            DeclarationRules.finYearOf(LocalDate.now(TaxYearFixtures.INDIA));

    @Autowired private TaxDeductions deductions;

    private Long old;
    private Long newer;
    private Long noPan;
    private Long history;

    @BeforeEach
    void year() {
        TaxYearFixtures.rates(jdbc, YEAR);
        old = TaxYearFixtures.member(jdbc, "870001");
        newer = TaxYearFixtures.member(jdbc, "870002");
        noPan = TaxYearFixtures.member(jdbc, "870003");
        history = TaxYearFixtures.member(jdbc, "870005");
        TaxYearFixtures.regime(jdbc, old, YEAR, "O");
        TaxYearFixtures.regime(jdbc, newer, YEAR, "N");
        TaxYearFixtures.regime(jdbc, noPan, YEAR, "O");
        ItTaxPorts.YEARS.put(old + "|" + YEAR, ItTaxPorts.year(YEAR, "100000", "8000", true));
        ItTaxPorts.YEARS.put(newer + "|" + YEAR, ItTaxPorts.year(YEAR, "50000", "0", false));
        ItTaxPorts.YEARS.put(noPan + "|" + YEAR, ItTaxPorts.year(YEAR, "100000", "0", true));
    }

    @AfterEach
    void clear() {
        ItTaxPorts.YEARS.clear();
    }

    private Session maker() throws Exception {
        return login("tax.maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private MvcResult get(Session s, String eis, String year) throws Exception {
        return mvc.perform(userGet(s, "/v1/tax/forecasts/" + eis + "?finYear=" + year)).andReturn();
    }

    private JsonNode forecast(Session s, String eis, String year) throws Exception {
        MvcResult r = get(s, eis, year);
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(r);
    }

    @Test
    void SRS_7_19_theActualYearIsComputedOnItsSlabsAndTheYearEndDifferenceIsShown()
            throws Exception {
        JsonNode f = forecast(maker(), "870001", YEAR);
        assertThat(f.path("view").asString()).isEqualTo("ACTUAL");
        assertThat(f.path("reasons")).isEmpty();
        JsonNode r = f.path("result");
        // 12 x 1,00,000; old slabs 12,500 + 1,00,000 + 60,000; cess 4 %.
        assertThat(r.path("taxableIncome").decimalValue()).isEqualByComparingTo("1200000");
        assertThat(r.path("slabTax").decimalValue()).isEqualByComparingTo("172500");
        assertThat(r.path("cess").decimalValue()).isEqualByComparingTo("6900");
        assertThat(r.path("annualTax").decimalValue()).isEqualByComparingTo("179400");
        assertThat(r.path("higherRateApplied").asBoolean()).isFalse();
        // SRS-7-19: actual tax less the 96,000 deducted.
        assertThat(f.path("yearEndDifference").decimalValue()).isEqualByComparingTo("83400");
        assertThat(f.path("inputs").path("regime").asString()).isEqualTo("OLD");
    }

    @Test
    void SRS_7_3_inTheYearTheTaxIsProjectedAndSpreadOverTheRemainingMonths() throws Exception {
        JsonNode f = forecast(maker(), "870002", YEAR);
        assertThat(f.path("view").asString()).isEqualTo("PROJECTED");
        // New slabs on 6,00,000: 15,000 + cess 600; twelve months still to come.
        assertThat(f.path("result").path("annualTax").decimalValue()).isEqualByComparingTo("15600");
        assertThat(f.path("result").path("monthlyTds").decimalValue()).isEqualByComparingTo("1300");
        assertThat(
                        f.path("yearEndDifference").isNull()
                                || f.path("yearEndDifference").isMissingNode())
                .isTrue();
    }

    /**
     * SC-08-0043 (I-07 postcondition l.2538; SRS-7-24, BR-TAX-014, SRS-7-14, SRS-7-3): accepted
     * declarations drive the monthly TDS for the rest of the year. 870002 (new regime, 6,00,000,
     * twelve months to come: 1,300 a month) declares 1,00,000 under a section of both regimes.
     * While the round only waits for the Maker nothing changes; once approved, the taxable income
     * falls to 5,00,000 (10,000 + cess 400) and the month's TDS to 10,400 / 12 = 867; a line
     * reversed at the proof deadline counts no longer.
     */
    @Test
    void SC_08_0043_acceptedDeclarationsDriveTheRemainingMonthsTds() throws Exception {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM it_section_master WHERE section_code = 'SC43IT'",
                        Integer.class)
                == 0) {
            jdbc.update(
                    "INSERT INTO it_section_master (section_code, section_description,"
                            + " max_deduction_amount, regime_applicability, effective_from) VALUES"
                            + " ('SC43IT', 'IT accepted declaration', NULL, 'BOTH', ?)",
                    TaxYearFixtures.april(YEAR));
        }
        Session maker = maker();
        assertThat(forecast(maker, "870002", YEAR).path("result").path("monthlyTds").decimalValue())
                .isEqualByComparingTo("1300");
        jdbc.update(
                "INSERT INTO it_declaration_submission (employee_id, fin_year, submission_no,"
                        + " submission_status) VALUES (?, ?, 43, 'SUBMITTED')",
                newer,
                YEAR);
        Long round =
                jdbc.queryForObject(
                        "SELECT submission_id FROM it_declaration_submission WHERE employee_id = ?"
                                + " AND fin_year = ? AND submission_no = 43",
                        Long.class,
                        newer,
                        YEAR);
        try {
            jdbc.update(
                    "INSERT INTO it_declaration (employee_id, submission_id, fin_year,"
                            + " section_code, declared_amount, allowed_amount, proof_status,"
                            + " verified_amount) VALUES (?, ?, ?, 'SC43IT', 100000, 100000,"
                            + " 'APPROVED', 100000)",
                    newer,
                    round,
                    YEAR);
            // A round not yet accepted drives nothing.
            JsonNode waiting = forecast(maker, "870002", YEAR).path("result");
            assertThat(waiting.path("annualTax").decimalValue()).isEqualByComparingTo("15600");
            assertThat(waiting.path("monthlyTds").decimalValue()).isEqualByComparingTo("1300");

            jdbc.update(
                    "UPDATE it_declaration_submission SET submission_status = 'APPROVED' WHERE"
                            + " submission_id = ?",
                    round);
            JsonNode accepted = forecast(maker, "870002", YEAR);
            assertThat(texts(accepted.path("inputs").path("deductions"), "sectionCode"))
                    .containsExactly("SC43IT");
            assertThat(accepted.path("result").path("taxableIncome").decimalValue())
                    .isEqualByComparingTo("500000");
            assertThat(accepted.path("result").path("annualTax").decimalValue())
                    .isEqualByComparingTo("10400");
            assertThat(accepted.path("result").path("monthlyTds").decimalValue())
                    .isEqualByComparingTo("867");

            // SRS-7-14: the exemption reversed at the proof deadline no longer counts.
            jdbc.update(
                    "UPDATE it_declaration SET proof_status = 'REVERSED' WHERE submission_id = ?",
                    round);
            assertThat(
                            forecast(maker, "870002", YEAR)
                                    .path("result")
                                    .path("monthlyTds")
                                    .decimalValue())
                    .isEqualByComparingTo("1300");
        } finally {
            // Fixture rows of the test's own year (other tests read 870002's year unchanged).
            jdbc.update("DELETE FROM it_declaration WHERE submission_id = ?", round);
            jdbc.update("DELETE FROM it_declaration_submission WHERE submission_id = ?", round);
        }
    }

    /**
     * SRS-SEC-4 (BR-SEC-001, l.482) and SRS-7-23: the PAN is Restricted. The C-07 forecast, its CSV
     * export and the I-07 declaration register of the member's office never carry the member's full
     * PAN (KLKPK7001A, demo seed V039_9).
     */
    @Test
    void SRS_SEC_4_thePanIsNeverInFullOnTheTaxListsOrTheForecastExport() throws Exception {
        String pan =
                jdbc.queryForObject(
                        "SELECT pan_number FROM employee WHERE employee_id = ?", String.class, old);
        assertThat(pan).isEqualTo("KLKPK7001A");
        Session maker = maker();
        assertThat(forecast(maker, "870001", YEAR).toString()).doesNotContain(pan);
        assertThat(csv(maker, "/v1/tax/forecasts/870001/export?finYear=" + YEAR))
                .contains("Annual tax")
                .doesNotContain(pan);
        // The I-07 register of the member's office: the demo round of tax.old@ is listed, no PAN.
        JsonNode register = all(maker, "/v1/tax/declarations?status=APPROVED");
        assertThat(texts(register, "eis")).contains("870001");
        assertThat(register.toString()).doesNotContain(pan);
    }

    // SC-18-0015: without a valid PAN, TDS at the higher prescribed rate.
    @Test
    void SRS_7_13_withoutAPanTheHigherRateApplies() throws Exception {
        JsonNode r = forecast(maker(), "870003", YEAR).path("result");
        assertThat(r.path("higherRateApplied").asBoolean()).isTrue();
        assertThat(r.path("annualTax").decimalValue()).isEqualByComparingTo("240000");
    }

    /**
     * SRS-7-13 (l.2471, BMAD TR-020): a PAN on record but malformed is "no valid PAN": the higher
     * prescribed rate applies in C-07 and in the month the engines take, and it is raised on X-05.
     * Member 870003 (no PAN in the demo) carries a malformed one for the test only.
     */
    @Test
    void SRS_7_13_aMalformedPanIsTaxedAtTheHigherRateLikeAMissingOne() throws Exception {
        String before =
                jdbc.queryForObject(
                        "SELECT pan_number FROM employee WHERE employee_id = ?",
                        String.class,
                        noPan);
        jdbc.update("UPDATE employee SET pan_number = 'ABCDE12345' WHERE employee_id = ?", noPan);
        try {
            JsonNode r = forecast(maker(), "870003", YEAR).path("result");
            assertThat(r.path("higherRateApplied").asBoolean()).isTrue();
            assertThat(r.path("annualTax").decimalValue()).isEqualByComparingTo("240000");
            TaxDeductions.Monthly month =
                    deductions.monthly(
                            noPan, YearMonth.from(TaxYearFixtures.april(YEAR)), "it.engine");
            assertThat(month.higherRateApplied()).isTrue();
            assertThat(month.tds()).isPresent();
            assertThat(exceptions(noPan, "PAN_MISSING")).isPositive();
        } finally {
            jdbc.update("UPDATE employee SET pan_number = ? WHERE employee_id = ?", before, noPan);
        }
    }

    @Test
    void SRS_7_26_whatIsMissingIsAnsweredAsAReasonNeverAssumed() throws Exception {
        Session maker = maker();
        // No approved regime and no earnings for 870005 in the year.
        JsonNode none = forecast(maker, "870005", YEAR);
        assertThat(none.path("reasons").valueStream().map(JsonNode::asString))
                .containsExactly(
                        TaxErrorCode.REGIME_REQUIRED.code(),
                        TaxErrorCode.EARNINGS_NOT_AVAILABLE.code());
        assertThat(none.path("result").isNull() || none.path("result").isMissingNode()).isTrue();
        // Today's year: the approved inputs of the demo (V039_9_1) are assembled, but no rate is
        // configured, so nothing is computed and the missing rule is named.
        ItTaxPorts.YEARS.put(
                old + "|" + TODAY_YEAR, ItTaxPorts.year(TODAY_YEAR, "100000", "0", false));
        JsonNode today = forecast(maker, "870001", TODAY_YEAR);
        assertThat(today.path("reasons").valueStream().map(JsonNode::asString))
                .containsExactly(TaxErrorCode.RATE_NOT_CONFIGURED.code() + ":TAX.SLAB.OLD");
        JsonNode in = today.path("inputs");
        assertThat(in.path("regime").asString()).isEqualTo("OLD");
        assertThat(texts(in.path("deductions"), "sectionCode")).contains("80C", "80CCD(1B)");
        for (JsonNode d : in.path("deductions")) {
            if ("80C".equals(d.path("sectionCode").asString())) {
                // SRS-7-20: capped at the section's limit.
                assertThat(d.path("allowed").decimalValue()).isEqualByComparingTo("150000");
            }
        }
        assertThat(in.path("transportRebate").decimalValue()).isEqualByComparingTo("9600");
        assertThat(texts(in.path("otherIncome"), "head")).contains("Honorarium");
        // Without a year the forecast is of today's year.
        assertThat(
                        body(mvc.perform(userGet(maker, "/v1/tax/forecasts/870001")).andReturn())
                                .path("finYear")
                                .asString())
                .isEqualTo(TODAY_YEAR);
    }

    @Test
    void SRS_7_27_theForecastIsDownloadedWithItsEarnings() throws Exception {
        Session maker = maker();
        String text = csv(maker, "/v1/tax/forecasts/870001/export?finYear=" + YEAR);
        assertThat(text).contains("Annual tax").contains("Monthly TDS").contains("Basic Pay");
        assertThat(text).contains(YEAR + " (ACTUAL)");
        MvcResult xlsx =
                download(maker, "/v1/tax/forecasts/870001/export?format=XLSX&finYear=" + YEAR);
        assertThat(xlsx.getResponse().getStatus()).isEqualTo(200);
        assertThat(xlsx.getResponse().getContentAsByteArray()).isNotEmpty();
        assertThat(text).contains("\"Member status\",\"IN_SERVICE\"");

        // Today's year: the approved inputs of the demo (deductions, rebate, other income) and the
        // reason nothing is computed (no rate is configured, OQ-4); projected months marked.
        ItTaxPorts.YEARS.put(
                old + "|" + TODAY_YEAR, ItTaxPorts.year(TODAY_YEAR, "100000", "0", false));
        String today = csv(maker, "/v1/tax/forecasts/870001/export?finYear=" + TODAY_YEAR);
        assertThat(today)
                .contains("Deduction 80C")
                .contains("Transport rebate")
                .contains("\"Other income\",\"Honorarium\"")
                .contains("(projected)")
                .contains("\"Not computed\",\"" + TaxErrorCode.RATE_NOT_CONFIGURED.code());
        assertThat(today).doesNotContain("Annual tax");

        // SRS-7-27, SRS-7-29: the employer's NPS contribution is income with its matching
        // exemption.
        ItTaxPorts.YEARS.put(
                newer + "|" + YEAR,
                new in.gov.fci.payroll.tax.business.TaxableEarnings.Year(
                        java.util.List.of(
                                new in.gov.fci.payroll.tax.business.TaxableEarnings.Month(
                                        TaxYearFixtures.months(YEAR).get(0),
                                        true,
                                        new java.math.BigDecimal("50000"),
                                        java.math.BigDecimal.ZERO,
                                        new java.math.BigDecimal("5000"),
                                        java.util.List.of()))));
        String nps = csv(maker, "/v1/tax/forecasts/870002/export?finYear=" + YEAR);
        assertThat(nps)
                .contains("\"Employer NPS contribution\",\"income\",\"5000\"")
                .contains(
                        "\"Employer NPS contribution\",\"matching exemption (deducted)\",\"5000\"");
    }

    @Test
    void SRS_22_21_onlyThePayrollDeskOfTheMembersOfficeReadsTheComputation() throws Exception {
        // Another paying unit reads the member as missing; so does the Delhi desk a Kolkata member.
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(get(kolkata, "870001", YEAR).getResponse().getStatus()).isEqualTo(404);
        assertThat(get(maker(), "870004", YEAR).getResponse().getStatus()).isEqualTo(404);
        assertThat(get(maker(), "999999", YEAR).getResponse().getStatus()).isEqualTo(404);
        // The member's own eSeva has no C-07; the Payroll Checker reads it.
        Session member = login("tax.old@fci-demo.test", "EMPLOYEE_ESEVA", null);
        assertThat(get(member, "870001", YEAR).getResponse().getStatus()).isEqualTo(403);
        Session checker = login("tax.checker.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(get(checker, "870001", YEAR).getResponse().getStatus()).isEqualTo(200);
        // A year that is not a financial year is refused at the boundary.
        assertThat(get(maker(), "870001", "2026").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void SRS_7_1_theEnginesGetTheMonthsTdsAndAMissingRegimeOrPanIsRaisedOnX05() {
        YearMonth april = YearMonth.from(TaxYearFixtures.april(YEAR));
        TaxDeductions.Monthly withPan = deductions.monthly(old, april, "it.engine");
        assertThat(withPan.tds()).isPresent();
        assertThat(withPan.higherRateApplied()).isFalse();
        // Delhi levies no PT (the demo PT master): nothing, not an assumed amount.
        assertThat(withPan.professionalTax()).hasValueSatisfying(pt -> assertThat(pt).isZero());

        TaxDeductions.Monthly without = deductions.monthly(noPan, april, "it.engine");
        assertThat(without.higherRateApplied()).isTrue();
        assertThat(exceptions(noPan, "PAN_MISSING")).isPositive();

        // No regime: no TDS, the regime raised on X-05, payroll continues.
        TaxDeductions.Monthly noRegime = deductions.monthly(history, april, "it.engine");
        assertThat(noRegime.tds()).isEmpty();
        assertThat(noRegime.reasons()).contains(TaxErrorCode.REGIME_REQUIRED.code());
        assertThat(exceptions(history, "TAX_REGIME_MISSING")).isPositive();
    }

    @Test
    void SRS_7_4_theStateOfTheOfficeDecidesPtAndAStateOutsideTheMasterIsNotConfigured() {
        YearMonth april = YearMonth.from(TaxYearFixtures.april(YEAR));
        // Ludhiana DO (Punjab, V039_11_9): in the demo PT master as not levying PT since module 16
        // phase 16e-2 (V040_8_9), so no PT and no reason; a state outside the master is refused
        // (TaxComputationServiceTest: no state on the office).
        Long ludhiana = TaxYearFixtures.member(jdbc, "870025");
        TaxYearFixtures.regime(jdbc, ludhiana, YEAR, "O");
        ItTaxPorts.YEARS.put(ludhiana + "|" + YEAR, ItTaxPorts.year(YEAR, "60000", "0", true));
        TaxDeductions.Monthly punjab = deductions.monthly(ludhiana, april, "it.engine");
        assertThat(punjab.professionalTax()).contains(java.math.BigDecimal.ZERO);
        assertThat(punjab.reasons().toString()).doesNotContain("pt_state_master");
        // Chennai DO (Tamil Nadu): PT applies, but not in a year before its PT master row.
        Long chennai = TaxYearFixtures.member(jdbc, "870018");
        TaxYearFixtures.regime(jdbc, chennai, YEAR, "O");
        ItTaxPorts.YEARS.put(chennai + "|" + YEAR, ItTaxPorts.year(YEAR, "60000", "0", true));
        assertThat(deductions.monthly(chennai, april, "it.engine").professionalTax())
                .hasValueSatisfying(pt -> assertThat(pt).isZero());
        // A member without earnings for the month: PT is not computed either.
        ItTaxPorts.YEARS.remove(chennai + "|" + YEAR);
        assertThat(deductions.monthly(chennai, april, "it.engine").reasons())
                .contains(TaxErrorCode.EARNINGS_NOT_AVAILABLE.code());
    }

    private int exceptions(Long employee, String code) {
        // X-05 holds one open exception per member and code, whichever screen raised it first
        // (ReturnIT raises the PAN on C-11).
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM payroll_exception WHERE employee_id = ? AND exception_code ="
                        + " ?",
                Integer.class,
                employee,
                code);
    }
}
