package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.iam.orchestration.SessionService;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * The HTTP session behind a sign-in (SRS-SEC-13 session fixation, SRS-SEC-14 idle timeout): what a
 * request without a session, or with a half-written one, reports; the fresh session a sign-in gets;
 * and the values read back from it.
 */
class WebSessionsTest {

    private static final SessionService.StartedSession STARTED =
            new SessionService.StartedSession(
                    "app-session-1",
                    42L,
                    true,
                    Instant.parse("2026-10-24T18:00:00Z"),
                    Duration.ofMinutes(10));

    private static MockHttpServletRequest withSession(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }

    @Test
    void aRequestWithoutASessionIsAnonymousAndHasNoPeriodExpiryOrTimeout() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(WebSessions.user(request)).isEmpty();
        assertThat(WebSessions.workingPeriod(request)).isEmpty();
        assertThat(WebSessions.absoluteExpiresAt(request)).isEmpty();
        assertThat(WebSessions.idleTimeoutSeconds(request)).isZero();
        WebSessions.passwordChanged(request);
        assertThat(request.getSession(false)).as("nothing to re-key").isNull();
    }

    @Test
    void aSessionIsSignedInOnlyWithBothTheUserAndTheApplicationSessionIds() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = withSession(session);
        assertThat(WebSessions.user(request)).as("empty session").isEmpty();
        session.setAttribute(WebSessions.USER_ID, "42");
        assertThat(WebSessions.user(request)).as("no application session").isEmpty();
        session.removeAttribute(WebSessions.USER_ID);
        session.setAttribute(WebSessions.APP_SESSION, "app-session-1");
        assertThat(WebSessions.user(request)).as("no user id").isEmpty();
        session.setAttribute(WebSessions.USER_ID, "42");
        assertThat(WebSessions.user(request))
                .hasValueSatisfying(
                        user -> {
                            assertThat(user.userId()).isEqualTo(42L);
                            assertThat(user.sessionId()).isEqualTo("app-session-1");
                            assertThat(user.passwordChangeRequired()).isFalse();
                        });
    }

    @Test
    void thePeriodAndTheExpiryAreReadBackOnlyOnceRecorded() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = withSession(session);
        assertThat(WebSessions.workingPeriod(request)).isEmpty();
        assertThat(WebSessions.absoluteExpiresAt(request)).isEmpty();
        WebSessions.workingPeriod(request, YearMonth.of(2026, 9));
        session.setAttribute(WebSessions.ABSOLUTE_EXPIRY, "2026-10-24T18:00:00Z");
        assertThat(WebSessions.workingPeriod(request)).contains(YearMonth.of(2026, 9));
        assertThat(WebSessions.absoluteExpiresAt(request))
                .contains(Instant.parse("2026-10-24T18:00:00Z"));
    }

    @Test
    void SRS_SEC_13_signingInReplacesThePreLoginSessionAndBindsTheClient() {
        MockHttpSession before = new MockHttpSession();
        before.setAttribute("stale", "value");
        MockHttpServletRequest request = withSession(before);
        request.addHeader("User-Agent", "Browser-A");
        request.setRemoteAddr(null);

        WebSessions.signIn(request, STARTED);

        assertThat(before.isInvalid()).as("the pre-login session is gone").isTrue();
        MockHttpSession after = (MockHttpSession) request.getSession(false);
        assertThat(after).isNotNull().isNotSameAs(before);
        assertThat(after.getAttribute("stale")).isNull();
        assertThat(WebSessions.user(request)).contains(new SessionUser(42L, "app-session-1", true));
        assertThat(WebSessions.absoluteExpiresAt(request)).contains(STARTED.absoluteExpiresAt());
        assertThat(WebSessions.idleTimeoutSeconds(request)).isEqualTo(600);
        assertThat(after.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
                .isEqualTo("42");
        assertThat(after.getAttribute(WebSessions.CLIENT_AGENT))
                .isEqualTo(SecureTokens.sha256Hex("Browser-A"));
        // A request whose address the container cannot tell is bound to the empty address, so a
        // later request from a known address is a changed client, never a crash.
        assertThat(after.getAttribute(WebSessions.CLIENT_ADDRESS))
                .isEqualTo(SecureTokens.sha256Hex(""));
    }

    @Test
    void aPasswordChangeClearsTheFlagAndRotatesTheSessionId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        WebSessions.signIn(request, STARTED);
        String id = request.getSession(false).getId();
        WebSessions.passwordChanged(request);
        assertThat(WebSessions.user(request))
                .contains(new SessionUser(42L, "app-session-1", false));
        assertThat(request.getSession(false).getId()).isNotEqualTo(id);
    }

    /**
     * SRS-G-48: a pending sign-in round-trips through the pre-login session (with and without a
     * starting scope); an absent, unreadable or cleared value is no pending sign-in.
     */
    @Test
    void SRS_G_48_aPendingSignInIsKeptReadBackAndForgotten() {
        MockHttpServletRequest request = withSession(new MockHttpSession());
        Instant at = Instant.parse("2026-10-29T06:00:00Z");
        var full =
                new in.gov.fci.payroll.iam.orchestration.LoginService.PendingLogin(
                        5L, "maker", 77L, "PAYROLL_MAKER", 3L, at);
        WebSessions.pendingLogin(request, full);
        assertThat(WebSessions.pendingLogin(request)).contains(full);
        var bare =
                new in.gov.fci.payroll.iam.orchestration.LoginService.PendingLogin(
                        5L, "a|b", 78L, null, null, at);
        WebSessions.pendingLogin(request, bare);
        assertThat(WebSessions.pendingLogin(request))
                .as("a | in the login id survives")
                .contains(bare);
        WebSessions.clearPendingLogin(request);
        assertThat(WebSessions.pendingLogin(request)).isEmpty();
        WebSessions.clearPendingLogin(new MockHttpServletRequest());
        assertThat(WebSessions.pendingLogin(new MockHttpServletRequest())).isEmpty();
        request.getSession().setAttribute(WebSessions.PENDING_LOGIN, "5|x|y");
        assertThat(WebSessions.pendingLogin(request)).as("too few parts").isEmpty();
        request.getSession().setAttribute(WebSessions.PENDING_LOGIN, "five|77|2026|r||m");
        assertThat(WebSessions.pendingLogin(request)).as("not a number").isEmpty();
        request.getSession().setAttribute(WebSessions.PENDING_LOGIN, 42);
        assertThat(WebSessions.pendingLogin(request)).as("not a string").isEmpty();
    }

    @Test
    void SRS_SEC_14_signingOutEndsTheSessionAndWithoutOneDoesNothing() {
        MockHttpSession session = new MockHttpSession();
        WebSessions.signOut(withSession(session));
        assertThat(session.isInvalid()).isTrue();
        MockHttpServletRequest none = new MockHttpServletRequest();
        WebSessions.signOut(none);
        assertThat(none.getSession(false)).isNull();
    }

    @Test
    void SRS_SEC_13_aSessionHoldingNonStringValuesIsNeverTakenAsSignedInOrAsHavingAPeriod() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = withSession(session);
        session.setAttribute(WebSessions.USER_ID, 42L);
        session.setAttribute(WebSessions.APP_SESSION, 7);
        assertThat(WebSessions.user(request)).as("only strings are trusted").isEmpty();
        session.setAttribute(WebSessions.USER_ID, "42");
        assertThat(WebSessions.user(request)).as("application session not a string").isEmpty();
        session.setAttribute(WebSessions.WORKING_PERIOD, 202610);
        session.setAttribute(WebSessions.ABSOLUTE_EXPIRY, 1L);
        assertThat(WebSessions.workingPeriod(request)).isEmpty();
        assertThat(WebSessions.absoluteExpiresAt(request)).isEmpty();
    }

    @Test
    void SRS_SEC_13_thePasswordChangeFlagIsReadOnlyFromTheExactStringTrue() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = withSession(session);
        session.setAttribute(WebSessions.USER_ID, "42");
        session.setAttribute(WebSessions.APP_SESSION, "app-session-1");
        session.setAttribute(WebSessions.PASSWORD_CHANGE, "true");
        assertThat(WebSessions.user(request).orElseThrow().passwordChangeRequired()).isTrue();
        session.setAttribute(WebSessions.PASSWORD_CHANGE, "TRUE");
        assertThat(WebSessions.user(request).orElseThrow().passwordChangeRequired()).isFalse();
        session.setAttribute(WebSessions.PASSWORD_CHANGE, Boolean.TRUE);
        assertThat(WebSessions.user(request).orElseThrow().passwordChangeRequired()).isFalse();
    }

    @Test
    void SRS_SEC_14_theIdleTimeoutFollowsTheScopeAndIsReadBackInWholeSeconds() {
        MockHttpServletRequest request = withSession(new MockHttpSession());
        WebSessions.idleTimeout(request, Duration.ofMinutes(15));
        assertThat(WebSessions.idleTimeoutSeconds(request)).isEqualTo(900);
        WebSessions.idleTimeout(request, Duration.ofMinutes(10));
        assertThat(WebSessions.idleTimeoutSeconds(request)).isEqualTo(600);
    }

    /**
     * OD-41: the pending sign-in keeps which factor came first; a value of another shape is none.
     */
    @Test
    void SRS_G_48_aPendingMobileSignInKeepsItsFirstFactor() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = withSession(session);
        java.time.Instant at = java.time.Instant.parse("2026-10-29T06:00:00Z");
        WebSessions.pendingLogin(
                request,
                new in.gov.fci.payroll.iam.orchestration.LoginService.PendingLogin(
                        0L, "9111111111", 0L, null, null, at, true));
        assertThat(WebSessions.pendingLogin(request))
                .hasValueSatisfying(
                        p -> {
                            assertThat(p.passwordless()).isTrue();
                            assertThat(p.loginId()).isEqualTo("9111111111");
                        });
        session.setAttribute(WebSessions.PENDING_LOGIN, "5|77|" + at + "|||maker");
        assertThat(WebSessions.pendingLogin(request)).as("the earlier six-part shape").isEmpty();
        session.setAttribute(WebSessions.PENDING_LOGIN, "5|77|" + at + "|||X|maker");
        assertThat(WebSessions.pendingLogin(request)).as("an unknown factor").isEmpty();
    }
}
