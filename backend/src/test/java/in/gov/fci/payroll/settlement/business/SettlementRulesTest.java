package in.gov.fci.payroll.settlement.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.settlement.business.SettlementRules.Stage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plan 11: the Full & Final Settlement rules (SRS-10-5, 10-8, 10-13, 10-14, 10-15, 10-18, 10-23).
 */
class SettlementRulesTest {

    // SC-11-0018: one case per separation whose stage moves only forward on its completing event.
    @Test
    void SRS_10_8_SRS_10_18_BR_FNF_012_SRS_DM_7_theStagesFollowTheUc06StepsAndOnlyMoveForward() {
        assertThat(Stage.of(1)).isEqualTo(Stage.SEPARATION_RECEIVED);
        assertThat(Stage.of(8)).isEqualTo(Stage.CLOSED);
        assertThat(Stage.APPROVED.number()).isEqualTo(6);
        assertThatThrownBy(() -> Stage.of(9)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SettlementRules.mayMove(Stage.CLEARANCES_RAISED, Stage.COMPUTED)).isTrue();
        assertThat(SettlementRules.mayMove(Stage.COMPUTED, Stage.COMPUTED)).isTrue();
        assertThat(SettlementRules.mayMove(Stage.APPROVED, Stage.COMPUTED)).isFalse();
        assertThat(SettlementRules.CLEARANCES).hasSize(5).contains("CPF_FINAL_STATUS");
    }

    @Test
    void SRS_10_14_anOutstandingClearanceHoldsTheCaseUnlessExcluded() {
        assertThat(
                        SettlementRules.clearancesSettled(
                                List.of("RECEIVED", "NOT_APPLICABLE", "EXCLUDED")))
                .isTrue();
        assertThat(SettlementRules.clearancesSettled(List.of("RECEIVED", "RAISED"))).isFalse();
        assertThat(SettlementRules.clearancesSettled(List.of("PENDING"))).isFalse();
    }

    @Test
    void SRS_10_13_BR_FNF_001_BR_FNF_005_theNetPayableExcludesGratuityAndANegativeNetIsARecovery() {
        BigDecimal net =
                SettlementRules.netPayable(
                        new BigDecimal("41000"),
                        new BigDecimal("841500"),
                        new BigDecimal("1200"),
                        null,
                        new BigDecimal("150000"));
        assertThat(net).isEqualByComparingTo("733700");
        assertThat(SettlementRules.isNetRecovery(net)).isFalse();
        BigDecimal owed =
                SettlementRules.netPayable(
                        BigDecimal.TEN, null, null, BigDecimal.ONE, new BigDecimal("20"));
        assertThat(owed).isEqualByComparingTo("-9");
        assertThat(SettlementRules.isNetRecovery(owed)).as("SRS-10-23").isTrue();
        assertThat(SettlementRules.isNetRecovery(BigDecimal.ZERO)).isFalse();
    }

    @Test
    void
            SRS_10_15_BR_FNF_008_gratuityIs15Over26PerYearWithAPartYearOverSixMonthsCountedAndTheCeiling() {
        Optional<BigDecimal> ceiling = Optional.of(new BigDecimal("2000000"));
        BigDecimal pay = new BigDecimal("84150");
        assertThat(SettlementRules.gratuity(pay, Period.of(33, 7, 0), ceiling))
                .contains(new BigDecimal("1650635"));
        assertThat(SettlementRules.gratuity(pay, Period.of(33, 6, 0), ceiling))
                .as("six months is not more than six")
                .contains(new BigDecimal("1602087"));
        assertThat(SettlementRules.gratuity(pay, Period.of(45, 0, 0), ceiling))
                .as("at most the ceiling")
                .contains(new BigDecimal("2000000"));
    }

    // Deep review DR-4: the part-year is judged with its days.
    @Test
    void SRS_10_15_BR_FNF_008_sixMonthsAndADayIsMoreThanSixMonths() {
        Optional<BigDecimal> ceiling = Optional.of(new BigDecimal("2000000"));
        BigDecimal pay = new BigDecimal("84150");
        assertThat(SettlementRules.gratuity(pay, Period.of(33, 6, 1), ceiling))
                .contains(new BigDecimal("1650635"));
        assertThat(SettlementRules.gratuity(pay, Period.of(33, 6, 10), ceiling))
                .contains(new BigDecimal("1650635"));
        assertThat(
                        SettlementRules.gratuity(
                                pay,
                                Period.between(LocalDate.of(1993, 4, 1), LocalDate.of(2026, 10, 2)),
                                ceiling))
                .as("33 years, 6 months and 1 day of service")
                .contains(new BigDecimal("1650635"));
        assertThat(
                        SettlementRules.gratuity(
                                pay,
                                Period.between(LocalDate.of(1993, 4, 1), LocalDate.of(2026, 10, 1)),
                                ceiling))
                .as("33 years and 6 months exactly")
                .contains(new BigDecimal("1602087"));
    }

    @Test
    void SRS_10_14_gratuityIsNeverDefaultedWithoutServiceOrCeiling() {
        Optional<BigDecimal> ceiling = Optional.of(BigDecimal.TEN);
        assertThat(SettlementRules.gratuity(BigDecimal.ONE, null, ceiling)).isEmpty();
        assertThat(SettlementRules.gratuity(null, Period.ofYears(1), ceiling)).isEmpty();
        assertThat(SettlementRules.gratuity(BigDecimal.ONE, Period.ofYears(1), Optional.empty()))
                .isEmpty();
        assertThat(SettlementRules.gratuity(BigDecimal.ONE, Period.of(-1, 0, 0), ceiling))
                .isEmpty();
        assertThat(SettlementRules.gratuity(BigDecimal.ONE, Period.of(1, 12, 0), ceiling))
                .isEmpty();
        assertThat(SettlementRules.gratuity(BigDecimal.ONE, Period.of(1, 0, 31), ceiling))
                .isEmpty();
        assertThat(SettlementRules.gratuity(BigDecimal.ONE, Period.of(1, 0, -1), ceiling))
                .isEmpty();
    }

    @Test
    void SRS_10_5_settlementNeedsASeparationDateAndAMemberNoLongerInService() {
        LocalDate day = LocalDate.of(2026, 10, 31);
        assertThat(SettlementRules.settleable(day, "SEPARATED")).isEmpty();
        assertThat(SettlementRules.settleable(null, "SEPARATED")).contains("NO_SEPARATION_DATE");
        assertThat(SettlementRules.settleable(day, "IN_SERVICE")).contains("ACTIVE_MEMBER");
        assertThat(SettlementRules.caseReference(2026, 42)).isEqualTo("FNF-2026-00042");
    }

    /**
     * SRS-1-39 (US-GAP-279): an absconding member separated - or still absconding with a separation
     * date - is settled, so the pending recoveries are recovered at separation under SRS-10-7 /
     * SRS-10-21 (payroll dues, or gratuity with Headquarters approval, BR-FNF-011).
     */
    @Test
    void SRS_1_39_anAbscondingMembersRecoveriesAreSettledAtSeparation() {
        LocalDate day = LocalDate.of(2026, 10, 31);
        assertThat(SettlementRules.settleable(day, "ABSCONDING")).isEmpty();
        assertThat(SettlementRules.settleable(day, "DISMISSED")).isEmpty();
        assertThat(SettlementRules.settleable(null, "ABSCONDING")).contains("NO_SEPARATION_DATE");
    }

    @Test
    void SRS_10_15_noYearsAndElevenMonthsStillEarnAGratuity() {
        Optional<BigDecimal> ceiling = Optional.of(new BigDecimal("2000000"));
        assertThat(SettlementRules.gratuity(new BigDecimal("26000"), Period.ofMonths(11), ceiling))
                .contains(new BigDecimal("15000"));
        assertThat(SettlementRules.gratuity(new BigDecimal("26000"), Period.ofMonths(12), ceiling))
                .isEmpty();
        assertThat(SettlementRules.gratuity(new BigDecimal("26000"), Period.ofDays(30), ceiling))
                .as("under six months: no year counted")
                .contains(new BigDecimal("0"));
    }
}
