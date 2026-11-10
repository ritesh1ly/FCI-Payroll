package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.integration.api.InterfaceCalls;
import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.jobs.api.InterfaceGuard;
import java.time.Clock;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;

/**
 * The scheduled jobs' way through the integration layer (plan 23 phase 23a): a call under the
 * interface's settings in force and the window it exchanged, by its counts.
 */
@Component
public class JobsInterfaceGuard implements InterfaceGuard {

    private final InterfaceCalls calls;
    private final InterfaceIntake intake;
    private final Clock clock;

    public JobsInterfaceGuard(InterfaceCalls calls, InterfaceIntake intake, Clock clock) {
        this.calls = calls;
        this.intake = intake;
        this.clock = clock;
    }

    @Override
    public <T> T call(String interfaceCode, Callable<T> call) {
        return calls.call(interfaceCode, call);
    }

    @Override
    public void window(
            String interfaceCode,
            String batchReference,
            long received,
            long accepted,
            long rejected,
            String detail) {
        long run = intake.open(interfaceCode, batchReference, null, clock.instant());
        intake.completeCounts(run, received, accepted, rejected, detail);
    }
}
