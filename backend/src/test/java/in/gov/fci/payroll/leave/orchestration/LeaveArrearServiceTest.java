package in.gov.fci.payroll.leave.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.leave.business.LeaveErrorCode;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import in.gov.fci.payroll.payroll.api.StaffEarnings;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * I-24 Leave Encashment Arrear (plan 13 phase 13d; SRS-8-23, SRS-8-24; BR-ARR-LE-003; TX-1): the
 * run computes every member first and writes the event and all the records in one transaction; a
 * member separated before the revision is recorded not eligible; a missing divisor, pay elements or
 * revised pay skips the member and writes nothing for them; a month with nothing due only
 * supersedes; the submission takes CALCULATED records only; the Checker's decision approves or
 * returns them; the invoice report lists every record.
 */
class LeaveArrearServiceTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 7, 1);
    private static final LocalDate PAID_ON = LocalDate.of(2026, 8, 12);

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final AuditService audit = mock(AuditService.class);
    private final Circulars circulars = mock(Circulars.class);
    private final PendingSalary salary = mock(PendingSalary.class);
    private final StaffEarnings earnings = mock(StaffEarnings.class);
    private final LeaveParameters parameters = mock(LeaveParameters.class);
    private final RetroEvents events = mock(RetroEvents.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final LeaveArrearService service;

    private final AccessContext context =
            new AccessContext(
                    1L,
                    "maker@x",
                    "M",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    LeaveArrearServiceTest() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service =
                new LeaveArrearService(
                        named,
                        policy,
                        approvals,
                        audit,
                        circulars,
                        salary,
                        earnings,
                        parameters,
                        events,
                        transactions);
        when(events.header(any())).thenReturn(90L);
    }

    private static LeaveArrearService.Run run(String eis) {
        return new LeaveArrearService.Run(eis, 3L, EFFECTIVE, "DA order");
    }

    private void members(Object... idEisSeparation) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < idEisSeparation.length; i += 3) {
            Map<String, Object> m = new HashMap<>();
            m.put("EMPLOYEE_ID", idEisSeparation[i]);
            m.put("EMP_NUM", idEisSeparation[i + 1]);
            m.put("SEPARATION_DATE", idEisSeparation[i + 2]);
            rows.add(m);
        }
        when(named.queryForList(
                        contains("SELECT DISTINCT e.employee_id"), any(SqlParameterSource.class)))
                .thenReturn(rows);
    }

    private void encashments(Object... dayDaysPaid) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < dayDaysPaid.length; i += 3) {
            rows.add(
                    Map.of(
                            "PERIOD_FROM", dayDaysPaid[i],
                            "DAYS_ENCASHED", new BigDecimal((String) dayDaysPaid[i + 1]),
                            "AMOUNT_PAID", new BigDecimal((String) dayDaysPaid[i + 2])));
        }
        when(named.queryForList(
                        contains("FROM leave_encashment WHERE"), any(SqlParameterSource.class)))
                .thenReturn(rows);
    }

    private void configured() {
        when(parameters.number(eq(LeaveParameters.ENCASH_DIVISOR), any(), any()))
                .thenReturn(Optional.of(new BigDecimal("30")));
        when(parameters.text(eq(LeaveParameters.ENCASH_PAY_ELEMENTS), any(), any()))
                .thenReturn(Optional.of("101+111"));
        when(salary.compute(any(), any()))
                .thenReturn(
                        new PendingSalary.Result(
                                true,
                                null,
                                null,
                                null,
                                null,
                                List.of(
                                        new PendingSalary.Line(101, new BigDecimal("60000")),
                                        new PendingSalary.Line(111, new BigDecimal("30000")),
                                        new PendingSalary.Line(121, new BigDecimal("9000"))),
                                "h"));
        when(named.queryForObject(
                        contains("COALESCE(SUM(arrear_amount)"),
                        any(SqlParameterSource.class),
                        eq(BigDecimal.class)))
                .thenReturn(null);
        when(named.queryForObject(
                        contains("SELECT le_arrear_id FROM"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(5L);
    }

    @Test
    void SRS_8_23_theOrderAndItsDateAreNeededAndAnUnknownMemberIsNotFound() {
        assertThatThrownBy(
                        () ->
                                service.run(
                                        context,
                                        new LeaveArrearService.Run(null, null, EFFECTIVE, "r")))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(LeaveErrorCode.ARREAR_SELECTION);
        assertThatThrownBy(
                        () -> service.run(context, new LeaveArrearService.Run(null, 3L, null, "r")))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(LeaveErrorCode.ARREAR_SELECTION);
        members();
        assertThatThrownBy(() -> service.run(context, run(" 104882 ")))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        AccessContext nowhere =
                new AccessContext(
                        1L, "maker@x", "M", "s", "P", null, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        assertThatThrownBy(() -> service.run(nowhere, run(null)))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void SRS_8_23_aRunWithNoMemberStillRecordsItsEventAndNothingElse() {
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        members();
        LeaveArrearService.RunResult r =
                service.run(context, new LeaveArrearService.Run("  ", 3L, EFFECTIVE, null));
        assertThat(r.eventId()).isEqualTo(90L);
        assertThat(r.records()).isZero();
        verify(named, never())
                .update(
                        contains("INSERT INTO leave_encashment_arrear"),
                        any(SqlParameterSource.class));
    }

    @Test
    void SRS_8_23_eachAffectedMonthIsRecalculatedOnTheRevisedPayAndAnEarlierRecordSuperseded() {
        members(7L, "104882", null);
        encashments(
                Date.valueOf(PAID_ON),
                "10",
                "20000",
                Date.valueOf(PAID_ON.plusDays(3)),
                "5",
                "10000",
                Date.valueOf(EFFECTIVE.minusDays(1)),
                "4",
                "100");
        configured();
        when(earnings.months(eq(7L), anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new StaffEarnings.Month(
                                        "202607",
                                        true,
                                        List.of(
                                                new StaffEarnings.Line(
                                                        101,
                                                        "Basic",
                                                        new BigDecimal("55000"),
                                                        true),
                                                new StaffEarnings.Line(
                                                        111, "DA", new BigDecimal("27500"), true),
                                                new StaffEarnings.Line(
                                                        121, "HRA", new BigDecimal("8000"), true)),
                                        BigDecimal.ZERO,
                                        BigDecimal.ZERO)));
        LeaveArrearService.RunResult r = service.run(context, run("104882"));
        assertThat(r.records())
                .as("one month: the day before the revision is not affected")
                .isEqualTo(1);
        assertThat(r.skipped()).isEmpty();
        verify(named)
                .update(contains("SET le_status = 'CANCELLED'"), any(SqlParameterSource.class));
        verify(named)
                .update(
                        contains("INSERT INTO leave_encashment_arrear"),
                        any(SqlParameterSource.class));
        verify(audit).record(eq("maker@x"), any());
    }

    @Test
    void
            BR_ARR_LE_003_aMonthAlreadyPaidInFullOnlySupersedesAndAMemberSeparatedBeforeTheRevisionIsNotEligible() {
        members(7L, "104882", null, 8L, "104883", Date.valueOf(EFFECTIVE.minusDays(5)));
        encashments(Date.valueOf(PAID_ON), "10", "40000");
        configured();
        when(earnings.months(eq(7L), anyString(), anyString())).thenReturn(List.of());
        when(earnings.months(eq(8L), anyString(), anyString())).thenReturn(List.of());
        LeaveArrearService.RunResult r = service.run(context, run(null));
        // 104882: due 90,000 / 30 x 10 = 30,000 <= 40,000 paid: nothing to record.
        // 104883: separated before the revision: one NOT_ELIGIBLE record.
        assertThat(r.records()).isEqualTo(1);
        assertThat(r.members()).isEqualTo(2);
        verify(named, times(2))
                .update(contains("SET le_status = 'CANCELLED'"), any(SqlParameterSource.class));
        verify(named, times(1))
                .update(
                        contains("INSERT INTO leave_encashment_arrear"),
                        any(SqlParameterSource.class));
    }

    @Test
    void SRS_8_23_aMissingDivisorPayElementsOrRevisedPaySkipsTheMemberAndWritesNothingForThem() {
        members(7L, "104882", null);
        encashments(Date.valueOf(PAID_ON), "10", "20000");
        when(parameters.number(eq(LeaveParameters.ENCASH_DIVISOR), any(), any()))
                .thenReturn(Optional.empty());
        assertThat(service.run(context, run(null)).skipped()).hasSize(1);
        when(parameters.number(eq(LeaveParameters.ENCASH_DIVISOR), any(), any()))
                .thenReturn(Optional.of(BigDecimal.ZERO));
        assertThat(service.run(context, run(null)).skipped()).as("a zero divisor").hasSize(1);
        when(parameters.number(eq(LeaveParameters.ENCASH_DIVISOR), any(), any()))
                .thenReturn(Optional.of(new BigDecimal("30")));
        when(parameters.text(eq(LeaveParameters.ENCASH_PAY_ELEMENTS), any(), any()))
                .thenReturn(Optional.empty());
        assertThat(service.run(context, run(null)).skipped()).hasSize(1);
        when(parameters.text(eq(LeaveParameters.ENCASH_PAY_ELEMENTS), any(), any()))
                .thenReturn(Optional.of("999"));
        when(salary.compute(any(), any()))
                .thenReturn(
                        new PendingSalary.Result(
                                true,
                                null,
                                null,
                                null,
                                null,
                                List.of(new PendingSalary.Line(101, BigDecimal.TEN)),
                                "h"));
        assertThat(service.run(context, run(null)).skipped())
                .as("no revised pay element")
                .hasSize(1);
        when(salary.compute(any(), any())).thenThrow(new PendingSalary.NotComputable("no rate"));
        LeaveArrearService.RunResult r = service.run(context, run(null));
        assertThat(r.skipped())
                .extracting(LeaveArrearService.Skipped::eis)
                .containsExactly("104882");
        verify(named, never())
                .update(contains("leave_encashment_arrear"), any(SqlParameterSource.class));
    }

    @Test
    void SRS_8_24_onlyCalculatedRecordsOfTheOfficeAreSubmittedWithARemark() {
        assertThatThrownBy(() -> service.submit(context, List.of(), "r"))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(LeaveErrorCode.ARREAR_SELECTION);
        assertThatThrownBy(() -> service.submit(context, null, "r"))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.submit(context, List.of(1L), null))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.submit(context, List.of(1L), " "))
                .isInstanceOf(FciException.class);
        when(named.queryForList(
                        contains("a.le_arrear_id IN (:ids)"), any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.submit(context, List.of(1L), "r"))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        when(named.queryForList(
                        contains("a.le_arrear_id IN (:ids)"), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "LE_ARREAR_ID",
                                        1L,
                                        "RECORD_NUMBER",
                                        "LEA-1",
                                        "LE_STATUS",
                                        "APPROVED",
                                        "ARREAR_AMOUNT",
                                        BigDecimal.TEN)));
        assertThatThrownBy(() -> service.submit(context, List.of(1L), "r"))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(LeaveErrorCode.ARREAR_STATE);
        when(named.queryForList(
                        contains("a.le_arrear_id IN (:ids)"), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "LE_ARREAR_ID",
                                        1L,
                                        "RECORD_NUMBER",
                                        "LEA-1",
                                        "LE_STATUS",
                                        "CALCULATED",
                                        "ARREAR_AMOUNT",
                                        BigDecimal.TEN),
                                Map.of(
                                        "LE_ARREAR_ID",
                                        2L,
                                        "RECORD_NUMBER",
                                        "LEA-2",
                                        "LE_STATUS",
                                        "CALCULATED",
                                        "ARREAR_AMOUNT",
                                        BigDecimal.ONE)));
        when(named.update(contains("SET le_status = 'SUBMITTED'"), any(SqlParameterSource.class)))
                .thenReturn(2);
        service.submit(context, List.of(1L, 2L, 2L), " Order ");
        verify(approvals).submit(eq(context), any(), any());
        verify(named)
                .update(
                        contains("WHERE le_arrear_id IN (:ids) AND le_status = 'CALCULATED'"),
                        any(SqlParameterSource.class));
    }

    /**
     * SRS-8-24 and Oracle review F4: two Makers read the same records as CALCULATED; the second
     * submission finds them already SUBMITTED in its UPDATE (fewer rows changed than selected) and
     * is refused as a concurrent change, so no record goes to the Checker twice.
     */
    @Test
    void SRS_8_24_aSecondSubmissionOfTheSameRecordsIsRefused() {
        when(named.queryForList(
                        contains("a.le_arrear_id IN (:ids)"), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "LE_ARREAR_ID",
                                        1L,
                                        "RECORD_NUMBER",
                                        "LEA-1",
                                        "LE_STATUS",
                                        "CALCULATED",
                                        "ARREAR_AMOUNT",
                                        BigDecimal.TEN),
                                Map.of(
                                        "LE_ARREAR_ID",
                                        2L,
                                        "RECORD_NUMBER",
                                        "LEA-2",
                                        "LE_STATUS",
                                        "CALCULATED",
                                        "ARREAR_AMOUNT",
                                        BigDecimal.ONE)));
        // The first Maker's submission took record 2 between the read and the update.
        when(named.update(contains("SET le_status = 'SUBMITTED'"), any(SqlParameterSource.class)))
                .thenReturn(1);
        assertThatThrownBy(() -> service.submit(context, List.of(1L, 2L), "Order"))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.PRECONDITION_FAILED);
        // Nothing of the first submission: no record changed now.
        when(named.update(contains("SET le_status = 'SUBMITTED'"), any(SqlParameterSource.class)))
                .thenReturn(0);
        assertThatThrownBy(() -> service.submit(context, List.of(1L, 2L), "Order"))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.PRECONDITION_FAILED);
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome) {
        return new ApprovalDecided(
                4L, "I-24", entity, "LEB-1", outcome, "wrong month", null, "ddo@x", Instant.now());
    }

    @Test
    void SRS_8_24_theCheckersApprovalApprovesTheBatchAndARejectionReturnsItToCalculated() {
        service.on(decided("other", ApprovalStatus.APPROVED));
        verify(named, never()).update(anyString(), any(SqlParameterSource.class));
        service.on(decided(LeaveArrearService.ENTITY, ApprovalStatus.APPROVED));
        verify(named).update(contains("SET le_status = 'APPROVED'"), any(SqlParameterSource.class));
        service.on(decided(LeaveArrearService.ENTITY, ApprovalStatus.REJECTED));
        verify(named)
                .update(contains("SET le_status = 'CALCULATED'"), any(SqlParameterSource.class));
        verify(audit, times(2)).record(eq("ddo@x"), any());
    }

    private ResultSet row(boolean separated, boolean named_) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("le_arrear_id")).thenReturn(1L);
        when(rs.getString("record_number")).thenReturn("LEA-2026-00001");
        when(rs.getString("emp_num")).thenReturn("104882");
        when(rs.getString("first_name")).thenReturn(named_ ? "Rakesh" : null);
        when(rs.getString("last_name")).thenReturn(named_ ? "Sharma" : null);
        when(rs.getDate("separation_date")).thenReturn(separated ? Date.valueOf(EFFECTIVE) : null);
        when(rs.getString("circular_number")).thenReturn(separated ? "DA/1" : null);
        when(rs.getDate("revision_effective_date")).thenReturn(Date.valueOf(EFFECTIVE));
        when(rs.getString("arrear_yymm")).thenReturn("202608");
        when(rs.getString("le_status")).thenReturn("CALCULATED");
        return rs;
    }

    @Test
    void SRS_G_13_theRegisterPagesAndTheInvoiceReportListsEveryRecord() throws Exception {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);
        when(named.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<LeaveArrearService.Row>>any()))
                .thenAnswer(
                        call -> {
                            RowMapper<?> mapper = call.getArgument(2);
                            return List.of(
                                    mapper.mapRow(row(true, true), 0),
                                    mapper.mapRow(row(false, false), 1));
                        });
        var page =
                service.records(
                        context,
                        new LeaveArrearService.Filter(" CALCULATED ", ""),
                        new PageSpec(0, 20));
        assertThat(page.items())
                .extracting(LeaveArrearService.Row::name)
                .containsExactly("Rakesh Sharma", "");
        assertThat(service.reportSize(context, new LeaveArrearService.Filter(null, "104882")))
                .isEqualTo(2L);

        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(2L);
        doAnswer(
                        call -> {
                            RowCallbackHandler handler = call.getArgument(2);
                            handler.processRow(row(true, true));
                            handler.processRow(row(false, true));
                            return null;
                        })
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        service.report(context, new LeaveArrearService.Filter(null, null), sink);
        verify(sink, times(2)).row(any(Object[].class));
        verify(sink).header(any(String[].class));
    }

    @Test
    void SRS_G_13_aReportThatCannotBeWrittenFailsAndAShortReportIsRefused() throws Exception {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        ExportSink broken = mock(ExportSink.class);
        doAnswer(
                        call -> {
                            throw new java.io.IOException("disk full");
                        })
                .when(broken)
                .row(any(Object[].class));
        doAnswer(
                        call -> {
                            RowCallbackHandler handler = call.getArgument(2);
                            handler.processRow(row(false, true));
                            return null;
                        })
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        assertThatThrownBy(
                        () ->
                                service.report(
                                        context, new LeaveArrearService.Filter(null, null), broken))
                .isInstanceOf(java.io.UncheckedIOException.class);
        ExportSink empty = mock(ExportSink.class);
        when(empty.rows()).thenReturn(0L);
        doAnswer(call -> null)
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        assertThatThrownBy(
                        () ->
                                service.report(
                                        context, new LeaveArrearService.Filter(null, null), empty))
                .isInstanceOf(RuntimeException.class);
        assertThat(YearMonth.of(2026, 8)).isNotNull();
    }

    /**
     * BR-ARR-LE-003 (branch gap): the dates read back as {@link LocalDate} (as a driver may return
     * a DATE column) are read the same as {@code java.sql.Date}: the member separated before the
     * revision is not eligible and the encashment after it is recalculated.
     */
    @Test
    void BR_ARR_LE_003_datesReadAsLocalDateAreReadTheSameAsSqlDates() {
        members(7L, "104882", null, 8L, "104883", EFFECTIVE.minusDays(5));
        encashments(PAID_ON, "10", "40000");
        configured();
        when(earnings.months(eq(7L), anyString(), anyString())).thenReturn(List.of());
        when(earnings.months(eq(8L), anyString(), anyString())).thenReturn(List.of());
        LeaveArrearService.RunResult r = service.run(context, run(null));
        assertThat(r.records()).isEqualTo(1);
        assertThat(r.members()).isEqualTo(2);
        verify(named, times(1))
                .update(
                        contains("INSERT INTO leave_encashment_arrear"),
                        any(SqlParameterSource.class));
    }
}
