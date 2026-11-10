package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.integration.business.IntegrationRules;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * The Integration Failure raised at once (SRS-INT-17, SRS-INT-31, SRS-INT-32; plan 23 D23-6): an
 * item for the Administrator in the exception queue ({@code interface_exception}, shown on X-03 and
 * in the Integration Failure Report) and an X-05 entry naming the interface, kept to one open entry
 * per interface (the worklist does not duplicate an unsettled condition). The entry is a system
 * condition resolved on X-03 by the Administrator (field source SYS), not an interface value routed
 * upstream; it is cleared once no item of the interface is open.
 */
@Component
class FailureAlerts {

    static final String CODE = "INTEGRATION_FAILURE";
    static final String SYSTEM = "system.integration";

    private final IntegrationStore store;
    private final ExceptionWorklist worklist;
    private final Clock clock;

    FailureAlerts(IntegrationStore store, ExceptionWorklist worklist, Clock clock) {
        this.store = store;
        this.worklist = worklist;
        this.clock = clock;
    }

    /**
     * Queues the item for the Administrator and raises the failure.
     *
     * @param stagingId the staged record, or null for a window- or call-level failure
     * @param rejectedRecord the data as received, or null
     * @return the exception id
     */
    long raise(
            IntegrationStore.Registered intf,
            Long runId,
            Long stagingId,
            String rejectedRecord,
            String reason) {
        long id =
                store.insert(
                        "interface_exception",
                        "exception_id",
                        IntegrationStore.row(
                                "run_id",
                                runId,
                                "staging_id",
                                stagingId,
                                "interface_id",
                                intf.id(),
                                "rejected_record",
                                rejectedRecord,
                                "rejection_reason",
                                IntegrationRules.cut(reason, 2000),
                                "remediation_status",
                                "OPEN",
                                "raised_at",
                                Timestamp.from(clock.instant())));
        worklist.raise(
                SYSTEM,
                new ExceptionWorklist.NewEntry(
                        null,
                        null,
                        "X-03",
                        CODE,
                        "WARNING",
                        null,
                        null,
                        "interface_registry",
                        intf.code(),
                        "SYS",
                        "X-03",
                        IntegrationRules.cut("Interface " + intf.code() + ": " + reason, 1000)));
        return id;
    }
}
