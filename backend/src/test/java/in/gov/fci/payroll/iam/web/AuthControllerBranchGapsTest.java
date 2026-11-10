package in.gov.fci.payroll.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.iam.business.LoginThrottle;
import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.LoginService;
import in.gov.fci.payroll.iam.orchestration.MeService;
import in.gov.fci.payroll.iam.orchestration.PasswordService;
import in.gov.fci.payroll.iam.orchestration.SessionService;
import in.gov.fci.payroll.iam.security.WebSessions;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.SecurityAuditLogger;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/**
 * Branch gaps of the sign-in endpoints (SRS-G-48, OD-41, SRS-SEC-13): a blank starting office is no
 * office, an expired pending sign-in is dropped, the account-discovery floor with a zero floor, and
 * the language toggle with and without a signed-in user.
 */
class AuthControllerBranchGapsTest {

    private final LoginService login = mock(LoginService.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PasswordService passwords = mock(PasswordService.class);
    private final MeService me = mock(MeService.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final ApiProperties properties = mock(ApiProperties.class);
    private final ApiProperties.Session session = mock(ApiProperties.Session.class);
    private AuthController controller;

    private static final SessionService.StartedSession STARTED =
            new SessionService.StartedSession(
                    "app-session-1",
                    42L,
                    false,
                    Instant.now().plusSeconds(3600),
                    Duration.ofMinutes(15));

    private void floor(int millis) {
        when(properties.rateLimits())
                .thenReturn(new ApiProperties.RateLimits(120, 5, 60, 3, 120, 4, 5, millis));
    }

    @BeforeEach
    void wire() {
        floor(0);
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
        when(properties.i18n()).thenReturn(new ApiProperties.I18n("en", "FCI_LOCALE"));
        when(properties.session()).thenReturn(session);
        controller =
                new AuthController(
                        login,
                        org,
                        mock(SessionService.class),
                        passwords,
                        me,
                        contexts,
                        mock(LoginThrottle.class),
                        mock(SecurityAuditLogger.class),
                        properties,
                        mock(PayrollPeriods.class));
    }

    @Test
    void SRS_G_48_aBlankStartingOfficeAsksForNoOffice() {
        when(login.login(any()))
                .thenReturn(
                        new LoginService.Signed(
                                new LoginService.LoginResult(STARTED, "STAFF", null, "X-01")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        var answer =
                controller.login(
                        new AuthController.LoginRequest("maker", "pw", null, "   ", null), request);
        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        verify(org, never()).byCode(any());
        verify(login)
                .login(
                        argThat(
                                r ->
                                        r.locationId() == null
                                                && r.idType() == LoginService.IdType.USER_ID));
    }

    @Test
    void SRS_G_48_anExpiredPendingSignInIsDroppedAndAFreshOneSignsIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        WebSessions.pendingLogin(
                request,
                new LoginService.PendingLogin(
                        5L, "maker", 77L, null, null, Instant.now().minus(Duration.ofMinutes(11))));
        assertThatThrownBy(
                        () ->
                                controller.loginOtp(
                                        new AuthController.LoginOtpRequest("482913"), request))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.INVALID_OTP));
        assertThat(WebSessions.pendingLogin(request)).isEmpty();
        verify(login, never()).completeWithOtp(any(), any(), any(), any());

        LoginService.PendingLogin fresh =
                new LoginService.PendingLogin(5L, "maker", 77L, null, null, Instant.now());
        WebSessions.pendingLogin(request, fresh);
        when(login.completeWithOtp(eq(fresh), eq("482913"), any(), any()))
                .thenReturn(new LoginService.LoginResult(STARTED, "STAFF", null, "X-01"));
        assertThat(
                        controller
                                .loginOtp(new AuthController.LoginOtpRequest("482913"), request)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(WebSessions.pendingLogin(request)).as("used up").isEmpty();
    }

    @Test
    void SRS_SEC_13_aForgotPasswordAnswersAtOnceWithoutAFloorAndAfterItWithOne() {
        assertThat(
                        controller
                                .forgot(new AuthController.ForgotRequest("maker"))
                                .getStatusCode()
                                .value())
                .isEqualTo(202);
        floor(200);
        assertThat(
                        controller
                                .forgot(new AuthController.ForgotRequest("maker"))
                                .getStatusCode()
                                .value())
                .isEqualTo(202);
        verify(passwords, times(2)).requestReset("maker");
    }

    @Test
    void SRS_SEC_13_anInterruptedWaitForTheFloorStillAnswers202AndKeepsTheInterruptFlag() {
        floor(60_000);
        Thread.currentThread().interrupt();
        try {
            long started = System.nanoTime();
            var answer = controller.forgot(new AuthController.ForgotRequest("maker"));
            assertThat(answer.getStatusCode().value()).isEqualTo(202);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt is re-asserted for the caller, not swallowed")
                    .isTrue();
            assertThat(System.nanoTime() - started)
                    .as("the wait was cut short by the interrupt")
                    .isLessThan(Duration.ofSeconds(30).toNanos());
            verify(passwords).requestReset("maker");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void SRS_SEC_13_aForgotPasswordForAnUnknownIdentifierAnswersTheSame202() {
        assertThat(
                        controller
                                .forgot(new AuthController.ForgotRequest("no.such.user"))
                                .getStatusCode()
                                .value())
                .isEqualTo(202);
        verify(passwords).requestReset("no.such.user");
    }

    @Test
    void SRS_G_48_theLanguageIsStoredForASignedInUserAndOnlyInTheCookieOtherwise() {
        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        var cookie = controller.locale(new AuthController.LocaleRequest("hi"), anonymous);
        assertThat(cookie.getHeaders().getFirst("Set-Cookie")).contains("FCI_LOCALE=hi");
        verify(me, never()).setLocale(any(), any());

        MockHttpServletRequest signed = new MockHttpServletRequest();
        WebSessions.signIn(signed, STARTED);
        controller.locale(new AuthController.LocaleRequest("hi"), signed);
        verify(me).setLocale(any(), eq("hi"));
    }
}
