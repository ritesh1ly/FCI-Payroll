package in.gov.fci.payroll.integration.business;

import in.gov.fci.payroll.platform.security.InputPatterns;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rules of the integration layer (plan 23; SRS-INT-6, SRS-INT-12, SRS-INT-31, SRS-INT-32). Pure
 * functions: no state, no I/O.
 */
public final class IntegrationRules {

    /** X-03 window status (SRS-INT-32). */
    public static final String OK = "OK";

    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";

    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]*$");
    private static final int MAX_EXPONENT = 20;

    private IntegrationRules() {}

    /**
     * SRS-INT-32: a window whose counts do not balance (received = accepted + rejected) is Failed;
     * one with rejections is Partial; otherwise OK.
     */
    public static String windowStatus(long received, long accepted, long rejected) {
        if (received < 0 || accepted < 0 || rejected < 0 || received != accepted + rejected) {
            return FAILED;
        }
        return rejected > 0 ? PARTIAL : OK;
    }

    /**
     * SRS-INT-6: the wait before retry {@code attempt} (1 = the first retry): FIXED waits the
     * back-off each time, LINEAR the back-off times the attempt, EXPONENTIAL doubles it each time
     * (capped so it never overflows).
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

    /** SRS-INT-6: the breaker opens when a required breaker reaches its failure threshold. */
    public static boolean opens(
            boolean breakerRequired, Integer threshold, int consecutiveFailures) {
        return breakerRequired && threshold != null && consecutiveFailures >= threshold;
    }

    /** SRS-INT-31 / SRS-INT-33: the Administrator's reason, mandatory, printable ASCII, 1000. */
    public static Optional<String> reasonDefect(String reason) {
        if (reason == null || reason.isBlank()) {
            return Optional.of("REASON");
        }
        String r = reason.trim();
        return r.length() > 1000 || !InputPatterns.isPrintableText(r)
                ? Optional.of("REASON")
                : Optional.empty();
    }

    /**
     * SRS-INT-12: every inbound record carries a source key (up to 100 printable characters), a
     * transaction type and its payload.
     */
    public static Optional<String> recordDefect(String txnType, String requestId, String payload) {
        if (txnType == null || txnType.isBlank() || txnType.length() > 40) {
            return Optional.of("TXN_TYPE");
        }
        if (requestId == null
                || requestId.isBlank()
                || requestId.length() > 100
                || !PRINTABLE.matcher(requestId).matches()) {
            return Optional.of("SOURCE_KEY");
        }
        return payload == null || payload.isEmpty() ? Optional.of("PAYLOAD") : Optional.empty();
    }

    /** DDL interface_exception.rejection_reason (2000) and staging error_detail (2000). */
    public static String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
