package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.payroll.api.MemberComputationBlocked;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.business.PayDataException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * UC-01 alternate flow, SRS-G-9 (US-GAP-1): a member the Actual run skips is published for the X-05
 * worklist with where its value is corrected when the rule knows it; a General run, or a skip
 * without a member, publishes nothing.
 */
class BlockedMemberListenerTest {

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private static CycleRuns.OpenRun run(CycleRuns.RunType type) {
        return new CycleRuns.OpenRun(7L, 70L, 1L, "202610", LocalDate.of(2026, 10, 1), type);
    }

    private static MemberReader.MemberRow member(long id) {
        return new MemberReader.MemberRow(
                id, 1L, BigDecimal.TEN, null, "X", "ACTIVE", null, null, null, null);
    }

    @Test
    void SRS_G_9_anActualRunSkipIsPublishedWithWhereTheValueIsCorrected() {
        BlockedMemberListener listener =
                new BlockedMemberListener(run(CycleRuns.RunType.ACTUAL), events);
        listener.onSkipInProcess(
                member(5L),
                new MemberBlockedException(
                        new PayDataException("HRA rate not seeded", PayDataException.RATE_SET)));
        ArgumentCaptor<MemberComputationBlocked> event =
                ArgumentCaptor.forClass(MemberComputationBlocked.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue())
                .isEqualTo(
                        new MemberComputationBlocked(
                                70L,
                                5L,
                                "HRA rate not seeded",
                                "rate_set_line",
                                "rate_value",
                                "RULE",
                                "M-04"));
    }

    @Test
    void SRS_G_9_aSkipWhoseFieldIsUnknownNamesNoFieldNorSource() {
        BlockedMemberListener listener =
                new BlockedMemberListener(run(CycleRuns.RunType.ACTUAL), events);
        listener.onSkipInProcess(
                member(6L), new MemberBlockedException(new PayDataException("No pay pattern")));
        listener.onSkipInProcess(member(8L), new IllegalStateException("other"));
        ArgumentCaptor<MemberComputationBlocked> event =
                ArgumentCaptor.forClass(MemberComputationBlocked.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(event.capture());
        assertThat(event.getAllValues())
                .extracting(MemberComputationBlocked::fieldSource)
                .containsOnlyNulls();
        assertThat(event.getAllValues().get(1).detail()).isEqualTo("other");
    }

    @Test
    void SRS_G_9_aGeneralRunOrASkipWithoutAMemberRaisesNothing() {
        new BlockedMemberListener(run(CycleRuns.RunType.SIMULATION), events)
                .onSkipInProcess(member(5L), new IllegalStateException("x"));
        new BlockedMemberListener(run(CycleRuns.RunType.ACTUAL), events)
                .onSkipInProcess(null, new IllegalStateException("x"));
        verify(events, never()).publishEvent(any());
    }
}
