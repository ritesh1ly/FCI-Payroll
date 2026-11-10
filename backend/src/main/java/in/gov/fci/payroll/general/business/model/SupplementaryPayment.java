package in.gov.fci.payroll.general.business.model;

import in.gov.fci.payroll.general.business.AppsDates;
import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.hibernate.type.YesNoConverter;

/**
 * {@code supplementary_payment} (target DDL v4.0 S18; I-23; SRS-G-33, SRS-G-34, BR-CYC-010): the
 * only route to pay or recover an amount for a month-end-LOCKED period. A locked result is never
 * reopened: the payment is a new row linked to the locked cycle instance.
 *
 * <p>Lifecycle ({@code payment_status}): RECORDED -(submit)-> SUBMITTED -(Checker with OTP)->
 * APPROVED -(module 23)-> RELEASED; SUBMITTED -(returned)-> RECORDED; SUBMITTED -(rejected)->
 * REJECTED; any but RELEASED -(cancel)-> CANCELLED. Never deleted (SRS-G-37).
 *
 * <p>DDL rules mirrored here so the user gets a message code rather than a constraint error: {@code
 * chk_supp_post_lock_otp} (approval carries the OTP challenge), {@code chk_supp_dup_passed}
 * (approval needs a passed duplicate check), {@code chk_supp_sanction_after_appl}, {@code
 * chk_supp_cancel_apps}. {@code trg_supp_period_locked} and {@code trg_supp_component_excluded}
 * stay in the database as the last line.
 */
@Entity
@Table(name = "supplementary_payment")
public class SupplementaryPayment {

    /** The BR-CYC-010 / I-23 enumeration (DDL CHECK on {@code payment_type}). */
    public static final List<String> TYPES =
            List.of(
                    "SALARY_WAGE",
                    "SALARY_ARREAR",
                    "PLI",
                    "OTA",
                    "INCENTIVE",
                    "EX_GRATIA",
                    "LEAVE_ENCASHMENT",
                    "LTC_ENCASHMENT",
                    "LEAVE_ENCASH_ARREAR",
                    "OVER_AND_ABOVE",
                    "REIMBURSEMENT",
                    "CAFETERIA",
                    "CPF_PAYMENT",
                    "CPF_SUPPLEMENTARY",
                    "EPS_HIGHER_PENSION",
                    "EMPLOYER_CPF_SEPARATED",
                    "EMPLOYER_NPS_SEPARATED",
                    "COURT_CASE_PAYMENT",
                    "RECOVERY");

    /** Types that pay salary for the month: checked against the regular run and I-20 cases. */
    public static final Set<String> SALARY_TYPES = Set.of("SALARY_WAGE", "SALARY_ARREAR");

    /**
     * Types payable for a member already separated (SRS-G-14: otherwise the record is read-only).
     */
    public static final Set<String> SEPARATED_TYPES =
            Set.of(
                    "EMPLOYER_CPF_SEPARATED",
                    "EMPLOYER_NPS_SEPARATED",
                    "COURT_CASE_PAYMENT",
                    "CPF_PAYMENT",
                    "CPF_SUPPLEMENTARY",
                    "EPS_HIGHER_PENSION",
                    "RECOVERY");

    public static final String RECOVERY = "RECOVERY";

    public enum Status {
        RECORDED,
        SUBMITTED,
        APPROVED,
        RELEASED,
        REJECTED,
        CANCELLED
    }

    public enum DuplicateCheck {
        PENDING,
        PASSED,
        BLOCKED
    }

    /** Invoice statuses after which a cancellation needs APPS's confirmation. */
    private static final Set<String> SENT =
            Set.of("SENT_TO_APPS", "CANCEL_REQUESTED", "CANCELLED_IN_APPS");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "cycle_instance_id")
    private Long cycleInstanceId;

    @Column(name = "for_period_yymm", nullable = false, length = 6)
    private String forPeriodYymm;

    @Column(name = "payment_type", nullable = false, length = 30)
    private String paymentType;

    @Column(name = "component_id")
    private Long componentId;

    /** Negative for a RECOVERY ("recovery shown negative"). */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "application_date", nullable = false)
    private LocalDate applicationDate;

    @Column(name = "sanction_date")
    private LocalDate sanctionDate;

    @Column(name = "sanction_order_ref", length = 100)
    private String sanctionOrderRef;

    @Column(name = "order_attachment_id")
    private Long orderAttachmentId;

    @Column(name = "taxable_flag", nullable = false, length = 1)
    @Convert(converter = YesNoConverter.class)
    private Boolean taxable;

    @Column(name = "eps_applicable_flag", nullable = false, length = 1)
    @Convert(converter = YesNoConverter.class)
    private Boolean epsApplicable;

    @Column(name = "net_pay_floor_breach_flag", nullable = false, length = 1)
    @Convert(converter = YesNoConverter.class)
    private Boolean netPayFloorBreach;

    @Enumerated(EnumType.STRING)
    @Column(name = "duplicate_check_status", nullable = false, length = 10)
    private DuplicateCheck duplicateCheck;

    @Column(name = "duplicate_of_payment_id")
    private Long duplicateOfPaymentId;

    @Column(name = "ap_invoice_id")
    private Long apInvoiceId;

    @Column(name = "invoice_status", nullable = false, length = 20)
    private String invoiceStatus;

    @Column(name = "invoice_sent_at")
    private Instant invoiceSentAt;

    @Column(name = "apps_cancel_status", nullable = false, length = 15)
    private String appsCancelStatus;

    @Column(name = "approval_id")
    private Long approvalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 15)
    private Status status;

    @Column(name = "otp_challenge_id")
    private Long otpChallengeId;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "record_origin", nullable = false, length = 10)
    private String recordOrigin;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected SupplementaryPayment() {}

    /**
     * A recorded request. The order is already stored ({@code chk_supp_locked_cycle} needs it at
     * insert) and the cycle instance is the LOCKED one of the period ({@code
     * trg_supp_period_locked}).
     *
     * @throws FciException SUP-422-INPUT for an unknown type, a recovery not negative or a payment
     *     not positive; SUP-422-SANCTION when the sanction is not after the application ({@code
     *     chk_supp_sanction_after_appl})
     */
    public static SupplementaryPayment record(
            Long employeeId,
            Long lockedCycleInstanceId,
            String forPeriodYymm,
            String paymentType,
            Long componentId,
            BigDecimal amount,
            LocalDate applicationDate,
            LocalDate sanctionDate,
            String sanctionOrderRef,
            Long orderAttachmentId,
            boolean taxable,
            boolean epsApplicable) {
        if (!TYPES.contains(paymentType)) {
            throw new FciException(GeneralErrorCode.SUPPLEMENTARY_INPUT);
        }
        // "Recovery shown negative": the sign carries the direction, never a zero amount.
        boolean recovery = RECOVERY.equals(paymentType);
        if (amount == null || amount.signum() == 0 || (amount.signum() < 0) != recovery) {
            throw new FciException(GeneralErrorCode.SUPPLEMENTARY_INPUT);
        }
        if (applicationDate == null
                || (sanctionDate != null && !sanctionDate.isAfter(applicationDate))) {
            throw new FciException(GeneralErrorCode.SUPPLEMENTARY_SANCTION_DATE);
        }
        SupplementaryPayment p = new SupplementaryPayment();
        p.employeeId = employeeId;
        p.cycleInstanceId = lockedCycleInstanceId;
        p.forPeriodYymm = forPeriodYymm;
        p.paymentType = paymentType;
        p.componentId = componentId;
        p.amount = amount;
        p.applicationDate = applicationDate;
        p.sanctionDate = sanctionDate;
        p.sanctionOrderRef = sanctionOrderRef;
        p.orderAttachmentId = orderAttachmentId;
        p.taxable = taxable;
        p.epsApplicable = epsApplicable;
        p.netPayFloorBreach = false;
        p.duplicateCheck = DuplicateCheck.PENDING;
        p.invoiceStatus = "NOT_RAISED";
        p.appsCancelStatus = "NOT_REQUESTED";
        p.status = Status.RECORDED;
        p.recordOrigin = "SYSTEM";
        return p;
    }

    /**
     * Records the duplicate check (BR-CYC-010).
     *
     * @param duplicate whether a matching payment was found
     * @param matchingPaymentId the matching I-23 payment, when the match is one
     */
    public void duplicateChecked(boolean duplicate, Long matchingPaymentId) {
        this.duplicateCheck = duplicate ? DuplicateCheck.BLOCKED : DuplicateCheck.PASSED;
        this.duplicateOfPaymentId = duplicate ? matchingPaymentId : null;
    }

    public boolean duplicateCheckPassed() {
        return duplicateCheck == DuplicateCheck.PASSED;
    }

    /** RECORDED -> SUBMITTED; a blocked request cannot be submitted ("failure blocks submit"). */
    public void submit(Long approvalId) {
        requireStatus(Status.RECORDED);
        requirePassed();
        this.approvalId = approvalId;
        this.status = Status.SUBMITTED;
    }

    /**
     * SUBMITTED -> APPROVED by the Checker with OTP ({@code chk_supp_post_lock_otp}, {@code
     * chk_supp_dup_passed}). The OTP is enforced by the approval service; a missing challenge here
     * is a programming error.
     */
    public void approve(Long otpChallengeId) {
        requireStatus(Status.SUBMITTED);
        requirePassed();
        if (otpChallengeId == null) {
            throw new IllegalStateException("chk_supp_post_lock_otp: approval without an OTP");
        }
        this.otpChallengeId = otpChallengeId;
        this.status = Status.APPROVED;
    }

    /** SUBMITTED -> RECORDED: the Maker corrects and resubmits. */
    public void returned() {
        requireStatus(Status.SUBMITTED);
        this.status = Status.RECORDED;
    }

    public void rejected() {
        requireStatus(Status.SUBMITTED);
        this.status = Status.REJECTED;
    }

    /**
     * Cancelled with a reason (SRS-G-37). Not while the Checker decides, not once released; after
     * the invoice is sent only when APPS confirms the cancellation ({@code chk_supp_cancel_apps}).
     */
    public void cancel(String reason, Instant now) {
        if (status == Status.SUBMITTED || status == Status.RELEASED || status == Status.CANCELLED) {
            throw new FciException(
                    GeneralErrorCode.SUPPLEMENTARY_STATUS,
                    new Object[] {status.name()},
                    List.of(),
                    null);
        }
        if (SENT.contains(invoiceStatus) && !"CONFIRMED".equals(appsCancelStatus)) {
            // SRS-EH-12: the refusal states the Invoice Sent to APPS Date.
            throw new FciException(
                    GeneralErrorCode.SUPPLEMENTARY_INVOICE_SENT,
                    new Object[] {AppsDates.display(invoiceSentAt)},
                    List.of(),
                    null);
        }
        this.cancelReason = reason;
        this.cancelledAt = now;
        this.status = Status.CANCELLED;
    }

    private void requireStatus(Status expected) {
        if (status != expected) {
            throw new FciException(
                    GeneralErrorCode.SUPPLEMENTARY_STATUS,
                    new Object[] {status.name()},
                    List.of(),
                    null);
        }
    }

    private void requirePassed() {
        if (!duplicateCheckPassed()) {
            throw new FciException(GeneralErrorCode.SUPPLEMENTARY_DUPLICATE);
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

    public String getForPeriodYymm() {
        return forPeriodYymm;
    }

    public String getPaymentType() {
        return paymentType;
    }

    public Long getComponentId() {
        return componentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getApplicationDate() {
        return applicationDate;
    }

    public LocalDate getSanctionDate() {
        return sanctionDate;
    }

    public String getSanctionOrderRef() {
        return sanctionOrderRef;
    }

    public Long getOrderAttachmentId() {
        return orderAttachmentId;
    }

    public boolean isTaxable() {
        return Boolean.TRUE.equals(taxable);
    }

    public boolean isEpsApplicable() {
        return Boolean.TRUE.equals(epsApplicable);
    }

    public DuplicateCheck getDuplicateCheck() {
        return duplicateCheck;
    }

    public Long getDuplicateOfPaymentId() {
        return duplicateOfPaymentId;
    }

    public String getInvoiceStatus() {
        return invoiceStatus;
    }

    public Instant getInvoiceSentAt() {
        return invoiceSentAt;
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

    public Status getStatus() {
        return status;
    }

    public Long getOtpChallengeId() {
        return otpChallengeId;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }
}
