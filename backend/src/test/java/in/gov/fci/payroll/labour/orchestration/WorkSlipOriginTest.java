package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * UC M-11 (SRS l.1321; OD-51, Wave A review): the labour module answers the transfer origin check
 * with the member's earliest work-slip dated on or after the date, cancelled and rejected slips not
 * counted; none when nothing is found.
 */
class WorkSlipOriginTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WorkSlipOrigin origin = new WorkSlipOrigin(jdbc);

    @Test
    void SRS_16_18_theFirstWorkSlipOnOrAfterTheDate() {
        LocalDate day = LocalDate.of(2026, 10, 15);
        when(jdbc.queryForList(anyString(), eq(Date.class), any(), any())).thenReturn(List.of());
        assertThat(origin.firstFrom(7L, day)).isEmpty();
        when(jdbc.queryForList(anyString(), eq(Date.class), any(), any()))
                .thenReturn(Arrays.asList((Date) null));
        assertThat(origin.firstFrom(7L, day)).isEmpty();
        when(jdbc.queryForList(
                        contains(
                                "p.work_slip_date >= ? AND s.slip_status NOT IN ('CANCELLED',"
                                        + " 'REJECTED')"),
                        eq(Date.class),
                        eq(7L),
                        eq(Date.valueOf(day))))
                .thenReturn(List.of(Date.valueOf(day)));
        assertThat(origin.firstFrom(7L, day)).contains(day);
    }
}
