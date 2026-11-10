package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.HrmsReply;
import in.gov.fci.payroll.integration.api.HrmsReply.Outcome;
import in.gov.fci.payroll.integration.business.HrmsContractCatalog;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-INT-1, SRS-INT-6, SRS-INT-12: the gateway checks a call against the contract before it is
 * sent and classifies the answer; a call that contradicts the contract never reaches HRMS.
 */
class HrmsGatewayServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HrmsClient client = mock(HrmsClient.class);
    private final HrmsGatewayService gateway =
            new HrmsGatewayService(client, HrmsContractCatalog.load());

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void SRS_INT_6_aConsistentCallIsSentAndItsAnswerClassified() {
        when(client.send(eq(HrmsApi.REQUEST_OTA), isNull(), anyString(), eq("K-1")))
                .thenReturn(
                        new HrmsClient.Response(
                                200, "{\"status\":\"success\",\"data\":{\"emp_ota_id\":9}}", 2));
        HrmsReply reply =
                gateway.submit(
                        HrmsApi.REQUEST_OTA,
                        null,
                        json("{\"request_id\":5,\"emp_num\":160662}"),
                        "K-1");
        assertThat(reply.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(reply.attempts()).isEqualTo(2);
        assertThat(reply.data().get("emp_ota_id").asInt()).isEqualTo(9);
        verify(client)
                .send(HrmsApi.REQUEST_OTA, null, "{\"request_id\":5,\"emp_num\":160662}", "K-1");
    }

    @Test
    void SRS_INT_12_aCallThatContradictsTheContractIsNeverSent() {
        HrmsReply unknownField =
                gateway.submit(HrmsApi.REQUEST_OTA, null, json("{\"zzz\":1}"), null);
        assertThat(unknownField.outcome()).isEqualTo(Outcome.INVALID_REQUEST);
        assertThat(unknownField.problems()).containsExactly("field 'zzz' is not in the contract");
        HrmsReply badQuery = gateway.status(HrmsApi.GET_OTA_STATUS, Map.of("nope", "1"));
        assertThat(badQuery.outcome()).isEqualTo(Outcome.INVALID_REQUEST);
        HrmsReply noBody = gateway.cancel(HrmsApi.CANCEL_OTA, null, null);
        assertThat(noBody.problems())
                .containsExactly("the contract carries a body and none is given");
        verifyNoInteractions(client);
    }

    @Test
    void SRS_INT_6_aStatusReadSendsNoBody() {
        when(client.send(eq(HrmsApi.GET_OTA_STATUS), any(), isNull(), isNull()))
                .thenReturn(
                        new HrmsClient.Response(
                                404,
                                "{\"status\":\"error\",\"error_code\":\"REQUEST_NOT_FOUND\"}",
                                1));
        HrmsReply reply =
                gateway.status(
                        HrmsApi.GET_OTA_STATUS, Map.of("request_id", "1", "emp_ota_id", "2"));
        assertThat(reply.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(reply.errorCode()).isEqualTo("REQUEST_NOT_FOUND");
    }

    @Test
    void SRS_INT_6_aCancelIsAPutWithItsBody() {
        when(client.send(eq(HrmsApi.CANCEL_OTA), isNull(), anyString(), eq("C-1")))
                .thenReturn(new HrmsClient.Response(200, "{\"status\":\"success\"}", 1));
        assertThat(
                        gateway.cancel(
                                        HrmsApi.CANCEL_OTA,
                                        json("{\"request_id\":1,\"emp_ota_id\":2}"),
                                        "C-1")
                                .accepted())
                .isTrue();
    }

    @Test
    void SRS_INT_6_aFailureOfTheClientPropagates() {
        when(client.send(any(), any(), any(), any())).thenThrow(new IllegalStateException("down"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> gateway.status(HrmsApi.GET_OTA_STATUS, Map.of("request_id", "1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void SRS_INT_33_welcomeClassifiesTheProbeAnswer() {
        when(client.probe())
                .thenReturn(
                        new HrmsClient.Response(
                                200, "{\"status\":\"success\",\"message\":\"Welcome\"}", 1));
        HrmsReply reply = gateway.welcome();
        assertThat(reply.accepted()).isTrue();
        assertThat(reply.message()).isEqualTo("Welcome");
        when(client.probe())
                .thenReturn(
                        new HrmsClient.Response(401, "{\"error_code\":\"API_KEY_MISSING\"}", 1));
        assertThat(gateway.welcome().errorCode()).isEqualTo("API_KEY_MISSING");
        verify(client, never()).send(any(), any(), any(), any());
    }

    @Test
    void SRS_INT_1_theDefaultConstructorLoadsTheContractOfTheClassPath() {
        HrmsGatewayService standard = new HrmsGatewayService(client);
        assertThat(standard.status(HrmsApi.GET_OTA_STATUS, Map.of("nope", "1")).outcome())
                .isEqualTo(Outcome.INVALID_REQUEST);
    }
}
