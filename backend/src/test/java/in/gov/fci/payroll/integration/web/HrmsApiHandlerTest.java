package in.gov.fci.payroll.integration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import in.gov.fci.payroll.integration.orchestration.HrmsAccessService;
import in.gov.fci.payroll.integration.orchestration.HrmsInboundService;
import in.gov.fci.payroll.integration.orchestration.HrmsInboundService.Ack;
import in.gov.fci.payroll.integration.orchestration.HrmsRequestService;
import in.gov.fci.payroll.platform.error.FciException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * SRS-INT-1, SRS-INT-4, SRS-INT-12, SRS-INT-32, SRS-INT-35: the handler of the published HRMS
 * routes: unmatched route, token body, one or many acknowledgements, status, cancel, report not
 * enabled, content type and size of the body, repeated parameters.
 */
class HrmsApiHandlerTest {

    private final HrmsAccessService access = mock(HrmsAccessService.class);
    private final HrmsInboundService inbound = mock(HrmsInboundService.class);
    private final HrmsRequestService requests = mock(HrmsRequestService.class);
    private final HrmsApiHandler handler;

    HrmsApiHandlerTest() {
        Map<String, ApiUrl> urls = new HashMap<>();
        for (HrmsApi api : HrmsApi.values()) {
            urls.put(api.code(), new ApiUrl("hrms/" + api.code()));
        }
        handler =
                new HrmsApiHandler(
                        new HrmsApiRoutes(new HrmsApiProperties(null, urls)),
                        access,
                        inbound,
                        requests);
    }

    private static MockHttpServletRequest call(HrmsApi api, String json) {
        MockHttpServletRequest r = new MockHttpServletRequest(api.method(), "/hrms/" + api.code());
        r.setRequestURI("/hrms/" + api.code());
        if (json != null) {
            r.setContentType("application/json");
            r.setContent(json.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    private static Ack ack(int http, String id, String status, String reason, boolean dup) {
        return new Ack(http, id, "H-" + id, status, reason, dup);
    }

    @Test
    void SRS_INT_16_aCallToAPathNoApiIsPublishedAtIsNotFound() {
        var r = new MockHttpServletRequest("POST", "/hrms/nothing-here");
        r.setRequestURI("/hrms/nothing-here");
        assertThatThrownBy(() -> handler.handle(r)).isInstanceOf(FciException.class);
        var wrongMethod = new MockHttpServletRequest("GET", "/hrms/request_ota");
        wrongMethod.setRequestURI("/hrms/request_ota");
        assertThatThrownBy(() -> handler.handle(wrongMethod)).isInstanceOf(FciException.class);
    }

    @Test
    void SRS_INT_4_theTokenBodyTakesOnlyUsernameAndPasswordAsText() throws IOException {
        when(access.exchange("hrms-client", "pw-0123456789", "127.0.0.1"))
                .thenReturn(new HrmsAccessService.Token("tok", 900));
        ResponseEntity<Map<String, Object>> ok =
                handler.handle(
                        call(
                                HrmsApi.TOKEN,
                                "{\"username\":\"hrms-client\",\"password\":\"pw-0123456789\"}"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody())
                .containsEntry("access_token", "tok")
                .containsEntry("token_type", "Bearer")
                .containsEntry("expires_in", 900);
        assertThat(ok.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void SRS_INT_4_aTokenBodyWithNonTextOrMissingCredentialsPassesNullsToTheExchange()
            throws IOException {
        when(access.exchange(isNull(), isNull(), eq("127.0.0.1")))
                .thenReturn(new HrmsAccessService.Token("t", 1));
        handler.handle(call(HrmsApi.TOKEN, "{\"username\":123,\"password\":true}"));
        handler.handle(call(HrmsApi.TOKEN, "{}"));
        handler.handle(call(HrmsApi.TOKEN, "{\"username\":null,\"password\":[1]}"));
        verify(access, org.mockito.Mockito.times(3)).exchange(isNull(), isNull(), eq("127.0.0.1"));
    }

    @Test
    void SRS_INT_12_aTokenBodyWithAnExtraFieldOrNoJsonIsMalformedAndNeverExchanged() {
        for (String body :
                new String[] {
                    "{\"username\":\"a\",\"role\":\"ADMIN\"}",
                    "",
                    "  ",
                    "[",
                    "{\"username\":\"a\",\"username\":\"b\"}"
                }) {
            assertThatThrownBy(() -> handler.handle(call(HrmsApi.TOKEN, body)))
                    .as(body)
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getMessage())
                                            .isEqualTo(IntegrationErrorCode.HRMS_MALFORMED.code()));
        }
        verify(access, never()).exchange(any(), any(), any());
    }

    @Test
    void SRS_INT_32_oneRequestIsAnsweredWithItsOwnStatusAndAReasonOnlyWhenThereIsOne()
            throws IOException {
        when(inbound.intake(eq(HrmsApi.REQUEST_OTA), any(), any()))
                .thenReturn(
                        new HrmsInboundService.Result(
                                List.of(ack(202, "4516", "Received", null, false))))
                .thenReturn(
                        new HrmsInboundService.Result(
                                List.of(ack(422, "4517", "Rejected", "employee not found", true))));
        var first = handler.handle(call(HrmsApi.REQUEST_OTA, "{\"request_id\":4516}"));
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(first.getBody())
                .containsEntry("request_id", "4516")
                .containsEntry("duplicate", false)
                .doesNotContainKey("reason");
        var second = handler.handle(call(HrmsApi.REQUEST_OTA, "{\"request_id\":4517}"));
        assertThat(second.getStatusCode().value()).isEqualTo(422);
        assertThat(second.getBody())
                .containsEntry("reason", "employee not found")
                .containsEntry("duplicate", true);
    }

    @Test
    void SRS_INT_32_severalRequestsAreOneWindowOfReceivedAcceptedAndRejected() throws IOException {
        when(inbound.intake(eq(HrmsApi.POST_CAFETERIA_ALLOWANCE), any(), any()))
                .thenReturn(
                        new HrmsInboundService.Result(
                                List.of(
                                        ack(202, "1", "Received", null, false),
                                        ack(200, "2", "Accepted", null, false),
                                        ack(422, "3", "Rejected", "bad", false))));
        var out = handler.handle(call(HrmsApi.POST_CAFETERIA_ALLOWANCE, "[{},{},{}]"));
        assertThat(out.getStatusCode().value()).isEqualTo(207);
        assertThat(out.getBody())
                .containsEntry("received", 3)
                .containsEntry("accepted", 2L)
                .containsEntry("rejected", 1L);
        assertThat((List<?>) out.getBody().get("results")).hasSize(3);
    }

    @Test
    void SRS_INT_35_aStatusCallPassesItsQueryAndARepeatedParameterIsRefused() throws IOException {
        when(requests.status(eq(HrmsApi.GET_OTA_STATUS), any()))
                .thenReturn(Map.of("status", "Received"));
        MockHttpServletRequest r = call(HrmsApi.GET_OTA_STATUS, null);
        r.setParameter("request_id", "4516");
        assertThat(handler.handle(r).getBody()).containsEntry("status", "Received");
        verify(requests).status(HrmsApi.GET_OTA_STATUS, Map.of("request_id", "4516"));

        MockHttpServletRequest twice = call(HrmsApi.GET_OTA_STATUS, null);
        twice.setParameter("request_id", "1", "2");
        assertThatThrownBy(() -> handler.handle(twice)).isInstanceOf(FciException.class);

        MockHttpServletRequest longName = call(HrmsApi.GET_OTA_STATUS, null);
        longName.setParameter("x".repeat(41), "1", "2");
        assertThatThrownBy(() -> handler.handle(longName)).isInstanceOf(FciException.class);
        MockHttpServletRequest shortName = call(HrmsApi.GET_OTA_STATUS, null);
        shortName.setParameter("x".repeat(40), "1", "2");
        assertThatThrownBy(() -> handler.handle(shortName)).isInstanceOf(FciException.class);
    }

    @Test
    void SRS_INT_31_aCancelPassesItsBody() throws IOException {
        when(requests.cancel(eq(HrmsApi.CANCEL_OTA), any()))
                .thenReturn(Map.of("status", "Cancelled"));
        var out = handler.handle(call(HrmsApi.CANCEL_OTA, "{\"request_id\":4516}"));
        assertThat(out.getStatusCode().value()).isEqualTo(200);
        assertThat(out.getBody()).containsEntry("status", "Cancelled");
    }

    @Test
    void SRS_INT_1_aReportApiIsRoutedAndRefusedAsNotEnabled() {
        assertThatThrownBy(() -> handler.handle(call(HrmsApi.FORECAST_REPORT, null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getMessage())
                                        .isEqualTo(IntegrationErrorCode.HRMS_NOT_ENABLED.code()));
    }

    @Test
    void SRS_INT_12_aBodyNeedsAJsonContentTypeAndAtMostTheSizeLimit() throws IOException {
        var none = call(HrmsApi.CANCEL_OTA, null);
        assertThatThrownBy(() -> handler.handle(none)).isInstanceOf(FciException.class);
        var text = call(HrmsApi.CANCEL_OTA, "{}");
        text.setContentType("text/plain");
        assertThatThrownBy(() -> handler.handle(text)).isInstanceOf(FciException.class);
        var broken = call(HrmsApi.CANCEL_OTA, "{}");
        broken.setContentType("not a media type");
        assertThatThrownBy(() -> handler.handle(broken))
                .isInstanceOf(InvalidMediaTypeException.class);

        var utf8 = call(HrmsApi.CANCEL_OTA, "{}");
        utf8.setContentType("application/json;charset=UTF-8");
        when(requests.cancel(eq(HrmsApi.CANCEL_OTA), any())).thenReturn(Map.of());
        assertThat(handler.handle(utf8).getStatusCode().value()).isEqualTo(200);

        var atLimit = call(HrmsApi.CANCEL_OTA, "x".repeat(HrmsApiHandler.MAX_BODY_BYTES));
        assertThat(handler.handle(atLimit).getStatusCode().value()).isEqualTo(200);
        var over = call(HrmsApi.CANCEL_OTA, "x".repeat(HrmsApiHandler.MAX_BODY_BYTES + 1));
        assertThatThrownBy(() -> handler.handle(over))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getMessage())
                                        .isEqualTo(IntegrationErrorCode.HRMS_TOO_LARGE.code()));
    }
}
