package in.gov.fci.payroll.payroll.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code computation_work_unit}: One unit of work of a computation run (target DDL v4.0 S08). */
@Entity
@Table(name = "computation_work_unit")
public class ComputationWorkUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "work_unit_id")
    private Long workUnitId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "unit_no", nullable = false)
    private Integer unitNo;

    @Column(name = "org_unit_id", nullable = false)
    private Long orgUnitId;

    @Column(name = "member_from_key")
    private Long memberFromKey;

    @Column(name = "member_to_key")
    private Long memberToKey;

    @Column(name = "member_count", nullable = false)
    private Integer memberCount;

    @Column(name = "unit_status", nullable = false, length = 12)
    private String unitStatus;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "results_committed")
    private Integer resultsCommitted;

    @Column(name = "failure_detail", length = 2000)
    private String failureDetail;

    protected ComputationWorkUnit() {}

    public Long getWorkUnitId() {
        return workUnitId;
    }

    public Long getRunId() {
        return runId;
    }

    public Integer getUnitNo() {
        return unitNo;
    }

    public Long getOrgUnitId() {
        return orgUnitId;
    }

    public Long getMemberFromKey() {
        return memberFromKey;
    }

    public Long getMemberToKey() {
        return memberToKey;
    }

    public Integer getMemberCount() {
        return memberCount;
    }

    public String getUnitStatus() {
        return unitStatus;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public Instant getCommittedAt() {
        return committedAt;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public Integer getResultsCommitted() {
        return resultsCommitted;
    }

    public String getFailureDetail() {
        return failureDetail;
    }
}
