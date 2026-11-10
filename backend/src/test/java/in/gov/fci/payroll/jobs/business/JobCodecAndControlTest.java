package in.gov.fci.payroll.jobs.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * C-13 job parameters and control commands (BR-CYC-011..017: re-runnable with unchanged
 * parameters): parameters are stored as sorted JSON so equal parameters hash equally; each control
 * command is valid only from its statuses; an applied or refused command records the node and
 * outcome.
 */
class JobCodecAndControlTest {

    /**
     * BR-CYC-011: the parameter hash identifies "the same job with the same parameters" across
     * releases. Requests queued before an upgrade (Jackson 2 → 3, ...) must keep their hash, so it
     * is pinned here to a value computed outside the code (Python: sorted keys, compact JSON).
     */
    @Test
    void BR_CYC_011_theParameterHashIsStableAcrossLibraryUpgrades() {
        Map<String, String> params =
                Map.of("period", "2026-10", "cycleUnit", "DH001", "note", "a \"q\" \\ b");
        assertThat(JobParameterCodec.toJson(params))
                .isEqualTo(
                        "{\"cycleUnit\":\"DH001\",\"note\":\"a \\\"q\\\" \\\\"
                                + " b\",\"period\":\"2026-10\"}");
        assertThat(JobParameterCodec.hash("PAYROLL_COMPUTATION", params))
                .isEqualTo("e7bc913b634dd9034a7057974a98521c7affa53084ff73dff910265c69b5324f");
    }

    private static final Instant NOW = Instant.parse("2026-10-21T05:00:00Z");

    @Test
    void BR_CYC_011_parametersAreStoredAsSortedJsonAndReadBack() {
        String json = JobParameterCodec.toJson(Map.of("payPeriod", "2026-10", "locationId", "4"));
        assertThat(json).isEqualTo("{\"locationId\":\"4\",\"payPeriod\":\"2026-10\"}");
        assertThat(JobParameterCodec.fromJson(json))
                .containsExactly(Map.entry("locationId", "4"), Map.entry("payPeriod", "2026-10"));
        assertThat(JobParameterCodec.fromJson(null)).isEmpty();
        assertThat(JobParameterCodec.fromJson("  ")).isEmpty();
        assertThatThrownBy(() -> JobParameterCodec.fromJson("{not json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void BR_CYC_011_equalParametersHashEquallyWhateverTheirOrder() {
        String a = JobParameterCodec.hash("DA_ARREAR", Map.of("a", "1", "b", "2"));
        String b = JobParameterCodec.hash("DA_ARREAR", Map.of("b", "2", "a", "1"));
        assertThat(a).isEqualTo(b).hasSize(64).matches("^[0-9a-f]+$");
        assertThat(JobParameterCodec.hash("PAYROLL_DRY_RUN", Map.of("a", "1", "b", "2")))
                .isNotEqualTo(a);
        assertThat(JobParameterCodec.hash("DA_ARREAR", Map.of("a", "1", "b", "3"))).isNotEqualTo(a);
    }

    @Test
    void C_13_eachControlCommandIsValidOnlyFromItsStatuses() {
        assertThat(ControlCommand.STOP.isValidFor(JobStatus.PENDING)).isTrue();
        assertThat(ControlCommand.STOP.isValidFor(JobStatus.RUNNING)).isTrue();
        assertThat(ControlCommand.STOP.isValidFor(JobStatus.PAUSED)).isTrue();
        assertThat(ControlCommand.STOP.isValidFor(JobStatus.COMPLETED)).isFalse();
        assertThat(ControlCommand.PAUSE.isValidFor(JobStatus.RUNNING)).isTrue();
        assertThat(ControlCommand.PAUSE.isValidFor(JobStatus.PAUSED)).isFalse();
        assertThat(ControlCommand.RESUME.isValidFor(JobStatus.PAUSED)).isTrue();
        assertThat(ControlCommand.RESUME.isValidFor(JobStatus.RUNNING)).isFalse();
        assertThat(ControlCommand.RESTART.isValidFor(JobStatus.FAILED)).isTrue();
        assertThat(ControlCommand.RESTART.isValidFor(JobStatus.STOPPED)).isTrue();
        assertThat(ControlCommand.RESTART.isValidFor(JobStatus.COMPLETED)).isFalse();
    }

    @Test
    void C_13_onlyResumeAndRestartStartANewAttempt() {
        assertThat(ControlCommand.RESUME.startsAttempt()).isTrue();
        assertThat(ControlCommand.RESTART.startsAttempt()).isTrue();
        assertThat(ControlCommand.STOP.startsAttempt()).isFalse();
        assertThat(ControlCommand.PAUSE.startsAttempt()).isFalse();
    }

    @Test
    void C_13_aControlIsPendingThenAppliedOrRefusedByANode() {
        BatchJobControl c =
                BatchJobControl.request(5L, ControlCommand.PAUSE, 7L, "maker", "lunch", NOW);
        assertThat(c.getStatus()).isEqualTo(BatchJobControl.Status.PENDING);
        assertThat(c.getJobRequestId()).isEqualTo(5L);
        assertThat(c.getCommand()).isEqualTo(ControlCommand.PAUSE);
        assertThat(c.getRequestedByLogin()).isEqualTo("maker");
        assertThat(c.getReason()).isEqualTo("lunch");
        assertThat(c.getRequestedAt()).isEqualTo(NOW);
        c.applied("batch-1", "paused", NOW.plusSeconds(2));
        assertThat(c.getStatus()).isEqualTo(BatchJobControl.Status.APPLIED);
        assertThat(c.getAppliedBy()).isEqualTo("batch-1");
        assertThat(c.getAppliedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(c.getOutcomeMessage()).isEqualTo("paused");

        BatchJobControl refused =
                BatchJobControl.request(5L, ControlCommand.RESUME, 7L, "maker", "go", NOW);
        refused.rejected("batch-2", "x".repeat(600), NOW);
        assertThat(refused.getStatus()).isEqualTo(BatchJobControl.Status.REJECTED);
        assertThat(refused.getAppliedBy()).isEqualTo("batch-2");
        assertThat(refused.getOutcomeMessage()).hasSize(500);
        BatchJobControl exact = BatchJobControl.request(5L, ControlCommand.STOP, 7L, "m", "r", NOW);
        exact.rejected("b", "y".repeat(500), NOW);
        assertThat(exact.getOutcomeMessage()).hasSize(500);
        BatchJobControl none = BatchJobControl.request(5L, ControlCommand.STOP, 7L, "m", "r", NOW);
        none.rejected("b", null, NOW);
        assertThat(none.getOutcomeMessage()).isNull();
    }
}
