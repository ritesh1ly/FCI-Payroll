package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.error.ProblemDetailFactory;
import in.gov.fci.payroll.platform.error.ProblemDetailWriter;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-G-48 session controls / OWASP CSRF Prevention Cheat Sheet (Fetch Metadata, Origin check),
 * ASVS V50.4.3: a state-changing call a browser marks as cross-site never reaches the CSRF token
 * check, whatever cookie an attacker managed to plant.
 */
class FetchMetadataFilterTest {

    private final FetchMetadataFilter filter =
            new FetchMetadataFilter(
                    new ProblemResponder(
                            new ProblemDetailFactory(
                                    "https://errors.fci.gov.in/", null, Clock.systemUTC()),
                            new ProblemDetailWriter(new JsonMapper())));

    private MockHttpServletResponse run(String method, String site, String origin)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/v1/inbox");
        request.setServerName("payroll.fci.gov.in");
        if (site != null) {
            request.addHeader("Sec-Fetch-Site", site);
        }
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (chain.getRequest() != null) {
            response.setStatus(299); // passed on
        }
        return response;
    }

    @ParameterizedTest(name = "{0} Sec-Fetch-Site={1} Origin={2} -> {3}")
    @CsvSource(
            nullValues = "-",
            value = {
                // a write from another site, or from a sibling sub-domain: refused
                "POST, cross-site, -, 403",
                "PUT, same-site, -, 403",
                "PATCH, CROSS-SITE, -, 403",
                "DELETE, cross-site, https://payroll.fci.gov.in, 403",
                // the page itself, or the user typing the address: passed on
                "POST, same-origin, https://evil.example, 299",
                "POST, none, -, 299",
                " POST, ' same-origin ', -, 299",
                // no Fetch Metadata: the Origin decides when it is sent
                "POST, -, https://payroll.fci.gov.in, 299",
                "POST, -, https://PAYROLL.fci.gov.in:8743, 299",
                "POST, -, https://evil.example, 403",
                "POST, -, https://payroll.fci.gov.in.evil.example, 403",
                "POST, -, null, 403",
                "POST, -, '%%', 403",
                "POST, -, file:///etc/passwd, 403",
                // neither header (server-side rendering, scripts): left to the CSRF token
                "POST, -, -, 299",
                // reads are never refused here (a cross-site read carries no SameSite=Strict
                // session)
                "GET, cross-site, https://evil.example, 299",
                "HEAD, cross-site, -, 299",
                "OPTIONS, cross-site, -, 299"
            })
    void SRS_G_48_aWriteTheBrowserMarksAsCrossSiteIsRefused(
            String method, String site, String origin, int expected) throws Exception {
        MockHttpServletResponse response = run(method.trim(), site, origin);
        assertThat(response.getStatus()).isEqualTo(expected);
        if (expected == 403) {
            // The generic refusal: it names no header and no rule.
            assertThat(response.getContentAsString())
                    .contains("COM-403-DENIED")
                    .doesNotContainIgnoringCase("sec-fetch")
                    .doesNotContainIgnoringCase("origin");
        }
    }

    @Test
    void SRS_G_48_anIpv6OriginMatchesItsBracketlessServerName() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/inbox");
        request.setServerName("::1");
        request.addHeader("Origin", "https://[::1]:8743");
        assertThat(FetchMetadataFilter.crossSite(request)).isFalse();
        request.setServerName("[::1]");
        assertThat(FetchMetadataFilter.crossSite(request)).isFalse();
    }

    private static MockHttpServletRequest write(String serverName, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/inbox");
        request.setServerName(serverName);
        request.addHeader("Origin", origin);
        return request;
    }

    @Test
    void SRS_G_48_aServerNameWithAnOpeningBracketOnlyIsComparedAsItIsAndNeverMatchesAnOrigin() {
        // startsWith("[") true, endsWith("]") false: not a bracketed literal, kept whole
        assertThat(FetchMetadataFilter.crossSite(write("[::1", "https://[::1]:8743"))).isTrue();
        assertThat(FetchMetadataFilter.crossSite(write("[", "https://[::1]"))).isTrue();
        assertThat(FetchMetadataFilter.crossSite(write("[::1", "https://payroll.fci.gov.in")))
                .isTrue();
    }

    @Test
    void SRS_G_48_aServerNameWithAClosingBracketOnlyIsComparedAsItIs() {
        assertThat(FetchMetadataFilter.crossSite(write("::1]", "https://[::1]"))).isTrue();
        assertThat(FetchMetadataFilter.crossSite(write("::1]", "https://payroll.fci.gov.in")))
                .isTrue();
    }

    @Test
    void SRS_G_48_anIpv6OriginAndServerNameMatchAcrossCaseButNotAcrossAddresses() {
        assertThat(FetchMetadataFilter.crossSite(write("2001:DB8::1", "https://[2001:db8::1]")))
                .isFalse();
        assertThat(FetchMetadataFilter.crossSite(write("2001:db8::2", "https://[2001:db8::1]")))
                .isTrue();
    }

    @Test
    void SRS_G_48_aBlankOrBareOriginIsCrossSiteAndAHeaderWithOnlySpacesIsRead() {
        assertThat(FetchMetadataFilter.crossSite(write("payroll.fci.gov.in", ""))).isTrue();
        assertThat(FetchMetadataFilter.crossSite(write("payroll.fci.gov.in", "payroll.fci.gov.in")))
                .as("no scheme: no host")
                .isTrue();
        assertThat(
                        FetchMetadataFilter.crossSite(
                                write("payroll.fci.gov.in", "  https://payroll.fci.gov.in  ")))
                .isFalse();
    }

    @Test
    void SRS_G_48_aFetchMetadataHeaderWinsOverAnOriginThatWouldHavePassed() {
        MockHttpServletRequest request = write("payroll.fci.gov.in", "https://payroll.fci.gov.in");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        assertThat(FetchMetadataFilter.crossSite(request)).isTrue();
        MockHttpServletRequest same = write("payroll.fci.gov.in", "https://evil.example");
        same.addHeader("Sec-Fetch-Site", "same-origin");
        assertThat(FetchMetadataFilter.crossSite(same)).isFalse();
    }

    @Test
    void SRS_G_48_aRefusedWriteNeverReachesTheChain() throws Exception {
        MockHttpServletRequest request = write("payroll.fci.gov.in", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).as("the chain was not called").isNull();
    }
}
