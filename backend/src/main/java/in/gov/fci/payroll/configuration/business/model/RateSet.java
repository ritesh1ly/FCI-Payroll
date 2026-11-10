package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * {@code rate_set}: One version of a rate set (target DDL v4.0 S06): the ten-plus M-22 rate masters
 * and M-04 rate tables in one shape.
 */
@Entity
@Table(name = "rate_set")
public class RateSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rate_set_id")
    private Long rateSetId;

    @Column(name = "rate_set_code", nullable = false, length = 40)
    private String rateSetCode;

    @Column(name = "rate_set_name", nullable = false, length = 200)
    private String rateSetName;

    @Column(name = "payroll_type", length = 20)
    private String payrollType;

    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "circular_order_id")
    private Long circularOrderId;

    @Column(name = "approval_state", nullable = false, length = 20)
    private String approvalState;

    @Column(name = "approval_id")
    private Long approvalId;

    protected RateSet() {}

    /** A proposed version of a rate set; pending until its approval is decided. */
    public static RateSet propose(
            String code,
            String name,
            String payrollType,
            int versionNo,
            LocalDate from,
            Long circularOrderId,
            Long approvalId) {
        RateSet set = new RateSet();
        set.rateSetCode = code;
        set.rateSetName = name;
        set.payrollType = payrollType;
        set.versionNo = versionNo;
        set.effectiveFrom = from;
        set.circularOrderId = circularOrderId;
        set.approvalState = "PENDING_APPROVAL";
        set.approvalId = approvalId;
        return set;
    }

    public boolean isApproved() {
        return "APPROVED".equals(approvalState);
    }

    public boolean isPending() {
        return "PENDING_APPROVAL".equals(approvalState);
    }

    /** In force on {@code day}: approved and within its dates. */
    public boolean isInForceOn(LocalDate day) {
        return isApproved()
                && !day.isBefore(effectiveFrom)
                && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    public void approve() {
        if (!isPending()) {
            throw new IllegalStateException("Not pending");
        }
        approvalState = "APPROVED";
    }

    public void reject() {
        if (isPending()) {
            approvalState = "REJECTED";
        }
    }

    /** Superseded: the last day in force is the day before the successor starts. */
    public void endBefore(LocalDate successorFrom) {
        effectiveTo = successorFrom.minusDays(1);
    }

    public Long getRateSetId() {
        return rateSetId;
    }

    public String getRateSetCode() {
        return rateSetCode;
    }

    public String getRateSetName() {
        return rateSetName;
    }

    public String getPayrollType() {
        return payrollType;
    }

    public Long getLocationId() {
        return locationId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public Long getCircularOrderId() {
        return circularOrderId;
    }

    public String getApprovalState() {
        return approvalState;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
