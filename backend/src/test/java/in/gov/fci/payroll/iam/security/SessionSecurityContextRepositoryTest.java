package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;

/**
 * SRS-SEC-13 / SRS-SEC-14, ADR-0009: the authentication is rebuilt on every request from the
 * session strings and carries one authority only, ROLE_USER; permissions never come from here and
 * nothing is stored back. A request without a signed-in session is anonymous (no authentication).
 */
class SessionSecurityContextRepositoryTest {

    private final SessionSecurityContextRepository repository =
            new SessionSecurityContextRepository();

    private static MockHttpServletRequest signedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(WebSessions.USER_ID, "42");
        session.setAttribute(WebSessions.APP_SESSION, "app-session-1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }

    @Test
    void SRS_SEC_13_aSignedInRequestGetsTheSessionUserWithTheSingleAuthorityRoleUser() {
        DeferredSecurityContext deferred = repository.loadDeferredContext(signedIn());
        SecurityContext context = deferred.get();
        assertThat(context.getAuthentication()).isNotNull();
        assertThat(context.getAuthentication().isAuthenticated()).isTrue();
        assertThat(context.getAuthentication().getPrincipal())
                .isEqualTo(new SessionUser(42L, "app-session-1", false));
        assertThat(context.getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_USER");
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(deferred.isGenerated()).isFalse();
    }

    @Test
    void SRS_SEC_13_aRequestWithoutASessionIsAnonymousAndTheContextIsMarkedGenerated() {
        DeferredSecurityContext deferred =
                repository.loadDeferredContext(new MockHttpServletRequest());
        assertThat(deferred.get().getAuthentication()).isNull();
        assertThat(deferred.isGenerated()).isTrue();
    }

    @Test
    void SRS_SEC_13_aHalfWrittenSessionIsAnonymousToo() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(WebSessions.USER_ID, "42");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        assertThat(repository.loadDeferredContext(request).get().getAuthentication()).isNull();
        assertThat(repository.containsContext(request)).isFalse();
    }

    @Test
    void SRS_SEC_14_theDeferredContextIsBuiltOnceAndThenReused() {
        MockHttpServletRequest request = signedIn();
        DeferredSecurityContext deferred = repository.loadDeferredContext(request);
        SecurityContext first = deferred.get();
        request.getSession().removeAttribute(WebSessions.USER_ID);
        assertThat(deferred.get()).as("not rebuilt within the request").isSameAs(first);
        assertThat(repository.loadDeferredContext(request).get().getAuthentication())
                .as("the next request is judged afresh")
                .isNull();
    }

    @Test
    @SuppressWarnings("deprecation")
    void SRS_SEC_13_theLegacyLoadAnswersTheSameAsTheDeferredLoad() {
        SecurityContext context =
                repository.loadContext(
                        new HttpRequestResponseHolder(signedIn(), new MockHttpServletResponse()));
        assertThat(context.getAuthentication().getPrincipal())
                .isEqualTo(new SessionUser(42L, "app-session-1", false));
        SecurityContext anonymous =
                repository.loadContext(
                        new HttpRequestResponseHolder(
                                new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertThat(anonymous.getAuthentication()).isNull();
    }

    @Test
    void SRS_SEC_13_savingNeverWritesAnythingBackIntoTheSession() {
        MockHttpServletRequest request = signedIn();
        MockHttpSession session = (MockHttpSession) request.getSession();
        List<String> before = Collections.list(session.getAttributeNames());
        repository.saveContext(
                SecurityContextHolder.createEmptyContext(), request, new MockHttpServletResponse());
        assertThat(Collections.list(session.getAttributeNames()))
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void SRS_SEC_13_aContextIsContainedOnlyWhileTheSessionNamesAUser() {
        assertThat(repository.containsContext(signedIn())).isTrue();
        assertThat(repository.containsContext(new MockHttpServletRequest())).isFalse();
    }
}
