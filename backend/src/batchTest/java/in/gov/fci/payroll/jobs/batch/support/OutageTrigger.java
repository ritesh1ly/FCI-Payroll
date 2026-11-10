package in.gov.fci.payroll.jobs.batch.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.tools.TriggerAdapter;

/**
 * Test-only H2 trigger: simulates a database outage (neither retryable nor skippable) on the {@link
 * #failOn} insert, counted across the whole test. {@code failOn} below 1 disarms it.
 */
public class OutageTrigger extends TriggerAdapter {

    public static final AtomicInteger INSERTS = new AtomicInteger();
    public static final AtomicInteger FAIL_ON = new AtomicInteger(-1);

    public static void arm(int failOn) {
        INSERTS.set(0);
        FAIL_ON.set(failOn);
    }

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (INSERTS.incrementAndGet() == FAIL_ON.get()) {
            throw new SQLException("simulated outage", "45000");
        }
    }
}
