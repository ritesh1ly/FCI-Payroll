package in.gov.fci.payroll.integration.web;

import in.gov.fci.payroll.integration.orchestration.HrmsAccessService;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.security.ProblemDetailAccessDeniedHandler;
import in.gov.fci.payroll.platform.security.ProblemDetailAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * The security chain of the published HRMS APIs (SRS-INT-4): system-to-system, so no session
 * (STATELESS), no CSRF token (there is no browser cookie to forge) and no login; its own matcher
 * takes only the paths the URL settings publish, so the browser chain on {@code /v1} and the deny
 * chain are untouched. Everything else about access is {@link HrmsApiAuthFilter}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("online")
public class HrmsApiSecurityConfig {

    @Bean
    @Order(3)
    SecurityFilterChain hrmsApiChain(
            HttpSecurity http,
            HrmsApiRoutes routes,
            HrmsAccessService access,
            ProblemResponder responder,
            ProblemDetailAuthenticationEntryPoint entryPoint,
            ProblemDetailAccessDeniedHandler denied)
            throws Exception {
        http.securityMatcher(routes::isHrmsPath)
                .authorizeHttpRequests(
                        auth -> auth.anyRequest().hasAuthority(HrmsApiAuthFilter.AUTHORITY))
                .addFilterBefore(
                        new HrmsApiAuthFilter(routes, access, responder), AuthorizationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(
                        e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
