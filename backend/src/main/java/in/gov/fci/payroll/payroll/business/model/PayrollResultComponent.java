package in.gov.fci.payroll.payroll.business.model;

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
 * {@code payroll_result_component}: One signed component amount of a payroll result (target DDL
 * v4.0 S09); immutable once locked.
 */
@Entity
@Table(name = "payroll_result_component")
public class PayrollResultComponent {

    // Oracle: the primary key also carries the partition key(s); the identity column alone is
    // unique.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_component_id")
    private Long resultComponentId;

    @Column(name = "payroll_result_id", nullable = false)
    private Long payrollResultId;

    @Column(name = "cycle_instance_id", nullable = false)
    private Long cycleInstanceId;

    @Column(name = "org_unit_id", nullable = false)
    private Long orgUnitId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "component_id", nullable = false)
    private Long componentId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "rule_version_id")
    private Long ruleVersionId;

    @Column(name = "rate_applied", precision = 14, scale = 4)
    private BigDecimal rateApplied;

    @Column(name = "basis_amount", precision = 14, scale = 2)
    private BigDecimal basisAmount;

    @Column(name = "run_type", nullable = false, length = 12)
    private String runType;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_locked", nullable = false, length = 1)
    private Boolean isLocked;

    @Column(name = "payroll_yymm", nullable = false, length = 6)
    private String payrollYymm;

    @Column(name = "period_date", nullable = false)
    private LocalDate periodDate;

    @Column(name = "arrear_detail_id")
    private Long arrearDetailId;

    @Column(name = "legacy_pay_code")
    private Integer legacyPayCode;

    protected PayrollResultComponent() {}

    public Long getResultComponentId() {
        return resultComponentId;
    }

    public Long getPayrollResultId() {
        return payrollResultId;
    }

    public Long getCycleInstanceId() {
        return cycleInstanceId;
    }

    public Long getOrgUnitId() {
        return orgUnitId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getComponentId() {
        return componentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Long getRuleVersionId() {
        return ruleVersionId;
    }

    public BigDecimal getRateApplied() {
        return rateApplied;
    }

    public BigDecimal getBasisAmount() {
        return basisAmount;
    }

    public String getRunType() {
        return runType;
    }

    public boolean isIsLocked() {
        return Boolean.TRUE.equals(isLocked);
    }

    public String getPayrollYymm() {
        return payrollYymm;
    }

    public LocalDate getPeriodDate() {
        return periodDate;
    }

    public Long getArrearDetailId() {
        return arrearDetailId;
    }

    public Integer getLegacyPayCode() {
        return legacyPayCode;
    }
}
