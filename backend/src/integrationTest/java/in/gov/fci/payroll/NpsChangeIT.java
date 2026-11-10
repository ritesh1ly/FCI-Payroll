package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.nps.api.NpsReceipts;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * US-GAP-291 (SRS-9-23, SRS-G-45, SRS-G-29, SRS-G-1; OD-55): a later NPS record of a month still
 * open that changes what the month pays applies only through its I-21 task: the month keeps its
 * record until the Payroll Checker approves, then the record takes the HRMS values, linked to the
 * task and audited with the approval; the Payroll Maker who sent the task never approves it. A
 * Delhi member (the DH001 desk decides), months sixteen years ahead that no other test uses.
 */
class NpsChangeIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private NpsReceipts receipts;

    private long employeeId;
    private String eis;

    @BeforeEach
    void member() {
        Map<String, Object> m =
                jdbc.queryForMap(
                        "SELECT employee_id, emp_num FROM employee WHERE location_id = (SELECT"
                                + " location_id FROM org_location WHERE location_code = 'DH001')"
                                + " AND employment_status = 'IN_SERVICE' AND record_status ="
                                + " 'ACTIVE' ORDER BY emp_num OFFSET 11 ROWS FETCH NEXT 1 ROWS"
                                + " ONLY");
        employeeId = ((Number) m.get("EMPLOYEE_ID")).longValue();
        eis = (String) m.get("EMP_NUM");
    }

    private static String month(int ahead) {
        YearMonth m = YearMonth.now(IST).plusYears(16).plusMonths(ahead);
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private NpsReceipts.Received receive(String yymm, String employee, String employer) {
        return receipts.receive(
                "it.hrms",
                new NpsReceipts.Receipt(
                        eis,
                        yymm,
                        true,
                        "110012345678",
                        new BigDecimal(employee),
                        new BigDecimal(employer),
                        null));
    }

    private Map<String, Object> row(String yymm) {
        return jdbc.queryForMap(
                "SELECT nps_deduction_id, employee_amount, employer_amount, hrms_change_task_id"
                        + " FROM nps_deduction WHERE employee_id = ? AND payroll_yymm = ? AND"
                        + " record_type = 'MONTHLY'",
                employeeId,
                yymm);
    }

    @Test
    void SRS_9_23_aChangeWithPayEffectOfAnOpenMonthAppliesOnlyOnceItsI21TaskIsApproved()
            throws Exception {
        String yymm = month(1);
        receive(yymm, "5000", "7000");
        NpsReceipts.Received changed = receive(yymm, "5400", "7560");
        assertThat(changed.heldForTask()).isNotBlank();
        assertThat((BigDecimal) row(yymm).get("EMPLOYEE_AMOUNT"))
                .as("the month keeps its record until the Checker approves")
                .isEqualByComparingTo("5000");
        long taskId =
                jdbc.queryForObject(
                        "SELECT hrms_change_task_id FROM hrms_change_task WHERE task_no = ?",
                        Long.class,
                        changed.heldForTask());

        // SRS-G-1: the sender holds the Checker role too (the dual-role demo user of DH001).
        Session maker = login("maker.checker@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult detail =
                mvc.perform(userGet(maker, "/v1/hrms-change-tasks/" + taskId)).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(detail).path("effect").asString())
                .as("the Checker sees what approving would do")
                .contains("employee 5000 -> 5400", "employer 7000 -> 7560");
        MvcResult submitted =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/hrms-change-tasks/" + taskId + "/submissions",
                                        Map.of("heldInHrms", true)))
                        .andReturn();
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString())
                .isLessThan(300);
        long approval = body(submitted).get("approvalId").asLong();
        // SRS-G-29: the task is listed only in the inbox of a Checker whose scope covers DH001;
        // a Checker of another office neither sees it nor decides it.
        Session chennai = login("ddo.chennai@fci-demo.test", "PAYROLL_CHECKER", "CH001");
        assertThat(all(chennai, "/v1/inbox").findValuesAsString("approvalId"))
                .doesNotContain(String.valueOf(approval));
        assertThat(
                        all(
                                        login(
                                                "ddo.delhi@fci-demo.test",
                                                "PAYROLL_CHECKER",
                                                "DH001"),
                                        "/v1/inbox")
                                .findValuesAsString("approvalId"))
                .contains(String.valueOf(approval));
        MvcResult outside =
                mvc.perform(
                                userPost(
                                        chennai,
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(outside.getResponse().getStatus()).isEqualTo(403);
        assertThat((BigDecimal) row(yymm).get("EMPLOYEE_AMOUNT")).isEqualByComparingTo("5000");

        // R-MC / SRS-G-1: the maker never approves the own item, not even under the Checker role.
        MvcResult own =
                mvc.perform(
                                userPost(
                                        login(
                                                "maker.checker@fci-demo.test",
                                                "PAYROLL_CHECKER",
                                                "DH001"),
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(own.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(own).path("errorCode").asString()).isEqualTo("WF-403-SOD");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .as("the item still waits for another Checker")
                .isEqualTo("PENDING_APPROVAL");
        assertThat((BigDecimal) row(yymm).get("EMPLOYEE_AMOUNT")).isEqualByComparingTo("5000");

        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        Map<String, Object> applied = row(yymm);
        assertThat((BigDecimal) applied.get("EMPLOYEE_AMOUNT")).isEqualByComparingTo("5400");
        assertThat((BigDecimal) applied.get("EMPLOYER_AMOUNT")).isEqualByComparingTo("7560");
        assertThat(((Number) applied.get("HRMS_CHANGE_TASK_ID")).longValue()).isEqualTo(taskId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'nps_deduction' AND entity_key = ? AND field_name ="
                                        + " 'employee_amount' AND old_value = '5000' AND new_value"
                                        + " = '5400' AND approval_id = ?",
                                Long.class,
                                eis + "|" + yymm,
                                approval))
                .as("R-AUD: before and after, with the approval")
                .isEqualTo(1L);
    }
}
