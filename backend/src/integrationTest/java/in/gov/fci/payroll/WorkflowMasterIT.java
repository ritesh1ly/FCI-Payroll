package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * A-08 Approval Workflow Master (Module 02 F-1; SRS-22-12, 22-16, 22-17, 22-19, BR-APPR-005): the
 * HQ Administrator proposes, a different Payroll Checker approves, and only an approved version is
 * in force, from its date; the version before it ends the day before. Every test uses an action
 * code of its own, so no route other tests use is touched.
 */
class WorkflowMasterIT extends ApiTestSupport {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private Session admin() throws Exception {
        return login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
    }

    private Session checker() throws Exception {
        return login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
    }

    private static String newCode() {
        return "IT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private static Map<String, Object> level(int no, String role) {
        return Map.of(
                "levelNo", no, "roleCode", role, "officeLevel", "PAYING_UNIT", "otpAtLevel", false);
    }

    private static Map<String, Object> route(String code, LocalDate from, Integer hours) {
        Map<String, Object> r = new HashMap<>();
        r.put("actionCode", code);
        r.put("actionName", "IT route " + code);
        r.put("moduleCode", "GENERAL");
        r.put("screenCode", "I-20");
        r.put("officeLevel", "PAYING_UNIT");
        r.put("levelCount", 2);
        r.put("attachmentRequired", false);
        r.put("bulkApprovalAllowed", true);
        r.put("targetTatHours", hours);
        r.put("effectiveFrom", from.toString());
        r.put("levels", List.of(level(1, "PAYROLL_MAKER"), level(2, "PAYROLL_CHECKER")));
        r.put("reason", "IT: route " + code);
        return r;
    }

    private MvcResult propose(Session s, Map<String, Object> route) throws Exception {
        return mvc.perform(userPost(s, "/v1/admin/workflow-actions", route)).andReturn();
    }

    private JsonNode action(Session s, String code) throws Exception {
        for (JsonNode a : all(s, "/v1/admin/workflow-actions")) {
            if (a.get("actionCode").asString().equals(code)) {
                return a;
            }
        }
        return null;
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    /**
     * SRS-G-13 (US-GAP-21): bulk approval is optional on a route. A proposal that does not mention
     * it (every caller written before the flag existed) is read, not refused as malformed, and
     * allows no bulk approval.
     */
    @Test
    void SRS_G_13_aRouteProposedWithoutTheBulkFlagIsReadAsNoBulkApproval() throws Exception {
        Session admin = admin();
        String code = newCode();
        Map<String, Object> route = route(code, TODAY, 48);
        route.remove("bulkApprovalAllowed");
        MvcResult proposed = propose(admin, route);
        assertThat(status(proposed)).as(proposed.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(body(proposed).get("bulkApprovalAllowed").asBoolean()).isFalse();
    }

    @Test
    void SRS_22_19_theHqAdministratorProposesAnotherCheckerApprovesThenItIsInForce()
            throws Exception {
        Session admin = admin();
        String code = newCode();
        MvcResult proposed = propose(admin, route(code, TODAY, 72));
        assertThat(status(proposed)).isEqualTo(202);
        JsonNode version = body(proposed);
        assertThat(version.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(version.get("versionNo").asInt()).isEqualTo(1);
        assertThat(version.get("levels")).hasSize(2);
        long approval = version.get("approvalId").asLong();

        JsonNode listed = action(admin, code);
        assertThat(listed.get("current").isNull()).as("nothing in force before approval").isTrue();
        assertThat(listed.path("pending").get("targetTatHours").asInt()).isEqualTo(72);
        // A second proposal while one waits: refused (BR-APPR-005).
        MvcResult second = propose(admin, route(code, TODAY.plusDays(1), 72));
        assertThat(status(second)).isEqualTo(409);
        assertThat(body(second).get("errorCode").asString()).isEqualTo("WF-409-ROUTE-PENDING");
        // The proposer never decides the own item.
        assertThat(decide(admin, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);

        JsonNode active = action(admin, code).get("current");
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("effectiveFrom").asString()).isEqualTo(TODAY.toString());
        // SRS-G-13 (US-GAP-21): the flag X-01 proposed is in force with the version.
        assertThat(active.get("bulkApprovalAllowed").asBoolean()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'approval_workflow_action' AND entity_key = ?",
                                Long.class,
                                String.valueOf(version.get("id").asLong())))
                .isPositive();
    }

    @Test
    void BR_APPR_005_aNewVersionReplacesTheOneInForceFromItsDateAndARejectedOneNever()
            throws Exception {
        Session admin = admin();
        Session checker = checker();
        String code = newCode();
        long first = body(propose(admin, route(code, TODAY, null))).get("approvalId").asLong();
        assertThat(decide(checker, first, "APPROVE", null, null)).isEqualTo(200);

        // Version 2 from tomorrow: version 1 stays in force today and ends today.
        JsonNode v2 = body(propose(admin, route(code, TODAY.plusDays(1), 24)));
        assertThat(v2.get("versionNo").asInt()).isEqualTo(2);
        assertThat(decide(checker, v2.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode after = action(admin, code);
        assertThat(after.get("current").get("versionNo").asInt()).isEqualTo(1);
        assertThat(after.get("current").get("effectiveTo").asString()).isEqualTo(TODAY.toString());

        // Version 3, rejected: cancelled, never in force; the same date may be proposed again.
        JsonNode v3 = body(propose(admin, route(code, TODAY.plusDays(5), 12)));
        // R-ASCII: a reason the US7ASCII database cannot hold is refused, not stored as "?".
        assertThat(decide(checker, v3.get("approvalId").asLong(), "REJECT", "अस्वीकृत", null))
                .isEqualTo(400);
        assertThat(
                        decide(
                                checker,
                                v3.get("approvalId").asLong(),
                                "REJECT",
                                "Target too short",
                                null))
                .isEqualTo(200);
        List<String> states = new ArrayList<>();
        action(admin, code)
                .get("history")
                .forEach(h -> states.add(h.get("versionNo") + ":" + h.get("status").asString()));
        assertThat(states).contains("3:CANCELLED", "2:ACTIVE");
        assertThat(action(admin, code).get("pending").isNull()).isTrue();
    }

    @Test
    void SRS_19_11_aFutureRouteReplacedBeforeItStartsIsSupersededAndTheApprovalSucceeds()
            throws Exception {
        Session admin = admin();
        Session checker = checker();
        String code = newCode();
        long first = body(propose(admin, route(code, TODAY, null))).get("approvalId").asLong();
        assertThat(decide(checker, first, "APPROVE", null, null)).isEqualTo(200);
        // Version 2 approved to start on day 30.
        JsonNode future = body(propose(admin, route(code, TODAY.plusDays(30), 36)));
        assertThat(decide(checker, future.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        // Version 3 from day 10 replaces version 2 before it starts. The database refuses an end
        // before a start (chk_appr_wf_span): this approval failed before the fix.
        JsonNode sooner = body(propose(admin, route(code, TODAY.plusDays(10), 48)));
        assertThat(decide(checker, sooner.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);

        // The version in force today is listed as "current", the others in the history.
        JsonNode listed = action(admin, code);
        Map<Integer, JsonNode> byVersion = new HashMap<>();
        listed.get("history").forEach(h -> byVersion.put(h.get("versionNo").asInt(), h));
        byVersion.put(listed.get("current").get("versionNo").asInt(), listed.get("current"));
        assertThat(byVersion.get(1).get("effectiveTo").asString())
                .isEqualTo(TODAY.plusDays(9).toString());
        assertThat(byVersion.get(2).get("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(byVersion.get(2).get("effectiveFrom").asString())
                .isEqualTo(TODAY.plusDays(30).toString());
        assertThat(byVersion.get(2).get("effectiveTo").isNull()).as("its dates stay").isTrue();
        assertThat(byVersion.get(3).get("status").asString()).isEqualTo("ACTIVE");
        // The closure of version 2 is audited as a status change (SRS-20-2).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'approval_workflow_action' AND field_name ="
                                        + " 'action_status' AND new_value = 'SUPERSEDED' AND"
                                        + " reason LIKE 'Version 2 replaced%'",
                                Long.class))
                .isPositive();
    }

    @Test
    void SRS_22_17_aRouteBreakingTheRulesIsRefusedWithEveryBrokenRule() throws Exception {
        Map<String, Object> bad = route(newCode(), TODAY.minusDays(1), 0);
        bad.put("levelCount", 3);
        bad.put(
                "levels",
                List.of(
                        level(1, "PAYROLL_MAKER"),
                        level(2, "NO_SUCH_ROLE"),
                        level(3, "PAYROLL_CHECKER")));
        MvcResult refused = propose(admin(), bad);
        assertThat(status(refused)).isEqualTo(422);
        assertThat(body(refused).get("errorCode").asString()).isEqualTo("WF-422-ROUTE");
        List<String> codes = new ArrayList<>();
        body(refused).get("violations").forEach(v -> codes.add(v.get("code").asString()));
        assertThat(codes).contains("LEVEL_ROLE", "TARGET_HOURS", "EFFECTIVE_FROM_PAST");
        // A reason is required.
        Map<String, Object> noReason = route(newCode(), TODAY, null);
        noReason.put("reason", " ");
        MvcResult blank = propose(admin(), noReason);
        assertThat(status(blank)).isEqualTo(400);
        assertThat(body(blank).get("errorCode").asString()).isEqualTo("COM-400-VALIDATION");
    }

    /**
     * SRS-22-11 (l.1036: "Depots and units below a Division hold no approval authority") with X-01
     * l.1123 / l.1127 (US-GAP-126): an approving level (Checker or Approver) at a depot or unit is
     * refused, and nothing is proposed; the deciding office levels are HQ / ZO / RO / DO, the
     * paying unit and the unit running its own cycle instance (target DDL v4.0 CHECK).
     */
    @Test
    void SRS_22_11_anApprovingLevelAtADepotOrUnitIsRefused() throws Exception {
        Session admin = admin();
        for (String depot : new String[] {"DEPOT", "DEPOT_UNIT", "UNIT"}) {
            for (int approving : new int[] {2, 3}) {
                String code = newCode();
                Map<String, Object> r = route(code, TODAY, null);
                List<Map<String, Object>> levels = new ArrayList<>();
                levels.add(level(1, "PAYROLL_MAKER"));
                levels.add(level(2, "PAYROLL_CHECKER"));
                if (approving == 3) {
                    r.put("levelCount", 3);
                    levels.add(level(3, "PAYROLL_CHECKER"));
                }
                Map<String, Object> atDepot = new HashMap<>(levels.get(approving - 1));
                atDepot.put("officeLevel", depot);
                levels.set(approving - 1, atDepot);
                r.put("levels", levels);
                MvcResult refused = propose(admin, r);
                assertThat(status(refused)).as(depot + " at level " + approving).isEqualTo(400);
                // A depot / unit is not an office level a route may name at all: the request is
                // refused as malformed (no such enumeration value), never read as another level.
                assertThat(body(refused).get("errorCode").asString())
                        .as(depot + " at level " + approving)
                        .isEqualTo("COM-400-MALFORMED");
                assertThat(action(admin, code)).as("nothing proposed").isNull();
            }
        }
    }

    /**
     * NEW-G1-09 (US-02-06 AC5, SC-02-0012): SRS-22-12 "Apply a third level only where the matrix
     * names one". A three-level route for an action not on the M-16 list THIRD_LEVEL_ACTION is
     * refused at the level count and nothing is proposed; once the action is on the list (a
     * configuration change, here written by the fixture) the same route is proposed; a two-level
     * route never needs the list. The seed names the matrix's third-level actions.
     */
    @Test
    void SRS_22_12_aThirdLevelOnlyForAnActionTheMatrixNamesOne() throws Exception {
        Session admin = admin();
        String code = newCode();
        Map<String, Object> three = route(code, TODAY, null);
        three.put("levelCount", 3);
        three.put(
                "levels",
                List.of(
                        level(1, "PAYROLL_MAKER"),
                        level(2, "PAYROLL_CHECKER"),
                        level(3, "PAYROLL_CHECKER")));
        MvcResult refused = propose(admin, three);
        assertThat(status(refused)).as(refused.getResponse().getContentAsString()).isEqualTo(422);
        List<String> broken = new ArrayList<>();
        body(refused)
                .get("violations")
                .forEach(
                        v ->
                                broken.add(
                                        v.get("field").asString()
                                                + ":"
                                                + v.get("code").asString()));
        assertThat(broken).containsExactly("levelCount:THIRD_LEVEL_NOT_NAMED");
        assertThat(action(admin, code)).as("nothing proposed").isNull();

        jdbc.update(
                "INSERT INTO reference_value (list_id, value_code, value_label_en, sort_order,"
                        + " effective_from, changed_by, change_reason) SELECT list_id, ?, 'IT"
                        + " third level', 99, ?, 'it', 'IT' FROM reference_list WHERE list_code ="
                        + " 'THIRD_LEVEL_ACTION'",
                code,
                java.sql.Date.valueOf(TODAY));
        cacheChanged(jdbc, "REFERENCE");
        MvcResult allowed = propose(admin, three);
        assertThat(status(allowed)).as(allowed.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(body(allowed).get("levelCount").asInt()).isEqualTo(3);

        assertThat(status(propose(admin, route(newCode(), TODAY, null))))
                .as("two levels: no list needed")
                .isEqualTo(202);
        assertThat(
                        jdbc.queryForList(
                                "SELECT v.value_code FROM reference_value v JOIN reference_list l"
                                        + " ON l.list_id = v.list_id WHERE l.list_code ="
                                        + " 'THIRD_LEVEL_ACTION' AND v.changed_by = 'deploy.seed'",
                                String.class))
                .as("SRS-22-12 / FRS V0.5 §6.6.4 matrix")
                .contains(
                        "I04_SUBMIT",
                        "I22_CREATE",
                        "O05_NET_RECOVERY",
                        "O05_GRATUITY_HQ",
                        "I21_SUBMIT_HQ",
                        "C02_UNDO",
                        "C02_UNFREEZE")
                .doesNotContain("I06_FLOOR", "I20_SUBMIT");
    }

    /**
     * NEW-G1-04 (US-01-05 AC7, SC-01-0030): SRS-G-32 seeds the order-required transaction types.
     * The types whose order gate is the route's mandatory attachment (SRS-22-16) are seeded with
     * it: the I-04 sanction order and its reschedule / foreclosure, the I-06 penalty or recovery
     * order, the I-09 remittance advice, the I-20 approval note or order and the I-23 order or
     * approval note. (The other SRS-G-32 types take the order on their own screen - M-11, I-05,
     * I-21, X-07 - or wait: PENDING G32-LIST.)
     */
    @Test
    void SRS_G_32_theSeededRoutesOfTheOrderRequiredTypesNeedTheirOrder() {
        List<String> required =
                jdbc.queryForList(
                        "SELECT action_code FROM approval_workflow_action WHERE action_status ="
                                + " 'ACTIVE' AND attachment_required = 'Y'",
                        String.class);
        assertThat(required)
                .contains(
                        "I04_LOAN_SANCTION",
                        "I04_LOAN_RESCHEDULE",
                        "I04_LOAN_FORECLOSE",
                        "I06_CREATE",
                        "I06_CANCEL",
                        "I06_REVOKE",
                        "I09_CREATE",
                        "I20_CREATE",
                        "I23_SUBMIT");
    }

    @Test
    void SRS_22_19_onlyTheHqAdministratorProposes() throws Exception {
        Map<String, Object> r = route(newCode(), TODAY, null);
        // A zone administrator: HQ only.
        Session north = login("rbac.admin.north@fci-demo.test", "SYSTEM_ADMINISTRATOR", "NA11");
        MvcResult zone = propose(north, r);
        assertThat(status(zone)).isEqualTo(403);
        // The Payroll Checker views and approves but never proposes; a maker sees nothing.
        Session checker = checker();
        assertThat(status(mvc.perform(userGet(checker, "/v1/admin/workflow-actions")).andReturn()))
                .isEqualTo(200);
        assertThat(status(propose(checker, r))).isEqualTo(403);
        Session maker = login("maker.delhi@fci-demo.test", null, null);
        // OD-125 (Wireframes v6.1 role access, V078_4): X-01 (built A-08) is drawn for the Payroll
        // Maker (read only; changes stay with the Administrator); WF-071 for FCI.
        assertThat(status(mvc.perform(userGet(maker, "/v1/admin/workflow-actions")).andReturn()))
                .isEqualTo(200);
        assertThat(status(propose(maker, r))).isEqualTo(403);
        // OD-125: X-01 (built A-08) is drawn for the Payroll Maker - its reads, never a proposal
        // (above).
        assertThat(
                        status(
                                mvc.perform(userGet(maker, "/v1/admin/workflow-actions/unstaffed"))
                                        .andReturn()))
                .isEqualTo(200);
    }

    @Test
    void SRS_22_12_theSeededRoutesAreListedWithTheirLevels() throws Exception {
        JsonNode a08 = action(admin(), "A08_EDIT");
        assertThat(a08).isNotNull();
        assertThat(a08.get("officeLevel").asString()).isEqualTo("HQ");
        JsonNode current = a08.get("current");
        assertThat(current.get("levelCount").asInt()).isEqualTo(2);
        assertThat(current.get("levels").get(0).get("name").asString()).isEqualTo("MAKER");
        assertThat(current.get("levels").get(1).get("roleCode").asString())
                .isEqualTo("PAYROLL_CHECKER");
        assertThat(current.get("otpAction").isNull()).isTrue();
        assertThat(current.get("bulkApprovalAllowed").asBoolean())
                .as("the DDL default: no seeded route allows bulk approval")
                .isFalse();
        assertThat(current.get("targetTatHours").isNull()).as("no target is seeded").isTrue();
    }
}
