package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.increment.business.IncrementRules.Flag;
import in.gov.fci.payroll.penalty.api.IncrementStops;
import in.gov.fci.payroll.penalty.api.PenaltyPayEffects;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * The Basic effects of a penalty order (SRS-14-13, SRS-14-17, SRS-14-8, SRS-13-23, SRS-13-26,
 * SC-17-0044): the restored increment comes from the original due date, on the Basic of that date,
 * through the run's own record; what cannot be restored is reported, never guessed. Today is held
 * at 2026-11-07 (IST) so a due date reads the same on any day.
 */
class PenaltyBasicEffectsTest {

    private static final LocalDate DUE = LocalDate.of(2026, 7, 1);

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final IncrementRunService run = mock(IncrementRunService.class);
    private final BasicPayService basic = mock(BasicPayService.class);
    private final IncrementStops stops = mock(IncrementStops.class);
    private final Scales scales = mock(Scales.class);
    private final PenaltyBasicEffects effects =
            new PenaltyBasicEffects(
                    named,
                    run,
                    basic,
                    stops,
                    scales,
                    Clock.fixed(Instant.parse("2026-11-07T05:00:00Z"), ZoneOffset.UTC));

    private void member(String labour, LocalDate due) {
        Map<String, Object> m = new HashMap<>();
        m.put("EMP_LBR_FLAG", labour);
        m.put("LOCATION_ID", 5L);
        m.put("CURRENT_BASIC", new BigDecimal("50000"));
        m.put("NEXT_DUE_DATE", due == null ? null : Date.valueOf(due));
        when(named.queryForMap(contains("FROM employee e"), any(SqlParameterSource.class)))
                .thenReturn(m);
    }

    private void proposal(List<Flag> flags) {
        when(run.propose(anyLong(), any(), any(), any()))
                .thenReturn(
                        new IncrementRunService.Proposal(
                                new BigDecimal("51500"), flags, List.of(), "ANNUAL", null, null));
        when(run.record(
                        anyLong(),
                        any(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(70L);
    }

    private void later(long rows) {
        when(named.queryForObject(
                        contains("effective_from > :day"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(rows);
    }

    private static PenaltyPayEffects.Waived waived(LocalDate from, LocalDate to) {
        return new PenaltyPayEffects.Waived(9L, "PEN-1", "REV/1", from, to, 44L, "ddo@x");
    }

    @Test
    void SRS_14_17_theStoppedIncrementIsGivenFromItsOriginalDueDate() {
        member("N", DUE);
        proposal(List.of());
        when(stops.stopping(9L, DUE)).thenReturn(List.of());
        when(basic.basicOn(9L, DUE)).thenReturn(new BigDecimal("50000"));
        later(0);

        PenaltyPayEffects.Restored done =
                effects.restoreIncrement(waived(LocalDate.of(2026, 1, 1), null));

        verify(run)
                .record(
                        eq(9L),
                        eq(DUE),
                        eq("2026-27"),
                        eq("ANNUAL"),
                        eq(new BigDecimal("50000")),
                        eq(new BigDecimal("51500")),
                        eq(44L),
                        eq("ddo@x"),
                        eq("INCREMENT"),
                        contains("PEN-1"));
        assertThat(done).isEqualTo(new PenaltyPayEffects.Restored(70L, 1, null));
    }

    @Test
    void SRS_14_17_aStopOverTwoDueDatesRestoresEachWithTheBasicOfItsDate() {
        LocalDate earlier = LocalDate.of(2025, 7, 1);
        member("N", earlier);
        proposal(List.of());
        when(stops.stopping(anyLong(), any())).thenReturn(List.of());
        later(0);
        when(basic.basicOn(9L, earlier)).thenReturn(null);
        when(basic.basicOn(9L, DUE)).thenReturn(new BigDecimal("51500"));

        PenaltyPayEffects.Restored done =
                effects.restoreIncrement(waived(LocalDate.of(2025, 1, 1), null));

        assertThat(done.count()).isEqualTo(2);
        assertThat(done.incrementId()).isEqualTo(70L);
        // No history on the first date: the current Basic stands in.
        verify(run)
                .record(
                        eq(9L),
                        eq(earlier),
                        eq("2025-26"),
                        any(),
                        eq(new BigDecimal("50000")),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
        verify(run)
                .record(
                        eq(9L),
                        eq(DUE),
                        eq("2026-27"),
                        any(),
                        eq(new BigDecimal("51500")),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void SRS_14_17_aDueDateOutsideTheWaiverOrInALaterMonthStaysForTheRun() {
        member("N", DUE);
        assertThat(effects.restoreIncrement(waived(LocalDate.of(2026, 8, 1), null)).count())
                .isZero();
        assertThat(
                        effects.restoreIncrement(
                                        waived(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)))
                                .count())
                .isZero();
        member("N", LocalDate.of(2026, 12, 1));
        PenaltyPayEffects.Restored future =
                effects.restoreIncrement(waived(LocalDate.of(2026, 1, 1), null));
        assertThat(future).isEqualTo(new PenaltyPayEffects.Restored(null, 0, null));
        verify(run, never())
                .record(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void SRS_14_17_anotherStopInForceKeepsTheIncrementStopped() {
        member("N", DUE);
        when(stops.stopping(9L, DUE)).thenReturn(List.of("PEN-2"));

        assertThat(effects.restoreIncrement(waived(LocalDate.of(2026, 1, 1), null)).unresolved())
                .contains("PEN-2");
    }

    @Test
    void SRS_13_23_aFlaggedMemberIsReportedNotIncremented() {
        member("N", DUE);
        proposal(List.of(Flag.NOT_A_STAGE));
        when(stops.stopping(9L, DUE)).thenReturn(List.of());

        PenaltyPayEffects.Restored done =
                effects.restoreIncrement(waived(LocalDate.of(2026, 1, 1), null));

        assertThat(done.unresolved()).contains("NOT_A_STAGE");
        assertThat(done.count()).isZero();
    }

    @Test
    void SRS_13_5_anIncrementAlreadyRecordedIsNotGivenTwice() {
        member("N", DUE);
        proposal(List.of());
        when(stops.stopping(9L, DUE)).thenReturn(List.of());
        when(run.exists(9L, "2026-27", "ANNUAL")).thenReturn(true);

        assertThat(effects.restoreIncrement(waived(LocalDate.of(2026, 1, 1), null)).unresolved())
                .contains("2026-27");
    }

    @Test
    void SRS_14_17_aStagnationMemberKeepsTheStagnationEvent() {
        member("N", DUE);
        proposal(List.of(Flag.STAGNATION));
        when(stops.stopping(9L, DUE)).thenReturn(List.of());
        later(0);

        effects.restoreIncrement(waived(LocalDate.of(2026, 1, 1), null));

        verify(run, times(1))
                .record(
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq("STAGNATION_INCREMENT"),
                        any());
    }

    @Test
    void SRS_14_17_laterBasicEventsStandAndGoToX05() {
        member("N", DUE);
        proposal(List.of());
        when(stops.stopping(9L, DUE)).thenReturn(List.of());
        later(1);

        assertThat(effects.restoreIncrement(waived(LocalDate.of(2026, 1, 1), null)).unresolved())
                .contains("refix them on I-05");
    }

    @Test
    void SRS_2_54_aLabourersIncrementIsNotRestoredBeforeTheLabourRules() {
        member("Y", DUE);

        PenaltyPayEffects.Restored done =
                effects.restoreIncrement(waived(LocalDate.of(2026, 1, 1), null));

        assertThat(done.unresolved()).contains("OQ-9");
        verify(stops, never()).stopping(anyLong(), any());
    }

    @Test
    void SRS_14_17_noDueDateIsSaidNotAssumed() {
        member("N", null);

        assertThat(effects.restoreIncrement(waived(LocalDate.of(2026, 1, 1), null)).unresolved())
                .contains("no increment due date");
    }

    @Test
    void SRS_14_8_aReducedBasicMustBeBelowTheBasicAndOnAStage() {
        member("N", DUE);
        when(basic.basicOn(9L, DUE)).thenReturn(new BigDecimal("50000"));
        when(scales.ofMember(9L, DUE)).thenReturn(Optional.empty());

        assertThat(effects.reductionDefect(9L, DUE, new BigDecimal("50000"))).contains("not below");
        assertThat(effects.reductionDefect(9L, DUE, new BigDecimal("-1"))).contains("not below");
        assertThat(effects.reductionDefect(9L, DUE, new BigDecimal("48000"))).isNull();

        Scales.Scale noStages = scale(List.of());
        when(scales.ofMember(9L, DUE)).thenReturn(Optional.of(noStages));
        assertThat(effects.reductionDefect(9L, DUE, new BigDecimal("48000"))).isNull();

        Scales.Scale staged = scale(List.of(new BigDecimal("47000"), new BigDecimal("49000")));
        when(scales.ofMember(9L, DUE)).thenReturn(Optional.of(staged));
        assertThat(effects.reductionDefect(9L, DUE, new BigDecimal("48000"))).contains("stage");
        assertThat(effects.reductionDefect(9L, DUE, new BigDecimal("47000"))).isNull();
    }

    @Test
    void SRS_14_8_noBasicAtAllIsSaid() {
        Map<String, Object> m = new HashMap<>();
        m.put("CURRENT_BASIC", null);
        when(named.queryForMap(contains("FROM employee e"), any(SqlParameterSource.class)))
                .thenReturn(m);

        assertThat(effects.reductionDefect(9L, DUE, BigDecimal.TEN)).contains("no Basic");
    }

    private static Scales.Scale scale(List<BigDecimal> stages) {
        return new Scales.Scale(1L, "E-2", 2L, null, null, null, null, stages);
    }

    @Test
    void SRS_13_26_theReductionIsABasicEventWithItsOrderAndSource() {
        effects.reduceBasic(
                new PenaltyPayEffects.Reduction(
                        9L,
                        "PEN-1",
                        "VIG/44",
                        DUE,
                        new BigDecimal("48000"),
                        "HRMS",
                        "HR-1",
                        7L,
                        44L,
                        "ddo@x"));

        ArgumentCaptor<BasicPayService.Change> c =
                ArgumentCaptor.forClass(BasicPayService.Change.class);
        verify(basic).apply(c.capture());
        assertThat(c.getValue().eventType()).isEqualTo(PenaltyBasicEffects.REDUCTION);
        assertThat(c.getValue().orderReference()).isEqualTo("VIG/44");
        assertThat(c.getValue().sourceCode()).isEqualTo("HRMS");
        assertThat(c.getValue().retroModule()).isEqualTo("PENALTY");
        assertThat(c.getValue().retroKey()).isEqualTo("PEN-1");
    }

    @Test
    void SRS_14_17_aRevokedReductionPutsTheBasicBeforeItBack() {
        when(named.queryForList(contains("event_type = :type"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "OLD_BASIC_AMOUNT",
                                        new BigDecimal("50000"),
                                        "EFFECTIVE_FROM",
                                        Date.valueOf(DUE))));
        later(0);

        assertThat(effects.restoreBasic(waived(DUE, null), "VIG/44")).isNull();

        ArgumentCaptor<BasicPayService.Change> c =
                ArgumentCaptor.forClass(BasicPayService.Change.class);
        verify(basic).apply(c.capture());
        assertThat(c.getValue().basic()).isEqualByComparingTo("50000");
        assertThat(c.getValue().effectiveFrom()).isEqualTo(DUE);
        assertThat(c.getValue().eventType()).isEqualTo(PenaltyBasicEffects.RESTORATION);
    }

    @Test
    void SRS_14_17_aReductionThatNeverWroteABasicHasNothingToRestore() {
        when(named.queryForList(contains("event_type = :type"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of());

        assertThat(effects.restoreBasic(waived(DUE, null), "VIG/44")).isNull();

        verify(basic, never()).apply(any());
    }
}
