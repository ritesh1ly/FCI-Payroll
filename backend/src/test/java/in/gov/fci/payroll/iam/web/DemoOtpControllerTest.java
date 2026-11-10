package in.gov.fci.payroll.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.orchestration.DemoOtpCodes;
import in.gov.fci.payroll.iam.orchestration.LoginService;
import in.gov.fci.payroll.iam.security.WebSessions;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;

/** OD-146: the two reads of the demo OTP code viewer take the user from the caller only. */
class DemoOtpControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-09T05:00:00Z");

    private final DemoOtpCodes codes = mock(DemoOtpCodes.class);
    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final ApiProperties properties = mock(ApiProperties.class);
    private final DemoOtpController controller =
            new DemoOtpController(codes, contexts, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    {
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
    }

    private static MockHttpServletRequest pending(long userId, Instant issuedAt) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        WebSessions.pendingLogin(
                request,
                new LoginService.PendingLogin(
                        userId, "u@fci-demo.test", 9L, null, null, issuedAt, false));
        return request;
    }

    @Test
    void OD_146_theSignedInUserReadsTheOwnCodeOnly() {
        AccessContext me = mock(AccessContext.class);
        when(me.userId()).thenReturn(11L);
        when(contexts.current()).thenReturn(me);
        when(codes.enabled()).thenReturn(true);
        when(codes.latest(11L, null))
                .thenReturn(new DemoOtpCodes.Shown("APPROVAL", "654321", NOW.plusSeconds(120)));
        var answer = controller.mine();
        assertThat(answer.getBody().code()).isEqualTo("654321");
        assertThat(answer.getBody().validSeconds()).isEqualTo(120);
        assertThat(answer.getHeaders().getCacheControl()).contains("no-store");
        verify(codes, never()).latest(12L, null);
    }

    @Test
    void OD_146_thePendingSignInReadsItsOwnLoginCode() {
        when(codes.enabled()).thenReturn(true);
        when(codes.latest(21L, OtpGatedAction.AUTH_LOGIN))
                .thenReturn(new DemoOtpCodes.Shown("AUTH_LOGIN", "100200", NOW.plusSeconds(300)));
        var answer = controller.pendingSignIn(pending(21L, NOW.minusSeconds(60)));
        assertThat(answer.getBody().code()).isEqualTo("100200");
    }

    @Test
    void OD_146_withoutAPendingSignInOrAfterItsValidityNothingIsShown() {
        when(codes.enabled()).thenReturn(true);
        assertThatThrownBy(() -> controller.pendingSignIn(new MockHttpServletRequest()))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(
                        () ->
                                controller.pendingSignIn(
                                        pending(21L, NOW.minus(Duration.ofMinutes(11)))))
                .isInstanceOf(FciException.class);
        verify(codes, never()).latest(anyLong(), any());
    }

    @Test
    void OD_146_switchedOffBothReadsAreNotFound() {
        when(codes.enabled()).thenReturn(false);
        assertThatThrownBy(controller::mine).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> controller.pendingSignIn(pending(21L, NOW)))
                .isInstanceOf(FciException.class);
        verify(contexts, never()).current();
    }

    @Test
    void OD_146_theSignedInReadNeedsASessionAndTheSignInReadIsPublic() throws Exception {
        assertThat(
                        DemoOtpController.class
                                .getMethod("mine")
                                .getAnnotation(PreAuthorize.class)
                                .value())
                .isEqualTo("isAuthenticated()");
        assertThat(
                        DemoOtpController.class
                                .getMethod("pendingSignIn", HttpServletRequest.class)
                                .getAnnotation(PreAuthorize.class)
                                .value())
                .isEqualTo("permitAll()");
    }
}
