package in.gov.fci.payroll.tax.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import org.hibernate.type.YesNoConverter;

/**
 * {@code it_declaration_submission}: one round of a member's declarations for a financial year
 * (target DDL v4.0 Section 11; SRS-7-24, BR-TAX-014). A resubmission is a new {@code
 * submission_no}; the earlier one is kept (SUPERSEDED) as Form 12BB evidence.
 */
@Entity
@Table(name = "it_declaration_submission")
public class ItDeclarationSubmission {

    /** {@code submission_status} values this module writes. */
    public enum Status {
        SUBMITTED,
        VERIFIED,
        APPROVED,
        RETURNED,
        SUPERSEDED
    }

    /** A round the member may still replace with a new one. */
    private static final Set<Status> REPLACEABLE =
            Set.of(Status.SUBMITTED, Status.RETURNED, Status.APPROVED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "submission_id")
    private Long submissionId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "submission_no", nullable = false)
    private Integer submissionNo;

    @Column(name = "regime_detail_id")
    private Long regimeDetailId;

    @Column(name = "submitted_via", nullable = false, length = 10)
    private String submittedVia;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "submission_status", nullable = false, length = 20)
    private String submissionStatus;

    @Column(name = "verified_by", length = 50)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "approval_id")
    private Long approvalId;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "proof_deadline_reversed", nullable = false, length = 1)
    private Boolean proofDeadlineReversed;

    @Column(name = "return_reason", length = 1000)
    private String returnReason;

    protected ItDeclarationSubmission() {}

    /** The member's round {@code number} in eSeva. */
    public static ItDeclarationSubmission submitted(
            Long employeeId, String finYear, int number, Long regimeDetailId, Instant at) {
        ItDeclarationSubmission s = new ItDeclarationSubmission();
        s.employeeId = employeeId;
        s.finYear = finYear;
        s.submissionNo = number;
        s.regimeDetailId = regimeDetailId;
        s.submittedVia = "ESS";
        s.submittedAt = at;
        s.submissionStatus = Status.SUBMITTED.name();
        s.proofDeadlineReversed = false;
        return s;
    }

    public Status status() {
        return Status.valueOf(submissionStatus);
    }

    /**
     * A newer round may follow: SUBMITTED or RETURNED (replaced at once) or APPROVED (replaced when
     * the newer round is approved), never one at the Checker.
     */
    public boolean replaceable() {
        return REPLACEABLE.contains(status());
    }

    public void supersede() {
        submissionStatus = Status.SUPERSEDED.name();
    }

    /** The Payroll Maker verified it and sent it to the Checker (approval {@code approval}). */
    public void verified(String by, Instant at, Long approval) {
        submissionStatus = Status.VERIFIED.name();
        verifiedBy = by;
        verifiedAt = at;
        approvalId = approval;
    }

    /** SRS-7-24: back to the member with the reason. */
    public void returned(String reason) {
        submissionStatus = Status.RETURNED.name();
        returnReason = reason;
    }

    public void approved() {
        submissionStatus = Status.APPROVED.name();
    }

    /** SRS-7-14: the provisional exemption of the unproved lines was reversed. */
    public void proofDeadlineReversed() {
        proofDeadlineReversed = true;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getFinYear() {
        return finYear;
    }

    public int getSubmissionNo() {
        return submissionNo;
    }

    public Long getRegimeDetailId() {
        return regimeDetailId;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getVerifiedBy() {
        return verifiedBy;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public boolean isProofDeadlineReversed() {
        return Boolean.TRUE.equals(proofDeadlineReversed);
    }

    public String getReturnReason() {
        return returnReason;
    }
}
