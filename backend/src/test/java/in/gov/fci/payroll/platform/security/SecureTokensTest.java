package in.gov.fci.payroll.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The one hashing and token helper (SRS-G-48: OTP codes stored only as an HMAC; OWASP ASVS V6.2:
 * approved algorithms, V2.9: random tokens of at least 128 bits). Checked against the published
 * test vectors (FIPS 180-2 for SHA-256, RFC 4231 for HMAC-SHA-256).
 */
class SecureTokensTest {

    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void SRS_G_48_digestsMatchTheirPublishedTestVectors() {
        assertThat(SecureTokens.sha256Hex("abc")).isEqualTo(ABC_SHA256);
        assertThat(SecureTokens.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(ABC_SHA256);
        assertThat(SecureTokens.sha256Base64Url("abc"))
                .isEqualTo("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0");
        // RFC 4231 test case 2.
        assertThat(
                        SecureTokens.hmacSha256Hex(
                                "Jefe".getBytes(StandardCharsets.UTF_8),
                                "what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void SRS_G_48_aPlatformWithoutTheAlgorithmsFailsLoudlyRatherThanHashOtherwise() {
        assertThatThrownBy(() -> SecureTokens.digest("NO-SUCH-DIGEST", new byte[] {1}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NO-SUCH-DIGEST is required by every Java platform");
        assertThatThrownBy(() -> SecureTokens.mac("NO-SUCH-MAC", new byte[] {1}, new byte[] {2}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NO-SUCH-MAC is required by every Java platform");
    }

    @Test
    void SRS_G_48_tokensCarryAtLeast128BitsAndNeverRepeat() {
        assertThatThrownBy(() -> SecureTokens.randomToken(15))
                .isInstanceOf(IllegalArgumentException.class);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = SecureTokens.randomToken(16);
            assertThat(token).matches("^[A-Za-z0-9_-]{22}$");
            assertThat(seen.add(token)).isTrue();
        }
    }

    @Test
    void SRS_G_48_secretsAreComparedWithoutRevealingWhereTheyDiffer() {
        assertThat(SecureTokens.constantTimeEquals("482913", "482913")).isTrue();
        assertThat(SecureTokens.constantTimeEquals("482913", "482914")).isFalse();
        assertThat(SecureTokens.constantTimeEquals(null, "482913")).isFalse();
        assertThat(SecureTokens.constantTimeEquals("482913", null)).isFalse();
    }
}
