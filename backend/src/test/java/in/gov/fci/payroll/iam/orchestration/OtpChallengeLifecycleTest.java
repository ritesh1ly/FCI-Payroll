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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import in.gov.fci.payroll.platform.notification.SmsSender;
import in.gov.fci.payroll.platform.security.KeyMaterial;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The life of one OTP code, the failure paths {@link OtpServiceTest} leaves out (SRS-G-3: "correct,
 * unexpired and unused"; SRS-G-22: retry limit, cooling-off and the audit event; SC-01-0149: a
 * resend makes the earlier code invalid). The clock is a test clock: nothing waits real time.
 */
class OtpChallengeLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");
    private static final Duration VALIDITY = Duration.ofMinutes(5);
    private static final OtpGatedAction ACTION = OtpGatedAction.SUPPLEMENTARY_POST_LOCK;

    private final IdentityRepositories.Otps otps = mock(IdentityRepositories.Otps.class);
    private final NotificationSender notifications = mock(NotificationSender.class);
    private final KeyMaterial keys = mock(KeyMaterial.class);
    private final ApiProperties properties = mock(ApiProperties.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final SmsSender sms = mock(SmsSender.class);
    private final List<OtpChallenge> store = new ArrayList<>();

    private AppUser user;

    @BeforeEach
    void wire() {
        when(keys.auditHmacKey())
                .thenReturn("test-hmac-key-of-32-bytes-length".getBytes(StandardCharsets.US_ASCII));
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
        when(parameters.otpValidity()).thenReturn(VALIDITY);
        when(parameters.otpMaxAttempts()).thenReturn(5);
        when(parameters.otpCoolingOff()).thenReturn(Optional.of(Duration.ofMinutes(15)));
        // Every saved challenge has an id of its own; the store answers the service's own reads.
        when(otps.save(any()))
                .thenAnswer(
                        i -> {
                            OtpChallenge c = i.getArgument(0);
                            if (c.getId() == null) {
                                store.add(c);
                                ReflectionTestUtils.setField(c, "id", (long) store.size());
                            }
                            return c;
                        });
        when(otps.findById(any()))
                .thenAnswer(
                        i ->
                                store.stream()
                                        .filter(c -> c.getId().equals(i.getArgument(0)))
                                        .findFirst());
        when(otps.findByUserIdAndGatedActionAndOutcome(5L, ACTION, OtpChallenge.Outcome.ISSUED))
                .thenAnswer(
                        i ->
                                store.stream()
                                        .filter(c -> c.getOutcome() == OtpChallenge.Outcome.ISSUED)
                                        .toList());
        when(otps.findFirstByUserIdAndGatedActionAndOutcomeOrderByIdDesc(
                        5L, ACTION, OtpChallenge.Outcome.LOCKED_OUT))
                .thenAnswer(
                        i ->
                                store.stream()
                                        .filter(
                                                c ->
                                                        c.getOutcome()
                                                                == OtpChallenge.Outcome.LOCKED_OUT)
                                        .reduce((a, b) -> b));
        user = mock(AppUser.class);
        when(user.getId()).thenReturn(5L);
        when(user.getLoginId()).thenReturn("officer");
        when(user.getDisplayName()).thenReturn("A. Officer");
        when(user.getRegisteredEmail()).thenReturn("officer@fci-demo.test");
    }

    private OtpService at(Instant now) {
        return new OtpService(
                otps,
                notifications,
                keys,
                Clock.fixed(now, ZoneOffset.UTC),
                properties,
                parameters,
                audit,
                sms);
    }

    /** Issues a challenge at {@code when} and returns the code the user received last. */
    private String issue(Instant when) {
        at(when).issue(user, ACTION, "approval", "77");
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(store.size()))
                .sendOtp(anyString(), anyString(), eq(ACTION.name()), code.capture(), anyInt());
        return code.getValue();
    }

    private static String wrongFor(String code) {
        return "000000".equals(code) ? "111111" : "000000";
    }

    private static void invalid(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.INVALID_OTP));
    }

    @Test
    void SRS_G_3_aCodePastItsValidityIsRefusedTheChallengeExpiresAndNoAttemptIsCounted() {
        String code = issue(NOW);
        OtpChallenge challenge = store.get(0);

        invalid(() -> at(NOW.plus(VALIDITY)).verify(user, ACTION, 1L, code));

        assertThat(challenge.getOutcome()).isEqualTo(OtpChallenge.Outcome.EXPIRED);
        assertThat(challenge.getAttempts()).isZero();
        verify(audit, never()).securityEvent(any(), any(), any(), any(), any());
    }

    @Test
    void SRS_G_3_theLastSecondOfTheValidityStillAccepts() {
        String code = issue(NOW);

        assertThat(at(NOW.plus(VALIDITY).minusSeconds(1)).verify(user, ACTION, 1L, code))
                .isSameAs(store.get(0));
    }

    @Test
    void SRS_G_3_aCodeAlreadyUsedIsRefusedOnTheSecondUse() {
        String code = issue(NOW);
        at(NOW).verify(user, ACTION, 1L, code);
        assertThat(store.get(0).getOutcome()).isEqualTo(OtpChallenge.Outcome.SUCCESS);

        invalid(() -> at(NOW.plusSeconds(1)).verify(user, ACTION, 1L, code));

        assertThat(store.get(0).getOutcome()).isEqualTo(OtpChallenge.Outcome.SUCCESS);
    }

    /** SC-01-0149: Resend OTP issues a fresh code and the earlier one becomes invalid. */
    @Test
    void SRS_G_48_aResendKillsTheEarlierCodeAndOnlyTheNewOneWorks() {
        String first = issue(NOW);
        String second = issue(NOW.plusSeconds(30));

        assertThat(store.get(0).getOutcome()).isEqualTo(OtpChallenge.Outcome.ABANDONED);
        invalid(() -> at(NOW.plusSeconds(31)).verify(user, ACTION, 1L, first));
        assertThat(store.get(0).getAttempts()).isZero();

        assertThat(at(NOW.plusSeconds(32)).verify(user, ACTION, 2L, second).getOutcome())
                .isEqualTo(OtpChallenge.Outcome.SUCCESS);
    }

    @Test
    void SRS_G_48_theOlderCodeDoesNotWorkOnTheNewChallenge() {
        String first = issue(NOW);
        String second = issue(NOW.plusSeconds(30));
        if (first.equals(second)) {
            return;
        }

        invalid(() -> at(NOW.plusSeconds(31)).verify(user, ACTION, 2L, first));

        assertThat(store.get(1).getOutcome()).isEqualTo(OtpChallenge.Outcome.ISSUED);
        assertThat(store.get(1).getAttempts()).isOne();
    }

    /** Below the limit a wrong code leaves the action retryable, with no event and no block. */
    @Test
    void SRS_G_22_belowTheRetryLimitTheRightCodeStillWorksAndNoAuditEventIsRaised() {
        String code = issue(NOW);
        for (int attempt = 1; attempt < 5; attempt++) {
            invalid(() -> at(NOW).verify(user, ACTION, 1L, wrongFor(code)));
        }
        verify(audit, never()).securityEvent(any(), any(), any(), any(), any());

        assertThat(at(NOW).verify(user, ACTION, 1L, code).getOutcome())
                .isEqualTo(OtpChallenge.Outcome.SUCCESS);
    }

    @Test
    void SRS_G_22_theLimitRaisesOneAuditEventAndBlocksTheActionForTheCoolingOffPeriod() {
        String code = issue(NOW);
        for (int attempt = 1; attempt <= 5; attempt++) {
            invalid(() -> at(NOW).verify(user, ACTION, 1L, wrongFor(code)));
        }
        // A further attempt on the closed challenge is no second event.
        invalid(() -> at(NOW).verify(user, ACTION, 1L, wrongFor(code)));

        verify(audit, times(1))
                .securityEvent(
                        AuditService.Security.OTP_LOCKOUT,
                        "officer",
                        "otp_challenge",
                        "1",
                        ACTION.name());
        Instant minuteLater = NOW.plusSeconds(60);
        assertThatThrownBy(() -> at(minuteLater).issue(user, ACTION, "approval", "77"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.OTP_COOLING_OFF));
        assertThat(store).hasSize(1);

        assertThat(at(NOW.plus(Duration.ofMinutes(15))).issue(user, ACTION, "approval", "77"))
                .isNotNull();
    }

    /**
     * A delivery failure while sending the code is the caller's failure, never a silent success: no
     * "code sent" can be answered, and the caller's transaction rolls this challenge back.
     */
    @Test
    void SRS_G_3_aFailedDeliveryOfTheCodeFailsTheRequest() {
        doThrow(new IllegalStateException("mail down"))
                .when(notifications)
                .sendOtp(anyString(), anyString(), anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> at(NOW).issue(user, ACTION, "approval", "77"))
                .isInstanceOf(IllegalStateException.class);
    }
}
