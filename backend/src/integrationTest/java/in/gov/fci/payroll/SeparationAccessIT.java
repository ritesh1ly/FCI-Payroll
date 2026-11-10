package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.employee.api.HrmsChanges;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SRS-G-36 (EmployeeAccessEffects), SRS-G-53: an approved separation (from HRMS, I-21) deactivates
 * the member's login, revokes every access assignment and ends the open sessions, in the approval's
 * transaction and audited. The member (109613, login rbac.group.ended) is used by no other
 * integration test.
 */
class SeparationAccessIT extends ApiTestSupport {

    private static final String EIS = "109613";
    private static final String LOGIN = "rbac.group.ended@fci-demo.test";

    @Autowired private HrmsChanges intake;

    @Test
    void SRS_G_36_anApprovedSeparationDeactivatesTheLoginAndRevokesItsAccess() throws Exception {
        long userId =
                jdbc.queryForObject(
                        "SELECT user_id FROM app_user WHERE login_id = ?", Long.class, LOGIN);
        long employeeId =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, EIS);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        // SRS-1-63 (plan 16 D16-33): a separation arrives from HRMS through I-21, never keyed on
        // I-17.
        HrmsChanges.Received task =
                intake.receive(
                        new HrmsChanges.Change(
                                employeeId,
                                "SEPARATION",
                                "SUPERANNUATION",
                                today,
                                "IT-SEP-" + System.nanoTime(),
                                HrmsChanges.Source.HRMS_PUSH));
        Map<String, Object> order = new HashMap<>();
        order.put("orderNo", "EST/SEP/IT");
        order.put("orderDate", today.minusDays(1).toString());
        order.put("issuingAuthority", "Deputy General Manager (P), Ludhiana");
        order.put("heldInHrms", false);
        Session maker = login("tax.maker.ludhiana@fci-demo.test", "PAYROLL_MAKER", "LD001");
        attachTaskOrder(maker, task.taskId());
        MvcResult recorded =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/hrms-change-tasks/" + task.taskId() + "/submissions",
                                        order))
                        .andReturn();
        assertThat(recorded.getResponse().getStatus())
                .as(recorded.getResponse().getContentAsString())
                .isEqualTo(202);
        // Nothing changes before the checker decides (maker-checker).
        assertThat(status(userId)).isNotEqualTo("DEACTIVATED");

        Session checker = login("rbac.checker.ludhiana@fci-demo.test", "PAYROLL_CHECKER", "LD001");
        assertThat(
                        decide(
                                checker,
                                body(recorded).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);

        assertThat(status(userId)).isEqualTo("DEACTIVATED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM app_user_access_group WHERE user_id = ? AND"
                                        + " (effective_to IS NULL OR effective_to >= CURRENT_DATE)"
                                        + " AND revoked_at IS NULL",
                                Integer.class,
                                userId))
                .as("no access left in force")
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM app_session WHERE user_id = ? AND ended_at IS"
                                        + " NULL",
                                Integer.class,
                                userId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name = 'app_user'"
                                        + " AND entity_key = ? AND module_code = 'I-21'",
                                Integer.class,
                                String.valueOf(userId)))
                .isPositive();
        // SRS-G-53 (US-GAP-112): the separated member's record stays readable by the last unit
        // and by its parent offices (here Headquarters); another unit does not see it.
        // The body is the separated member's own record, not an empty 200 (TEST_SCOPE US-GAP-112).
        assertSeparatedRecord(maker, "the last unit");
        Session hq = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        assertSeparatedRecord(hq, "a parent office");
        Session other = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        assertThat(
                        mvc.perform(userGet(other, "/v1/employees/" + EIS))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("another unit")
                .isIn(403, 404);
    }

    /** SRS-G-53: the record read is the member's, marked separated by the approved I-21 task. */
    private void assertSeparatedRecord(Session reader, String who) throws Exception {
        MvcResult read = mvc.perform(userGet(reader, "/v1/employees/" + EIS)).andReturn();
        assertThat(read.getResponse().getStatus()).as(who).isEqualTo(200);
        assertThat(body(read).get("eis").asString()).as(who).isEqualTo(EIS);
        assertThat(body(read).get("employmentStatus").asString()).as(who).isEqualTo("SEPARATED");
    }

    private String status(long userId) {
        return jdbc.queryForObject(
                "SELECT user_status FROM app_user WHERE user_id = ?", String.class, userId);
    }
}
