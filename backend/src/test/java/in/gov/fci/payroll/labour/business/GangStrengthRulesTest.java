package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.labour.business.GangStrengthRules.Check;
import in.gov.fci.payroll.labour.business.GangStrengthRules.Member;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Gang Strength check (SRS-2-48 l.4403; I-01 l.4545; BR-LABM-011): present strength from the
 * day's marks against the Sanctioned Strength in force; the pool (no strength) is not checked.
 */
class GangStrengthRulesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 14);

    private static Member at(String code, String mark, String weight) {
        return new Member(code, mark, weight == null ? null : new BigDecimal(weight));
    }

    @Test
    void SRS_2_48_presentBelowSanctionedIsAShortfallNamingTheAbsent() {
        Check c =
                GangStrengthRules.check(
                        4,
                        List.of(
                                at("L1", "W", "1"),
                                at("L2", "A", "0"),
                                at("L3", null, null),
                                at("L4", "F", "0.5")));
        assertThat(c.present()).as("the half day F counts as present").isEqualTo(2);
        assertThat(c.shortBy()).isEqualTo(2);
        assertThat(c.shortfall()).isTrue();
        assertThat(c.absent()).containsExactly("L2", "L3");
        assertThat(GangStrengthRules.detail("Okhla Gang 1", DAY, c))
                .isEqualTo(
                        "Gang Okhla Gang 1 on 2026-09-14: present 2 of Sanctioned Strength 4, short"
                                + " 2; absent: L2, L3 (SRS-2-48)");
    }

    @Test
    void SRS_2_48_equalOrAboveTheSanctionedStrengthIsNoShortfall() {
        Check equal = GangStrengthRules.check(2, List.of(at("L1", "W", "1"), at("L2", "D", "1")));
        assertThat(equal.shortfall()).isFalse();
        assertThat(equal.shortBy()).isZero();
        assertThat(equal.absent()).isEmpty();
        Check above =
                GangStrengthRules.check(
                        1, List.of(at("L1", "W", "1"), at("L2", "W", "1"), at("L3", "A", "0")));
        assertThat(above.shortBy()).as("never negative").isZero();
        assertThat(above.present()).isEqualTo(2);
        assertThat(GangStrengthRules.detail("G", DAY, equal)).doesNotContain("absent");
        Check one = GangStrengthRules.check(2, List.of(at("L1", "W", "1")));
        assertThat(one.shortBy()).as("one below").isEqualTo(1);
    }

    @Test
    void SRS_2_48_thePoolWithoutStrengthIsNotCheckedAndAMarkNeedsAPresentWeight() {
        assertThat(GangStrengthRules.check(null, List.of(at("L1", "A", "0")))).isNull();
        assertThat(GangStrengthRules.present(at("L1", "W", null))).isFalse();
        assertThat(GangStrengthRules.present(at("L1", null, "1"))).isFalse();
        assertThat(GangStrengthRules.present(at("L1", "W", "1"))).isTrue();
        assertThat(GangStrengthRules.check(3, List.of()).shortBy()).as("nobody").isEqualTo(3);
        assertThat(GangStrengthRules.key(5L, DAY)).isEqualTo("gang 5 2026-09-14");
    }

    @Test
    void SRS_2_48_aLongListOfAbsentMembersIsCutToTheDetailColumn() {
        List<Member> many =
                java.util.stream.IntStream.range(0, 400)
                        .mapToObj(i -> at("L" + (10_000 + i), "A", "0"))
                        .toList();
        String detail = GangStrengthRules.detail("G", DAY, GangStrengthRules.check(400, many));
        assertThat(detail).hasSize(1000).endsWith("...");
        String exact = GangStrengthRules.detail("G", DAY, GangStrengthRules.check(1, List.of()));
        assertThat(exact).endsWith("(SRS-2-48)");
    }

    /**
     * SRS-2-48: a detail of exactly the 1,000 characters of the column is kept whole; one more is
     * cut.
     */
    @Test
    void SRS_2_48_aDetailOfExactlyTheColumnIsKeptWholeAndOneMoreIsCut() {
        Check shortOne = GangStrengthRules.check(1, List.of());
        int frame = GangStrengthRules.detail("", DAY, shortOne).length();
        String exact = GangStrengthRules.detail("g".repeat(1000 - frame), DAY, shortOne);
        assertThat(exact).hasSize(1000).endsWith("(SRS-2-48)");
        String over = GangStrengthRules.detail("g".repeat(1001 - frame), DAY, shortOne);
        assertThat(over).hasSize(1000).endsWith("(SRS-2...");
    }
}
