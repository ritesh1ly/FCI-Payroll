package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleVersionChanged;
import in.gov.fci.payroll.configuration.api.SimulationPopulation;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** M-08 impact simulation (plan 06 phase 06e, D-12; SRS-19-4): its texts, guards and link. */
class RuleSimulationServiceTest {

    private final RuleCatalogueService catalogue = mock(RuleCatalogueService.class);
    private final Authorization policy = mock(Authorization.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccessContext context = mock(AccessContext.class);

    @SuppressWarnings("unchecked")
    private final RuleSimulationService service =
            new RuleSimulationService(
                    catalogue,
                    mock(RuleEngine.class),
                    mock(SystemParameters.class),
                    mock(ObjectProvider.class),
                    org,
                    policy,
                    mock(AuditService.class),
                    jdbc,
                    mock(ObjectMapper.class),
                    Clock.systemUTC(),
                    mock(ObjectProvider.class));

    @Test
    void SRS_19_4_eachValueShapeHasItsText() {
        assertThat(RuleSimulationService.describe(new BigDecimal("12.500"), null, List.of()))
                .isEqualTo("12.5");
        assertThat(RuleSimulationService.describe(null, "  BASIC * 2 ", List.of()))
                .isEqualTo("FORMULA BASIC * 2");
        assertThat(RuleSimulationService.describe(BigDecimal.ONE, " ", List.of())).isEqualTo("1");
        assertThat(RuleSimulationService.describe(null, null, List.of()))
                .isEqualTo("NOT_CONFIGURED");
        assertThat(RuleSimulationService.describe(null, null, List.of("0-10: 1", "10-: 2")))
                .isEqualTo("SLAB 0-10: 1; 10-: 2");
        assertThat(RuleSimulationService.band(BigDecimal.ZERO, null, new BigDecimal("2.50"), null))
                .isEqualTo("0-: 2.5");
        assertThat(RuleSimulationService.band(null, BigDecimal.TEN, null, "IDA"))
                .isEqualTo("IDA -10: ");
    }

    @Test
    void SRS_19_4_theRunScopeNamesTheApprovalKeyTypeAndOffice() {
        assertThat(
                        RuleSimulationService.scope(
                                7L, "K", new RuleSimulationService.Request(null, null)))
                .isEqualTo("M08;7;K;ALL;ALL");
        assertThat(
                        RuleSimulationService.scope(
                                7L, "K", new RuleSimulationService.Request("STAFF", 3L)))
                .isEqualTo("M08;7;K;STAFF;3");
        assertThat(new RuleSimulationService.Impact(1, 1, 0, null).groups()).isEmpty();
        assertThat(RuleSimulationService.sha256("a")).hasSize(64);
    }

    @Test
    void SRS_19_4_onlyTheRuleAdministratorOrApproverAtHeadquartersSimulates() {
        assertThatThrownBy(() -> service.simulate(context, "K", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(CommonErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> service.offices(context))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(CommonErrorCode.ACCESS_DENIED));
        when(policy.can(context, "M-08", "APPROVE")).thenReturn(true);
        assertThatThrownBy(() -> service.simulate(context, "K", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiErrorCode.HQ_ONLY));
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        assertThatThrownBy(
                        () ->
                                service.simulate(
                                        context,
                                        "K",
                                        new RuleSimulationService.Request("CASUAL", null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        when(org.location(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                service.simulate(
                                        context, "K", new RuleSimulationService.Request(null, 9L)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        when(catalogue.pendingProposal("K")).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                service.simulate(
                                        context,
                                        "K",
                                        new RuleSimulationService.Request("STAFF", null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.RULE_NO_PROPOSAL));
        assertThat(service.simulations(context, "K")).isEmpty();
    }

    private static RuleVersionChanged changed(RuleVersionChanged.Kind kind, Long versionId) {
        return new RuleVersionChanged(
                kind, "K", "RATE", versionId, 2, LocalDate.now(), null, LocalDate.now());
    }

    @Test
    void SRS_19_4_theRunsAreLinkedToTheApprovedVersionOnly() {
        RuleVersionChanged.Kind other =
                Arrays.stream(RuleVersionChanged.Kind.values())
                        .filter(k -> k != RuleVersionChanged.Kind.APPROVED)
                        .findFirst()
                        .orElseThrow();
        service.on(changed(other, 5L));
        service.on(changed(RuleVersionChanged.Kind.APPROVED, null));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(5L))).thenReturn(null);
        service.on(changed(RuleVersionChanged.Kind.APPROVED, 5L));
        verify(jdbc, never()).update(anyString(), any(), any());
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(6L))).thenReturn(40L);
        service.on(changed(RuleVersionChanged.Kind.APPROVED, 6L));
        verify(jdbc).update(anyString(), eq(6L), eq("M08;40;K;%"));
    }

    // BR-RULE-006: a proposed version is simulated before the Approver activates it.
    @Test
    void SRS_19_4_theDescribedFormsOfAResolvedVersionAndAProposal() {
        RuleEngine.Resolved resolved =
                new RuleEngine.Resolved(
                        "K",
                        "SLAB",
                        1L,
                        1,
                        LocalDate.now(),
                        null,
                        null,
                        List.of(
                                new RuleEngine.Band(
                                        1,
                                        BigDecimal.ZERO,
                                        BigDecimal.TEN,
                                        BigDecimal.ONE,
                                        null,
                                        null)),
                        null,
                        null,
                        false);
        assertThat(RuleSimulationService.describe(resolved)).isEqualTo("SLAB 0-10: 1");
        RuleCatalogueService.VersionProposal proposal =
                new RuleCatalogueService.VersionProposal(
                        "K",
                        "RATE",
                        LocalDate.now(),
                        null,
                        new BigDecimal("3.0"),
                        null,
                        null,
                        null,
                        1L,
                        "a",
                        null);
        assertThat(RuleSimulationService.describe(proposal)).isEqualTo("3");
        verify(policy, never()).require(any(), anyString(), anyString());
        SimulationPopulation.Sample sample = new SimulationPopulation.Sample(0, List.of());
        assertThat(sample.members()).isEmpty();
    }
}
