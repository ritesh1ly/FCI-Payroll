package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import in.gov.fci.payroll.platform.notification.SmsSender;
import in.gov.fci.payroll.platform.security.KeyMaterial;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * One-time passwords (SRS-G-48, SRS-G-49; R-OTP). Six digits to the user's own registered address,
 * stored only as an HMAC, five attempts, single use, at most five an hour per action; a new
 * challenge abandons the open one. Any mismatch (format, owner, action, unknown id) is the same
 * INVALID_OTP. After three wrong codes the user is alerted by e-mail and SMS, at the fifth the
 * challenge locks out as a security event; the account itself is never locked. A failed
 * notification never fails the user's action.
 */
class OtpServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");
    private static final byte[] KEY =
            "test-hmac-key-of-32-bytes-length".getBytes(StandardCharsets.US_ASCII);
    private static final OtpGatedAction ACTION = OtpGatedAction.AUTH_LOGIN;

    private final IdentityRepositories.Otps otps = mock(IdentityRepositories.Otps.class);
    private final NotificationSender notifications = mock(NotificationSender.class);
    private final KeyMaterial keys = mock(KeyMaterial.class);
    private final ApiProperties properties = mock(ApiProperties.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final SmsSender sms = mock(SmsSender.class);

    private OtpService service;
    private AppUser user;

    @BeforeEach
    void wire() {
        when(keys.auditHmacKey()).thenReturn(KEY);
        when(properties.otp())
                .thenReturn(
                        new ApiProperties.Otp(
                                Duration.ofMinutes(10),
                                5,
                                5,
                                3,
                                ApiProperties.Verification.Y,
                                true,
                                true));
        when(parameters.otpValidity()).thenReturn(Duration.ofMinutes(5));
        when(parameters.otpMaxAttempts()).thenReturn(5);
        when(otps.save(any()))
                .thenAnswer(
                        i -> {
                            OtpChallenge c = i.getArgument(0);
                            if (c.getId() == null) {
                                ReflectionTestUtils.setField(c, "id", 9L);
                            }
                            return c;
                        });
        service =
                new OtpService(
                        otps,
                        notifications,
                        keys,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        properties,
                        parameters,
                        audit,
                        sms);
        user = user("officer@fci-demo.test", "9876543210");
    }

    private static AppUser user(String email, String mobile) {
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(5L);
        when(u.getLoginId()).thenReturn("officer");
        when(u.getDisplayName()).thenReturn("A. Officer");
        when(u.getRegisteredEmail()).thenReturn(email);
        when(u.getRegisteredMobile()).thenReturn(mobile);
        return u;
    }

    /** Issues a challenge and returns the code the user received. */
    private String issued(OtpChallenge[] holder) {
        holder[0] = service.issue(user, ACTION, "app_user", "5");
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(notifications)
                .sendOtp(
                        eq("officer@fci-demo.test"),
                        eq("A. Officer"),
                        eq("AUTH_LOGIN"),
                        code.capture(),
                        eq(5));
        when(otps.findById(9L)).thenReturn(Optional.of(holder[0]));
        return code.getValue();
    }

    private static void invalid(ThrowingCallable call) {
        refused(call, ApiErrorCode.INVALID_OTP);
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    /**
     * OD-146: on a demo stack the code just sent is kept for the demo viewer, under the user's own
     * id, until the code's validity ends; the stored challenge still holds only the HMAC.
     */
    @Test
    void OD_146_aDemoStackKeepsTheSentCodeForItsOwnerUntilItExpires() {
        org.springframework.mock.env.MockEnvironment env =
                new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("demo", "online");
        env.setProperty("fci.api.otp.demo-viewer", "true");
        DemoOtpCodes demo = new DemoOtpCodes(env, Clock.fixed(NOW, ZoneOffset.UTC));
        service.setDemoCodes(demo);
        OtpChallenge[] holder = new OtpChallenge[1];
        String code = issued(holder);
        DemoOtpCodes.Shown shown = demo.latest(5L, ACTION);
        assertThat(shown.code()).isEqualTo(code);
        assertThat(shown.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(ReflectionTestUtils.getField(holder[0], "codeHash")).isNotEqualTo(code);
    }

    @Test
    void SRS_G_48_aSixDigitCodeGoesToTheUsersOwnAddressAndIsStoredOnlyAsAnHmac() {
        OtpChallenge[] challenge = new OtpChallenge[1];
        String code = issued(challenge);

        assertThat(code).matches("^[0-9]{6}$");
        String stored = (String) ReflectionTestUtils.getField(challenge[0], "codeHash");
        assertThat(stored)
                .isEqualTo(SecureTokens.hmacSha256Hex(KEY, "5:AUTH_LOGIN:" + code))
                .doesNotContain(code);

        assertThat(service.verify(user, ACTION, 9L, code)).isSameAs(challenge[0]);
        assertThat(challenge[0].getOutcome()).isEqualTo(OtpChallenge.Outcome.SUCCESS);
        verify(notifications).sendOtpVerified("officer@fci-demo.test", "A. Officer", "AUTH_LOGIN");
        verify(sms)
                .send(
                        "9876543210",
                        "FCILekha: OTP for AUTH_LOGIN verified on your account. Not you? Contact"
                                + " your administrator.");
        // Single use.
        invalid(() -> service.verify(user, ACTION, 9L, code));
    }

    // SC-01-0149: Resend OTP issues a fresh code, the earlier becomes invalid, within the resend
    // limit.
    @Test
    void SRS_G_48_aNewChallengeAbandonsTheOpenOneAndAtMostFiveAreIssuedAnHour() {
        OtpChallenge open =
                OtpChallenge.issue(
                        5L,
                        "officer",
                        ACTION,
                        "app_user",
                        "5",
                        "x",
                        null,
                        NOW,
                        Duration.ofMinutes(5));
        when(otps.findByUserIdAndGatedActionAndOutcome(5L, ACTION, OtpChallenge.Outcome.ISSUED))
                .thenReturn(List.of(open));
        service.issue(user, ACTION, "app_user", "5");
        assertThat(open.getOutcome()).isEqualTo(OtpChallenge.Outcome.ABANDONED);

        when(otps.countIssuedSince(eq(5L), eq(ACTION), any())).thenReturn(5L);
        refused(
                () -> service.issue(user, ACTION, "app_user", "5"),
                CommonErrorCode.TOO_MANY_REQUESTS);
    }

    /**
     * UC-07 precondition (SRS l.576), SRS-G-3 (US-GAP-118): the code goes only to the user's own
     * registered contact - a user with neither a mobile nor an e-mail (blank counts as none) gets
     * no challenge and no fallback to another contact: the step is refused.
     */
    @Test
    void SRS_G_3_aUserWithoutARegisteredContactIsRefusedTheOtpWithNoFallback() {
        for (AppUser none : new AppUser[] {user(null, null), user("  ", " ")}) {
            refused(
                    () -> service.issue(none, ACTION, "app_user", "5"),
                    ApiErrorCode.OTP_NO_CONTACT);
        }
        verify(otps, never()).save(any());
        verify(notifications, never()).sendOtp(any(), any(), any(), any(), anyInt());
        verify(sms, never()).sendOtp(any(), any(), any(), anyInt());
    }

    @Test
    void SRS_G_48_aMalformedForeignOrUnknownCodeIsTheSameInvalidOtp() {
        OtpChallenge[] challenge = new OtpChallenge[1];
        String code = issued(challenge);
        for (String bad : Arrays.asList(null, "", "12345", "1234567", "12345a", " 482913")) {
            invalid(() -> service.verify(user, ACTION, 9L, bad));
        }
        invalid(() -> service.verify(user, ACTION, null, code));
        invalid(() -> service.verify(user, ACTION, 404L, code));
        invalid(() -> service.verify(user, OtpGatedAction.CYCLE_FREEZE_LOCK, 9L, code));
        AppUser other = user("other@fci-demo.test", null);
        when(other.getId()).thenReturn(6L);
        invalid(() -> service.verify(other, ACTION, 9L, code));
        assertThat(challenge[0].getAttempts()).as("never a counted attempt").isZero();
        assertThat(challenge[0].getOutcome()).isEqualTo(OtpChallenge.Outcome.ISSUED);
    }

    @Test
    void SRS_G_21_aCodeAskedForOneItemIsRefusedOnAnotherAndNotUsedUp() {
        // Security review 2026-10-31: an OTP confirms one transaction, not an action type.
        OtpChallenge[] challenge = new OtpChallenge[1];
        String code = issued(challenge);
        invalid(() -> service.verify(user, ACTION, 9L, code, "app_user", "6"));
        invalid(() -> service.verify(user, ACTION, 9L, code, "approval_item", "5"));
        invalid(() -> service.verify(user, ACTION, 9L, code, null, "5"));
        assertThat(challenge[0].getAttempts()).as("never a counted attempt").isZero();
        assertThat(challenge[0].getOutcome()).isEqualTo(OtpChallenge.Outcome.ISSUED);
        // The item it was issued for: confirmed. A key longer than the stored 100 characters is
        // compared as stored.
        assertThat(service.verify(user, ACTION, 9L, code, "app_user", "5").getOutcome())
                .isEqualTo(OtpChallenge.Outcome.SUCCESS);
        OtpChallenge[] longKey = new OtpChallenge[1];
        org.mockito.Mockito.clearInvocations(notifications);
        longKey[0] = service.issue(user, ACTION, "app_user", "k".repeat(120));
        ArgumentCaptor<String> second = ArgumentCaptor.forClass(String.class);
        verify(notifications).sendOtp(any(), any(), eq("AUTH_LOGIN"), second.capture(), eq(5));
        when(otps.findById(9L)).thenReturn(Optional.of(longKey[0]));
        assertThat(
                        service.verify(
                                        user,
                                        ACTION,
                                        9L,
                                        second.getValue(),
                                        "app_user",
                                        "k".repeat(120))
                                .getOutcome())
                .isEqualTo(OtpChallenge.Outcome.SUCCESS);
    }

    @Test
    void SRS_G_49_threeWrongCodesAlertTheUserAndTheFifthLocksOnlyTheChallengeOut() {
        OtpChallenge[] challenge = new OtpChallenge[1];
        String code = issued(challenge);
        String wrong = "000000".equals(code) ? "111111" : "000000";

        for (int attempt = 1; attempt <= 5; attempt++) {
            invalid(() -> service.verify(user, ACTION, 9L, wrong));
        }

        verify(notifications)
                .sendOtpFailureAlert("officer@fci-demo.test", "A. Officer", "AUTH_LOGIN", 3);
        verify(sms)
                .send(
                        "9876543210",
                        "FCILekha security alert: 3 wrong OTPs for AUTH_LOGIN. Account not locked."
                                + " Not you? Contact your administrator.");
        assertThat(challenge[0].getOutcome()).isEqualTo(OtpChallenge.Outcome.LOCKED_OUT);
        verify(audit)
                .securityEvent(
                        AuditService.Security.OTP_LOCKOUT,
                        "officer",
                        "otp_challenge",
                        "9",
                        "AUTH_LOGIN");
        // Locked out: even the right code is refused now.
        invalid(() -> service.verify(user, ACTION, 9L, code));
    }

    @Test
    void SRS_G_48_aFailedNotificationNeverFailsTheUsersAction() {
        OtpChallenge[] challenge = new OtpChallenge[1];
        String code = issued(challenge);
        doThrow(new IllegalStateException("gateway down")).when(sms).send(anyString(), anyString());

        assertThat(service.verify(user, ACTION, 9L, code)).isSameAs(challenge[0]);
        assertThat(challenge[0].getOutcome()).isEqualTo(OtpChallenge.Outcome.SUCCESS);
    }

    @Test
    void SRS_G_48_noMailWithoutAnAddressAndNoSmsWithoutAMobile() {
        // Issued while the contact was recorded; verified after it was removed.
        OtpChallenge challenge = service.issue(user, ACTION, "app_user", "5");
        org.mockito.Mockito.clearInvocations(sms, notifications);
        user = user(null, "  ");
        String code = "123456";
        ReflectionTestUtils.setField(
                challenge, "codeHash", SecureTokens.hmacSha256Hex(KEY, "5:AUTH_LOGIN:" + code));
        when(otps.findFirstByUserIdAndGatedActionAndOutcomeOrderByIdDesc(
                        5L, ACTION, OtpChallenge.Outcome.ISSUED))
                .thenReturn(Optional.of(challenge));
        when(otps.findById(9L)).thenReturn(Optional.of(challenge));

        assertThat(service.verifyLatest(user, ACTION, code)).isSameAs(challenge);

        verify(notifications, never()).sendOtpVerified(any(), any(), any());
        verify(sms, never()).send(any(), any());
    }

    @Test
    void SRS_G_48_withoutAnOpenChallengeTheLatestCannotBeVerified() {
        invalid(() -> service.verifyLatest(user, ACTION, "123456"));
    }

    /**
     * SRS-G-21 (project owner, 2026-10-23): with OTP verification switched off the critical
     * activity records its challenge as confirmed at once; nothing is sent to the user.
     */
    @Test
    void SRS_G_21_withVerificationOffTheChallengeIsConfirmedWithoutACodeOrAMessage() {
        ApiProperties off = mock(ApiProperties.class);
        when(off.otp())
                .thenReturn(
                        new ApiProperties.Otp(
                                Duration.ofMinutes(10),
                                5,
                                5,
                                3,
                                ApiProperties.Verification.N,
                                true,
                                true));
        OtpService noOtp =
                new OtpService(
                        otps,
                        notifications,
                        keys,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        off,
                        parameters,
                        audit,
                        sms);
        assertThat(noOtp.verificationRequired()).isFalse();
        OtpChallenge c = noOtp.confirmWithoutCode(user, ACTION, "app_session", "77");
        assertThat(c.getOutcome()).isEqualTo(OtpChallenge.Outcome.SUCCESS);
        assertThat(c.getGatedAction()).isEqualTo(ACTION);
        assertThat(c.getEntityName()).isEqualTo("app_session");
        assertThat(c.getEntityKey()).isEqualTo("77");
        assertThat(c.getId()).isEqualTo(9L);
        verify(otps).save(c);
        verify(notifications, never())
                .sendOtp(anyString(), anyString(), anyString(), anyString(), anyInt());
        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void SRS_G_21_withVerificationRequiredNoChallengeIsEverConfirmedWithoutACode() {
        assertThat(service.verificationRequired()).isTrue();
        assertThatThrownBy(() -> service.confirmWithoutCode(user, ACTION, "app_session", "77"))
                .isInstanceOf(IllegalStateException.class);
        verify(otps, never()).save(any());
    }

    @Test
    void SRS_G_21_aChallengeAlreadyClosedIsNotConfirmedAgain() {
        OtpChallenge c =
                OtpChallenge.issue(
                        5L, "officer", ACTION, "e", "k", "h", null, NOW, Duration.ofMinutes(5));
        c.abandon(NOW);
        c.confirmWithoutCode(NOW);
        assertThat(c.getOutcome()).isEqualTo(OtpChallenge.Outcome.ABANDONED);
    }

    /**
     * SRS-G-48 (OD-36): the code goes to the registered mobile; with a gateway connected only
     * there, and while none is connected also to the registered e-mail; without a mobile (absent or
     * blank) to the e-mail alone. The challenge names the channel.
     */
    @Test
    void SRS_G_48_theCodeGoesToTheMobileAndToTheEmailOnlyWhileNoGatewayIsConnected() {
        when(sms.connected()).thenReturn(true);
        OtpChallenge bySms = service.issue(user, ACTION, "app_user", "5");
        assertThat(bySms.getDeliveryChannel()).isEqualTo(OtpChallenge.SMS);
        verify(sms).sendOtp(eq("9876543210"), eq("AUTH_LOGIN"), anyString(), eq(5));
        verify(notifications, never()).sendOtp(any(), any(), any(), any(), anyInt());

        AppUser noMobile = user("nomobile@fci-demo.test", null);
        OtpChallenge byEmail = service.issue(noMobile, ACTION, "app_user", "5");
        assertThat(byEmail.getDeliveryChannel()).isEqualTo(OtpChallenge.EMAIL);
        AppUser blankMobile = user("blank@fci-demo.test", "  ");
        assertThat(service.issue(blankMobile, ACTION, "app_user", "5").getDeliveryChannel())
                .isEqualTo(OtpChallenge.EMAIL);
        verify(notifications)
                .sendOtp(eq("nomobile@fci-demo.test"), any(), eq("AUTH_LOGIN"), anyString(), eq(5));
        verify(notifications)
                .sendOtp(eq("blank@fci-demo.test"), any(), eq("AUTH_LOGIN"), anyString(), eq(5));

        AppUser noContact = user(null, null);
        refused(
                () -> service.issue(noContact, ACTION, "app_user", "5"),
                ApiErrorCode.OTP_NO_CONTACT);
    }

    @Test
    void anUnknownDeliveryChannelIsAProgrammingError() {
        assertThatThrownBy(
                        () ->
                                OtpChallenge.issue(
                                        5L,
                                        "officer",
                                        ACTION,
                                        null,
                                        null,
                                        "hash",
                                        null,
                                        java.time.Instant.now(),
                                        Duration.ofMinutes(5),
                                        "PIGEON"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // NEW-G1-01 (US-01-04 AC4; SC-01-0003, SC-01-0022): the retry limit is the A-07 setting.
    @Test
    void SRS_G_22_theRetryLimitIsTheA07Setting() {
        when(parameters.otpMaxAttempts()).thenReturn(2);
        OtpChallenge[] holder = new OtpChallenge[1];
        issued(holder);
        invalid(() -> service.verify(user, ACTION, 9L, "000000"));
        assertThat(holder[0].getOutcome()).isEqualTo(OtpChallenge.Outcome.ISSUED);
        invalid(() -> service.verify(user, ACTION, 9L, "000000"));
        assertThat(holder[0].getOutcome())
                .as("locked at the second wrong code")
                .isEqualTo(OtpChallenge.Outcome.LOCKED_OUT);
        assertThat(holder[0].getOutcomeAt()).isEqualTo(NOW);
        verify(audit)
                .securityEvent(
                        eq(AuditService.Security.OTP_LOCKOUT),
                        eq("officer"),
                        eq("otp_challenge"),
                        eq("9"),
                        eq("AUTH_LOGIN"));
    }

    // NEW-G1-01: after a lockout the action is blocked for the A-07 cooling-off period: a new
    // challenge is refused one second before the end, issued at the end; another action and a
    // lockout without a recorded time are not blocked; no period set = no block (OD-67).
    @Test
    void SRS_G_22_afterTheRetryLimitTheActionIsBlockedForTheCoolingOffPeriod() {
        OtpChallenge locked =
                OtpChallenge.issue(
                        5L,
                        "officer",
                        ACTION,
                        "app_user",
                        "5",
                        "hash",
                        null,
                        NOW.minusSeconds(1200),
                        Duration.ofMinutes(10),
                        OtpChallenge.EMAIL);
        ReflectionTestUtils.setField(locked, "outcome", OtpChallenge.Outcome.LOCKED_OUT);
        ReflectionTestUtils.setField(locked, "outcomeAt", NOW.minusSeconds(599));
        when(otps.findFirstByUserIdAndGatedActionAndOutcomeOrderByIdDesc(
                        5L, ACTION, OtpChallenge.Outcome.LOCKED_OUT))
                .thenReturn(Optional.of(locked));

        when(parameters.otpCoolingOff()).thenReturn(Optional.empty());
        assertThat(service.issue(user, ACTION, "app_user", "5")).as("no period set").isNotNull();

        when(parameters.otpCoolingOff()).thenReturn(Optional.of(Duration.ofMinutes(10)));
        assertThatThrownBy(() -> service.issue(user, ACTION, "app_user", "5"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.OTP_COOLING_OFF);
                            // Locked at 05:50:01 UTC + 10 minutes = 11:30:01 in India.
                            assertThat(e.getMessageArgs()).containsExactly("23-10-2026 11:30");
                        });
        assertThat(service.issue(user, OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE, "app_user", "5"))
                .as("another action is not blocked")
                .isNotNull();

        ReflectionTestUtils.setField(locked, "outcomeAt", NOW.minusSeconds(600));
        assertThat(service.issue(user, ACTION, "app_user", "5"))
                .as("the period has ended (boundary)")
                .isNotNull();

        ReflectionTestUtils.setField(locked, "outcomeAt", null);
        assertThat(service.issue(user, ACTION, "app_user", "5"))
                .as("no lockout time recorded")
                .isNotNull();
    }

    /** SRS-G-48: by SMS without a connected gateway and without an e-mail, no mail is sent. */
    @Test
    void SRS_G_48_aCodeBySmsWithoutAGatewayAndWithoutAnEmailIsSentBySmsOnly() {
        AppUser mobileOnly = user(null, "9876543210");
        OtpChallenge challenge = service.issue(mobileOnly, ACTION, "app_user", "5");
        assertThat(challenge.getDeliveryChannel()).isEqualTo(OtpChallenge.SMS);
        verify(sms).sendOtp(eq("9876543210"), eq("AUTH_LOGIN"), anyString(), eq(5));
        verify(notifications, never()).sendOtp(any(), any(), any(), any(), anyInt());
    }

    /** SRS-G-48: a verified code is confirmed by mail only to an account without a mobile. */
    @Test
    void SRS_G_48_aVerifiedCodeOfAnAccountWithoutAMobileIsConfirmedByMailOnly() {
        user = user("officer@fci-demo.test", null);
        OtpChallenge[] challenge = new OtpChallenge[1];
        String code = issued(challenge);
        assertThat(service.verify(user, ACTION, 9L, code)).isSameAs(challenge[0]);
        verify(notifications).sendOtpVerified("officer@fci-demo.test", "A. Officer", "AUTH_LOGIN");
        verify(sms, never()).send(any(), any());
    }
}
