package in.gov.fci.payroll.general.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.FciException;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Payroll months (DDL {@code *_yymm}) and free text of the Module 01 transactions. */
class InputsTest {

    private static final GeneralErrorCode CODE = GeneralErrorCode.SUPPLEMENTARY_INPUT;

    @Test
    void aPayrollMonthReadsAndPrintsAsYyyymm() {
        assertThat(Inputs.month("202607", CODE)).isEqualTo(YearMonth.of(2026, 7));
        assertThat(Inputs.yymm(YearMonth.of(2026, 1))).isEqualTo("202601");
        assertThat(Inputs.month("209912", CODE)).isEqualTo(YearMonth.of(2099, 12));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"202613", "202600", "2026-07", "20267", "199912", "2026071", "abcdef"})
    void anythingElseIsRefusedWithTheCallersCode(String value) {
        assertThatThrownBy(() -> Inputs.month(value, CODE))
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(CODE));
    }

    @Test
    void textIsStrippedAndKeptWithinItsLength() {
        assertThat(Inputs.text("  HQ/PAY/17 ", 20, CODE)).isEqualTo("HQ/PAY/17");
        assertThat(Inputs.text("x".repeat(20), 20, CODE)).hasSize(20);
        assertThat(Inputs.text("HQ PAY 17", 20, CODE)).isEqualTo("HQ PAY 17");
        assertThat(Inputs.optionalText("  ", 20, CODE)).isNull();
        assertThat(Inputs.optionalText(null, 20, CODE)).isNull();
        assertThat(Inputs.optionalText(" A ", 20, CODE)).isEqualTo("A");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "xहy", "tab\tinside", "bell\u0007", "del\u007f"})
    void blankNonAsciiOrControlTextIsRefused(String value) {
        assertThatThrownBy(() -> Inputs.text(value, 20, CODE)).isInstanceOf(FciException.class);
    }

    @Test
    void SRS_G_11_queryTextKeepsLineBreaksAndTabs() {
        assertThat(Inputs.multilineText(" Line 1\r\nLine 2\tend ", 20, CODE))
                .isEqualTo("Line 1\r\nLine 2\tend");
        assertThat(Inputs.multilineText("x".repeat(20), 20, CODE)).hasSize(20);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "xहy", "bell\u0007", "del\u007f", "nul\u0000x"})
    void SRS_G_11_queryTextStillRefusesBlankNonAsciiAndControlCharacters(String value) {
        assertThatThrownBy(() -> Inputs.multilineText(value, 20, CODE))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_G_11_queryTextLongerThanItsLimitIsRefused() {
        assertThatThrownBy(() -> Inputs.multilineText("x".repeat(21), 20, CODE))
                .isInstanceOf(FciException.class);
    }

    @Test
    void systemValuesAreKeptToTheirColumn() {
        assertThat(Inputs.cut("abc", 3)).isEqualTo("abc");
        assertThat(Inputs.cut("abcd", 3)).isEqualTo("abc");
        assertThat(Inputs.cutEnd("order.pdf", 9)).isEqualTo("order.pdf");
        assertThat(Inputs.cutEnd("long-order.pdf", 9)).isEqualTo("order.pdf");
    }

    @Test
    void textLongerThanTheColumnIsRefused() {
        assertThatThrownBy(() -> Inputs.text("x".repeat(21), 20, CODE))
                .isInstanceOf(FciException.class);
    }
}
