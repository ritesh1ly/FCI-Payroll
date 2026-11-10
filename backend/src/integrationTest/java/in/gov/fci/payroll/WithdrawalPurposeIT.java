package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * CPF withdrawal purposes and checklists on M-19 (docs/plan/07-cpf.md phase 07f-1; SRS-6-5,
 * SRS-6-14, SRS-6-39, SRS-6-2, SRS-6-23; BR-CPF-028, 039): the SRS-named purposes seeded
 * unconfirmed, the limits and conditions the SRS states seeded as rule versions, a new version
 * proposed by the Rule Administrator and approved by a different Rule Approver, and the refusals.
 * Each test uses a purpose code of its own, so the tests never meet.
 */
class WithdrawalPurposeIT extends ApiTestSupport {

    private Session admin() throws Exception {
        return login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
    }

    private Session approver() throws Exception {
        return login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
    }

    private Map<String, Object> purpose(
            String code, String from, List<Map<String, Object>> documents) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "IT purpose " + code);
        body.put("category", "ADVANCE");
        body.put("limitRuleKey", "CPF.LIMIT.MGW.MARRIAGE");
        body.put("maxOccurrences", 2);
        body.put("noCasteDispute", true);
        body.put("propertyDetails", false);
        body.put("effectiveFrom", from);
        body.put("confirmed", true);
        body.put("documents", documents);
        body.put("reason", "IT: checklist supplied by FCI");
        return body;
    }

    private static Map<String, Object> doc(String type, boolean mandatory, String tier) {
        Map<String, Object> d = new HashMap<>();
        d.put("documentType", type);
        d.put("mandatory", mandatory);
        d.put("tier", tier);
        return d;
    }

    private MvcResult propose(Session s, Map<String, Object> body) throws Exception {
        return mvc.perform(userPost(s, "/v1/cpf/withdrawal-purposes", body)).andReturn();
    }

    private JsonNode list(Session s) throws Exception {
        return body(mvc.perform(userGet(s, "/v1/cpf/withdrawal-purposes")).andReturn());
    }

    private static List<JsonNode> versions(JsonNode list, String code) {
        List<JsonNode> out = new java.util.ArrayList<>();
        list.path("purposes")
                .forEach(
                        p -> {
                            if (code.equals(p.path("code").asString())) {
                                out.add(p);
                            }
                        });
        return out;
    }

    private BigDecimal ruleValue(String key) {
        List<BigDecimal> values =
                jdbc.queryForList(
                        "SELECT v.scalar_value FROM rule_version v JOIN rule_definition d ON"
                                + " d.rule_id = v.rule_id WHERE d.rule_key = ?",
                        BigDecimal.class,
                        key);
        return values.isEmpty() ? null : values.get(0);
    }

    @Test
    void SRS_6_39_BR_CPF_032_theSrsNamedPurposesAreSeededUnconfirmedWithTheStatedLimits()
            throws Exception {
        JsonNode list = list(login("cpf.north@fci-demo.test", "CPF_DIVISION", "NA11"));
        for (String code :
                List.of(
                        "MARRIAGE",
                        "RELIGIOUS",
                        "HIGHER_EDUCATION",
                        "ILLNESS",
                        "SPECIAL_CIRCUMSTANCES",
                        "READY_BUILT_HOUSE",
                        "CONSTRUCTION",
                        "PLOT_PURCHASE",
                        "RENOVATION",
                        "HOME_LOAN_REPAYMENT",
                        "BEFORE_RETIREMENT_90",
                        "FINAL_PAYMENT")) {
            assertThat(versions(list, code)).as(code).isNotEmpty();
        }
        JsonNode house =
                versions(list, "READY_BUILT_HOUSE")
                        .get(versions(list, "READY_BUILT_HOUSE").size() - 1);
        assertThat(house.path("propertyDetails").asBoolean()).isTrue();
        assertThat(house.path("category").asString()).isEqualTo("PART_FINAL");
        assertThat(versions(list, "ILLNESS").get(0).path("limitRuleKey").isNull()).isTrue();
        assertThat(versions(list, "HOME_LOAN_REPAYMENT").get(0).path("confirmed").asBoolean())
                .isFalse();
        // BR-CPF-028: no Covid-19 purpose.
        list.path("purposes")
                .forEach(p -> assertThat(p.path("code").asString()).doesNotContain("COVID"));
        // SRS-6-2 / 6-5 / 6-24 as rule versions; the MGW basis is not configured (OQ-3).
        assertThat(ruleValue("CPF.LIMIT.MGW.MARRIAGE")).isEqualByComparingTo("12");
        assertThat(ruleValue("CPF.LIMIT.MGW.HOUSE")).isEqualByComparingTo("36");
        assertThat(ruleValue("CPF.LIMIT.BALANCE.BEFORE_RETIREMENT")).isEqualByComparingTo("0.9");
        assertThat(ruleValue("CPF.LIMIT.BALANCE.ADVANCE_CAP")).isEqualByComparingTo("0.5");
        assertThat(ruleValue("CPF.LIMIT.PAY.RENOVATION")).isEqualByComparingTo("12");
        assertThat(ruleValue("CPF.CONDITION.PARTFINAL.MAX_COUNT")).isEqualByComparingTo("6");
        assertThat(ruleValue("CPF.CONDITION.PARTFINAL.MIN_YEARS")).isEqualByComparingTo("5");
        assertThat(ruleValue("CPF.CONDITION.FINAL90.WINDOW_YEARS")).isEqualByComparingTo("1");
        assertThat(ruleValue("CPF.MGW")).isNull();
    }

    @Test
    void SRS_6_14_aNewVersionWithItsChecklistIsApprovedByADifferentUserAndEndsTheOldOne()
            throws Exception {
        String code = "IT_PURPOSE_A";
        Session admin = admin();
        MvcResult first = propose(admin, purpose(code, "2026-05-01", List.of()));
        assertThat(first.getResponse().getStatus())
                .as(first.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(
                        decide(
                                approver(),
                                body(first).path("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);

        MvcResult second =
                propose(
                        admin(),
                        purpose(
                                code,
                                "2027-04-01",
                                List.of(
                                        doc("CERTIFICATE", true, "UNIT_CPF_ADMIN"),
                                        doc("OTHER", false, null))));
        assertThat(second.getResponse().getStatus()).isEqualTo(202);
        long approval = body(second).path("approvalId").asLong();
        JsonNode pending = list(admin()).path("pending");
        boolean listed = false;
        for (JsonNode p : pending) {
            listed |= p.path("approvalId").asLong() == approval;
        }
        assertThat(listed).isTrue();
        // One proposal per purpose at a time; the maker never decides it.
        assertThat(
                        propose(admin(), purpose(code, "2027-05-01", List.of()))
                                .getResponse()
                                .getStatus())
                .isEqualTo(409);
        assertThat(decide(admin(), approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(approver(), approval, "APPROVE", null, null)).isEqualTo(200);

        List<JsonNode> versions = versions(list(admin()), code);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).path("effectiveFrom").asString()).isEqualTo("2027-04-01");
        assertThat(versions.get(0).path("documents")).hasSize(2);
        assertThat(versions.get(0).path("documents").get(1).path("tier").asString())
                .isEqualTo("UNIT_CPF_ADMIN");
        assertThat(versions.get(0).path("noCasteDispute").asBoolean()).isTrue();
        assertThat(versions.get(1).path("effectiveTo").asString()).isEqualTo("2027-03-31");
        assertThat(versions.get(1).path("inForce").asBoolean()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'cpf_withdrawal_purpose' AND entity_key = ?",
                                Integer.class,
                                code))
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void SRS_G_1_aRejectedPurposeWritesNothingAndANewVersionEndsOnlyTheOneInForce()
            throws Exception {
        // A rejected proposal leaves no version and nothing pending (maker-checker).
        String rejected = "IT_PURPOSE_R";
        long refused =
                body(propose(admin(), purpose(rejected, "2026-05-01", List.of())))
                        .path("approvalId")
                        .asLong();
        assertThat(decide(approver(), refused, "REJECT", "IT: not this purpose", null))
                .isEqualTo(200);
        assertThat(versions(list(admin()), rejected)).isEmpty();
        list(admin())
                .path("pending")
                .forEach(p -> assertThat(p.path("approvalId").asLong()).isNotEqualTo(refused));

        // Three versions: the third ends the second; the first keeps the end it already had.
        String code = "IT_PURPOSE_C";
        for (String from : List.of("2026-05-01", "2027-04-01", "2028-04-01")) {
            long approval =
                    body(propose(admin(), purpose(code, from, List.of())))
                            .path("approvalId")
                            .asLong();
            assertThat(decide(approver(), approval, "APPROVE", null, null)).isEqualTo(200);
        }
        List<JsonNode> versions = versions(list(admin()), code);
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).path("effectiveTo").isNull()).isTrue();
        assertThat(versions.get(1).path("effectiveTo").asString()).isEqualTo("2028-03-31");
        assertThat(versions.get(2).path("effectiveTo").asString()).isEqualTo("2027-03-31");
    }

    @Test
    void SRS_6_5_eachRefusalIsNamed() throws Exception {
        Session admin = admin();
        Map<String, Object> covid = purpose("COVID_19", "2026-06-01", List.of());
        Map<String, Object> limit = purpose("IT_PURPOSE_B", "2026-06-01", List.of());
        limit.put("limitRuleKey", "CPF.VPF.MAX");
        Map<String, Object> finalWithLimit = purpose("IT_PURPOSE_B", "2026-06-01", List.of());
        finalWithLimit.put("category", "FINAL");
        Map<String, Object> unknownDoc =
                purpose("IT_PURPOSE_B", "2026-06-01", List.of(doc("PASSPORT", true, null)));
        Map<String, Object> twice =
                purpose(
                        "IT_PURPOSE_B",
                        "2026-06-01",
                        List.of(doc("OTHER", true, null), doc("OTHER", false, null)));
        Map<String, Object> early = purpose("MARRIAGE", "2026-04-01", List.of());
        Map<String, String> refused = new HashMap<>();
        refused.put("covid", body(propose(admin, covid)).path("errorCode").asString());
        refused.put("limit", body(propose(admin, limit)).path("errorCode").asString());
        refused.put("final", body(propose(admin, finalWithLimit)).path("errorCode").asString());
        refused.put("doc", body(propose(admin, unknownDoc)).path("errorCode").asString());
        refused.put("twice", body(propose(admin, twice)).path("errorCode").asString());
        refused.put("early", body(propose(admin, early)).path("errorCode").asString());
        assertThat(refused)
                .containsEntry("covid", "CPF-422-PURPOSE-WITHDRAWN")
                .containsEntry("limit", "CPF-422-PURPOSE-LIMIT")
                .containsEntry("final", "CPF-422-PURPOSE-LIMIT")
                .containsEntry("doc", "CPF-422-PURPOSE-DOCUMENT")
                .containsEntry("twice", "CPF-422-PURPOSE-DOCUMENT")
                .containsEntry("early", "CPF-422-PURPOSE-DATE");
        // The request boundary: a bad category or tier, a name the US7ASCII database cannot hold.
        Map<String, Object> category = purpose("IT_PURPOSE_B", "2026-06-01", List.of());
        category.put("category", "LOAN");
        assertThat(propose(admin, category).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> hindi = purpose("IT_PURPOSE_B", "2026-06-01", List.of());
        hindi.put("name", "विवाह");
        assertThat(propose(admin, hindi).getResponse().getStatus()).isEqualTo(400);
        assertThat(
                        propose(
                                        admin,
                                        purpose(
                                                "IT_PURPOSE_B",
                                                "2026-06-01",
                                                List.of(doc("OTHER", true, "FINANCE"))))
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }

    @Test
    void SRS_22_21_theCpfDivisionReadsTheRuleApproverDoesNotPropose() throws Exception {
        Session division = login("cpf.north@fci-demo.test", "CPF_DIVISION", "NA11");
        assertThat(
                        propose(division, purpose("IT_PURPOSE_C", "2026-06-01", List.of()))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        assertThat(
                        propose(approver(), purpose("IT_PURPOSE_C", "2026-06-01", List.of()))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        mvc.perform(userGet(maker, "/v1/cpf/withdrawal-purposes"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }
}
