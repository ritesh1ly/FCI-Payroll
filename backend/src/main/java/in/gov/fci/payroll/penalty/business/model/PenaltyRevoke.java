package in.gov.fci.payroll.penalty.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code penalty_revoke}: one Penalty Revoke against a Penalty ID (target DDL v4.0 Section 18;
 * SRS-14-16, SRS-14-17, BR-PEN2-008): partial or full, by amount or by period, under maker-checker;
 * APPROVED only with its approval ({@code chk_penalty_revoke_approved}). Its reversing arrear and
 * the restored increment are linked when the arrear and increment modules record them.
 */
@Entity
@Table(name = "penalty_revoke")
public class PenaltyRevoke {

    /** {@code revoke_type} (DDL CHECK). */
    public enum Type {
        PARTIAL,
        FULL
    }

    /** {@code revoke_status} (DDL CHECK). */
    public enum Status {
        PENDING,
        APPROVED,
        RETURNED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "penalty_revoke_id")
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "revoke_type", nullable = false, length = 10)
    private String revokeType;

    @Column(name = "revoke_order_ref", nullable = false, length = 100)
    private String revokeOrderRef;

    @Column(name = "revoke_order_date", nullable = false)
    private LocalDate revokeOrderDate;

    @Column(name = "revoke_reason", nullable = false, length = 1000)
    private String revokeReason;

    @Column(name = "waived_amount")
    private BigDecimal waivedAmount;

    @Column(name = "waived_period_from")
    private LocalDate waivedPeriodFrom;

    @Column(name = "waived_period_to")
    private LocalDate waivedPeriodTo;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "increment_restored_flag", nullable = false, length = 1)
    private Boolean incrementRestored;

    @Column(name = "restored_increment_id")
    private Long restoredIncrementId;

    @Column(name = "reversing_arrear_event_id")
    private Long reversingArrearEventId;

    @Column(name = "revoke_status", nullable = false, length = 15)
    private String revokeStatus;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PenaltyRevoke() {}

    /** A revoke submitted for the Checker. */
    @SuppressWarnings("java:S107") // the DDL row
    public static PenaltyRevoke submitted(
            Long orderId,
            Type type,
            String orderRef,
            LocalDate orderDate,
            String reason,
            BigDecimal waivedAmount,
            LocalDate periodFrom,
            LocalDate periodTo,
            Instant now) {
        PenaltyRevoke r = new PenaltyRevoke();
        r.orderId = orderId;
        r.revokeType = type.name();
        r.revokeOrderRef = orderRef;
        r.revokeOrderDate = orderDate;
        r.revokeReason = reason;
        r.waivedAmount = waivedAmount;
        r.waivedPeriodFrom = periodFrom;
        r.waivedPeriodTo = periodTo;
        r.incrementRestored = false;
        r.revokeStatus = Status.PENDING.name();
        r.createdAt = now;
        return r;
    }

    /** Sent with its approval. */
    public void approval(Long id) {
        this.approvalId = id;
    }

    /** The Checker's decision. */
    public void decided(Status next) {
        this.revokeStatus = next.name();
    }

    /** SRS-14-17: the increment this revoke restored ({@code pay_increment_cycle}). */
    public void restored(Long incrementId) {
        this.incrementRestored = Boolean.TRUE;
        this.restoredIncrementId = incrementId;
    }

    public Long getRestoredIncrementId() {
        return restoredIncrementId;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Type type() {
        return Type.valueOf(revokeType);
    }

    public String getRevokeOrderRef() {
        return revokeOrderRef;
    }

    public LocalDate getRevokeOrderDate() {
        return revokeOrderDate;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public BigDecimal getWaivedAmount() {
        return waivedAmount;
    }

    public LocalDate getWaivedPeriodFrom() {
        return waivedPeriodFrom;
    }

    public LocalDate getWaivedPeriodTo() {
        return waivedPeriodTo;
    }

    public boolean isIncrementRestored() {
        return Boolean.TRUE.equals(incrementRestored);
    }

    public Status status() {
        return Status.valueOf(revokeStatus);
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
