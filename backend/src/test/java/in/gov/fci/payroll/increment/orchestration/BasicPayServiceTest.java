package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * The one Basic writer (plan 17; SRS-13-1, SRS-13-4, SRS-13-26; D17-1): every change is a history
 * event; the Basic in force this month is updated unless a later event already takes over this
 * month; a change before this month raises the one retro event (a pay revision as WAGE_REVISION,
 * anything else as PROMOTION); a future change and a change without a retro key raise none.
 */
class BasicPayServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final RetroEvents retro = mock(RetroEvents.class);
    private final BasicPayService service =
            new BasicPayService(
                    named,
                    audit,
                    retro,
                    Clock.fixed(Instant.parse("2026-10-15T05:00:00Z"), ZoneOffset.UTC));

    private void member(BigDecimal current, BigDecimal historyBasic, LocalDate nextStart, Long id) {
        Map<String, Object> e = new HashMap<>();
        e.put("CURRENT_BASIC", current);
        e.put("BAND_ID", 4L);
        when(named.queryForMap(
                        contains("SELECT current_basic, band_id"), any(SqlParameterSource.class)))
                .thenReturn(e);
        when(named.queryForList(
                        contains("SELECT basic_amount FROM employee_basic_history"),
                        any(SqlParameterSource.class),
                        eq(BigDecimal.class)))
                .thenReturn(historyBasic == null ? List.of() : List.of(historyBasic));
        List<Date> next =
                new ArrayList<>(
                        Collections.singletonList(
                                nextStart == null ? null : Date.valueOf(nextStart)));
        when(named.queryForList(
                        contains("SELECT MIN(effective_from)"),
                        any(SqlParameterSource.class),
                        eq(Date.class)))
                .thenReturn(next);
        when(named.queryForObject(
                        contains("COALESCE(MAX(event_sequence)"),
                        any(SqlParameterSource.class),
                        eq(Integer.class)))
                .thenReturn(null);
        when(named.queryForObject(
                        contains("SELECT history_id"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(id);
    }

    private static BasicPayService.Change change(LocalDate from, String module, String key) {
        return new BasicPayService.Change(
                7L,
                new BigDecimal("64300"),
                from,
                "PAY_REVISION",
                "CALC",
                "x".repeat(600),
                "WR-1",
                null,
                null,
                9L,
                "ra@x",
                module,
                "wage_revision_member",
                key);
    }

    // BR-INCR-002: the old Basic stays on the history; BR-INCR-005: a past revision raises its
    // arrear.
    @Test
    void SRS_13_4_aPastRevisionIsAHistoryEventSetsTheBasicAndRaisesTheWageRevisionArrear() {
        member(new BigDecimal("60000"), null, null, null);
        when(retro.raise(any())).thenReturn(70L);
        BasicPayService.Applied a =
                service.apply(change(LocalDate.of(2026, 7, 1), "WAGE_REVISION", "11"));
        assertThat(a.historyId()).as("no id read back").isEqualTo(-1);
        assertThat(a.oldBasic())
                .as("no history yet: the current Basic")
                .isEqualByComparingTo("60000");
        assertThat(a.current()).isTrue();
        assertThat(a.arrearEventId()).isEqualTo(70L);
        ArgumentCaptor<RetroEvents.Raise> raised = ArgumentCaptor.forClass(RetroEvents.Raise.class);
        verify(retro).raise(raised.capture());
        assertThat(raised.getValue().eventCode()).isEqualTo("WAGE_REVISION");
        // Oracle review F5: the row version is bumped with the Basic, so a JPA save of the member
        // that read the old Basic fails its version check instead of writing it back.
        verify(named)
                .update(
                        contains(
                                "UPDATE employee SET current_basic = :basic, row_version ="
                                        + " row_version + 1 WHERE employee_id = :employee"),
                        any(SqlParameterSource.class));
    }

    @Test
    void SRS_13_26_aLaterEventInForceThisMonthKeepsTheBasicAndAPromotionIsItsOwnEvent() {
        member(null, new BigDecimal("62400"), LocalDate.of(2026, 10, 1), 3L);
        BasicPayService.Applied a =
                service.apply(change(LocalDate.of(2026, 8, 1), "INCREMENT", "5"));
        assertThat(a.oldBasic()).isEqualByComparingTo("62400");
        verify(named, never())
                .update(
                        contains("UPDATE employee SET current_basic"),
                        any(SqlParameterSource.class));
        ArgumentCaptor<RetroEvents.Raise> raised = ArgumentCaptor.forClass(RetroEvents.Raise.class);
        verify(retro).raise(raised.capture());
        assertThat(raised.getValue().eventCode()).isEqualTo("PROMOTION");
    }

    @Test
    void SRS_13_1_aFutureChangeIsHistoryOnlyAndAChangeWithoutARetroKeyRaisesNoArrear() {
        member(null, null, LocalDate.of(2027, 3, 1), 4L);
        BasicPayService.Applied future =
                service.apply(change(LocalDate.of(2027, 1, 1), "INCREMENT", "6"));
        assertThat(future.current()).isFalse();
        assertThat(future.oldBasic()).isNull();
        member(new BigDecimal("60000"), null, LocalDate.of(2026, 12, 1), 5L);
        BasicPayService.Applied noKey =
                service.apply(change(LocalDate.of(2026, 7, 1), "INCREMENT", null));
        assertThat(noKey.arrearEventId()).isNull();
        verify(named)
                .update(
                        contains("UPDATE employee SET current_basic"),
                        any(SqlParameterSource.class));
        verify(retro, never()).raise(any());
    }
}
