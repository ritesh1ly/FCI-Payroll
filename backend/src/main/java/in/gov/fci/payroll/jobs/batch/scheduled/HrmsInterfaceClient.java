package in.gov.fci.payroll.jobs.batch.scheduled;

/**
 * The HRMS staging interface (INTF_PAY_*, X-03). The contract is open (OQ-8: retain the JSON
 * functions and staging tables, or a REST API; PENDING API-23-1), so the only implementation today
 * reports that no source is configured. A real client replaces it without touching the job.
 */
public interface HrmsInterfaceClient {

    /** Outcome of one pull: records received, accepted and rejected, and a one-line note. */
    record PullResult(long received, long accepted, long rejected, String note) {}

    PullResult pull(String payPeriod);
}
