package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 20 phase 20b (plan docs/plan/20-labour-common-departmental.md; SRS-2-2, SRS-2-29,
 * SRS-2-35, SRS-2-38, SRS-3-1; BR-LABM-004, BR-LABM-005; D20-8): the Labour Dealing Hand of Depot
 * Okhla marks Okhla Gang 1 for every day of last month in the Departmental code set and submits the
 * month; the Payroll Checker of Delhi returns it, the corrected month is submitted again and
 * approved, and each member's month is counted from the marks (F half a day). Refused: a day with a
 * member left unmarked, a code outside the set, a future day, a change while the batch is with the
 * Checker, a mark over another gang's mark and a mark past retirement (both logged and kept), the
 * current month as a batch, the Payroll Maker marking, another office's Maker reading the gang.
 */
class AttendanceIT extends ApiTestSupport {

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

    private Session ldh() throws Exception {
        return login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private MvcResult day(Session s, LocalDate date, Map<String, String> codes) throws Exception {
        List<Map<String, Object>> marks = new ArrayList<>();
        codes.forEach((code, mark) -> marks.add(Map.of("employeeId", member(code), "code", mark)));
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/labour/gangs/" + gang() + "/attendance-days",
                                Map.of("date", date.toString(), "marks", marks)))
                .andReturn();
    }

    private static Map<String, String> all(String code) {
        return Map.of("L10701", code, "L10702", code, "L10002", code, "L10003", code);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String errorCode(MvcResult r) throws Exception {
        String code = body(r).path("errorCode").asString();
        return code.isEmpty() ? status(r) + " " + r.getResponse().getContentAsString() : code;
    }

    private int blocks(long employee, String why) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_amendment_block WHERE employee_id = ? AND"
                        + " block_reason = ?",
                Integer.class,
                employee,
                why);
    }

    // SC-20-0144 (Capture Gang Attendance, main flow: a blank grid in the payroll type's code set,
    // every member marked, the batch submitted and approved), SC-20-0151 (verify: the payable-day
    // figures are derived from the marks by the payroll type's formula, F half a day, never typed).
    @Test
    void SRS_2_29_aMonthIsMarkedSubmittedReturnedCorrectedApprovedAndCounted() throws Exception {
        Session ldh = ldh();
        YearMonth month = YearMonth.now(IST).minusMonths(1);
        String m = month.toString();
        JsonNode grid =
                body(
                        mvc.perform(
                                        userGet(
                                                ldh,
                                                "/v1/labour/gangs/"
                                                        + gang()
                                                        + "/attendance?month="
                                                        + m))
                                .andReturn());
        assertThat(grid.get("batchState").asString()).isEqualTo("OPEN");
        assertThat(grid.get("members")).hasSize(4);
        assertThat(grid.get("codes")).hasSize(15);

        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            Map<String, String> codes = new java.util.HashMap<>(all("W"));
            if (d.getDayOfMonth() == 1) {
                codes.put("L10002", "A");
            }
            if (d.getDayOfMonth() == 2) {
                codes.put("L10003", "F");
            }
            MvcResult saved = day(ldh, d, codes);
            assertThat(status(saved)).as(saved.getResponse().getContentAsString()).isEqualTo(200);
            if (d.getDayOfMonth() == 1) {
                assertThat(body(saved).get("added").asInt()).isEqualTo(4);
                MvcResult again = day(ldh, d, codes);
                assertThat(body(again).get("added").asInt()).as("the same marks again").isZero();
            }
        }
        Map<String, String> changed = new java.util.HashMap<>(all("W"));
        changed.put("L10002", "A");
        changed.put("L10701", "H");
        assertThat(body(day(ldh, month.atDay(1), changed)).get("changed").asInt()).isEqualTo(1);

        MvcResult sent =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/gangs/" + gang() + "/attendance-batches",
                                        Map.of("month", m)))
                        .andReturn();
        assertThat(status(sent)).as(sent.getResponse().getContentAsString()).isEqualTo(202);
        long first = body(sent).get("id").asLong();
        assertThat(errorCode(day(ldh, month.atDay(3), all("W"))))
                .as("no change while the batch is with the Checker")
                .isEqualTo("LBR-409-STATE");
        assertThat(decide(ldh, first, "APPROVE", null, null))
                .as("the maker never decides")
                .isEqualTo(403);
        assertThat(decide(checker(), first, "RETURN", "Day 3 of the Sardar is a holiday", null))
                .isEqualTo(200);
        // SRS-2-29 / l.4516 (US-20-07 AC2): the returned batch shows the Checker's reasons.
        JsonNode returned =
                body(
                        mvc.perform(
                                        userGet(
                                                ldh,
                                                "/v1/labour/gangs/"
                                                        + gang()
                                                        + "/attendance?month="
                                                        + m))
                                .andReturn());
        assertThat(returned.get("batchState").asString()).isEqualTo("RETURNED");
        assertThat(returned.get("returnReason").asString())
                .isEqualTo("Day 3 of the Sardar is a holiday");

        Map<String, String> holiday = new java.util.HashMap<>(all("W"));
        holiday.put("L10701", "H");
        assertThat(status(day(ldh, month.atDay(3), holiday)))
                .as("a returned batch is open again")
                .isEqualTo(200);
        MvcResult again =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/gangs/" + gang() + "/attendance-batches",
                                        Map.of("month", m)))
                        .andReturn();
        assertThat(status(again)).as(again.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(decide(checker(), body(again).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);

        int days = month.lengthOfMonth();
        Map<String, Object> bhola =
                jdbc.queryForMap(
                        "SELECT calendar_days, marked_days, present_days, leave_days, absent_days"
                            + " FROM labour_attendance_month WHERE employee_id = ? AND payroll_yymm"
                            + " = ?",
                        member("L10002"),
                        month.toString().replace("-", ""));
        assertThat(((Number) bhola.get("CALENDAR_DAYS")).intValue()).isEqualTo(days);
        assertThat(((Number) bhola.get("MARKED_DAYS")).intValue()).isEqualTo(days);
        assertThat((BigDecimal) bhola.get("PRESENT_DAYS"))
                .isEqualByComparingTo(BigDecimal.valueOf(days - 1));
        assertThat((BigDecimal) bhola.get("ABSENT_DAYS")).isEqualByComparingTo(BigDecimal.ONE);
        BigDecimal shyam =
                jdbc.queryForObject(
                        "SELECT present_days FROM labour_attendance_month WHERE employee_id = ? AND"
                                + " payroll_yymm = ?",
                        BigDecimal.class,
                        member("L10003"),
                        month.toString().replace("-", ""));
        assertThat(shyam)
                .as("F counts half a day")
                .isEqualByComparingTo(new BigDecimal(days).subtract(new BigDecimal("0.5")));

        JsonNode approved =
                body(
                        mvc.perform(
                                        userGet(
                                                ldh,
                                                "/v1/labour/gangs/"
                                                        + gang()
                                                        + "/attendance?month="
                                                        + m))
                                .andReturn());
        assertThat(approved.get("batchState").asString()).isEqualTo("APPROVED");
        assertThat(errorCode(day(ldh, month.atDay(5), all("W")))).isEqualTo("LBR-409-STATE");
    }

    // SC-20-0145 (past retirement blocked, the reason kept), SC-20-0057 (the payroll type's own
    // codes, never HRMS leave types), SC-20-0074 (each entry checked against the labour master and
    // the capture rules).
    @Test
    void SRS_2_35_aDayIsWholeInTheCodeSetAndOneStatusPerLabourPerDate() throws Exception {
        Session ldh = ldh();
        LocalDate today = LocalDate.now(IST);
        Map<String, String> three = new java.util.HashMap<>(all("W"));
        three.remove("L10003");
        assertThat(body(day(ldh, today, three)).toString()).contains("UNMARKED");
        Map<String, String> unknown = new java.util.HashMap<>(all("W"));
        unknown.put("L10003", "P");
        assertThat(body(day(ldh, today, unknown)).toString()).contains("UNKNOWN_CODE");
        assertThat(body(day(ldh, today.plusDays(1), all("W"))).toString()).contains("FUTURE");
        assertThat(
                        errorCode(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        "/v1/labour/gangs/"
                                                                + gang()
                                                                + "/attendance-batches",
                                                        Map.of(
                                                                "month",
                                                                YearMonth.now(IST).toString())))
                                        .andReturn()))
                .isEqualTo("LBR-422-ATTENDANCE");

        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(status(day(maker, today, all("W"))))
                .as("the Payroll Maker views only")
                .isEqualTo(403);
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        chennai,
                                                        "/v1/labour/gangs/"
                                                                + gang()
                                                                + "/attendance?month="
                                                                + YearMonth.now(IST)))
                                        .andReturn()))
                .as("another office's gang reads as unknown")
                .isEqualTo(404);

        // SRS-2-38: a mark in another gang that day (here the depot's Ancillary Pool) stops the
        // day.
        long bhola = member("L10002");
        long pool = id("SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-DL01-G001'");
        jdbc.update(
                "INSERT INTO labour_attendance (employee_id, gang_id, depot_location_id,"
                    + " attendance_date, attendance_code_id, attendance_code, engine_code,"
                    + " source_code, capture_mode) SELECT ?, ?, g.depot_location_id, ?,"
                    + " c.attendance_code_id, 'W', 'DEPT_LABOUR', 'ENT', 'MANUAL' FROM labour_gang"
                    + " g JOIN attendance_code_master c ON c.engine_code = 'DEPT_LABOUR' AND"
                    + " c.attendance_code = 'W' WHERE g.gang_id = ?",
                bhola,
                pool,
                java.sql.Date.valueOf(today),
                pool);
        MvcResult marked = day(ldh, today, all("W"));
        assertThat(errorCode(marked)).isEqualTo("LBR-409-MARKED");
        assertThat(body(marked).toString()).contains("Ancillary Pool");
        assertThat(blocks(bhola, "ALREADY_MARKED_OTHER_GANG"))
                .as("the attempt is kept")
                .isEqualTo(1);

        // BR-LABM-004: no attendance past the retirement date.
        long shyam = member("L10003");
        // A day of this month's open batch: yesterday, or today on the 1st (yesterday then falls
        // in the previous month, whose batch is no longer editable and refuses first).
        LocalDate yesterday = today.minusDays(1);
        boolean sameMonth = yesterday.getMonthValue() == today.getMonthValue();
        LocalDate day = sameMonth ? yesterday : today;
        Object before =
                jdbc.queryForObject(
                        "SELECT retirement_date FROM employee WHERE employee_id = ?",
                        Object.class,
                        shyam);
        jdbc.update(
                "UPDATE employee SET retirement_date = ? WHERE employee_id = ?",
                java.sql.Date.valueOf(day.minusDays(1)),
                shyam);
        try {
            MvcResult retired = day(ldh, day, all("W"));
            assertThat(errorCode(retired)).isEqualTo("LBR-409-BLOCKED");
            assertThat(blocks(shyam, "PAST_RETIREMENT")).isEqualTo(1);
            Map<String, String> without = new java.util.HashMap<>(all("W"));
            without.remove("L10003");
            if (sameMonth) {
                assertThat(status(day(ldh, yesterday, without))).isEqualTo(200);
            }
        } finally {
            jdbc.update(
                    "UPDATE employee SET retirement_date = ? WHERE employee_id = ?", before, shyam);
        }
    }

    private String said(MvcResult r) throws Exception {
        return status(r) + " " + r.getResponse().getContentAsString();
    }

    /**
     * SRS-2-35 / SRS-2-29 / SRS-2-38: a member marked twice in one day, a batch of the open month,
     * a month with unmarked days and a second submission of a submitted batch are each refused and
     * named; a day of a gang not yet formed is refused.
     */
    @Test
    void SRS_2_29_aBatchIsWholeOncePerMonthAndADayMarksEachMemberOnce() throws Exception {
        Session ldh = ldh();
        LocalDate today = LocalDate.now(IST);
        List<Map<String, Object>> twice = new ArrayList<>();
        twice.add(Map.of("employeeId", member("L10701"), "code", "W"));
        twice.add(Map.of("employeeId", member("L10701"), "code", "A"));
        assertThat(
                        said(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        "/v1/labour/gangs/"
                                                                + gang()
                                                                + "/attendance-days",
                                                        Map.of(
                                                                "date",
                                                                today.toString(),
                                                                "marks",
                                                                twice)))
                                        .andReturn()))
                .contains("MARKED_TWICE");
        String batches = "/v1/labour/gangs/" + gang() + "/attendance-batches";
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
                .as("the open month")
                .isGreaterThanOrEqualTo(400);
        YearMonth part = YearMonth.now(IST).minusMonths(7);
        assertThat(status(day(ldh, part.atDay(2), all("W")))).isEqualTo(200);
        assertThat(
                        said(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        batches,
                                                        Map.of("month", part.toString())))
                                        .andReturn()))
                .contains("UNMARKED_DAYS");
        YearMonth whole = YearMonth.now(IST).minusMonths(8);
        for (int d = 1; d <= whole.lengthOfMonth(); d++) {
            assertThat(status(day(ldh, whole.atDay(d), all("W")))).isEqualTo(200);
        }
        MvcResult first =
                mvc.perform(userPost(ldh, batches, Map.of("month", whole.toString()))).andReturn();
        assertThat(status(first)).as(said(first)).isEqualTo(202);
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        batches,
                                                        Map.of("month", whole.toString())))
                                        .andReturn()))
                .as("a submitted batch is not submitted again")
                .isEqualTo(409);
        assertThat(decide(checker(), body(first).get("id").asLong(), "RETURN", "IT: recheck", null))
                .isEqualTo(200);
        assertThat(said(day(ldh, LocalDate.of(1990, 1, 2), all("W")))).contains("GANG_NOT_ACTIVE");
    }

    /**
     * BMAD TR-052 (SRS-2-38 l.4394): one attendance status per labour per date across all gangs and
     * units. A W mark already held in a gang of another depot under another Divisional Office
     * (Depot Khanna, Ludhiana) stops the Okhla day with LBR-409-MARKED, and the refusal names the
     * existing mark, its gang and its depot; the attempt is kept. The fixture gang and the fixture
     * mark are removed in finally.
     */
    @Test
    void SRS_2_38_aMarkInAGangOfAnotherDepotAndDoStopsTheDayAndNamesTheMark() throws Exception {
        Session ldh = ldh();
        long khanna = id("SELECT location_id FROM org_location WHERE location_code = 'DP-PB01'");
        Integer there =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang WHERE gang_code = 'DP-PB01-G38X'",
                        Integer.class);
        if (there == null || there == 0) {
            jdbc.update(
                    "INSERT INTO labour_gang (gang_code, gang_name, depot_location_id,"
                            + " payroll_type, gang_type, gang_status, effective_from) VALUES"
                            + " ('DP-PB01-G38X', 'Khanna Fixture G38X', ?, 'DEPT_LABOUR',"
                            + " 'HANDLING', 'ACTIVE', ?)",
                    khanna,
                    java.sql.Date.valueOf(LocalDate.now(IST).minusYears(2)));
        }
        long other = id("SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-PB01-G38X'");
        long sardar = member("L10701");
        // A day of a month eleven months back on which no member of the gang is marked yet.
        YearMonth month = YearMonth.now(IST).minusMonths(11);
        LocalDate date = null;
        for (int d = 1; d <= month.lengthOfMonth() && date == null; d++) {
            Integer marked =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM labour_attendance WHERE attendance_date = ? AND"
                                    + " employee_id IN (?, ?, ?, ?)",
                            Integer.class,
                            java.sql.Date.valueOf(month.atDay(d)),
                            sardar,
                            member("L10702"),
                            member("L10002"),
                            member("L10003"));
            if (marked != null && marked == 0) {
                date = month.atDay(d);
            }
        }
        assertThat(date).as("a free day in " + month).isNotNull();
        int blocksBefore = blocks(sardar, "ALREADY_MARKED_OTHER_GANG");
        jdbc.update(
                "INSERT INTO labour_attendance (employee_id, gang_id, depot_location_id,"
                    + " attendance_date, attendance_code_id, attendance_code, engine_code,"
                    + " source_code, capture_mode) SELECT ?, ?, g.depot_location_id, ?,"
                    + " c.attendance_code_id, 'W', 'DEPT_LABOUR', 'ENT', 'MANUAL' FROM labour_gang"
                    + " g JOIN attendance_code_master c ON c.engine_code = 'DEPT_LABOUR' AND"
                    + " c.attendance_code = 'W' WHERE g.gang_id = ?",
                sardar,
                other,
                java.sql.Date.valueOf(date),
                other);
        try {
            MvcResult marked = day(ldh, date, all("W"));
            assertThat(errorCode(marked)).isEqualTo("LBR-409-MARKED");
            List<String> args = new ArrayList<>();
            body(marked).get("args").forEach(a -> args.add(a.asString()));
            assertThat(args)
                    .as("the labour, the existing mark, the date, its gang and its depot")
                    .containsExactly(
                            "L10701", "W", date.toString(), "Khanna Fixture G38X", "DP-PB01");
            assertThat(blocks(sardar, "ALREADY_MARKED_OTHER_GANG"))
                    .as("the attempt is kept")
                    .isEqualTo(blocksBefore + 1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM labour_attendance WHERE"
                                            + " attendance_date = ? AND gang_id = ?",
                                    Integer.class,
                                    java.sql.Date.valueOf(date),
                                    gang()))
                    .as("nothing of the Okhla day is saved")
                    .isZero();
        } finally {
            jdbc.update(
                    "DELETE FROM labour_attendance WHERE employee_id = ? AND gang_id = ? AND"
                            + " attendance_date = ?",
                    sardar,
                    other,
                    java.sql.Date.valueOf(date));
        }
    }
}
