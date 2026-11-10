package in.gov.fci.payroll.document.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.YesNoConverter;

/**
 * {@code document_type_master} (target DDL v4.0 S25): the kinds of document an order control
 * accepts. SRS-G-30 / SRS-DM-13 name four: Sanction Order, Circular, Certificate, Other. {@code
 * requires_reference_number} carries the rule "order or reference number mandatory for a sanction
 * order or circular" as data, not as a list of type names in code (OC-3). Read-only here: the
 * catalogue is maintained as reference data.
 */
@Entity
@Table(name = "document_type_master")
public class DocumentType {

    @Id
    @Column(name = "document_type_id")
    private Long id;

    @Column(name = "document_type_code", nullable = false, length = 40)
    private String code;

    @Column(name = "document_type_name", nullable = false, length = 200)
    private String name;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "requires_reference_number", nullable = false, length = 1)
    private Boolean requiresReferenceNumber;

    /** GENERAL, PERSONAL, SANCTION, CERTIFICATE or OTHER (CHECK of the DDL). */
    @Column(name = "order_class", nullable = false, length = 11)
    private String orderClass;

    protected DocumentType() {}

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean requiresReferenceNumber() {
        return Boolean.TRUE.equals(requiresReferenceNumber);
    }

    public String getOrderClass() {
        return orderClass;
    }
}
