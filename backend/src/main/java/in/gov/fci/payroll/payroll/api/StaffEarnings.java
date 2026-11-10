package in.gov.fci.payroll.payroll.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A staff member's computed earnings month by month, for the statutory modules (plan 16 phase
 * 16e-1; SRS-1-10, SRS-7-3, SRS-7-12): the CURRENT Actual STAFF result of each month with its
 * earning lines, and whether the month is locked. Module 08 builds its taxable year from it ({@code
 * TaxableEarnings}); the taxable part of a line is the pay code's {@code taxable_flag} (Y or
 * PARTLY: an exemption such as HRA is the tax module's, from the declarations).
 */
@Component
public class StaffEarnings {

    /** One earning line of a month as the payslip names it. */
    public record Line(int payCode, String name, BigDecimal amount, boolean taxable) {}

    /**
     * One month with a result.
     *
     * @param tdsDeducted the TDS of the month's TDS line (plan 16 phase 16e-2)
     * @param employerNps the employer NPS of the month's earning line (plan 18 phase 18b; SRS-9-21:
     *     income with its matching exemption, never in the taxable gross)
     */
    public record Month(
            String yymm,
            boolean locked,
            List<Line> lines,
            BigDecimal tdsDeducted,
            BigDecimal employerNps) {

        public Month {
            lines = List.copyOf(lines);
            tdsDeducted = tdsDeducted == null ? BigDecimal.ZERO : tdsDeducted;
            employerNps = employerNps == null ? BigDecimal.ZERO : employerNps;
        }

        public Month(String yymm, boolean locked, List<Line> lines) {
            this(yymm, locked, lines, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        /** The earnings of the month that attract tax. */
        public BigDecimal taxableGross() {
            return lines.stream()
                    .filter(Line::taxable)
                    .map(Line::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private final JdbcTemplate jdbc;

    public StaffEarnings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The months {@code fromYymm} to {@code toYymm} (YYYYMM, inclusive) that have a result, in
     * order.
     */
    @Transactional(readOnly = true)
    public List<Month> months(long employeeId, String fromYymm, String toYymm) {
        Map<String, Boolean> locked = new LinkedHashMap<>();
        Map<String, List<Line>> lines = new LinkedHashMap<>();
        jdbc.query(
                "SELECT r.payroll_yymm, r.is_locked, c.legacy_pay_code, c.component_name,"
                    + " c.taxable_flag, SUM(rc.amount) FROM payroll_result r JOIN"
                    + " payroll_result_component rc ON rc.payroll_result_id = r.payroll_result_id"
                    + " JOIN pay_component c ON c.component_id = rc.component_id WHERE"
                    + " r.employee_id = ? AND r.payroll_type = 'STAFF' AND r.run_type = 'ACTUAL'"
                    + " AND r.result_status = 'CURRENT' AND r.payroll_yymm BETWEEN ? AND ? AND"
                    + " c.component_type = 'EARNING' GROUP BY r.payroll_yymm, r.is_locked,"
                    + " c.legacy_pay_code, c.component_name, c.taxable_flag ORDER BY"
                    + " r.payroll_yymm, c.legacy_pay_code",
                rs -> {
                    String yymm = rs.getString(1);
                    locked.merge(yymm, "Y".equals(rs.getString(2)), Boolean::logicalAnd);
                    lines.computeIfAbsent(yymm, k -> new ArrayList<>())
                            .add(
                                    new Line(
                                            rs.getInt(3),
                                            rs.getString(4),
                                            rs.getBigDecimal(6),
                                            !"N".equals(rs.getString(5))));
                },
                employeeId,
                fromYymm,
                toYymm);
        // Plan 16 phase 16e-2 (SRS-7-3): the TDS deducted in each month, its TDS line (signed).
        Map<String, BigDecimal> tds = new LinkedHashMap<>();
        StatutoryPayCodes.of(jdbc, "TDS", java.time.LocalDate.now())
                .ifPresent(
                        code ->
                                jdbc.query(
                                        "SELECT r.payroll_yymm, SUM(rc.amount) FROM payroll_result"
                                            + " r JOIN payroll_result_component rc ON"
                                            + " rc.payroll_result_id = r.payroll_result_id WHERE"
                                            + " r.employee_id = ? AND r.payroll_type = 'STAFF' AND"
                                            + " r.run_type = 'ACTUAL' AND r.result_status ="
                                            + " 'CURRENT' AND r.payroll_yymm BETWEEN ? AND ? AND"
                                            + " rc.legacy_pay_code = ? GROUP BY r.payroll_yymm",
                                        rs -> {
                                            tds.put(rs.getString(1), rs.getBigDecimal(2).negate());
                                        },
                                        employeeId,
                                        fromYymm,
                                        toYymm,
                                        code));
        // Plan 18 phase 18b (SRS-9-21): the employer NPS of each month, its earning line.
        Map<String, BigDecimal> nps = new LinkedHashMap<>();
        StatutoryPayCodes.of(jdbc, "NPS_EMPLOYER_EARNING", java.time.LocalDate.now())
                .ifPresent(
                        code ->
                                lines.forEach(
                                        (yymm, monthLines) ->
                                                monthLines.stream()
                                                        .filter(l -> l.payCode() == code)
                                                        .forEach(
                                                                l ->
                                                                        nps.merge(
                                                                                yymm,
                                                                                l.amount(),
                                                                                BigDecimal::add))));
        return lines.entrySet().stream()
                .map(
                        e ->
                                new Month(
                                        e.getKey(),
                                        locked.get(e.getKey()),
                                        e.getValue(),
                                        tds.get(e.getKey()),
                                        nps.get(e.getKey())))
                .toList();
    }
}
