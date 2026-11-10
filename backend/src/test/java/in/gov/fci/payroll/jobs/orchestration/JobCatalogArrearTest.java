package in.gov.fci.payroll.jobs.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.YearMonth;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * C-13 DA / HRA arrear jobs (plan 13; SRS-8-6, SRS-8-10, SRS-NF-7): a period range, from not after
 * to, within the retro window (SYSTEM_PARAMETER MAX_RETRO_PERIOD), every parameter required and
 * only the job's own; the arrear's end month files the request.
 */
class JobCatalogArrearTest {

    private final PayrollPeriods periods = mock(PayrollPeriods.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final JobCatalog catalog = new JobCatalog(parameters, periods);

    JobCatalogArrearTest() {
        when(periods.current()).thenReturn(YearMonth.of(2026, 10));
        when(parameters.maxRetroMonths()).thenReturn(OptionalLong.of(6));
    }

    private void refused(Map<String, String> params, String name) {
        assertThatThrownBy(() -> catalog.validate(JobType.HRA_ARREAR, params))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ApiErrorCode.JOB_PARAMETER_INVALID);
                            assertThat(e.getMessageArgs()).containsExactly(name);
                        });
    }

    // SC-13-0038: the effective span lies within the back-date limit.
    @Test
    void SRS_8_6_anArrearRunsOverARangeFromNotAfterToWithinTheRetroWindow() {
        Map<String, String> ok =
                Map.of("locationId", "3", "fromPeriod", "2026-05", "toPeriod", "2026-10");
        assertThat(catalog.validate(JobType.HRA_ARREAR, ok)).isEqualTo(new java.util.TreeMap<>(ok));
        assertThat(JobCatalog.periodOf(JobType.DA_ARREAR, ok)).isEqualTo("2026-10");
        assertThat(JobCatalog.periodOf(JobType.PAYROLL_DRY_RUN, Map.of("payPeriod", "2026-09")))
                .isEqualTo("2026-09");
        refused(
                Map.of("locationId", "3", "fromPeriod", "2026-09", "toPeriod", "2026-08"),
                "fromPeriod");
        refused(
                Map.of("locationId", "3", "fromPeriod", "2026-03", "toPeriod", "2026-10"),
                "fromPeriod");
        refused(Map.of("locationId", "3", "toPeriod", "2026-10"), "fromPeriod");
        refused(Map.of("locationId", "3", "fromPeriod", " ", "toPeriod", "2026-10"), "fromPeriod");
        refused(
                Map.of("locationId", "3", "fromPeriod", "2026-5", "toPeriod", "2026-10"),
                "fromPeriod");
        refused(
                Map.of("locationId", "3", "fromPeriod", "2026-05", "toPeriod", "2026-11"),
                "toPeriod");
        refused(
                Map.of(
                        "locationId",
                        "3",
                        "fromPeriod",
                        "2026-05",
                        "toPeriod",
                        "2026-10",
                        "payPeriod",
                        "2026-10"),
                "payPeriod");
        assertThatThrownBy(() -> catalog.validate(JobType.HRA_ARREAR, null))
                .isInstanceOf(FciException.class);
    }

    /** SRS-8-6 boundaries: a span or a period exactly at the limit is inside, one more is not. */
    @Test
    void SRS_8_6_aSpanOrPeriodExactlyAtTheBackDateLimitIsAcceptedAndOneMonthMoreIsNot() {
        Map<String, String> six =
                Map.of("locationId", "3", "fromPeriod", "2026-04", "toPeriod", "2026-10");
        assertThat(catalog.validate(JobType.DA_ARREAR, six)).containsEntry("fromPeriod", "2026-04");
        Map<String, String> atLimit = Map.of("locationId", "3", "payPeriod", "2026-04");
        assertThat(catalog.validate(JobType.PAYROLL_COMPUTATION, atLimit))
                .containsEntry("payPeriod", "2026-04");
        assertThatThrownBy(
                        () ->
                                catalog.validate(
                                        JobType.PAYROLL_COMPUTATION,
                                        Map.of("locationId", "3", "payPeriod", "2026-03")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("payPeriod"));
    }

    /**
     * SRS-8-6, R-RULE, KB CF-49 (CLR-147): the back-date limit has no source value, so outside the
     * demo data it is unset. A check whose answer depends on it is refused "not configured", never
     * decided with a guessed window; the current period and a one-month span need no limit.
     */
    @Test
    void SRS_8_6_anUnsetBackDateLimitRefusesAnEarlierPeriodNotConfiguredAndKeepsTheCurrentOne() {
        SystemParameters unset = mock(SystemParameters.class);
        when(unset.maxRetroMonths()).thenReturn(OptionalLong.empty());
        when(unset.today()).thenReturn(java.time.LocalDate.of(2026, 10, 29));
        JobCatalog withoutLimit = new JobCatalog(unset, periods);
        Map<String, String> current = Map.of("locationId", "3", "payPeriod", "2026-10");
        assertThat(withoutLimit.validate(JobType.PAYROLL_COMPUTATION, current))
                .containsEntry("payPeriod", "2026-10");
        Map<String, String> oneMonth =
                Map.of("locationId", "3", "fromPeriod", "2026-10", "toPeriod", "2026-10");
        assertThat(withoutLimit.validate(JobType.HRA_ARREAR, oneMonth))
                .containsEntry("toPeriod", "2026-10");
        for (Map<String, String> needsTheLimit :
                java.util.List.of(
                        Map.of("locationId", "3", "payPeriod", "2026-09"),
                        Map.of(
                                "locationId",
                                "3",
                                "fromPeriod",
                                "2026-09",
                                "toPeriod",
                                "2026-10"))) {
            JobType type =
                    needsTheLimit.containsKey("payPeriod")
                            ? JobType.PAYROLL_DRY_RUN
                            : JobType.DA_ARREAR;
            assertThatThrownBy(() -> withoutLimit.validate(type, needsTheLimit))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> {
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.RULE_NOT_CONFIGURED);
                                assertThat(e.getMessageArgs()[0]).isEqualTo("MAX_RETRO_PERIOD");
                            });
        }
        // A future period is refused as before, without reading the limit.
        assertThatThrownBy(
                        () ->
                                withoutLimit.validate(
                                        JobType.PAYROLL_COMPUTATION,
                                        Map.of("locationId", "3", "payPeriod", "2026-11")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.JOB_PARAMETER_INVALID));
    }
}
