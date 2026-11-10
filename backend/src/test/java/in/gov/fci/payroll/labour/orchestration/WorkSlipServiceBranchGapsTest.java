package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.labour.business.AttendanceRules.BatchState;
import in.gov.fci.payroll.labour.orchestration.WorkSlipService.Entry;
import in.gov.fci.payroll.labour.orchestration.WorkSlipService.RowEntry;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

/**
 * I-02 work-slip paths the other unit tests leave one way (SRS-2-4, SRS-2-29, SRS-3-9, SRS-5-4,
 * SRS-5-8): a returned month shows the Checker's reason, an entry without participants, an NWNP
 * member whose membership ends after the date stays on the slip, two rows of one slip that do not
 * repeat each other, blank free text stored as no value, and a participant's name without a last
 * name.
 */
class WorkSlipServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);
    private static final long TIME_OP = 4L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LabourGangs gangs = mock(LabourGangs.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final WorkSlipService service =
            new WorkSlipService(
                    jdbc,
                    gangs,
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    parameters,
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                    mock(ExceptionWorklist.class));

    private static LabourGangs.Gang gang(String payrollType) {
        return new LabourGangs.Gang(
                5L,
                "DP-DL01-G002",
                "Okhla Gang 1",
                7L,
                "DP-DL01 - Okhla",
                payrollType,
                "ACTIVE",
                LocalDate.of(2025, 7, 1),
                null);
    }

    @BeforeEach
    void wire() throws Exception {
        when(parameters.today()).thenReturn(TODAY);
        when(context.loginId()).thenReturn("ldh.depot@fci-demo.test");
        when(gangs.workGang(5L)).thenReturn(gang("NWNP_LABOUR"));
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(null, BatchState.OPEN));
        // One operation of the payroll type, by time (no bags, not present-but-no-work).
        doAnswer(
                        inv -> {
                            RowCallbackHandler h = inv.getArgument(1);
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getLong("operation_id")).thenReturn(TIME_OP);
                            when(rs.getString("operation_code")).thenReturn("SWEEP");
                            when(rs.getString("operation_name")).thenReturn("Sweeping");
                            when(rs.getString("is_bag_based")).thenReturn("N");
                            when(rs.getString("is_present_but_no_work")).thenReturn("N");
                            h.processRow(rs);
                            return null;
                        })
                .when(jdbc)
                .query(
                        contains("FROM operation_master"),
                        any(RowCallbackHandler.class),
                        any(Object[].class));
    }

    private static RowEntry row(String start, String end, String pbnwText, String rake) {
        return new RowEntry(TIME_OP, null, null, null, start, end, null, pbnwText, rake);
    }

    private static Map<String, Object> member(long id, LocalDate to) {
        Map<String, Object> m = new HashMap<>();
        m.put("EMPLOYEE_ID", id);
        m.put("GANG_ROLE", "MEMBER");
        m.put("EFFECTIVE_TO", to == null ? null : Date.valueOf(to));
        m.put("ATTENDANCE_CODE", null);
        m.put("PRESENT_DAY_WEIGHT", null);
        return m;
    }

    private static ResultSet participant(long id, String last) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("employee_id")).thenReturn(id);
        when(rs.getString("labour_number")).thenReturn("DL" + id);
        when(rs.getString("first_name")).thenReturn("Ram");
        when(rs.getString("last_name")).thenReturn(last);
        when(rs.getString("gang_role")).thenReturn("MEMBER");
        when(rs.getString("present_flag")).thenReturn("Y");
        return rs;
    }

    @Test
    void SRS_2_29_aReturnedMonthShowsTheCheckersReason() {
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(41L, BatchState.RETURNED, "Bags short"));
        var month = service.month(context, 5L, YearMonth.of(2026, 9));
        assertThat(month.batchState()).isEqualTo("RETURNED");
        assertThat(month.returnReason()).isEqualTo("Bags short");
        assertThat(month.approvalId()).isEqualTo(41L);
    }

    @Test
    void SRS_2_4_anEntryWithoutParticipantsIsRefused() {
        assertThatThrownBy(
                        () ->
                                service.save(
                                        context,
                                        5L,
                                        new Entry(
                                                DAY,
                                                false,
                                                List.of(row("09:00", "11:00", null, null)),
                                                List.of())))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(String.valueOf(e.getMessageArgs()[0]))
                                        .isEqualTo("PARTICIPANTS"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_5_4_SRS_5_8_anNwnpMemberEndingAfterTheDateStaysAndTwoDistinctRowsAreSaved()
            throws Exception {
        List<Map<String, Object>> members = new ArrayList<>();
        members.add(member(13L, DAY.plusDays(5)));
        members.add(member(14L, null));
        members.add(member(15L, DAY.plusDays(1)));
        when(jdbc.queryForList(contains("FROM labour_gang_membership m"), any(Object[].class)))
                .thenReturn(members);
        when(jdbc.queryForObject(
                        contains("WHERE work_slip_number = ?"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(70L);
        Map<String, Object> saved = new HashMap<>();
        saved.put("WORK_SLIP_NUMBER", "WS-5-20261002-1");
        saved.put("WORK_SLIP_DATE", Date.valueOf(DAY));
        saved.put("SLIP_STATUS", "DRAFT");
        saved.put("CONTINUE_WORK_FLAG", "N");
        saved.put("HOLIDAY_FLAG", "N");
        saved.put("LABOUR_STRENGTH", 3);
        saved.put("AMENDMENT_OF_SLIP_ID", null);
        when(jdbc.queryForMap(contains("WHERE s.work_slip_id = ?"), eq(70L))).thenReturn(saved);
        // SRS-2-4: a participant without a last name (null or blank) shows the first name only.
        ResultSet noLast = participant(13L, null);
        ResultSet blankLast = participant(14L, "  ");
        ResultSet withLast = participant(15L, "Kumar");
        doAnswer(
                        inv -> {
                            RowMapper<Object> m = inv.getArgument(1);
                            return List.of(
                                    m.mapRow(noLast, 0),
                                    m.mapRow(blankLast, 1),
                                    m.mapRow(withLast, 2));
                        })
                .when(jdbc)
                .query(
                        contains("FROM work_slip_participant p"),
                        any(RowMapper.class),
                        any(Object[].class));

        var slip =
                service.save(
                        context,
                        5L,
                        new Entry(
                                DAY,
                                false,
                                List.of(
                                        row("09:00", "10:00", "  ", " RK-0921 "),
                                        row("10:00", "11:00", null, null)),
                                List.of(13L, 14L, 15L)));

        assertThat(slip.participants())
                .extracting(WorkSlipService.Participant::name)
                .containsExactly("Ram", "Ram", "Ram Kumar");
        // Every member stays: each membership ends after the date (or never).
        verify(jdbc)
                .update(
                        contains("INSERT INTO work_slip_participant"),
                        eq(70L),
                        any(),
                        eq(13L),
                        eq("MEMBER"),
                        eq("Y"));
        verify(jdbc)
                .update(
                        contains("INSERT INTO work_slip_participant"),
                        eq(70L),
                        any(),
                        eq(15L),
                        eq("MEMBER"),
                        eq("Y"));
        // Blank free text is no value; the rake reference is stored stripped.
        verify(jdbc)
                .update(
                        contains("INSERT INTO work_slip_row"),
                        eq(70L),
                        any(),
                        eq(1),
                        eq(TIME_OP),
                        isNull(),
                        isNull(),
                        eq("09:00"),
                        eq("10:00"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq("RK-0921"));
        verify(jdbc)
                .update(
                        contains("INSERT INTO work_slip_row"),
                        eq(70L),
                        any(),
                        eq(2),
                        eq(TIME_OP),
                        isNull(),
                        isNull(),
                        eq("10:00"),
                        eq("11:00"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
    }
}
