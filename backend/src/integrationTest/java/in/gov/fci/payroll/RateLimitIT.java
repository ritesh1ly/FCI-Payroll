package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The per-user sign-in rate limit (ADR-0008), in a context of its own with a limit of one a minute
 * (the token bucket refills continuously, so a limit the test could reach only slowly would be
 * flaky): the attempt beyond the limit is refused with 429 and Retry-After before the password is
 * even checked, so the limiter cannot be used to probe passwords. The OTP issuance cap per user and
 * action and hour (SRS-G-22) is checked here too (the shared IT profile raises it).
 */
@TestPropertySource(
        properties = {
            "fci.api.rate-limits.login-per-ip-per-minute=10",
            "fci.api.rate-limits.login-per-username-per-minute=1",
            "fci.api.otp.max-per-hour=2"
        })
class RateLimitIT extends ApiTestSupport {

    @Test
    void signInsBeyondTheLimitForOneUserAreRefusedWith429() throws Exception {
        String login = "agm.north@fci-demo.test";
        assertThat(loginRaw(login, DEMO_PASSWORD, null, null).getResponse().getStatus())
                .isEqualTo(200);
        MvcResult beyondLimit = loginRaw(login, DEMO_PASSWORD, null, null);
        assertThat(beyondLimit.getResponse().getStatus()).isEqualTo(429);
        JsonNode problem = body(beyondLimit);
        assertThat(problem.get("errorCode").asString()).isEqualTo("COM-429-RATE-LIMIT");
        assertThat(beyondLimit.getResponse().getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void SRS_G_22_otpChallengesBeyondTheHourlyCapAreRefusedWith429() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        long subject =
                jdbc.queryForObject(
                        "SELECT user_id FROM app_user WHERE login_id = 'agm.north@fci-demo.test'",
                        Long.class);
        long group =
                jdbc.queryForObject(
                        "SELECT access_group_id FROM access_group WHERE group_code = 'AG-PC-MR001'",
                        Long.class);
        java.util.Map<String, Object> request =
                java.util.Map.of("userId", subject, "groupId", group, "reason", "Cap test");
        for (int i = 0; i < 2; i++) {
            assertThat(
                            mvc.perform(userPost(admin, "/v1/admin/assignments", request))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus())
                    .as("challenge %d", i + 1)
                    .isEqualTo(428);
        }
        MvcResult third =
                mvc.perform(userPost(admin, "/v1/admin/assignments", request)).andReturn();
        assertThat(third.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(third).get("errorCode").asString()).isEqualTo("COM-429-RATE-LIMIT");
    }
}
