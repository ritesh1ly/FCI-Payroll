package in.gov.fci.payroll.payroll.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.orchestration.CycleLeases;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * BR-CYC-012 / SRS-15-15 (G0, SRS l.1519; BMAD TR-070): the eligible count a run is closed with. A
 * single execution keeps its own committed count; a failed resumed execution adds it to the count
 * recorded before; a completed resumed run counts every member computed plus every member still
 * open as COMPUTATION_BLOCKED, since its executions' counts overlap. A simulation stops after the
 * run's own row (no cycle status, no result status). Counts are fixtures.
 */
class CycleRunsResumeCountTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final CycleLeases leases = mock(CycleLeases.class);
    private final CycleRuns runs =
            new CycleRuns(
                    db.jdbc,
                    mock(OrgDirectory.class),
                    Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC),
                    mock(NetPayFloors.class),
                    leases,
                    mock(ApplicationEventPublisher.class),
                    mock(in.gov.fci.payroll.payroll.orchestration.CycleRegisters.class));

    private static final CycleRuns.OpenRun RUN =
            new CycleRuns.OpenRun(
                    31L,
                    9L,
                    40L,
                    "202610",
                    LocalDate.of(2026, 10, 1),
                    CycleRuns.RunType.SIMULATION);

    CycleRunsResumeCountTest() {
        db.value("SELECT COUNT(*) FROM payroll_result WHERE run_id", 7L)
                .value("COUNT(DISTINCT employee_id) FROM payroll_exception", 2L)
                .value("SELECT eligible_count FROM computation_run", 10L);
    }

    /** The eligible count written on the run's row (the fourth bound value). */
    private Object eligibleWritten() {
        ScriptedJdbc.Update u = db.updates("UPDATE computation_run SET run_status = ?").getLast();
        return u.args().get(3);
    }

    @Test
    void BR_CYC_012_aSingleExecutionKeepsItsOwnCommittedCount() {
        runs.close(RUN, true, 5);
        assertThat(eligibleWritten()).isEqualTo(5L);
        assertThat(db.updates("UPDATE computation_run SET run_status = ?").getLast().args())
                .startsWith("COMPLETE");
        assertThat(db.queries("eligible_count FROM computation_run")).isEmpty();
        assertThat(db.queries("payroll_exception")).isEmpty();
        verify(leases).releaseRun(9L, 31L);
        // A simulation's run row only: no result or cycle status is moved.
        assertThat(db.updates("payroll_result")).isEmpty();
        assertThat(db.updates("payroll_cycle_instance")).isEmpty();
    }

    @Test
    void BR_CYC_012_aFailedResumedExecutionAddsItsCountToTheOneRecordedBefore() {
        runs.close(RUN, false, 4, true);
        assertThat(eligibleWritten()).isEqualTo(14L);
        assertThat(db.updates("UPDATE computation_run SET run_status = ?").getLast().args())
                .startsWith("FAILED");
        db.value("SELECT eligible_count FROM computation_run", ScriptedJdbc.NULL);
        runs.close(RUN, false, 4, true);
        assertThat(eligibleWritten()).as("nothing recorded before").isEqualTo(4L);
    }

    @Test
    void BR_CYC_012_aCompletedResumedRunCountsTheComputedAndTheBlockedMembers() {
        runs.close(RUN, true, 3, true);
        assertThat(eligibleWritten()).isEqualTo(9L);
        assertThat(db.queries("eligible_count FROM computation_run")).isEmpty();
        db.value("SELECT COUNT(*) FROM payroll_result WHERE run_id", ScriptedJdbc.NULL)
                .value("COUNT(DISTINCT employee_id) FROM payroll_exception", ScriptedJdbc.NULL);
        runs.close(RUN, true, 3, true);
        assertThat(eligibleWritten()).as("no result and no open block").isEqualTo(0L);
    }
}
