package in.gov.fci.payroll.payroll.batch.arrear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import in.gov.fci.payroll.payroll.batch.reader.EmployeeRow;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Plan 13 phase 13c (SRS-8-10): the HRA arrear's rate is the HRA % of the member's city class of
 * that month; a member without a city class, or a class without a rate, has none (never a guess).
 */
class HraArrearRateTest {

    private static MemberReader.MemberRow row(String cityClass) {
        return row("IDA", cityClass);
    }

    private static MemberReader.MemberRow row(String pattern, String cityClass) {
        return new MemberReader.MemberRow(
                7L, 1L, BigDecimal.TEN, pattern, cityClass, "IN_SERVICE", null, null, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_8_10_theRateIsTheHraPercentOfTheMonthsCityClass() {
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        PayConfiguration.Snapshot snapshot =
                new PayConfiguration.Snapshot(
                        java.time.LocalDate.of(2026, 5, 1),
                        Map.of(),
                        Map.of(
                                "X",
                                new BigDecimal("30"),
                                PayConfiguration.CDA_HRA + "Y",
                                new BigDecimal("15")),
                        Map.of(),
                        false,
                        Map.of(),
                        Map.of(),
                        "hash");
        DaArrearProcessor.Rate hra = HraArrearJobConfig.hra(named);
        EmployeeRow member = new EmployeeRow(7L, 1L, BigDecimal.TEN, "N", "IDA");
        YearMonth month = YearMonth.of(2026, 5);
        assertThat(hra.payCode()).isEqualTo(PayRules.HRA);
        assertThat(hra.label()).isEqualTo("HRA");

        when(named.query(anyString(), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(row("X")));
        assertThat(hra.percent(snapshot, member, month)).contains(new BigDecimal("30"));
        when(named.query(anyString(), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(row("Y")));
        assertThat(hra.percent(snapshot, member, month)).as("no rate for the class").isEmpty();
        when(named.query(anyString(), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(row(null)));
        assertThat(hra.percent(snapshot, member, month)).as("no city class").isEmpty();
        when(named.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
        assertThat(hra.percent(snapshot, member, month)).as("not on the master").isEmpty();
        // SRS-1-3 (US-GAP-41): a CDA member's arrear rate is the CDA rule's, never the IDA one.
        when(named.query(anyString(), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(row("CDA", "Y")));
        assertThat(hra.percent(snapshot, member, month)).contains(new BigDecimal("15"));
        when(named.query(anyString(), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(row("CDA", "X")));
        assertThat(hra.percent(snapshot, member, month)).as("no CDA rate for X").isEmpty();
    }
}
