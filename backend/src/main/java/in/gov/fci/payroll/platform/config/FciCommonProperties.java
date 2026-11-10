package in.gov.fci.payroll.platform.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the shared FCI infrastructure, bound from {@code fci.common.*}.
 *
 * @param appName short application name written to the {@code app} MDC key (e.g. {@code
 *     fci-payroll}); defaults to {@code spring.application.name}
 * @param errorTypeBaseUri base of the RFC 9457 {@code type} URIs
 * @param maxJsonBodyBytes largest accepted JSON request body
 * @param maxJsonNestingDepth deepest accepted JSON nesting
 * @param managementPort local port of the management connector; 0 disables port separation
 * @param actuatorBasePath Actuator base path
 * @param logValueMaxLength longest argument value rendered by the ESER aspect
 */
@Validated
@ConfigurationProperties(prefix = "fci.common")
public record FciCommonProperties(
        String appName,
        @DefaultValue("https://errors.fci.gov.in/") @NotBlank String errorTypeBaseUri,
        @DefaultValue("1048576") @Min(1024) @Max(10485760) int maxJsonBodyBytes,
        @DefaultValue("32") @Min(4) @Max(256) int maxJsonNestingDepth,
        @DefaultValue("0") @Min(0) @Max(65535) int managementPort,
        @DefaultValue("/actuator") @NotBlank String actuatorBasePath,
        @DefaultValue("128") @Min(16) @Max(1024) int logValueMaxLength) {}
