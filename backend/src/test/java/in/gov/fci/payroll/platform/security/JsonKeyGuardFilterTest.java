package in.gov.fci.payroll.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.error.ProblemDetailFactory;
import in.gov.fci.payroll.platform.error.ProblemDetailWriter;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class JsonKeyGuardFilterTest {

    private final ProblemResponder responder =
            new ProblemResponder(
                    new ProblemDetailFactory("https://errors.fci.gov.in/", null, Clock.systemUTC()),
                    new ProblemDetailWriter(new JsonMapper()));
    private final JsonKeyGuardFilter filter = new JsonKeyGuardFilter(responder, 2048, 8);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"username\":{\"$ne\":null},\"password\":{\"$ne\":null}}",
                "{\"$where\":\"sleep(1000)\"}",
                "{\"filter\":{\"profile.role\":\"ADMIN\"}}",
                "[{\"ok\":1},{\"$gt\":\"\"}]",
                "{\"a\\u0000b\":1}"
            })
    void rejectsOperatorDottedAndNulKeys(String body) throws Exception {
        MockHttpServletResponse response = run(body, "application/json");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("COM-400-JSON-KEY");
    }

    @Test
    void rejectsExcessiveNesting() throws Exception {
        MockHttpServletResponse response =
                run("{\"a\":".repeat(10) + "1" + "}".repeat(10), "application/json");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("COM-400-MALFORMED");
    }

    @Test
    void rejectsOversizedBodiesByDeclaredOrActualLength() throws Exception {
        String big = "{\"a\":\"" + "x".repeat(3000) + "\"}";
        MockHttpServletResponse response = run(big, "application/json");
        assertThat(response.getStatus()).isEqualTo(413);

        // Chunked transfer: no declared length, so the bounded read must catch it.
        MockHttpServletRequest chunked =
                new MockHttpServletRequest("POST", "/api/v1/users") {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
        chunked.setContentType("application/json");
        chunked.setContent(big.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse chunkedResponse = new MockHttpServletResponse();
        filter.doFilter(chunked, chunkedResponse, new MockFilterChain());
        assertThat(chunkedResponse.getStatus()).isEqualTo(413);
    }

    /**
     * SRS-G-25 / R-SRC (security review 2026-11-04, ASVS V1.5): a property sent twice in one
     * object, at any depth, is refused - a proxy-edited body cannot make the guard and the binder
     * read different values; the same name in two sibling objects is fine.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"reason\":\"ok\",\"reason\":\"x\"}",
                "{\"status\":\"DRAFT\",\"note\":\"n\",\"status\":\"APPROVED\"}",
                "{\"a\":{\"id\":1,\"id\":2}}",
                "[{\"ok\":1,\"ok\":2}]"
            })
    void SRS_G_25_rejectsAPropertyNamedTwiceInOneObject(String body) throws Exception {
        MockHttpServletResponse response = run(body, "application/json");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("COM-400-MALFORMED");
        assertThat(run("[{\"id\":1},{\"id\":2}]", "application/json").getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        MockHttpServletResponse response = run("{\"a\":", "application/json");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("COM-400-MALFORMED");
    }

    @Test
    void passesCleanBodyThroughAndItCanBeReadAgain() throws Exception {
        String body = "{\"username\":\"ritesh\",\"roles\":[\"USER\"],\"email\":\"r@fci.gov.in\"}";
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletRequest request = request(body, "application/json; charset=UTF-8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (req, res) -> seen.set(((HttpServletRequest) req).getReader().readLine()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(body);
    }

    @Test
    void ignoresNonJsonAndBodylessRequests() throws Exception {
        MockHttpServletRequest form = request("$where=1", "application/x-www-form-urlencoded");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(form, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(form);

        MockHttpServletRequest getRequest = new MockHttpServletRequest("GET", "/x");
        MockFilterChain getChain = new MockFilterChain();
        filter.doFilter(getRequest, new MockHttpServletResponse(), getChain);
        assertThat(getChain.getRequest()).isSameAs(getRequest);
    }

    @Test
    void inspectsVendorJsonAndRejectsUnparseableContentType() throws Exception {
        assertThat(run("{\"$x\":1}", "application/merge-patch+json").getStatus()).isEqualTo(400);
        assertThat(run("{\"$x\":1}", "not a media type").getStatus()).isEqualTo(400);
    }

    @Test
    void allowedNameRules() {
        assertThat(JsonKeyGuardFilter.isAllowedName("officeId")).isTrue();
        assertThat(JsonKeyGuardFilter.isAllowedName("price$")).isTrue();
        assertThat(JsonKeyGuardFilter.isAllowedName("")).isFalse();
        assertThat(JsonKeyGuardFilter.isAllowedName("x".repeat(129))).isFalse();
    }

    private MockHttpServletResponse run(String body, String contentType) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(body, contentType), response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String body, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
