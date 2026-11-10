package in.gov.fci.payroll.iam.config;

import in.gov.fci.payroll.iam.business.PasswordPolicy;
import in.gov.fci.payroll.platform.config.ApiProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/** Domain policy objects configured from properties and bundled resources. */
@Configuration(proxyBeanMethods = false)
public class DomainConfig {

    /**
     * Loads the common-password deny-list (one per line, {@code #} comments) bundled with the WAR.
     */
    @Bean
    PasswordPolicy passwordPolicy(ApiProperties properties, ResourceLoader resourceLoader) {
        Set<String> common = new HashSet<>();
        var resource = resourceLoader.getResource(properties.password().commonPasswordsLocation());
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .forEach(common::add);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read the common-password list", ex);
        }
        return new PasswordPolicy(common);
    }
}
