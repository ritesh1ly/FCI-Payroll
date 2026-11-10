package in.gov.fci.payroll.payroll.batch.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.jobs.batch.BatchTestSupport;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.support.OutageTrigger;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.payroll.orchestration.PayslipPdfService;
import in.gov.fci.payroll.platform.export.ZipBundle;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * PAYSLIP_BULK end to end on the demo data (OD-149; SRS-NF-2: the bulk payslip download is a
 * background job; SRS-NF-7: a failed member stops only itself, restart without repeat or loss; SRS
 * l.3380-3392: a locked period only, reproducible): one ZIP of single-month PDFs in staff-number
 * order, kept for the requester; a member whose payslip cannot be read is left out, listed in the
 * manifest and counted as skipped while the job completes; an open month fails the job; an office
 * without results gives an empty ZIP; a failure while storing the file fails the job and the
 * restart stores the same bytes once. Fixture months in 1994, which no other test uses.
 */
class PayslipBulkJobIT extends BatchTestSupport {

    @Autowired
    @Qualifier("payslipBulkJob")
    private Job job;

    @Autowired private DocumentStore documents;

    @BeforeEach
    void useJob() {
        jobLauncher.setJob(job);
    }

    @AfterEach
    void disarm() {
        OutageTrigger.arm(-1);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_test_bulk_outage");
    }

    private long unit() {
        return jdbc.queryForObject(
                "SELECT location_id FROM org_location WHERE location_code = 'DH001'", Long.class);
    }

    /** STAFF members of the unit's offices, in staff-number order. */
    private List<Map<String, Object>> staff(int count) {
        return named.queryForList(
                "SELECT employee_id, emp_num FROM employee WHERE employee_type = 'STAFF' AND"
                        + " location_id IN (:locations) ORDER BY emp_num FETCH FIRST "
                        + count
                        + " ROWS ONLY",
                Map.of("locations", org.subtree(unit())));
    }

    /** The month's cycle at the unit, in {@code status}; returns its id. */
    private long cycle(String yymm, String status) {
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES (?, ?, 'DO', '1993-94', ?,"
                        + " 'batch.fixture')",
                yymm,
                unit(),
                status);
        return jdbc.queryForObject(
                "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?",
                Long.class,
                yymm,
                unit());
    }

    /** A locked result of {@code employee} for the month, of {@code payrollType}, with one line. */
    private void result(long cycle, String yymm, long employee, String payrollType) {
        Date day =
                Date.valueOf(
                        YearMonth.parse(yymm.substring(0, 4) + "-" + yymm.substring(4)).atDay(1));
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, result_status, is_locked) VALUES (?, ?, ?,"
                        + " ?, ?, ?, 'ACTUAL', 30000, 0, 30000, 'CURRENT', 'Y')",
                cycle,
                unit(),
                employee,
                payrollType,
                yymm,
                day);
        jdbc.update(
                "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type, is_locked,"
                        + " payroll_yymm, period_date, legacy_pay_code) SELECT r.payroll_result_id,"
                        + " r.cycle_instance_id, r.org_unit_id, r.employee_id, c.component_id,"
                        + " 30000, 'ACTUAL', 'Y', r.payroll_yymm, r.period_date, c.legacy_pay_code"
                        + " FROM payroll_result r JOIN pay_component c ON c.legacy_pay_code = 101"
                        + " WHERE r.employee_id = ? AND r.payroll_yymm = ?",
                employee,
                yymm);
    }

    private BatchJobRequest request(String yymm) {
        return pickedRequest(
                JobType.PAYSLIP_BULK,
                Map.of(
                        "locationId",
                        String.valueOf(unit()),
                        "payPeriod",
                        yymm.substring(0, 4) + "-" + yymm.substring(4),
                        "level",
                        "STAFF"),
                unit());
    }

    /** The ZIP the job stored for the request (the newest), or null. */
    private byte[] output(long requestId) {
        List<DocumentStore.AttachmentView> files =
                documents.active(PayslipPdfService.OUTPUT_ENTITY, String.valueOf(requestId));
        if (files.isEmpty()) {
            return null;
        }
        return documents
                .read(
                        "test.maker",
                        null,
                        PayslipPdfService.OUTPUT_ENTITY,
                        String.valueOf(requestId),
                        files.getLast().id())
                .bytes();
    }

    private static Map<String, byte[]> entries(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    @Test
    void SRS_NF_2_OD_149_aLockedMonthGivesOneZipOfThePayslipsInStaffNumberOrder() throws Exception {
        String yymm = "199401";
        long cycle = cycle(yymm, "LOCKED");
        List<Map<String, Object>> members = staff(3);
        assertThat(members).as("the demo unit has staff").hasSize(3);
        for (Map<String, Object> m : members) {
            result(cycle, yymm, ((Number) m.get("EMPLOYEE_ID")).longValue(), "STAFF");
        }
        BatchJobRequest request = request(yymm);
        JobExecution run = jobLauncher.startJob(JobLaunchService.parameters(request));
        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, byte[]> zip = entries(output(request.getId()));
        assertThat(zip.keySet())
                .containsExactlyElementsOf(
                        members.stream()
                                .map(m -> "payslip-" + m.get("EMP_NUM") + "-" + yymm + ".pdf")
                                .toList());
        assertThat(zip.values())
                .allSatisfy(
                        pdf ->
                                assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII))
                                        .isEqualTo("%PDF-"));
        BatchJobRequest done = requests.findById(request.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.getWriteCount()).isEqualTo(3);
        assertThat(done.getSkipCount()).isZero();
    }

    @Test
    void SRS_NF_7_aMemberWhosePayslipCannotBeReadIsLeftOutListedAndSkipped() throws Exception {
        String yymm = "199402";
        long cycle = cycle(yymm, "LOCKED");
        Map<String, Object> ok = staff(1).getFirst();
        result(cycle, yymm, ((Number) ok.get("EMPLOYEE_ID")).longValue(), "STAFF");
        // A labour member's result filed as STAFF: the payslip read refuses it (BR-ESEVA-005).
        List<Map<String, Object>> labour =
                jdbc.queryForList(
                        "SELECT employee_id, emp_num FROM employee WHERE employee_type <> 'STAFF'"
                                + " ORDER BY employee_id FETCH FIRST 1 ROWS ONLY");
        assertThat(labour).as("the demo seed has a labour member").isNotEmpty();
        result(cycle, yymm, ((Number) labour.getFirst().get("EMPLOYEE_ID")).longValue(), "STAFF");
        BatchJobRequest request = request(yymm);
        JobExecution run = jobLauncher.startJob(JobLaunchService.parameters(request));
        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, byte[]> zip = entries(output(request.getId()));
        String failed = "payslip-" + labour.getFirst().get("EMP_NUM") + "-" + yymm + ".pdf";
        assertThat(zip).containsKey("payslip-" + ok.get("EMP_NUM") + "-" + yymm + ".pdf");
        assertThat(zip).doesNotContainKey(ZipBundle.entryName(failed));
        assertThat(new String(zip.get(ZipBundle.MANIFEST), StandardCharsets.US_ASCII))
                .contains(ZipBundle.entryName(failed), "FciException");
        BatchJobRequest done = requests.findById(request.getId()).orElseThrow();
        assertThat(done.getWriteCount()).isEqualTo(1);
        assertThat(done.getSkipCount()).isEqualTo(1);
    }

    @Test
    void SRS_l3389_aMonthNotLockedFailsTheJobAndStoresNothing() throws Exception {
        String yymm = "199403";
        long cycle = cycle(yymm, "COMPUTED");
        result(cycle, yymm, ((Number) staff(1).getFirst().get("EMPLOYEE_ID")).longValue(), "STAFF");
        BatchJobRequest request = request(yymm);
        JobExecution run = jobLauncher.startJob(JobLaunchService.parameters(request));
        assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(output(request.getId())).isNull();
        BatchJobRequest failed = requests.findById(request.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("not locked");
    }

    @Test
    void OD_149_anOfficeWithoutResultsGivesAnEmptyZip() throws Exception {
        String yymm = "199404";
        cycle(yymm, "LOCKED");
        BatchJobRequest request = request(yymm);
        assertThat(jobLauncher.startJob(JobLaunchService.parameters(request)).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(entries(output(request.getId()))).isEmpty();
    }

    @Test
    void SRS_NF_7_aFailureWhileStoringFailsTheJobAndTheRestartStoresTheSameBytesOnce()
            throws Exception {
        String yymm = "199405";
        long cycle = cycle(yymm, "LOCKED");
        for (Map<String, Object> m : staff(3)) {
            result(cycle, yymm, ((Number) m.get("EMPLOYEE_ID")).longValue(), "STAFF");
        }
        // A clean run of the same month, for the bytes to compare with.
        BatchJobRequest clean = request(yymm);
        jobLauncher.startJob(JobLaunchService.parameters(clean));
        byte[] expected = output(clean.getId());

        jdbc.execute(
                "CREATE TRIGGER trg_test_bulk_outage BEFORE INSERT ON document_attachment FOR EACH"
                        + " ROW CALL \""
                        + OutageTrigger.class.getName()
                        + "\"");
        OutageTrigger.arm(1);
        BatchJobRequest request = request(yymm);
        JobExecution first = jobLauncher.startJob(JobLaunchService.parameters(request));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(output(request.getId())).isNull();

        OutageTrigger.arm(-1);
        BatchJobRequest failed = requests.findById(request.getId()).orElseThrow();
        failed.pick("test-node", clock.instant());
        requests.save(failed);
        JobExecution second = jobLauncher.startJob(JobLaunchService.parameters(failed));
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId())
                .isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(
                        documents.active(
                                PayslipPdfService.OUTPUT_ENTITY, String.valueOf(request.getId())))
                .as("stored once")
                .hasSize(1);
        byte[] restarted = output(request.getId());
        assertThat(restarted).as("the same request, the same bytes").isEqualTo(expected);
        assertThat(entries(restarted).keySet()).hasSize(3).doesNotHaveDuplicates();
    }
}
