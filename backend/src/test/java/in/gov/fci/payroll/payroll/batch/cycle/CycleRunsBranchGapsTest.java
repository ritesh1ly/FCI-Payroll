package in.gov.fci.payroll.payroll.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.orchestration.CycleLeases;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * BR-CYC-011 (SRS l.1493): a work unit is committed or failed as a whole; the failure of a failed
 * unit is kept to the 2000 characters of {@code computation_work_unit.failure_detail} (a longer one
 * cut, one of exactly 2000 kept whole), a committed unit records none.
 */
class CycleRunsBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final CycleRuns runs =
            new CycleRuns(
                    db.jdbc,
                    mock(OrgDirectory.class),
                    Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC),
                    mock(NetPayFloors.class),
                    mock(CycleLeases.class),
                    mock(ApplicationEventPublisher.class),
                    mock(in.gov.fci.payroll.payroll.orchestration.CycleRegisters.class));

    private Object failureWritten() {
        return db.updates("UPDATE computation_work_unit SET unit_status").getLast().args().get(4);
    }

    @Test
    void BR_CYC_011_theFailureOfAWorkUnitIsKeptToItsColumn() {
        runs.finishUnit(70L, false, 0, "x".repeat(2001));
        assertThat((String) failureWritten()).hasSize(2000);
        runs.finishUnit(71L, false, 0, "y".repeat(2000));
        assertThat((String) failureWritten()).isEqualTo("y".repeat(2000));
        runs.finishUnit(72L, true, 250, null);
        assertThat(failureWritten()).isNull();
        assertThat(db.updates("UPDATE computation_work_unit SET unit_status").getLast().args())
                .startsWith("COMMITTED");
    }
}
