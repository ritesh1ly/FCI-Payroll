package in.gov.fci.payroll.nps.orchestration;

import in.gov.fci.payroll.nps.business.NpsRules;
import in.gov.fci.payroll.nps.business.NpsRules.Mismatch;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import in.gov.fci.payroll.payroll.api.StatutoryPayCodes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The NPS of the staff payroll as received from HRMS (plan 18 phase 18b; SRS-9-1, SRS-9-1a,
 * SRS-9-18, SRS-9-21; D18-3 … D18-5, D18-9): after CPF (10) and before TDS (30). The employee NPS
 * is deducted as received, never recalculated; the employer NPS is a contribution (an earning and a
 * deduction of the same amount: net unchanged, in the taxable gross of TDS). Nothing is taken for a
 * month with a flag / amount mismatch (the lock waits for HRMS), for a member separated before the
 * month, or without a record of the month (not flagged NPS by HRMS that month).
 *
 * <p>OPEN-QUESTION (PENDING 18-missing-record): the SRS does not say what happens when an NPS
 * member's record for the month has not arrived from HRMS by the run; SRS-9-1 makes a member NPS
 * only when HRMS flags it, so no record means no NPS (D18-9).
 */
@Component
public class NpsDeduction implements StaffDeductions {

    private final JdbcTemplate jdbc;

    public NpsDeduction(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return 20;
    }

    /** Gate G5 (SRS-15-18, SRS l.1524): this source is the the NPS deduction sub-process. */
    @Override
    public java.util.Optional<String> statutoryProcess() {
        return java.util.Optional.of("NPS_DEDUCTION");
    }

    @Override
    public Run open(LocalDate asOf) {
        Optional<Integer> employee = StatutoryPayCodes.of(jdbc, "NPS", asOf);
        Optional<Integer> employerDeduction = StatutoryPayCodes.of(jdbc, "NPS_EMPLOYER", asOf);
        Optional<Integer> employerEarning =
                StatutoryPayCodes.of(jdbc, "NPS_EMPLOYER_EARNING", asOf);
        return new NpsRun(employee, employerEarning, employerDeduction);
    }

    /** The month's record of a member: flag, amounts, status and separation date. */
    record Record(
            boolean flag,
            BigDecimal employeeAmount,
            BigDecimal employerAmount,
            Mismatch status,
            LocalDate separation) {

        /** Whether NPS is taken for the month (SRS-9-1, 9-1a, 9-18). */
        boolean applies(java.time.YearMonth month) {
            return flag && !status.blocksLock() && !NpsRules.separatedBefore(separation, month);
        }
    }

    private final class NpsRun implements Run {

        private final Optional<Integer> employee;
        private final Optional<Integer> employerEarning;
        private final Optional<Integer> employerDeduction;

        /** The last member read: contributions and deductions of one member read it once. */
        private final AtomicReference<Object[]> last = new AtomicReference<>();

        NpsRun(
                Optional<Integer> employee,
                Optional<Integer> employerEarning,
                Optional<Integer> employerDeduction) {
            this.employee = employee;
            this.employerEarning = employerEarning;
            this.employerDeduction = employerDeduction;
        }

        @Override
        public String versions() {
            return "codes="
                    + employee.map(String::valueOf).orElse("-")
                    + ","
                    + employerEarning.map(String::valueOf).orElse("-")
                    + ","
                    + employerDeduction.map(String::valueOf).orElse("-");
        }

        @Override
        public List<Contribution> contributions(Member member) {
            Record r = record(member);
            if (r == null || !r.applies(member.month()) || !positive(r.employerAmount())) {
                return List.of();
            }
            if (employerEarning.isEmpty() || employerDeduction.isEmpty()) {
                throw new NotComputable("Employer NPS pay codes not configured (M-16)");
            }
            return List.of(
                    new Contribution(
                            employerEarning.get(), employerDeduction.get(), r.employerAmount()));
        }

        @Override
        public List<Line> deductions(Member member) {
            Record r = record(member);
            if (r == null || !r.applies(member.month()) || !positive(r.employeeAmount())) {
                return List.of();
            }
            int code =
                    employee.orElseThrow(
                            () -> new NotComputable("NPS pay code not configured (M-16)"));
            return List.of(new Line(code, r.employeeAmount(), null, null));
        }

        private Record record(Member member) {
            Object[] cached = last.get();
            if (cached != null && cached[0].equals(member.employeeId())) {
                return (Record) cached[1];
            }
            Record r = read(member);
            last.set(new Object[] {member.employeeId(), r});
            return r;
        }
    }

    private Record read(Member member) {
        String yymm =
                String.format("%04d%02d", member.month().getYear(), member.month().getMonthValue());
        return jdbc
                .queryForList(
                        "SELECT n.hrms_nps_flag, n.employee_amount, n.employer_amount,"
                                + " n.mismatch_status, e.separation_date FROM nps_deduction n JOIN"
                                + " employee e ON e.employee_id = n.employee_id WHERE"
                                + " n.employee_id = ? AND n.payroll_yymm = ? AND n.record_type ="
                                + " 'MONTHLY'",
                        member.employeeId(),
                        yymm)
                .stream()
                .findFirst()
                .map(NpsDeduction::record)
                .orElse(null);
    }

    private static Record record(Map<String, Object> row) {
        Object separation = row.get("SEPARATION_DATE");
        return new Record(
                "Y".equals(row.get("HRMS_NPS_FLAG")),
                (BigDecimal) row.get("EMPLOYEE_AMOUNT"),
                (BigDecimal) row.get("EMPLOYER_AMOUNT"),
                Mismatch.valueOf((String) row.get("MISMATCH_STATUS")),
                separation instanceof java.sql.Date d ? d.toLocalDate() : null);
    }

    private static boolean positive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }
}
