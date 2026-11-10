package in.gov.fci.payroll.platform.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Lets a Boolean setting be written as the Y / N flag the project uses everywhere in configuration
 * (owner 2026-11-06: "all flag based"), besides what Spring already accepts. Spring alone takes
 * true / false, yes / no, on / off and 1 / 0 but not a single letter, and YAML does not read a bare
 * {@code Y} or {@code N} as a boolean, so {@code mask-data: Y} would stop the start. Anything else
 * is refused with the offending value named, so a typo never silently becomes false.
 */
@Component
@ConfigurationPropertiesBinding
public class YesNoBooleanConverter implements Converter<String, Boolean> {

    @Override
    public Boolean convert(String source) {
        String value = source.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "y", "yes", "true", "on", "1" -> Boolean.TRUE;
            case "n", "no", "false", "off", "0" -> Boolean.FALSE;
            case "" -> null;
            default ->
                    throw new IllegalArgumentException(
                            "'" + source + "' is not a flag: use Y or N (or true / false)");
        };
    }
}
