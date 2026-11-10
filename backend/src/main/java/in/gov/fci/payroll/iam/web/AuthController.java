package in.gov.fci.payroll.iam.web;

import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.iam.business.LoginThrottle;
import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.LoginService;
import in.gov.fci.payroll.iam.orchestration.MeService;
import in.gov.fci.payroll.iam.orchestration.PasswordService;
import in.gov.fci.payroll.iam.orchestration.SessionService;
import in.gov.fci.payroll.iam.security.WebSessions;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.MdcKeys;
import in.gov.fci.payroll.platform.logging.SecurityAuditLogger;
import in.gov.fci.payroll.platform.logging.SecurityEvent;
import in.gov.fci.payroll.platform.logging.Sensitive;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public sign-in API of the browser ({@code /api/auth/**}). Login is e-mail (or login ID) plus
 * password, with lockout after the configured failures and no OTP; no persona or location is chosen
 * at sign-in (the office and role are switched in the workspace top bar, /auth/switch-scope). Every
 * POST is CSRF-protected, including login. Responses never contain tokens: the browser holds only
 * the HttpOnly session cookie.
 */
@RestController
@Profile("online")
public class AuthController {

    private static final String ROLE = "^[A-Z][A-Z_]{1,29}$";
    private static final String LOCATION = "^[A-Z0-9-]{2,20}$";

    /**
     * User ID (or registered e-mail) and password. {@code role} and {@code location} optionally ask
     * for the starting scope (API clients and tests); the browser sends neither.
     */
    public record LoginRequest(
            @NotBlank @Size(max = 150) @PrintableAscii String loginId,
            @Sensitive @NotBlank @Size(max = 128) String password,
            @Pattern(regexp = ROLE) String role,
            @Pattern(regexp = LOCATION) String location,
            @Pattern(regexp = "USER_ID|EMPLOYEE_ID|MOBILE") String idType) {
        @Override
        public String toString() {
            return "LoginRequest{role="
                    + role
                    + ", location="
                    + location
                    + ", idType="
                    + idType
                    + "}";
        }
    }

    /** "Sign in with OTP on registered mobile" (OD-41): the number, as typed. */
    public record MobileOtpRequest(@NotBlank @Size(max = 20) @PrintableAscii String mobile) {}

    /** X-08 step 2: the six-digit OTP of the pending sign-in (SRS-G-48). */
    public record LoginOtpRequest(
            @Sensitive @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
        @Override
        public String toString() {
            return "LoginOtpRequest{}";
        }
    }

    public record ForgotRequest(@NotBlank @Size(max = 150) @PrintableAscii String loginId) {}

    public record ResetRequest(
            @NotBlank @Size(max = 150) @PrintableAscii String loginId,
            @Sensitive @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code,
            @Sensitive @NotBlank @Size(min = 12, max = 128) String newPassword) {
        @Override
        public String toString() {
            return "ResetRequest{}";
        }
    }

    public record LocaleRequest(@NotBlank @Pattern(regexp = "^(en|hi)$") String locale) {}

    public record PeriodRequest(
            @NotBlank @Pattern(regexp = "^20[0-9]{2}-(0[1-9]|1[0-2])$") String period) {}

    private final LoginService login;
    private final OrgDirectory org;
    private final SessionService sessions;
    private final PasswordService passwords;
    private final MeService me;
    private final AccessContextService contexts;
    private final LoginThrottle throttle;
    private final SecurityAuditLogger securityLog;
    private final PayrollPeriods periods;
    private final ApiProperties properties;

    public AuthController(
            LoginService login,
            OrgDirectory org,
            SessionService sessions,
            PasswordService passwords,
            MeService me,
            AccessContextService contexts,
            LoginThrottle throttle,
            SecurityAuditLogger securityLog,
            ApiProperties properties,
            PayrollPeriods periods) {
        this.login = login;
        this.org = org;
        this.sessions = sessions;
        this.passwords = passwords;
        this.me = me;
        this.contexts = contexts;
        this.throttle = throttle;
        this.securityLog = securityLog;
        this.properties = properties;
        this.periods = periods;
    }

    /** Issues the CSRF cookie ({@code XSRF-TOKEN}); call once before the first POST. */
    @GetMapping("/auth/csrf")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * What the sign-in page needs before sign-in: only the demo flag (the demo note). Personas and
     * locations are no longer chosen at sign-in; the scopes of the signed-in user are /auth/scopes.
     */
    @GetMapping("/auth/login-options")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Map<String, Object>> loginOptions() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("demo", properties.demo().enabled());
        // SRS-G-48 (OD-36): whether the password is followed by an OTP to the registered mobile.
        body.put("otpAtLogin", properties.otp().atLogin());
        // OD-41: whether "Sign in with OTP on registered mobile" is offered.
        body.put("mobileOtp", login.mobileLoginAvailable());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * Signs in and establishes the browser session (new session id); returns where to go next: the
     * workspace, which opens on the acting role's landing screen when its RBAC allows it (else the
     * first screen of its menu). There is no intermediate welcome page.
     */
    @PostMapping("/auth/login")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Map<String, Object>> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        ApiProperties.RateLimits limits = properties.rateLimits();
        limit(
                "login-ip",
                LoginThrottle.ipKey(clientIp()),
                limits.loginPerIpPerMinute(),
                Duration.ofMinutes(1));
        limit(
                "login-user",
                LoginThrottle.identityKey(body.loginId()),
                limits.loginPerUsernamePerMinute(),
                Duration.ofMinutes(1));
        Long locationId =
                body.location() == null || body.location().isBlank()
                        ? null
                        : locationIdOf(body.location());
        LoginService.LoginOutcome outcome =
                login.login(
                        new LoginService.LoginRequest(
                                body.loginId(),
                                body.password(),
                                body.role(),
                                locationId,
                                clientIp(),
                                request.getHeader("User-Agent"),
                                body.idType() == null
                                        ? LoginService.IdType.USER_ID
                                        : LoginService.IdType.valueOf(body.idType())));
        return switch (outcome) {
            case LoginService.Signed signed -> signedIn(request, signed.result());
            case LoginService.OtpRequired otp -> otpSent(request, otp);
        };
    }

    /**
     * X-08 step 2 (SRS-G-48): the OTP of this browser's pending sign-in. Without one (never asked,
     * already used, or expired) the code is refused as an invalid OTP; a wrong code is counted.
     */
    @PostMapping("/auth/login/otp")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Map<String, Object>> loginOtp(
            @Valid @RequestBody LoginOtpRequest body, HttpServletRequest request) {
        LoginService.PendingLogin pending = pending(request);
        ApiProperties.RateLimits limits = properties.rateLimits();
        limit(
                "login-ip",
                LoginThrottle.ipKey(clientIp()),
                limits.loginPerIpPerMinute(),
                Duration.ofMinutes(1));
        LoginService.LoginResult result =
                login.completeWithOtp(
                        pending, body.code(), clientIp(), request.getHeader("User-Agent"));
        WebSessions.clearPendingLogin(request);
        return signedIn(request, result);
    }

    /**
     * "Sign in with OTP on registered mobile" (OD-41): sends the sign-in code to the account that
     * carries the number; the code alone then signs in (POST /auth/login/otp). An unregistered
     * number is answered the same and nothing is sent. Rate-limited per address and per number.
     */
    @PostMapping("/auth/login/mobile")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Map<String, Object>> loginMobile(
            @Valid @RequestBody MobileOtpRequest body, HttpServletRequest request) {
        long started = System.nanoTime();
        ApiProperties.RateLimits limits = properties.rateLimits();
        limit(
                "login-ip",
                LoginThrottle.ipKey(clientIp()),
                limits.loginPerIpPerMinute(),
                Duration.ofMinutes(1));
        limit(
                "login-user",
                LoginThrottle.identityKey(LoginService.normaliseMobile(body.mobile())),
                limits.loginPerUsernamePerMinute(),
                Duration.ofMinutes(1));
        ResponseEntity<Map<String, Object>> answer =
                otpSent(request, login.requestMobileOtp(body.mobile()));
        answerNoSoonerThanTheFloor(started);
        return answer;
    }

    /** Resend OTP (X-08): a new code for this browser's pending sign-in; the earlier one stops. */
    @PostMapping("/auth/login/otp/resend")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Map<String, Object>> resendLoginOtp(HttpServletRequest request) {
        LoginService.PendingLogin pending = pending(request);
        limit(
                "login-user",
                LoginThrottle.identityKey(pending.loginId()),
                properties.rateLimits().loginPerUsernamePerMinute(),
                Duration.ofMinutes(1));
        return otpSent(request, login.resendOtp(pending));
    }

    /** This browser's pending sign-in, still within the OTP's validity; else INVALID_OTP. */
    private LoginService.PendingLogin pending(HttpServletRequest request) {
        LoginService.PendingLogin pending =
                WebSessions.pendingLogin(request)
                        .orElseThrow(() -> new FciException(ApiErrorCode.INVALID_OTP));
        if (pending.issuedAt().plus(properties.otp().ttl()).isBefore(java.time.Instant.now())) {
            WebSessions.clearPendingLogin(request);
            throw new FciException(ApiErrorCode.INVALID_OTP);
        }
        return pending;
    }

    private ResponseEntity<Map<String, Object>> signedIn(
            HttpServletRequest request, LoginService.LoginResult result) {
        WebSessions.signIn(request, result.session());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("route", Personas.WORKSPACE);
        response.put("passwordChangeRequired", result.session().passwordChangeRequired());
        response.put("landingScreen", result.landingScreen());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    /**
     * 202: the OTP was sent; where to (masked) and for how long. The pending sign-in stays here.
     */
    private ResponseEntity<Map<String, Object>> otpSent(
            HttpServletRequest request, LoginService.OtpRequired otp) {
        WebSessions.pendingLogin(request, otp.pending());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("otpRequired", true);
        response.put("channel", otp.channel());
        response.put("destination", otp.destination());
        response.put("validSeconds", otp.validSeconds());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(response);
    }

    /** Ends the session and clears every cookie and client-side store of this origin. */
    @PostMapping("/auth/logout")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        WebSessions.user(request).ifPresent(user -> sessions.end(user.sessionId()));
        WebSessions.signOut(request);
        ResponseCookie csrf =
                ResponseCookie.from("XSRF-TOKEN", "")
                        .path("/")
                        .maxAge(0)
                        .secure(properties.session().secureCookie())
                        .sameSite("Strict")
                        .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, csrf.toString())
                .header("Clear-Site-Data", "\"cookies\", \"storage\"")
                .build();
    }

    /**
     * Forgot-password: an OTP to the registered e-mail. Always 202, whether or not an account
     * matched.
     */
    @PostMapping("/auth/password/forgot")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> forgot(@Valid @RequestBody ForgotRequest body) {
        long started = System.nanoTime();
        ApiProperties.RateLimits limits = properties.rateLimits();
        limit(
                "forgot-ip",
                LoginThrottle.ipKey(clientIp()),
                limits.forgotPerIpPerHour(),
                Duration.ofHours(1));
        limit(
                "forgot-id",
                LoginThrottle.identityKey(body.loginId()),
                limits.forgotPerIdentifierPerHour(),
                Duration.ofHours(1));
        passwords.requestReset(body.loginId());
        answerNoSoonerThanTheFloor(started);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * T1 for the account-discovery questions (forgot password, sign-in by mobile): the answer is
     * the same 202 for a known and an unknown identifier, and it is given no sooner than the
     * sign-in failure floor, so the time a known one takes to issue and send its code does not tell
     * it apart (security review 2026-10-31). Request threads are virtual: the wait holds no
     * platform thread.
     */
    private void answerNoSoonerThanTheFloor(long startedNanos) {
        long remaining =
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                                properties.rateLimits().loginFailureFloorMillis())
                        - (System.nanoTime() - startedNanos);
        if (remaining <= 0) {
            return;
        }
        try {
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @PostMapping("/auth/password/reset")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetRequest body) {
        limit(
                "reset-ip",
                LoginThrottle.ipKey(clientIp()),
                properties.rateLimits().resetPerIpPerHour(),
                Duration.ofHours(1));
        passwords.reset(body.loginId(), body.code(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** EN | हिं toggle: stored as the user's preference (if signed in) and in the cookie. */
    @PutMapping("/auth/locale")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> locale(
            @Valid @RequestBody LocaleRequest body, HttpServletRequest request) {
        if (WebSessions.user(request).isPresent()) {
            me.setLocale(contexts.current(), body.locale());
        }
        ResponseCookie cookie =
                ResponseCookie.from(properties.i18n().cookieName(), body.locale())
                        .path("/")
                        .maxAge(Duration.ofDays(365))
                        .secure(properties.session().secureCookie())
                        .sameSite("Lax")
                        .httpOnly(true)
                        .build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }

    /**
     * The payroll period the session works on (top-bar selector): an approved period of the M-10
     * calendar, the current one or a back-dated one, never a future one. Kept in the session only.
     */
    @PutMapping("/auth/period")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> period(
            @Valid @RequestBody PeriodRequest body, HttpServletRequest request) {
        YearMonth period = YearMonth.parse(body.period());
        if (periods.of(period).isEmpty() || period.isAfter(periods.current())) {
            throw new FciException(
                    ApiErrorCode.CYCLE_NO_CALENDAR, new Object[] {body.period()}, List.of(), null);
        }
        WebSessions.workingPeriod(request, period);
        return ResponseEntity.noContent().build();
    }

    private Long locationIdOf(String locationCode) {
        return org.byCode(locationCode)
                .map(OrgLocation::getId)
                .orElseThrow(() -> new FciException(ApiErrorCode.UNKNOWN_LOCATION));
    }

    private static String clientIp() {
        return MDC.get(MdcKeys.CLIENT_IP);
    }

    private void limit(String scope, String key, int permits, Duration period) {
        OptionalLong retryAfter = throttle.tryAcquire(scope, key, permits, period);
        if (retryAfter.isPresent()) {
            securityLog.log(
                    SecurityEvent.failure(
                            "RATE_LIMITED",
                            "anonymous",
                            scope.toUpperCase(Locale.ROOT).replace('-', '_')));
            throw new RateLimitedException(retryAfter.getAsLong());
        }
    }
}
