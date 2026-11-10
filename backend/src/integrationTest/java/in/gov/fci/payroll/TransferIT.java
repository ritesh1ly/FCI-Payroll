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

/**
 * Module 20 phase 20e (plan docs/plan/20-labour-common-departmental.md; SRS-2-6, SRS-2-7, SRS-2-47;
 * BR-LABM-006 to BR-LABM-009; D20-12 to D20-15): at Depot Okhla, a handling labour of a fixture
 * gang A is transferred to gang B once A's attendance is complete (approved: A's membership ends
 * the day before, B's starts), then attached to A for three days (approved: B ends, A covers the
 * days, B resumes); an empty gang is end-dated. Refused: incomplete attendance, a Sardar, a second
 * transfer while one waits, an overlapping attachment, the Payroll Maker, a gang with members or a
 * work-slip after the end date. Fixture gangs and labour of its own, so the demo gang is untouched.
 */
class TransferIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long okhla() {
        return id("SELECT location_id FROM org_location WHERE location_code = 'DP-DL01'");
    }

    /** A fixture handling gang of Depot Okhla, active a year back. */
    private long gang(String code, LocalDate today) {
        return gang(code, today, "DEPT_LABOUR");
    }

    /** A fixture handling gang of Depot Okhla of the payroll type, active a year back. */
    private long gang(String code, LocalDate today, String payrollType) {
        jdbc.update(
                "INSERT INTO labour_gang (gang_code, gang_name, depot_location_id, payroll_type,"
                        + " gang_type, gang_status, effective_from) VALUES (?, ?, ?, ?,"
                        + " 'HANDLING', 'ACTIVE', ?)",
                code,
                "Fixture " + code,
                okhla(),
                payrollType,
                Date.valueOf(today.minusYears(1)));
        return id("SELECT gang_id FROM labour_gang WHERE gang_code = ?", code);
    }

    /** A fixture labour of Depot Okhla in the gang from the date. */
    private long labour(String code, String pan, long gangId, String role, LocalDate from) {
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
                        + " effective_from) VALUES (?, ?, ?, ?)",
                gangId,
                employee,
                role,
                Date.valueOf(from));
        return employee;
    }

    private void mark(long employee, long gangId, LocalDate day) {
        jdbc.update(
                "INSERT INTO labour_attendance (employee_id, gang_id, depot_location_id,"
                        + " attendance_date, attendance_code_id, attendance_code, engine_code,"
                        + " source_code, capture_mode) SELECT ?, ?, ?, ?, attendance_code_id, 'W',"
                        + " 'DEPT_LABOUR', 'ENT', 'MANUAL' FROM attendance_code_master WHERE"
                        + " engine_code = 'DEPT_LABOUR' AND attendance_code = 'W'",
                employee,
                gangId,
                okhla(),
                Date.valueOf(day));
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String message(MvcResult r) throws Exception {
        return status(r) + " " + r.getResponse().getContentAsString();
    }

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private MvcResult transfer(Session s, long employee, long to, LocalDate date) throws Exception {
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/labour/members/" + employee + "/transfers",
                                Map.of(
                                        "toGangId",
                                        to,
                                        "transferDate",
                                        date.toString(),
                                        "reason",
                                        "Order 7/2026",
                                        "orderReference",
                                        "TO/7/2026",
                                        "orderDate",
                                        LocalDate.now(IST).toString(),
                                        "issuingAuthority",
                                        "Area Manager Delhi")))
                .andReturn();
    }

    /** A transfer in the body of an older client: no order. */
    private MvcResult transferWithoutOrder(Session s, long employee, long to, LocalDate date)
            throws Exception {
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/labour/members/" + employee + "/transfers",
                                Map.of(
                                        "toGangId",
                                        to,
                                        "transferDate",
                                        date.toString(),
                                        "reason",
                                        "Order 7/2026")))
                .andReturn();
    }

    // SC-22-0031 / SC-20-0052 (defect found 2026-11-03): an NWNP labour marks no attendance
    // (D22-1), so its transfer is not refused ATTENDANCE_INCOMPLETE; a transfer without its order
    // (SRS l.4429) is refused ORDER, also in the body of an older client.
    @Test
    void SRS_5_14_anNwnpLabourIsTransferredWithoutAttendanceAndOnlyWithItsOrder() throws Exception {
        LocalDate today = LocalDate.now(IST);
        long a = gang("DP-DL01-TXN1", today, "NWNP_LABOUR");
        long b = gang("DP-DL01-TXN2", today, "NWNP_LABOUR");
        long x = labour("LTX091", "DMTPL0091N", a, "HANDLING", today.minusDays(20));
        jdbc.update(
                "UPDATE labour_profile SET payroll_type = 'NWNP_LABOUR' WHERE employee_id = ?", x);
        Session hr = hr();
        LocalDate date = today.plusDays(1);
        assertThat(message(transferWithoutOrder(hr, x, b, date))).contains("ORDER");
        MvcResult sent = transfer(hr, x, b, date);
        assertThat(status(sent)).as(message(sent)).isEqualTo(202);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                body(sent).get("id").asLong()))
                .contains("TO/7/2026")
                .contains("Area Manager Delhi");
    }

    private MvcResult attach(Session s, long employee, long to, LocalDate from, LocalDate until)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("toGangId", to);
        body.put("from", from.toString());
        body.put("to", until == null ? null : until.toString());
        body.put("reason", "Rake surge");
        body.put("orderReference", "DM/12");
        return mvc.perform(userPost(s, "/v1/labour/members/" + employee + "/attachments", body))
                .andReturn();
    }

    private List<Map<String, Object>> memberships(long employee) {
        return jdbc.queryForList(
                "SELECT m.gang_id, m.effective_from, m.effective_to FROM labour_gang_membership m"
                        + " WHERE m.employee_id = ? ORDER BY m.effective_from",
                employee);
    }

    // SC-20-0112: a labour attached to another gang From and To, back in the gang after;
    // SC-20-0138: the attached labour is in the receiving gang's complement for those days.
    @Test
    void SRS_2_6_aHandlingLabourIsTransferredThenAttachedForThreeDays() throws Exception {
        LocalDate today = LocalDate.now(IST);
        long a = gang("DP-DL01-TXA", today);
        long b = gang("DP-DL01-TXB", today);
        long x = labour("LTX001", "DMTPL0001A", a, "HANDLING", today);
        long sardar = labour("LTX002", "DMTPL0002B", a, "SARDAR", today);
        Session hr = hr();
        LocalDate date = today.plusDays(2);

        assertThat(message(transfer(hr, x, b, date)))
                .as("today is not marked in A")
                .contains("ATTENDANCE_INCOMPLETE");
        mark(x, a, today);
        assertThat(message(transfer(hr, sardar, b, today))).contains("LEADER");
        assertThat(message(transfer(hr, x, a, date))).contains("SAME_GANG");
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(status(transfer(maker, x, b, date))).isEqualTo(403);

        MvcResult sent = transfer(hr, x, b, date);
        assertThat(status(sent)).as(message(sent)).isEqualTo(202);
        assertThat(message(transfer(hr, x, b, date.plusDays(1)))).contains("PENDING");
        assertThat(decide(checker(), body(sent).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        List<Map<String, Object>> after = memberships(x);
        assertThat(after).hasSize(2);
        assertThat(after.get(0).get("EFFECTIVE_TO").toString())
                .startsWith(date.minusDays(1).toString());
        assertThat(((Number) after.get(1).get("GANG_ID")).longValue()).isEqualTo(b);

        LocalDate from = today.plusDays(3);
        MvcResult attached = attach(hr, x, a, from, from.plusDays(2));
        assertThat(status(attached)).as(message(attached)).isEqualTo(202);
        assertThat(message(attach(hr, x, a, from.plusDays(1), null)))
                .as("one gang a day")
                .contains("OVERLAP");
        assertThat(decide(checker(), body(attached).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        List<Map<String, Object>> rows = memberships(x);
        assertThat(rows).hasSize(4);
        assertThat(((Number) rows.get(2).get("GANG_ID")).longValue())
                .as("attached to A")
                .isEqualTo(a);
        assertThat(rows.get(2).get("EFFECTIVE_TO").toString())
                .startsWith(from.plusDays(2).toString());
        assertThat(((Number) rows.get(3).get("GANG_ID")).longValue()).as("back in B").isEqualTo(b);
        assertThat(rows.get(3).get("EFFECTIVE_TO")).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attachment_status FROM labour_temp_attachment WHERE"
                                        + " employee_id = ?",
                                String.class,
                                x))
                .isEqualTo("APPROVED");
        assertThat(message(attach(hr, x, b, today.plusDays(1), null)))
                .as("the membership of A ends before a permanent move could start")
                .contains("MEMBERSHIP_ENDS");
    }

    // SC-20-0132: a gang with later work-slips is not end-dated; SC-20-0142: the gang status
    // (End-Dated) follows the approved end date.
    @Test
    void SRS_2_7_aGangEndsWithoutMembersOrLaterWorkSlips() throws Exception {
        LocalDate today = LocalDate.now(IST);
        long full = gang("DP-DL01-TXC", today);
        labour("LTX003", "DMTPL0003C", full, "HANDLING", today.minusDays(10));
        long empty = gang("DP-DL01-TXD", today);
        long worked = gang("DP-DL01-TXE", today);
        jdbc.update(
                "INSERT INTO work_slip (work_slip_number, engine_code, gang_id, depot_location_id,"
                    + " work_slip_date, slip_status, created_by) VALUES ('WS-TXE-1', 'DEPT_LABOUR',"
                    + " ?, ?, ?, 'DRAFT', 'it')",
                worked,
                okhla(),
                Date.valueOf(today));
        Session hr = hr();
        Map<String, Object> end =
                Map.of("endDate", today.minusDays(1).toString(), "reason", "Shed closed");
        assertThat(
                        message(
                                mvc.perform(
                                                userPost(
                                                        hr,
                                                        "/v1/labour/gangs/" + full + "/end-dates",
                                                        end))
                                        .andReturn()))
                .contains("MEMBERS_AFTER");
        assertThat(
                        message(
                                mvc.perform(
                                                userPost(
                                                        hr,
                                                        "/v1/labour/gangs/" + worked + "/end-dates",
                                                        end))
                                        .andReturn()))
                .contains("WORK_SLIPS_AFTER");
        MvcResult ended =
                mvc.perform(userPost(hr, "/v1/labour/gangs/" + empty + "/end-dates", end))
                        .andReturn();
        assertThat(status(ended)).as(message(ended)).isEqualTo(202);
        assertThat(decide(checker(), body(ended).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT gang_status FROM labour_gang WHERE gang_id = ?",
                                String.class,
                                empty))
                .isEqualTo("END_DATED");
    }

    private List<String> members(Session s, long gangId, java.time.YearMonth month)
            throws Exception {
        MvcResult r =
                mvc.perform(userGet(s, "/v1/labour/gangs/" + gangId + "/attendance?month=" + month))
                        .andReturn();
        assertThat(status(r)).as(message(r)).isEqualTo(200);
        return texts(body(r).path("members"), "labourCode");
    }

    // Deep review DR-12: a labour moved to another gang shows under the gang of each month.
    @Test
    void SRS_2_18_aMovedLabourShowsUnderTheGangOfEachMonth() throws Exception {
        LocalDate today = LocalDate.now(IST);
        java.time.YearMonth now = java.time.YearMonth.from(today);
        java.time.YearMonth before = now.minusMonths(1);
        long a = gang("DP-DL01-TXH", today);
        long b = gang("DP-DL01-TXI", today);
        long x = labour("LTX021", "DMTPL0021A", a, "HANDLING", before.minusMonths(2).atDay(1));
        jdbc.update(
                "UPDATE labour_gang_membership SET effective_to = ? WHERE employee_id = ?",
                Date.valueOf(before.atEndOfMonth()),
                x);
        jdbc.update(
                "INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role,"
                        + " effective_from) VALUES (?, ?, 'HANDLING', ?)",
                b,
                x,
                Date.valueOf(now.atDay(1)));
        Session ldh = login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
        assertThat(members(ldh, a, before))
                .as("SRS-2-40: by membership, not status")
                .contains("LTX021");
        assertThat(members(ldh, a, now)).doesNotContain("LTX021");
        assertThat(members(ldh, b, before)).doesNotContain("LTX021");
        assertThat(members(ldh, b, now)).contains("LTX021");
        jdbc.update(
                "UPDATE labour_profile SET labour_status = 'SEPARATED' WHERE employee_id = ?", x);
        assertThat(members(ldh, a, before))
                .as("SRS-2-40: a labour separated since is still shown for the month")
                .contains("LTX021");
    }
}
