package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.security.SecureTokens;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/**
 * The session's client binding (OWASP Session Management Cheat Sheet): another browser is always a
 * different client; another address only when address binding is on; a session without a
 * fingerprint (started before the binding existed) and a request without a session are not judged.
 */
class WebSessionsBindingTest {

    private static MockHttpServletRequest request(
            MockHttpSession session, String agent, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        if (agent != null) {
            request.addHeader("User-Agent", agent);
        }
        request.setRemoteAddr(address);
        return request;
    }

    private static MockHttpSession bound(String agent, String address) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(WebSessions.CLIENT_AGENT, SecureTokens.sha256Hex(agent));
        session.setAttribute(WebSessions.CLIENT_ADDRESS, SecureTokens.sha256Hex(address));
        return session;
    }

    @Test
    void theSigningInBrowserAtTheSameAddressIsTheSameClient() {
        MockHttpSession session = bound("Browser-A", "10.0.0.5");
        assertThat(WebSessions.clientChanged(request(session, "Browser-A", "10.0.0.5"), true))
                .isFalse();
    }

    @Test
    void anotherBrowserIsAnotherClientWhateverTheAddressSetting() {
        MockHttpSession session = bound("Browser-A", "10.0.0.5");
        assertThat(WebSessions.clientChanged(request(session, "Browser-B", "10.0.0.5"), false))
                .isTrue();
        assertThat(WebSessions.clientChanged(request(session, "Browser-B", "10.0.0.5"), true))
                .isTrue();
        // A missing User-Agent is not the signing-in browser either.
        assertThat(WebSessions.clientChanged(request(session, null, "10.0.0.5"), false)).isTrue();
    }

    @Test
    void anotherAddressCountsOnlyWithAddressBinding() {
        MockHttpSession session = bound("Browser-A", "10.0.0.5");
        assertThat(WebSessions.clientChanged(request(session, "Browser-A", "10.9.9.9"), false))
                .isFalse();
        assertThat(WebSessions.clientChanged(request(session, "Browser-A", "10.9.9.9"), true))
                .isTrue();
    }

    @Test
    void aSessionWithoutFingerprintOrNoSessionIsNotJudged() {
        assertThat(
                        WebSessions.clientChanged(
                                request(new MockHttpSession(), "Any", "1.1.1.1"), true))
                .isFalse();
        MockHttpServletRequest noSession = new MockHttpServletRequest();
        assertThat(WebSessions.clientChanged(noSession, true)).isFalse();
    }
}
