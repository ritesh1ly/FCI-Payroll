package in.gov.fci.payroll.eseva.business;

import in.gov.fci.payroll.platform.logging.SensitiveDataMasker;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Rules of the e-mailed member documents (OD-148; SRS-17-1, SRS-17-12, SRS-INT-16; PENDING MAIL-1,
 * MAIL-2). Pure functions: the retry schedule from the MEMBER_EMAIL interface settings (R-INT), the
 * PDF password from the configured rule (never a coded rule: off until FCI decides), the failure
 * text as stored.
 */
public final class DocumentMailRules {

    /** DDL {@code notification.delivery_detail}. */
    public static final int DETAIL_MAX = 1000;

    /** The longest back-off exponent (2^16 x the base), so a long outage cannot overflow. */
    private static final int MAX_EXPONENT = 16;

    /** One part of a password rule: plain text ({@code name} null) or a token with its argument. */
    private record Part(String name, String argument, String text) {}

    private DocumentMailRules() {}

    /**
     * SRS-INT-16: the wait before the attempt after {@code attempt} failed ones, by the interface's
     * back-off strategy ({@code FIXED}, {@code LINEAR}, {@code EXPONENTIAL}; DDL {@code
     * interface_config.backoff_strategy}).
     */
    public static long backoffSeconds(String strategy, int backoffSeconds, int attempt) {
        if (attempt < 1 || backoffSeconds < 0) {
            throw new IllegalArgumentException(
                    "attempt " + attempt + ", back-off " + backoffSeconds);
        }
        return switch (strategy) {
            case "FIXED" -> backoffSeconds;
            case "LINEAR" -> (long) backoffSeconds * attempt;
            case "EXPONENTIAL" -> (long) backoffSeconds << Math.min(attempt - 1, MAX_EXPONENT);
            default -> throw new IllegalArgumentException("back-off strategy " + strategy);
        };
    }

    /**
     * SRS-INT-16 / MAIL-2: the back-off of {@link #backoffSeconds} stretched by up to {@code
     * jitterPct} per cent ({@code random} in [0, 1)), so e-mails that failed together in an outage
     * do not all come back in the same second.
     */
    public static long retryDelaySeconds(
            String strategy, int backoffSeconds, int attempt, int jitterPct, double random) {
        long base = backoffSeconds(strategy, backoffSeconds, attempt);
        return base + (long) Math.floor(base * (jitterPct / 100.0) * random);
    }

    /** RFC 5321: the longest address a mail server must accept. */
    public static final int ADDRESS_MAX = 254;

    /** Characters of the local part (before the {@code @}) besides letters and digits. */
    private static final String LOCAL = "._%+'-";

    /**
     * SRS l.3763 (Alphanumeric (100), HRMS-sourced): whether the registered address can be sent to:
     * printable ASCII (R-ASCII), at most {@link #ADDRESS_MAX} characters, one {@code @}, a dotted
     * domain, no blank. Anything else is failed as an invalid address, never sent.
     */
    public static boolean validAddress(String address) {
        // One pass over at most ADDRESS_MAX characters: linear, no regular expression.
        if (address == null || address.length() > ADDRESS_MAX || address.contains("..")) {
            return false;
        }
        int at = address.indexOf('@');
        if (at < 1 || at > 64 || address.indexOf('@', at + 1) >= 0) {
            return false;
        }
        for (int i = 0; i < at; i++) {
            char c = address.charAt(i);
            if (!alphanumeric(c) && LOCAL.indexOf(c) < 0) {
                return false;
            }
        }
        int labels = 0;
        int length = 0;
        for (int i = at + 1; i <= address.length(); i++) {
            char c = i == address.length() ? '.' : address.charAt(i);
            if (c == '.') {
                if (length < 1 || length > 63) {
                    return false;
                }
                labels++;
                length = 0;
            } else if (alphanumeric(c) || c == '-') {
                length++;
            } else {
                return false;
            }
        }
        return labels >= 2;
    }

    private static boolean alphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean printableAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * The parts of {@code rule} in order, or null when it is malformed: a brace without its pair,
     * an unknown token, an empty argument. One pass, linear in the rule's length.
     */
    private static List<Part> parts(String rule) {
        List<Part> parts = new ArrayList<>();
        int i = 0;
        while (i < rule.length()) {
            int open = rule.indexOf('{', i);
            int close = rule.indexOf('}', i);
            if (open < 0) {
                if (close >= 0) {
                    return null;
                }
                parts.add(new Part(null, null, rule.substring(i)));
                break;
            }
            if (close >= 0 && close < open) {
                return null;
            }
            if (open > i) {
                parts.add(new Part(null, null, rule.substring(i, open)));
            }
            if (close < 0) {
                return null;
            }
            String token = rule.substring(open + 1, close);
            if (token.indexOf('{') >= 0) {
                return null;
            }
            int colon = token.indexOf(':');
            String name = colon < 0 ? token : token.substring(0, colon);
            String argument = colon < 0 ? null : token.substring(colon + 1);
            if (!("pan".equals(name) || "eis".equals(name) || "dob".equals(name))
                    || (argument != null && argument.isEmpty())) {
                return null;
            }
            parts.add(new Part(name, argument, null));
            i = close + 1;
        }
        return parts;
    }

    /**
     * SRS-17-12 / MAIL-2: whether an e-mail that failed {@code attempts} times is given up: the
     * first attempt and {@code retryCount} retries are allowed.
     */
    public static boolean givenUp(int attempts, int retryCount) {
        return attempts > retryCount;
    }

    /**
     * MAIL-1: whether a password rule is usable: at least one token, only the known tokens ({pan},
     * {pan:N} with N from 1 to 10, {eis}, {dob:PATTERN} with a valid date pattern) and printable
     * ASCII text around them.
     */
    public static boolean validRule(String rule) {
        if (rule == null || rule.isBlank() || !printableAscii(rule)) {
            return false;
        }
        List<Part> parts = parts(rule);
        if (parts == null) {
            return false;
        }
        int tokens = 0;
        for (Part p : parts) {
            if (p.name() != null) {
                if (!validToken(p.name(), p.argument())) {
                    return false;
                }
                tokens++;
            }
        }
        return tokens > 0;
    }

    private static boolean validToken(String name, String argument) {
        return switch (name) {
            case "pan" ->
                    argument == null
                            || "10".equals(argument)
                            || (argument.length() == 1
                                    && argument.charAt(0) >= '1'
                                    && argument.charAt(0) <= '9');
            case "eis" -> argument == null;
            default -> argument != null && datePattern(argument);
        };
    }

    private static boolean datePattern(String pattern) {
        // Day, month and year letters with plain separators only (no quoted text, no time).
        if (pattern.length() > 20) {
            return false;
        }
        boolean field = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            boolean letter = c == 'd' || c == 'M' || c == 'y' || c == 'u';
            if (!letter && "./ -".indexOf(c) < 0) {
                return false;
            }
            field |= letter;
        }
        if (!field) {
            return false;
        }
        try {
            DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(LocalDate.of(2000, 1, 1));
            return true;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    /**
     * MAIL-1: the member's PDF password by {@code rule} (see {@link #validRule}); empty when the
     * rule is not usable or names a value the member's record does not hold (no PAN, no birth
     * date), so the e-mail is not sent unprotected.
     */
    public static Optional<String> password(
            String rule, String pan, LocalDate birthDate, String eis) {
        if (!validRule(rule)) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        for (Part p : parts(rule)) {
            if (p.name() == null) {
                out.append(p.text());
                continue;
            }
            String value = value(p.name(), p.argument(), pan, birthDate, eis);
            if (value == null) {
                return Optional.empty();
            }
            out.append(value);
        }
        return Optional.of(out.toString());
    }

    private static String value(
            String name, String argument, String pan, LocalDate birthDate, String eis) {
        return switch (name) {
            case "pan" -> {
                String p = pan == null ? "" : pan.strip().toUpperCase(Locale.ROOT);
                int n = argument == null ? p.length() : Integer.parseInt(argument);
                yield p.length() < Math.max(n, 1) ? null : p.substring(0, n);
            }
            case "eis" -> eis == null || eis.isBlank() ? null : eis.strip();
            default ->
                    birthDate == null
                            ? null
                            : DateTimeFormatter.ofPattern(argument, Locale.ROOT).format(birthDate);
        };
    }

    /**
     * The failure as stored: its kind and message with personal data masked (a server's refusal may
     * quote the address, R-PII), printable ASCII, cut to the column.
     */
    public static String detail(String prefix, Throwable failure) {
        String message = failure.getMessage();
        String text =
                prefix
                        + failure.getClass().getSimpleName()
                        + (message == null || message.isBlank() ? "" : ": " + message);
        String printable =
                SensitiveDataMasker.maskEmail(text).replaceAll("[^\\x20-\\x7E]", " ").strip();
        return printable.substring(0, Math.min(DETAIL_MAX, printable.length()));
    }
}
