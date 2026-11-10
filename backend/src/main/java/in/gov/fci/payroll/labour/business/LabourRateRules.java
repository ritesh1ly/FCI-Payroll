package in.gov.fci.payroll.labour.business;

import in.gov.fci.payroll.platform.security.InputPatterns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * M-05 Labour Rate Tables rules (plan 21 phase 21c; SRS-4-11, SRS-4-2, M-05 field table; DDL {@code
 * labour_min_wage}): a rate set holds the Minimum Guaranteed Wage day rate of every designation of
 * the payroll type, each mandatory; the Interim Relief, the GoI rate with its source and the
 * comparison grain are optional settings (left empty, the computation refuses); the set starts
 * after the latest set's date. Pure functions: no state, no I/O.
 */
public final class LabourRateRules {

    /** DDL NUMBER(12,2). */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    /** DDL {@code labour_min_wage.comparison_grain}. */
    public static final Set<String> GRAINS = Set.of("PER_DAY", "PER_PERIOD", "PER_LABOUR");

    /**
     * One designation's rates as entered: the Minimum Guaranteed Wage day rate (DPS), or the Basic
     * wage and DA (Departmental, SRS-3-13; DDL {@code basic_wage}, {@code vda}).
     */
    public record Rate(Long designationId, BigDecimal mgw, BigDecimal basicWage, BigDecimal da) {

        /** A DPS day rate. */
        public Rate(Long designationId, BigDecimal mgw) {
            this(designationId, mgw, null, null);
        }
    }

    /** The settings of a set besides the day rates. */
    public record Settings(
            BigDecimal interimRelief, BigDecimal goiRate, String goiRateSource, String grain) {}

    private LabourRateRules() {}

    /**
     * The first defect of a proposed set, or empty: RATES (a designation of the payroll type
     * without its rate), DESIGNATION (one not of the payroll type, or twice), MGW, INTERIM_RELIEF,
     * GOI_RATE, GOI_SOURCE (a GoI rate needs its source; 1 to 200 printable characters), GRAIN,
     * REASON (1 to 500 printable characters).
     */
    public static Optional<String> defect(
            List<Rate> rates, Set<Long> designations, Settings s, String reason) {
        if (rates == null || rates.isEmpty() || designations.isEmpty()) {
            return Optional.of("RATES");
        }
        Set<Long> seen = new HashSet<>();
        for (Rate r : rates) {
            if (r == null
                    || r.designationId() == null
                    || !designations.contains(r.designationId())
                    || !seen.add(r.designationId())) {
                return Optional.of("DESIGNATION");
            }
            if (!amount(r.mgw(), false)) {
                return Optional.of("MGW");
            }
        }
        if (!seen.equals(designations)) {
            return Optional.of("RATES");
        }
        if (s == null) {
            return Optional.of("SETTINGS");
        }
        if (s.interimRelief() != null && !amount(s.interimRelief(), true)) {
            return Optional.of("INTERIM_RELIEF");
        }
        if (s.goiRate() != null && !amount(s.goiRate(), false)) {
            return Optional.of("GOI_RATE");
        }
        boolean source = multiLine(s.goiRateSource(), 200);
        if ((s.goiRate() != null && !source)
                || (s.goiRateSource() != null && !s.goiRateSource().isBlank() && !source)) {
            return Optional.of("GOI_SOURCE");
        }
        if (s.grain() != null && !GRAINS.contains(s.grain())) {
            return Optional.of("GRAIN");
        }
        if (!multiLine(reason, 500)) {
            return Optional.of("REASON");
        }
        return Optional.empty();
    }

    /**
     * SRS-3-13 (US-GAP-61): the first defect of a proposed Departmental Labour Basic wage and DA
     * set, or empty: RATES (a designation of the payroll type without its rates), DESIGNATION (one
     * not of the payroll type, or twice), BASIC_WAGE (positive), DA (zero or more), SETTINGS (the
     * Minimum Guaranteed Wage, Interim Relief, GoI rate and comparison grain are DPS settings, not
     * part of a Departmental set), REASON (1 to 500 printable characters).
     */
    public static Optional<String> deptDefect(
            List<Rate> rates, Set<Long> designations, Settings s, String reason) {
        if (rates == null || rates.isEmpty() || designations.isEmpty()) {
            return Optional.of("RATES");
        }
        Set<Long> seen = new HashSet<>();
        for (Rate r : rates) {
            if (r == null
                    || r.designationId() == null
                    || !designations.contains(r.designationId())
                    || !seen.add(r.designationId())) {
                return Optional.of("DESIGNATION");
            }
            if (!amount(r.basicWage(), false)) {
                return Optional.of("BASIC_WAGE");
            }
            if (!amount(r.da(), true)) {
                return Optional.of("DA");
            }
            if (r.mgw() != null) {
                return Optional.of("SETTINGS");
            }
        }
        if (!seen.equals(designations)) {
            return Optional.of("RATES");
        }
        if (s != null
                && (s.interimRelief() != null
                        || s.goiRate() != null
                        || (s.goiRateSource() != null && !s.goiRateSource().isBlank())
                        || s.grain() != null)) {
            return Optional.of("SETTINGS");
        }
        if (!multiLine(reason, 500)) {
            return Optional.of("REASON");
        }
        return Optional.empty();
    }

    /**
     * M-05: a new set starts on a date after the latest set of the payroll type and depot (the DDL
     * key holds one set a day); the date may be in the past (a retrospective revision).
     */
    public static Optional<String> effectiveDefect(LocalDate from, LocalDate latest) {
        if (from == null) {
            return Optional.of("EFFECTIVE_FROM");
        }
        if (latest != null && !from.isAfter(latest)) {
            return Optional.of("EFFECTIVE_BEFORE_LATEST");
        }
        return Optional.empty();
    }

    private static boolean amount(BigDecimal v, boolean zeroAllowed) {
        return v != null
                && (zeroAllowed ? v.signum() >= 0 : v.signum() > 0)
                && v.compareTo(MAX_AMOUNT) <= 0
                && v.stripTrailingZeros().scale() <= 2;
    }

    /**
     * A text area (a reason, the GoI rate source): present after stripping, at most {@code max}
     * characters, printable ASCII with line breaks kept (R-ASCII).
     */
    private static boolean multiLine(String v, int max) {
        if (v == null) {
            return false;
        }
        String s = v.strip();
        return !s.isEmpty() && s.length() <= max && InputPatterns.isPrintableText(s);
    }
}
