package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.YesNoConverter;

/**
 * {@code circular_order_scope}: M-25 applicable payroll types and offices of a circular (target DDL
 * v4.0 S02).
 */
@Entity
@Table(name = "circular_order_scope")
public class CircularOrderScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "circular_scope_id")
    private Long circularScopeId;

    @Column(name = "circular_order_id", nullable = false)
    private Long circularOrderId;

    @Column(name = "scope_type", nullable = false, length = 15)
    private String scopeType;

    @Column(name = "payroll_type", length = 20)
    private String payrollType;

    @Column(name = "pay_pattern", length = 10)
    private String payPattern;

    @Column(name = "location_id")
    private Long locationId;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "include_descendants", nullable = false, length = 1)
    private Boolean includeDescendants;

    protected CircularOrderScope() {}

    /** An applicable payroll type (SRS-G-38), optionally narrowed to one staff pay pattern. */
    public static CircularOrderScope payrollType(
            Long circularId, String payrollType, String payPattern) {
        CircularOrderScope s = new CircularOrderScope();
        s.circularOrderId = circularId;
        s.scopeType = "PAYROLL_TYPE";
        s.payrollType = payrollType;
        s.payPattern = payPattern;
        s.includeDescendants = true;
        return s;
    }

    /** An applicable office and, unless {@code descendants} is false, the offices under it. */
    public static CircularOrderScope location(
            Long circularId, Long locationId, boolean descendants) {
        CircularOrderScope s = new CircularOrderScope();
        s.circularOrderId = circularId;
        s.scopeType = "LOCATION";
        s.locationId = locationId;
        s.includeDescendants = descendants;
        return s;
    }

    public Long getCircularScopeId() {
        return circularScopeId;
    }

    public Long getCircularOrderId() {
        return circularOrderId;
    }

    public String getScopeType() {
        return scopeType;
    }

    public String getPayrollType() {
        return payrollType;
    }

    public String getPayPattern() {
        return payPattern;
    }

    public Long getLocationId() {
        return locationId;
    }

    public boolean isIncludeDescendants() {
        return Boolean.TRUE.equals(includeDescendants);
    }
}
