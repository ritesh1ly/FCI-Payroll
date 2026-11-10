package in.gov.fci.payroll.penalty.business.model;

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
 * {@code over_and_above_payment}: an additional or ad-hoc payment recorded on I-06 (target DDL v4.0
 * Section 19; SRS-14-2, SRS-14-5, SRS-14-14, BR-PEN2-002): its pay component (taxable as the
 * component is), the sanction reference and date (after the application date, {@code
 * chk_over_above_sanction_dt}), approved by the Payroll Checker before posting. Never deleted: a
 * cancellation is a status (SRS-G-37, SRS-14-20).
 */
@Entity
@Table(name = "over_and_above_payment")
public class OverAndAbovePayment {

    /** {@code payment_status} (DDL CHECK). */
    public enum Status {
        RECORDED,
        SUBMITTED,
        APPROVED,
        PAID,
        REJECTED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "over_above_id")
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "component_id")
    private Long componentId;

    @Column(name = "payroll_yymm", length = 6)
    private String payrollYymm;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "taxable_flag", nullable = false, length = 1)
    private Boolean taxable;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "eps_applicable_flag", nullable = false, length = 1)
    private Boolean epsApplicable;

    @Column(name = "application_date")
    private LocalDate applicationDate;

    @Column(name = "sanction_reference", length = 100)
    private String sanctionReference;

    @Column(name = "sanction_date")
    private LocalDate sanctionDate;

    @Column(name = "invoice_status", nullable = false, length = 20)
    private String invoiceStatus;

    /** SRS l.3030 / SRS-G-37: when the invoice went to APPS (written by the APPS interface). */
    @Column(name = "invoice_sent_at", insertable = false, updatable = false)
    private java.time.Instant invoiceSentAt;

    @Column(name = "apps_cancel_status", nullable = false, length = 15)
    private String appsCancelStatus;

    @Column(name = "ap_invoice_id", insertable = false, updatable = false)
    private Long apInvoiceId;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "invoice_locked_flag", nullable = false, length = 1)
    private Boolean invoiceLocked;

    @Column(name = "payment_status", nullable = false, length = 15)
    private String paymentStatus;

    @Column(name = "approval_id")
    private Long approvalId;

    protected OverAndAbovePayment() {}

    /** A payment submitted for the Checker. */
    @SuppressWarnings("java:S107") // the DDL row
    public static OverAndAbovePayment submitted(
            Long employeeId,
            Long componentId,
            boolean taxable,
            String payrollYymm,
            BigDecimal amount,
            LocalDate applicationDate,
            String sanctionReference,
            LocalDate sanctionDate) {
        OverAndAbovePayment p = new OverAndAbovePayment();
        p.employeeId = employeeId;
        p.componentId = componentId;
        p.taxable = taxable;
        p.epsApplicable = false;
        p.payrollYymm = payrollYymm;
        p.amount = amount;
        p.applicationDate = applicationDate;
        p.sanctionReference = sanctionReference;
        p.sanctionDate = sanctionDate;
        p.invoiceStatus = "NOT_RAISED";
        p.appsCancelStatus = "NOT_REQUESTED";
        p.invoiceLocked = false;
        p.paymentStatus = Status.SUBMITTED.name();
        return p;
    }

    public void approval(Long id) {
        this.approvalId = id;
    }

    public void status(Status next) {
        this.paymentStatus = next.name();
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getComponentId() {
        return componentId;
    }

    public String getPayrollYymm() {
        return payrollYymm;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public boolean isTaxable() {
        return Boolean.TRUE.equals(taxable);
    }

    public LocalDate getApplicationDate() {
        return applicationDate;
    }

    public String getSanctionReference() {
        return sanctionReference;
    }

    public LocalDate getSanctionDate() {
        return sanctionDate;
    }

    public java.time.Instant getInvoiceSentAt() {
        return invoiceSentAt;
    }

    public String getInvoiceStatus() {
        return invoiceStatus;
    }

    public Status status() {
        return Status.valueOf(paymentStatus);
    }

    public Long getApInvoiceId() {
        return apInvoiceId;
    }

    public String getAppsCancelStatus() {
        return appsCancelStatus;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
