package in.gov.fci.payroll.jobs.batch;

import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Time-triggered jobs of the batch instance. They do not run work themselves: they queue a request
 * (source SCHEDULE), and the poller runs it like any UI request, so every run is visible, auditable
 * and controllable on C-13. Cron in the business zone (IST), values in application-batch.yml.
 */
@Component
@Profile("batch")
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final JobRequestService jobs;
    private final PayrollPeriods periods;

    public ScheduledJobs(JobRequestService jobs, PayrollPeriods periods) {
        this.jobs = jobs;
        this.periods = periods;
    }

    @Scheduled(
            cron = "${fci.batch.schedules.interface-pull:0 30 1 * * *}",
            zone = "${fci.api.business-zone:Asia/Kolkata}")
    public void interfacePull() {
        enqueue(JobType.INTERFACE_PULL);
    }

    @Scheduled(
            cron = "${fci.batch.schedules.month-end:0 0 20 L * *}",
            zone = "${fci.api.business-zone:Asia/Kolkata}")
    public void monthEnd() {
        enqueue(JobType.MONTH_END_CLOSE);
    }

    private void enqueue(JobType type) {
        // The calendar's current period (M-10), not a value fixed at deployment.
        String period = periods.current().toString();
        boolean queued = jobs.enqueueScheduled(type, Map.of("payPeriod", period));
        log.info("event=JOB_SCHEDULED type={} period={} queued={}", type, period, queued);
    }
}
