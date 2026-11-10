package in.gov.fci.payroll.jobs.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The parameter hash (duplicate guard, resume rule) and the control-command state machine. */
class JobRulesTest {

    @Test
    void theHashIgnoresKeyOrderButNotValuesOrJob() {
        String a =
                JobParameterCodec.hash(
                        "PAYROLL_DRY_RUN", Map.of("locationId", "3", "payPeriod", "2026-07"));
        String b =
                JobParameterCodec.hash(
                        "PAYROLL_DRY_RUN",
                        new java.util.LinkedHashMap<>(
                                Map.of("payPeriod", "2026-07", "locationId", "3")));
        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(
                        JobParameterCodec.hash(
                                "PAYROLL_DRY_RUN",
                                Map.of("locationId", "3", "payPeriod", "2026-06")))
                .isNotEqualTo(a);
        assertThat(
                        JobParameterCodec.hash(
                                "DA_ARREAR", Map.of("locationId", "3", "payPeriod", "2026-07")))
                .isNotEqualTo(a);
    }

    @Test
    void parametersRoundTripAsCanonicalJson() {
        String json = JobParameterCodec.toJson(Map.of("payPeriod", "2026-07", "locationId", "3"));
        assertThat(json).isEqualTo("{\"locationId\":\"3\",\"payPeriod\":\"2026-07\"}");
        assertThat(JobParameterCodec.fromJson(json))
                .containsEntry("locationId", "3")
                .containsEntry("payPeriod", "2026-07");
    }

    @Test
    void commandsAreValidOnlyInTheirStates() {
        assertThat(ControlCommand.PAUSE.validFrom()).containsExactly(JobStatus.RUNNING);
        assertThat(ControlCommand.RESUME.validFrom()).containsExactly(JobStatus.PAUSED);
        assertThat(ControlCommand.RESTART.validFrom())
                .containsExactlyInAnyOrder(JobStatus.FAILED, JobStatus.STOPPED);
        assertThat(ControlCommand.STOP.validFrom())
                .containsExactlyInAnyOrder(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.PAUSED);
        assertThat(ControlCommand.RESTART.isValidFor(JobStatus.COMPLETED)).isFalse();
        assertThat(ControlCommand.STOP.isValidFor(JobStatus.PICKED)).isFalse();
        assertThat(ControlCommand.RESUME.startsAttempt()).isTrue();
        assertThat(ControlCommand.STOP.startsAttempt()).isFalse();
    }

    @Test
    void openStatusesBlockDuplicates() {
        assertThat(JobStatus.OPEN)
                .containsExactlyInAnyOrder(
                        JobStatus.AWAITING_APPROVAL,
                        JobStatus.PENDING,
                        JobStatus.PICKED,
                        JobStatus.RUNNING,
                        JobStatus.PAUSED);
        assertThat(JobStatus.REJECTED.isOpen()).isFalse();
        assertThat(JobStatus.COMPLETED.isOpen()).isFalse();
        assertThat(JobStatus.FAILED.isOpen()).isFalse();
    }
}
