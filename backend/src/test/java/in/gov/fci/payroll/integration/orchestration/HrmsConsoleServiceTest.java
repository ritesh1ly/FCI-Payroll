package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.HrmsGateway;
import in.gov.fci.payroll.integration.api.HrmsReply;
import in.gov.fci.payroll.integration.api.HrmsReply.Outcome;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.config.HrmsConsoleProperties;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-INT-33, SRS-INT-6, R-AUD, R-PII; screen X-03: the Administrator's HRMS API console calls an
 * outbound API through the gateway, audits the call without its content and returns the data masked
 * and cut.
 */
class HrmsConsoleServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CODE = HrmsApi.GET_LOAN_STATUS.code();

    private final HrmsGateway gateway = mock(HrmsGateway.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);

    private HrmsConsoleService service(Boolean enabled) {
        when(context.loginId()).thenReturn("admin");
        return new HrmsConsoleService(gateway, new HrmsConsoleProperties(enabled), policy, audit);
    }

    private static HrmsConsoleService.CallRequest request(
            Map<String, String> query, JsonNode body) {
        return new HrmsConsoleService.CallRequest(query, body, "Check the API");
    }

    private static HrmsReply reply(Outcome outcome, int status, JsonNode data) {
        return new HrmsReply(outcome, status, "E-1", "msg", data, List.of("f: bad"), true, 2);
    }

    @Test
    void SRS_INT_33_aDisabledConsoleRefusesBeforeAnythingIsCalled() {
        HrmsConsoleService off = service(false);
        assertThatThrownBy(() -> off.call(context, CODE, request(null, null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(IntegrationErrorCode.CONSOLE_DISABLED));
        verifyNoInteractions(gateway, audit);
        verify(policy).require(context, "X-03", "EDIT");
    }

    @Test
    void SRS_INT_33_anAbsentSettingMeansOff() {
        assertThat(new HrmsConsoleProperties(null).on()).isFalse();
        assertThat(new HrmsConsoleProperties(false).on()).isFalse();
        assertThat(new HrmsConsoleProperties(true).on()).isTrue();
        HrmsConsoleService absent = service(null);
        assertThatThrownBy(() -> absent.call(context, CODE, request(null, null)))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void SRS_INT_33_consoleDisabledIsA403Code() {
        IntegrationErrorCode code = IntegrationErrorCode.CONSOLE_DISABLED;
        assertThat(code.code()).isEqualTo("INT-403-CONSOLE-DISABLED");
        assertThat(code.status().value()).isEqualTo(403);
        assertThat(code.type()).isEqualTo("integration/console-disabled");
        assertThat(code.defaultTitle()).isEqualTo("API console disabled");
        assertThat(code.defaultDetail())
                .isEqualTo(
                        "The HRMS API console is not enabled on this server"
                                + " (fci.integration.hrms.console.enabled).");
    }

    @Test
    void SRS_INT_33_aRoleWithoutX03EditIsRefusedByThePolicy() {
        HrmsConsoleService on = service(true);
        doThrow(new FciException(IntegrationErrorCode.CIRCUIT_OPEN))
                .when(policy)
                .require(context, "X-03", "EDIT");
        assertThatThrownBy(() -> on.call(context, CODE, request(null, null)))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(gateway, audit);
    }

    @Test
    void SRS_INT_33_anUnknownCodeIsNotRegistered() {
        HrmsConsoleService on = service(true);
        for (String code : List.of("nope", "", "GET_LOAN_STATUS", "get_loan_status ")) {
            assertThatThrownBy(() -> on.call(context, code, request(null, null)))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> {
                                assertThat(e.getErrorCode())
                                        .isEqualTo(IntegrationErrorCode.UNKNOWN_INTERFACE);
                                assertThat(e.getMessageArgs()).containsExactly(code);
                            });
        }
        verifyNoInteractions(gateway, audit);
    }

    @Test
    void SRS_INT_33_everyApiCodeCanBeCalledAndCarriesItsMethod() {
        HrmsConsoleService on = service(true);
        when(gateway.call(any(), any(), any(), any()))
                .thenReturn(reply(Outcome.ACCEPTED, 200, null));
        for (HrmsApi api : HrmsApi.values()) {
            HrmsConsoleService.CallResult result =
                    on.call(context, api.code(), request(null, null));
            assertThat(result.code()).isEqualTo(api.code());
            assertThat(result.method()).isEqualTo(api.method());
            verify(gateway).call(eq(api), isNull(), isNull(), isNull());
        }
        assertThat(HrmsApi.values()).extracting(HrmsApi::method).contains("GET", "POST", "PUT");
    }

    @Test
    void SRS_INT_33_everyOutcomeIsMappedFieldForField() {
        HrmsConsoleService on = service(true);
        for (Outcome outcome : Outcome.values()) {
            JsonNode data = JSON.readTree("{\"k\":1}");
            when(gateway.call(any(), any(), any(), any())).thenReturn(reply(outcome, 207, data));
            HrmsConsoleService.CallResult r = on.call(context, CODE, request(null, null));
            assertThat(r.outcome()).isEqualTo(outcome);
            assertThat(r.httpStatus()).isEqualTo(207);
            assertThat(r.errorCode()).isEqualTo("E-1");
            assertThat(r.message()).isEqualTo("msg");
            assertThat(r.problems()).containsExactly("f: bad");
            assertThat(r.duplicate()).isTrue();
            assertThat(r.attempts()).isEqualTo(2);
            assertThat(r.elapsedMillis()).isGreaterThanOrEqualTo(0L);
            assertThat(r.data()).isEqualTo("{\"k\":1}");
        }
    }

    @Test
    void SRS_INT_33_aCallThatWasNeverMadeKeepsStatusZeroAndNoData() {
        HrmsConsoleService on = service(true);
        when(gateway.call(any(), any(), any(), any()))
                .thenReturn(HrmsReply.invalid(List.of("a missing")));
        HrmsConsoleService.CallResult r = on.call(context, CODE, request(null, null));
        assertThat(r.outcome()).isEqualTo(Outcome.INVALID_REQUEST);
        assertThat(r.httpStatus()).isZero();
        assertThat(r.attempts()).isZero();
        assertThat(r.data()).isNull();
        assertThat(r.problems()).containsExactly("a missing");
    }

    @Test
    void SRS_INT_33_queryAndBodyReachTheGatewayWithoutAnIdempotencyKey() {
        HrmsConsoleService on = service(true);
        when(gateway.call(any(), any(), any(), any()))
                .thenReturn(reply(Outcome.ACCEPTED, 200, null));
        Map<String, String> query = Map.of("request_id", "5");
        JsonNode body = JSON.readTree("{\"request_id\":5}");
        on.call(context, "request_ota", request(query, body));
        verify(gateway).call(HrmsApi.REQUEST_OTA, query, body, null);
    }

    @Test
    void SRS_INT_33_nullEmptyAndJsonNullInputsAreAllowedAndNullIsPassedOn() {
        HrmsConsoleService on = service(true);
        when(gateway.call(any(), any(), any(), any()))
                .thenReturn(reply(Outcome.ACCEPTED, 200, null));
        on.call(context, CODE, request(null, null));
        verify(gateway).call(HrmsApi.GET_LOAN_STATUS, null, null, null);
        Map<String, String> empty = Map.of();
        on.call(context, CODE, request(empty, JSON.readTree("null")));
        verify(gateway).call(HrmsApi.GET_LOAN_STATUS, empty, null, null);
    }

    @Test
    void SRS_INT_33_arrayScalarAndHugeBodiesPassThroughUnchanged() {
        HrmsConsoleService on = service(true);
        when(gateway.call(any(), any(), any(), any()))
                .thenReturn(reply(Outcome.ACCEPTED, 200, null));
        JsonNode array = JSON.readTree("[1,2,{\"a\":[]}]");
        on.call(context, CODE, request(null, array));
        verify(gateway).call(HrmsApi.GET_LOAN_STATUS, null, array, null);
        JsonNode scalar = JSON.readTree("\"text\"");
        on.call(context, "request_ota", request(null, scalar));
        verify(gateway).call(HrmsApi.REQUEST_OTA, null, scalar, null);
        StringBuilder big = new StringBuilder("[");
        for (int i = 0; i < 20000; i++) {
            big.append(i == 0 ? "" : ",").append(i);
        }
        JsonNode huge = JSON.readTree(big.append(']').toString());
        on.call(context, "cancel_ota", request(null, huge));
        verify(gateway).call(HrmsApi.CANCEL_OTA, null, huge, null);
    }

    @Test
    void SRS_INT_33_postPutAndGetApisAreAllReachable() {
        HrmsConsoleService on = service(true);
        when(gateway.call(any(), any(), any(), any()))
                .thenReturn(reply(Outcome.ACCEPTED, 200, null));
        List<String> methods = new ArrayList<>();
        for (String code : List.of("get_loan_status", "request_ota", "cancel_ota")) {
            methods.add(on.call(context, code, request(null, null)).method());
        }
        assertThat(methods).containsExactly("GET", "POST", "PUT");
    }

    @Test
    void SRS_INT_33_dataIsMaskedAsPlatformMaskingDoes() {
        HrmsConsoleService on = service(true);
        JsonNode data = JSON.readTree("{\"pan\":\"ABCPE1234F\",\"note\":\"ok\"}");
        when(gateway.call(any(), any(), any(), any()))
                .thenReturn(reply(Outcome.ACCEPTED, 200, data));
        String text = on.call(context, CODE, request(null, null)).data();
        assertThat(text).doesNotContain("ABCPE1234F").contains("ok");
    }

    @Test
    void SRS_INT_33_dataIsCutAtFourThousandCharacters() {
        assertThat(HrmsConsoleService.DATA_LIMIT).isEqualTo(4000);
        JsonNode exact = JSON.readTree("{\"v\":\"" + "x".repeat(4000 - 8) + "\"}");
        assertThat(exact.toString()).hasSize(4000);
        assertThat(HrmsConsoleService.data(exact)).hasSize(4000).isEqualTo(exact.toString());
        JsonNode over = JSON.readTree("{\"v\":\"" + "x".repeat(4000 - 7) + "\"}");
        assertThat(HrmsConsoleService.data(over)).hasSize(4000);
        JsonNode huge = JSON.readTree("{\"v\":\"" + "y".repeat(50_000) + "\"}");
        assertThat(HrmsConsoleService.data(huge)).hasSize(4000).startsWith("{\"v\":\"yyy");
        assertThat(HrmsConsoleService.data(null)).isNull();
    }

    @Test
    void SRS_INT_33_theAuditEntryHoldsTheOutcomeAndReasonNeverTheContent() {
        HrmsConsoleService on = service(true);
        JsonNode data = JSON.readTree("{\"secret_data\":\"DATAMARKER\"}");
        when(gateway.call(any(), any(), any(), any()))
                .thenReturn(reply(Outcome.REJECTED, 422, data));
        on.call(
                context,
                "request_ota",
                new HrmsConsoleService.CallRequest(
                        Map.of("qkey", "QUERYMARKER"),
                        JSON.readTree("{\"b\":\"BODYMARKER\"}"),
                        "Because I said"));
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("admin"), entry.capture());
        verify(audit, never()).recordIndependently(anyString(), any());
        AuditService.Entry e = entry.getValue();
        assertThat(e.entityName()).isEqualTo("hrms_api_call");
        assertThat(e.entityKey()).isEqualTo("request_ota");
        assertThat(e.reason()).isEqualTo("Because I said");
        assertThat(e.newValue()).isEqualTo("REJECTED 422 E-1");
        assertThat(e.moduleCode()).isEqualTo("X-03");
        assertThat(e.toString()).doesNotContain("QUERYMARKER", "BODYMARKER", "DATAMARKER", "qkey");
    }

    @Test
    void SRS_INT_33_aGatewayFailureIsAuditedOnItsOwnAndPropagatesUnchanged() {
        HrmsConsoleService on = service(true);
        for (IntegrationErrorCode code :
                List.of(
                        IntegrationErrorCode.CALL_FAILED,
                        IntegrationErrorCode.HRMS_API_NOT_CONFIGURED,
                        IntegrationErrorCode.BULKHEAD_FULL)) {
            FciException failure = new FciException(code);
            doThrow(failure).when(gateway).call(any(), any(), any(), any());
            assertThatThrownBy(() -> on.call(context, CODE, request(null, null))).isSameAs(failure);
        }
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit, times(3)).recordIndependently(eq("admin"), entry.capture());
        assertThat(entry.getAllValues())
                .extracting(AuditService.Entry::newValue)
                .containsExactly(
                        "EXCEPTION INT-502-CALL-FAILED",
                        "EXCEPTION INT-503-HRMS-API-NOT-CONFIGURED",
                        "EXCEPTION INT-503-BULKHEAD-FULL");
        verify(audit, never()).record(anyString(), any());
    }

    @Test
    void SRS_INT_33_aPlainRuntimeFailureIsAuditedByItsClassAndRethrown() {
        HrmsConsoleService on = service(true);
        IllegalStateException failure = new IllegalStateException("boom");
        when(gateway.call(any(), any(), any(), any())).thenThrow(failure);
        assertThatThrownBy(() -> on.call(context, CODE, request(null, null))).isSameAs(failure);
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).recordIndependently(eq("admin"), entry.capture());
        assertThat(entry.getValue().newValue()).isEqualTo("EXCEPTION IllegalStateException");
        assertThat(entry.getValue().toString()).doesNotContain("boom");
    }

    @Test
    void SRS_INT_33_aFullQueryOfLongValuesIsPassedInItsOrder() {
        HrmsConsoleService on = service(true);
        when(gateway.call(any(), any(), any(), any()))
                .thenReturn(reply(Outcome.ACCEPTED, 200, null));
        Map<String, String> query = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            query.put("k" + i, "v".repeat(200));
        }
        on.call(context, CODE, request(query, null));
        verify(gateway).call(HrmsApi.GET_LOAN_STATUS, query, null, null);
    }
}
