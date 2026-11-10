package in.gov.fci.payroll.integration.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** SRS-INT-4: the bearer token is short-lived, signed, and refused when changed. */
class HrmsTokensTest {

    private static final String SECRET = "test-only-signing-secret-0123456789abcdef";
    private static final Instant NOW = Instant.now();

    @Test
    void SRS_INT_4_aFreshTokenVerifies() {
        String token = HrmsTokens.issue(SECRET, "hrms-client", NOW, 900, "id-1");
        var claims = HrmsTokens.verify(SECRET, token, NOW.plusSeconds(10)).orElseThrow();
        assertThat(claims.subject()).isEqualTo("hrms-client");
        assertThat(claims.id()).isEqualTo("id-1");
        assertThat(claims.expiresAt().getEpochSecond())
                .isEqualTo(NOW.plusSeconds(900).getEpochSecond());
    }

    @Test
    void SRS_INT_4_anExpiredTokenIsRefused() {
        String token = HrmsTokens.issue(SECRET, "c", NOW, 900, "i");
        assertThat(HrmsTokens.verify(SECRET, token, NOW.plusSeconds(900))).isEmpty();
        assertThat(HrmsTokens.verify(SECRET, token, NOW.plusSeconds(899))).isPresent();
    }

    @Test
    void SRS_INT_4_aTokenFromTheFutureBeyondSkewIsRefused() {
        String token = HrmsTokens.issue(SECRET, "c", NOW.plusSeconds(3600), 900, "i");
        assertThat(HrmsTokens.verify(SECRET, token, NOW)).isEmpty();
    }

    @Test
    void SRS_INT_4_aTokenSignedWithAnotherSecretIsRefused() {
        String token = HrmsTokens.issue("another-secret-another-secret-123456", "c", NOW, 900, "i");
        assertThat(HrmsTokens.verify(SECRET, token, NOW)).isEmpty();
    }

    // The last character of a 32-byte signature has two unused bits: a decoder reads a changed
    // value of them as the same bytes, so the signature must be compared as issued.
    @Test
    void SRS_INT_4_aSignatureChangedOnlyInItsUnusedBitsIsRefused() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (int run = 0; run < 64; run++) {
            String token = HrmsTokens.issue(SECRET, "c", NOW, 900, "i" + run);
            int last = alphabet.indexOf(token.charAt(token.length() - 1));
            assertThat(HrmsTokens.verify(SECRET, token, NOW)).isPresent();
            for (int unused = 1; unused < 4; unused++) {
                String changed =
                        token.substring(0, token.length() - 1) + alphabet.charAt(last | unused);
                assertThat(HrmsTokens.verify(SECRET, changed, NOW)).as(changed).isEmpty();
            }
        }
    }

    @Test
    void SRS_INT_4_aChangedPayloadOrSignatureIsRefused() {
        String token = HrmsTokens.issue(SECRET, "c", NOW, 900, "i");
        String[] parts = token.split("\\.");
        String forged =
                Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        ("admin|0|" + (NOW.getEpochSecond() + 99999) + "|i")
                                                .getBytes())
                        + "."
                        + parts[1];
        assertThat(HrmsTokens.verify(SECRET, forged, NOW)).isEmpty();
        assertThat(HrmsTokens.verify(SECRET, parts[0] + "." + parts[1].substring(1) + "A", NOW))
                .isEmpty();
    }

    @Test
    void SRS_INT_4_malformedTokensAreRefused() {
        for (String bad :
                new String[] {
                    null,
                    "",
                    ".",
                    "a.",
                    ".b",
                    "a.b.c",
                    "no-dot",
                    "!!.!!",
                    "x".repeat(600),
                    Base64.getUrlEncoder().withoutPadding().encodeToString("a|b".getBytes()) + ".c"
                }) {
            assertThat(HrmsTokens.verify(SECRET, bad, NOW)).as("%s", bad).isEmpty();
        }
    }

    @Test
    void SRS_INT_4_aSignedBodyWithBadNumbersOrPartsIsRefused() {
        for (String body : new String[] {"c|x|y|i", "c|1|2", "c|1|2|3|4"}) {
            String payload =
                    Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes());
            String token = payload + "." + signature(payload);
            assertThat(HrmsTokens.verify(SECRET, token, NOW)).as(body).isEmpty();
        }
    }

    private static String signature(String payload) {
        String good = HrmsTokens.issue(SECRET, "c", NOW, 1, "i");
        // Re-sign through a token whose payload we replace: use the library on the same payload.
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(), "HmacSHA256"));
            assertThat(good).isNotBlank();
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes()));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void SRS_INT_4_aSmallClockDifferenceBetweenInstancesIsTolerated() {
        String token = HrmsTokens.issue(SECRET, "c", NOW.plusSeconds(30), 900, "i");
        assertThat(HrmsTokens.verify(SECRET, token, NOW)).as("issued 30 s ahead").isPresent();
        String far = HrmsTokens.issue(SECRET, "c", NOW.plusSeconds(120), 900, "i");
        assertThat(HrmsTokens.verify(SECRET, far, NOW)).as("issued 120 s ahead").isEmpty();
    }

    @Test
    void SRS_INT_4_anUnsignedTokenOfTheJwtNoneKindIsRefused() {
        String header =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("{\"alg\":\"none\"}".getBytes());
        String body =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(("c|0|" + (NOW.getEpochSecond() + 900) + "|i").getBytes());
        assertThat(HrmsTokens.verify(SECRET, header + "." + body + ".", NOW)).isEmpty();
        assertThat(HrmsTokens.verify(SECRET, body + ".", NOW)).isEmpty();
        assertThat(HrmsTokens.verify("", HrmsTokens.issue(SECRET, "c", NOW, 900, "i"), NOW))
                .as("no secret verifies nothing")
                .isEmpty();
        assertThat(HrmsTokens.verify(null, HrmsTokens.issue(SECRET, "c", NOW, 900, "i"), NOW))
                .isEmpty();
    }
}
