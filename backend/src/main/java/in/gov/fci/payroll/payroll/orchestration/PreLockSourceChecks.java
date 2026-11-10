package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.payroll.api.PreLockCheck;
import in.gov.fci.payroll.payroll.business.PreLockRules;
import in.gov.fci.payroll.payroll.business.PreLockRules.RecoveryLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The four pre-lock checks of SRS §6.7.2 that had no evaluator (OD-152; SRS-15-39, FRS V0.5
 * §6.11.3; DDL v4.0 {@code pre_payroll_check}, BLOCK, all payrolls): APPS_POSTING_UNIT_ACTIVE,
 * BANK_ACCOUNT_IFSC_VALID, PF_UAN_VALID and NO_DUPLICATE_RECOVERY. Each counts the failing members
 * of the instance's run of record (its current and quarantined Actual results); the rules are
 * {@link PreLockRules}. Read only: HRMS-owned values are never changed here (they are fixed on
 * I-21, labour on M-02). Details never show an account number, UAN or PAN (R-PII).
 */
public final class PreLockSourceChecks {

    /** The results of the run and their members (joined further by each check). */
    static final String RESULTS =
            " FROM payroll_result r JOIN employee e ON e.employee_id = r.employee_id";

    /** The instance's run of record: its current and quarantined Actual results. */
    static final String RUN_OF_RECORD =
            " WHERE r.cycle_instance_id = ? AND r.run_type = 'ACTUAL' AND r.result_status IN"
                    + " ('CURRENT', 'QUARANTINED')";

    /** The members of the instance's run of record. */
    static final String POPULATION = RESULTS + RUN_OF_RECORD;

    /** APPS_POSTING_UNIT_ACTIVE: each member's place of posting and its APPS state. */
    static final String POSTING_SQL =
            "SELECT p.location_id, p.apps_active_flag FROM (SELECT COALESCE(lp.depot_location_id,"
                    + " e.depot_location_id, e.location_id) AS posting"
                    + RESULTS
                    + " LEFT JOIN labour_profile lp ON lp.employee_id = e.employee_id"
                    + RUN_OF_RECORD
                    + ") x LEFT JOIN org_location p ON p.location_id = x.posting";

    /** BANK_ACCOUNT_IFSC_VALID: each member's net pay and bank route. */
    static final String BANK_SQL =
            "SELECT r.net_pay, e.bank_account_number, b.branch_id, b.active_flag, b.ifsc_code"
                    + RESULTS
                    + " LEFT JOIN bank_branch b ON b.branch_id = e.bank_branch_id"
                    + RUN_OF_RECORD;

    /** PF_UAN_VALID: each member's PF membership, CPF code and UAN (labour: Labour Master). */
    static final String PF_SQL =
            "SELECT e.emp_lbr_flag, e.provident_fund_type, lp.pf_applicable_flag, CASE WHEN"
                    + " e.emp_lbr_flag = 'Y' THEN lp.cpf_code ELSE e.cpf_code END, CASE WHEN"
                    + " e.emp_lbr_flag = 'Y' THEN lp.uan_number ELSE e.uan_number END"
                    + RESULTS
                    + " LEFT JOIN labour_profile lp ON lp.employee_id = e.employee_id"
                    + RUN_OF_RECORD;

    /** NO_DUPLICATE_RECOVERY: the deduction lines of the members' current results of the month. */
    static final String RECOVERY_SQL =
            "SELECT c.employee_id, c.component_id, r2.payroll_yymm FROM payroll_result r2 JOIN"
                    + " payroll_result_component c ON c.payroll_result_id = r2.payroll_result_id"
                    + " AND c.period_date = r2.period_date AND c.org_unit_id = r2.org_unit_id JOIN"
                    + " pay_component pc ON pc.component_id = c.component_id WHERE"
                    + " pc.component_type = 'DEDUCTION' AND c.arrear_detail_id IS NULL AND"
                    + " c.run_type = 'ACTUAL' AND r2.run_type = 'ACTUAL' AND r2.result_status IN"
                    + " ('CURRENT', 'QUARANTINED') AND r2.payroll_yymm = ? AND r2.employee_id IN"
                    + " (SELECT r.employee_id"
                    + POPULATION
                    + ")";

    private PreLockSourceChecks() {}

    /** APPS_POSTING_UNIT_ACTIVE: the place of posting (depot or unit) is active in APPS. */
    @Component
    public static class AppsPostingUnitActive implements PreLockCheck {

        static final String KEY = "APPS_POSTING_UNIT_ACTIVE";

        private final JdbcTemplate jdbc;

        public AppsPostingUnitActive(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String ruleKey() {
            return KEY;
        }

        @Override
        public Outcome evaluate(Cycle cycle) {
            // SRS §6.7.2 P5 / row 1: "place of posting (depot or unit)"; labour: its Labour Master
            // depot.
            List<Boolean> ok =
                    jdbc.query(
                            POSTING_SQL,
                            (rs, i) ->
                                    PreLockRules.postingActiveInApps(
                                            longOrNull(rs, 1), rs.getString(2)),
                            cycle.cycleInstanceId());
            long failures = ok.stream().filter(b -> !b).count();
            return new Outcome(
                    failures,
                    failures
                            + " members whose place of posting is not active in APPS (staff: HRMS"
                            + " through I-21; labour: M-02)");
        }
    }

    /** BANK_ACCOUNT_IFSC_VALID: account and IFSC present and valid for every member to be paid. */
    @Component
    public static class BankAccountIfscValid implements PreLockCheck {

        static final String KEY = "BANK_ACCOUNT_IFSC_VALID";

        private final JdbcTemplate jdbc;

        public BankAccountIfscValid(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String ruleKey() {
            return KEY;
        }

        @Override
        public Outcome evaluate(Cycle cycle) {
            List<Boolean> ok =
                    jdbc.query(
                            BANK_SQL,
                            (rs, i) ->
                                    PreLockRules.bankRouteValid(
                                            rs.getBigDecimal(1),
                                            rs.getString(2),
                                            rs.getObject(3) != null,
                                            rs.getString(4),
                                            rs.getString(5)),
                            cycle.cycleInstanceId());
            long failures = ok.stream().filter(b -> !b).count();
            return new Outcome(
                    failures,
                    failures
                            + " members to be paid without a valid bank account or IFSC (staff:"
                            + " HRMS through I-21; labour: M-02)");
        }
    }

    /** PF_UAN_VALID: a PF member holds a CPF code and a valid UAN. */
    @Component
    public static class PfUanValid implements PreLockCheck {

        static final String KEY = "PF_UAN_VALID";

        private final JdbcTemplate jdbc;

        public PfUanValid(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String ruleKey() {
            return KEY;
        }

        @Override
        public Outcome evaluate(Cycle cycle) {
            // Labour hold their CPF code and UAN on the Labour Master (DDL labour_profile), staff
            // on
            // the HRMS-sourced employee record.
            List<Boolean> ok =
                    jdbc.query(
                            PF_SQL,
                            (rs, i) ->
                                    PreLockRules.pfUanValid(
                                            "Y".equals(rs.getString(1)),
                                            rs.getString(2),
                                            rs.getString(3),
                                            rs.getString(4),
                                            rs.getString(5)),
                            cycle.cycleInstanceId());
            long failures = ok.stream().filter(b -> !b).count();
            return new Outcome(
                    failures,
                    failures
                            + " PF members without a CPF code or a valid UAN (staff: HRMS through"
                            + " I-21; labour: M-02)");
        }
    }

    /** NO_DUPLICATE_RECOVERY: no head recovered twice from a member for the month. */
    @Component
    public static class NoDuplicateRecovery implements PreLockCheck {

        static final String KEY = "NO_DUPLICATE_RECOVERY";

        private final JdbcTemplate jdbc;

        public NoDuplicateRecovery(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String ruleKey() {
            return KEY;
        }

        @Override
        public Outcome evaluate(Cycle cycle) {
            List<RecoveryLine> lines =
                    jdbc.query(
                            RECOVERY_SQL,
                            (rs, i) ->
                                    new RecoveryLine(rs.getLong(1), rs.getLong(2), rs.getString(3)),
                            cycle.payrollYymm(),
                            cycle.cycleInstanceId());
            long failures = PreLockRules.membersWithDuplicateRecovery(lines);
            return new Outcome(
                    failures,
                    failures
                            + " members recovered the same head more than once this month (Loans &"
                            + " Advances, I-06, I-19)");
        }
    }

    static Long longOrNull(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
