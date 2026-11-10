package in.gov.fci.payroll.payroll.batch.computation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.payroll.api.MemberPaymentHeld;
import in.gov.fci.payroll.payroll.api.MembersPaymentReleased;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.business.PayRules;
import in.gov.fci.payroll.payroll.orchestration.CycleLeases;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * The result writer's payment hold (plan 16 phase 16d; SRS-1-41): a held member of an actual run
 * raises the X-05 exception through {@link MemberPaymentHeld}; a member no longer held is released
 * ({@link MembersPaymentReleased}, once per chunk); a simulation, raises none.
 */
class ResultWriterTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneId.of("UTC"));

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CycleLeases leases = mock(CycleLeases.class);

    ResultWriterTest() {
        doAnswer(
                        i -> {
                            ((GeneratedKeyHolder) i.getArgument(1))
                                    .getKeyList()
                                    .add(Map.of("PAYROLL_RESULT_ID", 9L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    private void write(CycleRuns.RunType type, String hold) throws Exception {
        CycleRuns.OpenRun run =
                new CycleRuns.OpenRun(1L, 2L, 300L, "202610", LocalDate.of(2026, 10, 1), type);
        MemberReader.MemberRow row =
                new MemberReader.MemberRow(
                        7L, 3L, BigDecimal.TEN, "IDA", "X", "ACTIVE", null, null, null, null);
        PayRules.Outcome outcome =
                new PayRules.Outcome(
                        "COMPUTED",
                        null,
                        List.of(),
                        BigDecimal.TEN,
                        BigDecimal.ZERO,
                        BigDecimal.TEN,
                        hold);
        new ResultWriter(jdbc, run, 5L, CLOCK, leases, events)
                .write(new Chunk<>(List.of(new ComputationProcessor.Computed(row, outcome))));
    }

    @Test
    void SRS_1_41_aHeldMemberOfAnActualRunRaisesThePaymentHold() throws Exception {
        write(CycleRuns.RunType.ACTUAL, "SALARY_WITHHELD");
        verify(events).publishEvent(new MemberPaymentHeld(2L, 7L, "SALARY_WITHHELD"));
    }

    @Test
    void SRS_1_41_aSimulationRaisesNothing() throws Exception {
        write(CycleRuns.RunType.SIMULATION, "SALARY_WITHHELD");
        write(CycleRuns.RunType.SIMULATION, null);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void SRS_1_41_anActualRunReleasesAMemberNoLongerHeldOncePerChunk() throws Exception {
        write(CycleRuns.RunType.ACTUAL, null);
        verify(events).publishEvent(new MembersPaymentReleased(2L, List.of(7L)));
        verify(events, never()).publishEvent(any(MemberPaymentHeld.class));
    }

    /**
     * SRS-15-28 / BR-CYC-015 (BMAD TR-076): every chunk the run writes renews its cycle lease
     * (heartbeat), so a long run's lease never expires under it and no freeze, un-freeze or lock
     * takes the instance mid-run.
     */
    @Test
    void SRS_15_28_everyChunkWrittenRenewsTheRunsCycleLease() throws Exception {
        write(CycleRuns.RunType.ACTUAL, null);
        write(CycleRuns.RunType.SIMULATION, null);
        verify(leases, org.mockito.Mockito.times(2)).heartbeatRun(1L);
    }
}
