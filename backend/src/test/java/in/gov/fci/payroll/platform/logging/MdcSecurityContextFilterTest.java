package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

// Thread-local logging / security context: never alongside another such test.
@ResourceLock("thread-context")
class MdcSecurityContextFilterTest {

    private final MdcSecurityContextFilter filter = new MdcSecurityContextFilter();

    @AfterEach
    void clear() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void addsUserIdAndHashedSessionId() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "user-42", null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpSession session = new MockHttpSession(null, "raw-session-id");
        request.setSession(session);
        Map<String, String> seen = new HashMap<>();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> seen.putAll(MDC.getCopyOfContextMap()));

        assertThat(seen).containsEntry(MdcKeys.USER_ID, "user-42");
        assertThat(seen.get(MdcKeys.SESSION_ID))
                .hasSize(16)
                .isEqualTo(MdcSecurityContextFilter.hashSessionId("raw-session-id"))
                .doesNotContain("raw-session-id");
    }

    @Test
    void ignoresAnonymousAuthenticationAndMissingSession() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new AnonymousAuthenticationToken(
                                "key",
                                "anonymousUser",
                                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        Map<String, String> seen = new HashMap<>();

        filter.doFilter(
                new MockHttpServletRequest("GET", "/x"),
                new MockHttpServletResponse(),
                (req, res) -> {
                    Map<String, String> mdc = MDC.getCopyOfContextMap();
                    if (mdc != null) {
                        seen.putAll(mdc);
                    }
                });

        assertThat(seen).doesNotContainKeys(MdcKeys.USER_ID, MdcKeys.SESSION_ID);
    }
}
