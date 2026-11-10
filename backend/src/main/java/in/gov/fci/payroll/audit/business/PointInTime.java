package in.gov.fci.payroll.audit.business;

import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Point-in-time reconstruction (SRS-20-3, SRS-20-6, BR-AUDIT-007): the state of a master or rule
 * record as it stood on a date. Pure rules, no I/O.
 */
public final class PointInTime {

    private PointInTime() {}

    /** The outcome of an as-of enquiry. */
    public sealed interface Result permits Found, NotInForce, Overlap {}

    /** Exactly one version was in force on the date. */
    public record Found(
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long approvalId,
            Map<String, String> values)
            implements Result {}

    /** The record had no version in force on the date (before its first, or after its end). */
    public record NotInForce() implements Result {}

    /**
     * More than one version claims the date: a data error reported as such, never resolved by
     * picking one (SRS-20-6: exactly one active version per key per date).
     */
    public record Overlap(List<Integer> versionNos) implements Result {
        public Overlap {
            versionNos = List.copyOf(versionNos);
        }
    }

    /** SRS-20-6: the one approved version in force on {@code day}. */
    public static Result asOf(List<Version> versions, LocalDate day) {
        List<Version> inForce =
                versions.stream()
                        .filter(v -> v.inForceOn(day))
                        .sorted(Comparator.comparingInt(Version::versionNo))
                        .toList();
        if (inForce.isEmpty()) {
            return new NotInForce();
        }
        if (inForce.size() > 1) {
            return new Overlap(inForce.stream().map(Version::versionNo).toList());
        }
        Version v = inForce.get(0);
        return new Found(
                v.versionNo(), v.effectiveFrom(), v.effectiveTo(), v.approvalId(), v.values());
    }

    /**
     * One field-level audit row of a record kept as a single current row.
     *
     * @param initial the row recorded the field's first value (change type INITIAL_VALUE)
     */
    public record FieldChange(
            long id,
            String field,
            String oldValue,
            String newValue,
            LocalDate effectiveDate,
            Instant changedAt,
            boolean initial) {}

    private static final Comparator<FieldChange> APPLIED_ORDER =
            Comparator.comparing(FieldChange::effectiveDate)
                    .thenComparing(FieldChange::changedAt)
                    .thenComparingLong(FieldChange::id);

    /**
     * SRS-20-3 for a record without versions: each field as it stood on {@code day}, rebuilt
     * backwards from the current values and the field's audit rows. The value is the new value of
     * the last change effective on or before the day; with none, the old value of the first change
     * effective after it; a field never changed keeps its current value. A record whose first
     * values were all recorded as effective after the day did not exist yet.
     */
    public static Optional<Map<String, String>> reconstruct(
            Map<String, String> current, List<FieldChange> changes, LocalDate day) {
        boolean createdLater =
                changes.stream().anyMatch(FieldChange::initial)
                        && changes.stream()
                                .filter(FieldChange::initial)
                                .allMatch(c -> c.effectiveDate().isAfter(day));
        if (createdLater) {
            return Optional.empty();
        }
        Map<String, String> state = new LinkedHashMap<>();
        current.keySet().stream()
                .sorted()
                .forEach(
                        field -> {
                            List<FieldChange> own =
                                    changes.stream()
                                            .filter(c -> c.field().equals(field))
                                            .sorted(APPLIED_ORDER)
                                            .toList();
                            Optional<FieldChange> lastBefore =
                                    own.stream()
                                            .filter(c -> !c.effectiveDate().isAfter(day))
                                            .reduce((a, b) -> b);
                            Optional<FieldChange> firstAfter =
                                    own.stream()
                                            .filter(c -> c.effectiveDate().isAfter(day))
                                            .findFirst();
                            state.put(
                                    field,
                                    lastBefore
                                            .map(FieldChange::newValue)
                                            .orElseGet(
                                                    () ->
                                                            firstAfter
                                                                    .map(FieldChange::oldValue)
                                                                    .orElse(current.get(field))));
                        });
        return Optional.of(state);
    }
}
