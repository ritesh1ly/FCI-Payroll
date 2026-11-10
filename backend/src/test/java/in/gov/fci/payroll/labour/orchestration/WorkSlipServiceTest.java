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
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.labour.business.AttendanceRules.BatchState;
import in.gov.fci.payroll.labour.orchestration.WorkSlipService.Entry;
import in.gov.fci.payroll.labour.orchestration.WorkSlipService.RowEntry;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * I-02 work-slip refusals and quiet paths that the API's validation never lets through (plan 20
 * phase 20c, plan 21 / 22; SRS-2-4, SRS-2-15, SRS-2-29, SRS-2-34, SRS-5-14): an entry without its
 * parts, a gang not working that day or of no labour payroll, a closed batch, too few or too many
 * rows, an unknown operation, a missing or over-long reason, a decision of another screen.
 */
class WorkSlipServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LabourGangs gangs = mock(LabourGangs.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final WorkSlipService service =
            new WorkSlipService(
                    jdbc,
                    gangs,
                    mock(Authorization.class),
                    approvals,
                    parameters,
                    audit,
                    Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                    mock(ExceptionWorklist.class));

    private static LabourGangs.Gang gang(String payrollType, String status, LocalDate to) {
        return new LabourGangs.Gang(
                5L,
                "DP-DL01-G002",
                "Okhla Gang 1",
                7L,
                "DP-DL01 - Okhla",
                payrollType,
                status,
                LocalDate.of(2025, 7, 1),
                to);
    }

    private static RowEntry row(Long operation) {
        return new RowEntry(operation, null, null, null, "09:00", "11:00", null, null, null);
    }

    private static Entry entry(List<RowEntry> rows, List<Long> participants) {
        return new Entry(DAY, false, rows, participants);
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        when(context.loginId()).thenReturn("ldh.depot@fci-demo.test");
        when(gangs.workGang(5L)).thenReturn(gang("DEPT_LABOUR", "ACTIVE", null));
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(null, BatchState.OPEN));
    }

    private void saved(String status, Object date) {
        Map<String, Object> row = new HashMap<>();
        row.put("WORK_SLIP_ID", 70L);
        row.put("WORK_SLIP_NUMBER", "WS-5-20261002-1");
        row.put("GANG_ID", 5L);
        row.put("WORK_SLIP_DATE", date);
        row.put("SLIP_STATUS", status);
        when(jdbc.queryForList(contains("FROM work_slip WHERE work_slip_id"), eq(70L)))
                .thenReturn(List.of(row));
        Map<String, Object> slip = new HashMap<>(row);
        slip.put("CONTINUE_WORK_FLAG", "N");
        slip.put("HOLIDAY_FLAG", "N");
        slip.put("LABOUR_STRENGTH", null);
        slip.put("AMENDMENT_OF_SLIP_ID", null);
        when(jdbc.queryForMap(contains("WHERE s.work_slip_id = ?"), eq(70L))).thenReturn(slip);
    }

    @Test
    void SRS_2_4_anEntryNeedsItsDateRowsAndParticipants() {
        defect(() -> service.save(context, 5L, null), "ENTRY");
        defect(
                () -> service.save(context, 5L, new Entry(null, false, List.of(), List.of())),
                "ENTRY");
        defect(() -> service.save(context, 5L, entry(null, List.of(1L))), "ENTRY");
        defect(() -> service.save(context, 5L, entry(List.of(row(1L)), null)), "ENTRY");
        defect(
                () ->
                        service.save(
                                context,
                                5L,
                                new Entry(TODAY.plusDays(1), false, List.of(row(1L)), List.of(1L))),
                "FUTURE");
    }

    @Test
    void SRS_2_4_theGangWorksThatDayAndIsOfALabourPayroll() {
        when(gangs.workGang(5L)).thenReturn(gang("DEPT_LABOUR", "INACTIVE", null));
        defect(
                () -> service.save(context, 5L, entry(List.of(row(1L)), List.of(1L))),
                "GANG_NOT_ACTIVE");
        when(gangs.workGang(5L)).thenReturn(gang("DEPT_LABOUR", "ACTIVE", DAY.minusDays(1)));
        defect(
                () -> service.save(context, 5L, entry(List.of(row(1L)), List.of(1L))),
                "GANG_NOT_ACTIVE");
        when(gangs.workGang(5L)).thenReturn(gang("CASUAL", "ACTIVE", null));
        defect(
                () -> service.save(context, 5L, entry(List.of(row(1L)), List.of(1L))),
                "PAYROLL_TYPE");
    }

    @Test
    void SRS_2_29_aSubmittedBatchTakesNoSlipAndRowsAreOneToTwenty() {
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(41L, BatchState.SUBMITTED));
        assertThatThrownBy(() -> service.save(context, 5L, entry(List.of(row(1L)), List.of(1L))))
                .isInstanceOf(FciException.class)
                .hasMessage("LBR-409-STATE");
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(null, BatchState.RETURNED));
        defect(() -> service.save(context, 5L, entry(List.of(), List.of(1L))), "ROWS");
        List<RowEntry> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add(row(1L));
        }
        defect(() -> service.save(context, 5L, entry(many, List.of(1L))), "ROWS");
        defect(
                () -> service.save(context, 5L, entry(List.of(row(null)), List.of(1L))),
                "OPERATION null");
        defect(
                () -> service.save(context, 5L, entry(List.of(row(9L)), List.of(1L))),
                "OPERATION 9");
    }

    @Test
    void SRS_2_15_anAmendmentKeepsTheDateAndNeedsAReason() {
        saved("DRAFT", Date.valueOf(DAY));
        defect(
                () -> service.amend(context, 70L, entry(List.of(row(1L)), List.of(1L)), null),
                "REASON");
        defect(
                () -> service.amend(context, 70L, entry(List.of(row(1L)), List.of(1L)), " "),
                "REASON");
        defect(
                () ->
                        service.amend(
                                context,
                                70L,
                                entry(List.of(row(1L)), List.of(1L)),
                                "r".repeat(1001)),
                "REASON");
        defect(() -> service.amend(context, 70L, null, "Wrong bags"), "DATE_CHANGED");
        defect(
                () ->
                        service.amend(
                                context,
                                70L,
                                new Entry(DAY.plusDays(1), false, List.of(row(1L)), List.of(1L)),
                                "Wrong bags"),
                "DATE_CHANGED");
        saved("APPROVED", Date.valueOf(DAY));
        assertThatThrownBy(() -> service.cancel(context, 70L, "Entered twice"))
                .isInstanceOf(FciException.class)
                .hasMessage("LBR-409-STATE");
    }

    @Test
    void SRS_2_15_aCancelledSlipLeavesTheDayLockedWhileAnotherSlipRemains() {
        // Oracle answers a DATE as a timestamp: the slip date reads the same either way.
        saved("DRAFT", Timestamp.valueOf(DAY.atStartOfDay()));
        when(jdbc.queryForObject(contains("slip_status NOT IN"), eq(Integer.class), eq(5L), any()))
                .thenReturn(1);
        var slip = service.cancel(context, 70L, "Entered twice");
        assertThat(slip.date()).isEqualTo(DAY);
        assertThat(slip.labourStrength()).isNull();
        verify(jdbc, never()).update(contains("work_slip_filed_flag = 'N'"), anyLong(), any());
        when(jdbc.queryForObject(contains("slip_status NOT IN"), eq(Integer.class), eq(5L), any()))
                .thenReturn(null);
        service.cancel(context, 70L, "Entered twice");
        verify(jdbc).update(contains("work_slip_filed_flag = 'N'"), eq(5L), any(Date.class));
    }

    @Test
    void SRS_2_29_aMonthIsSubmittedOnlyWhenOverOpenAndWithSlips() {
        defect(() -> service.submit(context, 5L, null), "MONTH_NOT_OVER");
        defect(() -> service.submit(context, 5L, YearMonth.from(TODAY)), "MONTH_NOT_OVER");
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(41L, BatchState.APPROVED));
        assertThatThrownBy(() -> service.submit(context, 5L, YearMonth.of(2026, 9)))
                .isInstanceOf(FciException.class)
                .hasMessage("LBR-409-STATE");
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(null, BatchState.OPEN));
        defect(() -> service.submit(context, 5L, YearMonth.of(2026, 9)), "NO_SLIPS");
    }

    @Test
    void SRS_2_34_onlyADpsSlipGoesToTheDepotManager() {
        saved("DRAFT", Date.valueOf(DAY));
        defect(() -> service.submitSlip(context, 70L), "PAYROLL_TYPE");
    }

    @Test
    void SRS_2_34_aDecisionOfAnotherScreenOrOnNoSubmittedSlipChangesNothing() {
        service.on(decision("rate_set", "M-05", ApprovalStatus.APPROVED));
        service.on(decision(WorkSlipService.BOOKING_ENTITY, "I-01", ApprovalStatus.APPROVED));
        when(jdbc.update(contains("slip_status = 'SUBMITTED'"), anyString(), anyLong()))
                .thenReturn(0);
        service.on(decision(WorkSlipService.BOOKING_ENTITY, "I-02", ApprovalStatus.REJECTED));
        verify(audit, never()).record(anyString(), any());
    }

    @Test
    void SRS_2_4_aFutureMonthReadsFromItsFirstDay() {
        var month = service.month(context, 5L, YearMonth.from(TODAY).plusMonths(1));
        assertThat(month.slips()).isEmpty();
        assertThat(month.operations()).isEmpty();
        assertThat(month.batchState()).isEqualTo("OPEN");
    }

    private static ApprovalDecided decision(String entity, String module, ApprovalStatus outcome) {
        return new ApprovalDecided(
                41L,
                module,
                entity,
                "5|2026-09",
                outcome,
                null,
                "{}",
                "checker",
                Instant.now(),
                null);
    }
}
