package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Sensitive;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * The demo OTP code viewer (OD-146): on a demo stack, a signed-in evaluator (or the browser of a
 * pending sign-in) may see the last one-time code sent to THEIR OWN account, so OTP can stay on
 * (SRS-G-21, SRS-G-48) without a phone or mailbox for every demo user.
 *
 * <p>Safeguards (R-OTP is unchanged: the database holds only the HMAC of a code):
 *
 * <ul>
 *   <li>On only when the profile {@code demo} is active AND {@code fci.api.otp.demo-viewer=true};
 *       {@code StartupGuards} refuse the switch without {@code demo}, with {@code prod}, and in a
 *       UAT that is not a local demo stack. Off, nothing is kept and every read is not found.
 *   <li>The code is kept in this instance's memory only (never in the database, a log or a file),
 *       one per user (the newest replaces the older), until the code's own validity ends; an
 *       expired entry is never shown and is dropped.
 *   <li>Read only by the user id of the caller's own session or pending sign-in: there is no
 *       parameter naming another user, so another user's code cannot be asked for.
 * </ul>
 */
@Component
public class DemoOtpCodes {

    /** The newest code of a user while it is valid. */
    public record Shown(String action, @Sensitive String code, Instant expiresAt) {
        @Override
        public String toString() {
            // Never the code in a log line or an exception message.
            return "Shown[action=" + action + ", expiresAt=" + expiresAt + "]";
        }
    }

    private final boolean enabled;
    private final Clock clock;
    private final Map<Long, Shown> codes = new ConcurrentHashMap<>();

    public DemoOtpCodes(Environment environment, Clock clock) {
        this.enabled = enabled(environment);
        this.clock = clock;
    }

    /** Whether the switch is on (the demo profile and {@code fci.api.otp.demo-viewer}). */
    static boolean enabled(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("demo"))
                && !environment.acceptsProfiles(Profiles.of("prod"))
                && environment.getProperty("fci.api.otp.demo-viewer", Boolean.class, false);
    }

    public boolean enabled() {
        return enabled;
    }

    /** Called by {@link OtpService#issue} with the code just sent; nothing when off. */
    void remember(long userId, OtpGatedAction action, @Sensitive String code, Instant expiresAt) {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        codes.values().removeIf(s -> !s.expiresAt().isAfter(now));
        codes.put(userId, new Shown(action.name(), code, expiresAt));
    }

    /**
     * The caller's own newest valid code ({@code action} null: of any action); not found when the
     * viewer is off, when none was sent, or when it has expired.
     */
    public Shown latest(long userId, OtpGatedAction action) {
        if (!enabled) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        Shown shown = codes.get(userId);
        Instant now = clock.instant();
        return Optional.ofNullable(shown)
                .filter(s -> s.expiresAt().isAfter(now))
                .filter(s -> action == null || action.name().equals(s.action()))
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }
}
