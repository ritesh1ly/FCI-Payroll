package in.gov.fci.payroll.payroll.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.business.PayDataException;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The deductions of the staff payroll (plan 16 phase 16e-2; SRS-1-6, SRS-1-8, SRS-DM-6): the
 * sources apply in their order of recovery, each line written negative; net = gross less
 * deductions; a source that cannot compute, a non-positive line or a negative net refuses the
 * member; with no source the outcome and the hash are unchanged. Amounts are fixtures.
 */
class StaffDeductionRunnerTest {

    private static final PayConfiguration.Snapshot CONFIG =
            new PayConfiguration.Snapshot(
                    LocalDate.of(2026, 10, 1),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    false,
                    Map.of(101, 1L, 701, 7L, 801, 8L),
                    Map.of(),
                    "config-hash");

    private static final PayRules.Outcome COMPUTED =
            new PayRules.Outcome(
                    PayRules.COMPUTED,
                    null,
                    List.of(new PayRules.Line(101, 1L, new BigDecimal("30000.00"), null, null)),
                    new BigDecimal("30000.00"),
                    BigDecimal.ZERO,
                    new BigDecimal("30000.00"),
                    "HOLD_FLAG");

    private static StaffDeductions source(
            int order, int payCode, String amount, List<String> calls) {
        return new StaffDeductions() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public Run open(LocalDate asOf) {
                calls.add("open " + order + "@" + asOf);
                return new Run() {
                    @Override
                    public String versions() {
                        return "v" + order + "@" + asOf;
                    }

                    @Override
                    public List<Line> deductions(Member member) {
                        calls.add(
                                order
                                        + ":"
                                        + member.gross()
                                        + ":"
                                        + member.earnings().size()
                                        + ":"
                                        + member.month());
                        if (amount == null) {
                            throw new NotComputable("rate not configured " + payCode);
                        }
                        return List.of(
                                new Line(payCode, new BigDecimal(amount), null, member.gross()));
                    }
                };
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static StaffDeductionRunner runner(StaffDeductions... sources) {
        ObjectProvider<StaffDeductions> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(i -> Stream.of(sources));
        return new StaffDeductionRunner(provider);
    }

    private static PayRules.Outcome apply(StaffDeductionRunner r, PayRules.Outcome o) {
        return r.open(YearMonth.of(2026, 10)).apply(o, 7L, 3L, "IDA", CONFIG);
    }

    /** A source that names the statutory sub-process it computes. */
    private static StaffDeductions statutory(int order, String process) {
        StaffDeductions plain = source(order, 701, "1", new ArrayList<>());
        return new StaffDeductions() {
            @Override
            public int order() {
                return plain.order();
            }

            @Override
            public Run open(LocalDate asOf) {
                return plain.open(asOf);
            }

            @Override
            public java.util.Optional<String> statutoryProcess() {
                return java.util.Optional.of(process);
            }
        };
    }

    @Test
    void SRS_15_18_G5_theStatutoryProcessesAreThoseOfTheRegisteredSourcesOnly() {
        assertThat(runner().statutoryProcesses()).isEmpty();
        assertThat(
                        runner(
                                        source(50, 801, "1", new ArrayList<>()),
                                        statutory(40, "PROFESSIONAL_TAX"),
                                        statutory(10, "CPF_PF"))
                                .statutoryProcesses())
                .as("a loan or penalty source is no statutory process")
                .containsExactlyInAnyOrder("CPF_PF", "PROFESSIONAL_TAX");
    }

    @Test
    void SRS_1_6_theSourcesApplyInOrderAndNetIsGrossLessDeductions() {
        List<String> calls = new ArrayList<>();
        PayRules.Outcome o =
                apply(
                        runner(source(20, 801, "200", calls), source(10, 701, "3600.4", calls)),
                        COMPUTED);
        assertThat(calls)
                .containsExactly(
                        "open 10@2026-10-01",
                        "open 20@2026-10-01",
                        "10:30000.00:1:2026-10",
                        "20:30000.00:1:2026-10");
        assertThat(o.deductions()).isEqualByComparingTo("3800");
        assertThat(o.net()).isEqualByComparingTo("26200");
        assertThat(o.gross()).isEqualByComparingTo("30000");
        assertThat(o.paymentHold()).as("the hold is kept").isEqualTo("HOLD_FLAG");
        assertThat(o.lines()).extracting(PayRules.Line::payCode).containsExactly(101, 701, 801);
        assertThat(o.lines().get(1).amount()).as("SRS-DM-6: signed").isEqualByComparingTo("-3600");
        assertThat(o.lines().get(1).componentId()).isEqualTo(7L);
        assertThat(o.lines().get(1).basis()).isEqualByComparingTo("30000");
    }

    @Test
    void SRS_1_6_aSourceThatCannotComputeOrABadLineRefusesTheMember() {
        List<String> calls = new ArrayList<>();
        assertThatThrownBy(() -> apply(runner(source(10, 701, null, calls)), COMPUTED))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("rate not configured 701")
                .hasMessageContaining("employee 7");
        assertThatThrownBy(() -> apply(runner(source(10, 701, "-1", calls)), COMPUTED))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("not a positive amount");
        assertThatThrownBy(() -> apply(runner(source(10, 999, "10", calls)), COMPUTED))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("999");
        // SRS-1-8 (BMAD TR-037): a negative net is computed and kept for the run to quarantine
        // (NEGATIVE_NET), never a refusal of the member.
        PayRules.Outcome negative = apply(runner(source(10, 701, "30001", calls)), COMPUTED);
        assertThat(negative.status()).isEqualTo(PayRules.COMPUTED);
        assertThat(negative.net()).isEqualByComparingTo("-1");
        assertThat(negative.deductions()).isEqualByComparingTo("30001");
        PayRules.Outcome zero = apply(runner(source(10, 701, "0", calls)), COMPUTED);
        assertThat(zero.lines()).hasSize(1);
        assertThat(zero.net()).isEqualByComparingTo("30000");
        PayRules.Outcome whole = apply(runner(source(10, 701, "30000", calls)), COMPUTED);
        assertThat(whole.net()).isEqualByComparingTo("0");
    }

    /** A source with an employer contribution (earning 908, deduction 907) and its own line 906. */
    private static StaffDeductions contributing(String employer, String employee) {
        return new StaffDeductions() {
            @Override
            public int order() {
                return 20;
            }

            @Override
            public Run open(LocalDate asOf) {
                return new Run() {
                    @Override
                    public String versions() {
                        return "c";
                    }

                    @Override
                    public List<Contribution> contributions(Member member) {
                        return List.of(new Contribution(908, 907, new BigDecimal(employer)));
                    }

                    @Override
                    public List<Line> deductions(Member member) {
                        return List.of(new Line(906, new BigDecimal(employee), null, null));
                    }
                };
            }
        };
    }

    private static final PayConfiguration.Snapshot NPS_CONFIG =
            new PayConfiguration.Snapshot(
                    LocalDate.of(2026, 10, 1),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    false,
                    Map.of(101, 1L, 801, 8L, 906, 96L, 907, 97L, 908, 98L),
                    Map.of(),
                    "config-hash");

    @Test
    void SRS_9_21_anEmployerContributionIsOnBothSidesAndALaterSourceSeesIt() {
        List<String> calls = new ArrayList<>();
        PayRules.Outcome o =
                runner(contributing("2100", "1500"), source(30, 801, "100", calls))
                        .open(YearMonth.of(2026, 10))
                        .apply(COMPUTED, 7L, 3L, "IDA", NPS_CONFIG);
        assertThat(calls).contains("30:32100.00:2:2026-10");
        assertThat(o.gross()).isEqualByComparingTo("32100");
        assertThat(o.deductions()).isEqualByComparingTo("3700");
        assertThat(o.net())
                .as("net = 30000 - 1500 - 100: the employer part is unchanged")
                .isEqualByComparingTo("28400");
        assertThat(o.lines())
                .extracting(PayRules.Line::payCode)
                .containsExactly(101, 908, 907, 906, 801);
        assertThat(o.lines().get(1).amount()).isEqualByComparingTo("2100");
        assertThat(o.lines().get(2).amount()).isEqualByComparingTo("-2100");

        PayRules.Outcome zero =
                runner(contributing("0", "1500"))
                        .open(YearMonth.of(2026, 10))
                        .apply(COMPUTED, 7L, 3L, "IDA", NPS_CONFIG);
        assertThat(zero.gross())
                .as("a zero contribution writes nothing")
                .isEqualByComparingTo("30000");
        assertThatThrownBy(
                        () ->
                                runner(contributing("-1", "1500"))
                                        .open(YearMonth.of(2026, 10))
                                        .apply(COMPUTED, 7L, 3L, "IDA", NPS_CONFIG))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("not a positive amount");
        assertThatThrownBy(
                        () ->
                                runner(contributing("2100", "1500"))
                                        .open(YearMonth.of(2026, 10))
                                        .apply(COMPUTED, 7L, 3L, "IDA", CONFIG))
                .as("the employer codes not configured (M-07)")
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("908");
    }

    @Test
    void SRS_1_6_withoutSourcesOrForASkippedMemberNothingChanges() {
        StaffDeductionRunner none = runner();
        assertThat(apply(none, COMPUTED)).isSameAs(COMPUTED);
        assertThat(none.open(YearMonth.of(2026, 10)).versionSetHash(CONFIG))
                .isEqualTo("config-hash");
        PayRules.Outcome skipped =
                new PayRules.Outcome(PayRules.SKIPPED, "STOP_PAYMENT", List.of(), null, null, null);
        List<String> calls = new ArrayList<>();
        StaffDeductionRunner one = runner(source(10, 701, "1", calls));
        assertThat(apply(one, skipped)).isSameAs(skipped);
        assertThat(calls).as("opened, nothing computed").containsExactly("open 10@2026-10-01");
        assertThat(PayRules.withDeductions(COMPUTED, List.of(), CONFIG)).isSameAs(COMPUTED);
        String hash = one.open(YearMonth.of(2026, 10)).versionSetHash(CONFIG);
        assertThat(hash).isNotEqualTo("config-hash").hasSize(64);
        assertThat(one.open(YearMonth.of(2026, 11)).versionSetHash(CONFIG))
                .as("the source's versions")
                .isNotEqualTo(hash);
    }

    @Test
    void SRS_11_12_aOneTimeEarningIsPositiveAndAddsToGrossAndNet() {
        for (BigDecimal bad : new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("-1")}) {
            assertThatThrownBy(
                            () ->
                                    PayRules.withDeductions(
                                            COMPUTED,
                                            List.of(new PayRules.Earning(701, bad)),
                                            List.of(),
                                            List.of(),
                                            CONFIG))
                    .isInstanceOf(PayDataException.class);
        }
        PayRules.Outcome paid =
                PayRules.withDeductions(
                        COMPUTED,
                        List.of(new PayRules.Earning(701, new BigDecimal("1.40"))),
                        List.of(),
                        List.of(),
                        CONFIG);
        assertThat(paid.gross()).isEqualByComparingTo("30001");
        assertThat(paid.net()).isEqualByComparingTo("30001");
        assertThat(paid.lines()).extracting(PayRules.Line::payCode).containsExactly(101, 701);
    }

    private static StaffDeductions reducing(String cut, List<String> seen) {
        return new StaffDeductions() {
            @Override
            public int order() {
                return 5;
            }

            @Override
            public Run open(LocalDate asOf) {
                return new Run() {
                    @Override
                    public String versions() {
                        return "leave@" + asOf;
                    }

                    @Override
                    public List<Reduction> reductions(Member member) {
                        if (cut == null) {
                            throw new NotComputable("leave rule not configured");
                        }
                        return List.of(new Reduction(101, new BigDecimal(cut)));
                    }

                    @Override
                    public List<Line> deductions(Member member) {
                        seen.add(member.gross().toPlainString());
                        return List.of();
                    }
                };
            }
        };
    }

    // D19-4: the month's leave reductions come first, so every later source sees the reduced pay;
    // a reduction above the earning leaves it at zero; a reduction that cannot be computed refuses
    // the member.
    @Test
    void D19_4_aReductionLowersThePayEveryLaterSourceSeesAndAnUnknownOneRefuses() {
        List<String> seen = new ArrayList<>();
        PayRules.Outcome o = apply(runner(reducing("5000.00", seen)), COMPUTED);
        assertThat(seen).containsExactly("25000.00");
        assertThat(o.gross()).isEqualByComparingTo("25000");
        List<String> floor = new ArrayList<>();
        apply(runner(reducing("40000.00", floor)), COMPUTED);
        assertThat(floor).containsExactly("0.00");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> apply(runner(reducing(null, new ArrayList<>())), COMPUTED))
                .hasMessageContaining("leave rule not configured")
                .hasMessageContaining("employee 7");
    }

    private static StaffDeductions earning(String amount) {
        return new StaffDeductions() {
            @Override
            public int order() {
                return 3;
            }

            @Override
            public Run open(LocalDate asOf) {
                return new Run() {
                    @Override
                    public String versions() {
                        return "arrear@" + asOf;
                    }

                    @Override
                    public List<Earning> earnings(Member member) {
                        if (amount == null) {
                            throw new NotComputable(
                                    "Arrear pay code of component 101 not configured");
                        }
                        return List.of(new Earning(701, new BigDecimal(amount)));
                    }

                    @Override
                    public List<Line> deductions(Member member) {
                        return List.of();
                    }
                };
            }
        };
    }

    // D10-6 / SRS-1-6: a one-time earning that cannot be computed refuses this member with its
    // reason (a PayDataException the run counts or quarantines), never the whole run with a raw
    // exception (found by SimulationIT at the 2026-11-04 checkpoint); one that can is paid.
    @Test
    void D10_6_anEarningThatCannotBeComputedRefusesTheMemberOnly() {
        assertThatThrownBy(() -> apply(runner(earning(null)), COMPUTED))
                .isInstanceOf(PayDataException.class)
                .hasMessage("Arrear pay code of component 101 not configured (employee 7)");
        PayRules.Outcome paid = apply(runner(earning("250.00")), COMPUTED);
        assertThat(paid.gross()).isEqualByComparingTo("30250");
        assertThat(paid.lines()).extracting(PayRules.Line::payCode).containsExactly(101, 701);
    }
}
