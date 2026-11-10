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
 * {@code cpf_purpose_document}: one document of a purpose version's checklist (target DDL v4.0
 * Section 10; SRS-6-14): the document type, whether it is mandatory and the tier that verifies it.
 * Belongs to one purpose version; a new version carries its own checklist.
 */
@Entity
@Table(name = "cpf_purpose_document")
public class CpfPurposeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "purpose_doc_id")
    private Long purposeDocId;

    @Column(name = "purpose_id", nullable = false)
    private Long purposeId;

    @Column(name = "document_type_code", nullable = false, length = 40)
    private String documentTypeCode;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_mandatory", nullable = false, length = 1)
    private Boolean isMandatory;

    @Column(name = "verification_tier", length = 25)
    private String verificationTier;

    protected CpfPurposeDocument() {}

    public static CpfPurposeDocument of(
            Long purposeId, String documentType, boolean mandatory, String tier) {
        CpfPurposeDocument d = new CpfPurposeDocument();
        d.purposeId = purposeId;
        d.documentTypeCode = documentType;
        d.isMandatory = mandatory;
        d.verificationTier = tier;
        return d;
    }

    public Long getPurposeDocId() {
        return purposeDocId;
    }

    public Long getPurposeId() {
        return purposeId;
    }

    public String getDocumentTypeCode() {
        return documentTypeCode;
    }

    public boolean isMandatory() {
        return Boolean.TRUE.equals(isMandatory);
    }

    public String getVerificationTier() {
        return verificationTier;
    }
}
