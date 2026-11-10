package in.gov.fci.payroll.employee.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.employee.business.HrmsChangeRules.Route;
import in.gov.fci.payroll.employee.business.HrmsChangeRules.Status;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * I-21 HRMS Change Approval rules (plan 16 phase 16a): timing against the freeze (SRS-1-58), who
 * may submit (SRS-1-28), the order details (SRS-G-31), the route (SRS-1-61, SRS-1-23), the DOB
 * effects (SRS-1-22, SRS-1-24) and the masking of Restricted values (SRS-1-54, SRS-1-56).
 */
class HrmsChangeRulesTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    /**
     * UC I-21 l.3813 (US-GAP-276), from SRS-1-28 / SRS-1-58: a change effective before the month it
     * is paid in raises an arrear or recovery for the months from its effective month to the month
     * before; effective in that month, none; effective later, paid from its own month.
     */
    @Test
    void SRS_1_28_thePayImpactByMonth() {
        YearMonth nov = YearMonth.of(2026, 11);
        HrmsChangeRules.PayImpact back = HrmsChangeRules.payImpact(LocalDate.of(2026, 8, 15), nov);
        assertThat(back.appliesFrom()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(back.paidFrom()).isEqualTo(nov);
        assertThat(back.arrearFrom()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(back.arrearTo()).isEqualTo(YearMonth.of(2026, 10));
        HrmsChangeRules.PayImpact lastMonth =
                HrmsChangeRules.payImpact(LocalDate.of(2026, 10, 31), nov);
        assertThat(lastMonth.arrearFrom()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(lastMonth.arrearTo()).isEqualTo(YearMonth.of(2026, 10));
        HrmsChangeRules.PayImpact same = HrmsChangeRules.payImpact(LocalDate.of(2026, 11, 1), nov);
        assertThat(same.paidFrom()).isEqualTo(nov);
        assertThat(same.arrearFrom()).isNull();
        assertThat(same.arrearTo()).isNull();
        HrmsChangeRules.PayImpact later = HrmsChangeRules.payImpact(LocalDate.of(2027, 1, 1), nov);
        assertThat(later.appliesFrom()).isEqualTo(YearMonth.of(2027, 1));
        assertThat(later.paidFrom()).isEqualTo(YearMonth.of(2027, 1));
        assertThat(later.arrearFrom()).isNull();
        // Across the year end: a December change paid in January.
        HrmsChangeRules.PayImpact year =
                HrmsChangeRules.payImpact(LocalDate.of(2026, 12, 10), YearMonth.of(2027, 1));
        assertThat(year.arrearFrom()).isEqualTo(YearMonth.of(2026, 12));
        assertThat(year.arrearTo()).isEqualTo(YearMonth.of(2026, 12));
    }

    @Test
    void SRS_1_58_aChangeBeforeTheFreezeIsPaidThisMonthAndAfterItNextMonth() {
        assertThat(HrmsChangeRules.timing(OCT, null))
                .isEqualTo(new HrmsChangeRules.Timing(OCT, true));
        assertThat(HrmsChangeRules.timing(OCT, "INITIATED"))
                .isEqualTo(new HrmsChangeRules.Timing(OCT, true));
        for (String frozen : new String[] {"FROZEN", "COMPUTED", "VERIFIED", "LOCKED"}) {
            assertThat(HrmsChangeRules.timing(OCT, frozen))
                    .as(frozen)
                    .isEqualTo(new HrmsChangeRules.Timing(OCT.plusMonths(1), false));
        }
    }

    @Test
    void SRS_1_28_onlyATaskWaitingForItsOrderOrReturnedIsSubmittedAndPendingOnesHoldTheLock() {
        assertThat(HrmsChangeRules.submittable(Status.PENDING_ORDER_DETAILS)).isTrue();
        assertThat(HrmsChangeRules.submittable(Status.RETURNED_TO_MAKER)).isTrue();
        for (Status s :
                new Status[] {
                    Status.PENDING_CHECKER,
                    Status.APPROVED,
                    Status.AUTO_APPLIED,
                    Status.REJECTED_RETURNED_TO_HRMS,
                    Status.PENDING_HQ_ESTABLISHMENT
                }) {
            assertThat(HrmsChangeRules.submittable(s)).as(s.name()).isFalse();
        }
        assertThat(HrmsChangeRules.PENDING)
                .containsExactlyInAnyOrder(
                        Status.PENDING_ORDER_DETAILS,
                        Status.PENDING_CHECKER,
                        Status.PENDING_HQ_ESTABLISHMENT,
                        Status.RETURNED_TO_MAKER);
    }

    // SC-04-0041: the order number, date and issuing authority are recorded on the HRMS task.
    @Test
    void SRS_G_31_theOrderNamesNumberDateAndAuthorityOrIsHeldInHrms() {
        assertThat(HrmsChangeRules.orderDefect("ORD/1", TODAY, "GM (P)", false, TODAY)).isEmpty();
        assertThat(HrmsChangeRules.orderDefect(null, null, null, true, TODAY)).isEmpty();
        assertThat(HrmsChangeRules.orderDefect(" ", TODAY, "GM", false, TODAY)).contains("MISSING");
        assertThat(HrmsChangeRules.orderDefect("ORD/1", null, "GM", false, TODAY))
                .contains("MISSING");
        assertThat(HrmsChangeRules.orderDefect("ORD/1", TODAY, null, false, TODAY))
                .contains("MISSING");
        assertThat(HrmsChangeRules.orderDefect(null, TODAY, "GM", false, TODAY))
                .contains("MISSING");
        assertThat(HrmsChangeRules.orderDefect("x".repeat(60), TODAY, "GM", false, TODAY))
                .isEmpty();
        assertThat(HrmsChangeRules.orderDefect("x".repeat(61), TODAY, "GM", false, TODAY))
                .contains("TOO_LONG");
        assertThat(HrmsChangeRules.orderDefect("ORD", TODAY, "a".repeat(200), false, TODAY))
                .isEmpty();
        assertThat(HrmsChangeRules.orderDefect("ORD", TODAY, "a".repeat(201), false, TODAY))
                .contains("TOO_LONG");
        assertThat(HrmsChangeRules.orderDefect("ORD", TODAY.plusDays(1), "GM", false, TODAY))
                .contains("IN_FUTURE");
    }

    @Test
    void SRS_1_61_otpOnlyForBasicOrIncrementAndHqOnlyForAPastRetirement() {
        assertThat(HrmsChangeRules.route(true, false, false)).isEqualTo(Route.SUBMIT_OTP);
        assertThat(HrmsChangeRules.route(true, true, true)).isEqualTo(Route.SUBMIT_OTP);
        assertThat(HrmsChangeRules.route(false, true, true)).isEqualTo(Route.SUBMIT_HQ);
        assertThat(HrmsChangeRules.route(false, true, false)).isEqualTo(Route.SUBMIT);
        assertThat(HrmsChangeRules.route(false, false, true)).isEqualTo(Route.SUBMIT);
    }

    @Test
    void SRS_1_22_theRetirementDateEndsTheMonthTheAgeIsReachedAndEpsStopsAt58() {
        assertThat(HrmsChangeRules.retirementDate(LocalDate.of(1966, 8, 14), 60))
                .isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(HrmsChangeRules.retirementDate(LocalDate.of(1966, 8, 1), 60))
                .isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(HrmsChangeRules.retirementDate(LocalDate.of(1966, 2, 28), 60))
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(HrmsChangeRules.epsCutoff(LocalDate.of(1968, 3, 9), 58))
                .isEqualTo(LocalDate.of(2026, 3, 9));
    }

    @Test
    void SRS_1_24_aRetirementThisOrNextMonthAlertsBeforeTheLock() {
        assertThat(HrmsChangeRules.retiresSoon(LocalDate.of(2026, 10, 31), OCT)).isTrue();
        assertThat(HrmsChangeRules.retiresSoon(LocalDate.of(2026, 11, 30), OCT)).isTrue();
        assertThat(HrmsChangeRules.retiresSoon(LocalDate.of(2026, 12, 31), OCT)).isFalse();
        assertThat(HrmsChangeRules.retiresSoon(LocalDate.of(2026, 9, 30), OCT)).isFalse();
        assertThat(HrmsChangeRules.taskNo(2026, 7)).isEqualTo("HCA-2026-0007");
        assertThat(HrmsChangeRules.taskNo(2026, 12345)).isEqualTo("HCA-2026-12345");
    }

    @Test
    void SRS_1_56_panBankAccountAndAadhaarAreShownMasked() {
        // SRS l.754 / l.802 (BMAD TR-035): the last four characters only.
        assertThat(HrmsChangeRules.mask("PAN", "ABCDE1234K")).isEqualTo("******234K");
        assertThat(HrmsChangeRules.mask("PAN", "")).isEqualTo("");
        assertThat(HrmsChangeRules.mask("PAN", "SHORT")).isEqualTo("****");
        assertThat(HrmsChangeRules.mask("BANK_ACCOUNT", "123456789012")).isEqualTo("********9012");
        assertThat(HrmsChangeRules.mask("AADHAAR", "234567890123")).isEqualTo("********0123");
        assertThat(HrmsChangeRules.mask("AADHAAR", "1234")).isEqualTo("****");
        assertThat(HrmsChangeRules.mask("UAN", "100234567890")).isEqualTo("100234567890");
        assertThat(HrmsChangeRules.mask("PAN", null)).isNull();
    }

    /** SRS-1-56: an Aadhaar or bank account sent blank is shown as received, nothing to mask. */
    @Test
    void SRS_1_56_aBlankAadhaarOrBankAccountIsShownAsReceived() {
        assertThat(HrmsChangeRules.mask("AADHAAR", "   ")).isEqualTo("   ");
        assertThat(HrmsChangeRules.mask("BANK_ACCOUNT", "")).isEqualTo("");
    }

    @Test
    void SRS_1_68_aPendingTaskIsOverdueOnlyPastTheSetDays() {
        assertThat(HrmsChangeRules.overdue(true, 8, OptionalLong.of(7))).isTrue();
        assertThat(HrmsChangeRules.overdue(true, 7, OptionalLong.of(7))).as("on the day").isFalse();
        assertThat(HrmsChangeRules.overdue(false, 30, OptionalLong.of(7))).as("decided").isFalse();
        assertThat(HrmsChangeRules.overdue(true, 30, OptionalLong.empty()))
                .as("not configured")
                .isFalse();
    }

    /** I-21 Reason for Change (SRS l.3850; OD-71): held as HRMS sent it, within its limits. */
    @Test
    void I_21_theHrmsReasonIsHeldStrippedAsciiAndWithinFiveHundred() {
        assertThat(HrmsChangeRules.reason(null)).isNull();
        assertThat(HrmsChangeRules.reason("")).isNull();
        assertThat(HrmsChangeRules.reason("   ")).isNull();
        assertThat(HrmsChangeRules.reason("  Promotion order 12  "))
                .isEqualTo("Promotion order 12");
        // A character outside printable ASCII (a Hindi letter, a tab) is shown as '?'.
        assertThat(HrmsChangeRules.reason("Order \u0905 1\t2")).isEqualTo("Order ? 1?2");
        // The ends of printable ASCII: a space and '~' (0x7E) are kept, DEL (0x7F) is not.
        assertThat(HrmsChangeRules.reason("Order ~12 \u007F3")).isEqualTo("Order ~12 ?3");
        assertThat(HrmsChangeRules.reason("a".repeat(500))).hasSize(500);
        assertThat(HrmsChangeRules.reason("a".repeat(499))).hasSize(499);
        assertThat(HrmsChangeRules.reason("b".repeat(501))).isEqualTo("b".repeat(500));
    }
}
