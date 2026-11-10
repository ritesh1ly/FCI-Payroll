package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * {@code savings_declaration}: the savings and rebates of a member's year on I-18 (target DDL v4.0
 * Section 11; SRS I-18 field table): INTERNAL (saved through payroll, as configured and carried
 * forward, read-only here), EXTERNAL (written when an external savings entry is approved,
 * BR-TAX-015) and TRANSPORT_REBATE (one row per month of the year, approved by the Payroll
 * Checker). Rows are written only on approval and never changed.
 */
@Entity
@Table(name = "savings_declaration")
public class SavingsDeclaration {

    /** {@code savings_type}. */
    public enum Type {
        INTERNAL,
        EXTERNAL,
        TRANSPORT_REBATE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "savings_id")
    private Long savingsId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "savings_type", nullable = false, length = 20)
    private String savingsType;

    @Column(name = "component_id")
    private Long componentId;

    @Column(name = "agency_id")
    private Long agencyId;

    @Column(name = "annual_amount", precision = 14, scale = 2)
    private BigDecimal annualAmount;

    @Column(name = "payroll_yymm", length = 6)
    private String payrollYymm;

    @Column(name = "monthly_amount", precision = 12, scale = 2)
    private BigDecimal monthlyAmount;

    @Column(name = "section_code", length = 20)
    private String sectionCode;

    @Column(name = "external_income_id")
    private Long externalIncomeId;

    @Column(name = "approval_id")
    private Long approvalId;

    protected SavingsDeclaration() {}

    /** BR-TAX-015: the savings row of an approved external savings entry. */
    public static SavingsDeclaration external(ExternalIncomeDeclaration entry, Long approval) {
        SavingsDeclaration s = new SavingsDeclaration();
        s.employeeId = entry.getEmployeeId();
        s.finYear = entry.getFinYear();
        s.savingsType = Type.EXTERNAL.name();
        s.annualAmount = entry.getAmount();
        s.sectionCode = entry.getSectionCode();
        s.externalIncomeId = entry.getExtIncomeId();
        s.approvalId = approval;
        return s;
    }

    /** One approved month of a member's transport rebate. */
    public static SavingsDeclaration transportRebate(
            Long employeeId, String finYear, String yymm, BigDecimal monthly, Long approval) {
        SavingsDeclaration s = new SavingsDeclaration();
        s.employeeId = employeeId;
        s.finYear = finYear;
        s.savingsType = Type.TRANSPORT_REBATE.name();
        s.payrollYymm = yymm;
        s.monthlyAmount = monthly;
        s.approvalId = approval;
        return s;
    }

    public Type type() {
        return Type.valueOf(savingsType);
    }

    public Long getSavingsId() {
        return savingsId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getFinYear() {
        return finYear;
    }

    public Long getComponentId() {
        return componentId;
    }

    public BigDecimal getAnnualAmount() {
        return annualAmount;
    }

    public String getPayrollYymm() {
        return payrollYymm;
    }

    public BigDecimal getMonthlyAmount() {
        return monthlyAmount;
    }

    public String getSectionCode() {
        return sectionCode;
    }

    public Long getExternalIncomeId() {
        return externalIncomeId;
    }
}
