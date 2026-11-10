package in.gov.fci.payroll.leave.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.payroll.api.StaffDeductions;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plan 19 phase 19d (SRS-LV-15, SRS-LV-22, SRS-LV-27; D19-4; plan 13 D13-6; US-GAP-298): the
 * month's leave reduction - each component M-07 marks subject to LWP reduced by amount / divisor x
 * days lost, a member without leave untouched, a leave without a configured pay effect, a missing
 * divisor or component list, or more days than the month refusing the member (never a zero
 * deduction).
 */
class LeavePayReductionTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final YearMonth MONTH = YearMonth.from(DAY);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LeaveParameters parameters = mock(LeaveParameters.class);
    private final LeavePayReduction reduction = new LeavePayReduction(jdbc, parameters);

    LeavePayReductionTest() {
        rolls(LocalDate.of(2010, 7, 1), null);
    }

    /** The member's joining and separation dates (SRS-LV-22: the days on the rolls). */
    private void rolls(LocalDate joining, LocalDate separation) {
        Map<String, Object> row = new HashMap<>();
        row.put("COMP_JOINING_DATE", Date.valueOf(joining));
        row.put("SEPARATION_DATE", separation == null ? null : Date.valueOf(separation));
        when(jdbc.queryForMap(contains("FROM employee"), eq(7L))).thenReturn(row);
    }

    private void configured(String divisor, String components) {
        when(parameters.text(eq(LeaveParameters.LWP_DIVISOR), isNull(), eq(DAY)))
                .thenReturn(Optional.ofNullable(divisor));
        when(parameters.number(eq(LeaveParameters.LWP_DIVISOR), isNull(), eq(DAY)))
                .thenReturn(Optional.empty());
        // US-GAP-298: the components subject to LWP are the codes M-07 marks.
        when(jdbc.queryForList(contains("lwp_subject_flag"), eq(Integer.class), any()))
                .thenReturn(
                        components == null
                                ? List.of()
                                : java.util.Arrays.stream(components.split("[+]"))
                                        .map(Integer::valueOf)
                                        .toList());
    }

    private void leave(LocalDate from, LocalDate to, String days, String effect, String type) {
        Map<String, Object> row = new HashMap<>();
        row.put("PERIOD_FROM", Date.valueOf(from));
        row.put("PERIOD_TO", Date.valueOf(to));
        row.put("DAYS", new BigDecimal(days));
        row.put("WAGE_EFFECT_CODE", effect);
        row.put("LEAVE_EVENT_TYPE", type);
        when(jdbc.queryForList(anyString(), eq(7L), any(), any())).thenReturn(List.of(row));
    }

    private static StaffDeductions.Member member() {
        return new StaffDeductions.Member(
                7L,
                1L,
                "IDA",
                MONTH,
                List.of(
                        new StaffDeductions.Earning(101, new BigDecimal("60000")),
                        new StaffDeductions.Earning(111, new BigDecimal("30000")),
                        new StaffDeductions.Earning(121, new BigDecimal("15000")),
                        new StaffDeductions.Earning(131, BigDecimal.ZERO)),
                new BigDecimal("105000"));
    }

    @Test
    void SRS_LV_15_eachListedComponentIsReducedByTheDaysLost() {
        configured("CALENDAR_DAYS", "101+111+131");
        leave(DAY.plusDays(2), DAY.plusDays(4), "3", "FULL_DEDUCTION", "ADDITION");
        StaffDeductions.Run run = reduction.open(DAY);
        assertThat(reduction.order()).isEqualTo(5);
        assertThat(run.versions()).isEqualTo("lwp=30;components=[101, 111, 131]");
        assertThat(run.deductions(member())).isEmpty();
        List<StaffDeductions.Reduction> out = run.reductions(member());
        assertThat(out)
                .as("HRA (121) is not listed; a zero component is not reduced")
                .extracting(StaffDeductions.Reduction::payCode)
                .containsExactly(101, 111);
        assertThat(out.get(0).amount()).isEqualByComparingTo("6000");
        assertThat(out.get(1).amount()).isEqualByComparingTo("3000");
    }

    @Test
    void SRS_LV_15_aMemberWithoutLeaveOrWithALeaveOfNoEffectIsNotReduced() {
        configured("CALENDAR_DAYS", "101");
        when(jdbc.queryForList(anyString(), eq(7L), any(), any())).thenReturn(List.of());
        assertThat(reduction.open(DAY).reductions(member())).isEmpty();
        leave(DAY, DAY, "1", "NO_EFFECT", "ADDITION");
        assertThat(reduction.open(DAY).reductions(member())).as("no days lost").isEmpty();
    }

    @Test
    void D19_3_aLeaveWithoutAPayEffectOrMissingParametersRefuseTheMember() {
        configured(null, null);
        StaffDeductions.Run unset = reduction.open(DAY);
        assertThat(unset.versions()).isEqualTo("lwp=-;components=-");
        leave(DAY, DAY, "1", "HELD_UNDEFINED", "ADDITION");
        assertThatThrownBy(() -> unset.reductions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("pay effect");
        leave(DAY, DAY, "1", "FULL_DEDUCTION", "ADDITION");
        assertThatThrownBy(() -> unset.reductions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("divisor");
        configured("CALENDAR_DAYS", null);
        assertThatThrownBy(() -> reduction.open(DAY).reductions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("LWP");
    }

    @Test
    void SRS_LV_22_moreDaysLostThanTheMonthHasRefuseTheMember() {
        configured("CALENDAR_DAYS", "101");
        Map<String, Object> a = new HashMap<>();
        a.put("PERIOD_FROM", Date.valueOf(DAY));
        a.put("PERIOD_TO", Date.valueOf(MONTH.atEndOfMonth()));
        a.put("DAYS", new BigDecimal("30"));
        a.put("WAGE_EFFECT_CODE", "FULL_DEDUCTION");
        a.put("LEAVE_EVENT_TYPE", "ADDITION");
        Map<String, Object> b = new HashMap<>(a);
        when(jdbc.queryForList(anyString(), eq(7L), any(), any())).thenReturn(List.of(a, b));
        assertThatThrownBy(() -> reduction.open(DAY).reductions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class);
    }

    @Test
    void SRS_LV_22_halfADayOfLwpAndExactlyTheDaysOfTheMonthOrOnTheRolls() {
        // US-GAP-308 (SRS I-11 Days l.4199, FRS l.7619): a 0.5-day LWP reduces half a day's pay.
        configured("CALENDAR_DAYS", "101");
        leave(DAY.plusDays(4), DAY.plusDays(4), "0.5", "FULL_DEDUCTION", "ADDITION");
        List<StaffDeductions.Reduction> half = reduction.open(DAY).reductions(member());
        assertThat(half).extracting(StaffDeductions.Reduction::payCode).containsExactly(101);
        assertThat(half.get(0).amount())
                .as("60000 / 30 calendar days x 0.5")
                .isEqualByComparingTo("1000");
        // LWP of every day of September (30) is within the month; the rule is "cannot exceed".
        leave(DAY, MONTH.atEndOfMonth(), "30", "FULL_DEDUCTION", "ADDITION");
        assertThat(reduction.open(DAY).reductions(member()).get(0).amount())
                .isEqualByComparingTo("60000");
        // Joined on 20 September: exactly the 11 days on the rolls is within, 11.5 is not.
        rolls(LocalDate.of(2026, 9, 20), null);
        leave(DAY.plusDays(19), MONTH.atEndOfMonth(), "11", "FULL_DEDUCTION", "ADDITION");
        assertThat(reduction.open(DAY).reductions(member())).isNotEmpty();
        leave(DAY.plusDays(18), MONTH.atEndOfMonth(), "11.5", "FULL_DEDUCTION", "ADDITION");
        assertThatThrownBy(() -> reduction.open(DAY).reductions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("11 days the member was on the rolls");
    }

    @Test
    void BR_RULE_007_aClosedPeriodReadsTheParametersAndTheM07MarkingOfItsOwnDate() {
        // US-GAP-300 (SRS l.4161): a closed period is reproduced with the version that applied
        // then - the run reads the divisor and the components on the period's first day.
        LocalDate closed = LocalDate.of(2025, 4, 1);
        configured("CALENDAR_DAYS", "101+111");
        when(parameters.text(eq(LeaveParameters.LWP_DIVISOR), isNull(), eq(closed)))
                .thenReturn(Optional.empty());
        when(parameters.number(eq(LeaveParameters.LWP_DIVISOR), isNull(), eq(closed)))
                .thenReturn(Optional.of(new BigDecimal("26")));
        when(jdbc.queryForList(
                        contains("lwp_subject_flag"), eq(Integer.class), eq(Date.valueOf(closed))))
                .thenReturn(List.of(101));
        assertThat(reduction.open(closed).versions()).isEqualTo("lwp=26;components=[101]");
        assertThat(reduction.open(DAY).versions()).isEqualTo("lwp=30;components=[101, 111]");
    }

    /**
     * Wave D review 3 (BR-CYC-011..017): the codes subject to LWP are read by their end date only -
     * an M-07 revision rewrites the start date in place and a retirement marks the code retired
     * before its last day - so a re-run of an earlier month reads the same codes.
     */
    @Test
    void BR_CYC_011_theLwpCodesAreReadByTheirEndDateOnlySoAReRunIsTheSame() {
        configured("CALENDAR_DAYS", "101");
        reduction.open(DAY);
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc)
                .queryForList(sql.capture(), eq(Integer.class), eq(Date.valueOf(DAY)));
        assertThat(sql.getValue())
                .contains("lwp_subject_flag = 'Y'")
                .contains("effective_to IS NULL OR effective_to >= ?")
                .doesNotContain("effective_from")
                .doesNotContain("is_retired");
    }

    @Test
    void SRS_LV_15_aCancellationGivesTheDaysBack() {
        configured("CALENDAR_DAYS", "101");
        Map<String, Object> add = new HashMap<>();
        add.put("PERIOD_FROM", Date.valueOf(DAY));
        add.put("PERIOD_TO", Date.valueOf(DAY.plusDays(1)));
        add.put("DAYS", new BigDecimal("2"));
        add.put("WAGE_EFFECT_CODE", "FULL_DEDUCTION");
        add.put("LEAVE_EVENT_TYPE", "ADDITION");
        Map<String, Object> cancel = new HashMap<>(add);
        cancel.put("LEAVE_EVENT_TYPE", "CANCELLATION");
        when(jdbc.queryForList(anyString(), eq(7L), any(), any())).thenReturn(List.of(add, cancel));
        assertThat(reduction.open(DAY).reductions(member())).isEmpty();
    }

    @Test
    void SRS_LV_22_moreLwpDaysThanTheDaysOnTheRollsRefuseTheMember() {
        configured("CALENDAR_DAYS", "101");
        // Joined on 20 September: 11 days on the rolls; 12 days of LWP cannot be.
        rolls(LocalDate.of(2026, 9, 20), null);
        leave(DAY, DAY.plusDays(11), "12", "FULL_DEDUCTION", "ADDITION");
        StaffDeductions.Run run = reduction.open(DAY);
        assertThatThrownBy(() -> run.reductions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("11 days the member was on the rolls");
        // Separated on 10 September: the 10th is counted, so 10 days of LWP are within.
        rolls(LocalDate.of(2010, 7, 1), LocalDate.of(2026, 9, 10));
        leave(DAY, DAY.plusDays(9), "10", "FULL_DEDUCTION", "ADDITION");
        assertThat(reduction.open(DAY).reductions(member())).isNotEmpty();
    }

    /**
     * SRS-LV-15 (branch gap): a component so small that its reduction rounds to zero rupees gives
     * no reduction line (never a zero deduction); the larger one is still reduced.
     */
    @Test
    void SRS_LV_15_aReductionRoundingToZeroRupeesGivesNoLine() {
        configured("CALENDAR_DAYS", "101+111");
        leave(DAY.plusDays(2), DAY.plusDays(2), "1", "FULL_DEDUCTION", "ADDITION");
        StaffDeductions.Member small =
                new StaffDeductions.Member(
                        7L,
                        1L,
                        "IDA",
                        MONTH,
                        List.of(
                                new StaffDeductions.Earning(101, new BigDecimal("60000")),
                                new StaffDeductions.Earning(111, new BigDecimal("10"))),
                        new BigDecimal("60010"));
        List<StaffDeductions.Reduction> out = reduction.open(DAY).reductions(small);
        // 10 / 30 x 1 = 0.33, rounded to 0: no line for 111.
        assertThat(out).extracting(StaffDeductions.Reduction::payCode).containsExactly(101);
        assertThat(out.get(0).amount()).isEqualByComparingTo("2000");
    }
}
