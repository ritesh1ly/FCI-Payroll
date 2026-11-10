package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.payroll.batch.simulation.SimulationJobConfig;
import in.gov.fci.payroll.payroll.orchestration.SimulationEngine;
import in.gov.fci.payroll.payroll.orchestration.SimulationRecords;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * C-09 General (Simulation) Run (plan 05 phase 05i; SRS-15-7, SRS C-09, BR-CYC-007; D-17 to D-21):
 * the Payroll Maker or Checker simulates at once up to the interactive limit and in the background
 * above it; the run record is kept, the result rows are not; a changed input makes the record out
 * of date; roles, office scope, selection, what-if and the unseeded settings are enforced. The demo
 * seed sets the limits to 1000 and 5 (V039_19_9). Assertions name this test's records only.
 */
class SimulationIT extends ApiTestSupport {

    @Autowired private SimulationEngine engine;
    @Autowired private SimulationRecords records;
    @Autowired private SystemParameters parameters;

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private MvcResult run(
            Session s, String population, List<String> members, String whatIf, String ida)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("population", population);
        body.put("members", members);
        body.put("whatIf", whatIf);
        if (ida != null) {
            body.put("daIda", ida);
        }
        return mvc.perform(userPost(s, "/v1/simulations", body)).andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    /** Eligible members of DH001's cycle unit, by employee number. */
    private List<String> delhiMembers(int n) {
        return jdbc.queryForList(
                "SELECT e.emp_num FROM employee e WHERE e.location_id = ? AND e.record_status ="
                        + " 'ACTIVE' AND e.employment_status NOT IN ('SEPARATED', 'DISMISSED')"
                        + " ORDER BY e.emp_num FETCH FIRST "
                        + n
                        + " ROWS ONLY",
                String.class,
                locationId("DH001"));
    }

    /**
     * SRS-15-7, D-17: a member list within the interactive limit is computed at once, as-is and
     * with the what-if DA rate, with its member rows; only the run record is kept (no
     * payroll_result row, results_retained N); the tiles and the list show it.
     */
    // SC-05-0114: a simulation result is never posted, reported, paid or shown to a member - every
    // output reads payroll_result / payroll_result_component, and the run writes neither.
    @Test
    void SRS_15_7_aSmallSimulationIsComputedAtOnceAndOnlyItsRecordIsKept() throws Exception {
        Session maker = maker();
        List<String> members = delhiMembers(2);
        assertThat(members).hasSize(2);
        Long results = jdbc.queryForObject("SELECT COUNT(*) FROM payroll_result", Long.class);
        Long lines =
                jdbc.queryForObject("SELECT COUNT(*) FROM payroll_result_component", Long.class);
        MvcResult done = run(maker, "MEMBER_LIST", members, "DA_RATE", "60");
        assertThat(done.getResponse().getStatus())
                .as(done.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode detail = body(done);
        assertThat(detail.get("run").get("mode").asString()).isEqualTo("INTERACTIVE");
        assertThat(detail.get("run").get("status").asString()).isEqualTo("COMPLETE");
        assertThat(detail.get("run").get("resultsRetained").asBoolean()).isFalse();
        assertThat(detail.get("members")).hasSize(2);
        assertThat(detail.get("members").get(0).get("eis").asString()).isIn(members);
        assertThat(detail.get("whatIf").isNull()).isFalse();
        assertThat(detail.get("current").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payroll_result", Long.class))
                .as("no result row is written")
                .isEqualTo(results);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payroll_result_component", Long.class))
                .as("nor a component line a payslip, report or posting could read")
                .isEqualTo(lines);
        long id = detail.get("run").get("id").asLong();
        // Scoped to THIS run (simulation_run_id): the shared H2 database carries SIMULATION-typed
        // result rows left by other integration tests, so the invariant to check is that THIS
        // small simulation persisted none of its own, not that the whole table is empty.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_result WHERE run_type = 'SIMULATION'"
                                        + " AND simulation_run_id = ?",
                                Long.class,
                                id))
                .as("this simulation wrote no result row of its own")
                .isZero();
        Map<String, Object> record =
                jdbc.queryForMap(
                        "SELECT results_retained, population_count, population_scope,"
                                + " rule_version_id FROM rule_simulation WHERE simulation_id = ?",
                        id);
        assertThat(record).containsEntry("RESULTS_RETAINED", "N");
        assertThat(((Number) record.get("POPULATION_COUNT")).longValue()).isEqualTo(2);
        assertThat((String) record.get("POPULATION_SCOPE"))
                .startsWith("C09;")
                .contains(";DH001;MEMBER_LIST;2;DA_RATE;IDA=60;CDA=-");
        assertThat(record.get("RULE_VERSION_ID")).isNull();

        JsonNode again = body(mvc.perform(userGet(maker, "/v1/simulations/" + id)).andReturn());
        assertThat(again.get("members")).as("rows are never stored").isEmpty();
        assertThat(again.get("current").asBoolean()).isTrue();
        JsonNode list = body(mvc.perform(userGet(maker, "/v1/simulations?size=10")).andReturn());
        assertThat(list.get("items").get(0).get("reference").asString()).isEqualTo("SIM-" + id);
        JsonNode tiles = body(mvc.perform(userGet(maker, "/v1/simulations/tiles")).andReturn());
        assertThat(tiles.get("runsThisCycle").asLong()).isPositive();
        assertThat(tiles.get("mineThisCycle").asLong()).isPositive();
        assertThat(tiles.get("maxSelection").asLong()).isEqualTo(1000);
        assertThat(tiles.get("interactiveLimit").asLong()).isEqualTo(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'rule_simulation' AND entity_key = ?",
                                Long.class,
                                String.valueOf(id)))
                .isPositive();
    }

    /**
     * SRS-15-7, D-18, D-21: the whole office above the interactive limit is queued as job
     * SIMULATION_RUN without an approval and takes no cycle lease; the job keeps the summary; a
     * change of a member's input afterwards makes the record out of date.
     */
    // SC-05-0112 (above the interactive limit the run is queued with progress), SC-05-0113 (a
    // change after the run marks it out of date).
    @Test
    void SRS_15_7_aLargeSimulationRunsInTheBackgroundAndGoesOutOfDateWhenDataChanges()
            throws Exception {
        Session maker = maker();
        assertThat(delhiMembers(6)).as("the demo office is above the limit of 5").hasSize(6);
        Long leases = jdbc.queryForObject("SELECT COUNT(*) FROM cycle_lease", Long.class);
        MvcResult queued = run(maker, "OFFICE", List.of(), "AS_IS", null);
        assertThat(queued.getResponse().getStatus())
                .as(queued.getResponse().getContentAsString())
                .isEqualTo(202);
        JsonNode run = body(queued).get("run");
        assertThat(run.get("mode").asString()).isEqualTo("BACKGROUND");
        assertThat(run.get("status").asString()).isEqualTo("QUEUED");
        long id = run.get("id").asLong();
        long requestId = run.get("jobRequestId").asLong();
        Map<String, Object> job =
                jdbc.queryForMap(
                        "SELECT job_name, job_status, approval_id FROM batch_job_request WHERE"
                                + " job_request_id = ?",
                        requestId);
        assertThat(job)
                .containsEntry("JOB_NAME", "SIMULATION_RUN")
                .containsEntry("JOB_STATUS", "PENDING");
        assertThat(job.get("APPROVAL_ID")).as("no approval").isNull();
        JsonNode waiting = body(mvc.perform(userGet(maker, "/v1/simulations/" + id)).andReturn());
        assertThat(waiting.get("run").get("status").asString()).isEqualTo("QUEUED");
        assertThat(waiting.get("current").isNull()).as("not computed yet").isTrue();
        SimulationRecords.Doc doc = SimulationJobConfig.run(engine, records, id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cycle_lease", Long.class))
                .as("D-18: no cycle lease")
                .isEqualTo(leases);
        assertThat(doc.done()).isGreaterThanOrEqualTo(6);
        JsonNode detail = body(mvc.perform(userGet(maker, "/v1/simulations/" + id)).andReturn());
        assertThat(detail.get("run").get("status").asString()).isEqualTo("COMPLETE");
        assertThat(detail.get("asIs").isNull()).isFalse();
        assertThat(detail.get("current").asBoolean()).isTrue();

        String member = delhiMembers(1).get(0);
        jdbc.update(
                "UPDATE employee SET current_basic = current_basic + 1 WHERE emp_num = ? AND"
                        + " location_id = ?",
                member,
                locationId("DH001"));
        try {
            JsonNode stale = body(mvc.perform(userGet(maker, "/v1/simulations/" + id)).andReturn());
            assertThat(stale.get("current").asBoolean()).as("data changed since the run").isFalse();
        } finally {
            jdbc.update(
                    "UPDATE employee SET current_basic = current_basic - 1 WHERE emp_num = ? AND"
                            + " location_id = ?",
                    member,
                    locationId("DH001"));
        }
    }

    /**
     * SRS C-09 access: Finance has no access, the Administrator views but may not run, another
     * office's record is not found; a member outside the office, a duplicate, a missing what-if and
     * an unconfigured setting are refused.
     */
    // SC-05-0116: the population selection up to the configured maximum.
    @Test
    void SRS_15_7_rolesScopeSelectionAndSettingsAreEnforced() throws Exception {
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        assertThat(
                        mvc.perform(userGet(finance, "/v1/simulations/tiles"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        assertThat(
                        mvc.perform(userGet(admin, "/v1/simulations/tiles"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(run(admin, "OFFICE", List.of(), "AS_IS", null).getResponse().getStatus())
                .isEqualTo(403);

        Session maker = maker();
        long mine =
                body(run(maker, "MEMBER", delhiMembers(1), "AS_IS", null))
                        .get("run")
                        .get("id")
                        .asLong();
        assertThat(
                        mvc.perform(userGet(admin, "/v1/simulations/" + mine))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("another office's record")
                .isEqualTo(404);

        String kolkata =
                jdbc.queryForObject(
                        "SELECT MIN(emp_num) FROM employee WHERE location_id = ?",
                        String.class,
                        locationId("KL001"));
        MvcResult outside = run(maker, "MEMBER", List.of(kolkata), "AS_IS", null);
        assertThat(code(outside)).isEqualTo("SIM-422-MEMBER");
        String one = delhiMembers(1).get(0);
        assertThat(code(run(maker, "MEMBER_LIST", List.of(one, one), "AS_IS", null)))
                .isEqualTo("SIM-422-SELECTION");
        assertThat(code(run(maker, "MEMBER", delhiMembers(2), "AS_IS", null)))
                .isEqualTo("SIM-422-SELECTION");
        assertThat(code(run(maker, "MEMBER", List.of(one), "DA_RATE", null)))
                .isEqualTo("SIM-422-WHAT-IF");
        assertThat(code(run(maker, "MEMBER", List.of(one), "AS_IS", "50")))
                .isEqualTo("SIM-422-WHAT-IF");

        jdbc.update(
                "UPDATE rule_version SET approval_state = 'DRAFT' WHERE created_by ="
                    + " 'seed.sysadmin' AND rule_id = (SELECT rule_id FROM rule_definition WHERE"
                    + " rule_key = 'SIMULATION_INTERACTIVE_LIMIT')");
        rulesChanged();
        parameters.evict();
        try {
            MvcResult refused = run(maker, "MEMBER", List.of(one), "AS_IS", null);
            assertThat(code(refused)).isEqualTo("SIM-422-NOT-CONFIGURED");
            assertThat(body(refused).get("args").get(0).asString())
                    .isEqualTo("SIMULATION_INTERACTIVE_LIMIT");
            JsonNode tiles = body(mvc.perform(userGet(maker, "/v1/simulations/tiles")).andReturn());
            assertThat(tiles.get("interactiveLimit").isNull()).isTrue();
        } finally {
            jdbc.update(
                    "UPDATE rule_version SET approval_state = 'APPROVED' WHERE created_by ="
                            + " 'seed.sysadmin' AND rule_id = (SELECT rule_id FROM rule_definition"
                            + " WHERE rule_key = 'SIMULATION_INTERACTIVE_LIMIT')");
            rulesChanged();
            parameters.evict();
        }
    }

    /**
     * Sets a demo C-09 setting (seed V039_19_9) for the duration of {@code body}, then restores it.
     */
    private void withSetting(String key, long value, ThrowingRunnable body) throws Exception {
        String sql =
                "UPDATE rule_version SET scalar_value = ? WHERE created_by = 'seed.sysadmin' AND"
                        + " rule_id = (SELECT rule_id FROM rule_definition WHERE rule_key = ?)";
        Long before =
                jdbc.queryForObject(
                        "SELECT scalar_value FROM rule_version WHERE created_by = 'seed.sysadmin'"
                                + " AND rule_id = (SELECT rule_id FROM rule_definition WHERE"
                                + " rule_key = ?)",
                        Long.class,
                        key);
        jdbc.update(sql, value, key);
        parameters.evict();
        try {
            body.run();
        } finally {
            jdbc.update(sql, before, key);
            parameters.evict();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * SRS-15-7: an office above the configured maximum is refused; an office within the interactive
     * limit is computed at once with its member rows; more than five unknown numbers are named with
     * a count; a body without members is an empty selection; an abandoned or failed background run
     * is closed on its record, a finished one is not.
     */
    @Test
    void SRS_15_7_limitsUnknownMembersAndAbandonedOrFailedRunsAreHandled() throws Exception {
        Session maker = maker();
        withSetting(
                "SIMULATION_MAX_SELECTION",
                3,
                () -> {
                    MvcResult refused = run(maker, "OFFICE", List.of(), "AS_IS", null);
                    assertThat(code(refused)).isEqualTo("SIM-422-SELECTION");
                    assertThat(body(refused).get("args").get(0).asString())
                            .startsWith("TOO_MANY (");
                });
        withSetting(
                "SIMULATION_INTERACTIVE_LIMIT",
                1000,
                () -> {
                    JsonNode office = body(run(maker, "OFFICE", List.of(), "AS_IS", null));
                    assertThat(office.get("run").get("mode").asString()).isEqualTo("INTERACTIVE");
                    assertThat(office.get("members").size()).isGreaterThanOrEqualTo(6);
                    assertThat(office.get("members").get(0).get("eis").asString()).isNotBlank();
                });
        List<String> unknown = List.of("ZZ1", "ZZ2", "ZZ3", "ZZ4", "ZZ5", "ZZ6", "ZZ7");
        MvcResult missing = run(maker, "MEMBER_LIST", unknown, "AS_IS", null);
        assertThat(code(missing)).isEqualTo("SIM-422-MEMBER");
        assertThat(body(missing).get("args").get(0).asString())
                .isEqualTo("ZZ1, ZZ2, ZZ3, ZZ4, ZZ5 (+2)");
        assertThat(code(run(maker, "MEMBER", null, "AS_IS", null))).isEqualTo("SIM-422-SELECTION");

        JsonNode first = body(run(maker, "OFFICE", List.of(), "AS_IS", null)).get("run");
        JsonNode second = body(run(maker, "OFFICE", List.of(), "AS_IS", null)).get("run");
        assertThat(records.abandoned(first.get("jobRequestId").asLong())).isEqualTo(1);
        assertThat(records.abandoned(first.get("jobRequestId").asLong()))
                .as("already closed")
                .isZero();
        JsonNode abandoned =
                body(
                        mvc.perform(userGet(maker, "/v1/simulations/" + first.get("id").asLong()))
                                .andReturn());
        assertThat(abandoned.get("run").get("status").asString()).isEqualTo("FAILED");
        assertThat(abandoned.get("run").get("failure").asString()).isEqualTo("ABANDONED");
        long secondId = second.get("id").asLong();
        records.fail(secondId, records.find(secondId).orElseThrow().doc(), "node lost");
        assertThat(records.find(secondId).orElseThrow().doc().failure()).isEqualTo("node lost");
        assertThat(records.abandoned(second.get("jobRequestId").asLong()))
                .as("a failed run is not open any more")
                .isZero();
    }
}
