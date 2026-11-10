package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code circular_order_link}: M-25 "where used": a circular linked to a rule version, master entry
 * or transaction; cancelled, never deleted.
 */
@Entity
@Table(name = "circular_order_link")
public class CircularOrderLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "circular_link_id")
    private Long circularLinkId;

    @Column(name = "circular_order_id", nullable = false)
    private Long circularOrderId;

    @Column(name = "link_type", nullable = false, length = 20)
    private String linkType;

    @Column(name = "linked_entity", nullable = false, length = 100)
    private String linkedEntity;

    @Column(name = "linked_key", nullable = false, length = 100)
    private String linkedKey;

    @Column(name = "transaction_type", length = 60)
    private String transactionType;

    @Column(name = "screen_code", length = 10)
    private String screenCode;

    @Column(name = "link_status", nullable = false, length = 10)
    private String linkStatus;

    @Column(name = "linked_by", nullable = false, length = 50)
    private String linkedBy;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "cancelled_by", length = 50)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    protected CircularOrderLink() {}

    /** {@code link_type} (DDL CHECK). */
    public enum Type {
        RULE_VERSION,
        MASTER_ENTRY,
        TRANSACTION
    }

    /** A link from a rule, master entry or transaction to the Active circular that drives it. */
    public static CircularOrderLink link(
            Long circularId,
            Type type,
            String entity,
            String key,
            String screen,
            String actor,
            Instant now) {
        CircularOrderLink l = new CircularOrderLink();
        l.circularOrderId = circularId;
        l.linkType = type.name();
        l.linkedEntity = entity;
        l.linkedKey = key;
        l.screenCode = screen;
        l.linkStatus = "ACTIVE";
        l.linkedBy = actor;
        l.linkedAt = now;
        return l;
    }

    /**
     * Wave D review 6 (DDL: "a wrong link is CANCELLED", never deleted): the link no longer stands,
     * e.g. the record it was made for was rejected or returned.
     */
    public void cancel(String actor, String reason, Instant now) {
        linkStatus = "CANCELLED";
        cancelledBy = actor;
        cancelledAt = now;
        cancelReason = reason;
    }

    public Long getCircularLinkId() {
        return circularLinkId;
    }

    public Long getCircularOrderId() {
        return circularOrderId;
    }

    public String getLinkType() {
        return linkType;
    }

    public String getLinkedEntity() {
        return linkedEntity;
    }

    public String getLinkedKey() {
        return linkedKey;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public String getScreenCode() {
        return screenCode;
    }

    public String getLinkStatus() {
        return linkStatus;
    }

    public String getLinkedBy() {
        return linkedBy;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }
}
