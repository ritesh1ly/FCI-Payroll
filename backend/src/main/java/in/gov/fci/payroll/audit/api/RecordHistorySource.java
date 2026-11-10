package in.gov.fci.payroll.audit.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A module's master or rule records, for their change history and point-in-time reconstruction
 * (SRS-20-3, SRS-20-6, SRS-20-29; X-04). Each module that owns a master implements it once per
 * record type; the audit module finds the record through it and never reads another module's
 * tables. A record type without a source cannot be enquired on (X-04 Record Key: "must resolve to
 * an existing master or rule record").
 */
public interface RecordHistorySource {

    /** The record type: {@code audit_event.entity_name} of its rows (e.g. {@code rule_version}). */
    String entity();

    /** The record with this key, or empty if there is none. */
    Optional<HistoryRecord> find(String key);

    /** A source from a lookup function (most modules need no more). */
    static RecordHistorySource of(
            String entity, java.util.function.Function<String, Optional<HistoryRecord>> finder) {
        return new RecordHistorySource() {
            @Override
            public String entity() {
                return entity;
            }

            @Override
            public Optional<HistoryRecord> find(String key) {
                return finder.apply(key);
            }
        };
    }

    /**
     * One record as the audit module needs it.
     *
     * @param entity the record type
     * @param key the business key shown and searched for
     * @param screen the screen whose VIEW opens the record (and its History button)
     * @param locationId the office the record belongs to (scope), or null for a national record
     * @param auditKeys every {@code audit_event.entity_key} the record's rows were written under
     * @param versioned the record is kept as effective-dated versions (as-of picks the version in
     *     force); otherwise it is one current row, rebuilt backwards from its audit rows
     * @param versions the approved versions of a versioned record (empty before the first one is
     *     approved), else empty
     * @param current the current field values of a record without versions, else empty
     * @param existsFrom the day a record kept as one row took effect (e.g. a reference value's
     *     effective-from); before it the record was not in force (SRS-20-3). Null when unknown.
     */
    record HistoryRecord(
            String entity,
            String key,
            String screen,
            Long locationId,
            List<String> auditKeys,
            boolean versioned,
            List<Version> versions,
            Map<String, String> current,
            LocalDate existsFrom) {

        public HistoryRecord {
            auditKeys = List.copyOf(auditKeys);
            versions = List.copyOf(versions);
            // Values may be null (a field never set): kept in a null-tolerant copy.
            current = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(current));
        }

        /** A versioned record: history and as-of from its versions. */
        public static HistoryRecord ofVersions(
                String entity,
                String key,
                String screen,
                Long locationId,
                List<String> auditKeys,
                List<Version> versions) {
            return new HistoryRecord(
                    entity, key, screen, locationId, auditKeys, true, versions, Map.of(), null);
        }

        /** A record kept as one row: as-of is rebuilt from its field-level audit rows. */
        public static HistoryRecord ofCurrent(
                String entity,
                String key,
                String screen,
                Long locationId,
                Map<String, String> current) {
            return ofCurrent(entity, key, screen, locationId, current, null);
        }

        /** As {@link #ofCurrent}, not in force before {@code existsFrom} (its effective-from). */
        public static HistoryRecord ofCurrent(
                String entity,
                String key,
                String screen,
                Long locationId,
                Map<String, String> current,
                LocalDate existsFrom) {
            return new HistoryRecord(
                    entity,
                    key,
                    screen,
                    locationId,
                    List.of(key),
                    false,
                    List.of(),
                    current,
                    existsFrom);
        }

        public boolean isVersioned() {
            return versioned;
        }
    }

    /**
     * One approved version (BR-AUDIT-002: a change end-dates the prior version and adds a new one).
     * Only approved versions are given: a pending proposal is not history (KB AU-9).
     */
    record Version(
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long approvalId,
            Map<String, String> values) {

        public Version {
            values = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
        }

        /** In force on {@code day}: from on or before it, to (inclusive) open or on or after it. */
        public boolean inForceOn(LocalDate day) {
            return !effectiveFrom.isAfter(day)
                    && (effectiveTo == null || !effectiveTo.isBefore(day));
        }
    }
}
