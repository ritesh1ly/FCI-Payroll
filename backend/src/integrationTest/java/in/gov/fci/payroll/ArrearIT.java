package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import in.gov.fci.payroll.arrear.orchestration.ArrearUndo;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 13 phase 13a (plan docs/plan/13-arrears-retro-wage-revision.md): an arrear event computed
 * for a Delhi HQ (DH001) member is listed on I-08 with its differentials, submitted by the Payroll
 * Maker with a remark and approved by the unit's Payroll Checker (Approve &amp; Merge, SRS-8-14):
 * it posts into the next open cycle (SRS-8-8) and the member's pay of that month carries the arrear
 * under the component's arrear pay code (SRS-8-21); the maker never approves; an event approved but
 * not yet posted is undone under the controlled undo (SRS-21-6).
 */
class ArrearIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private PendingSalary pending;
    @Autowired private ArrearUndo undo;
    @Autowired private ApplicationEventPublisher events;

    /** A COMPUTED DA arrear event of DH001 with one member's differential (the job's output). */
    private long computedEvent(long employeeId, BigDecimal arrear) {
        YearMonth month = YearMonth.now(IST).minusMonths(3);
        String yymm = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        jdbc.update(
                "INSERT INTO arrear_event (event_type_id, event_reference, effective_from,"
                        + " effective_to, scope_payroll_type, scope_location_id, scope_from_yymm,"
                        + " scope_to_yymm, source_module, event_status, raised_by) SELECT"
                        + " event_type_id, ?, ?, ?, 'STAFF', ?, ?, ?, 'RATE_CHANGE', 'COMPUTED',"
                        + " 'it.arrear' FROM arrear_event_type WHERE event_code = 'DA_REVISION'",
                "IT DA arrear " + System.nanoTime(),
                java.sql.Date.valueOf(month.atDay(1)),
                java.sql.Date.valueOf(month.atEndOfMonth()),
                locationId("DH001"),
                yymm,
                yymm);
        long event =
                jdbc.queryForObject("SELECT MAX(arrear_event_id) FROM arrear_event", Long.class);
        jdbc.update(
                "INSERT INTO arrear_detail (arrear_event_id, employee_id, component_id,"
                        + " differential_yymm, original_amount, recomputed_amount, arrear_amount,"
                        + " period_date) SELECT ?, ?, component_id, ?, 10000, ?, ?, ? FROM"
                        + " pay_component WHERE legacy_pay_code = 111",
                event,
                employeeId,
                yymm,
                BigDecimal.valueOf(10000).add(arrear),
                arrear,
                java.sql.Date.valueOf(month.atDay(1)));
        return event;
    }

    /** The demo arrear pay code of DA (IT fixture; production leaves the list empty, D13-1). */
    private void daArrearCode() {
        Integer present =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pay_component WHERE legacy_pay_code = 9111",
                        Integer.class);
        if (present == null || present == 0) {
            jdbc.update(
                    "INSERT INTO pay_component (legacy_pay_code, component_name, component_type,"
                            + " payslip_group, source_code, taxable_flag, in_cpf_base_flag,"
                            + " effective_from) VALUES (9111, 'DA arrear (IT)', 'EARNING',"
                            + " 'ARREARS', 'CALC', 'Y', 'Y', DATE '2026-04-01')");
            jdbc.update(
                    "INSERT INTO reference_value (list_id, value_code, value_label_en, sort_order,"
                        + " numeric_attribute, effective_from, changed_by, change_reason) SELECT"
                        + " list_id, '111', 'DA arrear', 1, 9111, DATE '2026-04-01', 'it', 'IT"
                        + " fixture' FROM reference_list WHERE list_code = 'ARREAR_PAY_CODE'");
        }
    }

    private long member() {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND employment_status ="
                    + " 'IN_SERVICE' AND record_status = 'ACTIVE' ORDER BY emp_num OFFSET 3 ROWS"
                    + " FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"));
    }

    // SC-13-0024: UC-02 main flow (I-08). SC-13-0032: the arrear line in the next open cycle's
    // computation and in the Arrears Register.
    @Test
    void SRS_8_14_SRS_21_6_anEventIsSubmittedApprovedAndMergedIntoTheNextOpenCycle()
            throws Exception {
        daArrearCode();
        long employeeId = member();
        long event = computedEvent(employeeId, new BigDecimal("1200"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode listed = all(maker, "/v1/arrear-events?status=COMPUTED");
        assertThat(listed.findValuesAsString("reference"))
                .anyMatch(r -> r.startsWith("IT DA arrear"));
        JsonNode details =
                body(
                        mvc.perform(userGet(maker, "/v1/arrear-events/" + event + "/details"))
                                .andReturn());
        assertThat(details.path("items").get(0).path("arrear").decimalValue())
                .isEqualByComparingTo("1200");

        assertThat(
                        code(
                                post(
                                        maker,
                                        "/v1/arrear-events/" + event + "/submissions",
                                        Map.of("remark", " "))))
                .as("SRS-21-9: a remark is mandatory")
                .isEqualTo("ARR-422-REMARK");
        MvcResult submitted =
                post(
                        maker,
                        "/v1/arrear-events/" + event + "/submissions",
                        Map.of("remark", "DA order (IT)"));
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(submitted).get("approvalId").asLong();
        assertThat(
                        code(
                                post(
                                        maker,
                                        "/v1/arrear-events/" + event + "/submissions",
                                        Map.of("remark", "again"))))
                .isIn("WF-409-PENDING-BY", "ARR-409-STATE");
        assertThat(decide(maker, approval, "APPROVE", null, null))
                .as("never the maker")
                .isEqualTo(403);
        // E2E-08 (e2e l.166-167), SRS-8-14: nothing is merged into a cycle before the Checker
        // approves - no posting month, the differential still computed, not approved.
        Map<String, Object> waiting =
                jdbc.queryForMap(
                        "SELECT e.posted_to_yymm, d.arrear_status FROM arrear_event e JOIN"
                                + " arrear_detail d ON d.arrear_event_id = e.arrear_event_id"
                                + " WHERE e.arrear_event_id = ?",
                        event);
        assertThat(waiting.get("POSTED_TO_YYMM")).as("not merged before approval").isNull();
        assertThat(waiting.get("ARREAR_STATUS")).isNotIn("APPROVED", "POSTED");
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        Map<String, Object> merged =
                jdbc.queryForMap(
                        "SELECT event_status, posted_to_yymm FROM arrear_event WHERE"
                                + " arrear_event_id = ?",
                        event);
        assertThat(merged.get("EVENT_STATUS")).isEqualTo("APPROVED");
        String posted = (String) merged.get("POSTED_TO_YYMM");
        assertThat(posted).as("the next open cycle, never a closed one").isNotNull();
        // BMAD TR-031 (SRS-8-8): exactly the unit's open month - this month, or the next when this
        // month's cycle is LOCKED - and never a LOCKED cycle.
        YearMonth now = YearMonth.now(IST);
        java.util.function.Function<YearMonth, String> yymm =
                m -> String.format("%04d%02d", m.getYear(), m.getMonthValue());
        java.util.List<String> lockedNow =
                jdbc.queryForList(
                        "SELECT cycle_status FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                                + " org_unit_id = ? AND cycle_status = 'LOCKED'",
                        String.class,
                        yymm.apply(now),
                        locationId("DH001"));
        assertThat(posted).isEqualTo(yymm.apply(lockedNow.isEmpty() ? now : now.plusMonths(1)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = ?"
                                        + " AND org_unit_id = ? AND cycle_status = 'LOCKED'",
                                Integer.class,
                                posted,
                                locationId("DH001")))
                .as("never a LOCKED cycle")
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT arrear_status FROM arrear_detail WHERE arrear_event_id = ?",
                                String.class,
                                event))
                .isEqualTo("APPROVED");

        // SRS-8-21: the month's pay carries the arrear under DA's arrear pay code.
        YearMonth month =
                YearMonth.of(
                        Integer.parseInt(posted.substring(0, 4)),
                        Integer.parseInt(posted.substring(4)));
        PendingSalary.Result pay = pending.compute(employeeId, month);
        assertThat(pay.lines())
                .anySatisfy(
                        l -> {
                            assertThat(l.payCode()).isEqualTo(9111);
                            assertThat(l.amount()).isEqualByComparingTo("1200");
                        });

        // SRS-8-21 / SRS-G-13: the Arrears Register carries the event's row, member by member,
        // with its period, component, amount and status.
        String eis =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?",
                        String.class,
                        employeeId);
        String register =
                csv(
                        maker,
                        "/v1/arrear-events/register?format=CSV&status=APPROVED&eventCode=DA_REVISION");
        assertThat(register.lines().findFirst().orElseThrow())
                .contains("event")
                .contains("arrear_period")
                .contains("component")
                .contains("status");
        List<String> rows =
                register.lines()
                        .filter(l -> l.startsWith(event + ",") || l.startsWith("\"" + event + "\""))
                        .toList();
        assertThat(rows).as(register).hasSize(1);
        assertThat(rows.getFirst())
                .contains("DA_REVISION")
                .contains(eis)
                .contains(
                        YearMonth.now(IST)
                                .minusMonths(3)
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")))
                .contains("111 - ")
                .contains("1200")
                .contains("APPROVED");

        // SRS-21-6: approved, not posted: undone under the controlled undo.
        Long unit = locationId("DH001");
        Integer cycles =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                                + " org_unit_id = ?",
                        Integer.class,
                        posted,
                        unit);
        if (cycles == null || cycles == 0) {
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by) VALUES (?, ?, 'DO',"
                        + " '2026-27', 'INITIATED', 'ON_DEMAND', 'it.fixture')",
                    posted,
                    unit);
        }
        // SRS-21-6 (SC-13-0068, E2E-05): through the module-05 controlled undo - the Maker asks on
        // C-02, the unit's Checker verifies (never the Maker), the System Administrator authorises
        // with the OTP; the event is REVERSED, never deleted, and only once.
        assertThat(undo.find(String.valueOf(event))).as("approved, not posted").isPresent();
        String period = posted.substring(0, 4) + "-" + posted.substring(4);
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Map<String, Object> ask =
                Map.of(
                        "transactionEntity",
                        "arrear_event",
                        "transactionKey",
                        String.valueOf(event),
                        "reason",
                        "Wrong order (IT)");
        assertThat(
                        mvc.perform(
                                        userPost(
                                                checker,
                                                "/v1/cycles/" + period + "/undo-requests",
                                                ask))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("the Checker never asks")
                .isEqualTo(403);
        MvcResult asked =
                mvc.perform(userPost(maker, "/v1/cycles/" + period + "/undo-requests", ask))
                        .andReturn();
        assertThat(asked.getResponse().getStatus())
                .as(asked.getResponse().getContentAsString())
                .isEqualTo(202);
        long undoApproval = body(asked).get("approvalId").asLong();
        assertThat(decide(maker, undoApproval, "APPROVE", null, null))
                .as("SRS-G-1: the Maker never verifies the own request")
                .isEqualTo(403);
        assertThat(decide(checker, undoApproval, "APPROVE", null, null)).isEqualTo(200);
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        assertThat(decide(admin, undoApproval, "APPROVE", null, null))
                .as("SRS-G-21: the OTP first")
                .isEqualTo(428);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT event_status FROM arrear_event WHERE arrear_event_id = ?",
                                String.class,
                                event))
                .isEqualTo("APPROVED");
        assertThat(decide(admin, undoApproval, "APPROVE", null, "UNDO_TRANSACTION")).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT event_status FROM arrear_event WHERE arrear_event_id = ?",
                                String.class,
                                event))
                .isEqualTo("REVERSED");
        assertThat(undo.find(String.valueOf(event))).as("once").isEmpty();
        assertThat(undo.find("not-a-number")).isEmpty();
        assertThat(undo.find(null)).isEmpty();
        assertThat(undo.entity()).isEqualTo("arrear_event");
    }

    @Test
    void SRS_G_29_anotherOfficeNeverSeesOrSubmitsTheEvent() throws Exception {
        long event = computedEvent(member(), new BigDecimal("500"));
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(
                        mvc.perform(userGet(kolkata, "/v1/arrear-events/" + event + "/details"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(
                        post(
                                        kolkata,
                                        "/v1/arrear-events/" + event + "/submissions",
                                        Map.of("remark", "x"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        assertThat(
                        post(
                                        finance,
                                        "/v1/arrear-events/" + event + "/submissions",
                                        Map.of("remark", "x"))
                                .getResponse()
                                .getStatus())
                .as("only the Payroll Maker submits")
                .isEqualTo(403);
    }

    /**
     * SRS-8-11 / BR-ARR-HRA-002: the legacy manual-entry path for HRA arrears is retired. I-08 has
     * no route that takes a keyed arrear: an HRA differential sent to the register or to an event's
     * details is refused by the router and writes nothing; arrears come only from the arrear
     * mechanism (events raised by a rate change or a retrospective change).
     */
    @Test
    void SRS_8_11_noRouteTakesAManuallyKeyedHraArrear() throws Exception {
        long event = computedEvent(member(), new BigDecimal("500"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Long events = jdbc.queryForObject("SELECT COUNT(*) FROM arrear_event", Long.class);
        Long details = jdbc.queryForObject("SELECT COUNT(*) FROM arrear_detail", Long.class);
        Map<String, Object> hra =
                Map.of(
                        "eventCode",
                        "HRA_REVISION",
                        "eis",
                        "100041",
                        "payCode",
                        121,
                        "yymm",
                        "202607",
                        "amount",
                        new BigDecimal("1500"));
        // I-08 "New Retro Event" (Wireframes v6.1, OD-121) raises an event of a type M-16 lists
        // (ARREAR_MANUAL_EVENT_TYPE), never with keyed amounts: the HRA body is refused (400), the
        // details
        // have no create route (405), and nothing is stored.
        assertThat(post(maker, "/v1/arrear-events", hra).getResponse().getStatus())
                .as("no keyed HRA arrear at /v1/arrear-events")
                .isIn(400, 409);
        assertThat(
                        post(maker, "/v1/arrear-events/" + event + "/details", hra)
                                .getResponse()
                                .getStatus())
                .as("no keyed HRA arrear at the details")
                .isEqualTo(405);
        assertThat(
                        mvc.perform(
                                        userRequest(
                                                maker,
                                                put("/v1/arrear-events/" + event + "/details")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("a differential is never edited either")
                .isEqualTo(405);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM arrear_event", Long.class))
                .isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM arrear_detail", Long.class))
                .isEqualTo(details);
    }

    /**
     * SRS-8-17 (D13-2, D13-3): at the lock the paid differentials become POSTED; the part of the
     * recovery the net-pay floor held back is carried as a new APPROVED detail, so the event stays
     * APPROVED until it is recovered; an event with nothing left is POSTED.
     */
    @Test
    void SRS_8_17_theLockPostsThePaidDifferentialsAndCarriesTheUnrecoveredRest() throws Exception {
        Integer present =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pay_component WHERE legacy_pay_code = 9121",
                        Integer.class);
        if (present == null || present == 0) {
            jdbc.update(
                    "INSERT INTO pay_component (legacy_pay_code, component_name, component_type,"
                            + " payslip_group, source_code, taxable_flag, in_cpf_base_flag,"
                            + " effective_from) VALUES (9121, 'Arrear recovery (IT)', 'DEDUCTION',"
                            + " 'RECOVERIES', 'CALC', 'N', 'N', DATE '2026-04-01')");
            jdbc.update(
                    "INSERT INTO reference_value (list_id, value_code, value_label_en, sort_order,"
                            + " numeric_attribute, effective_from, changed_by, change_reason)"
                            + " SELECT list_id, 'RECOVERY', 'Arrear recovery', 2, 9121, DATE"
                            + " '2026-04-01', 'it', 'IT fixture' FROM reference_list WHERE"
                            + " list_code = 'ARREAR_PAY_CODE'");
        }
        long employeeId = member();
        YearMonth month = YearMonth.now(IST).plusYears(12);
        String yymm = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        long carried = computedEvent(employeeId, new BigDecimal("-500"));
        long settled = computedEvent(employeeId, new BigDecimal("1000"));
        // An HRA over-payment: totals are per component, so it is not netted against the DA arrear.
        jdbc.update(
                "UPDATE arrear_detail SET component_id = (SELECT component_id FROM pay_component"
                        + " WHERE legacy_pay_code = 121) WHERE arrear_event_id = ?",
                carried);
        jdbc.update(
                "UPDATE arrear_event SET event_status = 'APPROVED', posted_to_yymm = ? WHERE"
                        + " arrear_event_id IN (?, ?)",
                yymm,
                carried,
                settled);
        jdbc.update(
                "UPDATE arrear_detail SET arrear_status = 'APPROVED' WHERE arrear_event_id IN (?,"
                        + " ?)",
                carried,
                settled);
        long unit = locationId("DH001");
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by) VALUES (?, ?, 'DO',"
                        + " '2026-27', 'LOCKED', 'ON_DEMAND', 'it.fixture')",
                yymm,
                unit);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        yymm,
                        unit);
        java.sql.Date first = java.sql.Date.valueOf(month.atDay(1));
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, result_status) VALUES (?, ?, ?, 'STAFF', ?,"
                        + " ?, 'ACTUAL', 30000, 200, 29800, 'CURRENT')",
                cycle,
                unit,
                employeeId,
                yymm,
                first);
        long result =
                jdbc.queryForObject(
                        "SELECT MAX(payroll_result_id) FROM payroll_result WHERE employee_id = ?",
                        Long.class,
                        employeeId);
        jdbc.update(
                "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type,"
                        + " payroll_yymm, period_date) SELECT ?, ?, ?, ?, component_id, -200,"
                        + " 'ACTUAL', ?, ? FROM pay_component WHERE legacy_pay_code = 9121",
                result,
                cycle,
                unit,
                employeeId,
                yymm,
                first);

        events.publishEvent(new CycleLocked(cycle, unit, yymm, "it.fixture", Instant.now()));

        assertThat(
                        jdbc.queryForList(
                                "SELECT arrear_amount FROM arrear_detail WHERE arrear_event_id = ?"
                                        + " AND arrear_status = 'APPROVED'",
                                BigDecimal.class,
                                carried))
                .as("the 300 the floor held back is carried")
                .singleElement()
                .satisfies(a -> assertThat(a).isEqualByComparingTo("-300"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM arrear_detail WHERE arrear_event_id IN (?, ?)"
                                        + " AND arrear_status = 'POSTED'",
                                Integer.class,
                                carried,
                                settled))
                .isEqualTo(2);
        assertThat(eventStatus(carried)).isEqualTo("APPROVED");
        assertThat(eventStatus(settled)).isEqualTo("POSTED");
    }

    private String eventStatus(long event) {
        return jdbc.queryForObject(
                "SELECT event_status FROM arrear_event WHERE arrear_event_id = ?",
                String.class,
                event);
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }
}
