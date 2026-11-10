package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * X-08 sign-in with OTP (SRS-G-48, SRS-G-21; owner 2026-10-29, OD-36 / OD-37), in a context with
 * the sign-in OTP on: the right user ID and password send a six-digit OTP to the registered mobile
 * and open no session; the code opens it (step-up confirmed, the challenge on the session); a wrong
 * code is refused and counted, a used code never works twice, a code without this browser's pending
 * sign-in is refused, a resend makes the earlier code useless; a wrong password sends nothing; the
 * Employee ID (EIS) signs in its own account and an unknown one is unknown; a user without a mobile
 * gets the code by e-mail; an account locked meanwhile is not signed in by its code.
 */
@TestPropertySource(properties = "fci.api.otp.at-login=true")
class LoginOtpIT extends ApiTestSupport {

    private static final String USER = "agm.west@fci-demo.test";

    private String mobileOf(String login) {
        return jdbc.queryForObject(
                "SELECT registered_mobile FROM app_user WHERE login_id = ?", String.class, login);
    }

    /** The newest login OTP the SMS gateway (captured) received for {@code login}. */
    private String smsCode(String login) {
        return SMS_OTP.get(mobileOf(login) + "|AUTH_LOGIN");
    }

    /** Step 1; returns the response (202 with the pre-login session cookie, or a refusal). */
    private MvcResult password(Map<String, Object> body) throws Exception {
        return mvc.perform(anonymousPost("/auth/login", body)).andReturn();
    }

    private static Map<String, Object> credentials(String login, String password, String idType) {
        Map<String, Object> body = new HashMap<>();
        body.put("loginId", login);
        body.put("password", password);
        if (idType != null) {
            body.put("idType", idType);
        }
        return body;
    }

    private static String cookieOf(MvcResult result) {
        Cookie c = result.getResponse().getCookie(SESSION_COOKIE);
        return c == null ? null : c.getValue();
    }

    private MvcResult otp(String cookie, String code) throws Exception {
        var request =
                withCsrf(post("/auth/login/otp"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", code)));
        if (cookie != null) {
            request.cookie(new Cookie(SESSION_COOKIE, cookie));
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult resend(String cookie) throws Exception {
        return mvc.perform(
                        withCsrf(post("/auth/login/otp/resend"))
                                .cookie(new Cookie(SESSION_COOKIE, cookie)))
                .andReturn();
    }

    private int me(String cookie) throws Exception {
        return mvc.perform(get("/auth/me").cookie(new Cookie(SESSION_COOKIE, cookie)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String errorCode(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    @Test
    void SRS_G_48_thePasswordSendsAnOtpToTheMobileAndTheCodeOpensTheSession() throws Exception {
        MvcResult first = password(credentials(USER, DEMO_PASSWORD, null));
        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        JsonNode sent = body(first);
        assertThat(sent.get("otpRequired").asBoolean()).isTrue();
        assertThat(sent.get("channel").asString()).isEqualTo("SMS");
        String mobile = mobileOf(USER);
        assertThat(sent.get("destination").asString())
                .isEqualTo("******" + mobile.substring(mobile.length() - 4))
                .doesNotContain(mobile.substring(0, 4));
        assertThat(sent.get("validSeconds").asInt()).isPositive();
        assertThat(sent.toString()).as("the code never travels back").doesNotContain(smsCode(USER));
        String cookie = cookieOf(first);
        assertThat(cookie).as("the pre-login session").isNotNull();
        assertThat(me(cookie)).as("no session before the OTP").isEqualTo(401);

        MvcResult signed = otp(cookie, smsCode(USER));
        assertThat(signed.getResponse().getStatus())
                .as(signed.getResponse().getContentAsString())
                .isEqualTo(200);
        String session = cookieOf(signed);
        assertThat(session).as("a new session id (fixation)").isNotNull().isNotEqualTo(cookie);
        assertThat(me(session)).isEqualTo(200);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT login_otp_challenge_id, step_up_at FROM app_session WHERE user_id ="
                                + " (SELECT user_id FROM app_user WHERE login_id = ?) AND ended_at"
                                + " IS NULL ORDER BY started_at DESC FETCH FIRST 1 ROWS ONLY",
                        USER);
        assertThat(row.get("LOGIN_OTP_CHALLENGE_ID"))
                .as("the challenge on the session")
                .isNotNull();
        assertThat(row.get("STEP_UP_AT"))
                .as("the step-up is confirmed by the sign-in OTP")
                .isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT delivery_channel FROM otp_challenge WHERE"
                                        + " otp_challenge_id = ?",
                                String.class,
                                row.get("LOGIN_OTP_CHALLENGE_ID")))
                .isEqualTo("SMS");
    }

    @Test
    void SRS_G_48_aWrongCodeIsRefusedAndAUsedOrUnaskedCodeNeverSignsIn() throws Exception {
        MvcResult first = password(credentials(USER, DEMO_PASSWORD, null));
        String cookie = cookieOf(first);
        String code = smsCode(USER);
        String wrong = code.equals("000000") ? "111111" : "000000";
        MvcResult refused = otp(cookie, wrong);
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(refused)).isEqualTo("AUTH-400-OTP");
        assertThat(me(cookie)).isEqualTo(401);
        assertThat(otp(cookie, code).getResponse().getStatus())
                .as("still pending: the right code after one wrong one")
                .isEqualTo(200);
        assertThat(errorCode(otp(cookie, code)))
                .as("used: the pending sign-in is gone")
                .isEqualTo("AUTH-400-OTP");
        assertThat(errorCode(otp(null, code)))
                .as("another browser (no pending sign-in)")
                .isEqualTo("AUTH-400-OTP");
        assertThat(otp(cookie, "12ab56").getResponse().getStatus())
                .as("not six digits")
                .isEqualTo(400);
    }

    @Test
    void SRS_G_48_aResendMakesTheEarlierCodeUseless() throws Exception {
        MvcResult first = password(credentials(USER, DEMO_PASSWORD, null));
        String cookie = cookieOf(first);
        String earlier = smsCode(USER);
        MvcResult again = resend(cookie);
        assertThat(again.getResponse().getStatus()).isEqualTo(202);
        String later = smsCode(USER);
        if (!later.equals(earlier)) {
            assertThat(errorCode(otp(cookie, earlier))).isEqualTo("AUTH-400-OTP");
        }
        assertThat(otp(cookie, later).getResponse().getStatus()).isEqualTo(200);
        assertThat(
                        mvc.perform(withCsrf(post("/auth/login/otp/resend")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("no pending sign-in to resend")
                .isEqualTo(400);
    }

    @Test
    void SRS_G_49_aWrongPasswordSendsNoOtp() throws Exception {
        SMS_OTP.remove(mobileOf(USER) + "|AUTH_LOGIN");
        MvcResult refused = password(credentials(USER, "Wrong#Pass2026", null));
        assertThat(refused.getResponse().getStatus()).isEqualTo(401);
        assertThat(smsCode(USER)).isNull();
    }

    @Test
    void SRS_G_35_theEmployeeIdSignsInItsOwnAccountAndAnUnknownOneIsUnknown() throws Exception {
        String eis =
                jdbc.queryForObject(
                        "SELECT e.emp_num FROM app_user u JOIN employee e ON e.employee_id ="
                                + " u.employee_id WHERE u.login_id = 'emp.rsharma@fci-demo.test'",
                        String.class);
        MvcResult first = password(credentials(eis, DEMO_PASSWORD, "EMPLOYEE_ID"));
        assertThat(first.getResponse().getStatus())
                .as(first.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(
                        otp(cookieOf(first), smsCode("emp.rsharma@fci-demo.test"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(errorCode(password(credentials("NO-SUCH-EIS", DEMO_PASSWORD, "EMPLOYEE_ID"))))
                .isEqualTo("AUTH-401-CREDENTIALS");
        assertThat(password(credentials(eis, DEMO_PASSWORD, "BADGE")).getResponse().getStatus())
                .as("an unknown identifier type")
                .isEqualTo(400);
    }

    @Test
    void SRS_G_48_aUserWithoutAMobileGetsTheCodeByEmail() throws Exception {
        String login = "agm.south@fci-demo.test";
        String mobile = mobileOf(login);
        jdbc.update("UPDATE app_user SET registered_mobile = NULL WHERE login_id = ?", login);
        try {
            JsonNode sent = body(password(credentials(login, DEMO_PASSWORD, null)));
            assertThat(sent.get("channel").asString()).isEqualTo("EMAIL");
            assertThat(sent.get("destination").asString()).doesNotContain("agm.south");
        } finally {
            jdbc.update(
                    "UPDATE app_user SET registered_mobile = ? WHERE login_id = ?", mobile, login);
        }
    }

    /**
     * UC-07 precondition (SRS l.576), SRS-G-3 (US-GAP-118): without a registered mobile or e-mail
     * no code is sent anywhere and the sign-in stops with the reason; no session opens.
     */
    @Test
    void SRS_G_3_aUserWithoutARegisteredContactGetsNoCodeAndNoFallback() throws Exception {
        String login = "agm.south@fci-demo.test";
        String mobile = mobileOf(login);
        long id =
                jdbc.queryForObject(
                        "SELECT user_id FROM app_user WHERE login_id = ?", Long.class, login);
        jdbc.update(
                // Signed in by a login ID (an identifier with '@' is looked up by the e-mail).
                "UPDATE app_user SET registered_mobile = NULL, registered_email = NULL, login_id ="
                        + " 'agmsouth' WHERE user_id = ?",
                id);
        String sessions = "SELECT COUNT(*) FROM app_session WHERE user_id = ?";
        Integer before = jdbc.queryForObject(sessions, Integer.class, id);
        try {
            MvcResult refused = password(credentials("agmsouth", DEMO_PASSWORD, null));
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused).get("errorCode").asString()).isEqualTo("AUTH-409-NO-CONTACT");
            assertThat(jdbc.queryForObject(sessions, Integer.class, id))
                    .as("no session opened")
                    .isEqualTo(before);
        } finally {
            jdbc.update(
                    "UPDATE app_user SET registered_mobile = ?, registered_email = ?, login_id = ?"
                            + " WHERE user_id = ?",
                    mobile,
                    login,
                    login,
                    id);
        }
    }

    @Test
    void SRS_G_49_anAccountLockedMeanwhileIsNotSignedInByItsCode() throws Exception {
        String login = "agm.east@fci-demo.test";
        MvcResult first = password(credentials(login, DEMO_PASSWORD, null));
        jdbc.update(
                "UPDATE app_user_credential SET locked_until = CURRENT_TIMESTAMP + INTERVAL '1'"
                        + " HOUR WHERE user_id = (SELECT user_id FROM app_user WHERE login_id = ?)",
                login);
        try {
            MvcResult refused = otp(cookieOf(first), smsCode(login));
            assertThat(refused.getResponse().getStatus()).isEqualTo(423);
        } finally {
            jdbc.update(
                    "UPDATE app_user_credential SET locked_until = NULL, failed_attempt_count = 0"
                            + " WHERE user_id = (SELECT user_id FROM app_user WHERE login_id = ?)",
                    login);
        }
    }

    /** OD-41: "Sign in with OTP on registered mobile": the mobile names the account. */
    @Test
    void SRS_G_48_theRegisteredMobileAndThePasswordSendTheCodeToThatMobile() throws Exception {
        String login = "agm.north@fci-demo.test";
        String mobile = mobileOf(login);
        MvcResult first = password(credentials("+91 " + mobile, DEMO_PASSWORD, "MOBILE"));
        assertThat(first.getResponse().getStatus())
                .as(first.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(body(first).get("channel").asString()).isEqualTo("SMS");
        assertThat(otp(cookieOf(first), smsCode(login)).getResponse().getStatus()).isEqualTo(200);
        assertThat(errorCode(password(credentials("9999999999", DEMO_PASSWORD, "MOBILE"))))
                .as("a mobile no account carries")
                .isEqualTo("AUTH-401-CREDENTIALS");
    }

    private MvcResult mobile(String number) throws Exception {
        return mvc.perform(anonymousPost("/auth/login/mobile", Map.of("mobile", number)))
                .andReturn();
    }

    /**
     * OD-41: the mobile and its code, no password; the session opens with its step-up confirmed.
     */
    @Test
    void SRS_G_48_theRegisteredMobileAndItsCodeAloneSignIn() throws Exception {
        String login = "agm.north@fci-demo.test";
        String number = mobileOf(login);
        assertThat(
                        body(mvc.perform(get("/auth/login-options")).andReturn())
                                .get("mobileOtp")
                                .asBoolean())
                .isTrue();
        MvcResult sent = mobile("+91 " + number);
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(body(sent).get("destination").asString())
                .isEqualTo("******" + number.substring(6));
        MvcResult signed = otp(cookieOf(sent), smsCode(login));
        assertThat(signed.getResponse().getStatus()).isEqualTo(200);
        assertThat(me(cookieOf(signed))).isEqualTo(200);
    }

    /** OD-41, no enumeration: an unregistered number gets the same answer; no code completes it. */
    @Test
    void SRS_G_48_anUnregisteredMobileIsAnsweredLikeARegisteredOne() throws Exception {
        MvcResult sent = mobile("9111122222");
        assertThat(sent.getResponse().getStatus()).isEqualTo(202);
        JsonNode answer = body(sent);
        assertThat(answer.get("otpRequired").asBoolean()).isTrue();
        assertThat(answer.get("channel").asString()).isEqualTo("SMS");
        assertThat(answer.get("destination").asString()).isEqualTo("******2222");
        assertThat(SMS_OTP).doesNotContainKey("9111122222|AUTH_LOGIN");
        String cookie = cookieOf(sent);
        assertThat(errorCode(otp(cookie, "123456"))).isEqualTo("AUTH-400-OTP");
        assertThat(resend(cookie).getResponse().getStatus()).isEqualTo(202);
        assertThat(
                        mvc.perform(anonymousPost("/auth/login/mobile", Map.of("mobile", "")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("a number is required")
                .isEqualTo(400);
    }

    /** OD-41: a locked account learns it only with the right code; a wrong one says nothing. */
    @Test
    void SRS_G_49_aLockedAccountIsToldOnlyAfterTheRightCode() throws Exception {
        String login = "agm.south@fci-demo.test";
        MvcResult sent = mobile(mobileOf(login));
        jdbc.update(
                "UPDATE app_user_credential SET locked_until = CURRENT_TIMESTAMP + INTERVAL '1'"
                        + " HOUR WHERE user_id = (SELECT user_id FROM app_user WHERE login_id = ?)",
                login);
        try {
            String code = smsCode(login);
            String wrong = code.equals("000000") ? "111111" : "000000";
            assertThat(errorCode(otp(cookieOf(sent), wrong))).isEqualTo("AUTH-400-OTP");
            assertThat(otp(cookieOf(sent), code).getResponse().getStatus()).isEqualTo(423);
        } finally {
            jdbc.update(
                    "UPDATE app_user_credential SET locked_until = NULL, failed_attempt_count = 0"
                            + " WHERE user_id = (SELECT user_id FROM app_user WHERE login_id = ?)",
                    login);
        }
    }
}
