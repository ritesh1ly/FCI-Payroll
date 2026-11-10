package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.leave.api.LeaveReceipts;
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
import tools.jackson.databind.JsonNode;

/**
 * Module 11 (plan docs/plan/11-full-final-settlement.md): an approved separation from HRMS opens
 * one settlement case with its clearances (SRS-10-19, 10-27); the establishment no-dues moves it to
 * Clearances received; the computation refuses on partial data (SRS-10-28); the approval needs the
 * Checker's OTP and the release Finance's initiation and the Checker's OTP, after which the case
 * closes (SRS-10-19, 10-29); a Payroll Maker cannot release and the Administrator only views. The
 * member is a Delhi HQ (DH001) employee near the end of the list, separated on the last day of the
 * previous month.
 */
class SettlementIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private HrmsChanges intake;
    @Autowired private LeaveReceipts receipts;
    @Autowired private org.springframework.context.ApplicationEventPublisher events;
    @Autowired private in.gov.fci.payroll.arrear.api.RetroEvents retro;
    @Autowired private in.gov.fci.payroll.payroll.api.PayrollRecords payroll;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    private long separate(String eis, LocalDate day) throws Exception {
        long employeeId =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
        HrmsChanges.Received task =
                intake.receive(
                        new HrmsChanges.Change(
                                employeeId,
                                "SEPARATION",
                                "RETIREMENT",
                                day,
                                "IT-FNF-" + System.nanoTime(),
                                HrmsChanges.Source.HRMS_PUSH));
        Map<String, Object> order = new HashMap<>();
        order.put("heldInHrms", true);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult recorded =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/hrms-change-tasks/" + task.taskId() + "/submissions",
                                        order))
                        .andReturn();
        assertThat(recorded.getResponse().getStatus())
                .as(recorded.getResponse().getContentAsString())
                .isLessThan(300);
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                body(recorded).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        return employeeId;
    }

    /** The EIS numbers this run's fixture members took (879001, 879002, ...). */
    private static final java.util.concurrent.atomic.AtomicInteger FIXTURE_MEMBERS =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A member of this test alone: a copy (EIS 879nnn) of a Delhi HQ (DH001) staff member with an
     * opening Basic, in service, that no test paid, gave an arrear, a leave encashment, a loan, a
     * penalty, a PLI or a leave balance and that is no demo user. The settlement sums all of them,
     * so a shared member changes the net payable; separating a demo user's member would deactivate
     * the account other suites sign in with. The copy keeps the shared DH001 pool (about a dozen
     * members, also drawn on by LoanSanctionIT and others) whole: this class separates one member
     * per test (playbook 2026-11-04, small member pools in ITs).
     */
    private String member() {
        long template =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee e WHERE location_id = ? AND employee_type"
                            + " = 'STAFF' AND emp_lbr_flag = 'N' AND employment_status ="
                            + " 'IN_SERVICE' AND record_status = 'ACTIVE' AND absconding_flag = 'N'"
                            + " AND hold_flag = 'N' AND stop_payment_flag = 'N' AND separation_date"
                            + " IS NULL AND emp_num NOT LIKE '879%' AND EXISTS (SELECT 1 FROM"
                            + " employee_basic_history h WHERE h.employee_id = e.employee_id) AND"
                            + " NOT EXISTS (SELECT 1 FROM payroll_result r WHERE r.employee_id ="
                            + " e.employee_id) AND NOT EXISTS (SELECT 1 FROM leave_balance_snapshot"
                            + " b WHERE b.employee_id = e.employee_id) AND NOT EXISTS (SELECT 1"
                            + " FROM app_user u WHERE u.employee_id = e.employee_id) ORDER BY"
                            + " emp_num FETCH FIRST 1 ROWS ONLY",
                        Long.class, locationId("DH001"));
        String eis;
        do {
            eis = String.format("879%03d", FIXTURE_MEMBERS.incrementAndGet());
        } while (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM employee WHERE emp_num = ?", Integer.class, eis)
                > 0);
        String digits = eis.substring(2);
        jdbc.update(
                "INSERT INTO employee (emp_num, first_name, middle_name, last_name, birth_date,"
                        + " father_name, gender, comp_joining_date, employee_type, emp_lbr_flag,"
                        + " casual_labour_flag, pan_number, designation_id, grade_id, location_id,"
                        + " depot_location_id, department_id, cost_centre_id, band_id, pay_pattern,"
                        + " pay_pattern_source, provident_fund_type, cpf_code, cpf_code_origin,"
                        + " parent_zone_id, employment_status, record_status, retirement_date,"
                        + " is_stagnation_case, current_basic, mobile_number, email_address,"
                        + " bank_account_number, bank_branch_id, record_origin, hrms_employee_type,"
                        + " cda_staff_code) SELECT ?, 'Fixture', middle_name, 'Settlement',"
                        + " birth_date, father_name, gender, comp_joining_date, employee_type,"
                        + " emp_lbr_flag, casual_labour_flag, ?, designation_id, grade_id,"
                        + " location_id, depot_location_id, department_id, cost_centre_id, band_id,"
                        + " pay_pattern, pay_pattern_source, provident_fund_type, ?,"
                        + " cpf_code_origin, parent_zone_id, 'IN_SERVICE', 'ACTIVE',"
                        + " retirement_date, is_stagnation_case, current_basic, ?, ?, ?,"
                        + " bank_branch_id, record_origin, hrms_employee_type, cda_staff_code FROM"
                        + " employee WHERE employee_id = ?",
                eis,
                "FNFPT" + digits.substring(0, 4) + "S",
                "CPF/" + eis,
                "90000" + eis.substring(1),
                "fixture." + eis + "@fci-demo.test",
                "00000" + eis,
                template);
        long id = employee(eis);
        jdbc.update(
                "INSERT INTO employee_basic_history (employee_id, basic_amount, old_basic_amount,"
                    + " grade_pay, stagnation_amount, band_id, event_type, effective_from,"
                    + " event_sequence, effective_to, source_code, reason, changed_by) SELECT ?,"
                    + " basic_amount, old_basic_amount, grade_pay, stagnation_amount, band_id,"
                    + " event_type, effective_from, event_sequence, effective_to, source_code,"
                    + " reason, 'it.fixture' FROM employee_basic_history WHERE employee_id = ?",
                id,
                template);
        return eis;
    }

    private long employee(String eis) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
    }

    // BR-FNF-004: no case while the member is active; SRS-10-10: each step continues the case from
    // its recorded stage, a second event never re-initiates it.
    // SC-16-0075: the separation type and date from HRMS applied through the I-21 task.
    // SC-11-0032: UC-06 (O-05) main flow, separation to closed case.
    @Test
    void SRS_10_19_aSeparationOpensOneCaseThatIsApprovedWithOtpReleasedAndClosed()
            throws Exception {
        String eis = member();
        LocalDate day = YearMonth.now(IST).minusMonths(1).atEndOfMonth();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM settlement_case c JOIN employee e ON"
                                        + " e.employee_id = c.employee_id WHERE e.emp_num = ?",
                                Long.class,
                                eis))
                .as("BR-FNF-004: no settlement for an active member")
                .isZero();
        long employeeId = separate(eis, day);

        Map<String, Object> opened =
                jdbc.queryForMap(
                        "SELECT settlement_case_id, case_reference, case_stage, separation_type,"
                                + " benefits_held_flag FROM settlement_case WHERE employee_id = ?",
                        employeeId);
        long caseId = ((Number) opened.get("SETTLEMENT_CASE_ID")).longValue();
        assertThat((String) opened.get("CASE_REFERENCE")).startsWith("FNF-");
        assertThat(((Number) opened.get("CASE_STAGE")).intValue())
                .as("clearances raised")
                .isEqualTo(2);
        assertThat(opened.get("SEPARATION_TYPE")).isEqualTo("RETIREMENT");
        assertThat(opened.get("BENEFITS_HELD_FLAG")).as("no CPF final application").isEqualTo("Y");
        List<String> statuses =
                jdbc.queryForList(
                        "SELECT clearance_status FROM settlement_clearance WHERE settlement_case_id"
                                + " = ? ORDER BY clearance_type",
                        String.class,
                        caseId);
        assertThat(statuses).hasSize(5).containsOnly("RECEIVED", "RAISED");

        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode listed = all(maker, "/v1/settlement-cases?eis=" + eis);
        assertThat(listed.findValuesAsString("caseReference"))
                .contains((String) opened.get("CASE_REFERENCE"));
        JsonNode awaiting =
                body(mvc.perform(userGet(maker, "/v1/settlement-cases/awaiting")).andReturn());
        assertThat(awaiting.findValuesAsString("clearance")).contains("ESTABLISHMENT_NO_DUES");

        // SRS-10-14 (US-GAP-218): an outstanding clearance does not hold the computation (it
        // blocks only the approval): the Maker's computation reaches the sources, and stops at the
        // first that cannot answer (no encashable balance from HRMS yet, SRS-10-28).
        assertThat(code(post(maker, "/v1/settlement-cases/" + caseId + "/computations", Map.of())))
                .isEqualTo("FNF-422-SOURCE");
        assertThat(
                        status(
                                post(
                                        maker,
                                        "/v1/settlement-cases/" + caseId + "/no-dues",
                                        Map.of("dues", 0, "remark", "Nil"))))
                .as("only Establishment / HR answers the no-dues")
                .isEqualTo(403);
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        MvcResult noDues =
                post(
                        hr,
                        "/v1/settlement-cases/" + caseId + "/no-dues",
                        Map.of("dues", 500, "remark", "Licence fee of the quarters (IT)"));
        assertThat(status(noDues)).as(noDues.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(noDues).path("summary").path("stage").asString())
                .isEqualTo("CLEARANCES_RECEIVED");

        // SRS-10-28: a source that cannot answer (no encashable balance from HRMS) stops it.
        assertThat(code(post(maker, "/v1/settlement-cases/" + caseId + "/computations", Map.of())))
                .isEqualTo("FNF-422-SOURCE");

        // The sources answer: HRMS sends the encashable balance, and the regular run paid the
        // separation month (fixture result: Basic 50,000 + DA 25,000), so pending salary is nil
        // and leave encashment = 30 days x 75,000 / 30 (demo M-20 values, V046_3_9).
        receipts.receiveBalance(
                "hrms.it", new LeaveReceipts.Balance(eis, "EL", null, new BigDecimal("30"), day));
        paidMonth(employeeId, YearMonth.from(day));
        MvcResult computed =
                post(maker, "/v1/settlement-cases/" + caseId + "/computations", Map.of());
        assertThat(status(computed)).as(computed.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode settlement = body(computed);
        assertThat(settlement.path("summary").path("stage").asString()).isEqualTo("COMPUTED");
        assertThat(settlement.path("pendingSalary").decimalValue()).isZero();
        assertThat(settlement.path("leaveEncashment").decimalValue()).isEqualByComparingTo("75000");
        assertThat(settlement.path("summary").path("netPayable").decimalValue())
                .as("SRS-10-13: gratuity apart; the licence fee recovered from payroll dues")
                .isEqualByComparingTo("74500");

        // SRS-10-21 / 10-22: against gratuity only with a reason, the order and HQ approval; while
        // that approval waits, the settlement cannot be submitted; back to payroll dues it can.
        long recovery = settlement.path("recoveries").get(0).path("id").asLong();
        String sourcePath =
                "/v1/settlement-cases/" + caseId + "/recoveries/" + recovery + "/source";
        assertThat(code(source(maker, sourcePath, "GRATUITY", false))).isEqualTo("FNF-422-ENTRY");
        // SRS-10-21 / BR-FNF-011 (E2E-13): the CPF final payment is never a recovery source.
        for (String never : List.of("CPF_FINAL", "cpf_final", "gratuity", " ")) {
            assertThat(status(source(maker, sourcePath, never, false)))
                    .as("source %s", never)
                    .isEqualTo(400);
        }
        // SRS-G-25 / SRS-22-2: a user of another office neither sees nor acts on the case.
        Session other = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(
                        all(other, "/v1/settlement-cases?eis=" + eis)
                                .findValuesAsString("caseReference"))
                .doesNotContain((String) opened.get("CASE_REFERENCE"));
        assertThat(
                        status(
                                mvc.perform(userGet(other, "/v1/settlement-cases/" + caseId))
                                        .andReturn()))
                .isIn(403, 404);
        assertThat(
                        status(
                                post(
                                        other,
                                        "/v1/settlement-cases/" + caseId + "/computations",
                                        Map.of())))
                .isIn(403, 404);
        // SRS-10-3 (O-05 l.2937): no CPF final payment applied: no sanction reference shown.
        JsonNode viewed =
                body(mvc.perform(userGet(maker, "/v1/settlement-cases/" + caseId)).andReturn());
        assertThat(viewed.path("cpfSanctionNumber").isNull()).isTrue();
        assertThat(viewed.path("recoveries").get(0).path("hqApprovalStatus").isNull()).isTrue();
        // DR-3: the gratuity is not computed yet (11-service-history): nothing is recovered from
        // it.
        assertThat(code(source(maker, sourcePath, "GRATUITY", true)))
                .as("no recovery from a gratuity of 0")
                .isEqualTo("FNF-422-ENTRY");
        // Fixture: a computed gratuity smaller than the recovery, then one that covers it.
        String gratuity =
                "UPDATE final_settlement SET gratuity_amount = ? WHERE settlement_case_id = ?";
        jdbc.update(gratuity, new BigDecimal("499.99"), caseId);
        assertThat(code(source(maker, sourcePath, "GRATUITY", true)))
                .as("never beyond the gratuity")
                .isEqualTo("FNF-422-ENTRY");
        jdbc.update(gratuity, new BigDecimal("300000"), caseId);
        MvcResult toGratuity = source(maker, sourcePath, "GRATUITY", true);
        assertThat(status(toGratuity))
                .as(toGratuity.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(code(post(maker, "/v1/settlement-cases/" + caseId + "/submissions", Map.of())))
                .isEqualTo("FNF-409-APPROVAL");
        assertThat(status(source(maker, sourcePath, "PAYROLL_DUES", false))).isEqualTo(200);
        MvcResult submitted =
                post(maker, "/v1/settlement-cases/" + caseId + "/submissions", Map.of());
        assertThat(status(submitted))
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(submitted).get("approvalId").asLong();
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decide(maker, approval, "APPROVE", null, null))
                .as("the maker never approves")
                .isEqualTo(403);
        assertThat(decide(checker, approval, "APPROVE", null, null)).as("OTP first").isEqualTo(428);
        assertThat(decide(checker, approval, "APPROVE", null, "FNF_APPROVAL")).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settlement_status FROM final_settlement WHERE"
                                        + " settlement_case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo("APPROVED");

        assertThat(status(post(maker, "/v1/settlement-cases/" + caseId + "/releases", Map.of())))
                .as("Finance releases")
                .isEqualTo(403);
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        MvcResult released =
                post(finance, "/v1/settlement-cases/" + caseId + "/releases", Map.of());
        assertThat(status(released)).as(released.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(
                        decide(
                                checker,
                                body(released).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                "DISBURSEMENT_RELEASE"))
                .isEqualTo(200);
        Map<String, Object> closed =
                jdbc.queryForMap(
                        "SELECT c.case_stage, s.settlement_status FROM settlement_case c JOIN"
                            + " final_settlement s ON s.settlement_case_id = c.settlement_case_id"
                            + " WHERE c.settlement_case_id = ?",
                        caseId);
        assertThat(((Number) closed.get("CASE_STAGE")).intValue()).isEqualTo(8);
        assertThat(closed.get("SETTLEMENT_STATUS")).isEqualTo("PAID");
        // SRS-10-29: a closed case is read-only - no computation, no-dues or submission.
        assertThat(code(post(maker, "/v1/settlement-cases/" + caseId + "/computations", Map.of())))
                .isEqualTo("FNF-409-STAGE");
        assertThat(
                        code(
                                post(
                                        hr,
                                        "/v1/settlement-cases/" + caseId + "/no-dues",
                                        Map.of("dues", 0, "remark", "Nil"))))
                .isEqualTo("FNF-409-STAGE");
        assertThat(code(post(maker, "/v1/settlement-cases/" + caseId + "/submissions", Map.of())))
                .startsWith("FNF-409");

        Session admin = login("sysadmin@fci-demo.test", null, null);
        assertThat(
                        status(
                                mvc.perform(userGet(admin, "/v1/settlement-cases/" + caseId))
                                        .andReturn()))
                .as("the System Administrator views at Headquarters")
                .isIn(200, 403);
    }

    /**
     * SRS-10-6 / 10-23: a settlement whose recoveries exceed the dues is a net recovery: approved,
     * but its release first sends the further approval, and once given still waits for the credit
     * memo to APPS (module 23).
     */
    // SC-11-0067: a net-recovery case raises its credit memo after the further approval.
    @Test
    void SRS_10_6_aNetRecoveryNeedsTheFurtherApprovalAndTheCreditMemoBeforeRelease()
            throws Exception {
        String eis = member();
        LocalDate day = YearMonth.now(IST).minusMonths(1).atEndOfMonth();
        long employeeId = separate(eis, day);
        long caseId = caseOf(employeeId);
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        assertThat(
                        status(
                                post(
                                        hr,
                                        "/v1/settlement-cases/" + caseId + "/no-dues",
                                        Map.of("dues", 100000, "remark", "Arrears of rent (IT)"))))
                .isEqualTo(200);
        receipts.receiveBalance(
                "hrms.it", new LeaveReceipts.Balance(eis, "EL", null, new BigDecimal("30"), day));
        paidMonth(employeeId, YearMonth.from(day));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode computed =
                body(post(maker, "/v1/settlement-cases/" + caseId + "/computations", Map.of()));
        assertThat(computed.path("summary").path("netPayable").decimalValue())
                .isEqualByComparingTo("-25000");
        assertThat(computed.path("netRecovery").asBoolean())
                .as("flagged only once the further approval is given")
                .isFalse();
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        long approval =
                body(post(maker, "/v1/settlement-cases/" + caseId + "/submissions", Map.of()))
                        .get("approvalId")
                        .asLong();
        assertThat(decide(checker, approval, "APPROVE", null, "FNF_APPROVAL")).isEqualTo(200);
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        MvcResult first = post(finance, "/v1/settlement-cases/" + caseId + "/releases", Map.of());
        assertThat(status(first)).as(first.getResponse().getContentAsString()).isEqualTo(202);
        Long further = body(first).get("approvalId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT entity_name FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                further))
                .as("the further approval of the net recovery")
                .isEqualTo("final_settlement_net_recovery");
        assertThat(further).isNotNull();
        assertThat(
                        decide(
                                login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001"),
                                further,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(code(post(finance, "/v1/settlement-cases/" + caseId + "/releases", Map.of())))
                .as("the credit memo to APPS comes first")
                .isEqualTo("FNF-409-APPROVAL");
    }

    /**
     * SRS-10-14 (US-GAP-218, US-GAP-219) / 10-25: with a clearance outstanding the Maker computes
     * and submits; the Checker's approval is refused until the Checker records a named, reasoned
     * exclusion against the submitted settlement (never the Maker; only an outstanding clearance;
     * audited with the approval). Ex-gratia is entered at its letter's amount with the letter
     * attached and holds the submission until a different user approves it.
     */
    @Test
    void SRS_10_25_anExclusionIsTheCheckersAndAnExGratiaTakesTheCheckersApproval()
            throws Exception {
        String eis = member();
        LocalDate day = YearMonth.now(IST).minusMonths(1).atEndOfMonth();
        long employeeId = separate(eis, day);
        long caseId = caseOf(employeeId);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        String exclusions = "/v1/settlement-cases/" + caseId + "/exclusions";
        Map<String, Object> noDues =
                Map.of(
                        "clearanceType",
                        "ESTABLISHMENT_NO_DUES",
                        "reason",
                        "Quarters never allotted (IT)");
        assertThat(status(post(maker, exclusions, noDues)))
                .as("the Checker records the exclusion, not the Maker")
                .isEqualTo(403);
        assertThat(code(post(checker, exclusions, noDues)))
                .as("not before the settlement is submitted to the Checker")
                .isEqualTo("FNF-409-STAGE");
        assertThat(code(exGratia(maker, caseId, "0"))).isEqualTo("FNF-422-ENTRY");
        MvcResult entered = exGratia(maker, caseId, "15000");
        assertThat(status(entered)).as(entered.getResponse().getContentAsString()).isEqualTo(202);
        receipts.receiveBalance(
                "hrms.it", new LeaveReceipts.Balance(eis, "EL", null, new BigDecimal("30"), day));
        paidMonth(employeeId, YearMonth.from(day));
        assertThat(
                        status(
                                post(
                                        maker,
                                        "/v1/settlement-cases/" + caseId + "/computations",
                                        Map.of())))
                .isEqualTo(200);
        assertThat(code(post(maker, "/v1/settlement-cases/" + caseId + "/submissions", Map.of())))
                .as("the ex-gratia entry waits for its decision")
                .isEqualTo("FNF-409-APPROVAL");
        long entry = body(entered).get("approvalId").asLong();
        assertThat(decide(maker, entry, "APPROVE", null, null))
                .as("never its own maker")
                .isEqualTo(403);
        assertThat(decide(checker, entry, "APPROVE", null, null)).isEqualTo(200);
        JsonNode recomputed =
                body(post(maker, "/v1/settlement-cases/" + caseId + "/computations", Map.of()));
        assertThat(recomputed.path("exGratia").decimalValue()).isEqualByComparingTo("15000");
        assertThat(recomputed.path("summary").path("netPayable").decimalValue())
                .isEqualByComparingTo("90000");
        assertThat(recomputed.path("summary").path("awaiting").toString())
                .as("the establishment no-dues is still outstanding")
                .contains("ESTABLISHMENT_NO_DUES");
        assertThat(recomputed.path("awaitingChecker").asBoolean()).isFalse();

        // SRS-10-14: the submission goes ahead with the clearance outstanding ...
        MvcResult submitted =
                post(maker, "/v1/settlement-cases/" + caseId + "/submissions", Map.of());
        assertThat(status(submitted))
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(submitted).get("approvalId").asLong();
        assertThat(
                        body(mvc.perform(userGet(checker, "/v1/settlement-cases/" + caseId))
                                        .andReturn())
                                .path("awaitingChecker")
                                .asBoolean())
                .isTrue();
        // ... but the Checker's approval is refused while it is outstanding and not excluded.
        MvcResult held = decision(checker, approval);
        assertThat(status(held)).isEqualTo(409);
        assertThat(code(held)).isEqualTo("FNF-409-CLEARANCE");
        assertThat(body(held).path("args").toString()).contains("ESTABLISHMENT_NO_DUES");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .as("the refused decision is not recorded")
                .isEqualTo("PENDING_APPROVAL");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settlement_status FROM final_settlement WHERE"
                                        + " settlement_case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo("COMPUTED");
        assertThat(status(post(maker, exclusions, noDues))).as("never the Maker").isEqualTo(403);
        assertThat(
                        code(
                                post(
                                        checker,
                                        exclusions,
                                        Map.of("clearanceType", "LOANS_ADVANCES", "reason", "x"))))
                .as("only an outstanding clearance")
                .isEqualTo("FNF-422-ENTRY");
        assertThat(
                        code(
                                post(
                                        checker,
                                        exclusions,
                                        Map.of(
                                                "clearanceType",
                                                "ESTABLISHMENT_NO_DUES",
                                                "reason",
                                                " "))))
                .as("a named reason")
                .isEqualTo("FNF-422-ENTRY");
        long audited =
                count(
                        "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                + " 'settlement_clearance' AND field_name = 'clearance_status' AND"
                                + " new_value = 'EXCLUDED' AND approval_id = "
                                + approval);
        MvcResult excluded = post(checker, exclusions, noDues);
        assertThat(status(excluded)).as(excluded.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(excluded).path("summary").path("awaiting").size()).isZero();
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT clearance_status, exclusion_approval_id, remark FROM"
                                + " settlement_clearance WHERE settlement_case_id = ? AND"
                                + " clearance_type = 'ESTABLISHMENT_NO_DUES'",
                        caseId);
        assertThat(row.get("CLEARANCE_STATUS")).isEqualTo("EXCLUDED");
        assertThat(((Number) row.get("EXCLUSION_APPROVAL_ID")).longValue()).isEqualTo(approval);
        assertThat((String) row.get("REMARK")).contains("Quarters never allotted (IT)");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'settlement_clearance' AND field_name ="
                                        + " 'clearance_status' AND new_value = 'EXCLUDED' AND"
                                        + " approval_id = "
                                        + approval))
                .isEqualTo(audited + 1);
        assertThat(decide(checker, approval, "APPROVE", null, "FNF_APPROVAL")).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settlement_status FROM final_settlement WHERE"
                                        + " settlement_case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo("APPROVED");
    }

    /**
     * SRS-10-14 / SRS-10-27 (Wave C review, finding 4): the Checker excludes the outstanding
     * no-dues, Establishment / HR then answers it (nil) and the settlement is returned: the
     * clearance comes back Received with the answer's remark, not Raised, and the answer while it
     * was excluded is audited as an answer, not as a status change.
     */
    @Test
    void SRS_10_14_anAnswerWhileExcludedSurvivesTheReturn() throws Exception {
        String eis = member();
        LocalDate day = YearMonth.now(IST).minusMonths(1).atEndOfMonth();
        long employeeId = separate(eis, day);
        long caseId = caseOf(employeeId);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        receipts.receiveBalance(
                "hrms.it", new LeaveReceipts.Balance(eis, "EL", null, new BigDecimal("30"), day));
        paidMonth(employeeId, YearMonth.from(day));
        assertThat(
                        status(
                                post(
                                        maker,
                                        "/v1/settlement-cases/" + caseId + "/computations",
                                        Map.of())))
                .isEqualTo(200);
        long first =
                body(post(maker, "/v1/settlement-cases/" + caseId + "/submissions", Map.of()))
                        .get("approvalId")
                        .asLong();
        assertThat(
                        status(
                                post(
                                        checker,
                                        "/v1/settlement-cases/" + caseId + "/exclusions",
                                        Map.of(
                                                "clearanceType",
                                                "ESTABLISHMENT_NO_DUES",
                                                "reason",
                                                "Awaited from the estate office (IT)"))))
                .isEqualTo(200);
        assertThat(
                        status(
                                post(
                                        hr,
                                        "/v1/settlement-cases/" + caseId + "/no-dues",
                                        Map.of("dues", 0, "remark", "No quarters dues (IT)"))))
                .isEqualTo(200);
        Map<String, Object> excluded =
                jdbc.queryForMap(
                        "SELECT clearance_status, remark, answer_remark, received_at FROM"
                                + " settlement_clearance WHERE settlement_case_id = ? AND"
                                + " clearance_type = 'ESTABLISHMENT_NO_DUES'",
                        caseId);
        assertThat(excluded.get("CLEARANCE_STATUS")).isEqualTo("EXCLUDED");
        assertThat((String) excluded.get("REMARK")).contains("Awaited from the estate office");
        assertThat(excluded.get("ANSWER_REMARK")).isEqualTo("No quarters dues (IT)");
        assertThat(excluded.get("RECEIVED_AT")).isNotNull();
        String key =
                jdbc.queryForObject(
                                "SELECT case_reference FROM settlement_case WHERE"
                                        + " settlement_case_id = ?",
                                String.class,
                                caseId)
                        + "|ESTABLISHMENT_NO_DUES";
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_key = '"
                                        + key
                                        + "' AND field_name = 'clearance_status' AND old_value ="
                                        + " 'RAISED' AND new_value = 'RECEIVED'"))
                .as("no status change is claimed for a row that stayed excluded")
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_key = '"
                                        + key
                                        + "' AND field_name = 'answer_remark'"))
                .isEqualTo(1);

        assertThat(decide(checker, first, "RETURN", "Recheck (IT)", null)).isEqualTo(200);
        Map<String, Object> back =
                jdbc.queryForMap(
                        "SELECT clearance_status, remark, answer_remark, exclusion_approval_id FROM"
                                + " settlement_clearance WHERE settlement_case_id = ? AND"
                                + " clearance_type = 'ESTABLISHMENT_NO_DUES'",
                        caseId);
        assertThat(back.get("CLEARANCE_STATUS")).isEqualTo("RECEIVED");
        assertThat(back.get("REMARK")).isEqualTo("No quarters dues (IT)");
        assertThat(back.get("ANSWER_REMARK")).isNull();
        assertThat(back.get("EXCLUSION_APPROVAL_ID")).isNull();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'settlement_clearance' AND old_value = 'EXCLUDED' AND"
                                        + " new_value = 'RECEIVED' AND approval_id = "
                                        + first))
                .isEqualTo(1);
    }

    /**
     * SRS-10-14 / SRS-10-30 (Wave A review, O-05): an approval never stands for a settlement the
     * Checker did not see, and an exclusion falls with the approval it accompanied. Submitted, the
     * Checker excludes the outstanding no-dues and returns it: the clearance is outstanding again.
     * Resubmitted, dues itemised afterwards send it back to Initiated: it cannot be recomputed
     * while it waits, and the Checker's approval is refused before any OTP is asked; returned, it
     * is recomputed at the new net, resubmitted and approved.
     */
    @Test
    void SRS_10_30_anApprovalNeverStandsForASettlementTheCheckerDidNotSee() throws Exception {
        String eis = member();
        LocalDate day = YearMonth.now(IST).minusMonths(1).atEndOfMonth();
        long employeeId = separate(eis, day);
        long caseId = caseOf(employeeId);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        String computations = "/v1/settlement-cases/" + caseId + "/computations";
        String submissions = "/v1/settlement-cases/" + caseId + "/submissions";
        receipts.receiveBalance(
                "hrms.it", new LeaveReceipts.Balance(eis, "EL", null, new BigDecimal("30"), day));
        paidMonth(employeeId, YearMonth.from(day));
        assertThat(status(post(maker, computations, Map.of()))).isEqualTo(200);
        long first = body(post(maker, submissions, Map.of())).get("approvalId").asLong();

        // The Checker's exclusion, then a return: the clearance is outstanding again (audited).
        MvcResult excluded =
                post(
                        checker,
                        "/v1/settlement-cases/" + caseId + "/exclusions",
                        Map.of(
                                "clearanceType",
                                "ESTABLISHMENT_NO_DUES",
                                "reason",
                                "Quarters never allotted (IT)"));
        assertThat(status(excluded)).as(excluded.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(decide(checker, first, "RETURN", "Recheck the quarters (IT)", null))
                .isEqualTo(200);
        Map<String, Object> clearance =
                jdbc.queryForMap(
                        "SELECT clearance_status, exclusion_approval_id, remark FROM"
                                + " settlement_clearance WHERE settlement_case_id = ? AND"
                                + " clearance_type = 'ESTABLISHMENT_NO_DUES'",
                        caseId);
        assertThat(clearance.get("CLEARANCE_STATUS")).isEqualTo("RAISED");
        assertThat(clearance.get("EXCLUSION_APPROVAL_ID")).isNull();
        assertThat(clearance.get("REMARK")).isNull();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'settlement_clearance' AND old_value = 'EXCLUDED' AND"
                                        + " new_value = 'RAISED' AND approval_id = "
                                        + first))
                .isEqualTo(1);

        // Recomputed and resubmitted; the establishment then itemises dues.
        assertThat(status(post(maker, computations, Map.of()))).isEqualTo(200);
        long second = body(post(maker, submissions, Map.of())).get("approvalId").asLong();
        assertThat(
                        status(
                                post(
                                        hr,
                                        "/v1/settlement-cases/" + caseId + "/no-dues",
                                        Map.of("dues", 500, "remark", "Licence fee (IT)"))))
                .isEqualTo(200);
        assertThat(code(post(maker, computations, Map.of())))
                .as("never recomputed while it waits for the Checker")
                .isEqualTo("FNF-409-APPROVAL");
        long checkerId =
                jdbc.queryForObject(
                        "SELECT user_id FROM app_user WHERE login_id = ?",
                        Long.class,
                        checker.loginId());
        long challenges = count("SELECT COUNT(*) FROM otp_challenge WHERE user_id = " + checkerId);
        MvcResult changed = decision(checker, second);
        assertThat(status(changed))
                .as("refused before the OTP: the dues changed the settlement")
                .isEqualTo(409);
        // The establishment's answer sent the computed settlement back for recomputation
        // (INITIATED), so the approval is refused on the stage before any OTP.
        assertThat(code(changed)).isEqualTo("FNF-409-STAGE");
        assertThat(count("SELECT COUNT(*) FROM otp_challenge WHERE user_id = " + checkerId))
                .as("no OTP issued for a refused approval")
                .isEqualTo(challenges);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                second))
                .isEqualTo("PENDING_APPROVAL");

        // Returned, recomputed at the new net, resubmitted and approved.
        assertThat(decide(checker, second, "RETURN", "Dues itemised (IT)", null)).isEqualTo(200);
        JsonNode recomputed = body(post(maker, computations, Map.of()));
        assertThat(recomputed.path("summary").path("netPayable").decimalValue())
                .isEqualByComparingTo("74500");
        long third = body(post(maker, submissions, Map.of())).get("approvalId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                third))
                .contains("\"netPayable\":\"74500\"");
        assertThat(decide(checker, third, "APPROVE", null, "FNF_APPROVAL")).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settlement_status FROM final_settlement WHERE"
                                        + " settlement_case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo("APPROVED");
    }

    /**
     * UC-06 postconditions (SRS l.2915-2917): SC-11-0041 the paid case is closed, the member is
     * Separated and the records are read-only; SC-11-0042 (SRS-10-3, SRS-10-17) the CPF final
     * payment is linked to the case and shown for information only, never in the net payable;
     * SC-11-0043 (SRS-10-7, BR-FNF-006) the member's open loan is itemised, recovered in full and
     * closed on the loan ledger at posting - no balance stays outstanding after separation.
     */
    @Test
    void SC_11_0041_SC_11_0042_SC_11_0043_aPaidCaseClosesItsLoansShowsTheCpfFinalAndStaysReadOnly()
            throws Exception {
        String eis = member();
        long employeeId =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
        String run = Long.toString(System.nanoTime(), 36).toUpperCase();
        String loanNumber = "IT-F43-" + run;
        jdbc.update(
                "INSERT INTO loan_header (loan_number, employee_id, loan_type_id,"
                        + " sanctioned_amount, sanction_date, instalment_amount, loan_status,"
                        + " principal_outstanding, interest_outstanding) VALUES (?, ?, (SELECT"
                        + " MIN(loan_type_id) FROM loan_type_master), 50000, DATE '2025-04-01',"
                        + " 2000, 'ACTIVE', 12000, 300)",
                loanNumber,
                employeeId);
        long loanId =
                jdbc.queryForObject(
                        "SELECT loan_id FROM loan_header WHERE loan_number = ?",
                        Long.class,
                        loanNumber);
        String sanctionNumber = "IT-F42-" + run;
        jdbc.update(
                "INSERT INTO pf_sanction (system_sanction_number, employee_id, sanction_type,"
                        + " requested_amount, sanctioned_amount, sanction_date, workflow_stage)"
                        + " VALUES (?, ?, 'FINAL', 400000, 400000, DATE '2026-04-01', 'PAID')",
                sanctionNumber,
                employeeId);
        LocalDate day = YearMonth.now(IST).minusMonths(1).atEndOfMonth();
        separate(eis, day);
        long caseId = caseOf(employeeId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT employment_status FROM employee WHERE employee_id = ?",
                                String.class,
                                employeeId))
                .as("SC-11-0041: the member is marked Separated")
                .isEqualTo("SEPARATED");
        Map<String, Object> linked =
                jdbc.queryForMap(
                        "SELECT c.cpf_final_status_info, c.cpf_final_amount_info,"
                                + " c.benefits_held_flag, p.system_sanction_number FROM"
                                + " settlement_case c JOIN pf_sanction p ON p.sanction_id ="
                                + " c.cpf_sanction_id WHERE c.settlement_case_id = ?",
                        caseId);
        assertThat(linked.get("SYSTEM_SANCTION_NUMBER")).isEqualTo(sanctionNumber);
        assertThat(linked.get("CPF_FINAL_STATUS_INFO")).isEqualTo("PAID");
        assertThat((BigDecimal) linked.get("CPF_FINAL_AMOUNT_INFO")).isEqualByComparingTo("400000");
        assertThat(linked.get("BENEFITS_HELD_FLAG"))
                .as("BR-CPF-019: applied for, so the other benefits are not withheld")
                .isEqualTo("N");

        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        assertThat(
                        status(
                                post(
                                        hr,
                                        "/v1/settlement-cases/" + caseId + "/no-dues",
                                        Map.of("dues", 0, "remark", "Nil (IT)"))))
                .isEqualTo(200);
        receipts.receiveBalance(
                "hrms.it", new LeaveReceipts.Balance(eis, "EL", null, new BigDecimal("30"), day));
        paidMonth(employeeId, YearMonth.from(day));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult computedResult =
                post(maker, "/v1/settlement-cases/" + caseId + "/computations", Map.of());
        assertThat(status(computedResult))
                .as(computedResult.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode computed = body(computedResult);
        JsonNode loanLine = null;
        for (JsonNode r : computed.path("recoveries")) {
            if (r.path("reference").asString().contains(loanNumber)) {
                loanLine = r;
            }
        }
        assertThat(loanLine).as("SRS-10-4: the open loan is itemised").isNotNull();
        assertThat(loanLine.path("type").asString()).isIn("LOAN", "ADVANCE");
        assertThat(loanLine.path("amount").decimalValue())
                .as("principal and interest recovered in full")
                .isEqualByComparingTo("12300");
        assertThat(computed.path("summary").path("netPayable").decimalValue())
                .as("SRS-10-3: 75,000 encashment less the loan; the CPF final is never netted")
                .isEqualByComparingTo("62700");
        assertThat(computed.path("cpfSanctionNumber").asString()).isEqualTo(sanctionNumber);
        assertThat(computed.path("cpfAmount").decimalValue()).isEqualByComparingTo("400000");
        assertThat(computed.path("cpfStatus").asString()).isEqualTo("PAID");
        assertThat(computed.path("benefitsHeld").asBoolean()).isFalse();
        // SRS-SEC-4: the settlement view carries no PAN or bank account of the member.
        String pan =
                jdbc.queryForObject(
                        "SELECT pan_number FROM employee WHERE employee_id = ?",
                        String.class,
                        employeeId);
        if (pan != null && !pan.isBlank()) {
            assertThat(computedResult.getResponse().getContentAsString()).doesNotContain(pan);
        }

        long approval =
                body(post(maker, "/v1/settlement-cases/" + caseId + "/submissions", Map.of()))
                        .get("approvalId")
                        .asLong();
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decide(checker, approval, "APPROVE", null, "FNF_APPROVAL")).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT loan_status FROM loan_header WHERE loan_id = ?",
                                String.class,
                                loanId))
                .as("approval alone closes nothing: the ledger closes at posting")
                .isEqualTo("ACTIVE");
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        long release =
                body(post(finance, "/v1/settlement-cases/" + caseId + "/releases", Map.of()))
                        .get("approvalId")
                        .asLong();
        // SRS-10-19 / SRS-G-21 (BMAD TR-028): the release approval needs the Checker's OTP - asked
        // for, nothing released without it.
        assertThat(decide(checker, release, "APPROVE", null, null)).isEqualTo(428);
        Map<String, Object> waiting =
                jdbc.queryForMap(
                        "SELECT c.case_stage, s.settlement_status FROM settlement_case c JOIN"
                                + " final_settlement s ON s.settlement_case_id ="
                                + " c.settlement_case_id WHERE c.settlement_case_id = ?",
                        caseId);
        assertThat(waiting.get("SETTLEMENT_STATUS")).isEqualTo("APPROVED");
        assertThat(((Number) waiting.get("CASE_STAGE")).intValue()).isNotEqualTo(8);
        assertThat(decide(checker, release, "APPROVE", null, "DISBURSEMENT_RELEASE"))
                .isEqualTo(200);

        Map<String, Object> loan =
                jdbc.queryForMap(
                        "SELECT loan_status, principal_outstanding, interest_outstanding FROM"
                                + " loan_header WHERE loan_id = ?",
                        loanId);
        assertThat(loan.get("LOAN_STATUS")).isEqualTo("SETTLED_IN_FNF");
        assertThat((BigDecimal) loan.get("PRINCIPAL_OUTSTANDING")).isZero();
        assertThat((BigDecimal) loan.get("INTEREST_OUTSTANDING")).isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM loan_header WHERE employee_id = "
                                        + employeeId
                                        + " AND loan_status IN ('ACTIVE', 'STOPPED',"
                                        + " 'RESCHEDULED')"))
                .as("SC-11-0043: no loan or advance balance stays outstanding")
                .isZero();
        Map<String, Object> closed =
                jdbc.queryForMap(
                        "SELECT c.case_stage, c.closed_at, s.settlement_status FROM"
                                + " settlement_case c JOIN final_settlement s ON"
                                + " s.settlement_case_id = c.settlement_case_id WHERE"
                                + " c.settlement_case_id = ?",
                        caseId);
        assertThat(((Number) closed.get("CASE_STAGE")).intValue()).isEqualTo(8);
        assertThat(closed.get("CLOSED_AT")).isNotNull();
        assertThat(closed.get("SETTLEMENT_STATUS")).isEqualTo("PAID");
        // SC-11-0041: read-only after closing - every action of the case is refused.
        String base = "/v1/settlement-cases/" + caseId;
        assertThat(code(post(maker, base + "/computations", Map.of()))).isEqualTo("FNF-409-STAGE");
        assertThat(code(post(maker, base + "/submissions", Map.of()))).startsWith("FNF-409");
        assertThat(code(exGratia(maker, caseId, "1000"))).isEqualTo("FNF-409-STAGE");
        assertThat(code(post(finance, base + "/releases", Map.of())))
                .as("never released twice")
                .isEqualTo("FNF-409-STAGE");
        JsonNode viewed = body(mvc.perform(userGet(maker, base)).andReturn());
        assertThat(viewed.path("summary").path("status").asString()).isEqualTo("PAID");
        assertThat(viewed.path("cpfSanctionNumber").asString())
                .as("SC-11-0042: still shown on the closed case")
                .isEqualTo(sanctionNumber);
    }

    /**
     * SRS-10-19 / SRS-G-1 (BR-APPR-004, US-GAP-224): Finance initiates the release and the Payroll
     * Checker approves it; one user holding both roles never approves the release they initiated,
     * whatever role they sign in with (WF-403-SOD), and the item waits for another Checker.
     */
    @Test
    void SRS_10_19_oneUserWithFinanceAndCheckerRolesCannotApproveTheReleaseTheyInitiated()
            throws Exception {
        long caseId = approvedCase();
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        MvcResult released =
                post(finance, "/v1/settlement-cases/" + caseId + "/releases", Map.of());
        assertThat(status(released)).as(released.getResponse().getContentAsString()).isEqualTo(202);
        long release = body(released).get("approvalId").asLong();
        jdbc.update(
                "INSERT INTO app_user_access_group (user_id, access_group_id, effective_from,"
                        + " assigned_by, approval_id) SELECT u.user_id, g.access_group_id, ?,"
                        + " 'it.fixture.fnf', g.approval_id FROM app_user u, access_group g WHERE"
                        + " u.login_id = 'finance.hq@fci-demo.test' AND g.group_code ="
                        + " 'AG-PC-DH001'",
                java.sql.Date.valueOf(LocalDate.now(IST).minusDays(1)));
        try {
            Session both = login("finance.hq@fci-demo.test", "PAYROLL_CHECKER", "DH001");
            MvcResult refused = decision(both, release);
            assertThat(status(refused)).isEqualTo(403);
            assertThat(code(refused))
                    .as("refused on identity, not on the role")
                    .isEqualTo("WF-403-SOD");
        } finally {
            jdbc.update(
                    "UPDATE app_user_access_group SET revoked_at = CURRENT_TIMESTAMP, revoke_cause"
                            + " = 'ADMIN', revoke_reason = 'IT fixture ended' WHERE assigned_by ="
                            + " 'it.fixture.fnf' AND revoked_at IS NULL");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                release))
                .startsWith("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settlement_status FROM final_settlement WHERE"
                                        + " settlement_case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo("APPROVED");
        // SRS-G-21 (BMAD TR-028): the right code after its challenge expired releases nothing.
        Session ddo = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Map.Entry<MvcResult, Long> late =
                decideAfterOtpExpiry(ddo, release, "APPROVE", null, "DISBURSEMENT_RELEASE");
        assertThat(status(late.getKey())).isEqualTo(400);
        assertThat(code(late.getKey())).isEqualTo("AUTH-400-OTP");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settlement_status FROM final_settlement WHERE"
                                        + " settlement_case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo("APPROVED");
        assertThat(decide(ddo, release, "APPROVE", null, "DISBURSEMENT_RELEASE")).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settlement_status FROM final_settlement WHERE"
                                        + " settlement_case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo("PAID");
    }

    /**
     * SRS-10-19 / SRS-G-22 (R-OTP): the settlement approval answered with the right code after the
     * challenge expired is refused (AUTH-400-OTP), the challenge is EXPIRED and the settlement
     * stays computed; a fresh code then approves it.
     */
    @Test
    void FNF_APPROVAL_withAnExpiredOtpIsRefusedAndLeavesTheSettlementComputed() throws Exception {
        String eis = member();
        LocalDate day = YearMonth.now(IST).minusMonths(1).atEndOfMonth();
        long employeeId = separate(eis, day);
        long caseId = caseOf(employeeId);
        long approval = submittedCase(eis, employeeId, caseId, day);
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Map.Entry<MvcResult, Long> late =
                decideAfterOtpExpiry(checker, approval, "APPROVE", null, "FNF_APPROVAL");
        assertThat(status(late.getKey())).isEqualTo(400);
        assertThat(code(late.getKey())).isEqualTo("AUTH-400-OTP");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM otp_challenge WHERE otp_challenge_id = ?",
                                String.class,
                                late.getValue()))
                .isEqualTo("EXPIRED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settlement_status FROM final_settlement WHERE"
                                        + " settlement_case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo("COMPUTED");
        assertThat(decide(checker, approval, "APPROVE", null, "FNF_APPROVAL")).isEqualTo(200);
    }

    /**
     * SRS-10-22 / SRS-10-24 (BMAD TR-029): a recovery against gratuity, with its reason and order,
     * waits for the Headquarters Checker (route O05_GRATUITY_HQ) - the unit's Checker and the Maker
     * are refused - and holds the submission until approved; the confirmed source restates the
     * settlement: the recovery leaves the payroll net payable whole and is taken from gratuity, so
     * the statement reconciles to the payroll net payable plus the gratuity net of the recovery.
     */
    @Test
    void SRS_10_22_SRS_10_24_aGratuityRecoveryIsApprovedAtHeadquartersAndReducesGratuityOnly()
            throws Exception {
        String eis = member();
        LocalDate day = YearMonth.now(IST).minusMonths(1).atEndOfMonth();
        long employeeId = separate(eis, day);
        long caseId = caseOf(employeeId);
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        assertThat(
                        status(
                                post(
                                        hr,
                                        "/v1/settlement-cases/" + caseId + "/no-dues",
                                        Map.of("dues", 500, "remark", "Licence fee (IT)"))))
                .isEqualTo(200);
        receipts.receiveBalance(
                "hrms.it", new LeaveReceipts.Balance(eis, "EL", null, new BigDecimal("30"), day));
        paidMonth(employeeId, YearMonth.from(day));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode computed =
                body(post(maker, "/v1/settlement-cases/" + caseId + "/computations", Map.of()));
        assertThat(computed.path("summary").path("netPayable").decimalValue())
                .isEqualByComparingTo("74500");
        long recovery = computed.path("recoveries").get(0).path("id").asLong();
        // Fixture: the gratuity as computed once the qualifying service arrives
        // (11-service-history).
        jdbc.update(
                "UPDATE final_settlement SET gratuity_amount = 300000 WHERE settlement_case_id = ?",
                caseId);
        MvcResult chosen =
                source(
                        maker,
                        "/v1/settlement-cases/" + caseId + "/recoveries/" + recovery + "/source",
                        "GRATUITY",
                        true);
        assertThat(status(chosen)).as(chosen.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode restated =
                body(mvc.perform(userGet(maker, "/v1/settlement-cases/" + caseId)).andReturn());
        assertThat(restated.path("summary").path("netPayable").decimalValue())
                .as("SRS-10-24: the payroll net payable is not reduced by a gratuity recovery")
                .isEqualByComparingTo("75000");
        assertThat(restated.path("recoveredFromGratuity").decimalValue())
                .isEqualByComparingTo("500");
        assertThat(restated.path("totalRecoveries").decimalValue()).isEqualByComparingTo("500");
        assertThat(restated.path("gratuity").decimalValue()).isEqualByComparingTo("300000");
        assertThat(code(post(maker, "/v1/settlement-cases/" + caseId + "/submissions", Map.of())))
                .as("SRS-10-22: held until Headquarters approves")
                .isEqualTo("FNF-409-APPROVAL");

        long hqApproval =
                jdbc.queryForObject(
                        "SELECT hq_approval_id FROM fs_recovery_detail WHERE recovery_detail_id ="
                                + " ?",
                        Long.class,
                        recovery);
        assertThat(decide(maker, hqApproval, "APPROVE", null, null))
                .as("never its own maker")
                .isEqualTo(403);
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                hqApproval,
                                "APPROVE",
                                null,
                                null))
                .as("the unit's Checker does not hold the Headquarters authority")
                .isEqualTo(403);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                hqApproval))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(
                        decide(
                                login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001"),
                                hqApproval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        MvcResult submitted =
                post(maker, "/v1/settlement-cases/" + caseId + "/submissions", Map.of());
        assertThat(status(submitted))
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                body(submitted).get("approvalId").asLong()))
                .as("the Checker approves the restated net")
                .contains("\"netPayable\":\"75000\"");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'final_settlement' AND field_name = 'net_payable' AND"
                                        + " new_value = '75000'"))
                .as("R-AUD: the restatement is audited")
                .isPositive();
    }

    /**
     * SRS-LV-19 (BMAD TR-044): a leave recovery is recovered in the next open cycle within the
     * net-pay floor; the balance is carried forward as an outstanding recovery and appears in the
     * Full & Final Settlement. A LEAVE event of 4,000 approved into month N: the floor lets 2,000
     * through in N (2,000 carried), 1,500 in N+1 (500 carried). N and N+1 are far ahead, so no
     * other suite computes them.
     *
     * <p>SC-13-0029 (UC-02 E2, SRS-8-16): the arrear of a member who separated in the interval is
     * routed to the settlement case. After the separation, a back-dated change recomputes the
     * locked separation month the member was paid in (the retro engine includes a separated member,
     * SRS-8-6); the member is in no later cycle, and the settlement computation carries the event's
     * differentials beside the 500 still to recover. One member for both (member(): the Delhi pool
     * of members no other test touched is small).
     */
    @Test
    void SRS_LV_19_SC_13_0029_aCarriedLeaveRecoveryAndALaterArrearAreSettledInTheCase()
            throws Exception {
        recoveryPayCode();
        String eis = member();
        long employeeId =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
        YearMonth n = YearMonth.now(IST).plusYears(19);
        long event =
                in.gov.fci.payroll.platform.persistence.GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO arrear_event (event_type_id, event_reference, effective_from,"
                                + " scope_payroll_type, scope_location_id, scope_from_yymm,"
                                + " source_module, source_entity, source_key, event_status,"
                                + " posted_to_yymm, raised_by) SELECT event_type_id, ?, ?, 'STAFF',"
                                + " ?, ?, 'LEAVE', 'leave_transaction', ?, 'APPROVED', ?,"
                                + " 'it.fixture' FROM arrear_event_type WHERE event_code ="
                                + " 'LEAVE'",
                        "arrear_event_id",
                        "LEAVE IT recovery " + System.nanoTime(),
                        java.sql.Date.valueOf(YearMonth.now(IST).minusMonths(3).atDay(1)),
                        locationId("DH001"),
                        yymm(YearMonth.now(IST).minusMonths(3)),
                        "HCA-IT-" + System.nanoTime(),
                        yymm(n));
        jdbc.update(
                "INSERT INTO arrear_detail (arrear_event_id, employee_id, component_id,"
                        + " differential_yymm, original_amount, recomputed_amount, arrear_amount,"
                        + " arrear_status, period_date) SELECT ?, ?, component_id, ?, 30000, 26000,"
                        + " -4000, 'APPROVED', ? FROM pay_component WHERE legacy_pay_code = 101",
                event,
                employeeId,
                yymm(YearMonth.now(IST).minusMonths(3)),
                java.sql.Date.valueOf(YearMonth.now(IST).minusMonths(3).atDay(1)));

        lockedWithRecovery(employeeId, n, "2000");
        assertThat(outstanding(event))
                .as("cycle N: 2,000 of 4,000 taken, 2,000 carried")
                .containsExactly(new BigDecimal("-2000.00"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT event_status FROM arrear_event WHERE arrear_event_id = ?",
                                String.class,
                                event))
                .isEqualTo("APPROVED");

        lockedWithRecovery(employeeId, n.plusMonths(1), "1500");
        assertThat(outstanding(event))
                .as("cycle N+1: 1,500 of the 2,000 taken, 500 carried")
                .containsExactly(new BigDecimal("-500.00"));

        jdbc.update(
                "UPDATE payroll_result SET result_status = 'SUPERSEDED' WHERE employee_id = ? AND"
                        + " payroll_yymm IN (?, ?) AND is_locked = 'N'",
                employeeId,
                yymm(n),
                yymm(n.plusMonths(1)));

        // The member, paid for the separation month, separates; then a back-dated change arrives.
        LocalDate day = YearMonth.now(IST).minusMonths(1).atEndOfMonth();
        YearMonth paid = YearMonth.from(day);
        paidMonth(employeeId, paid);
        separate(eis, day);
        long caseId = caseOf(employeeId);
        Long later =
                new org.springframework.transaction.support.TransactionTemplate(transactions)
                        .execute(
                                st ->
                                        retro.raise(
                                                new in.gov.fci.payroll.arrear.api.RetroEvents.Raise(
                                                        employeeId,
                                                        paid.atDay(1),
                                                        "OTHER",
                                                        "IT",
                                                        "it_record",
                                                        "IT-SEP-" + System.nanoTime(),
                                                        "Back-dated change after separation (IT)",
                                                        "it.fixture")));
        assertThat(later).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT event_status FROM arrear_event WHERE arrear_event_id = ?",
                                String.class,
                                later))
                .as("SC-13-0029: the separated member is recomputed")
                .isEqualTo("COMPUTED");
        BigDecimal differential =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(arrear_amount), 0) FROM arrear_detail WHERE"
                                + " arrear_event_id = ? AND arrear_status = 'CALCULATED'",
                        BigDecimal.class,
                        later);
        assertThat(differential).as("the locked month paid less than it gives").isNotZero();
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        assertThat(
                        status(
                                post(
                                        hr,
                                        "/v1/settlement-cases/" + caseId + "/no-dues",
                                        Map.of("dues", 0, "remark", "Nil (IT)"))))
                .isEqualTo(200);
        receipts.receiveBalance(
                "hrms.it", new LeaveReceipts.Balance(eis, "EL", null, new BigDecimal("30"), day));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        // SRS-8-16, SRS-10-28, A-08: the arrear still awaits its decision on I-08: neither paid
        // unapproved nor left out, so the computation stops.
        assertThat(code(post(maker, "/v1/settlement-cases/" + caseId + "/computations", Map.of())))
                .isEqualTo("FNF-422-SOURCE");
        jdbc.update(
                "UPDATE arrear_detail SET arrear_status = 'APPROVED' WHERE arrear_event_id = ? AND"
                        + " arrear_status = 'CALCULATED'",
                later);
        MvcResult computed =
                post(maker, "/v1/settlement-cases/" + caseId + "/computations", Map.of());
        assertThat(status(computed)).as(computed.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode settlement = body(computed);
        BigDecimal arrears = differential.add(new BigDecimal("-500"));
        assertThat(arrears).isEqualByComparingTo(outstandingArrears(employeeId));
        assertThat(settlement.path("arrears").decimalValue())
                .as("the 500 leave recovery still outstanding and the later arrear, in the case")
                .isEqualByComparingTo(arrears);
        assertThat(settlement.path("summary").path("netPayable").decimalValue())
                .as("both reach the net payable")
                .isEqualByComparingTo(
                        settlement
                                .path("pendingSalary")
                                .decimalValue()
                                .add(settlement.path("leaveEncashment").decimalValue())
                                .add(settlement.path("exGratia").decimalValue())
                                .subtract(settlement.path("totalRecoveries").decimalValue())
                                .add(arrears));
    }

    /**
     * O-05 as drawn (API-11-2): the tiles and the payments for every drawn role; order copies
     * attached by the Maker alone, within the office; a hand-made upload refused for a wrong role,
     * an office out of scope and a file of another case (SRS-G-30, SRS-G-31, R-RBAC).
     */
    @Test
    void SRS_G_30_theOrderCopyOfACaseIsTheMakersWithinTheOffice() throws Exception {
        String eis = member();
        LocalDate day = YearMonth.now(IST).minusMonths(1).atEndOfMonth();
        long caseId = caseOf(separate(eis, day));
        String base = "/v1/settlement-cases/" + caseId;
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        for (Session s : List.of(maker, checker, hr)) {
            assertThat(status(mvc.perform(userGet(s, "/v1/settlement-cases/summary")).andReturn()))
                    .isEqualTo(200);
            assertThat(status(mvc.perform(userGet(s, "/v1/settlement-cases/invoices")).andReturn()))
                    .isEqualTo(200);
            assertThat(status(mvc.perform(userGet(s, base + "/orders")).andReturn()))
                    .isEqualTo(200);
        }
        for (Session s : List.of(checker, hr)) {
            assertThat(status(order(s, base, "FCI/IT/1")))
                    .as("only the Payroll Maker attaches")
                    .isEqualTo(403);
        }
        MvcResult stored = order(maker, base, "FCI/IT/1");
        assertThat(status(stored)).as(stored.getResponse().getContentAsString()).isEqualTo(201);
        long fileId = body(stored).path("id").asLong();
        assertThat(
                        body(mvc.perform(userGet(maker, base + "/orders")).andReturn())
                                .findValuesAsString("referenceNumber"))
                .contains("FCI/IT/1");
        assertThat(status(mvc.perform(userGet(checker, base + "/files/" + fileId)).andReturn()))
                .isEqualTo(200);
        assertThat(status(mvc.perform(userGet(maker, base + "/files/999999999")).andReturn()))
                .as("SRS-G-31: an attachment of no record of this case")
                .isEqualTo(404);
        Session other = login("pm.ropb@fci-demo.test", "PAYROLL_MAKER", "RO-PB");
        assertThat(status(mvc.perform(userGet(other, base + "/orders")).andReturn()))
                .as("another office's case is not found")
                .isEqualTo(404);
        assertThat(status(mvc.perform(userGet(other, base + "/files/" + fileId)).andReturn()))
                .isEqualTo(404);
        assertThat(status(order(other, base, "FCI/IT/2")))
                .as("a Maker out of scope attaches nothing")
                .isEqualTo(404);
    }

    private MvcResult order(Session s, String base, String reference) throws Exception {
        var request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                                base + "/orders")
                        .file(
                                new org.springframework.mock.web.MockMultipartFile(
                                        "file", "letter.pdf", "application/pdf", PDF))
                        .param("documentType", "SANCTION_ORDER")
                        .param("reference", reference)
                        .param("orderDate", LocalDate.now(IST).minusDays(1).toString())
                        .param("issuingAuthority", "RO Delhi");
        return mvc.perform(
                        userRequest(s, request)
                                .header("Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andReturn();
    }

    /** What the settlement sums as the member's arrears (approved, not yet paid). */
    private BigDecimal outstandingArrears(long employeeId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(arrear_amount), 0) FROM arrear_detail WHERE employee_id = ?"
                        + " AND arrear_status = 'APPROVED'",
                BigDecimal.class,
                employeeId);
    }

    /** The APPROVED (still to recover) details of an event. */
    private List<BigDecimal> outstanding(long event) {
        return jdbc
                .queryForList(
                        "SELECT arrear_amount FROM arrear_detail WHERE arrear_event_id = ? AND"
                                + " arrear_status = 'APPROVED' ORDER BY arrear_detail_id",
                        BigDecimal.class,
                        event)
                .stream()
                .map(a -> a.setScale(2, java.math.RoundingMode.UNNECESSARY))
                .toList();
    }

    /**
     * A LOCKED DH001 cycle of the month whose ACTUAL result recovered the given amount under the
     * arrear recovery pay code (the floor let only that much through), then its lock event.
     */
    private void lockedWithRecovery(long employeeId, YearMonth month, String recovered) {
        long unit = locationId("DH001");
        String yymm = yymm(month);
        long cycle =
                in.gov.fci.payroll.platform.persistence.GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id,"
                                + " org_unit_level, fin_year, cycle_status, trigger_type,"
                                + " initiated_by) VALUES (?, ?, 'DO', ?, 'LOCKED', 'ON_DEMAND',"
                                + " 'it.fixture')",
                        "cycle_instance_id",
                        yymm,
                        unit,
                        finYear(month));
        java.sql.Date first = java.sql.Date.valueOf(month.atDay(1));
        long result =
                in.gov.fci.payroll.platform.persistence.GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                                + " payroll_type, payroll_yymm, period_date, run_type,"
                                + " gross_earnings, total_deductions, net_pay, result_status)"
                                + " VALUES (?, ?, ?, 'STAFF', ?, ?, 'ACTUAL', 30000, ?, ?,"
                                + " 'CURRENT')",
                        "payroll_result_id",
                        cycle,
                        unit,
                        employeeId,
                        yymm,
                        first,
                        new BigDecimal(recovered),
                        new BigDecimal("30000").subtract(new BigDecimal(recovered)));
        jdbc.update(
                "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type,"
                        + " payroll_yymm, period_date) SELECT ?, ?, ?, ?, component_id, ?,"
                        + " 'ACTUAL', ?, ? FROM pay_component WHERE legacy_pay_code = 9121",
                result,
                cycle,
                unit,
                employeeId,
                new BigDecimal(recovered).negate(),
                yymm,
                first);
        events.publishEvent(
                new in.gov.fci.payroll.payroll.api.CycleLocked(
                        cycle, unit, yymm, "it.fixture", java.time.Instant.now()));
    }

    /** The arrear recovery pay code (M-16 ARREAR_PAY_CODE RECOVERY), as ArrearIT sets it. */
    private void recoveryPayCode() {
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
        }
        Integer value =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reference_value v JOIN reference_list l ON"
                                + " l.list_id = v.list_id WHERE l.list_code = 'ARREAR_PAY_CODE'"
                                + " AND v.value_code = 'RECOVERY'",
                        Integer.class);
        if (value == null || value == 0) {
            jdbc.update(
                    "INSERT INTO reference_value (list_id, value_code, value_label_en, sort_order,"
                            + " numeric_attribute, effective_from, changed_by, change_reason)"
                            + " SELECT list_id, 'RECOVERY', 'Arrear recovery', 2, 9121, DATE"
                            + " '2026-04-01', 'it', 'IT fixture' FROM reference_list WHERE"
                            + " list_code = 'ARREAR_PAY_CODE'");
        }
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private static String finYear(YearMonth m) {
        int start = m.getMonthValue() >= 4 ? m.getYear() : m.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** A separated member's case computed and submitted to the Checker; the approval id. */
    private long submittedCase(String eis, long employeeId, long caseId, LocalDate day)
            throws Exception {
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        assertThat(
                        status(
                                post(
                                        hr,
                                        "/v1/settlement-cases/" + caseId + "/no-dues",
                                        Map.of("dues", 0, "remark", "Nil (IT)"))))
                .isEqualTo(200);
        receipts.receiveBalance(
                "hrms.it", new LeaveReceipts.Balance(eis, "EL", null, new BigDecimal("30"), day));
        paidMonth(employeeId, YearMonth.from(day));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        status(
                                post(
                                        maker,
                                        "/v1/settlement-cases/" + caseId + "/computations",
                                        Map.of())))
                .isEqualTo(200);
        MvcResult submitted =
                post(maker, "/v1/settlement-cases/" + caseId + "/submissions", Map.of());
        assertThat(status(submitted))
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        return body(submitted).get("approvalId").asLong();
    }

    /** A separated member's case approved by the Checker (OTP); the case id. */
    private long approvedCase() throws Exception {
        String eis = member();
        LocalDate day = YearMonth.now(IST).minusMonths(1).atEndOfMonth();
        long employeeId = separate(eis, day);
        long caseId = caseOf(employeeId);
        long approval = submittedCase(eis, employeeId, caseId, day);
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                approval,
                                "APPROVE",
                                null,
                                "FNF_APPROVAL"))
                .isEqualTo(200);
        return caseId;
    }

    /** An APPROVE decision sent without an OTP: a refusal checked before the OTP answers here. */
    private MvcResult decision(Session s, long approvalId) throws Exception {
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/approvals/" + approvalId + "/decision",
                                Map.of("decision", "APPROVE")))
                .andReturn();
    }

    private long caseOf(long employeeId) {
        return jdbc.queryForObject(
                "SELECT settlement_case_id FROM settlement_case WHERE employee_id = ?",
                Long.class,
                employeeId);
    }

    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<< >>\nendobj\n"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private MvcResult source(Session s, String path, String source, boolean withOrder)
            throws Exception {
        var request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                                org.springframework.http.HttpMethod.PUT, path)
                        .param("source", source);
        if (withOrder) {
            request.param("reason", "Dues above the payroll dues (IT)")
                    .param("orderRef", "HQ/FNF/IT")
                    .param("orderDate", LocalDate.now(IST).minusDays(1).toString())
                    .param("issuingAuthority", "General Manager (Region), Delhi")
                    .file(
                            new org.springframework.mock.web.MockMultipartFile(
                                    "order", "order.pdf", "application/pdf", PDF));
        } else if ("GRATUITY".equals(source)) {
            request.param("reason", "No order (IT)");
        }
        return mvc.perform(
                        userRequest(s, request)
                                .header("Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andReturn();
    }

    private MvcResult exGratia(Session s, long caseId, String amount) throws Exception {
        var request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                                "/v1/settlement-cases/" + caseId + "/ex-gratia")
                        .file(
                                new org.springframework.mock.web.MockMultipartFile(
                                        "letter", "letter.pdf", "application/pdf", PDF))
                        .param("letterNo", "EXG/IT/" + System.nanoTime())
                        .param("letterDate", LocalDate.now(IST).minusDays(2).toString())
                        .param("amount", amount)
                        .param("issuingAuthority", "Executive Director (P), HQ");
        return mvc.perform(
                        userRequest(s, request)
                                .header("Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andReturn();
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    /** A regular-run result of {@code month} for the member (its cycle reused or created). */
    private void paidMonth(long employeeId, YearMonth month) {
        String yymm = String.format("%04d%02d", month.getYear(), month.getMonthValue());
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
                    + " total_deductions, net_pay, result_status) VALUES (?, ?, ?, 'STAFF', ?, ?,"
                    + " 'ACTUAL', 75000, 0, 75000, 'CURRENT')",
                cycle,
                unit,
                employeeId,
                yymm,
                first);
        long result =
                jdbc.queryForObject(
                        "SELECT MAX(payroll_result_id) FROM payroll_result WHERE employee_id = ?"
                                + " AND payroll_yymm = ?",
                        Long.class,
                        employeeId,
                        yymm);
        for (int[] line : new int[][] {{101, 50000}, {111, 25000}}) {
            jdbc.update(
                    "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                            + " org_unit_id, employee_id, component_id, amount, run_type,"
                            + " payroll_yymm, period_date) SELECT ?, ?, ?, ?, component_id, ?,"
                            + " 'ACTUAL', ?, ? FROM pay_component WHERE legacy_pay_code = ?",
                    result,
                    cycle,
                    unit,
                    employeeId,
                    line[1],
                    yymm,
                    first,
                    line[0]);
        }
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
