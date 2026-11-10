package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Role take-over and privilege-escalation attempts that must fail (Module 02; SRS-22-2, 22-3, 22-5,
 * 22-8, 22-13, 22-19, 22-23, SRS-G-36, SRS-SEC-14). Each case names the industry test it applies:
 * OWASP WSTG-ATHZ-02 (bypassing the authorisation schema), WSTG-ATHZ-03 (privilege escalation),
 * WSTG-ATHZ-04 (insecure direct object references) and OWASP ASVS 4.0 V4.1 / V4.2 (access control).
 */
class RbacEscalationIT extends ApiTestSupport {

    private static final String OTP = "ROLE_ACCESS_GROUP_CHANGE";

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private long userId(String login) {
        return jdbc.queryForObject(
                "SELECT user_id FROM app_user WHERE login_id = ?", Long.class, login);
    }

    private long groupId(String code) {
        return jdbc.queryForObject(
                "SELECT access_group_id FROM access_group WHERE group_code = ?", Long.class, code);
    }

    /** WSTG-ATHZ-03: a role or office the user does not hold cannot be taken in the session. */
    @Test
    void SRS_22_2_aRoleOrOfficeNotHeldCannotBeTakenAtSignInOrBySwitching() throws Exception {
        MvcResult signIn =
                loginRaw("maker.delhi@fci-demo.test", DEMO_PASSWORD, "PAYROLL_CHECKER", "DH001");
        assertThat(status(signIn)).isEqualTo(403);
        Session maker = login("maker.delhi@fci-demo.test", null, null);
        for (Map<String, Object> target :
                List.<Map<String, Object>>of(
                        Map.of("role", "PAYROLL_CHECKER", "location", "DH001"),
                        Map.of("role", "SYSTEM_ADMINISTRATOR", "location", "HQ001"),
                        Map.of("role", "PAYROLL_MAKER", "location", "MR001"),
                        Map.of("role", "EMPLOYEE_ESEVA"))) {
            MvcResult switched =
                    mvc.perform(userPost(maker, "/auth/switch-scope", target)).andReturn();
            assertThat(status(switched)).as(target.toString()).isEqualTo(403);
            assertThat(code(switched)).isEqualTo("AUTHZ-403-SWITCH");
        }
    }

    /** WSTG-ATHZ-02 / -03: screens of another role refuse a session that only knows their URL. */
    @Test
    void SRS_22_8_aMakerReachesNoAdministrationAndAnEsevaUserNoStaffScreen() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", null, null);
        for (String path :
                List.of(
                        "/v1/admin/users",
                        "/v1/admin/roles",
                        "/v1/admin/groups",
                        "/v1/admin/assignments")) {
            assertThat(status(mvc.perform(userGet(maker, path)).andReturn()))
                    .as(path)
                    .isEqualTo(403);
        }
        // OD-125 (Wireframes v6.1 role access, V078_4): X-04 Audit Trail (built A-05) and the
        // Approvals
        // Inbox are drawn for the Payroll Maker: read, never administered (WF-071 for FCI).
        for (String path :
                List.of(
                        "/v1/admin/audit?from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z",
                        "/v1/inbox")) {
            assertThat(status(mvc.perform(userGet(maker, path)).andReturn()))
                    .as(path)
                    .isEqualTo(200);
        }
        Session eseva = login("emp.rsharma@fci-demo.test", null, null);
        for (String path :
                List.of("/v1/employees", "/v1/employees/204913", "/v1/off-cycle", "/v1/inbox")) {
            assertThat(status(mvc.perform(userGet(eseva, path)).andReturn()))
                    .as(path)
                    .isIn(403, 404);
        }
    }

    /** ASVS V4.1.3 / SRS-22-19: a zone administrator cannot change roles, permissions or groups. */
    @Test
    void SRS_22_19_aZoneAdministratorCannotChangeRolesPermissionsOrGroups() throws Exception {
        Session north = login("rbac.admin.north@fci-demo.test", "SYSTEM_ADMINISTRATOR", "NA11");
        MvcResult grant =
                submitWithOtp(
                        north,
                        "PUT",
                        "/v1/admin/roles/grants",
                        Map.of("roleId", 1, "permissionId", 1, "granted", true, "reason", "x"),
                        OTP);
        assertThat(status(grant)).isEqualTo(403);
        assertThat(code(grant)).isEqualTo("ADM-403-HQ-ONLY");
        MvcResult role =
                submitWithOtp(
                        north,
                        "POST",
                        "/v1/admin/roles",
                        Map.of(
                                "code",
                                "ZONE_SUPER",
                                "name",
                                "Zone super",
                                "approver",
                                true,
                                "roleType",
                                "MODULE",
                                "baseRole",
                                "CHECKER",
                                "reason",
                                "x"),
                        OTP);
        assertThat(code(role)).isEqualTo("ADM-403-HQ-ONLY");
        MvcResult group =
                submitWithOtp(
                        north,
                        "POST",
                        "/v1/admin/groups",
                        Map.of(
                                "code",
                                "AG-ZONE-SELF",
                                "name",
                                "x",
                                "roleId",
                                1,
                                "scopeType",
                                "GLOBAL",
                                "consolidated",
                                false,
                                "accessMode",
                                "FULL",
                                "scopes",
                                List.of(),
                                "reason",
                                "x"),
                        OTP);
        assertThat(code(group)).isEqualTo("ADM-403-HQ-ONLY");
        MvcResult end =
                mvc.perform(
                                userPost(
                                        north,
                                        "/v1/admin/groups/" + groupId("AG-PM-DH001") + "/end",
                                        Map.of("reason", "x")))
                        .andReturn();
        assertThat(code(end)).isEqualTo("ADM-403-HQ-ONLY");
    }

    /**
     * WSTG-ATHZ-04 / SRS-22-13: a zone administrator sees and changes only its own zone's users.
     */
    @Test
    void SRS_22_13_aZoneAdministratorManagesOnlyItsOwnZonesUsers() throws Exception {
        Session north = login("rbac.admin.north@fci-demo.test", "SYSTEM_ADMINISTRATOR", "NA11");
        // Every page of it (the demo data holds more than one page).
        List<String> logins = all(north, "/v1/admin/users").findValuesAsString("loginId");
        assertThat(logins)
                .contains("rbac.maker.ludhiana@fci-demo.test", "maker.delhi@fci-demo.test");
        assertThat(logins)
                .doesNotContain(
                        "finance.mumbai@fci-demo.test",
                        "maker.kolkata@fci-demo.test",
                        "sysadmin@fci-demo.test");

        long west = userId("finance.mumbai@fci-demo.test");
        MvcResult read = mvc.perform(userGet(north, "/v1/admin/users/" + west)).andReturn();
        // A user of another zone reads as unknown (404), never as "exists elsewhere".
        assertThat(status(read)).isEqualTo(404);
        assertThat(code(read)).isEqualTo("COM-404-NOT-FOUND");
        for (String action : List.of("status", "unlock", "password")) {
            Map<String, Object> body =
                    switch (action) {
                        case "status" -> Map.of("status", "SUSPENDED", "note", "take-over");
                        case "unlock" -> Map.of("reason", "take-over");
                        default -> Map.of("temporaryPassword", "Attacker@Pass2026");
                    };
            MvcResult r =
                    mvc.perform(userPost(north, "/v1/admin/users/" + west + "/" + action, body))
                            .andReturn();
            assertThat(status(r)).as(action).isEqualTo(404);
        }
        // The national system account is out of any zone's reach.
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        north,
                                                        "/v1/admin/users/"
                                                                + userId("sysadmin@fci-demo.test")))
                                        .andReturn()))
                .isEqualTo(404);
        // Inside the zone the administrator works normally.
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        north,
                                                        "/v1/admin/users/"
                                                                + userId(
                                                                        "rbac.maker.ludhiana@fci-demo.test")))
                                        .andReturn()))
                .isEqualTo(200);
    }

    /**
     * WSTG-ATHZ-03: a zone administrator cannot hand out national or other zones' access (which
     * would be its own escalation by proxy), only groups wholly inside its zone.
     */
    @Test
    void SRS_22_23_aZoneAdministratorAssignsOnlyGroupsInsideItsZone() throws Exception {
        Session north = login("rbac.admin.north@fci-demo.test", "SYSTEM_ADMINISTRATOR", "NA11");
        long ludhiana = userId("rbac.checker.punjab@fci-demo.test");
        for (String group :
                List.of(
                        "AG-SYSADM-GLOBAL",
                        "AG-PC-HQ",
                        "AG-PC-HQ-CONS",
                        "AG-PC-MR001",
                        "AG-PC-WE11")) {
            MvcResult r =
                    submitWithOtp(
                            north,
                            "POST",
                            "/v1/admin/assignments",
                            Map.of(
                                    "userId",
                                    ludhiana,
                                    "groupId",
                                    groupId(group),
                                    "reason",
                                    "take-over"),
                            OTP);
            assertThat(status(r)).as(group).isEqualTo(403);
        }
        // A West user cannot be given even a North group.
        MvcResult west =
                submitWithOtp(
                        north,
                        "POST",
                        "/v1/admin/assignments",
                        Map.of(
                                "userId",
                                userId("finance.mumbai@fci-demo.test"),
                                "groupId",
                                groupId("AG-PM-LD001"),
                                "reason",
                                "x"),
                        OTP);
        // A user of another zone is unknown to this administrator (404).
        assertThat(status(west)).isEqualTo(404);
        // A North group to a North user: accepted for the checker (nothing granted before
        // approval).
        MvcResult inside =
                submitWithOtp(
                        north,
                        "POST",
                        "/v1/admin/assignments",
                        Map.of(
                                "userId",
                                ludhiana,
                                "groupId",
                                groupId("AG-PM-LD001"),
                                "reason",
                                "Second role"),
                        OTP);
        assertThat(status(inside)).isEqualTo(202);
    }

    /** ASVS V4.2.1 / SRS-22-5: nobody assigns access to themselves, whatever their role. */
    @Test
    void SRS_22_5_nobodyAssignsAccessToThemselves() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        MvcResult self =
                submitWithOtp(
                        admin,
                        "POST",
                        "/v1/admin/assignments",
                        Map.of(
                                "userId",
                                userId("sysadmin@fci-demo.test"),
                                "groupId",
                                groupId("AG-PC-HQ"),
                                "reason",
                                "x"),
                        OTP);
        assertThat(status(self)).isEqualTo(403);
        assertThat(code(self)).isEqualTo("ADM-403-SELF");
    }

    /** ASVS V5.1.2 (mass assignment): fields the API does not define are refused, never applied. */
    @Test
    void anUndefinedFieldCannotForceAnApprovalState() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        Map<String, Object> forged = new HashMap<>();
        forged.put("userId", userId("rbac.no.access@fci-demo.test"));
        forged.put("groupId", groupId("AG-PC-HQ"));
        forged.put("reason", "x");
        forged.put("approvalStatus", "APPROVED");
        forged.put("approvalId", 1);
        MvcResult r = mvc.perform(userPost(admin, "/v1/admin/assignments", forged)).andReturn();
        assertThat(status(r)).isEqualTo(400);
        Map<String, Object> grant =
                new HashMap<>(
                        Map.of("roleId", 1, "permissionId", 1, "granted", true, "reason", "x"));
        grant.put("approved", true);
        assertThat(status(mvc.perform(userPut(admin, "/v1/admin/roles/grants", grant)).andReturn()))
                .isEqualTo(400);
    }

    /** SRS-22-3: an HQ consolidated view never decides a region's item, whatever the URL. */
    @Test
    void SRS_22_3_aConsolidatedViewCannotDecideRegionItems() throws Exception {
        Session cons = login("rbac.checker.hqcons@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        JsonNode inbox = body(mvc.perform(userGet(cons, "/v1/inbox?size=50")).andReturn());
        JsonNode items = inbox.has("items") ? inbox.get("items") : inbox;
        assertThat(items.size()).isGreaterThan(0);
        for (JsonNode item : items) {
            assertThat(item.get("canDecide").asBoolean()).isFalse();
            int decided = decide(cons, item.get("approvalId").asLong(), "APPROVE", null, null);
            assertThat(decided).isEqualTo(403);
        }
    }

    /** SRS-SEC-14 / SRS-G-36: revoking access ends the user's sessions at once. */
    @Test
    void SRS_SEC_14_aRevocationEndsTheUsersSessionsAtOnce() throws Exception {
        Session user = login("rbac.hr.finance@fci-demo.test", "FINANCE", "LD001");
        assertThat(status(mvc.perform(userGet(user, "/auth/me")).andReturn())).isEqualTo(200);
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        Long assignment =
                jdbc.queryForObject(
                        "SELECT a.assignment_id FROM app_user_access_group a JOIN access_group g ON"
                                + " g.access_group_id = a.access_group_id WHERE g.group_code ="
                                + " 'AG-FIN-LD001' AND a.user_id = ? AND a.revoked_at IS NULL",
                        Long.class,
                        userId("rbac.hr.finance@fci-demo.test"));
        MvcResult revoked =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/admin/assignments/" + assignment + "/revoke",
                                        Map.of("reason", "Transferred", "cause", "TRANSFER")))
                        .andReturn();
        assertThat(status(revoked)).isEqualTo(200);
        assertThat(status(mvc.perform(userGet(user, "/auth/me")).andReturn())).isEqualTo(401);
    }
}
