package in.gov.fci.payroll.payroll.batch.computation;

import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Execution-context keys of the computation jobs (job context: the run; step context: the unit).
 */
public final class ComputationKeys {

    public static final String RUN_ID = "fci.runId";
    public static final String CYCLE_ID = "fci.cycleInstanceId";
    public static final String ORG_UNIT = "fci.orgUnitId";
    public static final String YYMM = "fci.payrollYymm";
    public static final String PERIOD_DATE = "fci.periodDate";
    public static final String RUN_TYPE = "fci.runType";
    public static final String ELIGIBLE = "fci.eligible";
    public static final String WORK_UNIT_ID = "fci.workUnitId";

    private ComputationKeys() {}

    static void put(ExecutionContext job, CycleRuns.OpenRun run) {
        job.putLong(RUN_ID, run.runId());
        job.putLong(CYCLE_ID, run.cycleId());
        job.putLong(ORG_UNIT, run.orgUnitId());
        job.putString(YYMM, run.yymm());
        job.putString(PERIOD_DATE, run.periodDate().toString());
        job.putString(RUN_TYPE, run.runType().name());
    }

    static CycleRuns.OpenRun get(ExecutionContext job) {
        return new CycleRuns.OpenRun(
                job.getLong(RUN_ID),
                job.getLong(CYCLE_ID),
                job.getLong(ORG_UNIT),
                job.getString(YYMM),
                java.time.LocalDate.parse(job.getString(PERIOD_DATE)),
                CycleRuns.RunType.valueOf(job.getString(RUN_TYPE)));
    }
}
