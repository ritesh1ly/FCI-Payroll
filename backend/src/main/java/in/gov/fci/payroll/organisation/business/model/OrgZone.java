package in.gov.fci.payroll.organisation.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.YesNoConverter;

/** {@code org_zone} (target DDL S02). CPF/EPS bands are not used by Phase 1 and not mapped. */
@Entity
@Table(name = "org_zone")
public class OrgZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "zone_id")
    private Long id;

    @Column(name = "zone_code", nullable = false, length = 10)
    private String code;

    @Column(name = "zone_name", nullable = false, length = 100)
    private String name;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "active_flag", nullable = false, length = 1)
    private Boolean active;

    protected OrgZone() {}

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(active);
    }
}
