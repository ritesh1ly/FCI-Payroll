package in.gov.fci.payroll.payroll.batch.arrear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * The rate arrear event (DDL v4.0 S13 {@code arrear_event}): a request without a recorded requester
 * raises the event as "system"; SRS-8-15: a range overlapping an open event of the type and unit is
 * refused.
 */
class ArrearEventsTest {

    @Test
    void aDaArrearRequestWithoutARequesterIsRaisedBySystem() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CycleRuns cycles = mock(CycleRuns.class);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(unit.getCode()).thenReturn("RO-DEL");
        when(cycles.cycleUnit(4L)).thenReturn(unit);
        when(jdbc.queryForObject(anyString(), eq(String.class), anyLong())).thenReturn(null);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("DA_REVISION"))).thenReturn(2L);
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString(), any(String[].class))).thenReturn(ps);
        doAnswer(
                        i -> {
                            ((PreparedStatementCreator) i.getArgument(0))
                                    .createPreparedStatement(connection);
                            ((GeneratedKeyHolder) i.getArgument(1))
                                    .getKeyList()
                                    .add(Map.of("ARREAR_EVENT_ID", 77L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));

        long id =
                new ArrearEvents(
                                jdbc,
                                cycles,
                                Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneOffset.UTC))
                        .raise(
                                5L,
                                4L,
                                YearMonth.of(2026, 7),
                                YearMonth.of(2026, 9),
                                YearMonth.of(2026, 10));

        assertThat(id).isEqualTo(77L);
        verify(ps).setString(10, "system");
    }

    @Test
    void SRS_8_15_aRangeOverlappingAnOpenEventOfTheTypeAndUnitIsRefused() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CycleRuns cycles = mock(CycleRuns.class);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(cycles.cycleUnit(4L)).thenReturn(unit);
        when(jdbc.queryForList(
                        anyString(), eq(Long.class), eq("HRA_REVISION"), eq(300L), any(), any()))
                .thenReturn(java.util.List.of(41L));
        ArrearEvents events =
                new ArrearEvents(
                        jdbc,
                        cycles,
                        Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneOffset.UTC));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                events.raise(
                                        5L,
                                        4L,
                                        YearMonth.of(2026, 7),
                                        YearMonth.of(2026, 9),
                                        YearMonth.of(2026, 10),
                                        "HRA_REVISION",
                                        "HRA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SRS-8-15")
                .hasMessageContaining("41");
        // US-GAP-252: the unit's row is locked before the overlap is checked, so two concurrent
        // runs of the unit never both pass the check.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(jdbc);
        order.verify(jdbc)
                .queryForList(org.mockito.ArgumentMatchers.contains("FOR UPDATE"), eq(300L));
        order.verify(jdbc)
                .queryForList(
                        anyString(), eq(Long.class), eq("HRA_REVISION"), eq(300L), any(), any());
    }
}
