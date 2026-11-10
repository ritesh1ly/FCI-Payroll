package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.AttendanceRules.BatchState;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * I-01 attendance month (SRS-2-29, SRS-2-38), the sides the other tests leave: a membership running
 * past the gang's end is owed only up to that end, and an approved month whose counts are already
 * stored updates them rather than adding a second row.
 */
class AttendanceServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LabourGangs gangs = mock(LabourGangs.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final AttendanceService service =
            new AttendanceService(
                    jdbc,
                    mock(NamedParameterJdbcTemplate.class),
                    gangs,
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    parameters,
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                    mock(GangStrengthService.class));

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(null, BatchState.OPEN));
    }

    @Test
    void SRS_2_29_aMembershipRunningPastTheGangsEndIsOwedOnlyUpToThatEnd() {
        when(gangs.workGang(5L))
                .thenReturn(
                        new LabourGangs.Gang(
                                5L,
                                "DP-DL01-G002",
                                "Okhla Gang 1",
                                7L,
                                "DP-DL01",
                                "DEPT_LABOUR",
                                "ACTIVE",
                                LocalDate.of(2026, 9, 5),
                                LocalDate.of(2026, 9, 20)));
        Map<String, Object> span = new HashMap<>();
        span.put("EMPLOYEE_ID", 11L);
        span.put("EFFECTIVE_FROM", Date.valueOf(LocalDate.of(2026, 9, 1)));
        span.put("EFFECTIVE_TO", Date.valueOf(LocalDate.of(2026, 9, 30)));
        span.put("RETIREMENT_DATE", Date.valueOf(LocalDate.of(2027, 3, 31)));
        when(jdbc.queryForList(
                        contains("labour_gang_membership m JOIN employee"),
                        eq(5L),
                        any(Date.class),
                        any(Date.class)))
                .thenReturn(List.of(span));
        // 5th - 20th: the gang's days, none marked; the later membership end and retirement are
        // beyond them.
        assertThatThrownBy(() -> service.submit(context, 5L, YearMonth.of(2026, 9)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(String.valueOf(e.getMessageArgs()[0]))
                                        .isEqualTo("UNMARKED_DAYS 16"));
    }

    @Test
    void SRS_2_38_anApprovedMonthAlreadyCountedIsUpdatedNotAddedAgain() {
        when(jdbc.queryForList(
                        contains("SELECT DISTINCT employee_id FROM labour_gang_membership"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(11L));
        when(jdbc.update(contains("UPDATE labour_attendance_month"), any(Object[].class)))
                .thenReturn(1);

        service.on(
                new ApprovalDecided(
                        41L,
                        AttendanceService.SCREEN,
                        AttendanceService.ENTITY,
                        "5:202609",
                        ApprovalStatus.APPROVED,
                        null,
                        "{}",
                        "checker.do",
                        Instant.now(),
                        null));

        verify(jdbc).update(contains("UPDATE labour_attendance_month"), any(Object[].class));
        verify(jdbc, never())
                .update(contains("INSERT INTO labour_attendance_month"), any(Object[].class));
    }
}
