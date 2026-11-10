package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The C-10 reissue and the C-11 manual challan entry through the real stack (SRS-7-9, SRS-7-21,
 * SRS-7-31; screens C-10 EDIT, C-11 CREATE; DDL tax_challan, form16_certificate): the Payroll Maker
 * of the paying unit enters a challan that is then listed for the unit and for no other, the same
 * challan twice is refused, and the wrong role, a malformed financial year, an amount of zero, a
 * period outside Q1-Q4, a comma in the reference, a date outside the year and a blank reason are
 * refused with nothing written. The year is 47 years back, unused by the other tax suites; every
 * challan carries a unique CIN so the suites sharing the database never meet.
 */
class StatutoryOutputControllerIT extends ApiTestSupport {

    private static final String YEAR = TaxYearFixtures.yearBack(47);

    private Session maker() throws Exception {
        return login("tax.maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session checker() throws Exception {
        return login("tax.checker.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private static String uniqueCin() {
        return "ITC" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static Map<String, Object> challan(String cin) {
        Map<String, Object> c = new HashMap<>();
        c.put("cin", cin);
        c.put("bsr", "0510308");
        c.put("date", TaxYearFixtures.april(YEAR).plusDays(10).toString());
        c.put("amount", "1250.50");
        c.put("reference", "UTR-" + cin);
        c.put("period", "Q1");
        return c;
    }

    private static Map<String, Object> with(Map<String, Object> source, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(source);
        copy.put(key, value);
        return copy;
    }

    private MvcResult enter(Session s, String finYear, Map<String, Object> challan)
            throws Exception {
        return post(s, "/v1/tax/challans?finYear=" + finYear, challan);
    }

    private boolean listed(Session s, String cin) throws Exception {
        return texts(
                        body(
                                mvc.perform(userGet(s, "/v1/tax/challans?finYear=" + YEAR))
                                        .andReturn()),
                        "cin")
                .contains(cin);
    }

    private int held(String cin) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM tax_challan WHERE cin_number = ?", Integer.class, cin);
    }

    @Test
    void SRS_7_21_aChallanEnteredByThePayrollMakerIsListedForItsUnitOnly() throws Exception {
        String cin = uniqueCin();
        MvcResult done = enter(maker(), YEAR, challan(cin));

        assertThat(status(done)).as(done.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode view = body(done);
        assertThat(view.path("cin").asString()).isEqualTo(cin);
        assertThat(view.path("bsr").asString()).isEqualTo("0510308");
        assertThat(view.path("amount").decimalValue()).isEqualByComparingTo("1250.50");
        assertThat(view.path("period").asString()).isEqualTo("Q1");
        assertThat(view.path("id").asLong()).isPositive();
        assertThat(done.getResponse().getHeader("Cache-Control")).contains("no-store");

        assertThat(listed(maker(), cin)).isTrue();
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(listed(kolkata, cin)).as("another unit does not see the challan").isFalse();
        assertThat(held(cin)).isEqualTo(1);
    }

    @Test
    void SRS_7_21_theSameChallanTwiceIsRefusedAndHeldOnce() throws Exception {
        String cin = uniqueCin();
        assertThat(status(enter(maker(), YEAR, challan(cin)))).isEqualTo(200);

        MvcResult again = enter(maker(), YEAR, challan(cin));

        assertThat(error(again)).isEqualTo("TAX-422-CHALLAN-FILE");
        assertThat(held(cin)).isEqualTo(1);
    }

    @Test
    void SRS_7_21_onlyThePayrollMakerEntersAChallanAndNothingIsWrittenForOthers() throws Exception {
        String cin = uniqueCin();

        assertThat(status(enter(checker(), YEAR, challan(cin)))).isEqualTo(403);
        Session member = login("tax.old@fci-demo.test", "EMPLOYEE_ESEVA", null);
        assertThat(status(enter(member, YEAR, challan(cin)))).isEqualTo(403);

        assertThat(held(cin)).isZero();
    }

    @Test
    void SRS_7_21_aChallanThatBreaksAFieldRuleIsRefusedAndNothingIsWritten() throws Exception {
        String cin = uniqueCin();
        Map<String, Map<String, Object>> bad = new LinkedHashMap<>();
        bad.put("amount zero", with(challan(cin), "amount", "0"));
        bad.put("amount negative", with(challan(cin), "amount", "-5"));
        bad.put("amount three decimals", with(challan(cin), "amount", "10.123"));
        bad.put("period Q5", with(challan(cin), "period", "Q5"));
        bad.put("period lower case", with(challan(cin), "period", "q1"));
        bad.put("comma in the reference", with(challan(cin), "reference", "UTR,1"));
        bad.put("CIN with a space", with(challan(cin), "cin", "CIN 24"));
        bad.put("CIN blank", with(challan(cin), "cin", " "));
        bad.put("BSR blank", with(challan(cin), "bsr", ""));
        bad.put("no date", with(challan(cin), "date", null));
        bad.put("reference over 50", with(challan(cin), "reference", "U".repeat(51)));
        Session maker = maker();

        for (Map.Entry<String, Map<String, Object>> e : bad.entrySet()) {
            assertThat(status(enter(maker, YEAR, e.getValue()))).as(e.getKey()).isEqualTo(400);
        }

        assertThat(held(cin)).isZero();
        assertThat(held("CIN 24")).isZero();
    }

    @Test
    void SRS_7_21_aMalformedYearOrADateOutsideTheYearIsRefusedAndAMonthIsAValidPeriod()
            throws Exception {
        Session maker = maker();
        String cin = uniqueCin();
        assertThat(status(enter(maker, "2026", challan(cin)))).isEqualTo(400);
        assertThat(status(enter(maker, "2026-2027", challan(cin)))).isEqualTo(400);

        LocalDate before = TaxYearFixtures.april(YEAR).minusDays(1);
        MvcResult early = enter(maker, YEAR, with(challan(cin), "date", before.toString()));
        assertThat(error(early)).isEqualTo("TAX-422-CHALLAN-FILE");
        LocalDate after = TaxYearFixtures.march(YEAR).plusDays(1);
        assertThat(error(enter(maker, YEAR, with(challan(cin), "date", after.toString()))))
                .isEqualTo("TAX-422-CHALLAN-FILE");
        assertThat(held(cin)).isZero();

        String firstMonth = TaxYearFixtures.months(YEAR).get(0);
        MvcResult monthly = enter(maker, YEAR, with(challan(cin), "period", firstMonth));
        assertThat(status(monthly)).as(monthly.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(monthly).path("period").asString()).isEqualTo(firstMonth);
        assertThat(held(cin)).isEqualTo(1);
    }

    @Test
    void SRS_7_9_aCertificateReissueNeedsTheMakerAReasonAndAnExistingCertificate()
            throws Exception {
        Session maker = maker();
        String path = "/v1/tax/form16-certificates/999999/reissue";

        assertThat(status(post(checker(), path, Map.of("reason", "IT: wrong Part A"))))
                .isEqualTo(403);
        assertThat(status(post(maker, path, Map.of("reason", "")))).isEqualTo(400);
        assertThat(status(post(maker, path, Map.of("reason", "   ")))).isEqualTo(400);
        assertThat(status(post(maker, path, Map.of()))).isEqualTo(400);
        assertThat(status(post(maker, path, Map.of("reason", "café")))).isEqualTo(400);
        assertThat(status(post(maker, path, Map.of("reason", "x".repeat(1001))))).isEqualTo(400);
        assertThat(
                        status(
                                post(
                                        maker,
                                        "/v1/tax/form16-certificates/0/reissue",
                                        Map.of("reason", "IT: zero id"))))
                .isEqualTo(400);
        assertThat(status(post(maker, path, Map.of("reason", "IT: no such certificate"))))
                .isEqualTo(404);
    }
}
