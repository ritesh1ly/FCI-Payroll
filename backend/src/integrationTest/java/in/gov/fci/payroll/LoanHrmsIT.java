package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Employee loans received from HRMS through I-21 (plan 09 phase 09c; SRS-12-17, SRS-12-19,
 * SRS-12-4, SRS-12-16, SRS-12-21; D09-5, D09-13): a sanction reaches the ledger only when its task
 * is approved (source HRMS, linked to the task); a reschedule recomputes on the balance remaining
 * and keeps the superseded schedule; a foreclosure clears the outstanding in one event and needs
 * the Checker's LOAN_FORECLOSURE OTP; a record that could not be applied is refused at intake. The
 * members are Delhi HQ (DH001) employees near the end of the list.
 */
class LoanHrmsIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private HrmsChanges intake;

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

    private HrmsChanges.Received received(
            long employee, String field, String value, String request) {
        return intake.receive(
                new HrmsChanges.Change(
                        employee,
                        field,
                        value,
                        LocalDate.now(IST),
                        request,
                        HrmsChanges.Source.HRMS_PUSH));
    }

    private long submitted(HrmsChanges.Received task) throws Exception {
        Map<String, Object> order = new HashMap<>();
        order.put("orderNo", "LOAN/2026/IT");
        order.put("orderDate", LocalDate.now(IST).minusDays(1).toString());
        order.put("issuingAuthority", "AGM (Pay), Delhi");
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

    private int approve(long approval, String otp) throws Exception {
        return decide(
                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                approval,
                "APPROVE",
                null,
                otp);
    }

    private Map<String, Object> loan(String request) {
        return jdbc.queryForMap(
                "SELECT loan_id, loan_number, loan_status, loan_source, principal_outstanding,"
                        + " interest_outstanding, hrms_change_task_id, instalment_amount FROM"
                        + " loan_header WHERE hrms_request_id = ?",
                request);
    }

    @Test
    void
            SRS_12_19_anHrmsSanctionReachesTheLedgerOnlyWhenItsTaskIsApprovedAndIsRescheduledOnTheBalance()
                    throws Exception {
        long employee = member(11);
        String request = "IT-LN-" + System.nanoTime();
        HrmsChanges.Received task =
                received(
                        employee,
                        "LOAN_SANCTION",
                        "{\"loanType\":\"PLI_ADVANCE\",\"sanctioned\":12000,\"principalInstalments\":4,"
                            + "\"instalment\":3000,\"startYymm\":\""
                                + next()
                                + "\"}",
                        request);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM loan_header WHERE hrms_request_id = ?",
                                Long.class,
                                request))
                .as("not before approval")
                .isZero();
        assertThat(approve(submitted(task), null)).isEqualTo(200);
        Map<String, Object> l = loan(request);
        assertThat(l.get("LOAN_SOURCE")).isEqualTo("HRMS");
        assertThat(l.get("LOAN_STATUS")).isEqualTo("ACTIVE");
        assertThat(((Number) l.get("HRMS_CHANGE_TASK_ID")).longValue()).isEqualTo(task.taskId());

        // 09d records recoveries; here a recovery of 3,000 already reduced the balance to 9,000.
        jdbc.update(
                "UPDATE loan_header SET principal_outstanding = 9000 WHERE loan_id = ?",
                l.get("LOAN_ID"));
        HrmsChanges.Received reschedule =
                received(
                        employee,
                        "LOAN_RESCHEDULE",
                        "{\"loanReference\":\""
                                + request
                                + "\",\"principalInstalments\":6,\"instalment\":1500,\"startYymm\":\""
                                + next()
                                + "\"}",
                        request + "-R");
        assertThat(approve(submitted(reschedule), null)).isEqualTo(200);
        Map<String, Object> r = loan(request);
        assertThat(r.get("LOAN_STATUS")).isEqualTo("RESCHEDULED");
        assertThat((BigDecimal) r.get("INSTALMENT_AMOUNT")).isEqualByComparingTo("1500");
        List<Map<String, Object>> versions =
                jdbc.queryForList(
                        "SELECT version_no, reason, superseded_at FROM loan_schedule_version WHERE"
                                + " loan_id = ? ORDER BY version_no",
                        r.get("LOAN_ID"));
        assertThat(versions)
                .extracting(v -> v.get("REASON"))
                .containsExactly("ORIGINAL", "RESCHEDULE");
        assertThat(versions.get(0).get("SUPERSEDED_AT"))
                .as("SRS-12-20: kept, superseded")
                .isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT SUM(l.principal_amount) FROM loan_recovery_schedule l JOIN"
                                    + " loan_schedule_version v ON v.schedule_version_id ="
                                    + " l.schedule_version_id WHERE v.loan_id = ? AND v.version_no"
                                    + " = 2",
                                BigDecimal.class,
                                r.get("LOAN_ID")))
                .as("SRS-12-4: on the remaining 9,000")
                .isEqualByComparingTo("9000");
        // SRS-12-10 (US-GAP-30): the member is told the new instalment, as for an I-04 change.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification WHERE recipient_employee_id = ?"
                                        + " AND notification_type = 'LOAN_INSTALMENT' AND channel ="
                                        + " 'IN_APP' AND related_entity_key = ? AND message LIKE"
                                        + " '%RESCHEDULE%1500%'",
                                Integer.class, employee, r.get("LOAN_NUMBER")))
                .isEqualTo(1);
    }

    @Test
    void SRS_12_21_anHrmsForeclosureClearsTheOutstandingWithTheCheckersOtp() throws Exception {
        long employee = member(12);
        String request = "IT-LN-" + System.nanoTime();
        assertThat(
                        approve(
                                submitted(
                                        received(
                                                employee,
                                                "LOAN_SANCTION",
                                                "{\"loanType\":\"FESTIVAL_ADVANCE\",\"sanctioned\":10000,"
                                                    + "\"principalInstalments\":5,\"instalment\":2000,"
                                                    + "\"startYymm\":\""
                                                        + next()
                                                        + "\"}",
                                                request)),
                                null))
                .isEqualTo(200);
        long approval =
                submitted(
                        received(
                                employee,
                                "LOAN_FORECLOSURE",
                                "{\"loanReference\":\"" + request + "\"}",
                                request + "-F"));
        assertThat(approve(approval, null)).as("no OTP: asked for one").isEqualTo(428);
        assertThat(approve(approval, "LOAN_FORECLOSURE")).isEqualTo(200);
        Map<String, Object> l = loan(request);
        assertThat(l.get("LOAN_STATUS")).isEqualTo("FORECLOSED");
        assertThat((BigDecimal) l.get("PRINCIPAL_OUTSTANDING")).isEqualByComparingTo("0");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settlement_amount FROM loan_event WHERE loan_id = ? AND"
                                        + " event_type = 'FORECLOSURE'",
                                BigDecimal.class,
                                l.get("LOAN_ID")))
                .isEqualByComparingTo("10000");
        // SRS-12-10 (US-GAP-30): the member is told the recovery has ended.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification WHERE recipient_employee_id = ?"
                                        + " AND notification_type = 'LOAN_INSTALMENT' AND channel ="
                                        + " 'IN_APP' AND related_entity_key = ? AND message LIKE"
                                        + " '%FORECLOSURE%'",
                                Integer.class, employee, l.get("LOAN_NUMBER")))
                .isEqualTo(1);
    }

    @Test
    void SRS_12_17_aRecordThatCouldNotBeAppliedIsRefusedAtIntake() {
        long employee = member(13);
        for (String[] bad :
                new String[][] {
                    {"LOAN_SANCTION", "not json"},
                    {
                        "LOAN_SANCTION",
                        "{\"loanType\":\"NO_SUCH\",\"sanctioned\":1,\"principalInstalments\":1,\"instalment\":1,\"startYymm\":\""
                                + next()
                                + "\"}"
                    },
                    {
                        "LOAN_SANCTION",
                        "{\"loanType\":\"PLI_ADVANCE\",\"sanctioned\":10000,\"principalInstalments\":3,\"instalment\":1000,\"startYymm\":\""
                                + next()
                                + "\"}"
                    },
                    {"LOAN_FORECLOSURE", "{\"loanReference\":\"NO-SUCH-LOAN\"}"},
                    {
                        "LOAN_RESCHEDULE",
                        "{\"loanReference\":\"NO-SUCH-LOAN\",\"principalInstalments\":2,\"instalment\":1,\"startYymm\":\""
                                + next()
                                + "\"}"
                    }
                }) {
            assertThatThrownBy(
                            () -> received(employee, bad[0], bad[1], "IT-BAD-" + System.nanoTime()))
                    .as(bad[0] + " " + bad[1])
                    .isInstanceOf(FciException.class);
        }
    }

    /**
     * US-GAP-201 (SRS-12-17, SRS-12-18, use case l.2688): an I-21 loan task goes to the Checker
     * only with its order details and the order copy; without either it stays with the Maker.
     */
    @Test
    void SRS_12_18_anHrmsLoanTaskNeedsTheOrderDetailsAndTheCopyBeforeTheChecker() throws Exception {
        HrmsChanges.Received task =
                received(
                        member(24),
                        "LOAN_SANCTION",
                        "{\"loanType\":\"PLI_ADVANCE\",\"sanctioned\":8000,\"principalInstalments\":4,"
                            + "\"instalment\":2000,\"startYymm\":\""
                                + next()
                                + "\"}",
                        "IT-LN-ORD-" + System.nanoTime());
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        String path = "/v1/hrms-change-tasks/" + task.taskId() + "/submissions";
        Map<String, Object> order = new HashMap<>();
        order.put("orderDate", LocalDate.now(IST).minusDays(1).toString());
        order.put("issuingAuthority", "AGM (Pay), Delhi");
        order.put("heldInHrms", false);
        assertThat(
                        body(mvc.perform(userPost(maker, path, order)).andReturn())
                                .path("errorCode")
                                .asString())
                .as("no order number")
                .isEqualTo("HRC-422-ORDER");
        order.put("orderNo", "LOAN/2026/IT-ORD");
        tools.jackson.databind.JsonNode noCopy =
                body(mvc.perform(userPost(maker, path, order)).andReturn());
        assertThat(noCopy.path("errorCode").asString()).isEqualTo("HRC-422-ORDER");
        assertThat(noCopy.path("args").get(0).asString()).isEqualTo("COPY_MISSING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT task_status FROM hrms_change_task WHERE"
                                        + " hrms_change_task_id = ?",
                                String.class,
                                task.taskId()))
                .isEqualTo("PENDING_ORDER_DETAILS");
        attachTaskOrder(maker, task.taskId());
        assertThat(mvc.perform(userPost(maker, path, order)).andReturn().getResponse().getStatus())
                .isEqualTo(202);
    }

    /**
     * US-GAP-199 (use case precondition l.2684): an HRMS loan sanction for a member who is not an
     * active, payable member (separated) is refused at intake; no task opens.
     */
    @Test
    void SRS_12_17_anHrmsSanctionForASeparatedMemberIsRefusedAtIntake() {
        long employee = member(25);
        String request = "IT-LN-SEP-" + System.nanoTime();
        jdbc.update(
                "UPDATE employee SET employment_status = 'SEPARATED' WHERE employee_id = ?",
                employee);
        try {
            assertThatThrownBy(
                            () ->
                                    received(
                                            employee,
                                            "LOAN_SANCTION",
                                            "{\"loanType\":\"PLI_ADVANCE\",\"sanctioned\":8000,"
                                                + "\"principalInstalments\":4,\"instalment\":2000,"
                                                + "\"startYymm\":\""
                                                    + next()
                                                    + "\"}",
                                            request))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> assertThat(e.getMessageArgs()).containsExactly("NOT_PAYABLE"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM hrms_change_task WHERE hrms_request_id ="
                                            + " ?",
                                    Long.class,
                                    request))
                    .isZero();
        } finally {
            jdbc.update(
                    "UPDATE employee SET employment_status = 'IN_SERVICE' WHERE employee_id = ?",
                    employee);
        }
    }
}
