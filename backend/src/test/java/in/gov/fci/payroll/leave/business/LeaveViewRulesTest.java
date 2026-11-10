package in.gov.fci.payroll.leave.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** SRS I-11 Pay effect rule and "Applied in" (SRS-LV-33, BR-LEAVE-006, BR-LEAVE-010; US-GAP-59). */
class LeaveViewRulesTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final Instant APPROVED = Instant.parse("2026-10-20T06:00:00Z");

    @Test
    void BR_LEAVE_010_aBackDatedEventCitesTheRetrospectiveRuleWhateverItsEffect() {
        assertThat(LeaveViewRules.ruleOf("FULL_DEDUCTION", true)).isEqualTo("BR-LEAVE-010");
        assertThat(LeaveViewRules.ruleOf("NO_EFFECT", true)).isEqualTo("BR-LEAVE-010");
        assertThat(LeaveViewRules.ruleOf(null, true)).isEqualTo("BR-LEAVE-010");
    }

    @Test
    void BR_LEAVE_007_aDeductionOrHalfPayOfAnOpenMonthCitesTheLwpRule() {
        assertThat(LeaveViewRules.ruleOf("FULL_DEDUCTION", false)).isEqualTo("BR-LEAVE-007");
        assertThat(LeaveViewRules.ruleOf("HALF_PAY", false)).isEqualTo("BR-LEAVE-007");
        assertThat(LeaveViewRules.ruleOf("NO_EFFECT", false)).isNull();
        assertThat(LeaveViewRules.ruleOf("ALLOWANCE_RECOVERY", false)).isNull();
        assertThat(LeaveViewRules.ruleOf(null, false)).isNull();
    }

    @Test
    void BR_LEAVE_006_aBackDatedEventAppliesInTheApprovalMonthWhileItsCycleIsOpen() {
        assertThat(LeaveViewRules.appliedIn(OCTOBER, null, APPROVED)).isEqualTo(OCTOBER);
        // Locked only after the approval: the event was settled in that month.
        assertThat(LeaveViewRules.appliedIn(OCTOBER, APPROVED.plusSeconds(1), APPROVED))
                .isEqualTo(OCTOBER);
    }

    @Test
    void BR_LEAVE_006_aMonthLockedAtOrBeforeTheApprovalSendsTheEventToTheNextOpenCycle() {
        assertThat(LeaveViewRules.appliedIn(OCTOBER, APPROVED, APPROVED))
                .isEqualTo(YearMonth.of(2026, 11));
        assertThat(LeaveViewRules.appliedIn(OCTOBER, APPROVED.minusSeconds(60), APPROVED))
                .isEqualTo(YearMonth.of(2026, 11));
        assertThat(
                        LeaveViewRules.appliedIn(
                                YearMonth.of(2026, 12), APPROVED.minusSeconds(60), APPROVED))
                .isEqualTo(YearMonth.of(2027, 1));
    }
}
