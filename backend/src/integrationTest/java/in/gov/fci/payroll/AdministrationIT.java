package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** System Administrator screens A-01 … A-05 and the remaining governed actions. */
class AdministrationIT extends ApiTestSupport {

    private static final String TEMP = "Temp#Gulmohar-2026";

    @Test
    void usersAreCreatedWithATemporaryPasswordAndManagedThroughTheirLifeCycle() throws Exception {
        Session admin = admin();
        JsonNode created =
                body(
                        mvc.perform(
                                        userPost(
                                                admin,
                                                "/v1/admin/users",
                                                Map.of(
                                                        "loginId",
                                                        "New.Joiner@FCI-Demo.test",
                                                        "displayName",
                                                        "New Joiner",
                                                        "category",
                                                        "OPERATIONS",
                                                        "employeeEis",
                                                        "106731",
                                                        "temporaryPassword",
                                                        TEMP)))
                                .andExpect(status().isCreated())
                                .andReturn());
        long id = created.get("id").asLong();
        assertThat(created.get("loginId").asString()).isEqualTo("new.joiner@fci-demo.test");
        assertThat(created.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(created.get("employeeEis").asString()).isEqualTo("106731");

        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users",
                                Map.of(
                                        "loginId",
                                        "new.joiner@fci-demo.test",
                                        "displayName",
                                        "Again",
                                        "category",
                                        "OPERATIONS",
                                        "temporaryPassword",
                                        TEMP)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ADM-409-LOGIN"));
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users",
                                Map.of(
                                        "loginId",
                                        "not-an-email",
                                        "displayName",
                                        "X",
                                        "category",
                                        "OPERATIONS",
                                        "temporaryPassword",
                                        TEMP)))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users",
                                Map.of(
                                        "loginId",
                                        "weak@fci-demo.test",
                                        "displayName",
                                        "Weak",
                                        "category",
                                        "OPERATIONS",
                                        "temporaryPassword",
                                        "weak")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH-400-PASSWORD-POLICY"));

        mvc.perform(userGet(admin, "/v1/admin/users/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(
                        userPut(
                                admin,
                                "/v1/admin/users/" + id + "/contact",
                                Map.of("displayName", "N. Joiner", "mobile", "9000000001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("N. Joiner"));
        // SRS-15-29 / SRS-NF-26: a save against the version read; a stale one names the change.
        long version =
                body(mvc.perform(userGet(admin, "/v1/admin/users/" + id)).andReturn())
                        .get("rowVersion")
                        .asLong();
        mvc.perform(
                        userPut(
                                        admin,
                                        "/v1/admin/users/" + id + "/contact",
                                        Map.of(
                                                "displayName",
                                                "N. Joiner II",
                                                "mobile",
                                                "9000000001"))
                                .header("If-Match", "W/\"" + version + "\""))
                .andExpect(status().isOk())
                // The answer names the version the save gave, for the caller's next If-Match.
                .andExpect(jsonPath("$.rowVersion").value(version + 1));
        mvc.perform(
                        userPut(
                                        admin,
                                        "/v1/admin/users/" + id + "/contact",
                                        Map.of("displayName", "Stale Name", "mobile", "9000000001"))
                                .header("If-Match", String.valueOf(version)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.errorCode").value("COM-412-CHANGED"))
                .andExpect(jsonPath("$.args[0]").value(admin.loginId()));
        mvc.perform(
                        userPut(
                                        admin,
                                        "/v1/admin/users/" + id + "/contact",
                                        Map.of("displayName", "Bad", "mobile", "9000000001"))
                                .header("If-Match", "\"abc\""))
                .andExpect(status().isBadRequest());
        mvc.perform(userGet(admin, "/v1/admin/users/" + id))
                .andExpect(jsonPath("$.displayName").value("N. Joiner II"));
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users/" + id + "/status",
                                Map.of("status", "SUSPENDED", "note", "Inquiry")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users/" + id + "/status",
                                Map.of("status", "SUSPENDED", "note", "Again")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ADM-409-STATUS"));
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users/" + id + "/status",
                                Map.of("status", "ACTIVE")))
                .andExpect(status().isOk());
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users/" + id + "/unlock",
                                Map.of("reason", "Verified by phone")))
                .andExpect(status().isOk());
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users/" + id + "/password",
                                Map.of("temporaryPassword", "Temp#Ashoka-2027")))
                .andExpect(status().isNoContent());
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users/" + id + "/status",
                                Map.of("status", "DEACTIVATED", "reason", "SEPARATION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("WF-400-REASON"));
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users/" + id + "/status",
                                Map.of(
                                        "status",
                                        "DEACTIVATED",
                                        "reason",
                                        "SEPARATION",
                                        "note",
                                        "Retired 2026-10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEACTIVATED"));
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users/" + id + "/status",
                                Map.of("status", "ACTIVE")))
                .andExpect(status().isConflict());

        long self =
                body(mvc.perform(userGet(admin, "/auth/me")).andReturn()).get("userId").asLong();
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users/" + self + "/status",
                                Map.of("status", "SUSPENDED", "note", "Self")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ADM-403-SELF"));
        JsonNode page =
                body(
                        mvc.perform(
                                        userGet(
                                                admin,
                                                "/v1/admin/users?status=ACTIVE&q=fci-demo&size=10"))
                                .andReturn());
        assertThat(page.get("items").size()).isLessThanOrEqualTo(10);
        assertThat(page.get("total").asLong()).isGreaterThan(10);
    }

    private static final String ACCESS_OTP = "ROLE_ACCESS_GROUP_CHANGE";

    /**
     * SRS-22-8 / SRS-22-23: a role and every permission grant are proposals a Payroll Checker at
     * Headquarters approves with an OTP; nothing changes before; the maker never approves.
     */
    @Test
    void SRS_22_8_rolesAndGrantsAreMakerCheckerWithAnOtpAndEffectiveDated() throws Exception {
        Session admin = admin();
        Session checker = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        String code = "LEAVE_ADMIN_" + (System.nanoTime() % 100000);
        Map<String, Object> newRole =
                new HashMap<>(
                        Map.of(
                                "code", code.toLowerCase(java.util.Locale.ROOT),
                                "name", "Leave Administrator",
                                "description", "HQ leave rules",
                                "approver", false,
                                "roleType", "MODULE",
                                "baseRole", "MAKER",
                                "reason", "Leave module"));
        MvcResult proposed = submitWithOtp(admin, "POST", "/v1/admin/roles", newRole, ACCESS_OTP);
        assertThat(proposed.getResponse().getStatus()).isEqualTo(202);
        long roleApproval = body(proposed).get("approvalId").asLong();
        assertThat(roleExists(admin, code)).as("nothing before the approval").isFalse();
        assertThat(decide(admin, roleApproval, "APPROVE", null, ACCESS_OTP))
                .as("the maker never approves")
                .isEqualTo(403);
        assertThat(decide(checker, roleApproval, "APPROVE", null, ACCESS_OTP)).isEqualTo(200);
        assertThat(roleExists(admin, code)).isTrue();

        // A second proposal of the same code, an invalid code, no reason.
        assertThat(
                        submitWithOtp(admin, "POST", "/v1/admin/roles", newRole, ACCESS_OTP)
                                .getResponse()
                                .getStatus())
                .isEqualTo(409);
        Map<String, Object> bad = new HashMap<>(newRole);
        bad.put("code", "x");
        mvc.perform(userPost(admin, "/v1/admin/roles", bad)).andExpect(status().isBadRequest());
        Map<String, Object> noReason = new HashMap<>(newRole);
        noReason.remove("reason");
        mvc.perform(userPost(admin, "/v1/admin/roles", noReason))
                .andExpect(status().isBadRequest());

        JsonNode matrix = body(mvc.perform(userGet(admin, "/v1/admin/roles")).andReturn());
        long permission = matrix.get("permissions").get(0).get("id").asLong();
        long roleId = roleId(admin, code);
        Map<String, Object> grant =
                Map.of(
                        "roleId",
                        roleId,
                        "permissionId",
                        permission,
                        "granted",
                        true,
                        "reason",
                        "Needed");
        MvcResult granted =
                submitWithOtp(admin, "PUT", "/v1/admin/roles/grants", grant, ACCESS_OTP);
        assertThat(granted.getResponse().getStatus()).isEqualTo(202);
        assertThat(grants(admin, roleId)).as("not before the approval").doesNotContain(permission);
        // While pending, the same change cannot be proposed again.
        assertThat(
                        submitWithOtp(admin, "PUT", "/v1/admin/roles/grants", grant, ACCESS_OTP)
                                .getResponse()
                                .getStatus())
                .isEqualTo(409);
        assertThat(
                        decide(
                                checker,
                                body(granted).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                ACCESS_OTP))
                .isEqualTo(200);
        assertThat(grants(admin, roleId)).contains(permission);
        // Granting what is already granted is refused (nothing to approve).
        assertThat(
                        submitWithOtp(admin, "PUT", "/v1/admin/roles/grants", grant, ACCESS_OTP)
                                .getResponse()
                                .getStatus())
                .isEqualTo(409);

        Map<String, Object> withdraw =
                Map.of(
                        "roleId",
                        roleId,
                        "permissionId",
                        permission,
                        "granted",
                        false,
                        "reason",
                        "Not needed");
        MvcResult removal =
                submitWithOtp(admin, "PUT", "/v1/admin/roles/grants", withdraw, ACCESS_OTP);
        assertThat(
                        decide(
                                checker,
                                body(removal).get("approvalId").asLong(),
                                "REJECT",
                                "Still needed",
                                ACCESS_OTP))
                .isEqualTo(200);
        assertThat(grants(admin, roleId))
                .as("a rejected proposal changes nothing")
                .contains(permission);
        removal = submitWithOtp(admin, "PUT", "/v1/admin/roles/grants", withdraw, ACCESS_OTP);
        assertThat(
                        decide(
                                checker,
                                body(removal).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                ACCESS_OTP))
                .isEqualTo(200);
        assertThat(grants(admin, roleId)).doesNotContain(permission);

        Map<String, Object> unknown =
                Map.of(
                        "roleId",
                        999999,
                        "permissionId",
                        permission,
                        "granted",
                        true,
                        "reason",
                        "x");
        mvc.perform(userPut(admin, "/v1/admin/roles/grants", unknown))
                .andExpect(status().isNotFound());
    }

    private boolean roleExists(Session admin, String code) throws Exception {
        for (JsonNode role :
                body(mvc.perform(userGet(admin, "/v1/admin/roles")).andReturn()).get("roles")) {
            if (code.equals(role.get("code").asString())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void accessGroupsAreMakerCheckerAtHeadquartersWithAnOtp() throws Exception {
        Session admin = admin();
        long role = roleId(admin, "FINANCE");
        Long mumbai = locationId("MR001");
        Map<String, Object> request =
                new HashMap<>(
                        Map.of(
                                "code",
                                "ag-fin-mr001-b",
                                "name",
                                "Finance - Mumbai (second desk)",
                                "roleId",
                                role,
                                "scopeType",
                                "DIVISION",
                                "consolidated",
                                false,
                                "scopes",
                                List.of(Map.of("locationId", mumbai)),
                                "reason",
                                "Second finance desk"));
        MvcResult challenge = mvc.perform(userPost(admin, "/v1/admin/groups", request)).andReturn();
        assertThat(challenge.getResponse().getStatus()).isEqualTo(428);
        request.put(
                "otp",
                Map.of(
                        "challengeId",
                        body(challenge).get("args").get(0).asLong(),
                        "code",
                        OTP_CODES.get("sysadmin@fci-demo.test|ROLE_ACCESS_GROUP_CHANGE")));
        JsonNode group =
                body(
                        mvc.perform(userPost(admin, "/v1/admin/groups", request))
                                .andExpect(status().isAccepted())
                                .andReturn());
        assertThat(group.get("code").asString()).isEqualTo("AG-FIN-MR001-B");
        assertThat(group.get("approvalStatus").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(group.get("scopes").get(0).get("code").asString()).isEqualTo("MR001");

        // A Region checker cannot approve a corporate (HQ-only) item; the HQ checker can.
        Session regionChecker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        String decide = "/v1/approvals/" + group.get("approvalId").asLong() + "/decision";
        mvc.perform(userPost(regionChecker, decide, Map.of("decision", "APPROVE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHZ-403-HQ-ONLY"));
        Session hqChecker = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        assertThat(all(hqChecker, "/v1/inbox").findValuesAsString("entityKey"))
                .contains("AG-FIN-MR001-B");
        MvcResult otp =
                mvc.perform(userPost(hqChecker, decide, Map.of("decision", "APPROVE"))).andReturn();
        assertThat(otp.getResponse().getStatus()).isEqualTo(428);
        mvc.perform(
                        userPost(
                                hqChecker,
                                decide,
                                Map.of(
                                        "decision",
                                        "APPROVE",
                                        "otp",
                                        Map.of(
                                                "challengeId",
                                                        body(otp).get("args").get(0).asLong(),
                                                "code",
                                                        OTP_CODES.get(
                                                                "checker.hq@fci-demo.test|ROLE_ACCESS_GROUP_CHANGE")))))
                .andExpect(status().isOk());
        JsonNode groups = body(mvc.perform(userGet(admin, "/v1/admin/groups")).andReturn());
        for (JsonNode g : groups) {
            if ("AG-FIN-MR001-B".equals(g.get("code").asString())) {
                assertThat(g.get("approvalStatus").asString()).isEqualTo("APPROVED");
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/admin/groups/" + g.get("id").asLong() + "/end",
                                        Map.of("lastDay", LocalDate.now().plusDays(30).toString())))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.effectiveTo").exists());
            }
        }
    }

    @Test
    void groupScopesMustMatchTheirScopeType() throws Exception {
        Session admin = admin();
        long role = roleId(admin, "FINANCE");
        expectInvalidGroup(
                admin,
                Map.of(
                        "code",
                        "AG-BAD-1",
                        "name",
                        "Bad",
                        "roleId",
                        role,
                        "scopeType",
                        "REGION",
                        "consolidated",
                        true,
                        "scopes",
                        List.of(Map.of("locationId", locationId("RO-DL"))),
                        "reason",
                        "x"),
                "ADM-400-SCOPE");
        expectInvalidGroup(
                admin,
                Map.of(
                        "code",
                        "AG-BAD-2",
                        "name",
                        "Bad",
                        "roleId",
                        role,
                        "scopeType",
                        "REGION",
                        "consolidated",
                        false,
                        "scopes",
                        List.of(Map.of("locationId", locationId("DH001"))),
                        "reason",
                        "x"),
                "ADM-400-SCOPE");
        expectInvalidGroup(
                admin,
                Map.of(
                        "code",
                        "AG-BAD-3",
                        "name",
                        "Bad",
                        "roleId",
                        role,
                        "scopeType",
                        "GLOBAL",
                        "consolidated",
                        false,
                        "scopes",
                        List.of(Map.of("locationId", locationId("DH001"))),
                        "reason",
                        "x"),
                "ADM-400-SCOPE");
        expectInvalidGroup(
                admin,
                Map.of(
                        "code",
                        "AG-BAD-4",
                        "name",
                        "Bad",
                        "roleId",
                        role,
                        "scopeType",
                        "ZONE",
                        "consolidated",
                        false,
                        "scopes",
                        List.of(),
                        "reason",
                        "x"),
                "ADM-400-SCOPE");
        expectInvalidGroup(
                admin,
                Map.of(
                        "code",
                        "AG-BAD-5",
                        "name",
                        "Bad",
                        "roleId",
                        role,
                        "scopeType",
                        "ZONE",
                        "consolidated",
                        false,
                        "scopes",
                        List.of(Map.of("zoneId", 999999)),
                        "reason",
                        "x"),
                "ADM-400-SCOPE");
        expectInvalidGroup(
                admin,
                Map.of(
                        "code",
                        "AG-PC-DH001",
                        "name",
                        "Dup",
                        "roleId",
                        role,
                        "scopeType",
                        "DIVISION",
                        "consolidated",
                        false,
                        "scopes",
                        List.of(Map.of("locationId", locationId("DH001"))),
                        "reason",
                        "x"),
                "COM-409-CONFLICT");
        MvcResult first =
                mvc.perform(userGet(admin, "/v1/admin/org")).andExpect(status().isOk()).andReturn();
        JsonNode org = body(first);
        assertThat(org.get("zones").findValuesAsString("code")).contains("NA11", "WE11");
        assertThat(org.get("locations").findValuesAsString("label"))
                .contains("DO — Delhi HQ (DH001)");
        // HLD §3.12: the hierarchy is kept by the browser and revalidated, never shared by a proxy.
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).startsWith("\"org-");
        assertThat(first.getResponse().getHeader("Cache-Control"))
                .contains("private")
                .contains("no-cache")
                .doesNotContain("public");
        MvcResult again =
                mvc.perform(userGet(admin, "/v1/admin/org").header("If-None-Match", etag))
                        .andExpect(status().isNotModified())
                        .andReturn();
        assertThat(again.getResponse().getContentAsString()).isEmpty();
        // A stale or forged tag gets the full answer.
        mvc.perform(userGet(admin, "/v1/admin/org").header("If-None-Match", "\"org-x\""))
                .andExpect(status().isOk());
        // The tag never gets past the authorisation: a session without A-03 / A-04 is refused.
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        mvc.perform(userGet(maker, "/v1/admin/org").header("If-None-Match", etag))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAuditTrailExportsAsSafeCsvAndRecordsTheExport() throws Exception {
        Session admin = admin();
        Instant to = Instant.now().plus(1, ChronoUnit.MINUTES);
        Instant from = to.minus(1, ChronoUnit.DAYS);
        String path = "/v1/admin/audit/export?from=" + from + "&to=" + to + "&field=LOGIN";
        assertThat(download(admin, path).getResponse().getHeader("Content-Disposition"))
                .contains("audit-trail.csv");
        assertThat(csv(admin, path)).startsWith("\"id\",\"changed_at\",\"changed_by\"");
        JsonNode exports =
                body(
                        mvc.perform(
                                        userGet(
                                                admin,
                                                // SRS-20-24: every export is logged (entity
                                                // "export", key = the list exported).
                                                "/v1/admin/audit?field=EXPORT&entity=export&from="
                                                        + from
                                                        + "&to="
                                                        + to))
                                .andReturn());
        assertThat(exports.get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(texts(exports, "entityKey")).contains("audit-trail");
    }

    /**
     * SRS l.1226 (US-03-06): the trail search matches record type, user and field without regard to
     * case and as part of the stored name; a LIKE wildcard typed by the user is a plain character.
     */
    @Test
    void SRS_20_2_theTrailSearchIgnoresCaseAndMatchesPartOfTheStoredName() throws Exception {
        Session admin = admin();
        Instant to = Instant.now().plus(1, ChronoUnit.MINUTES);
        Instant from = to.minus(1, ChronoUnit.DAYS);
        // An export is logged first, so the window holds at least one EXPORT row.
        csv(admin, "/v1/admin/audit/export?from=" + from + "&to=" + to + "&field=LOGIN");
        String window = "&from=" + from + "&to=" + to;
        long exact = trailTotal(admin, "/v1/admin/audit?field=EXPORT&entity=export" + window);
        assertThat(exact).isGreaterThanOrEqualTo(1);
        assertThat(trailTotal(admin, "/v1/admin/audit?field=export&entity=EXPORT" + window))
                .isEqualTo(exact);
        assertThat(trailTotal(admin, "/v1/admin/audit?field=xpor&entity=Expo" + window))
                .isGreaterThanOrEqualTo(exact);
        // A typed % or _ is not a wildcard: no stored name contains a percent sign.
        assertThat(trailTotal(admin, "/v1/admin/audit?entity=%" + window)).isZero();
        assertThat(trailTotal(admin, "/v1/admin/audit?field=EXP_RT" + window)).isZero();
        // A blank term is no filter (the request URL is encoded by the test client).
        assertThat(trailTotal(admin, "/v1/admin/audit?entity= &field=EXPORT" + window))
                .isGreaterThanOrEqualTo(exact);
    }

    private long trailTotal(Session s, String path) throws Exception {
        return body(mvc.perform(userGet(s, path)).andReturn()).get("total").asLong();
    }

    @Test
    void permittedUngovernedActionsAreRecordedAndUnknownOnesAre404() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        mvc.perform(userPost(maker, "/v1/actions", Map.of("screen", "M-01", "action", "EXPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECORDED"));
        // The month-end lock request needs the maker's OTP (approval_authority otp_required_flag =
        // Y).
        Map<String, Object> lock =
                new HashMap<>(
                        Map.of("screen", "C-02", "action", "LOCK", "reason", "Month end JUL 2026"));
        MvcResult challenge = mvc.perform(userPost(maker, "/v1/actions", lock)).andReturn();
        assertThat(challenge.getResponse().getStatus()).isEqualTo(428);
        lock.put(
                "otp",
                Map.of(
                        "challengeId",
                        body(challenge).get("args").get(0).asLong(),
                        "code",
                        OTP_CODES.get("maker.delhi@fci-demo.test|CYCLE_FREEZE_LOCK")));
        mvc.perform(userPost(maker, "/v1/actions", lock))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("SUBMITTED"));
        mvc.perform(userPost(maker, "/v1/actions", Map.of("screen", "M-01", "action", "VIEW")))
                .andExpect(status().isNotFound());
        mvc.perform(userPost(maker, "/v1/actions", Map.of("screen", "C-02", "action", "CREATE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("WF-400-REASON"));
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        JsonNode inbox = all(checker, "/v1/inbox");
        for (JsonNode item : inbox) {
            if ("payroll_period_lock".equals(item.get("entityName").asString())) {
                mvc.perform(
                                userPost(
                                        checker,
                                        "/v1/approvals/"
                                                + item.get("approvalId").asLong()
                                                + "/decision",
                                        Map.of(
                                                "decision",
                                                "REJECT",
                                                "reason",
                                                "Arrears not posted")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("REJECTED"));
            }
        }
    }

    // BR-EMPM-002, SRS-INT-9, SRS-1-20: an HRMS-owned field (name, date of birth ...) has no edit
    // endpoint.
    // SC-16-0098: a returned flag change leaves the prior value in force.
    // SC-04-0001: Payroll-owned fields are editable, HRMS-sourced ones have no endpoint.
    @Test
    void returnedFlagProposalEndsThePendingAndHrmsFieldsHaveNoEndpoint() throws Exception {
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "RO-DL");
        MvcResult proposed = proposeFlag(hr, "106731", "Posted to a notified city");
        assertThat(proposed.getResponse().getStatus())
                .as(proposed.getResponse().getContentAsString())
                .isEqualTo(202);
        long id = body(proposed).get("approvalId").asLong();
        Session checker = login("ddo.multi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        mvc.perform(
                        userPost(
                                checker,
                                "/v1/approvals/" + id + "/decision",
                                Map.of("decision", "RETURN", "reason", "Attach cancelled cheque")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"));
        mvc.perform(userGet(hr, "/v1/employees/106731/allowance-flags"))
                .andExpect(jsonPath("$.flags[?(@.pending == true)]").isEmpty());
        // CLAUDE.md section 2, R-SRC, SRS-G-45 (plan 16 D16-19): bank account, contact and PAN are
        // HRMS-owned; no Payroll endpoint takes them.
        int forged =
                mvc.perform(
                                userPost(
                                        hr,
                                        "/v1/employees/106731/changes",
                                        Map.of(
                                                "field",
                                                "bankAccount",
                                                "value",
                                                "1",
                                                "reason",
                                                "r")))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(forged).isIn(404, 405);
    }

    @Test
    void theMisIsConsolidatedAndTheLanguagePreferenceIsStored() throws Exception {
        Session su = login("su.pension@fci-demo.test", "PENSION_DIVISION_HQ", "HQ001");
        JsonNode counts = all(su, "/v1/mis/headcount");
        assertThat(counts.findValuesAsString("locationCode")).contains("DH001", "MR001");
        mvc.perform(userPut(su, "/auth/locale", Map.of("locale", "hi")))
                .andExpect(status().isNoContent());
        mvc.perform(userGet(su, "/auth/me")).andExpect(jsonPath("$.locale").value("hi"));
        mvc.perform(userPut(su, "/auth/locale", Map.of("locale", "fr")))
                .andExpect(status().isBadRequest());
        mvc.perform(userPut(su, "/auth/locale", Map.of("locale", "en")))
                .andExpect(status().isNoContent());
    }

    /**
     * Target DDL v4.0 column lengths (UI audit 2026-11-02, lengths below the DDL): a role name
     * takes app_role.role_name (150), a group code and name access_group.group_code (40) and
     * group_name (200), the A-05 field filter audit_event.field_name (100). One more is refused
     * (400); the boundary passes validation and goes on to the OTP challenge (428) or the search
     * (200).
     */
    @Test
    void DDL_v4_roleAndGroupNamesAndTheAuditFieldFilterTakeTheirColumnLengths() throws Exception {
        Session admin = admin();
        String suffix = String.valueOf(System.nanoTime() % 100000);
        Map<String, Object> role =
                new HashMap<>(
                        Map.of(
                                "code",
                                "len_role_" + suffix,
                                "name",
                                "R".repeat(151),
                                "approver",
                                false,
                                "roleType",
                                "MODULE",
                                "baseRole",
                                "MAKER",
                                "reason",
                                "Column length"));
        mvc.perform(userPost(admin, "/v1/admin/roles", role)).andExpect(status().isBadRequest());
        role.put("name", "R".repeat(150));
        mvc.perform(userPost(admin, "/v1/admin/roles", role))
                .andExpect(status().isPreconditionRequired());

        Map<String, Object> group =
                new HashMap<>(
                        Map.of(
                                "code",
                                ("ag-len-" + suffix + "-").concat("x".repeat(40)).substring(0, 41),
                                "name",
                                "Length check",
                                "roleId",
                                roleId(admin, "FINANCE"),
                                "scopeType",
                                "DIVISION",
                                "consolidated",
                                false,
                                "scopes",
                                List.of(Map.of("locationId", locationId("MR001"))),
                                "reason",
                                "Column length"));
        mvc.perform(userPost(admin, "/v1/admin/groups", group)).andExpect(status().isBadRequest());
        group.put("code", group.get("code").toString().substring(0, 40));
        group.put("name", "N".repeat(201));
        mvc.perform(userPost(admin, "/v1/admin/groups", group)).andExpect(status().isBadRequest());
        group.put("name", "N".repeat(200));
        mvc.perform(userPost(admin, "/v1/admin/groups", group))
                .andExpect(status().isPreconditionRequired());

        Instant to = Instant.now().plus(1, ChronoUnit.MINUTES);
        String window = "&from=" + to.minus(1, ChronoUnit.DAYS) + "&to=" + to;
        mvc.perform(userGet(admin, "/v1/admin/audit?field=" + "f".repeat(100) + window))
                .andExpect(status().isOk());
        mvc.perform(userGet(admin, "/v1/admin/audit?field=" + "f".repeat(101) + window))
                .andExpect(status().isBadRequest());
    }

    private Session admin() throws Exception {
        return login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
    }

    private void expectInvalidGroup(Session admin, Map<String, Object> request, String code)
            throws Exception {
        MvcResult result = mvc.perform(userPost(admin, "/v1/admin/groups", request)).andReturn();
        assertThat(body(result).get("errorCode").asString())
                .as(request.get("code").toString())
                .isEqualTo(code);
    }

    private List<Long> grants(Session admin, long roleId) throws Exception {
        JsonNode matrix = body(mvc.perform(userGet(admin, "/v1/admin/roles")).andReturn());
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        matrix.get("grants")
                .forEach(
                        g -> {
                            if (g.get("roleId").asLong() == roleId) {
                                ids.add(g.get("permissionId").asLong());
                            }
                        });
        return ids;
    }

    private long roleId(Session admin, String code) throws Exception {
        for (JsonNode role :
                body(mvc.perform(userGet(admin, "/v1/admin/roles")).andReturn()).get("roles")) {
            if (code.equals(role.get("code").asString())) {
                return role.get("id").asLong();
            }
        }
        throw new IllegalStateException(code);
    }
}
