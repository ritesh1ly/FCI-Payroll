package in.gov.fci.payroll.tax.batch;

import in.gov.fci.payroll.jobs.batch.support.PartitionedSteps;
import in.gov.fci.payroll.jobs.batch.support.RequestParametersValidator;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.tax.orchestration.Form16Service;
import in.gov.fci.payroll.tax.orchestration.ReturnService;
import java.util.List;
import java.util.function.ToLongFunction;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Open and close a Module 08 background run around its partitioned step. Before the job, once (a
 * restart finds the keys in the job's context and keeps them): the paying unit, the requester (who
 * the audit and X-05 name), and the checks the screen makes first. After a completed job, what the
 * screen does last. A run that fails or stops is closed only when it completes after a restart.
 */
final class TaxRunListeners {

    private TaxRunListeners() {}

    /** TAX_FORM16_PART_B: a locked year and a governing version; the audit record at the end. */
    static JobExecutionListener partB(OrgDirectory org, JdbcTemplate jdbc, Form16Service form16) {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution execution) {
                ExecutionContext ctx = execution.getExecutionContext();
                if (opened(execution)) {
                    return;
                }
                Opening at = open(execution, org, jdbc);
                Form16Service.Prepared p =
                        form16.preparePartB(at.unit().getId(), param(execution, "finYear"));
                at.remember(ctx);
                ctx.putString(TaxJobKeys.YEAR_END, p.yearEnd().toString());
                ctx.putLong(TaxJobKeys.VERSION_ID, p.versionId());
                ctx.putInt(TaxJobKeys.VERSION_NO, p.versionNo());
            }

            @Override
            public void afterJob(JobExecution execution) {
                if (execution.getStatus() != BatchStatus.COMPLETED
                        || !execution.getExecutionContext().containsKey(TaxJobKeys.VERSION_ID)) {
                    return;
                }
                ExecutionContext ctx = execution.getExecutionContext();
                form16.recordPartBRun(
                        ctx.getString(TaxJobKeys.ACTOR),
                        ctx.getString(TaxJobKeys.UNIT_CODE),
                        param(execution, "finYear"),
                        workers(execution, StepExecution::getWriteCount),
                        workers(execution, StepExecution::getFilterCount),
                        ctx.getInt(TaxJobKeys.VERSION_NO));
            }
        };
    }

    /**
     * TAX_24Q_RETURN: the original return of the quarter opened (or reopened) as DRAFT; its totals
     * and GENERATED at the end.
     */
    static JobExecutionListener quarterReturn(
            OrgDirectory org, JdbcTemplate jdbc, ReturnService returns) {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution execution) {
                ExecutionContext ctx = execution.getExecutionContext();
                if (opened(execution)) {
                    return;
                }
                Opening at = open(execution, org, jdbc);
                ReturnService.Opened o =
                        returns.openReturn(
                                at.actor(),
                                at.unit().getId(),
                                param(execution, "finYear"),
                                param(execution, "quarter"),
                                null,
                                true);
                at.remember(ctx);
                ctx.putLong(TaxJobKeys.RETURN_ID, o.returnId());
                ctx.putString(TaxJobKeys.MONTHS, String.join(",", o.months()));
                ctx.putString(TaxJobKeys.QUARTER_END, o.quarterEnd().toString());
                if (o.challanId() != null) {
                    ctx.putLong(TaxJobKeys.CHALLAN_ID, o.challanId());
                }
            }

            @Override
            public void afterJob(JobExecution execution) {
                ExecutionContext ctx = execution.getExecutionContext();
                if (execution.getStatus() == BatchStatus.COMPLETED
                        && ctx.containsKey(TaxJobKeys.RETURN_ID)) {
                    returns.finishReturn(
                            ctx.getString(TaxJobKeys.ACTOR), ctx.getLong(TaxJobKeys.RETURN_ID));
                }
            }
        };
    }

    /** The run was opened by an earlier execution (a restart), or is being stopped. */
    private static boolean opened(JobExecution execution) {
        return execution.getStatus() == BatchStatus.STOPPING
                || execution.getExecutionContext().containsKey(TaxJobKeys.UNIT_ID);
    }

    /**
     * The unit and the requester of a run. Remembered only after the opening checks passed: a
     * restart after a refused opening checks again.
     */
    private record Opening(OrgLocation unit, String actor) {

        void remember(ExecutionContext ctx) {
            ctx.putString(TaxJobKeys.ACTOR, actor);
            ctx.putLong(TaxJobKeys.UNIT_ID, unit.getId());
            ctx.putString(TaxJobKeys.UNIT_CODE, unit.getCode());
        }
    }

    private static Opening open(JobExecution execution, OrgDirectory org, JdbcTemplate jdbc) {
        OrgLocation unit =
                TaxMemberReader.unitOf(org, Long.parseLong(param(execution, "locationId")));
        long requestId = RequestParametersValidator.requestId(execution.getJobParameters());
        List<String> by =
                jdbc.queryForList(
                        "SELECT requested_by_login FROM batch_job_request WHERE job_request_id = ?",
                        String.class,
                        requestId);
        return new Opening(unit, by.isEmpty() || by.get(0) == null ? "system" : by.get(0));
    }

    private static String param(JobExecution execution, String key) {
        return RequestParametersValidator.required(execution.getJobParameters(), key);
    }

    /** A count over the partition workers (the manager's own counts add them up again). */
    private static long workers(JobExecution execution, ToLongFunction<StepExecution> count) {
        return execution.getStepExecutions().stream()
                .filter(s -> PartitionedSteps.isWorker(s.getStepName()))
                .mapToLong(count)
                .sum();
    }
}
