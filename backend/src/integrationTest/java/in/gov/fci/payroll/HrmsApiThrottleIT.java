package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import in.gov.fci.payroll.platform.security.SecureTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SRS-INT-4: a key or password cannot be guessed by brute force. Three refused attempts from one
 * address in the window lock it out (429), even for the right credentials, until the window ends.
 * Its own application context: the window is two seconds here (default 300) so the end of the lock
 * can be seen, and the lock must not touch the other HRMS tests.
 */
class HrmsApiThrottleIT extends ApiTestSupport {

    private static final String KEY = "it-key-" + SecureTokens.randomToken(24);
    private static final String PASSWORD = "it-pw-" + SecureTokens.randomToken(24);
    private static final String SECRET = "it-signing-" + SecureTokens.randomToken(32);

    @DynamicPropertySource
    static void hrms(DynamicPropertyRegistry registry) {
        registry.add("fci.integration.hrms.api.token.path", () -> "/hrms-throttle/token");
        registry.add("fci.integration.hrms.security.api-key", () -> KEY);
        registry.add("fci.integration.hrms.security.client-id", () -> "throttle-client");
        registry.add(
                "fci.integration.hrms.security.client-secret-hash",
                () -> PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
        registry.add("fci.integration.hrms.security.token-secret", () -> SECRET);
        registry.add("fci.integration.hrms.security.max-failures", () -> "3");
        registry.add("fci.integration.hrms.security.failure-window-seconds", () -> "2");
    }

    private int token(String key, String password) throws Exception {
        MvcResult r =
                mvc.perform(
                                post("/hrms-throttle/token")
                                        .header("api-key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"throttle-client\",\"password\":\""
                                                        + password
                                                        + "\"}"))
                        .andReturn();
        return r.getResponse().getStatus();
    }

    @Test
    void SRS_INT_4_pastTheLimitEvenTheRightCredentialsAreAnsweredTooManyUntilTheWindowEnds()
            throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(token(KEY, "wrong-" + i)).isEqualTo(401);
        }
        assertThat(token("wrong-key", "x")).isEqualTo(429);
        assertThat(token(KEY, PASSWORD)).as("right credentials while locked").isEqualTo(429);
        Thread.sleep(2500);
        assertThat(token(KEY, PASSWORD)).as("after the window").isEqualTo(200);
        // a success clears the count: two typos, a success, two typos never lock
        for (int round = 0; round < 3; round++) {
            assertThat(token(KEY, "typo")).isEqualTo(401);
            assertThat(token(KEY, "typo")).isEqualTo(401);
            assertThat(token(KEY, PASSWORD)).isEqualTo(200);
        }
    }
}
