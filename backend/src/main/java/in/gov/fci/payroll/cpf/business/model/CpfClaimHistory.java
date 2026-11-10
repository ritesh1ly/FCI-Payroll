package in.gov.fci.payroll.cpf.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code cpf_claim_history}: one status change of a member's CPF request, with the chain stage, who
 * changed it and why (target DDL v4.0 Section 10; SRS-6-4: the member sees the reason of a return).
 * Appended, never changed.
 */
@Entity
@Table(name = "cpf_claim_history")
public class CpfClaimHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "claim_history_id")
    private Long claimHistoryId;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    @Column(name = "workflow_stage", length = 30)
    private String workflowStage;

    @Column(name = "changed_by", nullable = false, length = 50)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "remark", length = 1000)
    private String remark;

    protected CpfClaimHistory() {}

    @SuppressWarnings("java:S107") // the DDL row
    public static CpfClaimHistory of(
            Long claimId,
            String from,
            String to,
            String stage,
            String by,
            Instant at,
            String remark) {
        CpfClaimHistory h = new CpfClaimHistory();
        h.claimId = claimId;
        h.fromStatus = from;
        h.toStatus = to;
        h.workflowStage = stage;
        h.changedBy = by;
        h.changedAt = at;
        h.remark = remark;
        return h;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public String getWorkflowStage() {
        return workflowStage;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getRemark() {
        return remark;
    }
}
