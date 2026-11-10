package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.penalty.api.IncrementStops;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Employee penalties received from HRMS through I-21 (plan 12 phase 12c; SRS-14-1, SRS-14-13,
 * CF-17; D12-2, D12-14): a recovery is recorded on I-06 with its Penalty ID and schedule only when
 * its task is approved (source HRMS, linked to the task); an increment stop is the effective-dated
 * flag the increment run reads; a record that could not be applied is refused at intake. Members of
 * Delhi (DH001) near the end of the list.
 */
class PenaltyHrmsIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private HrmsChanges intake;
    @Autowired private IncrementStops stops;

    private long member(int fromEnd) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                    + " 'ACTIVE' AND employment_status = 'IN_SERVICE' ORDER BY emp_num DESC OFFSET "
                        + fromEnd
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"));
    }

    private static String next() {
        YearMonth m = YearMonth.now(IST).plusMonths(1);
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private HrmsChanges.Received received(long employee, String value, String request) {
        return intake.receive(
                new HrmsChanges.Change(
                        employee,
                        "PENALTY_ORDER",
                        value,
                        LocalDate.now(IST),
                        request,
                        HrmsChanges.Source.HRMS_PUSH));
    }

    private void approved(HrmsChanges.Received task) throws Exception {
        Map<String, Object> order = new HashMap<>();
        order.put("orderNo", "PEN/2026/IT");
        order.put("orderDate", LocalDate.now(IST).minusDays(1).toString());
        order.put("issuingAuthority", "Disciplinary Authority, Delhi");
        order.put("heldInHrms", false);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        attachTaskOrder(maker, task.taskId());
        long approval =
                body(mvc.perform(
                                        userPost(
                                                maker,
                                                "/v1/hrms-change-tasks/"
                                                        + task.taskId()
                                                        + "/submissions",
                                                order))
                                .andReturn())
                        .get("approvalId")
                        .asLong();
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
    }

    @Test
    void SRS_14_1_anHrmsRecoveryIsRecordedOnlyOnceItsTaskIsApproved() throws Exception {
        long employee = member(21);
        String request = "IT-PEN-" + System.nanoTime();
        HrmsChanges.Received task =
                received(
                        employee,
                        "{\"nature\":\"RECOVERY\",\"orderReference\":\"DISC/IT/1\",\"effectiveDate\":\""
                                + LocalDate.now(IST)
                                + "\",\"amount\":3000,\"instalments\":3,\"startYymm\":\""
                                + next()
                                + "\"}",
                        request);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM penalty_order WHERE hrms_request_id = ?",
                                Integer.class,
                                request))
                .as("nothing before the approval")
                .isZero();
        approved(task);
        Map<String, Object> order =
                jdbc.queryForMap(
                        "SELECT order_id, penalty_ref, penalty_source, hrms_change_task_id FROM"
                                + " penalty_order WHERE hrms_request_id = ?",
                        request);
        assertThat(order.get("PENALTY_SOURCE")).isEqualTo("HRMS");
        assertThat(((Number) order.get("HRMS_CHANGE_TASK_ID")).longValue())
                .isEqualTo(task.taskId());
        assertThat((String) order.get("PENALTY_REF")).matches("PEN-\\d{4}-\\d{5}");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM penalty_recovery_schedule WHERE order_id = ?",
                                Integer.class,
                                order.get("ORDER_ID")))
                .isEqualTo(3);
    }

    @Test
    void SRS_14_13_anIncrementStopIsTheEffectiveDatedFlagOfTheIncrementRun() throws Exception {
        long employee = member(22);
        LocalDate from = LocalDate.now(IST).plusDays(10);
        LocalDate to = from.plusYears(1);
        String request = "IT-PEN-" + System.nanoTime();
        approved(
                received(
                        employee,
                        "{\"nature\":\"INCREMENT_STOP\",\"orderReference\":\"DISC/IT/2\",\"effectiveDate\":\""
                                + from
                                + "\",\"periodTo\":\""
                                + to
                                + "\"}",
                        request));
        String ref =
                jdbc.queryForObject(
                        "SELECT penalty_ref FROM penalty_order WHERE hrms_request_id = ?",
                        String.class,
                        request);
        assertThat(stops.stopping(employee, from.minusDays(1))).doesNotContain(ref);
        assertThat(stops.stopping(employee, from)).contains(ref);
        assertThat(stops.stopping(employee, to)).contains(ref);
        assertThat(stops.stopping(employee, to.plusDays(1))).doesNotContain(ref);
    }

    @Test
    void SRS_14_1_aRecordThatCouldNotBeAppliedIsRefusedAtIntake() {
        long employee = member(23);
        String today = LocalDate.now(IST).toString();
        for (String bad :
                List.of(
                        "not json",
                        "[1]",
                        "{\"nature\":\"NO_SUCH\",\"orderReference\":\"X\",\"effectiveDate\":\""
                                + today
                                + "\"}",
                        "{\"nature\":\"RECOVERY\",\"orderReference\":\"\",\"effectiveDate\":\""
                                + today
                                + "\"}",
                        "{\"nature\":\"RECOVERY\",\"orderReference\":\"X\"}",
                        "{\"nature\":\"RECOVERY\",\"orderReference\":\"X\",\"effectiveDate\":\"2026-13-01\"}",
                        "{\"nature\":\"RECOVERY\",\"orderReference\":\"X\",\"effectiveDate\":\""
                                + today
                                + "\",\"amount\":0,\"instalments\":1,\"startYymm\":\""
                                + next()
                                + "\"}",
                        "{\"nature\":\"INCREMENT_STOP\",\"orderReference\":\"X\",\"effectiveDate\":\""
                                + today
                                + "\",\"amount\":5}",
                        "{\"nature\":\"INCREMENT_STOP\",\"orderReference\":\"X\",\"effectiveDate\":\""
                                + today
                                + "\",\"periodTo\":\"2000-01-01\"}")) {
            assertThatThrownBy(() -> received(employee, bad, "IT-BAD-" + System.nanoTime()))
                    .as(bad)
                    .isInstanceOf(FciException.class);
        }
    }
}
