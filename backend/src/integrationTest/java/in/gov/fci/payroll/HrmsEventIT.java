package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Life-cycle events received from HRMS (plan 16 phase 16f-1; SRS-1-63, SRS-1-64, SRS-1-7): each
 * opens an I-21 task; approved, it records the event (source HRMS, linked to the task) and applies
 * it: a suspension sets the status and the subsistence the engine pays, absconding flags the member
 * so the payment is held; a suspension without a valid percentage cannot be approved. The members
 * are Delhi HQ (DH001) employees near the end of the list.
 */
class HrmsEventIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private HrmsChanges intake;
    @Autowired private NamedParameterJdbcTemplate named;
    @Autowired private PayConfiguration configuration;

    private long member(int fromEnd) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                    + " 'ACTIVE' AND employment_status = 'IN_SERVICE' ORDER BY emp_num DESC OFFSET "
                        + fromEnd
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"));
    }

    private long sent(long employee, String field, String value) throws Exception {
        return sent(employee, field, value, LocalDate.now(IST));
    }

    private long sent(long employee, String field, String value, LocalDate effective)
            throws Exception {
        HrmsChanges.Received task =
                intake.receive(
                        new HrmsChanges.Change(
                                employee,
                                field,
                                value,
                                effective,
                                "IT-EV-" + System.nanoTime(),
                                HrmsChanges.Source.HRMS_PUSH));
        Map<String, Object> order = new HashMap<>();
        order.put("orderNo", "DISC/2026/IT");
        order.put("orderDate", LocalDate.now(IST).minusDays(1).toString());
        order.put("issuingAuthority", "General Manager (P), Delhi");
        order.put("heldInHrms", false);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        attachTaskOrder(maker, task.taskId());
        return body(mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/hrms-change-tasks/" + task.taskId() + "/submissions",
                                        order))
                        .andReturn())
                .get("approvalId")
                .asLong();
    }

    private int approve(long approval) throws Exception {
        return decide(
                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                approval,
                "APPROVE",
                null,
                null);
    }

    // SC-16-0185: the event carries the order and its linked I-21 task.
    @Test
    void SRS_1_63_anApprovedHrmsSuspensionIsRecordedAndPaysTheSubsistence() throws Exception {
        long employee = member(8);
        assertThat(approve(sent(employee, "SUSPENSION", "50"))).isEqualTo(200);
        Map<String, Object> event =
                jdbc.queryForMap(
                        "SELECT event_source, subsistence_percent, hrms_change_task_id,"
                            + " approval_id, order_reference FROM employee_lifecycle_event WHERE"
                            + " employee_id = ? AND event_type = 'SUSPENSION'",
                        employee);
        assertThat(event.get("EVENT_SOURCE")).isEqualTo("HRMS");
        assertThat(((Number) event.get("SUBSISTENCE_PERCENT")).intValue()).isEqualTo(50);
        assertThat(event.get("HRMS_CHANGE_TASK_ID")).isNotNull();
        assertThat(event.get("ORDER_REFERENCE")).isEqualTo("DISC/2026/IT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT employment_status FROM employee WHERE employee_id = ?",
                                String.class,
                                employee))
                .isEqualTo("SUSPENDED");

        LocalDate start = YearMonth.now(IST).atDay(1);
        MemberReader.MemberRow row = MemberReader.member(named, employee, start).orElseThrow();
        assertThat(row.subsistencePercent()).isEqualByComparingTo("50");
        PayRules.Outcome o =
                PayRules.compute(row.member(), configuration.staffConfiguration(start));
        PayRules.Line basic =
                o.lines().stream()
                        .filter(l -> l.payCode() == PayRules.BASIC)
                        .findFirst()
                        .orElseThrow();
        assertThat(basic.amount())
                .isEqualByComparingTo(
                        row.basic()
                                .divide(java.math.BigDecimal.valueOf(2))
                                .setScale(0, java.math.RoundingMode.HALF_UP));
    }

    /**
     * US-GAP-277 (I-17 Effective Date, SRS l.3877; OD-74: I-17 is read-only, events come from HRMS
     * through I-21): an event dated before the member's joining date is refused at the Checker's
     * approval with EMP-422-BEFORE-JOINING naming both dates; the item stays pending, no event is
     * recorded and the member's status and flags are unchanged. The joining day itself is accepted
     * (boundary, EventRulesTest).
     */
    @Test
    void I_17_anEventBeforeTheJoiningDateIsRefused() throws Exception {
        long employee = member(11);
        LocalDate joined =
                jdbc.queryForObject(
                                "SELECT comp_joining_date FROM employee WHERE employee_id = ?",
                                java.sql.Date.class,
                                employee)
                        .toLocalDate();
        long approval = sent(employee, "ABSCONDING", "Y", joined.minusDays(1));
        var refused =
                mvc.perform(
                                userPost(
                                        login(
                                                "ddo.delhi@fci-demo.test",
                                                "PAYROLL_CHECKER",
                                                "DH001"),
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(refused).path("errorCode").asString()).isEqualTo("EMP-422-BEFORE-JOINING");
        assertThat(refused.getResponse().getContentAsString())
                .contains(joined.minusDays(1).toString(), joined.toString());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM employee_lifecycle_event WHERE employee_id ="
                                        + " ? AND event_type = 'ABSCONDING'",
                                Integer.class,
                                employee))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT absconding_flag FROM employee WHERE employee_id = ?",
                                String.class,
                                employee))
                .isNotEqualTo("Y");
    }

    @Test
    void SRS_1_64_aBadSubsistenceIsRefusedAndAbscondingHoldsThePayment() throws Exception {
        long bad = sent(member(9), "SUSPENSION", "fifty");
        assertThat(approve(bad)).as("not a percentage").isEqualTo(422);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                bad))
                .isEqualTo("PENDING_APPROVAL");

        long employee = member(10);
        assertThat(approve(sent(employee, "ABSCONDING", "Y"))).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT absconding_flag FROM employee WHERE employee_id = ?",
                                String.class,
                                employee))
                .isEqualTo("Y");
        MemberReader.MemberRow row =
                MemberReader.member(named, employee, YearMonth.now(IST).atDay(1)).orElseThrow();
        assertThat(row.member().paymentHold()).isEqualTo("ABSCONDING");
    }
}
