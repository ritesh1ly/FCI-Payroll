package in.gov.fci.payroll.payroll.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The payroll module's published reads for other modules (SRS-G-10: the I-20 / I-23 duplicate
 * checks and the off-cycle computation): unknown ids, no count, a member not on the master, a
 * stopped or held salary, and a data gap that cannot be computed.
 */
class PayrollApiEdgeTest {

    @Test
    void SRS_G_10_noIdNoResultAndNoCountMeansNotPaid() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PayrollRecords records = new PayrollRecords(jdbc);
        assertThat(records.result(null)).isEmpty();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
        assertThat(records.paidInRegularRun(1L, "202610")).isFalse();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
        assertThat(records.paidInRegularRun(1L, "202610")).isFalse();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(2);
        assertThat(records.paidInRegularRun(1L, "202610")).isTrue();
    }

    /** SRS-10-13: the months paid after the separation month, read from the month after it. */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_10_13_theMonthsPaidAfterAMonthAreReadFromTheNextMonth() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PayrollRecords records = new PayrollRecords(jdbc);
        when(jdbc.query(
                        org.mockito.ArgumentMatchers.contains(
                                "period_date > ? AND run_type = 'ACTUAL' AND result_status ="
                                        + " 'CURRENT'"),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        eq(7L),
                        eq(java.sql.Date.valueOf(LocalDate.of(2026, 9, 1)))))
                .thenAnswer(
                        inv -> {
                            org.springframework.jdbc.core.RowMapper<PayrollRecords.PaidMonth> m =
                                    inv.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getString(1)).thenReturn("202610");
                            when(rs.getBigDecimal(2)).thenReturn(java.math.BigDecimal.TEN);
                            return List.of(m.mapRow(rs, 0));
                        });
        assertThat(records.paidAfter(7L, java.time.YearMonth.of(2026, 9)))
                .containsExactly(new PayrollRecords.PaidMonth("202610", java.math.BigDecimal.TEN));
    }

    /**
     * UC M-11 (SRS l.1321; OD-51, Wave A review): the earliest ACTUAL period still standing that
     * starts on or after the date - the bound is inclusive, so a transfer dated the 1st finds that
     * month - and none when nothing is found.
     */
    @Test
    void SRS_16_18_theFirstComputedPeriodOnOrAfterTheDate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PayrollRecords records = new PayrollRecords(jdbc);
        LocalDate first = LocalDate.of(2026, 11, 1);
        when(jdbc.queryForList(anyString(), eq(java.sql.Date.class), any(), any()))
                .thenReturn(List.of());
        assertThat(records.firstActualPeriodFrom(7L, first)).isEmpty();
        when(jdbc.queryForList(anyString(), eq(java.sql.Date.class), any(), any()))
                .thenReturn(java.util.Arrays.asList((java.sql.Date) null));
        assertThat(records.firstActualPeriodFrom(7L, first)).isEmpty();
        when(jdbc.queryForList(
                        org.mockito.ArgumentMatchers.contains(
                                "period_date >= ? AND run_type = 'ACTUAL' AND result_status <>"
                                        + " 'SUPERSEDED'"),
                        eq(java.sql.Date.class),
                        eq(7L),
                        eq(java.sql.Date.valueOf(first))))
                .thenReturn(List.of(java.sql.Date.valueOf(first)));
        assertThat(records.firstActualPeriodFrom(7L, first)).contains(first);
    }

    private static PayConfiguration.Snapshot rates() {
        return new PayConfiguration.Snapshot(
                LocalDate.of(2026, 10, 1),
                Map.of("IDA", new BigDecimal("50")),
                Map.of("X", new BigDecimal("27")),
                Map.of(),
                true,
                Map.of(101, 1L, 111, 2L, 121, 3L),
                Map.of(),
                "v");
    }

    @SuppressWarnings("unchecked")
    private static PendingSalary pending(MemberReader.MemberRow row) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
                .thenReturn(row == null ? List.of() : List.of(row));
        PayConfiguration configuration = mock(PayConfiguration.class);
        when(configuration.staffConfiguration(any())).thenReturn(rates());
        return new PendingSalary(jdbc, configuration);
    }

    private static MemberReader.MemberRow member(String lastEvent, String pattern) {
        return new MemberReader.MemberRow(
                7L,
                2L,
                new BigDecimal("30000"),
                pattern,
                "X",
                "ACTIVE",
                null,
                lastEvent,
                "CCA",
                null);
    }

    @Test
    void SRS_G_10_theSalaryOfOneMonthIsComputedAsTheRunWouldOrRefused() {
        YearMonth month = YearMonth.of(2026, 10);
        PendingSalary.Result paid = pending(member(null, "IDA")).compute(7L, month);
        assertThat(paid.computed()).isTrue();
        assertThat(paid.gross()).isEqualByComparingTo("53100.00");
        assertThat(pending(member("SALARY_STOP", "IDA")).compute(7L, month).computed()).isFalse();
        assertThat(pending(member("HOLD", "IDA")).compute(7L, month).skipReason())
                .as("SRS-1-41: withheld, not paid off-cycle")
                .isEqualTo("SALARY_WITHHELD");
        assertThat(pending(member("HOLD", "IDA")).compute(7L, month).computed()).isFalse();
        assertThat(pending(member("SALARY_STOP", "IDA")).compute(7L, month).skipReason())
                .isEqualTo("STOP_PAYMENT");
        assertThatThrownBy(() -> pending(null).compute(7L, month))
                .isInstanceOf(PendingSalary.NotComputable.class)
                .hasMessageContaining("not on the Payroll master");
        assertThatThrownBy(() -> pending(member(null, null)).compute(7L, month))
                .isInstanceOf(PendingSalary.NotComputable.class)
                .hasMessageContaining("No pay pattern");
    }

    /**
     * SRS-G-10 / BMAD TR-037: the cycle quarantines a negative net (SRS-1-8), but an off-cycle
     * payment (I-20) of a negative net is no payment - refused, naming gross and deductions; a net
     * of exactly zero (deductions equal to gross) is computed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_G_10_anOffCyclePaymentOfANegativeNetIsRefused() {
        YearMonth month = YearMonth.of(2026, 10);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(member(null, "IDA")));
        PayConfiguration configuration = mock(PayConfiguration.class);
        when(configuration.staffConfiguration(any())).thenReturn(rates());
        java.util.function.Function<String, PendingSalary> with =
                amount -> {
                    StaffDeductions source =
                            new StaffDeductions() {
                                @Override
                                public int order() {
                                    return 10;
                                }

                                @Override
                                public Run open(LocalDate asOf) {
                                    return new Run() {
                                        @Override
                                        public String versions() {
                                            return "v";
                                        }

                                        @Override
                                        public List<Line> deductions(Member m) {
                                            return List.of(
                                                    new Line(
                                                            701,
                                                            new BigDecimal(amount),
                                                            null,
                                                            null));
                                        }
                                    };
                                }
                            };
                    org.springframework.beans.factory.ObjectProvider<StaffDeductions> provider =
                            mock(org.springframework.beans.factory.ObjectProvider.class);
                    when(provider.orderedStream())
                            .thenAnswer(i -> java.util.stream.Stream.of(source));
                    return new PendingSalary(
                            jdbc, configuration, new StaffDeductionRunner(provider));
                };
        Map<Integer, Long> ids = new java.util.HashMap<>(rates().componentIds());
        ids.put(701, 9L);
        when(configuration.staffConfiguration(any()))
                .thenReturn(
                        new PayConfiguration.Snapshot(
                                LocalDate.of(2026, 10, 1),
                                Map.of("IDA", new BigDecimal("50")),
                                Map.of("X", new BigDecimal("27")),
                                Map.of(),
                                true,
                                ids,
                                Map.of(),
                                "v"));
        assertThatThrownBy(() -> with.apply("53101").compute(7L, month))
                .isInstanceOf(PendingSalary.NotComputable.class)
                .hasMessageContaining("below zero")
                .hasMessageContaining("53100");
        assertThat(with.apply("53100").compute(7L, month).net()).isEqualByComparingTo("0");
    }
}
