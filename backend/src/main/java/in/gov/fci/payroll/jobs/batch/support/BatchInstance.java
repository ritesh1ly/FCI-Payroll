package in.gov.fci.payroll.jobs.batch.support;

import java.lang.management.ManagementFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The identity of this batch node ({@code pid@host}), written as claimed_by / held_by. */
@Component
@Profile("batch")
public class BatchInstance {

    private final String id = ManagementFactory.getRuntimeMXBean().getName();

    public String id() {
        return id;
    }
}
