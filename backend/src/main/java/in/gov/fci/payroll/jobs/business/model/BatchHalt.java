package in.gov.fci.payroll.jobs.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * The global batch halt ({@code batch_halt}, V038_1; plan 05j step j4): while a row is not
 * released, batch nodes launch nothing, apply no Resume / Restart and stop running jobs at their
 * next chunk. Engaged at once by a System Administrator at HQ; released only through an approved
 * request (C13_RELEASE). Never deleted: the rows are the history of every halt.
 */
@Entity
@Table(name = "batch_halt")
public class BatchHalt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "halt_id")
    private Long id;

    @Column(name = "halted_by", nullable = false, length = 150)
    private String haltedBy;

    @Column(name = "halted_at", nullable = false)
    private Instant haltedAt;

    @Column(name = "halt_reason", nullable = false, length = 500)
    private String haltReason;

    @Column(name = "release_approval_id")
    private Long releaseApprovalId;

    @Column(name = "release_requested_by", length = 150)
    private String releaseRequestedBy;

    @Column(name = "release_reason", length = 500)
    private String releaseReason;

    @Column(name = "released_by", length = 150)
    private String releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long version;

    protected BatchHalt() {}

    public static BatchHalt engage(String login, String reason, Instant now) {
        BatchHalt halt = new BatchHalt();
        halt.haltedBy = login;
        halt.haltReason = reason;
        halt.haltedAt = now;
        return halt;
    }

    /** A release was asked for (it takes effect only when approved). */
    public void releaseRequested(Long approvalId, String login, String reason) {
        this.releaseApprovalId = approvalId;
        this.releaseRequestedBy = login;
        this.releaseReason = reason;
    }

    /** The release was rejected or returned: the halt stays and a new release may be asked. */
    public void releaseRefused() {
        this.releaseApprovalId = null;
        this.releaseRequestedBy = null;
        this.releaseReason = null;
    }

    public void released(String checkerLogin, Instant now) {
        this.releasedBy = checkerLogin;
        this.releasedAt = now;
    }

    public boolean isEngaged() {
        return releasedAt == null;
    }

    public Long getId() {
        return id;
    }

    public String getHaltedBy() {
        return haltedBy;
    }

    public Instant getHaltedAt() {
        return haltedAt;
    }

    public String getHaltReason() {
        return haltReason;
    }

    public Long getReleaseApprovalId() {
        return releaseApprovalId;
    }

    public String getReleaseRequestedBy() {
        return releaseRequestedBy;
    }

    public String getReleaseReason() {
        return releaseReason;
    }

    public String getReleasedBy() {
        return releasedBy;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
