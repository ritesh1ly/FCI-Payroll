package in.gov.fci.payroll.jobs.batch;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SRS-NF-2 / SRS-NF-5 (deep review DR-18) on the batch node: when a job a user asked for ends, the
 * user finds an in-app notice saying how it went, written with the job's outcome; the request's id
 * ties the notice to the job on C-13.
 */
class JobEndNoticeIT extends BatchTestSupport {

    @Autowired
    @Qualifier("payrollDryRunJob")
    private Job job;

    @BeforeEach
    void useJob() {
        jobLauncher.setJob(job);
    }

    @Test
    void SRS_NF_2_theRequesterFindsANoticeWhenTheirDryRunEnds() throws Exception {
        Unit unit = busiestUnit();
        Long user = jdbc.queryForObject("SELECT MIN(user_id) FROM app_user", Long.class);
        BatchJobRequest request =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-06"),
                        unit.locationId());
        ReflectionTestUtils.setField(request, "requestedBy", user);
        request = requests.save(request);

        JobExecution execution = jobLauncher.startJob(JobLaunchService.parameters(request));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> notice =
                jdbc.queryForMap(
                        "SELECT recipient_user_id, channel, notification_type, message,"
                                + " delivery_status FROM notification WHERE related_entity_name ="
                                + " 'batch_job_request' AND related_entity_key = ?",
                        String.valueOf(request.getId()));
        assertThat(((Number) notice.get("RECIPIENT_USER_ID")).longValue()).isEqualTo(user);
        assertThat(notice.get("CHANNEL")).isEqualTo("IN_APP");
        assertThat(notice.get("NOTIFICATION_TYPE")).isEqualTo("JOB_COMPLETED");
        assertThat(notice.get("DELIVERY_STATUS")).isEqualTo("DELIVERED");
        assertThat((String) notice.get("MESSAGE"))
                .startsWith(
                        "Job " + request.getId() + " (PAYROLL_DRY_RUN, 2026-06) completed: read ");
    }
}
