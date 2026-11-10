package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Module 20 phase 20c (plan docs/plan/20-labour-common-departmental.md; SRS-2-3, SRS-2-4, SRS-2-5,
 * SRS-2-15, SRS-2-29, SRS-2-42, SRS-3-9; BR-AUDIT-006; D20-9): the Labour Dealing Hand of Depot
 * Okhla marks Okhla Gang 1 for a day two months back (three for the refusals), saves its work-slip
 * (Wagon-to-Platform, 1200 katta at stack height 22: Above-20; the absent member counted out of the
 * Labour Strength), after which the day's attendance is frozen; a second slip for another time
 * frame is amended and the first cancelled, never deleted; the month's slips go to the Payroll
 * Checker and are approved. Refused: a repeated slip, bag fields on a time-based operation, Present
 * But No Work without its reason, an end before the start, a day without attendance, someone
 * outside the gang, the Payroll Maker saving, a change after approval; a delete and a second Sardar
 * fail in the database.
 */
class WorkSlipIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long gang() {
        return id("SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-DL01-G002'");
    }

    private long member(String code) {
        return id("SELECT employee_id FROM employee WHERE emp_num = ?", code);
    }

    private long operation(String code) {
        return id("SELECT operation_id FROM operation_master WHERE operation_code = ?", code);
    }

    private long bag(String code) {
        return id("SELECT bag_type_id FROM bag_type WHERE bag_type_code = ?", code);
    }

    private Session ldh() throws Exception {
        return login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String errorCode(MvcResult r) throws Exception {
        String code = body(r).path("errorCode").asString();
        return code.isEmpty() ? status(r) + " " + r.getResponse().getContentAsString() : code;
    }

    private MvcResult mark(Session s, LocalDate day, String absent) throws Exception {
        List<Map<String, Object>> marks = new ArrayList<>();
        for (String code : List.of("L10701", "L10702", "L10002", "L10003")) {
            marks.add(Map.of("employeeId", member(code), "code", code.equals(absent) ? "A" : "W"));
        }
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/labour/gangs/" + gang() + "/attendance-days",
                                Map.of("date", day.toString(), "marks", marks)))
                .andReturn();
    }

    /** The gang's day with one member on the given code and the others on work. */
    private MvcResult markAs(Session s, LocalDate day, String labour, String code)
            throws Exception {
        List<Map<String, Object>> marks = new ArrayList<>();
        for (String c : List.of("L10701", "L10702", "L10002", "L10003")) {
            marks.add(Map.of("employeeId", member(c), "code", c.equals(labour) ? code : "W"));
        }
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/labour/gangs/" + gang() + "/attendance-days",
                                Map.of("date", day.toString(), "marks", marks)))
                .andReturn();
    }

    // SC-20-0189: the Holiday Flag is the system's, set when a participant worked on a holiday
    // with the depot open (code K); the holiday overtime basis waits for OQ-3 (v0.4).
    @Test
    void SRS_2_4_workOnAHolidaySetsTheHolidayFlagOfTheSlip() throws Exception {
        Session ldh = ldh();
        YearMonth month = YearMonth.now(IST).minusMonths(4);
        LocalDate holiday = month.atDay(3);
        LocalDate working = month.atDay(4);
        assertThat(status(markAs(ldh, holiday, "L10701", "K"))).isEqualTo(200);
        assertThat(status(markAs(ldh, working, "L10701", "W"))).isEqualTo(200);
        MvcResult onHoliday =
                save(ldh, slip(holiday, row("W2P", "KATTA50", 800, 12, "09:00", "12:00")));
        assertThat(status(onHoliday))
                .as(onHoliday.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(body(onHoliday).get("holiday").asBoolean()).isTrue();
        MvcResult onWorkday =
                save(ldh, slip(working, row("W2P", "KATTA50", 800, 12, "09:00", "12:00")));
        assertThat(status(onWorkday)).isEqualTo(201);
        assertThat(body(onWorkday).get("holiday").asBoolean()).isFalse();
    }

    private Map<String, Object> row(
            String op, String bagType, Integer bags, Integer stack, String start, String end) {
        Map<String, Object> r = new HashMap<>();
        r.put("operationId", operation(op));
        if (bagType != null) {
            r.put("bagTypeId", bag(bagType));
        }
        r.put("bags", bags);
        r.put("stackHeight", stack);
        r.put("start", start);
        r.put("end", end);
        return r;
    }

    private Map<String, Object> slip(LocalDate day, Map<String, Object> row) {
        return Map.of(
                "date",
                day.toString(),
                "continueWork",
                false,
                "rows",
                List.of(row),
                "participants",
                List.of(member("L10701"), member("L10702"), member("L10002"), member("L10003")));
    }

    private MvcResult save(Session s, Object body) throws Exception {
        return mvc.perform(userPost(s, "/v1/labour/gangs/" + gang() + "/work-slips", body))
                .andReturn();
    }

    /**
     * SRS-G-41 (l.555, US-GAP-110): the bag weight of a work-slip row comes from the bag-type
     * master, never typed. I-02 offers each bag type with the weight held on the master, and a row
     * that carries a typed weight (under any name) is refused as a whole, so nothing is saved for
     * the day.
     */
    @Test
    void SRS_G_41_aWorkSlipBagWeightComesFromM27AndATypedWeightIsRefused() throws Exception {
        Session ldh = ldh();
        YearMonth month = YearMonth.now(IST).minusMonths(7);
        LocalDate day = month.atDay(9);
        JsonNode view =
                body(
                        mvc.perform(
                                        userGet(
                                                ldh,
                                                "/v1/labour/gangs/"
                                                        + gang()
                                                        + "/work-slips?month="
                                                        + month))
                                .andReturn());
        assertThat(view.get("bagTypes")).isNotEmpty();
        for (JsonNode bag : view.get("bagTypes")) {
            java.math.BigDecimal master =
                    jdbc.queryForObject(
                            "SELECT bag_weight_kg FROM bag_type WHERE bag_type_id = ?",
                            java.math.BigDecimal.class,
                            bag.get("id").asLong());
            assertThat(bag.get("weightKg").decimalValue())
                    .as(bag.get("code").asString() + ": the master's weight")
                    .isEqualByComparingTo(master);
        }
        for (String typed : List.of("bagWeightKg", "weightKg", "bagWeight")) {
            Map<String, Object> row = row("W2P", "KATTA50", 700, 12, "09:00", "12:00");
            row.put(typed, 45);
            MvcResult refused = save(ldh, slip(day, row));
            assertThat(status(refused)).as(typed).isEqualTo(400);
            assertThat(errorCode(refused)).as(typed).isEqualTo("COM-400-VALIDATION");
            assertThat(body(refused).get("violations").toString()).as(typed).contains(typed);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM work_slip WHERE gang_id = ? AND"
                                        + " work_slip_date = ?",
                                Integer.class,
                                gang(),
                                java.sql.Date.valueOf(day)))
                .as("nothing saved")
                .isZero();
    }

    // SC-20-0146 (a filled work-slip freezes the day's attendance), SC-20-0174 (the duplicate slip
    // refused), SC-20-0074, SC-20-0173 (Capture Gang Work-Slip, main flow: operation, bags and
    // stack band, the labour list from the day's attendance, saved, amended, cancelled, approved),
    // SC-20-0179 (verify: the gang's attendance for that date can no longer be amended).
    @Test
    void SRS_2_4_aWorkSlipIsSavedFreezesTheDayIsAmendedCancelledAndApproved() throws Exception {
        Session ldh = ldh();
        YearMonth month = YearMonth.now(IST).minusMonths(2);
        LocalDate day = month.atDay(1);
        assertThat(status(mark(ldh, day, "L10003"))).isEqualTo(200);

        JsonNode view =
                body(
                        mvc.perform(
                                        userGet(
                                                ldh,
                                                "/v1/labour/gangs/"
                                                        + gang()
                                                        + "/work-slips?month="
                                                        + month))
                                .andReturn());
        assertThat(view.get("batchState").asString()).isEqualTo("OPEN");
        assertThat(view.get("operations").toString()).contains("W2P").contains("PBNW");
        assertThat(view.get("bagTypes")).hasSize(2);

        MvcResult first = save(ldh, slip(day, row("W2P", "KATTA50", 1200, 22, "09:00", "13:00")));
        assertThat(status(first)).as(first.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode saved = body(first);
        assertThat(saved.get("status").asString()).isEqualTo("DRAFT");
        assertThat(saved.get("labourStrength").asInt())
                .as("the absent member is not counted")
                .isEqualTo(3);
        assertThat(saved.get("rows").get(0).get("heightBand").asString()).isEqualTo("ABOVE_20");
        assertThat(saved.get("participants").toString()).contains("\"present\":false");
        long firstId = saved.get("id").asLong();

        // SRS-2-3: after the work-slip, the day's attendance does not change.
        assertThat(errorCode(mark(ldh, day, "L10002"))).isEqualTo("LBR-409-BLOCKED");
        // SRS-3-9: the same work again is refused.
        assertThat(
                        errorCode(
                                save(
                                        ldh,
                                        slip(
                                                day,
                                                row("W2P", "KATTA50", 900, 22, "09:00", "13:00")))))
                .isEqualTo("LBR-409-DUPLICATE-SLIP");

        MvcResult second = save(ldh, slip(day, row("T2P", "BAG66", 500, 12, "14:00", "17:00")));
        assertThat(status(second)).isEqualTo(201);
        assertThat(body(second).get("rows").get(0).get("heightBand").asString())
                .isEqualTo("UPTO_20");
        long secondId = body(second).get("id").asLong();

        MvcResult amended =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/work-slips/" + secondId + "/amendments",
                                        Map.of(
                                                "slip",
                                                slip(
                                                        day,
                                                        row(
                                                                "T2P", "BAG66", 600, 12, "14:00",
                                                                "17:00")),
                                                "reason",
                                                "Bag count per the rake memo")))
                        .andReturn();
        assertThat(status(amended)).as(amended.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(amended).get("amendmentOf").asLong()).isEqualTo(secondId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT slip_status FROM work_slip WHERE work_slip_id = ?",
                                String.class,
                                secondId))
                .isEqualTo("AMENDED");

        MvcResult cancelled =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/work-slips/" + firstId + "/cancellations",
                                        Map.of("reason", "Entered against the wrong gang")))
                        .andReturn();
        assertThat(body(cancelled).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM labour_attendance WHERE gang_id = ? AND"
                                        + " attendance_date = ? AND work_slip_filed_flag = 'Y'",
                                Integer.class,
                                gang(),
                                java.sql.Date.valueOf(day)))
                .as("the amended slip still holds the day")
                .isEqualTo(4);
        assertThatThrownBy(
                        () -> jdbc.update("DELETE FROM work_slip WHERE work_slip_id = ?", firstId))
                .hasMessageContaining("FCIPAY-WSL-001");

        MvcResult batch =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/gangs/" + gang() + "/work-slip-batches",
                                        Map.of("month", month.toString())))
                        .andReturn();
        assertThat(status(batch)).as(batch.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                body(batch).get("id").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        long current = body(amended).get("id").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT slip_status FROM work_slip WHERE work_slip_id = ?",
                                String.class,
                                current))
                .isEqualTo("APPROVED");
        assertThat(
                        errorCode(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        "/v1/labour/work-slips/"
                                                                + current
                                                                + "/cancellations",
                                                        Map.of("reason", "Too late")))
                                        .andReturn()))
                .isEqualTo("LBR-409-STATE");
        assertThat(
                        errorCode(
                                save(
                                        ldh,
                                        slip(
                                                day,
                                                row(
                                                        "STACK", "KATTA50", 100, 5, "18:00",
                                                        "19:00")))))
                .as("the approved month takes no new slip")
                .isEqualTo("LBR-409-STATE");

        // DDL trg_work_slip_role_singleton: one Sardar on a slip.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO work_slip_participant (work_slip_id,"
                                            + " work_slip_date, employee_id, gang_role) VALUES (?,"
                                            + " ?, ?, 'SARDAR')",
                                        current,
                                        java.sql.Date.valueOf(day),
                                        member("L10501")))
                .hasMessageContaining("FCIPAY-WSL-002");
    }

    // SC-20-0150 (Capture Gang Attendance, verify: the marked attendance is what the work-slip
    // takes; a day without it takes no slip).
    @Test
    void SRS_2_42_aRowIsWholeAndTheDayMarkedBeforeItsWorkSlip() throws Exception {
        Session ldh = ldh();
        YearMonth month = YearMonth.now(IST).minusMonths(3);
        LocalDate day = month.atDay(2);
        assertThat(
                        errorCode(
                                save(
                                        ldh,
                                        slip(
                                                day,
                                                row(
                                                        "W2P", "KATTA50", 100, null, "09:00",
                                                        "11:00")))))
                .isEqualTo("LBR-422-WORK-SLIP");
        assertThat(status(mark(ldh, day, null))).isEqualTo(200);
        assertThat(
                        body(save(
                                        ldh,
                                        slip(
                                                day,
                                                row(
                                                        "PBNW", "KATTA50", 100, null, "09:00",
                                                        "11:00"))))
                                .toString())
                .as("no bag fields on a time-based operation")
                .contains("BAGS");
        assertThat(
                        body(save(ldh, slip(day, row("PBNW", null, null, null, "09:00", "11:00"))))
                                .toString())
                .contains("PBNW_REASON");
        assertThat(
                        body(save(
                                        ldh,
                                        slip(
                                                day,
                                                row(
                                                        "W2P", "KATTA50", 100, null, "11:00",
                                                        "09:00"))))
                                .toString())
                .contains("TIME_ORDER");
        Map<String, Object> pbnw = row("PBNW", null, null, null, "09:00", "11:00");
        pbnw.put("pbnwReason", "RAKE_DELAYED");
        pbnw.put("rakeReference", "RK-2026-118");
        MvcResult waited = save(ldh, slip(day, pbnw));
        assertThat(status(waited)).as(waited.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(waited).get("rows").get(0).get("pbnwReason").asString())
                .isEqualTo("RAKE_DELAYED");

        Map<String, Object> outsider =
                new HashMap<>(slip(day, row("T2P", "KATTA50", 50, null, "12:00", "13:00")));
        outsider.put("participants", List.of(member("L10701"), member("L10501")));
        assertThat(body(save(ldh, outsider)).toString()).contains("NOT_A_MEMBER");

        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        status(
                                save(
                                        maker,
                                        slip(
                                                day,
                                                row(
                                                        "T2P", "KATTA50", 50, null, "12:00",
                                                        "13:00")))))
                .isEqualTo(403);
        assertThat(
                        errorCode(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        "/v1/labour/gangs/"
                                                                + gang()
                                                                + "/work-slip-batches",
                                                        Map.of(
                                                                "month",
                                                                YearMonth.now(IST).toString())))
                                        .andReturn()))
                .isEqualTo("LBR-422-WORK-SLIP");
    }

    private String said(MvcResult r) throws Exception {
        return status(r) + " " + r.getResponse().getContentAsString();
    }

    private Map<String, Object> slipOf(
            LocalDate day, List<Map<String, Object>> rows, List<Long> participants, boolean cont) {
        Map<String, Object> s = new java.util.HashMap<>();
        s.put("date", day.toString());
        s.put("continueWork", cont);
        s.put("rows", rows);
        s.put("participants", participants);
        return s;
    }

    /**
     * SRS-2-4 / SRS-2-29 / SRS-3-9: every malformed slip, amendment and batch is named: a future
     * day, an unknown operation or bag type, a member listed twice, the same row twice, an
     * amendment that moves the day or has no reason, a batch of the open month or of a month
     * without slips; a returned batch reopens its slips; work continuing past midnight is kept.
     */
    @Test
    void SRS_2_4_everyMalformedSlipAmendmentAndBatchIsNamed() throws Exception {
        Session ldh = ldh();
        YearMonth month = YearMonth.now(IST).minusMonths(5);
        LocalDate day = month.atDay(6);
        assertThat(status(markAs(ldh, day, "L10701", "W"))).isEqualTo(200);
        List<Long> all =
                List.of(member("L10701"), member("L10702"), member("L10002"), member("L10003"));
        Map<String, Object> ok = row("W2P", "KATTA50", 700, 12, "09:00", "12:00");

        assertThat(said(save(ldh, slipOf(LocalDate.now(IST).plusDays(1), List.of(ok), all, false))))
                .contains("FUTURE");
        Map<String, Object> unknownOp = new java.util.HashMap<>(ok);
        unknownOp.put("operationId", 987_654L);
        assertThat(said(save(ldh, slipOf(day, List.of(unknownOp), all, false))))
                .contains("OPERATION");
        Map<String, Object> unknownBag = new java.util.HashMap<>(ok);
        unknownBag.put("bagTypeId", 987_654L);
        assertThat(said(save(ldh, slipOf(day, List.of(unknownBag), all, false))))
                .contains("BAG_TYPE");
        assertThat(
                        said(
                                save(
                                        ldh,
                                        slipOf(
                                                day,
                                                List.of(ok),
                                                List.of(member("L10701"), member("L10701")),
                                                false))))
                .contains("PARTICIPANTS");
        assertThat(said(save(ldh, slipOf(day, List.of(ok, ok), all, false)))).contains("ROW_TWICE");
        assertThat(status(save(ldh, slipOf(day, List.of(), all, false))))
                .as("a slip without rows")
                .isBetween(400, 422);

        MvcResult night =
                save(
                        ldh,
                        slipOf(
                                day,
                                List.of(row("W2P", "KATTA50", 700, 12, "22:00", "02:00")),
                                all,
                                true));
        assertThat(status(night)).as(said(night)).isEqualTo(201);
        assertThat(body(night).get("continueWork").asBoolean()).isTrue();
        long nightId = body(night).get("id").asLong();

        String amend = "/v1/labour/work-slips/" + nightId + "/amendments";
        assertThat(
                        said(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        amend,
                                                        Map.of(
                                                                "slip",
                                                                slipOf(
                                                                        day.plusDays(1),
                                                                        List.of(ok),
                                                                        all,
                                                                        false),
                                                                "reason",
                                                                "Moved")))
                                        .andReturn()))
                .contains("DATE_CHANGED");
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        amend,
                                                        Map.of(
                                                                "slip",
                                                                slipOf(
                                                                        day,
                                                                        List.of(ok),
                                                                        all,
                                                                        false),
                                                                "reason",
                                                                " ")))
                                        .andReturn()))
                .isBetween(400, 422);
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        amend,
                                                        Map.of(
                                                                "slip",
                                                                slipOf(
                                                                        day,
                                                                        List.of(ok),
                                                                        all,
                                                                        false),
                                                                "reason",
                                                                "x".repeat(1001))))
                                        .andReturn()))
                .as("a reason over 1000 characters")
                .isBetween(400, 422);

        String batches = "/v1/labour/gangs/" + gang() + "/work-slip-batches";
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        batches,
                                                        Map.of(
                                                                "month",
                                                                YearMonth.now(IST).toString())))
                                        .andReturn()))
                .as("the open month is not submitted")
                .isGreaterThanOrEqualTo(400);
        assertThat(
                        said(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        batches,
                                                        Map.of(
                                                                "month",
                                                                month.minusMonths(1).toString())))
                                        .andReturn()))
                .contains("NO_SLIPS");

        MvcResult batch =
                mvc.perform(userPost(ldh, batches, Map.of("month", month.toString()))).andReturn();
        assertThat(status(batch)).as(said(batch)).isEqualTo(202);
        // A slip of a submitted batch is no longer amended.
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        amend,
                                                        Map.of(
                                                                "slip",
                                                                slipOf(day, List.of(ok), all, true),
                                                                "reason",
                                                                "Late correction")))
                                        .andReturn()))
                .isEqualTo(409);
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                body(batch).get("id").asLong(),
                                "RETURN",
                                "IT: bag count to check",
                                null))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT slip_status FROM work_slip WHERE work_slip_id = ?",
                                String.class,
                                nightId))
                .as("a returned batch reopens its slips")
                .isEqualTo("DRAFT");
    }
}
