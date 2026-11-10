package in.gov.fci.payroll.cpf.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.hibernate.type.YesNoConverter;

/**
 * {@code cpf_sanction_tier}: one tier action on one pass of a CPF sanction (target DDL v4.0 Section
 * 10; SRS-6-4): tier 1 Unit CPF Administrator, tier 2 Zonal / HQ CPF Division, tier 3 Competent
 * Authority with OTP. A return opens a new pass; one user acts at most once per pass ({@code
 * uk_cpf_tier_actor}). Written once, never changed.
 */
@Entity
@Table(name = "cpf_sanction_tier")
public class CpfSanctionTier {

    /** {@code tier_code} by {@code tier_no} (DDL {@code chk_cpf_tier_code}). */
    public static final List<String> TIER_CODES =
            List.of("UNIT_CPF_ADMIN", "ZONAL_HQ_CPF_DIVISION", "COMPETENT_AUTHORITY");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tier_action_id")
    private Long tierActionId;

    @Column(name = "sanction_id", nullable = false)
    private Long sanctionId;

    @Column(name = "pass_no", nullable = false)
    private Integer passNo;

    @Column(name = "tier_no", nullable = false)
    private Integer tierNo;

    @Column(name = "tier_code", nullable = false, length = 25)
    private String tierCode;

    @Column(name = "tier_action", nullable = false, length = 15)
    private String tierAction;

    @Column(name = "acted_by", nullable = false, length = 50)
    private String actedBy;

    @Column(name = "acting_location_id")
    private Long actingLocationId;

    @Column(name = "acted_at", nullable = false)
    private Instant actedAt;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "uan_kyc_verified", length = 1)
    private Boolean uanKycVerified;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "documents_verified", length = 1)
    private Boolean documentsVerified;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "eligibility_checked", length = 1)
    private Boolean eligibilityChecked;

    @Column(name = "ceiling_amount")
    private BigDecimal ceilingAmount;

    @Column(name = "capped_amount")
    private BigDecimal cappedAmount;

    @Column(name = "remark", length = 1000)
    private String remark;

    @Column(name = "otp_challenge_id")
    private Long otpChallengeId;

    @Column(name = "approval_id")
    private Long approvalId;

    protected CpfSanctionTier() {}

    /** One tier action as it happened. */
    @SuppressWarnings("java:S107") // the DDL row
    public static CpfSanctionTier acted(
            Long sanctionId,
            int passNo,
            int tierNo,
            String action,
            String actor,
            Long locationId,
            Instant at,
            String remark) {
        CpfSanctionTier t = new CpfSanctionTier();
        t.sanctionId = sanctionId;
        t.passNo = passNo;
        t.tierNo = tierNo;
        t.tierCode = TIER_CODES.get(tierNo - 1);
        t.tierAction = action;
        t.actedBy = actor;
        t.actingLocationId = locationId;
        t.actedAt = at;
        t.remark = remark;
        return t;
    }

    /** Tier 1: what the Unit CPF Administrator verified. */
    public CpfSanctionTier verified(boolean uanKyc, boolean documents) {
        uanKycVerified = uanKyc;
        documentsVerified = documents;
        return this;
    }

    /** Tier 2: the eligibility checked, the ceiling and the amount the sanction goes ahead at. */
    public CpfSanctionTier validated(BigDecimal ceiling, BigDecimal capped, Long approval) {
        eligibilityChecked = true;
        ceilingAmount = ceiling;
        cappedAmount = capped;
        approvalId = approval;
        return this;
    }

    /** Tier 3: the approval decision and its OTP. */
    public CpfSanctionTier decided(Long approval, Long otpChallenge) {
        approvalId = approval;
        otpChallengeId = otpChallenge;
        return this;
    }

    public Long getSanctionId() {
        return sanctionId;
    }

    public Integer getPassNo() {
        return passNo;
    }

    public Integer getTierNo() {
        return tierNo;
    }

    public String getTierCode() {
        return tierCode;
    }

    public String getTierAction() {
        return tierAction;
    }

    public String getActedBy() {
        return actedBy;
    }

    public Instant getActedAt() {
        return actedAt;
    }

    public String getRemark() {
        return remark;
    }

    public BigDecimal getCeilingAmount() {
        return ceilingAmount;
    }

    public BigDecimal getCappedAmount() {
        return cappedAmount;
    }

    public Long getOtpChallengeId() {
        return otpChallengeId;
    }
}
