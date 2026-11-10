package in.gov.fci.payroll.configuration.business.model;

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
 * {@code other_parameter}: M-23 Other Parameters, ceilings, deputation and shift windows (target
 * DDL v4.0 S19), effective-dated.
 */
@Entity
@Table(name = "other_parameter")
public class OtherParameter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "parameter_id")
    private Long parameterId;

    @Column(name = "parameter_code", nullable = false, length = 40)
    private String parameterCode;

    @Column(name = "parameter_name", nullable = false, length = 200)
    private String parameterName;

    @Column(name = "numeric_value", precision = 18, scale = 4)
    private BigDecimal numericValue;

    @Column(name = "ceiling_value", precision = 18, scale = 4)
    private BigDecimal ceilingValue;

    @Column(name = "percent_value", precision = 9, scale = 4)
    private BigDecimal percentValue;

    @Column(name = "window_from_time", length = 5)
    private String windowFromTime;

    @Column(name = "window_to_time", length = 5)
    private String windowToTime;

    @Column(name = "instalment_count")
    private Integer instalmentCount;

    @Column(name = "payroll_type_scope", length = 100)
    private String payrollTypeScope;

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

    protected OtherParameter() {}

    /**
     * A proposed version; in force only once its approval is APPROVED (other_parameter has no state
     * column).
     */
    public static OtherParameter propose(
            String code,
            String name,
            BigDecimal numericValue,
            BigDecimal ceilingValue,
            BigDecimal percentValue,
            String windowFromTime,
            String windowToTime,
            Integer instalmentCount,
            String payrollTypeScope,
            int versionNo,
            LocalDate from,
            boolean confirmed,
            Long approvalId) {
        OtherParameter p = new OtherParameter();
        p.parameterCode = code;
        p.parameterName = name;
        p.numericValue = numericValue;
        p.ceilingValue = ceilingValue;
        p.percentValue = percentValue;
        p.windowFromTime = windowFromTime;
        p.windowToTime = windowToTime;
        p.instalmentCount = instalmentCount;
        p.payrollTypeScope = payrollTypeScope;
        p.versionNo = versionNo;
        p.effectiveFrom = from;
        p.isConfirmed = confirmed;
        p.approvalId = approvalId;
        return p;
    }

    public boolean coversDay(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    public void endBefore(LocalDate successorFrom) {
        effectiveTo = successorFrom.minusDays(1);
    }

    public Long getParameterId() {
        return parameterId;
    }

    public String getParameterCode() {
        return parameterCode;
    }

    public String getParameterName() {
        return parameterName;
    }

    public BigDecimal getNumericValue() {
        return numericValue;
    }

    public BigDecimal getCeilingValue() {
        return ceilingValue;
    }

    public BigDecimal getPercentValue() {
        return percentValue;
    }

    public String getWindowFromTime() {
        return windowFromTime;
    }

    public String getWindowToTime() {
        return windowToTime;
    }

    public Integer getInstalmentCount() {
        return instalmentCount;
    }

    public String getPayrollTypeScope() {
        return payrollTypeScope;
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

    public boolean isIsConfirmed() {
        return Boolean.TRUE.equals(isConfirmed);
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
