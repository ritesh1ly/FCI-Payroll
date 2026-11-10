package in.gov.fci.payroll.payroll.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * {@code computation_run}: One computation run of a cycle instance (target DDL v4.0 S08): ACTUAL or
 * SIMULATION.
 */
@Entity
@Table(name = "computation_run")
public class ComputationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "run_id")
    private Long runId;

    @Column(name = "cycle_instance_id", nullable = false)
    private Long cycleInstanceId;

    @Column(name = "run_type", nullable = false, length = 12)
    private String runType;

    @Column(name = "run_no", nullable = false)
    private Integer runNo;

    @Column(name = "payroll_type", length = 20)
    private String payrollType;

    @Column(name = "run_trigger", nullable = false, length = 12)
    private String runTrigger;

    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "population_scope", nullable = false, length = 15)
    private String populationScope;

    @Lob
    @Column(name = "population_ref")
    private String populationRef;

    @Column(name = "start_mode", nullable = false, length = 10)
    private String startMode;

    @Column(name = "run_status", nullable = false, length = 15)
    private String runStatus;

    @Column(name = "started_by", nullable = false, length = 50)
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "parameter_hash", nullable = false, length = 64)
    private String parameterHash;

    @Column(name = "parameters_json", length = 4000)
    private String parametersJson;

    @Column(name = "rule_version_set_hash", nullable = false, length = 64)
    private String ruleVersionSetHash;

    @Column(name = "eligible_count")
    private Long eligibleCount;

    @Column(name = "result_count", nullable = false)
    private Long resultCount;

    @Column(name = "unit_total")
    private Integer unitTotal;

    @Column(name = "unit_committed", nullable = false)
    private Integer unitCommitted;

    @Column(name = "unit_failed", nullable = false)
    private Integer unitFailed;

    @Column(name = "failure_class", length = 15)
    private String failureClass;

    @Column(name = "resumed_from_run_id")
    private Long resumedFromRunId;

    @Column(name = "superseded_by_return_id")
    private Long supersededByReturnId;

    @Column(name = "abandon_after")
    private Instant abandonAfter;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected ComputationRun() {}

    public Long getRunId() {
        return runId;
    }

    public Long getCycleInstanceId() {
        return cycleInstanceId;
    }

    public String getRunType() {
        return runType;
    }

    public Integer getRunNo() {
        return runNo;
    }

    public String getPayrollType() {
        return payrollType;
    }

    public String getRunTrigger() {
        return runTrigger;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public String getPopulationScope() {
        return populationScope;
    }

    public String getPopulationRef() {
        return populationRef;
    }

    public String getStartMode() {
        return startMode;
    }

    public String getRunStatus() {
        return runStatus;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getParameterHash() {
        return parameterHash;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public String getRuleVersionSetHash() {
        return ruleVersionSetHash;
    }

    public Long getEligibleCount() {
        return eligibleCount;
    }

    public Long getResultCount() {
        return resultCount;
    }

    public Integer getUnitTotal() {
        return unitTotal;
    }

    public Integer getUnitCommitted() {
        return unitCommitted;
    }

    public Integer getUnitFailed() {
        return unitFailed;
    }

    public String getFailureClass() {
        return failureClass;
    }

    public Long getResumedFromRunId() {
        return resumedFromRunId;
    }

    public Long getSupersededByReturnId() {
        return supersededByReturnId;
    }

    public Instant getAbandonAfter() {
        return abandonAfter;
    }
}
