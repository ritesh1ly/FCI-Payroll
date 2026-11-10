package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.cpf.orchestration.CpfLockEffect;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * When a staff cycle locks, each CPF member it computed gets the month's SALARY row on the CPF
 * ledger (plan 07 phase 07i part 2; SRS-6-1, SRS-6-7, SRS-6-57, BR-CPF-017, SRS-G-20): the employee
 * contribution and VPF as deducted, the employer contribution at CPF.RATE.EMPLOYER and its EPS /
 * higher-pension split rounded once on the combined total. Demo rates (12 %, 8.33 %, 1.16 %) and
 * codes (904, 905), not FCI's. The month is far ahead so no other test's cycle meets it.
 */
class CpfLockIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private CpfLockEffect effect;
    @Autowired private PlatformTransactionManager transactionManager;

    private long cpfMember() {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                        + " 'ACTIVE' AND provident_fund_type = 'CPF' AND cpf_code IS NOT NULL AND"
                        + " parent_zone_id IS NOT NULL AND birth_date > ? ORDER BY emp_num FETCH"
                        + " FIRST 1 ROWS ONLY",
                Long.class,
                locationId("DH001"),
                // Under 58 in the test month: EPS is still booked (SRS-1-76; US-GAP-6).
                java.sql.Date.valueOf(
                        YearMonth.now(IST).plusMonths(70).atEndOfMonth().minusYears(58)));
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    /** A VERIFIED cycle of the month with the member's CPF and VPF lines; its id. */
    private long computed(long employee, YearMonth month) {
        long unit = locationId("DH001");
        String m = yymm(month);
        int start = month.getMonthValue() >= 4 ? month.getYear() : month.getYear() - 1;
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES (?, ?, 'DO', ?,"
                        + " 'VERIFIED', 'it.fixture')",
                m,
                unit,
                String.format("%d-%02d", start, (start + 1) % 100));
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        m,
                        unit);
        Date period = Date.valueOf(month.atDay(1));
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, result_status, is_locked) VALUES (?, ?, ?,"
                        + " 'STAFF', ?, ?, 'ACTUAL', 51000, 7900, 43100, 'CURRENT', 'N')",
                cycle,
                unit,
                employee,
                m,
                period);
        long result =
                jdbc.queryForObject(
                        "SELECT payroll_result_id FROM payroll_result WHERE cycle_instance_id = ?",
                        Long.class,
                        cycle);
        for (Object[] line :
                new Object[][] {{904, "-5400", "12", "45000"}, {905, "-2500", null, "45000"}}) {
            jdbc.update(
                    "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                            + " org_unit_id, employee_id, component_id, amount, rate_applied,"
                            + " basis_amount, run_type, is_locked, payroll_yymm, period_date,"
                            + " legacy_pay_code) SELECT ?, ?, ?, ?, component_id, ?, ?, ?,"
                            + " 'ACTUAL', 'N', ?, ?, legacy_pay_code FROM pay_component WHERE"
                            + " legacy_pay_code = ?",
                    result,
                    cycle,
                    unit,
                    employee,
                    new BigDecimal((String) line[1]),
                    line[2] == null ? null : new BigDecimal((String) line[2]),
                    new BigDecimal((String) line[3]),
                    m,
                    period,
                    line[0]);
        }
        return cycle;
    }

    @Test
    void SRS_6_7_theLockBooksTheMonthWithTheEmployerShareAndItsPensionSplit() {
        long employee = cpfMember();
        YearMonth month = YearMonth.now(IST).plusMonths(70);
        long cycle = computed(employee, month);

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        s ->
                                effect.on(
                                        new CycleLocked(
                                                cycle,
                                                locationId("DH001"),
                                                yymm(month),
                                                "it.checker",
                                                Instant.now())));

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT payment_type, epf_wage_base, employee_contribution,"
                                + " employer_contribution, vpf_contribution, eps_contribution,"
                                + " pohw_contribution, rounding_adjustment, zone_id FROM"
                                + " employee_pf_detail WHERE employee_id = ? AND payroll_yymm = ?",
                        employee,
                        yymm(month));
        assertThat(row.get("PAYMENT_TYPE")).isEqualTo("SALARY");
        assertThat((BigDecimal) row.get("EPF_WAGE_BASE")).isEqualByComparingTo("45000");
        assertThat((BigDecimal) row.get("EMPLOYEE_CONTRIBUTION")).isEqualByComparingTo("5400");
        assertThat((BigDecimal) row.get("EMPLOYER_CONTRIBUTION"))
                .as("12 % of the wage base")
                .isEqualByComparingTo("5400");
        assertThat((BigDecimal) row.get("VPF_CONTRIBUTION")).isEqualByComparingTo("2500");
        assertThat((BigDecimal) row.get("EPS_CONTRIBUTION")).isEqualByComparingTo("3748.50");
        assertThat((BigDecimal) row.get("POHW_CONTRIBUTION")).isEqualByComparingTo("522.00");
        assertThat((BigDecimal) row.get("ROUNDING_ADJUSTMENT"))
                .as("9.49 % = 4270.50, rounded once to 4271")
                .isEqualByComparingTo("0.50");
        assertThat(row.get("ZONE_ID")).isNotNull();
        int start = month.getMonthValue() >= 4 ? month.getYear() : month.getYear() - 1;
        BigDecimal closing =
                jdbc.queryForObject(
                        "SELECT closing_balance_employer FROM employee_pf_header WHERE employee_id"
                                + " = ? AND fin_year = ? AND fund_type = 'CPF'",
                        BigDecimal.class,
                        employee,
                        String.format("%d-%02d", start, (start + 1) % 100));
        assertThat(closing)
                .as("the ledger closes with the employer share")
                .isGreaterThanOrEqualTo(new BigDecimal("5400"));
    }

    @Test
    void SRS_6_1_aCycleWithoutCpfLinesBooksNothing() {
        YearMonth month = YearMonth.now(IST).plusMonths(71);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        s ->
                                effect.on(
                                        new CycleLocked(
                                                0L,
                                                locationId("DH001"),
                                                yymm(month),
                                                "it.checker",
                                                Instant.now())));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM employee_pf_detail WHERE payroll_yymm = ?",
                                Integer.class,
                                yymm(month)))
                .isZero();
    }
}
