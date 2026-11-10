package in.gov.fci.payroll.employee.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code employee_lifecycle_event}: I-17 employee life-cycle event (target DDL v4.0 S03):
 * suspension, revocation, salary stop / release, deputation, separation ...; normally from HRMS
 * (I-21), recorded in Payroll by maker-checker meanwhile.
 */
@Entity
@Table(name = "employee_lifecycle_event")
public class LifecycleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "subsistence_percent", precision = 5, scale = 2)
    private BigDecimal subsistencePercent;

    @Column(name = "release_from_yymm", length = 6)
    private String releaseFromYymm;

    @Column(name = "release_to_yymm", length = 6)
    private String releaseToYymm;

    @Column(name = "intervening_period_treatment", length = 50)
    private String interveningPeriodTreatment;

    @Column(name = "order_reference", length = 100)
    private String orderReference;

    @Column(name = "event_source", nullable = false, length = 20)
    private String eventSource;

    @Column(name = "hrms_request_id", length = 50)
    private String hrmsRequestId;

    @Column(name = "hrms_change_task_id")
    private Long hrmsChangeTaskId;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LifecycleEvent() {}

    /**
     * A life-cycle event recorded in Payroll (event_source PAYROLL); it takes effect when approved.
     */
    public static LifecycleEvent record(
            Long employeeId,
            String eventType,
            LocalDate eventDate,
            LocalDate endDate,
            String reason,
            BigDecimal subsistencePercent,
            String releaseFromYymm,
            String releaseToYymm,
            String orderReference,
            String createdBy,
            Instant now) {
        LifecycleEvent e = new LifecycleEvent();
        e.employeeId = employeeId;
        e.eventType = eventType;
        e.eventDate = eventDate;
        e.endDate = endDate;
        e.reason = reason;
        e.subsistencePercent = subsistencePercent;
        e.releaseFromYymm = releaseFromYymm;
        e.releaseToYymm = releaseToYymm;
        e.orderReference = orderReference;
        e.eventSource = "PAYROLL";
        e.createdBy = createdBy.length() <= 50 ? createdBy : createdBy.substring(0, 50);
        e.createdAt = now;
        return e;
    }

    /**
     * A life-cycle event received from HRMS and applied through its approved I-21 task (plan 16
     * phase 16f-1; SRS-1-63): event_source HRMS, linked to the task and the HRMS request.
     */
    public static LifecycleEvent fromHrms(
            Long employeeId,
            String eventType,
            LocalDate eventDate,
            String reason,
            BigDecimal subsistencePercent,
            String orderReference,
            String hrmsRequestId,
            Long hrmsChangeTaskId,
            Long approvalId,
            String createdBy,
            Instant now) {
        LifecycleEvent e =
                record(
                        employeeId,
                        eventType,
                        eventDate,
                        null,
                        reason,
                        subsistencePercent,
                        null,
                        null,
                        orderReference,
                        createdBy,
                        now);
        e.eventSource = "HRMS";
        e.hrmsRequestId = hrmsRequestId;
        e.hrmsChangeTaskId = hrmsChangeTaskId;
        e.approvalId = approvalId;
        return e;
    }

    public void attachApproval(Long approvalId) {
        this.approvalId = approvalId;
    }

    public Long getEventId() {
        return eventId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getEventType() {
        return eventType;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getReason() {
        return reason;
    }

    public BigDecimal getSubsistencePercent() {
        return subsistencePercent;
    }

    public String getReleaseFromYymm() {
        return releaseFromYymm;
    }

    public String getReleaseToYymm() {
        return releaseToYymm;
    }

    public String getInterveningPeriodTreatment() {
        return interveningPeriodTreatment;
    }

    public String getOrderReference() {
        return orderReference;
    }

    public String getEventSource() {
        return eventSource;
    }

    public String getHrmsRequestId() {
        return hrmsRequestId;
    }

    public Long getHrmsChangeTaskId() {
        return hrmsChangeTaskId;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
