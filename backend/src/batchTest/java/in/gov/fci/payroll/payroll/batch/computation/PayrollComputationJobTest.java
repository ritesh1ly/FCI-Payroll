package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.BatchTestSupport;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.support.OutageTrigger;
import in.gov.fci.payroll.jobs.business.JobParameterCodec;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.business.CycleGates;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * PAYROLL_COMPUTATION, the ACTUAL run of a FROZEN cycle (C-02; SRS-15-13, SRS-15-18, SRS-15-27;
 * BR-CYC-012, BR-CYC-014; target DDL v4.0 S08). The completed run is the run of record: the cycle
 * becomes COMPUTED and the sources of gates G1 (engine status), G4 (control total) and G6
 * (quarantine) are written. A re-computation supersedes the earlier run and its results, counts the
 * re-run and puts a reviewed quarantine entry back to review. A cycle not initiated, not frozen or
 * locked is refused before any result is written. A run that fails quarantines what it wrote and
 * leaves the cycle FROZEN; resumed to completion, the same run is of record with every result
 * CURRENT.
 */
class PayrollComputationJobTest extends BatchTestSupport {

    @Autowired
    @Qualifier("payrollComputationJob")
    private Job computation;

    @Autowired
    @Qualifier("payrollDryRunJob")
    private Job dryRun;

    @Autowired private in.gov.fci.payroll.platform.cache.CacheRegions regions;

    @AfterEach
    void disarm() {
        OutageTrigger.arm(-1);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_test_outage_actual");
    }

    // ---------------------------------------------------------------- fixtures

    /** A cycle instance of the unit in {@code status}, as C-02 would have left it. */
    private long cycle(Unit unit, String period, String status) {
        OrgLocation location = cycles.cycleUnit(unit.locationId());
        YearMonth month = YearMonth.parse(period);
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by) VALUES (?, ?, ?, ?,"
                        + " ?, 'ON_DEMAND', 'test.maker')",
                CycleKeys.yymm(month),
                location.getId(),
                CycleKeys.level(location.getType()),
                CycleKeys.finYear(month),
                status);
        return jdbc.queryForObject(
                "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?",
                Long.class,
                CycleKeys.yymm(month),
                location.getId());
    }

    /** An approved M-14 floor for the month: a net pay below {@code amount} breaches it. */
    private void absoluteFloor(String period, BigDecimal amount) {
        String key = "it-floor-" + period;
        jdbc.update(
                "INSERT INTO approval_queue (entity_name, entity_key, action_type, payload,"
                        + " submitted_by, status) VALUES ('net_pay_floor_rule', ?, 'CREATE', '{}',"
                        + " 'test.maker', 'APPROVED')",
                key);
        Long approval =
                jdbc.queryForObject(
                        "SELECT MAX(approval_id) FROM approval_queue WHERE entity_key = ?",
                        Long.class,
                        key);
        YearMonth month = YearMonth.parse(period);
        jdbc.update(
                "INSERT INTO net_pay_floor_rule (payroll_type, floor_basis, floor_value,"
                        + " is_confirmed, effective_from, effective_to, approval_id) VALUES"
                        + " ('STAFF', 'ABSOLUTE_AMOUNT', ?, 'Y', ?, ?, ?)",
                amount,
                Date.valueOf(month.atDay(1)),
                Date.valueOf(month.atEndOfMonth()),
                approval);
    }

    private BatchJobRequest request(JobType type, Unit unit, String period) {
        return pickedRequest(
                type,
                Map.of("locationId", unit.locationId().toString(), "payPeriod", period),
                unit.locationId());
    }

    /**
     * How a launch ended. Not a {@code JobExecution}: {@code @SpringBatchTest} invokes every
     * test-class method returning one as a job-scope factory.
     */
    private record Outcome(BatchStatus status, long written) {}

    private Outcome run(Job job, BatchJobRequest request) throws Exception {
        jobLauncher.setJob(job);
        JobExecution execution = jobLauncher.startJob(JobLaunchService.parameters(request));
        return new Outcome(
                execution.getStatus(),
                execution.getStepExecutions().stream()
                        .filter(s -> s.getStepName().contains(":partition"))
                        .mapToLong(StepExecution::getWriteCount)
                        .sum());
    }

    /** The computation_run row the request opened (its parameters name the request). */
    private Map<String, Object> runOf(BatchJobRequest request) {
        return jdbc.queryForMap(
                "SELECT * FROM computation_run WHERE parameters_json LIKE ?",
                "{\"jobRequestId\":" + request.getId() + ",%");
    }

    private static long id(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private List<String> statuses(long runId) {
        return jdbc.queryForList(
                "SELECT DISTINCT result_status FROM payroll_result WHERE run_id = ?",
                String.class,
                runId);
    }

    /** Members of the cycle the run could not compute (open COMPUTATION_BLOCKED on X-05). */
    private long blocked(long cycleId) {
        return jdbc.queryForObject(
                "SELECT COUNT(DISTINCT employee_id) FROM payroll_exception WHERE cycle_instance_id"
                        + " = ? AND exception_code = 'COMPUTATION_BLOCKED' AND exception_status ="
                        + " 'OPEN'",
                Long.class,
                cycleId);
    }

    private static final String REASON =
            "SELECT employee_id, CASE WHEN net_pay < 0 THEN 'NEGATIVE_NET' WHEN net_pay = 0 THEN"
                    + " 'ZERO_NET' ELSE 'FLOOR_BREACH' END FROM payroll_result WHERE run_id = ?"
                    + " AND ";

    /** "employee:reason" of every result the G6 rule must queue with no floor in force. */
    private List<String> notPositive(long runId) {
        return jdbc.query(
                REASON + "net_pay <= 0 ORDER BY employee_id",
                (rs, i) -> rs.getLong(1) + ":" + rs.getString(2),
                runId);
    }

    /** "employee:reason" of every result the G6 rule must queue under an absolute floor. */
    private List<String> belowTheFloor(long runId, BigDecimal floor) {
        return jdbc.query(
                REASON + "(net_pay <= 0 OR net_pay < ?) ORDER BY employee_id",
                (rs, i) -> rs.getLong(1) + ":" + rs.getString(2),
                runId,
                floor);
    }

    private List<String> queued(long cycleId, String review) {
        return jdbc.query(
                "SELECT employee_id, quarantine_reason FROM payroll_quarantine WHERE"
                        + " cycle_instance_id = ? AND review_status = ? ORDER BY employee_id",
                (rs, i) -> rs.getLong(1) + ":" + rs.getString(2),
                cycleId,
                review);
    }

    // ---------------------------------------------------------------- the run of record

    // SC-16-0090: the computed result is saved as a draft (unlocked) result set for the Checker.
    @Test
    void SRS_15_18_theActualRunOfAFrozenCycleIsTheRunOfRecordAndWritesTheGateSources()
            throws Exception {
        Unit unit = busiestUnit();
        long cycleId = cycle(unit, "2026-08", "FROZEN");
        BatchJobRequest request = request(JobType.PAYROLL_COMPUTATION, unit, "2026-08");

        Outcome outcome = run(computation, request);

        assertThat(outcome.status()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> run = runOf(request);
        long runId = id(run, "RUN_ID");
        long results = results(request.getId());
        assertThat(results).as("demo rates are seeded").isPositive().isEqualTo(outcome.written());
        assertThat(run.get("RUN_TYPE")).isEqualTo("ACTUAL");
        assertThat(run.get("RUN_STATUS")).isEqualTo("COMPLETE");
        assertThat(id(run, "RUN_NO")).isEqualTo(1);
        assertThat(id(run, "RESULT_COUNT")).isEqualTo(results);
        // G0 (SRS l.1519, BMAD TR-077): the eligible population is counted apart from the results:
        // the computed members plus those the run could not compute (flagged on X-05), never the
        // written count alone.
        assertThat(id(run, "ELIGIBLE_COUNT")).isEqualTo(results + blocked(cycleId));
        assertThat(id(run, "UNIT_FAILED")).isZero();
        assertThat(statuses(runId)).containsExactly("CURRENT");
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT is_locked FROM payroll_result WHERE run_id = ?",
                                String.class,
                                runId))
                .as("a draft for the Checker, not locked")
                .containsExactly("N");
        Map<String, Object> instance =
                jdbc.queryForMap(
                        "SELECT cycle_status, current_run_id FROM payroll_cycle_instance WHERE"
                                + " cycle_instance_id = ?",
                        cycleId);
        assertThat(instance.get("CYCLE_STATUS")).isEqualTo("COMPUTED");
        assertThat(id(instance, "CURRENT_RUN_ID")).isEqualTo(runId);

        // G1: the staff engine completed for the members it computed.
        Map<String, Object> engine =
                jdbc.queryForMap(
                        "SELECT engine_state, member_count, rerun_count, failure_detail FROM"
                                + " cycle_engine_status WHERE cycle_instance_id = ? AND"
                                + " payroll_type = 'STAFF'",
                        cycleId);
        assertThat(engine.get("ENGINE_STATE")).isEqualTo("COMPLETE");
        assertThat(id(engine, "MEMBER_COUNT")).isEqualTo(results);
        assertThat(id(engine, "RERUN_COUNT")).isZero();
        assertThat(engine.get("FAILURE_DETAIL")).isNull();

        // G4: the control total is the run's own totals.
        Map<String, Object> sums =
                jdbc.queryForMap(
                        "SELECT COUNT(*) AS members, SUM(gross_earnings) AS gross,"
                                + " SUM(total_deductions) AS deductions, SUM(net_pay) AS net FROM"
                                + " payroll_result WHERE run_id = ?",
                        runId);
        Map<String, Object> control =
                jdbc.queryForMap(
                        "SELECT member_count, gross_total, deduction_total, net_total FROM"
                                + " cycle_control_total WHERE cycle_instance_id = ? AND"
                                + " payroll_type = 'STAFF' AND total_source = 'PAYROLL_RESULT'",
                        cycleId);
        assertThat(id(control, "MEMBER_COUNT")).isEqualTo(id(sums, "MEMBERS"));
        assertThat((BigDecimal) control.get("GROSS_TOTAL"))
                .isEqualByComparingTo((BigDecimal) sums.get("GROSS"));
        assertThat((BigDecimal) control.get("DEDUCTION_TOTAL"))
                .isEqualByComparingTo((BigDecimal) sums.get("DEDUCTIONS"));
        assertThat((BigDecimal) control.get("NET_TOTAL"))
                .isEqualByComparingTo((BigDecimal) sums.get("NET"));
        // G4 (SRS-15-18, FRS V0.5 6.11.4 Step 6): the Salary Control Register (from the signed
        // lines by component type) and the disbursement register are written with the run and
        // reconcile with its results, so the hard gate is met by the producers, not a fixture.
        List<CycleGates.Total> totals =
                jdbc.query(
                        "SELECT total_source, member_count, gross_total, deduction_total,"
                                + " net_total FROM cycle_control_total WHERE cycle_instance_id = ?"
                                + " AND payroll_type = 'STAFF' ORDER BY total_source",
                        (rs, i) ->
                                new CycleGates.Total(
                                        rs.getString(1),
                                        rs.getLong(2),
                                        rs.getBigDecimal(3),
                                        rs.getBigDecimal(4),
                                        rs.getBigDecimal(5)),
                        cycleId);
        assertThat(totals)
                .extracting(CycleGates.Total::source)
                .containsExactly(
                        "DISBURSEMENT_REGISTER", "PAYROLL_RESULT", "SALARY_CONTROL_REGISTER");
        CycleGates.Gate g4 = CycleGates.g4(Map.of("STAFF", totals));
        assertThat(g4.met()).as(g4.detail()).isTrue();
        // G5 (SRS-15-18, SRS l.1524): CPF / PF, income tax, PT and the NPS deduction ran for every
        // member computed; the members the run could not compute are left uncomputed.
        List<CycleGates.Statutory> statutory =
                jdbc.query(
                        "SELECT statutory_process, process_state, members_uncomputed,"
                                + " members_computed FROM cycle_statutory_status WHERE"
                                + " cycle_instance_id = ?",
                        (rs, i) -> {
                            assertThat(rs.getLong(4)).isEqualTo(results);
                            return new CycleGates.Statutory(
                                    rs.getString(1), rs.getString(2), rs.getLong(3));
                        },
                        cycleId);
        assertThat(statutory)
                .extracting(CycleGates.Statutory::process)
                .containsExactlyInAnyOrderElementsOf(CycleGates.STATUTORY);
        assertThat(statutory)
                .allSatisfy(
                        st -> {
                            assertThat(st.state()).isEqualTo("COMPLETE");
                            assertThat(st.uncomputed()).isEqualTo(blocked(cycleId));
                        });
        assertThat(CycleGates.g5(statutory).met()).isEqualTo(blocked(cycleId) == 0);
        // R-AUD: the registers are audited in the run's transaction.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name IN"
                                        + " ('cycle_control_total', 'cycle_statutory_status') AND"
                                        + " entity_key LIKE ?",
                                Integer.class,
                                cycleId + ":%"))
                .isEqualTo(6);

        // G6 without an M-14 floor in force: only a zero or negative net waits for review.
        assertThat(queued(cycleId, "UNREVIEWED")).containsExactlyElementsOf(notPositive(runId));
        BatchJobRequest done = requests.findById(request.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    /**
     * UC-01 alternate flow, SRS-G-9 (US-GAP-1): a member the Actual run cannot compute is flagged
     * on the X-05 worklist (BLOCKING, gate G2), the rest is computed; once the member computes in a
     * later Actual run the exception is cleared, naming the run.
     */
    @Test
    void SRS_G_9_aMemberTheActualRunCannotComputeIsFlaggedOnTheWorklistUntilItComputes()
            throws Exception {
        Unit unit = busiestUnit();
        long cycleId = cycle(unit, "2026-10", "FROZEN");
        Long member =
                jdbc.queryForObject(
                        "SELECT MIN(e.employee_id) FROM employee e WHERE e.employee_type = 'STAFF'"
                                + " AND e.pay_pattern IS NOT NULL AND e.current_basic > 0 AND"
                                + " e.location_id IN ("
                                + org.subtree(unit.locationId()).stream()
                                        .map(String::valueOf)
                                        .collect(java.util.stream.Collectors.joining(","))
                                + ")",
                        Long.class);
        String pattern =
                jdbc.queryForObject(
                        "SELECT pay_pattern FROM employee WHERE employee_id = ?",
                        String.class,
                        member);
        String blocked =
                "SELECT exception_status, severity, blocks_gate, module_code, detail FROM"
                        + " payroll_exception WHERE exception_code = 'COMPUTATION_BLOCKED' AND"
                        + " cycle_instance_id = ? AND employee_id = ?";
        jdbc.update("UPDATE employee SET pay_pattern = NULL WHERE employee_id = ?", member);
        try {
            BatchJobRequest first = request(JobType.PAYROLL_COMPUTATION, unit, "2026-10");
            Outcome outcome = run(computation, first);
            assertThat(outcome.status()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(outcome.written()).as("the rest is computed").isPositive();
            // G0 (BMAD TR-077): the blocked member stays eligible, so the run does not reconcile.
            Map<String, Object> firstRun = runOf(first);
            assertThat(blocked(cycleId)).isPositive();
            assertThat(id(firstRun, "ELIGIBLE_COUNT"))
                    .isEqualTo(id(firstRun, "RESULT_COUNT") + blocked(cycleId));
            Map<String, Object> raised = jdbc.queryForMap(blocked, cycleId, member);
            assertThat(raised)
                    .containsEntry("EXCEPTION_STATUS", "OPEN")
                    .containsEntry("SEVERITY", "BLOCKING")
                    .containsEntry("BLOCKS_GATE", "G2")
                    .containsEntry("MODULE_CODE", "C-02");
            assertThat((String) raised.get("DETAIL")).contains("No pay pattern");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM payroll_result WHERE run_id = ? AND"
                                            + " employee_id = ?",
                                    Integer.class,
                                    id(runOf(first), "RUN_ID"),
                                    member))
                    .isZero();
        } finally {
            jdbc.update(
                    "UPDATE employee SET pay_pattern = ? WHERE employee_id = ?", pattern, member);
        }
        BatchJobRequest second = request(JobType.PAYROLL_COMPUTATION, unit, "2026-10");
        assertThat(run(computation, second).status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForMap(blocked, cycleId, member))
                .as("one entry, cleared by the run that computed the member")
                .containsEntry("EXCEPTION_STATUS", "RESOLVED");
    }

    @Test
    void SRS_15_13_aReComputationSupersedesTheEarlierRunAndPutsReviewedEntriesBackToReview()
            throws Exception {
        Unit unit = busiestUnit();
        long cycleId = cycle(unit, "2026-12", "FROZEN");
        BatchJobRequest first = request(JobType.PAYROLL_COMPUTATION, unit, "2026-12");
        assertThat(run(computation, first).status()).isEqualTo(BatchStatus.COMPLETED);
        long firstRun = id(runOf(first), "RUN_ID");
        assertThat(queued(cycleId, "UNREVIEWED")).containsExactlyElementsOf(notPositive(firstRun));

        // An M-14 floor at the highest net is approved; the cycle is re-computed.
        BigDecimal floor =
                jdbc.queryForObject(
                        "SELECT MAX(net_pay) FROM payroll_result WHERE run_id = ?",
                        BigDecimal.class,
                        firstRun);
        absoluteFloor("2026-12", floor);
        BatchJobRequest second = request(JobType.PAYROLL_COMPUTATION, unit, "2026-12");
        assertThat(run(computation, second).status()).isEqualTo(BatchStatus.COMPLETED);
        long secondRun = id(runOf(second), "RUN_ID");
        List<String> below = belowTheFloor(secondRun, floor);
        assertThat(below).as("the demo pay varies by member").isNotEmpty();
        assertThat(queued(cycleId, "UNREVIEWED")).containsExactlyElementsOf(below);
        assertThat(below.size())
                .as("a member paid the highest net is at the floor, not below it")
                .isLessThan((int) results(second.getId()));

        // A checker accepts one queued member before the next re-computation.
        String reviewed = below.getFirst();
        long member = Long.parseLong(reviewed.substring(0, reviewed.indexOf(':')));
        jdbc.update(
                "UPDATE payroll_quarantine SET review_status = 'ACCEPTED', reviewed_by = 'checker',"
                        + " review_reason = 'Recovery agreed' WHERE cycle_instance_id = ? AND"
                        + " employee_id = ?",
                cycleId,
                member);

        BatchJobRequest third = request(JobType.PAYROLL_COMPUTATION, unit, "2026-12");
        assertThat(run(computation, third).status()).isEqualTo(BatchStatus.COMPLETED);

        Map<String, Object> rerun = runOf(third);
        long thirdRun = id(rerun, "RUN_ID");
        assertThat(id(rerun, "RUN_NO")).isEqualTo(3);
        for (BatchJobRequest earlier : List.of(first, second)) {
            assertThat(runOf(earlier).get("RUN_STATUS")).isEqualTo("SUPERSEDED");
        }
        assertThat(statuses(firstRun)).containsExactly("SUPERSEDED");
        assertThat(statuses(secondRun)).containsExactly("SUPERSEDED");
        assertThat(statuses(thirdRun)).containsExactly("CURRENT");
        assertThat(
                        id(
                                jdbc.queryForMap(
                                        "SELECT current_run_id FROM payroll_cycle_instance WHERE"
                                                + " cycle_instance_id = ?",
                                        cycleId),
                                "CURRENT_RUN_ID"))
                .isEqualTo(thirdRun);
        // One engine row and one control total per source and cycle: each re-run updates them.
        assertThat(
                        jdbc.queryForList(
                                "SELECT rerun_count FROM cycle_engine_status WHERE"
                                        + " cycle_instance_id = ?",
                                Integer.class,
                                cycleId))
                .containsExactly(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM cycle_control_total WHERE cycle_instance_id"
                                        + " = ?",
                                Integer.class,
                                cycleId))
                .as("PAYROLL_RESULT, SALARY_CONTROL_REGISTER, DISBURSEMENT_REGISTER")
                .isEqualTo(3);
        // OPEN-QUESTION CLR-134 (BMAD TR-080): whether an acceptance survives a re-computation is
        // not stated; as built the member still below the floor is queued again, its review
        // cleared.
        assertThat(queued(cycleId, "ACCEPTED")).isEmpty();
        assertThat(queued(cycleId, "UNREVIEWED"))
                .containsExactlyElementsOf(belowTheFloor(thirdRun, floor));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reviewed_by FROM payroll_quarantine WHERE"
                                        + " cycle_instance_id = ? AND employee_id = ?",
                                String.class,
                                cycleId,
                                member))
                .isNull();
    }

    // ---------------------------------------------------------------- refusals

    /** "event:holder" of the instance's lease log, oldest first. */
    private List<String> leaseEvents(long cycleId) {
        return jdbc.queryForList(
                "SELECT event_type || ':' || held_by FROM cycle_lease_log WHERE cycle_instance_id"
                        + " = ? ORDER BY lease_log_id",
                String.class,
                cycleId);
    }

    /**
     * SRS-15-28 / BR-CYC-015 (plan 05 phase 05c): the run holds the instance's lease from its
     * opening to its close (the log shows the pair, nothing is held after), and a run asked while
     * another action holds the lease fails naming the holder, before any member is read.
     */
    @Test
    void SRS_15_28_aRunHoldsTheCycleLeaseAndIsRefusedWhileAnotherActionHoldsIt() throws Exception {
        Unit unit = busiestUnit();
        long cycleId = cycle(unit, "2026-11", "FROZEN");
        BatchJobRequest request = request(JobType.PAYROLL_COMPUTATION, unit, "2026-11");
        assertThat(run(computation, request).status()).isEqualTo(BatchStatus.COMPLETED);
        String requester = (String) runOf(request).get("STARTED_BY");
        assertThat(leaseEvents(cycleId))
                .containsExactly("ACQUIRED:" + requester, "RELEASED:" + requester);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM cycle_lease WHERE cycle_instance_id = ?",
                                Integer.class,
                                cycleId))
                .isZero();

        // A freeze in flight holds the instance (its row stands in for the transaction).
        long held = cycle(unit, "2027-07", "FROZEN");
        // SRS-15-28 (BMAD TR-069): the holder, the action and since when.
        java.sql.Timestamp since =
                java.sql.Timestamp.from(java.time.Instant.parse("2027-06-30T04:15:00Z"));
        jdbc.update(
                "INSERT INTO cycle_lease (cycle_instance_id, lease_action, held_by, acquired_at,"
                        + " heartbeat_at) VALUES (?, 'FREEZE', 'maker.busy', ?, CURRENT_TIMESTAMP)",
                held,
                since);
        BatchJobRequest refused = request(JobType.PAYROLL_COMPUTATION, unit, "2027-07");
        assertThat(run(computation, refused).status()).isEqualTo(BatchStatus.FAILED);
        BatchJobRequest failed = requests.findById(refused.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("CYCLE_LEASE_HELD", "maker.busy", "FREEZE");
        assertThat(failed.getErrorMessage()).contains("since 2027-06-30 09:45 IST");
        assertThat(results(refused.getId())).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM computation_run WHERE cycle_instance_id = ?",
                                Integer.class,
                                held))
                .as("the run's row rolled back with the refusal")
                .isZero();
        assertThat(leaseEvents(held)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT held_by FROM cycle_lease WHERE cycle_instance_id = ?",
                                String.class,
                                held))
                .isEqualTo("maker.busy");
    }

    /**
     * SRS-15-30 / plan 05 D-9: a Checker's return is answered by the next Actual run completed
     * after it (RECOMPUTED, the run named), and the cycle can be verified again (COMPUTED). A
     * return already answered or withdrawn is left as it is.
     */
    @Test
    void SRS_15_30_aFreshComputationAfterTheReturnAnswersItAndTheCycleIsComputedAgain()
            throws Exception {
        Unit unit = busiestUnit();
        long cycleId = cycle(unit, "2027-06", "FROZEN");
        jdbc.update(
                "INSERT INTO computation_run (cycle_instance_id, run_type, run_no, payroll_type,"
                        + " run_status, started_by, started_at, parameter_hash,"
                        + " rule_version_set_hash) VALUES (?, 'ACTUAL', 1, 'STAFF', 'SUPERSEDED',"
                        + " 'test.maker', DATEADD('HOUR', -2, CURRENT_TIMESTAMP), 'x', 'x')",
                cycleId);
        long returned =
                jdbc.queryForObject(
                        "SELECT run_id FROM computation_run WHERE cycle_instance_id = ?",
                        Long.class,
                        cycleId);
        String insert =
                "INSERT INTO cycle_return (cycle_instance_id, run_id, return_scope, from_status,"
                        + " reason, returned_by, returned_at) VALUES (?, ?, 'INSTANCE',"
                        + " 'VERIFIED', 'Totals do not tally', 'test.checker', ?)";
        jdbc.update(
                insert,
                cycleId,
                returned,
                java.sql.Timestamp.from(clock.instant().minusSeconds(3600)));
        jdbc.update(
                "INSERT INTO cycle_return (cycle_instance_id, run_id, return_scope, from_status,"
                        + " reason, returned_by, returned_at, return_status, withdrawn_by,"
                        + " withdrawal_reason) VALUES (?, ?, 'INSTANCE', 'VERIFIED', 'Wrong"
                        + " month', 'test.checker', ?, 'WITHDRAWN', 'test.checker', 'Raised in"
                        + " error')",
                cycleId,
                returned,
                java.sql.Timestamp.from(clock.instant().minusSeconds(1800)));

        BatchJobRequest request = request(JobType.PAYROLL_COMPUTATION, unit, "2027-06");
        assertThat(run(computation, request).status()).isEqualTo(BatchStatus.COMPLETED);
        long runId = id(runOf(request), "RUN_ID");
        assertThat(
                        jdbc.query(
                                "SELECT return_status, recompute_run_id FROM cycle_return WHERE"
                                        + " cycle_instance_id = ? ORDER BY returned_at",
                                (rs, i) -> rs.getString(1) + ":" + rs.getObject(2),
                                cycleId))
                .containsExactly("RECOMPUTED:" + runId, "WITHDRAWN:null");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT cycle_status FROM payroll_cycle_instance WHERE"
                                        + " cycle_instance_id = ?",
                                String.class,
                                cycleId))
                .isEqualTo("COMPUTED");
    }

    /**
     * G0 (SRS l.1519) and SRS-15-24 (BMAD TR-075): the Actual run of a FROZEN cycle whose unit has
     * no member to compute completes with an empty population - eligible 0, results 0, so the
     * result count matches the eligible population - and the cycle is COMPUTED with zero control
     * totals, never failed. Every demo cycle unit has staff, so a Divisional Office of its own is
     * added to the hierarchy for the test (the office cache reloads on the region's version) and
     * closed again afterwards (inactive, never deleted: R-DEL).
     */
    @Test
    void SRS_15_24_G0_anActualRunOfAUnitWithNoMemberCompletesWithAnEmptyPopulation()
            throws Exception {
        String code = "DO-IT-EMPTY";
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM org_location WHERE location_code = ?",
                        Integer.class,
                        code)
                == 0) {
            jdbc.update(
                    "INSERT INTO org_location (location_code, location_name, location_type,"
                            + " parent_location_id, zone_id, city_class, is_cycle_unit) SELECT ?,"
                            + " 'Empty Divisional Office (test)', 'DO', location_id, zone_id,"
                            + " city_class, 'Y' FROM org_location WHERE location_code = 'RO-MH'",
                    code);
        }
        jdbc.update("UPDATE org_location SET active_flag = 'Y' WHERE location_code = ?", code);
        organisationChanged();
        try {
            Long id =
                    jdbc.queryForObject(
                            "SELECT location_id FROM org_location WHERE location_code = ?",
                            Long.class,
                            code);
            assertThat(cycles.cycleUnit(id).getId()).as("a cycle unit of its own").isEqualTo(id);
            Unit unit = unitOf(id);
            assertThat(unit.activeEmployees()).as("nobody to compute").isZero();
            long cycleId = cycle(unit, "2026-07", "FROZEN");
            BatchJobRequest request = request(JobType.PAYROLL_COMPUTATION, unit, "2026-07");

            Outcome outcome = run(computation, request);

            assertThat(outcome.status()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(outcome.written()).isZero();
            Map<String, Object> run = runOf(request);
            assertThat(run.get("RUN_STATUS")).isEqualTo("COMPLETE");
            assertThat(id(run, "ELIGIBLE_COUNT")).as("G0: eligible 0").isZero();
            assertThat(id(run, "RESULT_COUNT")).as("G0: results 0 = eligible").isZero();
            assertThat(id(run, "UNIT_FAILED")).isZero();
            assertThat(results(request.getId())).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT cycle_status FROM payroll_cycle_instance WHERE"
                                            + " cycle_instance_id = ?",
                                    String.class,
                                    cycleId))
                    .isEqualTo("COMPUTED");
            List<Map<String, Object>> control =
                    jdbc.queryForList(
                            "SELECT member_count, gross_total, net_total FROM cycle_control_total"
                                    + " WHERE cycle_instance_id = ? AND payroll_type = 'STAFF'"
                                    + " AND total_source = 'PAYROLL_RESULT'",
                            cycleId);
            assertThat(control)
                    .allSatisfy(
                            c -> {
                                assertThat(id(c, "MEMBER_COUNT")).isZero();
                                assertThat((BigDecimal) c.get("GROSS_TOTAL"))
                                        .isEqualByComparingTo("0");
                                assertThat((BigDecimal) c.get("NET_TOTAL"))
                                        .isEqualByComparingTo("0");
                            });
            assertThat(queued(cycleId, "UNREVIEWED")).isEmpty();
            assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
                    .isEqualTo(JobStatus.COMPLETED);
        } finally {
            jdbc.update("UPDATE org_location SET active_flag = 'N' WHERE location_code = ?", code);
            organisationChanged();
        }
    }

    /** Marks the office hierarchy changed, so every OrgDirectory reloads it. */
    private void organisationChanged() {
        regions.changed(in.gov.fci.payroll.organisation.api.OrgDirectory.REGION, "test");
    }

    @Test
    // BMAD TR-065: the computation preconditions (re-freeze before the next computation), not G0.
    void SRS_15_31_BR_CYC_017_aCycleNotInitiatedNotFrozenOrLockedIsRefusedBeforeAnyResult()
            throws Exception {
        Unit unit = busiestUnit();
        cycle(unit, "2027-02", "INITIATED");
        cycle(unit, "2027-03", "LOCKED");
        Map<String, String> refusals =
                Map.of(
                        "2027-01", "CYCLE_NOT_INITIATED",
                        "2027-02", "CYCLE_NOT_FROZEN",
                        "2027-03", "CYCLE_LOCKED");
        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            BatchJobRequest request = request(JobType.PAYROLL_COMPUTATION, unit, refusal.getKey());
            Outcome outcome = run(computation, request);
            assertThat(outcome.status()).as(refusal.getKey()).isEqualTo(BatchStatus.FAILED);
            BatchJobRequest failed = requests.findById(request.getId()).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(failed.getErrorMessage()).contains(refusal.getValue());
            assertThat(results(request.getId())).isZero();
        }
        // A dry run is refused on a locked cycle too, and needs the month in the calendar (M-10).
        BatchJobRequest locked = request(JobType.PAYROLL_DRY_RUN, unit, "2027-03");
        assertThat(run(dryRun, locked).status()).isEqualTo(BatchStatus.FAILED);
        assertThat(requests.findById(locked.getId()).orElseThrow().getErrorMessage())
                .contains("CYCLE_LOCKED");
        BatchJobRequest outsideCalendar = request(JobType.PAYROLL_DRY_RUN, unit, "2031-01");
        assertThat(run(dryRun, outsideCalendar).status()).isEqualTo(BatchStatus.FAILED);
        assertThat(requests.findById(outsideCalendar.getId()).orElseThrow().getErrorMessage())
                .contains("NO_PAYROLL_CALENDAR");
    }

    @Test
    // BMAD TR-066: the run's starting user (SRS-15-24 / BR-CYC-011). OPEN-QUESTION CLR-135: DDL
    // computation_run.started_by holds 50 characters, a login up to 150 - cut as built.
    void SRS_15_24_aRequesterLoginLongerThanTheRunsColumnIsCutToFit() throws Exception {
        Unit unit = busiestUnit();
        cycle(unit, "2027-05", "FROZEN");
        String login = "a.very.long.officer.login.for.a.regional.office@fci-demo.test";
        Map<String, String> params =
                Map.of("locationId", unit.locationId().toString(), "payPeriod", "2027-05");
        BatchJobRequest request =
                BatchJobRequest.submit(
                        JobType.PAYROLL_COMPUTATION,
                        JobParameterCodec.toJson(params),
                        JobParameterCodec.hash(JobType.PAYROLL_COMPUTATION.name(), params),
                        5,
                        BatchJobRequest.Source.UI,
                        null,
                        login,
                        unit.locationId(),
                        "2027-05",
                        clock.instant());
        request = requests.save(request);
        request.pick("test-node", clock.instant());
        request = requests.save(request);

        assertThat(run(computation, request).status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(runOf(request).get("STARTED_BY")).isEqualTo(login.substring(0, 50));
    }

    // ---------------------------------------------------------------- failure and resume

    // SC-05-0091: a run failed part-way is not verified or locked until the Maker resumes it.
    @Test
    void BR_CYC_012_aFailedRunQuarantinesItsResultsAndOnceResumedEveryResultIsCurrent()
            throws Exception {
        Unit unit = busiestUnit();
        assertThat(unit.activeEmployees()).as("more than one chunk").isGreaterThan(5);
        long cycleId = cycle(unit, "2027-04", "FROZEN");
        jdbc.execute(
                "CREATE TRIGGER trg_test_outage_actual BEFORE INSERT ON payroll_result FOR EACH"
                        + " ROW CALL \""
                        + OutageTrigger.class.getName()
                        + "\"");
        OutageTrigger.arm(unit.activeEmployees() > 6 ? 7 : 6);
        BatchJobRequest request = request(JobType.PAYROLL_COMPUTATION, unit, "2027-04");

        Outcome failed = run(computation, request);

        assertThat(failed.status()).isEqualTo(BatchStatus.FAILED);
        Map<String, Object> run = runOf(request);
        long runId = id(run, "RUN_ID");
        assertThat(run.get("RUN_STATUS")).isEqualTo("FAILED");
        assertThat(run.get("FAILURE_CLASS")).isEqualTo("INFRASTRUCTURE");
        assertThat(results(request.getId())).as("committed chunks are kept").isPositive();
        assertThat(statuses(runId))
                .as("visible for investigation, never of record")
                .containsExactly("QUARANTINED");
        Map<String, Object> engine =
                jdbc.queryForMap(
                        "SELECT engine_state, failure_detail FROM cycle_engine_status WHERE"
                                + " cycle_instance_id = ? AND payroll_type = 'STAFF'",
                        cycleId);
        assertThat(engine.get("ENGINE_STATE")).isEqualTo("FAILED");
        assertThat((String) engine.get("FAILURE_DETAIL"))
                .isEqualTo("Run " + runId + " did not complete every work unit");
        // SRS-NF-7 / SRS-15-24 (BMAD TR-073): a failure stops only the affected work unit - the
        // other units are committed with their members, and the run counts both.
        Map<String, Object> units =
                jdbc.queryForMap(
                        "SELECT SUM(CASE WHEN unit_status = 'FAILED' THEN 1 ELSE 0 END) AS failed,"
                                + " SUM(CASE WHEN unit_status = 'COMMITTED' THEN 1 ELSE 0 END) AS"
                                + " committed, SUM(CASE WHEN unit_status = 'COMMITTED' THEN"
                                + " results_committed ELSE 0 END) AS rows_committed FROM"
                                + " computation_work_unit WHERE run_id = ?",
                        runId);
        assertThat(id(units, "FAILED")).as("the affected unit").isPositive();
        assertThat(id(units, "COMMITTED")).as("the other units go on").isPositive();
        assertThat(id(units, "ROWS_COMMITTED")).isPositive();
        assertThat(id(run, "UNIT_FAILED")).isEqualTo(id(units, "FAILED"));
        assertThat(id(run, "UNIT_COMMITTED")).isEqualTo(id(units, "COMMITTED"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT cycle_status FROM payroll_cycle_instance WHERE"
                                        + " cycle_instance_id = ?",
                                String.class,
                                cycleId))
                .isEqualTo("FROZEN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM cycle_control_total WHERE cycle_instance_id"
                                        + " = ?",
                                Integer.class,
                                cycleId))
                .as("no control total for a failed run")
                .isZero();
        // G5 (SRS-EH-6): a failed run's statutory sub-processes are FAILED, never complete.
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT process_state FROM cycle_statutory_status WHERE"
                                        + " cycle_instance_id = ?",
                                String.class,
                                cycleId))
                .containsExactly("FAILED");

        // Resume (BR-CYC-012): the committed units stay, the rest is computed, the same run.
        OutageTrigger.arm(-1);
        BatchJobRequest retry = requests.findById(request.getId()).orElseThrow();
        retry.pick("test-node", clock.instant());
        retry = requests.save(retry);
        Outcome resumed = run(computation, retry);

        assertThat(resumed.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(id(runOf(request), "RUN_ID")).isEqualTo(runId);
        assertThat(runOf(request).get("RUN_STATUS")).isEqualTo("COMPLETE");
        assertThat(results(request.getId()))
                .as("every computed member exactly once")
                .isEqualTo(failed.written() + resumed.written());
        // SRS-NF-7 / BR-CYC-012 (BMAD TR-070): no member twice, none lost - distinct members equal
        // the rows, and the rows equal the eligible population of the run.
        Map<String, Object> once =
                jdbc.queryForMap(
                        "SELECT COUNT(*) AS n, COUNT(DISTINCT employee_id) AS d FROM payroll_result"
                                + " WHERE run_id = ?",
                        runId);
        assertThat(id(once, "D")).isEqualTo(id(once, "N"));
        assertThat(id(once, "N") + blocked(cycleId))
                .as("the whole eligible population over both executions")
                .isEqualTo(id(runOf(request), "ELIGIBLE_COUNT"));
        assertThat(statuses(runId))
                .as("the complete run of record: no result left quarantined")
                .containsExactly("CURRENT");
        Map<String, Object> after =
                jdbc.queryForMap(
                        "SELECT engine_state, rerun_count, failure_detail FROM cycle_engine_status"
                                + " WHERE cycle_instance_id = ? AND payroll_type = 'STAFF'",
                        cycleId);
        assertThat(after.get("ENGINE_STATE")).isEqualTo("COMPLETE");
        assertThat(id(after, "RERUN_COUNT")).isEqualTo(1);
        assertThat(after.get("FAILURE_DETAIL")).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT cycle_status FROM payroll_cycle_instance WHERE"
                                        + " cycle_instance_id = ?",
                                String.class,
                                cycleId))
                .isEqualTo("COMPUTED");
    }
}
