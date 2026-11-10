package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.integration.business.HrmsTokens;
import in.gov.fci.payroll.integration.config.HrmsSecurityProperties;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/**
 * SRS-INT-4: key + bearer token, token exchange for the client credentials; unset = refuse all;
 * every refusal recorded for X-03 without saying which part was wrong. All secrets here are made up
 * for the test at run time.
 */
class HrmsAccessServiceTest {

    private static final String KEY = "it-key-" + SecureTokens.randomToken(16);
    private static final String SECRET = "it-signing-" + SecureTokens.randomToken(32);
    private static final String PASSWORD = "it-pw-" + SecureTokens.randomToken(16);
    private static final String HASH =
            PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);

    private final HrmsRefusals refusals = mock(HrmsRefusals.class);
    private Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);

    private HrmsAccessService service(HrmsSecurityProperties p) {
        return new HrmsAccessService(p, refusals, clock);
    }

    private static HrmsSecurityProperties complete() {
        return new HrmsSecurityProperties(KEY, null, "hrms-client", HASH, null, SECRET, null, 600);
    }

    private static void refused(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(FciException.class).hasMessage(code);
    }

    @Test
    void SRS_INT_4_theRightKeyAndCredentialsGiveATokenThatOpensEveryApi() {
        HrmsAccessService s = service(complete());
        HrmsAccessService.Token token = s.exchange("hrms-client", PASSWORD);
        assertThat(token.expiresIn()).isEqualTo(600);
        s.requireKey(KEY);
        s.requireKeyAndToken(KEY, "Bearer " + token.accessToken());
        s.requireKeyAndToken(KEY, "bearer " + token.accessToken());
        verify(refusals, never()).record(contains(""), contains(""));
    }

    @Test
    void SRS_INT_4_theDefaultTokenLifetimeIsShort() {
        var p = new HrmsSecurityProperties(KEY, null, "c", HASH, null, SECRET, null, null);
        assertThat(service(p).exchange("c", PASSWORD).expiresIn()).isEqualTo(900);
        assertThat(
                        new HrmsSecurityProperties(KEY, null, "c", HASH, null, SECRET, null, 0)
                                .lifetime())
                .isEqualTo(900);
    }

    @Test
    void SRS_INT_4_aWrongOrMissingKeyIsRefusedAndRecorded() {
        HrmsAccessService s = service(complete());
        for (String bad : new String[] {null, "", "wrong", KEY + "x", KEY.substring(1)}) {
            refused(() -> s.requireKey(bad), "INT-401-HRMS-AUTH");
        }
        verify(refusals, org.mockito.Mockito.atLeastOnce())
                .record(HrmsRefusals.AUTH_INTERFACE, "HRMS call refused: api-key missing or wrong");
    }

    @Test
    void SRS_INT_4_aMissingForgedOrExpiredTokenIsRefused() {
        HrmsAccessService s = service(complete());
        String good = s.exchange("hrms-client", PASSWORD).accessToken();
        refused(() -> s.requireKeyAndToken(KEY, null), "INT-401-HRMS-AUTH");
        refused(() -> s.requireKeyAndToken(KEY, ""), "INT-401-HRMS-AUTH");
        refused(() -> s.requireKeyAndToken(KEY, good), "INT-401-HRMS-AUTH");
        refused(() -> s.requireKeyAndToken(KEY, "Basic " + good), "INT-401-HRMS-AUTH");
        refused(() -> s.requireKeyAndToken(KEY, "Bearer " + good + "x"), "INT-401-HRMS-AUTH");
        refused(
                () ->
                        s.requireKeyAndToken(
                                KEY,
                                "Bearer "
                                        + HrmsTokens.issue(
                                                "another-secret-another-secret-1234567",
                                                "hrms-client",
                                                clock.instant(),
                                                600,
                                                "i")),
                "INT-401-HRMS-AUTH");
        refused(
                () ->
                        s.requireKeyAndToken(
                                KEY,
                                "Bearer "
                                        + HrmsTokens.issue(
                                                SECRET, "someone-else", clock.instant(), 600, "i")),
                "INT-401-HRMS-AUTH");
        clock = Clock.offset(clock, Duration.ofSeconds(601));
        HrmsAccessService later = service(complete());
        refused(() -> later.requireKeyAndToken(KEY, "Bearer " + good), "INT-401-HRMS-AUTH");
        verify(refusals, org.mockito.Mockito.atLeastOnce())
                .record(
                        HrmsRefusals.AUTH_INTERFACE,
                        "HRMS call refused: bearer token missing, wrong or expired");
    }

    @Test
    void SRS_INT_4_aTokenWithTheRightKeyButNoRightApiKeyIsRefused() {
        HrmsAccessService s = service(complete());
        String good = s.exchange("hrms-client", PASSWORD).accessToken();
        refused(() -> s.requireKeyAndToken("wrong", "Bearer " + good), "INT-401-HRMS-AUTH");
    }

    @Test
    void SRS_INT_4_wrongClientCredentialsGetNoTokenAndAreRecorded() {
        HrmsAccessService s = service(complete());
        refused(() -> s.exchange("hrms-client", "wrong"), "INT-401-HRMS-AUTH");
        refused(() -> s.exchange("other", PASSWORD), "INT-401-HRMS-AUTH");
        refused(() -> s.exchange(null, null), "INT-401-HRMS-AUTH");
        refused(() -> s.exchange("hrms-client", ""), "INT-401-HRMS-AUTH");
        verify(refusals, org.mockito.Mockito.atLeastOnce())
                .record(
                        HrmsRefusals.AUTH_INTERFACE,
                        "HRMS token exchange refused: wrong client credentials");
    }

    @Test
    void SRS_INT_4_aHashThatIsNotAHashRefusesEveryPassword() {
        var p =
                new HrmsSecurityProperties(
                        KEY, null, "c", "{bcrypt}not-a-hash", null, SECRET, null, 600);
        refused(() -> service(p).exchange("c", PASSWORD), "INT-401-HRMS-AUTH");
        var plain =
                new HrmsSecurityProperties(KEY, null, "c", "no-prefix", null, SECRET, null, 600);
        refused(() -> service(plain).exchange("c", PASSWORD), "INT-401-HRMS-AUTH");
    }

    @Test
    void SRS_INT_4_SRS_INT_16_anythingUnsetRefusesEveryApiWithAnUnavailableAnswer() {
        var none = new HrmsSecurityProperties(null, null, null, null, null, null, null, null);
        var noKey = new HrmsSecurityProperties(null, null, "c", HASH, null, SECRET, null, null);
        var noClient = new HrmsSecurityProperties(KEY, null, " ", HASH, null, SECRET, null, null);
        var noHash = new HrmsSecurityProperties(KEY, null, "c", null, null, SECRET, null, null);
        var noSecret = new HrmsSecurityProperties(KEY, null, "c", HASH, null, null, null, null);
        var shortSecret =
                new HrmsSecurityProperties(KEY, null, "c", HASH, null, "short", null, null);
        for (HrmsSecurityProperties p :
                new HrmsSecurityProperties[] {
                    none, noKey, noClient, noHash, noSecret, shortSecret
                }) {
            assertThat(p.complete()).isFalse();
            HrmsAccessService s = service(p);
            refused(() -> s.requireKey(KEY), "INT-503-HRMS-AUTH-NOT-CONFIGURED");
            refused(
                    () -> s.requireKeyAndToken(KEY, "Bearer x"),
                    "INT-503-HRMS-AUTH-NOT-CONFIGURED");
            refused(() -> s.exchange("c", PASSWORD), "INT-503-HRMS-AUTH-NOT-CONFIGURED");
        }
        verify(refusals, org.mockito.Mockito.atLeastOnce())
                .record(
                        HrmsRefusals.AUTH_INTERFACE,
                        "HRMS access refused: the key, client or signing secret is not configured");
    }

    @Test
    void SRS_INT_4_secretsMayComeFromFilesAndNeverAppearInToString(@TempDir Path dir)
            throws Exception {
        Path key = Files.writeString(dir.resolve("key"), KEY + "\n");
        Path secret = Files.writeString(dir.resolve("secret"), SECRET + "\n");
        Path hash = Files.writeString(dir.resolve("hash"), HASH + "\n");
        var p =
                new HrmsSecurityProperties(
                        null,
                        key.toString(),
                        "c",
                        null,
                        hash.toString(),
                        null,
                        secret.toString(),
                        null);
        assertThat(p.complete()).isTrue();
        assertThat(service(p).exchange("c", PASSWORD).accessToken()).isNotBlank();
        assertThat(p.toString()).doesNotContain(KEY).doesNotContain(SECRET).doesNotContain(HASH);
        var missing =
                new HrmsSecurityProperties(
                        null,
                        dir.resolve("nope").toString(),
                        "c",
                        null,
                        " ",
                        null,
                        dir.toString(),
                        null);
        assertThat(missing.key()).isEmpty();
        assertThat(missing.secretHash()).isEmpty();
        assertThat(missing.signingSecret()).isEmpty();
        Path blank = Files.writeString(dir.resolve("blank"), "  \n");
        assertThat(
                        new HrmsSecurityProperties(
                                        null, blank.toString(), null, null, null, null, null, null)
                                .key())
                .isEmpty();
    }
}
