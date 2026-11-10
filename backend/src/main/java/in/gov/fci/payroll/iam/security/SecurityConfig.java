package in.gov.fci.payroll.iam.security;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.SessionService;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.security.ProblemDetailAccessDeniedHandler;
import in.gov.fci.payroll.platform.security.ProblemDetailAuthenticationEntryPoint;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * HTTP security of the backend (deny by default). Token checks and API logging live here, in the
 * Spring Security chain; there is no separate API gateway.
 *
 * <ol>
 *   <li>{@code /actuator/**} (both profiles): health and info public (management port only),
 *       metrics and Prometheus behind HTTP Basic (and the Chaos Monkey endpoint with the profile
 *       {@code chaos-monkey});
 *   <li>browser APIs (profile {@code online}): {@code /auth/**}, {@code /menu}, {@code /i18n/**},
 *       {@code /v1/**}. Authentication is the server-side session ({@code FCISESSION} cookie), CSRF
 *       is checked on every unsafe method including login ({@code XSRF-TOKEN} cookie + {@code
 *       X-XSRF-TOKEN} header) after a write the browser marks as cross-site has been refused
 *       ({@link FetchMetadataFilter}), then the session policy and method security per endpoint;
 *   <li>everything else: denied. The batch profile therefore answers nothing but actuator.
 * </ol>
 *
 * CORS stays disabled: NGINX serves the Next.js frontend and the backend from one origin.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    private static final Set<String> PUBLIC_PATHS =
            Set.of(
                    "/auth/csrf",
                    "/auth/login-options",
                    "/auth/login",
                    // X-08 step 2 (SRS-G-48): only with this browser's pending sign-in
                    // (LoginService).
                    "/auth/login/otp",
                    "/auth/login/otp/resend",
                    // OD-146: the demo viewer of this browser's pending sign-in (404 unless a demo
                    // stack switched it on; DemoOtpController).
                    "/auth/login/otp/demo-code",
                    "/auth/login/mobile",
                    "/auth/logout",
                    "/auth/password/forgot",
                    "/auth/password/reset",
                    "/auth/locale");

    private final ProblemDetailAccessDeniedHandler deniedHandler;
    private final ProblemDetailAuthenticationEntryPoint entryPoint;

    public SecurityConfig(
            ProblemDetailAccessDeniedHandler deniedHandler,
            ProblemDetailAuthenticationEntryPoint entryPoint) {
        this.deniedHandler = deniedHandler;
        this.entryPoint = entryPoint;
    }

    /**
     * A request the firewall rejects (encoded slash, dot segment, semicolon, CRLF, NUL: path
     * tampering, WSTG-ATHZ-01) is answered as a neutral 400 problem with nosniff and no-store, not
     * with the container's error page (security review 2026-11-04).
     */
    @Bean
    org.springframework.security.web.firewall.RequestRejectedHandler requestRejectedHandler(
            ProblemResponder responder) {
        return (request, response, rejected) ->
                responder.write(
                        null,
                        in.gov.fci.payroll.platform.error.CommonErrorCode.MALFORMED_REQUEST,
                        request,
                        response);
    }

    /** Paths reachable without a session. */
    static boolean isPublic(String path) {
        return PUBLIC_PATHS.contains(path) || path.equals("/i18n") || path.startsWith("/i18n/");
    }

    @Bean
    @Order(1)
    SecurityFilterChain actuatorChain(
            HttpSecurity http,
            @Value("${fci.api.management.username:metrics}") String username,
            @Value("${fci.api.management.password-hash:}") String passwordHash,
            org.springframework.core.env.Environment environment)
            throws Exception {
        // Resilience runs only: the Chaos Monkey endpoint, behind the same Basic credential, when
        // the chaos-monkey profile is active (StartupGuards allow it on a local demo stack only).
        boolean chaos = environment.matchesProfiles("chaos-monkey");
        UserDetailsService metricsUsers =
                passwordHash.isBlank()
                        ? new InMemoryUserDetailsManager()
                        : new InMemoryUserDetailsManager(
                                User.withUsername(username)
                                        .password(passwordHash)
                                        .authorities("METRICS")
                                        .build());
        http.securityMatcher("/actuator/**")
                .authorizeHttpRequests(
                        auth -> {
                            auth.requestMatchers(
                                            "/actuator/health",
                                            "/actuator/health/**",
                                            "/actuator/info")
                                    .permitAll()
                                    .requestMatchers(
                                            "/actuator/metrics/**",
                                            "/actuator/prometheus",
                                            "/actuator/scheduledtasks")
                                    .hasAuthority("METRICS");
                            if (chaos) {
                                auth.requestMatchers(
                                                "/actuator/chaosmonkey", "/actuator/chaosmonkey/**")
                                        .hasAuthority("METRICS");
                            }
                            auth.anyRequest().denyAll();
                        })
                // The ProblemDetail entry point answers 401 itself; Basic's default calls
                // sendError,
                // whose /error dispatch the deny chain turned into 403.
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .authenticationManager(metricsAuthentication(metricsUsers))
                .passwordManagement(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return common(http).build();
    }

    @Bean
    @Order(2)
    @Profile("online")
    SecurityFilterChain browserChain(
            HttpSecurity http,
            ApiProperties properties,
            AccessContextService contexts,
            ProblemResponder responder,
            SessionService sessions,
            AuditService audit)
            throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Path "/" so the Next.js server (same origin, outside the /api context) can read it too.
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieCustomizer(
                cookie -> cookie.secure(properties.session().secureCookie()).sameSite("Strict"));
        http.securityMatcher("/auth/**", "/v1/**", "/menu", "/i18n", "/i18n/**")
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(PUBLIC_PATHS.toArray(String[]::new))
                                        .permitAll()
                                        .requestMatchers("/i18n", "/i18n/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfRepository)
                                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .securityContext(
                        context ->
                                context.securityContextRepository(
                                        new SessionSecurityContextRepository()))
                // Sessions are created only by a successful sign-in (WebSessions.signIn).
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.NEVER))
                .addFilterBefore(new ApiAccessLogFilter(), SecurityContextHolderFilter.class)
                // A write a browser marks as cross-site is refused before the token check
                // (FetchMetadataFilter: the double-submit token alone yields to a planted cookie).
                .addFilterBefore(new FetchMetadataFilter(responder), CsrfFilter.class)
                .addFilterAfter(
                        new SessionPolicyFilter(
                                contexts,
                                responder,
                                sessions,
                                audit,
                                properties.session().bindClientAddress()),
                        SecurityContextHolderFilter.class);
        return common(http).build();
    }

    @Bean
    @Order(Integer.MAX_VALUE)
    SecurityFilterChain denyEverythingElse(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return common(http).build();
    }

    /**
     * Metrics credentials are verified with Spring's standard encoders (the hash from
     * scripts/GenerateSecrets.java is {@code {pbkdf2@SpringSecurity_v5_8}}), not with the
     * user-password encoder, which accepts only Argon2 and bcrypt.
     */
    private static AuthenticationManager metricsAuthentication(UserDetailsService users) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        return new ProviderManager(provider);
    }

    private HttpSecurity common(HttpSecurity http) throws Exception {
        return http.cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .exceptionHandling(
                        e ->
                                e.accessDeniedHandler(deniedHandler)
                                        .authenticationEntryPoint(entryPoint))
                .headers(
                        h ->
                                h.contentSecurityPolicy(
                                                csp ->
                                                        csp.policyDirectives(
                                                                "default-src 'none';"
                                                                    + " frame-ancestors 'none'"))
                                        .frameOptions(f -> f.deny())
                                        .referrerPolicy(
                                                r ->
                                                        r.policy(
                                                                ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .NO_REFERRER))
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.includeSubDomains(true)
                                                                .maxAgeInSeconds(31_536_000))
                                        .permissionsPolicyHeader(
                                                p ->
                                                        p.policy(
                                                                "geolocation=(), camera=(),"
                                                                        + " microphone=()")));
    }
}
