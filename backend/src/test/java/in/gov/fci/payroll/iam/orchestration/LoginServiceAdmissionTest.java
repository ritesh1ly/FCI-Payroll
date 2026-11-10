package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.PasswordHasher;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-in admission (performance test 2026-10-27, RateLimits): sign-ins beyond the concurrent limit
 * wait their turn and are refused as unavailable when the queue time runs out or the wait is
 * interrupted; a request never prints its password; a hash already verified is not hashed again.
 */
class LoginServiceAdmissionTest {

    private static LoginService service(int concurrent, int queueSeconds) {
        ApiProperties properties = mock(ApiProperties.class);
        when(properties.rateLimits())
                .thenReturn(
                        new ApiProperties.RateLimits(
                                120, 5, 60, 3, 120, concurrent, queueSeconds, 60_000));
        return new LoginService(
                mock(AccessRepositories.Users.class),
                mock(IdentityRepositories.Credentials.class),
                mock(IdentityRepositories.Personas.class),
                mock(IdentityRepositories.Sessions.class),
                mock(ScopeResolver.class),
                mock(AccessContextService.class),
                mock(PasswordHasher.class),
                mock(SessionService.class),
                mock(AuditService.class),
                mock(NotificationSender.class),
                mock(OtpService.class),
                mock(TransactionTemplate.class),
                Clock.systemUTC(),
                properties,
                mock(SystemParameters.class));
    }

    private static final LoginService.LoginRequest REQUEST =
            new LoginService.LoginRequest(
                    "a@fci-demo.test", "Secret#1", "PAYROLL_MAKER", 3L, "10.0.0.1", "ua");

    @Test
    void aSignInBeyondTheConcurrentLimitIsRefusedWhenTheQueueTimeRunsOut() {
        LoginService service = service(0, 0);
        long started = System.nanoTime();
        assertThatThrownBy(() -> service.login(REQUEST))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
        // T1: the failure floor (60 s here) holds credential refusals only, never "busy, retry".
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(java.time.Duration.ofSeconds(30));
    }

    @Test
    void anInterruptedWaitIsRefusedAndKeepsTheInterrupt() {
        LoginService service = service(0, 0);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> service.login(REQUEST)).isInstanceOf(FciException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void R_PII_aRequestNeverPrintsItsPassword() {
        assertThat(REQUEST.toString())
                .isEqualTo("LoginRequest{loginId=a@fci-demo.test, role=PAYROLL_MAKER, location=3}")
                .doesNotContain("Secret");
    }

    @Test
    void aHashVerifiedOutsideTheTransactionIsReusedUnlessItChanged() {
        LoginService.Verified verified = new LoginService.Verified("h1", true, null);
        assertThat(verified.matches("h1", () -> false)).isTrue();
        assertThat(verified.matches("h2", () -> false))
                .as("a changed hash is checked again")
                .isFalse();
    }
}
