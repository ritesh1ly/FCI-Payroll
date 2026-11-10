package in.gov.fci.payroll.general.business;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;

/**
 * One line of the HRMS Fallback Upload Reconciliation (SRS-G-47, Annexure A.11, BR-INT-011; DDL
 * hrms_fallback_upload_line): what was uploaded, what HRMS holds after it is restored, and the size
 * of the difference. The DDL keeps the HRMS side as one text, {@code hrms_value_summary}; a line
 * compares one item, written {@code <ITEM>: <value>} (for example {@code LWP_DAYS: 3}; an event
 * line may hold the date alone). Nothing is decided here: the status and the arrear or recovery
 * come stored with the line (recon_status, recon_effect), this only lays the line out for X-07.
 */
public final class FallbackReconciliation {

    /** The item of a line that compares nothing in particular ("All items" on the screen). */
    public static final String ALL_ITEMS = "ALL";

    private static final String ATTENDANCE = "ATTENDANCE_LEAVE";
    private static final Map<String, Function<Facts, BigDecimal>> DAY_ITEMS =
            Map.of(
                    "PAY_DAYS", Facts::payDays,
                    "LWP_DAYS", Facts::lwpDays,
                    "HALF_PAY_DAYS", Facts::halfPayDays,
                    "OTHER_LEAVE_DAYS", Facts::otherLeaveDays);

    /** The stored line. */
    public record Facts(
            String recordType,
            String hrmsSummary,
            BigDecimal payDays,
            BigDecimal lwpDays,
            BigDecimal halfPayDays,
            BigDecimal otherLeaveDays,
            LocalDate eventDate) {}

    /**
     * The compared item; the uploaded and HRMS values as text; the size of a difference in days.
     */
    public record Comparison(String item, String uploaded, String hrms, String difference) {}

    private FallbackReconciliation() {}

    /** Lays one line out; a line HRMS does not hold has no HRMS value. */
    public static Comparison compare(Facts line) {
        if (!ATTENDANCE.equals(line.recordType())) {
            return new Comparison(
                    line.recordType(),
                    line.eventDate() == null ? null : line.eventDate().toString(),
                    valueOf(line.hrmsSummary()),
                    null);
        }
        String item = itemOf(line.hrmsSummary());
        Function<Facts, BigDecimal> uploaded = item == null ? null : DAY_ITEMS.get(item);
        if (uploaded == null) {
            return new Comparison(ALL_ITEMS, null, valueOf(line.hrmsSummary()), null);
        }
        BigDecimal mine = uploaded.apply(line);
        BigDecimal theirs = number(valueOf(line.hrmsSummary()));
        return new Comparison(
                item,
                plain(mine),
                plain(theirs),
                mine == null || theirs == null ? null : plain(mine.subtract(theirs).abs()));
    }

    private static String itemOf(String summary) {
        if (summary == null) {
            return null;
        }
        int colon = summary.indexOf(':');
        return colon < 0 ? null : summary.substring(0, colon).trim();
    }

    private static String valueOf(String summary) {
        return summary == null ? null : summary.substring(summary.indexOf(':') + 1).trim();
    }

    private static BigDecimal number(String text) {
        try {
            return text == null ? null : new BigDecimal(text);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
