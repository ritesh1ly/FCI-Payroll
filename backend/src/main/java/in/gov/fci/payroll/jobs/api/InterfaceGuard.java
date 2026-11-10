package in.gov.fci.payroll.jobs.api;

import java.util.concurrent.Callable;

/**
 * How a scheduled job reaches an external system (plan 23 phase 23a; SRS-INT-6, SRS-INT-16,
 * SRS-INT-32): implemented by the integration module, which applies the interface's settings in
 * force (refusing while it is not configured), its breaker, time limit and retries, and records the
 * exchange window. Declared here so the jobs module needs no dependency on the integration module.
 */
public interface InterfaceGuard {

    /** Calls through the interface; refused (an exception) while not configured or paused. */
    <T> T call(String interfaceCode, Callable<T> call);

    /** Records a completed window by its counts; one that does not balance is FAILED. */
    void window(
            String interfaceCode,
            String batchReference,
            long received,
            long accepted,
            long rejected,
            String detail);
}
