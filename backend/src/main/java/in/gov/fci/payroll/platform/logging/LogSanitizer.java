package in.gov.fci.payroll.platform.logging;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Neutralises user-supplied values before they reach a log line: encodes CR, LF and other control
 * characters (log forging, CWE-117), masks sensitive data and caps the length.
 */
public final class LogSanitizer {

    public static final int DEFAULT_MAX_LENGTH = 128;
    public static final String REDACTED = "***";

    /** Substrings that mark a name as sensitive wherever they appear (e.g. {@code newPassword}). */
    private static final List<String> SENSITIVE_FRAGMENTS =
            List.of(
                    "password",
                    "passwd",
                    "passphrase",
                    "secret",
                    "token",
                    "captcha",
                    "credential",
                    "authorization",
                    "apikey",
                    "privatekey",
                    "aadhaar",
                    "aadhar");

    /** Short terms that are sensitive only as a whole word (so {@code mapping} is not a PIN). */
    private static final Set<String> SENSITIVE_WORDS =
            Set.of("pwd", "otp", "pin", "mpin", "pan", "cvv");

    private static final Pattern WORD_BOUNDARY =
            Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[_.\\s-]+");

    private LogSanitizer() {}

    /**
     * Encodes control characters, masks sensitive patterns and truncates to {@link
     * #DEFAULT_MAX_LENGTH}.
     */
    public static String sanitize(Object value) {
        return sanitize(value, DEFAULT_MAX_LENGTH);
    }

    public static String sanitize(Object value, int maxLength) {
        if (value == null) {
            return "null";
        }
        String masked = SensitiveDataMasker.mask(String.valueOf(value));
        return encodeControlCharacters(truncate(masked, maxLength));
    }

    /** Encodes CR, LF, TAB and every other ISO control character as a visible escape sequence. */
    public static String encodeControlCharacters(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String replacement = replacementFor(c);
            if (replacement != null) {
                if (out == null) {
                    out = new StringBuilder(value.length() + 16);
                    out.append(value, 0, i);
                }
                out.append(replacement);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? value : out.toString();
    }

    /** Whether a parameter, field or key name denotes a secret whose value must never be logged. */
    public static boolean isSensitiveName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String fragment : SENSITIVE_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        for (String word : WORD_BOUNDARY.split(name)) {
            if (SENSITIVE_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String truncate(String value, int maxLength) {
        if (maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…(" + value.length() + ")";
    }

    private static String replacementFor(char c) {
        return switch (c) {
            case '\r' -> "\\r";
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            // Unicode line and paragraph separators (U+0085 is covered by isISOControl).
            case 0x2028, 0x2029 -> String.format("\\u%04x", (int) c);
            default -> Character.isISOControl(c) ? String.format("\\u%04x", (int) c) : null;
        };
    }
}
