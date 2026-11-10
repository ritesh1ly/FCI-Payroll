package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.leave.api.LeaveEncashments;
import in.gov.fci.payroll.leave.business.EncashmentRules;
import in.gov.fci.payroll.leave.business.LeaveErrorCode;
import in.gov.fci.payroll.leave.business.LeaveParameterRules;
import in.gov.fci.payroll.payroll.api.StaffEarnings;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The leave encashment valuation (plan 19 phase 19e; SRS-10-16, SRS-LV-16, SRS-LV-17, SRS-LV-21;
 * BR-FNF-009). The balance is the latest encashable balance HRMS sent for each encashable type on
 * or before the date ({@code leave_balance_snapshot}); the pay is the M-20 pay elements of the
 * member's latest computed month on or before the date ({@code payroll::api} StaffEarnings).
 * OPEN-QUESTION (PENDING 19-pay-on-date): a month whose pay was reduced for leave values at the
 * reduced amounts; the SRS says "the pay drawn on the date" without the treatment of such a month.
 */
@Service
public class LeaveEncashmentService implements LeaveEncashments {

    private final JdbcTemplate jdbc;
    private final LeaveParameters parameters;
    private final StaffEarnings earnings;

    public LeaveEncashmentService(
            JdbcTemplate jdbc, LeaveParameters parameters, StaffEarnings earnings) {
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.earnings = earnings;
    }

    @Override
    @Transactional(readOnly = true)
    public Valuation atSeparation(long employeeId, LocalDate separationDate) {
        BigDecimal ceiling =
                parameters
                        .number(LeaveParameters.SEPARATION_AGGREGATE_CEILING, null, separationDate)
                        .orElseThrow(() -> notConfigured("Aggregate ceiling at separation"));
        BigDecimal divisor =
                parameters
                        .number(LeaveParameters.ENCASH_DIVISOR, null, separationDate)
                        .filter(d -> d.signum() > 0)
                        .orElseThrow(() -> notConfigured("Encashment divisor"));
        Set<Integer> elements =
                parameters
                        .text(LeaveParameters.ENCASH_PAY_ELEMENTS, null, separationDate)
                        .map(t -> Set.copyOf(LeaveParameterRules.payCodes(t)))
                        .orElseThrow(() -> notConfigured("Encashment pay elements"));
        int payCode =
                parameters
                        .text(LeaveParameters.ENCASH_PAY_CODES, null, separationDate)
                        .map(t -> LeaveParameterRules.payCodes(t).getFirst())
                        .orElseThrow(() -> notConfigured("Encashment pay codes"));
        BigDecimal balance = balance(employeeId, separationDate);
        BigDecimal pay = pay(employeeId, separationDate, elements);
        EncashmentRules.Days days = EncashmentRules.atSeparation(balance, ceiling);
        BigDecimal amount = EncashmentRules.value(days.days(), pay, divisor);
        BigDecimal rate = EncashmentRules.ratePerDay(pay, divisor);
        String basis =
                "("
                        + elements.stream()
                                .sorted()
                                .map(String::valueOf)
                                .reduce((a, b) -> a + "+" + b)
                                .orElse("")
                        + ") "
                        + plain(pay)
                        + " / "
                        + plain(divisor)
                        + " x "
                        + plain(days.days())
                        + " days"
                        + (days.capped()
                                ? " (capped at the ceiling of " + plain(ceiling) + ")"
                                : "");
        return new Valuation(
                balance, days.days(), days.capped(), pay, divisor, rate, amount, payCode, basis);
    }

    /**
     * SRS-LV-16 / LV-17: an approved in-service encashment of {@code requested} days of {@code
     * leaveType} on {@code day}, capped by the per-occasion limit and the days left in the
     * financial year, valued at the pay on the day; recorded as a {@code leave_encashment} row
     * (never edited afterwards, SRS-LV-20).
     *
     * @return the row's id
     */
    @Transactional
    public long recordInService(
            long employeeId,
            String leaveType,
            BigDecimal requested,
            LocalDate day,
            long approvalId) {
        InService v = valueInService(employeeId, leaveType, requested, day);
        EncashmentRules.Days days = v.days();
        BigDecimal pay = v.pay();
        BigDecimal divisor = v.divisor();
        BigDecimal amount = v.amount();
        long componentId = v.componentId();
        String basis = v.basis();
        org.springframework.jdbc.support.GeneratedKeyHolder key =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    java.sql.PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO leave_encashment (employee_id, leave_type_code,"
                                            + " encashment_reason, period_from, period_to,"
                                            + " days_encashed, valuation_basis, rate_per_day,"
                                            + " amount_paid, was_capped, component_id, approval_id)"
                                            + " VALUES (?, ?, 'IN_SERVICE', ?, ?, ?, ?, ?, ?, ?, ?,"
                                            + " ?)",
                                    new String[] {"encashment_id"});
                    ps.setLong(1, employeeId);
                    ps.setString(2, leaveType);
                    ps.setDate(3, Date.valueOf(day));
                    ps.setDate(4, Date.valueOf(day));
                    ps.setBigDecimal(5, days.days());
                    ps.setString(6, basis);
                    ps.setBigDecimal(7, EncashmentRules.ratePerDay(pay, divisor));
                    ps.setBigDecimal(8, amount);
                    ps.setString(9, days.capped() ? "Y" : "N");
                    ps.setLong(10, componentId);
                    ps.setLong(11, approvalId);
                    return ps;
                },
                key);
        return in.gov.fci.payroll.platform.persistence.GeneratedKeys.id(key);
    }

    /**
     * SRS-LV-17 / BR-LEAVE-009 (US-GAP-58): the in-service encashment as it would be recorded: the
     * days after the per-occasion and per-year caps, the pay on the day and the amount, with its
     * basis. Shown to the Checker on I-21 before approval and used again when it is recorded.
     */
    public record InService(
            EncashmentRules.Days days,
            BigDecimal pay,
            BigDecimal divisor,
            BigDecimal amount,
            long componentId,
            String basis) {}

    @Transactional(readOnly = true)
    public InService valueInService(
            long employeeId, String leaveType, BigDecimal requested, LocalDate day) {
        BigDecimal perOccasion =
                parameters
                        .number(LeaveParameters.INSERVICE_PER_OCCASION_LIMIT, leaveType, day)
                        .orElseThrow(() -> notConfigured("In-service encashment per occasion"));
        BigDecimal perYear =
                parameters
                        .number(LeaveParameters.INSERVICE_PER_YEAR_LIMIT, leaveType, day)
                        .orElseThrow(() -> notConfigured("In-service encashment per year"));
        BigDecimal divisor =
                parameters
                        .number(LeaveParameters.ENCASH_DIVISOR, null, day)
                        .filter(d -> d.signum() > 0)
                        .orElseThrow(() -> notConfigured("Encashment divisor"));
        Set<Integer> elements =
                parameters
                        .text(LeaveParameters.ENCASH_PAY_ELEMENTS, null, day)
                        .map(t -> Set.copyOf(LeaveParameterRules.payCodes(t)))
                        .orElseThrow(() -> notConfigured("Encashment pay elements"));
        int payCode =
                parameters
                        .text(LeaveParameters.ENCASH_PAY_CODES, null, day)
                        .map(t -> LeaveParameterRules.payCodes(t).getFirst())
                        .orElseThrow(() -> notConfigured("Encashment pay codes"));
        LocalDate fyStart =
                LocalDate.of(day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1, 4, 1);
        BigDecimal used =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(days_encashed), 0) FROM leave_encashment WHERE"
                                + " employee_id = ? AND leave_type_code = ? AND encashment_reason ="
                                + " 'IN_SERVICE' AND period_from >= ? AND period_from < ?",
                        BigDecimal.class,
                        employeeId,
                        leaveType,
                        Date.valueOf(fyStart),
                        Date.valueOf(fyStart.plusYears(1)));
        EncashmentRules.Days days =
                EncashmentRules.inService(
                        requested,
                        perOccasion,
                        perYear.subtract(used == null ? BigDecimal.ZERO : used));
        if (days.days().signum() <= 0) {
            throw new FciException(
                    LeaveErrorCode.EVENT,
                    new Object[] {"the in-service encashment limit of the year is used"},
                    List.of(),
                    null);
        }
        BigDecimal pay = pay(employeeId, day, elements);
        BigDecimal amount = EncashmentRules.value(days.days(), pay, divisor);
        Long componentId =
                jdbc
                        .queryForList(
                                "SELECT component_id FROM pay_component WHERE legacy_pay_code = ?",
                                Long.class,
                                payCode)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> notConfigured("Pay code " + payCode + " on M-07"));
        String basis =
                plain(pay)
                        + " / "
                        + plain(divisor)
                        + " x "
                        + plain(days.days())
                        + " days"
                        + (days.capped() ? " (capped from " + plain(requested) + ")" : "");
        return new InService(days, pay, divisor, amount, componentId, basis);
    }

    /** The latest encashable balance of each encashable type on or before the day, summed. */
    BigDecimal balance(long employeeId, LocalDate day) {
        List<BigDecimal> rows =
                jdbc.queryForList(
                        "SELECT s.balance_days FROM leave_balance_snapshot s JOIN"
                            + " leave_type_reference t ON t.leave_type_code = s.leave_type_code"
                            + " WHERE s.employee_id = ? AND t.is_encashable = 'Y' AND s.as_of_date"
                            + " = (SELECT MAX(x.as_of_date) FROM leave_balance_snapshot x WHERE"
                            + " x.employee_id = s.employee_id AND x.leave_type_code ="
                            + " s.leave_type_code AND x.as_of_date <= ?)",
                        BigDecimal.class,
                        employeeId,
                        Date.valueOf(day));
        if (rows.isEmpty()) {
            throw notConfigured("The encashable leave balance from HRMS");
        }
        return rows.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The pay elements of the latest computed month on or before the day (within a year). */
    BigDecimal pay(long employeeId, LocalDate day, Set<Integer> elements) {
        YearMonth to = YearMonth.from(day);
        List<StaffEarnings.Month> months =
                earnings.months(employeeId, yymm(to.minusMonths(12)), yymm(to));
        if (months.isEmpty()) {
            throw notConfigured("The member's pay on " + day + " (no computed month)");
        }
        StaffEarnings.Month last = months.getLast();
        BigDecimal pay =
                last.lines().stream()
                        .filter(l -> elements.contains(l.payCode()))
                        .map(StaffEarnings.Line::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (pay.signum() <= 0) {
            throw notConfigured("The member's pay elements in " + last.yymm());
        }
        return pay;
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static FciException notConfigured(String what) {
        return new FciException(
                LeaveErrorCode.NOT_CONFIGURED, new Object[] {what}, List.of(), null);
    }
}
