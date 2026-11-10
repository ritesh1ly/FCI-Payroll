package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Module 22 phase 22a (plan docs/plan/22-nwnp-labour.md; SRS-5-4, SRS-5-6, SRS-5-8, SRS-5-14): the
 * Labour Dealing Hand of Depot Okhla records the NWNP gang's work-slips without attendance codes
 * (pay rests on the work-slip); a slip within a time frame already entered is refused naming it;
 * Present But No Work after 17:30 is a separate slip; a labour whose membership ends on the slip
 * date is left out and flagged for review on X-05; the month goes to the Payroll Checker as one
 * batch; an NWNP slip is never sent to the Depot Manager as a booking-slip.
 *
 * <p>Demo data V060_9 (Okhla NWNP Gang 1, L30701 ... L30704), V054_9 (operations W2P, T2P, PBNW).
 */
class NwnpWorkSlipIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long gang() {
        return id("SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-DL01-G005'");
    }

    private long member(String code) {
        return id("SELECT employee_id FROM employee WHERE emp_num = ?", code);
    }

    private List<Long> all() {
        return List.of(member("L30701"), member("L30702"), member("L30703"), member("L30704"));
    }

    private Map<String, Object> row(String op, String start, String end) {
        Map<String, Object> r = new HashMap<>();
        r.put(
                "operationId",
                id("SELECT operation_id FROM operation_master WHERE operation_code = ?", op));
        if (!"PBNW".equals(op)) {
            r.put(
                    "bagTypeId",
                    id("SELECT bag_type_id FROM bag_type WHERE bag_type_code = 'KATTA50'"));
            r.put("bags", 300);
        } else {
            r.put("pbnwReason", "RAKE_DELAYED");
        }
        r.put("start", start);
        r.put("end", end);
        return r;
    }

    private Map<String, Object> slip(LocalDate day, Map<String, Object> row, List<Long> who) {
        Map<String, Object> s = new HashMap<>();
        s.put("date", day.toString());
        s.put("continueWork", false);
        s.put("rows", List.of(row));
        s.put("participants", who);
        return s;
    }

    private MvcResult save(Session s, Map<String, Object> slip) throws Exception {
        return mvc.perform(userPost(s, "/v1/labour/gangs/" + gang() + "/work-slips", slip))
                .andReturn();
    }

    private static int code(MvcResult r) {
        return r.getResponse().getStatus();
    }

    // SC-22-0004, SC-22-0005, SC-22-0007, SC-22-0013, SC-22-0022
    @Test
    void SRS_5_8_nwnpWorkSlipsRestOnTheSlipRefuseAFrameWithinAnotherAndLeaveOutAnEndedMember()
            throws Exception {
        Session ldh = login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
        YearMonth month = YearMonth.now(IST).minusMonths(2);
        LocalDate day = month.atDay(8);

        // SRS-5-14: no attendance is marked for the NWNP gang; the slip is taken all the same.
        MvcResult first = save(ldh, slip(day, row("W2P", "09:00", "17:00"), all()));
        assertThat(code(first)).as(first.getResponse().getContentAsString()).isEqualTo(201);
        String firstNo = body(first).get("number").asString();
        assertThat(body(first).get("labourStrength").asInt()).isEqualTo(4);

        // SRS-5-8: another operation within that time frame is a duplicate.
        MvcResult inside = save(ldh, slip(day, row("T2P", "10:00", "12:00"), all()));
        assertThat(code(inside)).isEqualTo(409);
        assertThat(body(inside).get("args").get(0).asString()).isEqualTo(firstNo);

        // SRS-5-6: Present But No Work after 17:30 stands on its own slip.
        MvcResult pbnw = save(ldh, slip(day, row("PBNW", "17:30", "19:00"), all()));
        assertThat(code(pbnw)).as(pbnw.getResponse().getContentAsString()).isEqualTo(201);

        // SRS-5-4: a labour whose membership ends on the slip date is left out and flagged.
        LocalDate ending = month.atDay(9);
        long l4 = member("L30704");
        jdbc.update(
                "UPDATE labour_gang_membership SET effective_to = ? WHERE gang_id = ? AND"
                        + " employee_id = ?",
                java.sql.Date.valueOf(ending),
                gang(),
                l4);
        try {
            MvcResult partial = save(ldh, slip(ending, row("W2P", "09:00", "13:00"), all()));
            assertThat(code(partial)).as(partial.getResponse().getContentAsString()).isEqualTo(201);
            assertThat(body(partial).get("participants")).hasSize(3);
            assertThat(body(partial).get("labourStrength").asInt()).isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM payroll_exception WHERE exception_code ="
                                            + " 'NWNP_MEMBERSHIP_ENDS' AND employee_id = ?",
                                    Integer.class,
                                    l4))
                    .isPositive();
            // Only the ended member: on its own the slip has nobody.
            MvcResult nobody = save(ldh, slip(ending, row("T2P", "14:00", "15:00"), List.of(l4)));
            assertThat(code(nobody)).isEqualTo(422);
            assertThat(body(nobody).get("args").get(0).asString()).isEqualTo("PARTICIPANTS");
        } finally {
            jdbc.update(
                    "UPDATE labour_gang_membership SET effective_to = NULL WHERE gang_id = ? AND"
                            + " employee_id = ?",
                    gang(),
                    l4);
        }

        // An NWNP slip is not a booking-slip.
        MvcResult notBooking =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/work-slips/"
                                                + body(first).get("id").asLong()
                                                + "/submissions",
                                        Map.of()))
                        .andReturn();
        assertThat(code(notBooking)).isEqualTo(422);

        // SRS-2-29: the month goes to the Payroll Checker as one batch.
        MvcResult batch =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/gangs/" + gang() + "/work-slip-batches",
                                        Map.of("month", month.toString())))
                        .andReturn();
        assertThat(code(batch)).as(batch.getResponse().getContentAsString()).isEqualTo(202);
    }
}
