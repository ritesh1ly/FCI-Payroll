package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * M-19 PF Interest Rates end to end (docs/plan/07-cpf.md phase 07b; SRS-6-11, BR-CPF-033, SRS M-19
 * field table): the Rule Administrator at HQ records a rate, a different Rule Approver activates it
 * (no self-approval, SRS-G-1), a later rate supersedes the earlier one of its series, a rate
 * recorded undeclared is declared, every change is audited with old and new values (SRS-20-2) and
 * shown in the History button (SRS-20-29). Each test owns a far-future financial year.
 */
class InterestRateIT extends ApiTestSupport {

    private static final String RATES = "/v1/cpf/interest-rates";

    private Session ruleAdmin() throws Exception {
        return login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
    }

    private Session ruleApprover() throws Exception {
        return login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
    }

    private static Map<String, Object> rate(
            String year, String source, String percent, String from, boolean declared) {
        Map<String, Object> r = new HashMap<>();
        r.put("finYear", year);
        r.put("fundType", "CPF");
        r.put("rateSource", source);
        r.put("ratePercent", percent);
        r.put("appliesFrom", from);
        r.put("declared", declared);
        r.put("reason", "IT: declared by the Board of Trustees");
        return r;
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    /** Records a rate as the Rule Administrator and returns its approval item. */
    private long propose(Map<String, Object> rate) throws Exception {
        MvcResult r = post(ruleAdmin(), RATES, rate);
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(202);
        return body(r).path("approvalId").asLong();
    }

    private JsonNode rows(Session s, String year) throws Exception {
        JsonNode all = body(mvc.perform(userGet(s, RATES)).andReturn());
        List<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode r : all.path("rates")) {
            if (year.equals(r.path("finYear").asString())) {
                out.add(r);
            }
        }
        return json.valueToTree(out);
    }

    @Test
    void SRS_6_11_aRateIsRecordedAtHqAndActivatedByADifferentRuleApprover() throws Exception {
        long approval = propose(rate("2091-92", "FCI_TRUST", "8.25", "2091-04-01", true));
        // Pending: listed as a change, not yet a rate.
        JsonNode list = body(mvc.perform(userGet(ruleAdmin(), RATES)).andReturn());
        assertThat(list.path("pending").findValuesAsString("finYear")).contains("2091-92");
        assertThat(rows(ruleAdmin(), "2091-92")).isEmpty();
        // SRS-G-1: the proposer, also holding Rule Approver, never decides the own proposal.
        Session self = login("su.hq@fci-demo.test", "RULE_APPROVER", "HQ001");
        assertThat(decide(self, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(ruleApprover(), approval, "APPROVE", "IT: agreed", null)).isEqualTo(200);

        JsonNode row = rows(ruleAdmin(), "2091-92").get(0);
        assertThat(row.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(row.path("memberPopulation").asString()).isEqualTo("ACTIVE_AND_DEPUTED");
        assertThat(row.path("declared").asBoolean()).isTrue();
        assertThat(row.path("ratePercent").decimalValue()).isEqualByComparingTo("8.25");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                    + " 'pf_interest_rate' AND entity_key = '2091-92|CPF|FCI_TRUST'"
                                    + " AND field_name = 'rate_percent' AND new_value = '8.25'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void BR_CPF_033_aLaterRateSupersedesTheEarlierAndNoRateSlipsInBeforeIt() throws Exception {
        long first = propose(rate("2092-93", "FCI_TRUST", "8.10", "2092-04-01", true));
        // One change at a time per series: the approval engine holds the second.
        MvcResult second =
                post(ruleAdmin(), RATES, rate("2092-93", "FCI_TRUST", "8.20", "2092-10-01", true));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(error(second)).isEqualTo("WF-409-PENDING-BY");
        assertThat(decide(ruleApprover(), first, "APPROVE", null, null)).isEqualTo(200);

        long later = propose(rate("2092-93", "FCI_TRUST", "8.20", "2092-10-01", true));
        assertThat(decide(ruleApprover(), later, "APPROVE", null, null)).isEqualTo(200);
        JsonNode rows = rows(ruleAdmin(), "2092-93");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("appliesFrom").asString()).isEqualTo("2092-10-01");
        assertThat(rows.get(0).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(rows.get(1).path("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(rows.get(1).path("appliesTo").asString()).isEqualTo("2092-09-30");

        // Never before (or on) the approved rate it would replace.
        for (String from : List.of("2092-10-01", "2092-06-01")) {
            MvcResult early =
                    post(ruleAdmin(), RATES, rate("2092-93", "FCI_TRUST", "8.30", from, true));
            assertThat(early.getResponse().getStatus()).as(from).isEqualTo(409);
            assertThat(error(early)).isEqualTo("CPF-409-RATE-ORDER");
        }
        // The other source of the same year is its own series.
        long epfo = propose(rate("2092-93", "RPFC", "8.65", "2092-04-01", true));
        assertThat(decide(ruleApprover(), epfo, "APPROVE", null, null)).isEqualTo(200);

        // History: the series' approved rates as versions; the end date audited.
        JsonNode history =
                body(
                        mvc.perform(
                                        userGet(
                                                ruleAdmin(),
                                                "/v1/audit/history?entity=pf_interest_rate&key="
                                                        + "2092-93|CPF|FCI_TRUST"))
                                .andReturn());
        assertThat(history.path("screen").asString()).isEqualTo("M-19");
        assertThat(history.path("versions")).hasSize(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                    + " 'pf_interest_rate' AND entity_key = '2092-93|CPF|FCI_TRUST'"
                                    + " AND field_name = 'applies_to' AND new_value = '2092-09-30'",
                                Integer.class))
                .isPositive();
    }

    @Test
    void SRS_6_11_aRateRecordedUndeclaredIsDeclaredOnceAtTheDeclaredRate() throws Exception {
        long recorded = propose(rate("2093-94", "RPFC", "8.00", "2093-04-01", false));
        assertThat(decide(ruleApprover(), recorded, "APPROVE", null, null)).isEqualTo(200);
        JsonNode row = rows(ruleAdmin(), "2093-94").get(0);
        assertThat(row.path("declared").asBoolean()).isFalse();
        assertThat(row.path("memberPopulation").asString()).isEqualTo("INACTIVE");
        long id = row.path("id").asLong();

        MvcResult declared =
                post(
                        ruleAdmin(),
                        RATES + "/" + id + "/declare",
                        Map.of("ratePercent", "8.65", "reason", "IT: EPFO notified"));
        assertThat(declared.getResponse().getStatus()).isEqualTo(202);
        assertThat(
                        decide(
                                ruleApprover(),
                                body(declared).path("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        row = rows(ruleAdmin(), "2093-94").get(0);
        assertThat(row.path("declared").asBoolean()).isTrue();
        assertThat(row.path("ratePercent").decimalValue()).isEqualByComparingTo("8.65");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT old_value FROM audit_event WHERE entity_name ="
                                    + " 'pf_interest_rate' AND entity_key = '2093-94|CPF|RPFC' AND"
                                    + " field_name = 'rate_percent' AND new_value = '8.65'",
                                String.class))
                .isEqualTo("8");

        // A declared rate is not declared again; an unknown rate is not found.
        MvcResult again =
                post(
                        ruleAdmin(),
                        RATES + "/" + id + "/declare",
                        Map.of("ratePercent", "8.70", "reason", "IT: again"));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(error(again)).isEqualTo("CPF-409-DECLARED");
        assertThat(
                        post(
                                        ruleAdmin(),
                                        RATES + "/987654321/declare",
                                        Map.of("ratePercent", "8.70", "reason", "IT"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
    }

    @Test
    void SRS_6_11_aReturnedOrRejectedProposalLeavesNoRate() throws Exception {
        long returned = propose(rate("2094-95", "FCI_TRUST", "8.00", "2094-04-01", true));
        assertThat(decide(ruleApprover(), returned, "RETURN", "IT: wrong board resolution", null))
                .isEqualTo(200);
        long rejected = propose(rate("2094-95", "FCI_TRUST", "8.00", "2094-04-01", true));
        assertThat(decide(ruleApprover(), rejected, "REJECT", "IT: not declared yet", null))
                .isEqualTo(200);
        assertThat(rows(ruleAdmin(), "2094-95")).isEmpty();
        // The same date may be proposed again.
        long again = propose(rate("2094-95", "FCI_TRUST", "8.00", "2094-04-01", true));
        assertThat(decide(ruleApprover(), again, "APPROVE", null, null)).isEqualTo(200);
        assertThat(rows(ruleAdmin(), "2094-95")).hasSize(1);
    }

    @Test
    void BR_CPF_033_eachFieldIsCheckedAtItsBoundary() throws Exception {
        Session admin = ruleAdmin();
        Map<String, Object> badYear = rate("2095-97", "FCI_TRUST", "8", "2095-04-01", true);
        Map<String, Object> outside = rate("2095-96", "FCI_TRUST", "8", "2096-04-01", true);
        Map<String, Object> reversed = rate("2095-96", "FCI_TRUST", "8", "2095-06-01", true);
        reversed.put("appliesTo", "2095-05-31");
        Map<String, Object> tooHigh = rate("2095-96", "FCI_TRUST", "100.01", "2095-04-01", true);
        Map<String, Object> bonusOnEpfo = rate("2095-96", "RPFC", "8", "2095-04-01", true);
        bonusOnEpfo.put("bonusInterestPct", "2.50");
        List<Map<String, Object>> cases = List.of(badYear, outside, reversed, tooHigh, bonusOnEpfo);
        List<String> codes =
                List.of(
                        "CPF-422-FIN-YEAR",
                        "CPF-422-PERIOD",
                        "CPF-422-PERIOD",
                        "CPF-422-RATE",
                        "CPF-422-BONUS");
        for (int i = 0; i < cases.size(); i++) {
            MvcResult r = post(admin, RATES, cases.get(i));
            assertThat(r.getResponse().getStatus()).as(codes.get(i)).isEqualTo(422);
            assertThat(error(r)).isEqualTo(codes.get(i));
        }
        // The request boundary: digits, lists, text and server-assigned fields.
        admin = ruleAdmin();
        Map<String, Object> digits = rate("2095-96", "FCI_TRUST", "1000", "2095-04-01", true);
        Map<String, Object> fund = rate("2095-96", "FCI_TRUST", "8", "2095-04-01", true);
        fund.put("fundType", "NPS");
        Map<String, Object> hindi = rate("2095-96", "FCI_TRUST", "8", "2095-04-01", true);
        hindi.put("applicabilityNote", "सेवानिवृत्ति तक");
        Map<String, Object> forged = rate("2095-96", "FCI_TRUST", "8", "2095-04-01", true);
        forged.put("approvalId", 1);
        for (Map<String, Object> bad : List.of(digits, fund, hindi, forged)) {
            assertThat(post(admin, RATES, bad).getResponse().getStatus())
                    .as(bad.toString())
                    .isEqualTo(400);
        }
        // Bonus interest over the Trust rate is accepted (2.5 % over 9.50 %).
        Map<String, Object> bonus = rate("2095-96", "FCI_TRUST", "9.50", "2095-04-01", true);
        bonus.put("bonusInterestPct", "2.50");
        long ok = propose(bonus);
        assertThat(decide(ruleApprover(), ok, "APPROVE", null, null)).isEqualTo(200);
        assertThat(rows(ruleAdmin(), "2095-96").get(0).path("bonusInterestPct").decimalValue())
                .isEqualByComparingTo("2.5");
    }

    @Test
    void SRS_22_21_onlyTheRuleAdministratorAtHqRecordsAndTheCpfDivisionReads() throws Exception {
        Map<String, Object> r = rate("2096-97", "FCI_TRUST", "8", "2096-04-01", true);
        Session cpf = login("cpf.north@fci-demo.test", "CPF_DIVISION", "NA11");
        assertThat(mvc.perform(userGet(cpf, RATES)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(post(cpf, RATES, r).getResponse().getStatus()).isEqualTo(403);
        assertThat(post(ruleApprover(), RATES, r).getResponse().getStatus()).isEqualTo(403);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(mvc.perform(userGet(maker, RATES)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        Session manager = login("cpf.manager.north@fci-demo.test", "CPF_MANAGER", "NA11");
        assertThat(mvc.perform(userGet(manager, RATES)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void SRS_6_47_theCpfRateKeysAreRulesOfM18AndM19NotConfiguredUntilApproved() throws Exception {
        JsonNode catalogue = body(mvc.perform(userGet(ruleAdmin(), "/v1/rules")).andReturn());
        Map<String, JsonNode> byKey = new HashMap<>();
        for (JsonNode rule : catalogue.path("rules")) {
            byKey.put(rule.path("key").asString(), rule);
        }
        for (String key :
                List.of(
                        "CPF.RATE.EMPLOYEE",
                        "CPF.RATE.EMPLOYER",
                        "CPF.EPS.RATE",
                        "CPF.HIGHERPENSION.RATE")) {
            assertThat(byKey.get(key).path("owningModule").asString()).as(key).isEqualTo("M-18");
            // Demo versions: the employee rate (V040_9_9, module 16 phase 16e-2) and the employer
            // and
            // pension rates (V043_1_9, plan 07 phase 07i part 2); production leaves all unseeded.
            assertThat(byKey.get(key).path("inForce").isNull()).as(key).isFalse();
            assertThat(byKey.get(key).path("orderRequired").asBoolean()).as(key).isTrue();
        }
        assertThat(byKey.get("CPF.VPF.MAX").path("owningModule").asString()).isEqualTo("M-19");
        assertThat(byKey.get("CPF.VPF.MAX").path("inForce").isNull())
                .as("demo VPF maximum")
                .isFalse();
        // SRS-6-9 states the ex-member stop: three years from separation.
        JsonNode stop = byKey.get("CPF.INTEREST.EXMEMBER_YEARS");
        assertThat(stop.path("owningModule").asString()).isEqualTo("M-19");
        assertThat(stop.path("inForce").path("value").decimalValue()).isEqualByComparingTo("3");
        assertThat(stop.path("inForce").path("status").asString()).isEqualTo("ACTIVE");
    }

    /**
     * SRS M-19 (l.2340-2349), SRS-6-45, R-RBAC (V077_20): the CPF Division reads the M-19
     * parameters (rule versions through the M-08 catalogue), but cannot record, end or export one.
     */
    @Test
    void SRS_M19_theCpfDivisionReadsTheParametersButCannotChangeThem() throws Exception {
        Session cpf = login("cpf.north@fci-demo.test", "CPF_DIVISION", "NA11");
        MvcResult catalogue = mvc.perform(userGet(cpf, "/v1/rules")).andReturn();
        assertThat(catalogue.getResponse().getStatus()).isEqualTo(200);
        List<String> keys = body(catalogue).path("rules").findValuesAsString("key");
        assertThat(keys).contains("CPF.VPF.MAX", "CPF.INTEREST.EXMEMBER_YEARS");
        assertThat(
                        mvc.perform(userGet(cpf, "/v1/rules/CPF.VPF.MAX/versions"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        // Read only: no version proposal, no end, no export.
        String later =
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                        .plusYears(60)
                        .toString();
        Map<String, Object> version = new HashMap<>();
        version.put("effectiveFrom", later);
        version.put("value", "12");
        version.put("circularId", 1);
        version.put("reason", "IT: not allowed");
        assertThat(post(cpf, "/v1/rules/CPF.VPF.MAX/versions", version).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(
                        post(
                                        cpf,
                                        "/v1/rules/CPF.VPF.MAX/end",
                                        Map.of("endOn", later, "reason", "IT"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        assertThat(
                        mvc.perform(userGet(cpf, "/v1/rules/export"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        // Unchanged for a role without the grant: the Unit CPF Administrator does not see M-08.
        Session unit = login("cpf.unit.delhi@fci-demo.test", "UNIT_CPF_ADMIN", "DH001");
        assertThat(mvc.perform(userGet(unit, "/v1/rules")).andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }
}
