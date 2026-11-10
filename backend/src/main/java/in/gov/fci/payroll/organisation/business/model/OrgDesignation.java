package in.gov.fci.payroll.organisation.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code org_designation} (target DDL S02), the attributes M-01 displays. */
@Entity
@Table(name = "org_designation")
public class OrgDesignation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "designation_id")
    private Long id;

    @Column(name = "designation_code", nullable = false, length = 20)
    private String code;

    @Column(name = "designation_name", nullable = false, length = 150)
    private String name;

    @Column(name = "designation_family", nullable = false, length = 20)
    private String family;

    protected OrgDesignation() {}

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getFamily() {
        return family;
    }
}
