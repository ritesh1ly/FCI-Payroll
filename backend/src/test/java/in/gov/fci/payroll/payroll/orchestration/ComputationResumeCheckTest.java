package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SRS-15-26 / BR-CYC-013 for the computation jobs: the latest run of the request against the
 * request's parameter hash and the version set in force for the run's period now.
 */
class ComputationResumeCheckTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PayConfiguration configuration = mock(PayConfiguration.class);
    private final ComputationResumeCheck check = new ComputationResumeCheck(jdbc, configuration);

    @BeforeEach
    void versionsInForceForJuly() {
        PayConfiguration.Snapshot july =
                new PayConfiguration.Snapshot(
                        LocalDate.of(2026, 7, 1),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        false,
                        Map.of(),
                        Map.of(),
                        "v-now");
        when(configuration.staffConfiguration(LocalDate.of(2026, 7, 1))).thenReturn(july);
    }

    private void run(String parameterHash, String versions, String json) {
        when(jdbc.queryForList(anyString(), eq("{\"jobRequestId\":7,%")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "PARAMETER_HASH", parameterHash,
                                        "RULE_VERSION_SET_HASH", versions,
                                        "PARAMETERS_JSON", json)));
    }

    private static final String JULY =
            "{\"jobRequestId\":7,\"locationId\":4,\"payPeriod\":\"2026-07\"}";

    @Test
    void BR_CYC_013_anotherModulesJobIsNotJudged() {
        assertThat(check.refusal(7, "DA_ARREAR", "p")).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void BR_CYC_013_aRequestWhoseRunNeverOpenedComputedNothingAndContinues() {
        when(jdbc.queryForList(anyString(), eq("{\"jobRequestId\":7,%"))).thenReturn(List.of());
        assertThat(check.refusal(7, "PAYROLL_COMPUTATION", "p")).isEmpty();
    }

    @Test
    void BR_CYC_013_unchangedHashesContinue() {
        run("p", "v-now", JULY);
        assertThat(check.refusal(7, "PAYROLL_COMPUTATION", "p")).isEmpty();
        assertThat(check.refusal(7, "PAYROLL_DRY_RUN", "p")).isEmpty();
    }

    @Test
    void BR_CYC_013_aVersionActivatedForThePeriodRefusesWithTheRulesReason() {
        run("p", "v-start", JULY);
        assertThat(check.refusal(7, "PAYROLL_COMPUTATION", "p"))
                .contains(ApiErrorCode.JOB_RESUME_RULES_CHANGED);
    }

    @Test
    void BR_CYC_013_changedParametersRefuseWithTheParametersReason() {
        run("p", "v-now", JULY);
        assertThat(check.refusal(7, "PAYROLL_COMPUTATION", "q"))
                .contains(ApiErrorCode.JOB_RESUME_PARAMETERS_CHANGED);
    }

    @Test
    void BR_CYC_013_aRequestWithoutAHashIsComparedAsTheRunRecordedIt() {
        run(ComputationResumeCheck.NO_PARAMETER_HASH, "v-now", JULY);
        assertThat(check.refusal(7, "PAYROLL_COMPUTATION", null)).isEmpty();
    }

    @Test
    void BR_CYC_013_aRunWithoutItsPeriodIsNotContinuedOnAGuess() {
        run("p", "v-now", "{\"jobRequestId\":7}");
        assertThat(check.refusal(7, "PAYROLL_COMPUTATION", "p"))
                .contains(ApiErrorCode.JOB_RESUME_RULES_CHANGED);
    }
}
