package in.gov.fci.payroll.payroll.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code arrear_event_type}: Arrear event type (target DDL v4.0 S13, I-08). */
@Entity
@Table(name = "arrear_event_type")
public class ArrearEventType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_type_id")
    private Long eventTypeId;

    @Column(name = "event_code", nullable = false, length = 30)
    private String eventCode;

    @Column(name = "event_name", nullable = false, length = 150)
    private String eventName;

    @Column(name = "event_category", nullable = false, length = 20)
    private String eventCategory;

    @Column(name = "revision_flag_name", length = 30)
    private String revisionFlagName;

    protected ArrearEventType() {}

    public Long getEventTypeId() {
        return eventTypeId;
    }

    public String getEventCode() {
        return eventCode;
    }

    public String getEventName() {
        return eventName;
    }

    public String getEventCategory() {
        return eventCategory;
    }

    public String getRevisionFlagName() {
        return revisionFlagName;
    }
}
