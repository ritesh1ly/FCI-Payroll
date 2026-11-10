package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SRS D-01 Computation Status by Payroll Type (plan 05 phase 05i): the STAFF engine state of the
 * scope rolled up over its instances.
 */
class CycleStatusServiceTest {

    /** Slots: 0 members, 1 not started, 2 running, 3 complete, 4 partial, 5 failed. */
    private static long[] states(
            long notStarted, long running, long complete, long partial, long failed) {
        return new long[] {0, notStarted, running, complete, partial, failed};
    }

    @Test
    void SRS_15_14_theMostUrgentStateWinsAndAMixOfDoneAndNotStartedIsPartial() {
        assertThat(CycleStatusService.engineStatus(null)).isEqualTo("NOT_STARTED");
        assertThat(CycleStatusService.engineStatus(states(1, 1, 1, 1, 1))).isEqualTo("FAILED");
        assertThat(CycleStatusService.engineStatus(states(1, 1, 1, 1, 0))).isEqualTo("PARTIAL");
        assertThat(CycleStatusService.engineStatus(states(1, 1, 1, 0, 0))).isEqualTo("RUNNING");
        assertThat(CycleStatusService.engineStatus(states(1, 0, 1, 0, 0))).isEqualTo("PARTIAL");
        assertThat(CycleStatusService.engineStatus(states(0, 0, 2, 0, 0))).isEqualTo("COMPLETE");
        assertThat(CycleStatusService.engineStatus(states(2, 0, 0, 0, 0))).isEqualTo("NOT_STARTED");
    }

    @Test
    void SRS_15_14_eachEngineStateHasItsSlot() {
        assertThat(CycleStatusService.stateSlot("NOT_STARTED")).isEqualTo(1);
        assertThat(CycleStatusService.stateSlot("RUNNING")).isEqualTo(2);
        assertThat(CycleStatusService.stateSlot("COMPLETE")).isEqualTo(3);
        assertThat(CycleStatusService.stateSlot("PARTIAL")).isEqualTo(4);
        assertThat(CycleStatusService.stateSlot("FAILED")).isEqualTo(5);
        assertThat(CycleStatusService.stateSlot("ANY_OTHER")).isEqualTo(1);
    }
}
