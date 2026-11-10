package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.HrmsReply;
import in.gov.fci.payroll.integration.api.HrmsReply.Outcome;
import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.integration.api.InterfaceSettings.Settings;
import in.gov.fci.payroll.integration.business.HrmsContractCatalog;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Limit;
import in.gov.fci.payroll.integration.config.HrmsClientProperties;
import in.gov.fci.payroll.integration.config.HrmsCredentialsProperties;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-INT-1, SRS-INT-4, SRS-INT-6, SRS-INT-12, SRS-INT-35; R-INT: Payroll's gateway against a
 * RUNNING HRMS test double (the FCI HRMS mock server), end to end over HTTP with the real
 * WebClient, pool, retry, bulkhead, token handling and reply classification. It runs only when the
 * environment names the server, so a normal build never needs it:
 *
 * <pre>
 *   HRMS_MOCK_URL=http://localhost:8080  HRMS_MOCK_API_KEY=...  HRMS_MOCK_USER=...  HRMS_MOCK_PASSWORD=...
 *   ./gradlew :backend:test --tests '*HrmsMockServerLiveTest'
 * </pre>
 *
 * The key and the login are never written to a file. The test employee 104882 (Rakesh Sharma,
 * office DH001) is one of Payroll's demo employees, which the mock holds since the data alignment
 * of 2026-11-06 (docs/integration/HRMS_MOCK_ALIGNMENT.md). Request numbers are unique per run
 * because the mock keeps state.
 */
@EnabledIfEnvironmentVariable(named = "HRMS_MOCK_URL", matches = ".+")
class HrmsMockServerLiveTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long EMP = 104882L;
    private static final long RUN = 100_000 + (System.currentTimeMillis() / 1000) % 800_000;

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.strip();
    }

    private final List<HrmsClient> clients = new ArrayList<>();

    @AfterEach
    void close() {
        clients.forEach(HrmsClient::close);
    }

    private HrmsGatewayService gateway(
            String user, String password, int retries, int timeoutSeconds) {
        Map<String, ApiUrl> urls = new HashMap<>();
        Map<String, Limit> limits = new HashMap<>();
        limits.put("connection_test", new Limit(2, 1000));
        for (HrmsApi api : HrmsApi.values()) {
            urls.put(api.code(), new ApiUrl(api.collectionPath(), "OUTBOUND"));
            limits.put(api.code(), new Limit(5, 2000));
        }
        InterfaceIntake intake = mock(InterfaceIntake.class);
        when(intake.open(anyString(), anyString(), any(), any())).thenReturn(1L);
        IntegrationStore store = mock(IntegrationStore.class);
        when(store.interfaceOf(anyString()))
                .thenAnswer(
                        i ->
                                new IntegrationStore.Registered(
                                        1L, i.getArgument(0), "HRMS", "OUTBOUND"));
        HrmsClient client =
                new HrmsClient(
                        new HrmsApiProperties(env("HRMS_MOCK_URL"), urls, limits, null),
                        new HrmsClientProperties(
                                5, 5, 2, 30, 60, 1, env("HRMS_MOCK_API_KEY"), null, null, null),
                        new HrmsCredentialsProperties(user, password, null, 60),
                        code ->
                                Optional.of(
                                        new Settings(
                                                "X",
                                                retries,
                                                0,
                                                "FIXED",
                                                timeoutSeconds,
                                                false,
                                                null,
                                                null,
                                                null,
                                                LocalDate.now(),
                                                true)),
                        intake,
                        store,
                        mock(FailureAlerts.class),
                        mock(HrmsRefusals.class));
        clients.add(client);
        return new HrmsGatewayService(client, HrmsContractCatalog.load());
    }

    private HrmsGatewayService gateway() {
        return gateway(env("HRMS_MOCK_USER"), env("HRMS_MOCK_PASSWORD"), 1, 5);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static String ota(long requestId, long emp, String yymm) {
        return "{\"request_id\":"
                + requestId
                + ",\"emp_num\":"
                + emp
                + ",\"gross_amount\":100,\"it_ded\":1,\"othr_ded\":2,\"net_amt\":100,\"pay_mode\":1,\"remarks\":\"payroll"
                + " integration\",\"ciel_flag\":\"A\",\"ota_yymm\":"
                + yymm
                + ",\"otahrs\":12,\"otarate\":10}";
    }

    @Test
    void SRS_INT_33_welcomeAnswersWithTheApiKeyAlone() {
        HrmsReply reply = gateway().welcome();
        assertThat(reply.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(reply.data().get("endpoints").asInt()).isEqualTo(61);
    }

    @Test
    void SRS_INT_4_aLoginThatHrmsRefusesFailsTheCallWhateverTheReason() {
        for (String[] bad :
                new String[][] {
                    {"admin", "not-the-password"}, // 401 INVALID_CREDENTIALS
                    {"lockeduser", "Locked@123"}, // 423 ACCOUNT_LOCKED
                    {"inactiveuser", "Inactive@123"}, // 403 USER_INACTIVE
                    {"throttled", "any"}, // 429 TOO_MANY_LOGIN_ATTEMPTS
                    {"server_error", "any"}, // 500 on the token call
                }) {
            HrmsGatewayService g = gateway(bad[0], bad[1], 0, 5);
            assertThatThrownBy(
                            () ->
                                    g.status(
                                            HrmsApi.GET_OTA_STATUS,
                                            Map.of("request_id", "4517", "emp_ota_id", "1230")))
                    .as(bad[0])
                    .isInstanceOf(FciException.class)
                    .hasMessage("INT-502-CALL-FAILED");
        }
    }

    @Test
    void SRS_INT_35_aRequestForAPayrollEmployeeIsAcceptedReadBackAndCancelledOnce() {
        HrmsGatewayService g = gateway();
        long id = RUN * 10 + 1;
        HrmsReply submitted =
                g.submit(HrmsApi.REQUEST_OTA, null, json(ota(id, EMP, "2610")), "IT-" + id);
        assertThat(submitted.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(submitted.data().get("emp_name").asString()).isEqualTo("Rakesh Sharma");
        assertThat(submitted.data().get("status").asString()).isEqualTo("PENDING");
        long otaId = submitted.data().get("emp_ota_id").asLong();

        HrmsReply read =
                g.status(
                        HrmsApi.GET_OTA_STATUS,
                        Map.of(
                                "request_id",
                                String.valueOf(id),
                                "emp_ota_id",
                                String.valueOf(otaId)));
        assertThat(read.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(read.data().get("emp_num").asLong()).isEqualTo(EMP);
        assertThat(read.data().get("status").asString()).isEqualTo("PENDING");

        HrmsReply repeat =
                g.submit(HrmsApi.REQUEST_OTA, null, json(ota(id, EMP, "2610")), "IT-" + id);
        assertThat(repeat.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(repeat.duplicate()).as("the same request again is the same record").isTrue();

        String cancel = "{\"request_id\":" + id + ",\"emp_ota_id\":" + otaId + "}";
        HrmsReply cancelled = g.cancel(HrmsApi.CANCEL_OTA, json(cancel), "IT-C-" + id);
        assertThat(cancelled.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(cancelled.data().get("status").asString()).isEqualTo("CANCELLED");
        HrmsReply again = g.cancel(HrmsApi.CANCEL_OTA, json(cancel), "IT-C2-" + id);
        assertThat(again.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(again.httpStatus()).isEqualTo(409);
        assertThat(again.errorCode()).isEqualTo("ALREADY_CANCELLED");
    }

    @Test
    void SRS_INT_31_hrmsRefusalsAreClassifiedWithTheirCodes() {
        HrmsGatewayService g = gateway();
        HrmsReply unknown =
                g.status(HrmsApi.GET_OTA_STATUS, Map.of("request_id", "1", "emp_ota_id", "1"));
        assertThat(unknown.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(unknown.httpStatus()).isEqualTo(404);
        assertThat(unknown.errorCode()).isEqualTo("REQUEST_NOT_FOUND");

        HrmsReply nobody =
                g.submit(HrmsApi.REQUEST_OTA, null, json(ota(RUN * 10 + 2, 1L, "2610")), null);
        assertThat(nobody.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(nobody.errorCode()).isEqualTo("EMPLOYEE_NOT_FOUND");

        HrmsReply tooMuch =
                g.submit(
                        HrmsApi.REQUEST_BRIEF_CASE_REIMBURSEMENT,
                        null,
                        json(
                                "{\"request_id\":"
                                        + (RUN * 10 + 3)
                                        + ",\"emp_num\":"
                                        + EMP
                                        + ",\"sanction_date\":\"2022-12-30\",\"sanction_number\":\"1\","
                                        + "\"sanction_amount\":20000,\"bill_number\":\"b"
                                        + RUN
                                        + "\",\"bill_date\":\"2022-12-31\"}"),
                        null);
        assertThat(tooMuch.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(tooMuch.httpStatus()).isEqualTo(422);
        assertThat(tooMuch.errorCode()).isEqualTo("LIMIT_EXCEEDED");

        HrmsReply thin =
                g.submit(
                        HrmsApi.REQUEST_OTA,
                        null,
                        json("{\"request_id\":" + (RUN * 10 + 4) + "}"),
                        null);
        assertThat(thin.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(thin.httpStatus()).isEqualTo(400);
        assertThat(thin.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(thin.problems()).isNotEmpty();
    }

    @Test
    void SRS_INT_12_aCallThatContradictsTheContractNeverReachesTheServer() {
        HrmsGatewayService g = gateway();
        HrmsReply unknownField =
                g.submit(
                        HrmsApi.REQUEST_OTA,
                        null,
                        json("{\"request_id\":1,\"not_in_the_contract\":1}"),
                        null);
        assertThat(unknownField.outcome()).isEqualTo(Outcome.INVALID_REQUEST);
        HrmsReply wrongKind =
                g.submit(HrmsApi.REQUEST_OTA, null, json("{\"request_id\":true}"), null);
        assertThat(wrongKind.outcome()).isEqualTo(Outcome.INVALID_REQUEST);
        assertThat(wrongKind.attempts()).isZero();
    }

    @Test
    void SRS_INT_35_aPayrollEmployeeHasAPayslipInHrms() {
        HrmsReply payslip =
                gateway()
                        .status(
                                HrmsApi.GETPAYSLIP,
                                Map.of("emp_num", String.valueOf(EMP), "yymm", "202609"));
        assertThat(payslip.outcome()).isEqualTo(Outcome.ACCEPTED);
        JsonNode employee = payslip.data().get("employee");
        assertThat(employee.get("emp_name").asString()).isEqualTo("Rakesh Sharma");
        assertThat(employee.get("office").asString()).isEqualTo("DH001");
        assertThat(employee.get("designation").asString()).isEqualTo("MGR");
        assertThat(employee.get("emp_status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void SRS_INT_6_theFailuresOfTheServerAreRetriedAndThenReportedAsAFailedCall() {
        HrmsGatewayService g = gateway(env("HRMS_MOCK_USER"), env("HRMS_MOCK_PASSWORD"), 1, 5);
        for (String employee : new String[] {"999500", "999503", "999502", "999599"}) {
            assertThatThrownBy(
                            () ->
                                    g.status(
                                            HrmsApi.GET_MEDICAL_CLAIM_STATUS,
                                            Map.of("emp_num", employee, "payment_id", "1")))
                    .as(employee)
                    .isInstanceOf(FciException.class)
                    .hasMessage("INT-502-CALL-FAILED");
        }
    }

    @Test
    void SRS_INT_6_aSlowServerEndsAtThePayrollTimeLimit() {
        HrmsGatewayService g = gateway(env("HRMS_MOCK_USER"), env("HRMS_MOCK_PASSWORD"), 0, 2);
        long started = System.nanoTime();
        assertThatThrownBy(
                        () ->
                                g.status(
                                        HrmsApi.GET_MEDICAL_CLAIM_STATUS,
                                        Map.of("emp_num", "999504", "payment_id", "1")))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-502-CALL-FAILED");
        assertThat((System.nanoTime() - started) / 1_000_000_000L).as("seconds").isLessThan(20);
    }

    @Test
    void SRS_INT_31_answersThatAreNotWhatTheyClaimAreClassifiedNeverAsSuccess() {
        HrmsGatewayService g = gateway();
        Map<String, Outcome> expected =
                Map.of(
                        "999204", Outcome.UNREADABLE, // 200, empty body
                        "999299", Outcome.UNREADABLE, // 200, truncated JSON
                        "999200", Outcome.FAILED_IN_BODY, // 200, "status":"error"
                        "999429", Outcome.REJECTED); // 429 with Retry-After
        expected.forEach(
                (employee, outcome) -> {
                    HrmsReply reply =
                            g.status(
                                    HrmsApi.GET_MEDICAL_CLAIM_STATUS,
                                    Map.of("emp_num", employee, "payment_id", "1"));
                    assertThat(reply.outcome()).as(employee).isEqualTo(outcome);
                    assertThat(reply.accepted()).as(employee).isFalse();
                });
    }

    @Test
    void SRS_INT_4_aShortLivedTokenIsRenewedForEveryCallAndEveryCallSucceeds() {
        HrmsGatewayService g = gateway("shorttoken", "Short@123", 0, 5);
        for (int i = 0; i < 3; i++) {
            HrmsReply reply =
                    g.status(
                            HrmsApi.GET_OTA_STATUS,
                            Map.of("request_id", "4517", "emp_ota_id", "1230"));
            assertThat(reply.outcome()).as("call " + i).isEqualTo(Outcome.ACCEPTED);
        }
    }
}
