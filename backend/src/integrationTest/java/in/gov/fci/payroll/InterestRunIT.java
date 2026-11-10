package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * C-12 CPF Interest Run end to end (docs/plan/07-cpf.md phase 07e; SRS-6-45, SRS-6-46, SRS-6-11,
 * SRS-6-9): the Manager (CPF) of the North Zone runs and re-runs the zone's interest, each run
 * audited; the not-yet-calculated report; the refusals (rates, scope, role, office); the year-end
 * freeze decided by the AGM (CPF) without OTP, after which nothing more runs. Each test works on a
 * far-future financial year of its own (the 2200s: CpfLedgerIT holds the 2090s, OpeningBalanceIT
 * the 2080s, as the integration tests share one database), so the ledgers and runs never meet.
 */
class InterestRunIT extends ApiTestSupport {

    private static final String A = "104882";
    private static final String B = "105461";

    private Session manager() throws Exception {
        return login("cpf.manager.north@fci-demo.test", "CPF_MANAGER", "NA11");
    }

    private Session agm() throws Exception {
        return login("agm.cpf.north@fci-demo.test", "AGM_CPF", "NA11");
    }

    private long member(String eis) {
        return jdbc.queryForObject(
                "SELECT e.employee_id FROM employee e JOIN org_zone z ON z.zone_id ="
                        + " e.parent_zone_id WHERE e.emp_num = ? AND z.zone_code = 'NA11'",
                Long.class,
                eis);
    }

    /** A North Zone ledger of {@code year} in {@code status}, with an interest end date or none. */
    private void ledger(String eis, String year, String status, String interestEnd) {
        jdbc.update(
                "INSERT INTO employee_pf_header (employee_id, fin_year, zone_id,"
                    + " opening_balance_employee, closing_balance_employee, opening_balance_source,"
                    + " member_status, interest_end_date, interest_end_reason) VALUES (?, ?,"
                    + " (SELECT zone_id FROM org_zone WHERE zone_code = 'NA11'), 1000, 1000,"
                    + " 'MIGRATION', ?, ?, ?)",
                member(eis),
                year,
                status,
                interestEnd == null ? null : java.sql.Date.valueOf(interestEnd),
                interestEnd == null ? null : "EX_MEMBER_3_YEARS");
    }

    /** An approved M-19 rate of {@code year}, declared or not. */
    private void rate(String year, String source, boolean declared) {
        jdbc.update(
                "INSERT INTO pf_interest_rate (fin_year, fund_type, rate_source, member_population,"
                    + " rate_percent, applies_from, is_declared) VALUES (?, 'CPF', ?, ?, 8.25, ?,"
                    + " ?)",
                year,
                source,
                "FCI_TRUST".equals(source) ? "ACTIVE_AND_DEPUTED" : "INACTIVE",
                java.sql.Date.valueOf(year.substring(0, 4) + "-04-01"),
                declared ? "Y" : "N");
    }

    private MvcResult run(Session s, String year, String scope, String eis, String reason)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("finYear", year);
        body.put("scope", scope);
        if (eis != null) {
            body.put("eis", eis);
        }
        body.put("reason", reason);
        return mvc.perform(userPost(s, "/v1/cpf/interest-runs", body)).andReturn();
    }

    private MvcResult freeze(Session s, String year) throws Exception {
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/cpf/interest-runs/freeze",
                                Map.of("finYear", year, "reason", "IT: year-end freeze")))
                .andReturn();
    }

    private JsonNode summary(Session s, String year) throws Exception {
        return body(mvc.perform(userGet(s, "/v1/cpf/interest-runs?finYear=" + year)).andReturn());
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    @Test
    void SRS_6_45_runsAndReRunsAreEachAuditedAndTheMembersNotCalculatedAreListed()
            throws Exception {
        String year = "2200-01";
        ledger(A, year, "ACTIVE", null);
        ledger(B, year, "EX_MEMBER", "2090-12-31");
        rate(year, "FCI_TRUST", true);
        rate(year, "RPFC", true);
        Session manager = manager();

        MvcResult first = run(manager, year, "ALL_MEMBERS", null, "IT: first calculation");
        assertThat(first.getResponse().getStatus())
                .as(first.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode one = body(first);
        assertThat(one.path("run").asInt()).isEqualTo(1);
        assertThat(one.path("members").asLong()).isEqualTo(2);
        // Q-072: the method is open, so no member is calculated and nothing is credited.
        assertThat(one.path("calculated").asLong()).isZero();
        assertThat(one.path("notCalculated").asLong()).isEqualTo(2);
        assertThat(one.path("stopped").asLong()).isEqualTo(1);

        JsonNode two = body(run(manager, year, "INDIVIDUAL", A, "IT: re-run for one member"));
        assertThat(two.path("run").asInt()).isEqualTo(2);
        assertThat(two.path("eis").asString()).isEqualTo(A);
        assertThat(two.path("members").asLong()).isEqualTo(1);

        JsonNode tiles = summary(manager, year);
        assertThat(tiles.path("zone").asString()).isEqualTo("NA11");
        assertThat(tiles.path("members").asLong()).isEqualTo(2);
        assertThat(tiles.path("notYetCalculated").asLong()).isEqualTo(2);
        assertThat(tiles.path("reRuns").asInt()).isEqualTo(1);
        assertThat(tiles.path("freezeStatus").asString()).isEqualTo("OPEN");
        assertThat(tiles.path("runs")).hasSize(2);
        assertThat(tiles.path("runs").get(0).path("initiatedBy").asString())
                .isEqualTo("cpf.manager.north@fci-demo.test");
        assertThat(tiles.path("runs").get(0).path("reason").asString())
                .isEqualTo("IT: re-run for one member");

        JsonNode report = all(manager, "/v1/cpf/interest-runs/not-calculated?finYear=" + year);
        assertThat(report).hasSize(2);
        Map<String, JsonNode> byEis = new HashMap<>();
        report.forEach(n -> byEis.put(n.path("eis").asString(), n));
        assertThat(byEis.get(A).path("reason").asString())
                .isEqualTo("INTEREST_METHOD_NOT_CONFIGURED");
        assertThat(byEis.get(B).path("interestStopped").asBoolean()).isTrue();
        assertThat(byEis.get(B).path("memberStatus").asString()).isEqualTo("EX_MEMBER");

        assertThat(
                        jdbc.queryForList(
                                "SELECT run_number, run_scope, initiated_by FROM cpf_interest_run"
                                        + " WHERE fin_year = ? ORDER BY run_number",
                                year))
                .hasSize(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'cpf_interest_run' AND entity_key LIKE ?",
                                Integer.class,
                                year + "|NA11|%"))
                .isEqualTo(2);
        // The CPF Division reads the same year.
        Session division = login("cpf.north@fci-demo.test", "CPF_DIVISION", "NA11");
        assertThat(summary(division, year).path("runs")).hasSize(2);
    }

    @Test
    void SRS_6_45_aRunWithoutItsDeclaredRatesOrWithTheWrongScopeIsRefused() throws Exception {
        String year = "2202-03";
        ledger(A, year, "ACTIVE", null);
        ledger(B, year, "EX_MEMBER", null);
        Session manager = manager();
        Map<String, String> refused = new HashMap<>();
        refused.put("missing", error(run(manager, year, "ALL_MEMBERS", null, "IT")));
        rate(year, "FCI_TRUST", true);
        rate(year, "RPFC", false);
        refused.put("undeclared", error(run(manager, year, "ALL_MEMBERS", null, "IT")));
        refused.put("scope", error(run(manager, year, "INDIVIDUAL", null, "IT")));
        refused.put("scope2", error(run(manager, year, "ALL_MEMBERS", A, "IT")));
        assertThat(refused)
                .containsEntry("missing", "CPF-422-RATE-MISSING")
                .containsEntry("undeclared", "CPF-409-RATE-UNDECLARED")
                .containsEntry("scope", "CPF-422-RUN-SCOPE")
                .containsEntry("scope2", "CPF-422-RUN-SCOPE");
        // A member of the zone whose state needs only the declared FCI rate runs on its own.
        assertThat(
                        run(manager, year, "INDIVIDUAL", A, "IT: active member")
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);
        // A member with no ledger in the year, and one outside the zone.
        assertThat(error(run(manager, "2203-04", "INDIVIDUAL", A, "IT")))
                .isEqualTo("CPF-422-NO-LEDGER");
        String kolkata =
                jdbc.queryForObject(
                        "SELECT MIN(e.emp_num) FROM employee e JOIN org_location l ON l.location_id"
                                + " = e.location_id WHERE l.location_code = 'KL001'",
                        String.class);
        assertThat(run(manager, year, "INDIVIDUAL", kolkata, "IT").getResponse().getStatus())
                .isEqualTo(404);
        // The request boundary: no reason, a reason the US7ASCII database cannot hold, a bad year.
        assertThat(run(manager, year, "ALL_MEMBERS", null, " ").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(run(manager, year, "ALL_MEMBERS", null, "पुनः").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(run(manager, "2202-04", "ALL_MEMBERS", null, "IT").getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void SRS_22_21_onlyTheManagerCpfRunsOrAsksForTheFreeze() throws Exception {
        String year = "2204-05";
        Session division = login("cpf.north@fci-demo.test", "CPF_DIVISION", "NA11");
        assertThat(run(division, year, "ALL_MEMBERS", null, "IT").getResponse().getStatus())
                .isEqualTo(403);
        assertThat(freeze(division, year).getResponse().getStatus()).isEqualTo(403);
        Session agm = agm();
        assertThat(run(agm, year, "ALL_MEMBERS", null, "IT").getResponse().getStatus())
                .isEqualTo(403);
        assertThat(freeze(agm, year).getResponse().getStatus()).isEqualTo(403);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        // OD-125 (Wireframes v6.1 role access, V078_4): C-12 is drawn for the
                        // Payroll Maker (read only; the CPF Manager runs); WF-071 for FCI.
                        mvc.perform(userGet(maker, "/v1/cpf/interest-runs?finYear=" + year))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    @Test
    void SRS_6_46_theAgmCpfFreezesWithoutOtpAndNothingRunsAfter() throws Exception {
        String year = "2206-07";
        ledger(A, year, "ACTIVE", null);
        rate(year, "FCI_TRUST", true);
        Session manager = manager();
        assertThat(
                        run(manager, year, "ALL_MEMBERS", null, "IT: final calculation")
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);

        MvcResult requested = freeze(manager, year);
        assertThat(requested.getResponse().getStatus())
                .as(requested.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(requested).path("approvalId").asLong();
        assertThat(summary(manager, year).path("freezeStatus").asString()).isEqualTo("PENDING");
        // One request at a time; the maker never decides it.
        assertThat(freeze(manager(), year).getResponse().getStatus()).isEqualTo(409);
        assertThat(decide(manager(), approval, "APPROVE", null, null)).isEqualTo(403);
        // No OTP: the AGM (CPF) approves in one step.
        assertThat(decide(agm(), approval, "APPROVE", null, null)).isEqualTo(200);

        Map<String, Object> frozen =
                jdbc.queryForMap("SELECT * FROM cpf_interest_freeze WHERE fin_year = ?", year);
        assertThat(frozen.get("FROZEN_BY")).isEqualTo("agm.cpf.north@fci-demo.test");
        assertThat(((Number) frozen.get("PRECEDING_RUN_COUNT")).intValue()).isEqualTo(1);
        assertThat(frozen.get("FINAL_RUN_ID")).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT interest_frozen_flag FROM employee_pf_header WHERE"
                                        + " employee_id = ? AND fin_year = ?",
                                String.class,
                                member(A),
                                year))
                .isEqualTo("Y");
        JsonNode tiles = summary(manager(), year);
        assertThat(tiles.path("freezeStatus").asString()).isEqualTo("FROZEN");
        assertThat(tiles.path("frozenBy").asString()).isEqualTo("agm.cpf.north@fci-demo.test");
        assertThat(error(run(manager(), year, "ALL_MEMBERS", null, "IT: too late")))
                .isEqualTo("CPF-409-INTEREST-FROZEN");
        assertThat(error(freeze(manager(), year))).isEqualTo("CPF-409-INTEREST-FROZEN");
        // The freeze is never removed.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM cpf_interest_freeze WHERE fin_year = ?", year))
                .hasMessageContaining("FCIPAY-CPF-001");
    }

    @Test
    void SRS_6_46_aRejectedFreezeLeavesTheYearOpen() throws Exception {
        String year = "2208-09";
        ledger(A, year, "ACTIVE", null);
        long approval = body(freeze(manager(), year)).path("approvalId").asLong();
        assertThat(decide(agm(), approval, "REJECT", "IT: runs not complete", null)).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT 1 FROM cpf_interest_freeze WHERE fin_year = ?", year))
                .isEmpty();
        JsonNode tiles = summary(manager(), year);
        assertThat(tiles.path("freezeStatus").asString()).isEqualTo("OPEN");
        assertThat(tiles.path("runs")).isEmpty();
        assertThat(tiles.path("members").asLong()).isEqualTo(1);
    }
}
