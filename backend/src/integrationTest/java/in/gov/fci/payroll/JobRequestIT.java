package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * C-13 batch jobs from the online side (SRS-NF-2, SRS-EH-7, SRS-G-1): the submit form, the
 * parameter rules, the duplicate guard, maker-checker before any node runs a job, the job list and
 * detail within the data scope, and Stop / Pause / Resume / Restart requests checked against the
 * job's state, a pending command and the restart window. No batch node runs here (profile online).
 */
class JobRequestIT extends ApiTestSupport {

    @Autowired private JobRequestService service;
    @Autowired private PayConfiguration configuration;
    @Autowired private in.gov.fci.payroll.payroll.api.StaffDeductionRunner deductions;

    private static final YearMonth CURRENT =
            YearMonth.from(LocalDate.now(ZoneId.of("Asia/Kolkata")));

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Map<String, Object> dryRun(String period, long location) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobType", "PAYROLL_DRY_RUN");
        body.put("params", Map.of("locationId", String.valueOf(location), "payPeriod", period));
        body.put("reason", "IT job " + System.nanoTime());
        return body;
    }

    private MvcResult submit(Session s, Map<String, Object> body) throws Exception {
        return mvc.perform(userPost(s, "/v1/jobs", body)).andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private String period(int monthsBack) {
        return CURRENT.minusMonths(monthsBack).toString();
    }

    /** The commands the job's detail offers this session. */
    private List<String> offered(Session s, long id) throws Exception {
        JsonNode job =
                body(mvc.perform(userGet(s, "/v1/jobs/" + id))
                                .andExpect(status().isOk())
                                .andReturn())
                        .get("job");
        return job.get("allowedCommands").valueStream().map(JsonNode::asString).toList();
    }

    /** The ids a list query returns to this session. */
    private List<String> listed(Session s, String query) throws Exception {
        JsonNode page =
                body(
                        mvc.perform(userGet(s, "/v1/jobs?size=100&" + query))
                                .andExpect(status().isOk())
                                .andReturn());
        return texts(page.get("items"), "id");
    }

    @Test
    void SRS_NF_2_theSubmitFormOffersTheJobsTheCurrentPeriodAndTheMakersUnits() throws Exception {
        JsonNode types =
                body(
                        mvc.perform(userGet(maker(), "/v1/jobs/types"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(
                        texts(types.get("types"), "jobType").isEmpty()
                                ? texts(types.get("types"), "type")
                                : texts(types.get("types"), "jobType"))
                .contains("PAYROLL_DRY_RUN")
                .doesNotContain("INTERFACE_PULL", "MONTH_END_CLOSE");
        assertThat(types.get("currentPeriod").asString()).isEqualTo(CURRENT.toString());
        assertThat(texts(types.get("locations"), "code")).contains("DH001").doesNotContain("KL001");
        // A checker reviews jobs but does not submit them.
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(
                        mvc.perform(userGet(checker, "/v1/jobs/types"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }

    @Test
    void SRS_NF_7_theTaxDeskQueuesTheStatutoryOutputsOfItsUnitForTheChecker() throws Exception {
        Session desk = login("tax.maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        long dh = locationId("DH001");
        int start = CURRENT.getMonthValue() >= 4 ? CURRENT.getYear() : CURRENT.getYear() - 1;
        String year = start + "-" + String.format("%02d", (start + 1) % 100);
        Map<String, Object> partB = new HashMap<>();
        partB.put("jobType", "TAX_FORM16_PART_B");
        partB.put("params", Map.of("locationId", String.valueOf(dh), "finYear", year));
        partB.put("reason", "IT Part B " + System.nanoTime());
        Map<String, Object> quarter = new HashMap<>();
        quarter.put("jobType", "TAX_24Q_RETURN");
        quarter.put(
                "params",
                Map.of("locationId", String.valueOf(dh), "finYear", year, "quarter", "Q1"));
        quarter.put("reason", "IT 24Q " + System.nanoTime());
        for (Map<String, Object> body : List.of(partB, quarter)) {
            MvcResult r = submit(desk, body);
            assertThat(r.getResponse().getStatus())
                    .as(body.get("jobType") + ": " + r.getResponse().getContentAsString())
                    .isEqualTo(202);
            assertThat(body(r).get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        }
        // A year that has not begun is refused like a future period.
        Map<String, Object> ahead = new HashMap<>(partB);
        ahead.put(
                "params",
                Map.of(
                        "locationId",
                        String.valueOf(dh),
                        "finYear",
                        (start + 1) + "-" + String.format("%02d", (start + 2) % 100)));
        assertThat(code(submit(desk, ahead))).isEqualTo("JOB-422-PARAM");
    }

    @Test
    void SRS_G_1_aJobWaitsForTheCheckerAndTheSameJobIsNotQueuedTwice() throws Exception {
        Session maker = maker();
        long dh = locationId("DH001");
        Map<String, Object> body = dryRun(period(1), dh);
        MvcResult first = submit(maker, body);
        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        JsonNode job = body(first);
        assertThat(job.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(job.get("canDecide").asBoolean()).as("never the maker").isFalse();
        // SRS-EH-7: the same job and parameters while the first is open.
        assertThat(code(submit(maker, dryRun(period(1), dh)))).isEqualTo("JOB-409-QUEUED");

        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        JsonNode seen =
                body(
                        mvc.perform(userGet(checker, "/v1/jobs/" + job.get("id").asLong()))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(seen.get("job").get("canDecide").asBoolean()).isTrue();
        assertThat(decide(checker, job.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode approved =
                body(
                        mvc.perform(userGet(maker, "/v1/jobs/" + job.get("id").asLong()))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(approved.get("job").get("status").asString()).isEqualTo("PENDING");
        assertThat(texts(approved.get("audit"), "event")).isNotEmpty();
    }

    @Test
    void aRejectedJobIsClosedAndCanBeSubmittedAgain() throws Exception {
        Session maker = maker();
        long dh = locationId("DH001");
        JsonNode job = body(submit(maker, dryRun(period(2), dh)));
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decide(checker, job.get("approvalId").asLong(), "REJECT", "Wrong period", null))
                .isEqualTo(200);
        JsonNode rejected =
                body(mvc.perform(userGet(maker, "/v1/jobs/" + job.get("id").asLong())).andReturn());
        assertThat(rejected.get("job").get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("job").get("errorMessage").asString()).contains("Wrong period");
        assertThat(submit(maker, dryRun(period(2), dh)).getResponse().getStatus())
                .as("no longer open")
                .isEqualTo(202);
    }

    @Test
    void theParameterRulesRefuseEveryMalformedSubmission() throws Exception {
        Session maker = maker();
        long dh = locationId("DH001");
        Map<String, Object> unknown = dryRun(period(3), dh);
        unknown.put("jobType", "NO_SUCH_JOB");
        assertThat(code(submit(maker, unknown))).isEqualTo("JOB-422-TYPE");
        Map<String, Object> scheduled = dryRun(period(3), dh);
        scheduled.put("jobType", "INTERFACE_PULL");
        assertThat(code(submit(maker, scheduled)))
                .as("schedule-only job")
                .isEqualTo("JOB-422-TYPE");
        Map<String, Object> extra = dryRun(period(3), dh);
        extra.put(
                "params",
                Map.of("locationId", String.valueOf(dh), "payPeriod", period(3), "other", "x"));
        assertThat(code(submit(maker, extra))).isEqualTo("JOB-422-PARAM");
        Map<String, Object> missing = dryRun(period(3), dh);
        missing.put("params", Map.of("locationId", String.valueOf(dh)));
        assertThat(code(submit(maker, missing))).isEqualTo("JOB-422-PARAM");
        assertThat(code(submit(maker, dryRun(CURRENT.plusMonths(1).toString(), dh))))
                .as("future")
                .isEqualTo("JOB-422-PARAM");
        assertThat(code(submit(maker, dryRun("2019-01", dh))))
                .as("beyond the retro limit")
                .isEqualTo("JOB-422-PARAM");
        assertThat(code(submit(maker, dryRun("2026-13", dh)))).isEqualTo("JOB-422-PARAM");
        Map<String, Object> badLocation = dryRun(period(3), dh);
        badLocation.put("params", Map.of("locationId", "abc", "payPeriod", period(3)));
        assertThat(code(submit(maker, badLocation))).isEqualTo("JOB-422-PARAM");
        Map<String, Object> arrear = new HashMap<>();
        arrear.put("jobType", "DA_ARREAR");
        arrear.put(
                "params",
                Map.of(
                        "locationId",
                        String.valueOf(dh),
                        "fromPeriod",
                        period(1),
                        "toPeriod",
                        period(3)));
        arrear.put("reason", "reversed range");
        assertThat(code(submit(maker, arrear))).isEqualTo("JOB-422-PARAM");
        // The unit must exist and be the maker's.
        assertThat(submit(maker, dryRun(period(3), 99_999_999L)).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(submit(maker, dryRun(period(3), locationId("KL001"))).getResponse().getStatus())
                .isEqualTo(403);
        // Bean validation: priority, reason, job type pattern.
        Map<String, Object> priority = dryRun(period(3), dh);
        priority.put("priority", 0);
        assertThat(submit(maker, priority).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> noReason = dryRun(period(3), dh);
        noReason.remove("reason");
        assertThat(submit(maker, noReason).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> lower = dryRun(period(3), dh);
        lower.put("jobType", "dry_run");
        assertThat(submit(maker, lower).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void theListFiltersAndSortsWithinTheDataScope() throws Exception {
        Session maker = maker();
        long dh = locationId("DH001");
        JsonNode mine = body(submit(maker, dryRun(period(4), dh)));
        long id = mine.get("id").asLong();
        JsonNode page =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/jobs?status=AWAITING_APPROVAL&jobType=PAYROLL_DRY_RUN&locationId="
                                                        + dh
                                                        + "&requestedBy=maker.delhi&sort=requestedAt&direction=asc&size=100"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(texts(page.get("items"), "id")).contains(String.valueOf(id));
        assertThat(texts(page.get("items"), "status")).containsOnly("AWAITING_APPROVAL");
        assertThat(
                        mvc.perform(userGet(maker, "/v1/jobs?direction=up"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        mvc.perform(userGet(maker, "/v1/jobs?size=101"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        // Another office neither lists nor opens the job.
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        JsonNode theirs =
                body(
                        mvc.perform(userGet(kolkata, "/v1/jobs?size=100"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(texts(theirs.get("items"), "id")).doesNotContain(String.valueOf(id));
        assertThat(
                        mvc.perform(userGet(kolkata, "/v1/jobs/" + id))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(
                        mvc.perform(userGet(maker, "/v1/jobs/99999999"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
    }

    @Test
    void theSkippedRecordsOfARunAreListedWithoutPersonalData() throws Exception {
        Session maker = maker();
        long id = body(submit(maker, dryRun(period(5), locationId("DH001")))).get("id").asLong();
        jdbc.update(
                "INSERT INTO batch_skipped_item (job_request_id, job_execution_id, step_name,"
                    + " skip_phase, item_key, error_class, error_message, recorded_at) VALUES (?,"
                    + " 1, 'payrollStep', 'PROCESS', 'employee:7', 'InvalidRecordException', 'No"
                    + " pay scale', ?)",
                id,
                Timestamp.from(Instant.now()));
        JsonNode skipped =
                body(
                        mvc.perform(userGet(maker, "/v1/jobs/" + id + "/skipped"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(skipped.get("total").asLong()).isEqualTo(1);
        assertThat(texts(skipped.get("items"), "itemKey")).containsExactly("employee:7");
        JsonNode detail = body(mvc.perform(userGet(maker, "/v1/jobs/" + id)).andReturn());
        assertThat(detail.get("skippedTotal").asLong()).isEqualTo(1);
    }

    private long pendingJob(Session maker, int monthsBack) throws Exception {
        JsonNode job = body(submit(maker, dryRun(period(monthsBack), locationId("DH001"))));
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decide(checker, job.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        return job.get("id").asLong();
    }

    private MvcResult control(Session s, long id, String command, String reason) throws Exception {
        return mvc.perform(userPost(s, "/v1/jobs/" + id + "/" + command, Map.of("reason", reason)))
                .andReturn();
    }

    @Test
    void C_13_aCommandIsCheckedAgainstTheStateAPendingCommandAndTheReason() throws Exception {
        Session maker = maker();
        long id = pendingJob(maker, 6);
        // PAUSE and RESUME do not apply to a waiting job.
        assertThat(code(control(maker, id, "pause", "lunch"))).isEqualTo("JOB-409-STATE");
        assertThat(code(control(maker, id, "resume", "go"))).isEqualTo("JOB-409-STATE");
        assertThat(control(maker, id, "stop", " ").getResponse().getStatus()).isEqualTo(400);
        MvcResult stop = control(maker, id, "stop", "Wrong unit");
        assertThat(stop.getResponse().getStatus()).isBetween(200, 299);
        assertThat(body(stop).get("status").asString()).isEqualTo("PENDING");
        // One command at a time.
        assertThat(code(control(maker, id, "stop", "again"))).isEqualTo("JOB-409-CONTROL-PENDING");
        JsonNode detail = body(mvc.perform(userGet(maker, "/v1/jobs/" + id)).andReturn());
        assertThat(detail.get("job").get("pendingCommand").asString())
                .as("a command waits")
                .isEqualTo("PENDING");
        assertThat(texts(detail.get("controls"), "command")).contains("STOP");
        // The checker has no control right.
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(control(checker, id, "stop", "x").getResponse().getStatus()).isEqualTo(403);
    }

    /**
     * Fails the request and records the computation run it started, with the hashes given (null:
     * the ones of the request and of the configuration now).
     */
    private void failedWithRun(long id, int monthsBack, String parameterHash, String versionHash) {
        YearMonth period = CURRENT.minusMonths(monthsBack);
        jdbc.update(
                "UPDATE batch_job_request SET job_status = 'FAILED', finished_at = ? WHERE"
                        + " job_request_id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                id);
        String requested =
                jdbc.queryForObject(
                        "SELECT param_hash FROM batch_job_request WHERE job_request_id = ?",
                        String.class,
                        id);
        // The run's own cycle instance, far from any period another test computes.
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by) SELECT '205001', ?,"
                        + " 'DO', '2049-50', 'INITIATED', 'ON_DEMAND', 'it.resume' FROM dual WHERE"
                        + " NOT EXISTS (SELECT 1 FROM payroll_cycle_instance WHERE payroll_yymm ="
                        + " '205001' AND org_unit_id = ?)",
                locationId("DH001"),
                locationId("DH001"));
        Long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " '205001' AND org_unit_id = ?",
                        Long.class,
                        locationId("DH001"));
        Integer runNo =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(run_no), 0) + 1 FROM computation_run WHERE"
                                + " cycle_instance_id = ? AND run_type = 'SIMULATION'",
                        Integer.class,
                        cycle);
        jdbc.update(
                "INSERT INTO computation_run (cycle_instance_id, run_type, run_no, start_mode,"
                        + " run_status, started_by, parameter_hash, parameters_json,"
                        + " rule_version_set_hash) VALUES (?, 'SIMULATION', ?, 'NEW', 'FAILED',"
                        + " 'maker.delhi@fci-demo.test', ?, ?, ?)",
                cycle,
                runNo,
                parameterHash == null ? requested : parameterHash,
                "{\"jobRequestId\":"
                        + id
                        + ",\"locationId\":"
                        + locationId("DH001")
                        + ",\"payPeriod\":\""
                        + period
                        + "\"}",
                versionHash == null
                        // the run's hash: the pay configuration and the deduction sources (16e-2)
                        ? deductions
                                .open(period)
                                .versionSetHash(configuration.staffConfiguration(period.atDay(1)))
                        : versionHash);
    }

    private long commands(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_job_control WHERE job_request_id = ?", Long.class, id);
    }

    @Test
    void SRS_15_26_aRunIsNotResumedAfterARuleOrParameterChangeOnlyRestartedAfresh()
            throws Exception {
        Session maker = maker();
        // Months no other test of this class submits (an open job for them would be a duplicate).
        long unchanged = pendingJob(maker, 14);
        long rulesChanged = pendingJob(maker, 15);
        long paramsChanged = pendingJob(maker, 16);
        failedWithRun(unchanged, 14, null, null);
        // The version set the run started with is no longer the one in force for its period.
        failedWithRun(rulesChanged, 15, null, "0".repeat(64));
        failedWithRun(paramsChanged, 16, "f".repeat(64), null);

        assertThat(code(control(maker, rulesChanged, "restart", "retry")))
                .isEqualTo("JOB-409-RESUME-RULES");
        assertThat(code(control(maker, paramsChanged, "restart", "retry")))
                .isEqualTo("JOB-409-RESUME-PARAMS");
        assertThat(commands(rulesChanged) + commands(paramsChanged))
                .as("nothing is queued for the batch node")
                .isZero();
        assertThat(control(maker, unchanged, "restart", "retry").getResponse().getStatus())
                .as("nothing changed: the run continues where it stopped")
                .isBetween(200, 299);

        // The full restart is a new job for the same period: accepted, since the failed one is
        // closed.
        assertThat(submit(maker, dryRun(period(15), locationId("DH001"))).getResponse().getStatus())
                .isBetween(200, 299);
    }

    private void failed(long id) {
        jdbc.update(
                "UPDATE batch_job_request SET job_status = 'FAILED', finished_at = ? WHERE"
                        + " job_request_id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                id);
    }

    private long abandonApproval(long id) {
        return jdbc.queryForObject(
                "SELECT MAX(approval_id) FROM approval_queue WHERE entity_name ="
                        + " 'batch_job_abandon' AND entity_key = ?",
                Long.class,
                String.valueOf(id));
    }

    private MvcResult haltPost(Session s, String path, String reason) throws Exception {
        return mvc.perform(userPost(s, path, Map.of("reason", reason))).andReturn();
    }

    private boolean halted(Session s) throws Exception {
        return body(mvc.perform(userGet(s, "/v1/jobs/halt")).andExpect(status().isOk()).andReturn())
                .get("engaged")
                .asBoolean();
    }

    @Test
    void SRS_15_27_anAbandonIsProposedByTheControllerAndDecidedByTheUnitsChecker()
            throws Exception {
        Session maker = maker();
        long id = pendingJob(maker, 17);
        failed(id);
        assertThat(offered(maker, id)).containsExactlyInAnyOrder("RESTART", "ABANDON");
        // A reason, the control right of the job's unit, and the job in one's scope.
        assertThat(control(maker, id, "abandon", " ").getResponse().getStatus()).isEqualTo(400);
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(control(checker, id, "abandon", "Not mine to ask").getResponse().getStatus())
                .isEqualTo(403);
        Session mumbai = login("pm.mr001@fci-demo.test", "PAYROLL_MAKER", "MR001");
        assertThat(control(mumbai, id, "abandon", "Another unit").getResponse().getStatus())
                .as("another unit's job is not there for them")
                .isEqualTo(404);

        MvcResult asked = control(maker, id, "abandon", "Wrong period, not worth continuing");
        assertThat(asked.getResponse().getStatus()).isEqualTo(202);
        assertThat(body(asked).get("pendingCommand").asString())
                .isEqualTo("ABANDON_AWAITING_APPROVAL");
        assertThat(body(asked).get("status").asString()).isEqualTo("FAILED");
        // One proposal at a time; meanwhile nothing else is done with the job.
        assertThat(code(control(maker, id, "abandon", "again"))).isEqualTo("WF-409-PENDING-BY");
        assertThat(code(control(maker, id, "restart", "retry")))
                .isEqualTo("JOB-409-ABANDON-PENDING");
        assertThat(offered(maker, id)).isEmpty();

        long approvalId = abandonApproval(id);
        assertThat(decide(maker, approvalId, "APPROVE", null, null))
                .as("the maker never decides their own proposal")
                .isEqualTo(403);
        assertThat(decide(checker, approvalId, "APPROVE", null, null)).isEqualTo(200);

        // The approval queued the ABANDON command; the batch node (none runs here) ends the job.
        JsonNode detail = body(mvc.perform(userGet(maker, "/v1/jobs/" + id)).andReturn());
        assertThat(texts(detail.get("controls"), "command")).containsExactly("ABANDON");
        assertThat(detail.get("job").get("pendingCommand").asString()).isEqualTo("PENDING");
        assertThat(detail.get("job").get("status").asString()).isEqualTo("FAILED");
        assertThat(texts(detail.get("audit"), "event")).contains("ABANDON_REQUESTED");
    }

    @Test
    void SRS_15_27_aRejectedAbandonLeavesTheJobRestartable() throws Exception {
        Session maker = maker();
        long id = pendingJob(maker, 18);
        failed(id);
        assertThat(control(maker, id, "abandon", "Not worth continuing").getResponse().getStatus())
                .isEqualTo(202);
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decide(checker, abandonApproval(id), "REJECT", "Retry after the fix", null))
                .isEqualTo(200);
        assertThat(offered(maker, id)).containsExactlyInAnyOrder("RESTART", "ABANDON");
        JsonNode detail = body(mvc.perform(userGet(maker, "/v1/jobs/" + id)).andReturn());
        assertThat(detail.get("controls")).isEmpty();
        assertThat(texts(detail.get("audit"), "event")).contains("ABANDON_REFUSED");
        assertThat(detail.get("job").get("pendingCommand").isNull()).isTrue();
    }

    @Test
    void SRS_15_25_theKillSwitchHaltsAtOnceAndIsReleasedOnlyByTheHeadquartersChecker()
            throws Exception {
        Session maker = maker();
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        Session zonal = login("sysadm.na11@fci-demo.test", "SYSTEM_ADMINISTRATOR", "NA11");
        long id = pendingJob(maker, 19);
        failed(id);
        try {
            assertThat(halted(maker)).as("every C-13 viewer reads the state").isFalse();
            assertThat(haltPost(maker, "/v1/jobs/halt", "x").getResponse().getStatus())
                    .as("no HALT right")
                    .isEqualTo(403);
            assertThat(code(haltPost(zonal, "/v1/jobs/halt", "Zonal panic")))
                    .isEqualTo("AUTHZ-403-HQ-ONLY");
            assertThat(code(haltPost(admin, "/v1/jobs/halt/release", "Nothing to release")))
                    .isEqualTo("JOB-409-NOT-HALTED");
            assertThat(haltPost(admin, "/v1/jobs/halt", " ").getResponse().getStatus())
                    .isEqualTo(400);

            MvcResult engaged = haltPost(admin, "/v1/jobs/halt", "Wrong DA rate activated");
            assertThat(engaged.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(engaged).get("engaged").asBoolean()).isTrue();
            assertThat(body(engaged).get("haltedBy").asString())
                    .isEqualTo("sysadmin@fci-demo.test");
            assertThat(body(engaged).get("reason").asString()).isEqualTo("Wrong DA rate activated");
            assertThat(halted(maker)).isTrue();
            assertThat(code(haltPost(admin, "/v1/jobs/halt", "again")))
                    .isEqualTo("JOB-409-ALREADY-HALTED");

            // Nothing is resumed or restarted; abandoning is still possible.
            assertThat(code(control(maker, id, "restart", "retry"))).isEqualTo("JOB-409-HALTED");
            assertThat(offered(maker, id)).containsExactly("ABANDON");

            MvcResult release = haltPost(admin, "/v1/jobs/halt/release", "Rate corrected");
            assertThat(release.getResponse().getStatus()).isEqualTo(202);
            long approvalId = body(release).get("releaseApprovalId").asLong();
            assertThat(halted(maker)).as("in force until approved").isTrue();
            assertThat(
                            body(mvc.perform(userGet(admin, "/v1/jobs/halt")).andReturn())
                                    .get("releaseRequestedBy")
                                    .asString())
                    .isEqualTo("sysadmin@fci-demo.test");
            Session delhiChecker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
            assertThat(decide(delhiChecker, approvalId, "APPROVE", null, null))
                    .as("a unit checker is not Headquarters")
                    .isEqualTo(403);
            Session hqChecker = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
            assertThat(decide(hqChecker, approvalId, "APPROVE", null, null)).isEqualTo(200);

            assertThat(halted(maker)).isFalse();
            assertThat(offered(maker, id)).containsExactlyInAnyOrder("RESTART", "ABANDON");
        } finally {
            // Never leave the shared database halted for the other tests.
            jdbc.update("DELETE FROM batch_halt WHERE released_at IS NULL");
        }
    }

    @Test
    void C_13_aRestartIsAllowedOnlyWithinTheRestartWindow() throws Exception {
        Session maker = maker();
        long recent = pendingJob(maker, 7);
        long old = pendingJob(maker, 8);
        jdbc.update(
                "UPDATE batch_job_request SET job_status = 'FAILED', finished_at = ? WHERE"
                        + " job_request_id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                recent);
        jdbc.update(
                "UPDATE batch_job_request SET job_status = 'FAILED', finished_at = ? WHERE"
                        + " job_request_id = ?",
                Timestamp.from(Instant.now().minus(100, ChronoUnit.HOURS)),
                old);
        assertThat(offered(maker, recent)).containsExactlyInAnyOrder("RESTART", "ABANDON");
        assertThat(code(control(maker, old, "restart", "retry")))
                .isEqualTo("JOB-409-RESTART-EXPIRED");
        assertThat(control(maker, recent, "restart", "retry").getResponse().getStatus())
                .isBetween(200, 299);
        assertThat(offered(maker, old))
                .as("the window has passed: no restart, only the abandon (SRS-15-27)")
                .containsExactly("ABANDON");
        JsonNode view = body(mvc.perform(userGet(maker, "/v1/jobs/" + recent)).andReturn());
        assertThat(view.get("job").get("pendingCommand").asString()).isEqualTo("PENDING");
        assertThat(texts(view.get("controls"), "command")).contains("RESTART");
    }

    @Test
    void theSchedulerQueuesAJobOnceWhileItIsOpen() {
        Map<String, String> params =
                Map.of("payPeriod", period(9), "marker", "it-" + System.nanoTime());
        assertThat(service.enqueueScheduled(JobType.MONTH_END_CLOSE, params)).isTrue();
        assertThat(service.enqueueScheduled(JobType.MONTH_END_CLOSE, params)).isFalse();
        assertThat(
                        service.enqueueScheduled(
                                JobType.MONTH_END_CLOSE,
                                new HashMap<>(params) {
                                    {
                                        put("marker", "other-" + System.nanoTime());
                                    }
                                }))
                .isTrue();
    }

    // ---------------------------------------------------------------- edges (2026-10-23)

    @Test
    void SRS_G_29_aScheduledJobIsSeenByEveryViewerAndControlledByTheSystemAdministratorOnly()
            throws Exception {
        String marker = "it-national-" + System.nanoTime();
        assertThat(
                        service.enqueueScheduled(
                                JobType.MONTH_END_CLOSE,
                                Map.of("payPeriod", period(10), "marker", marker)))
                .isTrue();
        long id =
                jdbc.queryForObject(
                        "SELECT job_request_id FROM batch_job_request WHERE job_params LIKE ?",
                        Long.class,
                        "%" + marker + "%");
        Session maker = maker();
        // No location: every C-13 viewer sees it, whatever the offices of the session.
        JsonNode seen =
                body(
                        mvc.perform(userGet(maker, "/v1/jobs/" + id))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(seen.get("job").get("locationId").isNull()).isTrue();
        assertThat(seen.get("job").get("source").asString()).isEqualTo("SCHEDULE");
        assertThat(listed(maker, "jobType=MONTH_END_CLOSE")).contains(String.valueOf(id));
        // A Payroll maker holds C-13 EDIT at its office, not over a national job.
        assertThat(offered(maker, id)).isEmpty();
        assertThat(control(maker, id, "stop", "Not mine").getResponse().getStatus()).isEqualTo(403);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM batch_job_control WHERE job_request_id = ?",
                                Integer.class,
                                id))
                .isZero();
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        assertThat(offered(admin, id)).containsExactly("STOP");
        MvcResult stop = control(admin, id, "stop", "Month-end moved");
        assertThat(stop.getResponse().getStatus()).isBetween(200, 299);
        assertThat(offered(admin, id)).as("one command at a time").isEmpty();
    }

    @Test
    void C_13_theListFiltersByRequestDateAndTakesTheRequesterLiterally() throws Exception {
        Session maker = maker();
        long id = body(submit(maker, dryRun(period(11), locationId("DH001")))).get("id").asLong();
        String mine = String.valueOf(id);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        assertThat(listed(maker, "from=" + today + "&to=" + today)).contains(mine);
        assertThat(listed(maker, "from=" + today.plusDays(1))).doesNotContain(mine);
        assertThat(listed(maker, "to=" + today.minusDays(1))).doesNotContain(mine);
        // A blank requester filters nothing; the requester matches in any case.
        assertThat(listed(maker, "requestedBy=   ")).contains(mine);
        assertThat(listed(maker, "requestedBy=MAKER.Delhi")).contains(mine);
        // LIKE wildcards are taken literally: no login holds a '%' or an '_'.
        assertThat(listed(maker, "requestedBy=%")).doesNotContain(mine);
        assertThat(listed(maker, "requestedBy=_")).doesNotContain(mine);
    }

    @Test
    void C_13_anUnknownSortColumnSortsByTheRequestTime() throws Exception {
        Session maker = maker();
        body(submit(maker, dryRun(period(12), locationId("DH001"))));
        JsonNode page =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/jobs?size=100&sort=employeeName&direction=asc"))
                                .andExpect(status().isOk())
                                .andReturn());
        List<Instant> times =
                page.get("items")
                        .valueStream()
                        .map(i -> Instant.parse(i.get("requestedAt").asString()))
                        .toList();
        assertThat(times).hasSizeGreaterThan(1).isSorted();
    }

    @Test
    void C_13_aPausedJobOffersResumeAndStopAndTheListShowsAPendingCommand() throws Exception {
        Session maker = maker();
        long id = pendingJob(maker, 13);
        // Paused by a batch node: no end time, so the restart window runs from the request.
        jdbc.update(
                "UPDATE batch_job_request SET job_status = 'PAUSED' WHERE job_request_id = ?", id);
        assertThat(offered(maker, id)).containsExactlyInAnyOrder("STOP", "RESUME", "ABANDON");
        assertThat(control(maker, id, "resume", "Carry on").getResponse().getStatus())
                .isBetween(200, 299);
        JsonNode page =
                body(
                        mvc.perform(userGet(maker, "/v1/jobs?size=100&status=PAUSED"))
                                .andExpect(status().isOk())
                                .andReturn());
        JsonNode item =
                page.get("items")
                        .valueStream()
                        .filter(i -> i.get("id").asLong() == id)
                        .findFirst()
                        .orElseThrow();
        assertThat(item.get("pendingCommand").asString()).isEqualTo("PENDING");
        assertThat(item.get("allowedCommands")).isEmpty();
    }
}
