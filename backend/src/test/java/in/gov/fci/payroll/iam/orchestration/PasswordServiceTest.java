package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.PasswordHasher;
import in.gov.fci.payroll.iam.business.PasswordPolicy;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.business.model.PasswordHistoryEntry;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Password change, reset and temporary password beyond the integration flow (SRS-SEC-2, SRS-SEC-4;
 * DDL password_history): a change without the current password, a reset asked for an inactive
 * account or over the OTP limit (silent either way), a reset of an inactive account, a temporary
 * password missing, and the history pruned to its configured depth.
 */
class PasswordServiceTest {

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final IdentityRepositories.Credentials credentials =
            mock(IdentityRepositories.Credentials.class);
    private final IdentityRepositories.PasswordHistory history =
            mock(IdentityRepositories.PasswordHistory.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final OtpService otps = mock(OtpService.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final PasswordService service =
            new PasswordService(
                    users,
                    credentials,
                    history,
                    hasher,
                    mock(PasswordPolicy.class),
                    otps,
                    mock(SessionService.class),
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                    mock(ApiProperties.class),
                    parameters);

    private final AppUser user = mock(AppUser.class);
    private final AppUserCredential credential = mock(AppUserCredential.class);

    @BeforeEach
    void wire() {
        when(user.getId()).thenReturn(5L);
        when(user.getLoginId()).thenReturn("maker");
        when(user.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(users.findById(5L)).thenReturn(Optional.of(user));
        when(users.findByLoginId("maker")).thenReturn(Optional.of(user));
        when(credentials.findById(5L)).thenReturn(Optional.of(credential));
        when(credential.getPasswordHash()).thenReturn("h1");
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static AccessContext self() {
        return new AccessContext(
                5L, "maker", "Maker", "s", "P", 1L, null, List.of(), Map.of(), Set.of(), Set.of(),
                Set.of());
    }

    @Test
    void SRS_SEC_2_aChangeWithoutTheCurrentPasswordIsRefused() {
        refused(
                () -> service.change(self(), null, "N3w#Passw0rd", null),
                ApiErrorCode.CURRENT_PASSWORD_WRONG);
        verify(credential, never()).replace(any(), any());
    }

    private static in.gov.fci.payroll.iam.business.model.OtpChallenge challenge(long id) {
        in.gov.fci.payroll.iam.business.model.OtpChallenge c =
                mock(in.gov.fci.payroll.iam.business.model.OtpChallenge.class);
        when(c.getId()).thenReturn(id);
        return c;
    }

    /**
     * SRS-G-21 / X-08 l.894 (US-GAP-128): the own password change - the one action a user whose
     * password expired (SRS-SEC-13, 90 days) or must be changed may take - is confirmed with an OTP
     * bound to the user's own account: without one a code is sent and OTP_REQUIRED answers with its
     * challenge; a wrong code changes nothing; the right one changes the password.
     */
    @Test
    void SRS_G_21_anOwnPasswordChangeIsConfirmedWithAnOtpBoundToTheAccount() {
        when(otps.verificationRequired()).thenReturn(true);
        in.gov.fci.payroll.iam.business.model.OtpChallenge sent = challenge(9L);
        when(otps.issue(
                        user,
                        OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE,
                        PasswordService.CHANGE_ENTITY,
                        "5"))
                .thenReturn(sent);
        when(hasher.matches("Old#Passw0rd12", "h1")).thenReturn(true);
        when(hasher.hash("N3w#Passw0rd12")).thenReturn("h2");
        assertThatThrownBy(() -> service.change(self(), "Old#Passw0rd12", "N3w#Passw0rd12", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.OTP_REQUIRED);
                            assertThat(e.getMessageArgs()).containsExactly(9L);
                        });
        // A proof without its code is no proof: a code is sent again.
        assertThatThrownBy(
                        () ->
                                service.change(
                                        self(),
                                        "Old#Passw0rd12",
                                        "N3w#Passw0rd12",
                                        new in.gov.fci.payroll.platform.approval.OtpProof(
                                                9L, null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.OTP_REQUIRED));
        // Nor is a code without the challenge it answers.
        assertThatThrownBy(
                        () ->
                                service.change(
                                        self(),
                                        "Old#Passw0rd12",
                                        "N3w#Passw0rd12",
                                        new in.gov.fci.payroll.platform.approval.OtpProof(
                                                null, "123456")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.OTP_REQUIRED));
        when(otps.verify(
                        user,
                        OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE,
                        9L,
                        "000000",
                        PasswordService.CHANGE_ENTITY,
                        "5"))
                .thenThrow(new FciException(ApiErrorCode.INVALID_OTP));
        refused(
                () ->
                        service.change(
                                self(),
                                "Old#Passw0rd12",
                                "N3w#Passw0rd12",
                                new in.gov.fci.payroll.platform.approval.OtpProof(9L, "000000")),
                ApiErrorCode.INVALID_OTP);
        verify(credential, never()).replace(any(), any());

        when(otps.verify(
                        user,
                        OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE,
                        9L,
                        "123456",
                        PasswordService.CHANGE_ENTITY,
                        "5"))
                .thenReturn(sent);
        service.change(
                self(),
                "Old#Passw0rd12",
                "N3w#Passw0rd12",
                new in.gov.fci.payroll.platform.approval.OtpProof(9L, "123456"));
        verify(credential).replace(org.mockito.ArgumentMatchers.eq("h2"), any());
    }

    /**
     * SRS-G-21 (US-GAP-128): the current password and the new one's rules are checked before a code
     * is sent, so a refused password costs no OTP; with verification switched off (development,
     * R-OTP 'N') the change still records its confirmed challenge.
     */
    @Test
    void SRS_G_21_aRefusedPasswordSendsNoCodeAndADevelopmentChangeRecordsItsChallenge() {
        when(otps.verificationRequired()).thenReturn(true);
        when(hasher.matches("Old#Passw0rd12", "h1")).thenReturn(true);
        // The new password is the current one: reused, refused before any code.
        when(hasher.matches("Old#Passw0rd12", "h1")).thenReturn(true);
        refused(
                () -> service.change(self(), "Old#Passw0rd12", "Old#Passw0rd12", null),
                ApiErrorCode.PASSWORD_REUSED);
        verify(otps, never()).issue(any(), any(), any(), any());

        when(otps.verificationRequired()).thenReturn(false);
        in.gov.fci.payroll.iam.business.model.OtpChallenge confirmed = challenge(11L);
        when(otps.confirmWithoutCode(
                        user,
                        OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE,
                        PasswordService.CHANGE_ENTITY,
                        "5"))
                .thenReturn(confirmed);
        when(hasher.hash("N3w#Passw0rd12")).thenReturn("h2");
        service.change(self(), "Old#Passw0rd12", "N3w#Passw0rd12", null);
        verify(credential).replace(org.mockito.ArgumentMatchers.eq("h2"), any());
        verify(otps, never()).issue(any(), any(), any(), any());
    }

    @Test
    void SRS_SEC_4_aResetAskedForAnInactiveAccountOrOverTheOtpLimitIsSilent() {
        when(user.getStatus()).thenReturn(AppUser.Status.SUSPENDED);
        service.requestReset("MAKER");
        verify(otps, never()).issue(any(), any(), any(), any());
        refused(() -> service.reset("maker", "123456", "N3w#Passw0rd"), ApiErrorCode.INVALID_OTP);

        when(user.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        doThrow(
                        new FciException(
                                in.gov.fci.payroll.platform.error.CommonErrorCode
                                        .TOO_MANY_REQUESTS))
                .when(otps)
                .issue(any(), any(), any(), any());
        service.requestReset("maker");
        verify(credential, never()).markResetRequested(any());
    }

    @Test
    void SRS_SEC_4_aResetWithAWrongCodeComparesNoPasswordAndTellsNothingAboutTheAccount() {
        // Security review 2026-10-31: the reuse check after the OTP, never before (no oracle).
        doThrow(new FciException(ApiErrorCode.INVALID_OTP))
                .when(otps)
                .verifyLatest(any(), any(), any());
        when(hasher.matches(any(), any())).thenReturn(true);
        refused(() -> service.reset("maker", "000000", "N3w#Passw0rd"), ApiErrorCode.INVALID_OTP);
        verify(hasher, never()).matches(any(), any());
        verify(history, never()).findNewestFirst(any());
        // With the right code, a reused password is refused, after the code was proved.
        org.mockito.Mockito.reset(otps);
        refused(
                () -> service.reset("maker", "123456", "N3w#Passw0rd"),
                ApiErrorCode.PASSWORD_REUSED);
        verify(otps).verifyLatest(user, OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE, "123456");
    }

    @Test
    void SRS_SEC_4_aTemporaryPasswordIsRequired() {
        when(credentials.findById(5L)).thenReturn(Optional.empty());
        refused(() -> service.setTemporary(user, null), ApiErrorCode.PASSWORD_POLICY);
    }

    @Test
    void SRS_SEC_2_theHistoryKeepsOnlyItsConfiguredDepth() {
        when(parameters.passwordHistory()).thenReturn(5);
        List<PasswordHistoryEntry> six =
                List.of(
                        mock(PasswordHistoryEntry.class),
                        mock(PasswordHistoryEntry.class),
                        mock(PasswordHistoryEntry.class),
                        mock(PasswordHistoryEntry.class),
                        mock(PasswordHistoryEntry.class),
                        mock(PasswordHistoryEntry.class));
        when(history.findNewestFirst(5L)).thenReturn(six);
        service.setTemporary(user, "T3mp#Passw0rd");
        // Five kept with the current hash: the four newest previous ones stay, two are dropped.
        verify(history, times(1)).delete(six.get(4));
        verify(history, times(1)).delete(six.get(5));
        verify(history, never()).delete(six.get(3));
    }
}
