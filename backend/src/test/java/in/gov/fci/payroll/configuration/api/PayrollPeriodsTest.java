package in.gov.fci.payroll.configuration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.config.ApiProperties;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * M-10 payroll calendar periods: the current period is the approved calendar row containing today,
 * and only without one the configured period; rows map with their optional dates. The approved
 * calendar is cached (region PAYROLL_CALENDAR) and read again only after an approved change.
 */
class PayrollPeriodsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    private final AtomicLong version = new AtomicLong(1);
    private JdbcTemplate jdbc;
    private PayrollPeriods periods;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        SystemParameters parameters = mock(SystemParameters.class);
        when(parameters.today()).thenReturn(TODAY);
        ApiProperties api = mock(ApiProperties.class);
        when(api.payroll()).thenReturn(new ApiProperties.Payroll(YearMonth.of(2026, 7), "OPEN"));
        CacheRegions regions = mock(CacheRegions.class);
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        when(regions.version(PayrollPeriods.REGION)).thenAnswer(call -> version.get());
        periods = new PayrollPeriods(jdbc, parameters, api, regions);
    }

    @SuppressWarnings("unchecked")
    private void calendar(ResultSet... rows) {
        when(jdbc.query(contains("ORDER BY c.payroll_yymm DESC"), any(RowMapper.class)))
                .thenAnswer(
                        call -> {
                            RowMapper<Object> mapper = call.getArgument(1);
                            List<Object> out = new java.util.ArrayList<>();
                            for (int i = 0; i < rows.length; i++) {
                                out.add(mapper.mapRow(rows[i], i));
                            }
                            return out;
                        });
    }

    private static ResultSet row(String yymm, boolean withOptionalDates) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        YearMonth month =
                YearMonth.of(
                        Integer.parseInt(yymm.substring(0, 4)),
                        Integer.parseInt(yymm.substring(4)));
        when(rs.getString(1)).thenReturn(yymm);
        when(rs.getString(2)).thenReturn("2026-27");
        when(rs.getDate(3)).thenReturn(Date.valueOf(month.atDay(1)));
        when(rs.getDate(4)).thenReturn(Date.valueOf(month.atEndOfMonth()));
        when(rs.getDate(5)).thenReturn(withOptionalDates ? Date.valueOf(month.atDay(25)) : null);
        when(rs.getDate(6))
                .thenReturn(withOptionalDates ? Date.valueOf(month.atEndOfMonth()) : null);
        return rs;
    }

    @Test
    @SuppressWarnings("unchecked")
    void M_10_theCurrentPeriodIsTheApprovedCalendarMonthContainingToday() throws Exception {
        calendar(row("202611", true), row("202610", true), row("202609", true));
        assertThat(periods.current()).isEqualTo(YearMonth.of(2026, 10));
        verify(jdbc).query(contains("a.status = 'APPROVED'"), any(RowMapper.class));
    }

    @Test
    void M_10_withoutAnApprovedCalendarRowTheConfiguredPeriodApplies() throws Exception {
        calendar();
        assertThat(periods.current()).isEqualTo(YearMonth.of(2026, 7));
        version.incrementAndGet();
        calendar(row("202611", true));
        assertThat(periods.current())
                .as("today is outside the only period")
                .isEqualTo(YearMonth.of(2026, 7));
    }

    @Test
    void M_10_aPeriodIsLookedUpByItsYymmAndMapsItsOptionalDates() throws Exception {
        calendar(row("202608", false));
        PayrollPeriods.Period august = periods.of(YearMonth.of(2026, 8)).orElseThrow();
        assertThat(august.period()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(august.finYear()).isEqualTo("2026-27");
        assertThat(august.start()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(august.end()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(august.lockTarget()).isNull();
        assertThat(august.disbursement()).isNull();
        assertThat(periods.of(YearMonth.of(2019, 1))).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void M_10_theApprovedCalendarIsReadOnceUntilAnApprovedChange() throws Exception {
        calendar(row("202610", true));
        List<PayrollPeriods.Period> all = periods.all();
        assertThat(all)
                .extracting(PayrollPeriods.Period::period)
                .containsExactly(YearMonth.of(2026, 10));
        assertThat(all.get(0).lockTarget()).isEqualTo(LocalDate.of(2026, 10, 25));
        periods.current();
        periods.of(YearMonth.of(2026, 10));
        verify(jdbc, times(1))
                .query(contains("ORDER BY c.payroll_yymm DESC"), any(RowMapper.class));
        version.incrementAndGet();
        periods.all();
        verify(jdbc, times(2))
                .query(contains("ORDER BY c.payroll_yymm DESC"), any(RowMapper.class));
    }

    @Test
    void M_10_aPeriodThatEndedBeforeTodayIsNotTheCurrentOne() throws Exception {
        // September started before 21 October and ended before it: the configured period applies.
        calendar(row("202609", true));
        assertThat(periods.current()).isEqualTo(YearMonth.of(2026, 7));
    }
}
