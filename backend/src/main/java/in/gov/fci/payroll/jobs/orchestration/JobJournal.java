package in.gov.fci.payroll.jobs.orchestration;

import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The append-only history of every request (both profiles): {@code batch_job_audit} (who did what
 * and when, with the status change) and {@code batch_skipped_item} (the failure log of skipped
 * records). Spring Batch's own metadata keeps the step-level execution history next to it.
 */
@Component
public class JobJournal {

    public enum Event {
        SUBMITTED,
        APPROVED,
        REJECTED,
        PICKED,
        STARTED,
        COMPLETED,
        FAILED,
        STOPPED,
        PAUSED,
        CANCELLED,
        CONTROL_REQUESTED,
        CONTROL_APPLIED,
        CONTROL_REJECTED,
        RECOVERED,
        LOCK_REFUSED,
        RELEASED,
        ABANDON_REQUESTED,
        ABANDON_REFUSED,
        ABANDONED,
        HALTED
    }

    public record AuditEntry(
            Long id,
            String event,
            String actor,
            Instant at,
            String fromStatus,
            String toStatus,
            Long jobExecutionId,
            String detail) {}

    public record SkippedItem(
            Long id,
            Long jobExecutionId,
            String step,
            String phase,
            String itemKey,
            String errorClass,
            String errorMessage,
            Instant at) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JobJournal(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(
            Long requestId,
            Event event,
            String actor,
            JobStatus from,
            JobStatus to,
            Long executionId,
            String detail) {
        jdbc.update(
                "INSERT INTO batch_job_audit (job_request_id, event_type, actor, event_at,"
                    + " from_status, to_status, job_execution_id, detail) VALUES (?, ?, ?, ?, ?, ?,"
                    + " ?, ?)",
                requestId,
                event.name(),
                truncate(actor, 150),
                Timestamp.from(clock.instant()),
                from == null ? null : from.name(),
                to == null ? null : to.name(),
                executionId,
                truncate(detail, 500));
    }

    public void skipped(
            Long requestId,
            Long executionId,
            String step,
            String phase,
            String itemKey,
            Throwable error) {
        jdbc.update(
                "INSERT INTO batch_skipped_item (job_request_id, job_execution_id, step_name,"
                    + " skip_phase, item_key, error_class, error_message, recorded_at) VALUES (?,"
                    + " ?, ?, ?, ?, ?, ?, ?)",
                requestId,
                executionId,
                truncate(step, 100),
                phase,
                truncate(itemKey, 100),
                truncate(error.getClass().getName(), 200),
                truncate(error.getMessage(), 1000),
                Timestamp.from(clock.instant()));
    }

    public List<AuditEntry> audit(Long requestId) {
        return jdbc.query(
                "SELECT audit_id, event_type, actor, event_at, from_status, to_status,"
                        + " job_execution_id, detail FROM batch_job_audit WHERE job_request_id = ?"
                        + " ORDER BY audit_id",
                (rs, i) ->
                        new AuditEntry(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getTimestamp(4).toInstant(),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getObject(7, Long.class),
                                rs.getString(8)),
                requestId);
    }

    public long skippedCount(Long requestId) {
        Long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM batch_skipped_item WHERE job_request_id = ?",
                        Long.class,
                        requestId);
        return RowCounts.of(count);
    }

    /** One page of the failure log, newest first. */
    public List<SkippedItem> skippedItems(Long requestId, int page, int size) {
        return jdbc.query(
                "SELECT skipped_id, job_execution_id, step_name, skip_phase, item_key, error_class,"
                    + " error_message, recorded_at FROM batch_skipped_item WHERE job_request_id = ?"
                    + " ORDER BY skipped_id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, i) ->
                        new SkippedItem(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getString(7),
                                rs.getTimestamp(8).toInstant()),
                requestId,
                (long) page * size,
                size);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
