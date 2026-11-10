package in.gov.fci.payroll.jobs.batch.scheduled;

import in.gov.fci.payroll.jobs.api.InterfaceGuard;
import java.time.Clock;

/**
 * The nightly HRMS pull goes through the integration layer (plan 23 phase 23a; SRS-INT-6,
 * SRS-INT-16, SRS-INT-32; PENDING 01 "per-interface resilience settings"): the interface's settings
 * in force ({@code interface_config} of HRMS_BULK_RECONCILE) give the time limit, retries, back-off
 * and breaker threshold, and while it is not configured the pull is refused (the step fails with
 * INT-503-NOT-CONFIGURED, visible on C-13). Each pull writes its exchange envelope with the
 * received, accepted and rejected counts; a pull whose counts do not balance is a FAILED window on
 * X-03 and raises the Integration Failure. The failed job can be restarted from C-13 once the
 * interface is back.
 */
public class ResilientHrmsGateway {

    /** {@code interface_registry.interface_code} of the bulk reconciliation load. */
    static final String INTERFACE = "HRMS_BULK_RECONCILE";

    private final HrmsInterfaceClient client;
    private final InterfaceGuard guard;
    private final Clock clock;

    public ResilientHrmsGateway(HrmsInterfaceClient client, InterfaceGuard guard, Clock clock) {
        this.client = client;
        this.guard = guard;
        this.clock = clock;
    }

    /** Pulls through the interface's settings and records the window. */
    public HrmsInterfaceClient.PullResult pull(String payPeriod) {
        HrmsInterfaceClient.PullResult result = guard.call(INTERFACE, () -> client.pull(payPeriod));
        guard.window(
                INTERFACE,
                "HRMS-PULL-" + payPeriod + "-" + clock.millis(),
                result.received(),
                result.accepted(),
                result.rejected(),
                result.note());
        return result;
    }
}
