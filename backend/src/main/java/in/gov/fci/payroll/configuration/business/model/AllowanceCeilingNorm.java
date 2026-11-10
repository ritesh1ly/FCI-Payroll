package in.gov.fci.payroll.configuration.business.model;

import in.gov.fci.payroll.configuration.business.DatedVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code allowance_ceiling_norm}: M-15 allowance ceiling (target DDL v4.0 S19). An unconfirmed
 * ceiling raises an exception, never "unlimited".
 */
@Entity
@Table(name = "allowance_ceiling_norm")
public class AllowanceCeilingNorm implements DatedVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ceiling_norm_id")
    private Long ceilingNormId;

    @Column(name = "claim_type", nullable = false, length = 30)
    private String claimType;

    @Column(name = "ceiling_basis", length = 30)
    private String ceilingBasis;

    @Column(name = "ceiling_value", precision = 12, scale = 2)
    private BigDecimal ceilingValue;

    @Column(name = "periodicity", length = 30)
    private String periodicity;

    @Column(name = "eligibility_category", length = 40)
    private String eligibilityCategory;

    @Column(name = "designation_scope", length = 200)
    private String designationScope;

    @Column(name = "member_scope", nullable = false, length = 10)
    private String memberScope;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_confirmed", nullable = false, length = 1)
    private Boolean isConfirmed;

    @Column(name = "approval_id")
    private Long approvalId;

    protected AllowanceCeilingNorm() {}

    /** A proposed version (SRS-16-14); in force only once its approval is APPROVED. */
    public static AllowanceCeilingNorm propose(
            String claimType,
            String ceilingBasis,
            BigDecimal ceilingValue,
            String periodicity,
            String eligibilityCategory,
            String memberScope,
            int versionNo,
            LocalDate from,
            boolean confirmed,
            Long approvalId) {
        AllowanceCeilingNorm n = new AllowanceCeilingNorm();
        n.claimType = claimType;
        n.ceilingBasis = ceilingBasis;
        n.ceilingValue = ceilingValue;
        n.periodicity = periodicity;
        n.eligibilityCategory = eligibilityCategory;
        n.memberScope = memberScope;
        n.versionNo = versionNo;
        n.effectiveFrom = from;
        n.isConfirmed = confirmed;
        n.approvalId = approvalId;
        return n;
    }

    @Override
    public void endBefore(LocalDate successorFrom) {
        effectiveTo = successorFrom.minusDays(1);
    }

    public Long getCeilingNormId() {
        return ceilingNormId;
    }

    public String getClaimType() {
        return claimType;
    }

    public String getCeilingBasis() {
        return ceilingBasis;
    }

    public BigDecimal getCeilingValue() {
        return ceilingValue;
    }

    public String getPeriodicity() {
        return periodicity;
    }

    public String getEligibilityCategory() {
        return eligibilityCategory;
    }

    public String getDesignationScope() {
        return designationScope;
    }

    public String getMemberScope() {
        return memberScope;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    @Override
    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    @Override
    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public boolean isIsConfirmed() {
        return Boolean.TRUE.equals(isConfirmed);
    }

    @Override
    public Long getApprovalId() {
        return approvalId;
    }
}
