package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The income-tax section master and the PT state master on M-18 (docs/plan/08-income-tax.md phase
 * 08b; SRS-7-2, SRS-7-4, SRS-7-20, SRS-7-26; BR-TAX-002, BR-TAX-006): proposed by the Rule
 * Administrator at Headquarters, approved by a different Rule Approver, refusals named, and the
 * yearly rate keys present but not configured (OQ-4). Each test uses codes of its own.
 */
class TaxConfigurationIT extends ApiTestSupport {

    private Session admin() throws Exception {
        return login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
    }

    private Session approver() throws Exception {
        return login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
    }

    private static Map<String, Object> section(String code, String from, String limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("description", "IT section " + code);
        body.put("limit", limit);
        body.put("regime", "OLD");
        body.put("effectiveFrom", from);
        body.put("reason", "IT: section notified");
        return body;
    }

    private static Map<String, Object> state(String code, boolean applicable, String to) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "IT state " + code);
        body.put("ptApplicable", applicable);
        body.put("registrationNumber", "PTR-" + code + "-0001");
        body.put("returnPeriodicity", "MONTHLY");
        body.put("effectiveFrom", "2026-04-01");
        body.put("effectiveTo", to);
        body.put("reason", "IT: state notified");
        return body;
    }

    private MvcResult post(Session s, String path, Map<String, Object> body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private MvcResult put(Session s, String path, Map<String, Object> body) throws Exception {
        return mvc.perform(userPut(s, path, body)).andReturn();
    }

    private JsonNode get(Session s, String path) throws Exception {
        return body(mvc.perform(userGet(s, path)).andReturn());
    }

    /** The rows of {@code code} across every page (the demo seed fills more than one). */
    private static List<JsonNode> rows(JsonNode all, String code) {
        List<JsonNode> out = new ArrayList<>();
        all.forEach(
                r -> {
                    if (code.equals(r.path("code").asString())) {
                        out.add(r);
                    }
                });
        return out;
    }

    private static boolean listed(JsonNode pending, long approval) {
        boolean found = false;
        for (JsonNode p : pending) {
            found |= p.path("approvalId").asLong() == approval;
        }
        return found;
    }

    /** The audit rows of the master itself (the approval workflow writes rows of its own). */
    private int audits(String entity, String key) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE entity_name = ? AND entity_key = ? AND"
                        + " field_name IN ('version', 'state_name', 'pt_applicable_flag',"
                        + " 'registration_number', 'slab_rule_key', 'return_periodicity',"
                        + " 'effective_from', 'effective_to')",
                Integer.class,
                entity,
                key);
    }

    @Test
    void SRS_7_20_aSectionVersionIsApprovedByADifferentUserAndEndsTheVersionInForce()
            throws Exception {
        String code = "901ITA";
        MvcResult first = post(admin(), "/v1/tax/sections", section(code, "2026-04-01", "150000"));
        assertThat(first.getResponse().getStatus())
                .as(first.getResponse().getContentAsString())
                .isEqualTo(202);
        long firstApproval = body(first).path("approvalId").asLong();
        assertThat(listed(get(admin(), "/v1/tax/sections/pending"), firstApproval)).isTrue();
        // Nothing is written before the decision; one open proposal per section; never
        // self-approved.
        assertThat(rows(all(admin(), "/v1/tax/sections"), code)).isEmpty();
        assertThat(
                        post(admin(), "/v1/tax/sections", section(code, "2026-05-01", null))
                                .getResponse()
                                .getStatus())
                .isEqualTo(409);
        assertThat(decide(admin(), firstApproval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(approver(), firstApproval, "APPROVE", null, null)).isEqualTo(200);

        long second =
                body(post(admin(), "/v1/tax/sections", section(code, "2027-04-01", "200000.50")))
                        .path("approvalId")
                        .asLong();
        assertThat(decide(approver(), second, "APPROVE", null, null)).isEqualTo(200);

        List<JsonNode> versions = rows(all(admin(), "/v1/tax/sections"), code);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).path("effectiveFrom").asString()).isEqualTo("2027-04-01");
        assertThat(versions.get(0).path("limit").decimalValue()).isEqualByComparingTo("200000.50");
        assertThat(versions.get(0).path("regime").asString()).isEqualTo("OLD");
        assertThat(versions.get(1).path("effectiveTo").asString()).isEqualTo("2027-03-31");
        assertThat(versions.get(1).path("inForce").asBoolean()).isTrue();
        assertThat(audits("it_section_master", code)).isEqualTo(2);
        // A version not after the latest one is refused, by name.
        assertThat(
                        body(post(admin(), "/v1/tax/sections", section(code, "2027-04-01", null)))
                                .path("errorCode")
                                .asString())
                .isEqualTo("TAX-409-SECTION-ORDER");
    }

    @Test
    void SRS_7_2_aRejectedSectionWritesNothingAndASectionWithoutALimitKeepsNone() throws Exception {
        String rejected = "902ITR";
        long refused =
                body(post(admin(), "/v1/tax/sections", section(rejected, "2026-04-01", "1")))
                        .path("approvalId")
                        .asLong();
        assertThat(decide(approver(), refused, "REJECT", "IT: not notified", null)).isEqualTo(200);
        assertThat(rows(all(admin(), "/v1/tax/sections"), rejected)).isEmpty();
        assertThat(listed(get(admin(), "/v1/tax/sections/pending"), refused)).isFalse();

        String open = "903ITB(1B)";
        Map<String, Object> both = section(open, "2026-04-01", null);
        both.put("regime", "BOTH");
        long approval = body(post(admin(), "/v1/tax/sections", both)).path("approvalId").asLong();
        assertThat(decide(approver(), approval, "APPROVE", null, null)).isEqualTo(200);
        JsonNode row = rows(all(admin(), "/v1/tax/sections"), open).get(0);
        assertThat(row.path("limit").isNull()).isTrue();
        assertThat(row.path("regime").asString()).isEqualTo("BOTH");
    }

    @Test
    void SRS_7_4_aPtStateIsCreatedThenChangedUnderApprovalWithEachFieldAudited() throws Exception {
        String code = "ITPA";
        long created =
                body(post(admin(), "/v1/tax/pt-states", state(code, true, null)))
                        .path("approvalId")
                        .asLong();
        assertThat(listed(get(admin(), "/v1/tax/pt-states/pending"), created)).isTrue();
        assertThat(decide(approver(), created, "APPROVE", null, null)).isEqualTo(200);
        JsonNode row = rows(all(admin(), "/v1/tax/pt-states"), code).get(0);
        // BR-TAX-006: the slabs of a PT state are the PT.SLAB band set of its code (SYS).
        assertThat(row.path("slabRuleKey").asString()).isEqualTo("PT.SLAB");
        assertThat(row.path("ptApplicable").asBoolean()).isTrue();
        int initial = audits("pt_state_master", code);
        assertThat(initial).isGreaterThanOrEqualTo(5);

        // A second create of the same state is refused; an edit of an unknown state is not found.
        assertThat(
                        post(admin(), "/v1/tax/pt-states", state(code, true, null))
                                .getResponse()
                                .getStatus())
                .isEqualTo(409);
        assertThat(
                        put(admin(), "/v1/tax/pt-states/ITZZ", state("ITZZ", true, null))
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);

        // PT no longer applies: the slab key goes; only the two changed fields are audited.
        Map<String, Object> change = state(code, false, null);
        long edited =
                body(put(admin(), "/v1/tax/pt-states/" + code, change)).path("approvalId").asLong();
        assertThat(decide(admin(), edited, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(approver(), edited, "APPROVE", null, null)).isEqualTo(200);
        row = rows(all(admin(), "/v1/tax/pt-states"), code).get(0);
        assertThat(row.path("ptApplicable").asBoolean()).isFalse();
        assertThat(row.path("slabRuleKey").isNull()).isTrue();
        assertThat(audits("pt_state_master", code)).isEqualTo(initial + 2);
    }

    @Test
    void SRS_7_26_eachRefusalIsNamed() throws Exception {
        Session admin = admin();
        Map<String, Object> badCode = section("C80", "2026-04-01", null);
        Map<String, Object> badDates = state("ITPB", true, "2026-03-31");
        Map<String, Object> noCode = state(null, true, null);
        assertThat(body(post(admin, "/v1/tax/sections", badCode)).path("errorCode").asString())
                .isEqualTo("TAX-422-SECTION-CODE");
        assertThat(body(post(admin, "/v1/tax/pt-states", badDates)).path("errorCode").asString())
                .isEqualTo("TAX-422-PT-STATE");
        assertThat(body(post(admin, "/v1/tax/pt-states", noCode)).path("errorCode").asString())
                .isEqualTo("TAX-422-PT-STATE");
        // The request boundary: paise beyond two places, a negative limit, an unknown regime, a
        // name the US7ASCII database cannot hold.
        Map<String, Object> paise = section("904ITC", "2026-04-01", "1.005");
        Map<String, Object> negative = section("904ITC", "2026-04-01", "-1");
        Map<String, Object> regime = section("904ITC", "2026-04-01", null);
        regime.put("regime", "ANY");
        Map<String, Object> hindi = state("ITPC", true, null);
        hindi.put("name", "राज्य");
        for (Map<String, Object> body : List.of(paise, negative, regime)) {
            assertThat(post(admin, "/v1/tax/sections", body).getResponse().getStatus())
                    .as(body.toString())
                    .isEqualTo(400);
        }
        assertThat(post(admin, "/v1/tax/pt-states", hindi).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void SRS_22_21_onlyTheRuleAdministratorProposesAndThePayrollMakerDoesNotRead()
            throws Exception {
        assertThat(
                        post(approver(), "/v1/tax/sections", section("905ITD", "2026-04-01", null))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        assertThat(
                        post(approver(), "/v1/tax/pt-states", state("ITPD", true, null))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        for (String path : List.of("/v1/tax/sections", "/v1/tax/pt-states")) {
            assertThat(mvc.perform(userGet(maker, path)).andReturn().getResponse().getStatus())
                    .as(path)
                    .isEqualTo(403);
        }
    }

    @Test
    void SRS_7_26_theYearlyTaxRatesArePresentButNotConfigured() {
        List<Map<String, Object>> keys =
                jdbc.queryForList(
                        // OQ-4: no version in force today. The tax ITs give years long past
                        // their own test versions (TaxYearFixtures), each closed at its year's end.
                        "SELECT d.rule_key, d.rule_type_code, d.owning_module, (SELECT COUNT(*)"
                            + " FROM rule_version v WHERE v.rule_id = d.rule_id AND"
                            + " v.effective_from <= CURRENT_DATE AND (v.effective_to IS NULL OR"
                            + " v.effective_to >= CURRENT_DATE)) versions FROM rule_definition d"
                            + " WHERE d.rule_key IN ('TAX.SLAB.OLD', 'TAX.SLAB.NEW',"
                            + " 'TAX.SURCHARGE', 'TAX.CESS', 'TAX.NOPAN.RATE', 'PT.SLAB')");
        assertThat(keys).hasSize(6);
        keys.forEach(
                k -> {
                    assertThat(k.get("OWNING_MODULE")).as(k.toString()).isEqualTo("M-18");
                    // The demo PT slab (V040_8_9, module 16 phase 16e-2) is the one demo version.
                    assertThat(((Number) k.get("VERSIONS")).intValue())
                            .as(k.toString())
                            .isEqualTo("PT.SLAB".equals(k.get("RULE_KEY")) ? 1 : 0);
                });
    }
}
