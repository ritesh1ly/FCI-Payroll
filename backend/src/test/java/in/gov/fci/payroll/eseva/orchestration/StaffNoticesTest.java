package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.jobs.api.JobEnded;
import in.gov.fci.payroll.payroll.api.CycleReturned;
import in.gov.fci.payroll.workflow.api.ApproverDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Staff notices (owner 2026-10-28; SRS-15-30, SRS-1-68): a returned cycle is told to the unit's
 * Payroll Makers; an I-21 task past I21_AGEING_DAYS is told once to the unit's Maker and Checker;
 * nothing is sent while the setting is unseeded.
 */
class StaffNoticesTest {

    private static final Instant NOW = Instant.parse("2026-10-28T05:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApproverDirectory directory = mock(ApproverDirectory.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final StaffNotices notices =
            new StaffNotices(jdbc, directory, parameters, Clock.fixed(NOW, ZoneOffset.UTC));

    private static CycleReturned returned(String reason) {
        return new CycleReturned(5L, 9L, "2026-10", "DH001", "UNIT", reason, "ddo.delhi", NOW);
    }

    @Test
    void SRS_15_30_theUnitsMakersLearnOfTheReturnAndItsReason() {
        when(jdbc.queryForObject(contains("FROM payroll_cycle_instance"), eq(Long.class), eq(9L)))
                .thenReturn(3L);
        when(directory.holders("PAYROLL_MAKER", 3L)).thenReturn(List.of(21L, 22L));
        notices.on(returned("Wrong pay days"));
        verify(jdbc, times(2))
                .update(
                        contains("INSERT INTO notification"),
                        any(),
                        eq("CYCLE_RETURNED"),
                        eq("cycle_return"),
                        eq("5"),
                        contains("returned by ddo.delhi: Wrong pay days"),
                        any(),
                        any());
    }

    @Test
    void SRS_15_30_aCycleWithoutAnInstanceIsNotTold() {
        when(jdbc.queryForObject(contains("FROM payroll_cycle_instance"), eq(Long.class), eq(9L)))
                .thenReturn(null);
        notices.on(returned(null));
        verify(directory, never()).holders(anyString(), any());
    }

    @Test
    void SRS_1_68_nothingIsSentWhileTheAgeingDaysAreUnseeded() {
        when(parameters.configured(SystemParameters.Code.I21_AGEING_DAYS))
                .thenReturn(OptionalLong.empty());
        assertThat(notices.run(NOW)).isZero();
        assertThat(notices.name()).isEqualTo("i21AgeingAlerts");
    }

    // SC-16-0160: a task past the ageing period alerts the Maker and the Checker (the next level
    // waits for FCI, PENDING 16-ageing-escalation).
    @Test
    void SRS_1_68_anOverdueTaskIsToldOnceToTheMakerAndChecker() {
        when(parameters.configured(SystemParameters.Code.I21_AGEING_DAYS))
                .thenReturn(OptionalLong.of(3));
        when(jdbc.queryForList(contains("FROM hrms_change_task"), any(Object[].class)))
                .thenReturn(List.of(Map.of("HRMS_CHANGE_TASK_ID", 77L, "PAYING_UNIT_ID", 3L)));
        when(directory.holders("PAYROLL_MAKER", 3L)).thenReturn(List.of(21L));
        when(directory.holders("PAYROLL_CHECKER", 3L)).thenReturn(List.of(31L, 21L));
        assertThat(notices.run(NOW)).as("a user holding both roles is told once").isEqualTo(2);
        verify(jdbc, times(2))
                .update(
                        contains("INSERT INTO notification"),
                        any(),
                        eq("I21_OVERDUE"),
                        eq("hrms_change_task"),
                        eq("77"),
                        contains("more than 3 days"),
                        any(),
                        any());
    }

    /**
     * SRS-15-30: a return without a reason is told without one, and a message beyond the column
     * (2000) is cut to it; a session without an access group reads no staff notices.
     */
    @Test
    void SRS_15_30_aReturnWithoutAReasonIsToldAndALongMessageIsCutAndOnlyStaffRead() {
        when(jdbc.queryForObject(contains("FROM payroll_cycle_instance"), eq(Long.class), eq(9L)))
                .thenReturn(3L);
        when(directory.holders("PAYROLL_MAKER", 3L)).thenReturn(List.of(21L));
        notices.on(returned(null));
        verify(jdbc)
                .update(
                        contains("INSERT INTO notification"),
                        any(),
                        eq("CYCLE_RETURNED"),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.endsWith("returned by ddo.delhi: "),
                        any(),
                        any());
        assertThat(notices.notify(List.of(21L), "T", "e", "k", "x".repeat(2100))).isEqualTo(1);
        verify(jdbc)
                .update(
                        contains("INSERT INTO notification"),
                        any(),
                        eq("T"),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.argThat((String m) -> m.length() == 2000),
                        any(),
                        any());
        in.gov.fci.payroll.platform.security.AccessContext member =
                mock(in.gov.fci.payroll.platform.security.AccessContext.class);
        when(member.userId()).thenReturn(9L);
        when(member.groups()).thenReturn(List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                notices.mine(
                                        member,
                                        new in.gov.fci.payroll.platform.paging.PageSpec(0, 20)))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
    }

    private void told(String type, String text) {
        verify(jdbc)
                .update(
                        contains("INSERT INTO notification"),
                        eq(21L),
                        eq(type),
                        eq("batch_job_request"),
                        eq("132"),
                        eq(text),
                        any(),
                        any());
    }

    @Test
    void SRS_NF_2_theRequesterLearnsHowTheirJobEnded() {
        notices.on(
                new JobEnded(
                        132L, "PAYROLL_DRY_RUN", "COMPLETED", 21L, "2026-10", 2063, 2062, 1, null));
        told(
                "JOB_COMPLETED",
                "Job 132 (PAYROLL_DRY_RUN, 2026-10) completed: read 2063, written 2062, skipped"
                        + " 1.");

        notices.on(
                new JobEnded(
                        132L,
                        "PAYROLL_DRY_RUN",
                        "FAILED",
                        21L,
                        "2026-10",
                        0,
                        0,
                        0,
                        " CYCLE_LOCKED: locked "));
        told(
                "JOB_FAILED",
                "Job 132 (PAYROLL_DRY_RUN, 2026-10) failed: CYCLE_LOCKED: locked. It can be"
                        + " restarted on C-13.");
    }

    @Test
    void SRS_NF_5_aStoppedJobOrOneWithoutPeriodOrCauseIsToldPlainly() {
        notices.on(new JobEnded(132L, "TAX_24Q_RETURN", "STOPPED", 21L, null, 0, 0, 0, null));
        told("JOB_STOPPED", "Job 132 (TAX_24Q_RETURN) stopped.");

        notices.on(new JobEnded(132L, "TAX_24Q_RETURN", "FAILED", 21L, null, 0, 0, 0, null));
        told("JOB_FAILED", "Job 132 (TAX_24Q_RETURN) failed. It can be restarted on C-13.");
    }

    // US-GAP-198 (SRS-12-7): the unit's Makers learn of a returned loan request and its reason.
    @Test
    void SRS_12_7_theUnitsMakersLearnOfAReturnedLoanRequestAndItsReason() {
        when(directory.holders("PAYROLL_MAKER", 3L)).thenReturn(List.of(21L, 22L));
        notices.on(
                new in.gov.fci.payroll.loans.api.LoanRequestReturned(
                        3L,
                        44L,
                        "100042",
                        "FESTIVAL_ADVANCE",
                        " Order date does not match ",
                        "ddo"));
        verify(jdbc, times(2))
                .update(
                        contains("INSERT INTO notification"),
                        any(),
                        eq("LOAN_REQUEST_RETURNED"),
                        eq("approval_queue"),
                        eq("44"),
                        eq(
                                "Loan sanction request FESTIVAL_ADVANCE for 100042 returned by ddo:"
                                        + " Order date does not match"),
                        any(),
                        any());
        notices.on(
                new in.gov.fci.payroll.loans.api.LoanRequestReturned(
                        3L, 45L, "100042", "FESTIVAL_ADVANCE", null, "ddo"));
        verify(jdbc, times(2))
                .update(
                        contains("INSERT INTO notification"),
                        any(),
                        eq("LOAN_REQUEST_RETURNED"),
                        eq("approval_queue"),
                        eq("45"),
                        org.mockito.ArgumentMatchers.endsWith("returned by ddo: "),
                        any(),
                        any());
    }

    /** SRS-15-30: a session without a user (no staff account) neither reads nor marks notices. */
    @Test
    void SRS_15_30_aSessionWithoutAUserReadsNoStaffNotices() {
        in.gov.fci.payroll.platform.security.AccessContext anonymous =
                mock(in.gov.fci.payroll.platform.security.AccessContext.class);
        when(anonymous.userId()).thenReturn(null);
        when(anonymous.groups()).thenReturn(List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                notices.mine(
                                        anonymous,
                                        new in.gov.fci.payroll.platform.paging.PageSpec(0, 20)))
                .isInstanceOfSatisfying(
                        in.gov.fci.payroll.platform.error.FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(
                                                in.gov.fci.payroll.platform.error.CommonErrorCode
                                                        .ACCESS_DENIED));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> notices.read(anonymous, 5L))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
        verify(jdbc, never()).update(contains("is_read_flag"), any(), any());
    }
}
