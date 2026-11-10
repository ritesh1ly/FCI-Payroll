package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mutation gaps of {@link LoginService}: the password is hashed once, outside the transaction
 * (SRS-SEC-1, SRS-SEC-3), an outdated hash is upgraded from that outside hash only after a right
 * password, an account created between the two steps is still verified, and the refusal floor (T1)
 * never waits beyond what is left of it.
 *
 * <p>Equivalent: LoginService:243 (remaining at most 0 against below 0): a sleep of zero
 * nanoseconds returns at once, so the two forms cannot be told apart.
 */
class LoginServiceMutationGapsTest {

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final IdentityRepositories.Credentials credentials =
            mock(IdentityRepositories.Credentials.class);
    private final IdentityRepositories.Personas personas =
            mock(IdentityRepositories.Personas.class);
    private final ScopeResolver scopes = mock(ScopeResolver.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final ApiProperties properties = mock(ApiProperties.class);
    private final AtomicBoolean insideTransaction = new AtomicBoolean();
    private LoginService service;

    private final AppUser user = mock(AppUser.class);
    private final AppUserCredential stored = mock(AppUserCredential.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        when(properties.rateLimits())
                .thenReturn(new ApiProperties.RateLimits(120, 5, 60, 3, 120, 4, 5, 0));
        when(properties.otp())
                .thenReturn(
                        new ApiProperties.Otp(
                                Duration.ofMinutes(10),
                                5,
                                5,
                                3,
                                ApiProperties.Verification.Y,
                                false,
                                true));
        when(tx.execute(any()))
                .thenAnswer(
                        a -> {
                            insideTransaction.set(true);
                            try {
                                return ((TransactionCallback<Object>) a.getArgument(0))
                                        .doInTransaction(null);
                            } finally {
                                insideTransaction.set(false);
                            }
                        });
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
                        mock(AuditService.class),
                        mock(NotificationSender.class),
                        mock(OtpService.class),
                        tx,
                        Clock.fixed(Instant.parse("2026-11-06T06:00:00Z"), ZoneOffset.UTC),
                        properties,
                        parameters);
        when(user.getId()).thenReturn(5L);
        when(user.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(users.findByLoginId("maker")).thenReturn(Optional.of(user));
        when(credentials.findById(5L)).thenReturn(Optional.of(stored));
        when(stored.getPasswordHash()).thenReturn("h1");

        OrgLocation at = mock(OrgLocation.class);
        when(at.getId()).thenReturn(3L);
        when(scopes.availableScopes(any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                new ScopeResolver.RoleScope(
                                        "STAFF", at, "PAYROLL_MAKER", "Payroll Maker", List.of())));
        LoginPersona persona = mock(LoginPersona.class);
        when(persona.getCode()).thenReturn("STAFF");
        when(personas.findById("STAFF")).thenReturn(Optional.of(persona));
    }

    private static LoginService.LoginRequest asked(String password) {
        return new LoginService.LoginRequest(
                "maker", password, "PAYROLL_MAKER", 3L, "10.0.0.1", "ua");
    }

    @Test
    void SRS_SEC_3_thePasswordIsCheckedOnceOutsideTheTransactionAgainstTheStoredHash() {
        when(hasher.matches("pw", "h1")).thenReturn(true);

        assertThat(service.login(asked("pw"))).isInstanceOf(LoginService.Signed.class);

        verify(hasher, times(1)).matches(any(), any());
        verify(hasher).matches("pw", "h1");
        verify(hasher, never()).matches("pw", "");
    }

    @Test
    void SRS_SEC_3_anOutdatedHashIsUpgradedFromTheHashComputedOutsideTheTransaction() {
        when(hasher.matches("pw", "h1")).thenReturn(true);
        when(hasher.needsUpgrade("h1")).thenReturn(true);
        AtomicBoolean hashedInside = new AtomicBoolean();
        when(hasher.hash("pw"))
                .thenAnswer(
                        a -> {
                            hashedInside.set(insideTransaction.get());
                            return "h-new";
                        });

        service.login(asked("pw"));

        verify(stored).upgradeHash("h-new");
        verify(hasher, times(1)).hash("pw");
        assertThat(hashedInside).isFalse();
    }

    @Test
    void SRS_SEC_3_aCurrentHashIsNotUpgradedAndNothingIsHashedAgain() {
        when(hasher.matches("pw", "h1")).thenReturn(true);
        when(hasher.needsUpgrade("h1")).thenReturn(false);

        service.login(asked("pw"));

        verify(hasher, never()).hash(any());
        verify(stored, never()).upgradeHash(any());
    }

    @Test
    void SRS_SEC_3_aWrongPasswordIsNeverRehashedEvenWhenTheStoredHashIsOutdated() {
        when(hasher.matches("pw", "h1")).thenReturn(false);
        when(hasher.needsUpgrade("h1")).thenReturn(true);

        assertThatThrownBy(() -> service.login(asked("pw")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.INVALID_CREDENTIALS));

        verify(hasher, never()).hash(any());
        verify(stored, never()).upgradeHash(any());
    }

    @Test
    void SRS_SEC_3_anAccountCreatedAfterTheOutsideLookupIsStillVerifiedInTheTransaction() {
        // Unknown outside the transaction (a dummy hash is verified), known inside it.
        when(users.findByLoginId("maker"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(user));
        when(hasher.matches("pw", "h1")).thenReturn(false);

        assertThatThrownBy(() -> service.login(asked("pw")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.INVALID_CREDENTIALS));

        verify(hasher).matchesDummy("pw");
        verify(hasher).matches("pw", "h1");
    }

    @Test
    void SRS_SEC_1_theRefusalFloorWaitsOnlyForWhatIsLeftOfIt() {
        when(properties.rateLimits())
                .thenReturn(new ApiProperties.RateLimits(120, 5, 60, 3, 120, 4, 5, 200));

        // A refusal that has already taken 10 s is not held any longer.
        long longAgo = System.nanoTime() - Duration.ofSeconds(10).toNanos();
        long begin = System.nanoTime();
        ReflectionTestUtils.invokeMethod(service, "holdRefusal", longAgo);
        assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(2));

        // One that has just arrived waits out the floor.
        long justNow = System.nanoTime();
        ReflectionTestUtils.invokeMethod(service, "holdRefusal", justNow);
        Duration waited = Duration.ofNanos(System.nanoTime() - justNow);
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(150));
        assertThat(waited).isLessThan(Duration.ofSeconds(5));
    }
}
