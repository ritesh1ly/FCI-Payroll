package in.gov.fci.payroll.payroll.batch.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.jobs.batch.BatchTestSupport;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.payroll.orchestration.PayslipPdfService;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;

/**
 * OD-149 D-5: a bulk file carries at most {@code fci.api.pdf.bulk-max} payslips; a month with more
 * fails the job with both numbers and stores nothing (narrow the level). Fixture month 1994-06.
 */
@TestPropertySource(properties = "fci.api.pdf.bulk-max=1")
class PayslipBulkCapJobIT extends BatchTestSupport {

    @Autowired
    @Qualifier("payslipBulkJob")
    private Job job;

    @Autowired private DocumentStore documents;

    @Test
    void OD_149_moreMembersThanTheCapFailTheJobWithBothNumbers() throws Exception {
        jobLauncher.setJob(job);
        long unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DH001'",
                        Long.class);
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES ('199406', ?, 'DO',"
                        + " '1994-95', 'LOCKED', 'batch.fixture')",
                unit);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " '199406' AND org_unit_id = ?",
                        Long.class,
                        unit);
        List<Long> members =
                named.queryForList(
                        "SELECT employee_id FROM employee WHERE employee_type = 'STAFF' AND"
                                + " location_id IN (:locations) ORDER BY emp_num FETCH FIRST 2 ROWS"
                                + " ONLY",
                        Map.of("locations", org.subtree(unit)),
                        Long.class);
        for (Long e : members) {
            jdbc.update(
                    "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                            + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                            + " total_deductions, net_pay, result_status, is_locked) VALUES (?, ?,"
                            + " ?, 'STAFF', '199406', ?, 'ACTUAL', 1, 0, 1, 'CURRENT', 'Y')",
                    cycle,
                    unit,
                    e,
                    Date.valueOf("1994-06-01"));
        }
        BatchJobRequest request =
                pickedRequest(
                        JobType.PAYSLIP_BULK,
                        Map.of(
                                "locationId",
                                String.valueOf(unit),
                                "payPeriod",
                                "1994-06",
                                "level",
                                "STAFF"),
                        unit);
        JobExecution run = jobLauncher.startJob(JobLaunchService.parameters(request));
        assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(requests.findById(request.getId()).orElseThrow().getErrorMessage())
                .contains("2 payslips", "at most 1");
        assertThat(
                        documents.active(
                                PayslipPdfService.OUTPUT_ENTITY, String.valueOf(request.getId())))
                .isEmpty();
    }
}
