package in.gov.fci.payroll.integration.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The exchange envelope (SRS-INT-11, SRS-INT-12, SRS-INT-31; plan 23 D23-4 … D23-6): a blank
 * "reprocess of" names no earlier record; a rejection without the adapter's reason records that
 * none was given; a completed window or a record no longer RECEIVED takes nothing more; a staged
 * record outside any window counts in none.
 */
class IntakeServiceTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final IntegrationStore store = mock(IntegrationStore.class);
    private final FailureAlerts alerts = mock(FailureAlerts.class);
    private final IntakeService service =
            new IntakeService(
                    store,
                    alerts,
                    Clock.fixed(Instant.parse("2026-10-29T06:00:00Z"), ZoneOffset.UTC));

    IntakeServiceTest() {
        when(store.jdbc()).thenReturn(db.jdbc);
        when(store.insert(anyString(), anyString(), any())).thenReturn(51L);
        when(store.interfaceOf(anyString()))
                .thenReturn(new IntegrationStore.Registered(4L, "I-HRMS", "HRMS", "INBOUND"));
    }

    private void run(Object completedAt) {
        db.rows(
                "FROM interface_run r JOIN",
                row(
                        "run_id", 3L,
                        "interface_id", 4L,
                        "batch_reference", "B-1",
                        "received_count", 0L,
                        "accepted_count", 0L,
                        "rejected_count", 0L,
                        "completed_at", completedAt,
                        "interface_code", "I-HRMS",
                        "counterparty_system", "HRMS"));
    }

    private void staging(String status, Long runId) {
        db.rows(
                "FROM intf_staging_txn s JOIN",
                row(
                        "staging_id",
                        51L,
                        "run_id",
                        runId,
                        "staging_status",
                        status,
                        "payload",
                        "{}",
                        "interface_code",
                        "I-HRMS"));
    }

    @Test
    void SRS_INT_31_aBlankReprocessOfNamesNoEarlierRecord() {
        run(null);
        InterfaceIntake.Staged staged =
                service.stage(
                        3L, new InterfaceIntake.Inbound("EMP_CHANGE", "R-1", null, 7L, "{}", " "));
        assertThat(staged.outcome()).isEqualTo(InterfaceIntake.Outcome.STAGED);
        assertThat(db.updates("SET staging_status = 'REPROCESSED'")).isEmpty();
    }

    @Test
    void SRS_INT_12_aRejectionWithoutTheAdaptersReasonRecordsThatNoneWasGiven() {
        staging("RECEIVED", 3L);
        service.rejected(51L, null);
        service.rejected(51L, "  ");
        verify(alerts, times(2))
                .raise(any(), eq(3L), eq(51L), eq("{}"), eq("No reason given by the adapter"));
    }

    @Test
    void SRS_INT_11_aCompletedWindowOrAnUnknownOneTakesNothingMore() {
        run(java.sql.Timestamp.from(Instant.parse("2026-10-29T05:00:00Z")));
        assertThatThrownBy(() -> service.complete(3L, null)).isInstanceOf(FciException.class);
        db.rows("FROM interface_run r JOIN");
        assertThatThrownBy(() -> service.complete(3L, null)).isInstanceOf(FciException.class);
    }

    @Test
    void SRS_INT_12_aRecordNoLongerReceivedTakesNoOutcomeAndOneOutsideAWindowCountsInNone() {
        staging("APPLIED", 3L);
        assertThatThrownBy(() -> service.applied(51L, "employee", "7"))
                .isInstanceOf(FciException.class);
        staging("RECEIVED", null);
        service.applied(51L, "employee", "7");
        assertThat(db.updates("SET accepted_count = accepted_count + 1")).isEmpty();
        verify(store).insert(eq("intf_staging_event"), anyString(), any());
        verify(alerts, times(0)).raise(any(), anyLong(), any(), any(), anyString());
    }

    // BMAD TR-060 (SRS-INT-32 l.5155, SRS-INT-17 l.5222): a window that does not balance is
    // Failed and the Integration Failure is raised at once, window-level (no staged record).
    @Test
    void SRS_INT_32_anUnbalancedWindowRaisesTheIntegrationFailureAtOnce() {
        run(null);
        InterfaceIntake.Completed done = service.completeCounts(3L, 10, 7, 2, "pull");
        assertThat(done.status()).isEqualTo("FAILED");
        verify(alerts)
                .raise(
                        any(),
                        eq(3L),
                        isNull(),
                        isNull(),
                        eq(
                                "Received 10 is not accepted 7 + rejected 2: records lost in the"
                                        + " window"));
    }

    // BMAD TR-062: accepted + rejected above received (records counted twice) is Failed too.
    @Test
    void SRS_INT_32_anOverCountedWindowIsFailedAndRaised() {
        run(null);
        assertThat(service.completeCounts(3L, 3, 3, 1, "pull").status()).isEqualTo("FAILED");
        verify(alerts).raise(any(), eq(3L), isNull(), isNull(), contains("Received 3"));
    }

    // BMAD TR-060: a balanced window (partly rejected or not), and an empty one, raise nothing.
    @Test
    void SRS_INT_32_aBalancedOrEmptyWindowRaisesNothing() {
        run(null);
        assertThat(service.completeCounts(3L, 10, 8, 2, "pull").status()).isEqualTo("PARTIAL");
        assertThat(service.completeCounts(3L, 0, 0, 0, "pull").status()).isEqualTo("OK");
        verify(alerts, never()).raise(any(), anyLong(), any(), any(), anyString());
    }
}
