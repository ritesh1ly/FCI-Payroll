package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SRS-15-30 and Oracle review F2: a return can be withdrawn only while no ACTUAL run of the cycle
 * was started after the returned one - after by run number, never by run identity (NOORDER, cached
 * identities on Oracle RAC / the Autonomous Database give a later run a lower id). On an in-memory
 * copy of the {@code computation_run} columns used.
 */
class CycleCorrectionsRunOrderTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void schema() {
        org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:cyclecorrections_order;MODE=Oracle;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(h2);
        jdbc.execute("DROP TABLE IF EXISTS computation_run");
        jdbc.execute(
                "CREATE TABLE computation_run (run_id BIGINT PRIMARY KEY, cycle_instance_id BIGINT"
                        + " NOT NULL, run_type VARCHAR(12) NOT NULL, run_no INT NOT NULL,"
                        + " CONSTRAINT uk_computation_run UNIQUE (cycle_instance_id, run_type,"
                        + " run_no))");
        // Cycle 41: ACTUAL run 1 got id 9, run 2 got id 7 (a later run, a lower id).
        jdbc.update("INSERT INTO computation_run VALUES (9, 41, 'ACTUAL', 1)");
        jdbc.update("INSERT INTO computation_run VALUES (7, 41, 'ACTUAL', 2)");
        // A later simulation and another cycle's later run do not count.
        jdbc.update("INSERT INTO computation_run VALUES (12, 41, 'SIMULATION', 5)");
        jdbc.update("INSERT INTO computation_run VALUES (13, 42, 'ACTUAL', 5)");
    }

    private long since(long cycle, long returnedRun) {
        return jdbc.queryForObject(CycleCorrections.RUNS_SINCE, Long.class, cycle, returnedRun);
    }

    @Test
    void SRS_15_30_aRunNumberedAfterTheReturnedOneCountsWhateverItsId() {
        assertThat(since(41L, 9L)).as("run 2 (id 7) follows run 1 (id 9)").isEqualTo(1L);
        assertThat(since(41L, 7L)).as("nothing follows run 2").isZero();
        assertThat(since(41L, 99L)).as("an unknown run").isZero();
    }
}
