package in.gov.fci.payroll.integration.orchestration;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A refused HRMS exchange is recorded where X-03 shows it (SRS-INT-4, SRS-INT-31, SRS-INT-32;
 * R-INT): an open item in the exception queue of the interface and its X-05 entry. The same refusal
 * is recorded once a minute at most, so a caller hammering with a wrong key cannot flood the queue.
 * Reasons name the cause, never a credential, a token or a record value.
 */
@Component
public class HrmsRefusals {

    /** Registry code that carries system authentication refusals. */
    public static final String AUTH_INTERFACE = "HRMS_API_AUTH";

    private static final long SAME_REFUSAL_SECONDS = 60;

    private final IntegrationStore store;
    private final FailureAlerts alerts;
    private final Clock clock;

    HrmsRefusals(IntegrationStore store, FailureAlerts alerts, Clock clock) {
        this.store = store;
        this.alerts = alerts;
        this.clock = clock;
    }

    /** Records the refusal under the interface unless the same one was recorded a moment ago. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String interfaceCode, String reason) {
        IntegrationStore.Registered intf = store.interfaceOf(interfaceCode);
        Integer recent =
                store.jdbc()
                        .queryForObject(
                                "SELECT COUNT(*) FROM interface_exception WHERE interface_id = ?"
                                        + " AND rejection_reason = ? AND raised_at > ?",
                                Integer.class,
                                intf.id(),
                                reason,
                                Timestamp.from(clock.instant().minusSeconds(SAME_REFUSAL_SECONDS)));
        if (recent == null || recent == 0) {
            alerts.raise(intf, null, null, null, reason);
        }
    }
}
