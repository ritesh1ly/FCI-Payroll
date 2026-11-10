package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.RuleImpactPricing;
import in.gov.fci.payroll.configuration.api.SimulationPopulation;
import in.gov.fci.payroll.payroll.business.PayRules;
import in.gov.fci.payroll.payroll.business.SimulationRules;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** BR-RULE-006, SRS-1-2 (M-08 simulation): a DA rate is priced by the engine as a what-if only. */
class DaRuleImpactPricerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final List<SimulationPopulation.Member> SAMPLE =
            List.of(
                    new SimulationPopulation.Member(2L, "STAFF", 40L),
                    new SimulationPopulation.Member(1L, "STAFF", 40L));

    private final SimulationEngine engine = mock(SimulationEngine.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final DaRuleImpactPricer pricer =
            new DaRuleImpactPricer(
                    engine,
                    jdbc,
                    Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC));

    private static SimulationEngine.Side side(String da, String tds, String net) {
        return new SimulationEngine.Side(
                2,
                0,
                Map.of("no rate", 1L),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(net),
                Map.of(PayRules.DA, new BigDecimal(da), 300, new BigDecimal(tds)));
    }

    private static SimulationEngine.MemberResult member(String whatIfNet) {
        return new SimulationEngine.MemberResult(
                1L,
                "COMPUTED",
                null,
                null,
                null,
                null,
                whatIfNet == null ? null : new BigDecimal(whatIfNet));
    }

    @Test
    void BR_RULE_006_aProposedRateIsPricedOnTheSampleWithTheStatutoryKnockOn() throws Exception {
        when(engine.runAt(any(), any()))
                .thenReturn(
                        new SimulationEngine.Outcome(
                                2,
                                side("100.00", "10.00", "900.00"),
                                side("106.00", "11.00", "905.00"),
                                "h",
                                "v",
                                List.of(member("-1.00"), member("5.00"), member(null))));
        org.mockito.Mockito.doAnswer(
                        call -> {
                            RowCallbackHandler handler = call.getArgument(2);
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getInt(2)).thenReturn(300);
                            when(rs.getString(1)).thenReturn("TDS");
                            handler.processRow(rs);
                            return null;
                        })
                .when(jdbc)
                .query(
                        any(String.class),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));

        Optional<RuleImpactPricing.Priced> priced =
                pricer.price("DA.CDA.RATE", new BigDecimal("47"), FROM, SAMPLE);

        assertThat(priced).isPresent();
        RuleImpactPricing.Priced p = priced.get();
        assertThat(p.currentComponent()).isEqualByComparingTo("100.00");
        assertThat(p.proposedComponent()).isEqualByComparingTo("106.00");
        assertThat(p.statutoryDelta().get("TDS")).isEqualByComparingTo("1.00");
        assertThat(p.notComputed()).isEqualTo(1);
        assertThat(p.negativeNet()).isEqualTo(1);
        // July to September: two months have passed since the proposed start.
        assertThat(p.retroPeriods()).isEqualTo(2);
        ArgumentCaptor<SimulationEngine.Request> request =
                ArgumentCaptor.forClass(SimulationEngine.Request.class);
        verify(engine).runAt(eq(List.of(40L)), request.capture());
        assertThat(request.getValue().whatIf()).isEqualTo(SimulationRules.WhatIf.DA_RATE);
        assertThat(request.getValue().daCda()).isEqualByComparingTo("47");
        assertThat(request.getValue().daIda()).isNull();
        assertThat(request.getValue().employeeIds()).containsExactly(1L, 2L);
    }

    @Test
    void BR_RULE_006_aFutureStartHasNoRetroPeriodsAndTheIdaRateGoesToIda() {
        when(engine.runAt(any(), any()))
                .thenReturn(
                        new SimulationEngine.Outcome(
                                0, side("0", "0", "0"), side("0", "0", "0"), "h", "v", List.of()));
        RuleImpactPricing.Priced p =
                pricer.price("DA.IDA.RATE", BigDecimal.TEN, LocalDate.of(2027, 1, 1), SAMPLE)
                        .orElseThrow();
        assertThat(p.retroPeriods()).isZero();
        ArgumentCaptor<SimulationEngine.Request> request =
                ArgumentCaptor.forClass(SimulationEngine.Request.class);
        verify(engine).runAt(any(), request.capture());
        assertThat(request.getValue().daIda()).isEqualByComparingTo("10");
        assertThat(request.getValue().daCda()).isNull();
    }

    @Test
    void BR_RULE_006_anotherRuleAMissingValueOrAnEmptySampleIsNotPriced() {
        assertThat(pricer.price("CPF.RATE.EMPLOYER", BigDecimal.ONE, FROM, SAMPLE)).isEmpty();
        assertThat(pricer.price("DA.IDA.RATE", null, FROM, SAMPLE)).isEmpty();
        assertThat(pricer.price("DA.IDA.RATE", BigDecimal.ONE, FROM, List.of())).isEmpty();
        verify(engine, never()).runAt(any(), any());
    }
}
