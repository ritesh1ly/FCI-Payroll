package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Module 20 phase 20e, second part (plan docs/plan/20-labour-common-departmental.md; SRS-2-1,
 * SRS-2-31, SRS-2-46, SRS-2-49, SRS-2-30, SRS-10-19; D20-16, D20-17): on fixture gangs of Depot
 * Okhla, HR adds a free labour to a gang, makes a handling member the Sardar (the outgoing Sardar
 * stays as handling labour), moves a handling labour to Permanent Light Duty (designation
 * Ancillary, the depot's Ancillary Pool) and separates a labour (membership and record end, the
 * person is SEPARATED and the Full &amp; Final case opens, entered in Payroll); the Payroll Checker
 * approves each. Refused: a labour already in a gang, a leader who is not a handling member,
 * Protected pay without its Basic, a separation type outside the Labour Master's four.
 */
class LabourLifecycleIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long okhla() {
        return id("SELECT location_id FROM org_location WHERE location_code = 'DP-DL01'");
    }

    private long gang(String code, LocalDate today) {
        jdbc.update(
                "INSERT INTO labour_gang (gang_code, gang_name, depot_location_id, payroll_type,"
                        + " gang_type, gang_status, effective_from) VALUES (?, ?, ?, 'DEPT_LABOUR',"
                        + " 'HANDLING', 'ACTIVE', ?)",
                code,
                "Fixture " + code,
                okhla(),
                Date.valueOf(today.minusYears(1)));
        return id("SELECT gang_id FROM labour_gang WHERE gang_code = ?", code);
    }

    private long labour(String code, String pan, Long gangId, String role, LocalDate from) {
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
        if (gangId != null) {
            jdbc.update(
                    "INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role,"
                            + " effective_from) VALUES (?, ?, ?, ?)",
                    gangId,
                    employee,
                    role,
                    Date.valueOf(from));
        }
        return employee;
    }

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private void approve(MvcResult sent) throws Exception {
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                body(sent).get("id").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private String role(long gangId, long employee, LocalDate day) {
        return jdbc.queryForObject(
                "SELECT gang_role FROM labour_gang_membership WHERE gang_id = ? AND employee_id = ?"
                    + " AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)",
                String.class,
                gangId,
                employee,
                Date.valueOf(day),
                Date.valueOf(day));
    }

    // SC-20-0131: the gang keeps one Sardar when the leader changes. SC-20-0119: a free labour
    // of the depot is assigned to an active gang.
    @Test
    void SRS_2_1_aLabourJoinsAndAHandlingMemberBecomesTheSardar() throws Exception {
        LocalDate today = LocalDate.now(IST);
        long gang = gang("DP-DL01-LCA", today);
        long sardar = labour("LLC001", "DMOPL0001A", gang, "SARDAR", today.minusDays(5));
        long handling = labour("LLC002", "DMOPL0002B", gang, "HANDLING", today.minusDays(5));
        long free = labour("LLC003", "DMOPL0003C", null, null, today);
        Session hr = hr();
        LocalDate from = today.plusDays(1);

        approve(
                post(
                        hr,
                        "/v1/labour/gangs/" + gang + "/members",
                        Map.of(
                                "employeeId",
                                free,
                                "from",
                                from.toString(),
                                "reason",
                                "New joiner")));
        assertThat(role(gang, free, from)).isEqualTo("HANDLING");
        assertThat(
                        body(post(
                                        hr,
                                        "/v1/labour/gangs/" + gang + "/members",
                                        Map.of(
                                                "employeeId",
                                                free,
                                                "from",
                                                from.plusDays(3).toString(),
                                                "reason",
                                                "Again")))
                                .toString())
                .contains("MEMBER_IN_ANOTHER_GANG");

        assertThat(
                        body(post(
                                        hr,
                                        "/v1/labour/gangs/" + gang + "/leaders",
                                        Map.of(
                                                "role",
                                                "SARDAR",
                                                "employeeId",
                                                sardar,
                                                "from",
                                                from.toString(),
                                                "reason",
                                                "x")))
                                .toString())
                .as("the Sardar is not a handling member")
                .contains("NOT_A_HANDLING_MEMBER");
        LocalDate change = today.plusDays(2);
        approve(
                post(
                        hr,
                        "/v1/labour/gangs/" + gang + "/leaders",
                        Map.of(
                                "role",
                                "SARDAR",
                                "employeeId",
                                handling,
                                "from",
                                change.toString(),
                                "reason",
                                "Promotion")));
        assertThat(role(gang, handling, change)).isEqualTo("SARDAR");
        assertThat(role(gang, sardar, change)).isEqualTo("HANDLING");
        assertThat(role(gang, sardar, change.minusDays(1))).isEqualTo("SARDAR");
    }

    // SC-20-0111: a designation change with reason, effective date, order and the Light Duty pay
    // option; SC-20-0119: ancillary labour belong to the depot's Ancillary Pool; SC-20-0123: the
    // status (Separated, Retired) follows the approved transaction.
    @Test
    void SRS_2_49_aHandlingLabourMovesToLightDutyAndAnotherIsSeparated() throws Exception {
        LocalDate today = LocalDate.now(IST);
        long gang = gang("DP-DL01-LCB", today);
        long worker = labour("LLC004", "DMOPL0004D", gang, "HANDLING", today.minusDays(5));
        long leaver = labour("LLC005", "DMOPL0005E", gang, "HANDLING", today.minusDays(5));
        long ancillary =
                id("SELECT designation_id FROM org_designation WHERE designation_code = 'ANC'");
        Session hr = hr();
        LocalDate from = today.plusDays(1);
        Map<String, Object> order = new HashMap<>();
        order.put("toDesignationId", ancillary);
        order.put("effectiveDate", from.toString());
        order.put("payOption", "PROTECTED_PAY");
        order.put("orderReference", "MB/LD/7");
        order.put("orderDate", today.toString());
        order.put("issuingAuthority", "Medical Board");
        order.put("reason", "Medical board finding");
        assertThat(
                        body(post(hr, "/v1/labour/members/" + worker + "/light-duty-orders", order))
                                .toString())
                .contains("PROTECTED_BASIC");
        order.put("protectedBasic", 21500);
        approve(post(hr, "/v1/labour/members/" + worker + "/light-duty-orders", order));
        long pool = id("SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-DL01-G001'");
        assertThat(role(pool, worker, from)).isEqualTo("ANCILLARY");
        assertThat(id("SELECT designation_id FROM employee WHERE employee_id = ?", worker))
                .isEqualTo(ancillary);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT light_duty_status FROM labour_light_duty WHERE employee_id"
                                        + " = ?",
                                String.class,
                                worker))
                .isEqualTo("APPROVED");

        assertThat(
                        post(
                                        hr,
                                        "/v1/labour/members/" + leaver + "/separations",
                                        Map.of(
                                                "type",
                                                "ABSCONDED",
                                                "date",
                                                from.toString(),
                                                "reason",
                                                "x"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        // SRS l.4429: the body of an older client (no order) is answered with the ORDER refusal.
        assertThat(
                        post(
                                        hr,
                                        "/v1/labour/members/" + leaver + "/separations",
                                        Map.of(
                                                "type",
                                                "RESIGNATION",
                                                "date",
                                                from.toString(),
                                                "reason",
                                                "Resigned, letter 12"))
                                .getResponse()
                                .getContentAsString())
                .contains("ORDER");
        approve(
                post(
                        hr,
                        "/v1/labour/members/" + leaver + "/separations",
                        Map.of(
                                "type",
                                "RESIGNATION",
                                "date",
                                from.toString(),
                                "reason",
                                "Resigned, letter 12",
                                "orderReference",
                                "SO/RES/12",
                                "orderDate",
                                LocalDate.now(IST).toString(),
                                "issuingAuthority",
                                "Area Manager")));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT employment_status FROM employee WHERE employee_id = ?",
                                String.class,
                                leaver))
                .isEqualTo("SEPARATED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT labour_status FROM labour_profile WHERE employee_id = ?",
                                String.class,
                                leaver))
                .isEqualTo("SEPARATED");
        assertThat(role(gang, leaver, today)).isEqualTo("HANDLING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM labour_gang_membership WHERE employee_id = ?"
                                        + " AND (effective_to IS NULL OR effective_to >= ?)",
                                Integer.class,
                                leaver,
                                Date.valueOf(from)))
                .isZero();
        Map<String, Object> fnf =
                jdbc.queryForMap(
                        "SELECT separation_type, separation_source FROM settlement_case WHERE"
                                + " employee_id = ?",
                        leaver);
        assertThat(fnf.get("SEPARATION_TYPE")).isEqualTo("RESIGNATION");
        assertThat(fnf.get("SEPARATION_SOURCE")).isEqualTo("ENT");
    }
}
