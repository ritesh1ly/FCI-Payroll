package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.jobs.api.AbandonListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SRS-15-27 / BR-CYC-014 (plan 05j): when a computation job is abandoned, its run is ABANDONED
 * ({@code computation_run.run_status}) and its results are discarded (SRS l.1496 "discard its
 * results"; BMAD TR-064): the result rows of a dry run (SIMULATION, transient by the DDL, SRS-15-7
 * "keep the run record, discard the result rows") are removed as the dry-run purge removes them;
 * the results of an Actual run stay QUARANTINED, never of record (R-DEL: business results are never
 * deleted), so gate G0 keeps the cycle from being verified, locked or posted on them. The run
 * record is always kept. A run that completed is not touched (only a failed or partial one can
 * belong to an abandoned job).
 */
@Component
public class ComputationAbandonment implements AbandonListener {

    private static final Logger log = LoggerFactory.getLogger(ComputationAbandonment.class);

    /**
     * The runs of one abandoned job request (a compile-time constant, so every statement below is
     * one fixed SQL text with bind values only).
     */
    private static final String OF_THE_REQUEST =
            " IN (SELECT run_id FROM computation_run WHERE parameters_json LIKE ? AND"
                    + " run_status = 'ABANDONED')";

    private final JdbcTemplate jdbc;

    public ComputationAbandonment(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void abandoned(long jobRequestId, String jobName) {
        if (!ComputationResumeCheck.JOBS.contains(jobName)) {
            return;
        }
        // The run's request is in parameters_json, as CycleRuns writes it (ComputationResumeCheck).
        int runs =
                jdbc.update(
                        "UPDATE computation_run SET run_status = 'ABANDONED', row_version ="
                                + " row_version + 1 WHERE parameters_json LIKE ? AND run_status IN"
                                + " ('FAILED', 'PARTIAL', 'RUNNING')",
                        "{\"jobRequestId\":" + jobRequestId + ",%");
        // SRS-15-7 / SRS-15-27: the abandoned dry run's transient rows are discarded.
        jdbc.update(
                "DELETE FROM payroll_result_component WHERE run_type = 'SIMULATION' AND is_locked ="
                        + " 'N' AND payroll_result_id IN (SELECT payroll_result_id FROM"
                        + " payroll_result WHERE run_type = 'SIMULATION' AND run_id"
                        + OF_THE_REQUEST
                        + ")",
                "{\"jobRequestId\":" + jobRequestId + ",%");
        int discarded =
                jdbc.update(
                        "DELETE FROM payroll_result WHERE run_type = 'SIMULATION' AND is_locked ="
                                + " 'N' AND run_id"
                                + OF_THE_REQUEST,
                        "{\"jobRequestId\":" + jobRequestId + ",%");
        log.info(
                "event=COMPUTATION_RUN_ABANDONED jobRequestId={} runs={}"
                        + " discardedSimulationRows={}",
                jobRequestId,
                runs,
                discarded);
    }
}
