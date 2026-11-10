package in.gov.fci.payroll.platform.security;

import in.gov.fci.payroll.platform.config.ApiProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OpenAPI 3 documentation of the online API. Swagger UI and the API docs are reachable only where
 * springdoc is enabled ({@code local}, {@code dev}); in uat and prod springdoc is disabled and the
 * paths fall through to the deny-all chain.
 */
@Configuration(proxyBeanMethods = false)
@Profile("online")
public class OpenApiSecurityConfig {

    @Bean
    OpenAPI fciPayrollOpenApi(ApiProperties properties) {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("FCILekha Payroll API")
                                .version("v1")
                                .description(
                                        "Session sign-in, RBAC on the target model (S27), employee"
                                            + " master, maker-checker, audit and the payroll job"
                                            + " queue. Authentication is the HttpOnly session"
                                            + " cookie; every unsafe method also needs the"
                                            + " X-XSRF-TOKEN header."))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "session",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.COOKIE)
                                                .name(properties.session().cookieName())))
                .addSecurityItem(new SecurityRequirement().addList("session"));
    }

    @Bean
    @Order(3)
    @ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
    SecurityFilterChain openApiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
