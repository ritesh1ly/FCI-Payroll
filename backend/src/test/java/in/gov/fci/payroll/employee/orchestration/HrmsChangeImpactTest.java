package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * UC I-21 l.3813 (US-GAP-276): every task with pay effect carries its pay impact preview by month;
 * a change applied at once without pay effect (SRS-1-19), or a task without an effective date,
 * carries none.
 */
class HrmsChangeImpactTest {

    private static HrmsChangeService.TaskView task(String status, LocalDate effective) {
        return new HrmsChangeService.TaskView(
                1L,
                "HCA-1",
                "100041",
                "A B",
                "BASIC_PAY",
                "Basic pay",
                "SERVICE_PAY",
                "50000",
                "52000",
                effective,
                "R-1",
                "HRMS_PUSH",
                status,
                "2026-11",
                false,
                Instant.parse("2026-11-01T00:00:00Z"),
                1L,
                true,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null);
    }

    @Test
    void SRS_1_28_everyTaskWithPayEffectShowsItsPayImpactByMonth() {
        HrmsChangeService.PayImpact back =
                HrmsChangeService.impact(task("PENDING_CHECKER", LocalDate.of(2026, 9, 1)));
        assertThat(back)
                .isEqualTo(
                        new HrmsChangeService.PayImpact(
                                "2026-09", "2026-11", "2026-09", "2026-10"));
        assertThat(
                        HrmsChangeService.impact(
                                task("PENDING_ORDER_DETAILS", LocalDate.of(2026, 11, 20))))
                .isEqualTo(new HrmsChangeService.PayImpact("2026-11", "2026-11", null, null));
        assertThat(HrmsChangeService.impact(task("AUTO_APPLIED", LocalDate.of(2026, 9, 1))))
                .isNull();
        assertThat(HrmsChangeService.impact(task("PENDING_CHECKER", null))).isNull();
    }

    // Wave C review (SRS-9-23): a field whose owner shows no month-by-month impact carries none.
    @Test
    void SRS_1_28_aFieldWhoseOwnerShowsNoImpactCarriesNone() {
        HrmsChangeService.TaskView task = task("PENDING_CHECKER", LocalDate.of(2026, 9, 1));
        in.gov.fci.payroll.employee.api.HrmsTaskEffects owner =
                org.mockito.Mockito.mock(in.gov.fci.payroll.employee.api.HrmsTaskEffects.class);
        org.mockito.Mockito.when(owner.showsPayImpact("BASIC_PAY")).thenReturn(false);
        assertThat(HrmsChangeService.impact(task, java.util.Optional.of(owner))).isNull();
        org.mockito.Mockito.when(owner.showsPayImpact("BASIC_PAY")).thenReturn(true);
        assertThat(HrmsChangeService.impact(task, java.util.Optional.of(owner)))
                .isEqualTo(HrmsChangeService.impact(task));
        assertThat(HrmsChangeService.impact(task, java.util.Optional.empty()))
                .isEqualTo(HrmsChangeService.impact(task));
    }
}
