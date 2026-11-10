package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * D-01 Home - Cycle Status (plan 05 phase 05i; SRS D-01, D-19): a unit user sees its own cycle
 * unit, a Headquarters user the read-only roll-up across the units below; every KPI and blocking
 * condition carries its figure (retro events, interface health, DPS booking-slips, incentive norms
 * counted from their screens' data); the labour wage runs wait for FCI's rules (AWAITING_FCI); no
 * figure ever names an internal build module (owner 2026-10-29); members (eSeva) and roles without
 * D-01 have no access.
 */
class CycleStatusIT extends ApiTestSupport {

    /** SRS D-01: the unit's own cycle, its KPIs, payroll types and blocking panel. */
    // BR-EMPM-009: members without bank details are counted as blocking disbursement.
    // SRS-EH-14: pending HRMS changes that hold the lock are counted, with the link to I-21.
    // SC-05-0048: the D-01 cycle status summary of each instance in scope.
    @Test
    void SRS_15_14_aUnitUserSeesItsOwnCycleWithTheBlockingPanel() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode view = body(mvc.perform(userGet(maker, "/v1/cycle-status")).andReturn());
        assertThat(view.get("scopeCode").asString()).isEqualTo("DH001");
        assertThat(view.get("rollUp").asBoolean()).isFalse();
        assertThat(view.get("units").asInt()).isEqualTo(1);
        long total = 0;
        for (JsonNode n : view.get("byStatus")) {
            total += n.asLong();
        }
        assertThat(total).isEqualTo(1);
        JsonNode kpis = view.get("kpis");
        assertThat(kpis.get("pendingApproval").get("count").isNumber()).isTrue();
        assertThat(kpis.get("retroEvents").get("count").isNumber()).isTrue();
        assertThat(kpis.get("interfaceHealth").get("count").isNumber()).isTrue();
        assertThat(view.get("types")).hasSize(4);
        assertThat(view.get("types").get(0).get("payrollType").asString()).isEqualTo("STAFF");
        assertThat(view.get("types").get(0).get("unavailable").isNull()).isTrue();
        for (int i = 1; i < 4; i++) {
            assertThat(view.get("types").get(i).get("unavailable").asString())
                    .isEqualTo("AWAITING_FCI");
        }
        assertThat(view.toString())
                .as("no internal module reference reaches the screen")
                .doesNotContain("availableWith")
                .doesNotContain("engineModule");
        assertThat(texts(view.get("blockers"), "code"))
                .containsExactly(
                        "NET_PAY_FLOOR",
                        "BANK_DETAILS",
                        "DPS_BOOKING_SLIP",
                        "INCENTIVE_NORMS",
                        "PRE_PAYROLL_CHECK",
                        "HRMS_CHANGE_PENDING");
        assertThat(view.get("blockers").get(1).get("figure").get("count").isNumber()).isTrue();
        assertThat(view.get("blockers").get(5).get("figure").get("count").isNumber())
                .as("I-21 built: pending HRMS changes are counted")
                .isTrue();
        for (int i = 0; i < 6; i++) {
            assertThat(view.get("blockers").get(i).get("figure").get("count").isNumber())
                    .as("blocker " + i + " is counted")
                    .isTrue();
        }
        assertThat(view.get("blockers").get(2).get("fixOn").asString()).isEqualTo("I-02");
        assertThat(view.get("blockers").get(3).get("fixOn").asString()).isEqualTo("M-06");
        assertThat(view.get("blockers").get(5).get("fixOn").asString())
                .as("SRS-EH-14: the pending HRMS changes link to I-21")
                .isEqualTo("I-21");
        JsonNode units = body(mvc.perform(userGet(maker, "/v1/cycle-status/units")).andReturn());
        assertThat(units.get("total").asLong()).isEqualTo(1);
        assertThat(units.get("items").get(0).get("ownUnit").asBoolean()).isTrue();
    }

    /** SRS D-01 alternate flow: Headquarters sees the read-only roll-up across the units. */
    // SC-05-0005 (the read-only consolidated roll-up of the cycle instances), SC-05-0044
    // (Headquarters with consolidated view sees it on D-01).
    @Test
    void SRS_15_14_headquartersSeesTheRollUpAcrossUnits() throws Exception {
        Session hq = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        JsonNode view = body(mvc.perform(userGet(hq, "/v1/cycle-status")).andReturn());
        assertThat(view.get("rollUp").asBoolean()).isTrue();
        assertThat(view.get("units").asInt()).isGreaterThan(1);
        JsonNode units =
                body(mvc.perform(userGet(hq, "/v1/cycle-status/units?size=10")).andReturn());
        assertThat(units.get("total").asLong()).isEqualTo(view.get("units").asLong());
        assertThat(units.get("items").get(0).get("ownUnit").asBoolean())
                .as("own unit first")
                .isTrue();
        assertThat(texts(units.get("items"), "unitCode")).contains("HQ001");
    }

    /**
     * UC-01 verify (SC-05-0097; SRS-15-5, SRS-15-14): the read-only consolidated view of
     * Headquarters shows a locked instance beside the other units' instances, each with its own
     * status, and offers no action (the view has no write method). The unit is one no other IT runs
     * a cycle for this month; its locked instance is created here.
     */
    @Test
    void SC_05_0097_theConsolidatedViewShowsALockedInstanceReadOnlyWithNoLockAuthority()
            throws Exception {
        Session hq = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        String yymm =
                body(mvc.perform(userGet(hq, "/v1/cycle-status")).andReturn())
                        .get("period")
                        .asString()
                        .replace("-", "");
        Map<String, Object> unit =
                jdbc.queryForMap(
                        "SELECT location_id, location_code FROM org_location WHERE location_id ="
                            + " (SELECT MIN(l.location_id) FROM org_location l WHERE"
                            + " l.is_cycle_unit = 'Y' AND l.location_code NOT IN ('HQ001', 'DH001',"
                            + " 'KL001', 'CH001', 'MR001', 'LD001', 'NA11', 'RO-PB') AND NOT EXISTS"
                            + " (SELECT 1 FROM payroll_cycle_instance c WHERE c.org_unit_id ="
                            + " l.location_id AND c.payroll_yymm = ?))",
                        yymm);
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES (?, ?, 'DO', 'IT',"
                        + " 'LOCKED', 'it.fixture')",
                yymm,
                unit.get("LOCATION_ID"));
        String code = (String) unit.get("LOCATION_CODE");
        JsonNode units = all(hq, "/v1/cycle-status/units");
        String locked = null;
        java.util.Set<String> others = new java.util.HashSet<>();
        for (JsonNode u : units) {
            if (code.equals(u.get("unitCode").asString())) {
                locked = u.get("status").asString();
            } else if (!u.get("status").isNull()) {
                others.add(u.get("status").asString());
            }
        }
        assertThat(locked).as("the locked instance is in the roll-up").isEqualTo("LOCKED");
        assertThat(others).as("beside the other instances, each with its own status").isNotEmpty();
        assertThat(
                        mvc.perform(userPost(hq, "/v1/cycle-status", Map.of()))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("read-only: no action on the view")
                .isEqualTo(405);
    }

    /** SRS D-01: members (eSeva) and roles without the screen have no access. */
    // SC-05-0045: members (eSeva) have no access to D-01.
    @Test
    void SRS_15_14_membersHaveNoAccess() throws Exception {
        Session eseva = login("emp.rsharma@fci-demo.test", null, null);
        assertThat(
                        mvc.perform(userGet(eseva, "/v1/cycle-status"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        Session rules = login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
        assertThat(
                        mvc.perform(userGet(rules, "/v1/cycle-status"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }

    /**
     * SRS-15-14: an initiated instance of the period counts in the states of the roll-up, its STAFF
     * engine state and population show by payroll type, and its unit row carries the state; a page
     * past the last unit is empty.
     */
    // SC-05-0053: the computation status by payroll type (engine, population, computed, exceptions,
    // status).
    @Test
    void SRS_15_14_anInitiatedInstanceShowsInTheStatesTheEngineRowAndItsUnit() throws Exception {
        Session hq = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        JsonNode before = body(mvc.perform(userGet(hq, "/v1/cycle-status")).andReturn());
        String yymm = before.get("period").asString().replace("-", "");
        Long unit =
                jdbc.queryForObject(
                        // A unit no other IT runs a cycle for (they use the demo offices named
                        // here), so this instance changes nothing they check.
                        "SELECT MAX(l.location_id) FROM org_location l WHERE l.is_cycle_unit = 'Y'"
                            + " AND l.location_code NOT IN ('HQ001', 'DH001', 'KL001', 'CH001',"
                            + " 'MR001', 'LD001', 'NA11') AND NOT EXISTS (SELECT 1 FROM"
                            + " payroll_cycle_instance c WHERE c.org_unit_id = l.location_id AND"
                            + " c.payroll_yymm = ?)",
                        Long.class,
                        yymm);
        assertThat(unit).as("a cycle unit without an instance this period").isNotNull();
        String code =
                jdbc.queryForObject(
                        "SELECT location_code FROM org_location WHERE location_id = ?",
                        String.class,
                        unit);
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES (?, ?, 'DO', ?,"
                        + " 'INITIATED', 'it.fixture')",
                yymm,
                unit,
                before.get("period").asString().substring(0, 4) + "-XX");
        Long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE org_unit_id = ?"
                                + " AND payroll_yymm = ?",
                        Long.class,
                        unit,
                        yymm);
        jdbc.update(
                "INSERT INTO cycle_engine_status (cycle_instance_id, payroll_type, engine_state,"
                        + " member_count) VALUES (?, 'STAFF', 'FAILED', 7)",
                cycle);

        JsonNode view = body(mvc.perform(userGet(hq, "/v1/cycle-status")).andReturn());
        assertThat(view.get("byStatus").get("INITIATED").asLong())
                .isEqualTo(before.get("byStatus").get("INITIATED").asLong() + 1);
        JsonNode staff = view.get("types").get(0);
        assertThat(staff.get("status").asString()).isEqualTo("FAILED");
        assertThat(staff.get("population").asLong()).isGreaterThanOrEqualTo(7);
        JsonNode units = all(hq, "/v1/cycle-status/units");
        JsonNode row = null;
        for (JsonNode u : units) {
            if (code.equals(u.get("unitCode").asString())) {
                row = u;
            }
        }
        assertThat(row).as(code).isNotNull();
        assertThat(row.get("status").asString()).isEqualTo("INITIATED");
        JsonNode past =
                body(
                        mvc.perform(userGet(hq, "/v1/cycle-status/units?page=999&size=10"))
                                .andReturn());
        assertThat(past.get("items")).isEmpty();
        assertThat(past.get("total").asLong()).isEqualTo(view.get("units").asLong());
    }

    private JsonNode delhi() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        return body(mvc.perform(userGet(maker, "/v1/cycle-status")).andReturn());
    }

    /**
     * SRS-8-13, SRS-4-13, SRS-INT-31 on D-01: a back-dated event still to be decided in Delhi, an
     * open interface exception and a DPS booking-slip of the month not approved by the Depot
     * Manager each add one to their figure; decided, they no longer count.
     */
    @Test
    void SRS_15_14_retroEventsInterfaceHealthAndDpsSlipsAreCounted() throws Exception {
        JsonNode before = delhi();
        long delhi = locationId("DH001");
        Long eventType =
                jdbc.queryForObject("SELECT MIN(event_type_id) FROM arrear_event_type", Long.class);
        jdbc.update(
                "INSERT INTO arrear_event (event_type_id, effective_from, raised_by,"
                        + " scope_location_id) VALUES (?, CURRENT_DATE, 'it.d01', ?)",
                eventType,
                delhi);
        Long event =
                jdbc.queryForObject(
                        "SELECT MAX(arrear_event_id) FROM arrear_event WHERE raised_by = 'it.d01'",
                        Long.class);
        Long anInterface =
                jdbc.queryForObject("SELECT MIN(interface_id) FROM interface_registry", Long.class);
        jdbc.update(
                "INSERT INTO interface_exception (interface_id, rejection_reason) VALUES (?, 'IT:"
                        + " D-01 interface health')",
                anInterface);
        Long exception =
                jdbc.queryForObject(
                        "SELECT MAX(exception_id) FROM interface_exception WHERE rejection_reason"
                                + " = 'IT: D-01 interface health'",
                        Long.class);
        java.util.Map<String, Object> gang =
                jdbc.queryForMap(
                        "SELECT g.gang_id, g.depot_location_id FROM labour_gang g WHERE"
                                + " g.payroll_type = 'DPS_LABOUR' AND g.gang_status = 'ACTIVE'"
                                + " ORDER BY g.gang_id FETCH FIRST 1 ROWS ONLY");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        jdbc.update(
                "INSERT INTO work_slip (work_slip_number, engine_code, gang_id, depot_location_id,"
                        + " work_slip_date, created_by, slip_status) VALUES ('IT-D01-1',"
                        + " 'DPS_LABOUR', ?, ?, ?, 'it.d01', 'SUBMITTED')",
                gang.get("GANG_ID"),
                gang.get("DEPOT_LOCATION_ID"),
                java.sql.Date.valueOf(today));
        try {
            JsonNode after = delhi();
            assertThat(count(after.get("kpis").get("retroEvents")))
                    .isEqualTo(count(before.get("kpis").get("retroEvents")) + 1);
            assertThat(count(after.get("kpis").get("interfaceHealth")))
                    .isEqualTo(count(before.get("kpis").get("interfaceHealth")) + 1);
            assertThat(count(after.get("blockers").get(2).get("figure")))
                    .as("the DPS depot is in Delhi's scope")
                    .isEqualTo(count(before.get("blockers").get(2).get("figure")) + 1);
        } finally {
            // Decided: the figures fall back (rows are kept, never deleted).
            jdbc.update(
                    "UPDATE arrear_event SET event_status = 'REJECTED' WHERE arrear_event_id = ?",
                    event);
            jdbc.update(
                    "UPDATE interface_exception SET remediation_status = 'WRITTEN_OFF',"
                            + " written_off_by = 'it.d01', written_off_reason = 'IT',"
                            + " resolved_at = CURRENT_TIMESTAMP WHERE exception_id = ?",
                    exception);
            jdbc.update(
                    "UPDATE work_slip SET slip_status = 'APPROVED' WHERE work_slip_number ="
                            + " 'IT-D01-1'");
        }
        JsonNode settled = delhi();
        assertThat(count(settled.get("kpis").get("retroEvents")))
                .isEqualTo(count(before.get("kpis").get("retroEvents")));
        assertThat(count(settled.get("kpis").get("interfaceHealth")))
                .isEqualTo(count(before.get("kpis").get("interfaceHealth")));
    }

    private static long count(JsonNode figure) {
        return figure.get("count").asLong();
    }
}
