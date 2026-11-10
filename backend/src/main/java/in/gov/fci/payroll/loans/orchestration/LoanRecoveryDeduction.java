package in.gov.fci.payroll.loans.orchestration;

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
 * Loan and advance recovery in the staff payroll (plan 09 phase 09d; SRS-12-3, SRS-12-5, SRS-12-24,
 * SRS-1-6; D09-7, D09-15): the instalments of the month (principal and interest) of the member's
 * open loans, from each loan's schedule version in force, one line per loan type under its recovery
 * head (reference list LOAN_RECOVERY). After the statutory deductions (CPF 10, NPS 20, TDS 30, PT
 * 40), before penalty recovery. Nothing for a member separated before the month (SRS-12-5: the
 * balance goes to Full &amp; Final). A type whose recovery head is not set refuses the member
 * (R-RULE).
 *
 * <p>OPEN-QUESTION (PENDING 09-hold): SRS-12-5 stops recovery "on hold" as well as on separation;
 * no source says which hold (the member's payment hold or a loan hold), so only separation stops it
 * here.
 */
@Component
public class LoanRecoveryDeduction implements StaffDeductions {

    private final JdbcTemplate jdbc;

    public LoanRecoveryDeduction(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public Run open(LocalDate asOf) {
        Map<String, Integer> codes = new TreeMap<>();
        jdbc.query(
                "SELECT v.value_code, v.numeric_attribute FROM reference_value v JOIN"
                        + " reference_list l ON l.list_id = v.list_id WHERE l.list_code ="
                        + " 'LOAN_RECOVERY' AND v.numeric_attribute IS NOT NULL AND"
                        + " v.effective_from <= ? AND (v.effective_to IS NULL OR v.effective_to >="
                        + " ?)",
                rs -> {
                    codes.put(rs.getString(1), rs.getBigDecimal(2).intValueExact());
                },
                Date.valueOf(asOf),
                Date.valueOf(asOf));
        String versions = "recovery=" + codes;
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
                        "SELECT t.loan_type_code, SUM(l.principal_amount + l.interest_amount) FROM"
                                + " loan_header h JOIN loan_type_master t ON t.loan_type_id ="
                                + " h.loan_type_id JOIN loan_schedule_version v ON v.loan_id ="
                                + " h.loan_id AND v.superseded_at IS NULL JOIN"
                                + " loan_recovery_schedule l ON l.schedule_version_id ="
                                + " v.schedule_version_id JOIN employee e ON e.employee_id ="
                                + " h.employee_id WHERE h.employee_id = ? AND h.loan_status IN"
                                + " ('ACTIVE', 'RESCHEDULED') AND l.payroll_yymm = ? AND"
                                + " (e.separation_date IS NULL OR e.separation_date >= ?) GROUP BY"
                                + " t.loan_type_code",
                        rs -> {
                            due.put(rs.getString(1), rs.getBigDecimal(2));
                        },
                        member.employeeId(),
                        yymm,
                        Date.valueOf(member.month().atDay(1)));
                List<Line> lines = new ArrayList<>();
                for (Map.Entry<String, BigDecimal> e : due.entrySet()) {
                    if (e.getValue() == null || e.getValue().signum() <= 0) {
                        continue;
                    }
                    Integer code = codes.get(e.getKey());
                    if (code == null) {
                        throw new NotComputable(
                                "Loan recovery head of "
                                        + e.getKey()
                                        + " not configured (M-16 LOAN_RECOVERY)");
                    }
                    // SRS-G-19: as scheduled: LoanLedgerEffect posts these amounts.
                    lines.add(new Line(code, e.getValue(), null, null, true));
                }
                return lines;
            }
        };
    }
}
