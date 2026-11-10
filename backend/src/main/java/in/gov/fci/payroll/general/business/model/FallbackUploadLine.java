package in.gov.fci.payroll.general.business.model;

import in.gov.fci.payroll.general.business.Inputs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code hrms_fallback_upload_line} (target DDL v4.0 S08; SRS-DM-18, SRS-EH-11): one record of an
 * uploaded HRMS export, its validation result and, later, its reconciliation with HRMS (SRS-G-47).
 * Written once at upload and never edited by a user; reconciliation fields are set by the HRMS
 * interface (module 23).
 *
 * <p>DDL rules: a REJECTED line has a reason ({@code chk_hrms_fb_line_reject}); an ACCEPTED line
 * has a Payroll employee ({@code _emp}); an accepted order record has order number, order date and
 * effective date ({@code _order}); period_to is not before period_from ({@code _period}).
 */
@Entity
@Table(name = "hrms_fallback_upload_line")
public class FallbackUploadLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fallback_line_id")
    private Long id;

    @Column(name = "fallback_upload_id", nullable = false)
    private Long uploadId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "employee_number_raw", nullable = false, length = 30)
    private String employeeNumberRaw;

    /** ATTENDANCE_LEAVE, SUSPENSION, ABSCONDING or REVOCATION (CHECK of the DDL). */
    @Column(name = "record_type", nullable = false, length = 20)
    private String recordType;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Column(name = "pay_days", precision = 5, scale = 2)
    private BigDecimal payDays;

    @Column(name = "lwp_days", precision = 5, scale = 2)
    private BigDecimal lwpDays;

    @Column(name = "half_pay_days", precision = 5, scale = 2)
    private BigDecimal halfPayDays;

    @Column(name = "other_leave_code", length = 20)
    private String otherLeaveCode;

    @Column(name = "other_leave_days", precision = 5, scale = 2)
    private BigDecimal otherLeaveDays;

    @Column(name = "event_effective_date")
    private LocalDate eventEffectiveDate;

    @Column(name = "order_no", length = 60)
    private String orderNo;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "issuing_authority", length = 200)
    private String issuingAuthority;

    @Column(name = "raw_record", length = 4000)
    private String rawRecord;

    /** ACCEPTED or REJECTED. */
    @Column(name = "validation_status", nullable = false, length = 10)
    private String validationStatus;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /** PENDING until HRMS is back (then MATCHED, DIFFERENCE, NOT_IN_HRMS or NOT_APPLICABLE). */
    @Column(name = "recon_status", nullable = false, length = 15)
    private String reconStatus;

    protected FallbackUploadLine() {}

    /** A validated line, as {@code FallbackRules} produced it. */
    public static FallbackUploadLine of(
            Long uploadId, FallbackRecord r, Long employeeId, String rejectionReason) {
        FallbackUploadLine l = new FallbackUploadLine();
        l.uploadId = uploadId;
        l.lineNo = r.lineNo();
        l.employeeId =
                employeeId; // null only when the record matched no Payroll employee (DDL comment)
        l.employeeNumberRaw = Inputs.cut(r.employeeNumber() == null ? "" : r.employeeNumber(), 30);
        l.recordType = r.recordType();
        l.periodFrom = r.periodFrom();
        l.periodTo = r.periodTo();
        l.payDays = r.payDays();
        l.lwpDays = r.lwpDays();
        l.halfPayDays = r.halfPayDays();
        l.otherLeaveCode = r.otherLeaveCode() == null ? null : Inputs.cut(r.otherLeaveCode(), 20);
        l.otherLeaveDays = r.otherLeaveDays();
        l.eventEffectiveDate = r.eventEffectiveDate();
        l.orderNo = r.orderNo() == null ? null : Inputs.cut(r.orderNo(), 60);
        l.orderDate = r.orderDate();
        l.issuingAuthority =
                r.issuingAuthority() == null ? null : Inputs.cut(r.issuingAuthority(), 200);
        l.rawRecord = r.raw() == null ? null : Inputs.cut(r.raw(), 4000);
        l.validationStatus = rejectionReason == null ? "ACCEPTED" : "REJECTED";
        l.rejectionReason = rejectionReason == null ? null : Inputs.cut(rejectionReason, 500);
        // Rejected lines are never reconciled.
        l.reconStatus = rejectionReason == null ? "PENDING" : "NOT_APPLICABLE";
        return l;
    }

    public Long getId() {
        return id;
    }

    public Long getUploadId() {
        return uploadId;
    }

    public int getLineNo() {
        return lineNo;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getEmployeeNumberRaw() {
        return employeeNumberRaw;
    }

    public String getRecordType() {
        return recordType;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public BigDecimal getPayDays() {
        return payDays;
    }

    public BigDecimal getLwpDays() {
        return lwpDays;
    }

    public BigDecimal getHalfPayDays() {
        return halfPayDays;
    }

    public String getOtherLeaveCode() {
        return otherLeaveCode;
    }

    public BigDecimal getOtherLeaveDays() {
        return otherLeaveDays;
    }

    public LocalDate getEventEffectiveDate() {
        return eventEffectiveDate;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public String getIssuingAuthority() {
        return issuingAuthority;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getReconStatus() {
        return reconStatus;
    }
}
