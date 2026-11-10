package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.business.SimulationRules.Mode;
import in.gov.fci.payroll.payroll.business.SimulationRules.Population;
import in.gov.fci.payroll.payroll.business.SimulationRules.WhatIf;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SRS-15-7 and the SRS C-09 filters (plan 05 phase 05i, D-17): the population selection up to the
 * configured maximum, the what-if DA rate, the run mode against the interactive limit, and the
 * totals of a side.
 */
class SimulationRulesTest {

    @Test
    void SRS_15_7_theSelectionNamesMembersUpToTheMaximumOnceEach() {
        assertThat(SimulationRules.selectionDefect(Population.OFFICE, List.of(), 10)).isEmpty();
        assertThat(SimulationRules.selectionDefect(Population.OFFICE, null, 10)).isEmpty();
        assertThat(SimulationRules.selectionDefect(Population.OFFICE, List.of("E1"), 10))
                .contains("NOT_VALID");
        assertThat(SimulationRules.selectionDefect(Population.MEMBER, List.of("E1"), 10)).isEmpty();
        assertThat(SimulationRules.selectionDefect(Population.MEMBER, null, 10)).contains("EMPTY");
        assertThat(SimulationRules.selectionDefect(Population.MEMBER_LIST, List.of(), 10))
                .contains("EMPTY");
        assertThat(SimulationRules.selectionDefect(Population.MEMBER, List.of("E1", "E2"), 10))
                .contains("ONE_ONLY");
        assertThat(SimulationRules.selectionDefect(Population.MEMBER_LIST, List.of("E1", "E2"), 2))
                .isEmpty();
        assertThat(
                        SimulationRules.selectionDefect(
                                Population.MEMBER_LIST, List.of("E1", "E2", "E3"), 2))
                .contains("TOO_MANY");
        assertThat(SimulationRules.selectionDefect(Population.MEMBER_LIST, List.of("E1", " E1"), 5))
                .contains("DUPLICATE");
        assertThat(SimulationRules.selectionDefect(Population.MEMBER, List.of(" "), 5))
                .contains("NOT_VALID");
        assertThat(SimulationRules.selectionDefect(Population.MEMBER, List.of("Eé1"), 5))
                .contains("NOT_VALID");
        assertThat(SimulationRules.selectionDefect(Population.MEMBER, List.of("E\u00011"), 5))
                .contains("NOT_VALID");
        // Printable ASCII both ends included: space and tilde; DEL is not.
        assertThat(SimulationRules.selectionDefect(Population.MEMBER, List.of("E 1~"), 5))
                .isEmpty();
        assertThat(SimulationRules.selectionDefect(Population.MEMBER, List.of("E1"), 5))
                .contains("NOT_VALID");
        assertThat(SimulationRules.selectionDefect(Population.MEMBER, List.of("x".repeat(20)), 5))
                .isEmpty();
        assertThat(SimulationRules.selectionDefect(Population.MEMBER, List.of("x".repeat(21)), 5))
                .contains("NOT_VALID");
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThat(SimulationRules.selectionDefect(Population.MEMBER, withNull, 5))
                .contains("NOT_VALID");
        assertThat(SimulationRules.withinMaximum(10, 10)).isTrue();
        assertThat(SimulationRules.withinMaximum(11, 10)).isFalse();
    }

    @Test
    void SRS_15_7_aboveTheInteractiveLimitTheRunGoesToTheBackground() {
        assertThat(SimulationRules.mode(5, 5)).isEqualTo(Mode.INTERACTIVE);
        assertThat(SimulationRules.mode(0, 5)).isEqualTo(Mode.INTERACTIVE);
        assertThat(SimulationRules.mode(6, 5)).isEqualTo(Mode.BACKGROUND);
    }

    @Test
    void SRS_15_7_theWhatIfIsAsIsOrAPositiveDaRateWithinNumeric14And4() {
        BigDecimal ok = new BigDecimal("58.5");
        assertThat(SimulationRules.whatIfDefect(WhatIf.AS_IS, null, null)).isEmpty();
        assertThat(SimulationRules.whatIfDefect(WhatIf.AS_IS, ok, null)).contains("NOT_VALID");
        assertThat(SimulationRules.whatIfDefect(WhatIf.AS_IS, null, ok)).contains("NOT_VALID");
        assertThat(SimulationRules.whatIfDefect(WhatIf.DA_RATE, null, null)).contains("MISSING");
        assertThat(SimulationRules.whatIfDefect(WhatIf.DA_RATE, ok, null)).isEmpty();
        assertThat(SimulationRules.whatIfDefect(WhatIf.DA_RATE, null, ok)).isEmpty();
        assertThat(SimulationRules.whatIfDefect(WhatIf.DA_RATE, ok, ok)).isEmpty();
        assertThat(SimulationRules.whatIfDefect(WhatIf.DA_RATE, BigDecimal.ZERO, null))
                .contains("NOT_POSITIVE");
        assertThat(SimulationRules.whatIfDefect(WhatIf.DA_RATE, ok, new BigDecimal("-1")))
                .contains("NOT_POSITIVE");
        assertThat(SimulationRules.whatIfDefect(WhatIf.DA_RATE, new BigDecimal("1.0001"), null))
                .isEmpty();
        assertThat(SimulationRules.whatIfDefect(WhatIf.DA_RATE, new BigDecimal("1.50000"), null))
                .isEmpty();
        assertThat(SimulationRules.whatIfDefect(WhatIf.DA_RATE, new BigDecimal("1.00001"), null))
                .contains("TOO_PRECISE");
        assertThat(
                        SimulationRules.whatIfDefect(
                                WhatIf.DA_RATE, new BigDecimal("9999999999.9999"), null))
                .isEmpty();
        assertThat(
                        SimulationRules.whatIfDefect(
                                WhatIf.DA_RATE, new BigDecimal("10000000000"), null))
                .contains("TOO_LARGE");
    }

    @Test
    void SRS_15_7_theWhatIfDaRateReplacesOnlyThePatternsGivenAndIsMarked() {
        PayConfiguration.Snapshot base =
                new PayConfiguration.Snapshot(
                        LocalDate.of(2026, 10, 1),
                        Map.of("IDA", new BigDecimal("50"), "CDA", new BigDecimal("55")),
                        Map.of(),
                        Map.of(),
                        true,
                        Map.of(),
                        Map.of(),
                        "abc");
        PayConfiguration.Snapshot ida = SimulationRules.override(base, new BigDecimal("60"), null);
        assertThat(ida.daPercent())
                .containsEntry("IDA", new BigDecimal("60"))
                .containsEntry("CDA", new BigDecimal("55"));
        assertThat(ida.versionSetHash()).isEqualTo("abc|WHATIF");
        PayConfiguration.Snapshot cda = SimulationRules.override(base, null, new BigDecimal("61"));
        assertThat(cda.daPercent())
                .containsEntry("IDA", new BigDecimal("50"))
                .containsEntry("CDA", new BigDecimal("61"));
        assertThat(cda.ccaSeeded()).isTrue();
        assertThat(cda.asOf()).isEqualTo(base.asOf());
    }

    @Test
    void BR_CYC_007_aSideCountsComputedStoppedAndNotComputedAndAddsUpByPayCode() {
        SimulationRules.Totals t = new SimulationRules.Totals();
        t.add(
                new PayRules.Outcome(
                        PayRules.COMPUTED,
                        null,
                        List.of(
                                new PayRules.Line(101, 1, new BigDecimal("100.00"), null, null),
                                new PayRules.Line(111, 2, new BigDecimal("50.00"), null, null)),
                        new BigDecimal("150.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("140.00")));
        t.add(
                new PayRules.Outcome(
                        PayRules.COMPUTED,
                        null,
                        List.of(new PayRules.Line(101, 1, new BigDecimal("200.00"), null, null)),
                        new BigDecimal("200.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("200.00")));
        t.add(new PayRules.Outcome(PayRules.SKIPPED, "STOP_PAYMENT", List.of(), null, null, null));
        t.skip("CCA rates not seeded");
        t.skip("CCA rates not seeded");
        assertThat(t.computed()).isEqualTo(2);
        assertThat(t.stopped()).isEqualTo(1);
        assertThat(t.skipped()).containsEntry("CCA rates not seeded", 2L);
        assertThat(t.gross()).isEqualByComparingTo("350.00");
        assertThat(t.deductions()).isEqualByComparingTo("10.00");
        assertThat(t.net()).isEqualByComparingTo("340.00");
        assertThat(t.byPayCode())
                .containsEntry(101, new BigDecimal("300.00"))
                .containsEntry(111, new BigDecimal("50.00"));
    }
}
