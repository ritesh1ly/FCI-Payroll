package in.gov.fci.payroll.payroll.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.orchestration.CycleLeases;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * BR-CYC-001 (a run belongs to the payroll cycle unit of the requested location): the unit itself
 * or its nearest cycle-unit ancestor is used; a location with no cycle unit above it is refused,
 * naming the location, and no run is opened.
 */
class CycleRunsCycleUnitTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final CycleRuns runs =
            new CycleRuns(
                    db.jdbc,
                    org,
                    Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC),
                    mock(NetPayFloors.class),
                    mock(CycleLeases.class),
                    mock(ApplicationEventPublisher.class),
                    mock(in.gov.fci.payroll.payroll.orchestration.CycleRegisters.class));

    @Test
    void BR_CYC_001_aLocationWithoutACycleUnitAboveItIsRefusedNamingTheLocation() {
        when(org.cycleUnitOf(731L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runs.cycleUnit(731L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No payroll cycle unit above location 731");
    }

    @Test
    void BR_CYC_001_theCycleUnitOfALocationIsTheOneTheDirectoryResolves() {
        OrgLocation unit = mock(OrgLocation.class);
        when(org.cycleUnitOf(731L)).thenReturn(Optional.of(unit));

        assertThat(runs.cycleUnit(731L)).isSameAs(unit);
    }
}
