package in.gov.fci.payroll.allowance.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * SRS-11-19 / SRS-11-21 (I-03 medical register, plan 10 D10-9): the taxable flag is written Y when
 * confirmed taxable, N when confirmed not taxable and empty while not yet confirmed.
 */
class ClaimQueryServiceBranchGapsTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final ClaimScope scope = mock(ClaimScope.class);
    private final ClaimQueryService service =
            new ClaimQueryService(named, scope, mock(DocumentStore.class));

    private static ResultSet row(long id, String taxable) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(id);
        when(rs.getString(3)).thenReturn("Ram");
        when(rs.getDate(8)).thenReturn(Date.valueOf(LocalDate.of(2026, 10, 1)));
        when(rs.getDate(9)).thenReturn(Date.valueOf(LocalDate.of(2026, 10, 5)));
        when(rs.getString(13)).thenReturn("N");
        when(rs.getString(14)).thenReturn("PAID");
        when(rs.getString(15)).thenReturn("HRMS");
        when(rs.getString(17)).thenReturn(taxable);
        when(rs.wasNull()).thenReturn(true);
        when(rs.getBigDecimal(10)).thenReturn(BigDecimal.TEN);
        return rs;
    }

    @Test
    void SRS_11_19_theMedicalRegisterWritesTheTaxableFlagYNOrEmpty() throws Exception {
        when(scope.offices(any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(3L);
        ResultSet yes = row(1L, "Y");
        ResultSet no = row(2L, "N");
        ResultSet open = row(3L, null);
        doAnswer(
                        call -> {
                            RowCallbackHandler h = call.getArgument(2);
                            h.processRow(yes);
                            h.processRow(no);
                            h.processRow(open);
                            return null;
                        })
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(3L);

        service.medicalRegister(mock(AccessContext.class), "202610", sink);

        ArgumentCaptor<Object[]> rows = ArgumentCaptor.forClass(Object[].class);
        verify(sink, times(3)).row(rows.capture());
        List<Object> flags = rows.getAllValues().stream().map(r -> r[9]).toList();
        assertThat(flags).containsExactly("Y", "N", "");
    }
}
