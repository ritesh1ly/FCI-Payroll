package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.payroll.business.MdpRules.Status;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * SRS-15-2, SRS-15-19, SRS-G-37 and the SRS I-19 field table (plan 05 phase 05g): the window of the
 * period, a deduction-only amount, and when an entry may still change.
 */
class MdpRulesTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 28);
    private static final LocalDate TO = LocalDate.of(2026, 11, 10);

    @Test
    void SRS_15_2_entriesOnlyWhileTheWindowIsOpenBothEndsIncluded() {
        assertThat(MdpRules.windowOpen(FROM, FROM, TO)).isTrue();
        assertThat(MdpRules.windowOpen(TO, FROM, TO)).isTrue();
        assertThat(MdpRules.windowOpen(FROM.minusDays(1), FROM, TO)).isFalse();
        assertThat(MdpRules.windowOpen(TO.plusDays(1), FROM, TO)).isFalse();
        assertThat(MdpRules.windowOpen(FROM, null, TO)).isFalse();
        assertThat(MdpRules.windowOpen(FROM, FROM, null)).isFalse();
    }

    @Test
    void SRS_15_19_aDeductionOnlyAtMostTwoDecimalsWithinNumeric12And2() {
        assertThat(MdpRules.amountDefect(new BigDecimal("200.00"))).isEmpty();
        assertThat(MdpRules.amountDefect(new BigDecimal("0.01"))).isEmpty();
        assertThat(MdpRules.amountDefect(new BigDecimal("9999999999.99"))).isEmpty();
        assertThat(MdpRules.amountDefect(new BigDecimal("12.500"))).isEmpty();
        assertThat(MdpRules.amountDefect(null)).contains("NOT_A_DEDUCTION");
        assertThat(MdpRules.amountDefect(BigDecimal.ZERO)).contains("NOT_A_DEDUCTION");
        assertThat(MdpRules.amountDefect(new BigDecimal("-5"))).contains("NOT_A_DEDUCTION");
        assertThat(MdpRules.amountDefect(new BigDecimal("1.005"))).contains("TOO_PRECISE");
        assertThat(MdpRules.amountDefect(new BigDecimal("10000000000.00"))).contains("TOO_LARGE");
    }

    @Test
    void SRS_G_37_aCancelledOrPostedEntryIsFinalAndNothingChangesWhileADecisionWaits() {
        assertThat(MdpRules.changeDefect(Status.ENTERED, false)).isEmpty();
        assertThat(MdpRules.changeDefect(Status.AMENDED, false)).isEmpty();
        assertThat(MdpRules.changeDefect(Status.APPROVED, false)).isEmpty();
        assertThat(MdpRules.changeDefect(Status.APPROVED, true)).contains("PENDING");
        assertThat(MdpRules.changeDefect(Status.CANCELLED, false)).contains("CANCELLED");
        assertThat(MdpRules.changeDefect(Status.POSTED, true)).contains("POSTED");
    }
}
