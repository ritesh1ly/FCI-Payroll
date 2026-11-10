package in.gov.fci.payroll.tax.business.model;

import in.gov.fci.payroll.platform.logging.PiiMasks;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code statutory_return_deductee}: one member of a Form 24Q (target DDL v4.0 Section 11; SRS-7-7,
 * SRS-7-13, SRS-7-23). The PAN is held in full because the return file prescribes it, and is never
 * returned to a screen (masked there); its structure check and the higher-rate flag travel with it.
 * One row per member and return ({@code uk_return_deductee}).
 */
@Entity
@Table(name = "statutory_return_deductee")
public class ReturnDeductee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "deductee_id")
    private Long deducteeId;

    @Column(name = "return_id", nullable = false)
    private Long returnId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    /** Personal data (PII, sensitive): for the return file only (SRS-7-23). */
    @Column(name = "pan_number", length = 20)
    private String panNumber;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pan_valid_flag", nullable = false, length = 1)
    private String panValid;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "higher_rate_applied", nullable = false, length = 1)
    private String higherRateApplied;

    @Column(name = "amount_paid", precision = 14, scale = 2)
    private BigDecimal amountPaid;

    @Column(name = "tax_deducted", precision = 14, scale = 2)
    private BigDecimal taxDeducted;

    @Column(name = "challan_id")
    private Long challanId;

    protected ReturnDeductee() {}

    @SuppressWarnings("java:S107") // the DDL row, one argument per column
    public static ReturnDeductee of(
            Long returnId,
            Long employeeId,
            String pan,
            boolean panValid,
            boolean higherRate,
            BigDecimal paid,
            BigDecimal tax,
            Long challanId) {
        ReturnDeductee d = new ReturnDeductee();
        d.returnId = returnId;
        d.employeeId = employeeId;
        d.panNumber = pan;
        d.panValid = panValid ? "Y" : "N";
        d.higherRateApplied = higherRate ? "Y" : "N";
        d.amountPaid = paid;
        d.taxDeducted = tax;
        d.challanId = challanId;
        return d;
    }

    /**
     * SRS-7-13: the PAN as HRMS holds it when the return is validated (a PAN corrected after the
     * generation reaches the return); the TDS and the higher-rate flag stay as deducted.
     */
    public void panRefreshed(String pan, boolean valid) {
        panNumber = pan;
        panValid = valid ? "Y" : "N";
    }

    /**
     * The deductee's figures on a regeneration. A member no longer a deductee keeps the row with
     * nothing paid or deducted (never deleted, R-DEL) and is left out of the return.
     */
    @SuppressWarnings("java:S107") // one argument per column a regeneration rewrites
    public void refigured(
            String pan,
            boolean valid,
            boolean higherRate,
            BigDecimal paid,
            BigDecimal tax,
            Long challan) {
        panNumber = pan;
        panValid = valid ? "Y" : "N";
        higherRateApplied = higherRate ? "Y" : "N";
        amountPaid = paid;
        taxDeducted = tax;
        challanId = challan;
    }

    /** In the return: TDS was deducted in the quarter. */
    public boolean isInReturn() {
        return taxDeducted != null && taxDeducted.signum() > 0;
    }

    /**
     * The PAN as a screen may show it: the last four characters only (SRS-7-23), masked the one way
     * every module shows it ({@link PiiMasks#pan}; R-PII, BMAD TR-035 follow-up).
     */
    public String maskedPan() {
        return PiiMasks.pan(panNumber);
    }

    public Long getDeducteeId() {
        return deducteeId;
    }

    public Long getReturnId() {
        return returnId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public boolean isPanValid() {
        return "Y".equals(panValid);
    }

    public boolean isHigherRateApplied() {
        return "Y".equals(higherRateApplied);
    }

    public BigDecimal getAmountPaid() {
        return amountPaid;
    }

    public BigDecimal getTaxDeducted() {
        return taxDeducted;
    }

    public Long getChallanId() {
        return challanId;
    }
}
