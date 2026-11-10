package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.type.YesNoConverter;

/**
 * {@code it_declaration}: one line of a member's declaration, investment or HRA rent (target DDL
 * v4.0 Section 11; SRS-7-15, SRS-7-16, SRS-7-24; BR-TAX-002, BR-TAX-014). {@code allowed_amount} is
 * the declared amount capped at the section limit (never above the declared, {@code
 * chk_it_decl_allowed}); a line counts in TDS only once its submission is APPROVED.
 */
@Entity
@Table(name = "it_declaration")
public class ItDeclaration {

    /** {@code declaration_kind}. */
    public enum Kind {
        INVESTMENT,
        HRA_RENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "declaration_id")
    private Long declarationId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "submission_id")
    private Long submissionId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "declaration_kind", nullable = false, length = 15)
    private String declarationKind;

    @Column(name = "section_code", nullable = false, length = 20)
    private String sectionCode;

    @Column(name = "declared_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal declaredAmount;

    @Column(name = "allowed_amount", precision = 14, scale = 2)
    private BigDecimal allowedAmount;

    @Column(name = "proof_status", nullable = false, length = 20)
    private String proofStatus;

    @Column(name = "verified_amount", precision = 14, scale = 2)
    private BigDecimal verifiedAmount;

    @Column(name = "verified_by", length = 50)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "frozen_flag", nullable = false, length = 1)
    private Boolean frozen;

    @Column(name = "attachment_id")
    private Long attachmentId;

    @Column(name = "submitted_via", nullable = false, length = 10)
    private String submittedVia;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "approval_id")
    private Long approvalId;

    protected ItDeclaration() {}

    /** A line as the member declared it, capped at {@code allowed}. */
    @SuppressWarnings("java:S107") // the DDL row, one argument per column
    public static ItDeclaration declared(
            Long employeeId,
            Long submissionId,
            String finYear,
            Kind kind,
            String sectionCode,
            BigDecimal declared,
            BigDecimal allowed,
            Long attachmentId,
            Instant at) {
        ItDeclaration d = new ItDeclaration();
        d.employeeId = employeeId;
        d.submissionId = submissionId;
        d.finYear = finYear;
        d.declarationKind = kind.name();
        d.sectionCode = sectionCode;
        d.declaredAmount = declared;
        d.allowedAmount = allowed;
        d.proofStatus = attachmentId == null ? "DECLARED" : "PROOF_SUBMITTED";
        d.attachmentId = attachmentId;
        d.frozen = false;
        d.submittedVia = "ESS";
        d.submittedAt = at;
        return d;
    }

    /** A frozen rent line carried into a later round as it was (SRS-7-15). */
    public ItDeclaration carriedInto(Long nextSubmission, Instant at) {
        ItDeclaration d =
                declared(
                        employeeId,
                        nextSubmission,
                        finYear,
                        kind(),
                        sectionCode,
                        declaredAmount,
                        allowedAmount,
                        attachmentId,
                        at);
        // Already verified and approved: the Maker does not verify it again.
        d.frozen = true;
        d.verifiedAmount = verifiedAmount;
        d.verifiedBy = verifiedBy;
        d.verifiedAt = verifiedAt;
        d.proofStatus = "VERIFIED";
        return d;
    }

    /** The Payroll Maker verified the line at {@code amount} (at most the allowed amount). */
    public void verified(BigDecimal amount, String by, Instant at) {
        verifiedAmount = amount;
        verifiedBy = by;
        verifiedAt = at;
        proofStatus = "VERIFIED";
    }

    /** The Payroll Checker approved the submission; a rent line is frozen for the year. */
    public void approved(Long approval) {
        proofStatus = "APPROVED";
        approvalId = approval;
        if (kind() == Kind.HRA_RENT) {
            frozen = true;
        }
    }

    public void rejected() {
        proofStatus = "REJECTED";
    }

    /** SRS-7-14: no proof by the deadline; the provisional exemption is withdrawn. */
    public void reversed() {
        proofStatus = "REVERSED";
    }

    public Kind kind() {
        return Kind.valueOf(declarationKind);
    }

    public boolean hasProof() {
        return attachmentId != null;
    }

    public Long getDeclarationId() {
        return declarationId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public String getFinYear() {
        return finYear;
    }

    public String getSectionCode() {
        return sectionCode;
    }

    public BigDecimal getDeclaredAmount() {
        return declaredAmount;
    }

    public BigDecimal getAllowedAmount() {
        return allowedAmount;
    }

    public String getProofStatus() {
        return proofStatus;
    }

    public BigDecimal getVerifiedAmount() {
        return verifiedAmount;
    }

    public boolean isFrozen() {
        return Boolean.TRUE.equals(frozen);
    }

    public Long getAttachmentId() {
        return attachmentId;
    }
}
