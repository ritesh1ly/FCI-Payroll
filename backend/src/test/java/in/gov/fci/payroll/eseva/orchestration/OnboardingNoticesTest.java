package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.workflow.api.ApproverDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Onboarding notices (plan 16 D16-36, D16-37; SRS-1-12): a returned record is told with its reason
 * to Establishment / HR of the paying unit; a record in Payroll Setup Pending past
 * ONBOARDING_AGEING_DAYS is told once; nothing while the setting is unseeded.
 */
class OnboardingNoticesTest {

    private static final Instant NOW = Instant.parse("2026-10-28T05:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApproverDirectory directory = mock(ApproverDirectory.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final StaffNotices staff =
            new StaffNotices(jdbc, directory, parameters, Clock.fixed(NOW, ZoneOffset.UTC));
    private final OnboardingNotices notices =
            new OnboardingNotices(jdbc, directory, parameters, staff, org);

    @Test
    void SRS_1_12_hrOfThePayingUnitLearnsOfTheReturnAndItsReason() {
        when(directory.holders("ESTABLISHMENT_HR", 3L)).thenReturn(List.of(41L));
        notices.on(
                new EmployeeEvents.OnboardingReturned(9L, "104990", 3L, " Flag missing ", "ddo"));
        notices.on(new EmployeeEvents.OnboardingReturned(9L, "104990", 3L, null, "ddo"));
        verify(jdbc)
                .update(
                        contains("INSERT INTO notification"),
                        eq(41L),
                        eq("ONBOARDING_RETURNED"),
                        eq("employee"),
                        eq("104990"),
                        eq("New record 104990 returned by ddo: Flag missing"),
                        any(),
                        any());
        verify(jdbc)
                .update(
                        contains("INSERT INTO notification"),
                        eq(41L),
                        eq("ONBOARDING_RETURNED"),
                        eq("employee"),
                        eq("104990"),
                        eq("New record 104990 returned by ddo: "),
                        any(),
                        any());
    }

    @Test
    void D16_37_nothingIsSentWhileTheAgeingDaysAreUnseeded() {
        when(parameters.configured(SystemParameters.Code.ONBOARDING_AGEING_DAYS))
                .thenReturn(OptionalLong.empty());
        assertThat(notices.run(NOW)).isZero();
        assertThat(notices.name()).isEqualTo("onboardingAgeingAlerts");
        verify(jdbc, never()).queryForList(any(String.class), any(Object[].class));
    }

    @Test
    void D16_37_aRecordWaitingTooLongIsToldOnceToHrOfItsUnit() {
        when(parameters.configured(SystemParameters.Code.ONBOARDING_AGEING_DAYS))
                .thenReturn(OptionalLong.of(5));
        when(jdbc.queryForList(contains("PAYROLL_SETUP_PENDING"), any(Object[].class)))
                .thenReturn(
                        List.of(
                                Map.of("EMP_NUM", "104990", "LOCATION_ID", 12L),
                                Map.of("EMP_NUM", "104991", "LOCATION_ID", 99L)));
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(org.cycleUnitOf(12L)).thenReturn(Optional.of(unit));
        when(org.cycleUnitOf(99L)).thenReturn(Optional.empty());
        when(directory.holders("ESTABLISHMENT_HR", 3L)).thenReturn(List.of(41L, 42L));
        assertThat(notices.run(NOW)).as("the record without a unit is skipped").isEqualTo(2);
        verify(jdbc, times(2))
                .update(
                        contains("INSERT INTO notification"),
                        any(),
                        eq("ONBOARDING_OVERDUE"),
                        eq("employee"),
                        eq("104990"),
                        contains("more than 5 days"),
                        any(),
                        any());
    }
}
