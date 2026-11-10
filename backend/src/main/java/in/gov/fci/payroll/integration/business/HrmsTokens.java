package in.gov.fci.payroll.integration.business;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The short-lived bearer token of the HRMS APIs (SRS-INT-4): {@code base64url(sub|iat|exp|jti) "."
 * base64url(HMAC-SHA-256)}, signed with the token secret held outside the code. Stateless: any
 * instance verifies it with the same secret, nothing is stored. The signature is compared in
 * constant time; a token past its expiry, with a changed byte or signed with another secret is
 * refused alike. Pure: no I/O.
 */
public final class HrmsTokens {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int MAX_TOKEN_LENGTH = 512;

    private HrmsTokens() {}

    /** A verified token. */
    public record Claims(String subject, Instant issuedAt, Instant expiresAt, String id) {}

    /** Issues a token for {@code subject} valid for {@code lifetimeSeconds}. */
    public static String issue(
            String secret, String subject, Instant now, int lifetimeSeconds, String id) {
        String body =
                subject
                        + "|"
                        + now.getEpochSecond()
                        + "|"
                        + now.plusSeconds(lifetimeSeconds).getEpochSecond()
                        + "|"
                        + id;
        String payload = ENCODER.encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return payload + "." + ENCODER.encodeToString(sign(secret, payload));
    }

    /** The claims of a genuine, unexpired token, or empty (the reason is never told). */
    public static Optional<Claims> verify(String secret, String token, Instant now) {
        if (secret == null
                || secret.isEmpty()
                || token == null
                || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot < 1 || dot != token.lastIndexOf('.') || dot == token.length() - 1) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        byte[] body;
        try {
            body = DECODER.decode(payload);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        // Compared as text, not decoded: the last character of the signature carries two unused
        // bits, which a decoder ignores, so a changed character could still decode to the same
        // bytes and the token would pass with a byte changed.
        byte[] given = token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        byte[] expected =
                ENCODER.encodeToString(sign(secret, payload)).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(given, expected)) {
            return Optional.empty();
        }
        String[] parts = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            Instant issued = Instant.ofEpochSecond(Long.parseLong(parts[1]));
            Instant expires = Instant.ofEpochSecond(Long.parseLong(parts[2]));
            return now.isBefore(expires) && !now.isBefore(issued.minusSeconds(60))
                    ? Optional.of(new Claims(parts[0], issued, expires, parts[3]))
                    : Optional.empty();
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static byte[] sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }
}
