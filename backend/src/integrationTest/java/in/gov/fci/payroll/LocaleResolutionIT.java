package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.i18n.MessageBundles;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * SRS-G-40 (English and Hindi): the language of a request is the signed-in user's stored
 * preference, else the language cookie, else the browser's Accept-Language, else the configured
 * default; an unknown or malformed value never chooses a language.
 */
class LocaleResolutionIT extends ApiTestSupport {

    @Autowired private MessageBundles bundles;
    @Autowired private ApiProperties properties;

    private MockHttpServletRequest request(String cookie, String acceptLanguage) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (cookie != null) {
            r.setCookies(
                    new Cookie("OTHER", "hi"), new Cookie(properties.i18n().cookieName(), cookie));
        }
        if (acceptLanguage != null) {
            r.addHeader("Accept-Language", acceptLanguage);
        }
        return r;
    }

    @Test
    void SRS_G_40_preferenceThenCookieThenBrowserThenTheDefault() {
        String fallback = properties.i18n().defaultLocale();
        assertThat(bundles.resolve(request("en", "hi"), "hi")).isEqualTo("hi");
        assertThat(bundles.resolve(request("hi", "en"), null)).isEqualTo("hi");
        // An unknown preference or cookie is passed over, never used.
        assertThat(bundles.resolve(request("fr", "hi-IN,en;q=0.8"), "de")).isEqualTo("hi");
        assertThat(bundles.resolve(request(null, "fr-FR, en-GB;q=0.7"), null)).isEqualTo("en");
        assertThat(bundles.resolve(request(null, "fr, de"), null)).isEqualTo(fallback);
        assertThat(bundles.resolve(request(null, "en;q=x;;"), null)).isEqualTo(fallback);
        assertThat(bundles.resolve(request(null, null), null)).isEqualTo(fallback);
        // A cookie of another name never chooses the language.
        MockHttpServletRequest other = new MockHttpServletRequest();
        other.setCookies(new Cookie("OTHER", "hi"));
        assertThat(bundles.resolve(other, null)).isEqualTo(fallback);
    }

    @Test
    void SRS_G_40_bothBundlesHoldTheSameKeysAndAnUnknownLanguageReadsEnglish() {
        assertThat(bundles.messages("hi").keySet()).isEqualTo(bundles.messages("en").keySet());
        assertThat(bundles.messages("fr")).isSameAs(bundles.messages("en"));
        assertThat(bundles.keys()).contains("page.size", "page.next");
        assertThat(bundles.bundle("hi")).isPresent();
        assertThat(bundles.bundle("fr")).isEmpty();
    }
}
