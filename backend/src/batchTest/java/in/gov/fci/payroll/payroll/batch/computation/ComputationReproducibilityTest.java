package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.BatchTestSupport;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.support.OutageTrigger;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
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
 * The staff engine is deterministic and re-runnable through the real PAYROLL_COMPUTATION job (C-02;
 * CLAUDE.md section 7; BR-CYC-011, SRS-15-24, SRS-15-26, SRS-19-3; DDL computation_run
 * parameter_hash / rule_version_set_hash, payroll_result_component rate_applied):
 *
 * <ul>
 *   <li>the same inputs and rule versions give the same result rows, line by line;
 *   <li>a resume after a rate version was activated for the run's period is refused before any
 *       member is read, and a fresh run then uses the new version;
 *   <li>an earlier period recomputed after a version for a later period was activated keeps the
 *       version in force for its own period.
 * </ul>
 *
 * <p>A rate version is activated as its M-04 approval leaves it (an APPROVED {@code rate_set} with
 * its lines), bounded to one month far from the other batch tests' periods so their results do not
 * move.
 */
class ComputationReproducibilityTest extends BatchTestSupport {

    @Autowired
    @Qualifier("payrollComputationJob")
    private Job computation;

    @AfterEach
    void disarm() {
        OutageTrigger.arm(-1);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_test_outage_repro");
    }

    // ---------------------------------------------------------------- fixtures

    private long frozenCycle(Unit unit, String period) {
        OrgLocation location = cycles.cycleUnit(unit.locationId());
        YearMonth month = YearMonth.parse(period);
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by) VALUES (?, ?, ?, ?,"
                        + " 'FROZEN', 'ON_DEMAND', 'test.maker')",
                CycleKeys.yymm(month),
                location.getId(),
                CycleKeys.level(location.getType()),
                CycleKeys.finYear(month));
        return jdbc.queryForObject(
                "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?",
                Long.class,
                CycleKeys.yymm(month),
                location.getId());
    }

    private BatchJobRequest request(Unit unit, String period) {
        return pickedRequest(
                JobType.PAYROLL_COMPUTATION,
                Map.of("locationId", unit.locationId().toString(), "payPeriod", period),
                unit.locationId());
    }

    /** How a launch ended (not a JobExecution: SpringBatchTest treats those as factories). */
    private record Outcome(BatchStatus status, long read) {}

    private Outcome run(BatchJobRequest request) throws Exception {
        jobLauncher.setJob(computation);
        JobExecution execution = jobLauncher.startJob(JobLaunchService.parameters(request));
        return new Outcome(
                execution.getStatus(),
                execution.getStepExecutions().stream()
                        .filter(s -> s.getStepName().contains(":partition"))
                        .mapToLong(StepExecution::getReadCount)
                        .sum());
    }

    private Map<String, Object> runOf(BatchJobRequest request) {
        return jdbc.queryForMap(
                "SELECT * FROM computation_run WHERE parameters_json LIKE ?",
                "{\"jobRequestId\":" + request.getId() + ",%");
    }

    private long runId(BatchJobRequest request) {
        return ((Number) runOf(request).get("RUN_ID")).longValue();
    }

    /** Every result row of the run as text, in employee order (the run id left out). */
    private List<String> resultRows(long runId) {
        return jdbc.query(
                "SELECT employee_id, payroll_type, payroll_yymm, period_date, gross_earnings,"
                        + " total_deductions, net_pay, payable_days, lwp_days FROM payroll_result"
                        + " WHERE run_id = ? ORDER BY employee_id",
                (rs, i) ->
                        rs.getLong(1)
                                + "|"
                                + rs.getString(2)
                                + "|"
                                + rs.getString(3)
                                + "|"
                                + rs.getDate(4)
                                + "|"
                                + plain(rs.getBigDecimal(5))
                                + "|"
                                + plain(rs.getBigDecimal(6))
                                + "|"
                                + plain(rs.getBigDecimal(7))
                                + "|"
                                + plain(rs.getBigDecimal(8))
                                + "|"
                                + plain(rs.getBigDecimal(9)),
                runId);
    }

    /** Every component line of the run as text (member, pay code, amount, rate, basis). */
    private List<String> componentRows(long runId) {
        return jdbc.query(
                "SELECT c.employee_id, c.component_id, c.amount, c.rate_applied, c.basis_amount"
                    + " FROM payroll_result_component c JOIN payroll_result r ON"
                    + " r.payroll_result_id = c.payroll_result_id AND r.period_date = c.period_date"
                    + " AND r.org_unit_id = c.org_unit_id WHERE r.run_id = ? ORDER BY"
                    + " c.employee_id, c.component_id, c.amount, c.result_component_id",
                (rs, i) ->
                        rs.getLong(1)
                                + "|"
                                + rs.getLong(2)
                                + "|"
                                + plain(rs.getBigDecimal(3))
                                + "|"
                                + plain(rs.getBigDecimal(4))
                                + "|"
                                + plain(rs.getBigDecimal(5)),
                runId);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    /** The DA rates the run applied to IDA members (pay code 111), distinct. */
    private List<String> daRates(long runId) {
        return jdbc
                .queryForList(
                        "SELECT DISTINCT c.rate_applied FROM payroll_result_component c JOIN"
                            + " payroll_result r ON r.payroll_result_id = c.payroll_result_id AND"
                            + " r.period_date = c.period_date AND r.org_unit_id = c.org_unit_id"
                            + " JOIN pay_component p ON p.component_id = c.component_id JOIN"
                            + " employee e ON e.employee_id = c.employee_id WHERE r.run_id = ? AND"
                            + " p.legacy_pay_code = 111 AND e.pay_pattern = 'IDA' AND"
                            + " c.rate_applied IS NOT NULL",
                        BigDecimal.class,
                        runId)
                .stream()
                .map(ComputationReproducibilityTest::plain)
                .toList();
    }

    /**
     * A new DA_IDA version for one month, as its M-04 approval leaves it: APPROVED, the lines of
     * the version in force before it with the DA percentage set to {@code percent}.
     */
    private void activateDaVersion(String period, int percent) {
        YearMonth month = YearMonth.parse(period);
        String key = "it-da-" + period;
        jdbc.update(
                "INSERT INTO approval_queue (entity_name, entity_key, action_type, payload,"
                        + " submitted_by, status, decided_by) VALUES ('rate_set', ?, 'CREATE',"
                        + " '{}', 'test.ruleadmin', 'APPROVED', 'test.ruleapprover')",
                key);
        Long approval =
                jdbc.queryForObject(
                        "SELECT approval_id FROM approval_queue WHERE entity_name = 'rate_set' AND"
                                + " entity_key = ?",
                        Long.class,
                        key);
        Long earlier =
                jdbc.queryForObject(
                        "SELECT rate_set_id FROM rate_set WHERE rate_set_code = 'DA_IDA' AND"
                                + " version_no = 1",
                        Long.class);
        Integer next =
                jdbc.queryForObject(
                        "SELECT MAX(version_no) + 1 FROM rate_set WHERE rate_set_code = 'DA_IDA'",
                        Integer.class);
        jdbc.update(
                "INSERT INTO rate_set (rate_set_code, rate_set_name, payroll_type, version_no,"
                        + " effective_from, effective_to, approval_state, approval_id) VALUES"
                        + " ('DA_IDA', 'Dearness Allowance - IDA', 'STAFF', ?, ?, ?, 'APPROVED',"
                        + " ?)",
                next,
                Date.valueOf(month.atDay(1)),
                Date.valueOf(month.atEndOfMonth()),
                approval);
        Long created =
                jdbc.queryForObject(
                        "SELECT rate_set_id FROM rate_set WHERE rate_set_code = 'DA_IDA' AND"
                                + " version_no = ?",
                        Long.class,
                        next);
        jdbc.update(
                "INSERT INTO rate_set_line (rate_set_id, pay_pattern, city_class, rate_code,"
                        + " rate_value, unit_of_measure) SELECT ?, pay_pattern, city_class,"
                        + " rate_code, ?, unit_of_measure FROM rate_set_line WHERE rate_set_id = ?",
                created,
                percent,
                earlier);
    }

    // ---------------------------------------------------------------- determinism

    /**
     * BR-CYC-011 / SRS-15-24: two Actual runs of the same frozen cycle with the same parameters and
     * the same rule versions record the same two hashes and write the same result rows and
     * component lines, member by member; the second supersedes the first and nothing else differs.
     */
    @Test
    void BR_CYC_011_aRerunWithTheSameParameterAndRuleVersionHashesGivesIdenticalResultRows()
            throws Exception {
        Unit unit = busiestUnit();
        frozenCycle(unit, "2028-01");
        BatchJobRequest first = request(unit, "2028-01");
        assertThat(run(first).status()).isEqualTo(BatchStatus.COMPLETED);
        BatchJobRequest second = request(unit, "2028-01");
        assertThat(run(second).status()).isEqualTo(BatchStatus.COMPLETED);

        Map<String, Object> one = runOf(first);
        Map<String, Object> two = runOf(second);
        assertThat(two.get("PARAMETER_HASH")).isEqualTo(one.get("PARAMETER_HASH")).isNotNull();
        assertThat(two.get("RULE_VERSION_SET_HASH"))
                .isEqualTo(one.get("RULE_VERSION_SET_HASH"))
                .isNotNull();
        assertThat(one.get("RUN_STATUS")).isEqualTo("SUPERSEDED");
        assertThat(two.get("RUN_STATUS")).isEqualTo("COMPLETE");

        List<String> rows = resultRows(runId(first));
        assertThat(rows).as("demo members are computed").isNotEmpty();
        assertThat(resultRows(runId(second))).containsExactlyElementsOf(rows);
        List<String> lines = componentRows(runId(first));
        assertThat(lines).hasSizeGreaterThan(rows.size());
        assertThat(componentRows(runId(second))).containsExactlyElementsOf(lines);
        assertThat(two.get("RESULT_COUNT")).isEqualTo(one.get("RESULT_COUNT"));
    }

    // ---------------------------------------------------------------- resume after activation

    /**
     * SRS-15-26 / BR-CYC-013, end to end: an Actual run fails part-way; a DA version for its period
     * is then approved; the relaunch of the same request fails at once with JOB-409-RESUME-RULES,
     * reads no member and leaves the committed rows as they were. A new request opens a new run,
     * records the new version set and applies the new DA rate to every member.
     */
    @Test
    void SRS_15_26_aResumeIsRefusedWhenARuleVersionWasActivatedAfterTheRunStarted()
            throws Exception {
        Unit unit = busiestUnit();
        assertThat(unit.activeEmployees()).as("more than one chunk").isGreaterThan(5);
        frozenCycle(unit, "2028-02");
        jdbc.execute(
                "CREATE TRIGGER trg_test_outage_repro BEFORE INSERT ON payroll_result FOR EACH ROW"
                        + " CALL \""
                        + OutageTrigger.class.getName()
                        + "\"");
        OutageTrigger.arm(6);
        BatchJobRequest request = request(unit, "2028-02");
        assertThat(run(request).status()).isEqualTo(BatchStatus.FAILED);
        OutageTrigger.arm(-1);
        long failedRun = runId(request);
        String startedWith = (String) runOf(request).get("RULE_VERSION_SET_HASH");
        List<String> committed = resultRows(failedRun);
        assertThat(committed).as("chunks committed before the outage").isNotEmpty();
        assertThat(daRates(failedRun)).containsExactly("50");

        activateDaVersion("2028-02", 53);

        BatchJobRequest retry = requests.findById(request.getId()).orElseThrow();
        retry.pick("test-node", clock.instant());
        retry = requests.save(retry);
        Outcome relaunch = run(retry);
        assertThat(relaunch.status()).isEqualTo(BatchStatus.FAILED);
        assertThat(relaunch.read()).as("no member is read").isZero();
        BatchJobRequest refused = requests.findById(request.getId()).orElseThrow();
        assertThat(refused.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(refused.getErrorMessage()).contains("JOB-409-RESUME-RULES");
        assertThat(runId(request)).as("the same run, not continued").isEqualTo(failedRun);
        assertThat(runOf(request).get("RUN_STATUS")).isEqualTo("FAILED");
        assertThat(resultRows(failedRun)).containsExactlyElementsOf(committed);

        // A fresh run of the period computes everything again with the version now in force.
        BatchJobRequest fresh = request(unit, "2028-02");
        assertThat(run(fresh).status()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> rerun = runOf(fresh);
        assertThat(rerun.get("RULE_VERSION_SET_HASH")).isNotEqualTo(startedWith);
        assertThat(rerun.get("PARAMETER_HASH")).isEqualTo(runOf(request).get("PARAMETER_HASH"));
        assertThat(daRates(runId(fresh))).containsExactly("53");
    }

    // ---------------------------------------------------------------- version in force

    /**
     * SRS-19-3 / SRS-19-13 through the engine: a DA version approved for a later month is not used
     * when an earlier month is computed again (same version-set hash, identical rows, DA 50 %); the
     * later month's run uses it (DA 55 %, another hash). The month after it has the earlier version
     * again (the new one is bounded).
     */
    // SC-16-0082: the revised rate drives the staff computation for periods from its effective
    // date.
    @Test
    void SRS_19_3_anEarlierPeriodRecomputedAfterANewVersionUsesTheVersionThenInForce()
            throws Exception {
        Unit unit = busiestUnit();
        frozenCycle(unit, "2028-03");
        frozenCycle(unit, "2028-04");
        frozenCycle(unit, "2028-05");
        BatchJobRequest march = request(unit, "2028-03");
        assertThat(run(march).status()).isEqualTo(BatchStatus.COMPLETED);
        String marchVersions = (String) runOf(march).get("RULE_VERSION_SET_HASH");
        List<String> marchRows = resultRows(runId(march));

        activateDaVersion("2028-04", 55);

        BatchJobRequest marchAgain = request(unit, "2028-03");
        assertThat(run(marchAgain).status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(runOf(marchAgain).get("RULE_VERSION_SET_HASH")).isEqualTo(marchVersions);
        assertThat(resultRows(runId(marchAgain))).containsExactlyElementsOf(marchRows);
        assertThat(daRates(runId(marchAgain))).containsExactly("50");

        BatchJobRequest april = request(unit, "2028-04");
        assertThat(run(april).status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(runOf(april).get("RULE_VERSION_SET_HASH")).isNotEqualTo(marchVersions);
        assertThat(daRates(runId(april))).containsExactly("55");

        BatchJobRequest may = request(unit, "2028-05");
        assertThat(run(may).status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(daRates(runId(may))).containsExactly("50");
    }

    // ---------------------------------------------------------------- negative net (SRS-1-8)

    @Autowired private in.gov.fci.payroll.nps.api.NpsReceipts nps;

    /**
     * SRS-1-8 / SRS-15-13 (BMAD TR-037): a member whose deductions exceed the gross (an NPS amount
     * HRMS sent above the pay, deducted as received) is computed with its negative net and
     * quarantined NEGATIVE_NET, UNREVIEWED, for the Checker - never refused to the failure log.
     */
    @Test
    void SRS_1_8_aNegativeNetIsComputedAndQuarantinedNeverRefused() throws Exception {
        Unit unit = busiestUnit();
        long cycleId = frozenCycle(unit, "2028-06");
        Map<String, Object> member =
                named.queryForMap(
                        "SELECT e.employee_id, e.emp_num FROM employee e WHERE e.employee_type ="
                                + " 'STAFF' AND e.record_status = 'ACTIVE' AND"
                                + " e.employment_status = 'IN_SERVICE' AND e.pay_pattern = 'IDA'"
                                + " AND e.current_basic > 0 AND e.location_id IN (:offices)"
                                + " ORDER BY e.employee_id FETCH FIRST 1 ROWS ONLY",
                        Map.of("offices", org.subtree(unit.locationId())));
        long employee = ((Number) member.get("EMPLOYEE_ID")).longValue();
        nps.receive(
                "it.hrms",
                new in.gov.fci.payroll.nps.api.NpsReceipts.Receipt(
                        (String) member.get("EMP_NUM"),
                        "202806",
                        true,
                        "110012345678",
                        new BigDecimal("9999999"),
                        new BigDecimal("1"),
                        null));
        BatchJobRequest request = request(unit, "2028-06");
        assertThat(run(request).status()).isEqualTo(BatchStatus.COMPLETED);
        BigDecimal net =
                jdbc.queryForObject(
                        "SELECT net_pay FROM payroll_result WHERE run_id = ? AND employee_id = ?",
                        BigDecimal.class,
                        runId(request),
                        employee);
        assertThat(net).isNegative();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT quarantine_reason, review_status FROM payroll_quarantine"
                                        + " WHERE cycle_instance_id = ? AND employee_id = ?",
                                cycleId,
                                employee))
                .containsEntry("QUARANTINE_REASON", "NEGATIVE_NET")
                .containsEntry("REVIEW_STATUS", "UNREVIEWED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_exception WHERE exception_code ="
                                        + " 'COMPUTATION_BLOCKED' AND cycle_instance_id = ? AND"
                                        + " employee_id = ?",
                                Integer.class,
                                cycleId,
                                employee))
                .as("not refused to the failure log")
                .isZero();
    }

    // ---------------------------------------------------------------- abandoned Actual run

    @Autowired private in.gov.fci.payroll.jobs.batch.recovery.AbandonSweep sweep;
    @Autowired private in.gov.fci.payroll.jobs.config.BatchProperties batchProperties;

    /**
     * SRS-15-27 / BR-CYC-014 (BMAD TR-071): an Actual run failed part-way and left beyond the
     * configured period is abandoned by the system: the run is ABANDONED (its record kept), none of
     * its results is CURRENT (they stay QUARANTINED, never of record: R-DEL), the cycle stays
     * FROZEN with no run of record, so G0 can never be met on it.
     */
    @Test
    void SRS_15_27_aFailedActualRunLeftBeyondThePeriodIsAbandonedAndNeverOfRecord()
            throws Exception {
        Unit unit = busiestUnit();
        long cycleId = frozenCycle(unit, "2028-07");
        jdbc.execute(
                "CREATE TRIGGER trg_test_outage_repro BEFORE INSERT ON payroll_result FOR EACH ROW"
                        + " CALL \""
                        + OutageTrigger.class.getName()
                        + "\"");
        OutageTrigger.arm(6);
        BatchJobRequest request = request(unit, "2028-07");
        assertThat(run(request).status()).isEqualTo(BatchStatus.FAILED);
        OutageTrigger.arm(-1);
        long runId = runId(request);
        jdbc.update(
                "UPDATE batch_job_request SET finished_at = ? WHERE job_request_id = ?",
                java.sql.Timestamp.from(
                        clock.instant().minus(batchProperties.restartWindow().plusHours(1))),
                request.getId());

        assertThat(sweep.sweep()).isGreaterThanOrEqualTo(1);

        assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.ABANDONED);
        assertThat(runOf(request).get("RUN_STATUS")).isEqualTo("ABANDONED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT result_status FROM payroll_result WHERE run_id ="
                                        + " ?",
                                String.class,
                                runId))
                .containsExactly("QUARANTINED");
        Map<String, Object> cycle =
                jdbc.queryForMap(
                        "SELECT cycle_status, current_run_id FROM payroll_cycle_instance WHERE"
                                + " cycle_instance_id = ?",
                        cycleId);
        assertThat(cycle.get("CYCLE_STATUS")).isEqualTo("FROZEN");
        assertThat(cycle.get("CURRENT_RUN_ID")).as("no run of record: G0 unmet").isNull();
    }

    // ---------------------------------------------------------------- an unseeded rate

    /**
     * R-RULE / KB CF-49 (BMAD TR-086): with no DA rate for IDA in force for the period (a version
     * in force without its IDA line), no IDA member is computed - never a default of zero - and
     * each is flagged on X-05 naming the missing rate; the run does not reconcile (eligible above
     * the results), so G0 stays unmet. Whether such a run is a systemic failure (ABORTED) is
     * CLR-136.
     */
    @Test
    void R_RULE_aRateNotConfiguredForThePeriodComputesNoMemberAndFlagsEachNeverAZero()
            throws Exception {
        Unit unit = busiestUnit();
        long cycleId = frozenCycle(unit, "2028-08");
        YearMonth month = YearMonth.parse("2028-08");
        jdbc.update(
                "INSERT INTO approval_queue (entity_name, entity_key, action_type, payload,"
                        + " submitted_by, status, decided_by) VALUES ('rate_set', 'it-da-none',"
                        + " 'CREATE', '{}', 'test.ruleadmin', 'APPROVED', 'test.ruleapprover')");
        jdbc.update(
                "INSERT INTO rate_set (rate_set_code, rate_set_name, payroll_type, version_no,"
                        + " effective_from, effective_to, approval_state, approval_id) SELECT"
                        + " 'DA_IDA', 'Dearness Allowance - IDA', 'STAFF', MAX(version_no) + 1, ?,"
                        + " ?, 'APPROVED', (SELECT approval_id FROM approval_queue WHERE"
                        + " entity_key = 'it-da-none') FROM rate_set WHERE rate_set_code ="
                        + " 'DA_IDA'",
                Date.valueOf(month.atDay(1)),
                Date.valueOf(month.atEndOfMonth()));
        BatchJobRequest request = request(unit, "2028-08");
        run(request);
        Map<String, Object> computed = runOf(request);
        long runId = ((Number) computed.get("RUN_ID")).longValue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_result r JOIN employee e ON"
                                        + " e.employee_id = r.employee_id WHERE r.run_id = ? AND"
                                        + " e.pay_pattern = 'IDA'",
                                Integer.class,
                                runId))
                .as("no IDA member computed without its DA rate")
                .isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT detail FROM payroll_exception WHERE"
                                        + " exception_code = 'COMPUTATION_BLOCKED' AND"
                                        + " cycle_instance_id = ?",
                                String.class,
                                cycleId))
                .isNotEmpty()
                .anySatisfy(d -> assertThat(d).contains("DA rate not seeded"));
        assertThat(((Number) computed.get("ELIGIBLE_COUNT")).longValue())
                .as("G0: the run does not reconcile")
                .isGreaterThan(
                        computed.get("RESULT_COUNT") == null
                                ? 0
                                : ((Number) computed.get("RESULT_COUNT")).longValue());
    }

    // ---------------------------------------------------------------- dry-run rows

    @Autowired
    @Qualifier("payrollDryRunJob")
    private Job dryRun;

    /**
     * SRS-15-7 (BMAD TR-078; DDL payroll_result: SIMULATION rows are transient): when a new dry run
     * opens for the cycle, the earlier dry run's result rows are removed - only the latest preview
     * is held - while both run records stay.
     */
    @Test
    void SRS_15_7_aNewDryRunPurgesTheEarlierSimulationRowsAndKeepsBothRunRecords()
            throws Exception {
        Unit unit = busiestUnit();
        jobLauncher.setJob(dryRun);
        BatchJobRequest first =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-07"),
                        unit.locationId());
        assertThat(jobLauncher.startJob(JobLaunchService.parameters(first)).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        long firstRun = runId(first);
        assertThat(resultRows(firstRun)).isNotEmpty();
        BatchJobRequest second =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-07"),
                        unit.locationId());
        assertThat(jobLauncher.startJob(JobLaunchService.parameters(second)).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        long secondRun = runId(second);
        assertThat(resultRows(firstRun)).as("the earlier preview's rows are purged").isEmpty();
        assertThat(componentRows(firstRun)).isEmpty();
        assertThat(resultRows(secondRun)).isNotEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM computation_run WHERE run_id IN (?, ?)",
                                Integer.class,
                                firstRun,
                                secondRun))
                .as("both run records stay")
                .isEqualTo(2);
    }
}
