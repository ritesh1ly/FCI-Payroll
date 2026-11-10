package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * M-10 Payroll Calendar & Cycle Setup (plan 05 phase 05f; SRS-15-33, SRS M-10, SRS-15-1, SRS-5-10):
 * the System Administrator proposes, the Payroll Checker at HQ approves, the period is written with
 * its approval; the checks of the field table; a period a cycle was initiated for is fixed (D-12);
 * the run order keeps its dependencies (D-13). Uses the April of the financial year after the
 * current one (no demo period, no other IT).
 */
class PayrollCalendarIT extends ApiTestSupport {

    private static final YearMonth CURRENT =
            YearMonth.from(LocalDate.now(ZoneId.of("Asia/Kolkata")));

    /** April of the next financial year. */
    private static final YearMonth APRIL =
            YearMonth.of(
                    CURRENT.getMonthValue() >= 4 ? CURRENT.getYear() + 1 : CURRENT.getYear(), 4);

    private static final String FIN_YEAR =
            APRIL.getYear() + "-" + String.valueOf(APRIL.getYear() + 1).substring(2);

    private Session admin() throws Exception {
        return login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
    }

    private Session checker() throws Exception {
        return login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
    }

    private static Map<String, Object> period(YearMonth m, int freeze, int lock, int window) {
        Map<String, Object> p = new HashMap<>();
        p.put("month", m.toString());
        p.put("start", m.atDay(1).toString());
        p.put("end", m.atEndOfMonth().toString());
        p.put("freezeTarget", m.atDay(freeze).toString());
        p.put("lockTarget", m.atDay(lock).toString());
        p.put("supplementaryFrom", m.atDay(window).toString());
        p.put("supplementaryTo", m.atEndOfMonth().toString());
        p.put("mdpFrom", m.atDay(window).toString());
        p.put("mdpTo", m.atEndOfMonth().toString());
        return p;
    }

    private MvcResult propose(Session s, Map<String, Object> period) throws Exception {
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/payroll-calendars/" + FIN_YEAR + "/proposals",
                                Map.of(
                                        "periods",
                                        List.of(period),
                                        "reason",
                                        "Calendar " + FIN_YEAR)))
                .andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private JsonNode year(Session s) throws Exception {
        MvcResult r = mvc.perform(userGet(s, "/v1/payroll-calendars/" + FIN_YEAR)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return body(r);
    }

    /**
     * SRS-15-33 / SRS M-10: the Administrator proposes, the Checker approves; the field table's
     * checks refuse a wrong proposal before it reaches the Checker; once a cycle is initiated for
     * the period, its dates are fixed (D-12).
     */
    @Test
    void SRS_15_33_theAdministratorProposesThePeriodAndTheCheckerApprovesIt() throws Exception {
        Session admin = admin();
        JsonNode years = body(mvc.perform(userGet(admin, "/v1/payroll-calendars")).andReturn());
        List<String> offered = new ArrayList<>();
        years.forEach(y -> offered.add(y.asString()));
        assertThat(offered).contains(FIN_YEAR);

        assertThat(
                        propose(
                                        login(
                                                "maker.delhi@fci-demo.test",
                                                "PAYROLL_MAKER",
                                                "DH001"),
                                        period(APRIL, 20, 28, 28))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        MvcResult window = propose(admin, period(APRIL, 20, 28, 27));
        assertThat(code(window)).isEqualTo("CAL-422-PERIOD");
        assertThat(body(window).get("args").get(1).asString())
                .isEqualTo("SUPPLEMENTARY_BEFORE_LOCK");
        assertThat(code(propose(admin, period(APRIL, 25, 24, 28)))).isEqualTo("CAL-422-PERIOD");
        Map<String, Object> missing = period(APRIL, 20, 28, 28);
        missing.remove("lockTarget");
        assertThat(propose(admin, missing).getResponse().getStatus()).isEqualTo(400);

        MvcResult asked = propose(admin, period(APRIL, 20, 28, 28));
        assertThat(asked.getResponse().getStatus())
                .as(asked.getResponse().getContentAsString())
                .isEqualTo(202);
        long approvalId = body(asked).get("approvalId").asLong();
        assertThat(year(admin).get("pendingApprovalId").asLong()).isEqualTo(approvalId);
        assertThat(decide(admin, approvalId, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(checker(), approvalId, "APPROVE", null, null)).isEqualTo(200);

        JsonNode approved = year(admin);
        assertThat(approved.get("pendingApprovalId").isNull()).isTrue();
        JsonNode april = approved.get("periods").get(0);
        assertThat(april.get("period").get("month").asString()).isEqualTo(APRIL.toString());
        assertThat(april.get("period").get("lockTarget").asString())
                .isEqualTo(APRIL.atDay(28).toString());
        assertThat(april.get("initiated").asBoolean()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT approval_id FROM payroll_calendar WHERE payroll_yymm = ?",
                                Long.class,
                                APRIL.toString().replace("-", "")))
                .isEqualTo(approvalId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'payroll_calendar' AND entity_key = ? AND field_name ="
                                        + " 'lock_target_date'",
                                Integer.class,
                                APRIL.toString().replace("-", "")))
                .isGreaterThanOrEqualTo(1);

        // May may not start inside the approved April (no overlap with an adjacent period).
        Map<String, Object> may = period(APRIL.plusMonths(1), 20, 28, 28);
        may.put("start", APRIL.atDay(30).toString());
        MvcResult overlap = propose(admin, may);
        assertThat(code(overlap)).isEqualTo("CAL-422-PERIOD");
        assertThat(body(overlap).get("args").get(1).asString()).isEqualTo("OVERLAP");

        // A cycle initiated for April fixes its dates.
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by) VALUES (?, ?, 'DO',"
                        + " ?, 'INITIATED', 'ON_DEMAND', 'it.maker')",
                APRIL.toString().replace("-", ""),
                locationId("LD001"),
                FIN_YEAR);
        assertThat(code(propose(admin, period(APRIL, 21, 28, 28)))).isEqualTo("CAL-409-IN-USE");
        assertThat(year(admin).get("periods").get(0).get("initiated").asBoolean()).isTrue();
    }

    /**
     * SRS-15-1 / SRS-5-10 (D-13): the seeded steps; a new order that runs a step before the step it
     * depends on, or leaves one out, is refused; a valid one is applied on the Checker's approval.
     */
    // SC-05-0061: the engine run order and its dependencies.
    @Test
    void SRS_15_1_theRunOrderKeepsItsDependenciesAndChangesOnApproval() throws Exception {
        Session admin = admin();
        JsonNode view = body(mvc.perform(userGet(admin, "/v1/run-order-steps")).andReturn());
        List<String> order = new ArrayList<>();
        view.get("steps").forEach(s -> order.add(s.get("code").asString()));
        assertThat(order)
                .contains(
                        "STAFF_PAYROLL",
                        "NWNP_INITIALISE",
                        "NWNP_MONTH_END",
                        "LABOUR_INCENTIVE",
                        "DEPT_LABOUR_WAGES");
        assertThat(order.indexOf("LABOUR_INCENTIVE"))
                .isGreaterThan(order.indexOf("DEPT_LABOUR_WAGES"));

        List<String> early = new ArrayList<>(order);
        early.remove("LABOUR_INCENTIVE");
        early.add(0, "LABOUR_INCENTIVE");
        MvcResult refused =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/run-order-steps/proposals",
                                        Map.of("order", early, "reason", "Incentive first")))
                        .andReturn();
        assertThat(code(refused)).isEqualTo("CAL-422-ORDER");
        assertThat(body(refused).get("args").get(0).asString()).isEqualTo("BEFORE_DEPENDENCY");
        assertThat(
                        code(
                                mvc.perform(
                                                userPost(
                                                        admin,
                                                        "/v1/run-order-steps/proposals",
                                                        Map.of(
                                                                "order",
                                                                order.subList(1, order.size()),
                                                                "reason",
                                                                "One missing")))
                                        .andReturn()))
                .isEqualTo("CAL-422-ORDER");

        // Swap the first two independent steps (the staff payroll and the departmental wages).
        List<String> swapped = new ArrayList<>(order);
        int staff = swapped.indexOf("STAFF_PAYROLL");
        int dept = swapped.indexOf("DEPT_LABOUR_WAGES");
        swapped.set(staff, "DEPT_LABOUR_WAGES");
        swapped.set(dept, "STAFF_PAYROLL");
        MvcResult asked =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/run-order-steps/proposals",
                                        Map.of("order", swapped, "reason", "Labour first")))
                        .andReturn();
        assertThat(asked.getResponse().getStatus())
                .as(asked.getResponse().getContentAsString())
                .isEqualTo(202);
        long approvalId = body(asked).get("approvalId").asLong();
        assertThat(decide(checker(), approvalId, "APPROVE", null, null)).isEqualTo(200);
        JsonNode after = body(mvc.perform(userGet(admin, "/v1/run-order-steps")).andReturn());
        List<String> now = new ArrayList<>();
        after.get("steps").forEach(s -> now.add(s.get("code").asString()));
        assertThat(now).isEqualTo(swapped);
        assertThat(after.get("pendingApprovalId").isNull()).isTrue();
    }

    /** M-10 Cycle Parameters panel (plan 05 D-23; SRS-15-39): read-only, M-10 VIEW only. */
    @Test
    void SRS_15_39_theCycleParametersAreReadFromTheScreensThatKeepThem() throws Exception {
        JsonNode p =
                body(
                        mvc.perform(userGet(checker(), "/v1/cycle-parameters"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(p.get("authorities").findValuesAsString("action")).contains("C02_LOCK");
        assertThat(p.get("mdpHeads").findValuesAsString("payCode")).contains("348");
        assertThat(p.get("checks").findValuesAsString("code"))
                .contains("P1_RETIREMENT", "NET_PAY_NOT_BELOW_MINIMUM");
        int hrStatus =
                mvc.perform(
                                userGet(
                                        login(
                                                "hr.delhi@fci-demo.test",
                                                "ESTABLISHMENT_HR",
                                                "RO-DL"),
                                        "/v1/cycle-parameters"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(hrStatus).isEqualTo(403);
    }
}
