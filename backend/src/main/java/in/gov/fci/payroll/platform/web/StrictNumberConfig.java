package in.gov.fci.payroll.platform.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Strict decimal numbers in paths and parameters (OWASP A03 input validation; SRS-G-13, SRS-22-8):
 * Spring's default String to Long / Integer conversion reads "0x10" and "#10" as hexadecimal
 * (NumberUtils.parseNumber), so {@code POST /v1/approvals/0x10/decision} reached approval 16
 * (SecurityEdgeCasesIT). Only an optional minus sign and decimal digits are accepted; anything else
 * is a type mismatch (400) before any handler runs. An empty value stays absent, as before.
 */
@Configuration(proxyBeanMethods = false)
@Profile("online")
public class StrictNumberConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        // Added after the defaults: a converter registered later for the same pair is tried first.
        registry.addConverter(String.class, Long.class, StrictNumberConfig::toLong);
        registry.addConverter(String.class, Integer.class, StrictNumberConfig::toInteger);
    }

    /** "-?[0-9]+" as a Long; null for an empty value; anything else is refused. */
    static Long toLong(String value) {
        String text = decimal(value);
        return text == null ? null : Long.valueOf(text);
    }

    /** "-?[0-9]+" as an Integer; null for an empty value; anything else is refused. */
    static Integer toInteger(String value) {
        String text = decimal(value);
        return text == null ? null : Integer.valueOf(text);
    }

    private static String decimal(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        int start = value.charAt(0) == '-' ? 1 : 0;
        if (start == value.length()) {
            throw new IllegalArgumentException("not a decimal number");
        }
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("not a decimal number");
            }
        }
        return value;
    }
}
