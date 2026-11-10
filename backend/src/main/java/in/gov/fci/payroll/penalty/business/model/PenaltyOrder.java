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
 * {@code penalty_order}: a penalty or recovery order with its Penalty ID (target DDL v4.0 Section
 * 18; SRS-14-1, SRS-14-6, SRS-14-15, BR-PEN2-001 … 008): entered on I-06 (source ENT) or applied
 * from an HRMS penalty through I-21 (source HRMS, {@code chk_penalty_order_hrms}). Written on the
 * Checker's approval; before it the request lives in its approval item. Never deleted: a
 * cancellation or revoke changes the status (R-DEL).
 */
@Entity
@Table(name = "penalty_order")
public class PenaltyOrder {

    /** {@code order_status} (DDL CHECK). */
    public enum Status {
        ACTIVE,
        CANCELLED,
        PARTLY_REVOKED,
        REVOKED,
        COMPLETED,
        PAUSED_FNF
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long id;

    @Column(name = "penalty_ref", nullable = false, length = 20)
    private String penaltyRef;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "order_reference", nullable = false, length = 100)
    private String orderReference;

    @Column(name = "penalty_source", nullable = false, length = 10)
    private String penaltySource;

    @Column(name = "hrms_request_id", length = 50)
    private String hrmsRequestId;

    @Column(name = "hrms_change_task_id")
    private Long hrmsChangeTaskId;

    @Column(name = "penalty_nature", nullable = false, length = 40)
    private String penaltyNature;

    @Column(name = "total_recovery_amount")
    private BigDecimal totalRecoveryAmount;

    @Column(name = "instalment_count")
    private Integer instalmentCount;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Column(name = "imposed_date", nullable = false)
    private LocalDate imposedDate;

    @Column(name = "order_status", nullable = false, length = 15)
    private String orderStatus;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_backdated", nullable = false, length = 1)
    private Boolean backdated;

    @Column(name = "reversing_arrear_event_id")
    private Long reversingArrearEventId;

    @Column(name = "approved_by", length = 50)
    private String approvedBy;

    @Column(name = "approval_id")
    private Long approvalId;

    protected PenaltyOrder() {}

    /**
     * An approved order.
     *
     * @param hrmsTaskId the I-21 task of an HRMS penalty, null for one entered on I-06
     */
    @SuppressWarnings("java:S107") // the DDL row
    public static PenaltyOrder approved(
            String penaltyRef,
            Long employeeId,
            String orderReference,
            String nature,
            BigDecimal amount,
            Integer instalments,
            LocalDate periodFrom,
            LocalDate periodTo,
            LocalDate imposedDate,
            boolean backdated,
            Long hrmsTaskId,
            String hrmsRequestId,
            String approvedBy,
            Long approvalId) {
        PenaltyOrder o = new PenaltyOrder();
        o.penaltyRef = penaltyRef;
        o.employeeId = employeeId;
        o.orderReference = orderReference;
        o.penaltySource = hrmsTaskId == null ? "ENT" : "HRMS";
        o.hrmsChangeTaskId = hrmsTaskId;
        o.hrmsRequestId = hrmsRequestId;
        o.penaltyNature = nature;
        o.totalRecoveryAmount = amount;
        o.instalmentCount = instalments;
        o.periodFrom = periodFrom;
        o.periodTo = periodTo;
        o.imposedDate = imposedDate;
        o.backdated = backdated;
        o.approvedBy =
                approvedBy == null
                        ? null
                        : approvedBy.substring(0, Math.min(50, approvedBy.length()));
        o.approvalId = approvalId;
        o.orderStatus = Status.ACTIVE.name();
        return o;
    }

    /** The last day of the recovery period (the schedule's last month). */
    public void period(LocalDate to) {
        this.periodTo = to;
    }

    /** SRS-14-15: the Penalty ID, once the row has its number. */
    public void reference(String ref) {
        this.penaltyRef = ref;
    }

    /** A status change (completed, paused for F&amp;F, revoked, cancelled). */
    public void status(Status next) {
        this.orderStatus = next.name();
    }

    public Long getId() {
        return id;
    }

    public String getPenaltyRef() {
        return penaltyRef;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getOrderReference() {
        return orderReference;
    }

    public String getPenaltySource() {
        return penaltySource;
    }

    public Long getHrmsChangeTaskId() {
        return hrmsChangeTaskId;
    }

    public String getPenaltyNature() {
        return penaltyNature;
    }

    public BigDecimal getTotalRecoveryAmount() {
        return totalRecoveryAmount;
    }

    public Integer getInstalmentCount() {
        return instalmentCount;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public LocalDate getImposedDate() {
        return imposedDate;
    }

    public Status status() {
        return Status.valueOf(orderStatus);
    }

    public boolean isBackdated() {
        return Boolean.TRUE.equals(backdated);
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
