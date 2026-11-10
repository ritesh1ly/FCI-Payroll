package in.gov.fci.payroll.organisation.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code city_type_location_map}: City class and remote-area type of a location, effective-dated
 * (target DDL v4.0 S02): drives HRA / CCA.
 */
@Entity
@Table(name = "city_type_location_map")
public class CityTypeLocationMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "map_id")
    private Long mapId;

    @Column(name = "location_id", nullable = false)
    private Long locationId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "city_class", length = 1)
    private String cityClass;

    @Column(name = "remote_area_type", length = 20)
    private String remoteAreaType;

    @Column(name = "nwnp_loc_type", length = 20)
    private String nwnpLocType;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "approval_id")
    private Long approvalId;

    protected CityTypeLocationMap() {}

    public Long getMapId() {
        return mapId;
    }

    public Long getLocationId() {
        return locationId;
    }

    public String getCityClass() {
        return cityClass;
    }

    public String getRemoteAreaType() {
        return remoteAreaType;
    }

    public String getNwnpLocType() {
        return nwnpLocType;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
