package in.gov.fci.payroll.general.business.model;

import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.general.business.Inputs;
import in.gov.fci.payroll.platform.error.FciException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;

/**
 * {@code hrms_fallback_upload} (target DDL v4.0 S08; X-07; SRS-DM-18, SRS-G-46): one uploaded HRMS
 * export file of one office for one enablement, with its validation counts and decision.
 *
 * <p>Lifecycle and the DDL rules it mirrors:
 *
 * <pre>
 * VALIDATED ──submit──▶ PENDING_APPROVAL ──Checker (OTP)──▶ APPROVED   chk_hrms_fb_upload_otp, _sod
 *     ▲                        ├─────return (reason)──────▶ RETURNED    chk_hrms_fb_upload_rsn
 *     │                        └─────reject (reason)──────▶ REJECTED
 *     └── a new upload of the same office supersedes a VALIDATED / RETURNED one ──▶ SUPERSEDED
 * VALIDATED / RETURNED ──cancel──▶ CANCELLED   (never deleted, SRS-G-37)
 * </pre>
 *
 * {@code record_count = accepted_count + rejected_count} always ({@code chk_hrms_fb_upload_count}).
 * Only the ACCEPTED lines are approved; rejected lines are listed with their reason and never used.
 */
@Entity
@Table(name = "hrms_fallback_upload")
public class FallbackUpload {

    /** {@code upload_status} (CHECK of the DDL). */
    public enum Status {
        VALIDATED,
        PENDING_APPROVAL,
        APPROVED,
        RETURNED,
        REJECTED,
        SUPERSEDED,
        CANCELLED
    }

    /** {@code recon_status} (CHECK of the DDL). */
    public enum ReconStatus {
        NOT_DUE,
        PENDING,
        IN_PROGRESS,
        RECONCILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fallback_upload_id")
    private Long id;

    @Column(name = "batch_no", nullable = false, length = 30)
    private String batchNo;

    @Column(name = "enablement_id", nullable = false)
    private Long enablementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fallback_type", nullable = false, length = 25)
    private FallbackEnablement.Type type;

    @Column(name = "org_unit_id", nullable = false)
    private Long orgUnitId;

    @Column(name = "cycle_instance_id")
    private Long cycleInstanceId;

    @Column(name = "payroll_yymm", nullable = false, length = 6)
    private String payrollYymm;

    @Column(name = "source_file_name", nullable = false, length = 260)
    private String sourceFileName;

    /** SHA-256 of the file as uploaded. */
    @Column(name = "source_file_hash", nullable = false, length = 64)
    private String sourceFileHash;

    @Column(name = "source_attachment_id")
    private Long sourceAttachmentId;

    @Column(name = "record_count", nullable = false)
    private Integer recordCount;

    @Column(name = "accepted_count", nullable = false)
    private Integer acceptedCount;

    @Column(name = "rejected_count", nullable = false)
    private Integer rejectedCount;

    @Column(name = "uploaded_by", nullable = false, length = 50)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false, length = 20)
    private Status status;

    @Column(name = "checker_user", length = 50)
    private String checkerUser;

    @Column(name = "checker_decided_at")
    private Instant checkerDecidedAt;

    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "otp_challenge_id")
    private Long otpChallengeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recon_status", nullable = false, length = 15)
    private ReconStatus reconStatus;

    @Column(name = "hrms_restored_at")
    private Instant hrmsRestoredAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected FallbackUpload() {}

    /** A validated upload: counts of the validation, the kept source file and its hash. */
    public static FallbackUpload validated(
            String batchNo,
            FallbackEnablement enablement,
            Long orgUnitId,
            Long cycleInstanceId,
            String fileName,
            String fileHash,
            Long sourceAttachmentId,
            int accepted,
            int rejected,
            String uploadedBy,
            Instant now) {
        FallbackUpload u = new FallbackUpload();
        u.batchNo = batchNo;
        u.enablementId = enablement.getId();
        u.type = enablement.getType();
        u.payrollYymm = enablement.getPayrollYymm();
        u.orgUnitId = orgUnitId;
        u.cycleInstanceId = cycleInstanceId;
        u.sourceFileName = Inputs.cutEnd(fileName, 260);
        u.sourceFileHash = fileHash;
        u.sourceAttachmentId = sourceAttachmentId;
        u.acceptedCount = accepted;
        u.rejectedCount = rejected;
        u.recordCount = accepted + rejected; // chk_hrms_fb_upload_count
        u.uploadedBy = cut(uploadedBy);
        u.uploadedAt = now;
        u.status = Status.VALIDATED;
        u.reconStatus = ReconStatus.NOT_DUE;
        return u;
    }

    /** The source file is attached once the row has its id (the attachment's owning record). */
    public void sourceFile(Long attachmentId) {
        this.sourceAttachmentId = attachmentId;
    }

    /**
     * Submitted for the Checker; only a VALIDATED or RETURNED upload with at least one accepted
     * line.
     */
    public void submit(Long approvalId) {
        requireStatus(Status.VALIDATED, Status.RETURNED);
        if (acceptedCount == 0) {
            // Nothing to approve: the Checker "approves accepted records only".
            throw new FciException(GeneralErrorCode.FALLBACK_NOTHING_ACCEPTED);
        }
        this.approvalId = approvalId;
        this.status = Status.PENDING_APPROVAL;
    }

    /**
     * The Checker's approval with OTP. The DDL refuses an APPROVED row without checker, OTP and
     * approval ({@code chk_hrms_fb_upload_otp}) and with the uploader as checker ({@code _sod});
     * the same rules are enforced here so the violation is a clear error, not a constraint failure.
     */
    public void approve(String checker, Long otpChallenge, Instant now) {
        requireStatus(Status.PENDING_APPROVAL);
        if (otpChallenge == null) {
            throw new IllegalStateException(
                    "An X-07 approval needs the checker's OTP (HRMS_FALLBACK_UPLOAD_APPROVAL)");
        }
        if (cut(checker).equals(uploadedBy)) {
            throw new IllegalStateException("The uploader cannot approve the upload");
        }
        this.checkerUser = cut(checker);
        this.checkerDecidedAt = now;
        this.otpChallengeId = otpChallenge;
        this.status = Status.APPROVED;
        // Reconciliation with HRMS is due once HRMS is back (SRS-G-47).
        this.reconStatus = ReconStatus.PENDING;
    }

    /**
     * Returned or rejected by the Checker, with the mandatory reason ({@code
     * chk_hrms_fb_upload_rsn}).
     */
    public void decline(Status outcome, String checker, String reason, Instant now) {
        requireStatus(Status.PENDING_APPROVAL);
        if (outcome != Status.RETURNED && outcome != Status.REJECTED) {
            throw new IllegalArgumentException(outcome.name());
        }
        this.checkerUser = cut(checker);
        this.checkerDecidedAt = now;
        this.decisionReason =
                reason == null || reason.isBlank() ? "Returned without a stated reason" : reason;
        this.status = outcome;
    }

    /** A newer upload of the same office replaces this one ("resubmits as a new version"). */
    public void supersede() {
        requireStatus(Status.VALIDATED, Status.RETURNED);
        this.status = Status.SUPERSEDED;
    }

    /** Cancelled by the Maker before approval; never deleted (SRS-G-37). */
    public void cancel(String reason) {
        requireStatus(Status.VALIDATED, Status.RETURNED);
        this.decisionReason = reason;
        this.status = Status.CANCELLED;
    }

    private void requireStatus(Status... allowed) {
        if (!List.of(allowed).contains(status)) {
            throw new FciException(
                    GeneralErrorCode.FALLBACK_STATUS,
                    new Object[] {status.name()},
                    List.of(),
                    null);
        }
    }

    private static String cut(String login) {
        return Inputs.cut(login, 50);
    }

    public Long getId() {
        return id;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public Long getEnablementId() {
        return enablementId;
    }

    public FallbackEnablement.Type getType() {
        return type;
    }

    public Long getOrgUnitId() {
        return orgUnitId;
    }

    public Long getCycleInstanceId() {
        return cycleInstanceId;
    }

    public String getPayrollYymm() {
        return payrollYymm;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public String getSourceFileHash() {
        return sourceFileHash;
    }

    public Long getSourceAttachmentId() {
        return sourceAttachmentId;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public int getAcceptedCount() {
        return acceptedCount;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public Status getStatus() {
        return status;
    }

    public String getCheckerUser() {
        return checkerUser;
    }

    public Instant getCheckerDecidedAt() {
        return checkerDecidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public Long getOtpChallengeId() {
        return otpChallengeId;
    }

    public Instant getHrmsRestoredAt() {
        return hrmsRestoredAt;
    }

    public ReconStatus getReconStatus() {
        return reconStatus;
    }
}
