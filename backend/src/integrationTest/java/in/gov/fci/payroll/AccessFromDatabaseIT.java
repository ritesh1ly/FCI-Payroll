package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Who may do what is read from the database, never from the request and never from code (SRS-22-1,
 * SRS-22-6, SRS-22-8; OWASP ASVS V4.1.3 / V4.1.5, deny by default). Complements RbacGateMatrixIT
 * (every role x every gate against app_role_permission):
 *
 * <ul>
 *   <li>a grant counts only on the days it is effective, both boundaries inclusive, by the India
 *       business date (RBAC-SEC-089 / 090);
 *   <li>an account locked or a session past its 8-hour cap is refused on the very next request,
 *       however it was set, without waiting for any cache (RBAC-SEC-101, W-9);
 *   <li>an approval item without an office is decided at Headquarters only (RBAC-SEC-034, W-1);
 *   <li>a route of an OTP-gated action cannot drop or swap its OTP (RBAC-SEC-071, W-2).
 * </ul>
 */
class AccessFromDatabaseIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String code(MvcResult r) throws Exception {
        String text = r.getResponse().getContentAsString();
        return text.isEmpty() ? "" : json.readTree(text).path("errorCode").asString("");
    }

    private long roleId(String role) {
        return jdbc.queryForObject(
                "SELECT role_id FROM app_role WHERE role_code = ?", Long.class, role);
    }

    private long permissionId(String screen, String action) {
        return jdbc.queryForObject(
                "SELECT permission_id FROM app_permission WHERE screen_code = ? AND action_code ="
                        + " ?",
                Long.class,
                screen,
                action);
    }

    private MvcResult users(Session s) throws Exception {
        return mvc.perform(userGet(s, "/v1/admin/users")).andReturn();
    }

    /**
     * SRS-22-6 / SRS-22-1 (ASVS V4.1.3): the Payroll Maker holds no A-01. A grant given to the role
     * from tomorrow is not yet in force; from today it is; ended yesterday it is gone; ending today
     * it still holds (the India business date, both boundaries inclusive). The decision follows the
     * row each time: the grant table is the oracle, the role name is never consulted.
     */
    @Test
    void SRS_22_6_aGrantCountsOnlyOnTheDaysItIsEffectiveBothBoundariesInclusive() throws Exception {
        long role = roleId("PAYROLL_MAKER");
        long permission = permissionId("A-01", "VIEW");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM app_role_permission WHERE role_id = ? AND"
                                        + " permission_id = ?",
                                Integer.class,
                                role,
                                permission))
                .as("the Payroll Maker holds no A-01 VIEW in the seed")
                .isZero();
        LocalDate today = LocalDate.now(IST);
        Session maker = login("pm.rodl@fci-demo.test", "PAYROLL_MAKER", "RO-DL");
        assertThat(code(users(maker))).isEqualTo("AUTHZ-403-PERMISSION");
        try {
            jdbc.update(
                    "INSERT INTO app_role_permission (role_id, permission_id, effective_from)"
                            + " VALUES (?, ?, ?)",
                    role,
                    permission,
                    today.plusDays(1));
            accessContexts.evictAll(); // as every grant change of the application does
            assertThat(code(users(maker))).as("from tomorrow").isEqualTo("AUTHZ-403-PERMISSION");
            jdbc.update(
                    "UPDATE app_role_permission SET effective_from = ? WHERE role_id = ? AND"
                            + " permission_id = ?",
                    today,
                    role,
                    permission);
            accessContexts.evictAll();
            MvcResult fromToday = users(maker);
            assertThat(code(fromToday)).as("from today").isNotEqualTo("AUTHZ-403-PERMISSION");
            assertThat(status(fromToday)).isLessThan(500);
            jdbc.update(
                    "UPDATE app_role_permission SET effective_from = ?, effective_to = ? WHERE"
                            + " role_id = ? AND permission_id = ?",
                    today.minusDays(10),
                    today.minusDays(1),
                    role,
                    permission);
            accessContexts.evictAll();
            assertThat(code(users(maker))).as("ended yesterday").isEqualTo("AUTHZ-403-PERMISSION");
            jdbc.update(
                    "UPDATE app_role_permission SET effective_to = ? WHERE role_id = ? AND"
                            + " permission_id = ?",
                    today,
                    role,
                    permission);
            accessContexts.evictAll();
            assertThat(code(users(maker))).as("ending today").isNotEqualTo("AUTHZ-403-PERMISSION");
        } finally {
            jdbc.update(
                    "DELETE FROM app_role_permission WHERE role_id = ? AND permission_id = ?",
                    role,
                    permission);
            accessContexts.evictAll();
        }
        assertThat(code(users(maker))).isEqualTo("AUTHZ-403-PERMISSION");
    }

    /**
     * SRS-SEC-14 / SRS-G-48 / SRS-22-6 (ASVS V3.3.1 / V3.3.4; W-9): the account status and the
     * 8-hour cap are read from the database on every request. An account locked, suspended or
     * deactivated, and a session past its cap, are refused on the very next request, though the
     * session's rights are still in the cache and nothing evicted them.
     */
    @Test
    void SRS_SEC_14_aLockedAccountOrACappedSessionIsRefusedOnTheVeryNextRequest() throws Exception {
        String login = "pm.ropb@fci-demo.test";
        for (String blocked : List.of("LOCKED", "SUSPENDED", "DEACTIVATED")) {
            Session s = login(login, "PAYROLL_MAKER", "RO-PB");
            assertThat(status(mvc.perform(userGet(s, "/auth/me")).andReturn())).isEqualTo(200);
            try {
                jdbc.update(
                        "UPDATE app_user SET user_status = ?, deactivated_at = CASE WHEN ? ="
                                + " 'DEACTIVATED' THEN CURRENT_TIMESTAMP END, deactivation_reason"
                                + " = CASE WHEN ? = 'DEACTIVATED' THEN 'SEPARATION' END WHERE"
                                + " login_id = ?",
                        blocked,
                        blocked,
                        blocked,
                        login);
                MvcResult next = mvc.perform(userGet(s, "/v1/inbox")).andReturn();
                assertThat(status(next)).as(blocked).isEqualTo(401);
                assertThat(code(next)).isEqualTo("AUTH-401-SESSION-EXPIRED");
            } finally {
                jdbc.update(
                        "UPDATE app_user SET user_status = 'ACTIVE', deactivated_at = NULL,"
                                + " deactivation_reason = NULL WHERE login_id = ?",
                        login);
            }
        }
        Session capped = login(login, "PAYROLL_MAKER", "RO-PB");
        assertThat(status(mvc.perform(userGet(capped, "/auth/me")).andReturn())).isEqualTo(200);
        jdbc.update(
                "UPDATE app_session SET started_at = started_at - INTERVAL '9' HOUR WHERE"
                        + " ended_at IS NULL AND user_id = (SELECT user_id FROM app_user WHERE"
                        + " login_id = ?)",
                login);
        MvcResult afterCap = mvc.perform(userGet(capped, "/auth/me")).andReturn();
        assertThat(status(afterCap)).isEqualTo(401);
        assertThat(code(afterCap)).isEqualTo("AUTH-401-SESSION-EXPIRED");
    }

    /**
     * SRS-G-29 / SRS-22-11 (deny by default; RBAC-SEC-034, W-1): an approval item whose inbox task
     * names no office is in no office's scope. A checker of the role at a Divisional Office can
     * neither decide it nor open its documents through the API; Headquarters is not refused for the
     * office.
     */
    @Test
    void SRS_G_29_anApprovalItemWithoutAnOfficeIsDecidedAtHeadquartersOnly() throws Exception {
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        MvcResult submitted = proposeFlag(hr, "106620", "National item without an office");
        assertThat(status(submitted))
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        long approvalId = body(submitted).get("approvalId").asLong();
        jdbc.update(
                "UPDATE workflow_inbox_item SET org_scope_id = NULL WHERE approval_id = ?",
                approvalId);
        String decide = "/v1/approvals/" + approvalId + "/decision";
        Session divisional = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        MvcResult refused =
                mvc.perform(
                                userPost(
                                        divisional,
                                        decide,
                                        Map.of("decision", "APPROVE", "reason", "Out of scope")))
                        .andReturn();
        assertThat(status(refused)).isEqualTo(403);
        assertThat(code(refused)).isEqualTo("AUTHZ-403-SCOPE");
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        divisional,
                                                        "/v1/approvals/"
                                                                + approvalId
                                                                + "/documents"))
                                        .andReturn()))
                .isEqualTo(404);
        Session hq = login("pc.hq001@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        MvcResult atHq =
                mvc.perform(userPost(hq, decide, Map.of("decision", "RETURN", "reason", "At HQ")))
                        .andReturn();
        assertThat(code(atHq))
                .as(atHq.getResponse().getContentAsString())
                .isNotEqualTo("AUTHZ-403-SCOPE");
    }

    /**
     * SRS-G-21 / SRS-G-3 (R-OTP; RBAC-SEC-071, W-2): exactly the eighteen gated actions ask an OTP,
     * whatever the configuration. A new version of a route that asks one may not drop that OTP nor
     * swap it for another code; keeping it is not refused for it.
     */
    @Test
    void SRS_G_21_aNewVersionOfAnOtpGatedRouteCannotDropOrSwapItsOtp() throws Exception {
        Map<String, Object> gated =
                jdbc.queryForList(
                                "SELECT a.action_code, a.office_level, a.otp_action_code,"
                                    + " a.module_code, a.screen_code FROM approval_workflow_action"
                                    + " a WHERE a.otp_required = 'Y' AND a.otp_action_code IS NOT"
                                    + " NULL AND NOT EXISTS (SELECT 1 FROM approval_workflow_action"
                                    + " p WHERE p.action_code = a.action_code AND p.office_level ="
                                    + " a.office_level AND p.action_status = 'PENDING_APPROVAL')"
                                    + " ORDER BY a.workflow_action_id")
                        .get(0);
        String otp = String.valueOf(gated.get("OTP_ACTION_CODE"));
        String other = "AUTH_LOGIN".equals(otp) ? "CYCLE_FREEZE_LOCK" : "AUTH_LOGIN";
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        List<String> violations = new ArrayList<>();
        for (String proposed : new String[] {null, other, otp}) {
            Map<String, Object> route = new HashMap<>();
            route.put("actionCode", gated.get("ACTION_CODE"));
            route.put("actionName", "IT version " + gated.get("ACTION_CODE"));
            route.put("moduleCode", gated.get("MODULE_CODE"));
            route.put("screenCode", gated.get("SCREEN_CODE"));
            route.put("officeLevel", gated.get("OFFICE_LEVEL"));
            route.put("levelCount", 2);
            route.put("attachmentRequired", false);
            route.put("otpAction", proposed);
            // In the past: every proposal here is refused, so no version is left pending.
            route.put("effectiveFrom", LocalDate.now(IST).minusDays(1).toString());
            route.put(
                    "levels",
                    List.of(
                            Map.of(
                                    "levelNo",
                                    1,
                                    "roleCode",
                                    "PAYROLL_MAKER",
                                    "officeLevel",
                                    "PAYING_UNIT",
                                    "otpAtLevel",
                                    false),
                            Map.of(
                                    "levelNo",
                                    2,
                                    "roleCode",
                                    "PAYROLL_CHECKER",
                                    "officeLevel",
                                    "PAYING_UNIT",
                                    "otpAtLevel",
                                    false)));
            route.put("reason", "IT: OTP of a gated route");
            MvcResult r =
                    mvc.perform(userPost(admin, "/v1/admin/workflow-actions", route)).andReturn();
            assertThat(status(r)).as(String.valueOf(proposed)).isEqualTo(422);
            assertThat(code(r)).isEqualTo("WF-422-ROUTE");
            JsonNode body = body(r);
            List<String> codes = new ArrayList<>();
            body.path("violations").forEach(v -> codes.add(v.path("code").asString()));
            violations.add(proposed + " -> " + codes);
            if (otp.equals(proposed)) {
                assertThat(codes).as("kept").doesNotContain("OTP_DROPPED");
            } else {
                assertThat(codes).as(String.valueOf(proposed)).contains("OTP_DROPPED");
            }
        }
        System.out.println("RBAC-MATRIX OTP route versions " + violations);
    }
}
