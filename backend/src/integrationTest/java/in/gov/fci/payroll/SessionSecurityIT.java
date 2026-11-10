package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Session robustness against OWASP ASVS 4.0 V3 and the OWASP Session Management Cheat Sheet: cookie
 * attributes (V3.4.1–V3.4.5, {@code __Host-} prefix), a session cookie replayed from another
 * browser refused and the session ended (hijacking), the session id never accepted from a URL
 * (V3.1.1), and the rotation and sign-out rules (V3.2.1, V3.3.1).
 */
class SessionSecurityIT extends ApiTestSupport {

    private static final String LOGIN = "rbac.maker.ludhiana@fci-demo.test";
    private static final String BROWSER_A =
            "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0 Safari/537.36";
    private static final String BROWSER_B = "Mozilla/5.0 (X11; Linux x86_64) Firefox/131.0";

    private MvcResult signIn(String userAgent) throws Exception {
        return mvc.perform(
                        anonymousPost(
                                        "/auth/login",
                                        Map.of("loginId", LOGIN, "password", DEMO_PASSWORD))
                                .header("User-Agent", userAgent))
                .andReturn();
    }

    private int me(String cookie, String userAgent) throws Exception {
        return mvc.perform(
                        get("/auth/me")
                                .cookie(new Cookie(SESSION_COOKIE, cookie))
                                .header("User-Agent", userAgent))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void theSessionCookieIsHostPrefixedHttpOnlySecureAndStrict() throws Exception {
        MvcResult result = signIn(BROWSER_A);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
        String session =
                setCookies.stream()
                        .filter(c -> c.startsWith(SESSION_COOKIE + "="))
                        .findFirst()
                        .orElseThrow();
        assertThat(session)
                .startsWith("__Host-")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/")
                .doesNotContainIgnoringCase("Domain=");
        // A session cookie only: no Max-Age / Expires (it dies with the browser session).
        assertThat(session)
                .doesNotContainIgnoringCase("Max-Age")
                .doesNotContainIgnoringCase("Expires");
    }

    @Test
    void aSessionCookieReplayedFromAnotherBrowserIsRefusedAndEndsTheSession() throws Exception {
        MvcResult result = signIn(BROWSER_A);
        String cookie = result.getResponse().getCookie(SESSION_COOKIE).getValue();
        assertThat(me(cookie, BROWSER_A)).isEqualTo(200);

        long eventsBefore =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM security_event WHERE event_type ="
                                + " 'SESSION_HIJACK_SUSPECTED'",
                        Long.class);
        // The stolen cookie used from another browser: refused like an expired session.
        assertThat(me(cookie, BROWSER_B)).isEqualTo(401);
        // The session is over for everyone, the owner included (they sign in again).
        assertThat(me(cookie, BROWSER_A)).isEqualTo(401);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM security_event WHERE event_type ="
                                        + " 'SESSION_HIJACK_SUSPECTED'",
                                Long.class))
                .isEqualTo(eventsBefore + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT end_reason FROM app_session WHERE session_id = (SELECT"
                                    + " MAX(session_id) FROM app_session WHERE user_id = (SELECT"
                                    + " user_id FROM app_user WHERE login_id = ?))",
                                String.class,
                                LOGIN))
                .isEqualTo("REVOCATION");
    }

    @Test
    void theSameBrowserKeepsItsSession() throws Exception {
        String cookie = signIn(BROWSER_A).getResponse().getCookie(SESSION_COOKIE).getValue();
        for (int i = 0; i < 3; i++) {
            assertThat(me(cookie, BROWSER_A)).isEqualTo(200);
        }
    }

    @Test
    void theSessionIdIsNeverTakenFromTheUrl() throws Exception {
        String cookie = signIn(BROWSER_A).getResponse().getCookie(SESSION_COOKIE).getValue();
        int status =
                mvc.perform(get("/auth/me;jsessionid=" + cookie).header("User-Agent", BROWSER_A))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(status).isIn(400, 401, 404);
        assertThat(
                        mvc.perform(
                                        get("/auth/me?SESSION=" + cookie)
                                                .header("User-Agent", BROWSER_A))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
    }

    @Test
    void aSignInReplacesAnyPresentedSessionIdAndASignOutEndsIt() throws Exception {
        MvcResult result =
                mvc.perform(
                                anonymousPost(
                                                "/auth/login",
                                                Map.of("loginId", LOGIN, "password", DEMO_PASSWORD))
                                        .cookie(new Cookie(SESSION_COOKIE, "attacker-chosen-id"))
                                        .header("User-Agent", BROWSER_A))
                        .andReturn();
        String cookie = result.getResponse().getCookie(SESSION_COOKIE).getValue();
        assertThat(cookie).isNotEqualTo("attacker-chosen-id");
        int out =
                mvc.perform(
                                withCsrf(
                                                org.springframework.test.web.servlet.request
                                                        .MockMvcRequestBuilders.post(
                                                        "/auth/logout"))
                                        .cookie(new Cookie(SESSION_COOKIE, cookie))
                                        .header("User-Agent", BROWSER_A))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(out).isEqualTo(204);
        assertThat(me(cookie, BROWSER_A)).isEqualTo(401);
    }

    /**
     * A session ended on another instance (a revoked role, a lock, a sign-out) is refused by this
     * instance on its very next request, although this instance still holds the access context in
     * its cache: the session row is never read from the cache.
     */
    @Test
    void aSessionEndedElsewhereIsRefusedAtOnceDespiteTheCache() throws Exception {
        String cookie = signIn(BROWSER_A).getResponse().getCookie(SESSION_COOKIE).getValue();
        assertThat(me(cookie, BROWSER_A)).isEqualTo(200);
        // As another instance would: end the session row directly, without touching this cache.
        jdbc.update(
                "UPDATE app_session SET ended_at = CURRENT_TIMESTAMP, end_reason = 'REVOCATION'"
                        + " WHERE ended_at IS NULL AND user_id = (SELECT user_id FROM app_user"
                        + " WHERE login_id = ?)",
                LOGIN);
        assertThat(me(cookie, BROWSER_A)).isEqualTo(401);
    }
}
