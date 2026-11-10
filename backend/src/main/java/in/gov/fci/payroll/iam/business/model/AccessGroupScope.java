package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code access_group_scope} (target DDL v4.0 S27): at most one of zone or location per row
 * (chk_access_group_scope_one). v4.0 has no department scope.
 */
@Entity
@Table(name = "access_group_scope")
public class AccessGroupScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "scope_id")
    private Long id;

    @Column(name = "access_group_id", nullable = false)
    private Long accessGroupId;

    @Column(name = "zone_id")
    private Long zoneId;

    @Column(name = "location_id")
    private Long locationId;

    protected AccessGroupScope() {}

    public static AccessGroupScope of(Long groupId, Long zoneId, Long locationId) {
        if (zoneId != null && locationId != null) {
            throw new IllegalArgumentException("At most one of zone or location");
        }
        AccessGroupScope scope = new AccessGroupScope();
        scope.accessGroupId = groupId;
        scope.zoneId = zoneId;
        scope.locationId = locationId;
        return scope;
    }

    public Long getId() {
        return id;
    }

    public Long getAccessGroupId() {
        return accessGroupId;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public Long getLocationId() {
        return locationId;
    }
}
