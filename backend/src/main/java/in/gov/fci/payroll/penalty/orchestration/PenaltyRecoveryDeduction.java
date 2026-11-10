package in.gov.fci.payroll.penalty.orchestration;

import in.gov.fci.payroll.payroll.api.StaffDeductions;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Penalty and recovery orders in the staff payroll (plan 12 phase 12d; SRS-14-1, SRS-14-11,
 * SRS-1-6; D12-6, D12-7): the month's PENDING instalments of the member's ACTIVE orders, one line
 * per nature under its recovery head (reference list PENALTY_RECOVERY). After the loans (order 50),
 * last of the recoveries. Nothing for a member separated before the month (SRS-14-11: the balance
 * goes to Full &amp; Final). A nature whose recovery head is not set refuses the member (R-RULE).
 */
@Component
public class PenaltyRecoveryDeduction implements StaffDeductions {

    /** After the loans (50), SRS-1-6. */
    static final int ORDER = 60;

    private final JdbcTemplate jdbc;

    public PenaltyRecoveryDeduction(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Run open(LocalDate asOf) {
        Map<String, Integer> codes = new TreeMap<>();
        jdbc.query(
                "SELECT v.value_code, v.numeric_attribute FROM reference_value v JOIN"
                        + " reference_list l ON l.list_id = v.list_id WHERE l.list_code ="
                        + " 'PENALTY_RECOVERY' AND v.numeric_attribute IS NOT NULL AND"
                        + " v.effective_from <= ? AND (v.effective_to IS NULL OR v.effective_to >="
                        + " ?)",
                rs -> {
                    codes.put(rs.getString(1), rs.getBigDecimal(2).intValueExact());
                },
                Date.valueOf(asOf),
                Date.valueOf(asOf));
        String versions = "penalty=" + codes;
        return new Run() {
            @Override
            public String versions() {
                return versions;
            }

            @Override
            public List<Line> deductions(Member member) {
                String yymm =
                        String.format(
                                "%04d%02d",
                                member.month().getYear(), member.month().getMonthValue());
                Map<String, BigDecimal> due = new TreeMap<>();
                jdbc.query(
                        "SELECT o.penalty_nature, SUM(s.scheduled_amount) FROM penalty_order o JOIN"
                            + " penalty_recovery_schedule s ON s.order_id = o.order_id JOIN"
                            + " employee e ON e.employee_id = o.employee_id WHERE o.employee_id = ?"
                            + " AND o.order_status IN ('ACTIVE', 'PARTLY_REVOKED') AND"
                            + " s.payroll_yymm = ? AND s.schedule_status = 'PENDING' AND"
                            + " (e.separation_date IS NULL OR e.separation_date >= ?) GROUP BY"
                            + " o.penalty_nature",
                        rs -> {
                            due.put(rs.getString(1), rs.getBigDecimal(2));
                        },
                        member.employeeId(),
                        yymm,
                        Date.valueOf(member.month().atDay(1)));
                List<Line> lines = new ArrayList<>();
                for (Map.Entry<String, BigDecimal> e : due.entrySet()) {
                    Integer code = codes.get(e.getKey());
                    if (code == null) {
                        throw new NotComputable(
                                "Penalty recovery head of "
                                        + e.getKey()
                                        + " not configured (M-16 PENALTY_RECOVERY)");
                    }
                    // SRS-G-19: as scheduled: PenaltyLockEffect posts these amounts.
                    lines.add(new Line(code, e.getValue(), null, null, true));
                }
                return lines;
            }
        };
    }
}
