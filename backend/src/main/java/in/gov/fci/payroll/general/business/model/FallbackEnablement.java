package in.gov.fci.payroll.general.business.model;

import in.gov.fci.payroll.general.business.Inputs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * {@code hrms_fallback_enablement} (target DDL v4.0 S08; X-07; BR-INT-011, SRS-G-46): Headquarters
 * opens one of the two fallback uploads for a payroll month and a set of offices ({@link
 * FallbackEnableScope}). Without an OPEN enablement for the cycle and office, an upload is refused
 * with a message naming the HRMS interface as the normal route (SRS-EH-11).
 *
 * <p>Created only once its maker-checker approval is decided (X07_CREATE): until then the proposal
 * lives in the approval item, so nothing can be uploaded against an unapproved enablement.
 */
@Entity
@Table(name = "hrms_fallback_enablement")
public class FallbackEnablement {

    /** {@code fallback_type} (CHECK of the DDL): exactly two, no others (BR-INT-011). */
    public enum Type {
        /**
         * (a) the month's employee attendance and leave when HRMS is down at the payroll cut-off.
         */
        ATTENDANCE_LEAVE,
        /**
         * (b) suspension, absconding and revocation orders, interim until their HRMS interface is
         * live.
         */
        SUSPENSION_ABSCONDING
    }

    /** {@code enablement_status} (CHECK of the DDL). */
    public enum Status {
        OPEN,
        UPLOADED,
        APPROVED,
        RECONCILED,
        CLOSED,
        WITHDRAWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "enablement_id")
    private Long id;

    /** FBE-yyyy-nnn, unique ({@code uk_hrms_fallback_enable_ref}). */
    @Column(name = "enablement_ref", nullable = false, length = 30)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "fallback_type", nullable = false, length = 25)
    private Type type;

    @Column(name = "payroll_yymm", nullable = false, length = 6)
    private String payrollYymm;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "enabled_by", nullable = false, length = 50)
    private String enabledBy;

    @Column(name = "enabled_at", nullable = false)
    private Instant enabledAt;

    @Column(name = "hrms_down_since")
    private Instant hrmsDownSince;

    @Enumerated(EnumType.STRING)
    @Column(name = "enablement_status", nullable = false, length = 15)
    private Status status;

    @Column(name = "withdrawn_reason", length = 1000)
    private String withdrawnReason;

    @Column(name = "approval_id")
    private Long approvalId;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected FallbackEnablement() {}

    /** An approved enablement, OPEN for uploads. */
    public static FallbackEnablement open(
            String reference,
            Type type,
            String payrollYymm,
            String reason,
            String enabledBy,
            Instant hrmsDownSince,
            Long approvalId,
            Instant now) {
        FallbackEnablement e = new FallbackEnablement();
        e.reference = reference;
        e.type = type;
        e.payrollYymm = payrollYymm;
        e.reason = reason;
        e.enabledBy = Inputs.cut(enabledBy, 50);
        e.enabledAt = now;
        e.hrmsDownSince = hrmsDownSince;
        e.approvalId = approvalId;
        e.status = Status.OPEN;
        return e;
    }

    /** Uploads are accepted while the enablement is OPEN or already holds uploads. */
    public boolean acceptsUploads() {
        return status == Status.OPEN || status == Status.UPLOADED;
    }

    /** The first upload marks the enablement UPLOADED. */
    public void uploaded() {
        if (status == Status.OPEN) {
            status = Status.UPLOADED;
        }
    }

    /** Every enabled office has an approved upload. */
    public void approved() {
        if (status == Status.OPEN || status == Status.UPLOADED) {
            status = Status.APPROVED;
        }
    }

    /**
     * Withdrawn with a reason ({@code chk_hrms_fb_enable_wd}); only before any upload is approved.
     */
    public void withdraw(String why) {
        if (!acceptsUploads()) {
            throw new IllegalStateException("Enablement " + reference + " is " + status);
        }
        this.withdrawnReason = why;
        this.status = Status.WITHDRAWN;
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public Type getType() {
        return type;
    }

    public String getPayrollYymm() {
        return payrollYymm;
    }

    public String getReason() {
        return reason;
    }

    public String getEnabledBy() {
        return enabledBy;
    }

    public Instant getEnabledAt() {
        return enabledAt;
    }

    public Status getStatus() {
        return status;
    }

    public String getWithdrawnReason() {
        return withdrawnReason;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
