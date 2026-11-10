package in.gov.fci.payroll.audit.business.model;

import in.gov.fci.payroll.audit.api.ChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * {@code audit_event} (target DDL S05). BR-AUDIT-003: an entry without before, after, user,
 * timestamp, effective date and reason is not valid. Append-only: {@link Immutable} here, a trigger
 * in the database.
 */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long id;

    @Column(name = "entity_name", nullable = false, length = 100)
    private String entityName;

    @Column(name = "entity_key", nullable = false, length = 100)
    private String entityKey;

    @Column(name = "field_name", nullable = false, length = 100)
    private String fieldName;

    @Column(name = "old_value", length = 4000)
    private String oldValue;

    @Column(name = "new_value", length = 4000)
    private String newValue;

    @Column(name = "changed_by", nullable = false, length = 50)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "approval_id")
    private Long approvalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "module_code", length = 40)
    private String moduleCode;

    /** The office the row was recorded from (V023, FCILekha extension); null without a session. */
    @Column(name = "location_id")
    private Long locationId;

    protected AuditEvent() {}

    public static AuditEvent of(
            String entityName,
            String entityKey,
            String fieldName,
            String oldValue,
            String newValue,
            String changedBy,
            Instant changedAt,
            LocalDate effectiveDate,
            String reason,
            Long approvalId,
            ChangeType changeType,
            String sourceIp,
            String moduleCode,
            Long locationId) {
        AuditEvent event = new AuditEvent();
        event.entityName = Objects.requireNonNull(entityName);
        event.entityKey = Objects.requireNonNull(entityKey);
        event.fieldName = Objects.requireNonNull(fieldName);
        event.oldValue = truncate(oldValue);
        event.newValue = truncate(newValue);
        event.changedBy = Objects.requireNonNull(changedBy);
        event.changedAt = Objects.requireNonNull(changedAt);
        event.effectiveDate = Objects.requireNonNull(effectiveDate);
        event.reason = Objects.requireNonNull(reason);
        event.approvalId = approvalId;
        event.changeType = Objects.requireNonNull(changeType);
        event.sourceIp = sourceIp;
        event.moduleCode = moduleCode;
        event.locationId = locationId;
        return event;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 4000 ? value : value.substring(0, 4000);
    }

    public Long getId() {
        return id;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getEntityKey() {
        return entityKey;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public String getReason() {
        return reason;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public String getModuleCode() {
        return moduleCode;
    }

    public Long getLocationId() {
        return locationId;
    }
}
