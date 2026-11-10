package in.gov.fci.payroll.platform.persistence;

/**
 * A row count read with {@code queryForObject("SELECT COUNT(*) ...", Long.class)}: the driver never
 * returns null for COUNT, but the API is nullable - one guard here instead of a defensive branch at
 * every call site.
 */
public final class RowCounts {

    private RowCounts() {}

    /** The count, zero when the driver returned no value. */
    public static long of(Number count) {
        return count == null ? 0 : count.longValue();
    }
}
