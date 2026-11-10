package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.jobs.api.AbandonListener;
import in.gov.fci.payroll.jobs.api.BackgroundJobs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SRS-15-27 (plan 05j, 05i): an abandoned SIMULATION_RUN request leaves its C-09 record FAILED
 * ("ABANDONED"), never QUEUED or RUNNING for ever. Nothing is deleted.
 */
@Component
public class SimulationAbandonment implements AbandonListener {

    private static final Logger log = LoggerFactory.getLogger(SimulationAbandonment.class);

    private final SimulationRecords records;

    public SimulationAbandonment(SimulationRecords records) {
        this.records = records;
    }

    @Override
    public void abandoned(long jobRequestId, String jobName) {
        if (!BackgroundJobs.Kind.SIMULATION_RUN.name().equals(jobName)) {
            return;
        }
        int closed = records.abandoned(jobRequestId);
        log.info("event=SIMULATION_ABANDONED jobRequestId={} records={}", jobRequestId, closed);
    }
}
