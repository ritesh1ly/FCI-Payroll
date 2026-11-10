package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * OD-144 payslip PDFs end to end (SRS-17-1, SRS-1-10, SRS-NF-5, SRS O-01 l.3392, R-RBAC, R-PII):
 * the member downloads the own locked month as a PDF with safe headers and a strong ETag (a repeat
 * is 304); an unlocked month, another member's month and an unknown month are 404 alike (no
 * existence leak); a staff user without E-01 SELF is 403; a member cannot reach the O-01 member
 * read (403); a maker of another office reads a member outside the scope as 404. Fixture results in
 * 1991, which no other test computes.
 */
class PayslipPdfIT extends ApiTestSupport {

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

    private long rsharma() {
        return jdbc.queryForObject(
                "SELECT employee_id FROM app_user WHERE login_id = 'emp.rsharma@fci-demo.test'",
                Long.class);
    }

    private static String text(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(d).replaceAll("\\s+", " ");
        }
    }

    private MockHttpServletResponse get(Session s, String path) throws Exception {
        return download(s, path).getResponse();
    }

    @Test
    void SRS_17_1_theMemberDownloadsTheOwnLockedMonthAsAPdfWithSafeHeaders() throws Exception {
        result(rsharma(), YearMonth.of(1991, 1), true);
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        MockHttpServletResponse pdf = get(member, "/v1/payslips/mine/199101/pdf");
        assertThat(pdf.getStatus()).isEqualTo(200);
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(pdf.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(pdf.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"payslip-199101.pdf\"");
        assertThat(pdf.getHeader("Cache-Control")).contains("private").contains("no-cache");
        byte[] bytes = pdf.getContentAsByteArray();
        String text = text(bytes);
        assertThat(text).contains("Pay Slip for the month of JANUARY 1991", "39,600.00");
        assertThat(text).doesNotContainPattern("[A-Z]{5}[0-9]{4}[A-Z]");
        // SRS O-01 l.3392: the same closed month gives the same bytes; the ETag answers 304.
        String etag = pdf.getHeader("ETag");
        assertThat(get(member, "/v1/payslips/mine/199101/pdf").getContentAsByteArray())
                .isEqualTo(bytes);
        MockHttpServletResponse again =
                mvc.perform(
                                userGet(member, "/v1/payslips/mine/199101/pdf")
                                        .header("If-None-Match", etag))
                        .andReturn()
                        .getResponse();
        assertThat(again.getStatus()).isEqualTo(304);
        assertThat(again.getContentAsByteArray()).isEmpty();
    }

    @Test
    void SRS_17_1_anUnlockedAnUnknownOrABadMonthIsNotFoundAlike() throws Exception {
        result(rsharma(), YearMonth.of(1991, 2), false);
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        assertThat(get(member, "/v1/payslips/mine/199102/pdf").getStatus()).isEqualTo(404);
        assertThat(get(member, "/v1/payslips/mine/199103/pdf").getStatus()).isEqualTo(404);
        assertThat(get(member, "/v1/payslips/mine/199113/pdf").getStatus()).isIn(400, 404);
    }

    @Test
    void R_RBAC_aStaffUserHasNoOwnPayslipPdfAndAMemberNoO01Read() throws Exception {
        result(rsharma(), YearMonth.of(1991, 4), true);
        String eis =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?",
                        String.class,
                        rsharma());
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(get(maker, "/v1/payslips/mine/199104/pdf").getStatus()).isEqualTo(403);
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        assertThat(get(member, "/v1/payslips/members/" + eis + "/199104/pdf").getStatus())
                .isEqualTo(403);
        assertThat(get(member, "/v1/payslip-bulk-files?yymm=199104").getStatus()).isEqualTo(403);
    }

    @Test
    void SRS_G_2_aMemberOutsideTheMakersOfficesIsNotFound() throws Exception {
        result(rsharma(), YearMonth.of(1991, 5), true);
        String eis =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?",
                        String.class,
                        rsharma());
        Session elsewhere = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(get(elsewhere, "/v1/payslips/members/" + eis + "/199105/pdf").getStatus())
                .isIn(403, 404);
        Session inScope = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MockHttpServletResponse pdf = get(inScope, "/v1/payslips/members/" + eis + "/199105/pdf");
        assertThat(pdf.getStatus()).isEqualTo(200);
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
    }

    @Test
    void OD_149_theMemberBulkIsAZipOfOwnLockedMonths_oneMonthIsAPdf_anOpenMonthIsNotFound()
            throws Exception {
        result(rsharma(), YearMonth.of(1991, 6), true);
        result(rsharma(), YearMonth.of(1991, 7), true);
        result(rsharma(), YearMonth.of(1991, 8), false);
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        // The demo profile's setting (OD-149 D-4).
        assertThat(get(member, "/v1/payslips/mine/bulk-settings").getContentAsString())
                .contains("\"maxPeriods\":12");
        MockHttpServletResponse zip = get(member, "/v1/payslips/mine/zip?months=199107,199106");
        assertThat(zip.getStatus()).isEqualTo(200);
        assertThat(zip.getContentType()).isEqualTo("application/zip");
        assertThat(zip.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"payslips-199106-199107.zip\"");
        java.util.List<String> names = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream in =
                new java.util.zip.ZipInputStream(
                        new java.io.ByteArrayInputStream(zip.getContentAsByteArray()))) {
            for (java.util.zip.ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).containsExactly("payslip-199106.pdf", "payslip-199107.pdf");
        assertThat(
                        get(member, "/v1/payslips/mine/zip?months=199107,199106")
                                .getContentAsByteArray())
                .as("the same request, the same bytes")
                .isEqualTo(zip.getContentAsByteArray());
        MockHttpServletResponse one = get(member, "/v1/payslips/mine/zip?months=199106");
        assertThat(one.getContentType()).isEqualTo("application/pdf");
        // An open month with a locked one: the whole request is not found (no existence leak).
        assertThat(get(member, "/v1/payslips/mine/zip?months=199106,199108").getStatus())
                .isEqualTo(404);
        StringBuilder thirteen = new StringBuilder("199601");
        for (int m = 2; m <= 12; m++) {
            thirteen.append(",1996").append(String.format("%02d", m));
        }
        thirteen.append(",199101");
        MockHttpServletResponse tooMany = get(member, "/v1/payslips/mine/zip?months=" + thirteen);
        assertThat(tooMany.getStatus()).isEqualTo(422);
        assertThat(tooMany.getContentAsString()).contains("PDF-422-TOO-MANY");
        assertThat(get(member, "/v1/payslips/mine/zip?months=1991-06").getStatus()).isEqualTo(400);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(get(maker, "/v1/payslips/mine/zip?months=199106,199107").getStatus())
                .isEqualTo(403);
    }

    @Test
    void SRS_NF_2_theO01BulkOfAnOpenMonthIsRefusedAndAMemberCannotAskForIt() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MockHttpServletResponse open =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/payslip-bulk-files",
                                        java.util.Map.of("yymm", "199612", "level", "STAFF")))
                        .andReturn()
                        .getResponse();
        assertThat(open.getStatus()).isEqualTo(422);
        assertThat(get(maker, "/v1/payslip-bulk-files?yymm=199612&level=STAFF").getStatus())
                .isEqualTo(204);
        assertThat(get(maker, "/v1/payslip-bulk-files/999999/file").getStatus()).isEqualTo(404);
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        assertThat(get(member, "/v1/payslip-bulk-files?yymm=199612").getStatus()).isEqualTo(403);
    }

    @Test
    void SRS_NF_2_aSecondRequestWhileOneIsOpenIsRefused_andTheFileIsTheRequestersOnlyOnceReady()
            throws Exception {
        result(rsharma(), YearMonth.of(1991, 9), true);
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'LOCKED' WHERE payroll_yymm ="
                        + " '199109'");
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        java.util.Map<String, String> body = java.util.Map.of("yymm", "199109", "level", "STAFF");
        MockHttpServletResponse first =
                mvc.perform(userPost(maker, "/v1/payslip-bulk-files", body))
                        .andReturn()
                        .getResponse();
        assertThat(first.getStatus()).isEqualTo(202);
        long id =
                body(mvc.perform(userGet(maker, "/v1/payslip-bulk-files?yymm=199109&level=STAFF"))
                                .andReturn())
                        .path("jobRequestId")
                        .asLong();
        assertThat(id).isPositive();
        MockHttpServletResponse second =
                mvc.perform(userPost(maker, "/v1/payslip-bulk-files", body))
                        .andReturn()
                        .getResponse();
        assertThat(second.getStatus()).as("the same request is still open").isEqualTo(409);
        // Not run yet (no batch instance here): 409 with its state, never a file.
        MockHttpServletResponse unfinished = get(maker, "/v1/payslip-bulk-files/" + id + "/file");
        assertThat(unfinished.getStatus()).isEqualTo(409);
        assertThat(unfinished.getContentAsString()).contains("PDF-409-NOT-READY");
        // Another user's request reads as none.
        Session other = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        int otherStatus = get(other, "/v1/payslip-bulk-files/" + id + "/file").getStatus();
        assertThat(otherStatus).isIn(403, 404);
        Session elsewhere = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(get(elsewhere, "/v1/payslip-bulk-files/" + id + "/file").getStatus())
                .isEqualTo(404);
    }
}
