package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.PasswordHasher;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.business.model.LoginPersona;
import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The OTP step of the sign-in (SRS-G-48, SRS-G-21; OD-36 / OD-37) beyond LoginOtpIT: with OTP
 * switched off the login challenge is still recorded and the session's step-up confirmed; an
 * account gone, locked or deactivated between the two steps is not signed in; a refused code is
 * audited and rethrown; a scope lost meanwhile is refused; the Employee ID signs in exactly one
 * account (none, or two in two zones, is unknown); the destination is masked; a resend for an
 * account gone is refused. Also the password step around it with the OTP on: lockout, status and
 * hash upgrade; the scope the session starts in; the audit trail; the admission bulkhead.
 */
class LoginServiceOtpTest {

    private static final Instant NOW = Instant.parse("2026-10-29T06:00:00Z");

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final IdentityRepositories.Credentials credentials =
            mock(IdentityRepositories.Credentials.class);
    private final IdentityRepositories.Personas personas =
            mock(IdentityRepositories.Personas.class);
    private final ScopeResolver scopes = mock(ScopeResolver.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final SessionService sessions = mock(SessionService.class);
    private final AuditService audit = mock(AuditService.class);
    private final OtpService otps = mock(OtpService.class);
    private final NotificationSender notifications = mock(NotificationSender.class);
    private final IdentityRepositories.Sessions history = mock(IdentityRepositories.Sessions.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final ApiProperties properties = mock(ApiProperties.class);
    private LoginService service;

    private final AppUser user = mock(AppUser.class);
    private final AppUserCredential stored = mock(AppUserCredential.class);
    private final OtpChallenge challenge = mock(OtpChallenge.class);

    private void otpAtLogin(boolean on) {
        when(properties.otp())
                .thenReturn(
                        new ApiProperties.Otp(
                                Duration.ofMinutes(10),
                                5,
                                5,
                                3,
                                ApiProperties.Verification.Y,
                                on,
                                true));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        when(properties.rateLimits())
                .thenReturn(new ApiProperties.RateLimits(120, 5, 60, 3, 120, 4, 5, 0));
        otpAtLogin(true);
        when(tx.execute(any()))
                .thenAnswer(
                        a ->
                                ((TransactionCallback<Object>) a.getArgument(0))
                                        .doInTransaction(null));
        service =
                new LoginService(
                        users,
                        credentials,
                        personas,
                        history,
                        scopes,
                        mock(AccessContextService.class),
                        hasher,
                        sessions,
                        audit,
                        notifications,
                        otps,
                        tx,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        properties,
                        parameters);
        when(user.getId()).thenReturn(5L);
        when(user.getLoginId()).thenReturn("maker");
        when(user.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(user.getRegisteredMobile()).thenReturn("9876543210");
        when(user.getRegisteredEmail()).thenReturn("maker@fci-demo.test");
        when(users.findByLoginId("maker")).thenReturn(Optional.of(user));
        when(users.findById(5L)).thenReturn(Optional.of(user));
        when(credentials.findById(5L)).thenReturn(Optional.of(stored));
        when(stored.getPasswordHash()).thenReturn("h1");
        when(hasher.matches("pw", "h1")).thenReturn(true);
        when(parameters.otpValidity()).thenReturn(Duration.ofMinutes(5));
        when(challenge.getId()).thenReturn(77L);
        when(challenge.getDeliveryChannel()).thenReturn(OtpChallenge.SMS);
        when(otps.issue(eq(user), eq(OtpGatedAction.AUTH_LOGIN), anyString(), anyString()))
                .thenReturn(challenge);
        when(otps.verificationRequired()).thenReturn(true);
    }

    private ScopeResolver.RoleScope scope(long location) {
        OrgLocation at = mock(OrgLocation.class);
        when(at.getId()).thenReturn(location);
        return new ScopeResolver.RoleScope(
                "STAFF", at, "PAYROLL_MAKER", "Payroll Maker", List.of());
    }

    private void inScope() {
        List<ScopeResolver.RoleScope> one = List.of(scope(3L));
        when(scopes.availableScopes(any(), any(), any(), any())).thenReturn(one);
        LoginPersona persona = mock(LoginPersona.class);
        when(persona.getCode()).thenReturn("STAFF");
        when(personas.findById("STAFF")).thenReturn(Optional.of(persona));
    }

    private static LoginService.LoginRequest asked(String id, LoginService.IdType type) {
        return new LoginService.LoginRequest(id, "pw", null, null, "10.0.0.1", "ua", type);
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private LoginService.PendingLogin pending() {
        return new LoginService.PendingLogin(5L, "maker", 77L, null, null, NOW);
    }

    @Test
    void SRS_G_48_aRightPasswordSendsTheOtpAndOpensNoSession() {
        inScope();
        LoginService.LoginOutcome outcome =
                service.login(asked("maker", LoginService.IdType.USER_ID));
        assertThat(outcome).isInstanceOf(LoginService.OtpRequired.class);
        LoginService.OtpRequired otp = (LoginService.OtpRequired) outcome;
        assertThat(otp.channel()).isEqualTo("SMS");
        assertThat(otp.destination()).isEqualTo("******3210");
        assertThat(otp.validSeconds()).isEqualTo(300);
        assertThat(otp.pending().challengeId()).isEqualTo(77L);
        assertThat(otp.pending().issuedAt()).isEqualTo(NOW);
        verify(sessions, never()).start(any(), any(), any(), any());
        verify(stored, never()).registerSuccess();
    }

    @Test
    void SRS_G_48_aCodeSentByEmailShowsTheMaskedAddress() {
        inScope();
        when(challenge.getDeliveryChannel()).thenReturn(OtpChallenge.EMAIL);
        LoginService.OtpRequired otp =
                (LoginService.OtpRequired)
                        service.login(asked("maker", LoginService.IdType.USER_ID));
        assertThat(otp.channel()).isEqualTo("EMAIL");
        assertThat(otp.destination()).doesNotContain("maker@").contains("@");
        assertThat(LoginService.maskedMobile("123")).isEqualTo("****");
    }

    @Test
    void R_OTP_withOtpSwitchedOffTheLoginChallengeIsStillRecordedAndTheStepUpConfirmed() {
        inScope();
        when(otps.verificationRequired()).thenReturn(false);
        OtpChallenge confirmed = mock(OtpChallenge.class);
        when(confirmed.getId()).thenReturn(88L);
        when(otps.confirmWithoutCode(
                        eq(user), eq(OtpGatedAction.AUTH_LOGIN), anyString(), anyString()))
                .thenReturn(confirmed);
        assertThat(service.login(asked("maker", LoginService.IdType.USER_ID)))
                .isInstanceOf(LoginService.Signed.class);
        verify(sessions)
                .start(
                        eq(user),
                        eq(stored),
                        org.mockito.ArgumentMatchers.argThat(s -> s.getStepUpAt() != null),
                        anyString());
        verify(otps, never()).issue(any(), any(), any(), any());
    }

    @Test
    void SRS_G_48_theCodeOpensTheSessionWithTheStepUpConfirmed() {
        inScope();
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 77L, "482913")).thenReturn(challenge);
        service.completeWithOtp(pending(), "482913", "10.0.0.1", "ua");
        verify(stored).registerSuccess();
        verify(sessions)
                .start(
                        eq(user),
                        eq(stored),
                        org.mockito.ArgumentMatchers.argThat(s -> s.getStepUpAt() != null),
                        anyString());
    }

    @Test
    void SRS_G_49_anAccountGoneLockedOrDeactivatedMeanwhileIsNotSignedInByItsCode() {
        when(users.findById(5L)).thenReturn(Optional.empty());
        refused(
                () -> service.completeWithOtp(pending(), "482913", "ip", "ua"),
                ApiErrorCode.INVALID_OTP);
        when(users.findById(5L)).thenReturn(Optional.of(user));
        when(stored.isLockedAt(NOW)).thenReturn(true);
        refused(
                () -> service.completeWithOtp(pending(), "482913", "ip", "ua"),
                ApiErrorCode.TEMPORARILY_LOCKED);
        when(stored.isLockedAt(NOW)).thenReturn(false);
        when(user.getStatus()).thenReturn(AppUser.Status.DEACTIVATED);
        refused(
                () -> service.completeWithOtp(pending(), "482913", "ip", "ua"),
                ApiErrorCode.ACCOUNT_DEACTIVATED);
        verify(otps, never()).verify(any(), any(), anyLong(), anyString());
    }

    @Test
    void SRS_G_22_aRefusedCodeIsAuditedAndRethrownAndALostScopeIsRefused() {
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 77L, "000000"))
                .thenThrow(new FciException(ApiErrorCode.INVALID_OTP));
        refused(
                () -> service.completeWithOtp(pending(), "000000", "ip", "ua"),
                ApiErrorCode.INVALID_OTP);
        verify(audit).recordIndependently(eq("maker"), any());
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 77L, "482913")).thenReturn(challenge);
        when(scopes.availableScopes(any(), any(), any(), any())).thenReturn(List.of());
        refused(
                () -> service.completeWithOtp(pending(), "482913", "ip", "ua"),
                ApiErrorCode.NO_ACTIVE_SCOPE);
        verify(sessions, never()).start(any(), any(), any(), any());
    }

    @Test
    void SRS_G_35_theEmployeeIdSignsInExactlyOneAccount() {
        inScope();
        when(users.findByEmployeeNumber("104882")).thenReturn(List.of(user));
        assertThat(service.login(asked(" 104882 ", LoginService.IdType.EMPLOYEE_ID)))
                .isInstanceOf(LoginService.OtpRequired.class);
        AppUser other = mock(AppUser.class);
        when(users.findByEmployeeNumber("204882")).thenReturn(List.of(user, other));
        refused(
                () -> service.login(asked("204882", LoginService.IdType.EMPLOYEE_ID)),
                ApiErrorCode.INVALID_CREDENTIALS);
        refused(
                () -> service.login(asked("304882", LoginService.IdType.EMPLOYEE_ID)),
                ApiErrorCode.INVALID_CREDENTIALS);
        verify(users, never()).findByLoginId("204882");
    }

    @Test
    void SRS_G_48_aResendSendsANewCodeAndAnAccountGoneIsRefused() {
        LoginService.OtpRequired again = service.resendOtp(pending());
        assertThat(again.pending().challengeId()).isEqualTo(77L);
        when(users.findById(5L)).thenReturn(Optional.empty());
        refused(() -> service.resendOtp(pending()), ApiErrorCode.INVALID_OTP);
    }

    private static AuditService.Entry entryWith(String newValue) {
        return org.mockito.ArgumentMatchers.argThat(
                e -> e != null && newValue.equals(e.newValue()));
    }

    @Test
    void SRS_G_22_theSentCodeAndTheSignInAreAuditedAndTheResultCarriesTheScope() {
        inScope();
        service.login(asked("maker", LoginService.IdType.USER_ID));
        verify(audit).record(eq("maker"), entryWith("OTP_SENT SMS"));
        verify(hasher, org.mockito.Mockito.times(1)).matches("pw", "h1");
        verify(hasher, never()).matchesDummy(any());

        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 77L, "482913")).thenReturn(challenge);
        LoginService.LoginResult result =
                service.completeWithOtp(pending(), "482913", "10.0.0.1", "ua");
        assertThat(result).isNotNull();
        assertThat(result.locationId()).isEqualTo(3L);
        verify(user).recordLogin(NOW);
        verify(users).save(user);
        verify(audit).record(eq("maker"), entryWith("SUCCESS PAYROLL_MAKER @3"));
    }

    @Test
    void SRS_G_48_theMobileIsMaskedToItsLastFourDigits() {
        assertThat(LoginService.maskedMobile("1234")).isEqualTo("******1234");
        assertThat(LoginService.maskedMobile("123")).isEqualTo("****");
        assertThat(LoginService.maskedMobile("919876543210")).isEqualTo("******3210");
    }

    @Test
    void SRS_G_35_theRegisteredEmailSignsInExactlyOneAccountAndALeadingAtIsALoginId() {
        inScope();
        when(users.findByRegisteredEmail("maker@fci-demo.test")).thenReturn(List.of(user));
        assertThat(service.login(asked(" Maker@FCI-demo.test ", LoginService.IdType.USER_ID)))
                .isInstanceOf(LoginService.OtpRequired.class);
        AppUser other = mock(AppUser.class);
        when(users.findByRegisteredEmail("two@fci-demo.test")).thenReturn(List.of(user, other));
        refused(
                () -> service.login(asked("two@fci-demo.test", LoginService.IdType.USER_ID)),
                ApiErrorCode.INVALID_CREDENTIALS);
        // "@x" is not an e-mail (nothing before the @): looked up as a login ID.
        refused(
                () -> service.login(asked("@fci-demo.test", LoginService.IdType.USER_ID)),
                ApiErrorCode.INVALID_CREDENTIALS);
        verify(users, never()).findByRegisteredEmail("@fci-demo.test");
        // Looked up twice: for the hash outside the transaction, and again inside it.
        verify(users, org.mockito.Mockito.times(2)).findByLoginId("@fci-demo.test");
    }

    @Test
    void SRS_G_49_aLockedOrDeactivatedAccountIsRefusedBeforeAnyCodeIsSent() {
        inScope();
        when(stored.isLockedAt(NOW)).thenReturn(true);
        refused(
                () -> service.login(asked("maker", LoginService.IdType.USER_ID)),
                ApiErrorCode.TEMPORARILY_LOCKED);
        when(stored.isLockedAt(NOW)).thenReturn(false);
        when(user.getStatus()).thenReturn(AppUser.Status.DEACTIVATED);
        refused(
                () -> service.login(asked("maker", LoginService.IdType.USER_ID)),
                ApiErrorCode.ACCOUNT_DEACTIVATED);
        verify(otps, never()).issue(any(), any(), any(), any());
    }

    @Test
    void SRS_G_49_theLastWrongPasswordLocksAuditsAlertsAndSendsNoCode() {
        when(hasher.matches("wrong", "h1")).thenReturn(false);
        when(stored.registerFailure(anyInt(), any(), any())).thenReturn(true);
        when(user.getDisplayName()).thenReturn("Maker");
        refused(
                () ->
                        service.login(
                                new LoginService.LoginRequest(
                                        "maker",
                                        "wrong",
                                        null,
                                        null,
                                        "ip",
                                        "ua",
                                        LoginService.IdType.USER_ID)),
                ApiErrorCode.TEMPORARILY_LOCKED);
        verify(audit)
                .record(
                        eq("maker"),
                        org.mockito.ArgumentMatchers.argThat(
                                e -> e != null && e.newValue().startsWith("LOCKED_UNTIL")));
        verify(audit)
                .securityEvent(
                        eq(AuditService.Security.ACCOUNT_LOCKOUT),
                        eq("maker"),
                        eq("app_user"),
                        eq("5"),
                        anyString());
        verify(notifications).sendAccountLocked("maker@fci-demo.test", "Maker");
        verify(otps, never()).issue(any(), any(), any(), any());
    }

    @Test
    void SRS_SEC_3_anOutdatedHashIsUpgradedOnceWithTheHashMadeOutsideTheTransaction() {
        inScope();
        when(hasher.needsUpgrade("h1")).thenReturn(true);
        when(hasher.hash("pw")).thenReturn("h3", "h4");
        service.login(asked("maker", LoginService.IdType.USER_ID));
        verify(stored).upgradeHash("h3");
        verify(stored, never()).upgradeHash("h4");
        verify(hasher, org.mockito.Mockito.times(1)).hash("pw");
    }

    @Test
    void SRS_SEC_3_aCurrentHashIsNotUpgraded() {
        inScope();
        service.login(asked("maker", LoginService.IdType.USER_ID));
        verify(stored, never()).upgradeHash(anyString());
    }

    private ScopeResolver.RoleScope groupScope(long location, long group) {
        OrgLocation at = mock(OrgLocation.class);
        when(at.getId()).thenReturn(location);
        return new ScopeResolver.RoleScope(
                "STAFF",
                at,
                "PAYROLL_MAKER",
                "Payroll Maker",
                List.of(
                        new in.gov.fci.payroll.platform.security.ActiveGroup(
                                group,
                                "G" + group,
                                "Group",
                                1L,
                                "PAYROLL_MAKER",
                                "Payroll Maker",
                                in.gov.fci.payroll.platform.security.ScopeType.DEPOT,
                                false,
                                java.util.Set.of(location),
                                java.util.Set.of(),
                                in.gov.fci.payroll.platform.security.AccessMode.FULL)));
    }

    private static ScopeResolver.RoleScope eseva() {
        return new ScopeResolver.RoleScope("EMPLOYEE", null, "EMPLOYEE", "Employee", List.of());
    }

    private void previous(Long location, List<Long> groups) {
        in.gov.fci.payroll.iam.business.model.AppSession last =
                mock(in.gov.fci.payroll.iam.business.model.AppSession.class);
        when(last.getActiveLocationId()).thenReturn(location);
        when(last.activeGroupIdList()).thenReturn(groups);
        when(history.findFirstByUserIdOrderByStartedAtDesc(5L)).thenReturn(Optional.of(last));
    }

    /** The audit "SUCCESS ..." of a sign-in completed with its code, in the scopes offered. */
    private void signsInAt(List<ScopeResolver.RoleScope> offered, String expected) {
        org.mockito.Mockito.clearInvocations(audit);
        when(scopes.availableScopes(any(), any(), any(), any())).thenReturn(offered);
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 77L, "482913")).thenReturn(challenge);
        service.completeWithOtp(pending(), "482913", "ip", "ua");
        verify(audit).record(eq("maker"), entryWith(expected));
    }

    @Test
    void SRS_G_29_theSessionStartsInThePreviousScopeWhileHeldElseTheFirstOfficeElseEseva() {
        LoginPersona staff = mock(LoginPersona.class);
        when(staff.getCode()).thenReturn("STAFF");
        when(personas.findById("STAFF")).thenReturn(Optional.of(staff));
        LoginPersona employee = mock(LoginPersona.class);
        when(employee.getCode()).thenReturn("EMPLOYEE");
        when(personas.findById("EMPLOYEE")).thenReturn(Optional.of(employee));
        List<ScopeResolver.RoleScope> offered =
                List.of(eseva(), groupScope(3L, 11L), groupScope(4L, 12L));

        // No previous session: the first office, never eSeva while an office is held.
        signsInAt(offered, "SUCCESS PAYROLL_MAKER @3");
        // The previous office and group, still held.
        previous(4L, List.of(12L));
        signsInAt(offered, "SUCCESS PAYROLL_MAKER @4");
        // The previous office through another group only: not the same scope.
        previous(4L, List.of(99L));
        signsInAt(offered, "SUCCESS PAYROLL_MAKER @3");
        // The previous office no longer held.
        previous(9L, List.of(12L));
        signsInAt(offered, "SUCCESS PAYROLL_MAKER @3");
        // The previous session was eSeva (no office, no group): eSeva again.
        previous(null, List.of());
        signsInAt(offered, "SUCCESS EMPLOYEE");
        // eSeva with groups recorded is not that eSeva session.
        previous(null, List.of(12L));
        signsInAt(offered, "SUCCESS PAYROLL_MAKER @3");
        // eSeva alone.
        signsInAt(List.of(eseva()), "SUCCESS EMPLOYEE");
    }

    @Test
    void theAdmissionPermitIsReturnedAfterEverySignIn() {
        inScope();
        // Four permits (RateLimits loginConcurrent = 4): a fifth sequential sign-in must still be
        // admitted.
        for (int i = 0; i < 6; i++) {
            assertThat(service.login(asked("maker", LoginService.IdType.USER_ID))).isNotNull();
        }
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 77L, "482913")).thenReturn(challenge);
        for (int i = 0; i < 6; i++) {
            assertThat(service.completeWithOtp(pending(), "482913", "ip", "ua")).isNotNull();
        }
    }

    /** OD-41: the registered mobile names exactly one account, however it is typed. */
    @Test
    void SRS_G_48_theRegisteredMobileSignsInExactlyOneAccountHoweverItIsTyped() {
        inScope();
        when(users.findByRegisteredMobile("9876543210")).thenReturn(List.of(user));
        for (String typed : new String[] {"9876543210", "+91 98765 43210", "098765-43210"}) {
            assertThat(service.login(asked(typed, LoginService.IdType.MOBILE)))
                    .as(typed)
                    .isInstanceOf(LoginService.OtpRequired.class);
        }
        AppUser other = mock(AppUser.class);
        when(users.findByRegisteredMobile("9000000000")).thenReturn(List.of(user, other));
        refused(
                () -> service.login(asked("9000000000", LoginService.IdType.MOBILE)),
                ApiErrorCode.INVALID_CREDENTIALS);
        refused(
                () -> service.login(asked("9111111111", LoginService.IdType.MOBILE)),
                ApiErrorCode.INVALID_CREDENTIALS);
        verify(users, never()).findByLoginId("9876543210");
        assertThat(LoginService.normaliseMobile("919876543210")).isEqualTo("9876543210");
        assertThat(LoginService.normaliseMobile("98765")).isEqualTo("98765");
        assertThat(LoginService.normaliseMobile("12345678901")).isEqualTo("12345678901");
    }

    private LoginService.PendingLogin mobilePending(long userId) {
        return new LoginService.PendingLogin(userId, "maker", 77L, null, null, NOW, true);
    }

    /**
     * OD-41: offered only with the sign-in OTP on, the setting on and OTP verification required.
     */
    @Test
    void SRS_G_48_theMobileSignInIsOfferedOnlyWhereACodeIsReallyAsked() {
        assertThat(service.mobileLoginAvailable()).isTrue();
        when(otps.verificationRequired()).thenReturn(false);
        assertThat(service.mobileLoginAvailable()).as("no code checked: never").isFalse();
        refused(() -> service.requestMobileOtp("9876543210"), CommonErrorCode.NOT_FOUND);
        when(otps.verificationRequired()).thenReturn(true);
        when(properties.otp())
                .thenReturn(
                        new ApiProperties.Otp(
                                Duration.ofMinutes(10),
                                5,
                                5,
                                3,
                                ApiProperties.Verification.Y,
                                true,
                                false));
        assertThat(service.mobileLoginAvailable()).as("switched off by FCI").isFalse();
        otpAtLogin(false);
        assertThat(service.mobileLoginAvailable()).isFalse();
        verify(otps, never()).issue(any(), any(), any(), any());
    }

    /** OD-41: a registered number gets the code, by itself the first factor (no password). */
    @Test
    void SRS_G_48_aRegisteredMobileGetsTheCodeWithoutAPassword() {
        when(users.findByRegisteredMobile("9876543210")).thenReturn(List.of(user));
        LoginService.OtpRequired sent = service.requestMobileOtp("+91 98765-43210");
        assertThat(sent.pending().passwordless()).isTrue();
        assertThat(sent.pending().userId()).isEqualTo(5L);
        assertThat(sent.pending().loginId()).isEqualTo("maker");
        assertThat(sent.pending().challengeId()).isEqualTo(77L);
        assertThat(sent.destination()).isEqualTo("******3210");
        assertThat(sent.channel()).isEqualTo("SMS");
        verify(hasher, never()).matches(any(), any());
        verify(audit).record(eq("maker"), entryWith("OTP_SENT SMS"));
    }

    /**
     * OD-41, no enumeration: a number no account carries (or two do, or one without a credential)
     * gets the same answer; nothing is sent, the attempt is audited, a resend answers alike and no
     * code ever completes it.
     */
    @Test
    void SRS_G_48_anUnregisteredMobileIsAnsweredTheSameAndNothingIsSent() {
        LoginService.OtpRequired sent = service.requestMobileOtp("9111111111");
        assertThat(sent.channel()).isEqualTo("SMS");
        assertThat(sent.destination()).isEqualTo("******1111");
        assertThat(sent.validSeconds()).isEqualTo(300);
        assertThat(sent.pending().userId()).isEqualTo(LoginService.UNKNOWN);
        AppUser other = mock(AppUser.class);
        when(users.findByRegisteredMobile("9000000000")).thenReturn(List.of(user, other));
        assertThat(service.requestMobileOtp("9000000000").pending().userId())
                .isEqualTo(LoginService.UNKNOWN);
        AppUser noCredential = mock(AppUser.class);
        when(noCredential.getId()).thenReturn(6L);
        when(users.findByRegisteredMobile("9222222222")).thenReturn(List.of(noCredential));
        assertThat(service.requestMobileOtp("9222222222").pending().userId())
                .isEqualTo(LoginService.UNKNOWN);
        verify(otps, never()).issue(any(), any(), any(), any());
        verify(audit, org.mockito.Mockito.times(3)).recordIndependently(eq("anonymous"), any());

        LoginService.OtpRequired again = service.resendOtp(sent.pending());
        assertThat(again.destination()).isEqualTo("******1111");
        assertThat(again.pending().passwordless()).isTrue();
        verify(otps, never()).issue(any(), any(), any(), any());
        refused(
                () -> service.completeWithOtp(sent.pending(), "123456", "ip", "ua"),
                ApiErrorCode.INVALID_OTP);
    }

    /**
     * OD-41: without a password the code is checked first: a wrong code says nothing about the
     * account; only the right one tells a locked or deactivated account why it cannot sign in.
     */
    @Test
    void SRS_G_49_aLockedOrDeactivatedAccountIsToldOnlyAfterTheRightCode() {
        when(stored.isLockedAt(NOW)).thenReturn(true);
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 77L, "000000"))
                .thenThrow(new FciException(ApiErrorCode.INVALID_OTP));
        refused(
                () -> service.completeWithOtp(mobilePending(5L), "000000", "ip", "ua"),
                ApiErrorCode.INVALID_OTP);
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 77L, "482913")).thenReturn(challenge);
        refused(
                () -> service.completeWithOtp(mobilePending(5L), "482913", "ip", "ua"),
                ApiErrorCode.TEMPORARILY_LOCKED);
        when(stored.isLockedAt(NOW)).thenReturn(false);
        when(user.getStatus()).thenReturn(AppUser.Status.DEACTIVATED);
        refused(
                () -> service.completeWithOtp(mobilePending(5L), "482913", "ip", "ua"),
                ApiErrorCode.ACCOUNT_DEACTIVATED);
        verify(sessions, never()).start(any(), any(), any(), any());
    }

    /** OD-41: the right code alone opens the session, its step-up confirmed. */
    @Test
    void SRS_G_48_theCodeAloneOpensTheSessionOfAMobileSignIn() {
        inScope();
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 77L, "482913")).thenReturn(challenge);
        assertThat(service.completeWithOtp(mobilePending(5L), "482913", "ip", "ua").locationId())
                .isEqualTo(3L);
        verify(sessions)
                .start(
                        eq(user),
                        eq(stored),
                        org.mockito.ArgumentMatchers.argThat(s -> s.getStepUpAt() != null),
                        anyString());
        // A resend of a known pending mobile sign-in issues a new code, still passwordless.
        assertThat(service.resendOtp(mobilePending(5L)).pending().passwordless()).isTrue();
    }

    @Test
    void SRS_G_48_anAccountWhoseCredentialIsGoneIsNotSignedInByItsCode() {
        when(credentials.findById(5L)).thenReturn(Optional.empty());
        refused(
                () -> service.completeWithOtp(pending(), "482913", "ip", "ua"),
                ApiErrorCode.INVALID_OTP);
        verify(otps, never()).verify(any(), any(), anyLong(), any());
    }

    /** A starting office asked without a role skips the eSeva scope, which has no office. */
    @Test
    void SRS_G_48_aStartingOfficeWithoutARoleIsTheStaffScopeAtThatOffice() {
        ScopeResolver.RoleScope eseva =
                new ScopeResolver.RoleScope(
                        "ESEVA", null, AccessContextService.ESEVA_ROLE, "eSeva", List.of());
        List<ScopeResolver.RoleScope> offered = List.of(eseva, scope(4L), scope(3L));
        when(scopes.availableScopes(any(), any(), any(), any())).thenReturn(offered);
        LoginPersona persona = mock(LoginPersona.class);
        when(persona.getCode()).thenReturn("STAFF");
        when(personas.findById("STAFF")).thenReturn(Optional.of(persona));
        when(otps.verify(user, OtpGatedAction.AUTH_LOGIN, 77L, "482913")).thenReturn(challenge);
        LoginService.PendingLogin atThree =
                new LoginService.PendingLogin(5L, "maker", 77L, null, 3L, NOW);
        assertThat(service.completeWithOtp(atThree, "482913", "ip", "ua").locationId())
                .isEqualTo(3L);
    }

    /** OD-41: a mobile as typed is reduced to its ten digits; other lengths are kept as typed. */
    @Test
    void SRS_G_48_aTypedMobileLosesItsCountryCodeOrTrunkZeroOnly() {
        assertThat(LoginService.normaliseMobile("+91 98765-43210")).isEqualTo("9876543210");
        assertThat(LoginService.normaliseMobile("098765 43210")).isEqualTo("9876543210");
        assertThat(LoginService.normaliseMobile("44 9876543210"))
                .as("twelve digits without 91")
                .isEqualTo("449876543210");
        assertThat(LoginService.normaliseMobile("19876543210"))
                .as("eleven digits without 0")
                .isEqualTo("19876543210");
        assertThat(LoginService.normaliseMobile("9876543210")).isEqualTo("9876543210");
    }
}
