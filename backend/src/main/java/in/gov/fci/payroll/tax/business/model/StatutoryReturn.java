package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code statutory_return}: a quarterly Form 24Q of a paying unit, or a correction against one
 * (target DDL v4.0 Section 11; SRS-7-7, SRS-7-8, SRS-7-31). Generated from the locked quarter under
 * the governing M-17 version, validated (itemised checks and the prescribed utility), released by
 * the Payroll Checker, then filed by FCI's authorised signatory and recorded. One original per
 * year, quarter and office ({@code uk_statutory_return}).
 */
@Entity
@Table(name = "statutory_return")
public class StatutoryReturn {

    /** {@code return_status}. */
    public enum Status {
        DRAFT,
        GENERATED,
        VALIDATED,
        RELEASED,
        FILED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "return_id")
    private Long returnId;

    @Column(name = "return_type", nullable = false, length = 20)
    private String returnType;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "period_code", nullable = false, length = 10)
    private String periodCode;

    @Column(name = "org_unit_id")
    private Long orgUnitId;

    @Column(name = "deductee_count")
    private Long deducteeCount;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "definition_version_id")
    private Long definitionVersionId;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "validated_flag", nullable = false, length = 1)
    private String validatedFlag;

    @Column(name = "validation_utility", length = 100)
    private String validationUtility;

    @Column(name = "validation_result", length = 2000)
    private String validationResult;

    @Column(name = "return_status", nullable = false, length = 20)
    private String returnStatus;

    @Column(name = "released_by", length = 50)
    private String releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "is_correction", nullable = false, length = 1)
    private String correction;

    @Column(name = "corrects_return_id")
    private Long correctsReturnId;

    protected StatutoryReturn() {}

    /** A return as generated for a quarter of a paying unit. */
    public static StatutoryReturn generated(
            String type,
            String finYear,
            String quarter,
            Long unit,
            Long version,
            Long corrects,
            Instant at) {
        StatutoryReturn r = new StatutoryReturn();
        r.returnType = type;
        r.finYear = finYear;
        r.periodCode = quarter;
        r.orgUnitId = unit;
        r.definitionVersionId = version;
        r.generatedAt = at;
        r.validatedFlag = "N";
        r.returnStatus = Status.GENERATED.name();
        r.correction = corrects == null ? "N" : "Y";
        r.correctsReturnId = corrects;
        r.deducteeCount = 0L;
        r.totalAmount = BigDecimal.ZERO;
        return r;
    }

    /** The deductee count and the TDS of the return. */
    public void totals(long deductees, BigDecimal total) {
        deducteeCount = deductees;
        totalAmount = total;
    }

    /**
     * A return the background job (TAX_24Q_RETURN) is still writing: DRAFT (DDL return_status)
     * until its last member is written, so it can be neither validated nor released half done.
     */
    public void drafting() {
        returnStatus = Status.DRAFT.name();
    }

    /** The generation finished: a DRAFT becomes GENERATED (a screen generation already is). */
    public void generationFinished() {
        if (status() == Status.DRAFT) {
            returnStatus = Status.GENERATED.name();
        }
    }

    /** SRS-7-8: the validation outcome; VALIDATED only when every check and the utility passed. */
    public void validated(boolean passed, String utility, String result) {
        validatedFlag = passed ? "Y" : "N";
        validationUtility = utility;
        validationResult =
                result == null || result.length() <= 2000 ? result : result.substring(0, 2000);
        returnStatus = passed ? Status.VALIDATED.name() : Status.GENERATED.name();
    }

    public void released(String by, Instant at) {
        returnStatus = Status.RELEASED.name();
        releasedBy = by;
        releasedAt = at;
    }

    public void filed() {
        returnStatus = Status.FILED.name();
    }

    /**
     * C-11 alternate flow: the return regenerated in place after the underlying data was corrected
     * (one row per quarter, DDL uk_statutory_return); the earlier validation no longer holds.
     */
    public void regenerated(Long version, Instant at) {
        definitionVersionId = version;
        generatedAt = at;
        validatedFlag = "N";
        validationUtility = null;
        validationResult = null;
        returnStatus = Status.GENERATED.name();
        deducteeCount = 0L;
        totalAmount = BigDecimal.ZERO;
    }

    /**
     * Draft, generated or validated: neither with the Checker nor released, so it may be
     * regenerated (a DRAFT left by an abandoned background job is generated again).
     */
    public boolean isRegenerable() {
        return status() == Status.DRAFT
                || status() == Status.GENERATED
                || status() == Status.VALIDATED;
    }

    public Status status() {
        return Status.valueOf(returnStatus);
    }

    public boolean isCorrection() {
        return "Y".equals(correction);
    }

    public Long getReturnId() {
        return returnId;
    }

    public String getReturnType() {
        return returnType;
    }

    public String getFinYear() {
        return finYear;
    }

    public String getPeriodCode() {
        return periodCode;
    }

    public Long getOrgUnitId() {
        return orgUnitId;
    }

    public Long getDeducteeCount() {
        return deducteeCount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Long getDefinitionVersionId() {
        return definitionVersionId;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public boolean isValidated() {
        return "Y".equals(validatedFlag);
    }

    public String getValidationUtility() {
        return validationUtility;
    }

    public String getValidationResult() {
        return validationResult;
    }

    public String getReleasedBy() {
        return releasedBy;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public Long getCorrectsReturnId() {
        return correctsReturnId;
    }
}
