package in.gov.fci.payroll.eseva.business;

import in.gov.fci.payroll.platform.logging.PiiMasks;
import in.gov.fci.payroll.platform.security.InputPatterns;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents.Status;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * eSeva rules (plan 14; SRS-17-3, SRS-17-6, SRS-17-8, SRS-17-10, SRS-17-18; R-ASCII, R-PII). Pure
 * functions: no state, no I/O.
 */
public final class EsevaRules {

    /** DDL {@code ess_submission.submitted_value}. */
    public static final int VALUE_MAX = 1000;

    /** DDL {@code ess_session.access_reason}. */
    public static final int REASON_MAX = 500;

    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]*$");

    private EsevaRules() {}

    /**
     * SRS-17-3: a log row moves forward only. SUBMITTED may become anything; ROUTED may become
     * ACTIONED or REJECTED; ACTIONED and REJECTED are final (a resubmission is a new row).
     */
    public static boolean moves(Status from, Status to) {
        return switch (from) {
            case SUBMITTED -> to != Status.SUBMITTED;
            case ROUTED -> to == Status.ACTIONED || to == Status.REJECTED;
            case ACTIONED, REJECTED -> false;
        };
    }

    /** ACTIONED and REJECTED are final. */
    public static boolean isFinal(Status status) {
        return status == Status.ACTIONED || status == Status.REJECTED;
    }

    /**
     * R-ASCII: the summary as stored - non-printable characters replaced by a blank, cut to {@link
     * #VALUE_MAX}; null stays null.
     */
    public static String value(String text) {
        if (text == null) {
            return null;
        }
        String printable = text.replaceAll("[^\\x20-\\x7E]", " ").strip();
        return printable.substring(0, Math.min(VALUE_MAX, printable.length()));
    }

    /** DDL {@code notification.message}. */
    public static final int MESSAGE_MAX = 2000;

    /**
     * SRS-17-12 (D14-8): FCI's template text with its named placeholders ({@code {name}}) filled; a
     * placeholder without a value stays as written, and a null template is an empty text.
     */
    public static String render(String template, java.util.Map<String, String> values) {
        if (template == null) {
            return "";
        }
        String out = template;
        for (java.util.Map.Entry<String, String> v : values.entrySet()) {
            out = out.replace("{" + v.getKey() + "}", v.getValue() == null ? "" : v.getValue());
        }
        return out;
    }

    /** DDL {@code notification.message}: cut to {@link #MESSAGE_MAX}. */
    public static String message(String text) {
        return text == null ? null : text.substring(0, Math.min(MESSAGE_MAX, text.length()));
    }

    /** SRS-17-8 / D14-5: the reason of a support preview, or empty when it is not acceptable. */
    public static Optional<String> reason(String text) {
        String s = text == null ? "" : text.strip();
        if (s.isEmpty() || s.length() > REASON_MAX || !InputPatterns.isPrintableText(s)) {
            return Optional.empty();
        }
        return Optional.of(s);
    }

    /**
     * SRS-17-10 / R-PII (BR-SEC-001, SRS l.754, l.802; BMAD TR-035): PAN by its last 4 characters
     * only ({@code ******234D}), through the one platform mask.
     */
    public static String maskPan(String pan) {
        return PiiMasks.pan(pan);
    }

    /** SRS-17-10 / R-PII: an account or Aadhaar number by its last four digits only. */
    public static String maskTail(String number) {
        return PiiMasks.lastFour(number);
    }

    /** R-PII: a mobile number by its last two digits only. */
    public static String maskMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            return null;
        }
        String s = mobile.strip();
        return s.length() <= 2 ? "**" : "*".repeat(s.length() - 2) + s.substring(s.length() - 2);
    }
}
