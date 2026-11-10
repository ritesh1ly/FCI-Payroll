package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff payment of approved claims and of the cafeteria allowance (plan 10 phase 10d, D10-6;
 * SRS-11-12, SRS-11-16): an earnings source of the staff payroll (port {@code
 * payroll.api.StaffDeductions}, earnings hook), read once per run. An approved, unpaid employee
 * claim is an earning of the run under its claim type's pay code; a claim type without an active
 * pay code is left out (held, raised on X-05 at approval, D10-5). The cafeteria selection in force
 * in the month is paid pro rata for a part month. Nothing is written here: the claims become PAID
 * at the lock ({@link ClaimLockEffect}).
 */
@Component
public class ClaimPayments implements StaffDeductions {

    private final JdbcTemplate jdbc;

    public ClaimPayments(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Earnings first: before every deduction source (the order is the deductions' only). */
    @Override
    public int order() {
        return 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Run open(LocalDate asOf) {
        YearMonth month = YearMonth.from(asOf);
        Date first = Date.valueOf(month.atDay(1));
        Date last = Date.valueOf(month.atEndOfMonth());
        Map<Long, List<Earning>> byMember = new HashMap<>();
        jdbc.query(
                "SELECT c.employee_id, CAST(v.numeric_attribute AS INTEGER), c.payable_amount FROM"
                        + " reimbursement_claim c JOIN reference_list l ON l.list_code ="
                        + " 'CLAIM_TYPE' JOIN reference_value v ON v.list_id = l.list_id AND"
                        + " v.value_code = c.claim_type JOIN pay_component p ON p.legacy_pay_code ="
                        + " v.numeric_attribute AND p.is_retired = 'N' WHERE c.claim_status ="
                        + " 'APPROVED' AND c.member_type = 'EMPLOYEE' AND c.payable_amount > 0"
                        + " ORDER BY c.employee_id, c.claim_id",
                rs -> {
                    byMember.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new Earning(rs.getInt(2), rs.getBigDecimal(3)));
                });
        jdbc.query(
                "SELECT e.employee_id, p.legacy_pay_code, l.elected_amount, e.effective_from,"
                    + " e.effective_to FROM cafeteria_election e JOIN cafeteria_election_line l ON"
                    + " l.election_id = e.election_id JOIN pay_component p ON p.component_id ="
                    + " l.component_id WHERE e.election_status IN ('APPLIED', 'ARREAR_RAISED',"
                    + " 'SUPERSEDED') AND e.effective_from <= ? AND (e.effective_to IS NULL OR"
                    + " e.effective_to >= ?) ORDER BY e.employee_id, e.version_no",
                rs -> {
                    Date to = rs.getDate(5);
                    BigDecimal amount =
                            ClaimRules.proRata(
                                    rs.getBigDecimal(3),
                                    rs.getDate(4).toLocalDate(),
                                    to == null ? null : to.toLocalDate(),
                                    month);
                    if (amount.signum() > 0) {
                        byMember.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                                .add(new Earning(rs.getInt(2), amount));
                    }
                },
                last,
                first);
        return new Run() {
            @Override
            public String versions() {
                return "";
            }

            @Override
            public List<Earning> earnings(Member member) {
                return byMember.getOrDefault(member.employeeId(), List.of());
            }

            @Override
            public List<Line> deductions(Member member) {
                return List.of();
            }
        };
    }
}
