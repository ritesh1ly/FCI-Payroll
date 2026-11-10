package in.gov.fci.payroll.audit.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * {@code security_event} (target DDL v4.0 S05): lockouts, unlocks, out-of-scope access, OTP
 * lockouts. Written once, never changed. The detail holds identifiers and reasons, never a secret.
 */
@Entity
@Immutable
@Table(name = "security_event")
public class SecurityEventRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "security_event_id")
    private Long id;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "acting_user", length = 50)
    private String actingUser;

    @Column(name = "target_entity", length = 100)
    private String targetEntity;

    @Column(name = "target_key", length = 100)
    private String targetKey;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SecurityEventRecord() {}

    public static SecurityEventRecord of(
            String eventType,
            String actingUser,
            String targetEntity,
            String targetKey,
            String detail,
            String sourceIp,
            Instant occurredAt) {
        SecurityEventRecord event = new SecurityEventRecord();
        event.eventType = Objects.requireNonNull(eventType);
        event.actingUser = truncate(actingUser, 50);
        event.targetEntity = truncate(targetEntity, 100);
        event.targetKey = truncate(targetKey, 100);
        event.detail = truncate(detail, 2000);
        event.sourceIp = truncate(sourceIp, 45);
        event.occurredAt = Objects.requireNonNull(occurredAt);
        return event;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getActingUser() {
        return actingUser;
    }

    public String getTargetEntity() {
        return targetEntity;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public String getDetail() {
        return detail;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
