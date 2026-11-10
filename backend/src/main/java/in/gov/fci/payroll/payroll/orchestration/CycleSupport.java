package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the payroll-cycle services share (plan 05): the caller's payroll cycle unit, the login as
 * the 50-character audit columns take it, and the JDBC values of DATE / TIMESTAMP / NUMBER columns
 * as H2 (Oracle mode) and Oracle hand them over.
 */
final class CycleSupport {

    private CycleSupport() {}

    /**
     * The payroll cycle unit of the office the user works in; refused without an office, and when
     * the office is paid in no cycle unit.
     */
    static OrgLocation unit(OrgDirectory org, AccessContext context) {
        if (context.locationId() == null) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        return org.cycleUnitOf(context.locationId())
                .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT));
    }

    /** The login cut to the 50 of the *_by columns (an app_user login is up to 150). */
    static String login(AccessContext context) {
        return in.gov.fci.payroll.payroll.business.CycleKeys.truncate(context.loginId());
    }

    /**
     * A DATE column as JDBC hands it over: java.sql.Date, java.time, or a timestamp (H2's Oracle
     * mode stores DATE as TIMESTAMP); null for null.
     */
    static LocalDate date(Object value) {
        return switch (value) {
            case java.sql.Date d -> d.toLocalDate();
            case Timestamp ts -> ts.toLocalDateTime().toLocalDate();
            case LocalDate d -> d;
            case LocalDateTime dt -> dt.toLocalDate();
            case null, default -> null;
        };
    }

    /** A TIMESTAMP column as an instant; null for null. */
    static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** A column value as audit text: a number without exponent; null for null. */
    static String text(Object value) {
        if (value instanceof BigDecimal b) {
            return b.toPlainString();
        }
        return value == null ? null : value.toString();
    }

    /** A COUNT / SUM read as a number: 0 when the driver hands over none. */
    static long orZero(Long n) {
        return RowCounts.of(n);
    }

    /**
     * The open decision on an entity: the latest approval item of {@code entity} / {@code key},
     * when it still waits (PENDING_*); else null.
     */
    static Long pending(JdbcTemplate jdbc, ApprovalStatuses statuses, String entity, String key) {
        List<Long> items =
                jdbc.queryForList(
                        "SELECT approval_id FROM approval_queue WHERE entity_name = ? AND"
                                + " entity_key = ? ORDER BY approval_id DESC",
                        Long.class,
                        entity,
                        key);
        if (items.isEmpty()) {
            return null;
        }
        ApprovalStatus s = statuses.of(List.of(items.get(0))).get(items.get(0));
        return s != null && s.name().startsWith("PENDING") ? items.get(0) : null;
    }

    /** One export row from a JDBC row callback, which may not throw IOException itself. */
    static void row(ExportSink sink, Object... values) {
        try {
            sink.row(values);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** An export whose rows {@link #row} wrote: the IOException a row met comes out as itself. */
    static void exporting(Runnable rows) throws IOException {
        try {
            rows.run();
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }
}
