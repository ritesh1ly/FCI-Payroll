package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.leave.api.LeaveReceipts;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Module 13 phase 13b (plan docs/plan/13-arrears-retro-wage-revision.md): the one retrospective
 * mechanism (SRS-21-7). A back-dated change raises a single-member event in its own transaction;
 * once it commits, each locked month from the effective date is recomputed on that month's rates
 * and set against what the month paid, component by component (SRS-8-13); the same source record
 * never raises twice; a member that cannot be computed leaves the event RAISED for the Maker to
 * compute again on I-08; a back-dated leave approved through I-21 raises its event (SRS-LV-10).
 */
class RetroEventIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private RetroEvents retro;
    @Autowired private PendingSalary pending;
    @Autowired private LeaveReceipts receipts;
    @Autowired private in.gov.fci.payroll.employee.api.HrmsChanges changes;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private in.gov.fci.payroll.configuration.api.SystemParameters parameters;
    @Autowired private in.gov.fci.payroll.jobs.orchestration.JobCatalog jobs;

    private long member(int offset) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND employment_status ="
                        + " 'IN_SERVICE' AND record_status = 'ACTIVE' ORDER BY emp_num OFFSET ?"
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"),
                offset);
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    /** A locked DH001 cycle of the month whose ACTUAL result paid the member the given Basic. */
    private void paid(long employeeId, YearMonth month, String basic) {
        String yymm = yymm(month);
        long unit = locationId("DH001");
        String find =
                "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?";
        List<Long> cycles = jdbc.queryForList(find, Long.class, yymm, unit);
        if (cycles.isEmpty()) {
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                            + " fin_year, cycle_status, trigger_type, initiated_by) VALUES (?, ?,"
                            + " 'DO', '2026-27', 'LOCKED', 'ON_DEMAND', 'it.fixture')",
                    yymm,
                    unit);
            cycles = jdbc.queryForList(find, Long.class, yymm, unit);
        }
        long cycle = cycles.getFirst();
        java.sql.Date first = java.sql.Date.valueOf(month.atDay(1));
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, result_status) VALUES (?, ?, ?, 'STAFF', ?,"
                        + " ?, 'ACTUAL', ?, 0, ?, 'CURRENT')",
                cycle,
                unit,
                employeeId,
                yymm,
                first,
                new BigDecimal(basic),
                new BigDecimal(basic));
        long result =
                jdbc.queryForObject(
                        "SELECT MAX(payroll_result_id) FROM payroll_result WHERE employee_id = ?"
                                + " AND payroll_yymm = ?",
                        Long.class,
                        employeeId,
                        yymm);
        jdbc.update(
                "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type,"
                        + " payroll_yymm, period_date) SELECT ?, ?, ?, ?, component_id, ?,"
                        + " 'ACTUAL', ?, ? FROM pay_component WHERE legacy_pay_code = 101",
                result,
                cycle,
                unit,
                employeeId,
                new BigDecimal(basic),
                yymm,
                first);
    }

    private long raise(long employeeId, LocalDate from, String key) {
        Long id =
                new TransactionTemplate(transactions)
                        .execute(
                                s ->
                                        retro.raise(
                                                new RetroEvents.Raise(
                                                        employeeId,
                                                        from,
                                                        "OTHER",
                                                        "IT",
                                                        "it_record",
                                                        key,
                                                        "Back-dated change (IT)",
                                                        "it.fixture")));
        return id == null ? -1 : id;
    }

    private Map<String, Object> event(long id) {
        return jdbc.queryForMap(
                "SELECT event_status, source_module, scope_to_yymm FROM arrear_event WHERE"
                        + " arrear_event_id = ?",
                id);
    }

    @Test
    void
            SRS_8_2_SRS_8_13_SRS_21_1_eachLockedMonthIsRecomputedAgainstWhatItPaidComponentByComponent()
                    throws Exception {
        long employeeId = member(5);
        // Far ahead: no other IT locks or computes these months.
        YearMonth first = YearMonth.now(IST).plusYears(13);
        paid(employeeId, first, "1000");
        paid(employeeId, first.plusMonths(1), "1000");
        String key = "IT-" + System.nanoTime();
        long id = raise(employeeId, first.atDay(10), key);

        Map<String, Object> e = event(id);
        assertThat(e.get("EVENT_STATUS"))
                .as("computed once the raise committed")
                .isEqualTo("COMPUTED");
        assertThat(e.get("SCOPE_TO_YYMM")).isEqualTo(yymm(first.plusMonths(1)));
        BigDecimal due =
                pending.compute(employeeId, first).lines().stream()
                        .filter(l -> l.payCode() == 101)
                        .findFirst()
                        .orElseThrow()
                        .amount();
        List<Map<String, Object>> basic =
                jdbc.queryForList(
                        "SELECT d.differential_yymm, d.original_amount, d.recomputed_amount,"
                                + " d.arrear_amount FROM arrear_detail d JOIN pay_component c ON"
                                + " c.component_id = d.component_id WHERE d.arrear_event_id = ? AND"
                                + " c.legacy_pay_code = 101 AND d.arrear_status = 'CALCULATED'"
                                + " ORDER BY d.differential_yymm",
                        id);
        assertThat(basic).hasSize(2);
        assertThat((BigDecimal) basic.getFirst().get("ORIGINAL_AMOUNT"))
                .isEqualByComparingTo("1000");
        assertThat((BigDecimal) basic.getFirst().get("ARREAR_AMOUNT"))
                .isEqualByComparingTo(due.subtract(new BigDecimal("1000")));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM arrear_detail d JOIN pay_component c ON"
                                        + " c.component_id = d.component_id WHERE d.arrear_event_id"
                                        + " = ? AND c.payslip_group NOT IN ('PAY', 'ALLOWANCES')",
                                Integer.class,
                                id))
                .as("D13-7: only the pay-linked components")
                .isZero();
        assertThat(raise(employeeId, first.atDay(10), key)).as("never twice").isEqualTo(id);

        // I-08: the Maker computes it again; the earlier details are reversed, never deleted.
        Integer computedRows =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM arrear_detail WHERE arrear_event_id = ? AND"
                                + " arrear_status = 'CALCULATED'",
                        Integer.class,
                        id);
        assertThat(computedRows)
                .as("Basic and its linked components, two months")
                .isGreaterThanOrEqualTo(2);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult again =
                mvc.perform(userPost(maker, "/v1/arrear-events/" + id + "/computations", Map.of()))
                        .andReturn();
        assertThat(again.getResponse().getStatus())
                .as(again.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(again).path("computed").asBoolean()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM arrear_detail WHERE arrear_event_id = ? AND"
                                        + " arrear_status = 'REVERSED'",
                                Integer.class,
                                id))
                .as("every earlier row reversed, none deleted")
                .isEqualTo(computedRows);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM arrear_detail WHERE arrear_event_id = ? AND"
                                        + " arrear_status = 'CALCULATED'",
                                Integer.class,
                                id))
                .as("the same inputs give the same rows again")
                .isEqualTo(computedRows);
        JsonNode details =
                body(
                        mvc.perform(userGet(maker, "/v1/arrear-events/" + id + "/details"))
                                .andReturn());
        assertThat(details.path("total").asLong()).isEqualTo(2L * computedRows);

        // A bulk event (the DA arrear job's) is not recomputed here.
        long bulk =
                jdbc.queryForObject(
                        "SELECT MIN(arrear_event_id) FROM arrear_event WHERE event_reference ="
                                + " 'ARR-DEMO-DA-01'",
                        Long.class);
        MvcResult refused =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/arrear-events/" + bulk + "/computations",
                                        Map.of()))
                        .andReturn();
        assertThat(body(refused).path("errorCode").asString()).isEqualTo("ARR-409-STATE");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(
                        mvc.perform(
                                        userPost(
                                                checker,
                                                "/v1/arrear-events/" + id + "/computations",
                                                Map.of()))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("only the Payroll Maker computes")
                .isEqualTo(403);
    }

    /**
     * SRS-1-42 (BR-EMPR-012, US-GAP-48): when a period is recomputed for arrears, an approved
     * Maintain Direct Payroll (I-19) deduction and a supplementary-window (I-23) recovery of that
     * month keep their nature: the recompute sets only the engine's pay-linked earnings against
     * what the month paid, so neither entry becomes an arrear payment, neither is netted into the
     * differential, and both rows stay as they were.
     */
    @Test
    void SRS_1_42_mdpAndSupplementaryEntriesKeepTheirNatureWhenAPeriodIsRecomputed()
            throws Exception {
        long employeeId = member(6);
        YearMonth month = YearMonth.now(IST).plusYears(14);
        paid(employeeId, month, "1000");
        String yymm = yymm(month);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        yymm,
                        locationId("DH001"));
        long result =
                jdbc.queryForObject(
                        "SELECT MAX(payroll_result_id) FROM payroll_result WHERE employee_id = ?"
                                + " AND payroll_yymm = ?",
                        Long.class,
                        employeeId,
                        yymm);
        long deduction =
                jdbc.queryForObject(
                        "SELECT MIN(component_id) FROM pay_component WHERE component_type ="
                                + " 'DEDUCTION'",
                        Long.class);
        // The I-19 deduction, approved and carried in the month's result as a deduction line.
        jdbc.update(
                "INSERT INTO mdp_transaction (cycle_instance_id, employee_id, component_id, amount,"
                        + " mdp_status, reason, entered_by) VALUES (?, ?, ?, 250, 'APPROVED',"
                        + " 'IT: direct deduction', 'it.fixture')",
                cycle,
                employeeId,
                deduction);
        jdbc.update(
                "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type,"
                        + " payroll_yymm, period_date) VALUES (?, ?, ?, ?, ?, -250, 'ACTUAL', ?,"
                        + " ?)",
                result,
                cycle,
                locationId("DH001"),
                employeeId,
                deduction,
                yymm,
                java.sql.Date.valueOf(month.atDay(1)));
        // An I-23 recovery of an excess Basic of that month (outside the locked result).
        long basicComponent =
                jdbc.queryForObject(
                        "SELECT component_id FROM pay_component WHERE legacy_pay_code = 101",
                        Long.class);
        jdbc.update(
                "INSERT INTO supplementary_payment (employee_id, cycle_instance_id,"
                        + " for_period_yymm, payment_type, component_id, amount,"
                        + " application_date, payment_status, record_origin) VALUES (?, ?, ?,"
                        + " 'RECOVERY', ?, -400, ?, 'RECORDED', 'MIGRATED')",
                employeeId,
                cycle,
                yymm,
                basicComponent,
                java.sql.Date.valueOf(month.atDay(20)));
        long mdpRows = mdpSnapshot(employeeId, cycle);
        String supplementary = supplementarySnapshot(employeeId, yymm);

        long id = raise(employeeId, month.atDay(1), "IT-MDP-" + System.nanoTime());

        assertThat(event(id).get("EVENT_STATUS")).isEqualTo("COMPUTED");
        BigDecimal due =
                pending.compute(employeeId, month).lines().stream()
                        .filter(l -> l.payCode() == 101)
                        .findFirst()
                        .orElseThrow()
                        .amount();
        List<Map<String, Object>> details =
                jdbc.queryForList(
                        "SELECT d.component_id, d.original_amount, d.arrear_amount FROM"
                                + " arrear_detail d WHERE d.arrear_event_id = ? AND"
                                + " d.arrear_status = 'CALCULATED'",
                        id);
        assertThat(details)
                .as("the I-19 deduction is never recomputed into an arrear")
                .noneMatch(d -> ((Number) d.get("COMPONENT_ID")).longValue() == deduction);
        Map<String, Object> basicRow =
                details.stream()
                        .filter(d -> ((Number) d.get("COMPONENT_ID")).longValue() == basicComponent)
                        .findFirst()
                        .orElseThrow();
        assertThat((BigDecimal) basicRow.get("ORIGINAL_AMOUNT"))
                .as("what the locked result paid; the I-23 recovery is not netted in")
                .isEqualByComparingTo("1000");
        assertThat((BigDecimal) basicRow.get("ARREAR_AMOUNT"))
                .isEqualByComparingTo(due.subtract(new BigDecimal("1000")));
        assertThat(mdpSnapshot(employeeId, cycle))
                .as("the I-19 entry as it was")
                .isEqualTo(mdpRows);
        assertThat(supplementarySnapshot(employeeId, yymm))
                .as("the I-23 recovery stays a recovery")
                .isEqualTo(supplementary);
    }

    /** The member's approved I-19 deductions of the cycle with an amount of 250 (unchanged). */
    private long mdpSnapshot(long employeeId, long cycle) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM mdp_transaction WHERE employee_id = ? AND cycle_instance_id"
                        + " = ? AND mdp_status = 'APPROVED' AND amount = 250",
                Long.class,
                employeeId,
                cycle);
    }

    /** The member's I-23 rows of the month: type, amount and status. */
    private String supplementarySnapshot(long employeeId, String yymm) {
        return jdbc.queryForList(
                        "SELECT payment_type, amount, payment_status FROM supplementary_payment"
                                + " WHERE employee_id = ? AND for_period_yymm = ? ORDER BY"
                                + " payment_id",
                        employeeId,
                        yymm)
                .toString();
    }

    @Test
    void SRS_8_13_aMemberThatCannotBeComputedLeavesTheEventRaisedForTheMaker() throws Exception {
        long employeeId = member(6);
        // Before the demo rates begin (2026-04), inside the back-date limit (SRS-8-6): the month
        // cannot be computed - never a guess.
        YearMonth month = YearMonth.of(2026, 4).minusMonths(1);
        paid(employeeId, month, "1000");
        long id = raise(employeeId, month.atDay(1), "IT-" + System.nanoTime());
        assertThat(event(id).get("EVENT_STATUS")).isEqualTo("RAISED");
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult again =
                mvc.perform(userPost(maker, "/v1/arrear-events/" + id + "/computations", Map.of()))
                        .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(again).path("computed").asBoolean()).isFalse();
        assertThat(body(again).path("reason").asString()).isNotBlank();
        assertThat(event(id).get("EVENT_STATUS")).isEqualTo("RAISED");
        assertThat(
                        code(
                                mvc.perform(
                                                userPost(
                                                        maker,
                                                        "/v1/arrear-events/" + id + "/submissions",
                                                        Map.of("remark", "x")))
                                        .andReturn()))
                .as("only a computed event is submitted")
                .isEqualTo("ARR-409-STATE");
    }

    /** SRS-LV-10 / SRS-21-7: a back-dated leave approved through I-21 raises its arrear event. */
    // SC-13-0070 (SRS-21-8), SC-13-0074: a back-dated leave addition from the HRMS leave feed for a
    // locked period raises its Leave event through the I-21 task.
    @Test
    void SRS_21_7_aBackDatedLeaveApprovedThroughI21RaisesItsEvent() throws Exception {
        receipts.receiveType(
                "hrms.it", new LeaveReceipts.LeaveType("LWP", "Leave without pay", false, true));
        // Back-dated (before the open month), within the demo M-20 pay effects (from 2026-04-01;
        // a leave before them has no pay effect and raises nothing), outside the cycle ITs' months.
        LocalDate demo = LocalDate.of(2026, 4, 3);
        LocalDate sixBack = LocalDate.now(IST).minusMonths(6).withDayOfMonth(3);
        LocalDate day = sixBack.isAfter(demo) ? sixBack : demo;
        LeaveReceipts.Received received =
                receipts.receiveEvent(
                        "hrms.it",
                        new LeaveReceipts.Event(
                                "106118",
                                "LWP",
                                day,
                                day,
                                BigDecimal.ONE,
                                false,
                                "SAN/IT/" + System.nanoTime(),
                                "IT-LV-" + System.nanoTime(),
                                "DGM (P)",
                                LeaveReceipts.EventType.ADDITION,
                                null));
        assertThat(received.taskId()).isNotNull();
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
        List<Map<String, Object>> raised =
                jdbc.queryForList(
                        "SELECT e.event_status, t.event_code FROM arrear_event e JOIN"
                                + " arrear_event_type t ON t.event_type_id = e.event_type_id JOIN"
                                + " hrms_change_task k ON k.task_no = e.source_key WHERE"
                                + " e.source_module = 'LEAVE' AND k.hrms_change_task_id = ?",
                        received.taskId());
        assertThat(raised)
                .singleElement()
                .satisfies(r -> assertThat(r.get("EVENT_CODE")).isEqualTo("LEAVE"));
        assertThat(raised.getFirst().get("EVENT_STATUS")).isIn("COMPUTED", "RAISED");
    }

    /**
     * SRS-21-11: an approved HRMS change with pay effect and an earlier effective date raises its
     * event; one of the current month raises none.
     */
    @Test
    void SRS_21_11_aBackDatedHrmsPayChangeRaisesItsEventACurrentOneNone() throws Exception {
        long employeeId = member(7);
        BigDecimal basic =
                jdbc.queryForObject(
                        "SELECT current_basic FROM employee WHERE employee_id = ?",
                        BigDecimal.class,
                        employeeId);
        LocalDate today = LocalDate.now(IST);
        String backDated = applyBasic(employeeId, basic.add(BigDecimal.TEN), today.minusMonths(2));
        String current = applyBasic(employeeId, basic, today.withDayOfMonth(1));
        assertThat(raisedFor(backDated)).isEqualTo(1);
        assertThat(raisedFor(current)).isZero();
    }

    private String applyBasic(long employeeId, BigDecimal value, LocalDate effective)
            throws Exception {
        in.gov.fci.payroll.employee.api.HrmsChanges.Received received =
                changes.receive(
                        new in.gov.fci.payroll.employee.api.HrmsChanges.Change(
                                employeeId,
                                "BASIC_PAY",
                                value.toPlainString(),
                                effective,
                                "IT-HR-" + System.nanoTime(),
                                in.gov.fci.payroll.employee.api.HrmsChanges.Source.HRMS_PUSH));
        Map<String, Object> order = new HashMap<>();
        order.put("orderNo", "EST/2026/114");
        order.put("orderDate", LocalDate.now(IST).minusDays(3).toString());
        order.put("issuingAuthority", "General Manager (P), Delhi");
        order.put("heldInHrms", false);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        attachTaskOrder(maker, received.taskId());
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
                                "INCREMENT_DATE_BASIC_CORRECTION"))
                .isEqualTo(200);
        return jdbc.queryForObject(
                "SELECT task_no FROM hrms_change_task WHERE hrms_change_task_id = ?",
                String.class,
                received.taskId());
    }

    private int raisedFor(String taskNo) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM arrear_event WHERE source_module = 'HRMS_CHANGE' AND"
                                + " source_key = ?",
                        Integer.class,
                        taskNo);
        return n == null ? 0 : n;
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private static String nps(YearMonth month, String employeeAmount) {
        return "{\"payrollYymm\":\""
                + yymm(month)
                + "\",\"npsMember\":true,\"pran\":\"110012345678\",\"employeeAmount\":"
                + employeeAmount
                + ",\"employerAmount\":7000}";
    }

    /**
     * SRS-9-23 / SRS-G-45 (plan 18 phase 18d, plan 13 phase 13g): a back-dated NPS change of a
     * locked month comes through I-21; approved, the difference to what the month deducted is on
     * I-08 as a stated recovery, never recomputed; a month still open is refused at intake.
     */
    @Test
    void SRS_9_23_aBackDatedNpsChangeOfALockedMonthIsRecoveredThroughI08() throws Exception {
        long employeeId = member(8);
        YearMonth month = YearMonth.now(IST).plusYears(13).plusMonths(5);
        paid(employeeId, month, "1000");
        jdbc.update(
                "INSERT INTO nps_deduction (employee_id, payroll_yymm, record_type, hrms_nps_flag,"
                        + " employee_amount, employer_amount, deducted_amount, mismatch_status,"
                        + " apply_status, period_date) VALUES (?, ?, 'MONTHLY', 'Y', 5000, 7000,"
                        + " 5000, 'CONSISTENT', 'APPLIED', ?)",
                employeeId,
                yymm(month),
                java.sql.Date.valueOf(month.atDay(1)));
        in.gov.fci.payroll.employee.api.HrmsChanges.Received received =
                changes.receive(
                        new in.gov.fci.payroll.employee.api.HrmsChanges.Change(
                                employeeId,
                                "NPS_BACK_DATED",
                                nps(month, "5400"),
                                month.atDay(1),
                                "IT-NPS-" + System.nanoTime(),
                                in.gov.fci.payroll.employee.api.HrmsChanges.Source.HRMS_PUSH));
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
        Map<String, Object> stored =
                jdbc.queryForMap(
                        "SELECT arrear_event_id, hrms_change_task_id, deducted_amount FROM"
                                + " nps_deduction WHERE employee_id = ? AND payroll_yymm = ? AND"
                                + " record_type = 'BACK_DATED'",
                        employeeId,
                        yymm(month));
        assertThat(((Number) stored.get("HRMS_CHANGE_TASK_ID")).longValue())
                .isEqualTo(received.taskId());
        long id = ((Number) stored.get("ARREAR_EVENT_ID")).longValue();
        assertThat(event(id))
                .containsEntry("EVENT_STATUS", "COMPUTED")
                .containsEntry("SOURCE_MODULE", "NPS_BACKDATED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT arrear_amount FROM arrear_detail WHERE arrear_event_id = ?",
                                BigDecimal.class,
                                id))
                .as("400 more NPS than was deducted is recovered")
                .isEqualByComparingTo("-400");
        MvcResult again =
                mvc.perform(userPost(maker, "/v1/arrear-events/" + id + "/computations", Map.of()))
                        .andReturn();
        assertThat(code(again))
                .as("a stated arrear is never recomputed")
                .isEqualTo("ARR-409-STATE");

        YearMonth open = YearMonth.now(IST).plusYears(14);
        assertThatThrownBy(
                        () ->
                                changes.receive(
                                        new in.gov.fci.payroll.employee.api.HrmsChanges.Change(
                                                employeeId,
                                                "NPS_BACK_DATED",
                                                nps(open, "5400"),
                                                open.atDay(1),
                                                "IT-NPS-" + System.nanoTime(),
                                                in.gov.fci.payroll.employee.api.HrmsChanges.Source
                                                        .HRMS_PUSH)))
                .as("a month not locked takes the monthly record")
                .isInstanceOfSatisfying(
                        in.gov.fci.payroll.platform.error.FciException.class,
                        ex -> {
                            assertThat(ex.getMessage()).isEqualTo("NPS-422-RECEIPT");
                            assertThat(ex.getMessageArgs()).containsExactly("NOT_LOCKED");
                        });
    }

    private long activeCircular() {
        String number = "IT/RETRO/" + System.nanoTime();
        jdbc.update(
                "INSERT INTO document_attachment (entity_name, entity_key, file_name,"
                        + " storage_provider, storage_object_key, contains_pii, uploaded_by) VALUES"
                        + " ('circular_order', ?, 'it.pdf', 'ORACLE_SECUREFILE', ?, 'N', 'it')",
                number,
                "it/circular/" + number);
        jdbc.update(
                "INSERT INTO circular_order (circular_number, circular_date, subject,"
                        + " category_code, applies_all_offices, effective_from,"
                        + " document_attachment_id, circular_status, created_by) SELECT ?, ?,"
                        + " 'Old arrear (IT)', 'DA', 'Y', ?, attachment_id, 'ACTIVE', 'it.fixture'"
                        + " FROM document_attachment WHERE storage_object_key = ?",
                number,
                java.sql.Date.valueOf(LocalDate.now(IST)),
                java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)),
                "it/circular/" + number);
        return jdbc.queryForObject(
                "SELECT circular_order_id FROM circular_order WHERE circular_number = ?",
                Long.class,
                number);
    }

    // SC-13-0005 / SC-13-0025 / SC-13-0075: a back-dated change older than the HQ back-date
    // limit is held RAISED and computed only after a Headquarters exception with its order.
    @Test
    void SRS_8_6_anEventBeyondTheBackDateLimitIsComputedOnlyAsAHeadquartersException()
            throws Exception {
        long employeeId = member(7);
        LocalDate old =
                YearMonth.now(IST)
                        .minusMonths(parameters.maxRetroMonths().getAsLong() + 1L)
                        .atDay(1);
        long id = raise(employeeId, old, "IT-" + System.nanoTime());
        Map<String, Object> held =
                jdbc.queryForMap(
                        "SELECT event_status, beyond_window_flag, hq_exception_approval_id FROM"
                                + " arrear_event WHERE arrear_event_id = ?",
                        id);
        assertThat(held.get("EVENT_STATUS")).as("never computed on raise").isEqualTo("RAISED");
        assertThat(held.get("BEYOND_WINDOW_FLAG")).isEqualTo("Y");
        assertThat(held.get("HQ_EXCEPTION_APPROVAL_ID")).isNull();

        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        String computations = "/v1/arrear-events/" + id + "/computations";
        assertThat(code(mvc.perform(userPost(maker, computations, Map.of())).andReturn()))
                .isEqualTo("ARR-422-BEYOND-WINDOW");

        String exceptions = "/v1/arrear-events/" + id + "/hq-exceptions";
        assertThat(
                        mvc.perform(userPost(maker, exceptions, Map.of("reason", "Order of 2020")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("the order is required")
                .isEqualTo(400);
        long circular = activeCircular();
        MvcResult asked =
                mvc.perform(
                                userPost(
                                        maker,
                                        exceptions,
                                        Map.of("reason", "Order of 2020", "circularId", circular)))
                        .andReturn();
        assertThat(asked.getResponse().getStatus())
                .as(asked.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(asked).get("approvalId").asLong();
        JsonNode rows = body(mvc.perform(userGet(maker, "/v1/arrear-events?size=100")).andReturn());
        JsonNode row = null;
        for (JsonNode r : rows.path("items")) {
            if (r.path("id").asLong() == id) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("beyondWindow").asBoolean()).isTrue();
        assertThat(row.path("hqException").asString()).isEqualTo("PENDING");

        Session unitChecker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decide(maker, approval, "APPROVE", null, null))
                .as("the maker never approves")
                .isEqualTo(403);
        assertThat(decide(unitChecker, approval, "APPROVE", null, null))
                .as("Headquarters approves, not the unit")
                .isEqualTo(403);
        Session hq = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        assertThat(decide(hq, approval, "APPROVE", null, null)).isEqualTo(200);
        Map<String, Object> released =
                jdbc.queryForMap(
                        "SELECT hq_exception_approval_id, circular_order_id FROM arrear_event"
                                + " WHERE arrear_event_id = ?",
                        id);
        assertThat(((Number) released.get("HQ_EXCEPTION_APPROVAL_ID")).longValue())
                .isEqualTo(approval);
        assertThat(((Number) released.get("CIRCULAR_ORDER_ID")).longValue()).isEqualTo(circular);
        MvcResult again = mvc.perform(userPost(maker, computations, Map.of())).andReturn();
        assertThat(again.getResponse().getStatus())
                .as("past the limit once HQ approved: " + again.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    /**
     * SRS-8-20 (SC-13-0018): Basic and DA arrears of one month are worked out together in one event
     * - the Basic differential first, the DA on the revised Basic (the month recomputed as the
     * regular run computes it), both on the same arrear statement.
     */
    @Test
    void SRS_8_20_basicAndDaArrearsOfOneMonthAreComputedTogetherTheDaOnTheRevisedBasic()
            throws Exception {
        long employeeId = member(10);
        YearMonth month = YearMonth.now(IST).plusYears(17);
        paid(employeeId, month, "1000");
        jdbc.update(
                "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type,"
                        + " payroll_yymm, period_date) SELECT r.payroll_result_id,"
                        + " r.cycle_instance_id, r.org_unit_id, r.employee_id, c.component_id, 500,"
                        + " 'ACTUAL', r.payroll_yymm, r.period_date FROM payroll_result r,"
                        + " pay_component c WHERE r.employee_id = ? AND r.payroll_yymm = ? AND"
                        + " c.legacy_pay_code = 111",
                employeeId,
                yymm(month));
        long id = raise(employeeId, month.atDay(1), "IT-" + System.nanoTime());
        assertThat(event(id).get("EVENT_STATUS")).isEqualTo("COMPUTED");
        Map<Integer, BigDecimal> due = new HashMap<>();
        pending.compute(employeeId, month)
                .lines()
                .forEach(l -> due.merge(l.payCode(), l.amount(), BigDecimal::add));
        Map<Integer, Map<String, Object>> rows = new HashMap<>();
        for (Map<String, Object> r :
                jdbc.queryForList(
                        "SELECT c.legacy_pay_code, d.original_amount, d.recomputed_amount,"
                                + " d.arrear_amount FROM arrear_detail d JOIN pay_component c ON"
                                + " c.component_id = d.component_id WHERE d.arrear_event_id = ? AND"
                                + " d.differential_yymm = ? AND d.arrear_status = 'CALCULATED'",
                        id,
                        yymm(month))) {
            rows.put(((Number) r.get("LEGACY_PAY_CODE")).intValue(), r);
        }
        assertThat(rows).as("Basic and DA on one statement").containsKeys(101, 111);
        assertThat((BigDecimal) rows.get(101).get("ARREAR_AMOUNT"))
                .isEqualByComparingTo(due.get(101).subtract(new BigDecimal("1000")));
        assertThat((BigDecimal) rows.get(111).get("RECOMPUTED_AMOUNT"))
                .as("the DA of the revised Basic")
                .isEqualByComparingTo(due.get(111));
        assertThat((BigDecimal) rows.get(111).get("ARREAR_AMOUNT"))
                .isEqualByComparingTo(due.get(111).subtract(new BigDecimal("500")));
    }

    /** A DH001 member no demo user signs in as (its status may be changed for one test). */
    private long ownMember(int offset) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee e WHERE location_id = ? AND employment_status ="
                        + " 'IN_SERVICE' AND record_status = 'ACTIVE' AND employee_type = 'STAFF'"
                        + " AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.employee_id ="
                        + " e.employee_id) ORDER BY emp_num DESC OFFSET ? ROWS FETCH NEXT 1 ROWS"
                        + " ONLY",
                Long.class,
                locationId("DH001"),
                offset);
    }

    /** The member's posted accounts of the months: result and components, in a stable order. */
    private String postedAccounts(long employeeId, List<String> months) {
        StringBuilder s = new StringBuilder();
        for (String m : months) {
            s.append(
                    jdbc.queryForList(
                            "SELECT payroll_result_id, gross_earnings, total_deductions, net_pay,"
                                    + " result_status, run_type FROM payroll_result WHERE"
                                    + " employee_id = ? AND payroll_yymm = ? ORDER BY"
                                    + " payroll_result_id",
                            employeeId,
                            m));
            s.append(
                    jdbc.queryForList(
                            "SELECT result_component_id, component_id, amount FROM"
                                    + " payroll_result_component WHERE employee_id = ? AND"
                                    + " payroll_yymm = ? ORDER BY result_component_id",
                            employeeId,
                            m));
            s.append(
                    jdbc.queryForObject(
                            "SELECT cycle_status FROM payroll_cycle_instance WHERE payroll_yymm"
                                    + " = ? AND org_unit_id = ?",
                            String.class,
                            m,
                            locationId("DH001")));
        }
        return s.toString();
    }

    /**
     * SRS-8-8 / SRS-21-6, UC postconditions SC-13-0080 and SC-13-0091 (l.3207, l.3243): a
     * back-dated event recomputed, submitted, approved and merged leaves the closed months' posted
     * accounts exactly as they were (results, components, the cycle still LOCKED); the arrear is
     * posted to the next open cycle, never to a closed month (SC-13-0079, SC-13-0090).
     */
    @Test
    void SC_13_0080_SC_13_0091_aMergedBackDatedEventLeavesTheClosedMonthsPostedAccountsUnchanged()
            throws Exception {
        long employeeId = member(9);
        YearMonth first = YearMonth.now(IST).plusYears(15);
        paid(employeeId, first, "1000");
        paid(employeeId, first.plusMonths(1), "1000");
        List<String> closed = List.of(yymm(first), yymm(first.plusMonths(1)));
        String before = postedAccounts(employeeId, closed);
        long id = raise(employeeId, first.atDay(1), "IT-" + System.nanoTime());
        assertThat(event(id).get("EVENT_STATUS")).isEqualTo("COMPUTED");
        assertThat(postedAccounts(employeeId, closed))
                .as("computing the event touches no posted account")
                .isEqualTo(before);

        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult submitted =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/arrear-events/" + id + "/submissions",
                                        Map.of("remark", "Back-dated change (IT)")))
                        .andReturn();
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                body(submitted).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        Map<String, Object> merged =
                jdbc.queryForMap(
                        "SELECT event_status, posted_to_yymm FROM arrear_event WHERE"
                                + " arrear_event_id = ?",
                        id);
        assertThat(merged.get("EVENT_STATUS")).isEqualTo("APPROVED");
        assertThat((String) merged.get("POSTED_TO_YYMM"))
                .as("SRS-8-8: the next open cycle, never a closed month")
                .isNotNull()
                .isNotIn(closed);
        assertThat(postedAccounts(employeeId, closed))
                .as("SRS-8-8: never reopen a closed period's posted accounts")
                .isEqualTo(before);
    }

    /**
     * SRS-8-6 / SRS-8-2 (US-GAP-253): arrears include separated members, and a member who joined
     * inside the span is recomputed only for the locked months the member was paid - a month before
     * the joining carries no differential.
     */
    @Test
    void SRS_8_6_SRS_8_2_aSeparatedMemberIsIncludedAndAJoinerIsRecomputedOnlyForThePaidMonths()
            throws Exception {
        long employeeId = ownMember(3);
        YearMonth first = YearMonth.now(IST).plusYears(16);
        // Joined in the second month of the span: the first month paid nothing.
        paid(employeeId, first.plusMonths(1), "1000");
        paid(employeeId, first.plusMonths(2), "1000");
        String status =
                jdbc.queryForObject(
                        "SELECT employment_status FROM employee WHERE employee_id = ?",
                        String.class,
                        employeeId);
        jdbc.update(
                "UPDATE employee SET employment_status = 'SEPARATED' WHERE employee_id = ?",
                employeeId);
        try {
            long id = raise(employeeId, first.atDay(1), "IT-" + System.nanoTime());
            assertThat(event(id).get("EVENT_STATUS"))
                    .as("a separated member is still recomputed (SRS-8-6)")
                    .isEqualTo("COMPUTED");
            List<String> months =
                    jdbc.queryForList(
                            "SELECT DISTINCT differential_yymm FROM arrear_detail WHERE"
                                    + " arrear_event_id = ? AND arrear_status = 'CALCULATED' ORDER"
                                    + " BY differential_yymm",
                            String.class,
                            id);
            assertThat(months)
                    .as("only the months the member was paid")
                    .containsExactly(yymm(first.plusMonths(1)), yymm(first.plusMonths(2)));
            assertThat(event(id).get("SCOPE_TO_YYMM")).isEqualTo(yymm(first.plusMonths(2)));
        } finally {
            jdbc.update(
                    "UPDATE employee SET employment_status = ? WHERE employee_id = ?",
                    status,
                    employeeId);
        }
    }

    /**
     * BR-LEAVE-003 / I-08 Leave event (SRS l.3217; US-GAP-244): an employee leave event comes only
     * from the HRMS feed through I-21 - I-08 has no route for a Maker to key one: the request is
     * refused and no event is created.
     */
    @Test
    void BR_LEAVE_003_aMakerKeyingAnEmployeeLeaveEventOnI08IsRefused() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        long events = jdbc.queryForObject("SELECT COUNT(*) FROM arrear_event", Long.class);
        Map<String, Object> keyed = new HashMap<>();
        keyed.put("eventCode", "LEAVE");
        keyed.put("eis", "106118");
        keyed.put("effectiveFrom", LocalDate.now(IST).minusMonths(2).toString());
        keyed.put("remark", "Leave keyed by the Maker (IT)");
        MvcResult refused = mvc.perform(userPost(maker, "/v1/arrear-events", keyed)).andReturn();
        // The drawn I-08 "New Retro Event" (OD-121) raises only a type M-16 lists; a keyed leave
        // event
        // is refused (400 / 409), never created.
        assertThat(refused.getResponse().getStatus())
                .as(refused.getResponse().getContentAsString())
                .isIn(400, 404, 405, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM arrear_event", Long.class))
                .isEqualTo(events);
    }

    /**
     * SRS-LV-18 (BMAD TR-047): paid leave (EL, no pay effect) of a locked month that HRMS converts
     * to LWP (full deduction) cancels the earlier sanction's transaction, records the LWP
     * back-dated and raises one LEAVE event, whose recompute of the locked month is a recovery: the
     * Basic differential is the Basic the month gives with the LWP day less the Basic it paid
     * (negative). A member of the Maharashtra Region RO-MH, a cycle unit no suite computes, linked
     * to no user, and a past month inside the demo M-20 versions (from 2026-04).
     */
    @Test
    void SRS_LV_18_paidLeaveConvertedToLwpInALockedMonthIsRecoveredThroughTheRetroEngine()
            throws Exception {
        receipts.receiveType(
                "hrms.it", new LeaveReceipts.LeaveType("EL", "Earned Leave", true, false));
        receipts.receiveType(
                "hrms.it", new LeaveReceipts.LeaveType("LWP", "Leave without pay", false, true));
        long unit = locationId("RO-MH");
        Map<String, Object> who =
                jdbc.queryForMap(
                        "SELECT e.employee_id, e.emp_num FROM employee e WHERE e.location_id = ?"
                                + " AND e.employment_status = 'IN_SERVICE' AND e.record_status ="
                                + " 'ACTIVE' AND e.employee_type = 'STAFF' AND NOT EXISTS (SELECT 1"
                                + " FROM app_user u WHERE u.employee_id = e.employee_id) ORDER BY"
                                + " e.emp_num FETCH FIRST 1 ROWS ONLY",
                        unit);
        long employeeId = ((Number) who.get("EMPLOYEE_ID")).longValue();
        String eis = (String) who.get("EMP_NUM");
        YearMonth demoStart = YearMonth.of(2026, 4);
        YearMonth back = YearMonth.now(IST).minusMonths(2);
        YearMonth month = back.isBefore(demoStart) ? demoStart : back;
        LocalDate day = month.atDay(10);

        BigDecimal paidBasic = basic(employeeId, month);
        lockedResult(unit, employeeId, month, paidBasic);

        String elRef = "SAN/IT/EL/" + System.nanoTime();
        approveLeave(
                new LeaveReceipts.Event(
                        eis,
                        "EL",
                        day,
                        day,
                        BigDecimal.ONE,
                        false,
                        elRef,
                        "IT-LV-" + System.nanoTime(),
                        "DGM (P)",
                        LeaveReceipts.EventType.ADDITION,
                        null));
        long conversionTask =
                approveLeave(
                        new LeaveReceipts.Event(
                                eis,
                                "LWP",
                                day,
                                day,
                                BigDecimal.ONE,
                                false,
                                "SAN/IT/LWP/" + System.nanoTime(),
                                "IT-LV-" + System.nanoTime(),
                                "DGM (P)",
                                LeaveReceipts.EventType.CONVERSION,
                                elRef));

        List<Map<String, Object>> txns =
                jdbc.queryForList(
                        "SELECT leave_type_code, leave_event_type, wage_effect_code, is_back_dated"
                                + " FROM leave_transaction WHERE employee_id = ? AND"
                                + " hrms_change_task_id = ? ORDER BY leave_txn_id",
                        employeeId,
                        conversionTask);
        assertThat(txns)
                .as("the earlier EL cancelled, the LWP recorded back-dated")
                .anySatisfy(
                        t ->
                                assertThat(t)
                                        .containsEntry("LEAVE_TYPE_CODE", "EL")
                                        .containsEntry("LEAVE_EVENT_TYPE", "CANCELLATION"))
                .anySatisfy(
                        t ->
                                assertThat(t)
                                        .containsEntry("LEAVE_TYPE_CODE", "LWP")
                                        .containsEntry("WAGE_EFFECT_CODE", "FULL_DEDUCTION")
                                        .containsEntry("IS_BACK_DATED", "Y"));
        String taskNo =
                jdbc.queryForObject(
                        "SELECT task_no FROM hrms_change_task WHERE hrms_change_task_id = ?",
                        String.class,
                        conversionTask);
        Map<String, Object> event =
                jdbc.queryForMap(
                        "SELECT arrear_event_id, event_status, source_module FROM arrear_event"
                                + " WHERE source_module = 'LEAVE' AND source_key = ?",
                        taskNo);
        assertThat(event.get("EVENT_STATUS")).isEqualTo("COMPUTED");
        BigDecimal dueBasic = basic(employeeId, month);
        assertThat(dueBasic).as("the LWP day reduces the month's Basic").isLessThan(paidBasic);
        Map<String, Object> detail =
                jdbc.queryForMap(
                        "SELECT d.original_amount, d.recomputed_amount, d.arrear_amount FROM"
                                + " arrear_detail d JOIN pay_component c ON c.component_id ="
                                + " d.component_id WHERE d.arrear_event_id = ? AND"
                                + " c.legacy_pay_code = 101 AND d.differential_yymm = ? AND"
                                + " d.arrear_status = 'CALCULATED'",
                        ((Number) event.get("ARREAR_EVENT_ID")).longValue(),
                        yymm(month));
        assertThat((BigDecimal) detail.get("ORIGINAL_AMOUNT")).isEqualByComparingTo(paidBasic);
        assertThat((BigDecimal) detail.get("RECOMPUTED_AMOUNT")).isEqualByComparingTo(dueBasic);
        assertThat((BigDecimal) detail.get("ARREAR_AMOUNT"))
                .as("a recovery: negative, exactly the overpaid Basic")
                .isNegative()
                .isEqualByComparingTo(dueBasic.subtract(paidBasic));
    }

    /** The Basic (pay code 101) the engine gives a member for a month, as it stands now. */
    private BigDecimal basic(long employeeId, YearMonth month) {
        return pending.compute(employeeId, month).lines().stream()
                .filter(l -> l.payCode() == 101)
                .findFirst()
                .orElseThrow()
                .amount();
    }

    /** A LOCKED cycle of the month at a Regional Office whose ACTUAL result paid the Basic. */
    private void lockedResult(long unit, long employeeId, YearMonth month, BigDecimal basic) {
        String yymm = yymm(month);
        String find =
                "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?";
        if (jdbc.queryForList(find, Long.class, yymm, unit).isEmpty()) {
            int start = month.getMonthValue() >= 4 ? month.getYear() : month.getYear() - 1;
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                            + " fin_year, cycle_status, trigger_type, initiated_by) VALUES (?, ?,"
                            + " 'RO', ?, 'LOCKED', 'ON_DEMAND', 'it.fixture')",
                    yymm,
                    unit,
                    start + "-" + String.format("%02d", (start + 1) % 100));
        }
        long cycle = jdbc.queryForList(find, Long.class, yymm, unit).getFirst();
        java.sql.Date first = java.sql.Date.valueOf(month.atDay(1));
        long result =
                in.gov.fci.payroll.platform.persistence.GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                                + " payroll_type, payroll_yymm, period_date, run_type,"
                                + " gross_earnings, total_deductions, net_pay, result_status)"
                                + " VALUES (?, ?, ?, 'STAFF', ?, ?, 'ACTUAL', ?, 0, ?, 'CURRENT')",
                        "payroll_result_id",
                        cycle,
                        unit,
                        employeeId,
                        yymm,
                        first,
                        basic,
                        basic);
        jdbc.update(
                "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type,"
                        + " payroll_yymm, period_date) SELECT ?, ?, ?, ?, component_id, ?,"
                        + " 'ACTUAL', ?, ? FROM pay_component WHERE legacy_pay_code = 101",
                result,
                cycle,
                unit,
                employeeId,
                basic,
                yymm,
                first);
    }

    /** Receives a leave event and approves its I-21 task at RO-MH; the task id. */
    private long approveLeave(LeaveReceipts.Event e) throws Exception {
        LeaveReceipts.Received received = receipts.receiveEvent("hrms.it", e);
        assertThat(received.taskId()).as(String.valueOf(received.outcome())).isNotNull();
        Map<String, Object> order = new HashMap<>();
        order.put("heldInHrms", true);
        MvcResult submitted =
                mvc.perform(
                                userPost(
                                        login("pm.romh@fci-demo.test", "PAYROLL_MAKER", "RO-MH"),
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
                                login("pc.romh@fci-demo.test", "PAYROLL_CHECKER", "RO-MH"),
                                body(submitted).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        return received.taskId();
    }

    /**
     * SRS-8-6, R-RULE, KB CF-49 (CLR-147, OD-97): outside the demo data (UAT, production) the
     * back-date limit is unset - migration V077_41 takes the unsourced baseline out of force - so a
     * back-dated event and a C-13 period before the current one are refused "not configured", while
     * the current period still runs; the demo seed V077_42 restores the demo value. Both scripts
     * run here exactly as Flyway runs them, in a transaction that is rolled back.
     */
    @Test
    void SRS_8_6_withoutTheDemoSeedTheBackDateLimitIsNotConfiguredAndBackDatedWorkIsRefused() {
        java.nio.file.Path migration =
                java.nio.file.Path.of(
                        "..",
                        "db",
                        "migration",
                        "common",
                        "V077_41__a07_max_retro_period_unseeded.sql");
        java.nio.file.Path seed =
                java.nio.file.Path.of("..", "db", "seed", "V077_42__demo_max_retro_period.sql");
        long demoValue = parameters.maxRetroMonths().getAsLong();
        long employeeId = member(8);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(
                status -> {
                    try {
                        script(migration);
                        parameters.evict();
                        assertThat(parameters.maxRetroMonths()).as("UAT / production").isEmpty();
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM rule_version v JOIN"
                                                        + " rule_definition d ON d.rule_id ="
                                                        + " v.rule_id WHERE d.rule_key ="
                                                        + " 'FCI_PERIOD_FOR_PAYROLL_PROCESS' AND"
                                                        + " v.created_by = 'deploy.seed' AND"
                                                        + " v.approval_state = 'APPROVED' AND"
                                                        + " v.effective_to IS NULL",
                                                Long.class))
                                .as("CLR-148: the unsourced baseline 5 is in force no more")
                                .isZero();
                        assertThat(parameters.definitions())
                                .as("still defined, with its bounds, for A-07")
                                .anyMatch(d -> d.code().equals("MAX_RETRO_PERIOD"));
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM rule_version v JOIN"
                                                        + " rule_definition d ON d.rule_id ="
                                                        + " v.rule_id WHERE d.rule_key ="
                                                        + " 'MAX_RETRO_PERIOD' AND"
                                                        + " v.approval_state = 'REJECTED' AND"
                                                        + " v.is_blocked = 'Y'",
                                                Long.class))
                                .as("the baseline is kept in the history (R-DEL)")
                                .isEqualTo(1L);
                        LocalDate backDated = YearMonth.now(IST).minusMonths(2).atDay(1);
                        assertThatThrownBy(
                                        () ->
                                                retro.raise(
                                                        new RetroEvents.Raise(
                                                                employeeId,
                                                                backDated,
                                                                "OTHER",
                                                                "IT",
                                                                "it_record",
                                                                "IT-NC-" + System.nanoTime(),
                                                                "Back-dated change (IT)",
                                                                "it.fixture")))
                                .isInstanceOfSatisfying(
                                        in.gov.fci.payroll.platform.error.FciException.class,
                                        e ->
                                                assertThat(e.getErrorCode())
                                                        .isEqualTo(
                                                                in.gov.fci.payroll.arrear.business
                                                                        .ArrearErrorCode
                                                                        .NOT_CONFIGURED));
                        YearMonth current = jobs.currentPeriod();
                        String unit = String.valueOf(locationId("DH001"));
                        assertThat(
                                        jobs.validate(
                                                in.gov.fci.payroll.jobs.business.model.JobType
                                                        .PAYROLL_COMPUTATION,
                                                Map.of(
                                                        "locationId",
                                                        unit,
                                                        "payPeriod",
                                                        current.toString())))
                                .as("the current period needs no back-date limit")
                                .containsEntry("payPeriod", current.toString());
                        assertThatThrownBy(
                                        () ->
                                                jobs.validate(
                                                        in.gov.fci.payroll.jobs.business.model
                                                                .JobType.PAYROLL_COMPUTATION,
                                                        Map.of(
                                                                "locationId",
                                                                unit,
                                                                "payPeriod",
                                                                current.minusMonths(1).toString())))
                                .isInstanceOfSatisfying(
                                        in.gov.fci.payroll.platform.error.FciException.class,
                                        e ->
                                                assertThat(e.getErrorCode())
                                                        .isEqualTo(
                                                                in.gov.fci.payroll.platform.error
                                                                        .ApiErrorCode
                                                                        .RULE_NOT_CONFIGURED));

                        script(seed);
                        parameters.evict();
                        assertThat(parameters.maxRetroMonths())
                                .as("the demo data set restores its value")
                                .hasValue(demoValue);
                    } finally {
                        status.setRollbackOnly();
                    }
                });
        assertThat(parameters.maxRetroMonths()).hasValue(demoValue);
    }

    /** Runs a repository SQL script on the transaction's connection. */
    private void script(java.nio.file.Path path) {
        jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Void>)
                        connection -> {
                            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                                    connection,
                                    new org.springframework.core.io.FileSystemResource(path));
                            return null;
                        });
    }
}
