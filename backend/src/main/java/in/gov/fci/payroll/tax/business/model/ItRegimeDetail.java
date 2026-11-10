package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.YesNoConverter;

/**
 * {@code it_regime_detail}: the member's tax regime for one financial year (target DDL v4.0 Section
 * 11; SRS-7-1, BR-TAX-001, BR-TAX-014). One row per member and year ({@code uk_it_regime_detail});
 * made in eSeva, verified by the Payroll Maker and approved by the Payroll Checker, then frozen
 * ({@code locked_flag}) until an authorised, audited reset.
 */
@Entity
@Table(name = "it_regime_detail")
public class ItRegimeDetail {

    /** {@code regime_code}: O = old regime, N = new regime. */
    public enum Regime {
        OLD("O"),
        NEW("N");

        private final String code;

        Regime(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Regime ofCode(String code) {
            return "O".equals(code) ? OLD : NEW;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "regime_detail_id")
    private Long regimeDetailId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "regime_code", nullable = false, length = 1)
    private String regimeCode;

    @Column(name = "elected_at", nullable = false)
    private Instant electedAt;

    @Column(name = "elected_via", nullable = false, length = 10)
    private String electedVia;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "locked_flag", nullable = false, length = 1)
    private Boolean locked;

    @Column(name = "verify_status", nullable = false, length = 15)
    private String verifyStatus;

    @Column(name = "verified_by", length = 50)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "reset_approval_id")
    private Long resetApprovalId;

    @Column(name = "reset_reason", length = 1000)
    private String resetReason;

    protected ItRegimeDetail() {}

    /** The member's election in eSeva. */
    public static ItRegimeDetail elected(
            Long employeeId, String finYear, Regime regime, Instant at) {
        ItRegimeDetail r = new ItRegimeDetail();
        r.employeeId = employeeId;
        r.finYear = finYear;
        r.regimeCode = regime.code();
        r.electedAt = at;
        r.electedVia = "ESS";
        r.locked = false;
        r.verifyStatus = "SUBMITTED";
        return r;
    }

    /** A new choice while the regime is not frozen. */
    public void elect(Regime regime, Instant at) {
        regimeCode = regime.code();
        electedAt = at;
        verifyStatus = "SUBMITTED";
    }

    /** The Payroll Maker verified the declaration it came with. */
    public void verified(String by, Instant at) {
        verifyStatus = "VERIFIED";
        verifiedBy = by;
        verifiedAt = at;
    }

    /** Returned to the member with the declaration. */
    public void returned() {
        verifyStatus = "RETURNED";
    }

    /** BR-TAX-001: approved by the Payroll Checker and frozen for the year. */
    public void approvedAndFrozen(Long approval) {
        verifyStatus = "APPROVED";
        approvalId = approval;
        locked = true;
    }

    /** FRS V0.5 §14.3: an authorised, audited reset opens the choice again. */
    public void reset(Long approval, String reason) {
        locked = false;
        verifyStatus = "SUBMITTED";
        resetApprovalId = approval;
        resetReason = reason;
    }

    public Long getRegimeDetailId() {
        return regimeDetailId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getFinYear() {
        return finYear;
    }

    public Regime getRegime() {
        return Regime.ofCode(regimeCode);
    }

    public boolean isLocked() {
        return Boolean.TRUE.equals(locked);
    }

    public String getVerifyStatus() {
        return verifyStatus;
    }

    public String getResetReason() {
        return resetReason;
    }
}
