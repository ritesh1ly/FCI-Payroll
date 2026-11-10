package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * SRS-1-26 / SRS-21-11 (deep review DR-10): an approved allowance flag effective before the current
 * month (IST) is settled for the locked months by one arrear event; a flag from this month or later
 * raises none.
 */
class EmployeeFlagEffectTest {

    private static final Instant DECIDED = Instant.parse("2026-10-26T06:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RetroEvents retro = mock(RetroEvents.class);
    private final EmployeeFlagEffect effect =
            new EmployeeFlagEffect(
                    jdbc,
                    mock(AuditService.class),
                    new ObjectMapper(),
                    retro,
                    Clock.fixed(DECIDED, ZoneOffset.UTC));

    private static ApprovalDecided approved(String from) {
        return new ApprovalDecided(
                9L,
                "M-01",
                EmployeeFlagService.ENTITY,
                "100041|CCA|" + from,
                ApprovalStatus.APPROVED,
                null,
                "{\"employeeId\":7,\"flagCode\":\"CCA\",\"value\":\"Y\",\"effectiveFrom\":\""
                        + from
                        + "\",\"reason\":\"Order 4\"}",
                "checker",
                DECIDED);
    }

    @Test
    void SRS_1_26_aBackDatedFlagRaisesOneArrearEventForTheLockedMonths() {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(9L))).thenReturn("hr@x");
        effect.on(approved("2026-08-01"));
        ArgumentCaptor<RetroEvents.Raise> raised = ArgumentCaptor.forClass(RetroEvents.Raise.class);
        verify(retro).raise(raised.capture());
        assertThat(raised.getValue().employeeId()).isEqualTo(7L);
        assertThat(raised.getValue().effectiveFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(raised.getValue().sourceModule()).isEqualTo("EMPLOYEE_FLAG");
        assertThat(raised.getValue().sourceKey()).isEqualTo("100041|CCA|2026-08-01");
        assertThat(raised.getValue().raisedBy()).isEqualTo("checker");
    }

    // US-GAP-39: the end date the Maker gave is written with the version; none leaves it open.
    @Test
    void BR_EMPM_004_theVersionIsWrittenWithItsEndDateWhenItHasOne() {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(9L))).thenReturn("hr@x");
        ApprovalDecided ended =
                new ApprovalDecided(
                        9L,
                        "M-01",
                        EmployeeFlagService.ENTITY,
                        "100041|CCA|2026-11-01",
                        ApprovalStatus.APPROVED,
                        null,
                        "{\"employeeId\":7,\"flagCode\":\"CCA\",\"value\":\"Y\","
                                + "\"effectiveFrom\":\"2026-11-01\",\"effectiveTo\":\"2027-03-31\","
                                + "\"reason\":\"Order 4\"}",
                        "checker",
                        DECIDED);
        effect.on(ended);
        effect.on(approved("2026-12-01"));
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .update(
                        org.mockito.ArgumentMatchers.contains("effective_to"),
                        args.capture(),
                        args.capture(),
                        args.capture(),
                        args.capture(),
                        args.capture(),
                        args.capture(),
                        args.capture(),
                        args.capture(),
                        args.capture());
        assertThat(args.getAllValues().get(4)).isEqualTo(java.sql.Date.valueOf("2027-03-31"));
        assertThat(args.getAllValues().get(13)).as("open-ended").isNull();
    }

    @Test
    void SRS_1_26_aFlagFromThisMonthOrLaterRaisesNoArrear() {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(9L))).thenReturn("hr@x");
        effect.on(approved("2026-10-01"));
        effect.on(approved("2026-11-01"));
        verify(retro, never()).raise(any());
    }
}
