package in.gov.fci.payroll.payroll.orchestration;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SRS-15-27 / BR-CYC-014 (plan 05j): an abandoned computation job's run is ABANDONED; a dry run's
 * transient result rows are discarded, an Actual run's stay QUARANTINED (R-DEL); other modules'
 * jobs are not this module's business.
 */
class ComputationAbandonmentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ComputationAbandonment abandonment = new ComputationAbandonment(jdbc);

    @Test
    void BR_CYC_014_theRunOfAnAbandonedComputationJobIsAbandonedAndItsSimulationRowsDiscarded() {
        abandonment.abandoned(7, "PAYROLL_COMPUTATION");
        verify(jdbc).update(contains("SET run_status = 'ABANDONED'"), eq("{\"jobRequestId\":7,%"));
        abandonment.abandoned(8, "PAYROLL_DRY_RUN");
        verify(jdbc).update(contains("SET run_status = 'ABANDONED'"), eq("{\"jobRequestId\":8,%"));
        // SRS-15-27 / SRS-15-7 (BMAD TR-064): only SIMULATION rows of the abandoned run are
        // removed;
        // an Actual run's results are never deleted (R-DEL), they stay QUARANTINED.
        verify(jdbc)
                .update(
                        argThat(
                                (String sql) ->
                                        sql.startsWith("DELETE FROM payroll_result WHERE")
                                                && sql.contains("run_type = 'SIMULATION'")
                                                && sql.contains("run_status = 'ABANDONED'")),
                        eq("{\"jobRequestId\":8,%"));
        verify(jdbc)
                .update(
                        argThat(
                                (String sql) ->
                                        sql.startsWith("DELETE FROM payroll_result_component")
                                                && sql.contains("run_type = 'SIMULATION'")),
                        eq("{\"jobRequestId\":8,%"));
        verify(jdbc, never())
                .update(
                        argThat(
                                (String sql) ->
                                        sql.startsWith("DELETE") && sql.contains("'ACTUAL'")),
                        org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    void BR_CYC_014_anotherModulesJobIsNotTouched() {
        abandonment.abandoned(7, "DA_ARREAR");
        abandonment.abandoned(7, "INTERFACE_PULL");
        verifyNoInteractions(jdbc);
    }
}
