package in.gov.fci.payroll.organisation.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.YesNoConverter;

/**
 * {@code org_location} (target DDL S02): the office hierarchy through {@code parent_location_id}. A
 * closed location is end-dated, never deleted.
 */
@Entity
@Table(name = "org_location")
public class OrgLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "location_id")
    private Long id;

    @Column(name = "location_code", nullable = false, length = 20)
    private String code;

    @Column(name = "location_name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 10)
    private LocationType type;

    @Column(name = "parent_location_id")
    private Long parentId;

    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "city_class", length = 1)
    private String cityClass;

    /** The state of the office (Professional Tax by state, BR-TAX-006); read-only here. */
    @Column(name = "state_code", length = 10, insertable = false, updatable = false)
    private String stateCode;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_cycle_unit", nullable = false, length = 1)
    private Boolean cycleUnit;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "active_flag", nullable = false, length = 1)
    private Boolean active;

    protected OrgLocation() {}

    /** Active and inside its effective period on {@code day}. */
    public boolean isActiveOn(LocalDate day) {
        return Boolean.TRUE.equals(active)
                && !day.isBefore(effectiveFrom)
                && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /** {@code DO — Delhi HQ (DH001)}: the login-location format of the FCILekha login page. */
    public String displayLabel() {
        return type.name() + " — " + name + " (" + code + ")";
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public LocationType getType() {
        return type;
    }

    public Long getParentId() {
        return parentId;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public String getCityClass() {
        return cityClass;
    }

    public String getStateCode() {
        return stateCode;
    }

    public boolean isCycleUnit() {
        return Boolean.TRUE.equals(cycleUnit);
    }
}
