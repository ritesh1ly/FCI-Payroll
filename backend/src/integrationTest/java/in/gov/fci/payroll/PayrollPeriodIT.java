package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * The payroll period of the session (M-10 payroll calendar; top-bar selector): every approved
 * calendar period is offered with its cycle state at the session's paying unit, the current one is
 * the month containing today, and the session may work on the current or a back-dated period but
 * never a future one. The demo calendar holds FY 2025-26 and 2026-27 (V018_9).
 */
class PayrollPeriodIT extends ApiTestSupport {

    private static final YearMonth CURRENT =
            YearMonth.from(LocalDate.now(ZoneId.of("Asia/Kolkata")));

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private JsonNode periods(Session s) throws Exception {
        return body(mvc.perform(userGet(s, "/v1/periods")).andExpect(status().isOk()).andReturn());
    }

    private int choose(Session s, String period) throws Exception {
        return mvc.perform(userPut(s, "/auth/period", Map.of("period", period)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void M_10_everyCalendarPeriodIsListedNewestFirstWithTheCurrentOneFlagged() throws Exception {
        JsonNode list = periods(maker());
        assertThat(texts(list, "period"))
                .contains("2025-04", "2026-07", "2026-08", "2026-09", "2026-10", "2027-03");
        assertThat(list.get(0).get("period").asString()).isEqualTo("2027-03");
        long currents = 0;
        for (JsonNode p : list) {
            if (p.get("current").asBoolean()) {
                currents++;
                assertThat(p.get("period").asString()).isEqualTo(CURRENT.toString());
            }
        }
        assertThat(currents).isEqualTo(1);
        // The session's period defaults to the calendar's current month (not a configured July).
        mvc.perform(userGet(maker(), "/auth/me"))
                .andExpect(jsonPath("$.period").value(CURRENT.toString()))
                .andExpect(jsonPath("$.periodStatus").value("CURRENT"));
    }

    @Test
    void M_10_eachPeriodCarriesTheStateOfItsCycleAtTheSessionsPayingUnit() throws Exception {
        Long unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DH001'",
                        Long.class);
        // The month is not fixed: other suites initiate cycles for months counted back from today
        // (JobRequestIT 1-3, CycleIT 14-17 months back), so a fixed month collided with them on
        // some
        // dates (CI, October 2026: CycleIT's 16 months back is June 2025). Use a calendar month out
        // of their reach - older than 17 months, else a future one but 2027-03 (asserted empty) -
        // that holds no cycle at Delhi yet or this fixture's FROZEN one.
        String oldest = CURRENT.minusMonths(17).toString().replace("-", "");
        String latest = CURRENT.toString().replace("-", "");
        String yymm =
                jdbc.queryForList(
                                "SELECT c.payroll_yymm FROM payroll_calendar c WHERE NOT EXISTS"
                                        + " (SELECT 1 FROM payroll_cycle_instance i WHERE"
                                        + " i.payroll_yymm = c.payroll_yymm AND i.org_unit_id = ?"
                                        + " AND i.cycle_status <> 'FROZEN') AND (c.payroll_yymm"
                                        + " < ? OR (c.payroll_yymm > ? AND c.payroll_yymm <>"
                                        + " '202703')) ORDER BY c.payroll_yymm",
                                String.class,
                                unit,
                                oldest,
                                latest)
                        .get(0);
        String period = yymm.substring(0, 4) + "-" + yymm.substring(4);
        String finYear =
                jdbc.queryForObject(
                        "SELECT fin_year FROM payroll_calendar WHERE payroll_yymm = ?",
                        String.class,
                        yymm);
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = ?"
                                + " AND org_unit_id = ?",
                        Integer.class,
                        yymm,
                        unit)
                == 0) {
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by) VALUES (?, ?, 'DO',"
                        + " ?, 'FROZEN', 'ON_DEMAND', 'it.fixture')",
                    yymm,
                    unit,
                    finYear);
        }
        JsonNode delhi = periods(maker());
        assertThat(texts(delhi, "period")).contains(period);
        for (JsonNode p : delhi) {
            if (p.get("period").asString().equals(period)) {
                assertThat(p.get("status").asString()).isEqualTo("FROZEN");
                assertThat(p.get("finYear").asString()).isEqualTo(finYear);
            }
            if (p.get("period").asString().equals("2027-03")) {
                assertThat(p.get("status").isNull()).as("no cycle initiated").isTrue();
            }
        }
        // Another office has its own cycles: Kolkata sees its own state of that month, never
        // Delhi's.
        Long kolkataUnit =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'KL001'",
                        Long.class);
        String kolkataState =
                jdbc
                        .queryForList(
                                "SELECT cycle_status FROM payroll_cycle_instance WHERE"
                                        + " payroll_yymm = ? AND org_unit_id = ?",
                                String.class,
                                yymm,
                                kolkataUnit)
                        .stream()
                        .findFirst()
                        .orElse(null);
        JsonNode kolkata = periods(login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001"));
        for (JsonNode p : kolkata) {
            if (p.get("period").asString().equals(period)) {
                if (kolkataState == null) {
                    assertThat(p.get("status").isNull()).isTrue();
                } else {
                    assertThat(p.get("status").asString()).isEqualTo(kolkataState);
                }
            }
        }
        // An eSeva member has no office: periods without cycle states.
        JsonNode member = periods(login("emp.rsharma@fci-demo.test", null, null));
        // JSON null on every period (asserted as a node: Jackson 3 renders null as "", 2 as
        // "null").
        assertThat(member.findValues("status")).isNotEmpty().allMatch(JsonNode::isNull);
    }

    @Test
    void M_10_theSessionWorksOnACurrentOrBackDatedPeriodNeverAFutureOne() throws Exception {
        Session maker = maker();
        String backDated = CURRENT.minusMonths(2).toString();
        assertThat(choose(maker, backDated)).isEqualTo(204);
        mvc.perform(userGet(maker, "/auth/me"))
                .andExpect(jsonPath("$.period").value(backDated))
                .andExpect(jsonPath("$.periodStatus").value("PAST"));
        // C-02 reads the cycle of the chosen period.
        mvc.perform(userGet(maker, "/v1/cycles/" + backDated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value(backDated));

        // A future period, one outside the calendar and a malformed one are refused.
        assertThat(choose(maker, CURRENT.plusMonths(1).toString())).isEqualTo(422);
        assertThat(choose(maker, "2019-01")).isEqualTo(422);
        assertThat(choose(maker, "2026-13")).isEqualTo(400);
        assertThat(choose(maker, "July")).isEqualTo(400);
        // The refused choices left the session on its back-dated period; back to the current one.
        mvc.perform(userGet(maker, "/auth/me")).andExpect(jsonPath("$.period").value(backDated));
        assertThat(choose(maker, CURRENT.toString())).isEqualTo(204);
        mvc.perform(userGet(maker, "/auth/me"))
                .andExpect(jsonPath("$.period").value(CURRENT.toString()))
                .andExpect(jsonPath("$.periodStatus").value("CURRENT"));
    }

    @Test
    void anAnonymousRequestGetsNoPeriodsAndCannotChooseOne() throws Exception {
        assertThat(mvc.perform(get("/v1/periods")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        int put =
                mvc.perform(
                                withCsrf(MockMvcRequestBuilders.put("/auth/period"))
                                        .contentType("application/json")
                                        .content("{\"period\":\"2026-08\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(put).isIn(401, 403);
    }
}
