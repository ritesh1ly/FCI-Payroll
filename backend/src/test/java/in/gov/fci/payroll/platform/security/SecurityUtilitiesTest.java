package in.gov.fci.payroll.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.ProblemDetailFactory;
import in.gov.fci.payroll.platform.error.ProblemDetailWriter;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.json.JsonMapper;

class SecurityUtilitiesTest {

    @Test
    void randomTokensAreUrlSafeUniqueAndLongEnough() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = SecureTokens.randomToken(32);
            assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
            tokens.add(token);
        }
        assertThat(tokens).hasSize(1000);
        assertThatThrownBy(() -> SecureTokens.randomToken(8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashesAreStableKnownValues() {
        assertThat(SecureTokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(SecureTokens.sha256Base64Url("abc"))
                .isEqualTo("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0");
    }

    @Test
    void constantTimeEquals() {
        assertThat(SecureTokens.constantTimeEquals("a1", "a1")).isTrue();
        assertThat(SecureTokens.constantTimeEquals("a1", "a2")).isFalse();
        assertThat(SecureTokens.constantTimeEquals(null, "a")).isFalse();
        assertThat(SecureTokens.constantTimeEquals("a", null)).isFalse();
    }

    @ParameterizedTest(name = "{0} matches {1}: {2}")
    @CsvSource(
            delimiter = '|',
            value = {
                "USERNAME | ritesh.k | true",
                "USERNAME | Ritesh | false",
                "USERNAME | ab | false",
                "USERNAME | a$b | false",
                "OFFICE_ID | RO-LKO | true",
                "OFFICE_ID | RO.*  | false",
                "ROLE_CODE | REGIONAL_ADMIN | true",
                "ROLE_CODE | regional | false",
                "PERMISSION_CODE | USER:CREATE | true",
                "PERMISSION_CODE | USER:* | false",
                "PERMISSION_CODE | USER | false",
                "MOBILE_IN | +919876543210 | true",
                "MOBILE_IN | 9876543210 | false",
                "OBJECT_ID | 6720f1c2a9b3c4d5e6f70812 | true",
                "UUID | 0192a7c4-5b2e-7d3f-8a1b-9c0d1e2f3a4b | true",
                "SEARCH_TEXT | राम कुमार | true",
                "SEARCH_TEXT | {$ne:1} | false",
                "PERSON_NAME | D'Souza-Rao | true",
                "SORT | username,desc | true",
                "SORT | profile.secret | false",
                "OTP | 123456 | true",
                "OTP | 12345 | false",
                "EMPLOYEE_ID | FCI00123 | true"
            })
    void allowListPatterns(String name, String value, boolean expected) throws Exception {
        String regex = (String) InputPatterns.class.getField(name).get(null);
        assertThat(Pattern.matches(regex, value)).isEqualTo(expected);
    }

    @Test
    void securityHandlersWriteProblemResponses() throws Exception {
        ProblemResponder responder =
                new ProblemResponder(
                        new ProblemDetailFactory(
                                "https://errors.fci.gov.in/", null, Clock.systemUTC()),
                        new ProblemDetailWriter(new JsonMapper()));

        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        new ProblemDetailAuthenticationEntryPoint(responder)
                .commence(
                        new MockHttpServletRequest("GET", "/bff/v1/me"),
                        unauthorized,
                        new InsufficientAuthenticationException("no session"));
        assertThat(unauthorized.getStatus()).isEqualTo(401);
        assertThat(unauthorized.getContentAsString()).contains("COM-401-AUTH");
        assertThat(unauthorized.getHeader("WWW-Authenticate")).isNull();

        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        new ProblemDetailAccessDeniedHandler(responder)
                .handle(
                        new MockHttpServletRequest("POST", "/bff/v1/users"),
                        forbidden,
                        new AccessDeniedException("csrf"));
        assertThat(forbidden.getStatus()).isEqualTo(403);
        assertThat(forbidden.getContentAsString()).contains("COM-403-DENIED");
    }

    /**
     * R-ASCII (UI audit 2026-11-02, text areas): multi-line free text is printable ASCII with tab,
     * CR and LF, as {@code @PrintableAscii} accepts; every other control character and non-ASCII
     * letter is refused, and null is not text.
     */
    @Test
    void R_ASCII_printableTextKeepsTabAndLineBreaksOnly() {
        assertThat(InputPatterns.isPrintableText("")).isTrue();
        assertThat(InputPatterns.isPrintableText(" ~")).isTrue();
        assertThat(InputPatterns.isPrintableText("line one\r\nline two\n\tend")).isTrue();
        assertThat(InputPatterns.isPrintableText(null)).isFalse();
        assertThat(InputPatterns.isPrintableText("\u001f")).isFalse();
        assertThat(InputPatterns.isPrintableText("\u007f")).isFalse();
        assertThat(InputPatterns.isPrintableText("\u000b")).isFalse();
        assertThat(InputPatterns.isPrintableText("caf\u00e9")).isFalse();
        assertThat(InputPatterns.isPrintableAscii("a\nb"))
                .as("single-line text unchanged")
                .isFalse();
    }
}
