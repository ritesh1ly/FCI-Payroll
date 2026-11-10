package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/**
 * Mutation gaps of the SPA CSRF recipe: handling puts the XOR-encoded token on the request and
 * loads the deferred token; a token sent back in the header is taken raw, one rendered in a form
 * parameter is XOR-decoded.
 */
class SpaCsrfTokenRequestHandlerMutationGapsTest {

    private static final String RAW = "3f1c2b7e-5d4a-4c19-9a52-0be7d1c84a66";

    private final SpaCsrfTokenRequestHandler handler = new SpaCsrfTokenRequestHandler();
    private final CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", RAW);

    @Test
    void handleExposesTheTokenOnTheRequestAndLoadsTheDeferredToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AtomicInteger loaded = new AtomicInteger();
        handler.handle(
                request,
                new MockHttpServletResponse(),
                () -> {
                    loaded.incrementAndGet();
                    return token;
                });
        assertThat(request.getAttribute(CsrfToken.class.getName())).isInstanceOf(CsrfToken.class);
        assertThat(request.getAttribute("_csrf")).isInstanceOf(CsrfToken.class);
        assertThat(loaded.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theHeaderValueIsTakenRawNotXorDecoded() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-XSRF-TOKEN", RAW);
        assertThat(handler.resolveCsrfTokenValue(request, token)).isEqualTo(RAW);
    }

    @Test
    void anAbsentHeaderFallsBackToTheXorEncodedParameterAndABlankOneIsNotTakenRaw() {
        MockHttpServletRequest rendering = new MockHttpServletRequest();
        handler.handle(rendering, new MockHttpServletResponse(), () -> token);
        String encoded = ((CsrfToken) rendering.getAttribute(CsrfToken.class.getName())).getToken();
        assertThat(encoded).isNotEqualTo(RAW);

        MockHttpServletRequest form = new MockHttpServletRequest();
        form.addHeader("X-XSRF-TOKEN", "   ");
        form.setParameter("_csrf", encoded);
        // A blank header is still a header: Spring's XOR resolver takes it as the actual token
        // (it never reaches the parameter) and cannot decode it, so nothing is resolved. The
        // plain branch is not used either, which would hand back the blank text raw.
        assertThat(handler.resolveCsrfTokenValue(form, token)).isNull();

        MockHttpServletRequest noHeader = new MockHttpServletRequest();
        noHeader.setParameter("_csrf", encoded);
        assertThat(handler.resolveCsrfTokenValue(noHeader, token)).isEqualTo(RAW);
    }
}
