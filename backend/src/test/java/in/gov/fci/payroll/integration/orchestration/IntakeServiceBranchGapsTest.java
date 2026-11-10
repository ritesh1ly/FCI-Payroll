package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Branch gaps of the exchange envelope (SRS-INT-12): a staged record the store does not hold at all
 * takes no outcome, accepted or rejected, and nothing is written.
 */
class IntakeServiceBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final IntegrationStore store = mock(IntegrationStore.class);
    private final IntakeService service =
            new IntakeService(
                    store,
                    mock(FailureAlerts.class),
                    Clock.fixed(Instant.parse("2026-10-29T06:00:00Z"), ZoneOffset.UTC));

    IntakeServiceBranchGapsTest() {
        when(store.jdbc()).thenReturn(db.jdbc);
    }

    @Test
    void SRS_INT_12_anUnknownStagedRecordTakesNoOutcome() {
        assertThatThrownBy(() -> service.applied(404L, "employee", "7"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("STAGING"));
        assertThatThrownBy(() -> service.rejected(404L, "bad")).isInstanceOf(FciException.class);
        assertThat(db.updates("intf_staging_txn")).isEmpty();
    }
}
