package in.gov.fci.payroll.payroll.business.model;

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
import org.hibernate.type.YesNoConverter;

/**
 * {@code arrear_event}: An arrear event (target DDL v4.0 S13): a revision recomputed over past
 * periods, posted into the next open cycle.
 */
@Entity
@Table(name = "arrear_event")
public class ArrearEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "arrear_event_id")
    private Long arrearEventId;

    @Column(name = "event_type_id", nullable = false)
    private Long eventTypeId;

    @Column(name = "event_reference", length = 100)
    private String eventReference;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "revised_value", precision = 14, scale = 4)
    private BigDecimal revisedValue;

    @Column(name = "scope_payroll_type", length = 100)
    private String scopePayrollType;

    @Column(name = "scope_category", length = 40)
    private String scopeCategory;

    @Column(name = "scope_location_id")
    private Long scopeLocationId;

    @Column(name = "scope_from_yymm", length = 6)
    private String scopeFromYymm;

    @Column(name = "scope_to_yymm", length = 6)
    private String scopeToYymm;

    @Column(name = "source_module", length = 40)
    private String sourceModule;

    @Column(name = "source_entity", length = 100)
    private String sourceEntity;

    @Column(name = "source_key", length = 100)
    private String sourceKey;

    @Column(name = "circular_order_id")
    private Long circularOrderId;

    @Column(name = "wage_revision_id")
    private Long wageRevisionId;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "beyond_window_flag", nullable = false, length = 1)
    private Boolean beyondWindowFlag;

    @Column(name = "hq_exception_approval_id")
    private Long hqExceptionApprovalId;

    @Column(name = "event_status", nullable = false, length = 20)
    private String eventStatus;

    @Column(name = "posted_to_yymm", length = 6)
    private String postedToYymm;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "raised_by", nullable = false, length = 50)
    private String raisedBy;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    protected ArrearEvent() {}

    public Long getArrearEventId() {
        return arrearEventId;
    }

    public Long getEventTypeId() {
        return eventTypeId;
    }

    public String getEventReference() {
        return eventReference;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public BigDecimal getRevisedValue() {
        return revisedValue;
    }

    public String getScopePayrollType() {
        return scopePayrollType;
    }

    public String getScopeCategory() {
        return scopeCategory;
    }

    public Long getScopeLocationId() {
        return scopeLocationId;
    }

    public String getScopeFromYymm() {
        return scopeFromYymm;
    }

    public String getScopeToYymm() {
        return scopeToYymm;
    }

    public String getSourceModule() {
        return sourceModule;
    }

    public String getSourceEntity() {
        return sourceEntity;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public Long getCircularOrderId() {
        return circularOrderId;
    }

    public Long getWageRevisionId() {
        return wageRevisionId;
    }

    public boolean isBeyondWindowFlag() {
        return Boolean.TRUE.equals(beyondWindowFlag);
    }

    public Long getHqExceptionApprovalId() {
        return hqExceptionApprovalId;
    }

    public String getEventStatus() {
        return eventStatus;
    }

    public String getPostedToYymm() {
        return postedToYymm;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public String getRaisedBy() {
        return raisedBy;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }
}
