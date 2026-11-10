package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.api.AllowanceConfiguration;
import in.gov.fci.payroll.configuration.api.AllowanceConfiguration.TransportRate;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The flag-driven allowances and the payment hold (plan 16 phase 16d; SRS-1-4, SRS-1-7, SRS-1-41).
 * Rates are fixtures, not FCI values.
 */
class PayRulesAllowanceTest {

    private static final Map<Integer, Long> COMPONENTS =
            Map.of(101, 1L, 111, 2L, 121, 3L, 131, 4L, 901, 5L, 902, 6L);

    private static final AllowanceConfiguration.Allowances CONFIGURED =
            new AllowanceConfiguration.Allowances(
                    Map.of("NPA", 901, "TRANSPORT", 902, "CCA", 131),
                    new BigDecimal("25"),
                    new BigDecimal("9000"),
                    List.of(
                            new TransportRate("IDA", "X", new BigDecimal("3600")),
                            new TransportRate("IDA", null, new BigDecimal("1800")),
                            new TransportRate("CDA", null, new BigDecimal("1350"))),
                    "allowances");

    private static PayConfiguration.Snapshot config(AllowanceConfiguration.Allowances allowances) {
        return new PayConfiguration.Snapshot(
                LocalDate.of(2026, 7, 1),
                Map.of("IDA", new BigDecimal("50"), "CDA", new BigDecimal("40")),
                Map.of(
                        "X",
                        new BigDecimal("30"),
                        "Y",
                        new BigDecimal("20"),
                        PayConfiguration.CDA_HRA + "Y",
                        new BigDecimal("20")),
                Map.of("X", new BigDecimal("1800")),
                true,
                COMPONENTS,
                Map.of(),
                "hash",
                allowances);
    }

    private static PayRules.Member member(
            String pattern, String city, Set<String> flags, String hold) {
        return new PayRules.Member(
                7L, new BigDecimal("20000"), pattern, city, "IN_SERVICE", null, false, flags, hold);
    }

    private static BigDecimal amount(PayRules.Outcome o, int payCode) {
        return o.lines().stream()
                .filter(l -> l.payCode() == payCode)
                .map(PayRules.Line::amount)
                .findFirst()
                .orElse(null);
    }

    @Test
    void SRS_1_4_ccaIsPaidOnlyWhenFlagged() {
        PayRules.Outcome flagged =
                PayRules.compute(member("IDA", "X", Set.of("CCA"), null), config(CONFIGURED));
        assertThat(amount(flagged, PayRules.CCA)).isEqualByComparingTo("1800");
        PayRules.Outcome not =
                PayRules.compute(member("IDA", "X", Set.of(), null), config(CONFIGURED));
        assertThat(amount(not, PayRules.CCA)).isNull();
        assertThat(not.gross()).isEqualByComparingTo("36000"); // 20000 + DA 10000 + HRA 6000
        PayConfiguration.Snapshot unseeded =
                new PayConfiguration.Snapshot(
                        LocalDate.of(2026, 7, 1),
                        Map.of("IDA", new BigDecimal("50")),
                        Map.of("X", new BigDecimal("30")),
                        Map.of(),
                        false,
                        COMPONENTS,
                        Map.of(),
                        "hash");
        assertThat(PayRules.compute(member("IDA", "X", Set.of(), null), unseeded).status())
                .as("CCA rates matter only to a flagged member")
                .isEqualTo(PayRules.COMPUTED);
        assertThatThrownBy(
                        () -> PayRules.compute(member("IDA", "X", Set.of("CCA"), null), unseeded))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("CCA rates not seeded");
    }

    @Test
    void SRS_1_4_npaIsAPercentOfBasicUpToTheCeilingAndEntersTheDaBase() {
        PayRules.Outcome o =
                PayRules.compute(member("IDA", "Y", Set.of("NPA"), null), config(CONFIGURED));
        assertThat(amount(o, 901)).isEqualByComparingTo("5000"); // 25 % of 20000
        PayRules.Line da =
                o.lines().stream()
                        .filter(l -> l.payCode() == PayRules.DA)
                        .findFirst()
                        .orElseThrow();
        assertThat(da.basis()).isEqualByComparingTo("25000");
        assertThat(da.amount()).isEqualByComparingTo("12500");
        assertThat(o.gross()).isEqualByComparingTo("41500"); // 20000 + 12500 + 5000 + HRA 4000

        AllowanceConfiguration.Allowances lowCeiling =
                new AllowanceConfiguration.Allowances(
                        CONFIGURED.flagPayCodes(),
                        new BigDecimal("25"),
                        new BigDecimal("3000"),
                        CONFIGURED.transport(),
                        "c");
        assertThat(
                        amount(
                                PayRules.compute(
                                        member("IDA", "Y", Set.of("NPA"), null),
                                        config(lowCeiling)),
                                901))
                .isEqualByComparingTo("3000");
    }

    @Test
    void SRS_1_4_anUnsetNpaPercentCeilingOrPayCodeRefusesTheFlaggedMember() {
        AllowanceConfiguration.Allowances noPct =
                new AllowanceConfiguration.Allowances(
                        CONFIGURED.flagPayCodes(), null, new BigDecimal("9000"), List.of(), "a");
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member("IDA", "Y", Set.of("NPA"), null), config(noPct)))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("NPA_PCT");
        AllowanceConfiguration.Allowances noCeiling =
                new AllowanceConfiguration.Allowances(
                        CONFIGURED.flagPayCodes(), new BigDecimal("25"), null, List.of(), "b");
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member("IDA", "Y", Set.of("NPA"), null), config(noCeiling)))
                .hasMessageContaining("NPA_CEILING");
        AllowanceConfiguration.Allowances noCode =
                new AllowanceConfiguration.Allowances(
                        Map.of(), new BigDecimal("25"), new BigDecimal("9000"), List.of(), "c");
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member("IDA", "Y", Set.of("NPA"), null), config(noCode)))
                .hasMessageContaining("ELIGIBILITY_FLAG");
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member("IDA", "Y", Set.of("NPA"), null),
                                        config(AllowanceConfiguration.Allowances.none())))
                .isInstanceOf(PayDataException.class);
    }

    @Test
    void SRS_1_4_transportIsTheMonthlyAmountForTheCityClassTimesOnePlusDa() {
        PayRules.Outcome x =
                PayRules.compute(member("IDA", "X", Set.of("TRANSPORT"), null), config(CONFIGURED));
        assertThat(amount(x, 902)).isEqualByComparingTo("5400"); // 3600 x 1.5
        PayRules.Outcome y =
                PayRules.compute(member("IDA", "Y", Set.of("TRANSPORT"), null), config(CONFIGURED));
        assertThat(amount(y, 902)).as("the line for every class").isEqualByComparingTo("2700");
        PayRules.Outcome fnd =
                PayRules.compute(member("FND", "Y", Set.of("TRANSPORT"), null), config(CONFIGURED));
        assertThat(amount(fnd, 902)).as("FND follows CDA").isEqualByComparingTo("1890"); // x 1.4
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member("IDA", "X", Set.of("TRANSPORT"), null),
                                        config(
                                                new AllowanceConfiguration.Allowances(
                                                        CONFIGURED.flagPayCodes(),
                                                        null,
                                                        null,
                                                        List.of(),
                                                        "t"))))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("Transport rate not configured");
    }

    @Test
    void SRS_1_4_aFlaggedAllowanceWithoutARuleRefusesTheMemberNeverPaysZero() {
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member("IDA", "X", Set.of("CCA", "WASHING"), null),
                                        config(CONFIGURED)))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("WASHING");
    }

    // BR-EMPM-010: a hold flag suppresses payment with its reason; BR-EMPR-011: withheld is
    // computed and held.
    @Test
    void SRS_1_7_aHeldPaymentIsComputedAndCarriesItsReason() {
        PayRules.Outcome held =
                PayRules.compute(
                        member("IDA", "X", Set.of("CCA"), "STOP_PAYMENT_FLAG"), config(CONFIGURED));
        assertThat(held.status()).isEqualTo(PayRules.COMPUTED);
        assertThat(held.paymentHold()).isEqualTo("STOP_PAYMENT_FLAG");
        assertThat(held.gross()).isEqualByComparingTo("37800");
        assertThat(
                        PayRules.compute(member("IDA", "X", Set.of(), null), config(CONFIGURED))
                                .paymentHold())
                .isNull();
    }

    @Test
    void SRS_1_4_transportLinesThatAreAmbiguousOrMissingAnswerNothing() {
        AllowanceConfiguration.Allowances two =
                new AllowanceConfiguration.Allowances(
                        Map.of(),
                        null,
                        null,
                        List.of(
                                new TransportRate("IDA", null, BigDecimal.ONE),
                                new TransportRate("IDA", null, BigDecimal.TEN)),
                        "x");
        assertThat(two.transport("IDA", "X")).isEmpty();
        assertThat(CONFIGURED.transport("CDA", "X")).contains(new BigDecimal("1350"));
        assertThat(CONFIGURED.transport("IDA", "X")).contains(new BigDecimal("3600"));
        assertThat(AllowanceConfiguration.Allowances.none().transport("IDA", "X")).isEmpty();
        assertThat(AllowanceConfiguration.Allowances.none().payCode("NPA")).isEmpty();
        assertThat(new AllowanceConfiguration.Allowances(null, null, null, null, "").flagPayCodes())
                .isEmpty();
        assertThat(config(null).allowances().hash()).isEmpty();
    }
}
