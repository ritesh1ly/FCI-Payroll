package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.payroll.orchestration.CycleRegisters;
import java.math.BigDecimal;
import java.sql.Date;
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
 * Month-end lock with the gate sources written by their producers (SRS-15-11, SRS-15-14, SRS-15-15,
 * SRS-15-18, SRS-15-32, SRS-G-1, BR-CYC-004, BR-CYC-005): the Salary Control Register, the
 * disbursement register and the statutory status come from {@link CycleRegisters}, as the Actual
 * run writes them when it completes (the run itself is a batch-node job: here its result rows are a
 * fixture and the producer is called as the run's close calls it). Totals that do not reconcile
 * hold the lock naming G4; the lock is then requested by the Maker and decided only by a Payroll
 * Checker of the same office who is not the Maker, with the Checker's OTP; the lock is audited with
 * its approval. The office is CH001, never DH001 (OD-141: the evaluator's months).
 */
class CycleLockIT extends ApiTestSupport {

    private static final String OTP = "CYCLE_FREEZE_LOCK";
    private static final YearMonth CURRENT =
            YearMonth.from(LocalDate.now(ZoneId.of("Asia/Kolkata")));

    @Autowired private CycleRegisters registers;
    @Autowired private PlatformTransactionManager transactions;

    private Session maker() throws Exception {
        return login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
    }

    private Session checker() throws Exception {
        return login("ddo.chennai@fci-demo.test", "PAYROLL_CHECKER", "CH001");
    }

    private MvcResult post(Session s, String path, Map<String, Object> body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private String otpOf(Session s) {
        String email =
                jdbc.queryForObject(
                        "SELECT registered_email FROM app_user WHERE login_id = ?",
                        String.class,
                        s.loginId());
        return OTP_CODES.get(email + "|" + OTP);
    }

    /** The Maker's lock request; answers a 428 OTP challenge with the code sent to the Maker. */
    private MvcResult requestLock(Session maker, YearMonth period) throws Exception {
        String path = "/v1/cycles/" + period + "/lock";
        Map<String, Object> body = new HashMap<>(Map.of("reason", "Month-end " + period));
        MvcResult first = post(maker, path, body);
        if (first.getResponse().getStatus() != 428) {
            return first;
        }
        body.put(
                "otp",
                Map.of(
                        "challengeId",
                        body(first).get("args").get(0).asLong(),
                        "code",
                        otpOf(maker)));
        return post(maker, path, body);
    }

    private String cycleStatus(Session s, YearMonth period) throws Exception {
        return body(mvc.perform(userGet(s, "/v1/cycles/" + period))
                        .andExpect(status().isOk())
                        .andReturn())
                .get("status")
                .asString();
    }

    private long componentOf(String type) {
        return jdbc.queryForObject(
                "SELECT MIN(component_id) FROM pay_component WHERE component_type = ?",
                Long.class,
                type);
    }

    /**
     * A VERIFIED instance of {@code unit} whose Actual run of record computed one member with the
     * header totals 50000 / 8000 / 42000 and the lines {@code earning} / {@code deduction}
     * (signed); G0, G1, G6, the PAYROLL_RESULT total and a confirmed floor are fed as the batch run
     * leaves them, the pre-lock checks without an evaluator yet are parked. Returns the instance,
     * its run, the result and the parked checks.
     */
    private Fixture verified(
            String unit, YearMonth period, String maker, String checker, String deduction) {
        long unitId = locationId(unit);
        String yymm = CycleKeys.yymm(period);
        Date periodDate = Date.valueOf(period.atDay(1));
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by, frozen_by,"
                        + " frozen_at, verified_by, verified_at) VALUES (?, ?, 'DO', ?,"
                        + " 'VERIFIED', 'ON_DEMAND', ?, ?, CURRENT_TIMESTAMP, ?,"
                        + " CURRENT_TIMESTAMP)",
                yymm,
                unitId,
                CycleKeys.finYear(period),
                maker,
                maker,
                checker);
        long cycleId =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        yymm,
                        unitId);
        jdbc.update(
                "INSERT INTO computation_run (cycle_instance_id, run_type, run_no, payroll_type,"
                        + " run_status, started_by, started_at, completed_at, eligible_count,"
                        + " result_count, parameter_hash, rule_version_set_hash) VALUES (?,"
                        + " 'ACTUAL', 1, 'STAFF', 'COMPLETE', ?, CURRENT_TIMESTAMP,"
                        + " CURRENT_TIMESTAMP, 1, 1, 'it', 'it')",
                cycleId,
                maker);
        long runId =
                jdbc.queryForObject(
                        "SELECT MAX(run_id) FROM computation_run WHERE cycle_instance_id = ?",
                        Long.class,
                        cycleId);
        jdbc.update(
                "INSERT INTO computation_work_unit (run_id, unit_no, org_unit_id, member_count,"
                        + " unit_status) VALUES (?, 1, ?, 1, 'COMMITTED')",
                runId,
                unitId);
        long workUnit =
                jdbc.queryForObject(
                        "SELECT MAX(work_unit_id) FROM computation_work_unit WHERE run_id = ?",
                        Long.class,
                        runId);
        jdbc.update(
                "UPDATE payroll_cycle_instance SET current_run_id = ? WHERE cycle_instance_id = ?",
                runId,
                cycleId);
        jdbc.update(
                "INSERT INTO cycle_engine_status (cycle_instance_id, payroll_type, engine_state,"
                        + " member_count) VALUES (?, 'STAFF', 'COMPLETE', 1)",
                cycleId);
        long employee =
                jdbc.queryForObject(
                        "SELECT MIN(employee_id) FROM employee WHERE location_id = ? AND"
                                + " employee_type = 'STAFF'",
                        Long.class,
                        unitId);
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, run_id,"
                        + " work_unit_id, gross_earnings, total_deductions, net_pay,"
                        + " result_status, is_locked) VALUES (?, ?, ?, 'STAFF', ?, ?, 'ACTUAL', ?,"
                        + " ?, 50000, 8000, 42000, 'CURRENT', 'N')",
                cycleId,
                unitId,
                employee,
                yymm,
                periodDate,
                runId,
                workUnit);
        long resultId =
                jdbc.queryForObject(
                        "SELECT MAX(payroll_result_id) FROM payroll_result WHERE run_id = ?",
                        Long.class,
                        runId);
        for (Object[] line :
                new Object[][] {
                    {componentOf("EARNING"), new BigDecimal("50000")},
                    {componentOf("DEDUCTION"), new BigDecimal(deduction)}
                }) {
            jdbc.update(
                    "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                            + " org_unit_id, employee_id, component_id, amount, run_type,"
                            + " is_locked, payroll_yymm, period_date) VALUES (?, ?, ?, ?, ?, ?,"
                            + " 'ACTUAL', 'N', ?, ?)",
                    resultId,
                    cycleId,
                    unitId,
                    employee,
                    line[0],
                    line[1],
                    yymm,
                    periodDate);
        }
        jdbc.update(
                "INSERT INTO cycle_control_total (cycle_instance_id, payroll_type, total_source,"
                        + " member_count, gross_total, deduction_total, net_total) VALUES (?,"
                        + " 'STAFF', 'PAYROLL_RESULT', 1, 50000, 8000, 42000)",
                cycleId);
        Long approval =
                jdbc.queryForObject(
                        "SELECT approval_id FROM approval_queue WHERE entity_name ="
                                + " 'SEED_BASELINE' AND entity_key = 'demo-2026-10'",
                        Long.class);
        jdbc.update(
                "INSERT INTO net_pay_floor_rule (payroll_type, floor_basis, floor_value,"
                        + " is_confirmed, effective_from, effective_to, approval_id) VALUES"
                        + " ('STAFF', 'ABSOLUTE_AMOUNT', 0, 'Y', ?, ?, ?)",
                periodDate,
                Date.valueOf(period.atEndOfMonth()),
                approval);
        // OD-152: the four source checks (APPS posting, bank / IFSC, PF / UAN, duplicate recovery)
        // are evaluated, never parked; only checks whose sources this test does not set stay
        // parked.
        List<Object[]> parked =
                jdbc.query(
                        "SELECT check_id, is_active FROM pre_payroll_check WHERE check_code NOT IN"
                                + EVALUATED,
                        (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)});
        jdbc.update(
                "UPDATE pre_payroll_check SET is_active = 'N' WHERE check_code NOT IN" + EVALUATED);
        Map<String, Object> member =
                jdbc.queryForMap(
                        "SELECT location_id, depot_location_id, bank_account_number,"
                                + " bank_branch_id, provident_fund_type, cpf_code, cpf_code_origin,"
                                + " uan_number FROM employee WHERE employee_id = ?",
                        employee);
        Map<String, Object> posting =
                jdbc.queryForMap(
                        "SELECT location_id, apps_active_flag FROM org_location WHERE"
                                + " location_id = ?",
                        member.get("LOCATION_ID"));
        validSources(employee);
        return new Fixture(
                cycleId,
                runId,
                unitId,
                periodDate.toLocalDate(),
                parked,
                employee,
                member,
                posting);
    }

    /** The checks this test lets the gate evaluate (SRS-15-39): their sources are set below. */
    private static final String EVALUATED =
            " ('NET_PAY_NOT_BELOW_MINIMUM', 'HRMS_FALLBACK_NOT_APPROVED',"
                    + " 'APPS_POSTING_UNIT_ACTIVE', 'BANK_ACCOUNT_IFSC_VALID', 'PF_UAN_VALID',"
                    + " 'NO_DUPLICATE_RECOVERY')";

    /**
     * The member's sources as HRMS and APPS would hold them (a fixture: no APPS location feed
     * before module 23): the posting active in APPS, a bank route to a live branch with a valid
     * IFSC, a CPF code and a UAN.
     */
    private void validSources(long employee) {
        jdbc.update(
                "UPDATE org_location SET apps_active_flag = 'Y' WHERE location_id = (SELECT"
                        + " location_id FROM employee WHERE employee_id = ?)",
                employee);
        Long branch =
                jdbc.queryForObject(
                        "SELECT MIN(branch_id) FROM bank_branch WHERE active_flag = 'Y' AND"
                                + " REGEXP_LIKE(ifsc_code, '^[A-Z]{4}0[A-Z0-9]{6}$')",
                        Long.class);
        assertThat(branch).as("a live bank branch with a valid IFSC").isNotNull();
        jdbc.update(
                "UPDATE employee SET depot_location_id = NULL, bank_account_number ="
                        + " '123456789012', bank_branch_id = ?, provident_fund_type = 'CPF',"
                        + " cpf_code = COALESCE(cpf_code, 'ITLOCK' || employee_id),"
                        + " cpf_code_origin = COALESCE(cpf_code_origin, 'HRMS'), uan_number ="
                        + " '999900000001' WHERE employee_id = ?",
                branch,
                employee);
    }

    private record Fixture(
            long cycleId,
            long runId,
            long unitId,
            LocalDate periodDate,
            List<Object[]> parked,
            long employeeId,
            Map<String, Object> member,
            Map<String, Object> posting) {}

    /** The registers, as the Actual run's close writes them (one transaction). */
    private void produce(Fixture f) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx ->
                                registers.completed(
                                        new CycleRegisters.Run(
                                                f.cycleId(),
                                                f.runId(),
                                                f.unitId(),
                                                f.periodDate(),
                                                "STAFF")));
    }

    private void restore(Fixture f) {
        for (Object[] c : f.parked()) {
            jdbc.update(
                    "UPDATE pre_payroll_check SET is_active = ? WHERE check_id = ?", c[1], c[0]);
        }
        Map<String, Object> m = f.member();
        jdbc.update(
                "UPDATE employee SET depot_location_id = ?, bank_account_number = ?,"
                        + " bank_branch_id = ?, provident_fund_type = ?, cpf_code = ?,"
                        + " cpf_code_origin = ?, uan_number = ? WHERE employee_id = ?",
                m.get("DEPOT_LOCATION_ID"),
                m.get("BANK_ACCOUNT_NUMBER"),
                m.get("BANK_BRANCH_ID"),
                m.get("PROVIDENT_FUND_TYPE"),
                m.get("CPF_CODE"),
                m.get("CPF_CODE_ORIGIN"),
                m.get("UAN_NUMBER"),
                f.employeeId());
        jdbc.update(
                "UPDATE org_location SET apps_active_flag = ? WHERE location_id = ?",
                f.posting().get("APPS_ACTIVE_FLAG"),
                f.posting().get("LOCATION_ID"));
    }

    /**
     * A pre-lock check as the Checker reads it on C-01 ({@code GET /v1/cycles/{period}/gates}): its
     * result and failing count. A refused lock request rolls back what it evaluated, so the panel
     * is the place to read it.
     */
    private Map<String, Object> checkResult(YearMonth period, String code) throws Exception {
        MvcResult r =
                mvc.perform(userGet(checker(), "/v1/cycles/" + period + "/gates")).andReturn();
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode check =
                body(r).get("checks")
                        .valueStream()
                        .filter(c -> code.equals(c.get("code").asString()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(code + " not on the C-01 panel"));
        return Map.of(
                "CHECK_RESULT", check.get("result").asString(),
                "FAILURE_COUNT", check.get("failures").asLong());
    }

    /**
     * A second current Actual result of the fixture's member for the month, in another office's
     * instance, with a deduction head the run already recovers; returns its id.
     */
    private long duplicateResult(Fixture f, YearMonth period) {
        String yymm = CycleKeys.yymm(period);
        long other =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'KL001'",
                        Long.class);
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by) SELECT ?, ?,"
                        + " 'DO', ?, 'INITIATED', 'ON_DEMAND', 'it' FROM dual WHERE NOT EXISTS"
                        + " (SELECT 1 FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?)",
                yymm,
                other,
                CycleKeys.finYear(period),
                yymm,
                other);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm"
                                + " = ? AND org_unit_id = ?",
                        Long.class,
                        yymm,
                        other);
        Date periodDate = Date.valueOf(f.periodDate());
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, result_status, is_locked) VALUES (?, ?,"
                        + " ?, 'STAFF', ?, ?, 'ACTUAL', 0, 100, -100, 'CURRENT', 'N')",
                cycle,
                other,
                f.employeeId(),
                yymm,
                periodDate);
        long result =
                jdbc.queryForObject(
                        "SELECT MAX(payroll_result_id) FROM payroll_result WHERE"
                                + " cycle_instance_id = ? AND employee_id = ?",
                        Long.class,
                        cycle,
                        f.employeeId());
        jdbc.update(
                "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type, is_locked,"
                        + " payroll_yymm, period_date) VALUES (?, ?, ?, ?, ?, -100, 'ACTUAL',"
                        + " 'N', ?, ?)",
                result,
                cycle,
                other,
                f.employeeId(),
                componentOf("DEDUCTION"),
                yymm,
                periodDate);
        return result;
    }

    private long pendingLock(long cycleId) {
        return jdbc.queryForObject(
                "SELECT MAX(approval_id) FROM approval_queue WHERE entity_name ="
                        + " 'payroll_cycle_instance' AND entity_key = ? AND status ="
                        + " 'PENDING_APPROVAL'",
                Long.class,
                String.valueOf(cycleId));
    }

    private MvcResult decideRaw(Session s, long approval, Map<String, Object> body)
            throws Exception {
        return post(s, "/v1/approvals/" + approval + "/decision", body);
    }

    @Test
    void SRS_15_15_SRS_15_18_G4_unreconciledTotalsHoldTheLockAndTheCheckersOtpLocksTheMonth()
            throws Exception {
        YearMonth period = CURRENT.minusMonths(24);
        // The Salary Control Register's deduction line is 1000 short of the result header.
        Fixture f =
                verified(
                        "CH001",
                        period,
                        "maker.chennai@fci-demo.test",
                        "ddo.chennai@fci-demo.test",
                        "-7000");
        try {
            produce(f);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM cycle_statutory_status WHERE"
                                            + " cycle_instance_id = ? AND process_state ="
                                            + " 'COMPLETE' AND members_uncomputed = 0",
                                    Integer.class,
                                    f.cycleId()))
                    .as("G5: the four statutory processes, every member computed")
                    .isEqualTo(4);
            Session maker = maker();
            // SRS-15-11 / SRS-15-15: refused naming G4, the only gate that holds; no override.
            MvcResult refused = requestLock(maker, period);
            assertThat(code(refused)).isEqualTo("CYC-422-GATES");
            String unmet = body(refused).get("args").get(0).asString();
            assertThat(unmet).contains("G4").doesNotContain("G0", "G1", "G2", "G3", "G5", "G6");
            MvcResult override =
                    post(
                            checker(),
                            "/v1/cycles/" + period + "/gates/G4/accept",
                            Map.of("reason", "Accept the difference"));
            assertThat(override.getResponse().getStatus()).isBetween(400, 499);
            assertThat(cycleStatus(maker, period)).isEqualTo("VERIFIED");

            // The line is corrected and the run's registers written again: the totals agree.
            jdbc.update(
                    "UPDATE payroll_result_component SET amount = -8000 WHERE cycle_instance_id ="
                            + " ? AND amount = -7000",
                    f.cycleId());
            produce(f);
            MvcResult requested = requestLock(maker, period);
            assertThat(requested.getResponse().getStatus())
                    .as(requested.getResponse().getContentAsString())
                    .isEqualTo(200);
            assertThat(body(requested).get("lockApprovalStatus").asString())
                    .isEqualTo("PENDING_APPROVAL");
            long approval = pendingLock(f.cycleId());

            // Wrong role: the Maker and a Headquarters administrator never decide it.
            assertThat(
                            decideRaw(maker, approval, Map.of("decision", "APPROVE"))
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(403);
            Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
            assertThat(
                            decideRaw(admin, approval, Map.of("decision", "APPROVE"))
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(403);
            // SRS-15-6 / BR-CYC-004: a Checker of another office has no lock authority here.
            Session kolkata = login("ddo.kolkata@fci-demo.test", "PAYROLL_CHECKER", "KL001");
            assertThat(
                            decideRaw(kolkata, approval, Map.of("decision", "APPROVE"))
                                    .getResponse()
                                    .getStatus())
                    .isBetween(400, 499);
            assertThat(cycleStatus(maker, period)).isEqualTo("VERIFIED");

            // SRS-15-32: without the Checker's OTP nothing is locked.
            Session ddo = checker();
            Map<String, Object> decision = new HashMap<>(Map.of("decision", "APPROVE"));
            MvcResult challenge = decideRaw(ddo, approval, decision);
            assertThat(challenge.getResponse().getStatus()).isEqualTo(428);
            assertThat(code(challenge)).isEqualTo("AUTH-428-OTP");
            assertThat(cycleStatus(maker, period)).isEqualTo("VERIFIED");
            // A wrong code is refused and nothing is locked.
            String right = otpOf(ddo);
            String wrong =
                    right.substring(0, right.length() - 1)
                            + (right.charAt(right.length() - 1) == '0' ? '1' : '0');
            decision.put(
                    "otp",
                    Map.of(
                            "challengeId",
                            body(challenge).get("args").get(0).asLong(),
                            "code",
                            wrong));
            MvcResult refusedCode = decideRaw(ddo, approval, decision);
            assertThat(refusedCode.getResponse().getStatus()).isEqualTo(400);
            assertThat(code(refusedCode)).isEqualTo("AUTH-400-OTP");
            assertThat(cycleStatus(maker, period)).isEqualTo("VERIFIED");

            // The right code locks the month, audited with its approval.
            assertThat(decide(ddo, approval, "APPROVE", null, OTP)).isEqualTo(200);
            JsonNode locked = body(mvc.perform(userGet(maker, "/v1/cycles/" + period)).andReturn());
            assertThat(locked.get("status").asString()).isEqualTo("LOCKED");
            assertThat(locked.get("lockedBy").asString()).isEqualTo("ddo.chennai@fci-demo.test");
            Map<String, Object> audit =
                    jdbc.queryForMap(
                            "SELECT old_value, new_value, changed_by, approval_id FROM audit_event"
                                    + " WHERE entity_name = 'payroll_cycle_instance' AND"
                                    + " entity_key = ? AND field_name = 'cycle_status' AND"
                                    + " new_value = 'LOCKED'",
                            String.valueOf(f.cycleId()));
            assertThat(audit)
                    .containsEntry("OLD_VALUE", "VERIFIED")
                    .containsEntry("CHANGED_BY", "ddo.chennai@fci-demo.test");
            assertThat(((Number) audit.get("APPROVAL_ID")).longValue()).isEqualTo(approval);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT DISTINCT is_locked FROM payroll_result WHERE"
                                            + " cycle_instance_id = ?",
                                    String.class,
                                    f.cycleId()))
                    .containsExactly("Y");
            // R-AUD: the registers the gates read were audited when they were written.
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                            + " 'cycle_control_total' AND entity_key = ? AND"
                                            + " changed_by = 'SYSTEM'",
                                    Integer.class,
                                    f.cycleId() + ":STAFF:SALARY_CONTROL_REGISTER"))
                    .as("written, then rewritten")
                    .isEqualTo(2);
        } finally {
            restore(f);
        }
    }

    /**
     * OD-152, SRS-15-39: each of the four source checks is evaluated on the run's member; a failure
     * keeps G2 unmet and is not acceptable (a failed Block check, SRS-15-16), so the lock is
     * refused naming G2; with the sources valid again nothing holds it.
     */
    @Test
    void SRS_15_39_aFailedSourceCheckHoldsTheLockOnG2UntilTheSourceIsCorrected() throws Exception {
        YearMonth period = CURRENT.minusMonths(26);
        Fixture f =
                verified(
                        "CH001",
                        period,
                        "maker.chennai@fci-demo.test",
                        "ddo.chennai@fci-demo.test",
                        "-8000");
        try {
            produce(f);
            Session maker = maker();
            String[][] breaks = {
                {
                    "APPS_POSTING_UNIT_ACTIVE",
                    "UPDATE org_location SET apps_active_flag = 'N' WHERE location_id = (SELECT"
                            + " location_id FROM employee WHERE employee_id = ?)"
                },
                {
                    "BANK_ACCOUNT_IFSC_VALID",
                    "UPDATE employee SET bank_branch_id = NULL WHERE employee_id = ?"
                },
                {"PF_UAN_VALID", "UPDATE employee SET uan_number = NULL WHERE employee_id = ?"}
            };
            for (String[] b : breaks) {
                validSources(f.employeeId());
                jdbc.update(b[1], f.employeeId());
                MvcResult refused = requestLock(maker, period);
                assertThat(code(refused)).as(b[0]).isEqualTo("CYC-422-GATES");
                assertThat(body(refused).get("args").get(0).asString()).as(b[0]).contains("G2");
                Map<String, Object> result = checkResult(period, b[0]);
                assertThat(result).as(b[0]).containsEntry("CHECK_RESULT", "FAIL");
                assertThat(((Number) result.get("FAILURE_COUNT")).longValue()).isEqualTo(1);
                MvcResult accept =
                        post(
                                checker(),
                                "/v1/cycles/" + period + "/gates/G2/accept",
                                Map.of("reason", "IT: accept a failed Block check"));
                assertThat(accept.getResponse().getStatus()).as(b[0]).isBetween(400, 499);
            }
            // NO_DUPLICATE_RECOVERY: the same deduction head in a second current Actual result of
            // the member for the month.
            validSources(f.employeeId());
            long second = duplicateResult(f, period);
            MvcResult refused = requestLock(maker, period);
            assertThat(body(refused).get("args").get(0).asString()).contains("G2");
            assertThat(checkResult(period, "NO_DUPLICATE_RECOVERY"))
                    .containsEntry("CHECK_RESULT", "FAIL");
            jdbc.update(
                    "UPDATE payroll_result SET result_status = 'SUPERSEDED' WHERE"
                            + " payroll_result_id = ?",
                    second);
            MvcResult requested = requestLock(maker, period);
            assertThat(requested.getResponse().getStatus())
                    .as(requested.getResponse().getContentAsString())
                    .isEqualTo(200);
            for (String check :
                    List.of(
                            "APPS_POSTING_UNIT_ACTIVE",
                            "BANK_ACCOUNT_IFSC_VALID",
                            "PF_UAN_VALID",
                            "NO_DUPLICATE_RECOVERY")) {
                assertThat(checkResult(period, check))
                        .as(check)
                        .containsEntry("CHECK_RESULT", "PASS");
            }
            // Withdrawn by the office's Checker: this test locks no month.
            long approval = pendingLock(f.cycleId());
            assertThat(decide(checker(), approval, "REJECT", "IT: source checks only", OTP))
                    .isEqualTo(200);
        } finally {
            restore(f);
        }
    }

    /** SRS-G-1 / SRS-15-32: the Maker of the lock never approves it, even holding the role. */
    @Test
    void SRS_G_1_theMakerOfTheLockNeverApprovesItEvenHoldingTheCheckerRole() throws Exception {
        YearMonth period = CURRENT.minusMonths(25);
        Fixture f =
                verified(
                        "DH001",
                        period,
                        "maker.checker@fci-demo.test",
                        "ddo.delhi@fci-demo.test",
                        "-8000");
        try {
            produce(f);
            Session both = login("maker.checker@fci-demo.test", "PAYROLL_MAKER", "DH001");
            MvcResult requested = requestLock(both, period);
            assertThat(requested.getResponse().getStatus())
                    .as(requested.getResponse().getContentAsString())
                    .isEqualTo(200);
            long approval = pendingLock(f.cycleId());
            actAs(both, "PAYROLL_CHECKER");
            MvcResult own = decideRaw(both, approval, Map.of("decision", "APPROVE"));
            assertThat(own.getResponse().getStatus()).isEqualTo(403);
            assertThat(code(own)).isEqualTo("WF-403-SOD");
            assertThat(cycleStatus(both, period)).isEqualTo("VERIFIED");
            // Withdrawn by the office's Checker: no DH001 month is locked by this test.
            Session ddo = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
            assertThat(decide(ddo, approval, "REJECT", "IT: own-approval check only", OTP))
                    .isEqualTo(200);
            assertThat(cycleStatus(ddo, period)).isEqualTo("VERIFIED");
        } finally {
            restore(f);
        }
    }
}
