package in.gov.fci.payroll.cpf.business.model;

import in.gov.fci.payroll.cpf.business.SanctionRules.Stage;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code pf_sanction}: a CPF advance, part-final or final request on its way through the three-tier
 * chain (target DDL v4.0 Section 10; SRS-6-4, SRS-6-26, SRS-6-38; BR-CPF-030, 031). {@code
 * workflow_stage} is where it is now; every tier action is a {@link CpfSanctionTier} row. The
 * withdrawal register (SRS-6-55) is read from this table. Never deleted: cancelled with a reason.
 */
@Entity
@Table(name = "pf_sanction")
public class PfSanction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sanction_id")
    private Long sanctionId;

    @Column(name = "system_sanction_number", length = 30)
    private String systemSanctionNumber;

    @Column(name = "sanction_zone_id")
    private Long sanctionZoneId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "fund_type", nullable = false, length = 10)
    private String fundType;

    @Column(name = "sanction_type", nullable = false, length = 20)
    private String sanctionType;

    @Column(name = "purpose_id")
    private Long purposeId;

    @Column(name = "limit_rule_version_id")
    private Long limitRuleVersionId;

    @Column(name = "requested_amount")
    private BigDecimal requestedAmount;

    @Column(name = "computed_ceiling_amount")
    private BigDecimal computedCeilingAmount;

    @Column(name = "sanctioned_amount")
    private BigDecimal sanctionedAmount;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "was_capped", nullable = false, length = 1)
    private Boolean wasCapped;

    @Column(name = "sanction_date")
    private LocalDate sanctionDate;

    @Column(name = "sanctioning_location_id")
    private Long sanctioningLocationId;

    @Column(name = "workflow_stage", nullable = false, length = 30)
    private String workflowStage;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "kyc_verified_flag", nullable = false, length = 1)
    private Boolean kycVerifiedFlag;

    @Column(name = "cancel_reason", length = 1000)
    private String cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "approval_id")
    private Long approvalId;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected PfSanction() {}

    /** A member's request entering the chain at tier 1 (SRS-6-4). */
    public static PfSanction requested(
            Long employeeId, String type, Long purposeId, BigDecimal requested, Long zoneId) {
        PfSanction s = new PfSanction();
        s.employeeId = employeeId;
        s.fundType = "CPF";
        s.sanctionType = type;
        s.purposeId = purposeId;
        s.requestedAmount = requested;
        s.sanctionZoneId = zoneId;
        s.wasCapped = false;
        s.kycVerifiedFlag = false;
        s.workflowStage = Stage.UNIT_VERIFICATION.name();
        return s;
    }

    /** Tier 1 forwarded it, the UAN and KYC verified. */
    public void forwarded() {
        kycVerifiedFlag = true;
        workflowStage = Stage.ZONAL_HQ_VALIDATION.name();
    }

    /**
     * Tier 2 validated it: the ceiling in force on the request date and the amount it goes ahead at
     * (capped, not rejected: SRS-6-26), now with the Competent Authority.
     */
    public void validated(
            BigDecimal ceiling,
            BigDecimal sanctioned,
            boolean capped,
            Long ruleVersionId,
            Long approvalId) {
        computedCeilingAmount = ceiling;
        sanctionedAmount = sanctioned;
        wasCapped = capped;
        limitRuleVersionId = ruleVersionId;
        this.approvalId = approvalId;
        workflowStage = Stage.COMPETENT_AUTHORITY.name();
    }

    /** Tier 3 sanctioned it with OTP; the payment is blocked if the balance cannot carry it. */
    public void sanctioned(String number, LocalDate date, Long locationId, boolean blocked) {
        systemSanctionNumber = number;
        sanctionDate = date;
        sanctioningLocationId = locationId;
        workflowStage = (blocked ? Stage.PAYMENT_BLOCKED : Stage.SANCTIONED).name();
    }

    /** Returned to the member (tier 1) for correction; the next submission opens a new pass. */
    public void returnedToMember() {
        workflowStage = Stage.RETURNED.name();
    }

    /** Returned to the Unit CPF Administrator for a new pass (from tier 2 or tier 3). */
    public void backToUnit() {
        computedCeilingAmount = null;
        sanctionedAmount = null;
        wasCapped = false;
        approvalId = null;
        workflowStage = Stage.UNIT_VERIFICATION.name();
    }

    /** The member resubmits a returned request: a new pass at tier 1. */
    public void resubmitted(BigDecimal requested) {
        requestedAmount = requested;
        workflowStage = Stage.UNIT_VERIFICATION.name();
    }

    public void rejected() {
        workflowStage = Stage.REJECTED.name();
    }

    /** The member withdrew it before the sanction (a status with its reason, never a delete). */
    public void cancelled(String reason, Instant at) {
        cancelReason = reason;
        cancelledAt = at;
        workflowStage = Stage.CANCELLED.name();
    }

    public Stage stage() {
        return Stage.valueOf(workflowStage);
    }

    public Long getSanctionId() {
        return sanctionId;
    }

    public String getSystemSanctionNumber() {
        return systemSanctionNumber;
    }

    public Long getSanctionZoneId() {
        return sanctionZoneId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getSanctionType() {
        return sanctionType;
    }

    public Long getPurposeId() {
        return purposeId;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public BigDecimal getComputedCeilingAmount() {
        return computedCeilingAmount;
    }

    public BigDecimal getSanctionedAmount() {
        return sanctionedAmount;
    }

    public boolean isWasCapped() {
        return Boolean.TRUE.equals(wasCapped);
    }

    public LocalDate getSanctionDate() {
        return sanctionDate;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
