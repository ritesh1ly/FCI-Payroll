package in.gov.fci.payroll.leave.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Plan 13 phase 13d: the leave encashment arrear (SRS-8-23; BR-ARR-LE-001 to 003). */
class LeaveArrearRulesTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 7, 1);

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    @Test
    void SRS_8_23_theRevisionReachesAnEncashmentDatedOnOrAfterItsEffectiveDate() {
        assertThat(LeaveArrearRules.affected(EFFECTIVE, EFFECTIVE)).isTrue();
        assertThat(LeaveArrearRules.affected(EFFECTIVE, EFFECTIVE.plusDays(9))).isTrue();
        assertThat(LeaveArrearRules.affected(EFFECTIVE, EFFECTIVE.minusDays(1))).isFalse();
    }

    @Test
    void SRS_8_23_aMemberSeparatedBeforeTheRevisionIsNotEligible() {
        assertThat(LeaveArrearRules.ineligible(EFFECTIVE, null)).isEmpty();
        assertThat(LeaveArrearRules.ineligible(EFFECTIVE, EFFECTIVE)).as("on the date").isEmpty();
        assertThat(LeaveArrearRules.ineligible(EFFECTIVE, EFFECTIVE.minusDays(1)))
                .hasValueSatisfying(r -> assertThat(r).contains("before the revision"));
    }

    @Test
    void BR_ARR_LE_001_dueIsDaysTimesRevisedPayOverTheDivisorAndOnlyAFurtherDifferenceIsPaid() {
        BigDecimal due =
                LeaveArrearRules.due(
                        new BigDecimal("10"), new BigDecimal("62400"), new BigDecimal("30"));
        assertThat(due).isEqualByComparingTo("20800");
        assertThat(LeaveArrearRules.arrear(due, new BigDecimal("20000")))
                .hasValueSatisfying(a -> assertThat(a).isEqualByComparingTo("800"));
        assertThat(LeaveArrearRules.arrear(due, new BigDecimal("20800")))
                .as("BR-ARR-LE-003: never twice")
                .isEmpty();
        assertThat(LeaveArrearRules.arrear(due, new BigDecimal("21000"))).isEmpty();
    }

    @Test
    void BR_ARR_LE_003_aDifferenceOfOneRupeeIsPaidAndRoundingIsOnce() {
        assertThat(LeaveArrearRules.arrear(n("101"), n("100")))
                .hasValueSatisfying(a -> assertThat(a).isEqualByComparingTo("1"));
        assertThat(LeaveArrearRules.due(n("1"), n("100"), n("30"))).isEqualByComparingTo("3");
        assertThat(LeaveArrearRules.ineligible(EFFECTIVE, EFFECTIVE.plusDays(1))).isEmpty();
    }
}
