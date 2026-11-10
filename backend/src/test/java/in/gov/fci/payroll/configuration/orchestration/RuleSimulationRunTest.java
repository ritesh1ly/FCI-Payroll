package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleSet;
import in.gov.fci.payroll.configuration.api.SimulationPopulation;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.orchestration.RuleCatalogueService.Pending;
import in.gov.fci.payroll.configuration.orchestration.RuleCatalogueService.VersionProposal;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.persistence.GeneratedKeysStub;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-08 impact simulation run (plan 06 phase 06e, D-12; SRS-19-4): each group of the sample is
 * either covered by the rule or not applicable, a value not configured is named as such, the run
 * without a population source is empty, and a kept run says whether its inputs changed since.
 */
@SuppressWarnings("unchecked")
class RuleSimulationRunTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);

    private final RuleCatalogueService catalogue = mock(RuleCatalogueService.class);
    private final RuleEngine engine = mock(RuleEngine.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final ObjectProvider<SimulationPopulation> populations = mock(ObjectProvider.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccessContext context = mock(AccessContext.class);
    private final RuleSet inForce = mock(RuleSet.class);
    private final SimulationPopulation source = mock(SimulationPopulation.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final RuleSimulationService service =
            new RuleSimulationService(
                    catalogue,
                    engine,
                    settings,
                    populations,
                    org,
                    policy,
                    mock(AuditService.class),
                    jdbc,
                    json,
                    Clock.systemUTC(),
                    mock(ObjectProvider.class));

    @BeforeEach
    void wire() {
        GeneratedKeysStub.answer(jdbc, "INSERT INTO rule_simulation", 1L);
        when(policy.can(context, "M-08", "CREATE")).thenReturn(true);
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        when(context.loginId()).thenReturn("rule.admin@fci-demo.test");
        RuleDefinition rule = mock(RuleDefinition.class);
        when(rule.getKey()).thenReturn("K");
        when(rule.getPayrollTypeScope()).thenReturn("STAFF");
        when(rule.getOrgScopeLocationId()).thenReturn(null);
        when(catalogue.listedDefinition("K")).thenReturn(rule);
        when(catalogue.pendingProposal("K"))
                .thenReturn(
                        Optional.of(
                                new Pending<>(
                                        40L,
                                        new VersionProposal(
                                                "K",
                                                "RATE",
                                                DAY,
                                                null,
                                                new BigDecimal("12"),
                                                null,
                                                null,
                                                null,
                                                1L,
                                                "rule.admin",
                                                null))));
        when(settings.configured(SystemParameters.Code.SIMULATION_MAX_SELECTION))
                .thenReturn(OptionalLong.of(100));
        when(engine.snapshot(DAY)).thenReturn(inForce);
        when(populations.getIfAvailable()).thenReturn(source);
        when(source.sample(eq(DAY), any(), any(), anyInt()))
                .thenReturn(
                        new SimulationPopulation.Sample(
                                5,
                                List.of(
                                        new SimulationPopulation.Member(1, "STAFF", 3),
                                        new SimulationPopulation.Member(2, "STAFF", 3),
                                        new SimulationPopulation.Member(3, "DPS", 4))));
        when(org.subtree(anyLong())).thenReturn(Set.of());
        OrgLocation delhi = mock(OrgLocation.class);
        when(delhi.getName()).thenReturn("Delhi");
        when(org.location(3L)).thenReturn(Optional.of(delhi));
        when(org.location(4L)).thenReturn(Optional.empty());
    }

    private RuleEngine.Resolved resolved(BigDecimal value) {
        return new RuleEngine.Resolved(
                "K", "RATE", 7L, 1, DAY, null, value, List.of(), null, null, false);
    }

    @Test
    void SRS_19_4_aCoveredGroupChangesAndAnotherPayrollTypeIsNotApplicable() {
        when(inForce.resolve(eq("K"), any())).thenReturn(Optional.of(resolved(BigDecimal.TEN)));
        GeneratedKeysStub.answer(jdbc, "INSERT INTO rule_simulation", 21L);
        RuleSimulationService.Simulation run = service.simulate(context, "K", null);
        assertThat(run.id()).as("the key of its own insert (Oracle review F2)").isEqualTo(21L);
        assertThat(run.population()).isEqualTo(5);
        assertThat(run.impact().sampled()).isEqualTo(3);
        assertThat(run.impact().inScope()).isEqualTo(2);
        assertThat(run.impact().changed()).isEqualTo(3);
        assertThat(run.impact().groups())
                .extracting(
                        RuleSimulationService.Group::payrollType,
                        RuleSimulationService.Group::officeName,
                        RuleSimulationService.Group::proposed)
                .containsExactly(
                        tuple("STAFF", "Delhi", "12"), tuple("DPS", null, "NOT_APPLICABLE"));
    }

    @Test
    void SRS_19_4_aValueNotConfiguredIsNamedAndAnyOtherFailureIsRaised() {
        when(inForce.resolve(eq("K"), any()))
                .thenThrow(new FciException(ApiErrorCode.RULE_NOT_CONFIGURED));
        GeneratedKeysStub.answer(jdbc, "INSERT INTO rule_simulation", 9L);
        when(org.location(3L)).thenReturn(Optional.of(mock(OrgLocation.class)));
        RuleSimulationService.Simulation run =
                service.simulate(context, "K", new RuleSimulationService.Request("STAFF", 3L));
        assertThat(run.id()).isEqualTo(9L);
        assertThat(run.impact().groups())
                .extracting(RuleSimulationService.Group::current)
                .containsOnly("NOT_CONFIGURED");
        when(inForce.resolve(eq("K"), any()))
                .thenThrow(new FciException(CommonErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.simulate(context, "K", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    @Test
    void SRS_19_4_withoutAPopulationSourceOrItsLimitTheRunIsEmptyOrRefused() {
        when(populations.getIfAvailable()).thenReturn(null);
        RuleSimulationService.Simulation run = service.simulate(context, "K", null);
        assertThat(run.population()).isZero();
        assertThat(run.impact().groups()).isEmpty();
        when(settings.configured(SystemParameters.Code.SIMULATION_MAX_SELECTION))
                .thenReturn(OptionalLong.empty());
        assertThatThrownBy(() -> service.simulate(context, "K", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.SIMULATION_NOT_CONFIGURED));
    }

    @Test
    void SRS_19_4_aKeptRunSaysWhetherItsInputsChangedSince() throws Exception {
        when(inForce.resolve(eq("K"), any())).thenReturn(Optional.empty());
        ArgumentCaptor<RowCallbackHandler> handler =
                ArgumentCaptor.forClass(RowCallbackHandler.class);
        when(policy.can(context, "M-08", "VIEW")).thenReturn(true);
        List<RuleSimulationService.Simulation> none = service.simulations(context, "K");
        assertThat(none).isEmpty();
        verify(jdbc).query(anyString(), handler.capture(), eq("M08;40;K;%"));
        String impact =
                json.writeValueAsString(new RuleSimulationService.Impact(1, 1, 1, List.of()));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(4)).thenReturn("M08;40;K;ALL;ALL", "M08;40;K;STAFF;3");
        when(rs.getLong(8)).thenReturn(0L, 12L);
        when(rs.wasNull()).thenReturn(true, false);
        when(rs.getTimestamp(3)).thenReturn(Timestamp.from(Instant.EPOCH));
        when(rs.getString(6)).thenReturn("stale");
        when(rs.getString(7)).thenReturn(impact);
        handler.getValue().processRow(rs);
        handler.getValue().processRow(rs);
        assertThat(none)
                .extracting(
                        RuleSimulationService.Simulation::payrollType,
                        RuleSimulationService.Simulation::officeId,
                        RuleSimulationService.Simulation::ruleVersionId,
                        RuleSimulationService.Simulation::inputsChanged)
                .containsExactly(tuple(null, null, null, true), tuple("STAFF", 3L, 12L, true));
    }
}
