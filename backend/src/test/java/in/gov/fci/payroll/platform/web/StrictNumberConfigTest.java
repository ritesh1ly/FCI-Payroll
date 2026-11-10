package in.gov.fci.payroll.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;

/**
 * OWASP A03 / SRS-22-8: a number in a path or parameter is decimal only - "0x10", "#10", "1e3", "
 * 7", "+7", "1.5" and a lone minus are refused (the default conversion read "0x10" as 16); an empty
 * value stays absent.
 */
class StrictNumberConfigTest {

    @Test
    void SRS_22_8_onlyDecimalDigitsWithAnOptionalMinusAreNumbers() {
        assertThat(StrictNumberConfig.toLong("16")).isEqualTo(16L);
        assertThat(StrictNumberConfig.toLong("-1")).isEqualTo(-1L);
        assertThat(StrictNumberConfig.toLong("0")).isZero();
        assertThat(StrictNumberConfig.toLong("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThat(StrictNumberConfig.toInteger("42")).isEqualTo(42);
        assertThat(StrictNumberConfig.toInteger("-42")).isEqualTo(-42);
        assertThat(StrictNumberConfig.toLong("")).isNull();
        assertThat(StrictNumberConfig.toLong(null)).isNull();
        assertThat(StrictNumberConfig.toInteger("")).isNull();
    }

    @Test
    void SRS_22_8_hexOctalExponentSignAndSpacesAreRefused() {
        for (String bad :
                new String[] {"0x10", "#10", "0X1F", "1e3", "+7", " 7", "7 ", "1.5", "-", "%20"}) {
            assertThatThrownBy(() -> StrictNumberConfig.toLong(bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> StrictNumberConfig.toInteger(bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // Out of range: refused as well (NumberFormatException is an IllegalArgumentException).
        assertThatThrownBy(() -> StrictNumberConfig.toLong("99999999999999999999"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StrictNumberConfig.toInteger("2147483648"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SRS_22_8_theRegisteredConvertersReplaceTheLenientDefaults() {
        DefaultFormattingConversionService conversion = new DefaultFormattingConversionService();
        // The default reads hexadecimal ...
        assertThat(conversion.convert("0x10", Long.class)).isEqualTo(16L);
        new StrictNumberConfig().addFormatters(conversion);
        // ... the registered one does not.
        assertThat(conversion.convert("10", Long.class)).isEqualTo(10L);
        assertThat(conversion.convert("10", Integer.class)).isEqualTo(10);
        assertThatThrownBy(() -> conversion.convert("0x10", Long.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> conversion.convert("#10", Integer.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void SRS_22_8_theBinderEditorsAreTriedBeforeTheDefaultFallbackAndRefuseHex() {
        org.springframework.web.bind.WebDataBinder binder =
                new org.springframework.web.bind.WebDataBinder(null);
        new StrictNumberBinder().strictNumbers(binder);
        assertThat(binder.convertIfNecessary("16", Long.class)).isEqualTo(16L);
        assertThat(binder.convertIfNecessary("16", long.class)).isEqualTo(16L);
        assertThat(binder.convertIfNecessary("42", Integer.class)).isEqualTo(42);
        assertThat(binder.convertIfNecessary("42", int.class)).isEqualTo(42);
        assertThat(binder.convertIfNecessary("", Long.class)).isNull();
        for (Class<?> type : new Class<?>[] {Long.class, long.class, Integer.class, int.class}) {
            assertThatThrownBy(() -> binder.convertIfNecessary("0x10", type))
                    .as(type.getName())
                    .isInstanceOf(org.springframework.beans.TypeMismatchException.class);
        }
        StrictNumberBinder.Strict editor =
                new StrictNumberBinder.Strict(StrictNumberConfig::toLong);
        assertThat(editor.getAsText()).isEmpty();
        editor.setAsText("7");
        assertThat(editor.getAsText()).isEqualTo("7");
    }
}
