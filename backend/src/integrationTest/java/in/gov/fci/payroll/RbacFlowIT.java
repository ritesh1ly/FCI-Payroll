package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Effective authority = role ∩ access-group scope (RBAC_DESIGN.md §4–§7): data scope on every read,
 * maker-checker routing by approval_authority, the read-only consolidated HQ view, OTP-gated
 * assignments that are inert until approved, and the System Administrator's protections.
 */
@org.junit.jupiter.api.parallel.ResourceLock(ApiTestSupport.AGM_NORTH)
class RbacFlowIT extends ApiTestSupport {

    @Test
    void employeeListAndRecordsAreBoundedByTheLoginLocation() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode page =
                body(
                        mvc.perform(userGet(maker, "/v1/employees?size=100"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(10);
        // Delhi HQ and the depot below it; never Mumbai.
        page.get("items")
                .forEach(
                        row ->
                                assertThat(row.get("location").asString())
                                        .containsAnyOf("(DH001)", "(DP-DL01)"));
        // Mumbai's employee reads as unknown (404), never as "exists elsewhere".
        mvc.perform(userGet(maker, "/v1/employees/205101"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COM-404-NOT-FOUND"));
        mvc.perform(userGet(maker, "/v1/employees/105220"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.panMasked").value("******087M"))
                .andExpect(
                        jsonPath("$.bankAccountMasked")
                                .value(org.hamcrest.Matchers.startsWith("*")));
    }

    @Test
    void zoneGroupsSeeTheWholeZoneAndNothingElse() throws Exception {
        Session agm = login("agm.west@fci-demo.test", null, null);
        mvc.perform(userGet(agm, "/v1/employees/205101")).andExpect(status().isOk());
        // Another office's record reads as unknown (404, owner 2026-10-24; OWASP A01).
        mvc.perform(userGet(agm, "/v1/employees/105220")).andExpect(status().isNotFound());
    }

    @Test
    void eSevaSeesOnlyTheOwnRecord() throws Exception {
        Session employee = login("emp.rsharma@fci-demo.test", null, null);
        mvc.perform(userGet(employee, "/v1/eseva/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eis").value("104882"));
        mvc.perform(userGet(employee, "/v1/employees/105220"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHZ-403-PERMISSION"));
    }

    private JsonNode flagState(Session s, String eis, String code) throws Exception {
        for (JsonNode f :
                body(mvc.perform(userGet(s, "/v1/employees/" + eis + "/allowance-flags"))
                                .andReturn())
                        .path("flags")) {
            if (code.equals(f.path("code").asString())) {
                return f;
            }
        }
        throw new AssertionError(code + " not offered for " + eis);
    }

    // BR-EMPM-011: a Maker never approves their own item.
    @Test
    void makerCheckerFlagReachesTheRecordOnlyAfterApprovalByAnotherPerson() throws Exception {
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        // An M-01 allowance flag (SRS-1-11): the HRMS-owned contact and bank fields are never
        // keyed in Payroll (plan 16 D16-19).
        MvcResult submitted = proposeFlag(hr, "106620", "Posted to a notified city");
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        long approvalId = body(submitted).get("approvalId").asLong();
        String flag =
                jdbc.queryForObject(
                        "SELECT entity_key FROM approval_queue WHERE approval_id = ?",
                        String.class,
                        approvalId);
        String code = flag.split("\\|")[1];
        JsonNode pending = flagState(hr, "106620", code);
        assertThat(pending.get("pending").asBoolean()).isTrue();
        String before = pending.get("value").asString();
        // A second proposal of the same flag waits for the first.
        mvc.perform(
                        flagPost(
                                hr,
                                "106620",
                                Map.of(
                                        "flagCode",
                                        code,
                                        "value",
                                        "Y",
                                        "effectiveFrom",
                                        LocalDate.now(ZoneId.of("Asia/Kolkata"))
                                                .plusDays(1)
                                                .toString(),
                                        "reason",
                                        "Second proposal"),
                                true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EMP-409-FLAG-PENDING"));
        // The maker holds no approval permission at all.
        mvc.perform(
                        userPost(
                                hr,
                                "/v1/approvals/" + approvalId + "/decision",
                                Map.of("decision", "APPROVE")))
                .andExpect(status().isForbidden());

        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        JsonNode inbox = all(checker, "/v1/inbox");
        assertThat(inbox.findValuesAsString("entityKey")).contains(flag).doesNotContain("205327");
        mvc.perform(
                        userPost(
                                checker,
                                "/v1/approvals/" + approvalId + "/decision",
                                Map.of("decision", "APPROVE", "reason", "Verified")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        JsonNode decided = flagState(checker, "106620", code);
        assertThat(decided.get("pending").asBoolean()).isFalse();
        assertThat(decided.get("value").asString()).isNotEqualTo(before);
        JsonNode mine = all(hr, "/v1/submissions");
        assertThat(mine.findValuesAsString("status")).contains("APPROVED");
    }

    @Test
    void aRegionCheckerCannotDecideAnotherRegionsItem() throws Exception {
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Long mumbaiItem =
                pendingItemFor(
                        login("ddo.multi@fci-demo.test", "PAYROLL_CHECKER", "MR001"), "205327");
        mvc.perform(
                        userPost(
                                checker,
                                "/v1/approvals/" + mumbaiItem + "/decision",
                                Map.of("decision", "APPROVE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHZ-403-SCOPE"));
    }

    @Test
    void rejectAndReturnNeedAReason() throws Exception {
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Long item = pendingItemFor(checker, "106845");
        mvc.perform(
                        userPost(
                                checker,
                                "/v1/approvals/" + item + "/decision",
                                Map.of("decision", "RETURN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("WF-400-REASON"));
    }

    @Test
    void theConsolidatedHqViewIsReadOnlyOnRegionData() throws Exception {
        Session su = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        JsonNode me = body(mvc.perform(userGet(su, "/auth/me")).andReturn());
        assertThat(me.get("readOnlyConsolidated").asBoolean()).isTrue();
        mvc.perform(userGet(su, "/v1/mis/headcount")).andExpect(status().isOk());
        mvc.perform(
                        userPost(
                                su,
                                "/v1/actions",
                                Map.of(
                                        "screen",
                                        "M-22",
                                        "action",
                                        "CREATE",
                                        "locationId",
                                        locationId("DH001"),
                                        "reason",
                                        "Regional rate")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHZ-403-READ-ONLY"));
        // At Headquarters itself the rule administrator may propose (HQ-only rule), but never
        // approve own work.
        MvcResult proposed =
                mvc.perform(
                                userPost(
                                        su,
                                        "/v1/actions",
                                        Map.of(
                                                "screen",
                                                "M-23",
                                                "action",
                                                "CREATE",
                                                "reason",
                                                "Ceiling revision")))
                        .andExpect(status().isAccepted())
                        .andReturn();
        long approvalId = body(proposed).get("approvalId").asLong();
        // In the maker role the session cannot approve at all; switched to the approver role it is
        // refused on identity: nobody decides their own item (SRS-G-1, separation of duties).
        mvc.perform(
                        userPost(
                                su,
                                "/v1/approvals/" + approvalId + "/decision",
                                Map.of("decision", "APPROVE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHZ-403-PERMISSION"));
        actAs(su, "RULE_APPROVER");
        mvc.perform(
                        userPost(
                                su,
                                "/v1/approvals/" + approvalId + "/decision",
                                Map.of("decision", "APPROVE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("WF-403-SOD"));
    }

    @Test
    void assignmentsNeedAnOtpAreInertUntilApprovedAndNeverSelfService() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        long subject = userId(admin, "agm.north@fci-demo.test");
        long group = groupId(admin, "AG-PC-MR001");
        Map<String, Object> request =
                new HashMap<>(
                        Map.of(
                                "userId",
                                subject,
                                "groupId",
                                group,
                                "reason",
                                "Cover Mumbai approvals"));

        MvcResult challenge =
                mvc.perform(userPost(admin, "/v1/admin/assignments", request)).andReturn();
        assertThat(challenge.getResponse().getStatus()).isEqualTo(428);
        long challengeId = body(challenge).get("args").get(0).asLong();
        request.put(
                "otp",
                Map.of(
                        "challengeId",
                        challengeId,
                        "code",
                        OTP_CODES.get("sysadmin@fci-demo.test|ROLE_ACCESS_GROUP_CHANGE")));
        JsonNode proposed =
                body(
                        mvc.perform(userPost(admin, "/v1/admin/assignments", request))
                                .andExpect(status().isAccepted())
                                .andReturn());
        assertThat(proposed.get("approvalStatus").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(proposed.get("current").asBoolean()).isFalse();
        // Inert until approved: no right at MR001 yet.
        assertThat(
                        loginRaw(
                                        "agm.north@fci-demo.test",
                                        DEMO_PASSWORD,
                                        "PAYROLL_CHECKER",
                                        "MR001")
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);

        long self = userId(admin, "sysadmin@fci-demo.test");
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/assignments",
                                Map.of("userId", self, "groupId", group, "reason", "Self")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ADM-403-SELF"));
        // The administrator cannot approve: no checker role and maker-checker.
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/approvals/"
                                        + proposed.get("approvalId").asLong()
                                        + "/decision",
                                Map.of("decision", "APPROVE")))
                .andExpect(status().isForbidden());

        // SRS-22-23 (US-GAP-14): approved at the Zonal Office of MR001's zone, not at MR001.
        Session checker = login("agm.west@fci-demo.test", "PAYROLL_CHECKER", "ZO-W");
        String approve = "/v1/approvals/" + proposed.get("approvalId").asLong() + "/decision";
        MvcResult otp =
                mvc.perform(userPost(checker, approve, Map.of("decision", "APPROVE"))).andReturn();
        assertThat(otp.getResponse().getStatus()).isEqualTo(428);
        long checkerChallenge = body(otp).get("args").get(0).asLong();
        mvc.perform(
                        userPost(
                                checker,
                                approve,
                                Map.of(
                                        "decision",
                                        "APPROVE",
                                        "reason",
                                        "Approved for leave cover",
                                        "otp",
                                        Map.of(
                                                "challengeId",
                                                checkerChallenge,
                                                "code",
                                                OTP_CODES.get(
                                                        "agm.west@fci-demo.test|ROLE_ACCESS_GROUP_CHANGE")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // Approved: the new right at MR001 is there on the next sign-in.
        assertThat(
                        loginRaw(
                                        "agm.north@fci-demo.test",
                                        DEMO_PASSWORD,
                                        "PAYROLL_CHECKER",
                                        "MR001")
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    @Test
    void theSubjectOfAnAssignmentCannotApproveIt() throws Exception {
        // Seeded: sysadmin proposed AG-PC-MR001 for ddo.delhi; ddo.delhi holds the checker role but
        // at Delhi.
        Session subject = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        // The whole register, page by page (the coverage seed alone holds 153 assignments).
        JsonNode assignments = all(admin, "/v1/admin/assignments");
        Long approvalId = null;
        for (JsonNode a : assignments) {
            if ("ddo.delhi@fci-demo.test".equals(a.get("loginId").asString())
                    && "AG-PC-MR001".equals(a.get("groupCode").asString())) {
                approvalId = a.get("approvalId").asLong();
            }
        }
        assertThat(approvalId).isNotNull();
        mvc.perform(
                        userPost(
                                subject,
                                "/v1/approvals/" + approvalId + "/decision",
                                Map.of("decision", "APPROVE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("WF-403-SELF"));
    }

    @Test
    void revocationTakesEffectOnTheNextRequest() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        Session agm = login("agm.north@fci-demo.test", null, null);
        actAs(agm, "PAYROLL_CHECKER");
        mvc.perform(userGet(agm, "/v1/inbox")).andExpect(status().isOk());
        JsonNode assignments =
                body(mvc.perform(
                                        userGet(
                                                admin,
                                                "/v1/admin/assignments?size=100&userId="
                                                        + userId(admin, "agm.north@fci-demo.test")))
                                .andReturn())
                        .get("items");
        java.util.List<Long> revoked = new java.util.ArrayList<>();
        for (JsonNode a : assignments) {
            if ("AG-PC-NA11".equals(a.get("groupCode").asString())) {
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/admin/assignments/" + a.get("id").asLong() + "/revoke",
                                        Map.of("reason", "Transferred")))
                        .andExpect(status().isOk());
                revoked.add(a.get("id").asLong());
            }
        }
        try {
            revokedChecks(agm);
        } finally {
            // The shared context's other classes sign agm.north in as the NA11 Payroll Checker:
            // the seeded assignment is put back as it was (fixture, not an application change).
            for (long id : revoked) {
                jdbc.update(
                        "UPDATE app_user_access_group SET revoked_at = NULL, revoke_cause = NULL,"
                                + " revoke_reason = NULL WHERE assignment_id = ?",
                        id);
            }
            cacheChanged(jdbc, "ACCESS");
        }
    }

    /** What the revocation of agm.north's NA11 Checker group leaves (SRS-SEC-14). */
    private void revokedChecks(Session agm) throws Exception {
        // SRS-SEC-14: the revocation ends the open session at once (Module 02 D-5); after a new
        // sign-in the checker role is gone and the user's report group remains.
        mvc.perform(userGet(agm, "/v1/inbox")).andExpect(status().isUnauthorized());
        Session again = login("agm.north@fci-demo.test", null, null);
        mvc.perform(userGet(again, "/v1/inbox")).andExpect(status().isForbidden());
        actAs(again, "REPORT_ADMINISTRATOR");
        mvc.perform(userGet(again, "/v1/employees/105220")).andExpect(status().isOk());
        mvc.perform(userGet(again, "/v1/inbox")).andExpect(status().isForbidden());
    }

    @Test
    void theSystemAdministratorRoleIsProtectedAndHasNoPayrollRights() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        JsonNode matrix =
                body(
                        mvc.perform(userGet(admin, "/v1/admin/roles"))
                                .andExpect(status().isOk())
                                .andReturn());
        long sysadminRole = 0;
        for (JsonNode role : matrix.get("roles")) {
            if ("SYSTEM_ADMINISTRATOR".equals(role.get("code").asString())) {
                sysadminRole = role.get("id").asLong();
            }
        }
        long anyPermission = matrix.get("permissions").get(0).get("id").asLong();
        mvc.perform(
                        userPut(
                                admin,
                                "/v1/admin/roles/grants",
                                Map.of(
                                        "roleId",
                                        sysadminRole,
                                        "permissionId",
                                        anyPermission,
                                        "granted",
                                        true,
                                        "reason",
                                        "Widen the administrator")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ADM-403-PROTECTED"));
        // OD-125 (Wireframes v6.1 role access, V078_4): M-01 is drawn for the Administrator (read
        // only); WF-071 for FCI.
        mvc.perform(userGet(admin, "/v1/employees")).andExpect(status().isOk());
        // SRS-G-29 (plan 05 D-11, owner 2026-10-25): the Administrator opens the Approvals Inbox
        // for the undo authorisation alone. The engine offers an item only to the role of its
        // current level, and only route C02_UNDO names the Administrator at a deciding level.
        JsonNode inbox =
                body(
                        mvc.perform(userGet(admin, "/v1/inbox?size=100"))
                                .andExpect(status().isOk())
                                .andReturn());
        for (JsonNode item : inbox.get("items")) {
            assertThat(item.get("entityName").asString())
                    .as("only undo authorisations reach the Administrator")
                    .isEqualTo("undo_reversal_register");
        }
    }

    @Test
    void everySignInAndAssignmentEventIsAudited() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        Instant to = Instant.now().plus(1, ChronoUnit.MINUTES);
        Instant from = to.minus(1, ChronoUnit.DAYS);
        JsonNode page =
                body(
                        mvc.perform(
                                        userGet(
                                                admin,
                                                "/v1/admin/audit?size=100&field=LOGIN&from="
                                                        + from
                                                        + "&to="
                                                        + to))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(page.findValuesAsString("newValue"))
                .anyMatch(v -> v.startsWith("SUCCESS SYSTEM_ADMIN"));
        mvc.perform(
                        userGet(
                                admin,
                                "/v1/admin/audit?from="
                                        + from.minus(400, ChronoUnit.DAYS)
                                        + "&to="
                                        + to))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUD-400-WINDOW"));
        // An empty or reversed range is its own error, not "too large".
        mvc.perform(userGet(admin, "/v1/admin/audit?from=" + to + "&to=" + from))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUD-400-RANGE"));
        mvc.perform(userGet(admin, "/v1/admin/audit?from=" + from + "&to=" + from))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUD-400-RANGE"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        // OD-125 (Wireframes v6.1 role access, V078_4): X-04 Audit Trail (built A-05) is drawn for
        // the Payroll Maker; WF-071 for FCI.
        mvc.perform(userGet(maker, "/v1/admin/audit?from=" + from + "&to=" + to))
                .andExpect(status().isOk());
    }

    @Test
    void governedButtonsReportWhetherTheSessionMayPressThem() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode actions =
                body(
                        mvc.perform(userGet(maker, "/v1/me/actions"))
                                .andExpect(status().isOk())
                                .andReturn());
        for (JsonNode a : actions) {
            if ("M-01".equals(a.get("screenCode").asString())
                    && "APPROVE".equals(a.get("actionCode").asString())) {
                assertThat(a.get("allowed").asBoolean()).isFalse();
                assertThat(a.get("disabledReasonKey").asString()).isEqualTo("reason.checkerOnly");
            }
            if ("C-02".equals(a.get("screenCode").asString())
                    && "LOCK".equals(a.get("actionCode").asString())) {
                assertThat(a.get("allowed").asBoolean()).isTrue();
            }
        }
    }

    private Long pendingItemFor(Session checker, String entityKey) throws Exception {
        JsonNode inbox = all(checker, "/v1/inbox");
        for (JsonNode item : inbox) {
            if (entityKey.equals(item.get("entityKey").asString())) {
                return item.get("approvalId").asLong();
            }
        }
        throw new IllegalStateException("No pending item " + entityKey);
    }

    private long userId(Session admin, String loginId) throws Exception {
        JsonNode users =
                body(mvc.perform(userGet(admin, "/v1/admin/users?size=100&q=" + loginId))
                                .andReturn())
                        .get("items");
        return users.get(0).get("id").asLong();
    }

    private long groupId(Session admin, String code) throws Exception {
        for (JsonNode g : body(mvc.perform(userGet(admin, "/v1/admin/groups")).andReturn())) {
            if (code.equals(g.get("code").asString())) {
                return g.get("id").asLong();
            }
        }
        throw new IllegalStateException("No group " + code);
    }
}
