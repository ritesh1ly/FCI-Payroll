package in.gov.fci.payroll.payroll.batch.arrear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.jobs.batch.BatchTestSupport;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * DA_ARREAR end to end on the demo data (BR-ARR-DA-002 / 003; v4.0 UC-02 E1): one arrear_event
 * (DA_REVISION) is raised for the cycle unit and range, posted to the current period, and marked
 * COMPUTED when the job completes; members without an ACTUAL result of record get no arrear (the
 * months are counted, never invented); the request completes with its summary; the job refuses to
 * start without its parameters.
 */
class DaArrearJobIT extends BatchTestSupport {

    @Autowired
    @Qualifier("daArrearJob")
    private Job job;

    @BeforeEach
    void useJob() {
        jobLauncher.setJob(job);
    }

    private Long unit() {
        return jdbc.queryForObject(
                "SELECT location_id FROM org_location WHERE location_code = 'DH001'", Long.class);
    }

    @Test
    void BR_ARR_DA_002_BR_ARR_DA_005_SRS_8_19_aRunRaisesItsEventAndMarksItComputed()
            throws Exception {
        BatchJobRequest request =
                pickedRequest(
                        JobType.DA_ARREAR,
                        Map.of(
                                "locationId",
                                String.valueOf(unit()),
                                "fromPeriod",
                                "2025-01",
                                "toPeriod",
                                "2025-03",
                                "reason",
                                "DA arrear IT " + System.nanoTime()),
                        unit());
        JobExecution execution = jobLauncher.startJob(JobLaunchService.parameters(request));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Map<String, Object> event =
                jdbc.queryForMap(
                        "SELECT e.event_status, e.scope_from_yymm, e.scope_to_yymm,"
                                + " e.posted_to_yymm, e.source_key, e.raised_by, t.event_code FROM"
                                + " arrear_event e JOIN arrear_event_type t ON t.event_type_id ="
                                + " e.event_type_id WHERE e.source_entity = 'batch_job_request' AND"
                                + " e.source_key = ?",
                        String.valueOf(request.getId()));
        assertThat(event.get("EVENT_STATUS")).isEqualTo("COMPUTED");
        assertThat(event.get("EVENT_CODE")).isEqualTo("DA_REVISION");
        assertThat(event.get("SCOPE_FROM_YYMM")).isEqualTo("202501");
        assertThat(event.get("SCOPE_TO_YYMM")).isEqualTo("202503");
        assertThat((String) event.get("POSTED_TO_YYMM"))
                .as("the current period")
                .matches("^20\\d{4}$");
        assertThat(event.get("RAISED_BY")).isEqualTo("test.maker");

        // No ACTUAL result exists for these months: nothing invented, the months are counted.
        Long details =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM arrear_detail d JOIN arrear_event e ON"
                                + " e.arrear_event_id = d.arrear_event_id WHERE e.source_key = ?",
                        Long.class,
                        String.valueOf(request.getId()));
        assertThat(details).isZero();
        var step = execution.getStepExecutions().iterator().next();
        assertThat(step.getWriteCount()).isZero();
        assertThat(step.getFilterCount()).isEqualTo(step.getReadCount());
        assertThat(step.getExecutionContext().getLong(DaArrearProcessor.MONTHS_WITHOUT_RESULT))
                .isEqualTo(step.getReadCount() * 3);
        assertThat(step.getExecutionContext().getString(BatchKeys.SUMMARY))
                .contains("DA revision 2025-01 to 2025-03");

        BatchJobRequest done = requests.findById(request.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.getResultSummary()).contains("Arrear event");
    }

    @Test
    void theJobRefusesToStartWithoutItsRange() throws Exception {
        BatchJobRequest request =
                pickedRequest(
                        JobType.DA_ARREAR,
                        Map.of(
                                "locationId",
                                String.valueOf(unit()),
                                "reason",
                                "missing range " + System.nanoTime()),
                        unit());
        assertThatThrownBy(() -> jobLauncher.startJob(JobLaunchService.parameters(request)))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("fromPeriod");
    }

    private BatchJobRequest daRequest(String from, String to) {
        return pickedRequest(
                JobType.DA_ARREAR,
                Map.of(
                        "locationId",
                        String.valueOf(unit()),
                        "fromPeriod",
                        from,
                        "toPeriod",
                        to,
                        "reason",
                        "DA arrear IT " + System.nanoTime()),
                unit());
    }

    /** One result of the member's month with its DA line (amount on a basis). */
    private void result(long member, YearMonth month, String runType, String status, String da) {
        String yymm = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by) SELECT ?, ?, 'DO',"
                        + " '2026-27', 'LOCKED', 'ON_DEMAND', 'it.fixture' FROM dual WHERE NOT"
                        + " EXISTS (SELECT 1 FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?)",
                yymm,
                unit(),
                yymm,
                unit());
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        yymm,
                        unit());
        java.sql.Date first = java.sql.Date.valueOf(month.atDay(1));
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, result_status) VALUES (?, ?, ?, 'STAFF', ?,"
                        + " ?, ?, 1100, 0, 1100, ?)",
                cycle,
                unit(),
                member,
                yymm,
                first,
                runType,
                status);
        long result =
                jdbc.queryForObject(
                        "SELECT MAX(payroll_result_id) FROM payroll_result WHERE employee_id = ?"
                                + " AND payroll_yymm = ?",
                        Long.class,
                        member,
                        yymm);
        jdbc.update(
                "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, basis_amount,"
                        + " run_type, payroll_yymm, period_date, legacy_pay_code) SELECT ?, ?, ?,"
                        + " ?, component_id, ?, 1000, ?, ?, ?, legacy_pay_code FROM pay_component"
                        + " WHERE legacy_pay_code = 111",
                result,
                cycle,
                unit(),
                member,
                new java.math.BigDecimal(da),
                runType,
                yymm,
                first);
    }

    /**
     * SRS-8-6 (BMAD TR-082): a member separated before the run still gets the DA arrear of the
     * months the member was paid; SRS-8-2 (BMAD TR-084): only the ACTUAL result of record (CURRENT)
     * is set against the rate - a superseded result and a simulation of the same month are never
     * counted, so the month has exactly one differential.
     */
    @Test
    void SRS_8_6_SRS_8_2_aSeparatedMemberGetsTheArrearOfTheResultOfRecordOnly() throws Exception {
        long member =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee e WHERE location_id = ? AND"
                                + " record_status = 'ACTIVE' AND employment_status = 'IN_SERVICE'"
                                + " AND employee_type = 'STAFF' AND pay_pattern = 'IDA' AND NOT"
                                + " EXISTS (SELECT 1 FROM app_user u WHERE u.employee_id ="
                                + " e.employee_id) ORDER BY emp_num DESC OFFSET 7 ROWS FETCH NEXT 1"
                                + " ROWS ONLY",
                        Long.class,
                        unit());
        YearMonth month = YearMonth.now(java.time.ZoneId.of("Asia/Kolkata")).plusYears(18);
        result(member, month, "ACTUAL", "SUPERSEDED", "50");
        result(member, month, "SIMULATION", "CURRENT", "60");
        result(member, month, "ACTUAL", "CURRENT", "100");
        String status =
                jdbc.queryForObject(
                        "SELECT employment_status FROM employee WHERE employee_id = ?",
                        String.class,
                        member);
        jdbc.update(
                "UPDATE employee SET employment_status = 'SEPARATED' WHERE employee_id = ?",
                member);
        BatchJobRequest request = daRequest(month.toString(), month.toString());
        try {
            JobExecution execution = jobLauncher.startJob(JobLaunchService.parameters(request));
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        } finally {
            jdbc.update(
                    "UPDATE employee SET employment_status = ? WHERE employee_id = ?",
                    status,
                    member);
        }
        java.util.List<Map<String, Object>> details =
                jdbc.queryForList(
                        "SELECT d.original_amount, d.recomputed_amount FROM arrear_detail d JOIN"
                                + " arrear_event e ON e.arrear_event_id = d.arrear_event_id WHERE"
                                + " e.source_key = ? AND d.employee_id = ?",
                        String.valueOf(request.getId()),
                        member);
        assertThat(details).as("the separated member is included, once").hasSize(1);
        assertThat((java.math.BigDecimal) details.getFirst().get("ORIGINAL_AMOUNT"))
                .as("the CURRENT ACTUAL line, not the superseded or simulated one")
                .isEqualByComparingTo("100");
        jdbc.update(
                "UPDATE arrear_event SET event_status = 'REVERSED' WHERE source_key = ?",
                String.valueOf(request.getId()));
    }

    /**
     * SRS-8-15 (BMAD TR-081): a DA run whose range shares a month with an open DA event of the unit
     * is refused before reading a member; an adjacent range is accepted.
     */
    @Test
    void SRS_8_15_aDaRunOverlappingAnOpenDaEventIsRefusedAnAdjacentOneRuns() throws Exception {
        BatchJobRequest first = daRequest("2023-01", "2023-03");
        assertThat(jobLauncher.startJob(JobLaunchService.parameters(first)).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        BatchJobRequest overlapping = daRequest("2023-03", "2023-04");
        JobExecution refused = jobLauncher.startJob(JobLaunchService.parameters(overlapping));
        assertThat(refused.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(refused.getAllFailureExceptions())
                .anySatisfy(ex -> assertThat(ex).hasMessageContaining("SRS-8-15"));
        BatchJobRequest adjacent = daRequest("2023-04", "2023-05");
        assertThat(jobLauncher.startJob(JobLaunchService.parameters(adjacent)).getStatus())
                .as("the month after the open event's last")
                .isEqualTo(BatchStatus.COMPLETED);
    }
}
