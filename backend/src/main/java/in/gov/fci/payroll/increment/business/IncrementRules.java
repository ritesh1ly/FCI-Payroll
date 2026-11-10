package in.gov.fci.payroll.increment.business;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Increment and pay fixation rules (plan 17; SRS-13-1, SRS-13-5, SRS-13-23, SRS-13-26; BR-INCR-003,
 * BR-INCR-008, BR-EMPR-003). Pure: no framework, no I/O.
 */
public final class IncrementRules {

    private IncrementRules() {}

    /** Why a revised Basic cannot be taken, for the I-05 review (flagged, never applied). */
    public enum Flag {
        /** SRS-13-8: a bar or stoppage order (I-06) is in force on the due date. */
        BARRED,
        /** I-05 alternate flow: at or above the scale maximum, not eligible for stagnation. */
        ABOVE_MAXIMUM,
        /** SRS-13-23: not a stage of the member's scale. */
        NOT_A_STAGE,
        /** D17-3: the scale version has no stages configured (the check cannot run). */
        STAGES_NOT_CONFIGURED,
        /** D17-4 / R-RULE: the increment rule of the band is not configured. */
        NOT_CONFIGURED,
        /** The member has no pay scale (band) on the master. */
        NO_SCALE,
        /**
         * BR-INCR-STAG-001 / 007: at the scale maximum and eligible; the stagnation increment is
         * proposed (informative, never blocking).
         */
        STAGNATION
    }

    /** SRS-13-1 / BR-EMPR-003: the next increment falls due a year after the actual due date. */
    public static LocalDate nextDue(LocalDate due) {
        return due.plusYears(1);
    }

    /**
     * The Basic event's reason for an increment; a stagnation auto-trigger also names the
     * eligibility test that fired it (SRS-13-19), within the 500 characters of the reason.
     */
    public static String reason(String type, String period, String test) {
        String base = type + " increment, due period " + period;
        String text =
                test == null || test.isBlank() ? base : base + "; eligibility: " + test.strip();
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    /** The due period of an increment (financial year of the due date, e.g. 2026-27). */
    public static String duePeriod(LocalDate due) {
        int start = due.getMonthValue() >= 4 ? due.getYear() : due.getYear() - 1;
        return String.format("%04d-%02d", start, (start + 1) % 100);
    }

    /**
     * SRS-13-5 / I-05: the revised Basic against the scale in force: above the maximum is flagged
     * (only a stagnation increment may pass it); empty when it fits.
     */
    public static Optional<Flag> scale(BigDecimal revised, BigDecimal maximum, boolean stagnation) {
        if (maximum != null && !stagnation && revised.compareTo(maximum) > 0) {
            return Optional.of(Flag.ABOVE_MAXIMUM);
        }
        return Optional.empty();
    }

    /**
     * SRS-13-23 / BR-INCR-008: the Basic is a stage of the scale; with no stages configured the
     * check cannot run and says so (D17-3), never assumes a stage.
     */
    public static Optional<Flag> stage(BigDecimal basic, List<BigDecimal> stages) {
        if (stages == null || stages.isEmpty()) {
            return Optional.of(Flag.STAGES_NOT_CONFIGURED);
        }
        return stages.stream().anyMatch(s -> s.compareTo(basic) == 0)
                ? Optional.empty()
                : Optional.of(Flag.NOT_A_STAGE);
    }

    /**
     * The next stage above a Basic (fixation on promotion, IN-2: "next stage above present Basic in
     * the new scale"); empty when no stage is above.
     */
    public static Optional<BigDecimal> nextStageAbove(BigDecimal basic, List<BigDecimal> stages) {
        return stages.stream().filter(s -> s.compareTo(basic) > 0).min(BigDecimal::compareTo);
    }

    /** SRS-13-26: the system-generated event name of a Basic Pay event. */
    public static String eventName(String eventType, LocalDate effective) {
        return eventType.replace('_', ' ') + " w.e.f. " + effective;
    }

    /** One Basic history row: its id (in the order written), effective date and sequence. */
    public record Entry(long id, LocalDate effectiveFrom, int sequence) {}

    /**
     * One approved I-05 correction.
     *
     * @param type BASIC_PAY or INCREMENT_DATE ({@code increment_correction.correction_type})
     * @param correctionRow the Basic history row the correction wrote, or null (none written)
     * @param incrementRow the history row of the increment the date correction re-dated, or null
     */
    public record Correction(String type, Long correctionRow, Long incrementRow) {}

    /**
     * SRS-13-24: "The old entry stays in the Basic history, marked as corrected." For a Basic
     * correction, the old entry is the row in force on the corrected date before the correction was
     * written (the latest date and sequence before the correction's own, among the rows written
     * earlier); for an increment-date correction, the row of the increment given on the wrong date.
     *
     * @return the ids of the rows to show as corrected
     */
    public static Set<Long> corrected(List<Entry> rows, List<Correction> corrections) {
        Map<Long, Entry> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.id(), r));
        Set<Long> marked = new HashSet<>();
        Comparator<Entry> order =
                Comparator.comparing(Entry::effectiveFrom).thenComparingInt(Entry::sequence);
        for (Correction c : corrections) {
            if ("INCREMENT_DATE".equals(c.type())) {
                if (c.incrementRow() != null && byId.containsKey(c.incrementRow())) {
                    marked.add(c.incrementRow());
                }
                continue;
            }
            Entry written = c.correctionRow() == null ? null : byId.get(c.correctionRow());
            if (written == null) {
                continue;
            }
            rows.stream()
                    .filter(r -> r.id() < written.id() && order.compare(r, written) < 0)
                    .max(order)
                    .ifPresent(r -> marked.add(r.id()));
        }
        return marked;
    }
}
