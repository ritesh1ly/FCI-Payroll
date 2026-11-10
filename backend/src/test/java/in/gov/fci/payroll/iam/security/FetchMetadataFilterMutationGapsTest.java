package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Kills the PIT survivor of {@link FetchMetadataFilter#crossSite} (OD-104, OWASP CSRF). */
class FetchMetadataFilterMutationGapsTest {

    @Test
    void OD_104_anUnparsableOriginIsCrossSiteEvenWhenTheServerNameIsEmpty() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/inbox");
        // an empty server name must not be matched by "no host could be read"
        request.setServerName("");
        request.addHeader("Origin", "http://exa mple.invalid");
        assertThat(FetchMetadataFilter.crossSite(request)).isTrue();
    }

    @Test
    void OD_104_anOpaqueNullOriginIsCrossSite() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/inbox");
        request.setServerName("");
        request.addHeader("Origin", "null");
        assertThat(FetchMetadataFilter.crossSite(request)).isTrue();
    }

    @Test
    void OD_104_anIpv6OriginMatchesItsServerNameWithoutBrackets() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/inbox");
        request.setServerName("::1");
        request.addHeader("Origin", "http://[::1]:8443");
        assertThat(FetchMetadataFilter.crossSite(request)).isFalse();
    }
}
