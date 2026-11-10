package in.gov.fci.payroll.general.business.model;

import in.gov.fci.payroll.general.business.AppsDates;
import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.hibernate.type.YesNoConverter;

/**
 * {@code off_cycle_payment} (target DDL v4.0 S08; I-20; SRS-G-10, FRS V0.5 §6.12.7): pending salary
 * of an exceptional case paid outside the regular month-end cycle. "An off-cycle run is NOT AN
 * EXEMPTION from cycle control" (DDL comment): same maker-checker and audit, and a recorded
 * duplicate-payment check that must pass before disbursement - no role can override it.
 *
 * <p>The DDL has no workflow status column: the screen status (Draft, Pending Checker, Approved,
 * Blocked, Cancelled ...) is derived from the approval item, the duplicate check, {@code
 * record_status} and the invoice status (see {@code OffCycleService}).
 *
 * <p>Cancel, never delete (BR-AUDIT-009, SRS-G-37): free until the invoice is sent to APPS; after
 * that only once APPS confirms the invoice cancelled ({@code chk_off_cycle_cancel}).
 */
@Entity
@Table(name = "off_cycle_payment")
public class OffCyclePayment {

    /**
     * Case types named by SRS-G-10 / FRS V0.5 §6.12.7 (DDL comment: LATE_JOINER, DEATH_IN_SERVICE,
     * OTHER).
     */
    public static final Set<String> CASE_TYPES = Set.of("LATE_JOINER", "DEATH_IN_SERVICE", "OTHER");

    public static final String ACTIVE = "ACTIVE";
    public static final String CANCELLED = "CANCELLED";

    /**
     * Invoice statuses after which a cancellation needs APPS's confirmation (chk_off_cycle_sent).
     */
    private static final Set<String> SENT =
            Set.of("SENT_TO_APPS", "CANCEL_REQUESTED", "CANCELLED_IN_APPS");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "off_cycle_id")
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "cycle_instance_id")
    private Long cycleInstanceId;

    @Column(name = "case_type", nullable = false, length = 30)
    private String caseType;

    /** The month being paid FOR. */
    @Column(name = "salary_yymm", nullable = false, length = 6)
    private String salaryYymm;

    /** The month it is processed IN ("not set equal to Salary Month by default"). */
    @Column(name = "processing_yymm", nullable = false, length = 6)
    private String processingYymm;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "approval_reference", length = 100)
    private String approvalReference;

    @Column(name = "duplicate_check_run", nullable = false, length = 1)
    @Convert(converter = YesNoConverter.class)
    private Boolean duplicateCheckRun;

    @Column(name = "duplicate_check_at")
    private Instant duplicateCheckAt;

    /** "PASS" or "FAIL: reason" (chk_off_cycle_dup: required once the check has run). */
    @Column(name = "duplicate_check_result", length = 500)
    private String duplicateCheckResult;

    /** The regular cycle this payment is suppressed from (set on approval). */
    @Column(name = "excluded_from_yymm", length = 6)
    private String excludedFromYymm;

    @Column(name = "record_status", nullable = false, length = 15)
    private String recordStatus;

    @Column(name = "invoice_no", length = 30)
    private String invoiceNo;

    @Column(name = "invoice_status", nullable = false, length = 20)
    private String invoiceStatus;

    @Column(name = "invoice_sent_to_apps_date")
    private Instant invoiceSentToAppsDate;

    @Column(name = "apps_cancel_confirmed_at")
    private Instant appsCancelConfirmedAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "approval_id")
    private Long approvalId;

    protected OffCyclePayment() {}

    /**
     * A captured case with its computed amount (never typed: "Computed outside the regular cycle").
     */
    public static OffCyclePayment capture(
            Long employeeId,
            Long cycleInstanceId,
            String caseType,
            String salaryYymm,
            String processingYymm,
            BigDecimal amount,
            String approvalReference) {
        OffCyclePayment p = new OffCyclePayment();
        p.employeeId = employeeId;
        p.cycleInstanceId = cycleInstanceId;
        p.caseType = caseType;
        p.salaryYymm = salaryYymm;
        p.processingYymm = processingYymm;
        p.amount = amount;
        p.approvalReference = approvalReference;
        p.duplicateCheckRun = false;
        p.recordStatus = ACTIVE;
        p.invoiceStatus = "NOT_RAISED";
        return p;
    }

    /**
     * Records the outcome of the duplicate-payment check (SRS-G-10: "records the result on the
     * case").
     */
    public void duplicateCheck(String failure, Instant now) {
        this.duplicateCheckRun = true;
        this.duplicateCheckAt = now;
        this.duplicateCheckResult = failure == null ? "PASS" : "FAIL: " + failure;
    }

    public boolean duplicateCheckPassed() {
        return Boolean.TRUE.equals(duplicateCheckRun) && "PASS".equals(duplicateCheckResult);
    }

    public void submitted(Long approvalId) {
        requireActive();
        this.approvalId = approvalId;
    }

    /**
     * Approved by the Checker: "exclude the amount from the next regular run" - the regular cycle
     * of the salary month will not pay it again (the computation skips members with an approved,
     * active case for that month).
     */
    public void approved() {
        requireActive();
        if (!duplicateCheckPassed()) {
            throw new FciException(
                    GeneralErrorCode.OFFCYCLE_DUPLICATE,
                    new Object[] {duplicateCheckResult},
                    java.util.List.of(),
                    null);
        }
        this.excludedFromYymm = salaryYymm;
    }

    /**
     * Cancelled with a reason (chk_off_cycle_cancel). After the invoice is sent, APPS must confirm
     * first.
     */
    public void cancel(String reason) {
        requireActive();
        if (SENT.contains(invoiceStatus) && !"CANCELLED_IN_APPS".equals(invoiceStatus)) {
            // SRS-EH-12: the refusal states the Invoice Sent to APPS Date.
            throw new FciException(
                    GeneralErrorCode.OFFCYCLE_INVOICE_SENT,
                    new Object[] {AppsDates.display(invoiceSentToAppsDate)},
                    java.util.List.of(),
                    null);
        }
        this.cancelReason = reason;
        this.recordStatus = CANCELLED;
    }

    private void requireActive() {
        if (!ACTIVE.equals(recordStatus)) {
            throw new FciException(
                    GeneralErrorCode.OFFCYCLE_STATUS,
                    new Object[] {recordStatus},
                    java.util.List.of(),
                    null);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getCycleInstanceId() {
        return cycleInstanceId;
    }

    public String getCaseType() {
        return caseType;
    }

    public String getSalaryYymm() {
        return salaryYymm;
    }

    public String getProcessingYymm() {
        return processingYymm;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getApprovalReference() {
        return approvalReference;
    }

    public boolean isDuplicateCheckRun() {
        return Boolean.TRUE.equals(duplicateCheckRun);
    }

    public Instant getDuplicateCheckAt() {
        return duplicateCheckAt;
    }

    public String getDuplicateCheckResult() {
        return duplicateCheckResult;
    }

    public String getExcludedFromYymm() {
        return excludedFromYymm;
    }

    public String getRecordStatus() {
        return recordStatus;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public String getInvoiceStatus() {
        return invoiceStatus;
    }

    public Instant getInvoiceSentToAppsDate() {
        return invoiceSentToAppsDate;
    }

    public Instant getAppsCancelConfirmedAt() {
        return appsCancelConfirmedAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
