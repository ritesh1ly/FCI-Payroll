package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import in.gov.fci.payroll.tax.business.ReturnValidator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * C-11 Statutory Returns - Form 24Q (docs/plan/08-income-tax.md phase 08h; SRS-7-7, SRS-7-8,
 * SRS-7-13, SRS-7-21, SRS-7-23, SRS-7-31): the challan file, the return of a locked quarter under
 * the Active M-17 definition, the itemised validation (PAN on X-05, challan mapping and remittance,
 * control totals, the prescribed utility: the IT stand-ins of the posting interface and the
 * utility), the release by a different Payroll Checker (C11_RELEASE), the filing, and the
 * correction of a released return. One live original per quarter. The year is 42 years back.
 */
class ReturnIT extends ApiTestSupport {

    private static final String YEAR = TaxYearFixtures.yearBack(42);
    private static final byte[] ACK =
            "%PDF-1.7 24Q acknowledgement".getBytes(StandardCharsets.US_ASCII);

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.organisation.api.OrgDirectory org;

    @AfterEach
    void clear() {
        ItTaxPorts.YEARS.clear();
        ItTaxPorts.POSTED.clear();
        ItTaxPorts.REMITTED.clear();
        ItTaxPorts.VERDICTS.clear();
    }

    private Session maker() throws Exception {
        return login("tax.maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session checker() throws Exception {
        return login("tax.checker.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    /** FORM_24Q and FORM_24Q_CORRECTION from the year's April, each approved once (M-17). */
    private void definitions() throws Exception {
        for (String key : List.of("FORM_24Q", "FORM_24Q_CORRECTION")) {
            Integer held =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM report_definition_version v JOIN"
                                    + " report_definition d ON d.report_def_id = v.report_def_id"
                                    + " WHERE d.report_key = ?",
                            Integer.class,
                            key);
            if (held > 0) {
                continue;
            }
            Map<String, Object> v = new HashMap<>();
            v.put("effectiveFrom", TaxYearFixtures.april(YEAR).toString());
            v.put("layoutFormat", "PRESCRIBED_FILE");
            v.put("periodLockRequired", true);
            v.put(
                    "fields",
                    List.of(Map.of("label", "Form", "binding", "LITERAL", "reference", key)));
            v.put(
                    "controls",
                    List.of(
                            Map.of(
                                    "label", "TDS",
                                    "reconcilesAgainst", "CHALLAN",
                                    "tolerance", "0.00",
                                    "releaseGate", true)));
            v.put("reason", "IT: prescribed file layout");
            MvcResult p =
                    post(
                            login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001"),
                            "/v1/tax/output-definitions/" + key + "/versions",
                            v);
            assertThat(status(p)).as(p.getResponse().getContentAsString()).isEqualTo(202);
            assertThat(
                            decide(
                                    login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001"),
                                    body(p).path("approvalId").asLong(),
                                    "APPROVE",
                                    "IT",
                                    null))
                    .isEqualTo(200);
        }
    }

    private MvcResult importFile(Session s, String content) throws Exception {
        MockMultipartHttpServletRequestBuilder r =
                multipart("/v1/tax/challans/import?finYear=" + YEAR);
        r.file(
                new MockMultipartFile(
                        "file",
                        "challans.csv",
                        "text/csv",
                        content.getBytes(StandardCharsets.US_ASCII)));
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private MvcResult generate(Session s, String quarter, Long corrects) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("finYear", YEAR);
        body.put("quarter", quarter);
        body.put("correctsReturnId", corrects);
        return post(s, "/v1/tax/returns/generate", body);
    }

    private MvcResult filing(Session s, long id, LocalDate date) throws Exception {
        return filing(s, id, date, true);
    }

    /** The filing; {@code withEvidence} false sends neither the file nor its particulars. */
    private MvcResult filing(Session s, long id, LocalDate date, boolean withEvidence)
            throws Exception {
        MockMultipartHttpServletRequestBuilder r = multipart("/v1/tax/returns/" + id + "/filings");
        if (withEvidence) {
            r.file(new MockMultipartFile("file", "ack.pdf", "application/pdf", ACK));
        }
        Map<String, Object> f = new HashMap<>();
        f.put("filingDate", date.toString());
        f.put("signatoryName", "R K Sharma");
        f.put("signatoryDesignation", "Authorised Signatory (TDS)");
        f.put("acknowledgementToken", "TOKEN24QIT1");
        if (withEvidence) {
            f.put(
                    "evidence",
                    Map.of(
                            "documentType", "CERTIFICATE",
                            "documentDate", date.toString(),
                            "issuingAuthority", "TIN Facilitation Centre"));
        }
        r.file(
                new MockMultipartFile(
                        "request",
                        "",
                        MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(f)));
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private JsonNode returnOf(Session s, long id) throws Exception {
        for (JsonNode r : all(s, "/v1/tax/returns?finYear=" + YEAR)) {
            if (r.path("id").asLong() == id) {
                return r;
            }
        }
        return null;
    }

    @Test
    void SRS_7_21_theChallanFileIsCheckedLineByLineAndAChallanHeldIsPassedOver() throws Exception {
        Session maker = maker();
        // Q2: the lifecycle test reconciles Q1 of the same year.
        LocalDate day = TaxYearFixtures.april(YEAR).plusMonths(4).plusDays(6);
        String line = "CIN24IT9,0510308," + day + ",1000.00,UTR9,Q2";
        MvcResult first =
                importFile(maker, "cin,bsr,date,amount,reference,period\n" + line + "\n\n");
        assertThat(status(first)).as(first.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(first).path("imported").asInt()).isEqualTo(1);
        MvcResult again = importFile(maker, line);
        assertThat(body(again).path("imported").asInt()).isZero();
        assertThat(body(again).path("duplicates")).hasSize(1);
        // The whole file is refused on its first wrong line; a challan twice in one file.
        assertThat(error(importFile(maker, line.replace("Q2", "Q5"))))
                .isEqualTo("TAX-422-CHALLAN-FILE");
        assertThat(
                        error(
                                importFile(
                                        maker,
                                        line.replace("CIN24IT9", "CIN24ITA")
                                                + "\n"
                                                + line.replace("CIN24IT9", "CIN24ITA"))))
                .isEqualTo("TAX-422-CHALLAN-FILE");
        assertThat(
                        texts(
                                body(
                                        mvc.perform(
                                                        userGet(
                                                                maker,
                                                                "/v1/tax/challans?finYear=" + YEAR))
                                                .andReturn()),
                                "cin"))
                .contains("CIN24IT9")
                .doesNotContain("CIN24ITA");
        // Only the Payroll Maker imports; another unit does not see the challans.
        assertThat(status(importFile(checker(), line))).isEqualTo(403);
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(
                        body(
                                mvc.perform(userGet(kolkata, "/v1/tax/challans?finYear=" + YEAR))
                                        .andReturn()))
                .isEmpty();
    }

    /**
     * SC-08-0056 (C-11 main flow l.2593-2598), SC-08-0060 (postcondition l.2608: the validated
     * return is retained with its control totals; regenerated identically while nothing changed)
     * and SC-08-0061 (l.2609, SRS-7-31: the filing acknowledgement and token recorded once; the
     * challans stay mapped to the deductees: the filing record has no challan column, PENDING).
     */
    @Test
    void SRS_7_8_theReturnIsValidatedReleasedFiledAndCorrected() throws Exception {
        definitions();
        TaxYearFixtures.rates(jdbc, YEAR);
        List<String> q1 = TaxYearFixtures.months(YEAR).subList(0, 3);
        Long unit = TaxYearFixtures.delhiUnit(jdbc, org);
        TaxYearFixtures.locked(jdbc, unit, TaxYearFixtures.months(YEAR).subList(0, 6), YEAR);
        Long old = TaxYearFixtures.member(jdbc, "870001");
        Long noPan = TaxYearFixtures.member(jdbc, "870003");
        TaxYearFixtures.regime(jdbc, old, YEAR, "O");
        TaxYearFixtures.regime(jdbc, noPan, YEAR, "O");
        ItTaxPorts.YEARS.put(old + "|" + YEAR, ItTaxPorts.year(YEAR, "100000", "8000", true));
        // The member without a PAN had TDS in Q2 only.
        ItTaxPorts.YEARS.put(
                noPan + "|" + YEAR, ItTaxPorts.year(YEAR, "60000", "5000", true, 3, 5));
        Session maker = maker();
        LocalDate paid = TaxYearFixtures.april(YEAR).plusMonths(2).plusDays(19);
        assertThat(status(importFile(maker, "CIN24IT1,0510308," + paid + ",24000.00,UTR1,Q1")))
                .isEqualTo(200);

        // A quarter not locked; the checker never generates.
        assertThat(error(generate(maker, "Q3", null))).isEqualTo("TAX-409-PERIOD-NOT-LOCKED");
        assertThat(status(generate(checker(), "Q2", null))).isEqualTo(403);
        MvcResult g = generate(maker, "Q2", null);
        assertThat(status(g)).as(g.getResponse().getContentAsString()).isEqualTo(201);
        long first = body(g).path("id").asLong();
        assertThat(body(g).path("type").asString()).isEqualTo("FORM_24Q");
        assertThat(body(g).path("deductees").asLong()).isEqualTo(2);
        assertThat(body(g).path("totalTds").decimalValue()).isEqualByComparingTo("39000");
        // SC-08-0060: regenerated with nothing changed, the same row and the same control totals.
        MvcResult same = generate(maker, "Q2", null);
        assertThat(status(same)).as(same.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(same).path("id").asLong()).isEqualTo(first);
        assertThat(body(same).path("deductees").asLong()).isEqualTo(2);
        assertThat(body(same).path("totalTds").decimalValue()).isEqualByComparingTo("39000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM statutory_return_deductee WHERE return_id ="
                                        + " ?",
                                Integer.class,
                                first))
                .isEqualTo(2);

        // SRS-7-23: the deductees with the PAN masked.
        JsonNode deductees = all(maker, "/v1/tax/returns/" + first + "/deductees");
        assertThat(deductees.toString()).doesNotContain("KLKPK7001A");
        for (JsonNode d : deductees) {
            if ("870003".equals(d.path("eis").asString())) {
                assertThat(d.path("panValid").asBoolean()).isFalse();
                assertThat(d.path("higherRateApplied").asBoolean()).isTrue();
            } else {
                // R-PII: the one format of PiiMasks.pan - six asterisks and the last four, or
                // none of a value that is not a ten-character PAN.
                assertThat(d.path("panMasked").asString()).matches("[*]{6}[A-Z0-9]{4}|[*]{4}");
            }
        }

        // SRS-7-8: each failed check is itemised; the missing PAN is raised on X-05.
        MvcResult v = post(maker, "/v1/tax/returns/" + first + "/validate", Map.of());
        assertThat(status(v)).as(v.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(v).path("validated").asBoolean()).isFalse();
        assertThat(body(v).path("status").asString()).isEqualTo("GENERATED");
        assertThat(body(v).path("validationResult").valueStream().map(JsonNode::asString))
                .anyMatch(r -> r.contains("PAN missing or invalid for member 870003"))
                .anyMatch(r -> r.contains("no challan mapped for member 870003"))
                .anyMatch(r -> r.contains("posted payroll is not available"))
                .anyMatch(r -> r.contains("validation utility is not configured"));
        // X-05 holds one open exception per member and code, whichever screen raised it first.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_exception WHERE employee_id = ? AND"
                                        + " exception_code = 'PAN_MISSING'",
                                Integer.class,
                                noPan))
                .isPositive();
        assertThat(error(post(maker, "/v1/tax/returns/" + first + "/release", Map.of())))
                .isEqualTo("TAX-409-DECLARATION-STATE");
        // C-11 alternate flow: the underlying data corrected (the member's Q2 TDS put right), the
        // return is regenerated as a routine function, in place (one row per quarter, DDL
        // uk_statutory_return): the former deductee is left out, its row kept and emptied (never
        // deleted), the earlier validation gone.
        ItTaxPorts.YEARS.put(noPan + "|" + YEAR, ItTaxPorts.year(YEAR, "60000", "0", true));
        MvcResult again = generate(maker, "Q2", null);
        assertThat(status(again)).as(again.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(again).path("id").asLong()).isEqualTo(first);
        assertThat(body(again).path("deductees").asLong()).isEqualTo(1);
        assertThat(body(again).path("totalTds").decimalValue()).isEqualByComparingTo("24000");
        assertThat(body(again).path("validationResult")).isEmpty();
        assertThat(texts(all(maker, "/v1/tax/returns/" + first + "/deductees"), "eis"))
                .containsExactly("870001");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM statutory_return_deductee WHERE return_id ="
                                        + " ?",
                                Integer.class,
                                first))
                .isEqualTo(2);

        // Q1: the member without a PAN had no TDS, so is not a deductee.
        MvcResult q1Return = generate(maker, "Q1", null);
        assertThat(status(q1Return)).as(q1Return.getResponse().getContentAsString()).isEqualTo(201);
        long id = body(q1Return).path("id").asLong();
        assertThat(body(q1Return).path("deductees").asLong()).isEqualTo(1);
        assertThat(body(q1Return).path("totalTds").decimalValue()).isEqualByComparingTo("24000");

        Map<String, BigDecimal> posted = new ConcurrentHashMap<>();
        q1.forEach(m -> posted.put(m, new BigDecimal("8000")));
        ItTaxPorts.POSTED.put(unit, posted);
        ItTaxPorts.REMITTED.add("CIN24IT1|0510308|" + paid);
        ItTaxPorts.VERDICTS.put(id, new ReturnValidator.Verdict("FVU-IT", true, List.of()));
        MvcResult ok = post(maker, "/v1/tax/returns/" + id + "/validate", Map.of());
        assertThat(body(ok).path("validationResult").toString()).isEqualTo("[]");
        assertThat(body(ok).path("status").asString()).isEqualTo("VALIDATED");
        assertThat(body(ok).path("validationUtility").asString()).isEqualTo("FVU-IT");

        MvcResult release = post(maker, "/v1/tax/returns/" + id + "/release", Map.of());
        assertThat(status(release)).as(release.getResponse().getContentAsString()).isEqualTo(202);
        long approval = body(release).path("approvalId").asLong();
        assertThat(status(post(maker, "/v1/tax/returns/" + id + "/release", Map.of())))
                .isEqualTo(409);
        // Not regenerated while its release waits for the Checker.
        assertThat(error(generate(maker, "Q1", null))).isEqualTo("MST-409-PENDING");
        assertThat(decide(maker, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(checker(), approval, "APPROVE", "IT: validated", null)).isEqualTo(200);
        JsonNode released = returnOf(maker, id);
        assertThat(released.path("status").asString()).isEqualTo("RELEASED");
        assertThat(released.path("releasedBy").asString())
                .isEqualTo("tax.checker.delhi@fci-demo.test");
        // SC-08-0060: retained with its control totals and its clean validation.
        assertThat(released.path("validated").asBoolean()).isTrue();
        assertThat(released.path("validationUtility").asString()).isEqualTo("FVU-IT");
        assertThat(released.path("validationResult")).isEmpty();
        assertThat(released.path("deductees").asLong()).isEqualTo(1);
        assertThat(released.path("totalTds").decimalValue()).isEqualByComparingTo("24000");
        // A released original is corrected, never generated again.
        assertThat(error(generate(maker, "Q1", null))).isEqualTo("TAX-409-DECLARATION-STATE");

        // SRS-7-31: the filing by the authorised signatory; never dated ahead.
        LocalDate today = LocalDate.now(TaxYearFixtures.INDIA);
        assertThat(status(filing(maker, id, today.plusDays(1)))).isEqualTo(400);
        assertThat(status(filing(maker, id, today.plusDays(1), false))).isEqualTo(400);
        MvcResult filed = filing(maker, id, today);
        assertThat(status(filed)).as(filed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(filed).path("status").asString()).isEqualTo("FILED");
        long evidence = body(filed).path("filings").get(0).path("evidenceId").asLong();
        MvcResult ack =
                mvc.perform(userGet(maker, "/v1/tax/returns/" + id + "/documents/" + evidence))
                        .andReturn();
        assertThat(ack.getResponse().getContentAsByteArray()).isEqualTo(ACK);
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        kolkata,
                                                        "/v1/tax/returns/"
                                                                + id
                                                                + "/documents/"
                                                                + evidence))
                                        .andReturn()))
                .isEqualTo(404);
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        kolkata,
                                                        "/v1/tax/returns/" + id + "/deductees"))
                                        .andReturn()))
                .isEqualTo(404);
        assertThat(all(kolkata, "/v1/tax/returns?finYear=" + YEAR)).isEmpty();
        assertThat(error(filing(maker, id, today))).isEqualTo("TAX-409-DECLARATION-STATE");
        // SC-08-0061: recorded once - one filing with its token, signatory and evidence; the
        // challan stays mapped to the deductee.
        JsonNode recorded = returnOf(maker, id);
        assertThat(recorded.path("status").asString()).isEqualTo("FILED");
        assertThat(recorded.path("filings")).hasSize(1);
        JsonNode once = recorded.path("filings").get(0);
        assertThat(once.path("acknowledgementToken").asString()).isEqualTo("TOKEN24QIT1");
        assertThat(once.path("signatoryName").asString()).isEqualTo("R K Sharma");
        assertThat(once.path("filingDate").asString()).isEqualTo(today.toString());
        assertThat(once.path("evidenceId").asLong()).isEqualTo(evidence);
        assertThat(texts(all(maker, "/v1/tax/returns/" + id + "/deductees"), "challanReference"))
                .singleElement()
                .asString()
                .contains("CIN24IT1");

        // The correction of the filed return, under its own definition; never of a correction.
        MvcResult c = generate(maker, "Q1", id);
        assertThat(status(c)).as(c.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(c).path("type").asString()).isEqualTo("FORM_24Q_CORRECTION");
        assertThat(body(c).path("correction").asBoolean()).isTrue();
        assertThat(body(c).path("correctsReturnId").asLong()).isEqualTo(id);
        assertThat(error(generate(maker, "Q1", body(c).path("id").asLong())))
                .isEqualTo("TAX-409-DECLARATION-STATE");
        // One correction per corrected return (DDL uk_statutory_return): while not released it
        // is regenerated in place.
        MvcResult c2 = generate(maker, "Q1", id);
        assertThat(status(c2)).as(c2.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(c2).path("id").asLong()).isEqualTo(body(c).path("id").asLong());
        assertThat(error(generate(maker, "Q1", first))).isEqualTo("TAX-409-DECLARATION-STATE");
        assertThat(status(generate(maker, "Q5", null))).isEqualTo(400);
    }

    @Test
    void SRS_7_19_theFourthQuarterIsNotValidatedWhileAYearDoesNotReconcile() throws Exception {
        // A year of its own, governed by the FORM_24Q version that starts 42 years back.
        String year = TaxYearFixtures.yearBack(41);
        definitions();
        TaxYearFixtures.rates(jdbc, year);
        Long unit = TaxYearFixtures.delhiUnit(jdbc, org);
        TaxYearFixtures.locked(jdbc, unit, TaxYearFixtures.months(year), year);
        Long member = TaxYearFixtures.member(jdbc, "870002");
        TaxYearFixtures.regime(jdbc, member, year, "N");
        // New slabs on 6,00,000: 15,600 actual tax; 12 x 1,000 deducted: Rs 3,600 short.
        ItTaxPorts.YEARS.put(member + "|" + year, ItTaxPorts.year(year, "50000", "1000", true));
        Session maker = maker();
        Map<String, Object> body = new HashMap<>();
        body.put("finYear", year);
        body.put("quarter", "Q4");
        MvcResult g = post(maker, "/v1/tax/returns/generate", body);
        assertThat(status(g)).as(g.getResponse().getContentAsString()).isEqualTo(201);
        MvcResult v =
                post(
                        maker,
                        "/v1/tax/returns/" + body(g).path("id").asLong() + "/validate",
                        Map.of());
        assertThat(body(v).path("status").asString()).isEqualTo("GENERATED");
        assertThat(body(v).path("validationResult").valueStream().map(JsonNode::asString))
                .anyMatch(r -> r.contains("year-end difference of Rs 3600 for member 870002"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_exception WHERE employee_id = ? AND"
                                        + " exception_code = 'TAX_YEAR_END_MISMATCH'",
                                Integer.class,
                                member))
                .isPositive();
        // Another quarter of the same year is not held to the year-end reconciliation.
        body.put("quarter", "Q3");
        MvcResult q3 = post(maker, "/v1/tax/returns/generate", body);
        MvcResult v3 =
                post(
                        maker,
                        "/v1/tax/returns/" + body(q3).path("id").asLong() + "/validate",
                        Map.of());
        assertThat(body(v3).path("validationResult").toString()).doesNotContain("year-end");
    }
}
