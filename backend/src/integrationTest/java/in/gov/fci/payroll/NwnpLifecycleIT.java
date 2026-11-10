package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 22 use cases of the NWNP labour life cycle (test-scope batch E, 2026-11-04; SRS-2-30,
 * SRS-5-4, SRS-5-14, SRS-10-19; UC "NWNP Employee Joining", "NWNP Separation &amp; F&amp;F", "NWNP
 * Master Search", SRS l.4956-4989): Establishment / HR of Delhi records an NWNP labour on M-02, the
 * Payroll Checker approves it and it gets its NWN code; HR adds it to an NWNP gang and the Labour
 * Dealing Hand takes it on that gang's work-slip (pay rests on the slip, no attendance). An NWNP
 * labour is separated with its order: the record is SEPARATED, its membership ends the day before,
 * the Full &amp; Final case opens, and a later work-slip naming it is refused. The NWNP master
 * search returns the actor's scope only.
 *
 * <p>Fixture gangs DP-DL01-NJ1 / DP-DL01-NS1 and labour NWJ001 / NWJ002 are created here (fresh
 * codes, never a demo member).
 */
class NwnpLifecycleIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long okhla() {
        return id("SELECT location_id FROM org_location WHERE location_code = 'DP-DL01'");
    }

    private long designation(String code) {
        return id("SELECT designation_id FROM org_designation WHERE designation_code = ?", code);
    }

    private long nwnpGang(String code) {
        Integer there =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang WHERE gang_code = ?",
                        Integer.class,
                        code);
        if (there == null || there == 0) {
            jdbc.update(
                    "INSERT INTO labour_gang (gang_code, gang_name, depot_location_id,"
                            + " payroll_type, gang_type, gang_status, effective_from) VALUES (?,"
                            + " ?, ?, 'NWNP_LABOUR', 'HANDLING', 'ACTIVE', ?)",
                    code,
                    "Fixture " + code,
                    okhla(),
                    Date.valueOf(LocalDate.now(IST).minusYears(1)));
        }
        return id("SELECT gang_id FROM labour_gang WHERE gang_code = ?", code);
    }

    /** An NWNP labour of Okhla in {@code gang} since 30 days ago (SQL fixture). */
    private long fixtureLabour(String code, String pan, long gang) {
        jdbc.update(
                "INSERT INTO employee (emp_num, first_name, last_name, birth_date, gender,"
                    + " comp_joining_date, employee_type, emp_lbr_flag, pan_number, designation_id,"
                    + " location_id, depot_location_id, provident_fund_type, parent_zone_id,"
                    + " employment_status, record_status, record_origin) SELECT ?, 'Fixture', ?,"
                    + " DATE '1990-01-15', 'M', DATE '2015-07-01', 'NWNP_LABOUR', 'Y', ?,"
                    + " d.designation_id, ?, ?, 'NONE', l.zone_id, 'IN_SERVICE', 'ACTIVE',"
                    + " 'ENTERED' FROM org_designation d JOIN org_location l ON l.location_id = ?"
                    + " WHERE d.designation_code = 'NHL'",
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
                        + " VALUES (?, ?, 'NWNP_LABOUR', ?, 'N', 'ACTIVE', DATE '2015-07-01')",
                employee,
                code,
                okhla());
        jdbc.update(
                "INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role,"
                        + " effective_from) VALUES (?, ?, 'HANDLING', ?)",
                gang,
                employee,
                Date.valueOf(LocalDate.now(IST).minusDays(30)));
        return employee;
    }

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private Session ldh() throws Exception {
        return login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private static int code(MvcResult r) {
        return r.getResponse().getStatus();
    }

    /** Submitted (202), then the Delhi Payroll Checker approves; answers the approval id. */
    private long approve(MvcResult sent) throws Exception {
        assertThat(code(sent)).as(sent.getResponse().getContentAsString()).isEqualTo(202);
        long approval = body(sent).get("id").asLong();
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        return approval;
    }

    private Map<String, Object> slip(LocalDate day, List<Long> who) {
        Map<String, Object> r = new HashMap<>();
        r.put(
                "operationId",
                id("SELECT operation_id FROM operation_master WHERE operation_code = 'W2P'"));
        r.put("bagTypeId", id("SELECT bag_type_id FROM bag_type WHERE bag_type_code = 'KATTA50'"));
        r.put("bags", 120);
        r.put("start", "09:00");
        r.put("end", "12:00");
        Map<String, Object> s = new HashMap<>();
        s.put("date", day.toString());
        s.put("continueWork", false);
        s.put("rows", List.of(r));
        s.put("participants", who);
        return s;
    }

    private MvcResult saveSlip(Session s, long gang, Map<String, Object> slip) throws Exception {
        return post(s, "/v1/labour/gangs/" + gang + "/work-slips", slip);
    }

    // SC-22-0034 (NWNP Employee Joining, main flow), SC-22-0035 (verify: available to NWNP
    // attendance, work-slip and computation), SC-22-0038 (NWNP Master Search, main flow).
    @Test
    void SRS_2_30_anNwnpLabourJoinsIsApprovedAndIsTakenOnTheGangsWorkSlip() throws Exception {
        LocalDate today = LocalDate.now(IST);
        Session hr = hr();
        Map<String, Object> m = new HashMap<>();
        m.put("payrollType", "NWNP_LABOUR");
        m.put("firstName", "Joginder");
        m.put("lastName", "Nwnp");
        m.put("gender", "M");
        m.put("birthDate", "1988-03-10");
        m.put("joiningDate", today.minusDays(3).toString());
        m.put("designationId", designation("NHL"));
        m.put("depotLocationId", okhla());
        m.put("panNumber", "DMOPN7101N");
        m.put("ifsc", "SBIN0DEMO01");
        m.put("bankAccount", "0000097101");
        m.put("mobile", "9000097101");
        m.put("pfApplicable", false);
        m.put("reason", "NWNP appointment order 7101");
        approve(post(hr, "/v1/labour/members", m));
        long joined = id("SELECT employee_id FROM employee WHERE pan_number = 'DMOPN7101N'");
        Map<String, Object> profile =
                jdbc.queryForMap(
                        "SELECT p.labour_number, p.payroll_type, p.labour_status, e.employee_type"
                                + " FROM labour_profile p JOIN employee e ON e.employee_id ="
                                + " p.employee_id WHERE p.employee_id = ?",
                        joined);
        assertThat((String) profile.get("LABOUR_NUMBER")).matches("NWN-\\d{6}");
        assertThat(profile.get("PAYROLL_TYPE")).isEqualTo("NWNP_LABOUR");
        assertThat(profile.get("EMPLOYEE_TYPE")).isEqualTo("NWNP_LABOUR");
        assertThat(profile.get("LABOUR_STATUS")).isEqualTo("ACTIVE");

        // NWNP Master Search: by worker name and payroll type, within the actor's scope.
        JsonNode found =
                body(
                        mvc.perform(
                                        userGet(
                                                hr,
                                                "/v1/labour/members?payrollType=NWNP_LABOUR"
                                                        + "&text=JOGINDER&size=10"))
                                .andReturn());
        assertThat(found.get("total").asLong()).isEqualTo(1);
        assertThat(found.get("items").get(0).get("labourCode").asString())
                .isEqualTo(profile.get("LABOUR_NUMBER"));
        assertThat(
                        body(mvc.perform(
                                                userGet(
                                                        hr,
                                                        "/v1/labour/members?payrollType=DEPT_LABOUR"
                                                                + "&text=JOGINDER&size=10"))
                                        .andReturn())
                                .get("total")
                                .asLong())
                .as("another payroll type")
                .isZero();
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        MvcResult outside =
                mvc.perform(
                                userGet(
                                        chennai,
                                        "/v1/labour/members?payrollType=NWNP_LABOUR"
                                                + "&text=JOGINDER&size=10"))
                        .andReturn();
        assertThat(code(outside) == 200 ? body(outside).get("total").asLong() : 0L)
                .as("another office's search does not return it")
                .isZero();

        // Joining the NWNP gang, then the gang's work-slip takes the labour (SRS-5-14: no
        // attendance codes; pay rests on the slip).
        long gang = nwnpGang("DP-DL01-NJ1");
        approve(
                post(
                        hr,
                        "/v1/labour/gangs/" + gang + "/members",
                        Map.of(
                                "employeeId",
                                joined,
                                "from",
                                today.toString(),
                                "reason",
                                "Joined the NWNP gang")));
        MvcResult taken = saveSlip(ldh(), gang, slip(today, List.of(joined)));
        assertThat(code(taken)).as(taken.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(taken).get("labourStrength").asInt()).isEqualTo(1);
    }

    // SC-22-0036 (NWNP Separation & F&F, main flow), SC-22-0037 (verify: the record is separated
    // and excluded from later attendance, work-slip and computation cycles).
    @Test
    void SRS_10_19_anNwnpLabourIsSeparatedWithItsOrderAndLeftOffLaterWorkSlips() throws Exception {
        LocalDate today = LocalDate.now(IST);
        long gang = nwnpGang("DP-DL01-NS1");
        long leaver = fixtureLabour("NWJ001", "DMOPN7201A", gang);
        long stays = fixtureLabour("NWJ002", "DMOPN7202B", gang);
        Session hr = hr();
        Map<String, Object> separation = new HashMap<>();
        separation.put("type", "RESIGNATION");
        separation.put("date", today.toString());
        separation.put("reason", "Resigned, letter 72");
        separation.put("orderReference", "SO/NWNP/72");
        separation.put("orderDate", today.toString());
        separation.put("issuingAuthority", "Area Manager");
        // Before the month: refused naming the date.
        Map<String, Object> early = new HashMap<>(separation);
        early.put("date", today.withDayOfMonth(1).minusDays(1).toString());
        MvcResult refused = post(hr, "/v1/labour/members/" + leaver + "/separations", early);
        assertThat(code(refused)).isEqualTo(422);
        assertThat(body(refused).get("args").get(0).asString()).isEqualTo("DATE");

        approve(post(hr, "/v1/labour/members/" + leaver + "/separations", separation));
        Map<String, Object> after =
                jdbc.queryForMap(
                        "SELECT e.employment_status, p.labour_status FROM employee e JOIN"
                                + " labour_profile p ON p.employee_id = e.employee_id WHERE"
                                + " e.employee_id = ?",
                        leaver);
        assertThat(after.get("EMPLOYMENT_STATUS")).isEqualTo("SEPARATED");
        assertThat(after.get("LABOUR_STATUS")).isEqualTo("SEPARATED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM labour_gang_membership WHERE employee_id = ?"
                                        + " AND (effective_to IS NULL OR effective_to >= ?)",
                                Integer.class,
                                leaver,
                                Date.valueOf(today)))
                .as("no membership from the separation date")
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT separation_type FROM settlement_case WHERE employee_id = ?",
                                String.class,
                                leaver))
                .as("the Full & Final case opens")
                .isEqualTo("RESIGNATION");

        // Excluded from later work-slips: a slip naming the separated labour is refused, the
        // remaining member's slip is taken.
        Session ldh = ldh();
        MvcResult named = saveSlip(ldh, gang, slip(today, List.of(leaver, stays)));
        assertThat(code(named)).isEqualTo(422);
        assertThat(body(named).get("args").get(0).asString()).startsWith("NOT_A_MEMBER");
        MvcResult remaining = saveSlip(ldh, gang, slip(today, List.of(stays)));
        assertThat(code(remaining)).as(remaining.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(remaining).get("labourStrength").asInt()).isEqualTo(1);
    }
}
