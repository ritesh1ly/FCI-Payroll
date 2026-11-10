package in.gov.fci.payroll.iam.business;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Password rules (ADR-0008; SRS-SEC-13): 12–128 characters with upper-case and lower-case letters,
 * digits and special characters (all four classes by default, US-GAP-13; how many kinds is the A-07
 * setting PASSWORD_CHARACTER_CLASSES, SRS-G-49), no run of four identical characters, no username /
 * employee id / e-mail local part, and not in the common-password list. Reuse and expiry are
 * checked by the application service.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    /** SRS-SEC-13: upper case, lower case, digits and special characters. */
    public static final int CHARACTER_CLASSES = 4;

    private static final int MAX_REPEAT = 3;
    private static final int MIN_IDENTITY_FRAGMENT = 3;

    /** Machine-readable reasons, mirrored by the frontend's Zod schema. */
    public enum Violation {
        TOO_SHORT,
        TOO_LONG,
        LOW_COMPLEXITY,
        REPEATED_CHARACTERS,
        CONTAINS_IDENTITY,
        COMMON_PASSWORD
    }

    private final Set<String> commonPasswords;

    /**
     * @param commonPasswords lower-case common passwords to reject
     */
    public PasswordPolicy(Set<String> commonPasswords) {
        this.commonPasswords = Set.copyOf(commonPasswords);
    }

    /** Unicode NFKC normalisation, applied before both validation and hashing. */
    public static String normalise(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFKC);
    }

    public List<Violation> check(
            String rawPassword, String username, String employeeId, String email) {
        return check(rawPassword, username, employeeId, email, MIN_LENGTH);
    }

    /**
     * As {@link #check(String, String, String, String)} with a configured minimum length (system
     * parameter PASSWORD_MIN_LENGTH), which can only raise the floor of {@link #MIN_LENGTH}.
     */
    public List<Violation> check(
            String rawPassword, String username, String employeeId, String email, int minLength) {
        return check(rawPassword, username, employeeId, email, minLength, CHARACTER_CLASSES);
    }

    /**
     * As {@link #check(String, String, String, String, int)} with the kinds of characters the
     * password must mix (A-07 PASSWORD_CHARACTER_CLASSES, SRS-G-49; see {@link
     * #requiredClasses(int)}).
     */
    public List<Violation> check(
            String rawPassword,
            String username,
            String employeeId,
            String email,
            int minLength,
            int characterClasses) {
        String password = normalise(rawPassword);
        List<Violation> violations = new ArrayList<>();
        int length = password.codePointCount(0, password.length());
        if (length < Math.max(MIN_LENGTH, minLength)) {
            violations.add(Violation.TOO_SHORT);
        }
        if (length > MAX_LENGTH) {
            violations.add(Violation.TOO_LONG);
        }
        if (characterClasses(password) < requiredClasses(characterClasses)) {
            violations.add(Violation.LOW_COMPLEXITY);
        }
        if (hasRepeatedRun(password)) {
            violations.add(Violation.REPEATED_CHARACTERS);
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (containsIdentity(lower, username, employeeId, emailLocalPart(email))) {
            violations.add(Violation.CONTAINS_IDENTITY);
        }
        if (commonPasswords.contains(lower)) {
            violations.add(Violation.COMMON_PASSWORD);
        }
        return violations;
    }

    /**
     * The kinds a password must mix: the setting when it is 1 to 4, otherwise (never a value A-07
     * accepts) all four, the SRS-SEC-13 default, so a broken value never weakens the policy.
     */
    static int requiredClasses(int setting) {
        return setting >= 1 && setting <= CHARACTER_CLASSES ? setting : CHARACTER_CLASSES;
    }

    private static int characterClasses(String password) {
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean symbol = false;
        for (int i = 0; i < password.length(); ) {
            int cp = password.codePointAt(i);
            if (Character.isUpperCase(cp)) {
                upper = true;
            } else if (Character.isLowerCase(cp)) {
                lower = true;
            } else if (Character.isDigit(cp)) {
                digit = true;
            } else if (!Character.isWhitespace(cp)) {
                symbol = true;
            }
            i += Character.charCount(cp);
        }
        return (upper ? 1 : 0) + (lower ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
    }

    private static boolean hasRepeatedRun(String password) {
        int run = 1;
        for (int i = 1; i < password.length(); i++) {
            run = password.charAt(i) == password.charAt(i - 1) ? run + 1 : 1;
            if (run > MAX_REPEAT) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIdentity(String lowerPassword, String... fragments) {
        for (String fragment : fragments) {
            if (fragment != null
                    && fragment.length() >= MIN_IDENTITY_FRAGMENT
                    && lowerPassword.contains(fragment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String emailLocalPart(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
