package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * I-05 reports (plan 17; SRS-13-15, SRS-13-21, SRS-13-26): a member without Basic history in scope
 * is not found; the Increment Tracking Report shows a member still due as PENDING and one
 * incremented as PROCESSED, without a name or a date when none is held; a report that cannot be
 * written fails.
 */
class IncrementReportServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final IncrementReportService service =
            new IncrementReportService(named, mock(Authorization.class));
    private final AccessContext context =
            new AccessContext(
                    1L,
                    "hr@x",
                    "H",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    @Test
    void SRS_13_26_aMemberWithoutBasicHistoryInScopeIsNotFound() {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        assertThatThrownBy(() -> service.history(context, "104882"))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    private static ResultSet tracked(boolean processed) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("109991");
        when(rs.getString(2)).thenReturn(processed ? "Vikram" : null);
        when(rs.getString(3)).thenReturn(processed ? "Kumar" : null);
        when(rs.getString(4)).thenReturn("DH001");
        when(rs.getString(5)).thenReturn("Delhi");
        when(rs.getDate(6)).thenReturn(processed ? Date.valueOf(LocalDate.of(2026, 10, 1)) : null);
        when(rs.getString(7)).thenReturn(processed ? "STAGNATION" : null);
        when(rs.getString(11)).thenReturn(processed ? "hr.delhi@fci-demo.test" : null);
        when(rs.getString(12)).thenReturn(processed ? "ddo.delhi@fci-demo.test" : null);
        return rs;
    }

    /**
     * SRS-13-15 / BR-INCR-STAG-008: a stagnation increment of the month is reported with its
     * approving users (the Maker who submitted it, the Checker who approved it).
     */
    @Test
    void BR_INCR_STAG_008_aStagnationIncrementIsReportedWithItsApprovingUsers() throws Exception {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        ResultSet done = tracked(true);
        doAnswer(
                        call -> {
                            ((RowCallbackHandler) call.getArgument(2)).processRow(done);
                            return null;
                        })
                .when(named)
                .query(
                        contains("q.submitted_by"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(1L);
        service.tracking(context, YearMonth.of(2026, 10), sink);
        verify(sink)
                .header(
                        "eis",
                        "member",
                        "office",
                        "due_date",
                        "status",
                        "type",
                        "old_basic",
                        "revised_basic",
                        "maker",
                        "approved_by");
        verify(sink)
                .row(
                        "109991",
                        "Vikram Kumar",
                        "DH001 - Delhi",
                        "2026-10-01",
                        "PROCESSED",
                        "STAGNATION",
                        null,
                        null,
                        "hr.delhi@fci-demo.test",
                        "ddo.delhi@fci-demo.test");
    }

    @Test
    void SRS_13_21_theTrackingReportShowsPendingAndProcessedMembersAndAFailedWriteStopsIt()
            throws Exception {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);
        ResultSet done = tracked(true);
        ResultSet due = tracked(false);
        doAnswer(
                        call -> {
                            RowCallbackHandler h = call.getArgument(2);
                            h.processRow(done);
                            h.processRow(due);
                            return null;
                        })
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(2L);
        service.tracking(context, YearMonth.of(2026, 10), sink);
        verify(sink)
                .row(
                        "109991",
                        "Vikram Kumar",
                        "DH001 - Delhi",
                        "2026-10-01",
                        "PROCESSED",
                        "STAGNATION",
                        null,
                        null,
                        "hr.delhi@fci-demo.test",
                        "ddo.delhi@fci-demo.test");
        verify(sink).row("109991", "", "DH001 - Delhi", "", "PENDING", "", null, null, "", "");
        ExportSink broken = mock(ExportSink.class);
        doAnswer(
                        call -> {
                            throw new IOException("disk full");
                        })
                .when(broken)
                .row(any(Object[].class));
        assertThatThrownBy(() -> service.tracking(context, YearMonth.of(2026, 10), broken))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void SRS_13_26_aHistoryReportThatCannotBeWrittenFails() throws Exception {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getDate(1)).thenReturn(Date.valueOf(LocalDate.of(2010, 7, 1)));
        doAnswer(
                        call -> {
                            ((RowCallbackHandler) call.getArgument(2)).processRow(rs);
                            return null;
                        })
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        ExportSink broken = mock(ExportSink.class);
        doAnswer(
                        call -> {
                            throw new IOException("disk full");
                        })
                .when(broken)
                .row(any(Object[].class));
        assertThatThrownBy(() -> service.historyReport(context, "109991", broken))
                .isInstanceOf(UncheckedIOException.class);
    }
}
