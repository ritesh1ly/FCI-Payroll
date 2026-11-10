package in.gov.fci.payroll.payroll.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SRS-15-30 / plan 05 D-9 and Oracle review F2: a completed ACTUAL run answers the OPEN returns
 * that name an EARLIER run of the cycle, earlier by run number. Identities are NOORDER and cached
 * per instance on Oracle RAC / the Autonomous Database, so a later run can carry a lower run_id;
 * here run 2 got id 5 and run 3 got id 4 (on an in-memory copy of the DDL columns used).
 */
class CycleRunsRunOrderTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void schema() {
        org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:cycleruns_order;MODE=Oracle;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(h2);
        jdbc.execute("DROP TABLE IF EXISTS cycle_return");
        jdbc.execute("DROP TABLE IF EXISTS computation_run");
        jdbc.execute(
                "CREATE TABLE computation_run (run_id BIGINT PRIMARY KEY, cycle_instance_id BIGINT"
                        + " NOT NULL, run_type VARCHAR(12) NOT NULL, run_no INT NOT NULL,"
                        + " CONSTRAINT uk_computation_run UNIQUE (cycle_instance_id, run_type,"
                        + " run_no))");
        jdbc.execute(
                "CREATE TABLE cycle_return (return_id BIGINT PRIMARY KEY, cycle_instance_id BIGINT"
                        + " NOT NULL, run_id BIGINT NOT NULL, return_status VARCHAR(12) NOT NULL,"
                        + " recompute_run_id BIGINT)");
        // Cycle 41: ACTUAL runs 1, 2, 3 with ids 3, 5, 4 (id order is not run order).
        jdbc.update("INSERT INTO computation_run VALUES (3, 41, 'ACTUAL', 1)");
        jdbc.update("INSERT INTO computation_run VALUES (5, 41, 'ACTUAL', 2)");
        jdbc.update("INSERT INTO computation_run VALUES (4, 41, 'ACTUAL', 3)");
        // A simulation of the cycle and another cycle's run, numbered alike: never compared.
        jdbc.update("INSERT INTO computation_run VALUES (2, 41, 'SIMULATION', 1)");
        jdbc.update("INSERT INTO computation_run VALUES (1, 42, 'ACTUAL', 1)");
    }

    private String status(long returnId) {
        return jdbc.queryForObject(
                "SELECT return_status FROM cycle_return WHERE return_id = ?",
                String.class,
                returnId);
    }

    @Test
    void SRS_15_30_aRunCompletingAnswersTheReturnsOfEarlierRunsByRunNumberNotById() {
        // The Checker returned run 2 (id 5); run 3 (id 4, a LOWER id) is the fresh computation.
        jdbc.update("INSERT INTO cycle_return VALUES (10, 41, 5, 'OPEN', NULL)");
        int answered = jdbc.update(CycleRuns.RECOMPUTED_RETURNS, 4L, 41L, 4L);
        assertThat(answered).isEqualTo(1);
        assertThat(status(10)).isEqualTo("RECOMPUTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT recompute_run_id FROM cycle_return WHERE return_id = 10",
                                Long.class))
                .isEqualTo(4L);
    }

    @Test
    void SRS_15_30_aReturnOfTheCompletingRunOrOfALaterOneStaysOpen() {
        // Run 2 (id 5) completing does not answer a return of run 3 (id 4, a lower id), nor one of
        // itself, nor another cycle's.
        jdbc.update("INSERT INTO cycle_return VALUES (11, 41, 4, 'OPEN', NULL)");
        jdbc.update("INSERT INTO cycle_return VALUES (12, 41, 5, 'OPEN', NULL)");
        jdbc.update("INSERT INTO cycle_return VALUES (13, 42, 1, 'OPEN', NULL)");
        jdbc.update("INSERT INTO cycle_return VALUES (14, 41, 3, 'WITHDRAWN', NULL)");
        assertThat(jdbc.update(CycleRuns.RECOMPUTED_RETURNS, 5L, 41L, 5L)).isZero();
        assertThat(List.of(status(11), status(12), status(13), status(14)))
                .containsExactly("OPEN", "OPEN", "OPEN", "WITHDRAWN");
        // Run 2 (id 5) does answer a return of run 1 (id 3).
        jdbc.update("INSERT INTO cycle_return VALUES (15, 41, 3, 'OPEN', NULL)");
        assertThat(jdbc.update(CycleRuns.RECOMPUTED_RETURNS, 5L, 41L, 5L)).isEqualTo(1);
        assertThat(status(15)).isEqualTo("RECOMPUTED");
    }
}
