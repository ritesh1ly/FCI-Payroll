package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.nps.api.NpsReceipts;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Screen I-14 NPS Deduction (from HRMS) (plan 18 phase 18c; SRS-9-1a, SRS-9-2, SRS-9-6; D18-7): the
 * Payroll Maker of Delhi sees its unit's records only, Finance at Headquarters every unit; a
 * mismatch is listed first and counted; the register export and the NPS Deduction Report carry the
 * PRAN; a role without I-14 is refused; a month that is not one is refused. Records of a month far
 * ahead, so no cycle of another test meets them.
 */
class NpsScreenIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private NpsReceipts receipts;

    private String yymm;
    private String delhi;
    private String chennai;

    private String member(String office) {
        return jdbc.queryForObject(
                "SELECT MAX(e.emp_num) FROM employee e JOIN org_location l ON l.location_id ="
                        + " e.location_id WHERE l.location_code = ? AND e.record_status ="
                        + " 'ACTIVE'",
                String.class,
                office);
    }

    private void receive(String eis, boolean flag, String employee, String employer, String pran) {
        receipts.receive(
                "it.hrms",
                new NpsReceipts.Receipt(
                        eis,
                        yymm,
                        flag,
                        pran,
                        employee == null ? null : new BigDecimal(employee),
                        employer == null ? null : new BigDecimal(employer),
                        null));
    }

    @BeforeEach
    void records() {
        YearMonth m = YearMonth.now(IST).plusMonths(40);
        yymm = String.format("%04d%02d", m.getYear(), m.getMonthValue());
        delhi = member("DH001");
        chennai = member("CH001");
        receive(delhi, true, "1500", "2100", "110012345678");
        receive(chennai, true, null, "1800", "110087654321");
    }

    private JsonNode get(Session s, String path) throws Exception {
        var result = mvc.perform(userGet(s, path)).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return body(result);
    }

    @Test
    void D18_7_theUnitSeesItsOwnRecordsAndHeadquartersEveryUnit() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode own = get(maker, "/v1/nps-deductions?yymm=" + yymm + "&size=100");
        assertThat(texts(own.path("items"), "eis")).contains(delhi).doesNotContain(chennai);
        JsonNode row = own.path("items").get(0);
        assertThat(row.path("pran").asString()).isEqualTo("110012345678");
        assertThat(row.path("status").asString()).isEqualTo("RECEIVED");
        assertThat(
                        get(maker, "/v1/nps-deductions?yymm=" + yymm + "&eis=" + chennai)
                                .path("total")
                                .asLong())
                .as("another unit's member is not found")
                .isZero();

        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        JsonNode all = get(finance, "/v1/nps-deductions?yymm=" + yymm + "&size=100");
        assertThat(texts(all.path("items"), "eis")).contains(delhi, chennai);
        assertThat(all.path("items").get(0).path("status").asString())
                .as("SRS-9-1a: a mismatch first")
                .isEqualTo("FLAG_WITHOUT_AMOUNT");
        JsonNode counts = get(finance, "/v1/nps-deductions/counts?yymm=" + yymm);
        assertThat(texts(counts, "status")).contains("FLAG_WITHOUT_AMOUNT", "RECEIVED");
        assertThat(
                        get(
                                        finance,
                                        "/v1/nps-deductions?yymm="
                                                + yymm
                                                + "&status=FLAG_WITHOUT_AMOUNT&size=100")
                                .path("items")
                                .get(0)
                                .path("eis")
                                .asString())
                .isEqualTo(chennai);
    }

    // SC-16-0129: the PRAN is carried as received, a reference only.
    @Test
    void SRS_9_6_theExportAndTheReportCarryThePranAndOnlyConsistentRecordsAreReported()
            throws Exception {
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        String register = csv(finance, "/v1/nps-deductions/export?yymm=" + yymm + "&format=CSV");
        assertThat(register).contains("110012345678", "110087654321", "FLAG_WITHOUT_AMOUNT");
        String report =
                csv(
                        finance,
                        "/v1/nps-deductions/report?from=" + yymm + "&to=" + yymm + "&format=CSV");
        assertThat(report).contains("110012345678", delhi).doesNotContain("110087654321");
    }

    /**
     * SRS-G-45 / CLAUDE.md section 2: NPS amounts and the PRAN are HRMS-owned; Payroll offers no
     * way to key them. A create or a change on I-14 is refused and the record keeps what HRMS sent.
     */
    @Test
    void SRS_G_45_anNpsAmountOrPranKeyedInPayrollIsRefused() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Map<String, Object> keyed =
                Map.of(
                        "eis",
                        delhi,
                        "yymm",
                        yymm,
                        "pran",
                        "110099999999",
                        "employeeAmount",
                        9999,
                        "employerAmount",
                        9999);
        Long id =
                jdbc.queryForObject(
                        "SELECT n.nps_deduction_id FROM nps_deduction n JOIN employee e ON"
                                + " e.employee_id = n.employee_id WHERE e.emp_num = ? AND"
                                + " n.payroll_yymm = ? AND n.record_type = 'MONTHLY'",
                        Long.class,
                        delhi,
                        yymm);
        assertThat(
                        mvc.perform(userPost(maker, "/v1/nps-deductions", keyed))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("no create on I-14")
                .isEqualTo(405);
        for (var request :
                java.util.List.of(
                        userPut(maker, "/v1/nps-deductions/" + id, keyed),
                        userPost(maker, "/v1/nps-deductions/" + id, keyed))) {
            assertThat(mvc.perform(request).andReturn().getResponse().getStatus())
                    .as("no record to change")
                    .isEqualTo(404);
        }
        Map<String, Object> kept =
                jdbc.queryForMap(
                        "SELECT pran_reference, employee_amount, employer_amount FROM nps_deduction"
                                + " WHERE nps_deduction_id = ?",
                        id);
        assertThat(kept.get("PRAN_REFERENCE")).isEqualTo("110012345678");
        assertThat((BigDecimal) kept.get("EMPLOYEE_AMOUNT")).isEqualByComparingTo("1500");
        assertThat((BigDecimal) kept.get("EMPLOYER_AMOUNT")).isEqualByComparingTo("2100");
    }

    @Test
    void SRS_9_1_aRoleWithoutI14OrAMonthThatIsNotOneIsRefused() throws Exception {
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        assertThat(
                        mvc.perform(userGet(hr, "/v1/nps-deductions?yymm=" + yymm))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        for (String bad :
                new String[] {
                    "/v1/nps-deductions?yymm=202613",
                    "/v1/nps-deductions/counts?yymm=2026",
                    "/v1/nps-deductions/report?from=" + yymm + "&to=202001"
                }) {
            assertThat(mvc.perform(userGet(maker, bad)).andReturn().getResponse().getStatus())
                    .as(bad)
                    .isEqualTo(400);
        }
        Map<String, Object> noRows =
                jdbc.queryForMap(
                        "SELECT COUNT(*) AS n FROM nps_deduction WHERE payroll_yymm = '202001'");
        assertThat(((Number) noRows.get("N")).longValue()).isZero();
    }
}
