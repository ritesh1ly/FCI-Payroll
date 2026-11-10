package in.gov.fci.payroll.allowance.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * I-03 claim register and medical views (plan 10; SRS-11-19, SRS-11-21, SRS-11-23): each claim
 * shows its source (LDH, eSeva or HRMS), its taxable flag, IOGA and the Checker's reason when it
 * was rejected or is waiting; a member number, claim type or month in the wrong form is refused; a
 * register that cannot be written fails.
 */
class ClaimQueryServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final ClaimScope scope = mock(ClaimScope.class);
    private final ClaimQueryService service =
            new ClaimQueryService(named, scope, mock(DocumentStore.class));
    private final AccessContext context =
            new AccessContext(
                    1L,
                    "u@x",
                    "U",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private static ResultSet row(String source, String taxable, String status, boolean full)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(7L);
        when(rs.getString(3)).thenReturn("Ram");
        when(rs.getString(4)).thenReturn(full ? "Lal" : null);
        when(rs.getDate(8)).thenReturn(Date.valueOf(LocalDate.of(2026, 10, 1)));
        when(rs.getDate(9)).thenReturn(Date.valueOf(LocalDate.of(2026, 10, 5)));
        when(rs.getString(13)).thenReturn(full ? "Y" : "N");
        when(rs.getString(14)).thenReturn(status);
        when(rs.getString(15)).thenReturn(source);
        when(rs.getString(17)).thenReturn(taxable);
        when(rs.getTimestamp(18))
                .thenReturn(full ? Timestamp.from(Instant.parse("2026-10-20T05:00:00Z")) : null);
        when(rs.getDate(21)).thenReturn(full ? Date.valueOf(LocalDate.of(2026, 10, 2)) : null);
        when(rs.getLong(23)).thenReturn(full ? 41L : 0L);
        when(rs.wasNull()).thenReturn(!full);
        when(rs.getString(24)).thenReturn("No order");
        when(rs.getBigDecimal(10)).thenReturn(BigDecimal.TEN);
        return rs;
    }

    @Test
    void SRS_11_23_eachClaimShowsItsSourceTaxableFlagIogaAndTheReasonOfARejection() {
        when(scope.offices(context, LabourClaimService.SCREEN, "VIEW"))
                .thenReturn(Optional.of(Set.of(10L)));
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(3L);
        when(named.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<ClaimQueryService.ClaimRow>>any()))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(2);
                            return List.of(
                                    m.mapRow(row("ENT", "Y", "REJECTED", true), 0),
                                    m.mapRow(row("ESS", null, "SUBMITTED", false), 1),
                                    m.mapRow(row("HRMS", "N", "PAID", true), 2));
                        });
        var page =
                service.register(
                        context,
                        new ClaimQueryService.Filter("LABOUR", null, "LABOUR_TA", null),
                        new PageSpec(0, 20));
        assertThat(page.items())
                .extracting(ClaimQueryService.ClaimRow::source)
                .containsExactly("LDH", "ESEVA", "HRMS");
        assertThat(page.items())
                .extracting(ClaimQueryService.ClaimRow::taxable)
                .containsExactly(true, null, false);
        assertThat(page.items())
                .extracting(ClaimQueryService.ClaimRow::decisionReason)
                .containsExactly("No order", "No order", null);
        assertThat(page.items())
                .extracting(ClaimQueryService.ClaimRow::name)
                .containsExactly("Ram Lal", "Ram", "Ram Lal");
        assertThat(page.items().get(1).approvalId()).isNull();
        assertThat(page.items().get(1).iogaDate()).isNull();
    }

    @Test
    void SRS_11_19_aMemberNumberClaimTypeOrMonthInTheWrongFormIsRefused() {
        when(scope.offices(any(), anyString(), anyString())).thenReturn(Optional.empty());
        for (String eis : new String[] {null, "104 882", "x".repeat(21)}) {
            assertThatThrownBy(() -> service.medical(context, eis))
                    .extracting(e -> ((FciException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        }
        assertThatThrownBy(
                        () ->
                                service.registerSize(
                                        context,
                                        new ClaimQueryService.Filter(null, null, "medical", null)))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        for (String yymm : new String[] {null, "202613", "2026-10"}) {
            assertThatThrownBy(() -> service.medicalRegisterSize(context, yymm))
                    .extracting(e -> ((FciException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        }
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);
        assertThat(service.medicalRegisterSize(context, "202610")).isEqualTo(2L);
        assertThat(
                        service.registerSize(
                                context, new ClaimQueryService.Filter(null, null, null, null)))
                .isEqualTo(2L);
    }

    @Test
    void SRS_11_21_theMedicalRegisterListsEveryClaimAndAFailedWriteStopsIt() throws Exception {
        when(scope.offices(any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);
        ResultSet a = row("HRMS", "Y", "PAID", true);
        ResultSet b = row("HRMS", null, "PAID", false);
        doAnswer(
                        call -> {
                            RowCallbackHandler h = call.getArgument(2);
                            h.processRow(a);
                            h.processRow(b);
                            return null;
                        })
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(2L);
        service.medicalRegister(context, "202610", sink);
        verify(sink, org.mockito.Mockito.times(2)).row(any(Object[].class));
        ExportSink broken = mock(ExportSink.class);
        doAnswer(
                        call -> {
                            throw new IOException("disk full");
                        })
                .when(broken)
                .row(any(Object[].class));
        assertThatThrownBy(
                        () ->
                                service.register(
                                        context,
                                        new ClaimQueryService.Filter(null, null, null, null),
                                        broken))
                .isInstanceOf(IOException.class)
                .hasMessage("disk full");
    }
}
