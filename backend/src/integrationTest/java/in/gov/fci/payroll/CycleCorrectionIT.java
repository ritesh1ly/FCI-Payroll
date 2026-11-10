package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

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
 * C-02 corrections before lock (plan 05 phase 05d): the Checker's return with a reason and a scope
 * (SRS-15-30, BR-CYC-016) and its withdrawal, and the un-freeze requested by the Maker, checked by
 * the Payroll Checker of the unit and approved with OTP by the Payroll Checker of the parent
 * Regional Office (SRS-15-31, BR-CYC-017, SRS-G-21; plan 05 D-8). Returns at Kolkata (KL001),
 * periods 1, 2, 17 and 18 months back (no other IT uses them there); un-freezes at Chennai (CH001,
 * parent RO-TN), whose cycles no other IT moves. A computed run is a fixture standing in for C-13.
 */
class CycleCorrectionIT extends ApiTestSupport {

    private static final YearMonth CURRENT =
            YearMonth.from(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    private static final String RELEASE_OTP = "CYCLE_UNFREEZE_STUCK_RELEASE";
    private static final String RUNNER = "batch.compute@fci-demo.test";

    private Session maker(String unit) throws Exception {
        return "CH001".equals(unit)
                ? login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001")
                : login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
    }

    private Session checker(String unit) throws Exception {
        return "CH001".equals(unit)
                ? login("ddo.chennai@fci-demo.test", "PAYROLL_CHECKER", "CH001")
                : login("ddo.kolkata@fci-demo.test", "PAYROLL_CHECKER", "KL001");
    }

    /**
     * A COMPUTED instance of {@code period} at {@code unit}: initiated by the maker, frozen, and a
     * completed Actual run with three results as its run of record.
     *
     * @return {cycle id, run id}
     */
    private long[] computed(String unit, YearMonth period) throws Exception {
        MvcResult init = post(maker(unit), "/v1/cycles/" + period + "/initiate", Map.of());
        assertThat(init.getResponse().getStatus())
                .as(init.getResponse().getContentAsString())
                .isEqualTo(200);
        long cycleId =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        period.toString().replace("-", ""),
                        locationId(unit));
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'FROZEN', frozen_by = ?,"
                        + " frozen_at = CURRENT_TIMESTAMP WHERE cycle_instance_id = ?",
                "CH001".equals(unit)
                        ? "maker.chennai@fci-demo.test"
                        : "maker.kolkata@fci-demo.test",
                cycleId);
        long runId = run(cycleId, 1);
        List<Long> members =
                jdbc.queryForList(
                        "SELECT employee_id FROM employee ORDER BY emp_num OFFSET 20 ROWS FETCH"
                                + " NEXT 3 ROWS ONLY",
                        Long.class);
        for (Long member : members) {
            jdbc.update(
                    "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                            + " payroll_type, payroll_yymm, period_date, run_type, run_id,"
                            + " gross_earnings, total_deductions, net_pay, result_status,"
                            + " is_locked) VALUES (?, ?, ?, 'STAFF', ?, ?, 'ACTUAL', ?, ?, 0, ?,"
                            + " 'CURRENT', 'N')",
                    cycleId,
                    locationId(unit),
                    member,
                    period.toString().replace("-", ""),
                    Date.valueOf(period.atDay(1)),
                    runId,
                    BigDecimal.valueOf(30000),
                    BigDecimal.valueOf(30000));
        }
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'COMPUTED', current_run_id = ?"
                        + " WHERE cycle_instance_id = ?",
                runId,
                cycleId);
        return new long[] {cycleId, runId};
    }

    private long run(long cycleId, int runNo) {
        jdbc.update(
                "INSERT INTO computation_run (cycle_instance_id, run_type, run_no, payroll_type,"
                        + " run_status, started_by, started_at, completed_at, parameter_hash,"
                        + " parameters_json, rule_version_set_hash, result_count) VALUES (?,"
                        + " 'ACTUAL', ?, 'STAFF', 'COMPLETE', ?, CURRENT_TIMESTAMP,"
                        + " CURRENT_TIMESTAMP, 'it', '{}', 'it', 3)",
                cycleId,
                runNo,
                RUNNER);
        return jdbc.queryForObject(
                "SELECT MAX(run_id) FROM computation_run WHERE cycle_instance_id = ?",
                Long.class,
                cycleId);
    }

    private List<String> eis(long runId) {
        return jdbc.queryForList(
                "SELECT e.emp_num FROM payroll_result r JOIN employee e ON e.employee_id ="
                        + " r.employee_id WHERE r.run_id = ? ORDER BY e.emp_num",
                String.class,
                runId);
    }

    private List<String> resultStatuses(long runId) {
        return jdbc.queryForList(
                "SELECT DISTINCT result_status FROM payroll_result WHERE run_id = ?",
                String.class,
                runId);
    }

    private String runStatus(long runId) {
        return jdbc.queryForObject(
                "SELECT run_status FROM computation_run WHERE run_id = ?", String.class, runId);
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private MvcResult returnOf(Session s, YearMonth period, Map<String, Object> body)
            throws Exception {
        Map<String, Object> request = new HashMap<>(body);
        request.putIfAbsent("reason", "Figures do not match the attendance");
        return post(s, "/v1/cycles/" + period + "/returns", request);
    }

    private JsonNode cycle(Session s, YearMonth period) throws Exception {
        MvcResult r = mvc.perform(userGet(s, "/v1/cycles/" + period)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return body(r);
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private String arg(MvcResult r) throws Exception {
        return body(r).path("args").path(0).asString();
    }

    /** A request of the cycle waiting in the inbox, as a submitted item leaves it. */
    private long pendingItem(String entity, long cycleId) {
        jdbc.update(
                "INSERT INTO approval_queue (entity_name, entity_key, action_type, payload,"
                        + " submitted_by, status) VALUES (?, ?, 'UPDATE', '{}', 'it.maker',"
                        + " 'PENDING_APPROVAL')",
                entity,
                String.valueOf(cycleId));
        return jdbc.queryForObject(
                "SELECT MAX(approval_id) FROM approval_queue WHERE entity_name = ? AND entity_key"
                        + " = ?",
                Long.class,
                entity,
                String.valueOf(cycleId));
    }

    /**
     * SRS-15-30 / BR-CYC-016: the Checker returns a verified cycle for named members; approval
     * state only is reversed (run and results SUPERSEDED, never removed), the cycle is FROZEN again
     * and cannot be verified until a fresh computation; the scope is checked and shown.
     */
    // SC-05-0093: a return carries its reason and scope and reverses the approval.
    @Test
    void SRS_15_30_theCheckerReturnsAVerifiedCycleForNamedMembersAndItWaitsForAFreshComputation()
            throws Exception {
        YearMonth period = CURRENT.minusMonths(17);
        long[] made = computed("KL001", period);
        long runId = made[1];
        Session checker = checker("KL001");
        assertThat(
                        post(checker, "/v1/cycles/" + period + "/verify", Map.of())
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        List<String> eis = eis(runId);

        // The maker does not return; the scope must name what it needs and only the unit's own.
        assertThat(
                        returnOf(maker("KL001"), period, Map.of("scope", "INSTANCE"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        MvcResult noMembers = returnOf(checker, period, Map.of("scope", "MEMBERS"));
        assertThat(code(noMembers)).isEqualTo("CYC-422-SCOPE");
        assertThat(arg(noMembers)).isEqualTo("MEMBERS_REQUIRED");
        MvcResult stranger =
                returnOf(checker, period, Map.of("scope", "MEMBERS", "members", List.of("000000")));
        assertThat(arg(stranger)).isEqualTo("MEMBER_WITHOUT_RESULT");
        MvcResult itself =
                returnOf(checker, period, Map.of("scope", "ORG_UNIT", "orgUnitCode", "KL001"));
        assertThat(arg(itself)).isEqualTo("ORG_UNIT_NOT_BENEATH");
        assertThat(
                        returnOf(checker, period, Map.of("scope", "INSTANCE", "reason", " "))
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);

        MvcResult returned =
                returnOf(
                        checker,
                        period,
                        Map.of("scope", "MEMBERS", "members", List.of(eis.get(2), eis.get(0))));
        assertThat(returned.getResponse().getStatus())
                .as(returned.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode view = body(returned);
        assertThat(view.get("status").asString()).isEqualTo("FROZEN");
        assertThat(view.get("currentRunId").isNull()).isTrue();
        assertThat(view.get("verifiedBy").isNull())
                .as("the verification no longer stands")
                .isTrue();
        JsonNode open = view.get("openReturn");
        assertThat(open.get("scope").asString()).isEqualTo("MEMBERS");
        assertThat(open.get("fromStatus").asString()).isEqualTo("VERIFIED");
        assertThat(open.get("returnedBy").asString()).isEqualTo("ddo.kolkata@fci-demo.test");
        assertThat(open.get("reason").asString()).isEqualTo("Figures do not match the attendance");
        List<String> named = new java.util.ArrayList<>();
        open.get("members").forEach(m -> named.add(m.asString()));
        assertThat(named).containsExactly(eis.get(0), eis.get(2));
        assertThat(runStatus(runId)).isEqualTo("SUPERSEDED");
        assertThat(resultStatuses(runId)).containsExactly("SUPERSEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT superseded_by_return_id FROM computation_run WHERE run_id"
                                        + " = ?",
                                Long.class,
                                runId))
                .isEqualTo(open.get("id").asLong());

        // No resubmission without a fresh computation; nothing to return twice.
        assertThat(code(post(checker, "/v1/cycles/" + period + "/verify", Map.of())))
                .isEqualTo("CYC-409-STATE");
        assertThat(code(returnOf(checker, period, Map.of("scope", "INSTANCE"))))
                .isEqualTo("CYC-409-STATE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'cycle_return' AND entity_key = ? AND new_value ="
                                        + " 'OPEN'",
                                Integer.class,
                                open.get("id").asString()))
                .isEqualTo(1);
    }

    /**
     * SRS-15-30: a return raised in error is withdrawn explicitly, with a reason, and restores what
     * it reversed; once a computation has started since, or once withdrawn, it is final.
     */
    @Test
    void SRS_15_30_aReturnRaisedInErrorIsWithdrawnAndRestoresTheRunUntilAComputationStarts()
            throws Exception {
        YearMonth period = CURRENT.minusMonths(18);
        long[] made = computed("KL001", period);
        long cycleId = made[0];
        long runId = made[1];
        Session checker = checker("KL001");
        long returnId =
                body(returnOf(checker, period, Map.of("scope", "INSTANCE")))
                        .get("openReturn")
                        .get("id")
                        .asLong();
        String withdraw = "/v1/cycles/" + period + "/returns/" + returnId + "/withdraw";
        assertThat(post(maker("KL001"), withdraw, Map.of("reason", "x")).getResponse().getStatus())
                .isEqualTo(403);
        MvcResult back = post(checker, withdraw, Map.of("reason", "Returned the wrong month"));
        assertThat(back.getResponse().getStatus())
                .as(back.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode view = body(back);
        assertThat(view.get("status").asString()).isEqualTo("COMPUTED");
        assertThat(view.get("currentRunId").asLong()).isEqualTo(runId);
        assertThat(view.get("openReturn").isNull()).isTrue();
        assertThat(runStatus(runId)).isEqualTo("COMPLETE");
        assertThat(resultStatuses(runId)).containsExactly("CURRENT");
        Map<String, Object> ret =
                jdbc.queryForMap(
                        "SELECT return_status, withdrawn_by, withdrawal_reason FROM cycle_return"
                                + " WHERE return_id = ?",
                        returnId);
        assertThat(ret)
                .containsEntry("RETURN_STATUS", "WITHDRAWN")
                .containsEntry("WITHDRAWN_BY", "ddo.kolkata@fci-demo.test")
                .containsEntry("WITHDRAWAL_REASON", "Returned the wrong month");
        MvcResult again = post(checker, withdraw, Map.of("reason", "again"));
        assertThat(code(again)).isEqualTo("CYC-409-RETURN");
        assertThat(arg(again)).isEqualTo("WITHDRAWN");

        // Returned again, then a computation starts: the return can no longer be withdrawn.
        JsonNode depot =
                body(returnOf(
                                checker,
                                period,
                                Map.of("scope", "ORG_UNIT", "orgUnitCode", "DP-WB01")))
                        .get("openReturn");
        assertThat(depot.get("scope").asString()).isEqualTo("ORG_UNIT");
        assertThat(depot.get("orgUnitCode").asString()).isEqualTo("DP-WB01");
        assertThat(depot.get("fromStatus").asString()).isEqualTo("COMPUTED");
        long second = depot.get("id").asLong();
        run(cycleId, 2);
        MvcResult started =
                post(
                        checker,
                        "/v1/cycles/" + period + "/returns/" + second + "/withdraw",
                        Map.of("reason", "too late"));
        assertThat(code(started)).isEqualTo("CYC-409-RETURN");
        assertThat(arg(started)).isEqualTo("COMPUTATION_STARTED");
        // A return of another cycle is unknown here.
        assertThat(
                        post(
                                        checker,
                                        "/v1/cycles/"
                                                + CURRENT.minusMonths(17)
                                                + "/returns/"
                                                + second
                                                + "/withdraw",
                                        Map.of("reason", "x"))
                                .getResponse()
                                .getStatus())
                .isIn(404, 409);
    }

    /**
     * SRS-15-30, SRS-15-31, SRS-15-28: neither correction while a lock request waits or while
     * another action holds the lease; never after lock (SRS-15-10). Verify waits for the lease and
     * for a computation still running (SRS l.1734, C-02 review item 1).
     */
    @Test
    void SRS_15_10_noCorrectionWhileTheLockWaitsOrARunHoldsTheCycleAndNoneAfterLock()
            throws Exception {
        YearMonth period = CURRENT.minusMonths(1);
        long[] made = computed("KL001", period);
        long cycleId = made[0];
        Session checker = checker("KL001");
        Session maker = maker("KL001");
        String unfreeze = "/v1/cycles/" + period + "/unfreeze-requests";

        long lock = pendingItem("payroll_cycle_instance", cycleId);
        MvcResult waiting = returnOf(checker, period, Map.of("scope", "INSTANCE"));
        assertThat(code(waiting)).isEqualTo("CYC-409-PENDING");
        assertThat(arg(waiting)).isEqualTo("LOCK");
        assertThat(code(post(maker, unfreeze, Map.of("reason", "Late arrears order"))))
                .isEqualTo("CYC-409-PENDING");
        jdbc.update("UPDATE approval_queue SET status = 'REJECTED' WHERE approval_id = ?", lock);

        jdbc.update(
                "INSERT INTO cycle_lease (cycle_instance_id, lease_action, held_by, acquired_at,"
                        + " heartbeat_at) VALUES (?, 'COMPUTE', ?, CURRENT_TIMESTAMP,"
                        + " CURRENT_TIMESTAMP)",
                cycleId,
                RUNNER);
        assertThat(code(returnOf(checker, period, Map.of("scope", "INSTANCE"))))
                .isEqualTo("CYC-409-LEASE");
        assertThat(code(post(maker, unfreeze, Map.of("reason", "Late arrears order"))))
                .isEqualTo("CYC-409-LEASE");
        // SRS-15-28: Verify is refused the same way while the lease is held.
        String verify = "/v1/cycles/" + period + "/verify";
        assertThat(code(post(checker, verify, Map.of()))).isEqualTo("CYC-409-LEASE");
        jdbc.update("DELETE FROM cycle_lease WHERE cycle_instance_id = ?", cycleId);

        // SRS l.1734: a re-computation still RUNNING (its lease lapsed) would supersede the
        // results verified: Verify waits for it, naming the run; once it ends Verify goes on.
        long rerun = run(cycleId, 2);
        jdbc.update("UPDATE computation_run SET run_status = 'RUNNING' WHERE run_id = ?", rerun);
        MvcResult running = post(checker, verify, Map.of());
        assertThat(running.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(running)).isEqualTo("CYC-409-RUNNING");
        assertThat(arg(running)).isEqualTo("2");
        assertThat(cycle(checker, period).path("status").asString()).isEqualTo("COMPUTED");
        jdbc.update("UPDATE computation_run SET run_status = 'FAILED' WHERE run_id = ?", rerun);
        MvcResult verified = post(checker, verify, Map.of());
        assertThat(verified.getResponse().getStatus())
                .as(verified.getResponse().getContentAsString())
                .isEqualTo(200);

        YearMonth locked = CURRENT.minusMonths(2);
        long lockedId = computed("KL001", locked)[0];
        // computed() signs the maker in again: that ends the earlier session.
        maker = maker("KL001");
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'VERIFIED' WHERE"
                        + " cycle_instance_id = ?",
                lockedId);
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'LOCKED', locked_by = 'it' WHERE"
                        + " cycle_instance_id = ?",
                lockedId);
        MvcResult afterLock =
                post(maker, "/v1/cycles/" + locked + "/unfreeze-requests", Map.of("reason", "x"));
        assertThat(code(afterLock)).isEqualTo("CYC-409-STATE");
        assertThat(arg(afterLock)).isEqualTo("LOCKED");
        assertThat(code(returnOf(checker, locked, Map.of("scope", "INSTANCE"))))
                .isEqualTo("CYC-409-STATE");
    }

    /**
     * SRS-15-31 / BR-CYC-017, SRS-G-21 (plan 05 D-8): the Maker asks with a reason, the unit's
     * Payroll Checker checks it, the Payroll Checker of the parent Regional Office approves it with
     * the OTP; refused while a run holds the cycle at that moment; on approval the results are
     * cancelled and the cycle must be frozen again.
     */
    // SC-05-0094: an un-freeze for a late input, requested with a reason and approved.
    @Test
    void SRS_15_31_anUnfreezeIsCheckedByTheUnitAndApprovedWithOtpByTheRegionalOffice()
            throws Exception {
        YearMonth period = CURRENT.minusMonths(1);
        long[] made = computed("CH001", period);
        long cycleId = made[0];
        long runId = made[1];
        Session maker = maker("CH001");
        String path = "/v1/cycles/" + period + "/unfreeze-requests";

        assertThat(post(checker("CH001"), path, Map.of("reason", "x")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(post(maker, path, Map.of("reason", "")).getResponse().getStatus())
                .isEqualTo(400);
        MvcResult asked = post(maker, path, Map.of("reason", "Late increment order to admit"));
        assertThat(asked.getResponse().getStatus())
                .as(asked.getResponse().getContentAsString())
                .isEqualTo(202);
        long approvalId = body(asked).get("unfreezeApprovalId").asLong();
        assertThat(approvalId).isPositive();
        assertThat(code(post(maker, path, Map.of("reason", "again")))).isEqualTo("CYC-409-PENDING");

        // Level 2: the unit's checker checks it (no OTP); the cycle has not moved.
        assertThat(decide(maker, approvalId, "APPROVE", null, RELEASE_OTP)).isEqualTo(403);
        Session unitChecker = checker("CH001");
        assertThat(decide(unitChecker, approvalId, "APPROVE", null, null)).isEqualTo(200);
        assertThat(cycle(maker, period).get("status").asString()).isEqualTo("COMPUTED");
        // Level 3 is the Regional Office's: the unit's checker cannot decide it too.
        assertThat(decide(checker("CH001"), approvalId, "APPROVE", null, RELEASE_OTP))
                .isEqualTo(403);

        // A run takes the cycle before the RO decides: the approval is refused, the item waits.
        jdbc.update(
                "INSERT INTO cycle_lease (cycle_instance_id, lease_action, held_by, acquired_at,"
                        + " heartbeat_at) VALUES (?, 'COMPUTE', ?, CURRENT_TIMESTAMP,"
                        + " CURRENT_TIMESTAMP)",
                cycleId,
                RUNNER);
        Session region = login("pc.rotn@fci-demo.test", "PAYROLL_CHECKER", "RO-TN");
        assertThat(decide(region, approvalId, "APPROVE", null, RELEASE_OTP)).isEqualTo(409);
        jdbc.update("DELETE FROM cycle_lease WHERE cycle_instance_id = ?", cycleId);
        assertThat(cycle(maker, period).get("status").asString()).isEqualTo("COMPUTED");

        assertThat(decide(region, approvalId, "APPROVE", null, RELEASE_OTP)).isEqualTo(200);
        JsonNode view = cycle(maker, period);
        assertThat(view.get("status").asString()).isEqualTo("INITIATED");
        assertThat(view.get("frozenBy").isNull()).isTrue();
        assertThat(view.get("currentRunId").isNull()).isTrue();
        assertThat(view.get("unfreezeApprovalId").isNull()).isTrue();
        assertThat(runStatus(runId)).isEqualTo("SUPERSEDED");
        assertThat(resultStatuses(runId)).containsExactly("SUPERSEDED");
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT from_status, reason, requested_by, checked_by, authorised_by,"
                                + " otp_challenge_id, invalidated_run_id FROM cycle_unfreeze WHERE"
                                + " approval_id = ?",
                        approvalId);
        assertThat(row)
                .containsEntry("FROM_STATUS", "COMPUTED")
                .containsEntry("REASON", "Late increment order to admit")
                .containsEntry("REQUESTED_BY", "maker.chennai@fci-demo.test")
                .containsEntry("CHECKED_BY", "ddo.chennai@fci-demo.test")
                .containsEntry("AUTHORISED_BY", "pc.rotn@fci-demo.test")
                .containsEntry("INVALIDATED_RUN_ID", runId);
        assertThat(row.get("OTP_CHALLENGE_ID")).isNotNull();

        // Re-freeze is needed: there is nothing to un-freeze in an initiated cycle.
        MvcResult nothing = post(maker, path, Map.of("reason", "again"));
        assertThat(code(nothing)).isEqualTo("CYC-409-STATE");
        assertThat(arg(nothing)).isEqualTo("INITIATED");
    }
}
