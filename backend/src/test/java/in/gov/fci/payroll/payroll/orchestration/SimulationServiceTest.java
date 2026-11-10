package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.jobs.api.BackgroundJobs;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * C-09 (plan 05 phase 05i; SRS-15-7) and D-01 (SRS-15-14): the guards a request meets before any
 * read, and the helpers of the run record; the database paths are in SimulationIT and
 * CycleStatusIT.
 */
class SimulationServiceTest {

    private final OrgDirectory org = mock(OrgDirectory.class);

    private SimulationService simulations() {
        return new SimulationService(
                mock(NamedParameterJdbcTemplate.class),
                org,
                mock(Authorization.class),
                mock(SystemParameters.class),
                mock(PayrollPeriods.class),
                mock(PayConfiguration.class),
                mock(SimulationEngine.class),
                mock(SimulationRecords.class),
                mock(BackgroundJobs.class),
                mock(AuditService.class));
    }

    private CycleStatusService status() {
        return new CycleStatusService(
                mock(NamedParameterJdbcTemplate.class),
                org,
                mock(Authorization.class),
                mock(PayrollPeriods.class));
    }

    private static AccessContext at(Long locationId) {
        return new AccessContext(
                2L,
                "maker@x",
                "Maker",
                "s",
                "P",
                locationId,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                locationId == null ? Set.of() : Set.of(locationId));
    }

    @Test
    void SRS_15_7_aRequestWithoutAnOfficeOrOutsideAnyCycleUnitIsRefused() {
        assertThatThrownBy(() -> simulations().overview(at(null)))
                .isInstanceOf(FciException.class)
                .hasMessage("COM-403-DENIED");
        when(org.cycleUnitOf(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> simulations().overview(at(9L)))
                .isInstanceOf(FciException.class)
                .hasMessage("CYC-422-UNIT");
    }

    @Test
    void SRS_15_14_theHomeNeedsAnOfficeThatExistsAndIsPaidInACycleUnit() {
        assertThatThrownBy(() -> status().status(at(null)))
                .isInstanceOf(FciException.class)
                .hasMessage("COM-403-DENIED");
        when(org.location(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> status().status(at(9L)))
                .isInstanceOf(FciException.class)
                .hasMessage("COM-404-NOT-FOUND");
    }

    @Test
    void SRS_15_7_aMemberNameAndTheRequesterAreRecordedAsTheColumnsTakeThem() {
        assertThat(SimulationService.name("Asha", "Rao")).isEqualTo("Asha Rao");
        assertThat(SimulationService.name("Asha", null)).isEqualTo("Asha");
        assertThat(SimulationService.name("Asha", " ")).isEqualTo("Asha");
        assertThat(SimulationRecords.truncate("a".repeat(50))).hasSize(50);
        assertThat(SimulationRecords.truncate("a".repeat(70))).hasSize(50);
        assertThat(SimulationRecords.truncate("short")).isEqualTo("short");
    }
}
