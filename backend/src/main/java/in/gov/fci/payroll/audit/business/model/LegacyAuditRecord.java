package in.gov.fci.payroll.audit.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * {@code legacy_audit} (target DDL v4.0 §28): one migrated legacy audit row, as held in legacy
 * (SRS-20-20, BR-AUDIT-008). Read-only to every role, System Administrator included (SRS-20-21):
 * {@link Immutable}, no constructor for callers, no write method in its repository, and a trigger
 * in the database. Never merged into the native trail (SRS-20-22).
 */
@Entity
@Immutable
@Table(name = "legacy_audit")
public class LegacyAuditRecord {

    @Id
    @Column(name = "legacy_audit_id")
    private Long id;

    @Column(name = "legacy_source_object", nullable = false, length = 128)
    private String sourceObject;

    @Column(name = "legacy_source_key", nullable = false, length = 200)
    private String sourceKey;

    @Column(name = "mig_batch_id", nullable = false)
    private Long migrationBatchId;

    @Column(name = "entity_name", nullable = false, length = 100)
    private String entityName;

    @Column(name = "entity_key", length = 100)
    private String entityKey;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "record_grain", nullable = false, length = 10)
    private String grain;

    @Column(name = "action_code", length = 10)
    private String actionCode;

    @Column(name = "field_name", length = 128)
    private String fieldName;

    @Column(name = "old_value", length = 4000)
    private String oldValue;

    @Column(name = "new_value", length = 4000)
    private String newValue;

    @Lob
    @Column(name = "row_image")
    private String rowImage;

    @Column(name = "changed_fields", length = 4000)
    private String changedFields;

    @Column(name = "legacy_changed_by", length = 50)
    private String changedBy;

    @Column(name = "legacy_changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "legacy_reason", length = 1000)
    private String reason;

    @Column(name = "loaded_at", nullable = false)
    private Instant loadedAt;

    protected LegacyAuditRecord() {}

    public Long getId() {
        return id;
    }

    public String getSourceObject() {
        return sourceObject;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public Long getMigrationBatchId() {
        return migrationBatchId;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getEntityKey() {
        return entityKey;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    /** ROW_IMAGE (the whole legacy row, shown as a snapshot) or FIELD. */
    public String getGrain() {
        return grain;
    }

    public String getActionCode() {
        return actionCode;
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

    public String getRowImage() {
        return rowImage;
    }

    public String getChangedFields() {
        return changedFields;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getReason() {
        return reason;
    }

    public Instant getLoadedAt() {
        return loadedAt;
    }
}
