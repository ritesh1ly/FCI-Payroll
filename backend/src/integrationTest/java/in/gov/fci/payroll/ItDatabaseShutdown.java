package in.gov.fci.payroll;

import javax.sql.DataSource;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops an integration test's in-memory H2 database when its Spring context closes. The test URLs
 * keep {@code DB_CLOSE_DELAY=-1}, so the database survives the moments during start-up when no
 * connection is open; without this listener it then outlives its context and every closed context
 * keeps a migrated, seeded database in the heap of the fork until the JVM exits. With the context
 * cache capped at four (build-logic), that is what ran the single CI fork out of memory on
 * 2026-10-25. A test that closes its context explicitly gets the same clean-up.
 */
@Component
@Profile("it")
class ItDatabaseShutdown implements ApplicationListener<ContextClosedEvent> {

    private final DataSource dataSource;

    ItDatabaseShutdown(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        try {
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        } catch (RuntimeException ignored) {
            // Already closed, or not H2: nothing to free.
        }
    }
}
