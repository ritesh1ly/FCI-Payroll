package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.business.model.LoginPersona;
import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.business.model.UserPreference;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.api.GovernedActions;
import in.gov.fci.payroll.workflow.api.HeadquartersOnlyScreens;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user's own view: identity, active scope, effective permissions, the scopes the user
 * may switch to (only the user's OWN active groups) and the language preference.
 */
@Service
@Loggable
public class MeService {

    public record GroupView(
            Long id,
            String code,
            String name,
            String roleCode,
            String roleName,
            boolean consolidated) {}

    public record LocationView(Long id, String code, String name, String type, String label) {}

    public record Me(
            Long userId,
            String loginId,
            String displayName,
            String personaCode,
            String landingScreen,
            LocationView location,
            String actingRole,
            String actingRoleName,
            Long employeeId,
            List<GroupView> groups,
            List<String> roles,
            List<String> permissions,
            boolean readOnlyConsolidated,
            String period,
            String periodStatus,
            String preferredLocale,
            boolean passwordChangeRequired,
            boolean stepUpRequired,
            List<String> sensitiveScreens,
            Instant stepUpValidUntil,
            Instant lastLoginAt,
            List<String> hqOnlyScreens,
            boolean atHeadquarters) {}

    /**
     * A governed button of a screen and whether this session may press it (UX aid; re-checked on
     * use).
     */
    public record ActionView(
            String screenCode,
            String actionCode,
            String labelKey,
            boolean allowed,
            String disabledReasonKey) {}

    /**
     * An (office, acting role) the user may switch to without signing out: the options of the
     * top-bar 🏢 office and 👤 role selectors. {@code location} is null for Employee (eSeva).
     */
    public record ScopeOption(
            String personaCode,
            LocationView location,
            String roleCode,
            String roleName,
            List<GroupView> groups,
            boolean current) {}

    private final AccessContextService contexts;
    private final ScopeResolver scopes;
    private final OrgDirectory org;
    private final AccessRepositories.Users users;
    private final IdentityRepositories.Credentials credentials;
    private final IdentityRepositories.Personas personas;
    private final IdentityRepositories.Sessions sessions;
    private final IdentityRepositories.Preferences preferences;
    private final GovernedActions inventory;
    private final HeadquartersOnlyScreens hqOnly;
    private final AccessPolicy policy;
    private final AuditService audit;
    private final Clock clock;
    private final ApiProperties properties;
    private final SystemParameters parameters;
    private final PayrollPeriods periods;
    private final OtpService otps;

    public MeService(
            AccessContextService contexts,
            ScopeResolver scopes,
            OrgDirectory org,
            AccessRepositories.Users users,
            IdentityRepositories.Credentials credentials,
            IdentityRepositories.Personas personas,
            IdentityRepositories.Sessions sessions,
            IdentityRepositories.Preferences preferences,
            GovernedActions inventory,
            AccessPolicy policy,
            AuditService audit,
            Clock clock,
            ApiProperties properties,
            SystemParameters parameters,
            PayrollPeriods periods,
            OtpService otps,
            HeadquartersOnlyScreens hqOnly) {
        this.contexts = contexts;
        this.scopes = scopes;
        this.org = org;
        this.users = users;
        this.credentials = credentials;
        this.personas = personas;
        this.sessions = sessions;
        this.preferences = preferences;
        this.inventory = inventory;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
        this.parameters = parameters;
        this.periods = periods;
        this.otps = otps;
        this.hqOnly = hqOnly;
    }

    public Me me(AccessContext context) {
        return me(context, Optional.empty());
    }

    /**
     * The session's profile; {@code workingPeriod} is the period chosen in the top bar, else the
     * calendar's current period (M-10).
     */
    @Transactional(readOnly = true)
    public Me me(AccessContext context, Optional<YearMonth> workingPeriod) {
        var user =
                users.findById(context.userId())
                        .orElseThrow(
                                () -> new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        Optional<AppUserCredential> credential = credentials.findById(user.getId());
        boolean pcr =
                credential
                        .map(
                                c ->
                                        c.isMustChange()
                                                || c.isExpired(
                                                        clock.instant(),
                                                        parameters.passwordMaxAge()))
                        .orElse(true);
        String landing =
                personas.findById(context.personaCode())
                        .map(LoginPersona::getLandingScreenCode)
                        .orElse(null);
        boolean readOnly =
                !context.groups().isEmpty()
                        && context.groups().stream().allMatch(ActiveGroup::consolidated);
        String actingRole = actingRole(context);
        String actingRoleName;
        if (!context.groups().isEmpty()) {
            actingRoleName = context.groups().get(0).roleName();
        } else if (context.isEseva()) {
            actingRoleName =
                    scopes.activeDirectRole(
                                    context.userId(),
                                    AccessContextService.ESEVA_ROLE,
                                    contexts.today())
                            .map(r -> r.getName())
                            .orElse(AccessContextService.ESEVA_ROLE);
        } else {
            actingRoleName = null;
        }
        return new Me(
                user.getId(),
                user.getLoginId(),
                user.getDisplayName(),
                context.personaCode(),
                landing,
                location(context.locationId()),
                actingRole,
                actingRoleName,
                context.employeeId(),
                context.groups().stream().map(MeService::group).toList(),
                List.copyOf(new TreeSet<>(context.roleCodes())),
                List.copyOf(new TreeSet<>(context.permissionKeys())),
                readOnly,
                workingPeriod.orElseGet(periods::current).toString(),
                // CURRENT or PAST: a cycle's own state comes with GET /v1/periods (payroll module).
                workingPeriod.filter(p -> !p.equals(periods.current())).isPresent()
                        ? "PAST"
                        : "CURRENT",
                preferences
                        .findById(user.getId())
                        .map(UserPreference::getPreferredLocale)
                        .orElse(null),
                pcr,
                !context.sensitiveScreens().isEmpty()
                        && !context.stepUpValid(clock.instant(), parameters.stepUpValidity()),
                List.copyOf(new TreeSet<>(context.sensitiveScreens())),
                context.stepUpAt() == null
                        ? null
                        : context.stepUpAt().plus(parameters.stepUpValidity()),
                user.getLastLoginAt(),
                // Screens proposed at Headquarters only: disabled with the reason elsewhere.
                List.copyOf(hqOnly.on(contexts.today())),
                policy.isAtHeadquarters(context));
    }

    /** Every governed button with whether this session holds its permission. */
    @Transactional(readOnly = true)
    public List<ActionView> actions(AccessContext context) {
        return inventory.all().stream()
                .map(
                        a -> {
                            boolean allowed = policy.can(context, a.screenCode(), a.actionCode());
                            return new ActionView(
                                    a.screenCode(),
                                    a.actionCode(),
                                    a.label(),
                                    allowed,
                                    allowed ? null : a.disabledReasonMsg());
                        })
                .toList();
    }

    /**
     * Every (office, acting role) of the user's own groups and eSeva grant; never another user's.
     */
    @Transactional(readOnly = true)
    public List<ScopeOption> scopeOptions(AccessContext context) {
        Set<String> actingGroups = new java.util.HashSet<>();
        context.groups().forEach(g -> actingGroups.add(g.code()));
        return available(context).stream()
                .map(
                        o ->
                                new ScopeOption(
                                        o.personaCode(),
                                        o.isEseva() ? null : location(o.location()),
                                        o.roleCode(),
                                        o.roleName(),
                                        o.groups().stream().map(MeService::group).toList(),
                                        o.isEseva()
                                                ? context.isEseva()
                                                : o.location().getId().equals(context.locationId())
                                                        && o.groups().stream()
                                                                .anyMatch(
                                                                        g ->
                                                                                actingGroups
                                                                                        .contains(
                                                                                                g
                                                                                                        .code()))))
                .toList();
    }

    private List<ScopeResolver.RoleScope> available(AccessContext context) {
        return scopes.availableScopes(
                context.userId(),
                context.employeeId(),
                AccessContextService.ESEVA_ROLE,
                contexts.today());
    }

    /** The session acts in one role: its groups' role, or eSeva without an office; else none. */
    static String actingRole(AccessContext context) {
        if (!context.groups().isEmpty()) {
            return context.groups().get(0).roleCode();
        }
        return context.isEseva() ? AccessContextService.ESEVA_ROLE : null;
    }

    /**
     * Moves the session to another (office, acting role) of the user's own: no sign-out, no new
     * credentials. Audited; the cached access context is evicted, so the next request (menu,
     * permissions, data scope) is in the new scope.
     *
     * <p>SRS-G-48 / SRS-G-21, decision D-2 of the Module 02 plan: taking on another ROLE after
     * sign-in needs the user's OTP (gated action {@code AUTH_LOGIN}: a sign-in into that role), so
     * an unattended session cannot be raised to a more powerful role, unless the user confirmed an
     * OTP within {@code STEP_UP_VALIDITY}. Another office in the same role needs none. The
     * challenge is bound to the target scope: a code issued for one role and office is refused for
     * any other.
     *
     * @return the scope the session now acts in (the web layer applies its idle timeout,
     *     SRS-SEC-14)
     */
    @Transactional
    public ScopeResolver.RoleScope switchScope(
            AccessContext context, String roleCode, Long locationId, OtpProof otp) {
        ScopeResolver.RoleScope target =
                available(context).stream()
                        .filter(o -> o.matches(roleCode, locationId))
                        .findFirst()
                        .orElseThrow(() -> new FciException(ApiErrorCode.SCOPE_SWITCH_DENIED));
        // Another role needs the OTP unless the user confirmed one within STEP_UP_VALIDITY.
        boolean roleChange = !target.roleCode().equals(actingRole(context));
        Long confirmed =
                roleChange && !context.stepUpValid(clock.instant(), parameters.stepUpValidity())
                        ? requireRoleSwitchOtp(context, target, locationId, otp)
                        : null;
        LoginPersona persona =
                personas.findById(target.personaCode())
                        .orElseThrow(() -> new FciException(ApiErrorCode.SCOPE_SWITCH_DENIED));
        List<ActiveGroup> groups = target.groups();
        AppSession session =
                sessions.findByToken(context.sessionId())
                        .orElseThrow(
                                () -> new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        String before = context.roleCodes() + " @" + context.locationId();
        session.switchScope(
                persona.getCode(),
                target.isEseva() ? null : target.location().getId(),
                groups.stream().map(ActiveGroup::id).toList(),
                clock.instant());
        if (confirmed != null) {
            session.confirmStepUp(confirmed, clock.instant());
        }
        sessions.save(session);
        contexts.evictSession(session.getTokenHash());
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                        "app_session",
                        String.valueOf(session.getId()),
                        "SCOPE_SWITCH",
                        before,
                        target.roleCode() + " @" + locationId,
                        "Scope switched"));
        return target;
    }

    /**
     * The confirmed AUTH_LOGIN challenge of a step-up: without a code when OTP verification is
     * switched off (fci.api.otp.required = N), else issued (428) or verified against this session.
     */
    private OtpChallenge stepUpChallenge(AppUser user, String key, OtpProof otp) {
        if (!otps.verificationRequired()) {
            return otps.confirmWithoutCode(user, OtpGatedAction.AUTH_LOGIN, STEP_UP_ENTITY, key);
        }
        if (otp == null || otp.challengeId() == null || otp.code() == null) {
            Long challenge =
                    otps.issue(user, OtpGatedAction.AUTH_LOGIN, STEP_UP_ENTITY, key).getId();
            throw new FciException(
                    ApiErrorCode.OTP_REQUIRED, new Object[] {challenge}, List.of(), null);
        }
        var challenge = otps.verify(user, OtpGatedAction.AUTH_LOGIN, otp.challengeId(), otp.code());
        if (!STEP_UP_ENTITY.equals(challenge.getEntityName())
                || !key.equals(challenge.getEntityKey())) {
            throw new FciException(ApiErrorCode.INVALID_OTP);
        }
        return challenge;
    }

    private Long requireRoleSwitchOtp(
            AccessContext context, ScopeResolver.RoleScope target, Long locationId, OtpProof otp) {
        String key = roleSwitchKey(target.roleCode(), locationId);
        var user =
                users.findById(context.userId())
                        .orElseThrow(
                                () -> new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        if (!otps.verificationRequired()) {
            return otps.confirmWithoutCode(user, OtpGatedAction.AUTH_LOGIN, ROLE_SWITCH_ENTITY, key)
                    .getId();
        }
        if (otp == null || otp.challengeId() == null || otp.code() == null) {
            Long challenge =
                    otps.issue(user, OtpGatedAction.AUTH_LOGIN, ROLE_SWITCH_ENTITY, key).getId();
            throw new FciException(
                    ApiErrorCode.OTP_REQUIRED, new Object[] {challenge}, List.of(), null);
        }
        var challenge = otps.verify(user, OtpGatedAction.AUTH_LOGIN, otp.challengeId(), otp.code());
        if (!ROLE_SWITCH_ENTITY.equals(challenge.getEntityName())
                || !key.equals(challenge.getEntityKey())) {
            throw new FciException(ApiErrorCode.INVALID_OTP);
        }
        return challenge.getId();
    }

    /**
     * The step-up of a session whose acting role has sensitive screens (SRS-G-48 / SRS-G-21, Module
     * 02 D-2): without {@code otp} it sends a code ({@code AUTH_LOGIN}, bound to this session) and
     * answers 428 with the challenge; with it, the code is verified and the session is confirmed
     * for {@code STEP_UP_VALIDITY}, after which the next sensitive screen asks again. Returns false
     * when nothing needed confirming (no sensitive screen, or a confirmation still valid).
     */
    @Transactional
    public boolean stepUp(AccessContext context, OtpProof otp) {
        if (context.sensitiveScreens().isEmpty()
                || context.stepUpValid(clock.instant(), parameters.stepUpValidity())) {
            return false;
        }
        AppSession session =
                sessions.findByToken(context.sessionId())
                        .orElseThrow(
                                () -> new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        var user =
                users.findById(context.userId())
                        .orElseThrow(
                                () -> new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        String key = String.valueOf(session.getId());
        var challenge = stepUpChallenge(user, key, otp);
        session.confirmStepUp(challenge.getId(), clock.instant());
        sessions.save(session);
        contexts.evictSession(session.getTokenHash());
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                        "app_session",
                        String.valueOf(session.getId()),
                        "STEP_UP",
                        null,
                        String.valueOf(challenge.getId()),
                        "Sensitive role confirmed with OTP"));
        return true;
    }

    /** OTP entity of a step-up: the session itself. */
    static final String STEP_UP_ENTITY = "app_session.login";

    /** OTP entity of a role switch: the session's target role and office. */
    static final String ROLE_SWITCH_ENTITY = "app_session.role";

    static String roleSwitchKey(String roleCode, Long locationId) {
        return roleCode + "@" + (locationId == null ? "ESEVA" : locationId);
    }

    @Transactional
    public void setLocale(AccessContext context, String locale) {
        if (!UserPreference.LOCALES.contains(locale)) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        Instant now = clock.instant();
        UserPreference preference =
                preferences
                        .findById(context.userId())
                        .orElseGet(() -> new UserPreference(context.userId(), locale, now));
        preference.setLocale(locale, now);
        preferences.save(preference);
    }

    LocationView location(Long id) {
        return id == null ? null : org.location(id).map(MeService::location).orElse(null);
    }

    static LocationView location(OrgLocation l) {
        return new LocationView(
                l.getId(), l.getCode(), l.getName(), l.getType().name(), l.displayLabel());
    }

    static GroupView group(ActiveGroup g) {
        return new GroupView(
                g.id(), g.code(), g.name(), g.roleCode(), g.roleName(), g.consolidated());
    }
}
