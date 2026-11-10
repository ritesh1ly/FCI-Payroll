package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 21 phase 21a (plan docs/plan/21-dps-labour.md D21-1, D21-2; SRS-4-1, SRS-4-5, SRS-22-1):
 * the Labour Dealing Hand of Depot Okhla marks the DPS gang in the DPS code set (P present, Medical
 * counted); on the first Sunday of a week begun in the previous month, a labour absent on every day
 * of that week in the previous month is marked absent whatever was entered, and the answer names
 * the labour. The Depot Manager reads I-01 and never marks it.
 *
 * <p>Demo data V058_9 (Okhla DPS Gang 1, L20701 ... L20704). Re-runnable: the same marks again.
 */
class DpsAttendanceIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<String> GANG = List.of("L20701", "L20702", "L20703", "L20704");

    private long gang() {
        return jdbc.queryForObject(
                "SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-DL01-G003'", Long.class);
    }

    private long member(String code) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, code);
    }

    private Session ldh() throws Exception {
        return login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
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

    private static Map<String, String> every(String code) {
        Map<String, String> m = new HashMap<>();
        GANG.forEach(g -> m.put(g, code));
        return m;
    }

    /** A past month whose first Sunday falls before the 7th: its week began a month earlier. */
    private static YearMonth splitMonth() {
        YearMonth m = YearMonth.now(IST).minusMonths(3);
        while (m.atDay(1).with(TemporalAdjusters.firstInMonth(DayOfWeek.SUNDAY)).getDayOfMonth()
                >= 7) {
            m = m.minusMonths(1);
        }
        return m;
    }

    @Test
    // SRS-2-11: each labour payroll marks in its own code set held in Payroll, never the M-13 leave
    // types (the DPS set, not the Departmental one).
    void SRS_2_11_SRS_4_1_theDpsGangIsMarkedInTheDpsCodeSetWithMedicalCounted() throws Exception {
        Session ldh = ldh();
        JsonNode grid =
                body(
                        mvc.perform(
                                        userGet(
                                                ldh,
                                                "/v1/labour/gangs/"
                                                        + gang()
                                                        + "/attendance?month="
                                                        + YearMonth.now(IST).minusMonths(2)))
                                .andReturn());
        assertThat(grid.get("members")).hasSize(4);
        Map<String, String> weights = new HashMap<>();
        grid.get("codes")
                .forEach(c -> weights.put(c.get("code").asString(), c.get("weight").asString()));
        assertThat(weights.keySet())
                .containsExactlyInAnyOrder(
                        "W", "A", "H", "D", "P", "S", "K", "B", "F", "M", "CCL", "E");
        assertThat(new java.math.BigDecimal(weights.get("M"))).isEqualByComparingTo("1");
        assertThat(new java.math.BigDecimal(weights.get("P"))).isEqualByComparingTo("1");
        assertThat(new java.math.BigDecimal(weights.get("F"))).isEqualByComparingTo("0.5");
        // A Departmental-only code is outside the DPS set.
        Map<String, String> wrong = every("W");
        wrong.put("L20704", "V");
        assertThat(
                        day(ldh, YearMonth.now(IST).minusMonths(2).atDay(3), wrong)
                                .getResponse()
                                .getStatus())
                .isEqualTo(422);
    }

    // SC-21-0005, SC-21-0018
    @Test
    void SRS_4_5_theFirstSundayOfASplitWeekIsAbsentForALabourAbsentThroughTheWeekBefore()
            throws Exception {
        Session ldh = ldh();
        YearMonth month = splitMonth();
        LocalDate sunday = month.atDay(1).with(TemporalAdjusters.firstInMonth(DayOfWeek.SUNDAY));
        for (LocalDate d = sunday.minusDays(6); d.isBefore(month.atDay(1)); d = d.plusDays(1)) {
            Map<String, String> codes = every("W");
            codes.put("L20703", "A");
            MvcResult saved = day(ldh, d, codes);
            assertThat(saved.getResponse().getStatus())
                    .as(saved.getResponse().getContentAsString())
                    .isEqualTo(200);
            assertThat(body(saved).get("markedAbsent")).isEmpty();
        }
        MvcResult first = day(ldh, sunday, every("W"));
        assertThat(first.getResponse().getStatus())
                .as(first.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(first).get("markedAbsent").valueStream().map(JsonNode::asString))
                .containsExactly("L20703");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attendance_code FROM labour_attendance WHERE employee_id ="
                                        + " ? AND attendance_date = ?",
                                String.class,
                                member("L20703"),
                                java.sql.Date.valueOf(sunday)))
                .isEqualTo("A");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attendance_code FROM labour_attendance WHERE employee_id ="
                                        + " ? AND attendance_date = ?",
                                String.class,
                                member("L20704"),
                                java.sql.Date.valueOf(sunday)))
                .isEqualTo("W");
        // A later Sunday of the month is never marked by the rule.
        MvcResult later = day(ldh, sunday.plusWeeks(1), every("W"));
        assertThat(body(later).get("markedAbsent")).isEmpty();
    }

    @Test
    void SRS_22_1_theDepotManagerReadsI01AndNeverMarksIt() throws Exception {
        Session dm = login("dm.okhla@fci-demo.test", "DEPOT_MANAGER", "DP-DL01");
        assertThat(
                        mvc.perform(
                                        userGet(
                                                dm,
                                                "/v1/labour/gangs/"
                                                        + gang()
                                                        + "/attendance?month="
                                                        + YearMonth.now(IST).minusMonths(2)))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(
                        day(dm, YearMonth.now(IST).minusMonths(2).atDay(4), every("W"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }
}
