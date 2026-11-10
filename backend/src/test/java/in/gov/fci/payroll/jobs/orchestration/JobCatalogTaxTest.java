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
import org.junit.jupiter.api.Test;

/**
 * The parameters of the Module 08 background jobs (SRS-NF-7): a unit, a financial year 2026-27 not
 * after the one the current period is in, and a quarter Q1 - Q4; nothing else.
 */
class JobCatalogTaxTest {

    private final PayrollPeriods periods = mock(PayrollPeriods.class);
    private final JobCatalog catalog = new JobCatalog(mock(SystemParameters.class), periods);

    private void refused(JobType type, Map<String, String> params, String name) {
        assertThatThrownBy(() -> catalog.validate(type, params))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ApiErrorCode.JOB_PARAMETER_INVALID);
                            assertThat(e.getMessageArgs()).containsExactly(name);
                        });
    }

    @Test
    void SRS_NF_7_aYearAndAQuarterAreWellFormedAndNotInTheFuture() {
        when(periods.current()).thenReturn(YearMonth.of(2027, 2));
        assertThat(
                        catalog.validate(
                                JobType.TAX_24Q_RETURN,
                                Map.of("locationId", " 7 ", "finYear", "2026-27", "quarter", "Q4")))
                .containsExactly(
                        Map.entry("finYear", "2026-27"),
                        Map.entry("locationId", "7"),
                        Map.entry("quarter", "Q4"));
        assertThat(
                        catalog.validate(
                                JobType.TAX_FORM16_PART_B,
                                Map.of("locationId", "7", "finYear", "1999-00")))
                .containsEntry("finYear", "1999-00");
        refused(
                JobType.TAX_FORM16_PART_B,
                Map.of("locationId", "7", "finYear", "2026-28"),
                "finYear");
        refused(
                JobType.TAX_FORM16_PART_B,
                Map.of("locationId", "7", "finYear", "26-27"),
                "finYear");
        // February 2027 is in 2026-27: 2027-28 has not begun.
        refused(
                JobType.TAX_FORM16_PART_B,
                Map.of("locationId", "7", "finYear", "2027-28"),
                "finYear");
        refused(
                JobType.TAX_24Q_RETURN,
                Map.of("locationId", "7", "finYear", "2026-27", "quarter", "Q5"),
                "quarter");
        refused(JobType.TAX_24Q_RETURN, Map.of("locationId", "7", "finYear", "2026-27"), "quarter");
        refused(
                JobType.TAX_FORM16_PART_B,
                Map.of("locationId", "7", "finYear", "2026-27", "quarter", "Q1"),
                "quarter");
    }

    @Test
    void SRS_NF_7_fromAprilTheNewYearMayBeNamed() {
        when(periods.current()).thenReturn(YearMonth.of(2027, 4));
        assertThat(
                        catalog.validate(
                                JobType.TAX_FORM16_PART_B,
                                Map.of("locationId", "7", "finYear", "2027-28")))
                .containsEntry("finYear", "2027-28");
        assertThat(JobCatalog.periodOf(JobType.TAX_FORM16_PART_B, Map.of("finYear", "2027-28")))
                .isNull();
    }
}
