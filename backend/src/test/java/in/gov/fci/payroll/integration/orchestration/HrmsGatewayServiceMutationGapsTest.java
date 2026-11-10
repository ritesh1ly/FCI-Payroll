package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.HrmsReply;
import in.gov.fci.payroll.integration.api.HrmsReply.Outcome;
import in.gov.fci.payroll.integration.business.HrmsContractCatalog;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-INT-1, SRS-INT-12: the gateway refuses, before anything is sent, an API the contract does not
 * list and an API whose HTTP method differs from the contract's.
 */
class HrmsGatewayServiceMutationGapsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HrmsClient client = mock(HrmsClient.class);

    @Test
    void SRS_INT_12_anApiThatIsNotInTheContractIsRefusedAndNeverSent() {
        HrmsContractCatalog empty = HrmsContractCatalog.parse(JSON.readTree("{\"paths\":{}}"));
        HrmsReply reply =
                new HrmsGatewayService(client, empty)
                        .status(HrmsApi.GET_OTA_STATUS, Map.of("request_id", "1"));
        assertThat(reply.outcome()).isEqualTo(Outcome.INVALID_REQUEST);
        assertThat(reply.problems())
                .containsExactly(
                        "API " + HrmsApi.GET_OTA_STATUS.code() + " is not in the contract");
        verifyNoInteractions(client);
    }

    @Test
    void SRS_INT_12_anApiWhoseMethodDiffersFromTheContractIsRefusedAndNeverSent() {
        HrmsContractCatalog wrongMethod =
                HrmsContractCatalog.parse(
                        JSON.readTree(
                                "{\"paths\":{\"/x\":{\"post\":{\"operationId\":\""
                                        + HrmsApi.GET_OTA_STATUS.code()
                                        + "\"}}}}"));
        HrmsReply reply =
                new HrmsGatewayService(client, wrongMethod)
                        .status(HrmsApi.GET_OTA_STATUS, Map.of("request_id", "1"));
        assertThat(reply.outcome()).isEqualTo(Outcome.INVALID_REQUEST);
        assertThat(reply.problems())
                .containsExactly(
                        "the contract calls "
                                + HrmsApi.GET_OTA_STATUS.code()
                                + " with POST, not "
                                + HrmsApi.GET_OTA_STATUS.method());
        verifyNoInteractions(client);
    }
}
