package in.gov.fci.payroll.cpf.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code cpf_interest_run}: one C-12 interest run or re-run of a zone's financial year, audited
 * individually (target DDL v4.0 Section 10; SRS-6-45, FRS V0.5 §13.3): scope, initiator, time,
 * member counts and reason, so it is always demonstrable how many re-runs preceded the freeze and
 * who initiated each. Written once, complete; never changed or deleted.
 */
@Entity
@Table(name = "cpf_interest_run")
public class CpfInterestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "interest_run_id")
    private Long interestRunId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "zone_id")
    private Long zoneId;

    @Column(name = "run_number", nullable = false)
    private Integer runNumber;

    @Column(name = "run_scope", nullable = false, length = 15)
    private String runScope;

    @Column(name = "scope_employee_id")
    private Long scopeEmployeeId;

    @Column(name = "initiated_by", nullable = false, length = 50)
    private String initiatedBy;

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "members_calculated")
    private Long membersCalculated;

    @Column(name = "members_not_calculated")
    private Long membersNotCalculated;

    @Column(name = "members_interest_stopped")
    private Long membersInterestStopped;

    @Column(name = "interest_rate_id")
    private Long interestRateId;

    @Column(name = "run_status", nullable = false, length = 15)
    private String runStatus;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected CpfInterestRun() {}

    /** The counts a run reached over the members it covered. */
    public record Counts(long calculated, long notCalculated, long stopped) {}

    /** A run that went through its members (COMPLETED), as recorded the moment it finished. */
    @SuppressWarnings("java:S107") // the DDL row, one argument per audited column
    public static CpfInterestRun completed(
            String finYear,
            Long zoneId,
            int runNumber,
            String scope,
            Long scopeEmployeeId,
            String initiatedBy,
            Instant initiatedAt,
            String reason,
            Counts counts,
            Long interestRateId,
            Instant completedAt) {
        CpfInterestRun r = new CpfInterestRun();
        r.finYear = finYear;
        r.zoneId = zoneId;
        r.runNumber = runNumber;
        r.runScope = scope;
        r.scopeEmployeeId = scopeEmployeeId;
        r.initiatedBy = initiatedBy;
        r.initiatedAt = initiatedAt;
        r.reason = reason;
        r.membersCalculated = counts.calculated();
        r.membersNotCalculated = counts.notCalculated();
        r.membersInterestStopped = counts.stopped();
        r.interestRateId = interestRateId;
        r.runStatus = "COMPLETED";
        r.completedAt = completedAt;
        return r;
    }

    public Long getInterestRunId() {
        return interestRunId;
    }

    public String getFinYear() {
        return finYear;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public Integer getRunNumber() {
        return runNumber;
    }

    public String getRunScope() {
        return runScope;
    }

    public Long getScopeEmployeeId() {
        return scopeEmployeeId;
    }

    public String getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getInitiatedAt() {
        return initiatedAt;
    }

    public String getReason() {
        return reason;
    }

    public Long getMembersCalculated() {
        return membersCalculated;
    }

    public Long getMembersNotCalculated() {
        return membersNotCalculated;
    }

    public Long getMembersInterestStopped() {
        return membersInterestStopped;
    }

    public Long getInterestRateId() {
        return interestRateId;
    }

    public String getRunStatus() {
        return runStatus;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
