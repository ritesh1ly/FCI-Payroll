package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * OD-146: the demo OTP code viewer keeps the newest code of a user in memory while it is valid, on
 * a demo stack with the switch on only, and answers only for the user id of the caller's own
 * session.
 */
class DemoOtpCodesTest {

    private static final Instant NOW = Instant.parse("2026-11-09T05:00:00Z");
    private static final OtpGatedAction OTHER =
            java.util.Arrays.stream(OtpGatedAction.values())
                    .filter(a -> a != OtpGatedAction.AUTH_LOGIN)
                    .findFirst()
                    .orElseThrow();

    private static MockEnvironment env(boolean on, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        env.setProperty("fci.api.otp.demo-viewer", String.valueOf(on));
        return env;
    }

    private static DemoOtpCodes codes(MockEnvironment env, Instant now) {
        return new DemoOtpCodes(env, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static void notFound(Runnable read) {
        assertThatThrownBy(read::run)
                .isInstanceOf(FciException.class)
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void OD_146_theCodeIsShownToItsOwnerOnlyAndNeverToAnotherUser() {
        DemoOtpCodes codes = codes(env(true, "demo", "online"), NOW);
        assertThat(codes.enabled()).isTrue();
        codes.remember(11L, OtpGatedAction.AUTH_LOGIN, "123456", NOW.plusSeconds(600));
        DemoOtpCodes.Shown shown = codes.latest(11L, null);
        assertThat(shown.code()).isEqualTo("123456");
        assertThat(shown.action()).isEqualTo("AUTH_LOGIN");
        // Another user's id has no code of its own: not found, never the first user's.
        notFound(() -> codes.latest(12L, null));
        // The code never appears in the record's text (log lines, exception messages).
        assertThat(shown.toString()).doesNotContain("123456");
    }

    @Test
    void OD_146_theNewestCodeReplacesTheOlderAndTheActionFilterApplies() {
        DemoOtpCodes codes = codes(env(true, "demo", "online"), NOW);
        codes.remember(11L, OtpGatedAction.AUTH_LOGIN, "111111", NOW.plusSeconds(600));
        codes.remember(11L, OTHER, "222222", NOW.plusSeconds(600));
        assertThat(codes.latest(11L, null).code()).isEqualTo("222222");
        assertThat(codes.latest(11L, OTHER).code()).isEqualTo("222222");
        notFound(() -> codes.latest(11L, OtpGatedAction.AUTH_LOGIN));
    }

    @Test
    void OD_146_anExpiredCodeIsNeverShownAndIsDropped() {
        MockEnvironment env = env(true, "demo", "online");
        DemoOtpCodes at = codes(env, NOW.plusSeconds(600));
        at.remember(11L, OtpGatedAction.AUTH_LOGIN, "123456", NOW.plusSeconds(600));
        notFound(() -> at.latest(11L, null));
        // A newer code of another user drops the expired entry; the new one is shown.
        at.remember(12L, OtpGatedAction.AUTH_LOGIN, "654321", NOW.plusSeconds(1200));
        assertThat(at.latest(12L, null).code()).isEqualTo("654321");
        notFound(() -> at.latest(11L, null));
    }

    @Test
    void OD_146_offOutsideTheDemoProfileOrWithoutTheSwitchNothingIsKept() {
        for (DemoOtpCodes off :
                new DemoOtpCodes[] {
                    codes(env(true, "uat", "online"), NOW),
                    codes(env(false, "demo", "online"), NOW),
                    codes(env(true, "demo", "prod", "online"), NOW)
                }) {
            assertThat(off.enabled()).isFalse();
            off.remember(11L, OtpGatedAction.AUTH_LOGIN, "123456", NOW.plusSeconds(600));
            notFound(() -> off.latest(11L, null));
        }
    }
}
