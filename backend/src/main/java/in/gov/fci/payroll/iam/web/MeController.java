package in.gov.fci.payroll.iam.web;

import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.MeService;
import in.gov.fci.payroll.iam.orchestration.MenuCatalog;
import in.gov.fci.payroll.iam.orchestration.PasswordService;
import in.gov.fci.payroll.iam.orchestration.ScopeResolver;
import in.gov.fci.payroll.iam.orchestration.SessionTimeouts;
import in.gov.fci.payroll.iam.security.SessionUser;
import in.gov.fci.payroll.iam.security.WebSessions;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.i18n.MessageBundles;
import in.gov.fci.payroll.platform.logging.MdcSecurityContextFilter;
import in.gov.fci.payroll.platform.logging.SecurityAuditLogger;
import in.gov.fci.payroll.platform.logging.SecurityEvent;
import in.gov.fci.payroll.platform.logging.Sensitive;
import in.gov.fci.payroll.platform.paging.PageSizes;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.workflow.api.Otp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own session as the frontend needs it: profile with effective permissions
 * ({@code /auth/me}), the scopes of the user's OWN groups and switching between them, password
 * change, the filtered menu and the governed buttons of the screens. Route guards and the menu are
 * UX only; every business call is authorised again on the server.
 */
@RestController
@Profile("online")
@PreAuthorize("isAuthenticated()")
public class MeController {

    private static final Logger log = LoggerFactory.getLogger(MeController.class);

    private static final String ROLE = "^[A-Z][A-Z_]{1,29}$";
    private static final String LOCATION = "^[A-Z0-9-]{2,20}$";

    /**
     * The target scope: an acting role and, except for Employee (eSeva), an office code; {@code
     * otp} is needed when the role changes (SRS-G-48, Module 02 decision D-2).
     */
    public record SwitchRequest(
            @NotBlank @Pattern(regexp = ROLE) String role,
            @Pattern(regexp = LOCATION) String location,
            @Valid Otp otp) {}

    /**
     * The own password change (X-08): current and new password, and the OTP that confirms it
     * (SRS-G-21; absent on the first call, which answers 428 with the challenge).
     */
    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) @Sensitive String currentPassword,
            @NotBlank @Size(max = 128) @Sensitive String newPassword,
            @Valid Otp otp) {
        @Override
        public String toString() {
            return "ChangePasswordRequest{}";
        }
    }

    private final MeService me;
    private final SessionTimeouts timeouts;
    private final OrgDirectory org;
    private final PasswordService passwords;
    private final AccessContextService contexts;
    private final MessageBundles bundles;
    private final MenuCatalog menu;
    private final SecurityAuditLogger securityLog;
    private final Clock clock;
    private final ApiProperties properties;
    private final PageSizes pageSizes;

    public MeController(
            MeService me,
            SessionTimeouts timeouts,
            OrgDirectory org,
            PasswordService passwords,
            AccessContextService contexts,
            MessageBundles bundles,
            MenuCatalog menu,
            SecurityAuditLogger securityLog,
            Clock clock,
            ApiProperties properties,
            PageSizes pageSizes) {
        this.me = me;
        this.timeouts = timeouts;
        this.org = org;
        this.passwords = passwords;
        this.contexts = contexts;
        this.bundles = bundles;
        this.menu = menu;
        this.securityLog = securityLog;
        this.clock = clock;
        this.properties = properties;
        this.pageSizes = pageSizes;
    }

    /**
     * Identity, live persona id and route, login location label, period, roles, permissions,
     * groups, read-only flag, locale and session timing.
     */
    @GetMapping("/auth/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        MeService.Me view = me.me(contexts.current(), WebSessions.workingPeriod(request));
        Personas.Persona persona = Personas.byApiCode(view.personaCode()).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", view.userId());
        body.put("loginId", view.loginId());
        body.put("displayName", view.displayName());
        body.put("persona", persona == null ? null : persona.id());
        body.put("route", persona == null ? Personas.WORKSPACE : persona.route());
        body.put("landingScreen", view.landingScreen());
        body.put("role", view.actingRole());
        body.put("roleName", view.actingRoleName());
        body.put(
                "location",
                view.location() == null
                        ? null
                        : Map.of(
                                "code", view.location().code(),
                                "label",
                                        Personas.locationLabel(
                                                view.location().type(),
                                                view.location().name(),
                                                view.location().code())));
        body.put("period", view.period());
        body.put("periodStatus", view.periodStatus());
        body.put("roles", view.roles());
        body.put("groups", view.groups());
        body.put("permissions", view.permissions());
        body.put("readOnlyConsolidated", view.readOnlyConsolidated());
        body.put("passwordChangeRequired", view.passwordChangeRequired());
        body.put("stepUpRequired", view.stepUpRequired());
        body.put("sensitiveScreens", view.sensitiveScreens());
        body.put("hqOnlyScreens", view.hqOnlyScreens());
        body.put("atHeadquarters", view.atHeadquarters());
        // fci.api.otp.required: false only on development and local machines (the screens say so).
        body.put("otpRequired", properties.otp().verificationRequired());
        // docs/plan/X-pagination.md: the page sizes every list offers and the one it starts with.
        body.put(
                "paging",
                Map.of("sizes", pageSizes.offered(), "defaultSize", pageSizes.defaultSize()));
        body.put(
                "stepUpValidUntil",
                view.stepUpValidUntil() == null ? null : view.stepUpValidUntil().toString());
        body.put("locale", bundles.resolve(request, view.preferredLocale()));
        body.put("lastLoginAt", view.lastLoginAt() == null ? null : view.lastLoginAt().toString());
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put(
                "idleExpiresAt",
                clock.instant().plusSeconds(WebSessions.idleTimeoutSeconds(request)).toString());
        timing.put(
                "absoluteExpiresAt",
                WebSessions.absoluteExpiresAt(request).map(Object::toString).orElse(null));
        timing.put("idleTimeoutSeconds", WebSessions.idleTimeoutSeconds(request));
        body.put("session", timing);
        body.put("demo", properties.demo().enabled());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * The (office, acting role) pairs of the user's OWN groups and eSeva grant (never another
     * user's): the options of the top-bar 🏢 office and 👤 role selectors. {@code location} is null
     * for Employee (eSeva).
     */
    @GetMapping("/auth/scopes")
    public ResponseEntity<List<Map<String, Object>>> scopes() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MeService.ScopeOption option : me.scopeOptions(contexts.current())) {
            MeService.LocationView location = option.location();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", option.roleCode());
            entry.put("roleName", option.roleName());
            entry.put(
                    "persona",
                    Personas.byApiCode(option.personaCode())
                            .map(Personas.Persona::id)
                            .orElse(null));
            entry.put("location", location == null ? null : location.code());
            entry.put("locationType", location == null ? null : location.type());
            entry.put(
                    "label",
                    location == null
                            ? null
                            : Personas.locationLabel(
                                    location.type(), location.name(), location.code()));
            entry.put("current", option.current());
            result.add(entry);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    /**
     * Switches the session to another of the user's own (office, role) scopes without signing out;
     * the session id rotates and the next /auth/me, /menu and every call are in the new scope.
     */
    @PostMapping("/auth/switch-scope")
    public ResponseEntity<Void> switchScope(
            @Valid @RequestBody SwitchRequest body, HttpServletRequest request) {
        AccessContext context = contexts.current();
        Long locationId =
                body.location() == null || body.location().isBlank()
                        ? null
                        : org.byCode(body.location())
                                .map(OrgLocation::getId)
                                .orElseThrow(
                                        () -> new FciException(ApiErrorCode.SCOPE_SWITCH_DENIED));
        ScopeResolver.RoleScope scope =
                me.switchScope(context, body.role(), locationId, Otp.proof(body.otp()));
        request.changeSessionId();
        // SRS-SEC-14: the idle timeout follows the scope (eSeva 10 minutes, payroll users 15).
        WebSessions.idleTimeout(request, timeouts.idle(scope));
        securityLog.log(
                SecurityEvent.success("SCOPE_SWITCH", String.valueOf(context.userId()))
                        .withTarget(
                                "SESSION",
                                MdcSecurityContextFilter.hashSessionId(context.sessionId())));
        return ResponseEntity.noContent().build();
    }

    /** Optional OTP of a step-up: absent, a code is sent and 428 answers with the challenge. */
    public record StepUpRequest(@Valid Otp otp) {}

    /** The refused screen's code as the wireframe numbers it. */
    public record DeniedScreen(
            @jakarta.validation.constraints.NotBlank
                    @jakarta.validation.constraints.Pattern(regexp = "[A-Z]-\\d{2}")
                    String screen) {}

    /**
     * OD-130: the address bar reached a screen the session may not view. Told by the page itself, a
     * WARN and never an offence: a stale link or bookmark gets there without a tool, and no data
     * was reached.
     */
    @PostMapping("/auth/denied-screen")
    public ResponseEntity<Void> deniedScreen(
            @Valid @RequestBody DeniedScreen body, HttpServletRequest request) {
        log.warn(
                "event=SCREEN_DENIED screen={} userId={}",
                body.screen(),
                WebSessions.user(request).map(SessionUser::userId).orElse(null));
        return ResponseEntity.noContent().build();
    }

    /**
     * Confirms the session with the user's OTP before a sensitive screen (SRS-G-48 / SRS-G-21,
     * Module 02 decision D-2). 204 also when nothing needed confirming. A confirmation is a
     * privilege change: the session id rotates.
     */
    @PostMapping("/auth/step-up")
    public ResponseEntity<Void> stepUp(
            @Valid @RequestBody(required = false) StepUpRequest body, HttpServletRequest request) {
        AccessContext context = contexts.current();
        if (me.stepUp(context, Otp.proof(body == null ? null : body.otp()))) {
            request.changeSessionId();
            securityLog.log(
                    SecurityEvent.success("STEP_UP", String.valueOf(context.userId()))
                            .withTarget(
                                    "SESSION",
                                    MdcSecurityContextFilter.hashSessionId(context.sessionId())));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Changes the password with the user's OTP (SRS-G-21, X-08 l.894; 428 with the challenge first)
     * - every other session ends, the gate opens and the session id rotates.
     */
    @PostMapping("/auth/password/change")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        passwords.change(
                contexts.current(),
                body.currentPassword(),
                body.newPassword(),
                Otp.proof(body.otp()));
        WebSessions.passwordChanged(request);
        return ResponseEntity.noContent().build();
    }

    /** The workspace navigation for this session, filtered by its permissions. */
    @GetMapping("/menu")
    public ResponseEntity<Map<String, Object>> menu() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(menu.filtered(new HashSet<>(me.me(contexts.current()).permissions())));
    }

    /** The governed buttons of the screens and whether this session may press them (UX aid). */
    @GetMapping("/v1/me/actions")
    public ResponseEntity<List<MeService.ActionView>> actions() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(me.actions(contexts.current()));
    }
}
