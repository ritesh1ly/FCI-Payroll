package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
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
 * The Module 04 masters under maker-checker (docs/plan/04-masters-mdm.md): M-14 net-pay floors and
 * supplementary exclusion codes, M-15 ceiling norms, M-25 circulars, M-26 labels, M-27 operations.
 * Each is proposed by its maker, in force only once a different checker approves it, effective
 * dated without overlap and never deleted. Dates are far in the future so nothing seeded is
 * touched; codes and numbers are unique per run.
 */
@org.junit.jupiter.api.parallel.ResourceLock(ApiTestSupport.AGM_NORTH)
class MdmMastersIT extends ApiTestSupport {

    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<< >>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

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

    private JsonNode get(Session s, String path) throws Exception {
        MvcResult r = mvc.perform(userGet(s, path)).andReturn();
        assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
        return pagedOrWhole(s, path, body(r));
    }

    private static int code(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String errorCode(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private long pendingApproval(String entity, String key) {
        return jdbc.queryForObject(
                "SELECT approval_id FROM approval_queue WHERE entity_name = ? AND entity_key = ?"
                        + " AND status = 'PENDING_APPROVAL'",
                Long.class,
                entity,
                key);
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // ---------------------------------------------------------------- M-14

    private static Map<String, Object> floor(String from, String basis, Object value) {
        Map<String, Object> b = new HashMap<>();
        b.put("effectiveFrom", from);
        b.put("floorBasis", basis);
        b.put("floorValue", value);
        b.put("confirmed", false);
        b.put("reason", "IT: floor from the current system");
        return b;
    }

    @Test
    void SRS_16_13_aFloorIsInForceOnlyOnceApprovedAndAnOverlappingVersionIsRefused()
            throws Exception {
        Session admin = ruleAdmin();
        JsonNode rows = get(admin, "/v1/masters/net-pay-floors");
        assertThat(texts(rows, "payrollType"))
                .containsExactly("STAFF", "DEPT_LABOUR", "DPS_LABOUR", "NWNP_LABOUR");

        MvcResult first =
                post(
                        admin,
                        "/v1/masters/net-pay-floors/DPS_LABOUR/versions",
                        floor("2091-01-01", "ABSOLUTE_AMOUNT", "1500.00"));
        assertThat(code(first)).isEqualTo(202);
        long v1 = body(first).get("approvalId").asLong();
        assertThat(body(first).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        // The proposer never approves the own proposal; a Payroll Checker at HQ does.
        assertThat(decide(admin, v1, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(hqChecker(), v1, "APPROVE", null, null)).isEqualTo(200);

        MvcResult overlap =
                post(
                        admin,
                        "/v1/masters/net-pay-floors/DPS_LABOUR/versions",
                        floor("2090-06-01", "ABSOLUTE_AMOUNT", "1400"));
        assertThat(code(overlap)).isEqualTo(422);
        assertThat(errorCode(overlap)).isEqualTo("MST-422-DATE");

        long v2 =
                body(post(
                                admin,
                                "/v1/masters/net-pay-floors/DPS_LABOUR/versions",
                                floor("2092-04-01", "PERCENT_OF_GROSS", "25")))
                        .get("approvalId")
                        .asLong();
        assertThat(decide(hqChecker(), v2, "APPROVE", null, null)).isEqualTo(200);
        JsonNode history = get(admin, "/v1/masters/net-pay-floors/DPS_LABOUR/versions");
        assertThat(find(history, "effectiveFrom", "2091-01-01").get("effectiveTo").asString())
                .isEqualTo("2092-03-31");
        assertThat(find(history, "effectiveFrom", "2092-04-01").get("status").asString())
                .isEqualTo("APPROVED");
        // Audited in the approval transaction: the end of the earlier version and the new values.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'net_pay_floor_rule' AND entity_key = 'DPS_LABOUR'"
                                        + " AND approval_id = ?",
                                Integer.class,
                                v2))
                .isGreaterThanOrEqualTo(2);
        // SRS-20-29: the History button lists the approved versions with their own numbers.
        JsonNode record = get(admin, "/v1/audit/history?entity=net_pay_floor_rule&key=DPS_LABOUR");
        assertThat(record.get("screen").asString()).isEqualTo("M-14");
        assertThat(record.get("versioned").asBoolean()).isTrue();
        JsonNode earlier = find(record.get("versions"), "effectiveFrom", "2091-01-01");
        JsonNode later = find(record.get("versions"), "effectiveFrom", "2092-04-01");
        assertThat(earlier.get("effectiveTo").asString()).isEqualTo("2092-03-31");
        assertThat(later.get("versionNo").asInt()).isGreaterThan(earlier.get("versionNo").asInt());
        assertThat(later.get("approvalId").asLong()).isEqualTo(v2);
    }

    @Test
    void SRS_16_13_aFloorOutsideItsBoundsOrFromTheWrongRoleIsRefused() throws Exception {
        Session admin = ruleAdmin();
        MvcResult percent =
                post(
                        admin,
                        "/v1/masters/net-pay-floors/STAFF/versions",
                        floor("2093-01-01", "PERCENT_OF_GROSS", "101"));
        assertThat(code(percent)).isEqualTo(422);
        assertThat(errorCode(percent)).isEqualTo("MST-422-FLOOR");
        MvcResult negative =
                post(
                        admin,
                        "/v1/masters/net-pay-floors/STAFF/versions",
                        floor("2093-01-01", "ABSOLUTE_AMOUNT", "-1"));
        assertThat(errorCode(negative)).isEqualTo("MST-422-FLOOR");
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/masters/net-pay-floors/CONTRACT/versions",
                                        floor("2093-01-01", "NONE", null))))
                .isEqualTo(400);
        // A unit payroll maker has no M-14 rights at all.
        assertThat(
                        code(
                                post(
                                        delhiMaker(),
                                        "/v1/masters/net-pay-floors/STAFF/versions",
                                        floor("2093-01-01", "NONE", null))))
                .isEqualTo(403);
    }

    @Test
    void SRS_16_13_exclusionCodesMustBeCataloguedAndChangeOnlyOnApproval() throws Exception {
        Session admin = ruleAdmin();
        MvcResult unknown =
                post(
                        admin,
                        "/v1/masters/supplementary-exclusions",
                        Map.of("payCodes", List.of(999_999), "reason", "IT: unknown code"));
        assertThat(code(unknown)).isEqualTo(422);
        assertThat(errorCode(unknown)).isEqualTo("MST-422-COMPONENT");

        JsonNode components = get(admin, "/v1/masters/supplementary-exclusions").get("components");
        assertThat(components.size()).isPositive();
        JsonNode target = components.get(0);
        int payCode = target.get("payCode").asInt();
        boolean before = target.get("excluded").asBoolean();
        List<Integer> wanted = new java.util.ArrayList<>();
        for (JsonNode c : components) {
            boolean excluded = c.get("excluded").asBoolean();
            if (c.get("payCode").asInt() == payCode ? !excluded : excluded) {
                wanted.add(c.get("payCode").asInt());
            }
        }
        MvcResult proposed =
                post(
                        admin,
                        "/v1/masters/supplementary-exclusions",
                        Map.of("payCodes", wanted, "reason", "IT: flip one code"));
        assertThat(code(proposed)).isEqualTo(202);
        long approval = body(proposed).get("approvalId").asLong();
        // Nothing changes before approval.
        assertThat(flag(payCode)).isEqualTo(before ? "Y" : "N");
        assertThat(decide(hqChecker(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(flag(payCode)).isEqualTo(before ? "N" : "Y");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'pay_component' AND entity_key = ? AND approval_id = ?",
                                Integer.class,
                                String.valueOf(payCode),
                                approval))
                .isEqualTo(1);
        // Flip it back so other tests see the seeded state.
        long back =
                body(post(
                                admin,
                                "/v1/masters/supplementary-exclusions",
                                Map.of(
                                        "payCodes",
                                        components
                                                .valueStream()
                                                .filter(c -> c.get("excluded").asBoolean())
                                                .map(c -> c.get("payCode").asInt())
                                                .toList(),
                                        "reason",
                                        "IT: restore")))
                        .get("approvalId")
                        .asLong();
        assertThat(decide(hqChecker(), back, "APPROVE", null, null)).isEqualTo(200);
    }

    private String flag(int payCode) {
        return jdbc.queryForObject(
                "SELECT supp_excluded_flag FROM pay_component WHERE legacy_pay_code = ?",
                String.class,
                payCode);
    }

    // ---------------------------------------------------------------- M-15

    /** An Active M-25 circular of the seed (SRS-G-38, US-GAP-211: a norm revision links one). */
    private Long seededCircular() {
        return jdbc.queryForObject(
                "SELECT MIN(circular_order_id) FROM circular_order WHERE circular_status ="
                        + " 'ACTIVE'",
                Long.class);
    }

    private Map<String, Object> norm(String from, Object value, String eligibility) {
        Map<String, Object> b = new HashMap<>();
        b.put("circularOrderId", seededCircular());
        b.put("effectiveFrom", from);
        b.put("ceilingValue", value);
        b.put("periodicity", "MONTHLY");
        b.put("eligibilityCategory", eligibility);
        b.put("confirmed", false);
        b.put("reason", "IT: ceiling from the current system");
        return b;
    }

    @Test
    void SRS_16_14_aCeilingNormIsPositiveFromTheListsAndInForceOnlyOnceApproved() throws Exception {
        Session admin = ruleAdmin();
        assertThat(texts(get(admin, "/v1/masters/ceiling-norms"), "claimType"))
                .contains("LABOUR_TA", "TRANSFER_TA");
        MvcResult zero =
                post(
                        admin,
                        "/v1/masters/ceiling-norms/LABOUR_TA/versions",
                        norm("2091-01-01", 0, "DEMO_ALL"));
        assertThat(errorCode(zero)).isEqualTo("MST-422-CEILING");
        MvcResult unknownCategory =
                post(
                        admin,
                        "/v1/masters/ceiling-norms/LABOUR_TA/versions",
                        norm("2091-01-01", 500, "NOT_A_CATEGORY"));
        assertThat(errorCode(unknownCategory)).isEqualTo("MST-422-VALUE");
        MvcResult unknownClaim =
                post(
                        admin,
                        "/v1/masters/ceiling-norms/NOT_A_CLAIM/versions",
                        norm("2091-01-01", 500, "DEMO_ALL"));
        assertThat(errorCode(unknownClaim)).isEqualTo("MST-422-VALUE");
        // SRS-G-38 (US-GAP-211): a norm revision without its Active circular is not submitted.
        Map<String, Object> noCircular = norm("2091-01-01", 500, "DEMO_ALL");
        noCircular.remove("circularOrderId");
        assertThat(
                        errorCode(
                                post(
                                        admin,
                                        "/v1/masters/ceiling-norms/LABOUR_TA/versions",
                                        noCircular)))
                .isEqualTo("MST-422-CIRCULAR-REQUIRED");

        // Wave D review 6 (SRS-G-38): a rejected norm keeps no Active "where used" link.
        MvcResult refused =
                post(
                        admin,
                        "/v1/masters/ceiling-norms/LABOUR_TA/versions",
                        norm("2091-01-02", "740", "DEMO_ALL"));
        assertThat(code(refused)).isEqualTo(202);
        long refusedId = body(refused).get("id").asLong();
        assertThat(
                        decide(
                                hqChecker(),
                                body(refused).get("approvalId").asLong(),
                                "REJECT",
                                "IT: wrong ceiling",
                                null))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForList(
                                "SELECT link_status FROM circular_order_link WHERE linked_entity ="
                                        + " 'allowance_ceiling_norm' AND linked_key = ?",
                                String.class,
                                String.valueOf(refusedId)))
                .containsExactly("CANCELLED");
        admin = ruleAdmin();
        MvcResult ok =
                post(
                        admin,
                        "/v1/masters/ceiling-norms/LABOUR_TA/versions",
                        norm("2091-01-01", "750.50", "DEMO_ALL"));
        assertThat(code(ok)).isEqualTo(202);
        assertThat(decide(hqChecker(), body(ok).get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode v = get(admin, "/v1/masters/ceiling-norms/LABOUR_TA/versions").get(0);
        assertThat(v.get("status").asString()).isEqualTo("APPROVED");
        assertThat(v.get("memberScope").asString()).isEqualTo("BOTH");
        assertThat(v.get("confirmed").asBoolean()).isFalse();
        assertThat(v.get("circularOrderId").asLong()).isEqualTo(seededCircular());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM circular_order_link WHERE linked_entity ="
                                        + " 'allowance_ceiling_norm' AND linked_key = ? AND"
                                        + " link_type = 'MASTER_ENTRY' AND screen_code = 'M-15'",
                                Integer.class,
                                v.get("id").asString()))
                .isEqualTo(1);
        JsonNode record =
                get(admin, "/v1/audit/history?entity=allowance_ceiling_norm&key=LABOUR_TA");
        assertThat(record.get("screen").asString()).isEqualTo("M-15");
        assertThat(texts(record.get("versions"), "effectiveFrom")).contains("2091-01-01");
    }

    /**
     * SC-04-0056 (M-15 postcondition l.1404; SRS-16-14, SRS-G-38): the active ceiling norm is
     * effective-dated and kept beside its prior versions, so the adjudication of a closed period is
     * reproduced from the version in force then. Two approved versions of TRANSFER_TA (2095 and
     * 2096): the first ends the day before the second starts and is kept, and the version found as
     * of a date of each year is that year's, on both sides of the change day.
     */
    @Test
    void SC_04_0056_M15_aClaimAdjudicatedOnAPastDateUsesTheCeilingVersionThen() throws Exception {
        String path = "/v1/masters/ceiling-norms/TRANSFER_TA/versions";
        for (String[] v : new String[][] {{"2095-01-01", "610"}, {"2096-01-01", "680"}}) {
            Session admin = ruleAdmin();
            MvcResult proposed = post(admin, path, norm(v[0], v[1], "DEMO_ALL"));
            assertThat(code(proposed))
                    .as(proposed.getResponse().getContentAsString())
                    .isEqualTo(202);
            assertThat(
                            decide(
                                    hqChecker(),
                                    body(proposed).get("approvalId").asLong(),
                                    "APPROVE",
                                    null,
                                    null))
                    .isEqualTo(200);
        }
        Session admin = ruleAdmin();
        JsonNode versions = get(admin, path);
        JsonNode first = null;
        JsonNode second = null;
        for (JsonNode v : versions) {
            if ("2095-01-01".equals(v.get("effectiveFrom").asString())) {
                first = v;
            } else if ("2096-01-01".equals(v.get("effectiveFrom").asString())) {
                second = v;
            }
        }
        assertThat(first).as("the prior version is kept").isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.get("effectiveTo").asString()).isEqualTo("2095-12-31");
        assertThat(first.get("ceilingValue").decimalValue()).isEqualByComparingTo("610");
        assertThat(second.get("ceilingValue").decimalValue()).isEqualByComparingTo("680");
        assertThat(second.get("versionNo").asInt()).isGreaterThan(first.get("versionNo").asInt());

        for (String[] at :
                new String[][] {
                    {"2095-06-30", "610"}, {"2095-12-31", "610"}, {"2096-01-01", "680"}
                }) {
            JsonNode asOf =
                    get(
                            admin,
                            "/v1/audit/history/as-of?entity=allowance_ceiling_norm&key=TRANSFER_TA"
                                    + "&date="
                                    + at[0]);
            assertThat(asOf.get("status").asString()).as(at[0]).isEqualTo("FOUND");
            assertThat(new java.math.BigDecimal(asOf.get("values").get("ceiling_value").asString()))
                    .as(at[0])
                    .isEqualByComparingTo(at[1]);
        }
    }

    // ---------------------------------------------------------------- M-25

    private MvcResult recordCircular(
            Session maker, String number, Long supersedes, byte[] file, String fileName)
            throws Exception {
        Map<String, Object> c = new HashMap<>();
        c.put("number", number);
        c.put("date", "2026-10-01");
        c.put("issuingAuthority", "HQ_PERSONNEL");
        c.put("subject", "IT: revision of handling norms");
        c.put("category", "LABOUR_NORM");
        c.put("payrollTypes", List.of("DEPT_LABOUR", "DPS_LABOUR"));
        c.put("appliesAllOffices", true);
        c.put("effectiveFrom", "2026-10-01");
        c.put("supersedesId", supersedes);
        c.put("reason", "IT: new order");
        return mvc.perform(
                        userRequest(
                                        maker,
                                        multipart("/v1/masters/circulars")
                                                .file(
                                                        new MockMultipartFile(
                                                                "file",
                                                                fileName,
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

    /** An Active circular: recorded by the Delhi maker, approved by the Delhi checker. */
    private long activeCircular() throws Exception {
        MvcResult r = recordCircular(delhiMaker(), unique("IT/CIRC/"), null, PDF, "order.pdf");
        assertThat(code(r)).as(r.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(
                        decide(
                                delhiChecker(),
                                body(r).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        return body(r).get("id").asLong();
    }

    @Test
    void SRS_G_38_aCircularIsRecordedOnceWithItsCopyAndSupersededInTheApprovalTransaction()
            throws Exception {
        Session maker = delhiMaker();
        String number = unique("IT/DA/");
        MvcResult recorded = recordCircular(maker, number, null, PDF, "order.pdf");
        assertThat(code(recorded)).isEqualTo(202);
        JsonNode c = body(recorded);
        assertThat(c.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(c.get("documentId").isNull()).isFalse();
        long first = c.get("id").asLong();

        // The number is unique (DDL uk_circular_order_number); the copy must be a PDF.
        assertThat(errorCode(recordCircular(maker, number, null, PDF, "again.pdf")))
                .isEqualTo("MST-409-CIRCULAR");
        assertThat(
                        errorCode(
                                recordCircular(
                                        maker,
                                        unique("IT/X/"),
                                        null,
                                        "not a pdf".getBytes(StandardCharsets.US_ASCII),
                                        "order.pdf")))
                .isEqualTo("MST-422-CIRCULAR-DOC");
        // Only an Active circular can be superseded.
        assertThat(errorCode(recordCircular(maker, unique("IT/S/"), first, PDF, "s.pdf")))
                .isEqualTo("MST-409-CIRCULAR-STATE");

        // The maker never approves the own circular; the checker of the maker's office does.
        long approval = c.get("approvalId").asLong();
        assertThat(decide(maker, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(delhiChecker(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(get(maker, "/v1/masters/circulars/" + first).get("status").asString())
                .isEqualTo("ACTIVE");
        MvcResult copy = download(maker, "/v1/masters/circulars/" + first + "/document");
        assertThat(copy.getResponse().getContentAsByteArray()).isEqualTo(PDF);

        MvcResult successor = recordCircular(maker, unique("IT/DA2/"), first, PDF, "s.pdf");
        assertThat(code(successor)).isEqualTo(202);
        assertThat(
                        decide(
                                delhiChecker(),
                                body(successor).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(get(maker, "/v1/masters/circulars/" + first).get("status").asString())
                .isEqualTo("SUPERSEDED");
        JsonNode found =
                get(maker, "/v1/masters/circulars?text=" + number.substring(0, 6).toLowerCase());
        assertThat(texts(found, "number")).contains(number);
        JsonNode record = get(maker, "/v1/audit/history?entity=circular_order&key=" + first);
        assertThat(record.get("screen").asString()).isEqualTo("M-25");
        assertThat(record.get("versioned").asBoolean()).as("changed in place").isFalse();
        assertThat(record.get("versions")).as("one row, no versions").isEmpty();
        assertThat(texts(record.get("items"), "newValue")).contains("SUPERSEDED");
        // Today's fields, rebuilt from the row and its audit trail (SRS-20-3).
        JsonNode today =
                get(
                        maker,
                        "/v1/audit/history/as-of?entity=circular_order&key="
                                + first
                                + "&date="
                                + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
        assertThat(today.get("status").asString()).isEqualTo("FOUND");
        assertThat(today.get("values").get("circular_status").asString()).isEqualTo("SUPERSEDED");
        assertThat(today.get("values").get("circular_number").asString()).isEqualTo(number);
    }

    @Test
    void SRS_G_38_aWithdrawnCircularTakesNoNewLink() throws Exception {
        long id = activeCircular();
        Session maker = delhiMaker();
        assertThat(
                        code(
                                post(
                                        maker,
                                        "/v1/masters/circulars/" + id + "/withdraw",
                                        Map.of("reason", "IT: order cancelled"))))
                .isEqualTo(202);
        long withdrawal = pendingApproval("circular_withdrawal", String.valueOf(id));
        assertThat(decide(delhiChecker(), withdrawal, "APPROVE", null, null)).isEqualTo(200);
        assertThat(get(maker, "/v1/masters/circulars/" + id).get("status").asString())
                .isEqualTo("WITHDRAWN");
        MvcResult link =
                post(
                        ruleAdmin(),
                        "/v1/masters/operations/" + unique("W").substring(0, 8) + "/versions",
                        operation("Withdrawn order op", false, "TIME_BASED", id));
        assertThat(code(link)).isEqualTo(409);
        assertThat(errorCode(link)).isEqualTo("MST-409-CIRCULAR-STATE");
    }

    // ---------------------------------------------------------------- M-27

    private static Map<String, Object> operation(
            String name, boolean bagBased, String link, long circular) {
        Map<String, Object> b = new HashMap<>();
        b.put("effectiveFrom", "2091-01-01");
        b.put("name", name);
        b.put("departmental", true);
        b.put("dps", true);
        b.put("nwnp", false);
        b.put("dusting", false);
        b.put("presentButNoWork", false);
        b.put("bagBased", bagBased);
        b.put("rateNormLink", link);
        b.put("circularId", circular);
        b.put("reason", "IT: operation of the current system");
        return b;
    }

    // SRS-16-25: the Operation Master on M-27.
    @Test
    void SRS_G_41_anOperationLinksItsActiveCircularAndKeepsItsStandardName() throws Exception {
        long circular = activeCircular();
        Session admin = ruleAdmin();
        String code = unique("OP").substring(0, 8);
        MvcResult bag =
                post(
                        admin,
                        "/v1/masters/operations/" + code + "/versions",
                        operation("Wagon to Platform", true, "TIME_BASED", circular));
        assertThat(errorCode(bag)).isEqualTo("MST-422-OPERATION");
        Map<String, Object> none = operation("Wagon to Platform", false, "TIME_BASED", circular);
        none.put("departmental", false);
        none.put("dps", false);
        assertThat(errorCode(post(admin, "/v1/masters/operations/" + code + "/versions", none)))
                .isEqualTo("MST-422-OPERATION");

        MvcResult ok =
                post(
                        admin,
                        "/v1/masters/operations/" + code + "/versions",
                        operation("Wagon to Platform", true, "NORM_SLAB", circular));
        assertThat(code(ok)).isEqualTo(202);
        assertThat(decide(hqChecker(), body(ok).get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode used = get(admin, "/v1/masters/circulars/" + circular + "/where-used");
        assertThat(texts(used, "key")).contains(code);
        assertThat(find(used, "key", code).get("kind").asString()).isEqualTo("MASTER_ENTRY");

        Map<String, Object> renamed = operation("Wagon to Shed", true, "NORM_SLAB", circular);
        renamed.put("effectiveFrom", "2092-01-01");
        MvcResult rename = post(admin, "/v1/masters/operations/" + code + "/versions", renamed);
        assertThat(code(rename)).isEqualTo(409);
        assertThat(errorCode(rename)).isEqualTo("MST-409-OPERATION-NAME");

        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/masters/operations/" + code + "/end-date",
                                        Map.of("lastDay", "2095-12-31", "reason", "IT: retired"))))
                .isEqualTo(202);
        long end = pendingApproval("operation_end_date", code);
        assertThat(decide(hqChecker(), end, "APPROVE", null, null)).isEqualTo(200);
        assertThat(
                        get(admin, "/v1/masters/operations/" + code + "/versions")
                                .get(0)
                                .get("effectiveTo")
                                .asString())
                .isEqualTo("2095-12-31");
        JsonNode record = get(admin, "/v1/audit/history?entity=operation_master&key=" + code);
        assertThat(record.get("screen").asString()).isEqualTo("M-27");
        assertThat(record.get("versions").size()).isEqualTo(1);
        assertThat(record.get("versions").get(0).get("effectiveTo").asString())
                .isEqualTo("2095-12-31");
    }

    // ---------------------------------------------------------------- M-26

    private static Map<String, Object> label(
            String key, String type, String language, String scope, String text) {
        Map<String, Object> b = new HashMap<>();
        b.put("key", key);
        b.put("type", type);
        b.put("language", language);
        b.put("scope", scope);
        b.put("text", text);
        b.put("effectiveFrom", "2026-04-01");
        b.put("localisable", false);
        b.put("reason", "IT: label");
        if ("MASTER_VALUE".equals(type)) {
            b.put("masterEntity", "ORG_DESIGNATION");
            b.put("masterCode", "SARDAR");
        }
        return b;
    }

    private String resolved(Session s, String key, String language) throws Exception {
        return get(s, "/v1/masters/labels/resolve?key=" + key + "&language=" + language)
                .path("text")
                .asString();
    }

    @Test
    void SRS_G_39_aLabelIsShownOfficeFirstThenRegionThenGlobalThenEnglish() throws Exception {
        Session admin = sysadmin();
        String key = unique("LBL.IT.");
        MvcResult hindi =
                post(
                        admin,
                        "/v1/masters/labels",
                        label(key, "UI_LABEL", "HI", "GLOBAL", "परीक्षण"));
        assertThat(code(hindi)).as(hindi.getResponse().getContentAsString()).isEqualTo(202);
        MvcResult english =
                post(admin, "/v1/masters/labels", label(key, "UI_LABEL", "EN", "GLOBAL", "Test"));
        assertThat(code(english)).isEqualTo(202);
        // Not shown until approved: English falls back to nothing yet.
        Session maker = delhiMaker();
        assertThat(resolved(maker, key, "HI")).isEmpty();
        assertThat(
                        decide(
                                hqChecker(),
                                body(hindi).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(
                        decide(
                                hqChecker(),
                                body(english).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(resolved(maker, key, "HI")).isEqualTo("परीक्षण");
        // A language with no label of its own falls back to English Global.
        assertThat(resolved(maker, key, "TA")).isEqualTo("Test");

        // Screen text stays Global; a unit user may not propose a Global label.
        MvcResult localUi =
                post(maker, "/v1/masters/labels", label(key, "UI_LABEL", "EN", "OFFICE", "Local"));
        assertThat(errorCode(localUi)).isEqualTo("MST-422-LABEL-SCOPE");
        Map<String, Object> later = label(key, "UI_LABEL", "EN", "GLOBAL", "X");
        later.put("effectiveFrom", "2027-01-01");
        MvcResult global = post(maker, "/v1/masters/labels", later);
        assertThat(code(global)).isEqualTo(403);
        assertThat(errorCode(global)).isEqualTo("WF-403-NO-RULE");
        // The same slot and date as an Active label is refused before any route is tried.
        MvcResult taken =
                post(admin, "/v1/masters/labels", label(key, "UI_LABEL", "EN", "GLOBAL", "Again"));
        assertThat(errorCode(taken)).isEqualTo("MST-409-LABEL");
    }

    // SC-01-0172: Headquarters withdraws a local name and the standard name shows again.
    @Test
    void SRS_G_39_aLocalNameIsApprovedByTheRegionalOfficeAndWithdrawnByHeadquarters()
            throws Exception {
        Session maker = delhiMaker();
        assertThat(resolved(maker, "DESIG.SARDAR", "EN")).isEqualTo("Sardar");
        MvcResult local =
                post(
                        maker,
                        "/v1/masters/labels",
                        label("DESIG.SARDAR", "MASTER_VALUE", "EN", "OFFICE", "Mukadam"));
        assertThat(code(local)).as(local.getResponse().getContentAsString()).isEqualTo(202);
        long approval = body(local).get("approvalId").asLong();
        // Decided at the Regional Office above the proposing unit (route M26_LOCAL, level RO).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT org_scope_id FROM workflow_inbox_item WHERE approval_id ="
                                        + " ?",
                                Long.class,
                                approval))
                .isEqualTo(locationId("RO-DL"));
        Session zoneChecker = login("agm.north@fci-demo.test", "PAYROLL_CHECKER", "NA11");
        assertThat(decide(zoneChecker, approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(resolved(maker, "DESIG.SARDAR", "EN")).isEqualTo("Mukadam");
        // Another office still sees the standard name.
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(resolved(kolkata, "DESIG.SARDAR", "EN")).isEqualTo("Sardar");

        long labelId = body(local).get("id").asLong();
        Session admin = sysadmin();
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/masters/labels/" + labelId + "/withdraw",
                                        Map.of("reason", "IT: local name withdrawn"))))
                .isEqualTo(202);
        assertThat(
                        decide(
                                hqChecker(),
                                pendingApproval("label_withdrawal", String.valueOf(labelId)),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(resolved(maker, "DESIG.SARDAR", "EN")).isEqualTo("Sardar");
    }

    // SC-01-0173: a label with no Hindi text is on the Missing translations report.
    @Test
    void SRS_G_39_labelsAreDownloadedAndUploadedInBulkAndMissingHindiIsReported() throws Exception {
        Session admin = sysadmin();
        assertThat(texts(get(admin, "/v1/masters/labels/missing-hindi"), "key"))
                .contains("DESIG.SARDAR");
        String csv = csv(admin, "/v1/masters/labels/export?language=EN&scope=GLOBAL");
        assertThat(csv).contains("label_key").contains("DESIG.SARDAR");

        String a = unique("LBL.BULK.");
        String b = unique("LBL.BULK.");
        String file =
                "label_key,label_type,language,scope,label_text,effective_from\r\n"
                        + a
                        + ",UI_LABEL,HI,GLOBAL,पहला,2026-04-01\r\n"
                        + b
                        + ",HELP_TEXT,EN,GLOBAL,\"Second, with comma\",2026-04-01\r\n";
        MvcResult uploaded =
                mvc.perform(
                                userRequest(
                                                admin,
                                                multipart("/v1/masters/labels/upload")
                                                        .file(
                                                                new MockMultipartFile(
                                                                        "file",
                                                                        "labels.csv",
                                                                        "text/csv",
                                                                        file.getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)))
                                                        .param("reason", "IT: bulk labels"))
                                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                        .andReturn();
        assertThat(code(uploaded)).as(uploaded.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(texts(body(uploaded), "key")).containsExactly(a, b);
        assertThat(find(body(uploaded), "key", b).get("text").asString())
                .isEqualTo("Second, with comma");

        // One bad row stops the whole file: nothing is submitted.
        String bad =
                "label_key,label_type,language,scope,label_text,effective_from\r\n"
                        + unique("LBL.BAD.")
                        + ",UI_LABEL,HI,GLOBAL,ok,2026-04-01\r\n"
                        + "bad key!,UI_LABEL,HI,GLOBAL,x,2026-04-01\r\n";
        MvcResult refused =
                mvc.perform(
                                userRequest(
                                                admin,
                                                multipart("/v1/masters/labels/upload")
                                                        .file(
                                                                new MockMultipartFile(
                                                                        "file",
                                                                        "bad.csv",
                                                                        "text/csv",
                                                                        bad.getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)))
                                                        .param("reason", "IT: bulk labels"))
                                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                        .andReturn();
        assertThat(errorCode(refused)).isEqualTo("MST-422-LABEL");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM label_master WHERE label_key LIKE"
                                        + " 'LBL.BAD.%'",
                                Integer.class))
                .isZero();
        // SRS M-26 "Bulk upload (Administrator)" (UI_BMAD_g1 #48): an office maker, who may
        // propose a local name one by one, does not upload in bulk.
        String one =
                "label_key,label_type,language,scope,label_text,effective_from\r\n"
                        + unique("LBL.MAKER.")
                        + ",UI_LABEL,HI,GLOBAL,ok,2026-04-01\r\n";
        MvcResult maker =
                mvc.perform(
                                userRequest(
                                                delhiMaker(),
                                                multipart("/v1/masters/labels/upload")
                                                        .file(
                                                                new MockMultipartFile(
                                                                        "file",
                                                                        "maker.csv",
                                                                        "text/csv",
                                                                        one.getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)))
                                                        .param("reason", "IT: maker bulk"))
                                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                        .andReturn();
        assertThat(code(maker)).isEqualTo(403);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM label_master WHERE label_key LIKE"
                                        + " 'LBL.MAKER.%'",
                                Integer.class))
                .isZero();
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (n.path(field).asString().equals(value)) {
                return n;
            }
        }
        throw new AssertionError("no element with " + field + " = " + value);
    }

    private void operationLabel(
            String key, String language, String scope, String office, String text, String status) {
        jdbc.update(
                "INSERT INTO label_master (label_key, label_type, master_entity, master_code,"
                        + " language_code, scope_level, scope_location_id, label_text,"
                        + " is_localisable, version_no, effective_from, label_status, changed_by)"
                        + " VALUES (?, 'MASTER_VALUE', 'OPERATION', 'STACK', ?, ?, (SELECT"
                        + " location_id FROM org_location WHERE location_code = ?), ?, 'Y', 1, ?,"
                        + " ?, 'it')",
                key,
                language,
                scope,
                office,
                text,
                java.sql.Date.valueOf(
                        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(1)),
                status);
    }

    private JsonNode operations(Session s, String query) throws Exception {
        MvcResult r =
                mvc.perform(userGet(s, "/v1/masters/operations?size=100&" + query)).andReturn();
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(r).get("items");
    }

    /**
     * NEW-G1-05, NEW-G1-06 (US-01-18 AC5, US-01-19 AC1 / AC3; SC-01-0037): SRS-G-39 "Let search
     * find a record by any of its labels" and "show the first label found in this order: user's
     * language and office; ... English, global", with the local and the standard name together. An
     * office local name of operation STACK: the office's user sees it next to the standard name and
     * finds the operation by it; Headquarters finds it by the same text but sees the standard name
     * only; a withdrawn label is never searched; a text that matches nothing finds nothing.
     */
    @Test
    void SRS_G_39_anOperationIsFoundByAnyLabelAndShownWithItsLocalName() throws Exception {
        String local = "ITThappi" + unique("L").substring(0, 6);
        operationLabel("OPR.STACK." + local, "EN", "OFFICE", "DH001", local, "ACTIVE");
        String withdrawn = "ITOld" + unique("W").substring(0, 6);
        operationLabel("OPR.STACK." + withdrawn, "EN", "OFFICE", "DH001", withdrawn, "WITHDRAWN");

        JsonNode office = operations(delhiMaker(), "q=" + local.toLowerCase() + "&lang=en");
        assertThat(texts(office, "code")).containsExactly("STACK");
        assertThat(office.get(0).path("shownName").asString()).isEqualTo(local);
        assertThat(office.get(0).path("current").path("name").asString()).isEqualTo("Stacking");

        JsonNode hq = operations(hqChecker(), "q=" + local + "&lang=en");
        assertThat(texts(hq, "code")).as("found by the label anywhere").containsExactly("STACK");
        assertThat(hq.get(0).path("shownName").isNull()).as("HQ: the standard name").isTrue();

        assertThat(texts(operations(delhiMaker(), "q=" + withdrawn), "code"))
                .as("a withdrawn label is not searched")
                .isEmpty();
        assertThat(texts(operations(delhiMaker(), "q=stack"), "code"))
                .as("by code, ignoring case")
                .containsExactly("STACK");
        assertThat(texts(operations(delhiMaker(), "q=Truck-to"), "code"))
                .as("by the standard name")
                .containsExactly("T2P");
        assertThat(texts(operations(delhiMaker(), ""), "code"))
                .as("no text: every operation")
                .contains("STACK", "T2P", "W2P", "PBNW");
        assertThat(
                        mvc.perform(userGet(delhiMaker(), "/v1/masters/operations?lang=fr"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("an unknown language")
                .isEqualTo(400);
    }
}
