package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.business.HrmsContracts;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * SRS-INT-31, SRS-INT-35, BR-INT-003, R-DEL, R-PII: the status and cancel APIs over the staged
 * records: unknown request, another API's type, parameters missing or malformed, a cancel that is
 * recorded once, a payload that cannot be read back, no lookup for an intake API.
 */
class HrmsRequestServiceTest {

    private static final Instant NOW = Instant.now();

    private final IntegrationStore store = mock(IntegrationStore.class);
    private final AuditService audit = mock(AuditService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Kolkata"));
    private HrmsRequestService service;

    /** What the mocked JDBC answers: the staged rows and the cancellation count. */
    private List<Object[]> rows = List.of();

    private Integer cancelCount = 0;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc =
                mock(
                        JdbcTemplate.class,
                        invocation -> {
                            switch (invocation.getMethod().getName()) {
                                case "query" -> {
                                    @SuppressWarnings("unchecked")
                                    RowMapper<Object> mapper = invocation.getArgument(1);
                                    java.util.ArrayList<Object> out = new java.util.ArrayList<>();
                                    int i = 0;
                                    for (Object[] r : rows) {
                                        out.add(mapper.mapRow(resultSet(r), i++));
                                    }
                                    return out;
                                }
                                case "queryForObject" -> {
                                    return cancelCount;
                                }
                                default -> {
                                    return null;
                                }
                            }
                        });
        when(store.jdbc()).thenReturn(jdbc);
        when(store.insert(anyString(), anyString(), any())).thenReturn(7L);
        service = new HrmsRequestService(store, audit, clock);
    }

    private static ResultSet resultSet(Object[] r) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn((Long) r[0]);
        when(rs.getString(2)).thenReturn((String) r[1]);
        when(rs.getString(3)).thenReturn((String) r[2]);
        when(rs.getString(4)).thenReturn((String) r[3]);
        when(rs.getString(5)).thenReturn((String) r[4]);
        when(rs.getString(6)).thenReturn((String) r[5]);
        when(rs.getTimestamp(7)).thenReturn(Timestamp.from(NOW));
        return rs;
    }

    private void staged(String txnType, String status, String detail, String payload) {
        rows =
                java.util.Collections.singletonList(
                        new Object[] {41L, txnType, "4516", status, detail, payload});
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String idParam(HrmsApi api) {
        return HrmsContracts.lookup(api).orElseThrow().idParam();
    }

    private static void assertRefused(Runnable call, IntegrationErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getMessage()).isEqualTo(code.code()));
    }

    @Test
    void SRS_INT_35_anIntakeApiHasNoStatusOrCancelLookup() {
        assertThatThrownBy(() -> service.status(HrmsApi.REQUEST_OTA, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.cancel(HrmsApi.REQUEST_OTA, bytes("{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SRS_INT_35_aStatusAnswersTheStagedStatusAndTheAmountAsReceived() {
        staged("EMP_OTA", "APPLIED", null, "{\"request_id\":\"4516\",\"net_amt\":100.50}");
        Map<String, Object> out =
                service.status(
                        HrmsApi.GET_OTA_STATUS,
                        Map.of("request_id", "4516", idParam(HrmsApi.GET_OTA_STATUS), "77"));
        assertThat(out)
                .containsEntry("request_id", "4516")
                .containsEntry("status", "Accepted")
                .containsEntry("staging_status", "APPLIED")
                .containsEntry("amount", "100.5")
                .containsEntry("received_at", NOW.toString());
    }

    @Test
    void SRS_INT_35_aRequestWithACancellationEventAnswersCancellationRequested() {
        staged("EMP_OTA", "REJECTED", "employee not found", "{\"net_amt\":1}");
        cancelCount = 1;
        Map<String, Object> out =
                service.status(
                        HrmsApi.GET_OTA_STATUS,
                        Map.of("request_id", "4516", idParam(HrmsApi.GET_OTA_STATUS), "77"));
        assertThat(out)
                .containsEntry("status", "Cancellation requested")
                .containsEntry("reason", "employee not found");
        cancelCount = null;
        assertThat(
                        service.status(
                                HrmsApi.GET_OTA_STATUS,
                                Map.of(
                                        "request_id",
                                        "4516",
                                        idParam(HrmsApi.GET_OTA_STATUS),
                                        "77")))
                .containsEntry("status", "Rejected");
    }

    @Test
    void SRS_INT_35_aStatusCallWithAMissingOrMalformedParameterIsRefusedByName() {
        String param = idParam(HrmsApi.GET_OTA_STATUS);
        for (Map<String, String> q :
                List.of(
                        Map.<String, String>of(),
                        Map.of(param, "77"),
                        Map.of("request_id", "4516"),
                        Map.of("request_id", "4516 OR 1=1", param, "77"),
                        Map.of("request_id", "4516", param, "7'7"),
                        Map.of("request_id", "x".repeat(31), param, "77"))) {
            assertRefused(
                    () -> service.status(HrmsApi.GET_OTA_STATUS, q), IntegrationErrorCode.RECORD);
        }
        verify(store, never()).insert(anyString(), anyString(), any());
    }

    @Test
    void SRS_INT_35_aLoanOrMedicalStatusIsKeyedByTheEmployeeAndTheIdAndNeedsBoth() {
        String param = idParam(HrmsApi.GET_MEDICAL_CLAIM_STATUS);
        staged("EMP_MEDICAL_CLAIM", "RECEIVED", null, "{\"bill_amount\":10}");
        Map<String, Object> out =
                service.status(
                        HrmsApi.GET_MEDICAL_CLAIM_STATUS,
                        Map.of("request_id", "4516", "emp_num", "160661", param, "55"));
        assertThat(out).containsEntry("status", "Received").containsEntry("amount", null);
        for (Map<String, String> q :
                List.of(
                        Map.of(param, "55"),
                        Map.of("emp_num", "160661"),
                        Map.of("emp_num", "bad emp", param, "55"),
                        Map.of("emp_num", "160661", param, "bad id"))) {
            assertRefused(
                    () -> service.status(HrmsApi.GET_MEDICAL_CLAIM_STATUS, q),
                    IntegrationErrorCode.RECORD);
        }
    }

    @Test
    void SRS_INT_35_aStatusOfAnUnknownRequestIsNotFound() {
        rows = List.of();
        assertRefused(
                () ->
                        service.status(
                                HrmsApi.GET_OTA_STATUS,
                                Map.of("request_id", "9999", idParam(HrmsApi.GET_OTA_STATUS), "1")),
                IntegrationErrorCode.HRMS_REQUEST_NOT_FOUND);
    }

    @Test
    void R_PII_theAmountIsNullWhenTheTypeHasNoAmountFieldOrThePayloadCannotBeRead() {
        String param = idParam(HrmsApi.GET_OTA_STATUS);
        Map<String, String> q = Map.of("request_id", "4516", param, "77");
        // a staged type no contract knows
        staged("NO_SUCH_TYPE", "RECEIVED", null, "{\"net_amt\":1}");
        assertThat(service.status(HrmsApi.GET_OTA_STATUS, q)).containsEntry("amount", null);
        // a type without an amount field (HRA / DA)
        staged("EMP_HRA_DTL", "RECEIVED", null, "{\"net_amt\":1}");
        assertThat(service.status(HrmsApi.GET_OTA_STATUS, q)).containsEntry("amount", null);
        // no payload kept
        staged("EMP_OTA", "RECEIVED", null, null);
        assertThat(service.status(HrmsApi.GET_OTA_STATUS, q)).containsEntry("amount", null);
        // a payload that is not an object, is not JSON, or is empty
        for (String payload : new String[] {"[1,2]", "42", "not json", "", "{\"a\":1,\"a\":2}"}) {
            staged("EMP_OTA", "RECEIVED", null, payload);
            assertThat(service.status(HrmsApi.GET_OTA_STATUS, q))
                    .as(payload)
                    .containsEntry("amount", null);
        }
        // an object without the amount field
        staged("EMP_OTA", "RECEIVED", null, "{\"request_id\":\"4516\"}");
        assertThat(service.status(HrmsApi.GET_OTA_STATUS, q)).containsEntry("amount", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_INT_31_aCancelIsRecordedOnceWithAnEventAndAnAuditEntry() {
        staged("EMP_OTA", "RECEIVED", null, "{}");
        String param = idParam(HrmsApi.CANCEL_OTA);
        Map<String, Object> out =
                service.cancel(
                        HrmsApi.CANCEL_OTA,
                        bytes("{\"request_id\":\"4516\",\"" + param + "\":\"77\"}"));
        assertThat(out)
                .containsEntry("status", "Cancellation requested")
                .containsEntry("staging_status", "RECEIVED")
                .containsEntry("duplicate", false);
        ArgumentCaptor<Map<String, Object>> event = ArgumentCaptor.forClass(Map.class);
        verify(store).insert(eq("intf_staging_event"), eq("event_id"), event.capture());
        assertThat(event.getValue()).containsEntry("staging_id", 41L);
        verify(audit, times(1)).record(anyString(), any(AuditService.Entry.class));
    }

    @Test
    void SRS_INT_31_aRepeatedCancelIsIdempotentAndWritesNothingMore() {
        staged("EMP_OTA", "RECEIVED", null, "{}");
        cancelCount = 1;
        String param = idParam(HrmsApi.CANCEL_OTA);
        Map<String, Object> out =
                service.cancel(
                        HrmsApi.CANCEL_OTA,
                        bytes("{\"request_id\":\"4516\",\"" + param + "\":\"77\"}"));
        assertThat(out).containsEntry("duplicate", true);
        verify(store, never()).insert(anyString(), anyString(), any());
        verify(audit, never()).record(anyString(), any(AuditService.Entry.class));
    }

    @Test
    void SRS_INT_31_aCancelOfAnUnknownRequestIsNotFoundAndNothingIsWritten() {
        rows = List.of();
        String param = idParam(HrmsApi.CANCEL_OTA);
        assertRefused(
                () ->
                        service.cancel(
                                HrmsApi.CANCEL_OTA,
                                bytes("{\"request_id\":\"9999\",\"" + param + "\":\"77\"}")),
                IntegrationErrorCode.HRMS_REQUEST_NOT_FOUND);
        verify(store, never()).insert(anyString(), anyString(), any());
    }

    @Test
    void SRS_INT_12_aCancelBodyThatIsNotOneObjectWithOnlyItsTwoFieldsIsMalformed() {
        String param = idParam(HrmsApi.CANCEL_OTA);
        for (String body :
                new String[] {
                    "",
                    " ",
                    "[]",
                    "[{\"request_id\":\"1\"}]",
                    "42",
                    "\"text\"",
                    "{",
                    "{\"request_id\":\"1\",\"request_id\":\"2\"}",
                    "{\"request_id\":\"1\",\"" + param + "\":\"2\",\"status\":\"Paid\"}"
                }) {
            assertRefused(
                    () -> service.cancel(HrmsApi.CANCEL_OTA, bytes(body)),
                    IntegrationErrorCode.HRMS_MALFORMED);
        }
        assertRefused(
                () -> service.cancel(HrmsApi.CANCEL_OTA, null),
                IntegrationErrorCode.HRMS_MALFORMED);
    }

    @Test
    void SRS_INT_31_aCancelWithAMissingOrBadIdentifierIsRefusedByName() {
        String param = idParam(HrmsApi.CANCEL_OTA);
        for (String body :
                new String[] {
                    "{}",
                    "{\"request_id\":\"4516\"}",
                    "{\"" + param + "\":\"77\"}",
                    "{\"request_id\":null,\"" + param + "\":\"77\"}",
                    "{\"request_id\":\"45 16\",\"" + param + "\":\"77\"}",
                    "{\"request_id\":\"4516\",\"" + param + "\":\"7;7\"}",
                    "{\"request_id\":{\"a\":1},\"" + param + "\":\"77\"}"
                }) {
            assertRefused(
                    () -> service.cancel(HrmsApi.CANCEL_OTA, bytes(body)),
                    IntegrationErrorCode.RECORD);
        }
        verify(store, never()).insert(anyString(), anyString(), any());
    }
}
