package in.gov.fci.payroll.penalty.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * {@code penalty_recovery_schedule}: one month of an order's recovery (target DDL v4.0 Section 18;
 * SRS-14-1, SRS-14-11): PENDING until the month's cycle locks, then RECOVERED (or PARTIAL); WAIVED
 * by a revoke or cancellation; DEFERRED with its reason (to F&amp;F or a later month). One row per
 * order and month ({@code uk_penalty_recovery_schedule}).
 */
@Entity
@Table(name = "penalty_recovery_schedule")
public class PenaltyRecoveryLine {

    /** {@code schedule_status} (DDL CHECK). */
    public enum Status {
        PENDING,
        RECOVERED,
        PARTIAL,
        WAIVED,
        DEFERRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "payroll_yymm", nullable = false, length = 6)
    private String payrollYymm;

    @Column(name = "scheduled_amount", nullable = false)
    private BigDecimal scheduledAmount;

    @Column(name = "recovered_amount", nullable = false)
    private BigDecimal recoveredAmount;

    @Column(name = "schedule_status", nullable = false, length = 15)
    private String scheduleStatus;

    @Column(name = "deferral_reason", length = 500)
    private String deferralReason;

    protected PenaltyRecoveryLine() {}

    /** A month still to be recovered. */
    public static PenaltyRecoveryLine pending(Long orderId, String yymm, BigDecimal amount) {
        PenaltyRecoveryLine l = new PenaltyRecoveryLine();
        l.orderId = orderId;
        l.payrollYymm = yymm;
        l.scheduledAmount = amount;
        l.recoveredAmount = BigDecimal.ZERO;
        l.scheduleStatus = Status.PENDING.name();
        return l;
    }

    /** SRS-14-11: recovered by the month's locked payroll (in part when less was deducted). */
    public void recovered(BigDecimal amount) {
        this.recoveredAmount = amount;
        this.scheduleStatus =
                amount.compareTo(scheduledAmount) >= 0
                        ? Status.RECOVERED.name()
                        : Status.PARTIAL.name();
    }

    /** SRS-14-16: part of the month's instalment waived by a revoke (the rest still due). */
    public void reduced(BigDecimal scheduled) {
        this.scheduledAmount = scheduled;
    }

    /** SRS-14-16, SRS-14-20: waived by a revoke or a cancellation. */
    public void waived() {
        this.scheduleStatus = Status.WAIVED.name();
    }

    /** SRS-14-11: deferred (to F&amp;F, or a month paid supplementarily), with the reason. */
    public void deferred(String reason) {
        this.scheduleStatus = Status.DEFERRED.name();
        this.deferralReason =
                reason == null ? null : reason.substring(0, Math.min(500, reason.length()));
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getPayrollYymm() {
        return payrollYymm;
    }

    public BigDecimal getScheduledAmount() {
        return scheduledAmount;
    }

    public BigDecimal getRecoveredAmount() {
        return recoveredAmount;
    }

    public Status status() {
        return Status.valueOf(scheduleStatus);
    }

    public String getDeferralReason() {
        return deferralReason;
    }
}
