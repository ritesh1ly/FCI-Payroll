package in.gov.fci.payroll.cpf.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.YesNoConverter;

/**
 * {@code borrowing_organisation}: an organisation a member is deputed to, which remits the CPF
 * contributions and advance instalments recorded on I-09 (target DDL v4.0 Section 10; SRS-6-27,
 * BR-CPF-016). Never deleted: an organisation no longer used is made inactive (R-DEL).
 */
@Entity
@Table(name = "borrowing_organisation")
public class BorrowingOrganisation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "borrowing_org_id")
    private Long id;

    @Column(name = "org_code", nullable = false, length = 30)
    private String orgCode;

    @Column(name = "org_name", nullable = false, length = 250)
    private String orgName;

    @Column(name = "contact_detail", length = 500)
    private String contactDetail;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "active_flag", nullable = false, length = 1)
    private Boolean active;

    protected BorrowingOrganisation() {}

    /** A new, active organisation. */
    public static BorrowingOrganisation of(String code, String name, String contact) {
        BorrowingOrganisation o = new BorrowingOrganisation();
        o.orgCode = code;
        o.orgName = name;
        o.contactDetail = contact;
        o.active = true;
        return o;
    }

    public Long getId() {
        return id;
    }

    public String getOrgCode() {
        return orgCode;
    }

    public String getOrgName() {
        return orgName;
    }

    public String getContactDetail() {
        return contactDetail;
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(active);
    }
}
