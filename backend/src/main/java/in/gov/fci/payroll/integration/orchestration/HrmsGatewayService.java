package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.HrmsGateway;
import in.gov.fci.payroll.integration.api.HrmsReply;
import in.gov.fci.payroll.integration.business.HrmsContractCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * {@link HrmsGateway}: checks a call against the Postman contract, sends it through {@link
 * HrmsClient} (URL setting, api-key, bearer token, pool, bulkhead, retry, audit window) and
 * classifies the answer (SRS-INT-1, SRS-INT-6; R-INT). Nothing of a body is logged.
 */
@Service
public class HrmsGatewayService implements HrmsGateway {

    private final HrmsClient client;
    private final HrmsContractCatalog contract;

    @Autowired
    public HrmsGatewayService(HrmsClient client) {
        this(client, HrmsContractCatalog.load());
    }

    HrmsGatewayService(HrmsClient client, HrmsContractCatalog contract) {
        this.client = client;
        this.contract = contract;
    }

    @Override
    public HrmsReply call(
            HrmsApi api, Map<String, String> query, JsonNode body, String idempotencyKey) {
        HrmsContractCatalog.Entry entry = contract.of(api.code()).orElse(null);
        if (entry == null) {
            return HrmsReply.invalid(List.of("API " + api.code() + " is not in the contract"));
        }
        if (!entry.method().equalsIgnoreCase(api.method())) {
            return HrmsReply.invalid(
                    List.of(
                            "the contract calls "
                                    + api.code()
                                    + " with "
                                    + entry.method()
                                    + ", not "
                                    + api.method()));
        }
        List<String> defects = entry.defects(query, body);
        if (!defects.isEmpty()) {
            return HrmsReply.invalid(defects);
        }
        HrmsClient.Response answered =
                client.send(api, query, body == null ? null : body.toString(), idempotencyKey);
        return HrmsReply.parse(answered.status(), answered.body(), answered.attempts());
    }

    @Override
    public HrmsReply welcome() {
        HrmsClient.Response answered = client.probe();
        return HrmsReply.parse(answered.status(), answered.body(), answered.attempts());
    }
}
