package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * I-18 Savings & Tax Rebate Declarations (docs/plan/08-income-tax.md phase 08d; SRS-7-11, SRS-7-20,
 * SRS-7-25; BR-TAX-007, BR-TAX-015): the Payroll Maker of the paying unit records external savings
 * and other income with a supporting document, a different Payroll Checker approves, only approved
 * entries count, and the transport rebate months of the year. Members and sections of the demo seed
 * V039_9; each test uses a member of its own.
 */
class SavingsIT extends ApiTestSupport {

    private static final byte[] PDF = "%PDF-1.7 savings proof".getBytes(StandardCharsets.US_ASCII);
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final String YEAR =
            in.gov.fci.payroll.tax.business.DeclarationRules.finYearOf(TODAY);

    private Session maker() throws Exception {
        return login("tax.maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session checker() throws Exception {
        return login("tax.checker.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private static Map<String, Object> entry(
            String type, String section, String head, String amount) {
        Map<String, Object> e = new HashMap<>();
        e.put("type", type);
        e.put("sectionCode", section);
        e.put("incomeHead", head);
        e.put("description", "IT entry");
        e.put("amount", amount);
        e.put("tdsOtherSource", "0");
        e.put(
                "document",
                Map.of(
                        "documentType", "CERTIFICATE",
                        "documentDate", TODAY.minusDays(1).toString(),
                        "issuingAuthority", "Issuer, Delhi"));
        return e;
    }

    private MvcResult save(Session s, String eis, Map<String, Object> body, boolean file)
            throws Exception {
        MockMultipartHttpServletRequestBuilder r =
                multipart("/v1/tax/savings/members/" + eis + "/entries");
        if (file) {
            r.file(new MockMultipartFile("file", "proof.pdf", "application/pdf", PDF));
        }
        r.file(
                new MockMultipartFile(
                        "request",
                        "",
                        MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(body)));
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private JsonNode year(Session s, String eis) throws Exception {
        return body(mvc.perform(userGet(s, "/v1/tax/savings/members/" + eis)).andReturn());
    }

    private long approvalOf(long entry) {
        return jdbc.queryForObject(
                "SELECT approval_id FROM external_income_declaration WHERE ext_income_id = ?",
                Long.class,
                entry);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    @Test
    void SRS_7_25_anEntryWithItsDocumentIsSavedSubmittedAndApprovedByADifferentChecker()
            throws Exception {
        // tax.nopan@ (870003, DH001): no entries in the demo seed.
        String eis = "870003";
        Session maker = maker();
        MvcResult saved =
                save(maker, eis, entry("OTHER_INCOME", null, "Honorarium", "12000"), true);
        assertThat(status(saved)).as(saved.getResponse().getContentAsString()).isEqualTo(201);
        long id = body(saved).path("id").asLong();
        assertThat(body(saved).path("status").asString()).isEqualTo("MAKER_SAVED");
        long document = body(saved).path("documentId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT entity_key FROM document_attachment WHERE attachment_id ="
                                        + " ?",
                                String.class,
                                document))
                .isEqualTo(String.valueOf(id));
        assertThat(
                        download(maker, "/v1/tax/savings/entries/" + id + "/documents/" + document)
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);

        MvcResult submitted = post(maker, "/v1/tax/savings/entries/" + id + "/submit", Map.of());
        assertThat(status(submitted))
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(body(submitted).path("status").asString()).isEqualTo("PENDING_CHECKER");
        // Submitted once; neither cancelled nor submitted again; never decided by the Maker.
        assertThat(error(post(maker, "/v1/tax/savings/entries/" + id + "/submit", Map.of())))
                .isEqualTo("TAX-409-DECLARATION-STATE");
        assertThat(
                        error(
                                post(
                                        maker,
                                        "/v1/tax/savings/entries/" + id + "/cancel",
                                        Map.of("reason", "IT"))))
                .isEqualTo("TAX-409-DECLARATION-STATE");
        assertThat(decide(maker, approvalOf(id), "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(checker(), approvalOf(id), "APPROVE", null, null)).isEqualTo(200);

        // BR-TAX-015: an approved external savings entry writes its savings row; a rejected one
        // counts nothing.
        long savings =
                body(save(maker, eis, entry("EXTERNAL_SAVINGS", "80C", null, "40000"), true))
                        .path("id")
                        .asLong();
        post(maker, "/v1/tax/savings/entries/" + savings + "/submit", Map.of());
        assertThat(decide(checker(), approvalOf(savings), "APPROVE", null, null)).isEqualTo(200);
        long rejected =
                body(save(maker, eis, entry("OTHER_INCOME", null, "NSC interest", "900"), true))
                        .path("id")
                        .asLong();
        post(maker, "/v1/tax/savings/entries/" + rejected + "/submit", Map.of());
        assertThat(decide(checker(), approvalOf(rejected), "REJECT", "IT: no certificate", null))
                .isEqualTo(200);
        long cancelled =
                body(save(
                                maker,
                                eis,
                                entry("OTHER_INCOME", null, "Vehicle perquisite", "5000"),
                                true))
                        .path("id")
                        .asLong();
        assertThat(
                        status(
                                post(
                                        maker,
                                        "/v1/tax/savings/entries/" + cancelled + "/cancel",
                                        Map.of())))
                .isEqualTo(400);
        assertThat(
                        body(post(
                                        maker,
                                        "/v1/tax/savings/entries/" + cancelled + "/cancel",
                                        Map.of("reason", "IT: entered twice")))
                                .path("status")
                                .asString())
                .isEqualTo("CANCELLED");

        JsonNode view = year(maker, eis);
        assertThat(view.path("finYear").asString()).isEqualTo(YEAR);
        Map<Long, String> statuses = new HashMap<>();
        view.path("entries")
                .forEach(e -> statuses.put(e.path("id").asLong(), e.path("status").asString()));
        assertThat(statuses)
                .containsEntry(id, "APPROVED")
                .containsEntry(savings, "APPROVED")
                .containsEntry(rejected, "REJECTED")
                .containsEntry(cancelled, "CANCELLED");
        boolean external = false;
        for (JsonNode s : view.path("savings")) {
            external |=
                    "EXTERNAL".equals(s.path("type").asString())
                            && "80C".equals(s.path("sectionCode").asString());
        }
        assertThat(external).isTrue();
        // The register lists the entries of the year in the Maker's offices.
        JsonNode page =
                body(
                        mvc.perform(userGet(maker, "/v1/tax/savings?status=APPROVED&size=100"))
                                .andReturn());
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void BR_TAX_015_theTransportRebateMonthsAreApprovedOnceEach() throws Exception {
        // tax.kolkata@ (870004) belongs to KL001: the Delhi Maker reads it as missing.
        assertThat(
                        status(
                                mvc.perform(userGet(maker(), "/v1/tax/savings/members/870004"))
                                        .andReturn()))
                .isEqualTo(404);
        String eis = "870002"; // tax.new@, DH001
        String april = YEAR.substring(0, 4) + "04";
        String may = YEAR.substring(0, 4) + "05";
        Session maker = maker();
        MvcResult proposed =
                post(
                        maker,
                        "/v1/tax/savings/members/" + eis + "/transport-rebate",
                        Map.of(
                                "months",
                                Map.of(april, "1600", may, "1600"),
                                "reason",
                                "IT: rebate"));
        assertThat(status(proposed)).as(proposed.getResponse().getContentAsString()).isEqualTo(202);
        // One open proposal per member and year.
        assertThat(
                        error(
                                post(
                                        maker,
                                        "/v1/tax/savings/members/" + eis + "/transport-rebate",
                                        Map.of("months", Map.of(april, "1"), "reason", "IT"))))
                .isEqualTo("MST-409-PENDING");
        assertThat(year(maker, eis).path("pendingRebates")).hasSize(1);
        assertThat(
                        decide(
                                checker(),
                                body(proposed).path("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        int rebates = 0;
        for (JsonNode s : year(maker, eis).path("savings")) {
            rebates += "TRANSPORT_REBATE".equals(s.path("type").asString()) ? 1 : 0;
        }
        assertThat(rebates).isEqualTo(2);
        // An approved month is not proposed again; a month outside the year is refused.
        assertThat(
                        error(
                                post(
                                        maker,
                                        "/v1/tax/savings/members/" + eis + "/transport-rebate",
                                        Map.of("months", Map.of(april, "1600"), "reason", "IT"))))
                .isEqualTo("TAX-422-REBATE-MONTHS");
        assertThat(
                        error(
                                post(
                                        maker,
                                        "/v1/tax/savings/members/" + eis + "/transport-rebate",
                                        Map.of(
                                                "months",
                                                Map.of(YEAR.substring(0, 4) + "03", "1"),
                                                "reason",
                                                "IT"))))
                .isEqualTo("TAX-422-REBATE-MONTHS");
    }

    @Test
    void SRS_7_20_eachRefusalIsNamed() throws Exception {
        Session maker = maker();
        String eis = "870005"; // tax.history@, DH001
        assertThat(error(save(maker, eis, entry("EXTERNAL_SAVINGS", "899XX", null, "1"), true)))
                .isEqualTo("TAX-422-ENTRY-KIND");
        assertThat(error(save(maker, eis, entry("OTHER_INCOME", "80C", "Honorarium", "1"), true)))
                .isEqualTo("TAX-422-ENTRY-KIND");
        assertThat(error(save(maker, eis, entry("OTHER_INCOME", null, null, "1"), true)))
                .isEqualTo("TAX-422-ENTRY-KIND");
        // The request boundary: no document, no amount above zero, a negative TDS, an unknown
        // member.
        assertThat(status(save(maker, eis, entry("OTHER_INCOME", null, "Honorarium", "1"), false)))
                .isEqualTo(400);
        assertThat(status(save(maker, eis, entry("OTHER_INCOME", null, "Honorarium", "0"), true)))
                .isEqualTo(400);
        Map<String, Object> tds = entry("OTHER_INCOME", null, "Honorarium", "1");
        tds.put("tdsOtherSource", "-1");
        assertThat(status(save(maker, eis, tds, true))).isEqualTo(400);
        assertThat(
                        status(
                                save(
                                        maker,
                                        "999999",
                                        entry("OTHER_INCOME", null, "Honorarium", "1"),
                                        true)))
                .isEqualTo(404);
        assertThat(year(maker, eis).path("entries")).isEmpty();
    }

    @Test
    void SRS_22_21_whoMayDoWhat() throws Exception {
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        assertThat(status(mvc.perform(userGet(finance, "/v1/tax/savings")).andReturn()))
                .isEqualTo(200);
        assertThat(
                        status(
                                save(
                                        finance,
                                        "870003",
                                        entry("OTHER_INCOME", null, "Honorarium", "1"),
                                        true)))
                .isEqualTo(403);
        Session member = login("tax.old@fci-demo.test", null, null);
        assertThat(status(mvc.perform(userGet(member, "/v1/tax/savings")).andReturn()))
                .isEqualTo(403);
        assertThat(
                        status(
                                post(
                                        checker(),
                                        "/v1/tax/savings/members/870003/transport-rebate",
                                        Map.of("months", Map.of("202604", "1"), "reason", "IT"))))
                .isEqualTo(403);
    }
}
