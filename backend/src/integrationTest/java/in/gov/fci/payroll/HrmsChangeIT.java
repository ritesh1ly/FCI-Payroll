package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.employee.api.HrmsChanges;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * I-21 HRMS Change Approval (plan 16 phase 16a; SRS-1-19, SRS-1-21 to 23, SRS-1-28, SRS-1-29,
 * SRS-1-58, SRS-1-61, SRS-1-69): a change enters only through the intake port, idempotently; a
 * field without pay effect is applied at once; the Maker records the order and cannot change the
 * value; the Checker approves (OTP only for Basic Pay), rejects (old value kept) or returns; a DOB
 * whose new retirement date is past also needs HQ Establishment; a field without a Payroll column
 * is not applicable; a pending task received before the freeze counts against the lock. Employees
 * are Delhi HQ (DH001) members past the demo tasks' rows.
 */
@org.junit.jupiter.api.parallel.ResourceLock(ApiTestSupport.AGM_NORTH)
class HrmsChangeIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private HrmsChanges intake;
    @Autowired private in.gov.fci.payroll.increment.orchestration.Scales scales;
    @Autowired private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named;
    @Autowired private in.gov.fci.payroll.configuration.api.PayConfiguration configuration;

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private long employee(int offset) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                        + " 'ACTIVE' ORDER BY emp_num OFFSET "
                        + offset
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"));
    }

    private HrmsChanges.Received receive(
            long employeeId, String field, String value, String request) {
        return intake.receive(
                new HrmsChanges.Change(
                        employeeId,
                        field,
                        value,
                        LocalDate.now(IST),
                        request,
                        HrmsChanges.Source.HRMS_PUSH));
    }

    private MvcResult submit(Session s, long taskId, Map<String, Object> body) throws Exception {
        if (Boolean.FALSE.equals(body.get("heldInHrms"))) {
            // An individual order: its copy is attached first (SRS-G-31); a refusal shows below.
            attachTaskOrder(s, taskId);
        }
        return mvc.perform(userPost(s, "/v1/hrms-change-tasks/" + taskId + "/submissions", body))
                .andReturn();
    }

    private static Map<String, Object> order() {
        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", "EST/2026/114");
        body.put("orderDate", LocalDate.now(IST).minusDays(3).toString());
        body.put("issuingAuthority", "General Manager (P), Delhi");
        body.put("heldInHrms", false);
        return body;
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private String status(long taskId) {
        return jdbc.queryForObject(
                "SELECT task_status FROM hrms_change_task WHERE hrms_change_task_id = ?",
                String.class,
                taskId);
    }

    /**
     * A real-format PAN for the member: the 5th character is the surname's initial
     * (SeedDataFormatIT).
     */
    private String pan(long employeeId, String head, String tail) {
        String last =
                jdbc.queryForObject(
                        "SELECT COALESCE(last_name, first_name) FROM employee WHERE employee_id ="
                                + " ?",
                        String.class,
                        employeeId);
        return head + Character.toUpperCase(last.charAt(0)) + tail;
    }

    private static String unique() {
        return "IT-" + System.nanoTime();
    }

    /**
     * SRS-1-28 / SRS-G-31 (US-GAP-5): a task with an individual order is not sent without its copy
     * (the block is audited); the copy is attached, listed and viewed by the desk, never by another
     * office; after submission a copy can still be added.
     */
    @Test
    void SRS_G_31_anIndividualOrderIsSentWithItsCopyWhichOnlyTheDeskSees() throws Exception {
        long member = employee(15);
        HrmsChanges.Received task = receive(member, "PAN", pan(member, "ASDF", "1234K"), unique());
        Session maker = maker();
        MvcResult bare =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/hrms-change-tasks/" + task.taskId() + "/submissions",
                                        order()))
                        .andReturn();
        assertThat(code(bare)).isEqualTo("HRC-422-ORDER");
        assertThat(body(bare).get("args").get(0).asString()).isEqualTo("COPY_MISSING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_key = ? AND"
                                        + " field_name = 'SUBMISSION_BLOCKED'",
                                Integer.class,
                                task.taskNo()))
                .isEqualTo(1);
        // Another office's desk can neither attach nor see it.
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(attachTaskOrder(kolkata, task.taskId())).isIn(403, 404);
        assertThat(attachTaskOrder(maker, task.taskId())).isEqualTo(201);
        JsonNode copies =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/hrms-change-tasks/"
                                                        + task.taskId()
                                                        + "/documents"))
                                .andReturn());
        assertThat(copies).hasSize(1);
        long fileId = copies.get(0).get("id").asLong();
        MvcResult view =
                mvc.perform(
                                userGet(
                                        maker,
                                        "/v1/hrms-change-tasks/"
                                                + task.taskId()
                                                + "/documents/"
                                                + fileId))
                        .andReturn();
        assertThat(view.getResponse().getStatus()).isEqualTo(200);
        assertThat(view.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(
                        mvc.perform(
                                        userGet(
                                                kolkata,
                                                "/v1/hrms-change-tasks/"
                                                        + task.taskId()
                                                        + "/documents/"
                                                        + fileId))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        MvcResult sent =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/hrms-change-tasks/" + task.taskId() + "/submissions",
                                        order()))
                        .andReturn();
        assertThat(sent.getResponse().getStatus()).isEqualTo(202);
        // SRS-G-31: after submission, files can only be added.
        assertThat(attachTaskOrder(maker, task.taskId())).isEqualTo(201);
    }

    /**
     * SRS-1-28 / SRS-1-29: a PAN change is recorded once (same request again returns it), shown
     * masked, submitted with its order and approved by the Checker; a UAN change rejected keeps the
     * old value; a returned task goes back to the Maker and is resubmitted.
     */
    // SRS-16-1: an HRMS-sourced field changes only through its I-21 task, never by typing.
    @Test
    // SRS-INT-24 / BR-INT-015: a pay-effect change from HRMS opens an I-21 task and is not applied
    // directly; SC-23-0049 / SRS-INT-18: the HRMS value is never typed in Payroll (SRS-G-45).
    void
            SRS_1_28_SRS_DM_16_SRS_INT_24_BR_INT_015_aChangeIsApprovedRejectedOrReturnedAndTheValueIsNeverTyped()
                    throws Exception {
        long member = employee(10);
        String request = unique();
        String value = pan(member, "QWEP", "6789Z");
        HrmsChanges.Received pan = receive(member, "PAN", value, request);
        assertThat(pan.status()).isEqualTo("PENDING_ORDER_DETAILS");
        assertThat(pan.taskNo()).startsWith("HCA-");
        assertThat(receive(member, "PAN", value, request).duplicate()).isTrue();

        Session maker = maker();
        JsonNode detail =
                body(
                        mvc.perform(userGet(maker, "/v1/hrms-change-tasks/" + pan.taskId()))
                                .andReturn());
        assertThat(detail.get("task").get("newValue").asString())
                .isEqualTo("******" + value.substring(6));
        assertThat(detail.get("applicable").asBoolean()).isTrue();
        Map<String, Object> missing = order();
        missing.remove("orderNo");
        assertThat(code(submit(maker, pan.taskId(), missing))).isEqualTo("HRC-422-ORDER");
        MvcResult sent = submit(maker, pan.taskId(), order());
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(sent).get("approvalId").asLong();
        assertThat(status(pan.taskId())).isEqualTo("PENDING_CHECKER");
        assertThat(code(submit(maker, pan.taskId(), order()))).isEqualTo("HRC-409-STATE");
        assertThat(decide(maker, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(status(pan.taskId())).isEqualTo("APPROVED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pan_number FROM employee WHERE employee_id = ?",
                                String.class,
                                member))
                .isEqualTo(value);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT checker_user FROM hrms_change_task WHERE"
                                        + " hrms_change_task_id = ?",
                                String.class,
                                pan.taskId()))
                .isEqualTo("ddo.delhi@fci-demo.test");

        String uanBefore =
                jdbc.queryForObject(
                        "SELECT uan_number FROM employee WHERE employee_id = ?",
                        String.class,
                        member);
        HrmsChanges.Received uan = receive(member, "UAN", "100999888777", unique());
        long rejected = body(submit(maker, uan.taskId(), order())).get("approvalId").asLong();
        assertThat(decide(checker(), rejected, "REJECT", "Order does not match HRMS", null))
                .isEqualTo(200);
        assertThat(status(uan.taskId())).isEqualTo("REJECTED_RETURNED_TO_HRMS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT uan_number FROM employee WHERE employee_id = ?",
                                String.class,
                                member))
                .isEqualTo(uanBefore);

        HrmsChanges.Received eps = receive(member, "EPS_NUMBER", "EPS-DL-77001", unique());
        Map<String, Object> held = new HashMap<>();
        held.put("heldInHrms", true);
        long returned = body(submit(maker, eps.taskId(), held)).get("approvalId").asLong();
        assertThat(decide(checker(), returned, "RETURN", "Attach the order", null)).isEqualTo(200);
        assertThat(status(eps.taskId())).isEqualTo("RETURNED_TO_MAKER");
        assertThat(submit(maker, eps.taskId(), order()).getResponse().getStatus()).isEqualTo(202);
        assertThat(status(eps.taskId())).isEqualTo("PENDING_CHECKER");
    }

    /**
     * SRS-1-28 with the I-21 Effective Date row (l.3855: "never overwrites history"; US-GAP-282): a
     * second HRMS change to the same field while the first waits - and one effective earlier -
     * opens its own task with its own request, value and effective date; the first is left as it
     * was, and each is decided on its own. Which value stands when the earlier-dated one is
     * approved after the later one is FCI's to say (CLR-50).
     */
    @Test
    void SRS_1_28_aSecondChangeToTheSameFieldOpensItsOwnTaskAndOverwritesNothing()
            throws Exception {
        long member = employee(16);
        LocalDate today = LocalDate.now(IST);
        HrmsChanges.Received first =
                intake.receive(
                        new HrmsChanges.Change(
                                member,
                                "EPS_NUMBER",
                                "EPS-DL-77101",
                                today,
                                unique(),
                                HrmsChanges.Source.HRMS_PUSH));
        HrmsChanges.Received earlier =
                intake.receive(
                        new HrmsChanges.Change(
                                member,
                                "EPS_NUMBER",
                                "EPS-DL-77102",
                                today.minusDays(40),
                                unique(),
                                HrmsChanges.Source.HRMS_PUSH));
        assertThat(earlier.duplicate()).isFalse();
        assertThat(earlier.taskId()).isNotEqualTo(first.taskId());
        assertThat(earlier.taskNo()).isNotEqualTo(first.taskNo());
        Map<String, Object> a =
                jdbc.queryForMap(
                        "SELECT new_value, effective_date, task_status FROM hrms_change_task WHERE"
                                + " hrms_change_task_id = ?",
                        first.taskId());
        assertThat(a.get("NEW_VALUE")).isEqualTo("EPS-DL-77101");
        assertThat(ColumnDates.of(a.get("EFFECTIVE_DATE"))).isEqualTo(today);
        assertThat(a.get("TASK_STATUS")).isEqualTo("PENDING_ORDER_DETAILS");
        assertThat(status(earlier.taskId())).isEqualTo("PENDING_ORDER_DETAILS");

        // Each is decided on its own: the first approved, the earlier-dated one still waits.
        Map<String, Object> held = new HashMap<>();
        held.put("heldInHrms", true);
        long approval = body(submit(maker(), first.taskId(), held)).get("approvalId").asLong();
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(status(first.taskId())).isEqualTo("APPROVED");
        assertThat(status(earlier.taskId())).isEqualTo("PENDING_ORDER_DETAILS");
        Map<String, Object> b =
                jdbc.queryForMap(
                        "SELECT new_value, effective_date FROM hrms_change_task WHERE"
                                + " hrms_change_task_id = ?",
                        earlier.taskId());
        assertThat(b.get("NEW_VALUE")).isEqualTo("EPS-DL-77102");
        assertThat(ColumnDates.of(b.get("EFFECTIVE_DATE"))).isEqualTo(today.minusDays(40));
        // The task detail shows the earlier change's own pay impact (US-GAP-276).
        JsonNode detail =
                body(
                        mvc.perform(userGet(maker(), "/v1/hrms-change-tasks/" + earlier.taskId()))
                                .andReturn());
        assertThat(detail.path("impact").path("appliesFrom").asString())
                .isEqualTo(java.time.YearMonth.from(today.minusDays(40)).toString());
    }

    /**
     * SRS-1-61 / SRS-1-19: a Basic Pay change is approved with the OTP; a gender change is applied
     * at once without a task to decide; a field with no Payroll column cannot be submitted; Finance
     * has no Maker right.
     */
    // SC-16-0019 (name and DOB never typed in Payroll; a name change from HRMS applies at once),
    // SC-16-0157 (no task for a field without pay effect), SC-16-0077 (Basic Pay set only through
    // an approved I-21 task).
    @Test
    void SRS_1_61_basicPayNeedsTheOtpAndAFieldWithoutPayEffectAppliesAtOnce() throws Exception {
        long member = employee(11);
        HrmsChanges.Received basic = receive(member, "BASIC_PAY", "61200", unique());
        Session maker = maker();
        long approval = body(submit(maker, basic.taskId(), order())).get("approvalId").asLong();
        assertThat(decide(checker(), approval, "APPROVE", null, "INCREMENT_DATE_BASIC_CORRECTION"))
                .isEqualTo(200);
        assertThat(status(basic.taskId())).isEqualTo("APPROVED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_basic FROM employee WHERE employee_id = ?",
                                BigDecimal.class,
                                member))
                .isEqualByComparingTo("61200");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT otp_challenge_id FROM hrms_change_task WHERE"
                                        + " hrms_change_task_id = ?",
                                Long.class,
                                basic.taskId()))
                .isNotNull();

        HrmsChanges.Received gender = receive(member, "GENDER", "F", unique());
        assertThat(gender.status()).isEqualTo("AUTO_APPLIED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT gender FROM employee WHERE employee_id = ?",
                                String.class,
                                member))
                .isEqualTo("F");

        HrmsChanges.Received category = receive(member, "CATEGORY", "OBC", unique());
        assertThat(code(submit(maker, category.taskId(), order())))
                .isEqualTo("HRC-422-NOT-APPLICABLE");
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        assertThat(submit(finance, category.taskId(), order()).getResponse().getStatus())
                .isEqualTo(403);
        JsonNode list =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/hrms-change-tasks?status=PENDING_ORDER_DETAILS&size=100"))
                                .andReturn());
        assertThat(list.toString()).contains(category.taskNo());
    }

    /**
     * UC I-21 verify conditions (SC-16-0164, SC-16-0165; SRS-1-28, SRS-1-61, SRS-G-45): an approved
     * Basic Pay change applies from its effective date to pay - the staff engine reads the new
     * Basic for the effective month and computes the Basic line on it - and the audit trail and the
     * HRMS Change Register hold the old and new values, the HRMS Request ID, the effective date,
     * the Maker, the Checker and the approval. Bank advice and Form 16 wait for modules 15 / 23
     * (PENDING 05h-release).
     */
    @Test
    void SC_16_0164_anApprovedI21ValueAppliesFromItsEffectiveDateToPayAndIsRegisteredWithItsTrail()
            throws Exception {
        long member = employee(17);
        BigDecimal before =
                jdbc.queryForObject(
                        "SELECT current_basic FROM employee WHERE employee_id = ?",
                        BigDecimal.class,
                        member);
        // SRS-13-23: the Basic HRMS sends is a stage of the member's scale (the next one up).
        BigDecimal after =
                scales.ofMember(member, LocalDate.now(IST))
                        .flatMap(
                                s ->
                                        s.stages().stream()
                                                .filter(stage -> stage.compareTo(before) > 0)
                                                .findFirst())
                        .orElse(before.add(new BigDecimal("1300")));
        String request = unique();
        HrmsChanges.Received basic = receive(member, "BASIC_PAY", after.toPlainString(), request);
        Session maker = maker();
        long approval = body(submit(maker, basic.taskId(), order())).get("approvalId").asLong();
        java.time.LocalDate start = java.time.YearMonth.now(IST).atDay(1);
        assertThat(
                        in.gov.fci.payroll.payroll.batch.computation.MemberReader.member(
                                        named, member, start)
                                .orElseThrow()
                                .basic())
                .as("nothing applies before the approval")
                .isEqualByComparingTo(before);
        assertThat(decide(checker(), approval, "APPROVE", null, "INCREMENT_DATE_BASIC_CORRECTION"))
                .isEqualTo(200);

        // To pay, from the effective month: the engine's input and its Basic line.
        var row =
                in.gov.fci.payroll.payroll.batch.computation.MemberReader.member(
                                named, member, start)
                        .orElseThrow();
        assertThat(row.basic()).isEqualByComparingTo(after);
        var outcome =
                in.gov.fci.payroll.payroll.business.PayRules.compute(
                        row.member(), configuration.staffConfiguration(start));
        assertThat(
                        outcome.lines().stream()
                                .filter(
                                        l ->
                                                l.payCode()
                                                        == in.gov.fci.payroll.payroll.business
                                                                .PayRules.BASIC)
                                .findFirst()
                                .orElseThrow()
                                .amount())
                .isEqualByComparingTo(after);

        // The audit trail: old and new value with the approval (the Basic Pay history keys the
        // member by id, BasicPayService).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name = 'employee'"
                                        + " AND entity_key = ? AND field_name = 'current_basic'"
                                        + " AND new_value = ? AND approval_id = ?",
                                Integer.class,
                                String.valueOf(member),
                                after.toPlainString(),
                                approval))
                .isEqualTo(1);
        // The HRMS Change Register: the task with both values, the request, the date and the desk.
        JsonNode task =
                body(mvc.perform(userGet(maker, "/v1/hrms-change-tasks/" + basic.taskId()))
                                .andReturn())
                        .path("task");
        assertThat(task.path("status").asString()).isEqualTo("APPROVED");
        assertThat(new BigDecimal(task.path("currentValue").asString()))
                .isEqualByComparingTo(before);
        assertThat(task.path("newValue").asString()).isEqualTo(after.toPlainString());
        assertThat(task.path("hrmsRequestId").asString()).isEqualTo(request);
        assertThat(task.path("effectiveDate").asString()).isEqualTo(LocalDate.now(IST).toString());
        assertThat(task.path("makerUser").asString()).isEqualTo("maker.delhi@fci-demo.test");
        assertThat(task.path("checkerUser").asString()).isEqualTo("ddo.delhi@fci-demo.test");
        assertThat(task.path("approvalId").asLong()).isEqualTo(approval);
    }

    /**
     * SRS-1-29 / SRS-1-23 (BMAD TR-040; UC-04 E1): a rejected DOB change leaves the birth date, the
     * retirement date and every age-based date unchanged - rejected by HQ Establishment after the
     * Checker approved it (nothing applied at the Checker's level), and rejected by the Checker
     * itself. Each task is returned to HRMS.
     */
    @Test
    void SRS_1_29_aRejectedDobChangeLeavesTheDatesUnchangedAtEitherLevel() throws Exception {
        Session maker = maker();
        Session hq = login("hr.hq@fci-demo.test", "ESTABLISHMENT_HR", "HQ001");
        for (boolean atHq : new boolean[] {true, false}) {
            long member = employee(atHq ? 18 : 19);
            Map<String, Object> before =
                    jdbc.queryForMap(
                            "SELECT birth_date, retirement_date, eps_join_date FROM employee WHERE"
                                    + " employee_id = ?",
                            member);
            HrmsChanges.Received dob = receive(member, "DATE_OF_BIRTH", "1962-05-20", unique());
            long approval = body(submit(maker, dob.taskId(), order())).get("approvalId").asLong();
            if (atHq) {
                assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
                assertThat(status(dob.taskId())).isEqualTo("PENDING_CHECKER");
                assertThat(
                                jdbc.queryForMap(
                                        "SELECT birth_date, retirement_date, eps_join_date FROM"
                                                + " employee WHERE employee_id = ?",
                                        member))
                        .as("nothing applied at the Checker's level")
                        .isEqualTo(before);
                assertThat(decide(hq, approval, "REJECT", "Retirement already settled (IT)", null))
                        .isEqualTo(200);
            } else {
                assertThat(
                                decide(
                                        checker(),
                                        approval,
                                        "REJECT",
                                        "Birth certificate not matching (IT)",
                                        null))
                        .isEqualTo(200);
            }
            assertThat(status(dob.taskId())).isEqualTo("REJECTED_RETURNED_TO_HRMS");
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT birth_date, retirement_date, eps_join_date FROM"
                                            + " employee WHERE employee_id = ?",
                                    member))
                    .as(atHq ? "rejected by HQ Establishment" : "rejected by the Checker")
                    .isEqualTo(before);
        }
    }

    /**
     * SRS-1-30 / SRS-1-71 (UI audit S10): the I-21 register of a zone office shows the tasks of
     * every paying unit below it (a Delhi task reaches the North Zone checker), not only those paid
     * at its own unit; the Delhi desk still sees it, and another zone does not.
     */
    @Test
    void SRS_1_30_aZoneSeesTheTasksOfTheUnitsBelowIt() throws Exception {
        HrmsChanges.Received task = receive(employee(15), "CATEGORY", "SC", unique());
        String path = "/v1/hrms-change-tasks?size=100&status=PENDING_ORDER_DETAILS";
        Session zone = login("agm.north@fci-demo.test", "PAYROLL_CHECKER", "NA11");
        assertThat(body(mvc.perform(userGet(zone, path)).andReturn()).toString())
                .contains(task.taskNo());
        assertThat(body(mvc.perform(userGet(maker(), path)).andReturn()).toString())
                .contains(task.taskNo());
        Session west = login("agm.west@fci-demo.test", "PAYROLL_CHECKER", "ZO-W");
        assertThat(body(mvc.perform(userGet(west, path)).andReturn()).toString())
                .doesNotContain(task.taskNo());
    }

    /**
     * SRS-1-9 / BR-EMPM-003 (US-16-03 AC1): PAN is unique across the employees. An I-21 PAN change
     * to a PAN another employee holds is refused with the reason when the Maker sends it, and again
     * at the Checker's approval when another employee has taken the PAN meanwhile; the member's PAN
     * is unchanged.
     */
    @Test
    void SRS_1_9_SRS_DM_2_aPanChangeToAPanAlreadyHeldIsRefusedWithTheReason() throws Exception {
        long member = employee(13);
        long other = employee(14);
        String held =
                jdbc.queryForObject(
                        "SELECT pan_number FROM employee WHERE employee_id = ?",
                        String.class,
                        other);
        String before =
                jdbc.queryForObject(
                        "SELECT pan_number FROM employee WHERE employee_id = ?",
                        String.class,
                        member);
        Session maker = maker();
        HrmsChanges.Received taken = receive(member, "PAN", held, unique());
        MvcResult refused = submit(maker, taken.taskId(), order());
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(refused)).isEqualTo("HRC-422-PAN-HELD");
        assertThat(status(taken.taskId())).isEqualTo("PENDING_ORDER_DETAILS");

        String free = pan(member, "ZXCP", "4321Y");
        HrmsChanges.Received change = receive(member, "PAN", free, unique());
        long approval = body(submit(maker, change.taskId(), order())).get("approvalId").asLong();
        String othersOwn =
                jdbc.queryForObject(
                        "SELECT pan_number FROM employee WHERE employee_id = ?",
                        String.class,
                        other);
        jdbc.update("UPDATE employee SET pan_number = ? WHERE employee_id = ?", free, other);
        try {
            MvcResult late =
                    mvc.perform(
                                    userPost(
                                            checker(),
                                            "/v1/approvals/" + approval + "/decision",
                                            Map.of("decision", "APPROVE")))
                            .andReturn();
            assertThat(late.getResponse().getStatus()).isEqualTo(422);
            assertThat(code(late)).isEqualTo("HRC-422-PAN-HELD");
            assertThat(status(change.taskId())).isEqualTo("PENDING_CHECKER");
        } finally {
            jdbc.update(
                    "UPDATE employee SET pan_number = ? WHERE employee_id = ?", othersOwn, other);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pan_number FROM employee WHERE employee_id = ?",
                                String.class,
                                member))
                .isEqualTo(before);
    }

    /**
     * SRS-1-61 / SRS-G-21 (US-16-14 AC6, AC10): a Basic Pay approval is not taken without the OTP
     * (the challenge is raised and the task stays pending), nor with a wrong code; the Payroll
     * Checker of another office neither sees nor decides the task (R-RBAC office scope).
     */
    @Test
    void SRS_1_61_anApprovalWithoutTheOtpOrFromAnotherOfficeIsRefused() throws Exception {
        long member = employee(12);
        // A stage of the member's scale other than the Basic held (SRS-13-23).
        BigDecimal stage =
                jdbc.queryForObject(
                        "SELECT MAX(s.stage_basic) FROM pay_scale_band_stage s JOIN"
                                + " pay_scale_band_version v ON v.band_version_id ="
                                + " s.band_version_id JOIN employee e ON e.band_id = v.band_id"
                                + " WHERE e.employee_id = ? AND v.effective_to IS NULL AND"
                                + " s.stage_basic <> COALESCE(e.current_basic, -1)",
                        BigDecimal.class,
                        member);
        String newBasic = stage == null ? "61200" : stage.toPlainString();
        HrmsChanges.Received basic = receive(member, "BASIC_PAY", newBasic, unique());
        long approval = body(submit(maker(), basic.taskId(), order())).get("approvalId").asLong();
        Session checker = checker();
        MvcResult noOtp =
                mvc.perform(
                                userPost(
                                        checker,
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(noOtp.getResponse().getStatus())
                .as("SRS-G-21: the OTP is asked for, nothing is decided")
                .isEqualTo(428);
        long challenge = body(noOtp).get("args").get(0).asLong();
        MvcResult wrong =
                mvc.perform(
                                userPost(
                                        checker,
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of(
                                                "decision",
                                                "APPROVE",
                                                "otp",
                                                Map.of(
                                                        "challengeId",
                                                        challenge,
                                                        "code",
                                                        "000000"))))
                        .andReturn();
        assertThat(wrong.getResponse().getStatus()).as("a wrong code").isBetween(400, 499);
        assertThat(status(basic.taskId())).isNotEqualTo("APPROVED");

        Session kolkata = login("ddo.kolkata@fci-demo.test", "PAYROLL_CHECKER", "KL001");
        assertThat(
                        mvc.perform(userGet(kolkata, "/v1/hrms-change-tasks/" + basic.taskId()))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("another office does not see the task (out of scope)")
                .isEqualTo(403);
        assertThat(decide(kolkata, approval, "APPROVE", null, null))
                .as("nor decides it")
                .isEqualTo(403);
        assertThat(status(basic.taskId())).isNotEqualTo("APPROVED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_basic FROM employee WHERE employee_id = ?",
                                BigDecimal.class,
                                member))
                .as("the pay is unchanged")
                .isNotEqualByComparingTo(newBasic);

        assertThat(decide(checker(), approval, "APPROVE", null, "INCREMENT_DATE_BASIC_CORRECTION"))
                .as("the own office's Checker approves with the OTP")
                .isEqualTo(200);
        assertThat(status(basic.taskId())).isEqualTo("APPROVED");
    }

    /**
     * SRS-1-22 / SRS-1-23: a DOB change shows the recalculated retirement date and EPS cut-off; a
     * retirement date already past routes it to HQ Establishment after the Checker; approved, the
     * new DOB and retirement date apply together.
     */
    // SRS-1-60: the Payroll Checker approves a DOB task, with HQ Establishment where retirement is
    // past.
    @Test
    void SRS_1_23_aDobWithAPastRetirementNeedsHqEstablishmentAfterTheChecker() throws Exception {
        long member = employee(12);
        Map<String, Object> dates =
                jdbc.queryForMap(
                        "SELECT birth_date, retirement_date FROM employee WHERE employee_id = ?",
                        member);
        try {
            HrmsChanges.Received dob = receive(member, "DATE_OF_BIRTH", "1962-05-20", unique());
            Session maker = maker();
            JsonNode detail =
                    body(
                            mvc.perform(userGet(maker, "/v1/hrms-change-tasks/" + dob.taskId()))
                                    .andReturn());
            JsonNode preview = detail.get("dob");
            assertThat(preview.get("newRetirement").asString()).isEqualTo("2022-05-31");
            assertThat(preview.get("epsCutoff").asString()).isEqualTo("2020-05-20");
            assertThat(preview.get("retirementPast").asBoolean()).isTrue();
            long approval = body(submit(maker, dob.taskId(), order())).get("approvalId").asLong();
            assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
            assertThat(status(dob.taskId()))
                    .as("waits for HQ Establishment")
                    .isEqualTo("PENDING_CHECKER");
            MvcResult hq =
                    mvc.perform(
                                    userPost(
                                            login(
                                                    "hr.hq@fci-demo.test",
                                                    "ESTABLISHMENT_HR",
                                                    "HQ001"),
                                            "/v1/approvals/" + approval + "/decision",
                                            Map.of("decision", "APPROVE")))
                            .andReturn();
            assertThat(hq.getResponse().getStatus())
                    .as(hq.getResponse().getContentAsString())
                    .isEqualTo(200);
            assertThat(status(dob.taskId())).isEqualTo("APPROVED");
            Map<String, Object> row =
                    jdbc.queryForMap(
                            "SELECT birth_date, retirement_date FROM employee WHERE employee_id ="
                                    + " ?",
                            member);
            assertThat(row.get("BIRTH_DATE").toString()).startsWith("1962-05-20");
            assertThat(row.get("RETIREMENT_DATE").toString()).startsWith("2022-05-31");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT hq_estab_user FROM hrms_change_task WHERE"
                                            + " hrms_change_task_id = ?",
                                    String.class,
                                    dob.taskId()))
                    .isEqualTo("hr.hq@fci-demo.test");
        } finally {
            // A past retirement date fails the pre-run check P1 of every later DH001 cycle
            // (CycleIT): the member's dates are put back (test batch B).
            jdbc.update(
                    "UPDATE employee SET birth_date = ?, retirement_date = ? WHERE employee_id = ?",
                    dates.get("BIRTH_DATE"),
                    dates.get("RETIREMENT_DATE"),
                    member);
        }
    }

    /**
     * SRS-1-69 / SRS-15-40: a task received before the freeze and still pending counts against the
     * lock of its unit's cycle of that month; once approved it no longer does. The unit is one no
     * other IT runs a cycle for, and its cycle of the month is created here.
     */
    // SC-16-0161: a pending task received before the freeze blocks the lock.
    @Test
    void SRS_1_69_aPendingChangeReceivedBeforeTheFreezeHoldsTheLock() throws Exception {
        String yymm =
                String.format(
                        "%04d%02d",
                        LocalDate.now(IST).getYear(), LocalDate.now(IST).getMonthValue());
        // The lowest free unit: CycleStatusIT takes the highest one for its own cycle.
        Long unitId =
                jdbc.queryForObject(
                        "SELECT MIN(l.location_id) FROM org_location l WHERE l.is_cycle_unit = 'Y'"
                            + " AND l.location_code NOT IN ('HQ001', 'DH001', 'KL001', 'CH001',"
                            + " 'MR001', 'LD001', 'NA11') AND EXISTS (SELECT 1 FROM employee e"
                            + " WHERE e.location_id = l.location_id AND e.record_status = 'ACTIVE')"
                            + " AND NOT EXISTS (SELECT 1 FROM payroll_cycle_instance c WHERE"
                            + " c.org_unit_id = l.location_id AND c.payroll_yymm = ?)",
                        Long.class,
                        yymm);
        assertThat(unitId).as("a cycle unit with no cycle this month").isNotNull();
        Map<String, Object> pick =
                jdbc.queryForMap(
                        "SELECT MAX(employee_id) AS emp, MAX(location_id) AS unit FROM employee"
                                + " WHERE location_id = ? AND record_status = 'ACTIVE'",
                        unitId);
        assertThat(pick.get("EMP")).as("an employee of an unused cycle unit").isNotNull();
        long member = ((Number) pick.get("EMP")).longValue();
        long unit = ((Number) pick.get("UNIT")).longValue();
        HrmsChanges.Received task = receive(member, "EPS_JOIN_DATE", "2001-07-01", unique());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT received_before_freeze FROM hrms_change_task WHERE"
                                        + " hrms_change_task_id = ?",
                                String.class,
                                task.taskId()))
                .isEqualTo("Y");
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES (?, ?, 'DO', 'IT',"
                        + " 'INITIATED', 'it.fixture')",
                yymm,
                unit);
        Long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE org_unit_id = ?"
                                + " AND payroll_yymm = ?",
                        Long.class,
                        unit,
                        yymm);
        String count =
                "SELECT COALESCE(SUM(pending_task_count), 0) FROM v_cycle_pending_hrms_change WHERE"
                        + " cycle_instance_id = ?";
        assertThat(jdbc.queryForObject(count, Long.class, cycle)).isEqualTo(1);
        jdbc.update(
                "UPDATE hrms_change_task SET task_status = 'CANCELLED' WHERE hrms_change_task_id ="
                        + " ?",
                task.taskId());
        assertThat(jdbc.queryForObject(count, Long.class, cycle)).as("no longer pending").isZero();
    }

    /**
     * SRS-15-40 / SRS-1-69 (US-05-10 AC4, E2E-06): a pay-affecting change received AFTER the freeze
     * is paid next month and does not hold this month's lock, while it is still pending on I-21;
     * the same task received before the freeze does (the sibling test above).
     */
    @Test
    void SRS_15_40_aPendingChangeReceivedAfterTheFreezeLeavesTheLockFree() throws Exception {
        String yymm =
                String.format(
                        "%04d%02d",
                        LocalDate.now(IST).getYear(), LocalDate.now(IST).getMonthValue());
        Long unitId =
                jdbc.queryForObject(
                        "SELECT MIN(l.location_id) FROM org_location l WHERE l.is_cycle_unit = 'Y'"
                            + " AND l.location_code NOT IN ('HQ001', 'DH001', 'KL001', 'CH001',"
                            + " 'MR001', 'LD001', 'NA11') AND EXISTS (SELECT 1 FROM employee e"
                            + " WHERE e.location_id = l.location_id AND e.record_status = 'ACTIVE')"
                            + " AND NOT EXISTS (SELECT 1 FROM payroll_cycle_instance c WHERE"
                            + " c.org_unit_id = l.location_id AND c.payroll_yymm = ?)",
                        Long.class,
                        yymm);
        assertThat(unitId).as("a cycle unit with no cycle this month").isNotNull();
        long member =
                jdbc.queryForObject(
                        "SELECT MAX(employee_id) FROM employee WHERE location_id = ? AND"
                                + " record_status = 'ACTIVE'",
                        Long.class,
                        unitId);
        HrmsChanges.Received task = receive(member, "EPS_JOIN_DATE", "2001-07-01", unique());
        // As the interface marks a change that arrives once the cycle is frozen (BR-INT-002).
        jdbc.update(
                "UPDATE hrms_change_task SET received_before_freeze = 'N' WHERE"
                        + " hrms_change_task_id = ?",
                task.taskId());
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES (?, ?, 'DO', 'IT',"
                        + " 'FROZEN', 'it.fixture')",
                yymm,
                unitId);
        Long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE org_unit_id = ?"
                                + " AND payroll_yymm = ?",
                        Long.class,
                        unitId,
                        yymm);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT task_status FROM hrms_change_task WHERE"
                                        + " hrms_change_task_id = ?",
                                String.class,
                                task.taskId()))
                .as("still pending on I-21")
                .isEqualTo("PENDING_ORDER_DETAILS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COALESCE(SUM(pending_task_count), 0) FROM"
                                    + " v_cycle_pending_hrms_change WHERE cycle_instance_id = ?",
                                Long.class,
                                cycle))
                .as("a change received after the freeze does not hold the lock")
                .isZero();
    }

    /**
     * SRS-22-12 (US-GAP-123): the seed matrix holds the stated rules as configuration - a DOB
     * change from HRMS needs no OTP; a name, gender or marital-status change applies with no task;
     * the Payroll Maker raises an HRMS value (I-21) and Establishment/HR a value worked out in
     * Payroll (I-05); and where the Checker is final the route has two levels, its last the CHECKER
     * (a third level, the APPROVER, only where the matrix names one).
     */
    @Test
    void SRS_22_12_theSeedMatrixHoldsTheStatedMakersOtpFlagsAndWhereTheCheckerIsFinal() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT otp_required FROM hrms_pay_effect_field WHERE field_code ="
                                        + " 'DATE_OF_BIRTH' AND effective_to IS NULL",
                                String.class))
                .isEqualTo("N");
        assertThat(
                        jdbc.queryForList(
                                "SELECT field_code || ':' || has_pay_effect || auto_apply FROM"
                                        + " hrms_pay_effect_field WHERE field_code IN ('NAME',"
                                        + " 'GENDER', 'MARITAL_STATUS') AND effective_to IS NULL"
                                        + " ORDER BY field_code",
                                String.class))
                .containsExactly("GENDER:NY", "MARITAL_STATUS:NY", "NAME:NY");
        String levels =
                "SELECT l.level_no || ':' || l.level_name || ':' || l.role_code FROM"
                        + " approval_workflow_level l JOIN approval_workflow_action w ON"
                        + " w.workflow_action_id = l.workflow_action_id WHERE w.action_code = ?"
                        + " AND w.action_status = 'ACTIVE' ORDER BY l.level_no";
        for (String hrmsValue : new String[] {"I21_SUBMIT", "I21_SUBMIT_OTP"}) {
            assertThat(jdbc.queryForList(levels, String.class, hrmsValue))
                    .as(hrmsValue + ": the Checker is final")
                    .containsExactly("1:MAKER:PAYROLL_MAKER", "2:CHECKER:PAYROLL_CHECKER");
        }
        for (String payrollValue : new String[] {"I05_INCREMENT", "I05_FIXATION"}) {
            assertThat(jdbc.queryForList(levels, String.class, payrollValue))
                    .as(payrollValue + ": the Checker is final")
                    .containsExactly("1:MAKER:ESTABLISHMENT_HR", "2:CHECKER:PAYROLL_CHECKER");
        }
        assertThat(jdbc.queryForList(levels, String.class, "I21_SUBMIT_HQ"))
                .as("the DOB change with a past retirement date names a third level")
                .endsWith("3:APPROVER:ESTABLISHMENT_HR");
    }

    /** A DATE column as JDBC returns it, as a date. */
    private static final class ColumnDates {
        static LocalDate of(Object v) {
            return v instanceof java.sql.Date d
                    ? d.toLocalDate()
                    : v instanceof java.sql.Timestamp t
                            ? t.toLocalDateTime().toLocalDate()
                            : (LocalDate) v;
        }
    }
}
