package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.payroll.api.StaffEarnings;
import in.gov.fci.payroll.tax.business.TaxableEarnings;
import in.gov.fci.payroll.tax.orchestration.PayrollTaxableEarnings;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The staff results as the tax module reads them (plan 16 phase 16e-1; SRS-1-10, SRS-7-3,
 * SRS-7-12): the CURRENT Actual earnings of each month with their lines, locked or not; the taxable
 * year assembled from them. Fixture results in the financial year 2019-20 at Delhi HQ, which no
 * other test computes; the IT tax stand-in ({@code ItTaxPorts}) stays the TaxableEarnings bean, the
 * adapter is used directly.
 */
class StaffEarningsIT extends ApiTestSupport {

    @Autowired private StaffEarnings earnings;
    @Autowired private PayrollTaxableEarnings taxable;

    private long result(long employee, YearMonth month, boolean locked, int basic, int hra) {
        String yymm = month.toString().replace("-", "");
        long unit = locationId("DH001");
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) SELECT ?, ?, 'DO', '2019-20',"
                        + " 'COMPUTED', 'it.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM"
                        + " payroll_cycle_instance WHERE payroll_yymm = ? AND org_unit_id = ?)",
                yymm,
                unit,
                yymm,
                unit);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        yymm,
                        unit);
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, result_status, is_locked) VALUES (?, ?, ?,"
                        + " 'STAFF', ?, ?, 'ACTUAL', ?, 0, ?, 'CURRENT', ?)",
                cycle,
                unit,
                employee,
                yymm,
                Date.valueOf(month.atDay(1)),
                BigDecimal.valueOf(basic + hra),
                BigDecimal.valueOf(basic + hra),
                locked ? "Y" : "N");
        long id =
                jdbc.queryForObject(
                        "SELECT MAX(payroll_result_id) FROM payroll_result WHERE employee_id = ?"
                                + " AND payroll_yymm = ?",
                        Long.class,
                        employee,
                        yymm);
        for (int[] line : new int[][] {{101, basic}, {121, hra}}) {
            jdbc.update(
                    "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type, is_locked,"
                        + " payroll_yymm, period_date, legacy_pay_code) SELECT ?, ?, ?, ?,"
                        + " c.component_id, ?, 'ACTUAL', ?, ?, ?, c.legacy_pay_code FROM"
                        + " pay_component c WHERE c.legacy_pay_code = ?",
                    id,
                    cycle,
                    unit,
                    employee,
                    BigDecimal.valueOf(line[1]),
                    locked ? "Y" : "N",
                    yymm,
                    Date.valueOf(month.atDay(1)),
                    line[0]);
        }
        return id;
    }

    /**
     * SRS-7-12: April 2019 locked, May computed: the months with their lines; the year has April
     * actual, May onwards projected at May's pay; HRA (PARTLY taxable) counts in the taxable gross.
     */
    @Test
    void SRS_7_12_SRS_7_10_theTaxableYearIsBuiltFromTheStaffResults() {
        long employee =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                                + " 'ACTIVE' ORDER BY emp_num DESC OFFSET 5 ROWS FETCH NEXT 1 ROWS"
                                + " ONLY",
                        Long.class,
                        locationId("DH001"));
        result(employee, YearMonth.of(2019, 4), true, 30000, 6000);
        result(employee, YearMonth.of(2019, 5), false, 31000, 6200);

        List<StaffEarnings.Month> months = earnings.months(employee, "201904", "202003");
        assertThat(months)
                .extracting(StaffEarnings.Month::yymm)
                .containsExactly("201904", "201905");
        assertThat(months.get(0).locked()).isTrue();
        assertThat(months.get(1).locked()).isFalse();
        assertThat(months.get(0).taxableGross()).isEqualByComparingTo("36000");
        assertThat(months.get(0).lines())
                .extracting(StaffEarnings.Line::payCode)
                .containsExactly(101, 121);

        TaxableEarnings.Year year = taxable.year(employee, "2019-20").orElseThrow();
        assertThat(year.months().get(0).actual()).isTrue();
        assertThat(year.months().get(0).taxableGross()).isEqualByComparingTo("36000");
        assertThat(year.months().get(1).actual()).isFalse();
        assertThat(year.gross())
                .isEqualByComparingTo(
                        new BigDecimal("36000")
                                .add(new BigDecimal("37200").multiply(BigDecimal.valueOf(11))));
        assertThat(taxable.year(employee, "2018-19")).as("no result in that year").isEmpty();
    }
}
