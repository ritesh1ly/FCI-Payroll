package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.leave.business.LeavePayRules;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The month's leave pay reduction in the staff payroll (plan 19 phase 19d; SRS-LV-15, SRS-LV-27,
 * SRS-LV-22; D19-4, US-GAP-298): the leave transactions applied for the member overlapping the
 * month (approved I-21 tasks, back-dated ones included, plan 13 D13-6: their locked months' share
 * is the arrears module's recompute) reduce each component marked "Subject to LWP" on the component
 * catalogue (M-07; SRS-LV-15, BR-LEAVE-007, US-GAP-298) by (amount / divisor) x days lost, before
 * any deduction. A transaction whose type has no pay effect configured (HELD_UNDEFINED) refuses the
 * member: never a zero deduction. The divisor and the components are read once per run, on the
 * period's first day; a member without leave in the month needs neither.
 */
@Component
public class LeavePayReduction implements StaffDeductions {

    private final JdbcTemplate jdbc;
    private final LeaveParameters parameters;

    public LeavePayReduction(JdbcTemplate jdbc, LeaveParameters parameters) {
        this.jdbc = jdbc;
        this.parameters = parameters;
    }

    /** Before every recovery: a reduction of pay, not a deduction (D19-4). */
    @Override
    public int order() {
        return 5;
    }

    @Override
    public Run open(LocalDate asOf) {
        YearMonth month = YearMonth.from(asOf);
        Optional<BigDecimal> divisor =
                LeavePayRules.divisor(
                        parameters.number(LeaveParameters.LWP_DIVISOR, null, asOf),
                        parameters.text(LeaveParameters.LWP_DIVISOR, null, asOf),
                        month);
        return new ReductionRun(month, divisor, subjectToLwp(asOf));
    }

    private final class ReductionRun implements Run {

        private final YearMonth month;
        private final Optional<BigDecimal> divisor;
        private final Optional<Set<Integer>> components;

        ReductionRun(
                YearMonth month, Optional<BigDecimal> divisor, Optional<Set<Integer>> components) {
            this.month = month;
            this.divisor = divisor;
            this.components = components;
        }

        @Override
        public String versions() {
            return "lwp="
                    + divisor.map(BigDecimal::toPlainString).orElse("-")
                    + ";components="
                    + components.map(c -> c.stream().sorted().toList().toString()).orElse("-");
        }

        @Override
        public List<Line> deductions(Member member) {
            return List.of();
        }

        @Override
        public List<Reduction> reductions(Member member) {
            List<LeavePayRules.Leave> leave = leave(member.employeeId(), month);
            if (leave.isEmpty()) {
                return List.of();
            }
            if (leave.stream()
                    .anyMatch(l -> LeaveHrmsEffects.HELD_UNDEFINED.equals(l.wageEffect()))) {
                throw new NotComputable(
                        "Leave pay effect not configured on M-20 for the month's leave");
            }
            BigDecimal days = LeavePayRules.daysLost(leave, month);
            if (days.signum() == 0) {
                return List.of();
            }
            if (!LeavePayRules.withinMonth(days, month)) {
                throw new NotComputable(
                        "Leave days lost (" + days + ") exceed the days of the month");
            }
            long onRolls = daysOnRolls(member.employeeId(), month);
            if (days.compareTo(BigDecimal.valueOf(onRolls)) > 0) {
                throw new NotComputable(
                        "Leave days lost ("
                                + days
                                + ") exceed the "
                                + onRolls
                                + " days the member was on the rolls in "
                                + month);
            }
            BigDecimal by =
                    divisor.orElseThrow(
                            () -> new NotComputable("LWP divisor not configured on M-20"));
            Set<Integer> codes =
                    components.orElseThrow(
                            () ->
                                    new NotComputable(
                                            "Components subject to LWP not marked on M-07"));
            List<Reduction> out = new ArrayList<>();
            for (Earning e : member.earnings()) {
                if (codes.contains(e.payCode()) && e.amount().signum() > 0) {
                    BigDecimal r = LeavePayRules.reduction(e.amount(), by, days);
                    if (r.signum() > 0) {
                        out.add(new Reduction(e.payCode(), r));
                    }
                }
            }
            return out;
        }
    }

    /**
     * SRS-LV-15 / BR-LEAVE-007 (US-GAP-298): the pay codes in force on the day that M-07 marks
     * "Subject to LWP"; empty when none is marked (R-RULE: the reduction is then not configured,
     * never a default list).
     *
     * <p>Wave D review 3 (BR-CYC-011..017): only the end date is read. An M-07 revision rewrites
     * {@code effective_from} in place and a retirement sets {@code is_retired} at once although the
     * code stays in force up to its last day, so neither may decide a re-run of an earlier month.
     */
    private Optional<Set<Integer>> subjectToLwp(LocalDate asOf) {
        List<Integer> codes =
                jdbc.queryForList(
                        "SELECT legacy_pay_code FROM pay_component WHERE lwp_subject_flag = 'Y' AND"
                                + " (effective_to IS NULL OR effective_to >= ?) ORDER BY"
                                + " legacy_pay_code",
                        Integer.class,
                        Date.valueOf(asOf));
        return codes.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(codes));
    }

    /**
     * SRS-LV-22 (US-GAP-57): the days of the month the member was on the rolls, from the date of
     * joining to the date of separation. Both days are counted: the larger reading, so the check
     * refuses only LWP that exceeds the days on the rolls under either convention.
     */
    private long daysOnRolls(long employeeId, YearMonth month) {
        Map<String, Object> e =
                jdbc.queryForMap(
                        "SELECT comp_joining_date, separation_date FROM employee WHERE"
                                + " employee_id = ?",
                        employeeId);
        return LeavePayRules.daysOnRolls(
                LeaveParameterService.toLocalDate(e.get("COMP_JOINING_DATE")),
                e.get("SEPARATION_DATE") == null
                        ? null
                        : LeaveParameterService.toLocalDate(e.get("SEPARATION_DATE")),
                month);
    }

    /**
     * The member's applied leave transactions overlapping the month, back-dated ones included (plan
     * 13 D13-6): the open month's share of a back-dated transaction is reduced in the run, and the
     * locked months' share reaches the arrear through the retrospective recompute.
     */
    private List<LeavePayRules.Leave> leave(long employeeId, YearMonth month) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT period_from, period_to, days, wage_effect_code, leave_event_type"
                                + " FROM leave_transaction WHERE employee_id = ? AND period_from"
                                + " <= ? AND period_to >= ?",
                        employeeId,
                        Date.valueOf(month.atEndOfMonth()),
                        Date.valueOf(month.atDay(1)));
        return rows.stream()
                .map(
                        r ->
                                new LeavePayRules.Leave(
                                        LeaveParameterService.toLocalDate(r.get("PERIOD_FROM")),
                                        LeaveParameterService.toLocalDate(r.get("PERIOD_TO")),
                                        (BigDecimal) r.get("DAYS"),
                                        (String) r.get("WAGE_EFFECT_CODE"),
                                        "CANCELLATION".equals(r.get("LEAVE_EVENT_TYPE"))))
                .toList();
    }
}
