package in.gov.fci.payroll.cpf.business.model;

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
 * {@code employee_pf_detail}: one monthly CPF / PF ledger row of a member's ledger (target DDL v4.0
 * Section 10; BR-CPF-017, BR-CPF-018, BR-CPF-023): the contributions of one payment type of a
 * month, with the zone they were booked to. Written only through the ledger's posting path, never
 * changed.
 */
@Entity
@Table(name = "employee_pf_detail")
public class EmployeePfDetail {

    /** {@code payment_type} (DDL CHECK). */
    public enum PaymentType {
        SALARY,
        INCENTIVE,
        SUPPLEMENTARY,
        ARREAR,
        ADJUSTMENT,
        DEPUTATION_RECEIPT
    }

    // Oracle: the primary key also carries the partition key; the identity column alone is unique.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pf_detail_id")
    private Long pfDetailId;

    @Column(name = "pf_header_id", nullable = false)
    private Long pfHeaderId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "payroll_yymm", nullable = false, length = 6)
    private String payrollYymm;

    @Column(name = "payment_type", nullable = false, length = 20)
    private String paymentType;

    @Column(name = "epf_wage_base", precision = 12, scale = 2)
    private BigDecimal epfWageBase;

    @Column(name = "employee_contribution", nullable = false, precision = 12, scale = 2)
    private BigDecimal employeeContribution;

    @Column(name = "employer_contribution", nullable = false, precision = 12, scale = 2)
    private BigDecimal employerContribution;

    @Column(name = "vpf_contribution", nullable = false, precision = 12, scale = 2)
    private BigDecimal vpfContribution;

    @Column(name = "eps_contribution", nullable = false, precision = 12, scale = 2)
    private BigDecimal epsContribution;

    @Column(name = "pohw_contribution", precision = 12, scale = 2)
    private BigDecimal pohwContribution;

    @Column(name = "rounding_adjustment", precision = 8, scale = 2)
    private BigDecimal roundingAdjustment;

    @Column(name = "interest_amount", precision = 12, scale = 2)
    private BigDecimal interestAmount;

    @Column(name = "taxable_portion", precision = 12, scale = 2)
    private BigDecimal taxablePortion;

    @Column(name = "non_taxable_portion", precision = 12, scale = 2)
    private BigDecimal nonTaxablePortion;

    @Column(name = "zone_id")
    private Long zoneId;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_zero_salary_member", nullable = false, length = 1)
    private Boolean isZeroSalaryMember;

    @Column(name = "period_date", nullable = false)
    private LocalDate periodDate;

    protected EmployeePfDetail() {}

    /** The monthly row of one posted contribution (amounts as the posting carries them). */
    public static EmployeePfDetail of(
            Long headerId,
            Long employeeId,
            String yymm,
            PaymentType type,
            BigDecimal wageBase,
            BigDecimal employee,
            BigDecimal employer,
            BigDecimal vpf,
            BigDecimal eps,
            BigDecimal pohw,
            BigDecimal rounding,
            Long zoneId,
            LocalDate periodDate) {
        EmployeePfDetail d = new EmployeePfDetail();
        d.pfHeaderId = headerId;
        d.employeeId = employeeId;
        d.payrollYymm = yymm;
        d.paymentType = type.name();
        d.epfWageBase = wageBase;
        d.employeeContribution = employee;
        d.employerContribution = employer;
        d.vpfContribution = vpf;
        d.epsContribution = eps;
        d.pohwContribution = pohw;
        d.roundingAdjustment = rounding;
        d.zoneId = zoneId;
        d.isZeroSalaryMember = false;
        d.periodDate = periodDate;
        return d;
    }

    public Long getPfDetailId() {
        return pfDetailId;
    }

    public Long getPfHeaderId() {
        return pfHeaderId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getPayrollYymm() {
        return payrollYymm;
    }

    public String getPaymentType() {
        return paymentType;
    }

    public BigDecimal getEpfWageBase() {
        return epfWageBase;
    }

    public BigDecimal getEmployeeContribution() {
        return employeeContribution;
    }

    public BigDecimal getEmployerContribution() {
        return employerContribution;
    }

    public BigDecimal getVpfContribution() {
        return vpfContribution;
    }

    public BigDecimal getEpsContribution() {
        return epsContribution;
    }

    public BigDecimal getPohwContribution() {
        return pohwContribution;
    }

    public BigDecimal getRoundingAdjustment() {
        return roundingAdjustment;
    }

    public BigDecimal getInterestAmount() {
        return interestAmount;
    }

    public BigDecimal getTaxablePortion() {
        return taxablePortion;
    }

    public BigDecimal getNonTaxablePortion() {
        return nonTaxablePortion;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public boolean isIsZeroSalaryMember() {
        return Boolean.TRUE.equals(isZeroSalaryMember);
    }

    public LocalDate getPeriodDate() {
        return periodDate;
    }
}
