package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 21 phase 21c (plan docs/plan/21-dps-labour.md D21-4; SRS-4-11, SRS-4-2, M-05): the demo
 * DPS set (SRS-4-11 values, Interim Relief and GoI rate not configured) reads on M-05; the Rule
 * Administrator at Headquarters proposes a set with every DPS designation's day rate, dated after
 * the latest set, and the Rule Approver approves it, which ends the set before; a second proposal
 * while one waits, a missing designation, a date not after the latest and a Labour Dealing Hand
 * proposing are refused.
 *
 * <p>Demo data V058_9 (DPS designations) and V059_9 (the 2023-10-01 set).
 */
class LabourRateIT extends ApiTestSupport {

    private Session admin() throws Exception {
        return login("tax.ruleadmin@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
    }

    private Session approver() throws Exception {
        return login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
    }

    private JsonNode sets(Session s) throws Exception {
        return body(mvc.perform(userGet(s, "/v1/labour/rate-sets?payrollType=DPS_LABOUR&size=50"))
                        .andReturn())
                .get("items");
    }

    private List<Map<String, Object>> rates(Session s, String... skip) throws Exception {
        JsonNode d =
                body(
                        mvc.perform(
                                        userGet(
                                                s,
                                                "/v1/labour/rate-sets/designations?payrollType=DPS_LABOUR"))
                                .andReturn());
        List<Map<String, Object>> out = new ArrayList<>();
        d.forEach(
                x -> {
                    if (!List.of(skip).contains(x.get("code").asString())) {
                        out.add(Map.of("designationId", x.get("id").asLong(), "mgw", 820));
                    }
                });
        return out;
    }

    private MvcResult propose(Session s, LocalDate from, List<Map<String, Object>> rates)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("payrollType", "DPS_LABOUR");
        body.put("effectiveFrom", from.toString());
        body.put("rates", rates);
        body.put("interimRelief", 20);
        body.put("goiRate", 880);
        body.put("goiRateSource", "GoI CLC notification 2026");
        body.put("comparisonGrain", "PER_DAY");
        body.put("reason", "IT: DPS rate revision");
        return mvc.perform(userPost(s, "/v1/labour/rate-sets", body)).andReturn();
    }

    // SC-21-0011, SC-21-0024, SC-21-0025, SC-21-0026
    @Test
    void SRS_4_11_aDpsRateSetIsProposedApprovedAndEndsTheSetBefore() throws Exception {
        Session admin = admin();
        JsonNode before = sets(admin);
        JsonNode seed = null;
        for (JsonNode s : before) {
            if ("2023-10-01".equals(s.get("effectiveFrom").asString())) {
                seed = s;
            }
        }
        assertThat(seed).as("the demo set").isNotNull();
        assertThat(seed.get("rates")).hasSize(4);
        assertThat(seed.get("interimRelief").isNull()).isTrue();
        assertThat(seed.get("goiRate").isNull()).isTrue();
        Map<String, Integer> byRole = new HashMap<>();
        seed.get("rates")
                .forEach(
                        r ->
                                byRole.put(
                                        r.get("gangRole").asString(),
                                        r.get("mgw").decimalValue().intValueExact()));
        assertThat(byRole)
                .containsEntry("SARDAR", 799)
                .containsEntry("MANDAL", 786)
                .containsEntry("HANDLING", 781)
                .containsEntry("ANCILLARY", 751);

        LocalDate from = LocalDate.of(2026, 1, 1);
        assertThat(body(propose(admin, from, rates(admin, "DANC"))).get("args").get(0).asString())
                .isEqualTo("RATES");
        assertThat(
                        body(propose(admin, LocalDate.of(2023, 10, 1), rates(admin)))
                                .get("args")
                                .get(0)
                                .asString())
                .isEqualTo("EFFECTIVE_BEFORE_LATEST");
        assertThat(
                        propose(
                                        login(
                                                "ldh.depot@fci-demo.test",
                                                "LABOUR_DEALING_HAND",
                                                "DP-DL01"),
                                        from,
                                        rates(admin))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);

        MvcResult sent = propose(admin, from, rates(admin));
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(sent).get("id").asLong();
        assertThat(
                        body(propose(admin, from.plusMonths(1), rates(admin)))
                                .get("args")
                                .get(0)
                                .asString())
                .as("one proposal waits at a time")
                .isEqualTo("PENDING");
        assertThat(decide(admin, approval, "APPROVE", null, null))
                .as("the maker never approves")
                .isGreaterThanOrEqualTo(403);
        assertThat(decide(approver(), approval, "APPROVE", null, null)).isEqualTo(200);

        JsonNode after = sets(admin);
        String seedTo = null;
        String newState = null;
        for (JsonNode s : after) {
            if ("2023-10-01".equals(s.get("effectiveFrom").asString())) {
                seedTo = s.get("effectiveTo").asString();
            }
            if (from.toString().equals(s.get("effectiveFrom").asString())) {
                newState = s.get("approvalState").asString();
                assertThat(s.get("interimRelief").decimalValue()).isEqualByComparingTo("20");
                assertThat(s.get("comparisonGrain").asString()).isEqualTo("PER_DAY");
                assertThat(s.get("versionNo").asInt()).isEqualTo(2);
            }
        }
        assertThat(newState).isEqualTo("APPROVED");
        assertThat(seedTo).isEqualTo(from.minusDays(1).toString());
    }

    @Test
    void SRS_4_11_onlyALabourPayrollWithRatesHeldIsListed() throws Exception {
        Session admin = admin();
        // The NWNP rates are depot-wise rate sets of their own (/v1/labour/nwnp-rate-sets).
        MvcResult nwnp =
                mvc.perform(userGet(admin, "/v1/labour/rate-sets?payrollType=NWNP_LABOUR"))
                        .andReturn();
        assertThat(nwnp.getResponse().getStatus()).isEqualTo(422);
        assertThat(
                        mvc.perform(userGet(admin, "/v1/labour/rate-sets?payrollType=CASUAL"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }

    private MvcResult proposeDept(
            Session s, LocalDate from, List<Map<String, Object>> rates, Map<String, Object> extra)
            throws Exception {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("payrollType", "DEPT_LABOUR");
        body.put("effectiveFrom", from.toString());
        body.put("rates", rates);
        body.put("reason", "IT: Departmental Basic wage and DA");
        return mvc.perform(userPost(s, "/v1/labour/rate-sets", body)).andReturn();
    }

    private List<Map<String, Object>> deptRates(Session s, Object basic, Object da)
            throws Exception {
        JsonNode d =
                body(
                        mvc.perform(
                                        userGet(
                                                s,
                                                "/v1/labour/rate-sets/designations?payrollType=DEPT_LABOUR"))
                                .andReturn());
        List<Map<String, Object>> out = new ArrayList<>();
        d.forEach(
                x -> {
                    Map<String, Object> r = new HashMap<>();
                    r.put("designationId", x.get("id").asLong());
                    r.put("basicWage", basic);
                    r.put("da", da);
                    out.add(r);
                });
        return out;
    }

    /**
     * SRS-3-13 (US-GAP-61): the Departmental Labour Basic wage and DA as a dated set on M-05: no
     * set is seeded (OQ-80); the Rule Administrator proposes every Departmental designation's Basic
     * wage and DA, the Rule Approver approves it and the set before ends the day before; a set
     * missing a Basic wage, with DPS settings, or dated before the latest is refused.
     */
    @Test
    void SRS_3_13_aDepartmentalBasicWageAndDaSetIsProposedApprovedAndEndsTheSetBefore()
            throws Exception {
        Session admin = admin();
        JsonNode none =
                body(
                        mvc.perform(userGet(admin, "/v1/labour/rate-sets?payrollType=DEPT_LABOUR"))
                                .andReturn());
        assertThat(none.get("items")).as("unseeded until FCI gives the values").isEmpty();
        assertThat(deptRates(admin, 500, 100)).isNotEmpty();
        LocalDate first = LocalDate.of(2091, 1, 1);
        assertThat(
                        body(proposeDept(admin, first, deptRates(admin, null, 100), Map.of()))
                                .get("args")
                                .get(0)
                                .asString())
                .isEqualTo("BASIC_WAGE");
        assertThat(
                        body(proposeDept(
                                        admin,
                                        first,
                                        deptRates(admin, 500, 100),
                                        Map.of("interimRelief", 20)))
                                .get("args")
                                .get(0)
                                .asString())
                .isEqualTo("SETTINGS");
        MvcResult sent = proposeDept(admin, first, deptRates(admin, 500, 100), Map.of());
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(decide(approver(), body(sent).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        assertThat(
                        body(proposeDept(
                                        admin,
                                        first.minusDays(1),
                                        deptRates(admin, 510, 105),
                                        Map.of()))
                                .get("args")
                                .get(0)
                                .asString())
                .isEqualTo("EFFECTIVE_BEFORE_LATEST");
        LocalDate second = first.plusMonths(6);
        MvcResult revised = proposeDept(admin, second, deptRates(admin, 520, 110), Map.of());
        assertThat(revised.getResponse().getStatus()).isEqualTo(202);
        assertThat(decide(approver(), body(revised).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode after =
                body(mvc.perform(userGet(admin, "/v1/labour/rate-sets?payrollType=DEPT_LABOUR"))
                                .andReturn())
                        .get("items");
        assertThat(after).hasSize(2);
        assertThat(after.get(0).get("effectiveFrom").asString()).isEqualTo(second.toString());
        assertThat(after.get(0).get("rates").get(0).get("basicWage").decimalValue())
                .isEqualByComparingTo("520");
        assertThat(after.get(0).get("rates").get(0).get("da").decimalValue())
                .isEqualByComparingTo("110");
        assertThat(after.get(0).get("rates").get(0).get("mgw").isNull()).isTrue();
        assertThat(after.get(1).get("effectiveTo").asString())
                .isEqualTo(second.minusDays(1).toString());
    }
}
