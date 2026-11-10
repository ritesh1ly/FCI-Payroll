package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The approval engine on routes the seed does not hold (SRS-G-1, SRS-G-28, SRS-G-29, BR-APPR-004,
 * BR-APPR-007): a three-level route (maker, checker, approver) moves level by level and takes
 * effect only at the last; a decider never decides the same item twice; a missing route, or a maker
 * who is not the route's first level, has no approval rule; a level nobody in scope can decide is
 * flagged. Each test adds its own version of the M-01 control-flag route (an Establishment / HR
 * HOLD proposal is the item; I-17 is read-only since SRS-1-63) and restores the seeded one
 * afterwards (the integration tests run one at a time).
 */
class ApprovalEngineIT extends ApiTestSupport {

    private static final String ROUTE = "M01_FLAG";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final List<String> RESERVED =
            List.of("105220", "106620", "106845", "106958", "104882", "106004");

    /**
     * Adds an active version of the route with the given levels (role, office level) and returns
     * its id.
     */
    private long route(String[][] levels, Integer tatHours) {
        Integer version =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version_no), 0) + 1 FROM approval_workflow_action"
                                + " WHERE action_code = ?",
                        Integer.class,
                        ROUTE);
        jdbc.update(
                "INSERT INTO approval_workflow_action (action_code, action_name, module_code,"
                    + " screen_code, office_level, level_count, otp_required, attachment_required,"
                    + " target_tat_hours, hq_consolidated_view, version_no, effective_from,"
                    + " action_status) VALUES (?, 'IT route', 'EMPLOYEE', 'M-01', 'PAYING_UNIT', ?,"
                    + " 'N', 'N', ?, 'N', ?, ?, 'ACTIVE')",
                ROUTE,
                levels.length,
                tatHours,
                version,
                // (action_code, office_level, effective_from) is unique: a day of its own per
                // version.
                Date.valueOf(TODAY.minusDays(version)));
        long id =
                jdbc.queryForObject(
                        "SELECT workflow_action_id FROM approval_workflow_action WHERE action_code"
                                + " = ? AND version_no = ?",
                        Long.class,
                        ROUTE,
                        version);
        String[] names = {"MAKER", "CHECKER", "APPROVER"};
        for (int i = 0; i < levels.length; i++) {
            jdbc.update(
                    "INSERT INTO approval_workflow_level (workflow_action_id, level_no, level_name,"
                            + " role_code, office_level, otp_at_level) VALUES (?, ?, ?, ?, ?, 'N')",
                    id,
                    i + 1,
                    names[i],
                    levels[i][0],
                    levels[i][1]);
        }
        return id;
    }

    private void retire(long routeId) {
        jdbc.update(
                "UPDATE approval_workflow_action SET action_status = 'SUPERSEDED' WHERE"
                        + " workflow_action_id = ?",
                routeId);
    }

    /**
     * A member no earlier hold of this class used: a HOLD proposal waiting on a member refuses a
     * second one (EmployeeControlFlagService), so each call takes the next member.
     */
    private String member() {
        List<String> all = members();
        return all.get(NEXT.getAndIncrement() % all.size());
    }

    private static final java.util.concurrent.atomic.AtomicInteger NEXT =
            new java.util.concurrent.atomic.AtomicInteger(20);

    /** The in-service members of DH001 not reserved by other tests, in staff-number order. */
    private List<String> members() {
        return jdbc
                .queryForList(
                        "SELECT e.emp_num FROM employee e JOIN org_location l ON l.location_id ="
                            + " e.location_id WHERE l.location_code = 'DH001' AND"
                            + " e.employment_status = 'IN_SERVICE' AND e.record_status = 'ACTIVE'"
                            + " ORDER BY e.emp_num",
                        String.class)
                .stream()
                .filter(n -> !RESERVED.contains(n))
                .toList();
    }

    /** An Establishment / HR proposal of the M-01 HOLD control flag (with its order copy). */
    private MvcResult hold(Session hr, String eis) throws Exception {
        Map<String, Object> body =
                Map.of(
                        "flagCode",
                        "HOLD",
                        "value",
                        "Y",
                        "effectiveFrom",
                        TODAY.toString(),
                        "reason",
                        "IT engine " + System.nanoTime());
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder r =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/v1/employees/" + eis + "/control-flags");
        r.file(
                new org.springframework.mock.web.MockMultipartFile(
                        "flag",
                        "",
                        org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(body)));
        r.file(
                new org.springframework.mock.web.MockMultipartFile(
                        "file",
                        "order.pdf",
                        "application/pdf",
                        "%PDF-1.7 hold order"
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        r.param("documentType", "OTHER")
                .param("orderDate", TODAY.toString())
                .param("issuingAuthority", "Establishment, Delhi");
        return mvc.perform(
                        userRequest(hr, r)
                                .header("Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andReturn();
    }

    private JsonNode item(Session s, String list, long approvalId) throws Exception {
        for (JsonNode r : all(s, list)) {
            if (r.get("approvalId").asLong() == approvalId) {
                return r;
            }
        }
        return null;
    }

    private int decision(Session s, long id, String decision, String reason) throws Exception {
        Map<String, Object> body =
                reason == null
                        ? Map.of("decision", decision)
                        : Map.of("decision", decision, "reason", reason);
        return mvc.perform(userPost(s, "/v1/approvals/" + id + "/decision", body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String eventStatus(long approvalId) {
        return jdbc.queryForObject(
                "SELECT status FROM approval_queue WHERE approval_id = ?",
                String.class,
                approvalId);
    }

    // SC-02-0049: on a three-level route each approval moves the item to the next level.
    @Test
    void SRS_G_1_aThreeLevelRouteMovesLevelByLevelAndTakesEffectOnlyAtTheLast() throws Exception {
        long route =
                route(
                        new String[][] {
                            {"ESTABLISHMENT_HR", "PAYING_UNIT"},
                            {"PAYROLL_CHECKER", "PAYING_UNIT"},
                            {"PAYROLL_CHECKER", "HQ"}
                        },
                        24);
        try {
            Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
            long id = body(hold(hr, member())).get("approvalId").asLong();
            Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
            JsonNode atTwo = item(checker, "/v1/inbox", id);
            assertThat(atTwo).isNotNull();
            assertThat(atTwo.get("level").asInt()).isEqualTo(2);
            assertThat(atTwo.get("levelCount").asInt()).isEqualTo(3);
            // Due by the target turnaround of the route (SRS-22-16), its age shown against it.
            assertThat(atTwo.get("targetTatHours").isNull()).isFalse();
            assertThat(
                            java.time.Duration.between(
                                    java.time.Instant.parse(atTwo.get("submittedAt").asString()),
                                    java.time.Instant.parse(atTwo.get("slaDueAt").asString())))
                    .as("target turnaround of the route")
                    .isEqualTo(java.time.Duration.ofHours(atTwo.get("targetTatHours").asLong()));
            assertThat(atTwo.get("slaBreached").asBoolean()).isFalse();
            assertThat(atTwo.get("ageDays").asLong()).isZero();
            assertThat(decision(checker, id, "APPROVE", "Checked at the unit")).isEqualTo(200);
            assertThat(eventStatus(id))
                    .as("still pending at level 3")
                    .isEqualTo("PENDING_APPROVAL");
            // The level-2 checker never decides the same item again.
            assertThat(decision(checker, id, "APPROVE", null)).isIn(403, 409);

            Session hq = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
            JsonNode atThree = item(hq, "/v1/inbox", id);
            assertThat(atThree).isNotNull();
            assertThat(atThree.get("level").asInt()).isEqualTo(3);
            assertThat(decision(hq, id, "APPROVE", null)).isEqualTo(200);
            assertThat(eventStatus(id)).isEqualTo("APPROVED");
            // Decided: nobody decides again.
            assertThat(decision(hq, id, "REJECT", "late")).isIn(403, 409);
            Long levels =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM approval_decision WHERE approval_id = ?",
                            Long.class,
                            id);
            assertThat(levels).isEqualTo(2);
        } finally {
            retire(route);
        }
    }

    /**
     * NEW-G1-02 (US-01-01 AC4, US-02-05 AC7; SC-01-0026, SC-02-0039): SRS-G-28 / BR-APPR-005 "Items
     * in progress complete under the version in force at submission". An item submitted under a
     * three-level version keeps its three levels after a two-level version comes into force today:
     * the unit checker's approval leaves it pending at level 3 and only Headquarters completes it;
     * an item submitted after the change follows the new two-level version.
     */
    @Test
    void BR_APPR_005_anItemInProgressCompletesUnderTheRouteVersionOfItsSubmission()
            throws Exception {
        long three =
                route(
                        new String[][] {
                            {"ESTABLISHMENT_HR", "PAYING_UNIT"},
                            {"PAYROLL_CHECKER", "PAYING_UNIT"},
                            {"PAYROLL_CHECKER", "HQ"}
                        },
                        24);
        Long two = null;
        try {
            Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
            long before = body(hold(hr, member())).get("approvalId").asLong();
            jdbc.update(
                    "INSERT INTO approval_workflow_action (action_code, action_name, module_code,"
                        + " screen_code, office_level, level_count, otp_required,"
                        + " attachment_required, target_tat_hours, hq_consolidated_view,"
                        + " version_no, effective_from, action_status) SELECT ?, 'IT route v2',"
                        + " 'EMPLOYEE', 'M-01', 'PAYING_UNIT', 2, 'N', 'N', 24, 'N',"
                        + " MAX(version_no) + 1, ?, 'ACTIVE' FROM approval_workflow_action WHERE"
                        + " action_code = ?",
                    ROUTE,
                    Date.valueOf(TODAY),
                    ROUTE);
            two =
                    jdbc.queryForObject(
                            "SELECT workflow_action_id FROM approval_workflow_action WHERE"
                                    + " action_code = ? AND effective_from = ?",
                            Long.class,
                            ROUTE,
                            Date.valueOf(TODAY));
            for (String[] level :
                    new String[][] {
                        {"1", "MAKER", "ESTABLISHMENT_HR"}, {"2", "CHECKER", "PAYROLL_CHECKER"}
                    }) {
                jdbc.update(
                        "INSERT INTO approval_workflow_level (workflow_action_id, level_no,"
                                + " level_name, role_code, office_level, otp_at_level) VALUES (?,"
                                + " ?, ?, ?, 'PAYING_UNIT', 'N')",
                        two,
                        Integer.parseInt(level[0]),
                        level[1],
                        level[2]);
            }
            long after = body(hold(hr, member())).get("approvalId").asLong();

            Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
            assertThat(item(checker, "/v1/inbox", before).get("levelCount").asInt())
                    .as("the version of its submission")
                    .isEqualTo(3);
            assertThat(item(checker, "/v1/inbox", after).get("levelCount").asInt())
                    .as("a new item follows the version in force now")
                    .isEqualTo(2);
            assertThat(decision(checker, before, "APPROVE", "Checked")).isEqualTo(200);
            assertThat(eventStatus(before)).as("still at level 3").isEqualTo("PENDING_APPROVAL");
            assertThat(decision(checker, after, "APPROVE", "Checked")).isEqualTo(200);
            assertThat(eventStatus(after)).as("two levels: done").isEqualTo("APPROVED");
            Session hq = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
            assertThat(decision(hq, before, "APPROVE", null)).isEqualTo(200);
            assertThat(eventStatus(before)).isEqualTo("APPROVED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT workflow_action_id FROM approval_queue WHERE"
                                            + " approval_id = ?",
                                    Long.class,
                                    before))
                    .isEqualTo(three);
        } finally {
            retire(three);
            if (two != null) {
                retire(two);
            }
        }
    }

    /**
     * A level at a zone, region or division office is decided at that office above the item's
     * paying unit (DH001: a division office of region RO-DL in zone NA11).
     */
    @Test
    void SRS_22_11_aLevelAtAnOfficeLevelIsDecidedAtThatOfficeAboveTheItemsUnit() throws Exception {
        Long dh = locationId("DH001");
        Long region = locationId("RO-DL");
        Long zonal =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_type = 'ZO' AND"
                            + " zone_id = (SELECT zone_id FROM org_location WHERE location_code ="
                            + " 'DH001')",
                        Long.class);
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        // Three different members: a member with a pending change takes no second one.
        List<String> members = List.of(member(), member(), member());
        int round = 0;
        for (Object[] expected : new Object[][] {{"ZO", zonal}, {"RO", region}, {"DO", dh}}) {
            long route =
                    route(
                            new String[][] {
                                {"ESTABLISHMENT_HR", "PAYING_UNIT"},
                                {"PAYROLL_CHECKER", (String) expected[0]}
                            },
                            null);
            try {
                long id = body(hold(hr, members.get(round++))).get("approvalId").asLong();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT org_scope_id FROM workflow_inbox_item WHERE"
                                                + " approval_id = ?",
                                        Long.class,
                                        id))
                        .as("checker office at level " + expected[0])
                        .isEqualTo(expected[1]);
                // Withdraw the item so the member is free for the next round.
                jdbc.update(
                        "UPDATE approval_queue SET status = 'WITHDRAWN' WHERE approval_id = ?", id);
            } finally {
                retire(route);
            }
        }
    }

    /**
     * SRS-G-13, UC-07 A2 (US-GAP-21): "a Checker approves several items together ... Each item
     * still follows the identity rule and, where required, OTP": only items of a route that allows
     * bulk approval are taken; each is decided on its own, an unknown one refused by itself; every
     * approved item records the bulk reference.
     */
    @Test
    void SRS_G_13_itemsOfARouteThatAllowsItAreApprovedTogetherEachOnItsOwn() throws Exception {
        long route =
                route(
                        new String[][] {
                            {"ESTABLISHMENT_HR", "PAYING_UNIT"}, {"PAYROLL_CHECKER", "PAYING_UNIT"}
                        },
                        null);
        try {
            Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
            Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
            // The route as seeded (DDL default N): nothing is offered in bulk.
            long single = body(hold(hr, member())).get("approvalId").asLong();
            assertThat(item(checker, "/v1/inbox", single).get("bulkApproval").asBoolean())
                    .isFalse();
            // X-01 allows it on the route: every pending item of the route may now go in bulk.
            jdbc.update(
                    "UPDATE approval_workflow_action SET bulk_approval_allowed = 'Y' WHERE"
                            + " workflow_action_id = ?",
                    route);
            long first = body(hold(hr, member())).get("approvalId").asLong();
            long second = body(hold(hr, member())).get("approvalId").asLong();
            assertThat(item(checker, "/v1/inbox", first).get("bulkApproval").asBoolean()).isTrue();

            MvcResult bulk =
                    mvc.perform(
                                    userPost(
                                            checker,
                                            "/v1/approvals/bulk-approvals",
                                            Map.of(
                                                    "approvalIds",
                                                    List.of(first, second, 999_999_999L),
                                                    "reason",
                                                    "IT bulk")))
                            .andReturn();
            assertThat(bulk.getResponse().getStatus())
                    .as(bulk.getResponse().getContentAsString())
                    .isEqualTo(200);
            JsonNode out = body(bulk);
            assertThat(out.get("approved").size()).isEqualTo(2);
            assertThat(out.get("refused")).hasSize(1);
            assertThat(out.get("refused").get(0).get("reason").asString())
                    .isEqualTo("COM-404-NOT-FOUND");
            // Withdrawn again on X-01: the remaining item is not taken in bulk, named with why.
            jdbc.update(
                    "UPDATE approval_workflow_action SET bulk_approval_allowed = 'N' WHERE"
                            + " workflow_action_id = ?",
                    route);
            JsonNode notBulk =
                    body(
                            mvc.perform(
                                            userPost(
                                                    checker,
                                                    "/v1/approvals/bulk-approvals",
                                                    Map.of("approvalIds", List.of(single))))
                                    .andReturn());
            assertThat(notBulk.get("approved")).isEmpty();
            assertThat(notBulk.get("refused").get(0).get("reason").asString())
                    .isEqualTo("BULK_NOT_ALLOWED");
            String reference = out.get("reference").asString();
            assertThat(reference).startsWith("BULK-");
            for (long id : new long[] {first, second}) {
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT status FROM approval_queue WHERE approval_id = ?",
                                        String.class,
                                        id))
                        .isEqualTo("APPROVED");
                // Sec 6.4: the decision row carries the bulk reference (DDL approval_decision).
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT bulk_approval_ref FROM approval_decision WHERE"
                                                + " approval_id = ? AND decision = 'APPROVE'",
                                        String.class,
                                        id))
                        .isEqualTo(reference);
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM approval_queue WHERE approval_id = ?",
                                    String.class,
                                    single))
                    .isEqualTo("PENDING_APPROVAL");
            // Cleared on its own, as before.
            assertThat(decision(checker, single, "APPROVE", null)).isEqualTo(200);
            // Each item keeps every single-item rule: one already approved is not decided again,
            // and the Maker's attempt approves nothing (identity rule, UC-07 A2).
            JsonNode again =
                    body(
                            mvc.perform(
                                            userPost(
                                                    hr,
                                                    "/v1/approvals/bulk-approvals",
                                                    Map.of("approvalIds", List.of(first))))
                                    .andReturn());
            assertThat(again.get("approved")).isEmpty();
            assertThat(again.get("refused")).hasSize(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM approval_decision WHERE approval_id = ?",
                                    Integer.class,
                                    first))
                    .as("decided once")
                    .isEqualTo(1);
        } finally {
            retire(route);
        }
    }

    /**
     * SRS-G-21 (SRS v0.5 l.537, BMAD TR-003): an OTP is required for exactly the eighteen listed
     * actions and "no other action". Every seeded route that asks an OTP, of the maker (the action)
     * or at an approval level, names one of the eighteen {@code otp_challenge.gated_action} codes.
     */
    @Test
    void SRS_G_21_everyRouteThatAsksAnOtpNamesOneOfTheEighteenGatedActions() {
        List<String> gated = Arrays.stream(OtpGatedAction.values()).map(Enum::name).toList();
        List<Map<String, Object>> actions =
                jdbc.queryForList(
                        "SELECT workflow_action_id, action_code, otp_action_code FROM"
                                + " approval_workflow_action WHERE otp_required = 'Y'"
                                + " ORDER BY workflow_action_id");
        assertThat(actions).isNotEmpty();
        assertThat(actions)
                .allSatisfy(
                        a ->
                                assertThat(gated)
                                        .as("OTP code of route %s", a.get("ACTION_CODE"))
                                        .contains(String.valueOf(a.get("OTP_ACTION_CODE"))));
        // A level that takes the OTP (otp_at_level, the approval step of SRS-G-3) names the gated
        // action on its route, whether or not the maker's submission also asks one.
        List<Map<String, Object>> levels =
                jdbc.queryForList(
                        "SELECT l.workflow_level_id, a.action_code, a.otp_action_code FROM"
                                + " approval_workflow_level l JOIN approval_workflow_action a ON"
                                + " a.workflow_action_id = l.workflow_action_id WHERE"
                                + " l.otp_at_level = 'Y' ORDER BY l.workflow_level_id");
        assertThat(levels).isNotEmpty();
        assertThat(levels)
                .allSatisfy(
                        l ->
                                assertThat(gated)
                                        .as("OTP code of a level of route %s", l.get("ACTION_CODE"))
                                        .contains(String.valueOf(l.get("OTP_ACTION_CODE"))));
    }

    @Test
    void SRS_G_28_withoutARouteInForceThereIsNoApprovalRule() throws Exception {
        List<Long> active =
                jdbc.queryForList(
                        "SELECT workflow_action_id FROM approval_workflow_action WHERE action_code"
                                + " = ? AND action_status = 'ACTIVE'",
                        Long.class,
                        ROUTE);
        jdbc.update(
                "UPDATE approval_workflow_action SET action_status = 'SUPERSEDED' WHERE action_code"
                        + " = ?",
                ROUTE);
        try {
            MvcResult r =
                    hold(login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001"), member());
            assertThat(body(r).path("errorCode").asString()).isEqualTo("WF-403-NO-RULE");
        } finally {
            for (Long id : active) {
                jdbc.update(
                        "UPDATE approval_workflow_action SET action_status = 'ACTIVE' WHERE"
                                + " workflow_action_id = ?",
                        id);
            }
        }
    }

    @Test
    void SRS_G_28_aMakerWhoIsNotTheRoutesFirstLevelHasNoApprovalRule() throws Exception {
        long route =
                route(
                        new String[][] {
                            {"FINANCE", "PAYING_UNIT"}, {"PAYROLL_CHECKER", "PAYING_UNIT"}
                        },
                        null);
        try {
            MvcResult r =
                    hold(login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001"), member());
            assertThat(body(r).path("errorCode").asString()).isEqualTo("WF-403-NO-RULE");
        } finally {
            retire(route);
        }
    }

    @Test
    void BR_APPR_007_aLevelNobodyInScopeCanDecideIsFlaggedOnTheSubmission() throws Exception {
        long route =
                route(
                        new String[][] {
                            {"ESTABLISHMENT_HR", "PAYING_UNIT"},
                            {"LABOUR_DEALING_HAND", "PAYING_UNIT"}
                        },
                        null);
        try {
            Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
            long id = body(hold(hr, member())).get("approvalId").asLong();
            JsonNode mine = item(hr, "/v1/submissions", id);
            assertThat(mine).isNotNull();
            assertThat(mine.get("noApproverInScope").asBoolean()).isTrue();
            assertThat(mine.get("assignedRoleCode").asString()).isEqualTo("LABOUR_DEALING_HAND");
            // A Payroll Checker does not hold the level's role.
            Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
            assertThat(decision(checker, id, "APPROVE", null)).isEqualTo(403);
            // SRS-G-28: flagged to the HQ Administrator on A-08, never auto-approved.
            Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
            JsonNode flagged = null;
            for (JsonNode i : all(admin, "/v1/admin/workflow-actions/unstaffed")) {
                if (i.get("approvalId").asLong() == id) {
                    flagged = i;
                }
            }
            assertThat(flagged).isNotNull();
            assertThat(flagged.get("waitingForRole").asString()).isEqualTo("LABOUR_DEALING_HAND");
            assertThat(flagged.get("submittedBy").asString()).isEqualTo("hr.delhi@fci-demo.test");
        } finally {
            retire(route);
        }
    }

    @Test
    void SRS_G_1_aReturnOrRejectionNeedsAReasonAndAnUnknownItemIsNotFound() throws Exception {
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        long id = body(hold(hr, member())).get("approvalId").asLong();
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decision(checker, id, "RETURN", null)).isEqualTo(400);
        assertThat(decision(checker, id, "REJECT", " ")).isEqualTo(400);
        assertThat(decision(checker, id, "RETURN", "Order copy missing")).isEqualTo(200);
        assertThat(eventStatus(id)).isEqualTo("RETURNED");
        // Returned: no longer pending.
        assertThat(decision(checker, id, "APPROVE", null)).isIn(403, 409);
        assertThat(decision(checker, 99_999_999L, "APPROVE", null)).isEqualTo(404);
    }
}
