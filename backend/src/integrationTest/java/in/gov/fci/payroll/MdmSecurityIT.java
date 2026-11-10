package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Hostile input against the Module 04 masters (OWASP A03 injection, A04 insecure design, A08
 * integrity): file uploads that lie about their type or name or are too large, CSV formula
 * injection in the label download, fields a client adds to force a state, and SQL / LIKE
 * metacharacters in searches. Each must be refused or neutralised, never stored as given, never a
 * 5xx.
 */
class MdmSecurityIT extends ApiTestSupport {

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session sysadmin() throws Exception {
        return login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
    }

    private Map<String, Object> circular(String number) {
        Map<String, Object> c = new HashMap<>();
        c.put("number", number);
        c.put("date", "2026-10-01");
        c.put("issuingAuthority", "HQ_PERSONNEL");
        c.put("subject", "IT security");
        c.put("category", "LABOUR_NORM");
        c.put("payrollTypes", List.of("STAFF"));
        c.put("appliesAllOffices", true);
        c.put("effectiveFrom", "2026-10-01");
        c.put("reason", "IT");
        return c;
    }

    private MvcResult record(Map<String, Object> c, byte[] file, String name, String type)
            throws Exception {
        return mvc.perform(
                        userRequest(
                                        maker(),
                                        multipart("/v1/masters/circulars")
                                                .file(
                                                        new MockMultipartFile(
                                                                "file", name, type, file))
                                                .file(
                                                        new MockMultipartFile(
                                                                "circular",
                                                                "",
                                                                MediaType.APPLICATION_JSON_VALUE,
                                                                json.writeValueAsBytes(c))))
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @Test
    void OWASP_A08_anExecutableNamedPdfIsRefusedAndAPathInTheNameIsNeverStored() throws Exception {
        byte[] exe = "MZ\u0090\u0000 not a pdf".getBytes(StandardCharsets.ISO_8859_1);
        MvcResult disguised =
                record(circular(unique("SEC/EXE/")), exe, "order.pdf", "application/pdf");
        assertThat(body(disguised).path("errorCode").asString()).isEqualTo("MST-422-CIRCULAR-DOC");

        byte[] pdf = "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);
        MvcResult traversal =
                record(
                        circular(unique("SEC/PATH/")),
                        pdf,
                        "..\\..\\..\\windows\\evil\".pdf",
                        "application/pdf");
        assertThat(traversal.getResponse().getStatus())
                .as(traversal.getResponse().getContentAsString())
                .isEqualTo(202);
        long id = body(traversal).get("id").asLong();
        String stored =
                jdbc.queryForObject(
                        "SELECT file_name FROM document_attachment WHERE attachment_id = (SELECT"
                            + " document_attachment_id FROM circular_order WHERE circular_order_id"
                            + " = ?)",
                        String.class,
                        id);
        assertThat(stored).doesNotContain("..", "\\", "/", "\"");
        MvcResult download = download(maker(), "/v1/masters/circulars/" + id + "/document");
        assertThat(download.getResponse().getHeader("Content-Disposition"))
                .startsWith("attachment");
        assertThat(download.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void OWASP_A04_aCopyLargerThanTheLimitIsRefusedBeforeAnythingIsKept() throws Exception {
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), 0, big, 0, 8);
        Arrays.fill(big, 8, big.length, (byte) ' ');
        String number = unique("SEC/BIG/");
        MvcResult r = record(circular(number), big, "big.pdf", "application/pdf");
        assertThat(r.getResponse().getStatus()).isIn(413, 422);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM circular_order WHERE circular_number = ?",
                                Integer.class,
                                number))
                .isZero();
    }

    @Test
    void OWASP_A03_aLabelThatLooksLikeASpreadsheetFormulaIsDownloadedAsText() throws Exception {
        Session admin = sysadmin();
        String key = unique("LBL.CSV.");
        Map<String, Object> b = new HashMap<>();
        b.putAll(
                Map.of(
                        "key", key,
                        "type", "UI_LABEL",
                        "language", "HI",
                        "scope", "GLOBAL",
                        "text", "=HYPERLINK(\"http://example.invalid\",\"x\")",
                        "effectiveFrom", "2026-04-01",
                        "localisable", false,
                        "reason", "IT"));
        MvcResult r = mvc.perform(userPost(admin, "/v1/masters/labels", b)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        String csv = csv(admin, "/v1/masters/labels/export?language=HI&scope=GLOBAL");
        assertThat(csv).contains("\"'=HYPERLINK(").doesNotContain(",\"=HYPERLINK(");
    }

    @Test
    void OWASP_A08_fieldsAClientAddsCannotForceAStateOrAnApproval() throws Exception {
        Session admin = sysadmin();
        String key = unique("LBL.MASS.");
        Map<String, Object> b = new HashMap<>();
        b.putAll(
                Map.of(
                        "key", key,
                        "type", "UI_LABEL",
                        "language", "EN",
                        "scope", "GLOBAL",
                        "text", "x",
                        "effectiveFrom", "2026-04-01",
                        "localisable", false,
                        "reason", "IT"));
        b.put("labelStatus", "ACTIVE");
        b.put("status", "ACTIVE");
        b.put("approvalId", 1);
        b.put("changedBy", "someone.else");
        MvcResult r = mvc.perform(userPost(admin, "/v1/masters/labels", b)).andReturn();
        if (r.getResponse().getStatus() == 202) {
            JsonNode v = body(r);
            assertThat(v.get("status").asString()).isEqualTo("PENDING_APPROVAL");
            assertThat(v.get("approvalId").asLong()).isNotEqualTo(1L);
            assertThat(v.get("changedBy").asString()).isEqualTo("sysadmin@fci-demo.test");
        } else {
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
        }
    }

    @Test
    void OWASP_A03_sqlAndLikeMetacharactersInASearchAreJustText() throws Exception {
        Session maker = maker();
        for (String text :
                List.of("' OR '1'='1", "%", "_", "\\", "x'); DROP TABLE circular_order; --")) {
            MvcResult circulars =
                    mvc.perform(userGet(maker, "/v1/masters/circulars").param("text", text))
                            .andReturn();
            assertThat(circulars.getResponse().getStatus()).as(text).isIn(200, 400);
            if (circulars.getResponse().getStatus() == 200) {
                for (JsonNode c : body(circulars).get("items")) {
                    String hay =
                            (c.get("number").asString() + " " + c.get("subject").asString())
                                    .toLowerCase();
                    assertThat(hay).as(text).contains(text.toLowerCase());
                }
            }
            MvcResult labels =
                    mvc.perform(userGet(sysadmin(), "/v1/masters/labels").param("text", text))
                            .andReturn();
            assertThat(labels.getResponse().getStatus()).as(text).isIn(200, 400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM circular_order", Integer.class))
                .isNotNull();
    }
}
