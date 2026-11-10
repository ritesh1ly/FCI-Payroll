package in.gov.fci.payroll.platform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * R-ASCII at the request boundary: the database character set is US7ASCII, so request text is
 * printable ASCII, with tab and line breaks for multi-line text.
 */
class PrintableAsciiValidatorTest {

    private final PrintableAsciiValidator validator = new PrintableAsciiValidator();

    private boolean valid(String value) {
        return validator.isValid(value, null);
    }

    @Test
    void R_ASCII_plainTextFromSpaceToTildeIsTaken() {
        assertThat(valid("Order HQ/PAY/17 dated 01-10-2026: DA revised ~ 53%")).isTrue();
        assertThat(valid(" !~")).isTrue();
        assertThat(valid("")).isTrue();
        // @NotBlank decides whether a value is required.
        assertThat(valid(null)).isTrue();
    }

    @Test
    void R_ASCII_multiLineTextKeepsItsTabsAndLineBreaks() {
        assertThat(valid("First line\r\nSecond line\n\tindented")).isTrue();
    }

    @Test
    void R_ASCII_hindiSymbolsAndControlCharactersAreRefused() {
        assertThat(valid("आदेश")).isFalse();
        assertThat(valid("Approved ✔")).isFalse();
        assertThat(valid("Rs. 100 — final")).isFalse();
        assertThat(valid("bell\u0007")).isFalse();
        assertThat(valid("delete\u007f")).isFalse();
        assertThat(valid("null\u0000byte")).isFalse();
    }
}
