package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code circular_order}: M-25 Circular / Order Master (target DDL v4.0 S02): create, supersede and
 * withdraw are maker-checker; never deleted.
 */
@Entity
@Table(name = "circular_order")
public class CircularOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "circular_order_id")
    private Long circularOrderId;

    @Column(name = "circular_number", nullable = false, length = 60)
    private String circularNumber;

    @Column(name = "circular_date", nullable = false)
    private LocalDate circularDate;

    @Column(name = "issuing_authority_code", length = 40)
    private String issuingAuthorityCode;

    @Column(name = "issuing_location_id")
    private Long issuingLocationId;

    @Column(name = "subject", length = 500)
    private String subject;

    @Column(name = "category_code", length = 40)
    private String categoryCode;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "applies_all_offices", nullable = false, length = 1)
    private Boolean appliesAllOffices;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "supersedes_circular_id")
    private Long supersedesCircularId;

    @Column(name = "document_attachment_id")
    private Long documentAttachmentId;

    @Column(name = "circular_status", nullable = false, length = 20)
    private String circularStatus;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "approval_id")
    private Long approvalId;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "changed_by", length = 50)
    private String changedBy;

    @Column(name = "changed_at")
    private Instant changedAt;

    protected CircularOrder() {}

    /** {@code circular_status} (DDL CHECK). */
    public enum Status {
        PENDING_APPROVAL,
        ACTIVE,
        SUPERSEDED,
        WITHDRAWN,
        REJECTED
    }

    /** The particulars the maker records (SRS-G-38). */
    public record Particulars(
            String number,
            LocalDate date,
            String issuingAuthorityCode,
            Long issuingLocationId,
            String subject,
            String categoryCode,
            boolean appliesAllOffices,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long supersedesCircularId) {}

    /** A recorded circular awaiting the checker; its order copy is attached once it has an id. */
    public static CircularOrder record(Particulars p, String actor, Instant now) {
        CircularOrder c = new CircularOrder();
        c.circularNumber = p.number();
        c.circularDate = p.date();
        c.issuingAuthorityCode = p.issuingAuthorityCode();
        c.issuingLocationId = p.issuingLocationId();
        c.subject = p.subject();
        c.categoryCode = p.categoryCode();
        c.appliesAllOffices = p.appliesAllOffices();
        c.effectiveFrom = p.effectiveFrom();
        c.effectiveTo = p.effectiveTo();
        c.supersedesCircularId = p.supersedesCircularId();
        c.circularStatus = Status.PENDING_APPROVAL.name();
        c.createdBy = actor;
        c.createdAt = now;
        return c;
    }

    public Status status() {
        return Status.valueOf(circularStatus);
    }

    public boolean isActive() {
        return status() == Status.ACTIVE;
    }

    public void submitted(Long approvalId, Long documentAttachmentId) {
        this.approvalId = approvalId;
        this.documentAttachmentId = documentAttachmentId;
    }

    /** The checker's decision on the circular's own approval. */
    public void decided(boolean approved, String reason, String actor, Instant now) {
        circularStatus = approved ? Status.ACTIVE.name() : Status.REJECTED.name();
        statusReason = approved ? null : reason;
        touch(actor, now);
    }

    /** Replaced by an approved successor (in the successor's approval transaction). */
    public void supersede(String actor, Instant now) {
        circularStatus = Status.SUPERSEDED.name();
        touch(actor, now);
    }

    /** An approved withdrawal: no new links; existing links stay. */
    public void withdraw(String reason, String actor, Instant now) {
        circularStatus = Status.WITHDRAWN.name();
        statusReason = reason;
        touch(actor, now);
    }

    private void touch(String actor, Instant now) {
        changedBy = actor;
        changedAt = now;
    }

    public Long getCircularOrderId() {
        return circularOrderId;
    }

    public String getCircularNumber() {
        return circularNumber;
    }

    public LocalDate getCircularDate() {
        return circularDate;
    }

    public String getIssuingAuthorityCode() {
        return issuingAuthorityCode;
    }

    public Long getIssuingLocationId() {
        return issuingLocationId;
    }

    public String getSubject() {
        return subject;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public boolean isAppliesAllOffices() {
        return Boolean.TRUE.equals(appliesAllOffices);
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public Long getSupersedesCircularId() {
        return supersedesCircularId;
    }

    public Long getDocumentAttachmentId() {
        return documentAttachmentId;
    }

    public String getCircularStatus() {
        return circularStatus;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
