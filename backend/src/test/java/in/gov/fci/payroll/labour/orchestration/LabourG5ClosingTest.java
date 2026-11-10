package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.labour.business.AttendanceRules.BatchState;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.api.OrgMasters;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.persistence.GeneratedKeysStub;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Group g5 closing pass (PENDING 2i): the I-01 Attendance Source read-only from the HQ master
 * (SRS-2-36; G5-11), a new Permanent Light Duty order changing only the pay option (SRS-2-49
 * l.4404; G5-26) and the M-02 gang search by membership as of a date (SRS-2-40 l.4396; G5-55).
 */
class LabourG5ClosingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long LABOUR = 11L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final AuditService audit = mock(AuditService.class);
    private final LabourPersons persons = mock(LabourPersons.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final LabourGangs gangs = mock(LabourGangs.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        when(context.loginId()).thenReturn("hr.maker");
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    // ------------------------------------------------------------ G5-11 Attendance Source

    private AttendanceService attendance() {
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
                                LocalDate.of(2025, 7, 1),
                                null));
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(null, BatchState.OPEN));
        return new AttendanceService(
                jdbc,
                named,
                gangs,
                mock(Authorization.class),
                approvals,
                parameters,
                audit,
                clock,
                mock(GangStrengthService.class));
    }

    @Test
    void SRS_2_36_theDepotsAttendanceSourceIsShownOrNotConfigured() {
        AttendanceService service = attendance();
        assertThat(service.grid(context, 5L, YearMonth.of(2026, 10)).attendanceSource())
                .as("no row in the HQ master: not configured")
                .isNull();

        Map<String, Object> row = new HashMap<>();
        row.put("ATTENDANCE_MODE", "BOTH");
        row.put("ANNA_DARPAN_FLAG", "Y");
        row.put("SOURCE_STATUS", "PILOT");
        row.put("FALLBACK_TO_MANUAL", "N");
        row.put("EFFECTIVE_FROM", Date.valueOf(LocalDate.of(2026, 4, 1)));
        Map<String, Object> older = new HashMap<>(row);
        older.put("ATTENDANCE_MODE", "MANUAL");
        when(jdbc.queryForList(
                        contains("FROM attendance_source_master WHERE location_id = ?"),
                        eq(7L),
                        any(),
                        any()))
                .thenReturn(List.of(row, older));
        AttendanceService.AttendanceSource source =
                service.grid(context, 5L, YearMonth.of(2026, 10)).attendanceSource();
        assertThat(source)
                .as("the latest row in force wins")
                .isEqualTo(
                        new AttendanceService.AttendanceSource(
                                "BOTH", true, "PILOT", false, LocalDate.of(2026, 4, 1)));

        row.put("ANNA_DARPAN_FLAG", "N");
        row.put("FALLBACK_TO_MANUAL", "Y");
        row.put("SOURCE_STATUS", "SUSPENDED");
        source = service.grid(context, 5L, YearMonth.of(2026, 10)).attendanceSource();
        assertThat(source.annaDarpan()).isFalse();
        assertThat(source.fallbackToManual()).isTrue();
        assertThat(source.status()).isEqualTo("SUSPENDED");
    }

    @Test
    void SRS_2_36_theSourceIsReadForTheGridsDayAMonthAheadReadsItsFirstDay() {
        AttendanceService service = attendance();
        ArgumentCaptor<Object> day = ArgumentCaptor.forClass(Object.class);
        service.grid(context, 5L, YearMonth.of(2026, 12));
        verify(jdbc)
                .queryForList(
                        contains("FROM attendance_source_master"), eq(7L), day.capture(), any());
        assertThat(day.getValue()).isEqualTo(Date.valueOf(LocalDate.of(2026, 12, 1)));
    }

    // ------------------------------------------------------------ G5-26 Light Duty pay option

    private LabourLifecycleService lifecycle() {
        Map<String, Object> labour = new HashMap<>();
        labour.put("LABOUR_NUMBER", "DPT-000011");
        labour.put("PAYROLL_TYPE", "DEPT_LABOUR");
        labour.put("DEPOT_LOCATION_ID", 7L);
        labour.put("LABOUR_STATUS", "ACTIVE");
        labour.put("DESIGNATION_ID", 5L);
        when(jdbc.queryForList(contains("AND p.labour_status = 'ACTIVE'"), any(Object[].class)))
                .thenReturn(List.of(labour));
        // on Light Duty: no handling membership any more
        when(jdbc.queryForList(contains("m.gang_role = 'HANDLING'"), any(Object[].class)))
                .thenReturn(List.of());
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(3L);
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(office));
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(item.id()).thenReturn(91L);
        when(approvals.submit(any(), any(Submission.class), any())).thenReturn(item);
        return new LabourLifecycleService(
                jdbc,
                mock(Authorization.class),
                org,
                approvals,
                persons,
                parameters,
                audit,
                JsonMapper.builder().build(),
                clock);
    }

    private void inForce(String option, LocalDate from) {
        Map<String, Object> current = new HashMap<>();
        current.put("LIGHT_DUTY_ID", 61L);
        current.put("FROM_DESIGNATION_ID", 3L);
        current.put("TO_DESIGNATION_ID", 5L);
        current.put("FROM_GANG_ID", 2L);
        current.put("ANCILLARY_POOL_ID", 8L);
        current.put("EFFECTIVE_DATE", Date.valueOf(from));
        current.put("PAY_OPTION", option);
        when(jdbc.queryForList(contains("light_duty_status = 'APPROVED' ORDER BY"), eq(LABOUR)))
                .thenReturn(List.of(current));
    }

    private static LabourLifecycleService.LightDuty order(
            Long designation, LocalDate from, String option, BigDecimal basic) {
        return new LabourLifecycleService.LightDuty(
                designation, from, option, basic, "PO/77", TODAY, "Area Manager", "Medical board");
    }

    private void pending(Integer n) {
        when(jdbc.queryForObject(
                        contains("light_duty_status = 'PENDING_APPROVAL'"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(n);
    }

    @Test
    void SRS_2_49_aNewApprovedOrderChangesThePayOptionAndKeepsTheOld() {
        LabourLifecycleService service = lifecycle();
        inForce("ANCILLARY_PAY", TODAY.minusMonths(2));
        pending(0);
        GeneratedKeysStub.answer(jdbc, "INSERT INTO labour_light_duty", 62L);

        service.lightDuty(
                context, LABOUR, order(5L, TODAY, "PROTECTED_PAY", new BigDecimal("21500")));

        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), eq(null));
        assertThat(sent.getValue().workflowAction()).isEqualTo("M02_LIGHT_DUTY");
        assertThat(sent.getValue().entityKey()).isEqualTo("62");
        verify(jdbc).update(contains("UPDATE labour_light_duty SET approval_id"), eq(91L), eq(62L));
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("hr.maker"), entry.capture());
        assertThat(entry.getValue().fieldName()).isEqualTo("pay_option");
        assertThat(entry.getValue().oldValue()).isEqualTo("ANCILLARY_PAY");
        assertThat(entry.getValue().newValue()).isEqualTo("PROTECTED_PAY");
        // no designation pick (null) is the designation the order gave
        service.lightDuty(context, LABOUR, order(null, TODAY, "PROTECTED_PAY", BigDecimal.ONE));
        // nothing moves before approval
        verify(persons, never()).move(anyLong(), any(), any(), any(), any());
    }

    @Test
    void SRS_2_49_theSameOptionOrAPendingOrderIsRefused() {
        LabourLifecycleService service = lifecycle();
        inForce("PROTECTED_PAY", TODAY.minusMonths(2));
        pending(0);
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                order(5L, TODAY, "PROTECTED_PAY", new BigDecimal("21500"))),
                "SAME_PAY_OPTION");
        pending(1);
        defect(
                () -> service.lightDuty(context, LABOUR, order(5L, TODAY, "ANCILLARY_PAY", null)),
                "PENDING");
        // the order's own checks still apply: Protected pay needs its basic
        defect(
                () -> service.lightDuty(context, LABOUR, order(5L, TODAY, "PROTECTED_PAY", null)),
                "PROTECTED_BASIC");
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_2_49_aPayOptionChangeKeepsTheDesignationAndCannotPrecedeTheOrderInForce() {
        LabourLifecycleService service = lifecycle();
        pending(0);
        // no approved order and no handling gang: not a Light Duty labour
        when(jdbc.queryForList(contains("light_duty_status = 'APPROVED' ORDER BY"), eq(LABOUR)))
                .thenReturn(List.of());
        defect(
                () ->
                        service.lightDuty(
                                context, LABOUR, order(5L, TODAY, "PROTECTED_PAY", BigDecimal.TEN)),
                "NOT_HANDLING");
        inForce("ANCILLARY_PAY", TODAY.plusDays(5));
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                order(6L, TODAY.plusDays(5), "PROTECTED_PAY", BigDecimal.TEN)),
                "DESIGNATION");
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                order(5L, TODAY.plusDays(4), "PROTECTED_PAY", BigDecimal.TEN)),
                "DATE");
        // the boundary: on the order's own date
        GeneratedKeysStub.answer(jdbc, "INSERT INTO labour_light_duty", 63L);
        service.lightDuty(
                context, LABOUR, order(5L, TODAY.plusDays(5), "PROTECTED_PAY", BigDecimal.TEN));
        // the labour's designation changed since (another order): refused
        Map<String, Object> labour = new HashMap<>();
        labour.put("LABOUR_NUMBER", "DPT-000011");
        labour.put("PAYROLL_TYPE", "DEPT_LABOUR");
        labour.put("DEPOT_LOCATION_ID", 7L);
        labour.put("LABOUR_STATUS", "ACTIVE");
        labour.put("DESIGNATION_ID", 9L);
        when(jdbc.queryForList(contains("AND p.labour_status = 'ACTIVE'"), any(Object[].class)))
                .thenReturn(List.of(labour));
        defect(
                () ->
                        service.lightDuty(
                                context,
                                LABOUR,
                                order(null, TODAY.plusDays(5), "PROTECTED_PAY", BigDecimal.TEN)),
                "DESIGNATION");
    }

    @Test
    void SRS_2_49_anApprovedPayOptionChangeSupersedesTheOldOrderAndMovesNothing() {
        LabourLifecycleService service = lifecycle();
        Map<String, Object> order = new HashMap<>();
        order.put("EMPLOYEE_ID", LABOUR);
        order.put("TO_DESIGNATION_ID", 5L);
        order.put("FROM_GANG_ID", 2L);
        order.put("EFFECTIVE_DATE", Date.valueOf(TODAY));
        order.put("SUPERSEDES_LIGHT_DUTY_ID", 61L);
        order.put("POOL_GANG_ID", 9L);
        when(jdbc.queryForMap(contains("FROM labour_light_duty l JOIN"), any(Object[].class)))
                .thenReturn(order);
        service.on(decided(ApprovalStatus.REJECTED));
        verify(jdbc, never()).update(contains("'SUPERSEDED'"), any(Object[].class));
        service.on(decided(ApprovalStatus.APPROVED));
        verify(jdbc).update(contains("SET light_duty_status = 'SUPERSEDED'"), eq(61L));
        verify(jdbc, never())
                .update(contains("INSERT INTO labour_gang_membership"), any(Object[].class));
        verify(persons, never()).move(anyLong(), any(), any(), any(), any());
    }

    private static ApprovalDecided decided(ApprovalStatus outcome) {
        return new ApprovalDecided(
                71L,
                "M-02",
                LabourLifecycleService.LIGHT_DUTY,
                "62",
                outcome,
                "Board",
                null,
                "checker",
                Instant.now(),
                null);
    }

    // ------------------------------------------------------------ G5-55 gang search as of a date

    private LabourMasterService master() {
        when(context.locationId()).thenReturn(3L);
        when(context.groups()).thenReturn(List.of());
        when(context.dataScope()).thenReturn(Set.of(3L));
        when(named.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        return new LabourMasterService(
                jdbc,
                named,
                mock(Authorization.class),
                org,
                mock(OrgMasters.class),
                mock(LabourDepots.class),
                approvals,
                persons,
                parameters,
                audit,
                JsonMapper.builder().build());
    }

    private Object gangDate(LabourMasterService service, LabourMasterService.Search search) {
        clearInvocations(named);
        service.page(context, search, new PageSpec(0, 20));
        ArgumentCaptor<MapSqlParameterSource> p =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).queryForObject(contains(":gangDate"), p.capture(), eq(Long.class));
        return p.getValue().getValue("gangDate");
    }

    @Test
    void SRS_2_40_aGangSearchFindsTheMembersOfTheChosenDate() {
        LabourMasterService service = master();
        LocalDate past = TODAY.minusMonths(3);
        assertThat(
                        gangDate(
                                service,
                                new LabourMasterService.Search(null, null, "G001", null, past)))
                .isEqualTo(Date.valueOf(past));
        assertThat(
                        gangDate(
                                service,
                                new LabourMasterService.Search(null, null, "G001", null, null)))
                .as("no date: today's India business date")
                .isEqualTo(Date.valueOf(TODAY));
        assertThat(gangDate(service, new LabourMasterService.Search(null, null, "G001", null)))
                .as("the search without a date (four criteria) is today's")
                .isEqualTo(Date.valueOf(TODAY));
        LocalDate later = TODAY.plusDays(1);
        assertThat(gangDate(service, new LabourMasterService.Search(null, null, "G001", 7L, later)))
                .isEqualTo(Date.valueOf(later));
    }
}
