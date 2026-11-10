package in.gov.fci.payroll.configuration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * The allowance masters read for the engine (plan 16 phase 16d; SRS-1-11, R-RULE): the Transport
 * lines of the approved TRANSPORT_IDA / TRANSPORT_CDA sets by pay pattern and city class, their set
 * ids in the version hash so a new set changes it; NPA from its latest approved versions.
 */
class AllowanceConfigurationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private void rows(String sqlStart, List<ResultSet> rows) {
        doAnswer(
                        i -> {
                            RowCallbackHandler h = i.getArgument(1);
                            for (ResultSet rs : rows) {
                                h.processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(startsWith(sqlStart), any(RowCallbackHandler.class), any(Object[].class));
    }

    private static ResultSet transport(String code, long setId, String cityClass, String amount)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(code);
        when(rs.getLong(2)).thenReturn(setId);
        when(rs.getString(3)).thenReturn(cityClass);
        when(rs.getBigDecimal(4)).thenReturn(amount == null ? null : new BigDecimal(amount));
        return rs;
    }

    private static ResultSet npa(String code, long id, String pct, String ceiling)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(code);
        when(rs.getLong(2)).thenReturn(id);
        when(rs.getBigDecimal(3)).thenReturn(pct == null ? null : new BigDecimal(pct));
        when(rs.getBigDecimal(4)).thenReturn(ceiling == null ? null : new BigDecimal(ceiling));
        return rs;
    }

    @Test
    void SRS_1_11_transportLinesAndNpaAreReadAndANewSetChangesTheHash() throws Exception {
        rows(
                "SELECT p.parameter_code",
                List.of(npa("NPA_CEILING", 4L, null, "1000"), npa("NPA_PCT", 3L, "20", null)));
        rows(
                "SELECT s.rate_set_code",
                List.of(
                        transport("TRANSPORT_CDA", 12L, null, "800"),
                        transport("TRANSPORT_IDA", 11L, "X", "3600")));
        AllowanceConfiguration.Allowances first = new AllowanceConfiguration(jdbc).on(DAY);
        assertThat(first.transport())
                .containsExactly(
                        new AllowanceConfiguration.TransportRate(
                                "CDA", null, new BigDecimal("800")),
                        new AllowanceConfiguration.TransportRate(
                                "IDA", "X", new BigDecimal("3600")));
        assertThat(first.npaPercent()).isEqualByComparingTo("20");
        assertThat(first.npaCeiling()).isEqualByComparingTo("1000");

        rows("SELECT s.rate_set_code", List.of(transport("TRANSPORT_IDA", 13L, "X", "3600")));
        AllowanceConfiguration.Allowances second = new AllowanceConfiguration(jdbc).on(DAY);
        assertThat(second.hash()).isNotEqualTo(first.hash());
    }
}
