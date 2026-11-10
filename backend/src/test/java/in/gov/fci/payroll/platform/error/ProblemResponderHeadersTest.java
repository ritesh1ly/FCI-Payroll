package in.gov.fci.payroll.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-SEC-4 (ASVS V14.4.4 / V14.4.5): a problem a filter writes before the security chain carries
 * nosniff and no-store; headers already set by the chain are kept.
 */
class ProblemResponderHeadersTest {

    private final ProblemResponder responder =
            new ProblemResponder(
                    new ProblemDetailFactory("https://errors.fci.gov.in/", null, Clock.systemUTC()),
                    new ProblemDetailWriter(new JsonMapper()));

    @Test
    void SRS_SEC_4_aFilterWrittenProblemIsNeverSniffedOrCached() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        responder.write(
                null,
                CommonErrorCode.PAYLOAD_TOO_LARGE,
                new MockHttpServletRequest("POST", "/v1/x"),
                response);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void SRS_SEC_4_headersTheChainAlreadySetAreKept() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
        responder.write(
                null,
                CommonErrorCode.ACCESS_DENIED,
                new MockHttpServletRequest("GET", "/v1/x"),
                response);
        assertThat(response.getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }
}
