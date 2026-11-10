package in.gov.fci.payroll.payroll.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code payroll_result}: One member result per cycle instance and run type (target DDL v4.0 S09);
 * immutable once locked.
 */
@Entity
@Table(name = "payroll_result")
public class PayrollResult {

    // Oracle: the primary key also carries the partition key(s); the identity column alone is
    // unique.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payroll_result_id")
    private Long payrollResultId;

    @Column(name = "cycle_instance_id", nullable = false)
    private Long cycleInstanceId;

    @Column(name = "org_unit_id", nullable = false)
    private Long orgUnitId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "payroll_type", nullable = false, length = 20)
    private String payrollType;

    @Column(name = "payroll_yymm", nullable = false, length = 6)
    private String payrollYymm;

    @Column(name = "period_date", nullable = false)
    private LocalDate periodDate;

    @Column(name = "run_type", nullable = false, length = 12)
    private String runType;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "work_unit_id")
    private Long workUnitId;

    @Column(name = "simulation_run_id")
    private Long simulationRunId;

    @Column(name = "gross_earnings", nullable = false, precision = 14, scale = 2)
    private BigDecimal grossEarnings;

    @Column(name = "total_deductions", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalDeductions;

    @Column(name = "net_pay", nullable = false, precision = 14, scale = 2)
    private BigDecimal netPay;

    @Column(name = "payable_days", precision = 5, scale = 2)
    private BigDecimal payableDays;

    @Column(name = "lwp_days", precision = 5, scale = 2)
    private BigDecimal lwpDays;

    @Column(name = "present_days", precision = 5, scale = 2)
    private BigDecimal presentDays;

    @Column(name = "basic_history_id")
    private Long basicHistoryId;

    @Column(name = "result_status", nullable = false, length = 12)
    private String resultStatus;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_locked", nullable = false, length = 1)
    private Boolean isLocked;

    protected PayrollResult() {}

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

    public String getPayrollType() {
        return payrollType;
    }

    public String getPayrollYymm() {
        return payrollYymm;
    }

    public LocalDate getPeriodDate() {
        return periodDate;
    }

    public String getRunType() {
        return runType;
    }

    public Long getRunId() {
        return runId;
    }

    public Long getWorkUnitId() {
        return workUnitId;
    }

    public Long getSimulationRunId() {
        return simulationRunId;
    }

    public BigDecimal getGrossEarnings() {
        return grossEarnings;
    }

    public BigDecimal getTotalDeductions() {
        return totalDeductions;
    }

    public BigDecimal getNetPay() {
        return netPay;
    }

    public BigDecimal getPayableDays() {
        return payableDays;
    }

    public BigDecimal getLwpDays() {
        return lwpDays;
    }

    public BigDecimal getPresentDays() {
        return presentDays;
    }

    public Long getBasicHistoryId() {
        return basicHistoryId;
    }

    public String getResultStatus() {
        return resultStatus;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public boolean isIsLocked() {
        return Boolean.TRUE.equals(isLocked);
    }
}
