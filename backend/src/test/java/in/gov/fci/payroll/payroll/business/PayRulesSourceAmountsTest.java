package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.api.AllowanceConfiguration;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The pay amounts the SRS states, worked by hand: SRS-1-3 and l.5410 (HRA and accommodation),
 * l.5409, l.5412 and l.720 (DA and NPA bases with Grade Pay), SRS-G-17 (never round an intermediate
 * to the rupee), SRS-G-19 (a final instalment is not rounded). Rates are fixtures, not FCI values.
 */
class PayRulesSourceAmountsTest {

    private static final int NPA_CODE = 901;

    private static final Map<Integer, Long> COMPONENTS =
            Map.of(101, 1L, 111, 2L, 121, 3L, 131, 4L, NPA_CODE, 5L, 700, 6L);

    private static PayConfiguration.Snapshot config(String daRate, String ceiling) {
        return new PayConfiguration.Snapshot(
                LocalDate.of(2026, 7, 1),
                Map.of("IDA", new BigDecimal(daRate)),
                Map.of("X", new BigDecimal("30")),
                Map.of(),
                true,
                COMPONENTS,
                Map.of(),
                "hash",
                new AllowanceConfiguration.Allowances(
                        Map.of("NPA", NPA_CODE),
                        new BigDecimal("25"),
                        new BigDecimal(ceiling),
                        List.of(),
                        "allowances"));
    }

    private static PayRules.Member member(
            String basic, String gradePay, Set<String> flags, boolean suppressed) {
        return new PayRules.Member(
                7L,
                new BigDecimal(basic),
                "IDA",
                "X",
                "IN_SERVICE",
                null,
                false,
                flags,
                null,
                gradePay == null ? null : new BigDecimal(gradePay),
                suppressed);
    }

    private static PayRules.Outcome compute(PayRules.Member m) {
        return PayRules.compute(m, config("50", "90000"));
    }

    private static BigDecimal amount(PayRules.Outcome o, int code) {
        return line(o, code).amount();
    }

    private static PayRules.Line line(PayRules.Outcome o, int code) {
        return o.lines().stream().filter(l -> l.payCode() == code).findFirst().orElseThrow();
    }

    /** SRS l.5410: the three accommodation kinds it names stop HRA even on a row flagged N. */
    @ParameterizedTest
    @CsvSource({
        "DEPARTMENTAL_QUARTERS,true,true",
        "DEPARTMENTAL_QUARTERS,false,true",
        "COMPANY_LEASE,false,true",
        "SPOUSE,false,true",
        "THIRD_PARTY,true,true",
        "SELF_LEASE,true,true",
        "THIRD_PARTY,false,false",
        "SELF_LEASE,false,false",
    })
    void SRS_1_3_theRecordDecidesWhetherHraStops(String type, boolean flag, boolean stops) {
        assertThat(PayRules.hraSuppressed(type, flag)).isEqualTo(stops);
    }

    /** No record: no source stops HRA, so the existing payment stands (PENDING REF-1). */
    @Test
    void SRS_1_3_noRecordStopsNothing() {
        assertThat(PayRules.hraSuppressed(null, false)).isFalse();
        // A flag with no record cannot arise from the read; it still stops nothing.
        assertThat(PayRules.hraSuppressed(null, true)).isFalse();
    }

    /** Basic 50,000, X 30 %: in quarters no HRA line and gross 75,000; with none 90,000. */
    @Test
    void SRS_1_3_aMemberInQuartersHasNoHraLineAndGrossFollows() {
        PayRules.Outcome quarters = compute(member("50000", null, Set.of(), true));
        assertThat(quarters.lines()).noneMatch(l -> l.payCode() == PayRules.HRA);
        assertThat(quarters.gross()).isEqualByComparingTo("75000");
        assertThat(quarters.net()).isEqualByComparingTo("75000");

        PayRules.Outcome noRecord = compute(member("50000", null, Set.of(), false));
        assertThat(amount(noRecord, PayRules.HRA)).isEqualByComparingTo("15000");
        assertThat(noRecord.gross()).isEqualByComparingTo("90000");
    }

    /** SRS-11-17: a lease flagged suppressed is not paid HRA; DA and Basic do not move. */
    @Test
    void SRS_11_17_aLeaseIsNeverPaidWithFullHra() {
        PayRules.Outcome lease = compute(member("62400", null, Set.of(), true));
        assertThat(lease.lines()).noneMatch(l -> l.payCode() == PayRules.HRA);
        assertThat(amount(lease, PayRules.BASIC)).isEqualByComparingTo("62400");
        assertThat(amount(lease, PayRules.DA)).isEqualByComparingTo("31200");
    }

    /** A rate not seeded still refuses a suppressed member: suppression is not a default. */
    @Test
    void R_RULE_aSuppressedMemberStillNeedsTheHraRateConfigured() {
        PayRules.Member wrongCity =
                new PayRules.Member(
                        7L,
                        new BigDecimal("50000"),
                        "IDA",
                        "Z",
                        "IN_SERVICE",
                        null,
                        false,
                        Set.of(),
                        null,
                        null,
                        true);
        assertThatThrownBy(() -> compute(wrongCity))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("HRA rate not seeded");
    }

    /** SRS l.5409: (50,000 + 4,600) x 50 % = 27,300; HRA stays on Basic, l.5410 names no more. */
    @Test
    void SRS_1_2_gradePayEntersTheDaBaseAndNotTheHraBase() {
        PayRules.Outcome o = compute(member("50000", "4600", Set.of(), false));
        assertThat(amount(o, PayRules.DA)).isEqualByComparingTo("27300");
        assertThat(line(o, PayRules.DA).basis()).isEqualByComparingTo("54600");
        assertThat(amount(o, PayRules.HRA)).isEqualByComparingTo("15000");
        assertThat(line(o, PayRules.HRA).basis()).isEqualByComparingTo("50000");
        // It is a base, not a paid line: no source gives a pay code (PENDING REF-2-CODE).
        assertThat(o.lines()).hasSize(3);
        assertThat(o.gross()).isEqualByComparingTo("92300");
    }

    /** Zero, empty and absent Grade Pay leave the Basic-only base: DA 25,000. */
    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {"0", "0.00", "null"})
    void SRS_1_2_zeroOrEmptyGradePayLeavesTheBase(String gradePay) {
        PayRules.Outcome o = compute(member("50000", gradePay, Set.of(), false));
        assertThat(amount(o, PayRules.DA)).isEqualByComparingTo("25000");
        assertThat(line(o, PayRules.DA).basis()).isEqualByComparingTo("50000");
    }

    @Test
    void SRS_1_2_aNegativeGradePayIsRefused() {
        assertThatThrownBy(() -> compute(member("50000", "-1", Set.of(), false)))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("Grade Pay");
    }

    /**
     * A doctor, SRS l.5412 and l.5409: NPA 25 % x (50,000 + 4,600) = 13,650, DA (54,600 + 13,650) x
     * 50 % = 34,125. Gross 50,000 + 34,125 + 13,650 + 15,000 = 1,12,775.
     */
    @Test
    void SRS_1_4_aDoctorsNpaIsOnBasicAndGradePayAndEntersTheDaBase() {
        PayRules.Outcome o = compute(member("50000", "4600", Set.of("NPA"), false));
        assertThat(amount(o, NPA_CODE)).isEqualByComparingTo("13650");
        assertThat(line(o, NPA_CODE).basis()).isEqualByComparingTo("54600");
        assertThat(amount(o, PayRules.DA)).isEqualByComparingTo("34125");
        assertThat(line(o, PayRules.DA).basis()).isEqualByComparingTo("68250");
        assertThat(o.gross()).isEqualByComparingTo("112775");
    }

    /** The ceiling caps NPA, and the capped amount is what enters the DA base. */
    @Test
    void SRS_1_4_theNpaCeilingCapsTheAmountThatEntersTheDaBase() {
        PayRules.Outcome o =
                PayRules.compute(
                        member("50000", "4600", Set.of("NPA"), false), config("50", "9000"));
        assertThat(amount(o, NPA_CODE)).isEqualByComparingTo("9000");
        assertThat(amount(o, PayRules.DA)).isEqualByComparingTo("31800");
    }

    /**
     * SRS-G-17: Basic 40,001, NPA 25 % = 10,000.25, DA 46.5 % of 50,001.25 = 23,250.58: 23,251 from
     * the unrounded NPA, 23,250 from the rupee of its line.
     */
    @Test
    void SRS_G_17_theRoundedNpaDoesNotReEnterTheDaBase() {
        PayRules.Outcome o =
                PayRules.compute(
                        member("40001", null, Set.of("NPA"), false), config("46.5", "90000"));
        assertThat(amount(o, NPA_CODE)).isEqualByComparingTo("10000");
        assertThat(line(o, PayRules.DA).basis()).isEqualByComparingTo("50001.25");
        assertThat(amount(o, PayRules.DA)).isEqualByComparingTo("23251");
    }

    /**
     * SRS-G-17: subsistence 50 % of 40,001 = 20,000.50; DA 50 % = 10,000.25: DA 10,000 on the
     * carried basis, 10,001 was the old result of DA on the rounded 20,001.
     */
    @Test
    void SRS_G_17_theRoundedSubsistenceDoesNotReEnterTheDaBase() {
        PayRules.Outcome o =
                compute(
                        new PayRules.Member(
                                7L,
                                new BigDecimal("40001"),
                                "IDA",
                                "X",
                                "SUSPENDED",
                                new BigDecimal("50"),
                                false,
                                Set.of(),
                                null));
        assertThat(amount(o, PayRules.BASIC)).isEqualByComparingTo("20001");
        assertThat(line(o, PayRules.DA).basis()).isEqualByComparingTo("20000.50");
        assertThat(amount(o, PayRules.DA)).isEqualByComparingTo("10000");
    }

    /**
     * OPEN-QUESTION REF-2-SUB: Grade Pay follows the subsistence basis: (25,000 + 2,300) x 50 %.
     */
    @Test
    void SRS_1_64_gradePayFollowsTheSubsistenceBasis() {
        PayRules.Outcome o =
                compute(
                        new PayRules.Member(
                                7L,
                                new BigDecimal("50000"),
                                "IDA",
                                "X",
                                "SUSPENDED",
                                new BigDecimal("50"),
                                false,
                                Set.of(),
                                null,
                                new BigDecimal("4600"),
                                false));
        assertThat(amount(o, PayRules.DA)).isEqualByComparingTo("13650");
    }

    /** A zero DA rate is a rate, not a gap: the DA line is ₹ 0 and NPA still stands. */
    @Test
    void SRS_1_2_aZeroDaRatePaysNoDaAndStillPaysNpa() {
        PayRules.Outcome o =
                PayRules.compute(
                        member("50000", "4600", Set.of("NPA"), false), config("0", "90000"));
        assertThat(amount(o, PayRules.DA)).isEqualByComparingTo("0");
        assertThat(amount(o, NPA_CODE)).isEqualByComparingTo("13650");
    }

    /**
     * A mid-month joiner: the rules take no joining date, the month is computed whole; only a leave
     * reduction moves it (PENDING CLR-49: no source gives the first month's proration).
     */
    @Test
    void CLR_49_aMidMonthJoinerIsComputedForTheWholeMonthAsBuilt() {
        PayRules.Outcome o = compute(member("50000", "4600", Set.of(), true));
        PayRules.Outcome cut =
                PayRules.reduced(
                        o,
                        List.of(
                                new PayRules.Reduction(
                                        PayRules.BASIC, new BigDecimal("16666.67"))));
        assertThat(amount(cut, PayRules.BASIC)).isEqualByComparingTo("33333");
        assertThat(cut.gross()).isEqualByComparingTo("60633");
    }

    /** A suppressed member has no HRA line, so a leave reduction of HRA has nothing to cut. */
    @Test
    void SRS_LV_15_aReductionOfSuppressedHraIsRefusedNotPaidBelowZero() {
        PayRules.Outcome o = compute(member("50000", null, Set.of(), true));
        assertThatThrownBy(
                        () ->
                                PayRules.reduced(
                                        o,
                                        List.of(
                                                new PayRules.Reduction(
                                                        PayRules.HRA, BigDecimal.TEN))))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("no earning of that pay code");
    }

    /**
     * SRS-G-19, R-07: penalty ₹ 1,000.50 over 3 ends on 334.50: deducted as scheduled, so the
     * member pays ₹ 1,000.50 and not ₹ 1,001, and the deduction equals what the ledger posts.
     */
    @Test
    void SRS_G_19_aFinalInstalmentKeepsItsPaise() {
        PayRules.Outcome o = compute(member("50000", null, Set.of(), false));
        PayRules.Outcome net =
                PayRules.withDeductions(
                        o,
                        List.of(
                                new PayRules.Deduction(
                                        700, new BigDecimal("334.50"), null, null, true)),
                        config("50", "90000"));
        assertThat(amount(net, 700)).isEqualByComparingTo("-334.50");
        assertThat(net.deductions()).isEqualByComparingTo("334.50");
        assertThat(net.net()).isEqualByComparingTo("89665.50");
    }

    /** SRS-G-17: every other deduction stays rounded once to the rupee, half up. */
    @Test
    void SRS_G_17_anOrdinaryDeductionIsRoundedToTheRupee() {
        PayRules.Outcome o = compute(member("50000", null, Set.of(), false));
        PayRules.Outcome net =
                PayRules.withDeductions(
                        o,
                        List.of(new PayRules.Deduction(700, new BigDecimal("334.50"), null, null)),
                        config("50", "90000"));
        assertThat(amount(net, 700)).isEqualByComparingTo("-335");
    }

    /** The reader's row reaches the rules: an accommodation record or a grade with a value. */
    @Test
    void SRS_1_3_theRowCarriesGradePayAndTheRecordToTheRules() {
        MemberReader.MemberRow row =
                new MemberReader.MemberRow(
                        7L,
                        9L,
                        new BigDecimal("50000"),
                        "IDA",
                        "X",
                        "IN_SERVICE",
                        null,
                        null,
                        null,
                        null,
                        true,
                        new BigDecimal("4600"),
                        "DEPARTMENTAL_QUARTERS",
                        false);
        assertThat(row.member().gradePay()).isEqualByComparingTo("4600");
        assertThat(row.member().hraSuppressed()).isTrue();

        MemberReader.MemberRow none =
                new MemberReader.MemberRow(
                        7L,
                        9L,
                        new BigDecimal("50000"),
                        "IDA",
                        "X",
                        "IN_SERVICE",
                        null,
                        null,
                        null,
                        null);
        assertThat(none.member().gradePay()).isEqualByComparingTo("0");
        assertThat(none.member().hraSuppressed()).isFalse();
    }
}
