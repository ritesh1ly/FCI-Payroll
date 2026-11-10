package in.gov.fci.payroll.iam.web;

import in.gov.fci.payroll.iam.orchestration.DemoOtpCodes;
import in.gov.fci.payroll.iam.orchestration.LoginService;
import in.gov.fci.payroll.iam.security.WebSessions;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo OTP code viewer (OD-146; see {@link DemoOtpCodes} for its safeguards). Two reads, each
 * of the caller's OWN newest code only; neither takes a user, login or challenge parameter:
 *
 * <pre>
 * GET /auth/otp/demo-code        the signed-in user's newest code (any gated action)
 * GET /auth/login/otp/demo-code  the code of this browser's pending sign-in (AUTH_LOGIN)
 * </pre>
 *
 * Both answer 404 when the viewer is off (every profile but a demo stack with the switch on), when
 * nothing was sent or the code expired. {@code Cache-Control: no-store}. The code is shown, never
 * logged: neither this controller nor the holder is {@code @Loggable}, and {@link
 * DemoOtpCodes.Shown} prints without it.
 */
@RestController
@Profile("online")
public class DemoOtpController {

    /** What the OTP dialog shows on a demo stack. */
    public record DemoCode(String action, String code, long validSeconds) {}

    private final DemoOtpCodes codes;
    private final CurrentAccess contexts;
    private final ApiProperties properties;
    private final Clock clock;

    public DemoOtpController(
            DemoOtpCodes codes, CurrentAccess contexts, ApiProperties properties, Clock clock) {
        this.codes = codes;
        this.contexts = contexts;
        this.properties = properties;
        this.clock = clock;
    }

    /** The signed-in user's own newest code, of the action the OTP dialog is asking for. */
    @GetMapping("/auth/otp/demo-code")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DemoCode> mine() {
        if (!codes.enabled()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return answer(codes.latest(contexts.current().userId(), null));
    }

    /** The code of this browser's own pending sign-in, while the sign-in is pending. */
    @GetMapping("/auth/login/otp/demo-code")
    @PreAuthorize("permitAll()")
    public ResponseEntity<DemoCode> pendingSignIn(HttpServletRequest request) {
        if (!codes.enabled()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        LoginService.PendingLogin pending =
                WebSessions.pendingLogin(request)
                        .filter(
                                p ->
                                        !p.issuedAt()
                                                .plus(properties.otp().ttl())
                                                .isBefore(clock.instant()))
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        return answer(codes.latest(pending.userId(), OtpGatedAction.AUTH_LOGIN));
    }

    private ResponseEntity<DemoCode> answer(DemoOtpCodes.Shown shown) {
        long seconds =
                Math.max(
                        0,
                        java.time.Duration.between(clock.instant(), shown.expiresAt()).toSeconds());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new DemoCode(shown.action(), shown.code(), seconds));
    }
}
