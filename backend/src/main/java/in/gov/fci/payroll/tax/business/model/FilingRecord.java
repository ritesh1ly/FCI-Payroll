package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code statutory_filing_record}: the filing of a released return by FCI's authorised signatory
 * (target DDL v4.0 Section 11; SRS-7-31): the date, the signatory's name and designation as text
 * (not a system credential), the acknowledgement token and the evidence. Insert-only.
 */
@Entity
@Table(name = "statutory_filing_record")
public class FilingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "filing_id")
    private Long filingId;

    @Column(name = "return_id")
    private Long returnId;

    @Column(name = "filing_date", nullable = false)
    private LocalDate filingDate;

    @Column(name = "signatory_name", nullable = false, length = 150)
    private String signatoryName;

    @Column(name = "signatory_designation", nullable = false, length = 150)
    private String signatoryDesignation;

    @Column(name = "acknowledgement_token", length = 100)
    private String acknowledgementToken;

    @Column(name = "evidence_attachment_id")
    private Long evidenceAttachmentId;

    @Column(name = "recorded_by", nullable = false, length = 50)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected FilingRecord() {}

    @SuppressWarnings("java:S107") // the DDL row, one argument per column
    public static FilingRecord of(
            Long returnId,
            LocalDate date,
            String name,
            String designation,
            String token,
            Long evidence,
            String by,
            Instant at) {
        FilingRecord f = new FilingRecord();
        f.returnId = returnId;
        f.filingDate = date;
        f.signatoryName = name;
        f.signatoryDesignation = designation;
        f.acknowledgementToken = token;
        f.evidenceAttachmentId = evidence;
        f.recordedBy = by;
        f.recordedAt = at;
        return f;
    }

    public Long getFilingId() {
        return filingId;
    }

    public Long getReturnId() {
        return returnId;
    }

    public LocalDate getFilingDate() {
        return filingDate;
    }

    public String getSignatoryName() {
        return signatoryName;
    }

    public String getSignatoryDesignation() {
        return signatoryDesignation;
    }

    public String getAcknowledgementToken() {
        return acknowledgementToken;
    }

    public Long getEvidenceAttachmentId() {
        return evidenceAttachmentId;
    }
}
