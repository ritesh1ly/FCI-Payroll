package in.gov.fci.payroll.labour.business;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Work-slip rules (plan 20 phase 20c; SRS-2-4, SRS-2-5, SRS-2-42, SRS-3-9; D20-9). Pure functions:
 * no state, no I/O.
 */
public final class WorkSlipRules {

    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** DDL {@code work_slip_row.pbnw_reason}. */
    public static final Set<String> PBNW_REASONS =
            Set.of("RAKE_DELAYED", "RAKE_NOT_PLACED", "OTHER");

    /** Bags are counted up to five digits (I-02 field table). */
    public static final int MAX_BAGS = 99_999;

    /** One row of work as entered, with the facts of its operation (M-27). */
    public record Row(
            boolean bagBased,
            boolean presentButNoWork,
            Long bagTypeId,
            Integer bags,
            Integer stackHeight,
            String start,
            String end,
            String pbnwReason,
            String pbnwText) {}

    /** The Departmental duplicate key of a row (SRS-3-9): operation, norm, slab and time frame. */
    public record DuplicateKey(
            long operationId, String normType, String heightBand, String start, String end) {}

    private WorkSlipRules() {}

    /** HH:MM, 00:00 to 23:59. */
    public static boolean validTime(String value) {
        return value != null && TIME.matcher(value).matches();
    }

    /**
     * SRS-2-4 / SRS-2-42: what is wrong with a row: TIME (not HH:MM), TIME_ORDER (the end is not
     * after the start, unless the work continues past midnight), BAGS (a bag-based operation needs
     * a bag type and 1 to 99999 bags; any other operation has no bag fields), STACK (a stack height
     * is 1 or more, on a bag-based operation only), PBNW_REASON (Present But No Work needs its
     * reason, and the text when the reason is OTHER).
     */
    public static Optional<String> rowDefect(Row r, boolean continueWork) {
        if (!validTime(r.start()) || !validTime(r.end())) {
            return Optional.of("TIME");
        }
        if (!continueWork && r.end().compareTo(r.start()) <= 0) {
            return Optional.of("TIME_ORDER");
        }
        if (r.bagBased()) {
            if (r.bagTypeId() == null || r.bags() == null || r.bags() < 1 || r.bags() > MAX_BAGS) {
                return Optional.of("BAGS");
            }
            if (r.stackHeight() != null && r.stackHeight() < 1) {
                return Optional.of("STACK");
            }
        } else if (r.bagTypeId() != null || r.bags() != null || r.stackHeight() != null) {
            return Optional.of("BAGS");
        }
        if (r.presentButNoWork()) {
            if (r.pbnwReason() == null || !PBNW_REASONS.contains(r.pbnwReason())) {
                return Optional.of("PBNW_REASON");
            }
            if ("OTHER".equals(r.pbnwReason())
                    && (r.pbnwText() == null || r.pbnwText().isBlank())) {
                return Optional.of("PBNW_REASON");
            }
        } else if (r.pbnwReason() != null) {
            return Optional.of("PBNW_REASON");
        }
        return Optional.empty();
    }

    /**
     * SRS-2-5 (mandatory acceptance test): a stack height above 20 is Above-20, never Up-to-20; no
     * height, no band.
     */
    public static String heightBand(Integer stackHeight) {
        if (stackHeight == null) {
            return null;
        }
        return stackHeight > 20 ? "ABOVE_20" : "UPTO_20";
    }

    /** SRS-3-9: two Departmental rows are duplicates when their keys are equal. */
    public static boolean duplicate(DuplicateKey a, DuplicateKey b) {
        return a.operationId() == b.operationId()
                && Objects.equals(a.normType(), b.normType())
                && Objects.equals(a.heightBand(), b.heightBand())
                && a.start().equals(b.start())
                && a.end().equals(b.end());
    }

    /**
     * Each payroll has its own duplicate test (SRS I-02 ▸ DPS): Departmental rows repeat the
     * operation, norm, slab and time frame (SRS-3-9); a DPS booking-slip repeats the gang, date and
     * operation (SRS-2-34, D21-3), which covers the DPS test of operation and time frame (SRS-4-7).
     */
    public static boolean duplicate(String payrollType, DuplicateKey a, DuplicateKey b) {
        if (DPS.equals(payrollType)) {
            return a.operationId() == b.operationId();
        }
        if (NWNP.equals(payrollType)) {
            // SRS-5-8: the Departmental key, or a time frame within one already entered.
            return duplicate(a, b) || within(a, b) || within(b, a);
        }
        return duplicate(a, b);
    }

    /** SRS-5-8: the time frame of {@code inner} lies within that of {@code outer}. */
    static boolean within(DuplicateKey inner, DuplicateKey outer) {
        int[] i = span(inner.start(), inner.end());
        int[] o = span(outer.start(), outer.end());
        return i[0] >= o[0] && i[1] <= o[1];
    }

    /** Minutes from midnight; work past midnight ends on the next day. */
    private static int[] span(String start, String end) {
        int s = minutes(start);
        int e = minutes(end);
        return new int[] {s, e < s ? e + 24 * 60 : e};
    }

    private static int minutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    /** The NWNP payroll type. */
    public static final String NWNP = "NWNP_LABOUR";

    /** The DPS payroll type. */
    public static final String DPS = "DPS_LABOUR";

    /** D20-9: the work-slip number: WS-gang-date-sequence (at most 30 characters). */
    public static String number(long gangId, LocalDate date, int sequence) {
        return "WS-" + gangId + "-" + date.format(DAY) + "-" + sequence;
    }
}
