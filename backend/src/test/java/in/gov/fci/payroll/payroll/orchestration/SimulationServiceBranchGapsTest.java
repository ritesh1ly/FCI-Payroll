package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.jobs.api.BackgroundJobs;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.business.SimulationRules;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * C-09 Simulation Run (SRS-15-7): an interactive run of an office with no eligible member comes
 * back at once with its summary and no member rows, and reads no employee names.
 */
class SimulationServiceBranchGapsTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final PayrollPeriods periods = mock(PayrollPeriods.class);
    private final SimulationEngine engine = mock(SimulationEngine.class);
    private final SimulationRecords records = mock(SimulationRecords.class);
    private final SimulationService service =
            new SimulationService(
                    named,
                    org,
                    mock(Authorization.class),
                    parameters,
                    periods,
                    mock(PayConfiguration.class),
                    engine,
                    records,
                    mock(BackgroundJobs.class),
                    mock(AuditService.class));

    @Test
    void SRS_15_7_anInteractiveRunOfAnOfficeWithoutMembersReturnsNoMemberRows() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(40L);
        when(unit.getCode()).thenReturn("DO-DEL");
        when(org.cycleUnitOf(40L)).thenReturn(Optional.of(unit));
        when(parameters.configured(SystemParameters.Code.SIMULATION_MAX_SELECTION))
                .thenReturn(OptionalLong.of(1000));
        when(parameters.configured(SystemParameters.Code.SIMULATION_INTERACTIVE_LIMIT))
                .thenReturn(OptionalLong.of(200));
        when(periods.current()).thenReturn(YearMonth.of(2026, 10));
        when(engine.locations(40L)).thenReturn(List.of(40L));
        when(engine.count(any())).thenReturn(0L);
        SimulationEngine.Side empty =
                new SimulationEngine.Side(
                        0,
                        0,
                        Map.of(),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        Map.of());
        when(engine.run(any(), eq(true), any()))
                .thenReturn(new SimulationEngine.Outcome(0, empty, null, "in", "ver", List.of()));
        AtomicReference<SimulationRecords.Doc> stored = new AtomicReference<>();
        when(records.insert(anyString(), eq("202610"), any(), eq(0L), eq("in")))
                .thenAnswer(
                        call -> {
                            stored.set(call.getArgument(2));
                            return 9L;
                        });
        when(records.find(9L))
                .thenAnswer(
                        call ->
                                Optional.of(
                                        new SimulationRecords.Row(
                                                9L,
                                                "maker@x",
                                                Instant.parse("2026-10-28T06:00:00Z"),
                                                "C09;202610;DO-DEL;OFFICE;0;AS_IS",
                                                0,
                                                "in",
                                                stored.get())));
        AccessContext maker =
                new AccessContext(
                        2L,
                        "maker@x",
                        "Maker",
                        "s",
                        "P",
                        40L,
                        null,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(40L));
        SimulationService.Detail detail =
                service.run(
                        maker,
                        new SimulationService.Input(
                                SimulationRules.Population.OFFICE,
                                null,
                                SimulationRules.WhatIf.AS_IS,
                                null,
                                null));
        assertThat(detail.members()).isEmpty();
        assertThat(detail.current()).isTrue();
        assertThat(detail.run().status()).isEqualTo("COMPLETE");
        verify(named, never())
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        verify(records, never()).attachJob(anyLong(), any(), anyLong());
    }
}
