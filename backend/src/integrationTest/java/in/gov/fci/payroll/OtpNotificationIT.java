package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SRS-G-21 / SRS-G-49 with the project owner's decision of 2026-10-21: three wrong one-time
 * passwords on one challenge alert the user by e-mail and SMS and do NOT lock the account; every
 * verified one-time password is notified the same way. No code is ever in a notice.
 */
class OtpNotificationIT extends ApiTestSupport {

    private static final String LOGIN = "ddo.chennai@fci-demo.test";
    private static final String PURPOSE = "AUTH_LOGIN";

    private int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private MvcResult stepUp(Session session, Long challenge, String code) throws Exception {
        return mvc.perform(
                        userPost(
                                session,
                                "/auth/step-up",
                                challenge == null
                                        ? Map.of()
                                        : Map.of(
                                                "otp",
                                                Map.of("challengeId", challenge, "code", code))))
                .andReturn();
    }

    @Test
    void SRS_G_49_threeWrongCodesAlertByEmailAndSmsWithoutLockingAndAVerifiedCodeIsNotified()
            throws Exception {
        String email =
                jdbc.queryForObject(
                        "SELECT registered_email FROM app_user WHERE login_id = ?",
                        String.class,
                        LOGIN);
        String mobile =
                jdbc.queryForObject(
                        "SELECT registered_mobile FROM app_user WHERE login_id = ?",
                        String.class,
                        LOGIN);
        String key = email + "|" + PURPOSE;
        int alertsBefore = OTP_ALERTS.getOrDefault(key, 0);
        int verifiedBefore = OTP_VERIFIED.getOrDefault(key, 0);
        int smsBefore = SMS.getOrDefault(mobile, List.of()).size();

        Session checker = loginWithoutStepUp(LOGIN, null, null);
        MvcResult asked = stepUp(checker, null, null);
        assertThat(status(asked)).isEqualTo(428);
        long challenge = body(asked).get("args").get(0).asLong();
        String right = OTP_CODES.get(key);
        String wrong = "000000".equals(right) ? "111111" : "000000";

        assertThat(status(stepUp(checker, challenge, wrong))).isEqualTo(400);
        assertThat(status(stepUp(checker, challenge, wrong))).isEqualTo(400);
        assertThat(OTP_ALERTS.getOrDefault(key, 0))
                .as("no alert before the third")
                .isEqualTo(alertsBefore);
        assertThat(status(stepUp(checker, challenge, wrong))).isEqualTo(400);
        assertThat(OTP_ALERTS.getOrDefault(key, 0)).isEqualTo(alertsBefore + 1);
        List<String> texts = SMS.getOrDefault(mobile, List.of());
        assertThat(texts).hasSize(smsBefore + 1);
        assertThat(texts.get(texts.size() - 1)).contains("3 wrong OTPs").doesNotContain(right);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT user_status FROM app_user WHERE login_id = ?",
                                String.class,
                                LOGIN))
                .as("the account is not locked")
                .isEqualTo("ACTIVE");

        // The right code still works on the same challenge (two attempts left of five).
        assertThat(status(stepUp(checker, challenge, right))).isEqualTo(204);
        assertThat(OTP_VERIFIED.getOrDefault(key, 0)).isEqualTo(verifiedBefore + 1);
        texts = SMS.getOrDefault(mobile, List.of());
        assertThat(texts).hasSize(smsBefore + 2);
        assertThat(texts.get(texts.size() - 1)).contains("verified").doesNotContain(right);
    }
}
