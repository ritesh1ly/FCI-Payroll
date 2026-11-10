package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The staff payslip in eSeva (plan 16 phase 16e-3; SRS-1-10, SRS-17-1, SRS-17-7): the member's own
 * locked months only (no provisional payslip), one month's earnings and deductions (a deduction is
 * the negative row, shown positive), PAN masked; an open month is not found and a user without an
 * employee record has no payslip. Fixture results in 1999, which no other test computes.
 */
class PayslipIT extends ApiTestSupport {

    private long result(long employee, YearMonth month, boolean locked) {
        return result(
                employee,
                month,
                locked,
                new Object[][] {{101, "30000"}, {111, "15000"}, {904, "-5400"}});
    }

    private long result(long employee, YearMonth month, boolean locked, Object[][] lines) {
        String yymm = month.toString().replace("-", "");
        long unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = ?",
                        Long.class,
                        employee);
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) SELECT ?, ?, 'DO', '1998-99',"
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
                        + " 'STAFF', ?, ?, 'ACTUAL', 45000, 5400, 39600, 'CURRENT', ?)",
                cycle,
                unit,
                employee,
                yymm,
                Date.valueOf(month.atDay(1)),
                locked ? "Y" : "N");
        long id =
                jdbc.queryForObject(
                        "SELECT MAX(payroll_result_id) FROM payroll_result WHERE employee_id = ?"
                                + " AND payroll_yymm = ?",
                        Long.class,
                        employee,
                        yymm);
        for (Object[] line : lines) {
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
                    new BigDecimal((String) line[1]),
                    locked ? "Y" : "N",
                    yymm,
                    Date.valueOf(month.atDay(1)),
                    line[0]);
        }
        return id;
    }

    // SC-15-0037, SC-15-0040: a payslip only for a locked period. SC-14-0017: E-01 main flow (the
    // member views the payslip).
    @Test
    void SRS_17_1_theMemberSeesTheOwnLockedPayslipsOnly() throws Exception {
        long self =
                jdbc.queryForObject(
                        "SELECT employee_id FROM app_user WHERE login_id ="
                                + " 'emp.rsharma@fci-demo.test'",
                        Long.class);
        result(self, YearMonth.of(1999, 1), true);
        result(self, YearMonth.of(1999, 2), false);
        Session member = login("emp.rsharma@fci-demo.test", null, null);

        JsonNode months =
                body(mvc.perform(userGet(member, "/v1/payslips/mine?size=100")).andReturn());
        assertThat(months.toString()).contains("199901").doesNotContain("199902");

        JsonNode slip = body(mvc.perform(userGet(member, "/v1/payslips/mine/199901")).andReturn());
        assertThat(slip.get("net").decimalValue()).isEqualByComparingTo("39600");
        assertThat(slip.get("earnings")).hasSize(2);
        assertThat(slip.get("deductions")).hasSize(1);
        assertThat(slip.get("deductions").get(0).get("amount").decimalValue())
                .as("shown positive")
                .isEqualByComparingTo("5400");
        String pan = slip.path("panMasked").asString();
        assertThat(pan).matches("^\\*{6}[A-Z0-9]{4}$|^$");
        assertThat(slip.toString()).doesNotContain("\"panNumber\"");

        assertThat(
                        mvc.perform(userGet(member, "/v1/payslips/mine/199902"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("SRS-17-1: no provisional payslip")
                .isEqualTo(404);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        mvc.perform(userGet(maker, "/v1/payslips/mine"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }

    // US-GAP-292 (SRS-9-1, SRS-9-2, SRS-9-21; l.4005): the employee NPS is a deduction, the
    // employer NPS both an earning and a deduction (net unchanged), and the month's PRAN from the
    // HRMS record is on the payslip as a reference. Demo NPS codes 906 / 907 / 908 (V041_2_9).
    // SC-18-0017: the statutory result feeds the payslip (the EBS posting waits for module 23).
    @Test
    void SRS_9_21_thePayslipCarriesTheEmployeeAndEmployerNpsLinesAndThePran() throws Exception {
        long self =
                jdbc.queryForObject(
                        "SELECT employee_id FROM app_user WHERE login_id ="
                                + " 'emp.rsharma@fci-demo.test'",
                        Long.class);
        YearMonth month = YearMonth.of(1999, 3);
        result(
                self,
                month,
                true,
                new Object[][] {
                    {101, "30000"}, {908, "2100"}, {906, "-1500"}, {907, "-2100"}, {904, "-1800"}
                });
        jdbc.update(
                "INSERT INTO nps_deduction (employee_id, payroll_yymm, record_type, hrms_nps_flag,"
                        + " pran_reference, employee_amount, employer_amount, deducted_amount,"
                        + " mismatch_status, apply_status, period_date) VALUES (?, '199903',"
                        + " 'MONTHLY', 'Y', '110099887766', 1500, 2100, 1500, 'CONSISTENT',"
                        + " 'APPLIED', ?)",
                self,
                Date.valueOf(month.atDay(1)));
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        JsonNode slip = body(mvc.perform(userGet(member, "/v1/payslips/mine/199903")).andReturn());
        assertThat(slip.path("pran").asString()).isEqualTo("110099887766");
        assertThat(codes(slip.get("earnings"))).contains("908:2100");
        assertThat(codes(slip.get("deductions"))).contains("906:1500", "907:2100");
    }

    // NEW-G1-12 (US-04-06 AC2; SC-04-0007, SC-04-0032; OD-67): an output of a month that ended
    // before the staff number was reissued keeps the old number; the month of the reissue and
    // later ones carry the new number; a member never reissued keeps the current number.
    @Test
    void SRS_16_7_aMonthBeforeTheTransferKeepsTheOldStaffNumberOnThePayslip() throws Exception {
        long self =
                jdbc.queryForObject(
                        "SELECT employee_id FROM app_user WHERE login_id ="
                                + " 'emp.rsharma@fci-demo.test'",
                        Long.class);
        String current =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?", String.class, self);
        result(self, YearMonth.of(1999, 6), true);
        result(self, YearMonth.of(1999, 7), true);
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        assertThat(
                        body(mvc.perform(userGet(member, "/v1/payslips/mine/199906")).andReturn())
                                .path("eis")
                                .asString())
                .as("no reissue yet: the current number")
                .isEqualTo(current);
        // Reissued on the first day of July: June ended before it (boundary: the day after the
        // month's last day), July is the month of the reissue.
        jdbc.update(
                "INSERT INTO employee_number_history (employee_id, old_emp_num, new_emp_num,"
                        + " change_date, change_event, reason) VALUES (?, 'OLD19906', ?, ?,"
                        + " 'TRANSFER', 'IT inter-region transfer')",
                self,
                current,
                Date.valueOf("1999-07-01"));
        assertThat(
                        body(mvc.perform(userGet(member, "/v1/payslips/mine/199906")).andReturn())
                                .path("eis")
                                .asString())
                .as("a month before the transfer: the old number")
                .isEqualTo("OLD19906");
        assertThat(
                        body(mvc.perform(userGet(member, "/v1/payslips/mine/199907")).andReturn())
                                .path("eis")
                                .asString())
                .as("the month of the transfer: the new number")
                .isEqualTo(current);
    }

    /**
     * SC-15-0039 (l.3392), SC-15-0036: a payslip of a closed (locked) period is retained and
     * reproduced identically each time it is generated again - same lines, amounts and totals.
     */
    @Test
    void SC_15_0039_aPayslipRegeneratedForAClosedPeriodIsIdentical() throws Exception {
        long self =
                jdbc.queryForObject(
                        "SELECT employee_id FROM app_user WHERE login_id ="
                                + " 'emp.rsharma@fci-demo.test'",
                        Long.class);
        result(self, YearMonth.of(1998, 8), true);
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        String first =
                mvc.perform(userGet(member, "/v1/payslips/mine/199808"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body_(first).path("net").decimalValue()).isEqualByComparingTo("39600");
        Session again = login("emp.rsharma@fci-demo.test", null, null);
        String second =
                mvc.perform(userGet(again, "/v1/payslips/mine/199808"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(second).as("reproduced identically for the closed period").isEqualTo(first);
    }

    /**
     * BR-ESEVA-005 (FRS l.6974; US-GAP-263): the payslip layout is the staff one, read only from a
     * STAFF result - a labour result of the member is never shown as a staff payslip, in the list
     * or by month.
     */
    @Test
    void BR_ESEVA_005_aLabourResultIsNeverShownAsAStaffPayslip() throws Exception {
        long self =
                jdbc.queryForObject(
                        "SELECT employee_id FROM app_user WHERE login_id ="
                                + " 'emp.rsharma@fci-demo.test'",
                        Long.class);
        // Written unlocked, made a labour result, then locked (a locked result is immutable).
        long labour = result(self, YearMonth.of(1998, 9), false);
        jdbc.update(
                "UPDATE payroll_result_component SET is_locked = 'Y' WHERE payroll_result_id = ?",
                labour);
        jdbc.update(
                "UPDATE payroll_result SET payroll_type = 'DEPT_LABOUR', is_locked = 'Y' WHERE"
                        + " payroll_result_id = ?",
                labour);
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        assertThat(all(member, "/v1/payslips/mine").toString())
                .as("not listed")
                .doesNotContain("199809");
        assertThat(
                        mvc.perform(userGet(member, "/v1/payslips/mine/199809"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
    }

    private JsonNode body_(String text) throws Exception {
        return json.readTree(text);
    }

    private static java.util.List<String> codes(JsonNode lines) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode l : lines) {
            out.add(
                    l.path("payCode").asInt()
                            + ":"
                            + l.path("amount").decimalValue().stripTrailingZeros().toPlainString());
        }
        return out;
    }
}
