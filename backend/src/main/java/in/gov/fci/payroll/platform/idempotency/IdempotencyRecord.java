package in.gov.fci.payroll.platform.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/**
 * Outcome of a state-changing request, replayed when the same Idempotency-Key is retried
 * (ADR-0011).
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord {

    public enum Status {
        IN_PROGRESS,
        COMPLETED
    }

    @Id
    @Column(name = "key_id", length = 64)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_status", nullable = false, length = 12)
    private Status status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Lob
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long version;

    protected IdempotencyRecord() {}

    public static IdempotencyRecord start(
            String id, String userId, String requestHash, Instant now, Instant expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = Objects.requireNonNull(id);
        record.userId = Objects.requireNonNull(userId);
        record.requestHash = Objects.requireNonNull(requestHash);
        record.status = Status.IN_PROGRESS;
        record.createdAt = now;
        record.expiresAt = expiresAt;
        return record;
    }

    public void complete(int status, String contentType, String body) {
        this.status = Status.COMPLETED;
        this.responseStatus = status;
        this.contentType = contentType;
        this.responseBody = body;
    }

    public String getId() {
        return id;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getContentType() {
        return contentType;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
