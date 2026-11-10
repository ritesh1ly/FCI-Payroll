package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code external_income_declaration}: one external savings or other income entry of a member for a
 * financial year, recorded on I-18 by the Payroll Maker with a mandatory supporting document
 * (target DDL v4.0 Section 11; SRS-7-11, SRS-7-25; BR-TAX-007, BR-TAX-015). Only APPROVED entries
 * feed the tax computation. Never deleted: a saved entry is cancelled, a decided one stays.
 */
@Entity
@Table(name = "external_income_declaration")
public class ExternalIncomeDeclaration {

    /** {@code entry_type}. */
    public enum Type {
        EXTERNAL_SAVINGS,
        OTHER_INCOME
    }

    /** {@code entry_status}. */
    public enum Status {
        MAKER_SAVED,
        PENDING_CHECKER,
        APPROVED,
        REJECTED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ext_income_id")
    private Long extIncomeId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "entry_type", nullable = false, length = 20)
    private String entryType;

    @Column(name = "section_code", length = 20)
    private String sectionCode;

    @Column(name = "income_head", length = 60)
    private String incomeHead;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "tds_other_source", nullable = false, precision = 14, scale = 2)
    private BigDecimal tdsOtherSource;

    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    @Column(name = "entry_status", nullable = false, length = 15)
    private String entryStatus;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "entered_by", nullable = false, length = 50)
    private String enteredBy;

    @Column(name = "entered_at", nullable = false)
    private Instant enteredAt;

    /** {@code row_version}: two Makers never overwrite each other (SRS-NF-26). */
    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected ExternalIncomeDeclaration() {}

    /** An entry as the Payroll Maker saved it, with its supporting document. */
    @SuppressWarnings("java:S107") // the DDL row, one argument per column
    public static ExternalIncomeDeclaration saved(
            Long employeeId,
            String finYear,
            Type type,
            String sectionCode,
            String incomeHead,
            String description,
            BigDecimal amount,
            BigDecimal tdsOtherSource,
            Long attachmentId,
            String by,
            Instant at) {
        ExternalIncomeDeclaration e = new ExternalIncomeDeclaration();
        e.employeeId = employeeId;
        e.finYear = finYear;
        e.entryType = type.name();
        e.sectionCode = sectionCode;
        e.incomeHead = incomeHead;
        e.description = description;
        e.amount = amount;
        e.tdsOtherSource = tdsOtherSource;
        e.attachmentId = attachmentId;
        e.entryStatus = Status.MAKER_SAVED.name();
        e.enteredBy = by;
        e.enteredAt = at;
        return e;
    }

    public Status status() {
        return Status.valueOf(entryStatus);
    }

    public Type type() {
        return Type.valueOf(entryType);
    }

    /** Sent to the Payroll Checker (approval {@code approval}). */
    public void submitted(Long approval) {
        entryStatus = Status.PENDING_CHECKER.name();
        approvalId = approval;
    }

    public void approved() {
        entryStatus = Status.APPROVED.name();
    }

    public void rejected() {
        entryStatus = Status.REJECTED.name();
    }

    /** A saved entry withdrawn by the Maker before submission (R-DEL: kept, never deleted). */
    public void cancelled() {
        entryStatus = Status.CANCELLED.name();
    }

    public Long getExtIncomeId() {
        return extIncomeId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getFinYear() {
        return finYear;
    }

    public String getSectionCode() {
        return sectionCode;
    }

    public String getIncomeHead() {
        return incomeHead;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getTdsOtherSource() {
        return tdsOtherSource;
    }

    public Long getAttachmentId() {
        return attachmentId;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public String getEnteredBy() {
        return enteredBy;
    }

    public Instant getEnteredAt() {
        return enteredAt;
    }
}
