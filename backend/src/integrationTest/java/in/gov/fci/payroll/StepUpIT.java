package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * SRS-G-48 / SRS-G-21, Module 02 decision D-2: a screen on which the acting role holds a sensitive
 * action (approve, or change access or settings) needs the user's OTP; a confirmation is not asked
 * again until STEP_UP_VALIDITY ends (15 minutes); taking on another role needs the OTP unless one
 * is still valid; another office in the same role never does. Codes are bound to their purpose.
 */
class StepUpIT extends ApiTestSupport {

    private static final String AUTH_LOGIN = "AUTH_LOGIN";

    private String code(String loginId) {
        String email =
                jdbc.queryForObject(
                        "SELECT registered_email FROM app_user WHERE login_id = ?",
                        String.class,
                        loginId);
        return OTP_CODES.get(email + "|" + AUTH_LOGIN);
    }

    private MvcResult stepUp(Session session, Long challenge, String code) throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (challenge != null) {
            body.put("otp", Map.of("challengeId", challenge, "code", code));
        }
        return mvc.perform(userPost(session, "/auth/step-up", body)).andReturn();
    }

    private static java.util.List<String> screens(JsonNode me) {
        java.util.List<String> out = new java.util.ArrayList<>();
        me.get("sensitiveScreens").forEach(n -> out.add(n.asString()));
        return out;
    }

    private int status(Session session, String path) throws Exception {
        return mvc.perform(userGet(session, path)).andReturn().getResponse().getStatus();
    }

    @Test
    void SRS_G_48_aCheckersSensitiveScreenWaitsForTheOtpAndThenStaysOpen() throws Exception {
        Session checker = loginWithoutStepUp("ddo.delhi@fci-demo.test", null, null);
        MvcResult refused = mvc.perform(userGet(checker, "/v1/inbox")).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(refused).get("errorCode").asString()).isEqualTo("AUTH-403-STEP-UP");
        JsonNode me = body(mvc.perform(userGet(checker, "/auth/me")).andReturn());
        assertThat(me.get("stepUpRequired").asBoolean()).isTrue();
        assertThat(screens(me)).contains("W-01");

        MvcResult asked = stepUp(checker, null, null);
        assertThat(asked.getResponse().getStatus()).isEqualTo(428);
        long challenge = body(asked).get("args").get(0).asLong();
        String right = code(checker.loginId());
        MvcResult wrong = stepUp(checker, challenge, "000000".equals(right) ? "111111" : "000000");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertThat(stepUp(checker, challenge, right).getResponse().getStatus()).isEqualTo(204);

        assertThat(status(checker, "/v1/inbox")).isEqualTo(200);
        me = body(mvc.perform(userGet(checker, "/auth/me")).andReturn());
        assertThat(me.get("stepUpRequired").asBoolean()).isFalse();
        assertThat(me.get("stepUpValidUntil").isNull()).isFalse();
        // Within the validity a second step-up asks nothing.
        assertThat(stepUp(checker, null, null).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void SRS_G_48_theConfirmationExpiresAndTheUserIsAskedAgain() throws Exception {
        Session checker = login("ddo.delhi@fci-demo.test", null, null);
        assertThat(status(checker, "/v1/inbox")).isEqualTo(200);
        jdbc.update(
                "UPDATE app_session SET step_up_at = CURRENT_TIMESTAMP - INTERVAL '16' MINUTE"
                        + " WHERE ended_at IS NULL AND user_id = (SELECT user_id FROM app_user"
                        + " WHERE login_id = ?)",
                checker.loginId());
        accessContexts.evictAll();
        assertThat(status(checker, "/v1/inbox")).isEqualTo(403);
    }

    @Test
    void SRS_G_48_aRoleWithoutSensitiveActionsIsNeverAsked() throws Exception {
        Session maker = loginWithoutStepUp("maker.delhi@fci-demo.test", null, null);
        assertThat(status(maker, "/v1/off-cycle")).isEqualTo(200);
        JsonNode me = body(mvc.perform(userGet(maker, "/auth/me")).andReturn());
        assertThat(me.get("stepUpRequired").asBoolean()).isFalse();
        assertThat(screens(me)).isEmpty();
        assertThat(stepUp(maker, null, null).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void SRS_G_48_anotherRoleNeedsTheOtpBoundToThatRoleAndOffice() throws Exception {
        // Starts in the sensitive Rule Approver role, not yet confirmed.
        Session su = loginWithoutStepUp("su.hq@fci-demo.test", "RULE_APPROVER", "HQ001");
        Map<String, Object> toAdmin =
                new HashMap<>(Map.of("role", "RULE_ADMINISTRATOR", "location", "HQ001"));
        MvcResult asked = mvc.perform(userPost(su, "/auth/switch-scope", toAdmin)).andReturn();
        assertThat(asked.getResponse().getStatus()).isEqualTo(428);
        long challenge = body(asked).get("args").get(0).asLong();

        // The code of the role switch is refused for the step-up (another purpose, same action).
        assertThat(stepUp(su, challenge, code(su.loginId())).getResponse().getStatus())
                .isEqualTo(400);

        // A fresh challenge for the switch, answered correctly: the switch also confirms the
        // session.
        asked = mvc.perform(userPost(su, "/auth/switch-scope", toAdmin)).andReturn();
        challenge = body(asked).get("args").get(0).asLong();
        toAdmin.put("otp", Map.of("challengeId", challenge, "code", code(su.loginId())));
        assertThat(
                        mvc.perform(userPost(su, "/auth/switch-scope", toAdmin))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        JsonNode me = body(mvc.perform(userGet(su, "/auth/me")).andReturn());
        assertThat(me.get("role").asString()).isEqualTo("RULE_ADMINISTRATOR");

        // Back to the approver role within the validity: no second OTP, and nothing is due.
        Map<String, Object> back = Map.of("role", "RULE_APPROVER", "location", "HQ001");
        assertThat(
                        mvc.perform(userPost(su, "/auth/switch-scope", back))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        me = body(mvc.perform(userGet(su, "/auth/me")).andReturn());
        assertThat(me.get("stepUpRequired").asBoolean()).isFalse();
    }

    @Test
    void SRS_G_48_anotherOfficeInTheSameRoleNeedsNoOtp() throws Exception {
        Session multi = loginWithoutStepUp("ddo.multi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Map<String, Object> mumbai = Map.of("role", "PAYROLL_CHECKER", "location", "MR001");
        assertThat(
                        mvc.perform(userPost(multi, "/auth/switch-scope", mumbai))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
    }
}
