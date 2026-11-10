package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * C-01 Payroll Cycle Control (plan 05 phase 05b): the pre-lock gates of an instance evaluated from
 * their sources and recorded, the Checker's acceptance of G2 / G5 with a reason (never a hard gate,
 * never by the maker), G6 per member, Warn checks acknowledged and Block checks never; and the
 * forward-only cycle with the three governed regressions of DDL v4.0. Each test uses its own past
 * period of Kolkata (KL001, a cycle unit no other test runs cycles at), moved through the states by
 * the API or, for a computation, by SQL.
 */
class CycleControlIT extends ApiTestSupport {

    private static final YearMonth CURRENT =
            YearMonth.from(LocalDate.now(ZoneId.of("Asia/Kolkata")));

    private Session maker() throws Exception {
        return login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
    }

    private Session checker() throws Exception {
        return login("ddo.kolkata@fci-demo.test", "PAYROLL_CHECKER", "KL001");
    }

    /** An instance of {@code period} at DH001, initiated and frozen by the maker, then COMPUTED. */
    private long computed(YearMonth period) throws Exception {
        Session maker = maker();
        mvc.perform(userPost(maker, "/v1/cycles/" + period + "/initiate", Map.of())).andReturn();
        long id =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        period.toString().replace("-", ""),
                        locationId("KL001"));
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'FROZEN', frozen_by ="
                        + " 'maker.kolkata@fci-demo.test', frozen_at = CURRENT_TIMESTAMP WHERE"
                        + " cycle_instance_id = ?",
                id);
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'COMPUTED' WHERE"
                        + " cycle_instance_id = ?",
                id);
        return id;
    }

    private JsonNode gates(Session s, YearMonth period) throws Exception {
        MvcResult r = mvc.perform(userGet(s, "/v1/cycles/" + period + "/gates")).andReturn();
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(r);
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private static JsonNode gate(JsonNode view, String code) {
        for (JsonNode g : view.get("gates")) {
            if (g.get("code").asString().equals(code)) {
                return g;
            }
        }
        throw new AssertionError(code);
    }

    private static JsonNode check(JsonNode view, String code) {
        for (JsonNode c : view.get("checks")) {
            if (c.get("code").asString().equals(code)) {
                return c;
            }
        }
        throw new AssertionError(code);
    }

    /**
     * SRS-15-18; SRS-15-42 (C-01 evaluates the HRMS fallback upload check; here no upload waits, so
     * it passes) and SRS-15-40 / SRS-1-69 (the HRMS-change check is evaluated since module 16 phase
     * 16a: no I-21 task of the unit received before the freeze is pending, so it passes).
     */
    @Test
    void SRS_15_18_everyGateIsEvaluatedFromItsSourceAndAnEmptySourceIsNeverMet() throws Exception {
        YearMonth period = CURRENT.minusMonths(10);
        long id = computed(period);
        JsonNode view = gates(maker(), period);
        assertThat(view.get("lockable").asBoolean()).isFalse();
        for (String g : new String[] {"G0", "G1", "G4", "G5", "G6"}) {
            assertThat(gate(view, g).get("met").asBoolean()).as(g).isFalse();
        }
        assertThat(gate(view, "G0").get("overridable").asBoolean()).isFalse();
        assertThat(gate(view, "G5").get("overridable").asBoolean()).isTrue();
        assertThat(gate(view, "G6").get("detail").asString())
                .contains("no confirmed net-pay floor");
        // Every evaluation is recorded per gate (DDL cycle_gate_evaluation, uk_cycle_gate).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM cycle_gate_evaluation WHERE cycle_instance_id"
                                        + " = ?",
                                Integer.class,
                                id))
                .isEqualTo(7);
        // SRS-1-69: evaluated from I-21 (module 16 phase 16a); no pending task of the unit.
        JsonNode hrms = check(view, "HRMS_CHANGE_PENDING");
        assertThat(hrms.get("result").asString()).isEqualTo("PASS");
        assertThat(check(view, "HRMS_FALLBACK_NOT_APPROVED").get("result").asString())
                .isEqualTo("PASS");
        // Labour checks apply only where a labour engine ran.
        assertThat(view.get("checks").valueStream().map(c -> c.get("code").asString()))
                .doesNotContain("DPS_BOOKING_SLIPS_APPROVED", "LABOUR_ATTENDANCE_DAYS");
    }

    /**
     * SRS-15-42 (US-05-10 AC5, E2E-07): an enabled HRMS fallback upload of the unit's period that
     * is not approved is a Block check: it fails and the cycle is not lockable; once its upload is
     * approved it passes. A withdrawn enablement holds nothing.
     */
    @Test
    void SRS_15_42_anEnabledFallbackUploadNotApprovedHoldsTheLock() throws Exception {
        YearMonth period = CURRENT.minusMonths(16);
        long id = computed(period);
        String yymm = period.toString().replace("-", "");
        String ref = "FBE-IT-" + (System.nanoTime() % 100000);
        jdbc.update(
                "INSERT INTO hrms_fallback_enablement (enablement_ref, fallback_type, payroll_yymm,"
                        + " reason, enabled_by, enablement_status) VALUES (?, 'ATTENDANCE_LEAVE',"
                        + " ?, 'HRMS down at cut-off (IT)', 'admin.hq', 'OPEN')",
                ref,
                yymm);
        long enablement =
                jdbc.queryForObject(
                        "SELECT enablement_id FROM hrms_fallback_enablement WHERE enablement_ref ="
                                + " ?",
                        Long.class,
                        ref);
        jdbc.update(
                "INSERT INTO hrms_fallback_enable_scope (enablement_id, org_unit_id,"
                        + " cycle_instance_id) VALUES (?, ?, ?)",
                enablement,
                locationId("KL001"),
                id);
        JsonNode view = gates(maker(), period);
        JsonNode fallback = check(view, "HRMS_FALLBACK_NOT_APPROVED");
        assertThat(fallback.get("result").asString()).isEqualTo("FAIL");
        assertThat(fallback.get("detail").asString()).contains("1 enabled fallback uploads");
        assertThat(view.get("lockable").asBoolean()).isFalse();

        // Withdrawn: the enablement no longer holds the lock.
        jdbc.update(
                "UPDATE hrms_fallback_enablement SET enablement_status = 'WITHDRAWN',"
                        + " withdrawn_reason = 'HRMS back (IT)' WHERE enablement_id = ?",
                enablement);
        assertThat(
                        check(gates(maker(), period), "HRMS_FALLBACK_NOT_APPROVED")
                                .get("result")
                                .asString())
                .isEqualTo("PASS");
    }

    @Test
    void SRS_15_16_theCheckerAcceptsG5ForNamedMembersButNeverAHardGateOrG6AsAWholeOrTheMaker()
            throws Exception {
        YearMonth period = CURRENT.minusMonths(11);
        long id = computed(period);
        String base = "/v1/cycles/" + period + "/gates/";
        Session checker = checker();
        assertThat(code(post(checker, base + "G4/accept", Map.of("reason", "x"))))
                .isEqualTo("CYC-422-GATE-HARD");
        // SRS l.1525 (BMAD TR-007): G6 is overridable, member by member - never as a whole gate.
        assertThat(code(post(checker, base + "G6/accept", Map.of("reason", "x"))))
                .isEqualTo("CYC-422-GATE-PER-MEMBER");
        assertThat(
                        post(checker, base + "G5/accept", Map.of("reason", " "))
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        // The maker holds no C-01 APPROVE.
        assertThat(
                        post(maker(), base + "G5/accept", Map.of("reason", "x"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);

        // SRS l.1524 (BMAD TR-010): G5 is accepted only for named members legitimately excluded.
        MvcResult unnamed = post(checker, base + "G5/accept", Map.of("reason", "Excluded"));
        assertThat(code(unnamed)).isEqualTo("CYC-422-GATE-MEMBERS");
        assertThat(body(unnamed).get("args").get(1).asString()).isEqualTo("NO_MEMBER");
        java.util.List<String> kolkata =
                jdbc.queryForList(
                        "SELECT emp_num FROM employee WHERE location_id = ? AND employee_type ="
                                + " 'STAFF' ORDER BY emp_num FETCH FIRST 2 ROWS ONLY",
                        String.class,
                        locationId("KL001"));
        Map<String, Object> naming =
                Map.of("reason", "Excluded", "members", List.of(kolkata.get(0)));
        // No statutory status reported: a process not complete is no member's exclusion.
        assertThat(body(post(checker, base + "G5/accept", naming)).get("args").get(1).asString())
                .isEqualTo("PROCESS_NOT_COMPLETE:CPF_PF");
        // The four processes complete, two members left uncomputed by CPF.
        jdbc.update(
                "INSERT INTO cycle_statutory_status (cycle_instance_id, statutory_process,"
                    + " process_state, members_computed, members_uncomputed) SELECT ?, p.c,"
                    + " 'COMPLETE', 10, CASE WHEN p.c = 'CPF_PF' THEN 2 ELSE 0 END FROM (SELECT"
                    + " 'CPF_PF' c FROM dual UNION ALL SELECT 'INCOME_TAX' FROM dual UNION ALL"
                    + " SELECT 'PROFESSIONAL_TAX' FROM dual UNION ALL SELECT 'NPS_DEDUCTION' FROM"
                    + " dual) p",
                id);
        assertThat(body(post(checker, base + "G5/accept", naming)).get("args").get(1).asString())
                .as("one named, two uncomputed: the other keeps G5 unmet")
                .isEqualTo("UNNAMED_MEMBERS:2");
        String delhi =
                jdbc.queryForObject(
                        "SELECT MIN(emp_num) FROM employee WHERE location_id = ?",
                        String.class,
                        locationId("DH001"));
        assertThat(
                        body(post(
                                        checker,
                                        base + "G5/accept",
                                        Map.of(
                                                "reason",
                                                "Excluded",
                                                "members",
                                                List.of(kolkata.get(0), delhi))))
                                .get("args")
                                .get(1)
                                .asString())
                .as("a member of another office is never named")
                .isEqualTo("NOT_A_MEMBER:" + delhi);

        MvcResult accepted =
                post(
                        checker,
                        base + "G5/accept",
                        Map.of(
                                "reason",
                                "Legitimately excluded",
                                "members",
                                List.of(kolkata.get(0), kolkata.get(1), kolkata.get(0))));
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        JsonNode g5 = gate(body(accepted), "G5");
        assertThat(g5.get("met").asBoolean()).isFalse();
        assertThat(g5.get("acceptedBy").asString()).isEqualTo("ddo.kolkata@fci-demo.test");
        assertThat(g5.get("acceptedReason").asString())
                .isEqualTo(
                        "Excluded members "
                                + kolkata.get(0)
                                + ", "
                                + kolkata.get(1)
                                + ": Legitimately excluded");
        assertThat(body(accepted).get("blocking").valueStream().map(JsonNode::asString))
                .doesNotContain("G5")
                .contains("G0", "G1", "G4");
        assertThat(code(post(checker, base + "G5/accept", naming))).isEqualTo("CYC-409-GATE-MET");
        // SRS-15-17: on the Gate Override & Undo / Reversal Register, maker and checker apart.
        Map<String, Object> reg =
                jdbc.queryForMap(
                        "SELECT requested_by, authorised_by, transaction_key FROM"
                                + " undo_reversal_register WHERE cycle_instance_id = ? AND"
                                + " register_type = 'GATE_OVERRIDE'",
                        id);
        assertThat(reg.get("REQUESTED_BY")).isEqualTo("maker.kolkata@fci-demo.test");
        assertThat(reg.get("AUTHORISED_BY")).isEqualTo("ddo.kolkata@fci-demo.test");
        assertThat(reg.get("TRANSACTION_KEY")).isEqualTo("G5");
    }

    /**
     * SRS-G-1, SRS-22-5 (separation of duties on identity, not role): a user holding both Payroll
     * Maker and Payroll Checker at DH001 never accepts a gate, nor reviews a quarantined member, of
     * a cycle the same user froze.
     */
    @Test
    void SRS_G_1_theMakerOfACycleNeverAcceptsItsGateEvenHoldingTheCheckerRole() throws Exception {
        YearMonth period = CURRENT.minusMonths(5);
        // The instance as the maker's initiation leaves it (a fixture: the prior-period rule of
        // initiation depends on which DH001 months other ITs initiated first; it is not under
        // test).
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) SELECT ?, ?, 'DO', 'IT',"
                        + " 'INITIATED', 'maker.checker@fci-demo.test' FROM dual WHERE NOT EXISTS"
                        + " (SELECT 1 FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?)",
                period.toString().replace("-", ""),
                locationId("DH001"),
                period.toString().replace("-", ""),
                locationId("DH001"));
        long id =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        period.toString().replace("-", ""),
                        locationId("DH001"));
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'COMPUTED', frozen_by ="
                        + " 'maker.checker@fci-demo.test', frozen_at = CURRENT_TIMESTAMP WHERE"
                        + " cycle_instance_id = ?",
                id);
        Session asChecker = login("maker.checker@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(
                        code(
                                post(
                                        asChecker,
                                        "/v1/cycles/" + period + "/gates/G5/accept",
                                        Map.of("reason", "Accepting my own cycle"))))
                .isEqualTo("WF-403-SOD");
    }

    @Test
    void SRS_15_16_g2IsAcceptedOnlyWhileNoBlockingExceptionOrBlockCheckRemains() throws Exception {
        YearMonth period = CURRENT.minusMonths(12);
        long id = computed(period);
        jdbc.update(
                "INSERT INTO payroll_exception (cycle_instance_id, exception_code, severity,"
                    + " detail) VALUES (?, 'IT_BLOCKING', 'BLOCKING', 'IT: blocking exception')",
                id);
        // Failed Block checks (not available) also keep G2 from being accepted.
        assertThat(
                        code(
                                post(
                                        checker(),
                                        "/v1/cycles/" + period + "/gates/G2/accept",
                                        Map.of("reason", "x"))))
                .isEqualTo("CYC-422-GATE-HARD");
        JsonNode g2 = gate(gates(checker(), period), "G2");
        assertThat(g2.get("met").asBoolean()).isFalse();
        assertThat(g2.get("detail").asString()).startsWith("1 blocking");
        // UC C-08 verify (SC-05-0066; SRS-15-14, SRS-G-9): a cleared exception no longer counts
        // against the lock, an open blocking one still does.
        jdbc.update(
                "INSERT INTO payroll_exception (cycle_instance_id, exception_code, severity,"
                    + " detail) VALUES (?, 'IT_STILL_OPEN', 'BLOCKING', 'IT: blocking exception')",
                id);
        assertThat(gate(gates(checker(), period), "G2").get("detail").asString())
                .startsWith("2 blocking");
        jdbc.update(
                "UPDATE payroll_exception SET exception_status = 'RESOLVED', resolved_at ="
                        + " CURRENT_TIMESTAMP WHERE cycle_instance_id = ? AND exception_code ="
                        + " 'IT_BLOCKING'",
                id);
        JsonNode cleared = gate(gates(checker(), period), "G2");
        assertThat(cleared.get("detail").asString()).startsWith("1 blocking");
        assertThat(cleared.get("met").asBoolean()).as("the open one still blocks").isFalse();
        jdbc.update(
                "UPDATE payroll_exception SET exception_status = 'RESOLVED', resolved_at ="
                        + " CURRENT_TIMESTAMP WHERE cycle_instance_id = ? AND exception_code ="
                        + " 'IT_STILL_OPEN'",
                id);
        assertThat(gate(gates(checker(), period), "G2").get("detail").asString())
                .startsWith("0 blocking");
    }

    @Test
    void SRS_15_39_aFailedWarnCheckIsAcknowledgedAndABlockCheckNever() throws Exception {
        YearMonth period = CURRENT.minusMonths(13);
        computed(period);
        // A Kolkata member whose increment fell due in the month and is not applied: the WARN
        // check INCREMENTS_DUE_APPLIED (module 17) fails.
        Long member =
                jdbc.queryForObject(
                        "SELECT MIN(employee_id) FROM employee WHERE location_id = ? AND"
                                + " emp_lbr_flag = 'N' AND employment_status = 'IN_SERVICE' AND"
                                + " record_status = 'ACTIVE'",
                        Long.class,
                        locationId("KL001"));
        java.sql.Date due = java.sql.Date.valueOf(period.atDay(1));
        if (jdbc.update(
                        "UPDATE employee_increment_due SET next_due_date = ? WHERE employee_id = ?",
                        due,
                        member)
                == 0) {
            jdbc.update(
                    "INSERT INTO employee_increment_due (employee_id, next_due_date, source_code,"
                            + " updated_by) VALUES (?, ?, 'SYS', 'it.fixture')",
                    member,
                    due);
        }
        JsonNode view = gates(checker(), period);
        long warn = check(view, "INCREMENTS_DUE_APPLIED").get("checkId").asLong();
        long block = check(view, "PF_UAN_VALID").get("checkId").asLong();
        assertThat(view.get("blocking").valueStream().map(JsonNode::asString))
                .contains("INCREMENTS_DUE_APPLIED");
        String base = "/v1/cycles/" + period + "/checks/";
        assertThat(code(post(checker(), base + block + "/acknowledge", Map.of("reason", "x"))))
                .isEqualTo("CYC-422-CHECK");
        // A check never evaluated for the cycle is not found.
        assertThat(
                        post(checker(), base + "999999/acknowledge", Map.of("reason", "x"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        MvcResult ok =
                post(
                        checker(),
                        base + warn + "/acknowledge",
                        Map.of("reason", "Increments due in I-05"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode after = body(ok);
        assertThat(check(after, "INCREMENTS_DUE_APPLIED").get("acknowledgedBy").asString())
                .isEqualTo("ddo.kolkata@fci-demo.test");
        assertThat(after.get("blocking").valueStream().map(JsonNode::asString))
                .doesNotContain("INCREMENTS_DUE_APPLIED");
        // The acknowledgement stands while the failure is unchanged.
        assertThat(
                        check(gates(maker(), period), "INCREMENTS_DUE_APPLIED")
                                .get("acknowledgedBy")
                                .isNull())
                .isFalse();
    }

    // SC-05-0088: the members an unresolved exception blocks are quarantined, the rest computed.
    @Test
    void SRS_15_13_eachQuarantinedMemberIsReviewedByTheCheckerWithAReason() throws Exception {
        YearMonth period = CURRENT.minusMonths(14);
        long id = computed(period);
        Long employee =
                jdbc.queryForObject(
                        "SELECT MIN(employee_id) FROM employee WHERE location_id = ?",
                        Long.class,
                        locationId("KL001"));
        jdbc.update(
                "INSERT INTO payroll_quarantine (cycle_instance_id, employee_id, quarantine_reason,"
                        + " computed_net_pay) VALUES (?, ?, 'NEGATIVE_NET', -125.50)",
                id,
                employee);
        JsonNode list = all(checker(), "/v1/cycles/" + period + "/quarantine");
        assertThat(list).hasSize(1);
        long q = list.get(0).get("id").asLong();
        assertThat(gate(gates(checker(), period), "G6").get("detail").asString())
                .startsWith("1 quarantined");
        String path = "/v1/cycles/" + period + "/quarantine/" + q + "/review";
        assertThat(
                        post(maker(), path, Map.of("accepted", true, "reason", "x"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        assertThat(
                        post(checker(), path, Map.of("accepted", true, "reason", "Recovery agreed"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT review_status FROM payroll_quarantine WHERE quarantine_id ="
                                        + " ?",
                                String.class,
                                q))
                .isEqualTo("ACCEPTED");
        assertThat(
                        post(checker(), path, Map.of("accepted", false, "reason", "again"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
    }

    @Test
    void SRS_15_10_theCycleRegressesOnlyWhenAReturnUnfreezeOrOverrideRecordsItAndNeverFromLocked()
            throws Exception {
        YearMonth period = CURRENT.minusMonths(15);
        Session maker = maker();
        mvc.perform(userPost(maker, "/v1/cycles/" + period + "/initiate", Map.of())).andReturn();
        // The maker's first freeze answers with an OTP challenge: the challenge an un-freeze
        // records.
        MvcResult challenge = post(maker, "/v1/cycles/" + period + "/freeze", Map.of());
        assertThat(challenge.getResponse().getStatus()).isEqualTo(428);
        long otp = body(challenge).get("args").get(0).asLong();
        long id = computed(period);
        Long approval =
                jdbc.queryForObject(
                        "SELECT approval_id FROM approval_queue WHERE entity_name = 'SEED_BASELINE'"
                                + " AND entity_key = 'demo-2026-10'",
                        Long.class);
        jdbc.update(
                "INSERT INTO computation_run (cycle_instance_id, run_type, run_no, run_status,"
                        + " started_by, parameter_hash, rule_version_set_hash) VALUES (?, 'ACTUAL',"
                        + " 1, 'COMPLETE', 'it', 'it', 'it')",
                id);
        long run =
                jdbc.queryForObject(
                        "SELECT MAX(run_id) FROM computation_run WHERE cycle_instance_id = ?",
                        Long.class,
                        id);
        String regress =
                "UPDATE payroll_cycle_instance SET cycle_status = ? WHERE cycle_instance_id = ?";
        assertThatThrownBy(() -> jdbc.update(regress, "FROZEN", id))
                .hasMessageContaining("FCIPAY-CYC-001");
        // A Checker return (OPEN) admits COMPUTED -> FROZEN.
        jdbc.update(
                "INSERT INTO cycle_return (cycle_instance_id, run_id, return_scope, from_status,"
                        + " reason, returned_by) VALUES (?, ?, 'INSTANCE', 'COMPUTED', 'IT',"
                        + " 'ddo.kolkata@fci-demo.test')",
                id,
                run);
        assertThat(jdbc.update(regress, "FROZEN", id)).isEqualTo(1);
        // An un-freeze executed just now admits FROZEN -> INITIATED.
        assertThatThrownBy(() -> jdbc.update(regress, "INITIATED", id))
                .hasMessageContaining("FCIPAY-CYC-001");
        jdbc.update(
                "INSERT INTO cycle_unfreeze (cycle_instance_id, from_status, reason, requested_by,"
                    + " authorised_by, approval_id, otp_challenge_id) VALUES (?, 'FROZEN', 'IT',"
                    + " 'maker.kolkata@fci-demo.test', 'ddo.kolkata@fci-demo.test', ?, ?)",
                id,
                approval,
                otp);
        assertThat(jdbc.update(regress, "INITIATED", id)).isEqualTo(1);
        // An approved override admits its own from / to only.
        jdbc.update(regress, "FROZEN", id);
        jdbc.update(regress, "COMPUTED", id);
        jdbc.update(regress, "VERIFIED", id);
        assertThatThrownBy(() -> jdbc.update(regress, "COMPUTED", id))
                .hasMessageContaining("FCIPAY-CYC-001");
        jdbc.update(
                "INSERT INTO cycle_override_log (cycle_instance_id, from_status, to_status,"
                        + " authorized_by, reason, approval_id) VALUES (?, 'VERIFIED', 'COMPUTED',"
                        + " 'it', 'IT', ?)",
                id,
                approval);
        assertThat(jdbc.update(regress, "COMPUTED", id)).isEqualTo(1);
        // Nothing leaves LOCKED, whatever is recorded.
        jdbc.update(regress, "VERIFIED", id);
        jdbc.update(regress, "LOCKED", id);
        jdbc.update(
                "INSERT INTO cycle_override_log (cycle_instance_id, from_status, to_status,"
                    + " authorized_by, reason, approval_id) VALUES (?, 'VERIFIED', 'FROZEN', 'it',"
                    + " 'IT', ?)",
                id,
                approval);
        assertThatThrownBy(() -> jdbc.update(regress, "VERIFIED", id))
                .hasMessageContaining("FCIPAY-CYC-002");
    }

    /**
     * SRS-15-39 pre-payroll check table (SRS l.1539-l.1551): the eleven checks are seeded from the
     * source with the payrolls it names and the severity it gives (Block, one Warn); nothing else
     * of theirs (thresholds, parameters) is seeded.
     */
    @Test
    void SRS_15_39_theElevenPrePayrollChecksAreSeededWithTheSeverityTheSourceGives() {
        Map<String, String> expected =
                Map.ofEntries(
                        Map.entry("APPS_POSTING_UNIT_ACTIVE", "ALL/BLOCK"),
                        Map.entry("LABOUR_ATTENDANCE_DAYS", "LABOUR/BLOCK"),
                        Map.entry("DPS_BOOKING_SLIPS_APPROVED", "DPS_LABOUR/BLOCK"),
                        Map.entry("NO_HRA_WITHOUT_ELIGIBLE_DAYS", "LABOUR/BLOCK"),
                        Map.entry("NO_DUPLICATE_RECOVERY", "ALL/BLOCK"),
                        Map.entry("PF_UAN_VALID", "ALL/BLOCK"),
                        Map.entry("INCREMENTS_DUE_APPLIED", "ALL/WARN"),
                        Map.entry("NET_PAY_NOT_BELOW_MINIMUM", "ALL/BLOCK"),
                        Map.entry("BANK_ACCOUNT_IFSC_VALID", "ALL/BLOCK"),
                        Map.entry("HRMS_CHANGE_PENDING", "STAFF/BLOCK"),
                        Map.entry("HRMS_FALLBACK_NOT_APPROVED", "STAFF/BLOCK"));
        for (Map.Entry<String, String> e : expected.entrySet()) {
            Map<String, Object> row =
                    jdbc.queryForMap(
                            "SELECT applies_to, severity, check_stage, is_active FROM"
                                    + " pre_payroll_check WHERE check_code = ? AND version_no = 1",
                            e.getKey());
            assertThat(row.get("APPLIES_TO") + "/" + row.get("SEVERITY"))
                    .as(e.getKey())
                    .isEqualTo(e.getValue());
            assertThat(row.get("CHECK_STAGE")).as(e.getKey()).isEqualTo("PRE_LOCK");
            assertThat(row.get("IS_ACTIVE")).as(e.getKey()).isEqualTo("Y");
        }
    }
}
