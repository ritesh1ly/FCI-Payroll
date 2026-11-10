package in.gov.fci.payroll.reporting.business;

import in.gov.fci.payroll.platform.security.InputPatterns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Report engine rules (plan 15; SRS-18-5, SRS-18-8, SRS-18-10, SRS-18-23, SRS-18-24, SRS-18-28,
 * SRS-G-12; BR-MIS-003, BR-MIS-004, BR-MIS-007). Pure functions: no state, no I/O.
 */
public final class ReportRules {

    /** The screen that produces the reports of this module (D15-1). */
    public static final String HERE = "O-04";

    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]*$");

    private ReportRules() {}

    /** What O-04 shows for a catalogue entry. */
    public enum Status {
        /** Produced on O-04. */
        AVAILABLE,
        /** Produced on another screen (D15-3). */
        PRODUCED_ELSEWHERE,
        /** SRS-18-24: not generated; the reason and the stage required are shown. */
        BLOCKED
    }

    /** SRS-18-28 / MI-15: the hierarchy levels of the Component-wise Summary. */
    public enum Level {
        UNIT,
        REGION,
        ZONE,
        ALL
    }

    /**
     * SRS-18-24: a blocked version is Blocked; otherwise the entry is produced here or on its own
     * screen. An entry without an approved version in force is Blocked too.
     */
    public static Status status(boolean hasVersion, boolean blocked, String producingScreen) {
        if (!hasVersion || blocked) {
            return Status.BLOCKED;
        }
        return HERE.equals(producingScreen) ? Status.AVAILABLE : Status.PRODUCED_ELSEWHERE;
    }

    /**
     * SRS-18-23 / BR-MIS-007: an output is final only when every cycle it reads is locked;
     * otherwise it is provisional and marked so on every page.
     */
    public static boolean provisional(long cycles, long locked) {
        return cycles == 0 || locked < cycles;
    }

    /**
     * FRS V0.5 l.7130: a pre-lock (provisional) operational report needs every cycle it reads
     * verified (VERIFIED or LOCKED); a month with no cycle has nothing to verify.
     */
    public static boolean verified(long cycles, long verified) {
        return verified >= cycles;
    }

    /** SRS-18-8: the control total matches the posted payroll within the tolerance. */
    public static boolean matches(BigDecimal reported, BigDecimal posted, BigDecimal tolerance) {
        BigDecimal r = reported == null ? BigDecimal.ZERO : reported;
        BigDecimal p = posted == null ? BigDecimal.ZERO : posted;
        BigDecimal t = tolerance == null ? BigDecimal.ZERO : tolerance.abs();
        return r.subtract(p).abs().compareTo(t) <= 0;
    }

    /**
     * SRS-18-10 / BR-MIS-003 (M-12): what is wrong with a proposed version: FORMAT (not one of the
     * DDL formats), EFFECTIVE (missing or before today), REASON (blocked without a printable reason
     * of at most 500 characters), STORE (not STORE or REGENERATE).
     */
    public static Optional<String> versionDefect(
            String format,
            LocalDate effectiveFrom,
            LocalDate today,
            boolean blocked,
            String reason,
            String storeMode) {
        if (format != null
                && !java.util.Set.of("PDF", "EXCEL", "CSV", "PRESCRIBED_FILE", "HTML")
                        .contains(format)) {
            return Optional.of("FORMAT");
        }
        if (effectiveFrom == null || effectiveFrom.isBefore(today)) {
            return Optional.of("EFFECTIVE");
        }
        String why = reason == null ? "" : reason.strip();
        if (blocked
                && (why.isEmpty() || why.length() > 500 || !InputPatterns.isPrintableText(why))) {
            return Optional.of("REASON");
        }
        if (!"STORE".equals(storeMode) && !"REGENERATE".equals(storeMode)) {
            return Optional.of("STORE");
        }
        return Optional.empty();
    }

    /** YYYYMM, if it is a month. */
    public static boolean isYymm(String yymm) {
        if (yymm == null || !yymm.matches("\\d{6}")) {
            return false;
        }
        int month = Integer.parseInt(yymm.substring(4));
        return month >= 1 && month <= 12;
    }
}
