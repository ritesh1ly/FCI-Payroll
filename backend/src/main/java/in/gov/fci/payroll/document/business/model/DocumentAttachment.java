package in.gov.fci.payroll.document.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code document_attachment} (target DDL v4.0 S25; SRS-DM-13, BR-AUDIT-009): one file attached to
 * one owning record ({@code entity_name}, {@code entity_key}), with its order particulars, size,
 * SHA-256 and where its bytes are stored.
 *
 * <p><b>Never deleted.</b> A wrong or replaced file becomes SUPERSEDED (pointing at its successor)
 * or CANCELLED with its transaction; the row and the bytes stay for the audit trail. These are the
 * only two transitions, and both start from ACTIVE.
 */
@Entity
@Table(name = "document_attachment")
public class DocumentAttachment {

    /** {@code attachment_status} values (CHECK of the DDL). */
    public static final String ACTIVE = "ACTIVE";

    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String CANCELLED = "CANCELLED";

    /**
     * {@code storage_provider} of the in-database byte store (decision D-1, the DDL S25 fallback).
     */
    public static final String ORACLE_SECUREFILE = "ORACLE_SECUREFILE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attachment_id")
    private Long id;

    @Column(name = "entity_name", nullable = false, length = 100)
    private String entityName;

    @Column(name = "entity_key", nullable = false, length = 100)
    private String entityKey;

    @Column(name = "document_type_id")
    private Long documentTypeId;

    @Column(name = "document_reference_number", length = 60)
    private String referenceNumber;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "issuing_authority", length = 200)
    private String issuingAuthority;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "storage_provider", nullable = false, length = 30)
    private String storageProvider;

    @Column(name = "storage_bucket", length = 100)
    private String storageBucket;

    @Column(name = "storage_object_key", length = 500)
    private String storageObjectKey;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "contains_pii", nullable = false, length = 1)
    private Boolean containsPii;

    @Column(name = "retention_class_id")
    private Long retentionClassId;

    @Column(name = "uploaded_by", nullable = false, length = 50)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "attachment_status", nullable = false, length = 12)
    private String status;

    @Column(name = "superseded_by_attachment_id")
    private Long supersededBy;

    protected DocumentAttachment() {}

    /**
     * A new ACTIVE attachment. Its storage location ({@link #storedAt}) must be set before the row
     * is saved: {@code storage_provider} is NOT NULL and (provider, bucket, key) is unique.
     */
    public static DocumentAttachment record(
            String entityName,
            String entityKey,
            Long documentTypeId,
            String referenceNumber,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            String contentType,
            long sizeBytes,
            String checksumSha256,
            boolean containsPii,
            String uploadedBy,
            Instant now) {
        DocumentAttachment a = new DocumentAttachment();
        a.entityName = entityName;
        a.entityKey = entityKey;
        a.documentTypeId = documentTypeId;
        a.referenceNumber = referenceNumber;
        a.orderDate = orderDate;
        a.issuingAuthority = issuingAuthority;
        a.fileName = fileName;
        a.contentType = contentType;
        a.fileSizeBytes = sizeBytes;
        a.checksumSha256 = checksumSha256;
        a.containsPii = containsPii;
        // uploaded_by is VARCHAR2(50): a longer login id is cut, never rejected (as in the audit
        // trail).
        a.uploadedBy = uploadedBy.substring(0, Math.min(50, uploadedBy.length()));
        a.uploadedAt = now;
        a.status = ACTIVE;
        return a;
    }

    /** Records where the bytes were stored (provider, bucket, object key). */
    public void storedAt(String provider, String bucket, String objectKey) {
        this.storageProvider = provider;
        this.storageBucket = bucket;
        this.storageObjectKey = objectKey;
    }

    /** Replaced by {@code successor} (SRS-G-31: only before the transaction is submitted). */
    public void supersede(Long successor) {
        requireActive();
        this.status = SUPERSEDED;
        this.supersededBy = successor;
    }

    /** Cancelled together with its transaction (BR-AUDIT-009). */
    public void cancel() {
        requireActive();
        this.status = CANCELLED;
    }

    /**
     * Adopted by its record: re-pointed once from a provisional key ({@code
     * DocumentStore.PROVISIONAL}) to the real owner key.
     */
    public void adoptedBy(String ownerKey, String provisionalPrefix) {
        requireActive();
        if (!entityKey.startsWith(provisionalPrefix) || ownerKey.startsWith(provisionalPrefix)) {
            throw new IllegalArgumentException("Attachment " + id + " is not provisional");
        }
        this.entityKey = ownerKey;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    /** True when the attachment belongs to the given owning record. */
    public boolean belongsTo(String entity, String key) {
        return entityName.equals(entity) && entityKey.equals(key);
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("Attachment " + id + " is " + status + ", not ACTIVE");
        }
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

    public Long getDocumentTypeId() {
        return documentTypeId;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public String getIssuingAuthority() {
        return issuingAuthority;
    }

    public String getFileName() {
        return fileName;
    }

    public String getStorageProvider() {
        return storageProvider;
    }

    public String getStorageBucket() {
        return storageBucket;
    }

    public String getStorageObjectKey() {
        return storageObjectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes == null ? 0 : fileSizeBytes;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public boolean containsPii() {
        return Boolean.TRUE.equals(containsPii);
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public String getStatus() {
        return status;
    }

    public Long getSupersededBy() {
        return supersededBy;
    }
}
