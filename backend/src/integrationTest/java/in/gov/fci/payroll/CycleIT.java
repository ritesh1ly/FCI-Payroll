package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * C-02 payroll cycle of the session's paying unit (BR-CYC-002, BR-CYC-008, SRS-G-21, SRS-G-1):
 * initiate only within the calendar and not ahead of the current period; freeze with the maker's
 * OTP; verify only by a checker who neither froze nor computed; lock requested by the maker with
 * OTP and done when the checker approves with OTP; forward only, each step refused out of order.
 * The computation itself runs on a batch node (batch suite); here it is stood in for by moving the
 * instance to COMPUTED.
 */
class CycleIT extends ApiTestSupport {

    private static final String OTP = "CYCLE_FREEZE_LOCK";
    private static final YearMonth CURRENT =
            YearMonth.from(LocalDate.now(ZoneId.of("Asia/Kolkata")));

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private MvcResult post(Session s, String period, String step, Map<String, Object> body)
            throws Exception {
        return mvc.perform(userPost(s, "/v1/cycles/" + period + "/" + step, body)).andReturn();
    }

    /** Posts; answers a 428 OTP challenge with the code the notification sender received. */
    private MvcResult withOtp(Session s, String period, String step, Map<String, Object> body)
            throws Exception {
        MvcResult first = post(s, period, step, body);
        if (first.getResponse().getStatus() != 428) {
            return first;
        }
        String email =
                jdbc.queryForObject(
                        "SELECT registered_email FROM app_user WHERE login_id = ?",
                        String.class,
                        s.loginId());
        Map<String, Object> again = new HashMap<>(body);
        again.put(
                "otp",
                Map.of(
                        "challengeId",
                        body(first).get("args").get(0).asLong(),
                        "code",
                        OTP_CODES.get(email + "|" + OTP)));
        return post(s, period, step, again);
    }

    private JsonNode json(Session s, String path) throws Exception {
        return body(mvc.perform(userGet(s, path)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode cycle(Session s, String period) throws Exception {
        return body(
                mvc.perform(userGet(s, "/v1/cycles/" + period))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    /**
     * SRS-15-1 (the Maker of the instance initiates and freezes), SRS-15-8 (the Actual Run only
     * through initiate, freeze, compute, verify, lock), SRS-15-2 (lock after verification),
     * SRS-15-24 (each compute attempt a run of its own) and SRS-15-32 (OTP at freeze and at lock);
     * BR-CYC-008.
     */
    // SC-05-0108: the lock is finalised only after the Checker's OTP, with the Maker and Checker
    // stamped.
    @Test
    void BR_CYC_008_theCycleRunsForwardInitiateFreezeComputedVerifyLock() throws Exception {
        String period = CURRENT.minusMonths(14).toString();
        Session maker = maker();
        JsonNode none = cycle(maker, period);
        assertThat(none.get("status").isNull()).isTrue();
        assertThat(none.get("inCalendar").asBoolean()).isTrue();
        assertThat(none.get("unitCode").asString()).isEqualTo("DH001");

        assertThat(post(maker, period, "initiate", Map.of()).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(code(post(maker, period, "initiate", Map.of())))
                .as("once")
                .isEqualTo("CYC-409-STATE");
        // Out of order: verify and lock before computation.
        assertThat(code(post(checker(), period, "verify", Map.of()))).isEqualTo("CYC-409-STATE");
        assertThat(code(post(maker, period, "lock", Map.of("reason", "early"))))
                .isEqualTo("CYC-409-STATE");

        // Freeze needs the maker's OTP: first a challenge, then the code.
        MvcResult challenge = post(maker, period, "freeze", Map.of());
        assertThat(challenge.getResponse().getStatus()).isEqualTo(428);
        assertThat(code(challenge)).isEqualTo("AUTH-428-OTP");
        assertThat(withOtp(maker, period, "freeze", Map.of()).getResponse().getStatus())
                .isEqualTo(200);
        JsonNode frozen = cycle(maker, period);
        assertThat(frozen.get("status").asString()).isEqualTo("FROZEN");
        assertThat(frozen.get("frozenBy").asString()).isEqualTo("maker.delhi@fci-demo.test");
        assertThat(code(withOtp(maker, period, "freeze", Map.of())))
                .as("frozen once")
                .isEqualTo("CYC-409-STATE");

        // The batch computation (C-13) moves the cycle to COMPUTED.
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'COMPUTED' WHERE"
                        + " cycle_instance_id = ?",
                frozen.get("id").asLong());
        // The maker who froze it never verifies; the checker does.
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        assertThat(post(admin, period, "verify", Map.of()).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(post(checker(), period, "verify", Map.of()).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(cycle(maker, period).get("verifiedBy").asString())
                .isEqualTo("ddo.delhi@fci-demo.test");

        // SRS-15-11 (plan 05 D-1): the lock is refused naming every gate that holds it; nothing
        // produces control totals (module 15) or statutory status (07 / 08 / 18) yet.
        MvcResult refused = withOtp(maker, period, "lock", Map.of("reason", "Month-end " + period));
        assertThat(code(refused)).isEqualTo("CYC-422-GATES");
        String unmet = body(refused).get("args").get(0).asString();
        assertThat(unmet).contains("G0", "G1", "G4", "G5", "G6");
        // Once every source is fed (a fixture standing in for the producers), the lock goes
        // through.
        long cycleId = frozen.get("id").asLong();
        List<Object[]> parked = feedEveryGate(cycleId, period);
        try {
            lockAndCheck(maker, period);
        } finally {
            restoreChecks(parked);
        }
    }

    private void lockAndCheck(Session maker, String period) throws Exception {
        // Lock: requested by the maker with OTP, done when the checker approves with OTP.
        MvcResult requested =
                withOtp(maker, period, "lock", Map.of("reason", "Month-end " + period));
        assertThat(requested.getResponse().getStatus())
                .as(requested.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode pending = body(requested);
        assertThat(pending.get("lockApprovalStatus").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.get("status").asString())
                .as("not locked before approval")
                .isEqualTo("VERIFIED");
        long lockApproval = pending.get("lockApprovalId").asLong();
        // SRS-15-32 (BMAD TR-009): the route asks the OTP at its deciding level, and an approval
        // without the Checker's OTP is not taken - the cycle stays VERIFIED.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT l.otp_at_level FROM approval_queue q JOIN"
                                        + " approval_workflow_level l ON l.workflow_action_id ="
                                        + " q.workflow_action_id WHERE q.approval_id = ? AND"
                                        + " l.level_no = (SELECT MAX(x.level_no) FROM"
                                        + " approval_workflow_level x WHERE x.workflow_action_id ="
                                        + " q.workflow_action_id)",
                                String.class,
                                lockApproval))
                .isEqualTo("Y");
        MvcResult noOtp =
                mvc.perform(
                                userPost(
                                        checker(),
                                        "/v1/approvals/" + lockApproval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(noOtp.getResponse().getStatus()).isEqualTo(428);
        assertThat(code(noOtp)).isEqualTo("AUTH-428-OTP");
        assertThat(cycle(maker, period).get("status").asString())
                .as("not locked without the Checker's OTP")
                .isEqualTo("VERIFIED");
        assertThat(decide(checker(), lockApproval, "APPROVE", null, OTP)).isEqualTo(200);
        JsonNode locked = cycle(maker, period);
        assertThat(locked.get("status").asString()).isEqualTo("LOCKED");
        assertThat(locked.get("lockedBy").isNull()).isFalse();
        // Forward only: nothing more.
        assertThat(code(post(maker, period, "initiate", Map.of()))).isEqualTo("CYC-409-STATE");
        assertThat(code(withOtp(maker, period, "freeze", Map.of()))).isEqualTo("CYC-409-STATE");
        // The board of the period lists the session's period state.
        JsonNode periods = body(mvc.perform(userGet(maker, "/v1/periods")).andReturn());
        for (JsonNode p : periods) {
            if (p.get("period").asString().equals(period)) {
                assertThat(p.get("status").asString()).isEqualTo("LOCKED");
            }
        }
    }

    /**
     * Feeds every gate source of a cycle as its producers will (run of record with committed work
     * units, engine status, the three control totals agreeing, the four statutory processes
     * complete, a confirmed STAFF floor in force) and parks the pre-lock checks that have no
     * evaluator yet. Returns the parked checks so the caller restores them.
     */
    private List<Object[]> feedEveryGate(long cycleId, String period) {
        String yymm = period.replace("-", "");
        Long approval =
                jdbc.queryForObject(
                        "SELECT approval_id FROM approval_queue WHERE entity_name ="
                                + " 'SEED_BASELINE' AND entity_key = 'demo-2026-10'",
                        Long.class);
        jdbc.update(
                "INSERT INTO computation_run (cycle_instance_id, run_type, run_no, payroll_type,"
                        + " run_status, started_by, started_at, completed_at, eligible_count,"
                        + " result_count, parameter_hash, rule_version_set_hash) VALUES (?,"
                        + " 'ACTUAL', 99, 'STAFF', 'COMPLETE', 'it', CURRENT_TIMESTAMP,"
                        + " CURRENT_TIMESTAMP, 1, 1, 'it', 'it')",
                cycleId);
        Long run =
                jdbc.queryForObject(
                        "SELECT MAX(run_id) FROM computation_run WHERE cycle_instance_id = ?",
                        Long.class,
                        cycleId);
        jdbc.update(
                "INSERT INTO computation_work_unit (run_id, unit_no, org_unit_id, member_count,"
                        + " unit_status) VALUES (?, 1, (SELECT org_unit_id FROM"
                        + " payroll_cycle_instance WHERE cycle_instance_id = ?), 1, 'COMMITTED')",
                run,
                cycleId);
        jdbc.update(
                "UPDATE payroll_cycle_instance SET current_run_id = ? WHERE cycle_instance_id = ?",
                run,
                cycleId);
        jdbc.update(
                "INSERT INTO cycle_engine_status (cycle_instance_id, payroll_type, engine_state)"
                        + " VALUES (?, 'STAFF', 'COMPLETE')",
                cycleId);
        for (String source :
                List.of("PAYROLL_RESULT", "SALARY_CONTROL_REGISTER", "DISBURSEMENT_REGISTER")) {
            jdbc.update(
                    "INSERT INTO cycle_control_total (cycle_instance_id, payroll_type,"
                            + " total_source, member_count, gross_total, deduction_total,"
                            + " net_total) VALUES (?, 'STAFF', ?, 1, 50000, 8000, 42000)",
                    cycleId,
                    source);
        }
        for (String process :
                List.of("CPF_PF", "INCOME_TAX", "PROFESSIONAL_TAX", "NPS_DEDUCTION")) {
            jdbc.update(
                    "INSERT INTO cycle_statutory_status (cycle_instance_id, statutory_process,"
                            + " process_state, members_uncomputed) VALUES (?, ?, 'COMPLETE', 0)",
                    cycleId,
                    process);
        }
        jdbc.update(
                "INSERT INTO net_pay_floor_rule (payroll_type, floor_basis, floor_value,"
                        + " is_confirmed, effective_from, effective_to, approval_id) VALUES"
                        + " ('STAFF', 'ABSOLUTE_AMOUNT', 0, 'Y', ?, ?, ?)",
                java.sql.Date.valueOf(java.time.YearMonth.parse(period).atDay(1)),
                java.sql.Date.valueOf(java.time.YearMonth.parse(period).atEndOfMonth()),
                approval);
        List<Object[]> parked =
                jdbc.query(
                        "SELECT check_id, is_active FROM pre_payroll_check WHERE check_code NOT IN"
                                + " ('NET_PAY_NOT_BELOW_MINIMUM', 'HRMS_FALLBACK_NOT_APPROVED')",
                        (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)});
        jdbc.update(
                "UPDATE pre_payroll_check SET is_active = 'N' WHERE check_code NOT IN"
                        + " ('NET_PAY_NOT_BELOW_MINIMUM', 'HRMS_FALLBACK_NOT_APPROVED')");
        return parked;
    }

    private void restoreChecks(List<Object[]> parked) {
        for (Object[] c : parked) {
            jdbc.update(
                    "UPDATE pre_payroll_check SET is_active = ? WHERE check_id = ?", c[1], c[0]);
        }
    }

    @Test
    void SRS_G_1_whoeverStartedTheComputationNeverVerifiesIt() throws Exception {
        String period = CURRENT.minusMonths(15).toString();
        Session maker = maker();
        post(maker, period, "initiate", Map.of());
        withOtp(maker, period, "freeze", Map.of());
        long id = cycle(maker, period).get("id").asLong();
        // The checker started the run (as if from C-13) and froze nothing: still refused.
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'COMPUTED', frozen_by ="
                        + " 'ddo.delhi@fci-demo.test' WHERE cycle_instance_id = ?",
                id);
        assertThat(code(post(checker(), period, "verify", Map.of()))).isEqualTo("WF-403-SOD");
    }

    // SC-05-0056: the Maker initiates its own instance against the approved calendar only;
    // SC-05-0057: the approved calendar drives the cycle-control checks.
    @Test
    void SRS_15_33_initiationIsRefusedAheadOfTheCurrentPeriodAndOutsideTheApprovedCalendar()
            throws Exception {
        Session maker = maker();
        assertThat(code(post(maker, CURRENT.plusMonths(1).toString(), "initiate", Map.of())))
                .isEqualTo("CYC-422-CALENDAR");
        assertThat(code(post(maker, "2019-01", "initiate", Map.of())))
                .isEqualTo("CYC-422-CALENDAR");
        assertThat(cycle(maker, "2019-01").get("inCalendar").asBoolean()).isFalse();
        MvcResult notAMonth = post(maker, "2026-13", "initiate", Map.of());
        assertThat(notAMonth.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(notAMonth)).isEqualTo("COM-400-VALIDATION");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm IN"
                                        + " ('201901', '202613')",
                                Integer.class))
                .as("nothing initiated by a refusal")
                .isZero();
    }

    /** SRS-15-4 (an independent instance per office) and SRS-15-33 (the Maker's own instance). */
    // SC-16-0093: the C-02 organisational scope is the Maker's Access Group scope.
    @Test
    void aCycleOfOneOfficeIsInvisibleToAnotherAndRolesKeepToTheirSteps() throws Exception {
        String period = CURRENT.minusMonths(16).toString();
        post(maker(), period, "initiate", Map.of());
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        JsonNode ours = cycle(maker(), period);
        assertThat(ours.get("unitCode").asString()).isEqualTo("DH001");
        JsonNode theirs = cycle(kolkata, period);
        assertThat(theirs.get("unitCode").asString()).isEqualTo("KL001");
        // CycleControlIT may hold a Kolkata cycle of the same month (its own instance): Delhi's
        // instance is never the one Kolkata sees (test batch B: the check was order-dependent).
        assertThat(
                        theirs.get("id").isNull()
                                || theirs.get("id").asLong() != ours.get("id").asLong())
                .as("Delhi's cycle is not Kolkata's")
                .isTrue();
        assertThat(theirs.path("initiatedBy").asString()).isNotEqualTo("maker.delhi@fci-demo.test");
        // The checker does not initiate or freeze; the maker does not verify.
        assertThat(
                        post(checker(), CURRENT.minusMonths(17).toString(), "initiate", Map.of())
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        assertThat(post(checker(), period, "freeze", Map.of()).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(post(maker(), period, "verify", Map.of()).getResponse().getStatus())
                .isEqualTo(403);
        // Results of a cycle that never computed: none; an unknown result line is not found.
        JsonNode results = resultPage(maker(), period, "");
        assertThat(results.path("items").size()).isZero();
        assertThat(results.path("total").asLong()).isZero();
        assertThat(
                        mvc.perform(userGet(maker(), "/v1/cycles/" + period + "/results/99999999"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
    }

    // ---------------------------------------------------------------- results (2026-10-23)

    /** Three members of the demo seed, by staff number. */
    private List<Long> members() {
        return jdbc.queryForList(
                "SELECT employee_id FROM employee ORDER BY emp_num FETCH FIRST 3 ROWS ONLY",
                Long.class);
    }

    /**
     * A completed run as a batch node leaves it (a fixture standing in for C-13): one result per
     * member, gross 30,000 plus 1,000 per member, no deductions, and two pay lines each.
     */
    private long completedRun(long cycleId, String period, String type, int runNo, String by) {
        jdbc.update(
                "INSERT INTO computation_run (cycle_instance_id, run_type, run_no, payroll_type,"
                        + " run_status, started_by, completed_at, parameter_hash, parameters_json,"
                        + " rule_version_set_hash, result_count) VALUES (?, ?, ?, 'STAFF',"
                        + " 'COMPLETE', ?, SYSTIMESTAMP, 'it', '{}', 'it', 3)",
                cycleId,
                type,
                runNo,
                by);
        long runId =
                jdbc.queryForObject(
                        "SELECT MAX(run_id) FROM computation_run WHERE cycle_instance_id = ?",
                        Long.class,
                        cycleId);
        long unit =
                jdbc.queryForObject(
                        "SELECT org_unit_id FROM payroll_cycle_instance WHERE cycle_instance_id"
                                + " = ?",
                        Long.class,
                        cycleId);
        YearMonth month = YearMonth.parse(period);
        String yymm = period.replace("-", "");
        List<Map<String, Object>> components =
                jdbc.queryForList(
                        "SELECT component_id, legacy_pay_code FROM pay_component ORDER BY"
                                + " legacy_pay_code FETCH FIRST 2 ROWS ONLY");
        int i = 0;
        for (Long member : members()) {
            BigDecimal gross = BigDecimal.valueOf(30000L + 1000L * i++);
            jdbc.update(
                    "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                            + " payroll_type, payroll_yymm, period_date, run_type, run_id,"
                            + " gross_earnings, total_deductions, net_pay, payable_days,"
                            + " result_status, computed_at, is_locked) VALUES (?, ?, ?, 'STAFF', ?,"
                            + " ?, ?, ?, ?, 0, ?, 30, 'CURRENT', SYSTIMESTAMP, 'N')",
                    cycleId,
                    unit,
                    member,
                    yymm,
                    Date.valueOf(month.atDay(1)),
                    type,
                    runId,
                    gross,
                    gross);
            long resultId =
                    jdbc.queryForObject(
                            "SELECT MAX(payroll_result_id) FROM payroll_result WHERE run_id = ?",
                            Long.class,
                            runId);
            BigDecimal[] amounts = {
                gross.subtract(BigDecimal.valueOf(1000)), BigDecimal.valueOf(1000)
            };
            for (int c = 0; c < components.size(); c++) {
                jdbc.update(
                        "INSERT INTO payroll_result_component (payroll_result_id,"
                                + " cycle_instance_id, org_unit_id, employee_id, component_id,"
                                + " amount, rate_applied, basis_amount, run_type, is_locked,"
                                + " payroll_yymm, period_date, legacy_pay_code) VALUES (?, ?, ?, ?,"
                                + " ?, ?, ?, ?, ?, 'N', ?, ?, ?)",
                        resultId,
                        cycleId,
                        unit,
                        member,
                        components.get(c).get("COMPONENT_ID"),
                        amounts[c],
                        c == 0 ? null : new BigDecimal("0.5000"),
                        c == 0 ? null : BigDecimal.valueOf(2000),
                        type,
                        yymm,
                        Date.valueOf(month.atDay(1)),
                        components.get(c).get("LEGACY_PAY_CODE"));
            }
        }
        return runId;
    }

    /** The rows of one page of the results (X-pagination: the page envelope's items). */
    private JsonNode results(Session s, String period, String query) throws Exception {
        return resultPage(s, period, query).path("items");
    }

    private JsonNode resultPage(Session s, String period, String query) throws Exception {
        return body(
                mvc.perform(userGet(s, "/v1/cycles/" + period + "/results" + query))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private int httpStatus(Session s, String path) throws Exception {
        return mvc.perform(userGet(s, path)).andReturn().getResponse().getStatus();
    }

    @Test
    void SRS_15_18_theScreenShowsTheRunOfRecordElseTheLatestDryRunPagedWithEachMembersLines()
            throws Exception {
        String period = CURRENT.minusMonths(18).toString();
        Session maker = maker();
        assertThat(results(maker, period, "").size()).as("no cycle, no results").isZero();
        post(maker, period, "initiate", Map.of());
        long cycleId = cycle(maker, period).get("id").asLong();
        assertThat(results(maker, period, "").size()).as("no run yet").isZero();

        // Two dry runs: the screen shows the latest.
        completedRun(cycleId, period, "SIMULATION", 1, "maker.delhi@fci-demo.test");
        long dryRun = completedRun(cycleId, period, "SIMULATION", 2, "maker.delhi@fci-demo.test");
        JsonNode view = cycle(maker, period);
        assertThat(view.get("resultsRunId").asLong()).isEqualTo(dryRun);
        assertThat(view.get("resultsRunType").asString()).isEqualTo("SIMULATION");
        JsonNode totals = view.get("totals");
        assertThat(totals.get("members").asLong()).isEqualTo(3);
        assertThat(totals.get("gross").decimalValue()).isEqualByComparingTo("93000");
        assertThat(totals.get("net").decimalValue()).isEqualByComparingTo("93000");
        assertThat(totals.get("components").size()).isEqualTo(2);
        assertThat(totals.get("components").get(1).get("amount").decimalValue())
                .as("1,000 for each of the three members")
                .isEqualByComparingTo("3000");

        // Paged by staff number (X-pagination): the page, its size and the total; a page past the
        // end is empty with the true total; a size not offered is refused.
        JsonNode page = resultPage(maker, period, "?page=0&size=10");
        assertThat(page.path("total").asLong()).isEqualTo(3);
        assertThat(page.path("size").asInt()).isEqualTo(10);
        JsonNode first = page.path("items");
        assertThat(first.size()).isEqualTo(3);
        JsonNode past = resultPage(maker, period, "?page=1&size=10");
        assertThat(past.path("items").size()).isZero();
        assertThat(past.path("total").asLong()).isEqualTo(3);
        assertThat(httpStatus(maker, "/v1/cycles/" + period + "/results?size=2")).isEqualTo(400);
        List<String> numbers =
                List.of(
                        first.get(0).get("eis").asString(),
                        first.get(1).get("eis").asString(),
                        first.get(2).get("eis").asString());
        assertThat(numbers).isSorted().doesNotHaveDuplicates();
        assertThat(first.get(0).get("name").asString()).isNotBlank().doesNotStartWith(" ");
        assertThat(first.get(0).get("status").asString()).isEqualTo("CURRENT");
        assertThat(first.get(0).get("locked").asBoolean()).isFalse();

        // C-02 l.3686-3692: each row's pay pattern, Basic (101) and DA (111) as computed in the
        // run, and the HRA city class; the Pay Pattern selector keeps one pattern (Both: none).
        for (JsonNode row : first) {
            long id = row.get("resultId").asLong();
            String pattern =
                    jdbc.queryForObject(
                            "SELECT e.pay_pattern FROM payroll_result r JOIN employee e ON"
                                + " e.employee_id = r.employee_id WHERE r.payroll_result_id = ?",
                            String.class,
                            id);
            assertThat(row.get("payPattern").asString()).isEqualTo(pattern);
            for (Object[] code : new Object[][] {{"basic", 101}, {"da", 111}}) {
                BigDecimal expected =
                        jdbc.queryForObject(
                                "SELECT SUM(amount) FROM payroll_result_component WHERE"
                                        + " payroll_result_id = ? AND legacy_pay_code = ?",
                                BigDecimal.class,
                                id,
                                code[1]);
                if (expected == null) {
                    assertThat(row.get((String) code[0]).isNull())
                            .as("no line, no amount")
                            .isTrue();
                } else {
                    assertThat(row.get((String) code[0]).decimalValue())
                            .isEqualByComparingTo(expected);
                }
            }
            assertThat(row.has("cityClass")).isTrue();
        }
        String pattern = first.get(0).get("payPattern").asString();
        JsonNode same = results(maker, period, "?payPattern=" + pattern);
        assertThat(same.size()).isPositive();
        for (JsonNode row : same) {
            assertThat(row.get("payPattern").asString()).isEqualTo(pattern);
        }
        String otherPattern = "IDA".equals(pattern) ? "CDA" : "IDA";
        assertThat(resultPage(maker, period, "?payPattern=" + otherPattern).path("total").asLong())
                .isEqualTo(3 - same.size());
        assertThat(httpStatus(maker, "/v1/cycles/" + period + "/results?payPattern=BOTH"))
                .as("only IDA or CDA; Both is no filter")
                .isEqualTo(400);

        // A member's lines, by pay code, with the rate and basis where the line has them.
        long resultId = first.get(0).get("resultId").asLong();
        JsonNode lines = all(maker, "/v1/cycles/" + period + "/results/" + resultId);
        assertThat(lines.size()).isEqualTo(2);
        assertThat(lines.get(0).get("payCode").asInt())
                .isLessThan(lines.get(1).get("payCode").asInt());
        assertThat(lines.get(0).get("rate").isNull()).isTrue();
        assertThat(lines.get(1).get("rate").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(lines.get(1).get("basis").decimalValue()).isEqualByComparingTo("2000");

        // C-02 as drawn (OD-121): the shown period, the tiles and totals, the filters, the trace.
        JsonNode shown = json(maker, "/v1/cycles/" + period + "/results-period");
        assertThat(shown.get("period").asString()).isEqualTo(period);
        assertThat(shown.get("runType").asString()).isEqualTo("SIMULATION");
        JsonNode tiles = json(maker, "/v1/cycles/" + period + "/results/summary");
        assertThat(
                        tiles.get("ida").get("members").asLong()
                                + tiles.get("cda").get("members").asLong())
                .isEqualTo(3);
        assertThat(tiles.get("shown").get("members").asLong()).isEqualTo(3);
        JsonNode narrow =
                json(maker, "/v1/cycles/" + period + "/results/summary?status=HELD&search=zzz");
        assertThat(narrow.get("shown").get("members").asLong()).isZero();
        assertThat(tiles.get("gross").decimalValue())
                .as("the tiles are the whole run, whatever the filter")
                .isEqualByComparingTo(narrow.get("gross").decimalValue());
        assertThat(resultPage(maker, period, "?status=COMPUTED").path("total").asLong())
                .isEqualTo(3);
        assertThat(resultPage(maker, period, "?status=HELD").path("total").asLong()).isZero();
        assertThat(
                        resultPage(maker, period, "?search=" + first.get(0).get("eis").asString())
                                .path("total")
                                .asLong())
                .isEqualTo(1);
        assertThat(httpStatus(maker, "/v1/cycles/" + period + "/results?status=DONE"))
                .isEqualTo(400);
        assertThat(httpStatus(maker, "/v1/cycles/" + period + "/results?search=%25"))
                .as("no wildcard reaches the list search")
                .isEqualTo(400);
        JsonNode trace = json(maker, "/v1/cycles/" + period + "/results/" + resultId + "/trace");
        assertThat(trace.get("eis").asString()).isEqualTo(first.get(0).get("eis").asString());
        assertThat(trace.get("lines").size()).isEqualTo(2);
        assertThat(httpStatus(maker, "/v1/cycles/" + period + "/results/export")).isEqualTo(200);
        // A year without PLI entries reads empty. Twenty months from the demo C-02 run (month
        // -10, V079_32), so never its financial year, whatever the month the suite runs in.
        String noPli = CURRENT.minusMonths(30).toString();
        assertThat(json(maker, "/v1/cycles/" + noPli + "/pli-payments").get("total").asLong())
                .isZero();

        // The result is known only in its own unit and period: elsewhere it does not exist.
        String other = CURRENT.minusMonths(19).toString();
        assertThat(httpStatus(maker, "/v1/cycles/" + other + "/results/" + resultId))
                .isEqualTo(404);
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(httpStatus(kolkata, "/v1/cycles/" + period + "/results/" + resultId))
                .isEqualTo(404);
        assertThat(results(kolkata, period, "").size())
                .as("Delhi's results are not Kolkata's")
                .isZero();
        assertThat(httpStatus(kolkata, "/v1/cycles/" + period + "/results/" + resultId + "/trace"))
                .as("nor is Delhi's trace")
                .isEqualTo(404);

        // The run of record, once computed, replaces the dry run on the screen.
        long actual = completedRun(cycleId, period, "ACTUAL", 1, "ddo.delhi@fci-demo.test");
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'COMPUTED', current_run_id = ?"
                        + " WHERE cycle_instance_id = ?",
                actual,
                cycleId);
        view = cycle(maker, period);
        assertThat(view.get("resultsRunId").asLong()).isEqualTo(actual);
        assertThat(view.get("resultsRunType").asString()).isEqualTo("ACTUAL");
        assertThat(view.get("runs").size()).isEqualTo(3);

        // SRS-G-1: the checker who started the computation does not verify it; once a batch
        // node started it, the checker may.
        assertThat(code(post(checker(), period, "verify", Map.of()))).isEqualTo("WF-403-SOD");
        jdbc.update(
                "UPDATE computation_run SET started_by = 'batch.node' WHERE run_id = ?", actual);
        assertThat(post(checker(), period, "verify", Map.of()).getResponse().getStatus())
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------- SRS-15-23 pre-run checks

    private JsonNode preRun(Session s, String period) throws Exception {
        return body(
                mvc.perform(userGet(s, "/v1/cycles/" + period + "/pre-run-checks"))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private static JsonNode check(JsonNode checks, String code) {
        for (JsonNode c : checks) {
            if (code.equals(c.get("code").asString())) {
                return c;
            }
        }
        throw new AssertionError(code + " not listed");
    }

    /**
     * SRS-15-23: Initialise stays refused until the pre-run checks are clear; a failing check names
     * its records by Employee Number; a check without its source is "not available" and does not
     * block (plan 05 D-22). Period CURRENT-3 is initiated by no other test.
     */
    @Test
    void SRS_15_23_initiationIsRefusedUntilThePreRunChecksAreClear() throws Exception {
        String period = CURRENT.minusMonths(3).toString();
        Session maker = maker();
        JsonNode clear = preRun(maker, period);
        assertThat(check(clear, "P1_RETIREMENT").get("status").asString()).isEqualTo("PASS");
        assertThat(check(clear, "P2_DUPLICATE_FPS").get("status").asString()).isEqualTo("PASS");
        for (String code :
                List.of("P3_NULL_FPS", "P4_APPS_PERIOD_CLOSE", "P5_APPS_POSTING_ACTIVE")) {
            assertThat(check(clear, code).get("status").asString())
                    .as(code)
                    .isEqualTo("NOT_AVAILABLE");
        }
        List<Map<String, Object>> two =
                jdbc.queryForList(
                        "SELECT e.employee_id, e.emp_num, e.retirement_date, e.eps_number FROM"
                                + " employee e JOIN org_location l ON l.location_id = e.location_id"
                                + " WHERE l.location_code = 'DH001' AND e.record_status = 'ACTIVE'"
                                + " AND e.employment_status NOT IN ('SEPARATED', 'DISMISSED')"
                                + " ORDER BY e.emp_num FETCH FIRST 2 ROWS ONLY");
        assertThat(two).hasSize(2);
        Object first = two.get(0).get("EMPLOYEE_ID");
        Object second = two.get(1).get("EMPLOYEE_ID");
        try {
            // P1: retired before the period starts.
            jdbc.update(
                    "UPDATE employee SET retirement_date = ? WHERE employee_id = ?",
                    Date.valueOf(YearMonth.parse(period).atDay(1).minusDays(1)),
                    first);
            // P2: one FPS number held by two members (a dummy in the EPFO format).
            jdbc.update(
                    "UPDATE employee SET eps_number = 'DLCPM00999990000099999' WHERE employee_id"
                            + " IN (?, ?)",
                    first,
                    second);
            JsonNode failing = preRun(maker, period);
            JsonNode p1 = check(failing, "P1_RETIREMENT");
            assertThat(p1.get("status").asString()).isEqualTo("FAIL");
            assertThat(p1.get("failures").asLong()).isOne();
            assertThat(p1.get("members").get(0).asString()).isEqualTo(two.get(0).get("EMP_NUM"));
            JsonNode p2 = check(failing, "P2_DUPLICATE_FPS");
            assertThat(p2.get("failures").asLong()).isEqualTo(2);
            assertThat(p2.get("correctionScreen").asString()).isEqualTo("I-21");
            MvcResult refused = post(maker, period, "initiate", Map.of());
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(code(refused)).isEqualTo("CYC-409-PRE-RUN");
            assertThat(body(refused).get("args").get(0).asString())
                    .isEqualTo("P1_RETIREMENT, P2_DUPLICATE_FPS");
            assertThat(cycle(maker, period).get("status").isNull()).isTrue();
        } finally {
            for (Map<String, Object> m : two) {
                jdbc.update(
                        "UPDATE employee SET retirement_date = ?, eps_number = ? WHERE"
                                + " employee_id = ?",
                        m.get("RETIREMENT_DATE"),
                        m.get("EPS_NUMBER"),
                        m.get("EMPLOYEE_ID"));
            }
        }
        // Readers without C-02 see nothing.
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "RO-DL");
        assertThat(
                        mvc.perform(userGet(hr, "/v1/cycles/" + period + "/pre-run-checks"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }
}
