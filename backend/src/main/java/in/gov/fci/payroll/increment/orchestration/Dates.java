package in.gov.fci.payroll.increment.orchestration;

import java.time.LocalDate;

/**
 * A DATE column read through {@code queryForMap} / {@code queryForList}: H2 in Oracle mode gives a
 * {@link java.sql.Timestamp}, Oracle a {@link java.sql.Date} - never cast to either.
 */
final class Dates {

    private Dates() {}

    static LocalDate local(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof java.time.LocalDateTime t) {
            return t.toLocalDate();
        }
        return new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
    }
}
