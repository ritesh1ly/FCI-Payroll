package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 21 phase 21b (plan docs/plan/21-dps-labour.md D21-3; SRS-2-34, SRS-4-7, SRS-4-13, SRS-G-1;
 * CF-40): the Labour Dealing Hand of Depot Okhla enters the DPS gang's booking-slips; a second slip
 * of the gang, date and operation is refused naming the first; each slip goes to the Depot Manager,
 * who approves one and returns another, which is corrected by an amendment and sent again; the
 * enterer never decides; the month goes to the Payroll Checker only once every booking-slip is
 * approved.
 *
 * <p>Demo data V058_9 (Okhla DPS Gang 1) and V054_9 (operation W2P, bag KATTA50).
 */
class DpsBookingSlipIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<String> GANG = List.of("L20701", "L20702", "L20703", "L20704");

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long gang() {
        return id("SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-DL01-G003'");
    }

    private List<Long> members() {
        return GANG.stream()
                .map(c -> id("SELECT employee_id FROM employee WHERE emp_num = ?", c))
                .toList();
    }

    private Session ldh() throws Exception {
        return login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
    }

    private Session manager() throws Exception {
        return login("dm.okhla@fci-demo.test", "DEPOT_MANAGER", "DP-DL01");
    }

    private void markWorked(Session s, LocalDate day) throws Exception {
        List<Map<String, Object>> marks = new ArrayList<>();
        members().forEach(m -> marks.add(Map.of("employeeId", m, "code", "W")));
        MvcResult r =
                mvc.perform(
                                userPost(
                                        s,
                                        "/v1/labour/gangs/" + gang() + "/attendance-days",
                                        Map.of("date", day.toString(), "marks", marks)))
                        .andReturn();
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private Map<String, Object> slip(LocalDate day, String start, String end) {
        Map<String, Object> row = new HashMap<>();
        row.put(
                "operationId",
                id("SELECT operation_id FROM operation_master WHERE operation_code = 'W2P'"));
        row.put(
                "bagTypeId",
                id("SELECT bag_type_id FROM bag_type WHERE bag_type_code = 'KATTA50'"));
        row.put("bags", 400);
        row.put("start", start);
        row.put("end", end);
        Map<String, Object> s = new HashMap<>();
        s.put("date", day.toString());
        s.put("continueWork", false);
        s.put("rows", List.of(row));
        s.put("participants", members());
        return s;
    }

    private MvcResult save(Session s, Map<String, Object> slip) throws Exception {
        return mvc.perform(userPost(s, "/v1/labour/gangs/" + gang() + "/work-slips", slip))
                .andReturn();
    }

    private MvcResult send(Session s, long slipId) throws Exception {
        return mvc.perform(
                        userPost(s, "/v1/labour/work-slips/" + slipId + "/submissions", Map.of()))
                .andReturn();
    }

    private MvcResult batch(Session s, YearMonth month) throws Exception {
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/labour/gangs/" + gang() + "/work-slip-batches",
                                Map.of("month", month.toString())))
                .andReturn();
    }

    private String status(long slipId) {
        return jdbc.queryForObject(
                "SELECT slip_status FROM work_slip WHERE work_slip_id = ?", String.class, slipId);
    }

    private static int code(MvcResult r) {
        return r.getResponse().getStatus();
    }

    // SC-21-0007, SC-21-0014, SC-20-0196 (Approve DPS Booking-Slips, main flow: entered, the
    // duplicate refused, approved or returned by the Depot Manager, the month sent once all are
    // approved).
    @Test
    void SRS_2_34_eachBookingSlipIsApprovedByTheDepotManagerBeforeTheMonthGoesOn()
            throws Exception {
        Session ldh = ldh();
        YearMonth month = YearMonth.now(IST).minusMonths(2);
        LocalDate first = month.atDay(5);
        LocalDate second = month.atDay(6);
        markWorked(ldh, first);
        markWorked(ldh, second);

        MvcResult one = save(ldh, slip(first, "09:00", "12:00"));
        assertThat(code(one)).as(one.getResponse().getContentAsString()).isEqualTo(201);
        long oneId = body(one).get("id").asLong();
        String oneNo = body(one).get("number").asString();
        // SC-20-0197 / BS-2: the same gang, date and operation again is refused, the existing slip
        // named,
        // whatever the time frame (the DPS duplicate test, SRS-4-7, is within it).
        MvcResult again = save(ldh, slip(first, "13:00", "15:00"));
        assertThat(code(again)).isEqualTo(409);
        assertThat(body(again).get("errorCode").asString()).isEqualTo("LBR-409-DUPLICATE-SLIP");
        assertThat(body(again).get("args").get(0).asString()).isEqualTo(oneNo);

        MvcResult two = save(ldh, slip(second, "09:00", "12:00"));
        assertThat(code(two)).isEqualTo(201);
        long twoId = body(two).get("id").asLong();

        // The month waits for the Depot Manager.
        MvcResult early = batch(ldh, month);
        assertThat(code(early)).isEqualTo(409);
        assertThat(body(early).get("errorCode").asString()).isEqualTo("LBR-409-BOOKING-PENDING");

        MvcResult sentOne = send(ldh, oneId);
        assertThat(code(sentOne)).as(sentOne.getResponse().getContentAsString()).isEqualTo(202);
        long approvalOne = body(sentOne).get("id").asLong();
        assertThat(status(oneId)).isEqualTo("SUBMITTED");
        assertThat(code(send(ldh, oneId))).as("sent once").isEqualTo(409);
        // SRS-G-1: the enterer never decides; the Payroll Checker is not the route's checker.
        assertThat(decide(ldh, approvalOne, "APPROVE", null, null)).isEqualTo(403);
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                approvalOne,
                                "APPROVE",
                                null,
                                null))
                .as("the Payroll Checker is not the booking-slip's checker")
                .isEqualTo(403);
        Session dm = manager();
        assertThat(decide(dm, approvalOne, "APPROVE", null, null)).isEqualTo(200);
        assertThat(status(oneId)).isEqualTo("APPROVED");

        long approvalTwo = body(send(ldh, twoId)).get("id").asLong();
        assertThat(decide(dm, approvalTwo, "RETURN", "Bag count differs from Anna Darpan", null))
                .isEqualTo(200);
        assertThat(status(twoId)).isEqualTo("REJECTED");
        // l.4669 / l.4644 / l.4650 (UI #62, #63): the register names who entered and who decided,
        // when, and the Depot Manager's reason of a returned booking-slip.
        JsonNode register =
                body(
                        mvc.perform(
                                        userGet(
                                                ldh,
                                                "/v1/labour/gangs/"
                                                        + gang()
                                                        + "/work-slips?month="
                                                        + month))
                                .andReturn());
        for (JsonNode s : register.get("slips")) {
            assertThat(s.get("enteredBy").asString()).isEqualTo("ldh.depot@fci-demo.test");
            if (s.get("id").asLong() == oneId) {
                assertThat(s.get("decidedBy").asString())
                        .isNotBlank()
                        .isNotEqualTo("ldh.depot@fci-demo.test");
                assertThat(s.get("decidedAt").isNull()).isFalse();
                assertThat(s.get("returnReason").isNull()).isTrue();
            }
            if (s.get("id").asLong() == twoId) {
                assertThat(s.get("returnReason").asString())
                        .isEqualTo("Bag count differs from Anna Darpan");
            }
        }
        // A returned booking-slip is corrected by an amendment and sent again.
        Map<String, Object> corrected = slip(second, "09:00", "12:30");
        MvcResult amended =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/work-slips/" + twoId + "/amendments",
                                        Map.of(
                                                "slip",
                                                corrected,
                                                "reason",
                                                "Bags per Anna Darpan")))
                        .andReturn();
        assertThat(code(amended)).as(amended.getResponse().getContentAsString()).isEqualTo(201);
        long threeId = body(amended).get("id").asLong();
        assertThat(status(twoId)).isEqualTo("AMENDED");
        assertThat(code(batch(ldh, month)))
                .as("the corrected slip is not approved yet")
                .isEqualTo(409);
        long approvalThree = body(send(ldh, threeId)).get("id").asLong();
        assertThat(decide(dm, approvalThree, "APPROVE", null, null)).isEqualTo(200);

        MvcResult sent = batch(ldh, month);
        assertThat(code(sent)).as(sent.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(status(oneId)).as("approved slips stay approved").isEqualTo("APPROVED");
    }

    @Test
    void SRS_2_34_aDepartmentalSlipIsNeverSentToTheDepotManager() throws Exception {
        Session ldh = ldh();
        long okhla = id("SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-DL01-G002'");
        LocalDate day = YearMonth.now(IST).minusMonths(9).atDay(10);
        List<Map<String, Object>> marks = new ArrayList<>();
        for (String c : List.of("L10701", "L10702", "L10002", "L10003")) {
            marks.add(
                    Map.of(
                            "employeeId",
                            id("SELECT employee_id FROM employee WHERE emp_num = ?", c),
                            "code",
                            "W"));
        }
        mvc.perform(
                userPost(
                        ldh,
                        "/v1/labour/gangs/" + okhla + "/attendance-days",
                        Map.of("date", day.toString(), "marks", marks)));
        Map<String, Object> s = slip(day, "09:00", "12:00");
        s.put("participants", marks.stream().map(m -> m.get("employeeId")).toList());
        MvcResult saved =
                mvc.perform(userPost(ldh, "/v1/labour/gangs/" + okhla + "/work-slips", s))
                        .andReturn();
        assertThat(code(saved)).as(saved.getResponse().getContentAsString()).isEqualTo(201);
        MvcResult r = send(ldh, body(saved).get("id").asLong());
        assertThat(code(r)).isEqualTo(422);
        assertThat(body(r).get("args").get(0).asString()).isEqualTo("PAYROLL_TYPE");
    }

    // SC-21-0008
    @Test
    void SRS_4_8_aDpsLabourIsTransferredOnlyOnceItsBookingSlipsAreApproved() throws Exception {
        LocalDate today = LocalDate.now(IST);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                today.getDayOfMonth() > 1, "a slip before today in the month is needed");
        Session ldh = ldh();
        YearMonth current = YearMonth.from(today);
        for (LocalDate d = current.atDay(1); d.isBefore(today); d = d.plusDays(1)) {
            markWorked(ldh, d);
        }
        MvcResult slip = save(ldh, slip(current.atDay(1), "08:00", "10:00"));
        assertThat(code(slip)).as(slip.getResponse().getContentAsString()).isEqualTo(201);
        long slipId = body(slip).get("id").asLong();
        long member = id("SELECT employee_id FROM employee WHERE emp_num = 'L20704'");
        Map<String, Object> transfer =
                Map.of(
                        "toGangId",
                        id("SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-DL01-G004'"),
                        "transferDate",
                        today.toString(),
                        "reason",
                        "IT: shed 2",
                        "orderReference",
                        "TO/DPS/2",
                        "orderDate",
                        today.toString(),
                        "issuingAuthority",
                        "Area Manager");
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        MvcResult refused =
                mvc.perform(userPost(hr, "/v1/labour/members/" + member + "/transfers", transfer))
                        .andReturn();
        assertThat(code(refused)).as(refused.getResponse().getContentAsString()).isEqualTo(422);
        assertThat(body(refused).get("args").get(0).asString()).isEqualTo("WORK_SLIPS_INCOMPLETE");
        long approval = body(send(ldh, slipId)).get("id").asLong();
        assertThat(decide(manager(), approval, "APPROVE", null, null)).isEqualTo(200);
        MvcResult accepted =
                mvc.perform(userPost(hr, "/v1/labour/members/" + member + "/transfers", transfer))
                        .andReturn();
        assertThat(code(accepted)).as(accepted.getResponse().getContentAsString()).isEqualTo(202);
    }

    /** An access group of {@code role} scoped to Depot Khanna (another DO), for this IT only. */
    private void khannaGroup(String code, String role) {
        Integer there =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM access_group WHERE group_code = ?",
                        Integer.class,
                        code);
        if (there == null || there == 0) {
            jdbc.update(
                    "INSERT INTO access_group (group_code, group_name, role_id, scope_type,"
                            + " consolidated_view, effective_from, approval_id) SELECT ?, ?,"
                            + " r.role_id, 'DEPOT', 'N', DATE '2026-04-01', (SELECT approval_id"
                            + " FROM approval_queue WHERE entity_name = 'SEED_BASELINE' AND"
                            + " entity_key = 'demo-2026-10') FROM app_role r WHERE r.role_code ="
                            + " ?",
                    code,
                    "IT " + role + " - Depot Khanna",
                    role);
            jdbc.update(
                    "INSERT INTO access_group_scope (access_group_id, location_id) SELECT"
                            + " g.access_group_id, l.location_id FROM access_group g, org_location"
                            + " l WHERE g.group_code = ? AND l.location_code = 'DP-PB01'",
                    code);
        }
        jdbc.update(
                "INSERT INTO app_user_access_group (user_id, access_group_id, effective_from,"
                        + " assigned_by, approval_id) SELECT u.user_id, g.access_group_id, ?,"
                        + " 'it.fixture.tr056', g.approval_id FROM app_user u, access_group g"
                        + " WHERE u.login_id = 'report.admin@fci-demo.test' AND g.group_code = ?",
                java.sql.Date.valueOf(LocalDate.now(IST).minusDays(1)),
                code);
    }

    /**
     * BMAD TR-056 (SRS-2-34 l.4386; SRS-G-1 l.517; SRS-G-29): the Depot Manager of the slip's own
     * depot is its checker. A Depot Manager of another depot (Khanna, another DO) cannot approve
     * the Okhla booking-slip, which stays SUBMITTED; a Labour Dealing Hand of that depot can
     * neither enter a slip for the Okhla gang nor read its register. The fixture access is revoked
     * in finally and the slip is then approved by Okhla's Depot Manager.
     */
    @Test
    void SRS_2_34_SRS_G_29_aDepotManagerOrDealingHandOfAnotherDepotIsOutOfScope() throws Exception {
        Session ldh = ldh();
        YearMonth month = YearMonth.now(IST).minusMonths(4);
        LocalDate day = month.atDay(12);
        markWorked(ldh, day);
        MvcResult saved = save(ldh, slip(day, "09:00", "12:00"));
        assertThat(code(saved)).as(saved.getResponse().getContentAsString()).isEqualTo(201);
        long slipId = body(saved).get("id").asLong();
        MvcResult sent = send(ldh, slipId);
        assertThat(code(sent)).as(sent.getResponse().getContentAsString()).isEqualTo(202);
        long approval = body(sent).get("id").asLong();
        khannaGroup("AG-IT-DM-DPPB01", "DEPOT_MANAGER");
        khannaGroup("AG-IT-LDH-DPPB01", "LABOUR_DEALING_HAND");
        try {
            Session foreignDm = login("report.admin@fci-demo.test", "DEPOT_MANAGER", "DP-PB01");
            MvcResult refused =
                    mvc.perform(
                                    userPost(
                                            foreignDm,
                                            "/v1/approvals/" + approval + "/decision",
                                            Map.of("decision", "APPROVE")))
                            .andReturn();
            assertThat(code(refused)).as(refused.getResponse().getContentAsString()).isIn(403, 404);
            assertThat(status(slipId)).as("the slip still waits").isEqualTo("SUBMITTED");

            Session foreignLdh =
                    login("report.admin@fci-demo.test", "LABOUR_DEALING_HAND", "DP-PB01");
            assertThat(code(save(foreignLdh, slip(day.plusDays(1), "09:00", "12:00"))))
                    .as("another depot's dealing hand enters no slip for this gang")
                    .isIn(403, 404);
            assertThat(
                            code(
                                    mvc.perform(
                                                    userGet(
                                                            foreignLdh,
                                                            "/v1/labour/gangs/"
                                                                    + gang()
                                                                    + "/work-slips?month="
                                                                    + month))
                                            .andReturn()))
                    .as("nor reads its register")
                    .isIn(403, 404);
            assertThat(code(send(foreignLdh, slipId))).as("nor sends its slip").isIn(403, 404, 409);
        } finally {
            jdbc.update(
                    "UPDATE app_user_access_group SET revoked_at = CURRENT_TIMESTAMP, revoke_cause"
                            + " = 'ADMIN', revoke_reason = 'IT fixture ended' WHERE assigned_by ="
                            + " 'it.fixture.tr056' AND revoked_at IS NULL");
        }
        assertThat(decide(manager(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(status(slipId)).isEqualTo("APPROVED");
    }
}
