package in.gov.fci.payroll.payroll.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.type.YesNoConverter;

/**
 * {@code payroll_cycle_instance}: One payroll cycle per (payroll month, org unit) (target DDL v4.0
 * S08): INITIATED, FROZEN, COMPUTED, VERIFIED, LOCKED; forward only.
 */
@Entity
@Table(name = "payroll_cycle_instance")
public class PayrollCycleInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cycle_instance_id")
    private Long cycleInstanceId;

    @Column(name = "payroll_yymm", nullable = false, length = 6)
    private String payrollYymm;

    @Column(name = "org_unit_id", nullable = false)
    private Long orgUnitId;

    @Column(name = "org_unit_level", nullable = false, length = 10)
    private String orgUnitLevel;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "calendar_id")
    private Long calendarId;

    @Column(name = "cycle_status", nullable = false, length = 15)
    private String cycleStatus;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "posted_to_ebs_flag", nullable = false, length = 1)
    private Boolean postedToEbsFlag;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "supplementary_open", nullable = false, length = 1)
    private Boolean supplementaryOpen;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "mdp_window_open", nullable = false, length = 1)
    private Boolean mdpWindowOpen;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "remittance_sent_flag", nullable = false, length = 1)
    private Boolean remittanceSentFlag;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_partial", nullable = false, length = 1)
    private Boolean isPartial;

    @Column(name = "trigger_type", nullable = false, length = 15)
    private String triggerType;

    @Column(name = "initiated_by", nullable = false, length = 50)
    private String initiatedBy;

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    @Column(name = "frozen_by", length = 50)
    private String frozenBy;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "verified_by", length = 50)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "locked_by", length = 50)
    private String lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "lock_otp_challenge_id")
    private Long lockOtpChallengeId;

    @Column(name = "freeze_otp_challenge_id")
    private Long freezeOtpChallengeId;

    @Column(name = "current_run_id")
    private Long currentRunId;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected PayrollCycleInstance() {}

    public Long getCycleInstanceId() {
        return cycleInstanceId;
    }

    public String getPayrollYymm() {
        return payrollYymm;
    }

    public Long getOrgUnitId() {
        return orgUnitId;
    }

    public String getOrgUnitLevel() {
        return orgUnitLevel;
    }

    public String getFinYear() {
        return finYear;
    }

    public Long getCalendarId() {
        return calendarId;
    }

    public String getCycleStatus() {
        return cycleStatus;
    }

    public boolean isPostedToEbsFlag() {
        return Boolean.TRUE.equals(postedToEbsFlag);
    }

    public boolean isSupplementaryOpen() {
        return Boolean.TRUE.equals(supplementaryOpen);
    }

    public boolean isMdpWindowOpen() {
        return Boolean.TRUE.equals(mdpWindowOpen);
    }

    public boolean isRemittanceSentFlag() {
        return Boolean.TRUE.equals(remittanceSentFlag);
    }

    public boolean isIsPartial() {
        return Boolean.TRUE.equals(isPartial);
    }

    public String getTriggerType() {
        return triggerType;
    }

    public String getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getInitiatedAt() {
        return initiatedAt;
    }

    public String getFrozenBy() {
        return frozenBy;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public String getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public Long getLockOtpChallengeId() {
        return lockOtpChallengeId;
    }

    public Long getFreezeOtpChallengeId() {
        return freezeOtpChallengeId;
    }

    public Long getCurrentRunId() {
        return currentRunId;
    }
}
