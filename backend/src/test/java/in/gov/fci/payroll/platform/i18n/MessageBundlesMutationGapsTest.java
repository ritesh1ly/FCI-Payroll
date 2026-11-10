package in.gov.fci.payroll.platform.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.security.SecureTokens;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * The user-interface label bundles and the locale rule (preference, cookie, Accept-Language,
 * default): gaps found by mutation testing. Hindi value written as escapes (UTF-8 independent).
 */
class MessageBundlesMutationGapsTest {

    private static final String HINDI_CATEGORY = "\u0936\u094d\u0930\u0947\u0923\u0940";

    private static MessageBundles bundles(String defaultLocale) {
        ApiProperties properties = mock(ApiProperties.class);
        when(properties.i18n()).thenReturn(new ApiProperties.I18n(defaultLocale, "FCI_LOCALE"));
        return new MessageBundles(new JsonMapper(), properties);
    }

    @Test
    void bothBundlesAreLoadedAsUtf8WithIdenticalKeys() {
        MessageBundles bundles = bundles("en");
        assertThat(bundles.messages("en")).containsEntry("a01.category", "Category");
        assertThat(bundles.messages("hi")).containsEntry("a01.category", HINDI_CATEGORY);
        assertThat(bundles.messages("hi").keySet()).isEqualTo(bundles.messages("en").keySet());
        assertThat(bundles.messages("en")).isNotEmpty();
    }

    @Test
    void anUnknownLocaleFallsBackToTheEnglishLabels() {
        MessageBundles bundles = bundles("en");
        assertThat(bundles.messages("fr")).isSameAs(bundles.messages("en"));
        assertThat(bundles.messages("fr")).containsEntry("a01.category", "Category");
    }

    @Test
    void keysListsEveryEnglishKey() {
        MessageBundles bundles = bundles("en");
        assertThat(bundles.keys())
                .isNotEmpty()
                .contains("a01.category")
                .hasSize(bundles.messages("en").size());
    }

    @Test
    void theServedBundleIsTheJsonOfTheLabelsWithAStrongEtagOfItsHash() {
        MessageBundles bundles = bundles("en");
        MessageBundles.Bundle hindi = bundles.bundle("hi").orElseThrow();
        assertThat(hindi.locale()).isEqualTo("hi");
        String json = new String(hindi.json(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"a01.category\":\"" + HINDI_CATEGORY + "\"");
        assertThat(hindi.etag())
                .isEqualTo("\"" + SecureTokens.sha256Hex(json).substring(0, 32) + "\"")
                .matches("\"[0-9a-f]{32}\"");
        assertThat(bundles.bundle("en").orElseThrow().etag()).isNotEqualTo(hindi.etag());
        assertThat(bundles.bundle("fr")).isEmpty();
    }

    @Test
    void aStoredPreferenceWinsOverTheCookieAndTheHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("FCI_LOCALE", "en"));
        request.addHeader("Accept-Language", "en");
        assertThat(bundles("en").resolve(request, "hi")).isEqualTo("hi");
    }

    @Test
    void anUnsupportedPreferenceIsIgnoredInFavourOfTheCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("FCI_LOCALE", "hi"));
        assertThat(bundles("en").resolve(request, "fr")).isEqualTo("hi");
        assertThat(bundles("en").resolve(request, null)).isEqualTo("hi");
    }

    @Test
    void theCookieWinsOverTheHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("OTHER", "en"), new Cookie("FCI_LOCALE", "hi"));
        request.addHeader("Accept-Language", "en");
        assertThat(bundles("en").resolve(request, null)).isEqualTo("hi");
    }

    @Test
    void aCookieOfAnotherNameIsNotTheLocaleCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("OTHER", "hi"));
        assertThat(bundles("en").resolve(request, null)).isEqualTo("en");
    }

    @Test
    void aLocaleCookieWithAnUnsupportedValueFallsThroughToTheHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("FCI_LOCALE", "fr"));
        request.addHeader("Accept-Language", "hi-IN,en;q=0.5");
        assertThat(bundles("en").resolve(request, null)).isEqualTo("hi");
    }

    @Test
    void aLocaleCookieWithAnUnsupportedValueAndNoHeaderGivesTheDefault() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("FCI_LOCALE", "fr"));
        assertThat(bundles("hi").resolve(request, null)).isEqualTo("hi");
    }

    @Test
    void theHeaderIsReadInOrderAndUnsupportedLanguagesAreSkipped() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "fr, hi;q=0.8, en;q=0.5");
        assertThat(bundles("en").resolve(request, null)).isEqualTo("hi");
    }

    @Test
    void aRegionalEnglishHeaderIsEnglishEvenWhenTheDefaultIsHindi() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "EN-IN");
        assertThat(bundles("hi").resolve(request, null)).isEqualTo("en");
    }

    @Test
    void aHeaderWithOnlyUnsupportedLanguagesGivesTheDefault() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "fr, de;q=0.5, *;q=0.1");
        assertThat(bundles("hi").resolve(request, null)).isEqualTo("hi");
        assertThat(bundles("en").resolve(request, null)).isEqualTo("en");
    }

    @Test
    void aMalformedHeaderGivesTheDefaultInsteadOfAnError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en;q=notanumber");
        assertThat(bundles("hi").resolve(request, null)).isEqualTo("hi");
    }

    @Test
    void noPreferenceCookieOrHeaderGivesTheDefault() {
        assertThat(bundles("hi").resolve(new MockHttpServletRequest(), null)).isEqualTo("hi");
        assertThat(bundles("en").resolve(new MockHttpServletRequest(), null)).isEqualTo("en");
    }

    @Test
    void theResolvedLocaleEndpointAnswersTheLocaleWithoutCaching() {
        MessageBundles bundles = mock(MessageBundles.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(bundles.resolve(request, null)).thenReturn("hi");
        ResponseEntity<Map<String, String>> response =
                new I18nController(bundles).resolved(request);
        assertThat(response.getBody()).isEqualTo(Map.of("locale", "hi"));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
