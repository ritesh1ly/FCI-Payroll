package in.gov.fci.payroll.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographically secure random tokens and the one place that hashes: refresh, reset and MFA
 * tokens, idempotency keys, session ids, document checksums, parameter and rule-set fingerprints,
 * and the HMAC under which one-time passwords are stored. Never use {@code java.util.Random} for
 * these purposes, and never re-implement a digest elsewhere.
 */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final String SHA_256 = "SHA-256";
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private SecureTokens() {}

    /** A URL-safe random token with {@code numBytes} bytes of entropy (minimum 16). */
    public static String randomToken(int numBytes) {
        if (numBytes < 16) {
            throw new IllegalArgumentException("Tokens need at least 128 bits of entropy");
        }
        byte[] bytes = new byte[numBytes];
        RANDOM.nextBytes(bytes);
        return BASE64_URL.encodeToString(bytes);
    }

    public static byte[] sha256(byte[] input) {
        return digest(SHA_256, input);
    }

    /** Lower-case hex SHA-256 of bytes (e.g. {@code document_attachment.checksum_sha256}). */
    public static String sha256Hex(byte[] input) {
        return HexFormat.of().formatHex(sha256(input));
    }

    public static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Base64Url(String input) {
        return BASE64_URL.encodeToString(sha256(input.getBytes(StandardCharsets.UTF_8)));
    }

    /** Lower-case hex HMAC-SHA-256 of {@code data} under {@code key} (UTF-8). */
    public static String hmacSha256Hex(byte[] key, String data) {
        return HexFormat.of()
                .formatHex(mac(HMAC_SHA_256, key, data.getBytes(StandardCharsets.UTF_8)));
    }

    /** Compares two secrets in time independent of where they differ. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Every Java platform must provide SHA-256 and HmacSHA256 (JCA standard names); one without
     * them fails loudly here rather than hashing with something else.
     */
    static byte[] digest(String algorithm, byte[] input) {
        try {
            return MessageDigest.getInstance(algorithm).digest(input);
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException(
                    algorithm + " is required by every Java platform", missing);
        }
    }

    static byte[] mac(String algorithm, byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            return mac.doFinal(data);
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException(
                    algorithm + " is required by every Java platform", missing);
        }
    }
}
