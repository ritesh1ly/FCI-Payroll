package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import in.gov.fci.payroll.platform.error.FciException;
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
 * C-10 Annual Tax Certificate - Form 16 (docs/plan/08-income-tax.md phase 08g; SRS-7-5, SRS-7-9,
 * SRS-7-19, SRS-7-21, SRS-7-23; BR-TAX-010 … 012): Part B of a fully locked year under the Active
 * FORM16_PART_B definition, Part A uploaded and merged by the Payroll Maker, the release after the
 * reconciliation to the posted TDS and the remitted challans (the IT stand-ins of the posting
 * interface), approved by a different Payroll Checker (C10_RELEASE); the member's released
 * certificates on E-01. The year is 41 years back (TaxYearFixtures); 45 back has no definition.
 */
class Form16IT extends ApiTestSupport {

    private static final String YEAR = TaxYearFixtures.yearBack(41);
    private static final String UNVERSIONED = TaxYearFixtures.yearBack(45);
    private static final String UNLOCKED = TaxYearFixtures.yearBack(46);

    /**
     * A one-page PDF standing for the TRACES Part A: a real PDF, because the release now renders
     * the certificate (Part A merged before Part B) to freeze it (OD-153).
     */
    private static final byte[] PDF = onePagePdf();

    private static byte[] onePagePdf() {
        try (org.apache.pdfbox.pdmodel.PDDocument d = new org.apache.pdfbox.pdmodel.PDDocument()) {
            d.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.tax.orchestration.Form16Service form16;

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.tax.orchestration.Form16IssuedCopies issuedCopies;

    private byte[] partBPdf(Session s, long id) throws Exception {
        MvcResult r =
                mvc.perform(userGet(s, "/v1/tax/form16-certificates/" + id + "/part-b"))
                        .andReturn();
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
        return r.getResponse().getContentAsByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.organisation.api.OrgDirectory org;

    @AfterEach
    void clear() {
        ItTaxPorts.YEARS.clear();
        ItTaxPorts.POSTED.clear();
        ItTaxPorts.REMITTED.clear();
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

    /** FORM16_PART_B from the year's April, approved once (M-17). */
    private void definition() throws Exception {
        Integer held =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM report_definition_version v JOIN report_definition d"
                                + " ON d.report_def_id = v.report_def_id WHERE d.report_key ="
                                + " 'FORM16_PART_B'",
                        Integer.class);
        if (held > 0) {
            return;
        }
        Map<String, Object> v = new HashMap<>();
        v.put("effectiveFrom", TaxYearFixtures.april(YEAR).toString());
        v.put("layoutFormat", "PDF");
        v.put("periodLockRequired", true);
        v.put(
                "fields",
                List.of(
                        Map.of(
                                "label",
                                "Form",
                                "binding",
                                "LITERAL",
                                "reference",
                                "Form 16 Part B")));
        v.put("reason", "IT: Part B layout");
        MvcResult p =
                post(
                        login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001"),
                        "/v1/tax/output-definitions/FORM16_PART_B/versions",
                        v);
        assertThat(p.getResponse().getStatus())
                .as(p.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(
                        decide(
                                login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001"),
                                body(p).path("approvalId").asLong(),
                                "APPROVE",
                                "IT",
                                null))
                .isEqualTo(200);
    }

    /**
     * The year computed: rates, a locked year for the paying unit, regimes, the actual earnings.
     * 870001 and 870003 had the whole actual tax deducted (12 x 14,950 = 1,79,400 on the old slabs;
     * 12 x 20,000 = 2,40,000 at the no-PAN rate); 870005 only 12 x 8,000, so its year does not
     * reconcile (SRS-7-19).
     */
    private void finalYear() {
        TaxYearFixtures.rates(jdbc, YEAR);
        TaxYearFixtures.locked(
                jdbc, TaxYearFixtures.delhiUnit(jdbc, org), TaxYearFixtures.months(YEAR), YEAR);
        Map<String, String> tds = Map.of("870001", "14950", "870003", "20000", "870005", "8000");
        tds.forEach(
                (eis, monthly) -> {
                    Long id = TaxYearFixtures.member(jdbc, eis);
                    TaxYearFixtures.regime(jdbc, id, YEAR, "O");
                    ItTaxPorts.YEARS.put(
                            id + "|" + YEAR, ItTaxPorts.year(YEAR, "100000", monthly, true));
                });
    }

    private JsonNode certificate(Session s, String eis) throws Exception {
        for (JsonNode c : all(s, "/v1/tax/form16-certificates?finYear=" + YEAR)) {
            if (eis.equals(c.path("eis").asString())) {
                return c;
            }
        }
        return null;
    }

    private MvcResult partA(Session s, long id) throws Exception {
        MockMultipartHttpServletRequestBuilder r =
                multipart("/v1/tax/form16-certificates/" + id + "/part-a");
        r.file(new MockMultipartFile("file", "part-a.pdf", "application/pdf", PDF));
        r.file(
                new MockMultipartFile(
                        "request",
                        "",
                        MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(
                                Map.of(
                                        "documentType", "CERTIFICATE",
                                        "reference", "TRACES-IT-1",
                                        "documentDate",
                                                TaxYearFixtures.march(YEAR)
                                                        .plusMonths(2)
                                                        .toString(),
                                        "issuingAuthority", "Income Tax Department"))));
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private void challan(Session s, String line) throws Exception {
        MockMultipartHttpServletRequestBuilder r =
                multipart("/v1/tax/challans/import?finYear=" + YEAR);
        r.file(
                new MockMultipartFile(
                        "file",
                        "challans.csv",
                        "text/csv",
                        ("cin,bsr,date,amount,reference,period\n" + line)
                                .getBytes(StandardCharsets.US_ASCII)));
        MvcResult m =
                mvc.perform(
                                userRequest(s, r)
                                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                        .andReturn();
        assertThat(m.getResponse().getStatus())
                .as(m.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    /**
     * SC-08-0049 (C-10 main flow l.2565-2569) and SC-08-0052 (postcondition l.2574): Part B from
     * the locked year for the selected population, Part A uploaded and merged, the release once
     * reconciled; the merged certificate is retained (never regenerated, never changed) and served
     * to the member on eSeva only once released, never while it waits for the Checker.
     */
    @Test
    void SRS_7_9_partBIsGeneratedPartAMergedAndTheCertificateReleasedAfterReconciliation()
            throws Exception {
        definition();
        finalYear();
        Session maker = maker();
        Map<String, Object> year = Map.of("finYear", YEAR);
        // SRS-7-21: only the Payroll Maker generates.
        assertThat(
                        post(checker(), "/v1/tax/form16-certificates/generate", year)
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        MvcResult g = post(maker, "/v1/tax/form16-certificates/generate", year);
        assertThat(g.getResponse().getStatus())
                .as(g.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(g).path("generated").asInt()).isEqualTo(2);
        // The members of the unit without the year's earnings are listed with the reason.
        assertThat(texts(body(g).path("skipped"), "reason"))
                .anyMatch(r -> r.contains("TAX-422-EARNINGS-NOT-AVAILABLE"));
        // SRS-7-19: 870005's actual tax (1,79,400) is not what was deducted (96,000): no
        // certificate; the difference goes to X-05 for correction first.
        assertThat(body(g).path("skipped").toString())
                .contains("870005")
                .contains("83400")
                .contains("SRS-7-19");
        assertThat(certificate(maker, "870005")).isNull();
        // C-10 main flow: the Maker selects the member population; one outside the paying unit
        // (Kolkata) or unknown reads as missing; a malformed EIS is refused at the boundary.
        MvcResult one =
                post(
                        maker,
                        "/v1/tax/form16-certificates/generate",
                        Map.of("finYear", YEAR, "eis", List.of("870003")));
        assertThat(body(one).path("generated").asInt()).isEqualTo(1);
        assertThat(body(one).path("skipped")).isEmpty();
        for (String outside : List.of("870004", "999999")) {
            assertThat(
                            post(
                                            maker,
                                            "/v1/tax/form16-certificates/generate",
                                            Map.of("finYear", YEAR, "eis", List.of(outside)))
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(404);
        }
        assertThat(
                        post(
                                        maker,
                                        "/v1/tax/form16-certificates/generate",
                                        Map.of("finYear", YEAR, "eis", List.of("87-0001")))
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_exception WHERE employee_id = ? AND"
                                        + " exception_code = 'TAX_YEAR_END_MISMATCH'",
                                Integer.class,
                                TaxYearFixtures.member(jdbc, "870005")))
                .isPositive();

        JsonNode c = certificate(maker, "870001");
        assertThat(c).isNotNull();
        assertThat(c.path("status").asString()).isEqualTo("PENDING_PART_A");
        assertThat(c.path("partAStatus").asString()).isEqualTo("PENDING");
        assertThat(c.path("grossSalary").decimalValue()).isEqualByComparingTo("1200000");
        assertThat(c.path("taxDeducted").decimalValue()).isEqualByComparingTo("179400");
        // SRS-7-23: never the PAN on the screen.
        assertThat(c.toString()).doesNotContain("KLKPK7001A");
        // SRS-SEC-4: the register shows the PAN masked, with its state.
        // R-PII: in the one format of PiiMasks.pan (the last four behind six asterisks).
        assertThat(c.path("panMasked").asString()).isEqualTo("******001A");
        assertThat(c.path("panStatus").asString()).isEqualTo("VALID");
        // Another paying unit sees none of them.
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(all(kolkata, "/v1/tax/form16-certificates?finYear=" + YEAR)).isEmpty();

        // Nothing merged yet: nothing to release.
        assertThat(error(post(maker, "/v1/tax/form16-certificates/release", year)))
                .isEqualTo("TAX-409-DECLARATION-STATE");
        long id = c.path("id").asLong();
        MvcResult merged = partA(maker, id);
        assertThat(merged.getResponse().getStatus())
                .as(merged.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(merged).path("status").asString()).isEqualTo("MERGED");
        assertThat(body(merged).path("partAStatus").asString()).isEqualTo("RECEIVED");
        assertThat(partA(kolkata, id).getResponse().getStatus()).isEqualTo(404);
        // SC-08-0052: a merged certificate not yet released is not on the member's eSeva.
        Session member = login("tax.old@fci-demo.test", "EMPLOYEE_ESEVA", null);
        assertThat(
                        texts(
                                body(
                                        mvc.perform(
                                                        userGet(
                                                                member,
                                                                "/v1/tax/form16-certificates/mine"))
                                                .andReturn()),
                                "finYear"))
                .doesNotContain(YEAR);

        // BR-TAX-012: the posted payroll and the challans are not available: not reconciled.
        assertThat(error(post(maker, "/v1/tax/form16-certificates/release", year)))
                .isEqualTo("TAX-409-NOT-RECONCILED");
        Long unit = TaxYearFixtures.delhiUnit(jdbc, org);
        Map<String, BigDecimal> posted = new ConcurrentHashMap<>();
        TaxYearFixtures.months(YEAR).forEach(m -> posted.put(m, new BigDecimal("14950")));
        ItTaxPorts.POSTED.put(unit, posted);
        LocalDate paid = TaxYearFixtures.march(YEAR).minusDays(20);
        challan(maker, "CIN16IT1,0510308," + paid + ",179400.00,UTR16IT,Q4");
        ItTaxPorts.REMITTED.add("CIN16IT1|0510308|" + paid);

        MvcResult release = post(maker, "/v1/tax/form16-certificates/release", year);
        assertThat(release.getResponse().getStatus())
                .as(release.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(release).path("approvalId").asLong();
        assertThat(
                        post(maker, "/v1/tax/form16-certificates/release", year)
                                .getResponse()
                                .getStatus())
                .isEqualTo(409);
        assertThat(decide(maker, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(checker(), approval, "APPROVE", "IT: reconciled", null)).isEqualTo(200);
        assertThat(certificate(maker, "870001").path("status").asString()).isEqualTo("RELEASED");

        // OD-153: the release froze the certificate once: one report_output of the certificate's
        // definition version, RELEASED, with the stored file and its checksum, linked from form16.
        Map<String, Object> output =
                jdbc.queryForMap(
                        "SELECT o.report_output_id, o.report_version_id, o.output_status,"
                                + " o.attachment_id, o.checksum_sha256, o.released_by,"
                                + " f.definition_version_id FROM form16 f JOIN report_output o ON"
                                + " o.report_output_id = f.report_output_id WHERE f.form16_id = ?",
                        id);
        assertThat(output)
                .containsEntry("OUTPUT_STATUS", "RELEASED")
                .containsEntry("RELEASED_BY", "tax.checker.delhi@fci-demo.test");
        assertThat(((Number) output.get("REPORT_VERSION_ID")).longValue())
                .isEqualTo(((Number) output.get("DEFINITION_VERSION_ID")).longValue());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM document_attachment WHERE entity_name ="
                                        + " 'form16_issued' AND entity_key = ?",
                                Integer.class,
                                String.valueOf(id)))
                .isEqualTo(1);
        // C-10 and the member read the same bytes, the stored ones (Part A first, then Part B).
        Session owner = login("tax.old@fci-demo.test", "EMPLOYEE_ESEVA", null);
        byte[] issued = partBPdf(maker, id);
        assertThat(sha256(issued)).isEqualTo(output.get("CHECKSUM_SHA256"));
        assertThat(partBPdf(owner, id)).isEqualTo(issued);
        // IDOR: another member never reads it.
        Session stranger = login("tax.new@fci-demo.test", "EMPLOYEE_ESEVA", null);
        assertThat(
                        mvc.perform(
                                        userGet(
                                                stranger,
                                                "/v1/tax/form16-certificates/" + id + "/part-b"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isIn(403, 404);
        // The year's payroll is no longer what it was (here: gone): the issued certificate does
        // not change, where recomputing it would now fail. The single download, the bulk job's
        // file and the e-mail (both through the same service) serve the issued bytes.
        Map<String, in.gov.fci.payroll.tax.business.TaxableEarnings.Year> saved =
                new HashMap<>(ItTaxPorts.YEARS);
        ItTaxPorts.YEARS.clear();
        try {
            assertThat(partBPdf(maker, id)).isEqualTo(issued);
            assertThat(partBPdf(owner, id)).isEqualTo(issued);
            assertThat(form16.certificatePdfForJob(id, "it.bulk")).isEqualTo(issued);
        } finally {
            ItTaxPorts.YEARS.putAll(saved);
        }
        // An unreleased certificate is rendered on demand and frozen by nothing.
        long pending = certificate(maker, "870003").path("id").asLong();
        assertThat(new String(partBPdf(maker, pending), 0, 4, StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM form16 WHERE form16_id = ? AND"
                                        + " report_output_id IS NOT NULL",
                                Integer.class,
                                pending))
                .isZero();
        // A reissue released again freezes a new copy; the previous output is SUPERSEDED and its
        // file stays (R-DEL).
        long first = ((Number) output.get("REPORT_OUTPUT_ID")).longValue();
        byte[] second = onePagePdf();
        long next =
                issuedCopies.freeze(
                        id,
                        ((Number) output.get("DEFINITION_VERSION_ID")).longValue(),
                        YEAR,
                        TaxYearFixtures.delhiUnit(jdbc, org),
                        "tax.checker.delhi@fci-demo.test",
                        new in.gov.fci.payroll.tax.orchestration.Form16Pdf.Merged(
                                second, 1, false));
        assertThat(next).isNotEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT output_status FROM report_output WHERE"
                                        + " report_output_id = ?",
                                String.class,
                                first))
                .isEqualTo("SUPERSEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM document_attachment WHERE entity_name ="
                                        + " 'form16_issued' AND entity_key = ?",
                                Integer.class,
                                String.valueOf(id)))
                .isEqualTo(2);
        assertThat(partBPdf(owner, id)).isEqualTo(second);
        // OD-153 ("Disable PDF with reason"): a certificate released before issued copies were
        // kept (no report_output linked) is refused with its code, never recomputed - not on C-10,
        // not for the member, not in the bulk job; E-01 lists it without a PDF.
        jdbc.update("UPDATE form16 SET report_output_id = NULL WHERE form16_id = ?", id);
        try {
            for (Session reader : List.of(maker, owner)) {
                MvcResult refused =
                        mvc.perform(
                                        userGet(
                                                reader,
                                                "/v1/tax/form16-certificates/" + id + "/part-b"))
                                .andReturn();
                assertThat(refused.getResponse().getStatus()).isEqualTo(409);
                assertThat(error(refused)).isEqualTo("TAX-409-NO-ISSUED-COPY");
            }
            assertThat(catchThrowable(() -> form16.certificatePdfForJob(id, "it.bulk")))
                    .isInstanceOf(FciException.class)
                    .hasMessageContaining("TAX-409-NO-ISSUED-COPY");
            JsonNode overview =
                    body(mvc.perform(userGet(owner, "/v1/eseva/overview/mine")).andReturn());
            assertThat(overview.path("documents").toString())
                    .contains("\"state\":\"ISSUED_NO_COPY\"");
        } finally {
            jdbc.update("UPDATE form16 SET report_output_id = ? WHERE form16_id = ?", next, id);
        }

        // E-01: the member's released certificate and its Part A; another member's is refused.
        // (a fresh session: one user holds one session, and the owner signed in again above)
        member = login("tax.old@fci-demo.test", "EMPLOYEE_ESEVA", null);
        JsonNode mine =
                body(mvc.perform(userGet(member, "/v1/tax/form16-certificates/mine")).andReturn());
        assertThat(texts(mine, "finYear")).contains(YEAR);
        JsonNode served = null;
        for (JsonNode m : mine) {
            if (m.path("id").asLong() == id) {
                served = m;
            }
        }
        assertThat(served).isNotNull();
        assertThat(served.path("status").asString()).isEqualTo("RELEASED");
        assertThat(served.path("partAStatus").asString()).isEqualTo("RECEIVED");
        assertThat(served.path("taxDeducted").decimalValue()).isEqualByComparingTo("179400");
        assertThat(served.path("releasedAt").isNull()).isFalse();
        assertThat(mine.toString()).doesNotContain("KLKPK7001A");
        assertThat(
                        mvc.perform(
                                        userGet(
                                                member,
                                                "/v1/tax/form16-certificates/" + id + "/part-a"))
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray())
                .isEqualTo(PDF);
        Session other = login("tax.new@fci-demo.test", "EMPLOYEE_ESEVA", null);
        assertThat(
                        mvc.perform(userGet(other, "/v1/tax/form16-certificates/" + id + "/part-a"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);

        // A released certificate is never generated again, nor reissued without a correction.
        MvcResult again = post(maker, "/v1/tax/form16-certificates/generate", year);
        assertThat(texts(body(again).path("skipped"), "eis")).contains("870001");
        // SC-08-0052: retained as released, the same certificate with the same amounts.
        JsonNode retained = certificate(maker, "870001");
        assertThat(retained.path("id").asLong()).isEqualTo(id);
        assertThat(retained.path("status").asString()).isEqualTo("RELEASED");
        assertThat(retained.path("taxDeducted").decimalValue()).isEqualByComparingTo("179400");
        assertThat(
                        error(
                                post(
                                        maker,
                                        "/v1/tax/form16-certificates/" + id + "/reissue",
                                        Map.of("reason", "IT"))))
                .isEqualTo("TAX-409-DECLARATION-STATE");
        // A merged certificate has its Part A replaced, a released one does not.
        assertThat(error(partA(maker, id))).isEqualTo("TAX-409-DECLARATION-STATE");
    }

    /**
     * SC-08-0034 (M-17 postcondition): the activated definition governs the output only for periods
     * on or after its effective date - a year before the FORM16_PART_B version is not generated.
     */
    @Test
    void BR_MIS_002_aYearWithoutAnActiveDefinitionOrALockedPeriodIsNotGenerated() throws Exception {
        Session maker = maker();
        TaxYearFixtures.locked(
                jdbc,
                TaxYearFixtures.delhiUnit(jdbc, org),
                TaxYearFixtures.months(UNVERSIONED),
                UNVERSIONED);
        assertThat(
                        error(
                                post(
                                        maker,
                                        "/v1/tax/form16-certificates/generate",
                                        Map.of("finYear", UNVERSIONED))))
                .isEqualTo("TAX-409-OUTPUT-BLOCKED");
        assertThat(
                        error(
                                post(
                                        maker,
                                        "/v1/tax/form16-certificates/generate",
                                        Map.of("finYear", UNLOCKED))))
                .isEqualTo("TAX-409-PERIOD-NOT-LOCKED");
        // The boundary: a year that is not a financial year; a status outside the vocabulary.
        assertThat(
                        post(
                                        maker,
                                        "/v1/tax/form16-certificates/generate",
                                        Map.of("finYear", "2026"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/tax/form16-certificates?finYear="
                                                        + YEAR
                                                        + "&status=SENT"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        // A certificate that does not exist.
        assertThat(
                        post(
                                        maker,
                                        "/v1/tax/form16-certificates/999999/reissue",
                                        Map.of("reason", "IT"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(
                        mvc.perform(userGet(maker, "/v1/tax/form16-certificates/999999/part-a"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
    }
}
