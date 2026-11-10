package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.leave.api.LeaveReceipts;
import in.gov.fci.payroll.leave.api.LeaveReceipts.Event;
import in.gov.fci.payroll.leave.api.LeaveReceipts.EventType;
import in.gov.fci.payroll.leave.api.LeaveReceipts.Outcome;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 19 phases 19a - 19c (plan docs/plan/19-leave.md): leave data received from HRMS, the X-05
 * hold of an unmatched event, the I-21 task of a valid one and its pay effect, and the M-20
 * parameter versions under maker-checker at Headquarters.
 */
class LeaveIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String EIS = "106118";

    @Autowired private LeaveReceipts receipts;
    @Autowired private in.gov.fci.payroll.leave.api.LeaveEncashments encashments;
    @Autowired private in.gov.fci.payroll.leave.orchestration.LeaveParameters parameters;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    private static LocalDate today() {
        return LocalDate.now(IST);
    }

    private void types() {
        receipts.receiveType(
                "hrms.it", new LeaveReceipts.LeaveType("EL", "Earned Leave", true, false));
        receipts.receiveType(
                "hrms.it", new LeaveReceipts.LeaveType("LWP", "Leave without pay", false, true));
        // A type no demo seed gives a pay effect (V046_3_9 gives LWP one): the undefined path.
        receipts.receiveType(
                "hrms.it", new LeaveReceipts.LeaveType("ITX", "IT-only leave", false, false));
    }

    private Event event(String type, LocalDate from, LocalDate to, String days, String ref) {
        return new Event(
                EIS,
                type,
                from,
                to,
                new BigDecimal(days),
                false,
                ref,
                "IT-LV-" + System.nanoTime(),
                "DGM (P)",
                EventType.ADDITION,
                null);
    }

    private long openExceptions(String code) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_exception WHERE exception_code = ? AND"
                                + " exception_status IN ('OPEN', 'IN_PROGRESS', 'ROUTED_UPSTREAM')",
                        Long.class,
                        code);
        return n == null ? 0 : n;
    }

    /** SRS-LV-25 / SRS-LV-13: an event without a sanction or a member is held, never applied. */
    @Test
    void SRS_LV_13_anUnmatchedEventIsHeldOnX05AndOpensNoTask() {
        types();
        long before = openExceptions("LEAVE_EVENT_INVALID");
        LocalDate day = today().minusDays(3);
        LeaveReceipts.Received noRef =
                receipts.receiveEvent("hrms.it", event("EL", day, day, "1", " "));
        assertThat(noRef.outcome()).isEqualTo(Outcome.HELD);
        assertThat(noRef.defect()).isEqualTo("NO_SANCTION_REF");
        Event stranger =
                new Event(
                        "999999",
                        "EL",
                        day,
                        day,
                        BigDecimal.ONE,
                        false,
                        "SAN/IT/X",
                        null,
                        null,
                        EventType.ADDITION,
                        null);
        assertThat(receipts.receiveEvent("hrms.it", stranger).defect()).isEqualTo("MEMBER");
        assertThat(openExceptions("LEAVE_EVENT_INVALID")).isGreaterThanOrEqualTo(before + 1);
        assertThat(noRef.taskId()).isNull();
    }

    /**
     * SRS-LV-26: a valid event opens an I-21 task; the same event again is a duplicate; once the
     * Payroll Checker approves it, the transaction carries the type's pay effect, or HELD_UNDEFINED
     * with a blocking X-05 entry when M-20 holds none (never a zero deduction).
     */
    // SRS-LV-33: the Payroll Maker reviews the leave task, the Payroll Checker approves it.
    // SC-19-0029: the I-11 main flow, received and applied.
    @Test
    void SRS_LV_26_aValidEventOpensATaskAndItsApprovalAppliesThePayEffect() throws Exception {
        types();
        // Two years ahead: no cycle IT computes that month, so the held member stays computable.
        LocalDate day = today().plusYears(2).withDayOfMonth(1);
        String ref = "SAN/IT/" + System.nanoTime();
        LeaveReceipts.Received received =
                receipts.receiveEvent("hrms.it", event("ITX", day, day.plusDays(1), "2", ref));
        assertThat(received.outcome()).isEqualTo(Outcome.TASK_OPENED);
        assertThat(received.taskId()).isNotNull();
        assertThat(
                        receipts.receiveEvent(
                                        "hrms.it", event("ITX", day, day.plusDays(1), "2", ref))
                                .outcome())
                .isEqualTo(Outcome.DUPLICATE);

        Map<String, Object> order = new HashMap<>();
        order.put("heldInHrms", true);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult submitted =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/hrms-change-tasks/"
                                                + received.taskId()
                                                + "/submissions",
                                        order))
                        .andReturn();
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString())
                .isLessThan(300);
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                body(submitted).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        Map<String, Object> txn =
                jdbc.queryForMap(
                        "SELECT wage_effect_code, is_back_dated, hrms_change_task_id FROM"
                                + " leave_transaction WHERE application_id = ?",
                        received.applicationId());
        assertThat(txn.get("WAGE_EFFECT_CODE")).isEqualTo("HELD_UNDEFINED");
        assertThat(((Number) txn.get("HRMS_CHANGE_TASK_ID")).longValue())
                .isEqualTo(received.taskId());
        assertThat(openExceptions("LEAVE_PAY_EFFECT_UNDEFINED")).isPositive();

        JsonNode page = all(maker, "/v1/leave-events?eis=" + EIS);
        assertThat(page.findValuesAsString("sanctionRef")).contains(ref);
        JsonNode counts = body(mvc.perform(userGet(maker, "/v1/leave-events/counts")).andReturn());
        assertThat(counts.path("applied").asLong()).isPositive();
        JsonNode typeCounts =
                body(mvc.perform(userGet(maker, "/v1/leave-types/counts")).andReturn());
        assertThat(typeCounts.path("received").asLong()).isGreaterThanOrEqualTo(2);
        // SRS I-11 / M-13 Export: the screen's rows and columns as a file, logged (SRS-20-24).
        String events = csv(maker, "/v1/leave-events/export?format=CSV&eis=" + EIS);
        assertThat(events).startsWith("\"hrms_sanction_ref\",\"eis\",\"member\"").contains(ref);
        String leaveTypes = csv(maker, "/v1/leave-types/export?format=CSV");
        assertThat(leaveTypes)
                .startsWith("\"hrms_code\",\"leave_type\"")
                .contains("\"EL\"", "\"LWP\"");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name = 'export' AND"
                                        + " entity_key = 'leave-events' AND changed_by = ?",
                                Integer.class,
                                "maker.delhi@fci-demo.test"))
                .isPositive();
    }

    /**
     * US-GAP-299 (SRS-1-69, SRS-LV-26, SRS-LV-33, SRS-15-40): a leave event received before the
     * freeze opens a LEAVE_EVENT task on I-21 that counts against the month-end lock of its unit's
     * cycle (pre-lock check HRMS_CHANGE_PENDING, v_cycle_pending_hrms_change) until it is decided.
     * The unit is one no other IT runs a cycle for this month, and its cycle is created here.
     */
    @Test
    void SRS_1_69_aPendingLeaveEventTaskFailsTheHrmsChangePendingGate() {
        types();
        String yymm = String.format("%04d%02d", today().getYear(), today().getMonthValue());
        Long unit =
                jdbc.queryForObject(
                        "SELECT MAX(l.location_id) FROM org_location l WHERE l.is_cycle_unit = 'Y'"
                            + " AND l.location_code NOT IN ('HQ001', 'DH001', 'KL001', 'CH001',"
                            + " 'MR001', 'LD001', 'NA11') AND EXISTS (SELECT 1 FROM employee e"
                            + " WHERE e.location_id = l.location_id AND e.record_status = 'ACTIVE'"
                            + " AND e.employment_status = 'IN_SERVICE') AND NOT EXISTS (SELECT 1"
                            + " FROM payroll_cycle_instance c WHERE c.org_unit_id = l.location_id"
                            + " AND c.payroll_yymm = ?)",
                        Long.class,
                        yymm);
        assertThat(unit).as("a cycle unit with no cycle this month").isNotNull();
        String eis =
                jdbc.queryForObject(
                        "SELECT MIN(emp_num) FROM employee WHERE location_id = ? AND record_status"
                                + " = 'ACTIVE' AND employment_status = 'IN_SERVICE'",
                        String.class,
                        unit);
        LocalDate day = today().withDayOfMonth(1);
        LeaveReceipts.Received received =
                receipts.receiveEvent(
                        "hrms.it",
                        new Event(
                                eis,
                                "ITX",
                                day,
                                day,
                                BigDecimal.ONE,
                                false,
                                "SAN/IT/GATE/" + System.nanoTime(),
                                "IT-LV-" + System.nanoTime(),
                                "DGM (P)",
                                EventType.ADDITION,
                                null));
        assertThat(received.outcome()).isEqualTo(Outcome.TASK_OPENED);
        Map<String, Object> task =
                jdbc.queryForMap(
                        "SELECT f.field_code, t.received_before_freeze, t.target_payroll_yymm,"
                                + " t.paying_unit_id, t.task_status FROM hrms_change_task t JOIN"
                                + " hrms_pay_effect_field f ON f.pay_effect_field_id ="
                                + " t.pay_effect_field_id WHERE t.hrms_change_task_id = ?",
                        received.taskId());
        assertThat(task)
                .containsEntry("FIELD_CODE", "LEAVE_EVENT")
                .containsEntry("RECEIVED_BEFORE_FREEZE", "Y")
                .containsEntry("TARGET_PAYROLL_YYMM", yymm)
                .containsEntry("TASK_STATUS", "PENDING_ORDER_DETAILS");
        long payingUnit = ((Number) task.get("PAYING_UNIT_ID")).longValue();
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES (?, ?, 'DO', 'IT',"
                        + " 'COMPUTED', 'it.fixture')",
                yymm,
                payingUnit);
        Long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE org_unit_id = ?"
                                + " AND payroll_yymm = ?",
                        Long.class,
                        payingUnit,
                        yymm);
        String pending =
                "SELECT COALESCE(SUM(pending_task_count), 0) FROM v_cycle_pending_hrms_change WHERE"
                        + " cycle_instance_id = ?";
        assertThat(jdbc.queryForObject(pending, Long.class, cycle))
                .as("the pending leave task holds the lock")
                .isEqualTo(1);
        // Sent to the Checker it still holds the lock; only a decision (here a cancellation)
        // frees it.
        jdbc.update(
                "UPDATE hrms_change_task SET task_status = 'PENDING_CHECKER',"
                        + " order_held_in_hrms = 'Y' WHERE hrms_change_task_id = ?",
                received.taskId());
        assertThat(jdbc.queryForObject(pending, Long.class, cycle)).isEqualTo(1);
        jdbc.update(
                "UPDATE hrms_change_task SET task_status = 'CANCELLED' WHERE hrms_change_task_id"
                        + " = ?",
                received.taskId());
        assertThat(jdbc.queryForObject(pending, Long.class, cycle)).isZero();
    }

    /**
     * UC I-11 verify (SC-19-0034; SRS-LV-26, SRS-LV-10, SRS-21-7): every leave pay effect in the
     * cycle is traced to its HRMS event. A back-dated LWP approved on I-21 is held as a leave
     * transaction carrying the HRMS sanction reference and the I-21 task, marked back-dated, and
     * the locked months' share is raised as one LEAVE arrear event naming the transaction and its
     * task. A DH001 member no other IT uses.
     */
    @Test
    void SC_19_0034_everyLeaveDeductionArrearAndRecoveryIsTracedToItsHrmsEvent() throws Exception {
        types();
        Map<String, Object> who =
                jdbc.queryForMap(
                        "SELECT employee_id, emp_num FROM employee WHERE location_id = (SELECT"
                                + " location_id FROM org_location WHERE location_code = 'DH001')"
                                + " AND record_status = 'ACTIVE' AND employment_status ="
                                + " 'IN_SERVICE' ORDER BY emp_num DESC OFFSET 25 ROWS FETCH NEXT 1"
                                + " ROWS ONLY");
        long member = ((Number) who.get("EMPLOYEE_ID")).longValue();
        LocalDate from = today().minusMonths(3).withDayOfMonth(2);
        String ref = "SAN/IT/TRACE/" + System.nanoTime();
        LeaveReceipts.Received received =
                receipts.receiveEvent(
                        "hrms.it",
                        new Event(
                                (String) who.get("EMP_NUM"),
                                "LWP",
                                from,
                                from.plusDays(1),
                                new BigDecimal("2"),
                                false,
                                ref,
                                "IT-LV-" + System.nanoTime(),
                                "DGM (P)",
                                EventType.ADDITION,
                                null));
        assertThat(received.outcome()).isEqualTo(Outcome.TASK_OPENED);
        Map<String, Object> order = new HashMap<>();
        order.put("heldInHrms", true);
        MvcResult submitted =
                mvc.perform(
                                userPost(
                                        login(
                                                "maker.delhi@fci-demo.test",
                                                "PAYROLL_MAKER",
                                                "DH001"),
                                        "/v1/hrms-change-tasks/"
                                                + received.taskId()
                                                + "/submissions",
                                        order))
                        .andReturn();
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString())
                .isLessThan(300);
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                body(submitted).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        String taskNo =
                jdbc.queryForObject(
                        "SELECT task_no FROM hrms_change_task WHERE hrms_change_task_id = ?",
                        String.class,
                        received.taskId());
        Map<String, Object> txn =
                jdbc.queryForMap(
                        "SELECT hrms_sanction_ref, hrms_change_task_id, is_back_dated,"
                                + " wage_effect_code, source_code FROM leave_transaction WHERE"
                                + " application_id = ?",
                        received.applicationId());
        assertThat(txn)
                .containsEntry("HRMS_SANCTION_REF", ref)
                .containsEntry("IS_BACK_DATED", "Y")
                .containsEntry("SOURCE_CODE", "INTF");
        assertThat(((Number) txn.get("HRMS_CHANGE_TASK_ID")).longValue())
                .isEqualTo(received.taskId());
        assertThat(txn.get("WAGE_EFFECT_CODE")).isNotEqualTo("HELD_UNDEFINED");
        Map<String, Object> arrear =
                jdbc.queryForMap(
                        "SELECT a.source_module, a.source_entity, a.effective_from, m.employee_id"
                                + " FROM arrear_event a JOIN arrear_event_member m ON"
                                + " m.arrear_event_id = a.arrear_event_id WHERE a.source_key = ?",
                        taskNo);
        assertThat(arrear)
                .containsEntry("SOURCE_MODULE", "LEAVE")
                .containsEntry("SOURCE_ENTITY", "leave_transaction");
        assertThat(((Number) arrear.get("EMPLOYEE_ID")).longValue()).isEqualTo(member);
        assertThat(arrear.get("EFFECTIVE_FROM").toString()).startsWith(from.toString());
    }

    /**
     * UC M-20 verify (SC-19-0037; SRS-LV-14, BR-LEAVE-007): every leave pay effect uses the
     * approved parameter version for the computation date, so a closed period is regenerated with
     * the version then in force: an earlier day reads the earlier version, a later day the later
     * one, a version not yet approved is never read and a day before the type's first version reads
     * the version for every type (D19-2). The versions are of the IT-only leave type and rolled
     * back, so no other test reads them.
     */
    @Test
    void SC_19_0037_aClosedPeriodRegeneratesWithTheM20VersionThenInForce() {
        types();
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            status.setRollbackOnly();
                            String insert =
                                    "INSERT INTO leave_pay_parameter (parameter_code,"
                                            + " parameter_name, leave_type_code, numeric_value,"
                                            + " version_no, effective_from, effective_to,"
                                            + " is_confirmed) VALUES ('LWP_DIVISOR', 'LWP"
                                            + " divisor (IT)', 'ITX', ?, ?, ?, ?, ?)";
                            jdbc.update(
                                    insert,
                                    30,
                                    1,
                                    java.sql.Date.valueOf("2091-01-01"),
                                    java.sql.Date.valueOf("2091-06-30"),
                                    "Y");
                            jdbc.update(
                                    insert, 26, 2, java.sql.Date.valueOf("2091-07-01"), null, "Y");
                            jdbc.update(
                                    insert, 31, 3, java.sql.Date.valueOf("2092-01-01"), null, "N");
                            String code =
                                    in.gov.fci.payroll.leave.orchestration.LeaveParameters
                                            .LWP_DIVISOR;
                            assertThat(parameters.number(code, "ITX", LocalDate.of(2091, 3, 15)))
                                    .as("a closed period keeps its version")
                                    .hasValueSatisfying(
                                            v -> assertThat(v).isEqualByComparingTo("30"));
                            assertThat(parameters.number(code, "ITX", LocalDate.of(2091, 6, 30)))
                                    .hasValueSatisfying(
                                            v -> assertThat(v).isEqualByComparingTo("30"));
                            assertThat(parameters.number(code, "ITX", LocalDate.of(2091, 7, 1)))
                                    .hasValueSatisfying(
                                            v -> assertThat(v).isEqualByComparingTo("26"));
                            assertThat(parameters.number(code, "ITX", LocalDate.of(2092, 2, 1)))
                                    .as("a version not yet approved is never read")
                                    .hasValueSatisfying(
                                            v -> assertThat(v).isEqualByComparingTo("26"));
                            assertThat(parameters.text(code, "ITX", LocalDate.of(2090, 12, 31)))
                                    .as(
                                            "before the type's first version: the version for every"
                                                    + " type")
                                    .contains("CALENDAR_DAYS");
                        });
    }

    /**
     * SRS-G-1 / R-MC on M-20: the Administrator who proposes a leave parameter version never
     * approves it; the version stays unconfirmed (never read) until another approver decides.
     */
    @Test
    void SRS_G_1_theM20MakerNeverApprovesTheOwnParameterVersion() throws Exception {
        types();
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        Map<String, Object> body = new HashMap<>();
        body.put("code", "PAY_EFFECT");
        body.put("leaveType", "LWP");
        body.put("text", "NO_EFFECT");
        body.put("effectiveFrom", today().plusYears(7).withDayOfMonth(1).toString());
        body.put("reason", "As in the current system (IT)");
        body.put("circularId", circular());
        MvcResult proposed =
                mvc.perform(userPost(admin, "/v1/leave-pay-parameters", body)).andReturn();
        assertThat(proposed.getResponse().getStatus())
                .as(proposed.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(proposed).get("approvalId").asLong();
        // Under the Rule Administrator role the decision is not even offered (no W-01 APPROVE).
        MvcResult asAdmin =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(asAdmin.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(asAdmin)).isEqualTo("AUTHZ-403-PERMISSION");
        // The same proposer under the approver role it also holds: refused as the maker.
        MvcResult own =
                mvc.perform(
                                userPost(
                                        login("su.hq@fci-demo.test", "RULE_APPROVER", "HQ001"),
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(own.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(own)).isEqualTo("WF-403-SOD");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_confirmed FROM leave_pay_parameter WHERE approval_id ="
                                        + " ?",
                                String.class,
                                approval))
                .isEqualTo("N");
        assertThat(
                        decide(
                                login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001"),
                                approval,
                                "REJECT",
                                "Not needed (IT)",
                                null))
                .isEqualTo(200);
    }

    /**
     * SRS-LV-14: a version is proposed by the Administrator at Headquarters and read only once the
     * Payroll Checker approves it; a unit user, a Payroll Maker, the proposer as approver and an
     * overlapping version are refused; the Administrator has no access to I-11.
     */
    // SC-19-0035: the M-20 main flow, proposed and approved.
    @Test
    void SRS_LV_14_aParameterVersionActivatesOnlyOnTheCheckersApprovalAtHeadquarters()
            throws Exception {
        types();
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        LocalDate from = today().withDayOfMonth(1);
        Map<String, Object> body = new HashMap<>();
        body.put("code", "PAY_EFFECT");
        body.put("leaveType", "EL");
        body.put("text", "NO_EFFECT");
        body.put("effectiveFrom", from.toString());
        body.put("reason", "As in the current system (IT)");
        // SRS M-20: where an order applies, the version names its Active M-25 circular.
        body.put("circularId", 987_654_321L);
        assertThat(code(mvc.perform(userPost(admin, "/v1/leave-pay-parameters", body)).andReturn()))
                .as("an unknown circular is refused")
                .isNotBlank();
        long circular = circular();
        body.put("circularId", circular);
        MvcResult proposed =
                mvc.perform(userPost(admin, "/v1/leave-pay-parameters", body)).andReturn();
        assertThat(proposed.getResponse().getStatus())
                .as(proposed.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(proposed).get("approvalId").asLong();
        assertThat(code(mvc.perform(userPost(admin, "/v1/leave-pay-parameters", body)).andReturn()))
                .as("one version waits at a time")
                .isEqualTo("LV-409-PARAMETER-PENDING");
        assertThat(decide(admin, approval, "APPROVE", null, null))
                .as("not the proposer")
                .isEqualTo(403);

        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        mvc.perform(userPost(maker, "/v1/leave-pay-parameters", body))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("a Payroll Maker proposes nothing")
                .isEqualTo(403);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_confirmed FROM leave_pay_parameter WHERE approval_id ="
                                        + " ?",
                                String.class,
                                approval))
                .isEqualTo("N");
        assertThat(
                        decide(
                                login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_confirmed FROM leave_pay_parameter WHERE approval_id ="
                                        + " ?",
                                String.class,
                                approval))
                .isEqualTo("Y");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM circular_order_link l JOIN"
                                    + " leave_pay_parameter p ON l.linked_key ="
                                    + " TO_CHAR(p.leave_pay_parameter_id) WHERE l.linked_entity ="
                                    + " 'leave_pay_parameter' AND p.approval_id = ? AND"
                                    + " l.circular_order_id = ?",
                                Integer.class,
                                approval,
                                circular))
                .isEqualTo(1);
        body.put("effectiveFrom", from.minusDays(1).toString());
        assertThat(code(mvc.perform(userPost(admin, "/v1/leave-pay-parameters", body)).andReturn()))
                .as("never on or before an approved version")
                .isEqualTo("LV-409-PARAMETER-DATE");

        assertThat(
                        mvc.perform(userGet(admin, "/v1/leave-events"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("the Administrator has no access to leave data")
                .isEqualTo(403);
        assertThat(download(admin, "/v1/leave-events/export").getResponse().getStatus())
                .as("nor to its export")
                .isEqualTo(403);
        assertThat(all(admin, "/v1/leave-types").findValuesAsString("code")).contains("EL", "LWP");
    }

    /**
     * SRS-LV-21 / LV-34 / BR-FNF-009: an encashment above the HRMS balance is held naming the
     * balance; a valid one opens its I-21 task once (never twice); the separation valuation refuses
     * while M-20 holds no parameter (never a default).
     */
    @Test
    void SRS_LV_21_anEncashmentAboveTheBalanceIsHeldAndAValidOneOpensItsTaskOnce() {
        types();
        // Dated on the balance day: another suite may send a later balance for the same member.
        LocalDate day = today().minusDays(1);
        receipts.receiveBalance(
                "hrms.it", new LeaveReceipts.Balance(EIS, "EL", null, BigDecimal.TEN, day));
        LeaveReceipts.Received above =
                receipts.receiveEncashment(
                        "hrms.it",
                        new LeaveReceipts.EncashmentSanction(
                                EIS, "EL", new BigDecimal("12"), day, "ENC/IT/1", null, false));
        assertThat(above.outcome()).isEqualTo(Outcome.HELD);
        assertThat(above.defect()).isEqualTo("ABOVE_BALANCE 10");
        assertThat(
                        receipts.receiveEncashment(
                                        "hrms.it",
                                        new LeaveReceipts.EncashmentSanction(
                                                EIS,
                                                "LWP",
                                                BigDecimal.ONE,
                                                today(),
                                                "ENC/IT/2",
                                                null,
                                                false))
                                .defect())
                .isEqualTo("NOT_ENCASHABLE");
        String ref = "ENC/IT/" + System.nanoTime();
        LeaveReceipts.EncashmentSanction valid =
                new LeaveReceipts.EncashmentSanction(
                        EIS,
                        "EL",
                        new BigDecimal("5"),
                        day,
                        ref,
                        "IT-ENC-" + System.nanoTime(),
                        false);
        LeaveReceipts.Received opened = receipts.receiveEncashment("hrms.it", valid);
        assertThat(opened.outcome()).isEqualTo(Outcome.TASK_OPENED);
        assertThat(opened.taskId()).isNotNull();
        assertThat(receipts.receiveEncashment("hrms.it", valid).outcome())
                .isEqualTo(Outcome.DUPLICATE);
        assertThat(openExceptions("LEAVE_ENCASHMENT_INVALID")).isGreaterThanOrEqualTo(1);
        // A member with no encashable balance from HRMS: never valued on an assumption.
        long employeeId =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = '105461'", Long.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> encashments.atSeparation(employeeId, today()))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private long circular() {
        String number = "IT/LV/" + System.nanoTime();
        jdbc.update(
                "INSERT INTO document_attachment (entity_name, entity_key, file_name,"
                        + " storage_provider, storage_object_key, contains_pii, uploaded_by) VALUES"
                        + " ('circular_order', ?, 'it.pdf', 'ORACLE_SECUREFILE', ?, 'N', 'it')",
                number,
                "it/circular/" + number);
        jdbc.update(
                "INSERT INTO circular_order (circular_number, circular_date, subject,"
                    + " category_code, applies_all_offices, effective_from, document_attachment_id,"
                    + " circular_status, created_by) SELECT ?, ?, 'Leave (IT)', 'DA', 'Y', ?,"
                    + " attachment_id, 'ACTIVE', 'it.fixture' FROM document_attachment WHERE"
                    + " storage_object_key = ?",
                number,
                java.sql.Date.valueOf(today()),
                java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)),
                "it/circular/" + number);
        return jdbc.queryForObject(
                "SELECT circular_order_id FROM circular_order WHERE circular_number = ?",
                Long.class,
                number);
    }

    /**
     * SRS-LV-28 / BR-LEAVE-001 / BR-LEAVE-005: Payroll keeps no leave function of its own for
     * employees - no leave application, sanction, cancellation, entitlement or balance is written
     * here (HRMS does these; events only arrive through I-21). The I-11 events, the M-13 types and
     * the balances are read-only: every write to them answers 405; the reads still answer.
     */
    @Test
    void SRS_LV_28_payrollHasNoLeaveApplicationSanctionOrBalanceFunction() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Map<String, Object> body = Map.of("leaveType", "EL", "from", "2026-10-01", "days", 1);
        for (String path :
                java.util.List.of(
                        "/v1/leave-events", "/v1/leave-types", "/v1/leave-balances/mine")) {
            assertThat(
                            mvc.perform(userPost(maker, path, body))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus())
                    .as(path)
                    .isIn(403, 405);
        }
        assertThat(
                        mvc.perform(userGet(maker, "/v1/leave-events"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    /**
     * SRS-LV-20 / BR-LEAVE-012 (the part that is built): a paid leave encashment record is never
     * edited - the arrear records offer no write but their run and submission, and a PUT / PATCH of
     * a record answers 405. The recovery of an excess is not built (PENDING B-18).
     */
    @Test
    void SRS_LV_20_aPaidEncashmentRecordIsNeverEdited() throws Exception {
        // BMAD TR-045: a paid encashment record exists (as the I-21 approval records it), then
        // every
        // way of changing it is refused - PUT / PATCH on the collection (405, read-only) and on the
        // record (no route edits one: 404) - and the row keeps what was paid.
        Long employee =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, EIS);
        String ref = "IT-LV20-" + System.nanoTime();
        jdbc.update(
                "INSERT INTO leave_encashment (employee_id, leave_type_code, encashment_reason,"
                        + " days_encashed, valuation_basis, rate_per_day, amount_paid, was_capped)"
                        + " VALUES (?, 'EL', 'IN_SERVICE', 5, ?, 2805, 14025, 'N')",
                employee,
                ref);
        Long id =
                jdbc.queryForObject(
                        "SELECT encashment_id FROM leave_encashment WHERE valuation_basis = ?",
                        Long.class,
                        ref);
        Map<String, Object> before =
                jdbc.queryForMap("SELECT * FROM leave_encashment WHERE encashment_id = ?", id);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Map<String, Object> edit = Map.of("amountPaid", 1, "daysEncashed", 1);
        for (String path : List.of("/v1/leave-encashment-arrears", "/v1/leave-encashments")) {
            int expected = path.endsWith("arrears") ? 405 : 404;
            assertThat(
                            mvc.perform(userPut(maker, path, edit))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus())
                    .as("PUT " + path)
                    .isEqualTo(expected);
            assertThat(
                            mvc.perform(
                                            userRequest(
                                                            maker,
                                                            org.springframework.test.web.servlet
                                                                    .request.MockMvcRequestBuilders
                                                                    .patch(path))
                                                    .header(
                                                            "Idempotency-Key",
                                                            java.util.UUID.randomUUID().toString())
                                                    .contentType(
                                                            org.springframework.http.MediaType
                                                                    .APPLICATION_JSON)
                                                    .content("{\"amountPaid\":1}"))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus())
                    .as("PATCH " + path)
                    .isEqualTo(expected);
            for (String record : List.of(path + "/" + id)) {
                assertThat(
                                mvc.perform(userPut(maker, record, edit))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus())
                        .as("PUT " + record)
                        .isEqualTo(404);
            }
        }
        assertThat(jdbc.queryForMap("SELECT * FROM leave_encashment WHERE encashment_id = ?", id))
                .isEqualTo(before);
    }

    /**
     * SRS-G-29, SRS-LV-14, SRS-LV-23: I-11 is scoped: another office's Maker reads no event of this
     * office, its report holds none of them either, and the Administrator has no leave data.
     */
    @Test
    void SRS_G_29_leaveEventsAndTheirReportHoldTheOfficeScope() throws Exception {
        types();
        LocalDate day = today().plusYears(2).withDayOfMonth(3);
        String ref = "SAN/SCOPE/" + System.nanoTime();
        assertThat(receipts.receiveEvent("hrms.it", event("EL", day, day, "1", ref)).outcome())
                .isEqualTo(Outcome.TASK_OPENED);
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        assertThat(all(chennai, "/v1/leave-events?eis=" + EIS).findValuesAsString("sanctionRef"))
                .doesNotContain(ref);
        String report =
                csv(
                        chennai,
                        "/v1/leave-events/recovery-report?format=CSV&fromMonth=202601&toMonth=203012");
        assertThat(report).doesNotContain(EIS);
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        assertThat(
                        mvc.perform(
                                        userGet(
                                                admin,
                                                "/v1/leave-events/recovery-report?fromMonth=202601"
                                                        + "&toMonth=202612"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }
}
