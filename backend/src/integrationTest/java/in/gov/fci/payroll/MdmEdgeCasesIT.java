package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * Edge cases of the Module 04 masters (docs/plan/04-masters-mdm.md): every refusal a maker can meet
 * (dates, scopes, lists, files, text, state), the checker's rejection leaving nothing in force, a
 * revised label superseding its predecessor, and searches that treat wildcards as text.
 */
class MdmEdgeCasesIT extends ApiTestSupport {

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);

    private Session ruleAdmin() throws Exception {
        return login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
    }

    private Session hqChecker() throws Exception {
        return login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
    }

    private Session sysadmin() throws Exception {
        return login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
    }

    private Session delhiMaker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session delhiChecker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private MvcResult get(Session s, String path) throws Exception {
        return mvc.perform(userGet(s, path)).andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private long pendingApproval(String entity, String key) {
        return jdbc.queryForObject(
                "SELECT approval_id FROM approval_queue WHERE entity_name = ? AND entity_key = ?"
                        + " AND status = 'PENDING_APPROVAL'",
                Long.class,
                entity,
                key);
    }

    // ---------------------------------------------------------------- M-25

    private Map<String, Object> circular(String number) {
        Map<String, Object> c = new HashMap<>();
        c.put("number", number);
        c.put("date", "2026-10-01");
        c.put("issuingAuthority", "HQ_PERSONNEL");
        c.put("subject", "IT edge case");
        c.put("category", "LABOUR_NORM");
        c.put("payrollTypes", List.of("STAFF"));
        c.put("appliesAllOffices", true);
        c.put("effectiveFrom", "2026-10-01");
        c.put("reason", "IT");
        return c;
    }

    private MvcResult record(Session maker, Map<String, Object> c, byte[] file, String name)
            throws Exception {
        return mvc.perform(
                        userRequest(
                                        maker,
                                        multipart("/v1/masters/circulars")
                                                .file(
                                                        new MockMultipartFile(
                                                                "file",
                                                                name,
                                                                "application/pdf",
                                                                file))
                                                .file(
                                                        new MockMultipartFile(
                                                                "circular",
                                                                "",
                                                                MediaType.APPLICATION_JSON_VALUE,
                                                                json.writeValueAsBytes(c))))
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    @Test
    void SRS_G_38_aCircularWithBadDatesTypesOfficesListsOrTextIsRefused() throws Exception {
        Session maker = delhiMaker();
        List<Map<String, Object>> invalid = new ArrayList<>();
        Map<String, Object> dates = circular(unique("E/D/"));
        dates.put("effectiveTo", "2026-09-30");
        invalid.add(dates);
        Map<String, Object> noOffices = circular(unique("E/O/"));
        noOffices.put("appliesAllOffices", false);
        invalid.add(noOffices);
        Map<String, Object> bothOffices = circular(unique("E/B/"));
        bothOffices.put("officeIds", List.of(locationId("DH001")));
        invalid.add(bothOffices);
        Map<String, Object> unknownOffice = circular(unique("E/U/"));
        unknownOffice.put("appliesAllOffices", false);
        unknownOffice.put("officeIds", List.of(999_999_999L));
        invalid.add(unknownOffice);
        for (Map<String, Object> c : invalid) {
            assertThat(code(record(maker, c, PDF, "o.pdf")))
                    .as(c.toString())
                    .isEqualTo("MST-422-CIRCULAR");
        }
        Map<String, Object> authority = circular(unique("E/A/"));
        authority.put("issuingAuthority", "NOT_LISTED");
        assertThat(code(record(maker, authority, PDF, "o.pdf"))).isEqualTo("MST-422-VALUE");
        Map<String, Object> hindi = circular(unique("E/H/"));
        hindi.put("subject", "महंगाई भत्ता");
        // R-ASCII: refused at the boundary, before the master's own text check.
        assertThat(code(record(maker, hindi, PDF, "o.pdf"))).isEqualTo("COM-400-VALIDATION");
        Map<String, Object> type = circular(unique("E/T/"));
        type.put("payrollTypes", List.of("CONTRACT"));
        assertThat(status(record(maker, type, PDF, "o.pdf"))).isEqualTo(400);
        Map<String, Object> superseding = circular(unique("E/S/"));
        superseding.put("supersedesId", 999_999_999L);
        assertThat(code(record(maker, superseding, PDF, "o.pdf")))
                .isEqualTo("MST-409-CIRCULAR-STATE");
        // The copy is checked by its content, not its name or declared type.
        assertThat(code(record(maker, circular(unique("E/F/")), new byte[0], "o.pdf")))
                .isEqualTo("MST-422-CIRCULAR-DOC");
        // Nothing of the refused proposals was kept.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM circular_order WHERE circular_number LIKE"
                                        + " 'E/%'",
                                Integer.class))
                .isZero();
        // Only the payroll maker records; the checker may not.
        assertThat(status(record(delhiChecker(), circular(unique("E/C/")), PDF, "o.pdf")))
                .isEqualTo(403);
    }

    @Test
    void SRS_G_38_aRejectedCircularNeverBecomesActiveAndARejectedWithdrawalLeavesItActive()
            throws Exception {
        Session maker = delhiMaker();
        MvcResult r = record(maker, circular(unique("IT/REJ/")), PDF, "o.pdf");
        long id = body(r).get("id").asLong();
        assertThat(
                        decide(
                                delhiChecker(),
                                body(r).get("approvalId").asLong(),
                                "REJECT",
                                "Not issued",
                                null))
                .isEqualTo(200);
        assertThat(body(get(maker, "/v1/masters/circulars/" + id)).get("status").asString())
                .isEqualTo("REJECTED");
        // A rejected circular can be neither withdrawn nor linked.
        assertThat(
                        code(
                                post(
                                        maker,
                                        "/v1/masters/circulars/" + id + "/withdraw",
                                        Map.of("reason", "x"))))
                .isEqualTo("MST-409-CIRCULAR-STATE");

        MvcResult active = record(maker, circular(unique("IT/ACT/")), PDF, "o.pdf");
        long activeId = body(active).get("id").asLong();
        decide(delhiChecker(), body(active).get("approvalId").asLong(), "APPROVE", null, null);
        post(maker, "/v1/masters/circulars/" + activeId + "/withdraw", Map.of("reason", "Mistake"));
        long withdrawal = pendingApproval("circular_withdrawal", String.valueOf(activeId));
        // One pending withdrawal at a time.
        assertThat(
                        status(
                                post(
                                        maker,
                                        "/v1/masters/circulars/" + activeId + "/withdraw",
                                        Map.of("reason", "again"))))
                .isEqualTo(409);
        assertThat(decide(delhiChecker(), withdrawal, "REJECT", "Order still in force", null))
                .isEqualTo(200);
        assertThat(body(get(maker, "/v1/masters/circulars/" + activeId)).get("status").asString())
                .isEqualTo("ACTIVE");
        // The checker withdraws nothing; unknown circulars answer 404.
        assertThat(
                        status(
                                post(
                                        delhiChecker(),
                                        "/v1/masters/circulars/" + activeId + "/withdraw",
                                        Map.of("reason", "x"))))
                .isEqualTo(403);
        for (String path :
                List.of(
                        "/v1/masters/circulars/999999999",
                        "/v1/masters/circulars/999999999/where-used",
                        "/v1/masters/circulars/999999999/document")) {
            assertThat(status(get(maker, path))).as(path).isEqualTo(404);
        }
    }

    @Test
    void SRS_G_38_aCircularForNamedOfficesIsCurrentOnlyThereAndSearchTreatsWildcardsAsText()
            throws Exception {
        Session maker = delhiMaker();
        assertThat(status(get(maker, "/v1/masters/circulars/offices"))).isEqualTo(200);
        assertThat(status(get(delhiChecker(), "/v1/masters/circulars/offices"))).isEqualTo(403);
        Map<String, Object> scoped = circular(unique("IT/RO%_"));
        scoped.put("appliesAllOffices", false);
        // The Delhi Region and the offices under it.
        scoped.put("officeIds", List.of(locationId("RO-DL")));
        MvcResult r = record(maker, scoped, PDF, "o.pdf");
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(202);
        String number = body(r).get("number").asString();
        decide(delhiChecker(), body(r).get("approvalId").asLong(), "APPROVE", null, null);
        assertThat(texts(body(get(maker, "/v1/masters/circulars/current")), "number"))
                .contains(number);
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(texts(body(get(kolkata, "/v1/masters/circulars/current")), "number"))
                .doesNotContain(number);
        // "%" and "_" are matched literally: "it/ro%_" finds this circular only.
        JsonNode found =
                body(
                        get(
                                maker,
                                "/v1/masters/circulars?text="
                                        + java.net.URLEncoder.encode(
                                                "ro%_", StandardCharsets.UTF_8)));
        assertThat(texts(found, "number")).allMatch(n -> n.contains("RO%_"));
        JsonNode office =
                body(get(maker, "/v1/masters/circulars/" + body(r).get("id").asLong()))
                        .get("offices");
        assertThat(office.get(0).get("office").asString()).contains("RO-DL");
        assertThat(status(get(maker, "/v1/masters/circulars?status=UNKNOWN"))).isEqualTo(400);
    }

    // ---------------------------------------------------------------- M-26

    private static Map<String, Object> label(
            String key, String type, String language, String scope, String text, String from) {
        Map<String, Object> b = new HashMap<>();
        b.put("key", key);
        b.put("type", type);
        b.put("language", language);
        b.put("scope", scope);
        b.put("text", text);
        b.put("effectiveFrom", from);
        b.put("localisable", false);
        b.put("reason", "IT");
        if ("MASTER_VALUE".equals(type)) {
            b.put("masterEntity", "ORG_DESIGNATION");
            b.put("masterCode", "SARDAR");
        }
        return b;
    }

    @Test
    void SRS_G_39_aLabelRefusesAnUnknownLanguageAndALocalNameFromHqOrForAFixedValue()
            throws Exception {
        Session admin = sysadmin();
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/masters/labels",
                                        label(
                                                unique("L.X."),
                                                "UI_LABEL",
                                                "FR",
                                                "GLOBAL",
                                                "x",
                                                "2026-04-01"))))
                .isEqualTo("MST-422-VALUE");
        // Headquarters has no local name to give.
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/masters/labels",
                                        label(
                                                "DESIG.SARDAR",
                                                "MASTER_VALUE",
                                                "EN",
                                                "OFFICE",
                                                "Mukhiya",
                                                "2099-01-01"))))
                .isEqualTo("MST-422-LABEL-SCOPE");
        // A master value whose Global label is not localisable takes no local name.
        String fixed = unique("DESIG.FIXED");
        Map<String, Object> global =
                label(fixed, "MASTER_VALUE", "EN", "GLOBAL", "Fixed", "2026-04-01");
        long approval = body(post(admin, "/v1/masters/labels", global)).get("approvalId").asLong();
        decide(hqChecker(), approval, "APPROVE", null, null);
        assertThat(
                        code(
                                post(
                                        delhiMaker(),
                                        "/v1/masters/labels",
                                        label(
                                                fixed,
                                                "MASTER_VALUE",
                                                "EN",
                                                "OFFICE",
                                                "Local",
                                                "2099-01-01"))))
                .isEqualTo("MST-422-LABEL-SCOPE");
        // A Region local name belongs to the proposer's region.
        MvcResult region =
                post(
                        delhiMaker(),
                        "/v1/masters/labels",
                        label(
                                "DESIG.SARDAR",
                                "MASTER_VALUE",
                                "HI",
                                "REGION",
                                "मुखिया",
                                "2099-02-01"));
        assertThat(status(region)).as(region.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(body(region).get("scopeLocationId").asLong()).isEqualTo(locationId("RO-DL"));
        // A Global label cannot be withdrawn; nor an unknown or pending one.
        long globalId =
                jdbc.queryForObject(
                        "SELECT label_id FROM label_master WHERE label_key = ? AND scope_level ="
                                + " 'GLOBAL'",
                        Long.class,
                        fixed);
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/masters/labels/" + globalId + "/withdraw",
                                        Map.of("reason", "x"))))
                .isEqualTo("MST-422-LABEL-SCOPE");
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/masters/labels/999999999/withdraw",
                                        Map.of("reason", "x"))))
                .isEqualTo(404);
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/masters/labels/"
                                                + body(region).get("id").asLong()
                                                + "/withdraw",
                                        Map.of("reason", "x"))))
                .isEqualTo(404);
    }

    @Test
    void SRS_G_39_aRevisedLabelSupersedesTheOneInForceAndARejectedOneNeverShows() throws Exception {
        Session admin = sysadmin();
        String key = unique("LBL.REV.");
        long first =
                body(post(
                                admin,
                                "/v1/masters/labels",
                                label(key, "UI_LABEL", "EN", "GLOBAL", "First", "2026-04-01")))
                        .get("approvalId")
                        .asLong();
        decide(hqChecker(), first, "APPROVE", null, null);
        long second =
                body(post(
                                admin,
                                "/v1/masters/labels",
                                label(key, "UI_LABEL", "EN", "GLOBAL", "Second", "2026-06-01")))
                        .get("approvalId")
                        .asLong();
        decide(hqChecker(), second, "APPROVE", null, null);
        JsonNode versions = all(admin, "/v1/masters/labels?text=" + key.toLowerCase());
        Map<String, String> statusByText = new HashMap<>();
        Map<String, String> toByText = new HashMap<>();
        for (JsonNode v : versions) {
            statusByText.put(v.get("text").asString(), v.get("status").asString());
            toByText.put(v.get("text").asString(), v.path("effectiveTo").asString());
        }
        assertThat(statusByText)
                .containsEntry("First", "SUPERSEDED")
                .containsEntry("Second", "ACTIVE");
        assertThat(toByText.get("First")).isEqualTo("2026-05-31");
        assertThat(
                        body(get(
                                        delhiMaker(),
                                        "/v1/masters/labels/resolve?key=" + key + "&language=EN"))
                                .get("text")
                                .asString())
                .isEqualTo("Second");

        long rejected =
                body(post(
                                admin,
                                "/v1/masters/labels",
                                label(key, "UI_LABEL", "HI", "GLOBAL", "अस्वीकृत", "2026-04-01")))
                        .get("approvalId")
                        .asLong();
        decide(hqChecker(), rejected, "REJECT", "Wrong word", null);
        // Hindi falls back to English Global: the rejected text never shows.
        assertThat(
                        body(get(
                                        delhiMaker(),
                                        "/v1/masters/labels/resolve?key=" + key + "&language=HI"))
                                .get("text")
                                .asString())
                .isEqualTo("Second");
        // The same slot and date as a pending or active version is taken.
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/masters/labels",
                                        label(
                                                key,
                                                "UI_LABEL",
                                                "EN",
                                                "GLOBAL",
                                                "Again",
                                                "2026-06-01"))))
                .isEqualTo("MST-409-LABEL");
    }

    private MvcResult upload(Session s, String csv, String reason) throws Exception {
        var request =
                multipart("/v1/masters/labels/upload")
                        .file(
                                new MockMultipartFile(
                                        "file",
                                        "l.csv",
                                        "text/csv",
                                        csv.getBytes(StandardCharsets.UTF_8)));
        if (reason != null) {
            request.param("reason", reason);
        }
        return mvc.perform(
                        userRequest(s, request)
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    @Test
    void SRS_G_39_aBulkUploadRefusesAnEmptyFileDuplicateRowsAndAMissingReason() throws Exception {
        Session admin = sysadmin();
        String head = "label_key,label_type,language,label_text,effective_from\r\n";
        assertThat(status(upload(admin, head, "IT"))).isEqualTo(400);
        String key = unique("LBL.DUP.");
        String dup =
                head
                        + key
                        + ",UI_LABEL,HI,a,2026-04-01\r\n"
                        + key
                        + ",UI_LABEL,HI,b,2026-04-01\r\n";
        assertThat(code(upload(admin, dup, "IT"))).isEqualTo("MST-409-LABEL");
        assertThat(
                        status(
                                upload(
                                        admin,
                                        head + unique("LBL.R.") + ",UI_LABEL,HI,a,2026-04-01\r\n",
                                        null)))
                .isEqualTo(400);
        StringBuilder big = new StringBuilder(head);
        for (int i = 0; i < 501; i++) {
            big.append("LBL.BIG.").append(i).append(",UI_LABEL,HI,x,2026-04-01\r\n");
        }
        assertThat(status(upload(admin, big.toString(), "IT"))).isEqualTo(400);
        // A unit maker uploads only its own local names: a Global row is refused by its route.
        assertThat(
                        status(
                                upload(
                                        delhiMaker(),
                                        head + unique("LBL.U.") + ",UI_LABEL,HI,x,2027-01-01\r\n",
                                        "IT")))
                .isEqualTo(403);
    }

    // ---------------------------------------------------------------- M-27

    private static Map<String, Object> operation(String name, long circular) {
        Map<String, Object> b = new HashMap<>();
        b.put("effectiveFrom", "2091-01-01");
        b.put("name", name);
        b.put("departmental", true);
        b.put("dps", false);
        b.put("nwnp", false);
        b.put("bagBased", false);
        b.put("rateNormLink", "TIME_BASED");
        b.put("dusting", false);
        b.put("presentButNoWork", false);
        b.put("circularId", circular);
        b.put("reason", "IT");
        return b;
    }

    private long activeCircular() throws Exception {
        MvcResult r = record(delhiMaker(), circular(unique("IT/OP/")), PDF, "o.pdf");
        decide(delhiChecker(), body(r).get("approvalId").asLong(), "APPROVE", null, null);
        return body(r).get("id").asLong();
    }

    @Test
    void SRS_G_41_dustingNamesAnActivePayCodeAndEndDatingNeedsAnOpenVersionAndALaterDay()
            throws Exception {
        long circular = activeCircular();
        Session admin = ruleAdmin();
        String code = unique("D").substring(0, 8);
        Map<String, Object> dusting = operation("Dusting", circular);
        dusting.put("dusting", true);
        dusting.put("rateNormLink", "ALLOWANCE");
        dusting.put("allowancePayCode", 999_999);
        assertThat(code(post(admin, "/v1/masters/operations/" + code + "/versions", dusting)))
                .isEqualTo("MST-422-COMPONENT");
        dusting.put("allowancePayCode", 101);
        MvcResult ok = post(admin, "/v1/masters/operations/" + code + "/versions", dusting);
        assertThat(status(ok)).as(ok.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(body(ok).get("allowancePayCode").asInt()).isEqualTo(101);
        // Not in force yet: nothing to end-date.
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/masters/operations/" + code + "/end-date",
                                        Map.of("lastDay", "2095-01-01", "reason", "x"))))
                .isEqualTo(404);
        decide(hqChecker(), body(ok).get("approvalId").asLong(), "APPROVE", null, null);
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/masters/operations/" + code + "/end-date",
                                        Map.of("lastDay", "2090-12-31", "reason", "x"))))
                .isEqualTo("MST-422-DATE");
        // The code is A10 and printable; the name printable ASCII.
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/masters/operations/bad code/versions",
                                        operation("x", circular))))
                .isEqualTo(400);
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/masters/operations/ABCDEFGHIJK/versions",
                                        operation("x", circular))))
                .isEqualTo(400);
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/masters/operations/"
                                                + unique("N").substring(0, 8)
                                                + "/versions",
                                        operation("डस्टिंग", circular))))
                .isEqualTo("COM-400-VALIDATION");
        // A rejected version leaves the operation as it was.
        Map<String, Object> second = operation("Dusting", circular);
        second.putAll(
                Map.of(
                        "dusting",
                        true,
                        "rateNormLink",
                        "ALLOWANCE",
                        "allowancePayCode",
                        101,
                        "effectiveFrom",
                        "2092-01-01"));
        long rejected =
                body(post(admin, "/v1/masters/operations/" + code + "/versions", second))
                        .get("approvalId")
                        .asLong();
        decide(hqChecker(), rejected, "REJECT", "Not needed", null);
        JsonNode history = all(admin, "/v1/masters/operations/" + code + "/versions");
        assertThat(history.get(0).get("status").asString()).isEqualTo("REJECTED");
        assertThat(history.get(1).get("effectiveTo").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- M-14 / M-15

    @Test
    void SRS_16_13_aRejectedFloorLeavesNothingInForceAndExclusionsRefuseANullCode()
            throws Exception {
        Session admin = ruleAdmin();
        Map<String, Object> floor = new HashMap<>();
        floor.put("effectiveFrom", "2099-01-01");
        floor.put("floorBasis", "NONE");
        floor.put("confirmed", true);
        floor.put("reason", "IT");
        long approval =
                body(post(admin, "/v1/masters/net-pay-floors/NWNP_LABOUR/versions", floor))
                        .get("approvalId")
                        .asLong();
        decide(hqChecker(), approval, "REJECT", "Not this floor", null);
        JsonNode history = all(admin, "/v1/masters/net-pay-floors/NWNP_LABOUR/versions");
        assertThat(history.get(0).get("status").asString()).isEqualTo("REJECTED");
        // The rejected version stays on record and keeps its date (uk_net_pay_floor_rule): a new
        // proposal takes another date.
        assertThat(code(post(admin, "/v1/masters/net-pay-floors/NWNP_LABOUR/versions", floor)))
                .isEqualTo("MST-409-DATE");
        floor.put("effectiveFrom", "2099-01-02");
        assertThat(status(post(admin, "/v1/masters/net-pay-floors/NWNP_LABOUR/versions", floor)))
                .isEqualTo(202);
        List<Object> withNull = new ArrayList<>();
        withNull.add(101);
        withNull.add(null);
        Map<String, Object> exclusions = new HashMap<>();
        exclusions.put("payCodes", withNull);
        exclusions.put("reason", "IT");
        assertThat(status(post(admin, "/v1/masters/supplementary-exclusions", exclusions)))
                .isEqualTo(400);
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/masters/supplementary-exclusions",
                                        Map.of("payCodes", List.of(101), "reason", " "))))
                .isEqualTo(400);
    }
}
