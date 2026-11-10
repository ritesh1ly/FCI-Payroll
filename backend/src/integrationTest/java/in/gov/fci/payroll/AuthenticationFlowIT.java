package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Sign-in, sessions and passwords end to end (SRS-G-35, SRS-G-48…G-50; ADR-0008, ADR-0009,
 * ADR-0023): user ID + password only, one generic failure, lockout, deactivation, forced change and
 * history, scope switching among the user's own groups, sign-out, forgot / reset with OTP, CSRF.
 * Each test uses its own seeded user, so the order does not matter.
 */
class AuthenticationFlowIT extends ApiTestSupport {

    @Autowired private SystemParameters parameters;

    @Test
    void anEmployeeSignsInWithoutAnOfficeAndActsInEseva() throws Exception {
        Session session = login("emp.rsharma@fci-demo.test", null, null);
        mvc.perform(userGet(session, "/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("emp.rsharma@fci-demo.test"))
                .andExpect(jsonPath("$.location").doesNotExist());
    }

    @Test
    void theLoginIdIsComparedTrimmedAndCaseInsensitively() throws Exception {
        assertThat(
                        loginRaw("  Maker.Delhi@FCI-Demo.test ", DEMO_PASSWORD, null, null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    @Test
    void anUnknownLoginAndAWrongPasswordAreTheSame401() throws Exception {
        MvcResult unknown = loginRaw("nobody@fci-demo.test", DEMO_PASSWORD, null, null);
        MvcResult wrong = loginRaw("agm.west@fci-demo.test", "Wrong@Password1", null, null);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(unknown).get("errorCode").asString())
                .isEqualTo(body(wrong).get("errorCode").asString())
                .isEqualTo("AUTH-401-CREDENTIALS");
    }

    @Test
    void lockedDeactivatedAndExpiredAccountsAreRefused() throws Exception {
        expectLoginError("locked.user@fci-demo.test", 423, "AUTH-423-ACCOUNT-LOCKED");
        expectLoginError("deactivated.user@fci-demo.test", 403, "AUTH-403-DEACTIVATED");
        expectLoginError("expired.group@fci-demo.test", 403, "AUTH-403-NO-SCOPE");
    }

    @Test
    void theFifthFailureLocksTheCredentialEvenForTheRightPassword() throws Exception {
        String login = "finance.mumbai@fci-demo.test";
        for (int i = 1; i <= 4; i++) {
            assertThat(loginRaw(login, "Wrong@Password" + i, null, null).getResponse().getStatus())
                    .isEqualTo(401);
        }
        assertThat(loginRaw(login, "Wrong@Password5", null, null).getResponse().getStatus())
                .isEqualTo(423);
        MvcResult correct = loginRaw(login, DEMO_PASSWORD, null, null);
        assertThat(correct.getResponse().getStatus()).isEqualTo(423);
        assertThat(body(correct).get("errorCode").asString()).isEqualTo("AUTH-423-LOCKED");

        // The account is shared demo data (SessionAdminIT, RbacEscalationIT sign in with it in the
        // same database): an administrator unlocks it with a reason, as in production, and the
        // right password works again. Without this, those suites fail depending on test order.
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        long id =
                jdbc.queryForObject(
                        "SELECT user_id FROM app_user WHERE login_id = ?", Long.class, login);
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/admin/users/" + id + "/unlock",
                                Map.of("reason", "IT: lockout test done")))
                .andExpect(status().isOk());
        assertThat(loginRaw(login, DEMO_PASSWORD, null, null).getResponse().getStatus())
                .isEqualTo(200);
    }

    // SC-01-0150 (the current password must match), SC-01-0151 (the policy and the reuse history).
    @Test
    void aForcedPasswordChangeBlocksEverythingElseAndTheHistoryIsEnforced() throws Exception {
        Session session = login("emp.mverma@fci-demo.test", null, null);
        assertThat(session.passwordChangeRequired()).isTrue();
        mvc.perform(userGet(session, "/v1/eseva/me"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH-403-PASSWORD-CHANGE"));
        changePassword(session, DEMO_PASSWORD, DEMO_PASSWORD, 400, "AUTH-400-PASSWORD-REUSED");
        changePassword(session, DEMO_PASSWORD, "short", 400, null);
        changePassword(
                session, "Not@MyPassword9", "Kesar#Aam-Mango-77", 400, "AUTH-400-CURRENT-PASSWORD");
        changePassword(session, DEMO_PASSWORD, "Kesar#Aam-Mango-77", 204, null);

        mvc.perform(userGet(session, "/v1/eseva/me")).andExpect(status().isOk());
        mvc.perform(userGet(session, "/auth/me"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false));
        // The demo password is now in the history: changing back is refused.
        changePassword(
                session, "Kesar#Aam-Mango-77", DEMO_PASSWORD, 400, "AUTH-400-PASSWORD-REUSED");
    }

    @Test
    void responsesNeverContainPasswordHashesOrTheSessionToken() throws Exception {
        Session session = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        String me =
                mvc.perform(userGet(session, "/auth/me"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String users =
                mvc.perform(userGet(session, "/v1/admin/users"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(me + users)
                .doesNotContain("{bcrypt}", "{argon2}", "$2a$", "password_hash", session.cookie());
    }

    // SC-01-0153: with the single-session setting a new sign-in ends the user's earlier session
    // (end reason SUPERSEDED, ADR-0009); without it both stay open.
    @Test
    void SRS_G_48_aNewSignInEndsTheEarlierSessionUnderTheSingleSessionSetting() throws Exception {
        String login = "su.pension@fci-demo.test";
        Session first = login(login, null, null);
        mvc.perform(userGet(first, "/auth/me")).andExpect(status().isOk());
        Session second = login(login, null, null);
        mvc.perform(userGet(second, "/auth/me")).andExpect(status().isOk());
        boolean single = parameters.singleSession();
        mvc.perform(userGet(first, "/auth/me"))
                .andExpect(single ? status().isUnauthorized() : status().isOk());
        Integer superseded =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_session s JOIN app_user u ON u.user_id ="
                                + " s.user_id WHERE u.login_id = ? AND s.end_reason ="
                                + " 'SUPERSEDED'",
                        Integer.class,
                        login);
        if (single) {
            assertThat(superseded).as("sessions ended by a later sign-in").isPositive();
        } else {
            assertThat(superseded).isZero();
        }
    }

    @Test
    void signOutEndsTheSessionAtOnceAndClearsTheSite() throws Exception {
        Session session = login("su.pension@fci-demo.test", null, null);
        mvc.perform(userGet(session, "/auth/me")).andExpect(status().isOk());
        mvc.perform(userPost(session, "/auth/logout", Map.of()))
                .andExpect(status().isNoContent())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                                .exists("Clear-Site-Data"));
        mvc.perform(userGet(session, "/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void theScopeSwitcherOffersAndAcceptsOnlyTheUsersOwnGroups() throws Exception {
        Session session = login("ddo.multi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        JsonNode scopes =
                body(
                        mvc.perform(userGet(session, "/auth/scopes"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(texts(scopes, "location")).contains("DH001", "MR001").doesNotContain("HQ001");

        mvc.perform(
                        userPost(
                                session,
                                "/auth/switch-scope",
                                Map.of("role", "PAYROLL_CHECKER", "location", "MR001")))
                .andExpect(status().isNoContent());
        mvc.perform(userGet(session, "/auth/me"))
                .andExpect(jsonPath("$.location.code").value("MR001"));

        mvc.perform(
                        userPost(
                                session,
                                "/auth/switch-scope",
                                Map.of("role", "PAYROLL_CHECKER", "location", "RO-MH")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHZ-403-SWITCH"));
        mvc.perform(
                        userPost(
                                session,
                                "/auth/switch-scope",
                                Map.of("role", "SYSTEM_ADMINISTRATOR", "location", "MR001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHZ-403-SWITCH"));
    }

    @Test
    void theSessionIdRotatesOnScopeSwitchAndTheOldIdIsDead() throws Exception {
        Session session = login("ddo.multi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        String before = session.cookie();
        mvc.perform(
                        userPost(
                                session,
                                "/auth/switch-scope",
                                Map.of("role", "PAYROLL_CHECKER", "location", "MR001")))
                .andExpect(status().isNoContent());
        assertThat(session.cookie()).isNotNull().isNotEqualTo(before);
        mvc.perform(get("/auth/me").cookie(new jakarta.servlet.http.Cookie(SESSION_COOKIE, before)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aStateChangingCallWithoutTheCsrfTokenIsRefused() throws Exception {
        mvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "loginId",
                                                        "agm.south@fci-demo.test",
                                                        "password",
                                                        DEMO_PASSWORD))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/auth/csrf")).andExpect(status().isNoContent());
    }

    // SC-01-0141: forgot password, reset by OTP self-service.
    @Test
    void forgotPasswordSendsAnOtpAndTheResetEndsEverySession() throws Exception {
        String login = "ldh.depot@fci-demo.test";
        // The IT database is shared: put the demo password back so later suites still sign in.
        Map<String, Object> saved =
                jdbc.queryForMap(
                        "SELECT c.user_id, c.password_hash, c.hash_algorithm, c.password_changed_at"
                                + " FROM app_user_credential c JOIN app_user u ON u.user_id ="
                                + " c.user_id WHERE u.login_id = ?",
                        login);
        try {
            resetsThePassword(login);
        } finally {
            jdbc.update(
                    "UPDATE app_user_credential SET password_hash = ?, hash_algorithm = ?,"
                            + " password_changed_at = ?, failed_attempt_count = 0, locked_until ="
                            + " NULL WHERE user_id = ?",
                    saved.get("PASSWORD_HASH"),
                    saved.get("HASH_ALGORITHM"),
                    saved.get("PASSWORD_CHANGED_AT"),
                    saved.get("USER_ID"));
        }
    }

    private void resetsThePassword(String login) throws Exception {
        Session before = login(login, null, null);
        mvc.perform(anonymousPost("/auth/password/forgot", Map.of("loginId", login)))
                .andExpect(status().isAccepted());
        // No account enumeration: an unknown identifier gets the same answer.
        mvc.perform(
                        anonymousPost(
                                "/auth/password/forgot", Map.of("loginId", "nobody@fci-demo.test")))
                .andExpect(status().isAccepted());
        String email =
                jdbc.queryForObject(
                        "SELECT registered_email FROM app_user WHERE login_id = ?",
                        String.class,
                        login);
        String code = OTP_CODES.get(email + "|AUTH_OWN_PASSWORD_CHANGE");
        assertThat(code).matches("^[0-9]{6}$");
        String wrong = code.equals("000000") ? "000001" : "000000";

        // Security review 2026-10-31: without the right code the endpoint tells nothing about the
        // account. The CURRENT password with a wrong code is "wrong code", not "reused" (no
        // password oracle); a password the policy refuses for any account is refused alike for an
        // unknown login (no account discovery).
        mvc.perform(
                        anonymousPost(
                                "/auth/password/reset",
                                Map.of(
                                        "loginId",
                                        login,
                                        "code",
                                        wrong,
                                        "newPassword",
                                        DEMO_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH-400-OTP"));
        String known =
                mvc.perform(
                                anonymousPost(
                                        "/auth/password/reset",
                                        Map.of(
                                                "loginId",
                                                login,
                                                "code",
                                                wrong,
                                                "newPassword",
                                                "aaaaaaaaaaaaaaaa")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String unknown =
                mvc.perform(
                                anonymousPost(
                                        "/auth/password/reset",
                                        Map.of(
                                                "loginId",
                                                "nobody@fci-demo.test",
                                                "code",
                                                wrong,
                                                "newPassword",
                                                "aaaaaaaaaaaaaaaa")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(json.readTree(known).get("errorCode"))
                .isEqualTo(json.readTree(unknown).get("errorCode"));

        mvc.perform(
                        anonymousPost(
                                "/auth/password/reset",
                                Map.of(
                                        "loginId",
                                        login,
                                        "code",
                                        wrong,
                                        "newPassword",
                                        "Neem#Tree-Shade-42")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH-400-OTP"));
        mvc.perform(
                        anonymousPost(
                                "/auth/password/reset",
                                Map.of(
                                        "loginId",
                                        login,
                                        "code",
                                        code,
                                        "newPassword",
                                        "Neem#Tree-Shade-42")))
                .andExpect(status().isNoContent());
        mvc.perform(userGet(before, "/auth/me")).andExpect(status().isUnauthorized());
        assertThat(loginRaw(login, "Neem#Tree-Shade-42", null, null).getResponse().getStatus())
                .isEqualTo(200);
        // A used code stays used.
        mvc.perform(
                        anonymousPost(
                                "/auth/password/reset",
                                Map.of(
                                        "loginId",
                                        login,
                                        "code",
                                        code,
                                        "newPassword",
                                        "Peepal#Tree-Shade-43")))
                .andExpect(status().isBadRequest());
    }

    private void changePassword(
            Session session, String current, String next, int expected, String code)
            throws Exception {
        // SRS-G-21: the change is confirmed with the user's OTP (428 with the challenge first).
        MvcResult result =
                submitWithOtp(
                        session,
                        "POST",
                        "/auth/password/change",
                        Map.of("currentPassword", current, "newPassword", next),
                        "AUTH_OWN_PASSWORD_CHANGE");
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(expected);
        if (code != null) {
            assertThat(body(result).get("errorCode").asString()).isEqualTo(code);
        }
    }

    /**
     * SRS-SEC-13 (a password expires after 90 days) with SRS-G-21 / X-08 l.894 (US-GAP-128): a
     * sign-in with an expired password opens a session that may only change the password; every
     * other call is refused AUTH-403-PASSWORD-CHANGE; the change needs the OTP (428 without it) and
     * then opens the workspace. The credential is restored afterwards for the other ITs.
     */
    @Test
    void SRS_SEC_13_anExpiredPasswordOffersOnlyThePasswordChangeWithOtp() throws Exception {
        String login = "agmcpf.zos@fci-demo.test";
        long id =
                jdbc.queryForObject(
                        "SELECT user_id FROM app_user WHERE login_id = ?", Long.class, login);
        Map<String, Object> saved =
                jdbc.queryForMap(
                        "SELECT password_hash, password_changed_at FROM app_user_credential WHERE"
                                + " user_id = ?",
                        id);
        jdbc.update(
                "UPDATE app_user_credential SET password_changed_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(
                        java.time.Instant.now().minus(java.time.Duration.ofDays(91))),
                id);
        try {
            Session session = login(login, null, null);
            assertThat(session.passwordChangeRequired()).isTrue();
            mvc.perform(userGet(session, "/v1/inbox"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-403-PASSWORD-CHANGE"));
            MvcResult withoutOtp =
                    mvc.perform(
                                    userPost(
                                            session,
                                            "/auth/password/change",
                                            Map.of(
                                                    "currentPassword",
                                                    DEMO_PASSWORD,
                                                    "newPassword",
                                                    "Jamun#Tree-Shade-58")))
                            .andReturn();
            assertThat(withoutOtp.getResponse().getStatus()).isEqualTo(428);
            assertThat(body(withoutOtp).get("errorCode").asString()).isEqualTo("AUTH-428-OTP");
            changePassword(session, DEMO_PASSWORD, "Jamun#Tree-Shade-58", 204, null);
            mvc.perform(userGet(session, "/auth/me"))
                    .andExpect(jsonPath("$.passwordChangeRequired").value(false));
        } finally {
            jdbc.update(
                    "UPDATE app_user_credential SET password_hash = ?, password_changed_at = ?,"
                            + " must_change_flag = 'N' WHERE user_id = ?",
                    saved.get("PASSWORD_HASH"),
                    saved.get("PASSWORD_CHANGED_AT"),
                    id);
            jdbc.update("DELETE FROM app_user_password_history WHERE user_id = ?", id);
        }
    }

    private void expectLoginError(String loginId, int status, String code) throws Exception {
        MvcResult result = loginRaw(loginId, DEMO_PASSWORD, null, null);
        assertThat(result.getResponse().getStatus()).as(loginId).isEqualTo(status);
        assertThat(body(result).get("errorCode").asString()).isEqualTo(code);
    }

    // ---------------------------------------------------------------- edges (2026-10-23)

    @Test
    void SRS_G_49_aSuspendedAccountIsRefusedWithItsOwnReason() throws Exception {
        expectLoginError("rbac.suspended@fci-demo.test", 403, "AUTH-403-SUSPENDED");
    }

    @Test
    void SRS_G_48_anEmailSharedByTwoAccountsSignsNeitherInButEachLoginIdStillWorks()
            throws Exception {
        String hash =
                jdbc.queryForObject(
                        "SELECT c.password_hash FROM app_user_credential c JOIN app_user u ON"
                                + " u.user_id = c.user_id WHERE u.login_id ="
                                + " 'maker.delhi@fci-demo.test'",
                        String.class);
        for (String login : List.of("twin.one", "twin.two")) {
            jdbc.update(
                    "INSERT INTO app_user (login_id, display_name, user_category,"
                            + " registered_email, user_status) VALUES (?, ?, 'OPERATIONS',"
                            + " 'twin@fci-demo.test', 'ACTIVE')",
                    login,
                    "Twin " + login);
            jdbc.update(
                    "INSERT INTO app_user_credential (user_id, password_hash, hash_algorithm,"
                            + " must_change_flag) VALUES ((SELECT user_id FROM app_user WHERE"
                            + " login_id = ?), ?, 'BCRYPT', 'N')",
                    login,
                    hash);
        }
        // By e-mail it is ambiguous: the same 401 as an unknown login, nothing revealed.
        expectLoginError("twin@fci-demo.test", 401, "AUTH-401-CREDENTIALS");
        // By login id the account is found; without an access group it has nowhere to start.
        expectLoginError("twin.one", 403, "AUTH-403-NO-SCOPE");
        expectLoginError("no-such-login", 401, "AUTH-401-CREDENTIALS");
    }

    @Test
    void SRS_G_48_aSignInNamingOnlyAnOfficeStartsInARoleThereAndAnOfficeOutsideIsRefused()
            throws Exception {
        MvcResult atDelhi = loginRaw("maker.delhi@fci-demo.test", DEMO_PASSWORD, null, "DH001");
        assertThat(atDelhi.getResponse().getStatus()).isEqualTo(200);
        Session session = sessionOf(atDelhi, "maker.delhi@fci-demo.test", body(atDelhi));
        mvc.perform(userGet(session, "/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location.code").value("DH001"));
        MvcResult elsewhere = loginRaw("maker.delhi@fci-demo.test", DEMO_PASSWORD, null, "KL001");
        assertThat(elsewhere.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(elsewhere).get("errorCode").asString()).isEqualTo("AUTH-403-LOCATION");
    }

    // ADR-0008: a password stored with outdated parameters is re-hashed at the next sign-in.
    @Test
    void aPasswordStoredWithAnOlderHashIsUpgradedAtTheNextSignIn() throws Exception {
        String login = "report.admin@fci-demo.test";
        long id =
                jdbc.queryForObject(
                        "SELECT user_id FROM app_user WHERE login_id = ?", Long.class, login);
        String legacy =
                "{bcrypt}"
                        + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4)
                                .encode(DEMO_PASSWORD);
        jdbc.update(
                "UPDATE app_user_credential SET password_hash = ? WHERE user_id = ?", legacy, id);
        assertThat(loginRaw(login, DEMO_PASSWORD, null, null).getResponse().getStatus())
                .isEqualTo(200);
        String now =
                jdbc.queryForObject(
                        "SELECT password_hash FROM app_user_credential WHERE user_id = ?",
                        String.class,
                        id);
        assertThat(now).startsWith("{argon2}").isNotEqualTo(legacy);
        assertThat(loginRaw(login, DEMO_PASSWORD, null, null).getResponse().getStatus())
                .isEqualTo(200);
    }

    // SRS-G-49: an account without a registered e-mail is still locked; no notice can be sent.
    @Test
    void anAccountWithoutAnEmailIsLockedAtTheFifthFailure() throws Exception {
        String email = "tax.ruleadmin@fci-demo.test";
        long id =
                jdbc.queryForObject(
                        "SELECT user_id FROM app_user WHERE login_id = ?", Long.class, email);
        // Signs in by a login ID (an identifier with '@' is looked up by the registered e-mail).
        jdbc.update(
                "UPDATE app_user SET registered_email = NULL, login_id = 'taxruleadmin' WHERE"
                        + " user_id = ?",
                id);
        try {
            for (int i = 1; i <= 4; i++) {
                assertThat(
                                loginRaw("taxruleadmin", "Wrong@Password" + i, null, null)
                                        .getResponse()
                                        .getStatus())
                        .isEqualTo(401);
            }
            assertThat(
                            loginRaw("taxruleadmin", "Wrong@Password5", null, null)
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(423);
        } finally {
            jdbc.update(
                    "UPDATE app_user_credential SET failed_attempt_count = 0, locked_until = NULL"
                            + " WHERE user_id = ?",
                    id);
            jdbc.update(
                    "UPDATE app_user SET registered_email = ?, login_id = ? WHERE user_id = ?",
                    email,
                    email,
                    id);
        }
        assertThat(loginRaw(email, DEMO_PASSWORD, null, null).getResponse().getStatus())
                .isEqualTo(200);
    }
}
