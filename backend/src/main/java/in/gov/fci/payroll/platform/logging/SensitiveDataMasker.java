package in.gov.fci.payroll.platform.logging;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets and personal data in free text before it is written to a log. It is a safety net:
 * the primary control is never passing such values to a logger (see {@link Sensitive}).
 *
 * <p>Covered: passwords/secrets/tokens/OTPs/CAPTCHA in {@code key=value} or JSON form, Bearer
 * credentials, JWTs, Aadhaar numbers (Verhoeff-validated), PAN, Indian mobile numbers and e-mail
 * addresses.
 */
public final class SensitiveDataMasker {

    private static final String MASK = "***";

    private static final Pattern BEARER =
            Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{4,}");

    private static final Pattern JWT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]*");

    /**
     * {@code password=x}, {@code "newPassword":"x"}, {@code otp: 123456}, {@code
     * refresh_token=...}. Group 1 keeps the key and separator; the value is replaced.
     *
     * <p>Log lines carry user-supplied text, so every pattern here runs in linear time: a key only
     * starts at a token boundary (the lookbehind) and every quantifier is bounded, so a long word
     * or digit run cannot make the engine backtrack across it (FindSecBugs REDOS).
     */
    private static final Pattern SECRET_KEY_VALUE =
            Pattern.compile(
                    "(?i)((?<![\\w-])[\"']?[\\w-]{0,64}?(?:password|passwd|passphrase|pwd|secret|token|otp|"
                        + "captcha|credential|authorization|api[_-]?key|private[_-]?key|mpin|cvv)"
                        + "[\\w-]{0,64}[\"']?\\s{0,8}[:=]\\s{0,8})(\"[^\"]*\"|'[^']*'|[^\"'\\s,&;}\\]]+)");

    private static final Pattern AADHAAR =
            Pattern.compile("(?<![\\d-])([2-9]\\d{3})[\\s-]?(\\d{4})[\\s-]?(\\d{4})(?![\\d-])");

    private static final Pattern PAN =
            Pattern.compile("\\b[A-Z]{3}[ABCFGHJLPT][A-Z]\\d{4}[A-Z]\\b");

    /**
     * A mobile number standing on its own: no letter, digit or underscore right before or after it
     * (performance run 2026-10-31: the old digit-only boundary masked ten digits inside a
     * correlation id such as {@code 83d42db2-...-7123458460dc}, which broke tracing a request by
     * its id). A number in text, JSON or {@code key=value} still has a non-word neighbour.
     */
    private static final Pattern MOBILE =
            Pattern.compile("(?<![\\w+])(?:\\+91[\\s-]?|0)?([6-9]\\d{5})(\\d{4})(?!\\w)");

    /**
     * Bounded to the e-mail and DNS limits (local part 64, label 63, at most 8 sub-labels, TLD 24):
     * a repeated group is otherwise matched recursively by java.util.regex and a long dotted token
     * overflows the stack.
     */
    private static final Pattern EMAIL =
            Pattern.compile(
                    "\\b([A-Za-z0-9])[A-Za-z0-9._%+-]{0,63}@([A-Za-z0-9])[A-Za-z0-9-]{0,62}"
                            + "((?:\\.[A-Za-z0-9-]{1,63}){0,8}\\.[A-Za-z]{2,24})\\b");

    private static final Pattern OTP_PHRASE =
            Pattern.compile(
                    "(?i)\\b(otp|one[- ]time password|verification code)(\\D{0,12})(\\d{4,8})\\b");

    private SensitiveDataMasker() {}

    /** Words one of which every secret {@code key=value} carries (a superset, case-insensitive). */
    private static final String[] SECRET_WORDS = {
        "passw",
        "passphrase",
        "pwd",
        "secret",
        "token",
        "otp",
        "captcha",
        "credential",
        "authorization",
        "key",
        "mpin",
        "cvv"
    };

    /**
     * Returns {@code text} with every recognised secret or personal-data value masked.
     *
     * <p>Every log line passes here, so each pattern runs only when one character scan shows that
     * it could match (perf note 2026-10-27: the masking was 37 % of the online CPU): no '@', no
     * e-mail; fewer than ten digits, no mobile number; fewer than twelve, no Aadhaar. The checks
     * are necessary conditions of the patterns, so the result is the same as running every pattern.
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Shape shape = Shape.of(text);
        String out = text;
        if (text.contains("eyJ")) {
            out = JWT.matcher(out).replaceAll(MASK + "JWT" + MASK);
        }
        if (containsIgnoreCase(out, "bearer") || containsIgnoreCase(out, "basic")) {
            out = BEARER.matcher(out).replaceAll("$1 " + MASK);
        }
        if (shape.separator() && containsAnyIgnoreCase(out, SECRET_WORDS)) {
            out = SECRET_KEY_VALUE.matcher(out).replaceAll(SensitiveDataMasker::maskSecretValue);
        }
        if (shape.digits() >= 4
                && (containsIgnoreCase(out, "otp")
                        || containsIgnoreCase(out, "one")
                        || containsIgnoreCase(out, "verification code"))) {
            out = OTP_PHRASE.matcher(out).replaceAll("$1$2" + MASK);
        }
        if (shape.digits() >= 12) {
            out = maskAadhaar(out);
        }
        if (shape.digits() >= 4 && shape.upper() >= 6) {
            out = PAN.matcher(out).replaceAll(m -> "XXXXX****" + m.group().charAt(9));
        }
        if (shape.at()) {
            out = EMAIL.matcher(out).replaceAll("$1" + MASK + "@$2" + MASK + "$3");
        }
        if (shape.digits() >= 10) {
            out = MOBILE.matcher(out).replaceAll("******$2");
        }
        return out;
    }

    /** What one scan of the text tells: digits, capitals, an '@', a key separator. */
    record Shape(int digits, int upper, boolean at, boolean separator) {

        static Shape of(String text) {
            int digits = 0;
            int upper = 0;
            boolean at = false;
            boolean separator = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= '0' && c <= '9') {
                    digits++;
                } else if (c >= 'A' && c <= 'Z') {
                    upper++;
                } else if (c == '@') {
                    at = true;
                } else if (c == ':' || c == '=') {
                    separator = true;
                }
            }
            return new Shape(digits, upper, at, separator);
        }
    }

    static boolean containsAnyIgnoreCase(String text, String[] words) {
        for (String w : words) {
            if (containsIgnoreCase(text, w)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code text} contains the lower-case ASCII {@code word}, ignoring case. */
    static boolean containsIgnoreCase(String text, String word) {
        int last = text.length() - word.length();
        for (int i = 0; i <= last; i++) {
            if (text.regionMatches(true, i, word, 0, word.length())) {
                return true;
            }
        }
        return false;
    }

    /** Masks a single e-mail address for display in logs, e.g. {@code r***@f***.gov.in}. */
    public static String maskEmail(String email) {
        return email == null
                ? null
                : EMAIL.matcher(email).replaceAll("$1" + MASK + "@$2" + MASK + "$3");
    }

    private static String maskSecretValue(MatchResult m) {
        String value = m.group(2);
        char first = value.charAt(0);
        String masked = (first == '"' || first == '\'') ? first + MASK + first : MASK;
        return Matcher.quoteReplacement(m.group(1) + masked);
    }

    private static String maskAadhaar(String text) {
        Matcher m = AADHAAR.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String digits = m.group(1) + m.group(2) + m.group(3);
            String replacement = Verhoeff.isValid(digits) ? "XXXX-XXXX-" + m.group(3) : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
