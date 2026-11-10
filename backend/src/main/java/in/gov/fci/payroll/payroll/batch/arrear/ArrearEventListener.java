package in.gov.fci.payroll.payroll.batch.arrear;

import in.gov.fci.payroll.jobs.batch.support.RequestParametersValidator;
import java.time.YearMonth;
import java.util.function.Supplier;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/**
 * Raises the arrear event before a rate arrear job (DA_ARREAR, HRA_ARREAR), marks it COMPUTED after
 * a completed one.
 */
public class ArrearEventListener implements JobExecutionListener {

    private final ArrearEvents events;

    /** The period the arrears are paid in: the calendar's current period when the job starts. */
    private final Supplier<YearMonth> nextOpen;

    private final String eventCode;
    private final String label;

    public ArrearEventListener(ArrearEvents events, Supplier<YearMonth> nextOpen) {
        this(events, nextOpen, "DA_REVISION", "DA");
    }

    public ArrearEventListener(
            ArrearEvents events, Supplier<YearMonth> nextOpen, String eventCode, String label) {
        this.events = events;
        this.nextOpen = nextOpen;
        this.eventCode = eventCode;
        this.label = label;
    }

    @Override
    public void beforeJob(JobExecution execution) {
        if (execution.getStatus() == BatchStatus.STOPPING
                || execution.getExecutionContext().containsKey(DaArrearJobConfig.EVENT_ID)) {
            return;
        }
        long id =
                events.raise(
                        RequestParametersValidator.requestId(execution.getJobParameters()),
                        Long.parseLong(
                                RequestParametersValidator.required(
                                        execution.getJobParameters(), "locationId")),
                        YearMonth.parse(
                                RequestParametersValidator.required(
                                        execution.getJobParameters(), "fromPeriod")),
                        YearMonth.parse(
                                RequestParametersValidator.required(
                                        execution.getJobParameters(), "toPeriod")),
                        nextOpen.get(),
                        eventCode,
                        label);
        execution.getExecutionContext().putLong(DaArrearJobConfig.EVENT_ID, id);
    }

    @Override
    public void afterJob(JobExecution execution) {
        if (execution.getStatus() == BatchStatus.COMPLETED
                && execution.getExecutionContext().containsKey(DaArrearJobConfig.EVENT_ID)) {
            events.computed(execution.getExecutionContext().getLong(DaArrearJobConfig.EVENT_ID));
        }
    }
}
