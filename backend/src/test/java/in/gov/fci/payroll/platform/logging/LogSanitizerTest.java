package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LogSanitizerTest {

    @Test
    void encodesCrLfSoThatForgedLogLinesStayOnOneLine() {
        String forged = "alice\r\n2026-10-14 INFO LOGIN_SUCCESS user=admin";
        assertThat(LogSanitizer.sanitize(forged))
                .doesNotContain("\r", "\n")
                .startsWith("alice\\r\\n2026");
    }

    @Test
    void encodesOtherControlAndLineSeparatorCharacters() {
        // Built from char codes: a formatter must never turn these into raw characters in source.
        String input =
                "a"
                        + (char) 0x09
                        + "b"
                        + (char) 0x00
                        + "c"
                        + (char) 0x2028
                        + "d"
                        + (char) 0x85
                        + "e";
        assertThat(LogSanitizer.encodeControlCharacters(input))
                .isEqualTo("a\\tb\\u0000c\\u2028d\\u0085e");
    }

    @Test
    void returnsSameInstanceWhenNothingToEncode() {
        String clean = "plain value";
        assertThat(LogSanitizer.encodeControlCharacters(clean)).isSameAs(clean);
        assertThat(LogSanitizer.encodeControlCharacters(null)).isNull();
    }

    @Test
    void truncatesLongValuesAndRecordsOriginalLength() {
        String result = LogSanitizer.sanitize("x".repeat(300), 10);
        assertThat(result).isEqualTo("xxxxxxxxxx…(300)");
        assertThat(LogSanitizer.sanitize("short", 0)).isEqualTo("short");
    }

    @Test
    void masksSensitiveData() {
        assertThat(LogSanitizer.sanitize("password=hunter2")).isEqualTo("password=***");
        assertThat(LogSanitizer.sanitize(null)).isEqualTo("null");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password",
                "newPassword",
                "currentPassword",
                "clientSecret",
                "refreshToken",
                "accessToken",
                "otp",
                "userOtp",
                "captchaAnswer",
                "pin",
                "mpin",
                "PAN",
                "pan_number",
                "aadhaarNumber",
                "Authorization",
                "apiKey"
            })
    void recognisesSensitiveNames(String name) {
        assertThat(LogSanitizer.isSensitiveName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "username",
                "officeId",
                "mapping",
                "company",
                "panel",
                "span",
                "spinner",
                "expand",
                "page"
            })
    void doesNotFlagOrdinaryNames(String name) {
        assertThat(LogSanitizer.isSensitiveName(name)).isFalse();
    }

    @Test
    void nullOrEmptyNameIsNotSensitive() {
        assertThat(LogSanitizer.isSensitiveName(null)).isFalse();
        assertThat(LogSanitizer.isSensitiveName("")).isFalse();
    }
}
