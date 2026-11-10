package in.gov.fci.payroll.leave.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Plan 19 phase 19e: leave encashment (SRS-LV-16, LV-17, LV-21, LV-22, LV-4; BR-FNF-009). */
class EncashmentRulesTest {

    private static final BigDecimal TEN = BigDecimal.TEN;

    @Test
    void SRS_LV_21_anEncashmentAboveTheHrmsBalanceIsRefusedNamingTheBalance() {
        // BR-LEAVE-013: an encashment above the encashable balance is refused with the balance
        // shown.
        assertThat(
                        EncashmentRules.inServiceDefect(
                                "EL", true, new BigDecimal("12"), TEN, false, false))
                .contains("ABOVE_BALANCE 10");
        assertThat(EncashmentRules.inServiceDefect("EL", true, TEN, TEN, false, false)).isEmpty();
        assertThat(EncashmentRules.inServiceDefect("EL", true, TEN, null, false, false))
                .contains("NO_BALANCE");
    }

    @Test
    void SRS_LV_17_onlyAnEncashableTypeAndNeverDisabilityLeave() {
        // BR-LEAVE-009: only leave types HRMS marks encashable are encashed.
        assertThat(EncashmentRules.inServiceDefect("HPL", false, TEN, TEN, false, false))
                .contains("NOT_ENCASHABLE");
        assertThat(EncashmentRules.inServiceDefect("DL", true, TEN, TEN, false, false))
                .as("SRS-LV-4")
                .contains("DISABILITY_LEAVE");
        assertThat(EncashmentRules.inServiceDefect("EL", true, BigDecimal.ZERO, TEN, false, false))
                .contains("DAYS");
        assertThat(EncashmentRules.inServiceDefect("EL", true, null, TEN, false, false))
                .contains("DAYS");
    }

    @Test
    void SRS_LV_22_blockedUnderSuspensionUnlessTheOrderPermits() {
        assertThat(EncashmentRules.inServiceDefect("EL", true, TEN, TEN, true, false))
                .contains("SUSPENDED");
        assertThat(EncashmentRules.inServiceDefect("EL", true, TEN, TEN, true, true)).isEmpty();
    }

    @Test
    void SRS_LV_17_aRequestAboveALimitIsCappedAndTheCapShown() {
        // BR-LEAVE-009: in-service encashment is capped at the per-occasion and per-year limits,
        // the cap shown.
        EncashmentRules.Days within =
                EncashmentRules.inService(TEN, new BigDecimal("15"), new BigDecimal("30"));
        assertThat(within.days()).isEqualByComparingTo("10");
        assertThat(within.capped()).isFalse();
        EncashmentRules.Days occasion =
                EncashmentRules.inService(TEN, new BigDecimal("6"), new BigDecimal("30"));
        assertThat(occasion.days()).isEqualByComparingTo("6");
        assertThat(occasion.capped()).isTrue();
        EncashmentRules.Days year =
                EncashmentRules.inService(TEN, new BigDecimal("15"), new BigDecimal("4"));
        assertThat(year.days()).isEqualByComparingTo("4");
        EncashmentRules.Days used =
                EncashmentRules.inService(TEN, new BigDecimal("15"), new BigDecimal("-2"));
        assertThat(used.days()).isZero();
    }

    @Test
    void SRS_10_16_BR_FNF_009_atSeparationTheBalanceIsLimitedToTheCeiling() {
        // SRS-LV-31: the balance HRMS sent is valued at separation within the aggregate ceiling.
        EncashmentRules.Days over =
                EncashmentRules.atSeparation(new BigDecimal("320"), new BigDecimal("300"));
        assertThat(over.days()).isEqualByComparingTo("300");
        assertThat(over.capped()).isTrue();
        EncashmentRules.Days under =
                EncashmentRules.atSeparation(new BigDecimal("120.5"), new BigDecimal("300"));
        assertThat(under.days()).isEqualByComparingTo("120.5");
        assertThat(under.capped()).isFalse();
        assertThat(EncashmentRules.atSeparation(new BigDecimal("-1"), TEN).days()).isZero();
        // US-GAP-226: exactly at the ceiling is paid in full, one day over is cut to it.
        EncashmentRules.Days at =
                EncashmentRules.atSeparation(new BigDecimal("300"), new BigDecimal("300"));
        assertThat(at.days()).isEqualByComparingTo("300");
        assertThat(at.capped()).isFalse();
        EncashmentRules.Days oneOver =
                EncashmentRules.atSeparation(new BigDecimal("301"), new BigDecimal("300"));
        assertThat(oneOver.days()).isEqualByComparingTo("300");
        assertThat(oneOver.capped()).isTrue();
    }

    @Test
    void SRS_LV_16_theValueIsDaysTimesPayOverTheDivisorRoundedOnce() {
        // BR-LEAVE-008: value = encashed days x (Basic + DA) / divisor.
        assertThat(
                        EncashmentRules.value(
                                new BigDecimal("300"),
                                new BigDecimal("84150"),
                                new BigDecimal("30")))
                .isEqualByComparingTo("841500");
        assertThat(
                        EncashmentRules.value(
                                new BigDecimal("10.5"),
                                new BigDecimal("56123"),
                                new BigDecimal("30")))
                .isEqualByComparingTo("19643");
        assertThat(EncashmentRules.ratePerDay(new BigDecimal("56123"), new BigDecimal("30")))
                .isEqualByComparingTo("1870.77");
    }
}
