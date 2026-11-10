package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-in paths beyond the integration flow (SRS-SEC-1, SRS-SEC-3; HLD sign-in identifier): a blank
 * identifier, an account without a credential, a request without a password, a lockout of an
 * account without an e-mail, a hash changed between the check and the transaction, a scope asked by
 * office alone, a persona missing from configuration, and an outdated hash upgraded in place; a
 * refused password step is answered no sooner than the failure floor, a sign-in at once (T1).
 */
class LoginServiceFlowTest {

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final IdentityRepositories.Credentials credentials =
            mock(IdentityRepositories.Credentials.class);
    private final IdentityRepositories.Personas personas =
            mock(IdentityRepositories.Personas.class);
    private final ScopeResolver scopes = mock(ScopeResolver.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final AuditService audit = mock(AuditService.class);
    private final NotificationSender notifications = mock(NotificationSender.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private LoginService service;
    private ApiProperties properties;

    private final AppUser user = mock(AppUser.class);
    private final AppUserCredential stored = mock(AppUserCredential.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        properties = mock(ApiProperties.class);
        when(properties.rateLimits())
                .thenReturn(new ApiProperties.RateLimits(120, 5, 60, 3, 120, 4, 5, 0));
        // The password path: no OTP at sign-in here (the OTP step is LoginServiceOtpTest).
        when(properties.otp())
                .thenReturn(
                        new ApiProperties.Otp(
                                java.time.Duration.ofMinutes(10),
                                5,
                                5,
                                3,
                                ApiProperties.Verification.Y,
                                false,
                                true));
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
                        mock(IdentityRepositories.Sessions.class),
                        scopes,
                        mock(AccessContextService.class),
                        hasher,
                        mock(SessionService.class),
                        audit,
                        notifications,
                        mock(OtpService.class),
                        tx,
                        Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                        properties,
                        parameters);
        when(user.getId()).thenReturn(5L);
        when(user.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(users.findByLoginId("maker")).thenReturn(Optional.of(user));
        when(credentials.findById(5L)).thenReturn(Optional.of(stored));
        when(stored.getPasswordHash()).thenReturn("h1");
    }

    private static LoginService.LoginRequest asked(
            String login, String password, String role, Long location) {
        return new LoginService.LoginRequest(login, password, role, location, "10.0.0.1", "ua");
    }

    private void refused(LoginService.LoginRequest request, ErrorCode code) {
        assertThatThrownBy(() -> service.login(request))
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void SRS_SEC_1_aBlankOrMissingIdentifierOrAnAccountWithoutCredentialIsUnknown() {
        refused(asked("  ", "x", null, null), ApiErrorCode.INVALID_CREDENTIALS);
        refused(asked(null, null, null, null), ApiErrorCode.INVALID_CREDENTIALS);
        verify(audit).recordIndependently(org.mockito.ArgumentMatchers.eq("anonymous"), any());
        when(credentials.findById(5L)).thenReturn(Optional.empty());
        refused(asked("maker", "x", null, null), ApiErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void SRS_SEC_3_aMissingPasswordFailsAndALockoutWithoutAnEmailSendsNothing() {
        refused(asked("maker", null, null, null), ApiErrorCode.INVALID_CREDENTIALS);
        verify(hasher).matchesDummy(null);
        when(stored.registerFailure(anyInt(), any(), any())).thenReturn(true);
        refused(asked("maker", null, null, null), ApiErrorCode.TEMPORARILY_LOCKED);
        verify(notifications, never()).sendAccountLocked(anyString(), anyString());
    }

    @Test
    void SRS_SEC_3_aHashChangedSinceTheCheckIsVerifiedAgain() {
        when(hasher.matches("pw", "h1")).thenReturn(true);
        // Read as h1 outside the transaction, h2 inside it: checked again (and fails).
        when(stored.getPasswordHash()).thenReturn("h1", "h2");
        refused(asked("maker", "pw", null, null), ApiErrorCode.INVALID_CREDENTIALS);
        verify(hasher).matches("pw", "h2");
    }

    private ScopeResolver.RoleScope scope(long location) {
        OrgLocation at = mock(OrgLocation.class);
        when(at.getId()).thenReturn(location);
        return new ScopeResolver.RoleScope(
                "STAFF", at, "PAYROLL_MAKER", "Payroll Maker", List.of());
    }

    @Test
    void SRS_SEC_1_aScopeAskedByOfficeAloneAndAMissingPersonaIsAConfigurationFault() {
        when(hasher.matches("pw", "h1")).thenReturn(true);
        List<ScopeResolver.RoleScope> two = List.of(scope(3L), scope(4L));
        when(scopes.availableScopes(any(), any(), any(), any())).thenReturn(two);
        refused(asked("maker", "pw", null, 9L), ApiErrorCode.LOCATION_NOT_IN_SCOPE);
        assertThatThrownBy(() -> service.login(asked("maker", "pw", null, 4L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("persona STAFF");
    }

    @Test
    void SRS_SEC_3_anOutdatedHashChangedMeanwhileIsUpgradedInPlace() {
        when(hasher.matches(any(), any())).thenReturn(true);
        when(hasher.needsUpgrade(any())).thenReturn(true);
        when(hasher.hash("pw")).thenReturn("h3");
        when(stored.getPasswordHash()).thenReturn("h1", "h2");
        List<ScopeResolver.RoleScope> one = List.of(scope(3L));
        when(scopes.availableScopes(any(), any(), any(), any())).thenReturn(one);
        LoginPersona persona = mock(LoginPersona.class);
        when(persona.getCode()).thenReturn("STAFF");
        when(personas.findById("STAFF")).thenReturn(Optional.of(persona));
        assertThat(
                        ((LoginService.Signed)
                                        service.login(asked("maker", "pw", "PAYROLL_MAKER", 3L)))
                                .result()
                                .locationId())
                .isEqualTo(3L);
        verify(stored).upgradeHash("h3");
    }

    private static final int FLOOR = 300;

    private void floor(int millis) {
        when(properties.rateLimits())
                .thenReturn(new ApiProperties.RateLimits(120, 5, 60, 3, 120, 4, 5, millis));
    }

    private static java.time.Duration since(long started) {
        return java.time.Duration.ofNanos(System.nanoTime() - started);
    }

    // T1 (security test 2026-10-29): an unknown login ID and a wrong password of a known account
    // (whatever its hash costs) are both answered no sooner than the floor.
    @Test
    void SRS_SEC_1_aRefusedPasswordStepIsAnsweredNoSoonerThanTheFailureFloor() {
        floor(FLOOR);
        long started = System.nanoTime();
        refused(asked("nobody", "pw", null, null), ApiErrorCode.INVALID_CREDENTIALS);
        assertThat(since(started)).isGreaterThanOrEqualTo(java.time.Duration.ofMillis(FLOOR));
        started = System.nanoTime();
        refused(asked("maker", "pw", null, null), ApiErrorCode.INVALID_CREDENTIALS);
        assertThat(since(started)).isGreaterThanOrEqualTo(java.time.Duration.ofMillis(FLOOR));
        when(stored.registerFailure(anyInt(), any(), any())).thenReturn(true);
        started = System.nanoTime();
        refused(asked("maker", "pw", null, null), ApiErrorCode.TEMPORARILY_LOCKED);
        assertThat(since(started)).isGreaterThanOrEqualTo(java.time.Duration.ofMillis(FLOOR));
    }

    // T1: a successful sign-in, a floor of 0 and an interrupted wait are never held.
    @Test
    void SRS_SEC_1_aSignInIsNeverHeldAndAnInterruptedFloorKeepsTheInterrupt()
            throws InterruptedException {
        floor(60_000);
        when(hasher.matches("pw", "h1")).thenReturn(true);
        List<ScopeResolver.RoleScope> one = List.of(scope(3L));
        when(scopes.availableScopes(any(), any(), any(), any())).thenReturn(one);
        LoginPersona persona = mock(LoginPersona.class);
        when(persona.getCode()).thenReturn("STAFF");
        when(personas.findById("STAFF")).thenReturn(Optional.of(persona));
        long started = System.nanoTime();
        assertThat(service.login(asked("maker", "pw", "PAYROLL_MAKER", 3L)))
                .isInstanceOf(LoginService.Signed.class);
        assertThat(since(started)).isLessThan(java.time.Duration.ofSeconds(30));
        floor(0);
        started = System.nanoTime();
        refused(asked("nobody", "pw", null, null), ApiErrorCode.INVALID_CREDENTIALS);
        assertThat(since(started)).isLessThan(java.time.Duration.ofSeconds(30));
        // Interrupted while it waits out the floor: refused at once, the interrupt kept.
        floor(60_000);
        Thread me = Thread.currentThread();
        Thread interrupter =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException ignored) {
                                        Thread.currentThread().interrupt();
                                    }
                                    me.interrupt();
                                });
        try {
            started = System.nanoTime();
            refused(asked("nobody", "pw", null, null), ApiErrorCode.INVALID_CREDENTIALS);
            assertThat(since(started)).isLessThan(java.time.Duration.ofSeconds(30));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            interrupter.join();
        }
    }
}
