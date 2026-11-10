package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.integration.api.InterfaceAdapter;
import org.springframework.stereotype.Component;

/**
 * The adapter of the payment-status interface to HRMS (SRS-INT-33, SRS-INT-35): Test connection on
 * X-03 calls the HRMS base address through {@link HrmsClient} (pooled WebClient, its own bulkhead,
 * the interface's time limit and retries). The outcome is one line - the HTTP status class and the
 * attempts - never a credential or a body. The status push itself is {@link
 * HrmsClient#pushPaymentStatus}; a failed push is re-sent from its owner, so Reprocess is not
 * offered here.
 */
@Component
public class HrmsConnectionAdapter implements InterfaceAdapter {

    private final HrmsClient client;

    public HrmsConnectionAdapter(HrmsClient client) {
        this.client = client;
    }

    @Override
    public String interfaceCode() {
        return HrmsClient.PAYMENT_STATUS_INTERFACE;
    }

    @Override
    public String testConnection() {
        HrmsClient.Response answered = client.probe();
        return "HRMS answered HTTP "
                + answered.status()
                + " after "
                + answered.attempts()
                + " attempt(s); "
                + client.tokenCheck();
    }

    @Override
    public void reprocess(long stagingId, String txnType, String payload) {
        throw new UnsupportedOperationException(
                "a payment status push is re-sent by its owner, not reprocessed from staging");
    }
}
