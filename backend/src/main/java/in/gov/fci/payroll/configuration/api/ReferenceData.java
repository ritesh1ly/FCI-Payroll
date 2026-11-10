package in.gov.fci.payroll.configuration.api;

import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.cache.VersionedCache;
import in.gov.fci.payroll.platform.config.ApiProperties;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M-16 reference lists (target DDL v4.0 {@code reference_list} / {@code reference_value}): the
 * values of every dropdown, in force on the business date, with their Hindi labels ({@code
 * label_master} MASTER_VALUE, entity REFERENCE_VALUE, keyed by value id; fall back to English,
 * never blank). Served from memory on every instance (cache region REFERENCE): an approved M-16
 * change marks the region changed in its transaction ({@link #changed}), so every instance reloads
 * within {@code fci.cache.check-interval}; keyed by date, so a value that starts or ends on a date
 * applies from the first read of that day.
 */
@Component
public class ReferenceData {

    public static final String REGION = "REFERENCE";

    /** label_master.master_entity of a reference value's label. */
    public static final String LABEL_ENTITY = "REFERENCE_VALUE";

    /**
     * One value: code, English and Hindi label (null if none), order, numeric attribute, parent
     * code, and whether it is in force today ({@code active}: offered in dropdowns). Values not in
     * force (ended, or starting later) are kept so older records still show their label.
     */
    public record Value(
            String code,
            String label,
            String labelHi,
            Integer sortOrder,
            BigDecimal numeric,
            String parent,
            boolean active) {

        public String label(String locale) {
            return "hi".equalsIgnoreCase(locale) && labelHi != null ? labelHi : label;
        }
    }

    /**
     * One list: code, name, owning module, system list (codes resolved by the platform), values in
     * order.
     */
    public record ListView(
            String code, String name, String module, boolean system, List<Value> values) {}

    /**
     * Every list in force on {@code asOf}; {@code version} is the region version it was read under
     * (ETag).
     */
    public record Snapshot(LocalDate asOf, long version, Map<String, ListView> lists) {}

    private final JdbcTemplate jdbc;
    private final ApiProperties properties;
    private final Clock clock;
    private final VersionedCache<LocalDate, Snapshot> cache;

    public ReferenceData(
            JdbcTemplate jdbc, ApiProperties properties, Clock clock, CacheRegions regions) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        // Today and tomorrow, plus the few effective dates back-dated transactions ask about.
        this.cache = VersionedCache.of(REGION, regions, 16);
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(properties.businessZone()));
    }

    /** Every list in force today (from memory). */
    public Snapshot snapshot() {
        long version = cache.version();
        return cache.get(today(), date -> load(date, version));
    }

    public Optional<ListView> list(String code) {
        return Optional.ofNullable(snapshot().lists().get(code));
    }

    /**
     * SRS l.650 (US-GAP-119): "The list is versioned and effective-dated. Each screen uses the
     * version in force on its own effective date" - the lists in force on {@code day} (a back-dated
     * transaction's effective date); today without one.
     */
    public Snapshot snapshot(LocalDate day) {
        if (day == null) {
            return snapshot();
        }
        long version = cache.version();
        return cache.get(day, date -> load(date, version));
    }

    /** Whether {@code code} is a value of {@code list} in force on {@code day} (today if null). */
    public boolean contains(String list, String code, LocalDate day) {
        return code != null
                && Optional.ofNullable(snapshot(day).lists().get(list))
                        .map(
                                l ->
                                        l.values().stream()
                                                .anyMatch(v -> v.active() && v.code().equals(code)))
                        .orElse(false);
    }

    /** Whether {@code code} is a value of {@code list} in force today. */
    public boolean contains(String list, String code) {
        return code != null
                && list(list)
                        .map(
                                l ->
                                        l.values().stream()
                                                .anyMatch(v -> v.active() && v.code().equals(code)))
                        .orElse(false);
    }

    /**
     * After an approved change of lists, values or their labels: every instance reloads (in the
     * caller's transaction).
     */
    public void changed(String changedBy) {
        cache.changed(changedBy);
    }

    private Snapshot load(LocalDate date, long version) {
        Date day = Date.valueOf(date);
        Map<Long, String> hindi = new HashMap<>();
        jdbc.query(
                "SELECT master_code, label_text FROM label_master WHERE label_type = 'MASTER_VALUE'"
                    + " AND master_entity = ? AND language_code = 'HI' AND scope_level = 'GLOBAL'"
                    + " AND label_status = 'ACTIVE' AND effective_from <= ? AND (effective_to IS"
                    + " NULL OR effective_to >= ?)",
                rs -> {
                    try {
                        hindi.put(Long.valueOf(rs.getString(1)), rs.getString(2));
                    } catch (NumberFormatException ignored) {
                        // not a value id: not a label of this table
                    }
                },
                LABEL_ENTITY,
                day,
                day);
        Map<String, String[]> heads = new LinkedHashMap<>();
        Map<String, List<Value>> values = new LinkedHashMap<>();
        jdbc.query(
                "SELECT list_code, list_name, owning_module, is_system_list FROM reference_list"
                        + " ORDER BY list_code",
                rs -> {
                    heads.put(
                            rs.getString(1),
                            new String[] {rs.getString(2), rs.getString(3), rs.getString(4)});
                    values.put(rs.getString(1), new ArrayList<>());
                });
        jdbc.query(
                "SELECT l.list_code, v.value_id, v.value_code, v.value_label_en, v.sort_order,"
                    + " v.numeric_attribute, p.value_code, CASE WHEN v.effective_from <= ? AND"
                    + " (v.effective_to IS NULL OR v.effective_to >= ?) THEN 1 ELSE 0 END FROM"
                    + " reference_value v JOIN reference_list l ON l.list_id = v.list_id LEFT JOIN"
                    + " reference_value p ON p.value_id = v.parent_value_id ORDER BY l.list_code,"
                    + " v.sort_order, v.value_code",
                rs -> {
                    int sort = rs.getInt(5);
                    Integer sortOrder = rs.wasNull() ? null : sort;
                    values.get(rs.getString(1))
                            .add(
                                    new Value(
                                            rs.getString(3),
                                            rs.getString(4),
                                            hindi.get(rs.getLong(2)),
                                            sortOrder,
                                            rs.getBigDecimal(6),
                                            rs.getString(7),
                                            rs.getInt(8) == 1));
                },
                day,
                day);
        Map<String, ListView> lists = new LinkedHashMap<>();
        heads.forEach(
                (code, head) ->
                        lists.put(
                                code,
                                new ListView(
                                        code,
                                        head[0],
                                        head[1],
                                        "Y".equals(head[2]),
                                        List.copyOf(values.get(code)))));
        return new Snapshot(date, version, Collections.unmodifiableMap(lists));
    }
}
