package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Group g5 closing pass (PENDING 2i) on fixture gangs and labour of Depot Okhla: a new Permanent
 * Light Duty order changes only the pay option (SRS-2-49 l.4404; G5-26), the I-01 grid carries the
 * depot's Attendance Source from the HQ master (SRS-2-36, I-01 l.4543; G5-11) and the M-02 gang
 * search finds the members of a chosen date (SRS-2-40 l.4396; G5-55). Wrong role, an office outside
 * the scope and the maker approving their own order are refused.
 */
class LabourG5ClosingIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long okhla() {
        return id("SELECT location_id FROM org_location WHERE location_code = 'DP-DL01'");
    }

    private long gang(String code, LocalDate from) {
        jdbc.update(
                "INSERT INTO labour_gang (gang_code, gang_name, depot_location_id, payroll_type,"
                        + " gang_type, gang_status, effective_from) VALUES (?, ?, ?, 'DEPT_LABOUR',"
                        + " 'HANDLING', 'ACTIVE', ?)",
                code,
                "Fixture " + code,
                okhla(),
                Date.valueOf(from));
        return id("SELECT gang_id FROM labour_gang WHERE gang_code = ?", code);
    }

    private long labour(String code, String pan, long gangId, LocalDate from, LocalDate to) {
        jdbc.update(
                "INSERT INTO employee (emp_num, first_name, last_name, birth_date, gender,"
                    + " comp_joining_date, employee_type, emp_lbr_flag, pan_number, designation_id,"
                    + " location_id, depot_location_id, provident_fund_type, parent_zone_id,"
                    + " employment_status, record_status, record_origin) SELECT ?, 'Fixture', ?,"
                    + " DATE '1990-01-15', 'M', DATE '2015-07-01', 'DEPT_LABOUR', 'Y', ?,"
                    + " d.designation_id, ?, ?, 'NONE', l.zone_id, 'IN_SERVICE', 'ACTIVE',"
                    + " 'ENTERED' FROM org_designation d JOIN org_location l ON l.location_id = ?"
                    + " WHERE d.designation_code = 'HL'",
                code,
                code,
                pan,
                okhla(),
                okhla(),
                okhla());
        long employee = id("SELECT employee_id FROM employee WHERE emp_num = ?", code);
        jdbc.update(
                "INSERT INTO labour_profile (employee_id, labour_number, payroll_type,"
                        + " depot_location_id, pf_applicable_flag, labour_status, effective_from)"
                        + " VALUES (?, ?, 'DEPT_LABOUR', ?, 'N', 'ACTIVE', DATE '2015-07-01')",
                employee,
                code,
                okhla());
        jdbc.update(
                "INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role,"
                        + " effective_from, effective_to) VALUES (?, ?, 'HANDLING', ?, ?)",
                gangId,
                employee,
                Date.valueOf(from),
                to == null ? null : Date.valueOf(to));
        return employee;
    }

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private long submitted(MvcResult sent) throws Exception {
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        return body(sent).get("id").asLong();
    }

    private String status(long lightDutyId) {
        return jdbc.queryForObject(
                "SELECT light_duty_status FROM labour_light_duty WHERE light_duty_id = ?",
                String.class,
                lightDutyId);
    }

    // SC-20-0111 / US-20-19 AC3: the Light Duty pay option is kept in history and changes only by
    // a new approved transaction.
    @Test
    void SRS_2_49_aNewApprovedOrderChangesOnlyThePayOptionAndKeepsTheOld() throws Exception {
        LocalDate today = LocalDate.now(IST);
        long gang = gang("DP-DL01-G5C1", today.minusYears(1));
        long worker = labour("LG5C01", "QZXPL0501F", gang, today.minusDays(5), null);
        long ancillary =
                id("SELECT designation_id FROM org_designation WHERE designation_code = 'ANC'");
        Session hr = hr();
        Session checker = checker();
        Map<String, Object> order = new HashMap<>();
        order.put("toDesignationId", ancillary);
        order.put("effectiveDate", today.plusDays(1).toString());
        order.put("payOption", "PROTECTED_PAY");
        order.put("protectedBasic", 21500);
        order.put("orderReference", "MB/LD/G5C");
        order.put("orderDate", today.toString());
        order.put("issuingAuthority", "Medical Board");
        order.put("reason", "Medical board finding");
        String path = "/v1/labour/members/" + worker + "/light-duty-orders";
        assertThat(decide(checker, submitted(post(hr, path, order)), "APPROVE", null, null))
                .isEqualTo(200);
        long first =
                id(
                        "SELECT light_duty_id FROM labour_light_duty WHERE employee_id = ? AND"
                                + " light_duty_status = 'APPROVED'",
                        worker);
        long poolRows =
                id(
                        "SELECT COUNT(*) FROM labour_gang_membership WHERE employee_id = ? AND"
                                + " gang_role = 'ANCILLARY'",
                        worker);

        // The option already in force is refused.
        order.put("effectiveDate", today.plusDays(2).toString());
        assertThat(post(hr, path, order).getResponse().getContentAsString())
                .contains("SAME_PAY_OPTION");
        // The Payroll Maker views only.
        order.put("payOption", "ANCILLARY_PAY");
        order.remove("protectedBasic");
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(post(maker, path, order).getResponse().getStatus()).isEqualTo(403);
        // HR of another office does not see the labour.
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        assertThat(post(chennai, path, order).getResponse().getStatus()).isIn(403, 404);

        long change = submitted(post(hr, path, order));
        long second =
                id(
                        "SELECT light_duty_id FROM labour_light_duty WHERE employee_id = ? AND"
                                + " light_duty_status = 'PENDING_APPROVAL'",
                        worker);
        assertThat(
                        id(
                                "SELECT supersedes_light_duty_id FROM labour_light_duty WHERE"
                                        + " light_duty_id = ?",
                                second))
                .isEqualTo(first);
        // A second order while one is with the Checker is refused.
        assertThat(post(hr, path, order).getResponse().getContentAsString()).contains("PENDING");
        // The maker cannot approve their own order.
        assertThat(decide(hr, change, "APPROVE", null, null)).isNotEqualTo(200);
        assertThat(status(second)).isEqualTo("PENDING_APPROVAL");

        assertThat(decide(checker, change, "APPROVE", null, null)).isEqualTo(200);
        assertThat(status(first)).as("the old choice stays in history").isEqualTo("SUPERSEDED");
        assertThat(status(second)).isEqualTo("APPROVED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pay_option FROM labour_light_duty WHERE light_duty_id = ?",
                                String.class,
                                second))
                .isEqualTo("ANCILLARY_PAY");
        assertThat(id("SELECT designation_id FROM employee WHERE employee_id = ?", worker))
                .as("the designation does not change again")
                .isEqualTo(ancillary);
        assertThat(
                        id(
                                "SELECT COUNT(*) FROM labour_gang_membership WHERE employee_id = ?"
                                        + " AND gang_role = 'ANCILLARY'",
                                worker))
                .as("the pool membership does not move")
                .isEqualTo(poolRows);
        assertThat(
                        id(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'labour_light_duty' AND field_name = 'pay_option' AND"
                                        + " old_value = 'PROTECTED_PAY' AND new_value ="
                                        + " 'ANCILLARY_PAY'"))
                .isGreaterThanOrEqualTo(1);
    }

    // SRS-2-36: the depot's Attendance Source from the HQ master, read-only on I-01.
    @Test
    void SRS_2_36_theI01GridCarriesTheDepotsAttendanceSourceOrNone() throws Exception {
        LocalDate today = LocalDate.now(IST);
        long gang = gang("DP-DL01-G5C2", today.minusYears(1));
        labour("LG5C02", "QZXPL0502G", gang, today.minusYears(1), null);
        Session ldh = login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
        String month = YearMonth.from(today).toString();
        String path = "/v1/labour/gangs/" + gang + "/attendance?month=" + month;
        JsonNode before = body(mvc.perform(userGet(ldh, path)).andReturn());
        Integer rows =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM attendance_source_master WHERE location_id = ?",
                        Integer.class,
                        okhla());
        if (rows != null && rows == 0) {
            assertThat(before.get("attendanceSource").isNull()).as("not configured").isTrue();
            jdbc.update(
                    "INSERT INTO attendance_source_master (location_id, attendance_mode,"
                            + " anna_darpan_flag, source_status, fallback_to_manual,"
                            + " effective_from) VALUES (?, 'BOTH', 'Y', 'PILOT', 'N', ?)",
                    okhla(),
                    Date.valueOf(today.minusYears(2)));
        }
        JsonNode source = body(mvc.perform(userGet(ldh, path)).andReturn()).get("attendanceSource");
        assertThat(source.get("mode").asString()).isIn("MANUAL", "BIOMETRIC", "BOTH");
        assertThat(source.get("status").asString()).isIn("PILOT", "LIVE", "SUSPENDED");
        assertThat(source.has("annaDarpan")).isTrue();
        assertThat(source.has("fallbackToManual")).isTrue();
        // An office outside the session's scope does not read the grid.
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        assertThat(mvc.perform(userGet(chennai, path)).andReturn().getResponse().getStatus())
                .isIn(403, 404);
    }

    // SRS-2-40: labour are selected by their gang membership in the period queried, not today's.
    @Test
    void SRS_2_40_aGangSearchFindsTheMembersOfTheChosenDate() throws Exception {
        LocalDate today = LocalDate.now(IST);
        String code = "DP-DL01-G5C3";
        long gang = gang(code, today.minusYears(1));
        labour("LG5C03", "QZXPL0503H", gang, today.minusDays(30), today.minusDays(10));
        Session hr = hr();
        String base = "/v1/labour/members?gang=" + code;
        assertThat(body(mvc.perform(userGet(hr, base)).andReturn()).get("items"))
                .as("no longer in the gang today")
                .isEmpty();
        JsonNode then =
                body(mvc.perform(userGet(hr, base + "&asOf=" + today.minusDays(20))).andReturn());
        assertThat(then.get("items")).hasSize(1);
        assertThat(then.get("items").get(0).get("labourCode").asString()).isEqualTo("LG5C03");
        // the boundaries: the first and last day in the gang, the day before and after
        assertThat(
                        body(mvc.perform(userGet(hr, base + "&asOf=" + today.minusDays(30)))
                                        .andReturn())
                                .get("items"))
                .hasSize(1);
        assertThat(
                        body(mvc.perform(userGet(hr, base + "&asOf=" + today.minusDays(10)))
                                        .andReturn())
                                .get("items"))
                .hasSize(1);
        assertThat(
                        body(mvc.perform(userGet(hr, base + "&asOf=" + today.minusDays(31)))
                                        .andReturn())
                                .get("items"))
                .isEmpty();
        assertThat(
                        body(mvc.perform(userGet(hr, base + "&asOf=" + today.minusDays(9)))
                                        .andReturn())
                                .get("items"))
                .isEmpty();
        // a malformed date is refused
        assertThat(
                        mvc.perform(userGet(hr, base + "&asOf=2026-13-01"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }
}
