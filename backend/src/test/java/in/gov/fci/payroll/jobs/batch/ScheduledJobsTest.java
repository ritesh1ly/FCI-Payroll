package in.gov.fci.payroll.jobs.batch;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The time-triggered jobs queue a request for the payroll calendar's current period (SRS-NF-2:
 * every run visible and controllable on C-13), never one fixed at deployment.
 */
class ScheduledJobsTest {

    private final JobRequestService jobs = mock(JobRequestService.class);
    private final PayrollPeriods periods = mock(PayrollPeriods.class);
    private final ScheduledJobs scheduled = new ScheduledJobs(jobs, periods);

    @Test
    void SRS_NF_2_theNightlyPullAndTheMonthEndCheckAreQueuedForTheCurrentPeriod() {
        when(periods.current()).thenReturn(YearMonth.of(2026, 10));
        scheduled.interfacePull();
        verify(jobs).enqueueScheduled(JobType.INTERFACE_PULL, Map.of("payPeriod", "2026-10"));

        // The calendar moved on: the next run takes the new period.
        when(periods.current()).thenReturn(YearMonth.of(2026, 11));
        scheduled.monthEnd();
        verify(jobs).enqueueScheduled(JobType.MONTH_END_CLOSE, Map.of("payPeriod", "2026-11"));
    }
}
