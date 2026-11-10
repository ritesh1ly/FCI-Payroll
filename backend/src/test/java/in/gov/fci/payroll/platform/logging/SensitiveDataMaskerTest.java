package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

// Measures elapsed time: runs alone in its fork, never beside other test classes.
@Isolated
class SensitiveDataMaskerTest {

    private static final String VALID_AADHAAR_PREFIX = "23456789012";

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '~',
            value = {
                "password=Secr3t!Pass | password=***",
                "newPassword=abc123 | newPassword=***",
                "\"password\":\"two words\" | \"password\":\"***\"",
                "'client_secret': 'xyz' | 'client_secret': '***'",
                "refresh_token=AbC.dEf-123 | refresh_token=***",
                "otp: 123456 | otp: ***",
                "captcha=7XK2PQ | captcha=***",
                "Authorization: Bearer abc.def.ghi | Authorization: *** ***"
            })
    void masksSecretKeyValuePairs(String input, String expected) {
        assertThat(SensitiveDataMasker.mask(input)).isEqualTo(expected);
    }

    @Test
    void masksJwtAnywhereInText() {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1MSJ9.c2lnbmF0dXJl";
        assertThat(SensitiveDataMasker.mask("token " + jwt + " rejected"))
                .isEqualTo("token ***JWT*** rejected")
                .doesNotContain("eyJ");
    }

    @Test
    void masksBearerCredentialWithoutKey() {
        assertThat(SensitiveDataMasker.mask("sent Bearer s3cr3tvalue to api"))
                .isEqualTo("sent Bearer *** to api");
    }

    @Test
    void masksValidAadhaarKeepingLastFourDigits() {
        String aadhaar = VALID_AADHAAR_PREFIX + Verhoeff.checkDigit(VALID_AADHAAR_PREFIX);
        String spaced =
                aadhaar.substring(0, 4)
                        + " "
                        + aadhaar.substring(4, 8)
                        + " "
                        + aadhaar.substring(8);

        assertThat(SensitiveDataMasker.mask("uid " + aadhaar))
                .isEqualTo("uid XXXX-XXXX-" + aadhaar.substring(8));
        assertThat(SensitiveDataMasker.mask("uid " + spaced))
                .isEqualTo("uid XXXX-XXXX-" + aadhaar.substring(8));
    }

    @Test
    void leavesTwelveDigitNumbersThatFailVerhoeffUntouched() {
        String aadhaar = VALID_AADHAAR_PREFIX + Verhoeff.checkDigit(VALID_AADHAAR_PREFIX);
        String invalid = VALID_AADHAAR_PREFIX + ((aadhaar.charAt(11) - '0' + 1) % 10);
        assertThat(SensitiveDataMasker.mask("order " + invalid)).isEqualTo("order " + invalid);
    }

    /**
     * The pre-checks of {@code mask} (perf note 2026-10-27) are necessary conditions: each pattern
     * still applies at the smallest text that can hold its match, in any letter case.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '~',
            value = {
                "ABCPE1234F | XXXXX****F",
                "9876543210 | ******3210",
                "OTP 4829 | OTP ***",
                "One time password 4829 | One time password ***",
                "verification code 4829 | verification code ***",
                "PASSWORD=x | PASSWORD=***",
                "PassPhrase: y | PassPhrase: ***",
                "api-key=k1 | api-key=***",
                "MPIN=1 | MPIN=***",
                "CVV:12 | CVV:***",
                "BASIC abcd1234 | BASIC ***",
                "bearer abcd1234 | bearer ***",
                "a@b.co | a***@b***.co",
                "password is x | password is x"
            })
    void masksAtTheSmallestShapeOfEachPattern(String input, String expected) {
        assertThat(SensitiveDataMasker.mask(input)).isEqualTo(expected);
    }

    @Test
    void findsAWordIgnoringCaseAnywhereButNotPastTheEnd() {
        assertThat(SensitiveDataMasker.containsIgnoreCase("xTOKEN", "token")).isTrue();
        assertThat(SensitiveDataMasker.containsIgnoreCase("TOKE", "token")).isFalse();
        assertThat(SensitiveDataMasker.containsIgnoreCase("token", "token")).isTrue();
        assertThat(
                        SensitiveDataMasker.containsAnyIgnoreCase(
                                "my Secret", new String[] {"pwd", "secret"}))
                .isTrue();
        assertThat(SensitiveDataMasker.containsAnyIgnoreCase("plain", new String[] {"pwd"}))
                .isFalse();
    }

    @Test
    void scansTheShapeOfATextOnce() {
        SensitiveDataMasker.Shape shape = SensitiveDataMasker.Shape.of("Ab9@=:Z0z");
        assertThat(shape.digits()).isEqualTo(2);
        assertThat(shape.upper()).isEqualTo(2);
        assertThat(shape.at()).isTrue();
        assertThat(shape.separator()).isTrue();
        SensitiveDataMasker.Shape plain = SensitiveDataMasker.Shape.of("plain text");
        assertThat(plain.at()).isFalse();
        assertThat(plain.separator()).isFalse();
    }

    @Test
    void masksPanKeepingLastLetter() {
        assertThat(SensitiveDataMasker.mask("PAN ABCPE1234F on file"))
                .isEqualTo("PAN XXXXX****F on file");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "call 9876543210 now | call ******3210 now",
                "call +91 9876543210 now | call ******3210 now",
                "call +91-9876543210 now | call ******3210 now",
                "call 09876543210 now | call ******3210 now"
            })
    void masksIndianMobileNumbers(String input, String expected) {
        assertThat(SensitiveDataMasker.mask(input)).isEqualTo(expected);
    }

    /**
     * R-PII without breaking tracing (performance run 2026-10-31): ten digits inside a token, such
     * as the last group of a correlation id, are not a mobile number; a number next to punctuation
     * is.
     */
    @Test
    void doesNotMaskDigitsInsideACorrelationIdOrAnotherToken() {
        String id = "83d42db2-d214-4bbe-acdd-7123458460dc";
        assertThat(SensitiveDataMasker.mask("\"correlationId\":\"" + id + "\""))
                .isEqualTo("\"correlationId\":\"" + id + "\"");
        assertThat(SensitiveDataMasker.mask("ref ab9876543210")).isEqualTo("ref ab9876543210");
        assertThat(SensitiveDataMasker.mask("ref 9876543210x")).isEqualTo("ref 9876543210x");
        assertThat(SensitiveDataMasker.mask("\"mobile\":\"9876543210\""))
                .isEqualTo("\"mobile\":\"******3210\"");
        assertThat(SensitiveDataMasker.mask("to=9876543210,")).isEqualTo("to=******3210,");
    }

    @Test
    void doesNotMaskLongerDigitRunsOrNonMobilePrefixes() {
        assertThat(SensitiveDataMasker.mask("ts=1728912345678")).isEqualTo("ts=1728912345678");
        assertThat(SensitiveDataMasker.mask("id 1234567890")).isEqualTo("id 1234567890");
    }

    @Test
    void masksEmailAddresses() {
        assertThat(SensitiveDataMasker.mask("mail ritesh.k@fci.gov.in sent"))
                .isEqualTo("mail r***@f***.gov.in sent");
        assertThat(SensitiveDataMaskerTest.maskEmail("a@b.co")).isEqualTo("a***@b***.co");
    }

    @Test
    void masksOtpMentionedInProse() {
        assertThat(SensitiveDataMasker.mask("Your OTP is 482913")).isEqualTo("Your OTP is ***");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        String text = "User USR-1001 updated office RO-LKO at 2026-10-14T10:15:30Z";
        assertThat(SensitiveDataMasker.mask(text)).isEqualTo(text);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void handlesNullAndEmpty(String input) {
        assertThat(SensitiveDataMasker.mask(input)).isEqualTo(input);
    }

    private static String maskEmail(String email) {
        return SensitiveDataMasker.maskEmail(email);
    }

    /**
     * Log lines carry user-supplied text (reasons, search terms), so the masker must not be a ReDoS
     * vector (FindSecBugs REDOS; before the fix a long dotted token overflowed the stack and a long
     * word took seconds). Each adversarial shape is masked in linear time: four times the input
     * takes well under sixteen times as long (a quadratic pattern would take ~16x), and 100 kB
     * stays far below a second of work even on a loaded build machine.
     */
    @ParameterizedTest
    @CsvSource({"dots", "local", "ats", "digits", "mixed"})
    void adversarialInputIsMaskedInLinearTime(String shape) {
        SensitiveDataMasker.mask(adversarial(shape, 2_000)); // warm up the JIT
        // Small and large inputs are timed alternately in this thread's CPU time (not wall
        // time: a loaded build machine makes a thread wait for a CPU, which is not the masker's
        // work) and the fastest run of each kept: a quadratic pattern still shows ~16x for 4x the
        // input, a linear one ~4x. The 500 ms floor is far below the seconds the super-linear
        // patterns took on these inputs.
        long[] fastest = fastest(adversarial(shape, 6_250), adversarial(shape, 25_000));
        long small = fastest[0];
        long large = fastest[1];
        assertThat(large).as("4x the input").isLessThan(Math.max(10 * small, 500_000_000L));
        assertTimeoutPreemptively(
                Duration.ofSeconds(10), () -> SensitiveDataMasker.mask(adversarial(shape, 25_000)));
    }

    private static long[] fastest(String small, String large) {
        long[] best = {Long.MAX_VALUE, Long.MAX_VALUE};
        for (int i = 0; i < 5; i++) {
            best[0] = Math.min(best[0], timeNanos(small));
            best[1] = Math.min(best[1], timeNanos(large));
        }
        return best;
    }

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    /** CPU time of this thread for one masking (wall time where the JVM cannot measure it). */
    private static long timeNanos(String input) {
        boolean cpu = THREADS.isCurrentThreadCpuTimeSupported();
        long start = cpu ? THREADS.getCurrentThreadCpuTime() : System.nanoTime();
        SensitiveDataMasker.mask(input);
        return (cpu ? THREADS.getCurrentThreadCpuTime() : System.nanoTime()) - start;
    }

    private static String adversarial(String shape, int n) {
        return switch (shape) {
            case "dots" -> "a@b" + ".a1".repeat(n) + "!";
            case "local" -> "a".repeat(4 * n) + "@";
            case "ats" -> "a@".repeat(2 * n);
            case "digits" -> "9".repeat(4 * n);
            default -> "x@y.".repeat(n) + "password=" + "q".repeat(n);
        };
    }
}
