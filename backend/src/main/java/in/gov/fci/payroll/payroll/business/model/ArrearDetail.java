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
 * {@code arrear_detail}: The differential of one member, component and period of an arrear event
 * (target DDL v4.0 S13).
 */
@Entity
@Table(name = "arrear_detail")
public class ArrearDetail {

    // Oracle: the primary key also carries the partition key(s); the identity column alone is
    // unique.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "arrear_detail_id")
    private Long arrearDetailId;

    @Column(name = "arrear_event_id", nullable = false)
    private Long arrearEventId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "component_id", nullable = false)
    private Long componentId;

    @Column(name = "differential_yymm", nullable = false, length = 6)
    private String differentialYymm;

    @Column(name = "original_amount", precision = 14, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "recomputed_amount", precision = 14, scale = 2)
    private BigDecimal recomputedAmount;

    @Column(name = "arrear_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal arrearAmount;

    @Column(name = "rule_version_id")
    private Long ruleVersionId;

    @Column(name = "instalment_no")
    private Integer instalmentNo;

    @Column(name = "arrear_status", nullable = false, length = 20)
    private String arrearStatus;

    @Column(name = "ap_invoice_id")
    private Long apInvoiceId;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "invoice_locked_flag", nullable = false, length = 1)
    private Boolean invoiceLockedFlag;

    @Column(name = "period_date", nullable = false)
    private LocalDate periodDate;

    protected ArrearDetail() {}

    public Long getArrearDetailId() {
        return arrearDetailId;
    }

    public Long getArrearEventId() {
        return arrearEventId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getComponentId() {
        return componentId;
    }

    public String getDifferentialYymm() {
        return differentialYymm;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getRecomputedAmount() {
        return recomputedAmount;
    }

    public BigDecimal getArrearAmount() {
        return arrearAmount;
    }

    public Long getRuleVersionId() {
        return ruleVersionId;
    }

    public Integer getInstalmentNo() {
        return instalmentNo;
    }

    public String getArrearStatus() {
        return arrearStatus;
    }

    public Long getApInvoiceId() {
        return apInvoiceId;
    }

    public boolean isInvoiceLockedFlag() {
        return Boolean.TRUE.equals(invoiceLockedFlag);
    }

    public LocalDate getPeriodDate() {
        return periodDate;
    }
}
