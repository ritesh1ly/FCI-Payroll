package in.gov.fci.payroll.allowance.business.model;

import in.gov.fci.payroll.allowance.business.ClaimRules;
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
import java.util.Objects;
import org.hibernate.type.YesNoConverter;

/**
 * {@code reimbursement_claim}: one claim of the I-03 Claim Register (target DDL v4.0 Section 19;
 * SRS-11-1, SRS-11-9, SRS-11-11, SRS-11-13; plan 10 D10-7, D10-8). A LABOUR claim is entered (ENT)
 * and validated under maker-checker; an EMPLOYEE claim arrives from HRMS through I-21 (HRMS) and is
 * written approved (DDL {@code chk_reimb_member_source}). Never deleted: a status (R-DEL).
 */
@Entity
@Table(name = "reimbursement_claim")
public class ReimbursementClaim {

    /** {@code claim_status} (DDL CHECK). */
    public enum Status {
        SUBMITTED,
        VALIDATED,
        CAPPED,
        APPROVED,
        PAID,
        REJECTED,
        OVERLAP_REJECTED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "claim_id")
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "member_type", nullable = false, length = 10)
    private String memberType;

    @Column(name = "claim_type", nullable = false, length = 30)
    private String claimType;

    @Column(name = "ceiling_norm_id")
    private Long ceilingNormId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(name = "claimed_amount", nullable = false)
    private BigDecimal claimedAmount;

    @Column(name = "entitlement_amount")
    private BigDecimal entitlementAmount;

    @Column(name = "payable_amount")
    private BigDecimal payableAmount;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "was_capped", nullable = false, length = 1)
    private Boolean capped;

    @Column(name = "ioga_number", length = 50)
    private String iogaNumber;

    @Column(name = "ioga_date")
    private LocalDate iogaDate;

    @Column(name = "ioga_amount")
    private BigDecimal iogaAmount;

    @Column(name = "scheme_type", length = 40)
    private String schemeType;

    @Column(name = "claim_status", nullable = false, length = 20)
    private String status;

    @Column(name = "source_code", nullable = false, length = 10)
    private String sourceCode;

    @Column(name = "hrms_request_id", length = 50)
    private String hrmsRequestId;

    @Column(name = "hrms_change_task_id")
    private Long hrmsChangeTaskId;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "taxable_flag", length = 1)
    private Boolean taxable;

    @Column(name = "taxable_confirmed_at")
    private Instant taxableConfirmedAt;

    @Column(name = "invoice_status", nullable = false, length = 20)
    private String invoiceStatus;

    @Column(name = "apps_cancel_status", nullable = false, length = 15)
    private String appsCancelStatus;

    @Column(name = "approval_id")
    private Long approvalId;

    protected ReimbursementClaim() {}

    /** D10-7: a labour claim as the Labour Dealing Hand enters it (SUBMITTED, source ENT). */
    public static ReimbursementClaim labour(
            long employeeId, String claimType, ClaimRules.Period period, BigDecimal claimed) {
        ReimbursementClaim c = base(employeeId, "LABOUR", claimType, period, claimed);
        c.sourceCode = "ENT";
        c.status = Status.SUBMITTED.name();
        return c;
    }

    /**
     * D10-8: an employee claim received from HRMS whose I-21 task was approved, written APPROVED
     * with its adjudication.
     */
    public static ReimbursementClaim fromHrms(
            long employeeId,
            String claimType,
            ClaimRules.Period period,
            BigDecimal claimed,
            Long normId,
            ClaimRules.Adjudication adjudication,
            String hrmsRequestId,
            long taskId,
            Boolean taxable,
            String schemeType,
            long approvalId) {
        ReimbursementClaim c = base(employeeId, "EMPLOYEE", claimType, period, claimed);
        c.sourceCode = "HRMS";
        c.hrmsRequestId = hrmsRequestId;
        c.hrmsChangeTaskId = taskId;
        c.taxable = taxable;
        c.schemeType = schemeType;
        c.adjudicated(normId, adjudication);
        c.status = Status.APPROVED.name();
        c.approvalId = approvalId;
        return c;
    }

    private static ReimbursementClaim base(
            long employeeId,
            String memberType,
            String claimType,
            ClaimRules.Period period,
            BigDecimal claimed) {
        ReimbursementClaim c = new ReimbursementClaim();
        c.employeeId = employeeId;
        c.memberType = memberType;
        c.claimType = Objects.requireNonNull(claimType);
        c.periodFrom = period.from();
        c.periodTo = period.to();
        c.claimedAmount = claimed;
        c.capped = false;
        c.invoiceStatus = "NOT_RAISED";
        c.appsCancelStatus = "NOT_REQUESTED";
        return c;
    }

    /** SRS-11-11: the adjudication shown at entry (to the Labour Dealing Hand); SUBMITTED stays. */
    public void enteredWith(Long normId, ClaimRules.Adjudication adjudication) {
        adjudicated(normId, adjudication);
        status = Status.SUBMITTED.name();
    }

    /** A returned claim goes back to the Maker (SUBMITTED) with the Checker's reason (D10-7). */
    public void returned() {
        status = Status.SUBMITTED.name();
    }

    /** SRS-11-9 / SRS-11-11: the Maker's validation, VALIDATED or CAPPED (D10-7). */
    public void validated(Long normId, ClaimRules.Adjudication adjudication) {
        adjudicated(normId, adjudication);
        status = adjudication.capped() ? Status.CAPPED.name() : Status.VALIDATED.name();
    }

    private void adjudicated(Long normId, ClaimRules.Adjudication adjudication) {
        ceilingNormId = normId;
        entitlementAmount = adjudication.entitlement();
        payableAmount = adjudication.payable();
        capped = adjudication.capped();
    }

    /** SRS-11-6: the period overlaps a live claim of the same type. */
    public void overlapRejected() {
        status = Status.OVERLAP_REJECTED.name();
    }

    /** The approval item the Maker's submission opened. */
    public void submittedFor(long approval) {
        approvalId = approval;
    }

    public void approved() {
        status = Status.APPROVED.name();
    }

    public void rejected() {
        status = Status.REJECTED.name();
    }

    /** SRS-11-12: paid in the cycle that computed it, at its lock (D10-6). */
    public void paid() {
        status = Status.PAID.name();
    }

    /** SRS-11-19 (D10-9): the taxable flag confirmed before the medical invoice. */
    public void taxableConfirmed(boolean flag, Instant at) {
        taxable = flag;
        taxableConfirmedAt = at;
    }

    /** SRS-11-21 (D10-10): the IOGA received for the claim, once approved. */
    public void ioga(String number, LocalDate date, BigDecimal amount) {
        iogaNumber = number;
        iogaDate = date;
        iogaAmount = amount;
    }

    public boolean live() {
        return !Status.REJECTED.name().equals(status)
                && !Status.OVERLAP_REJECTED.name().equals(status)
                && !Status.CANCELLED.name().equals(status);
    }

    public ClaimRules.Period period() {
        return new ClaimRules.Period(periodFrom, periodTo);
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getMemberType() {
        return memberType;
    }

    public String getClaimType() {
        return claimType;
    }

    public Long getCeilingNormId() {
        return ceilingNormId;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public BigDecimal getClaimedAmount() {
        return claimedAmount;
    }

    public BigDecimal getEntitlementAmount() {
        return entitlementAmount;
    }

    public BigDecimal getPayableAmount() {
        return payableAmount;
    }

    public boolean isCapped() {
        return Boolean.TRUE.equals(capped);
    }

    public String getIogaNumber() {
        return iogaNumber;
    }

    public LocalDate getIogaDate() {
        return iogaDate;
    }

    public BigDecimal getIogaAmount() {
        return iogaAmount;
    }

    public String getSchemeType() {
        return schemeType;
    }

    public String getStatus() {
        return status;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public String getHrmsRequestId() {
        return hrmsRequestId;
    }

    public Long getHrmsChangeTaskId() {
        return hrmsChangeTaskId;
    }

    public Boolean getTaxable() {
        return taxable;
    }

    public Instant getTaxableConfirmedAt() {
        return taxableConfirmedAt;
    }

    public String getInvoiceStatus() {
        return invoiceStatus;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
