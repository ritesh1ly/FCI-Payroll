package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import in.gov.fci.payroll.platform.logging.LogCapture;
import in.gov.fci.payroll.platform.logging.MdcKeys;
import jakarta.servlet.FilterChain;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/**
 * Mutation gaps of the API access line: the duration is in milliseconds (end minus start, over one
 * million) and a signed-in user is named by id.
 */
class ApiAccessLogFilterMutationGapsTest {

    private final ApiAccessLogFilter filter = new ApiAccessLogFilter();

    private String line(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        FilterChain chain =
                (req, res) -> {
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
        try (LogCapture capture = LogCapture.of("FCI_API_ACCESS", Level.INFO)) {
            filter.doFilter(request, response, chain);
            assertThat(capture.messages()).hasSize(1);
            return capture.messages().get(0);
        }
    }

    @Test
    void theDurationIsTheElapsedMillisecondsNotASumOrAProduct() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/employees");
        request.setServletPath("/employees");
        String message = line(request, new MockHttpServletResponse());
        Matcher m = Pattern.compile("durationMs=(\\d+) ").matcher(message);
        assertThat(m.find()).as(message).isTrue();
        long ms = Long.parseLong(m.group(1));
        assertThat(ms).isBetween(15L, 5_000L);
    }

    @Test
    void aSignedInUserIsNamedByIdAndAnAnonymousOneIsNot() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(WebSessions.USER_ID, "4207");
        session.setAttribute(WebSessions.APP_SESSION, "s-1");
        MockHttpServletRequest signedIn = new MockHttpServletRequest("POST", "/loans");
        signedIn.setServletPath("/loans");
        signedIn.setQueryString("q=a@b.in");
        signedIn.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(201);
        MDC.put(MdcKeys.CORRELATION_ID, "corr-77");
        String message;
        try {
            message = line(signedIn, response);
        } finally {
            MDC.remove(MdcKeys.CORRELATION_ID);
        }
        assertThat(message)
                .startsWith("event=API_ACCESS method=POST path=/loans status=201 durationMs=")
                .contains(" user=4207 correlationId=corr-77")
                .doesNotContain("a@b.in");

        MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/auth/csrf");
        anonymous.setServletPath("/auth/csrf");
        assertThat(line(anonymous, new MockHttpServletResponse())).contains(" user=anonymous ");
    }
}
