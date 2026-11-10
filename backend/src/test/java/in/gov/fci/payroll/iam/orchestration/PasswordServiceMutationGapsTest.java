package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.PasswordHasher;
import in.gov.fci.payroll.iam.business.PasswordPolicy;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.business.model.PasswordHistoryEntry;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.approval.OtpProof;
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
 * Mutation gaps of {@link PasswordService}: SRS-SEC-2 (history depth, reuse), SRS-SEC-4 (reset),
 * SRS-G-21 (OTP-confirmed own change), SRS-G-49 (policy settings), audit lines and session endings.
 */
class PasswordServiceMutationGapsTest {

    private static final Instant NOW = Instant.parse("2026-11-06T06:00:00.123456Z");
    private static final String OLD = "Old#Passw0rd12";
    private static final String NEW = "N3w#Passw0rd12";

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final IdentityRepositories.Credentials credentials =
            mock(IdentityRepositories.Credentials.class);
    private final IdentityRepositories.PasswordHistory history =
            mock(IdentityRepositories.PasswordHistory.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final PasswordPolicy policy = mock(PasswordPolicy.class);
    private final OtpService otps = mock(OtpService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final AuditService audit = mock(AuditService.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final PasswordService service =
            new PasswordService(
                    users,
                    credentials,
                    history,
                    hasher,
                    policy,
                    otps,
                    sessions,
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC),
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
        when(parameters.passwordHistory()).thenReturn(5);
        when(parameters.passwordMinLength()).thenReturn(14);
        when(parameters.passwordCharacterClasses()).thenReturn(3);
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static AccessContext self() {
        return new AccessContext(
                5L, "maker", "Maker", "sess-1", "P", 1L, null, List.of(), Map.of(), Set.of(),
                Set.of(), Set.of());
    }

    private static OtpChallenge challenge(long id) {
        OtpChallenge c = mock(OtpChallenge.class);
        when(c.getId()).thenReturn(id);
        return c;
    }

    private static AuditService.Entry entry(String field, String newValue, String reason) {
        return AuditService.Entry.change("app_user", "5", field, null, newValue, reason);
    }

    private static List<PasswordHistoryEntry> historyOf(int n) {
        List<PasswordHistoryEntry> list = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            PasswordHistoryEntry e = mock(PasswordHistoryEntry.class);
            when(e.getPasswordHash()).thenReturn("hE" + i);
            list.add(e);
        }
        return list;
    }

    // ------------------------------------------------------------------ change

    @Test
    void SRS_SEC_2_aChangeOfAnUnknownUserOrWithoutACredentialIsAnInvalidGrant() {
        when(users.findById(5L)).thenReturn(Optional.empty());
        refused(() -> service.change(self(), OLD, NEW, null), ApiErrorCode.INVALID_GRANT);

        when(users.findById(5L)).thenReturn(Optional.of(user));
        when(credentials.findById(5L)).thenReturn(Optional.empty());
        refused(() -> service.change(self(), OLD, NEW, null), ApiErrorCode.INVALID_GRANT);
    }

    @Test
    void SRS_SEC_2_aWrongCurrentPasswordIsAuditedIndependentlyAsAFailure() {
        when(hasher.matches(OLD, "h1")).thenReturn(false);

        refused(() -> service.change(self(), OLD, NEW, null), ApiErrorCode.CURRENT_PASSWORD_WRONG);

        verify(audit)
                .recordIndependently(
                        "maker",
                        entry(
                                "PASSWORD_CHANGE",
                                "FAILURE:" + ApiErrorCode.CURRENT_PASSWORD_WRONG.code(),
                                "Current password wrong"));
        verify(sessions, never()).endOtherSessions(any(), any(), any());
    }

    @Test
    void SRS_G_21_aVerifiedChangeEndsTheOtherSessionsAndAuditsWithTheChallengeId() {
        when(otps.verificationRequired()).thenReturn(true);
        when(hasher.matches(OLD, "h1")).thenReturn(true);
        when(hasher.hash(NEW)).thenReturn("h2");
        OtpChallenge ch77 = challenge(77L);
        when(otps.verify(
                        user,
                        OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE,
                        9L,
                        "123456",
                        PasswordService.CHANGE_ENTITY,
                        "5"))
                .thenReturn(ch77);

        service.change(self(), OLD, NEW, new OtpProof(9L, "123456"));

        verify(credential).replace("h2", NOW);
        verify(sessions).endOtherSessions(5L, "sess-1", NOW);
        verify(audit)
                .record(
                        "maker",
                        entry(
                                "PASSWORD_CHANGE",
                                "SUCCESS",
                                "Password changed by the user with OTP challenge 77"));
    }

    @Test
    void SRS_G_21_aChangeWithVerificationOffAuditsTheConfirmedChallengeId() {
        when(otps.verificationRequired()).thenReturn(false);
        when(hasher.matches(OLD, "h1")).thenReturn(true);
        when(hasher.hash(NEW)).thenReturn("h2");
        OtpChallenge ch11 = challenge(11L);
        when(otps.confirmWithoutCode(
                        user,
                        OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE,
                        PasswordService.CHANGE_ENTITY,
                        "5"))
                .thenReturn(ch11);

        service.change(self(), OLD, NEW, null);

        verify(audit)
                .record(
                        "maker",
                        entry(
                                "PASSWORD_CHANGE",
                                "SUCCESS",
                                "Password changed by the user with OTP challenge 11"));
    }

    @Test
    void SRS_SEC_2_aChangeKeepsOnlyTheConfiguredHistoryDepth() {
        when(otps.verificationRequired()).thenReturn(false);
        OtpChallenge ch1 = challenge(1L);
        when(otps.confirmWithoutCode(any(), any(), any(), any())).thenReturn(ch1);
        when(hasher.matches(OLD, "h1")).thenReturn(true);
        when(hasher.hash(NEW)).thenReturn("h2");
        List<PasswordHistoryEntry> six = historyOf(6);
        when(history.findNewestFirst(5L)).thenReturn(six);

        service.change(self(), OLD, NEW, null);

        verify(history, times(1)).delete(six.get(4));
        verify(history, times(1)).delete(six.get(5));
        verify(history, never()).delete(six.get(3));
        verify(history).save(any(PasswordHistoryEntry.class));
    }

    @Test
    void SRS_G_49_aNewPasswordBreakingThePolicyIsRefusedBeforeAnyCodeIsSent() {
        when(hasher.matches(OLD, "h1")).thenReturn(true);
        when(otps.verificationRequired()).thenReturn(true);
        when(policy.check(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(PasswordPolicy.Violation.TOO_SHORT));

        assertThatThrownBy(() -> service.change(self(), OLD, "short", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.PASSWORD_POLICY);
                            assertThat(e.getViolations()).hasSize(1);
                            assertThat(e.getViolations().get(0).field()).isEqualTo("newPassword");
                        });

        verify(otps, never()).issue(any(), any(), any(), any());
        verify(credential, never()).replace(any(), any());
    }

    @Test
    void SRS_G_49_thePolicyIsAskedWithTheAccountsIdentityAndTheConfiguredSettings() {
        when(user.getEmployeeId()).thenReturn(1234L);
        when(user.getRegisteredEmail()).thenReturn("maker@fci-demo.test");
        when(policy.check(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(PasswordPolicy.Violation.LOW_COMPLEXITY));
        when(hasher.matches(OLD, "h1")).thenReturn(true);

        refused(() -> service.change(self(), OLD, NEW, null), ApiErrorCode.PASSWORD_POLICY);
        verify(policy).check(NEW, "maker", "1234", "maker@fci-demo.test", 14, 3);

        when(user.getEmployeeId()).thenReturn(null);
        refused(() -> service.change(self(), OLD, NEW, null), ApiErrorCode.PASSWORD_POLICY);
        verify(policy).check(NEW, "maker", null, "maker@fci-demo.test", 14, 3);
    }

    @Test
    void SRS_G_49_aMissingNewPasswordIsATooShortPolicyFailure() {
        when(hasher.matches(OLD, "h1")).thenReturn(true);

        assertThatThrownBy(() -> service.change(self(), OLD, null, null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.PASSWORD_POLICY);
                            assertThat(e.getViolations().get(0).code()).isEqualTo("TOO_SHORT");
                        });
    }

    // ------------------------------------------------------------------ history depth

    @Test
    void SRS_SEC_2_aPasswordMatchingAnEntryWithinTheHistoryDepthIsReusedOneBeyondItIsNot() {
        when(parameters.passwordHistory()).thenReturn(3); // the current one + 2 previous
        List<PasswordHistoryEntry> four = historyOf(4);
        when(history.findNewestFirst(5L)).thenReturn(four);

        when(hasher.matches("T3mp#Passw0rd", "hE1")).thenReturn(true);
        refused(() -> service.setTemporary(user, "T3mp#Passw0rd"), ApiErrorCode.PASSWORD_REUSED);

        when(hasher.matches("T3mp#Passw0rd", "hE1")).thenReturn(false);
        when(hasher.matches("T3mp#Passw0rd", "hE2")).thenReturn(true);
        assertThatCode(() -> service.setTemporary(user, "T3mp#Passw0rd"))
                .as("the third previous password is past the depth")
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ requestReset

    @Test
    void SRS_SEC_4_anUnknownLoginIsAuditedAsIgnoredAndNothingIsSent() {
        when(users.findByLoginId("ghost")).thenReturn(Optional.empty());

        service.requestReset("ghost");

        verify(audit)
                .record(
                        "ghost",
                        AuditService.Entry.change(
                                "app_user",
                                "-",
                                "PASSWORD_RESET_REQUEST",
                                null,
                                "IGNORED",
                                "Unknown or inactive login"));
        verify(otps, never()).issue(any(), any(), any(), any());
    }

    @Test
    void SRS_SEC_4_aResetRequestSendsTheCodeMarksTheCredentialAndAudits() {
        service.requestReset(" Maker ");

        verify(otps).issue(user, OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE, "app_user", "5");
        verify(credential).markResetRequested(NOW);
        verify(credentials).save(credential);
        verify(audit)
                .record(
                        "maker",
                        AuditService.Entry.change(
                                "app_user",
                                "5",
                                "PASSWORD_RESET_REQUEST",
                                null,
                                "OTP_SENT",
                                "Reset OTP sent"));
    }

    @Test
    void SRS_SEC_4_aResetRequestForAnAccountWithoutCredentialStillAuditsTheSending() {
        when(credentials.findById(5L)).thenReturn(Optional.empty());

        service.requestReset("maker");

        verify(credentials, never()).save(any());
        verify(audit).record(eq("maker"), any(AuditService.Entry.class));
    }

    // ------------------------------------------------------------------ reset

    @Test
    void SRS_SEC_4_aResetWithAWeakPasswordIsRefusedBeforeAnyAccountIsLookedUp() {
        when(policy.check(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(PasswordPolicy.Violation.TOO_SHORT));

        refused(() -> service.reset("maker", "123456", "weak"), ApiErrorCode.PASSWORD_POLICY);

        verify(users, never()).findByLoginId(any());
        verify(otps, never()).verifyLatest(any(), any(), any());
    }

    @Test
    void SRS_SEC_4_aResetOfAnAccountWithoutCredentialIsAnInvalidOtp() {
        when(credentials.findById(5L)).thenReturn(Optional.empty());

        refused(() -> service.reset("maker", "123456", NEW), ApiErrorCode.INVALID_OTP);

        verify(otps, never()).verifyLatest(any(), any(), any());
    }

    @Test
    void SRS_SEC_4_aResetWithTheRightCodeStoresTheEndsAllSessionsAndAudits() {
        when(hasher.hash(NEW)).thenReturn("h2");

        service.reset(" MAKER ", "123456", NEW);

        verify(otps).verifyLatest(user, OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE, "123456");
        verify(credential).replace("h2", NOW);
        verify(sessions).endAllSessions(5L, NOW, AppSession.EndReason.REVOCATION);
        verify(audit)
                .record("maker", entry("PASSWORD_RESET", "SUCCESS", "Password reset with OTP"));
        // The policy is checked up front and again in each of the two reuse checks.
        verify(policy, times(3)).check(eq(NEW), any(), any(), any(), anyInt(), anyInt());
        verify(hasher, times(2)).matches(NEW, "h1");
    }

    // ------------------------------------------------------------------ setTemporary

    @Test
    void SRS_SEC_2_aTemporaryPasswordEqualToTheCurrentOneIsRefusedAndNothingIsStored() {
        when(hasher.matches("T3mp#Passw0rd", "h1")).thenReturn(true);

        refused(() -> service.setTemporary(user, "T3mp#Passw0rd"), ApiErrorCode.PASSWORD_REUSED);

        verify(credential, never()).resetByAdministrator(any(), any());
        verify(credentials, never()).save(any());
    }

    @Test
    void SRS_SEC_2_aTemporaryPasswordIsStoredAsMustChangeWithTheOldHashInTheHistory() {
        when(hasher.hash("T3mp#Passw0rd")).thenReturn("hT");

        service.setTemporary(user, "T3mp#Passw0rd");

        verify(credential)
                .resetByAdministrator("hT", NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        verify(credentials).save(credential);
        verify(history).save(any(PasswordHistoryEntry.class));
    }

    @Test
    void SRS_SEC_2_aTemporaryPasswordBreakingThePolicyIsRefusedForAnExistingCredential() {
        when(policy.check(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(PasswordPolicy.Violation.COMMON_PASSWORD));

        refused(() -> service.setTemporary(user, "password"), ApiErrorCode.PASSWORD_POLICY);

        verify(credential, never()).resetByAdministrator(any(), any());
    }
}
