package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.labour.business.DpsWageRules.Basis;
import in.gov.fci.payroll.labour.business.DpsWageRules.Floor;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** DPS wage formulas (plan 21 phase 21d; SRS-4-2, SRS-4-3, SRS-4-4): exactly as stated. */
class DpsWageRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    // SC-21-0002, SC-21-0021: the floor and its basis, kept on the trace.
    @Test
    void SRS_4_2_theFloorIsTheHigherOfMgwPlusIrAndTheGoiRate() {
        assertThat(DpsWageRules.floor(d("781"), d("20"), d("790")))
                .isEqualTo(new Floor(d("801"), Basis.MGW_IR));
        assertThat(DpsWageRules.floor(d("781"), d("20"), d("820")))
                .isEqualTo(new Floor(d("820"), Basis.GOI));
        assertThat(DpsWageRules.floor(d("781"), d("19"), d("800")).basis())
                .as("a tie keeps MGW + IR")
                .isEqualTo(Basis.MGW_IR);
        assertThatThrownBy(() -> DpsWageRules.floor(d("781"), null, d("800")))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Interim Relief");
        assertThatThrownBy(() -> DpsWageRules.floor(null, d("1"), d("800")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DpsWageRules.floor(d("781"), d("1"), null))
                .isInstanceOf(NullPointerException.class);
    }

    // SC-21-0027: DPS Actual Wage only where it exceeds the floor.
    @Test
    void SRS_4_2_onlyTheEarnedWageAboveTheFloorIsDpsActualWage() {
        assertThat(DpsWageRules.actualWageAboveFloor(d("950.50"), d("801")))
                .isEqualByComparingTo("149.50");
        assertThat(DpsWageRules.actualWageAboveFloor(d("801"), d("801"))).isEqualByComparingTo("0");
        assertThat(DpsWageRules.actualWageAboveFloor(d("700"), d("801"))).isEqualByComparingTo("0");
    }

    /**
     * SRS-4-2 boundary: an earned wage exactly at the floor has no excess, so pay code 190 is the
     * plain zero ("else 0"), not the 0.00 left over from the subtraction.
     */
    @Test
    void SRS_4_2_anEarnedWageExactlyAtTheFloorGivesAPlainZero() {
        assertThat(DpsWageRules.actualWageAboveFloor(d("801.00"), d("801.00")))
                .isEqualTo(BigDecimal.ZERO);
        assertThat(DpsWageRules.actualWageAboveFloor(d("801.01"), d("801.00")))
                .isEqualTo(d("0.01"));
    }

    // SC-21-0003
    @Test
    void SRS_4_3_belowTheMgwTheOtaHourlyRateIsMgwOverSeven() {
        assertThat(DpsWageRules.insufficientWorkOtaRate(d("781"), d("500")))
                .isEqualByComparingTo(
                        d("781").divide(BigDecimal.valueOf(7), java.math.MathContext.DECIMAL64));
        assertThat(DpsWageRules.insufficientWorkOtaRate(d("700"), d("699.99")))
                .isEqualByComparingTo("100");
        assertThat(DpsWageRules.insufficientWorkOtaRate(d("781"), d("781"))).isNull();
        assertThat(DpsWageRules.insufficientWorkOtaRate(d("781"), d("900"))).isNull();
    }

    // SC-21-0004
    @Test
    void SRS_4_4_SRS_12_2_BR_LOAN_002_theAdvanceCapIsMgwTimesThirtyTimesTheMonthsAllowed() {
        assertThat(DpsWageRules.advanceCap(d("781"), 3)).isEqualByComparingTo("70290");
        assertThat(DpsWageRules.advanceCap(d("781"), 0)).isEqualByComparingTo("0");
        assertThatThrownBy(() -> DpsWageRules.advanceCap(d("781"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
