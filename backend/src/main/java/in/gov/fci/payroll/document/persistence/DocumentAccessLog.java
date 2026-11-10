package in.gov.fci.payroll.document.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code document_access_log} (target DDL v4.0 S25; OC-7, SRS-G-31): every view or download of an
 * attachment is logged with who, when and from where. Append-only, written with plain JDBC because
 * the table's key (access_id, accessed_at) exists for monthly partitioning, not for an entity.
 */
@Component
public class DocumentAccessLog {

    /** {@code access_type} values (CHECK of the DDL). */
    public enum AccessType {
        VIEW,
        DOWNLOAD,
        DELETE_ATTEMPT
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DocumentAccessLog(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(Long attachmentId, String actor, AccessType type, String sourceIp) {
        jdbc.update(
                "INSERT INTO document_access_log (attachment_id, accessed_by, access_type,"
                        + " source_ip, accessed_at) VALUES (?, ?, ?, ?, ?)",
                attachmentId,
                actor.length() <= 50 ? actor : actor.substring(0, 50),
                type.name(),
                sourceIp == null || sourceIp.length() <= 45 ? sourceIp : sourceIp.substring(0, 45),
                Timestamp.from(clock.instant()));
    }
}
