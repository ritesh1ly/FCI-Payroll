package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppRole;
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
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.platform.security.ScopeType;
import in.gov.fci.payroll.workflow.api.GovernedActions;
import in.gov.fci.payroll.workflow.api.HeadquartersOnlyScreens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The signed-in user's own view and its controls (SRS-G-48, SRS-G-21; Module 02 D-2). Taking on
 * another role needs an OTP bound to that role and office, and a session with sensitive screens is
 * confirmed by an OTP bound to that session: a code issued for anything else is refused. The view
 * names the acting role (a group's, or eSeva without an office), asks for a password change when
 * the credential is missing, forced or expired, and keeps only the supported languages.
 */
class MeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");
    private static final long OFFICE = 40L;

    private final AccessContextService contexts = mock(AccessContextService.class);
    private final ScopeResolver scopes = mock(ScopeResolver.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final IdentityRepositories.Credentials credentials =
            mock(IdentityRepositories.Credentials.class);
    private final IdentityRepositories.Personas personas =
            mock(IdentityRepositories.Personas.class);
    private final IdentityRepositories.Sessions sessions =
            mock(IdentityRepositories.Sessions.class);
    private final IdentityRepositories.Preferences preferences =
            mock(IdentityRepositories.Preferences.class);
    private final AuditService audit = mock(AuditService.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final PayrollPeriods periods = mock(PayrollPeriods.class);
    private final OtpService otps = mock(OtpService.class);

    private final MeService service =
            new MeService(
                    contexts,
                    scopes,
                    org,
                    users,
                    credentials,
                    personas,
                    sessions,
                    preferences,
                    mock(GovernedActions.class),
                    mock(AccessPolicy.class),
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(ApiProperties.class),
                    parameters,
                    periods,
                    otps,
                    mock(HeadquartersOnlyScreens.class));

    private final AppUser user = mock(AppUser.class);
    private final AppSession session = mock(AppSession.class);

    @BeforeEach
    void wire() {
        when(user.getId()).thenReturn(1L);
        when(user.getLoginId()).thenReturn("maker@x");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(parameters.stepUpValidity()).thenReturn(Duration.ofMinutes(15));
        // UAT / production: the step-up and the role switch ask for an OTP (fci.api.otp.required).
        when(otps.verificationRequired()).thenReturn(true);
        when(parameters.passwordMaxAge()).thenReturn(Duration.ofDays(90));
        when(periods.current()).thenReturn(YearMonth.of(2026, 10));
        when(contexts.today()).thenReturn(LocalDate.of(2026, 10, 23));
        when(session.getId()).thenReturn(77L);
        when(session.getTokenHash()).thenReturn("token-hash");
        when(sessions.findByToken("s")).thenReturn(Optional.of(session));
        LoginPersona payroll = mock(LoginPersona.class);
        when(payroll.getCode()).thenReturn("PAYROLL");
        when(personas.findById("PAYROLL")).thenReturn(Optional.of(payroll));
    }

    private static ActiveGroup group(String role) {
        return new ActiveGroup(
                3L,
                "AG-" + role,
                "Group " + role,
                4L,
                role,
                "Role " + role,
                ScopeType.DIVISION,
                false,
                Set.of(OFFICE),
                Set.of(),
                AccessMode.FULL);
    }

    private static AccessContext context(
            List<ActiveGroup> groups, Long employee, Set<String> sensitive, Instant stepUpAt) {
        return new AccessContext(
                1L,
                "maker@x",
                "Maker",
                "s",
                "PAYROLL",
                groups.isEmpty() ? null : OFFICE,
                employee,
                groups,
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(OFFICE),
                sensitive,
                stepUpAt);
    }

    private ScopeResolver.RoleScope option(String role, Long location) {
        OrgLocation office = null;
        if (location != null) {
            office = mock(OrgLocation.class);
            when(office.getId()).thenReturn(location);
        }
        return new ScopeResolver.RoleScope(
                "PAYROLL",
                office,
                role,
                "Role " + role,
                location == null ? List.of() : List.of(group(role)));
    }

    private OtpChallenge challenge(long id, String entity, String key) {
        OtpChallenge c = mock(OtpChallenge.class);
        when(c.getId()).thenReturn(id);
        when(c.getEntityName()).thenReturn(entity);
        when(c.getEntityKey()).thenReturn(key);
        return c;
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ---------------------------------------------------------------- role switch

    @Test
    void SRS_G_48_anotherRoleNeedsAnOtpBoundToThatRoleAndOffice() {
        AccessContext maker = context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), null);
        ScopeResolver.RoleScope checker = option("PAYROLL_CHECKER", OFFICE);
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any())).thenReturn(List.of(checker));
        OtpChallenge issued = challenge(501L, MeService.ROLE_SWITCH_ENTITY, "PAYROLL_CHECKER@40");
        when(otps.issue(
                        user,
                        OtpGatedAction.AUTH_LOGIN,
                        MeService.ROLE_SWITCH_ENTITY,
                        "PAYROLL_CHECKER@40"))
                .thenReturn(issued);

        // Without a complete proof a challenge is issued for exactly this role and office.
        for (OtpProof partial :
                new OtpProof[] {null, new OtpProof(null, "123456"), new OtpProof(501L, null)}) {
            refused(
                    () -> service.switchScope(maker, "PAYROLL_CHECKER", OFFICE, partial),
                    ApiErrorCode.OTP_REQUIRED);
        }
        // A code issued for another role or office is refused.
        OtpChallenge forAnother =
                challenge(502L, MeService.ROLE_SWITCH_ENTITY, "PAYROLL_CHECKER@41");
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 502L, "654321")).thenReturn(forAnother);
        refused(
                () ->
                        service.switchScope(
                                maker, "PAYROLL_CHECKER", OFFICE, new OtpProof(502L, "654321")),
                ApiErrorCode.INVALID_OTP);
        OtpChallenge stepUpCode = challenge(503L, MeService.STEP_UP_ENTITY, "PAYROLL_CHECKER@40");
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 503L, "111111")).thenReturn(stepUpCode);
        refused(
                () ->
                        service.switchScope(
                                maker, "PAYROLL_CHECKER", OFFICE, new OtpProof(503L, "111111")),
                ApiErrorCode.INVALID_OTP);
        verify(session, never()).switchScope(any(), any(), any(), any());

        // The bound code moves the session and confirms the step-up with it.
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 501L, "123456")).thenReturn(issued);
        service.switchScope(maker, "PAYROLL_CHECKER", OFFICE, new OtpProof(501L, "123456"));
        verify(session).switchScope(eq("PAYROLL"), eq(OFFICE), eq(List.of(3L)), eq(NOW));
        verify(session).confirmStepUp(501L, NOW);
        verify(contexts).evictSession("token-hash");
    }

    @Test
    void SRS_G_48_anotherOfficeInTheSameRoleOrARecentStepUpNeedsNoNewOtp() {
        AccessContext recent =
                context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), NOW.minusSeconds(60));
        List<ScopeResolver.RoleScope> options =
                List.of(option("PAYROLL_MAKER", 41L), option("PAYROLL_CHECKER", OFFICE));
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any())).thenReturn(options);

        service.switchScope(recent, "PAYROLL_MAKER", 41L, null);
        service.switchScope(recent, "PAYROLL_CHECKER", OFFICE, null);

        verify(otps, never()).issue(any(), any(), any(), any());
        verify(session, never()).confirmStepUp(any(), any());
        refused(
                () -> service.switchScope(recent, "SYSTEM_ADMINISTRATOR", OFFICE, null),
                ApiErrorCode.SCOPE_SWITCH_DENIED);
    }

    @Test
    void SRS_SEC_14_aSwitchAnswersTheScopeNowInEffectSoItsIdleTimeoutCanFollow() {
        AccessContext recent =
                context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), NOW.minusSeconds(60));
        // Built before the stubbing that returns them (a mock stubbed inside thenReturn fails).
        ScopeResolver.RoleScope checker = option("PAYROLL_CHECKER", OFFICE);
        ScopeResolver.RoleScope eseva = option(AccessContextService.ESEVA_ROLE, null);
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any()))
                .thenReturn(List.of(checker, eseva));

        assertThat(service.switchScope(recent, AccessContextService.ESEVA_ROLE, null, null))
                .isSameAs(eseva)
                .satisfies(s -> assertThat(s.isEseva()).isTrue());
        assertThat(service.switchScope(recent, "PAYROLL_CHECKER", OFFICE, null))
                .isSameAs(checker)
                .satisfies(s -> assertThat(s.isEseva()).isFalse());
    }

    @Test
    void SRS_G_48_theESevaScopeIsKeyedWithoutAnOffice() {
        assertThat(MeService.roleSwitchKey("EMPLOYEE_ESEVA", null))
                .isEqualTo("EMPLOYEE_ESEVA@ESEVA");
        assertThat(MeService.roleSwitchKey("PAYROLL_MAKER", OFFICE)).isEqualTo("PAYROLL_MAKER@40");
        AccessContext maker = context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), null);
        List<ScopeResolver.RoleScope> esevaOnly =
                List.of(option(AccessContextService.ESEVA_ROLE, null));
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any())).thenReturn(esevaOnly);
        OtpChallenge issued = challenge(510L, MeService.ROLE_SWITCH_ENTITY, "EMPLOYEE_ESEVA@ESEVA");
        when(otps.issue(
                        user,
                        OtpGatedAction.AUTH_LOGIN,
                        MeService.ROLE_SWITCH_ENTITY,
                        "EMPLOYEE_ESEVA@ESEVA"))
                .thenReturn(issued);
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 510L, "123456")).thenReturn(issued);

        service.switchScope(
                maker, AccessContextService.ESEVA_ROLE, null, new OtpProof(510L, "123456"));

        verify(session).switchScope(eq("PAYROLL"), eq(null), eq(List.of()), eq(NOW));
    }

    // ---------------------------------------------------------------- step-up

    @Test
    void SRS_G_48_aSensitiveSessionIsConfirmedOnlyByACodeBoundToIt() {
        AccessContext plain = context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), null);
        assertThat(service.stepUp(plain, null)).as("nothing sensitive").isFalse();
        AccessContext confirmed =
                context(List.of(group("PAYROLL_MAKER")), 9L, Set.of("A-04"), NOW.minusSeconds(60));
        assertThat(service.stepUp(confirmed, null)).as("still confirmed").isFalse();

        AccessContext sensitive =
                context(List.of(group("PAYROLL_MAKER")), 9L, Set.of("A-04"), null);
        OtpChallenge issued = challenge(601L, MeService.STEP_UP_ENTITY, "77");
        when(otps.issue(user, OtpGatedAction.AUTH_LOGIN, MeService.STEP_UP_ENTITY, "77"))
                .thenReturn(issued);
        for (OtpProof partial :
                new OtpProof[] {null, new OtpProof(null, "123456"), new OtpProof(601L, null)}) {
            refused(() -> service.stepUp(sensitive, partial), ApiErrorCode.OTP_REQUIRED);
        }
        OtpChallenge anotherSession = challenge(602L, MeService.STEP_UP_ENTITY, "78");
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 602L, "222222"))
                .thenReturn(anotherSession);
        refused(
                () -> service.stepUp(sensitive, new OtpProof(602L, "222222")),
                ApiErrorCode.INVALID_OTP);
        OtpChallenge roleSwitch = challenge(603L, MeService.ROLE_SWITCH_ENTITY, "77");
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 603L, "333333")).thenReturn(roleSwitch);
        refused(
                () -> service.stepUp(sensitive, new OtpProof(603L, "333333")),
                ApiErrorCode.INVALID_OTP);

        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 601L, "123456")).thenReturn(issued);
        assertThat(service.stepUp(sensitive, new OtpProof(601L, "123456"))).isTrue();
        verify(session).confirmStepUp(601L, NOW);
        verify(audit).record(eq("maker@x"), any());
    }

    /**
     * SRS-G-21 (project owner, 2026-10-23): with OTP verification switched off the sensitive
     * session and another role are confirmed without a code, each by a challenge bound to it.
     */
    @Test
    void SRS_G_21_withOtpVerificationOffTheStepUpAndTheRoleSwitchNeedNoCode() {
        when(otps.verificationRequired()).thenReturn(false);
        AccessContext sensitive =
                context(List.of(group("PAYROLL_MAKER")), 9L, Set.of("A-04"), null);
        OtpChallenge bound = challenge(701L, MeService.STEP_UP_ENTITY, "77");
        when(otps.confirmWithoutCode(
                        user, OtpGatedAction.AUTH_LOGIN, MeService.STEP_UP_ENTITY, "77"))
                .thenReturn(bound);
        assertThat(service.stepUp(sensitive, null)).isTrue();
        verify(session).confirmStepUp(701L, NOW);

        AccessContext maker = context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), null);
        ScopeResolver.RoleScope checker = option("PAYROLL_CHECKER", OFFICE);
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any())).thenReturn(List.of(checker));
        OtpChallenge switched = challenge(702L, MeService.ROLE_SWITCH_ENTITY, "PAYROLL_CHECKER@40");
        when(otps.confirmWithoutCode(
                        user,
                        OtpGatedAction.AUTH_LOGIN,
                        MeService.ROLE_SWITCH_ENTITY,
                        "PAYROLL_CHECKER@40"))
                .thenReturn(switched);
        service.switchScope(maker, "PAYROLL_CHECKER", OFFICE, null);
        verify(session).confirmStepUp(702L, NOW);
        verify(otps, never()).issue(any(), any(), any(), any());
    }

    @Test
    void SRS_G_48_aSessionWhoseRecordIsGoneMustSignInAgain() {
        AccessContext sensitive =
                context(List.of(group("PAYROLL_MAKER")), 9L, Set.of("A-04"), null);
        when(sessions.findByToken("s")).thenReturn(Optional.empty());
        refused(() -> service.stepUp(sensitive, null), CommonErrorCode.AUTHENTICATION_REQUIRED);
        when(users.findById(1L)).thenReturn(Optional.empty());
        refused(() -> service.me(sensitive), CommonErrorCode.AUTHENTICATION_REQUIRED);
    }

    // ---------------------------------------------------------------- the view

    @Test
    void SRS_G_48_theViewNamesTheActingRoleAndAsksForAPasswordChangeWhenDue() {
        AppUserCredential expired = mock(AppUserCredential.class);
        when(expired.isExpired(NOW, Duration.ofDays(90))).thenReturn(true);
        when(credentials.findById(1L)).thenReturn(Optional.of(expired));
        AccessContext maker = context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), null);
        MeService.Me me = service.me(maker, Optional.of(YearMonth.of(2026, 9)));
        assertThat(me.actingRole()).isEqualTo("PAYROLL_MAKER");
        assertThat(me.actingRoleName()).isEqualTo("Role PAYROLL_MAKER");
        assertThat(me.passwordChangeRequired()).as("expired").isTrue();
        assertThat(me.period()).isEqualTo("2026-09");
        assertThat(me.periodStatus()).isEqualTo("PAST");

        // eSeva: the direct role's name, or its code when the role row is gone.
        AccessContext member = context(List.of(), 9L, Set.of(), null);
        AppRole eseva = mock(AppRole.class);
        when(eseva.getName()).thenReturn("Employee (eSeva)");
        when(scopes.activeDirectRole(
                        1L, AccessContextService.ESEVA_ROLE, LocalDate.of(2026, 10, 23)))
                .thenReturn(Optional.of(eseva));
        assertThat(service.me(member).actingRoleName()).isEqualTo("Employee (eSeva)");
        when(scopes.activeDirectRole(
                        1L, AccessContextService.ESEVA_ROLE, LocalDate.of(2026, 10, 23)))
                .thenReturn(Optional.empty());
        when(credentials.findById(1L)).thenReturn(Optional.empty());
        MeService.Me withoutRole = service.me(member);
        assertThat(withoutRole.actingRole()).isEqualTo(AccessContextService.ESEVA_ROLE);
        assertThat(withoutRole.actingRoleName()).isEqualTo(AccessContextService.ESEVA_ROLE);
        assertThat(withoutRole.passwordChangeRequired()).as("no credential on record").isTrue();
        assertThat(withoutRole.periodStatus()).isEqualTo("CURRENT");

        // Neither a group nor an employee record: no acting role.
        AccessContext none = context(List.of(), null, Set.of(), null);
        assertThat(service.me(none).actingRole()).isNull();
        assertThat(service.me(none).actingRoleName()).isNull();
    }

    @Test
    void SRS_G_39_onlyTheSupportedLanguagesAreKept() {
        AccessContext maker = context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), null);
        refused(() -> service.setLocale(maker, "fr"), CommonErrorCode.VALIDATION_FAILED);

        service.setLocale(maker, "hi");
        ArgumentCaptor<UserPreference> saved = ArgumentCaptor.forClass(UserPreference.class);
        verify(preferences).save(saved.capture());
        assertThat(saved.getValue().getPreferredLocale()).isEqualTo("hi");

        when(preferences.findById(1L)).thenReturn(Optional.of(saved.getValue()));
        service.setLocale(maker, "en");
        assertThat(saved.getValue().getPreferredLocale()).isEqualTo("en");
    }

    @Test
    void SRS_G_48_aStepUpWhoseUserRecordIsGoneMustSignInAgainAndChangesNothing() {
        AccessContext sensitive =
                context(List.of(group("PAYROLL_MAKER")), 9L, Set.of("A-04"), null);
        when(users.findById(1L)).thenReturn(Optional.empty());
        refused(() -> service.stepUp(sensitive, null), CommonErrorCode.AUTHENTICATION_REQUIRED);
        verify(otps, never()).issue(any(), any(), any(), any());
        verify(sessions, never()).save(any());
        verify(contexts, never()).evictSession(any());
    }

    @Test
    void SRS_G_48_aRoleSwitchOfASessionWhoseRecordIsGoneMustSignInAgain() {
        AccessContext recent =
                context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), NOW.minusSeconds(60));
        ScopeResolver.RoleScope sameRole = option("PAYROLL_MAKER", 41L);
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any())).thenReturn(List.of(sameRole));
        when(sessions.findByToken("s")).thenReturn(Optional.empty());
        refused(
                () -> service.switchScope(recent, "PAYROLL_MAKER", 41L, null),
                CommonErrorCode.AUTHENTICATION_REQUIRED);
        verify(sessions, never()).save(any());
        verify(contexts, never()).evictSession(any());
    }

    @Test
    void SRS_G_48_aRoleChangeWhoseUserRecordIsGoneIsRefusedBeforeAnyCodeIsIssued() {
        AccessContext maker = context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), null);
        ScopeResolver.RoleScope checker = option("PAYROLL_CHECKER", OFFICE);
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any())).thenReturn(List.of(checker));
        when(users.findById(1L)).thenReturn(Optional.empty());
        refused(
                () -> service.switchScope(maker, "PAYROLL_CHECKER", OFFICE, null),
                CommonErrorCode.AUTHENTICATION_REQUIRED);
        verify(otps, never()).issue(any(), any(), any(), any());
        verify(session, never()).switchScope(any(), any(), any(), any());
    }

    @Test
    void SRS_G_48_aSwitchToAScopeTheUserDoesNotHoldIsDeniedAndNothingIsRecorded() {
        AccessContext maker = context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), null);
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any())).thenReturn(List.of());
        refused(
                () -> service.switchScope(maker, "PAYROLL_CHECKER", OFFICE, null),
                ApiErrorCode.SCOPE_SWITCH_DENIED);
        verify(audit, never()).record(any(), any());
        verify(sessions, never()).save(any());
    }

    @Test
    void SRS_G_48_aSwitchWhosePersonaIsGoneIsDeniedAndTheSessionIsNotMoved() {
        AccessContext recent =
                context(List.of(group("PAYROLL_MAKER")), 9L, Set.of(), NOW.minusSeconds(60));
        ScopeResolver.RoleScope makerScope = option("PAYROLL_MAKER", 41L);
        when(scopes.availableScopes(eq(1L), eq(9L), any(), any())).thenReturn(List.of(makerScope));
        when(personas.findById("PAYROLL")).thenReturn(Optional.empty());
        refused(
                () -> service.switchScope(recent, "PAYROLL_MAKER", 41L, null),
                ApiErrorCode.SCOPE_SWITCH_DENIED);
        verify(session, never()).switchScope(any(), any(), any(), any());
    }
}
