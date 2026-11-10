package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VerhoeffTest {

    @Test
    void acceptsTheTextbookExample() {
        // 236 has check digit 3 (Verhoeff, 1969).
        assertThat(Verhoeff.checkDigit("236")).isEqualTo(3);
        assertThat(Verhoeff.isValid("2363")).isTrue();
        assertThat(Verhoeff.isValid("2364")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"23456789012", "99999999999", "50000000001"})
    void generatedCheckDigitsValidate(String payload) {
        assertThat(Verhoeff.isValid(payload + Verhoeff.checkDigit(payload))).isTrue();
    }

    @Test
    void detectsAdjacentTransposition() {
        String valid = "2345678901" + "2";
        String number = valid + Verhoeff.checkDigit(valid);
        String transposed = number.charAt(1) + "" + number.charAt(0) + number.substring(2);
        assertThat(Verhoeff.isValid(transposed)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12a4", "-1"})
    void rejectsNonDigits(String value) {
        assertThat(Verhoeff.isValid(value)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(Verhoeff.isValid(null)).isFalse();
    }
}
