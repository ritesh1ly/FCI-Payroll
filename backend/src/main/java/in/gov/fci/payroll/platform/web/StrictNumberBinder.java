package in.gov.fci.payroll.platform.web;

import java.beans.PropertyEditorSupport;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Closes the gap the converters of {@link StrictNumberConfig} leave (OWASP A03; SRS-G-13,
 * SRS-22-8): when a ConversionService converter refuses a value, the binder's TypeConverterDelegate
 * falls back to its default editors, which read "0x10" as 16, so {@code POST
 * /v1/approvals/0x10/decision} still reached approval 16 (SecurityEdgeCasesIT). A custom editor is
 * tried first and has no such fallback: its refusal is a type mismatch (400) for every path
 * variable and request parameter.
 */
@ControllerAdvice
@Profile("online")
public class StrictNumberBinder {

    /** Registers the strict editors on every request's binder. */
    @InitBinder
    public void strictNumbers(WebDataBinder binder) {
        binder.registerCustomEditor(Long.class, new Strict(StrictNumberConfig::toLong));
        binder.registerCustomEditor(long.class, new Strict(StrictNumberConfig::toLong));
        binder.registerCustomEditor(Integer.class, new Strict(StrictNumberConfig::toInteger));
        binder.registerCustomEditor(int.class, new Strict(StrictNumberConfig::toInteger));
    }

    /** Text to number through the strict parser; an IllegalArgumentException refuses the value. */
    static final class Strict extends PropertyEditorSupport {
        private final Function<String, ? extends Number> parse;

        Strict(Function<String, ? extends Number> parse) {
            this.parse = parse;
        }

        @Override
        public void setAsText(String text) {
            setValue(parse.apply(text));
        }

        @Override
        public String getAsText() {
            Object value = getValue();
            return value == null ? "" : value.toString();
        }
    }
}
