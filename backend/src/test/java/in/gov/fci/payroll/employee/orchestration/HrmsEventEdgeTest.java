package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.business.model.LifecycleEvent;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The refusals of the HRMS life-cycle events and of the M-01 flag effect (plan 16 phases 16f-1,
 * 16d-2; SRS-1-63, SRS-1-64, SRS-1-11): an event dated after the decision or without a date, a
 * subsistence outside 0-100 or not a number; a flag decision that is not approved, of another
 * entity, or with an unreadable payload.
 */
class HrmsEventEdgeTest {

    private static final Instant DECIDED = Instant.parse("2026-10-26T06:00:00Z");

    private final EventRepositories.LifecycleEvents events =
            mock(EventRepositories.LifecycleEvents.class);
    private final EmployeeEventEffects effects = mock(EmployeeEventEffects.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HrmsEventApplier applier =
            new HrmsEventApplier(events, effects, jdbc, mock(AuditService.class));

    private static ApprovalDecided decision(String entity, ApprovalStatus outcome, String payload) {
        return new ApprovalDecided(
                9L, "I-21", entity, "7", outcome, null, payload, "checker", DECIDED);
    }

    private static Map<String, Object> task(Object date, String value) {
        Map<String, Object> t = new HashMap<>();
        t.put("EFFECTIVE_DATE", date);
        t.put("NEW_VALUE", value);
        t.put("EMPLOYEE_ID", 7L);
        t.put("HRMS_CHANGE_TASK_ID", 11L);
        t.put("TASK_NO", "HCA-2026-0011");
        return t;
    }

    @Test
    void SRS_1_63_anEventAfterTodayOrWithoutADateIsNotApproved() {
        ApprovalDecided d = decision("hrms_change_task", ApprovalStatus.APPROVED, "{}");
        assertThatThrownBy(
                        () ->
                                applier.apply(
                                        task(
                                                java.sql.Date.valueOf(LocalDate.of(2026, 10, 27)),
                                                "50"),
                                        "SUSPENSION",
                                        d))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> applier.apply(task(null, null), "ABSCONDING", d))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(events);
    }

    /**
     * I-17 Effective Date (SRS l.3877, US-GAP-277): an HRMS event dated before the member's joining
     * date is refused at approval (EMP-422-BEFORE-JOINING) and nothing is recorded; on the joining
     * date itself, or for a member with no joining date on record, it is applied.
     */
    @Test
    void I_17_anEventBeforeTheJoiningDateIsRefusedAtApproval() {
        ApprovalDecided d = decision("hrms_change_task", ApprovalStatus.APPROVED, "{}");
        java.sql.Date joined = java.sql.Date.valueOf(LocalDate.of(2026, 10, 20));
        when(jdbc.queryForList(
                        org.mockito.ArgumentMatchers.contains("SELECT comp_joining_date"),
                        org.mockito.ArgumentMatchers.eq(java.sql.Date.class),
                        org.mockito.ArgumentMatchers.eq(7L)))
                .thenReturn(java.util.List.of(joined));
        assertThatThrownBy(
                        () ->
                                applier.apply(
                                        task(
                                                java.sql.Date.valueOf(LocalDate.of(2026, 10, 19)),
                                                null),
                                        "ABSCONDING",
                                        d))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                    .isEqualTo(
                                            in.gov.fci.payroll.platform.error.ApiErrorCode
                                                    .EMPLOYEE_EVENT_BEFORE_JOINING);
                            org.assertj.core.api.Assertions.assertThat(e.getMessageArgs())
                                    .containsExactly("2026-10-19", "2026-10-20");
                        });
        verifyNoInteractions(events);
        when(events.save(any())).thenAnswer(i -> i.getArgument(0));
        applier.apply(task(joined, null), "REVOCATION", d);
        when(jdbc.queryForList(
                        org.mockito.ArgumentMatchers.contains("SELECT comp_joining_date"),
                        org.mockito.ArgumentMatchers.eq(java.sql.Date.class),
                        org.mockito.ArgumentMatchers.eq(7L)))
                .thenReturn(java.util.Arrays.asList((java.sql.Date) null));
        applier.apply(task(java.sql.Date.valueOf(LocalDate.of(2026, 1, 1)), null), "REVOCATION", d);
        verify(effects, org.mockito.Mockito.times(2))
                .applyApproved(any(LifecycleEvent.class), any(), any(), any(), any());
    }

    @Test
    void SRS_1_64_theSubsistenceIsAPercentageFromZeroToHundred() {
        ApprovalDecided d = decision("hrms_change_task", ApprovalStatus.APPROVED, "{}");
        Object today = java.sql.Date.valueOf(LocalDate.of(2026, 10, 26));
        for (String bad : new String[] {"-1", "100.5", "fifty", null}) {
            assertThatThrownBy(() -> applier.apply(task(today, bad), "SUSPENSION", d))
                    .isInstanceOf(FciException.class);
        }
        when(events.save(any())).thenAnswer(i -> i.getArgument(0));
        applier.apply(task(today, "100"), "SUSPENSION", d);
        verify(effects).applyApproved(any(LifecycleEvent.class), any(), any(), any(), any());
    }

    @Test
    void SRS_1_11_aFlagDecisionWritesOnlyWhenApprovedAndReadable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmployeeFlagEffect effect =
                new EmployeeFlagEffect(
                        jdbc,
                        mock(AuditService.class),
                        new ObjectMapper(),
                        mock(in.gov.fci.payroll.arrear.api.RetroEvents.class),
                        java.time.Clock.systemUTC());
        effect.on(decision("employee", ApprovalStatus.APPROVED, "{}"));
        effect.on(decision(EmployeeFlagService.ENTITY, ApprovalStatus.REJECTED, "{}"));
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decision(
                                                EmployeeFlagService.ENTITY,
                                                ApprovalStatus.APPROVED,
                                                "not json")))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * SRS-1-63: only an HRMS life-cycle event type is applied as an event; a field that is none
     * (here NAME) is refused, so no employment status is derived from it.
     */
    @Test
    void SRS_1_63_aFieldThatIsNoEventTypeIsNotAppliedAsAnEvent() {
        ApprovalDecided d = decision("hrms_change_task", ApprovalStatus.APPROVED, "{}");
        when(events.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThatThrownBy(
                        () ->
                                applier.apply(
                                        task(
                                                java.sql.Date.valueOf(LocalDate.of(2026, 10, 1)),
                                                null),
                                        "NAME",
                                        d))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAME");
        verifyNoInteractions(effects);
    }
}
