package in.gov.fci.payroll.tax.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.jobs.batch.BatchTestSupport;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.platform.export.PdfDocument;
import in.gov.fci.payroll.platform.export.ZipBundle;
import in.gov.fci.payroll.tax.orchestration.Form16Service;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * FORM16_BULK end to end (OD-149; SRS-NF-2, SRS-NF-7; SRS-7-9, SRS-7-21): one ZIP of the paying
 * unit's certificates of a year whose Part B is generated, in staff-number order; a certificate
 * that cannot be printed is left out, listed in the manifest and skipped while the job completes; a
 * certificate without Part B is not in the file; a reissue (a new Part B) gives the new version.
 * The single certificate's rendering is Form16Service's own (Form16ServiceTest); here it is
 * replaced by a PDF that names the certificate and its Part B time, so the file's content shows
 * which version went in. Fixture year 1995-96.
 */
class Form16BulkJobIT extends BatchTestSupport {

    private static final String YEAR = "1995-96";

    @Autowired
    @Qualifier("form16BulkJob")
    private Job job;

    @Autowired private DocumentStore documents;

    @MockitoSpyBean private Form16Service form16;

    private long failing = -1;

    @BeforeEach
    void stubRendering() {
        jobLauncher.setJob(job);
        doAnswer(
                        a -> {
                            long id = a.getArgument(0);
                            if (id == failing) {
                                throw new IllegalStateException("render");
                            }
                            Timestamp at =
                                    jdbc.queryForObject(
                                            "SELECT part_b_generated_at FROM form16 WHERE form16_id"
                                                    + " = ?",
                                            Timestamp.class,
                                            id);
                            return PdfDocument.portrait("t", null)
                                    .heading("CERTIFICATE " + id + " PART B " + at.toInstant())
                                    .toBytes();
                        })
                .when(form16)
                .certificatePdfForJob(anyLong(), anyString());
    }

    private long unit() {
        return jdbc.queryForObject(
                "SELECT location_id FROM org_location WHERE location_code = 'DH001'", Long.class);
    }

    private List<Map<String, Object>> staff(int count) {
        return named.queryForList(
                "SELECT employee_id, emp_num FROM employee WHERE employee_type = 'STAFF' AND"
                        + " location_id IN (:locations) ORDER BY emp_num FETCH FIRST "
                        + count
                        + " ROWS ONLY",
                Map.of("locations", org.subtree(unit())));
    }

    private long certificate(long employee, String year, boolean partB) {
        jdbc.update(
                "INSERT INTO form16 (employee_id, fin_year, gross_salary, taxable_income,"
                        + " tax_deducted, part_b_generated_at, release_status) VALUES (?, ?, 100,"
                        + " 100, 1, ?, 'PENDING_PART_A')",
                employee,
                year,
                partB ? Timestamp.valueOf("1996-05-01 10:00:00") : null);
        return jdbc.queryForObject(
                "SELECT MAX(form16_id) FROM form16 WHERE employee_id = ? AND fin_year = ?",
                Long.class,
                employee,
                year);
    }

    private BatchJobRequest request(String year) {
        return pickedRequest(
                JobType.FORM16_BULK,
                Map.of("locationId", String.valueOf(unit()), "finYear", year),
                unit());
    }

    private static String text(byte[] pdf) throws java.io.IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d);
        }
    }

    private Map<String, String> zip(long requestId) throws Exception {
        List<DocumentStore.AttachmentView> files =
                documents.active(Form16Service.OUTPUT_ENTITY, String.valueOf(requestId));
        byte[] bytes =
                documents
                        .read(
                                "test.maker",
                                null,
                                Form16Service.OUTPUT_ENTITY,
                                String.valueOf(requestId),
                                files.getLast().id())
                        .bytes();
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                byte[] content = in.readAllBytes();
                out.put(
                        e.getName(),
                        e.getName().endsWith(".pdf")
                                ? text(content)
                                : new String(content, StandardCharsets.US_ASCII));
            }
        }
        return out;
    }

    @Test
    void SRS_NF_2_OD_149_theYearsCertificatesInStaffNumberOrder_aFailedOneListed_noPartBNotIn()
            throws Exception {
        List<Map<String, Object>> members = staff(3);
        long a = certificate(((Number) members.get(0).get("EMPLOYEE_ID")).longValue(), YEAR, true);
        long b = certificate(((Number) members.get(1).get("EMPLOYEE_ID")).longValue(), YEAR, true);
        certificate(((Number) members.get(2).get("EMPLOYEE_ID")).longValue(), YEAR, false);
        failing = b;
        BatchJobRequest request = request(YEAR);
        JobExecution run = jobLauncher.startJob(JobLaunchService.parameters(request));
        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, String> zip = zip(request.getId());
        String first = "form16-" + members.get(0).get("EMP_NUM") + "-" + YEAR + ".pdf";
        String second = "form16-" + members.get(1).get("EMP_NUM") + "-" + YEAR + ".pdf";
        assertThat(zip.keySet()).containsExactly(ZipBundle.entryName(first), ZipBundle.MANIFEST);
        assertThat(zip.get(ZipBundle.entryName(first))).contains("CERTIFICATE " + a);
        assertThat(zip.get(ZipBundle.MANIFEST))
                .contains(ZipBundle.entryName(second), "IllegalStateException");
        BatchJobRequest done = requests.findById(request.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.getWriteCount()).isEqualTo(1);
        assertThat(done.getSkipCount()).isEqualTo(1);
    }

    @Test
    void SRS_7_21_aReissuedCertificateGoesInAsItsNewVersion() throws Exception {
        String year = "1996-97";
        long id =
                certificate(
                        ((Number) staff(1).getFirst().get("EMPLOYEE_ID")).longValue(), year, true);
        BatchJobRequest before = request(year);
        jobLauncher.startJob(JobLaunchService.parameters(before));
        assertThat(zip(before.getId()).values()).singleElement().asString().contains("1996-05-01");
        jdbc.update(
                "UPDATE form16 SET part_b_generated_at = ? WHERE form16_id = ?",
                Timestamp.valueOf("1997-06-15 09:00:00"),
                id);
        BatchJobRequest after = request(year);
        assertThat(jobLauncher.startJob(JobLaunchService.parameters(after)).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(zip(after.getId()).values()).singleElement().asString().contains("1997-06-15");
    }

    @Test
    void OD_149_aUnitWithoutCertificatesGivesAnEmptyZip() throws Exception {
        BatchJobRequest request = request("1990-91");
        assertThat(jobLauncher.startJob(JobLaunchService.parameters(request)).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(zip(request.getId())).isEmpty();
    }
}
