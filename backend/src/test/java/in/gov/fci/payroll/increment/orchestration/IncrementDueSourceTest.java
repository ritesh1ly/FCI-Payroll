package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** SRS-13-1 (US-GAP-51): the next increment due date for the M-01 record, or none. */
class IncrementDueSourceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IncrementDueSource source = new IncrementDueSource(jdbc);

    @Test
    void SRS_13_1_theNextDueDateOnRecordOrNone() {
        when(jdbc.queryForList(anyString(), eq(Date.class), eq(7L)))
                .thenReturn(List.of(Date.valueOf("2027-07-01")));
        assertThat(source.nextDue(7L)).contains(LocalDate.of(2027, 7, 1));
        when(jdbc.queryForList(anyString(), eq(Date.class), eq(8L))).thenReturn(List.of());
        assertThat(source.nextDue(8L)).isEmpty();
    }
}
