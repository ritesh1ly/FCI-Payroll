package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.business.HrmsContracts;
import in.gov.fci.payroll.integration.orchestration.InterfaceSettingsService;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The HRMS APIs of the Postman collection, published by Payroll (SRS-INT-1, SRS-INT-4, SRS-INT-5,
 * SRS-INT-12, SRS-INT-23, SRS-INT-26, SRS-INT-31, SRS-INT-35; BR-INT-003, BR-INT-004; plan 23
 * section 7): the routes exist only where a URL is set; api-key + bearer token on every call but
 * the token API; stateless and CSRF-exempt; one staging record per request_id with an Accepted /
 * Received / Rejected answer; a pay-effect value only as an I-21 task; status and cancel without
 * exposing or changing anything else. Secrets here are generated for this run. Dates follow today
 * (IST).
 */
class HrmsApiIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String KEY = "it-key-" + SecureTokens.randomToken(24);
    private static final String PASSWORD = "it-pw-" + SecureTokens.randomToken(24);
    private static final String SECRET = "it-signing-" + SecureTokens.randomToken(32);
    private static final String CLIENT = "hrms-it-client";
    private static final String BASE = "/hrms";

    /** Collection APIs left unset: no route (and the two held and four not-required ones). */
    private static final java.util.Set<HrmsApi> UNSET =
            java.util.Set.of(
                    HrmsApi.GET_REQUEST_LAPTOP_BUYBACK,
                    HrmsApi.GET_BASIC_SYNCUP_STATUS,
                    HrmsApi.GET_PAYROLL_STATUST);

    @DynamicPropertySource
    static void hrms(DynamicPropertyRegistry registry) {
        for (HrmsApi api : HrmsApi.values()) {
            if (!UNSET.contains(api)) {
                registry.add(
                        "fci.integration.hrms.api." + api.code() + ".path",
                        () -> BASE + api.collectionPath());
            }
        }
        registry.add("fci.integration.hrms.security.api-key", () -> KEY);
        registry.add("fci.integration.hrms.security.client-id", () -> CLIENT);
        registry.add(
                "fci.integration.hrms.security.client-secret-hash",
                () -> PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
        registry.add("fci.integration.hrms.security.token-secret", () -> SECRET);
        // Demo claim type of M-15 (seed V045_3_9); the newspaper API is left unmapped on purpose.
        registry.add(
                "fci.integration.hrms.claim-type.request_pay_travel_allowance", () -> "MEDICAL");
    }

    private String token;

    @BeforeEach
    void configured() throws Exception {
        for (HrmsApi api : HrmsApi.values()) {
            HrmsContracts.request(api)
                    .map(HrmsContracts.Request::interfaceCode)
                    .filter(code -> !code.equals("HRMS_PAY_LIVERIES"))
                    .filter(code -> !code.equals("HRMS_LOAN_RESCHEDULE"))
                    .ifPresent(this::configure);
        }
        cacheChanged(jdbc, InterfaceSettingsService.REGION);
        token = exchange(PASSWORD).andReturnToken();
    }

    private void configure(String code) {
        jdbc.update(
                "INSERT INTO interface_config (interface_id, retry_count, backoff_seconds,"
                        + " backoff_strategy, timeout_seconds, circuit_breaker_required,"
                        + " effective_from, is_confirmed) SELECT interface_id, 1, 1, 'FIXED', 5,"
                        + " 'N', ?, 'N' FROM interface_registry WHERE interface_code = ? AND NOT"
                        + " EXISTS (SELECT 1 FROM interface_config c WHERE c.interface_id ="
                        + " interface_registry.interface_id)",
                Date.valueOf(LocalDate.of(2020, 4, 1)),
                code);
    }

    /** The answer of the token API. */
    private record Exchanged(MvcResult result, JsonNode body) {
        String andReturnToken() {
            return body.path("access_token").asString(null);
        }
    }

    private Exchanged exchange(String password) throws Exception {
        MvcResult r =
                mvc.perform(
                                post(BASE + "/token")
                                        .header("api-key", KEY)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\""
                                                        + CLIENT
                                                        + "\",\"password\":\""
                                                        + password
                                                        + "\"}"))
                        .andReturn();
        return new Exchanged(r, read(r));
    }

    private JsonNode read(MvcResult r) throws Exception {
        String text = r.getResponse().getContentAsString();
        return text.isBlank() ? json.createObjectNode() : json.readTree(text);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header("api-key", KEY).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MvcResult call(HrmsApi api, String query, String body) throws Exception {
        String path = BASE + api.collectionPath() + (query == null ? "" : "?" + query);
        MockHttpServletRequestBuilder b =
                switch (api.method()) {
                    case "POST" -> post(path);
                    case "PUT" -> put(path);
                    default -> get(path);
                };
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(authed(b)).andReturn();
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private String employee(int fromEnd) {
        return jdbc.queryForObject(
                "SELECT emp_num FROM employee WHERE location_id = ? AND record_status = 'ACTIVE'"
                    + " AND employment_status = 'IN_SERVICE' AND employee_type = 'STAFF' ORDER BY"
                    + " emp_num DESC OFFSET "
                        + fromEnd
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                String.class,
                locationId("DH001"));
    }

    private static String id() {
        return String.valueOf(10_000_000L + (System.nanoTime() % 80_000_000L));
    }

    private String travel(String request, String emp, LocalDate from, String amount) {
        return "{\"request_id\":"
                + request
                + ",\"emp_num\":"
                + emp
                + ",\"sanction_date\":\""
                + from
                + "\",\"sanction_number\":\"10\",\"sanction_amount\":"
                + amount
                + ",\"from_date\":\""
                + from
                + "\",\"to_date\":\""
                + from.plusDays(2)
                + "\",\"reim_reason\":\"IT travel\"}";
    }

    private LocalDate lastMonth() {
        return YearMonth.now(IST).minusMonths(1).atDay(1);
    }

    // SRS-INT-16: no setting, no route; the held and not-required APIs are never routed.
    @Test
    void SRS_INT_16_anApiWithoutAUrlSettingHasNoRouteAndNeitherHasAHeldOrUnneededOne()
            throws Exception {
        for (String path :
                new String[] {
                    BASE + "/get-request-laptop-buyback",
                    BASE + "/get-basic-syncup-status",
                    BASE + "/get-payroll-statust",
                    BASE + "/cpf-generation",
                    BASE + "/get-pension-slip",
                    BASE + "/request-direct-payroll",
                    BASE + "/welcome"
                }) {
            MvcResult r =
                    mvc.perform(
                                    authed(get(path))
                                            .param("request_id", "1")
                                            .param("reim_allow_id", "1"))
                            .andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isIn(401, 403, 404);
        }
    }

    // SRS-INT-4: key and token; stateless, no session, no CSRF token needed with valid credentials.
    @Test
    void SRS_INT_4_everyApiButTokenNeedsTheKeyAndTheBearerToken() throws Exception {
        String body = travel(id(), employee(11), lastMonth(), "100");
        String path = BASE + HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE.collectionPath();
        MockHttpServletRequestBuilder[] bad = {
            post(path), // nothing
            post(path).header("api-key", KEY), // key only
            post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token), // token only
            post(path)
                    .header("api-key", "wrong")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token),
            post(path)
                    .header("api-key", KEY)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer forged.token"),
            post(path).header("api-key", KEY).header(HttpHeaders.AUTHORIZATION, "Basic " + token),
            post(path)
                    .header("api-key", KEY.toUpperCase())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token),
        };
        for (MockHttpServletRequestBuilder b : bad) {
            MvcResult r =
                    mvc.perform(b.contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(read(r).path("errorCode").asString()).isEqualTo("INT-401-HRMS-AUTH");
            assertThat(r.getResponse().getHeader("Set-Cookie")).as("no session").isNull();
        }
        assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE txn_type = 'TRAVEL_REIMB'"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM interface_exception e JOIN interface_registry"
                                    + " i ON i.interface_id = e.interface_id WHERE i.interface_code"
                                    + " = 'HRMS_API_AUTH' AND e.remediation_status = 'OPEN'"))
                .as("refused exchanges are visible on X-03")
                .isPositive();
    }

    @Test
    void SRS_INT_4_aLoggedInBrowserSessionOpensNothing() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        MvcResult r =
                mvc.perform(
                                userGet(admin, BASE + "/get-ota-status")
                                        .param("request_id", "1")
                                        .param("emp_ota_id", "1"))
                        .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void SRS_INT_4_theTokenApiNeedsTheKeyAndTheRightCredentials() throws Exception {
        assertThat(exchange(PASSWORD).result().getResponse().getStatus()).isEqualTo(200);
        assertThat(exchange(PASSWORD).body().path("token_type").asString()).isEqualTo("Bearer");
        assertThat(exchange(PASSWORD).body().path("expires_in").asInt()).isEqualTo(900);
        assertThat(exchange("wrong-password").result().getResponse().getStatus()).isEqualTo(401);
        MvcResult noKey =
                mvc.perform(
                                post(BASE + "/token")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\""
                                                        + CLIENT
                                                        + "\",\"password\":\""
                                                        + PASSWORD
                                                        + "\"}"))
                        .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(401);
        assertThat(noKey.getResponse().getContentAsString()).doesNotContain("access_token");
        MvcResult extra =
                mvc.perform(
                                post(BASE + "/token")
                                        .header("api-key", KEY)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\""
                                                        + CLIENT
                                                        + "\",\"password\":\""
                                                        + PASSWORD
                                                        + "\",\"role\":\"ADMIN\"}"))
                        .andReturn();
        assertThat(extra.getResponse().getStatus()).as("mass assignment").isEqualTo(400);
        MvcResult dup =
                mvc.perform(
                                post(BASE + "/token")
                                        .header("api-key", KEY)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"x\",\"username\":\""
                                                        + CLIENT
                                                        + "\",\"password\":\""
                                                        + PASSWORD
                                                        + "\"}"))
                        .andReturn();
        assertThat(dup.getResponse().getStatus()).isEqualTo(400);
    }

    // SRS-INT-5, SRS-INT-12, SRS-1-19, BR-INT-003: staged once, answered Accepted, I-21 task
    // opened.
    @Test
    void SRS_INT_5_SRS_1_19_aTravelClaimIsStagedOnceAndOpensOneI21TaskAndARepeatAnswersTheSame()
            throws Exception {
        String request = id();
        String emp = employee(11);
        String body = travel(request, emp, lastMonth(), "2500");
        MvcResult first = call(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, null, body);
        JsonNode ack = read(first);
        assertThat(first.getResponse().getStatus())
                .as(first.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(ack.path("status").asString()).isEqualTo("Accepted");
        assertThat(ack.path("request_id").asString()).isEqualTo(request);
        assertThat(ack.path("hrms_request_id").asString()).isEqualTo(request);
        assertThat(ack.path("duplicate").asBoolean()).isFalse();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM hrms_change_task WHERE hrms_request_id = ?",
                                "TRAVEL_REIMB:" + request))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT staging_status FROM intf_staging_txn WHERE txn_type ="
                                        + " 'TRAVEL_REIMB' AND request_id = ?",
                                String.class,
                                request))
                .isEqualTo("APPLIED");
        MvcResult again = call(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, null, body);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(again).path("status").asString()).isEqualTo("Accepted");
        assertThat(read(again).path("duplicate").asBoolean()).isTrue();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM intf_staging_txn WHERE txn_type ="
                                        + " 'TRAVEL_REIMB' AND request_id = ?",
                                request))
                .as("never staged twice")
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM hrms_change_task WHERE hrms_request_id = ?",
                                "TRAVEL_REIMB:" + request))
                .isEqualTo(1);
        // The same request_id on another API is another request (travel 4516 and mobile 4516
        // coexist).
        MvcResult other =
                call(
                        HrmsApi.REQUEST_MOBILE_REIMBURSEMENT,
                        null,
                        "{\"request_id\":"
                                + request
                                + ",\"emp_num\":"
                                + emp
                                + ",\"sanction_amount\":10}");
        assertThat(other.getResponse().getStatus()).isEqualTo(202);
        // Status from the staging record, with the amount as received (SRS-INT-35).
        MvcResult status =
                call(
                        HrmsApi.GET_REQUEST_PAY_TRAVEL_ALLOWANCE,
                        "request_id=" + request + "&reim_allow_id=77",
                        null);
        assertThat(status.getResponse().getStatus()).isEqualTo(200);
        JsonNode s = read(status);
        assertThat(s.path("status").asString()).isEqualTo("Accepted");
        assertThat(s.path("amount").asString()).isEqualTo("2500");
        assertThat(s.path("request_id").asString()).isEqualTo(request);
        assertThat(status.getResponse().getContentAsString())
                .doesNotContainIgnoringCase("pan")
                .doesNotContainIgnoringCase("bank");
    }

    @Test
    void SRS_INT_26_aRefusalByTheOwningModuleIsRejectedWithItsReason() throws Exception {
        // A medical claim of a separated employee is refused by the allowance module (SRS-11-4).
        String emp = employee(12);
        jdbc.update("UPDATE employee SET employment_status = 'SEPARATED' WHERE emp_num = ?", emp);
        String request = id();
        MvcResult refused =
                call(
                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                        null,
                        travel(request, emp, lastMonth(), "100"));
        assertThat(refused.getResponse().getStatus())
                .as(refused.getResponse().getContentAsString())
                .isEqualTo(422);
        JsonNode ack = read(refused);
        assertThat(ack.path("status").asString()).isEqualTo("Rejected");
        assertThat(ack.path("reason").asString()).startsWith("ALW-");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT staging_status FROM intf_staging_txn WHERE txn_type ="
                                        + " 'TRAVEL_REIMB' AND request_id = ?",
                                String.class,
                                request))
                .isEqualTo("REJECTED");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM interface_exception e JOIN intf_staging_txn s"
                                    + " ON s.staging_id = e.staging_id WHERE s.request_id = ? AND"
                                    + " e.remediation_status = 'OPEN'",
                                request))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM hrms_change_task WHERE hrms_request_id = ?",
                                "TRAVEL_REIMB:" + request))
                .isZero();
        // A repeat of the rejected request answers the earlier result and stages nothing.
        MvcResult repeat =
                call(
                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                        null,
                        travel(request, emp, lastMonth(), "100"));
        assertThat(repeat.getResponse().getStatus()).isEqualTo(422);
        assertThat(read(repeat).path("duplicate").asBoolean()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE request_id = ?", request))
                .isEqualTo(1);
    }

    // SRS-INT-27: labour have no HRMS interface.
    @Test
    void SRS_INT_27_aLabourMemberIsNotAnHrmsEmployee() throws Exception {
        String labour =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_type = 'DEPT_LABOUR' AND"
                            + " record_status <> 'CANCELLED' ORDER BY emp_num FETCH FIRST 1 ROWS"
                            + " ONLY",
                        String.class);
        String request = id();
        MvcResult r =
                call(
                        HrmsApi.REQUEST_OTA,
                        null,
                        "{\"request_id\":"
                                + request
                                + ",\"emp_num\":\""
                                + labour
                                + "\",\"net_amt\":1}");
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(read(r).path("reason").asString()).contains("labour");
        assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE request_id = ?", request))
                .isZero();
    }

    @Test
    void SRS_INT_12_aRecordThatCannotBeStagedLeavesNoKeyAndIsQueued() throws Exception {
        String request = id();
        String good = travel(request, employee(13), lastMonth(), "100");
        long exceptionsBefore = count("SELECT COUNT(*) FROM interface_exception");
        // unknown employee
        MvcResult unknown =
                call(
                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                        null,
                        good.replace("\"emp_num\":" + employee(13), "\"emp_num\":99999999"));
        assertThat(unknown.getResponse().getStatus()).isEqualTo(422);
        assertThat(read(unknown).path("status").asString()).isEqualTo("Rejected");
        assertThat(read(unknown).path("reason").asString()).contains("not a Payroll employee");
        assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE request_id = ?", request))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM interface_exception"))
                .isGreaterThan(exceptionsBefore);
        // bad date, unknown field, Hindi text, negative amount: refused by name, never by value
        for (String bad :
                new String[] {
                    good.replace(lastMonth().toString(), "13/09/2023"),
                    good.replace(
                            "\"sanction_number\"",
                            "\"bad name\":\"ABCDE1234F\",\"sanction_number\""),
                    good.replace("IT travel", "यात्रा"),
                    good.replace("\"sanction_amount\":100", "\"sanction_amount\":-5")
                }) {
            MvcResult r = call(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, null, bad);
            assertThat(r.getResponse().getStatus()).isEqualTo(422);
            assertThat(read(r).path("reason").asString()).doesNotContain("ABCDE1234F");
        }
        assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE request_id = ?", request))
                .isZero();
        // ...so HRMS may resend it corrected under the same request_id.
        assertThat(call(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, null, good).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE request_id = ?", request))
                .isEqualTo(1);
    }

    @Test
    void SRS_INT_12_unreadableOversizedAndRepeatedInputIsRefused() throws Exception {
        String good = travel(id(), employee(14), lastMonth(), "100");
        assertThat(
                        call(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, null, "{\"request_id\":")
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        call(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, null, "[" + good + "]")
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        call(
                                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                                        null,
                                        good.replace("\"emp_num\"", "\"request_id\":9,\"emp_num\""))
                                .getResponse()
                                .getStatus())
                .as("duplicate key")
                .isEqualTo(400);
        assertThat(
                        call(
                                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                                        null,
                                        good.replace("IT travel", "x".repeat(300_000)))
                                .getResponse()
                                .getStatus())
                .isEqualTo(413);
        MvcResult noType =
                mvc.perform(
                                authed(post(BASE + "/request-pay-travel-allowance"))
                                        .contentType(MediaType.TEXT_PLAIN)
                                        .content(good))
                        .andReturn();
        assertThat(noType.getResponse().getStatus()).isEqualTo(400);
        MvcResult repeated =
                mvc.perform(
                                authed(get(BASE + "/get-ota-status"))
                                        .param("request_id", "1", "2")
                                        .param("emp_ota_id", "1"))
                        .andReturn();
        assertThat(repeated.getResponse().getStatus()).isEqualTo(400);
        MvcResult wrongMethod =
                mvc.perform(authed(get(BASE + "/request-pay-travel-allowance"))).andReturn();
        assertThat(wrongMethod.getResponse().getStatus()).isEqualTo(405);
    }

    @Test
    void SRS_INT_16_anInterfaceWithoutSettingsInForceIsRefusedAndNothingIsStaged()
            throws Exception {
        // The demo settings configure every HRMS interface (V077_44): this one's are ended here.
        long id =
                jdbc.queryForObject(
                        "SELECT interface_id FROM interface_registry WHERE interface_code ="
                                + " 'HRMS_PAY_LIVERIES'",
                        Long.class);
        jdbc.update(
                "UPDATE interface_config SET effective_to = ? WHERE interface_id = ?",
                Date.valueOf(LocalDate.now(IST).minusDays(1)),
                id);
        cacheChanged(jdbc, InterfaceSettingsService.REGION);
        try {
            String request = id();
            MvcResult r =
                    call(
                            HrmsApi.REQUEST_PAY_LIVERIES_REIMBURSEMENT,
                            null,
                            "{\"request_id\":" + request + ",\"emp_num\":" + employee(15) + "}");
            assertThat(r.getResponse().getStatus()).isEqualTo(503);
            assertThat(read(r).path("errorCode").asString()).isEqualTo("INT-503-NOT-CONFIGURED");
            assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE request_id = ?", request))
                    .isZero();
        } finally {
            jdbc.update(
                    "UPDATE interface_config SET effective_to = NULL WHERE interface_id = ?", id);
            cacheChanged(jdbc, InterfaceSettingsService.REGION);
        }
    }

    // PENDING API-23-8: class B and unmapped types are staged and answered Received, nothing
    // applied.
    @Test
    void SRS_INT_26_aTypeWithNoApplyRuleIsKeptAsReceivedForItsReasonAndAppliesNothing()
            throws Exception {
        String emp = employee(16);
        long tasksBefore = count("SELECT COUNT(*) FROM hrms_change_task");
        String ota = id();
        MvcResult r =
                call(
                        HrmsApi.REQUEST_OTA,
                        null,
                        "{\"request_id\":"
                                + ota
                                + ",\"emp_num\":"
                                + emp
                                + ",\"gross_amount\":100,\"net_amt\":100,\"ota_yymm\":2303,\"otahrs\":12,\"otarate\":10}");
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        assertThat(read(r).path("status").asString()).isEqualTo("Received");
        assertThat(read(r).path("reason").asString()).contains("API-23-11");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM intf_staging_event ev JOIN intf_staging_txn s"
                                    + " ON s.staging_id = ev.staging_id WHERE s.request_id = ? AND"
                                    + " ev.event_type = 'NO_APPLY_RULE'",
                                ota))
                .isEqualTo(1);
        String news = id();
        LocalDate m = lastMonth();
        String yymm = String.format("%02d%02d", m.getYear() % 100, m.getMonthValue());
        assertThat(
                        call(
                                        HrmsApi.REQUEST_NEWSPAPER_ALLOWANCE,
                                        null,
                                        "{\"request_id\":"
                                                + news
                                                + ",\"emp_num\":"
                                                + emp
                                                + ",\"sanction_amount\":300,\"from_yymm\":"
                                                + yymm
                                                + ",\"to_yymm\":"
                                                + yymm
                                                + "}")
                                .getResponse()
                                .getStatus())
                .as("claim type not mapped")
                .isEqualTo(202);
        String cafeteria = id();
        MvcResult c =
                call(
                        HrmsApi.POST_CAFETERIA_ALLOWANCE,
                        "request_id=" + cafeteria + "&emp_num=" + emp,
                        "[{\"request_id\":\""
                                + cafeteria
                                + "\",\"emp_num\":\""
                                + emp
                                + "\",\"pay_code\":\"211\",\"percent\":\"6\",\"fin_year\":\"202526\",\"effective_dt\":\""
                                + m
                                + "\",\"attribute1\":\"CA1\"},"
                                + "{\"request_id\":\""
                                + cafeteria
                                + "\",\"emp_num\":\""
                                + emp
                                + "\",\"pay_code\":\"208\",\"percent\":\"10\"}]");
        assertThat(c.getResponse().getStatus()).isEqualTo(202);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM intf_staging_txn WHERE txn_type ="
                                        + " 'CAFETERIA_SELECTION' AND request_id = ?",
                                cafeteria))
                .isEqualTo(1);
        MvcResult mixed =
                call(
                        HrmsApi.POST_CAFETERIA_ALLOWANCE,
                        null,
                        "[{\"request_id\":\"1\",\"emp_num\":\""
                                + emp
                                + "\"},{\"request_id\":\"2\",\"emp_num\":\""
                                + emp
                                + "\",\"effective_dt\":\"2023-02-30\"}]");
        assertThat(mixed.getResponse().getStatus())
                .as("several requests: one answer each")
                .isEqualTo(207);
        assertThat(read(mixed).path("accepted").asInt()).isEqualTo(1);
        assertThat(read(mixed).path("rejected").asInt()).isEqualTo(1);
        MvcResult hra =
                call(
                        HrmsApi.UPDATING_HRA_DA,
                        "request_id=" + id() + "&emp_num=" + emp + "&request_for=HRA&user_id=1",
                        "[{\"request_id\":"
                                + id()
                                + ",\"request_for\":\"HRA\",\"effective_date\":\""
                                + m
                                + "\",\"hra_rate\":65.21}]");
        assertThat(hra.getResponse().getStatus())
                .as("request_id of the query differs from the body")
                .isEqualTo(422);
        String hraId = id();
        MvcResult hraOk =
                call(
                        HrmsApi.UPDATING_HRA_DA,
                        "request_id=" + hraId + "&emp_num=" + emp + "&request_for=HRA&user_id=1",
                        "[{\"request_id\":"
                                + hraId
                                + ",\"request_for\":\"HRA\",\"effective_date\":\""
                                + m
                                + "\",\"hra_rate\":65.21}]");
        assertThat(hraOk.getResponse().getStatus()).isEqualTo(202);
        assertThat(count("SELECT COUNT(*) FROM hrms_change_task"))
                .as("nothing applied")
                .isEqualTo(tasksBefore);
    }

    // SRS-INT-35: status of one request, never another API's, unknown = not found.
    @Test
    void SRS_INT_35_aStatusCallAnswersOnlyItsOwnApisRequestAndAnUnknownOneIsNotFound()
            throws Exception {
        String emp = employee(17);
        String ota = id();
        call(
                HrmsApi.REQUEST_OTA,
                null,
                "{\"request_id\":" + ota + ",\"emp_num\":" + emp + ",\"net_amt\":100.50}");
        MvcResult own = call(HrmsApi.GET_OTA_STATUS, "request_id=" + ota + "&emp_ota_id=5", null);
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(own).path("status").asString()).isEqualTo("Received");
        assertThat(read(own).path("amount").asString()).isEqualTo("100.5");
        MvcResult otherApi =
                call(
                        HrmsApi.GET_REQUEST_MOBILE_REIMBURSEMENT,
                        "request_id=" + ota + "&reim_allow_id=5",
                        null);
        assertThat(otherApi.getResponse().getStatus()).as("another API's request").isEqualTo(404);
        assertThat(read(otherApi).path("errorCode").asString()).isEqualTo("INT-404-HRMS-REQUEST");
        assertThat(
                        call(HrmsApi.GET_OTA_STATUS, "request_id=1&emp_ota_id=5", null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(
                        call(HrmsApi.GET_OTA_STATUS, "request_id=" + ota, null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(422);
        assertThat(
                        call(HrmsApi.GET_OTA_STATUS, "emp_ota_id=5&request_id=' OR 1=1 --", null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(422);
        // keyed by employee: the id parameter is the request and the employee must be its employee
        String medical = id();
        call(
                HrmsApi.REQUEST_MEDICAL_CLAIM,
                null,
                "{\"request_id\":" + medical + ",\"emp_num\":" + emp + ",\"netamt\":4413}");
        MvcResult byEmployee =
                call(
                        HrmsApi.GET_MEDICAL_CLAIM_STATUS,
                        "emp_num=" + emp + "&payment_id=" + medical,
                        null);
        assertThat(byEmployee.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(byEmployee).path("amount").asString()).isEqualTo("4413");
        assertThat(
                        call(
                                        HrmsApi.GET_MEDICAL_CLAIM_STATUS,
                                        "emp_num=" + employee(18) + "&payment_id=" + medical,
                                        null)
                                .getResponse()
                                .getStatus())
                .as("another employee's request")
                .isEqualTo(404);
    }

    // SRS-INT-31, R-DEL: a cancellation request is recorded once; nothing is deleted or edited.
    @Test
    void SRS_INT_31_aCancelIsRecordedOnceAndChangesNothing() throws Exception {
        String ota = id();
        call(
                HrmsApi.REQUEST_OTA,
                null,
                "{\"request_id\":" + ota + ",\"emp_num\":" + employee(19) + ",\"net_amt\":10}");
        String body = "{\"request_id\":" + ota + ",\"emp_ota_id\":99}";
        MvcResult first = call(HrmsApi.CANCEL_OTA, null, body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(first).path("status").asString()).isEqualTo("Cancellation requested");
        assertThat(read(first).path("duplicate").asBoolean()).isFalse();
        MvcResult second = call(HrmsApi.CANCEL_OTA, null, body);
        assertThat(read(second).path("duplicate").asBoolean()).isTrue();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM intf_staging_event ev JOIN intf_staging_txn s"
                                    + " ON s.staging_id = ev.staging_id WHERE s.request_id = ? AND"
                                    + " ev.event_type = 'CANCEL_REQUESTED'",
                                ota))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'intf_staging_txn' AND field_name = 'cancellation' AND"
                                        + " reason LIKE '%EMP_OTA%'"))
                .isPositive();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT staging_status FROM intf_staging_txn WHERE txn_type ="
                                        + " 'EMP_OTA' AND request_id = ?",
                                String.class,
                                ota))
                .isEqualTo("RECEIVED");
        assertThat(
                        read(call(
                                        HrmsApi.GET_OTA_STATUS,
                                        "request_id=" + ota + "&emp_ota_id=99",
                                        null))
                                .path("status")
                                .asString())
                .isEqualTo("Cancellation requested");
        assertThat(
                        call(HrmsApi.CANCEL_OTA, null, "{\"request_id\":1,\"emp_ota_id\":99}")
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(
                        call(
                                        HrmsApi.CANCEL_MEDICAL_CLAIM,
                                        null,
                                        "{\"request_id\":" + ota + ",\"payment_id\":99}")
                                .getResponse()
                                .getStatus())
                .as("another API's request")
                .isEqualTo(404);
        assertThat(
                        call(
                                        HrmsApi.CANCEL_OTA,
                                        null,
                                        "{\"request_id\":"
                                                + ota
                                                + ",\"emp_ota_id\":99,\"force\":\"Y\"}")
                                .getResponse()
                                .getStatus())
                .as("mass assignment")
                .isEqualTo(400);
        assertThat(
                        call(HrmsApi.CANCEL_OTA, null, "{\"request_id\":" + ota + "}")
                                .getResponse()
                                .getStatus())
                .isEqualTo(422);
    }

    // PENDING API-23-8: the reports are routed and refused until the owner decides.
    @Test
    void SRS_INT_1_theReportApisAreRoutedAndRefusedAsNotEnabled() throws Exception {
        for (HrmsApi api :
                new HrmsApi[] {
                    HrmsApi.GETPAYSLIP,
                    HrmsApi.FORECAST_REPORT,
                    HrmsApi.GETCPFSLIP,
                    HrmsApi.GETSALARYCONTROLREGISTER
                }) {
            MvcResult r = call(api, "emp_num=" + employee(20) + "&yymm=2401", null);
            assertThat(r.getResponse().getStatus()).as(api.code()).isEqualTo(501);
            assertThat(read(r).path("errorCode").asString()).isEqualTo("INT-501-HRMS-NOT-ENABLED");
        }
    }

    // SRS-INT-32: each call is one window on X-03 with its counts balanced.
    @Test
    void SRS_INT_32_everyCallIsOneBalancedWindow() throws Exception {
        String request = id();
        call(
                HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                null,
                travel(request, employee(21), lastMonth(), "100"));
        call(
                HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                null,
                travel(request, employee(21), lastMonth(), "100"));
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM interface_run WHERE batch_reference = ? AND"
                                        + " received_count = accepted_count + rejected_count AND"
                                        + " completed_at IS NOT NULL",
                                request))
                .isEqualTo(2);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM interface_run WHERE batch_reference = ? AND"
                                        + " run_status = 'FAILED'",
                                request))
                .isZero();
    }

    // ---------------------------------------------------------------- edge cases (owner
    // 2026-11-05)

    private String stagedPayload(String type, String request) {
        return jdbc.queryForObject(
                "SELECT payload FROM intf_staging_txn WHERE txn_type = ? AND request_id = ?",
                String.class,
                type,
                request);
    }

    @Test
    void SRS_INT_5_theSameRequestIdWithDifferentContentIsConflictingAndNeverOverwritten()
            throws Exception {
        String request = id();
        String emp = employee(22);
        String first = "{\"request_id\":" + request + ",\"emp_num\":" + emp + ",\"net_amt\":100}";
        assertThat(call(HrmsApi.REQUEST_OTA, null, first).getResponse().getStatus()).isEqualTo(202);
        MvcResult changed = call(HrmsApi.REQUEST_OTA, null, first.replace(":100}", ":999}"));
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(changed).path("status").asString()).isEqualTo("Rejected");
        assertThat(read(changed).path("reason").asString()).contains("different content");
        assertThat(stagedPayload("EMP_OTA", request)).isEqualTo(first);
        // the same content again, laid out differently, is still the same request
        MvcResult same =
                call(
                        HrmsApi.REQUEST_OTA,
                        null,
                        "{ \"net_amt\" : 100 , \"emp_num\":"
                                + emp
                                + ",\"request_id\":"
                                + request
                                + " }");
        assertThat(same.getResponse().getStatus()).isEqualTo(202);
        assertThat(read(same).path("duplicate").asBoolean()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE request_id = ?", request))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM intf_staging_event ev JOIN intf_staging_txn s"
                                    + " ON s.staging_id = ev.staging_id WHERE s.request_id = ? AND"
                                    + " ev.event_type = 'CONFLICT_REJECTED'",
                                request))
                .isEqualTo(1);
    }

    @Test
    void SRS_INT_31_aRejectedRequestIsResentUnderANewKeyNamingIt() throws Exception {
        String emp = employee(23);
        jdbc.update("UPDATE employee SET employment_status = 'SEPARATED' WHERE emp_num = ?", emp);
        String rejected = id();
        MvcResult first =
                call(
                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                        null,
                        travel(rejected, emp, lastMonth(), "100"));
        assertThat(first.getResponse().getStatus()).isEqualTo(422);
        jdbc.update("UPDATE employee SET employment_status = 'IN_SERVICE' WHERE emp_num = ?", emp);
        // same key: still the earlier answer
        assertThat(
                        read(call(
                                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                                        null,
                                        travel(rejected, emp, lastMonth(), "100")))
                                .path("duplicate")
                                .asBoolean())
                .isTrue();
        String resend = id();
        MvcResult ok =
                call(
                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                        "reprocess_of=" + rejected,
                        travel(resend, emp, lastMonth(), "100"));
        assertThat(ok.getResponse().getStatus())
                .as(ok.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT staging_status FROM intf_staging_txn WHERE request_id = ?",
                                String.class,
                                rejected))
                .isEqualTo("REPROCESSED");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM intf_staging_txn s JOIN intf_staging_txn o ON"
                                    + " o.staging_id = s.reprocess_of_staging_id WHERE s.request_id"
                                    + " = ? AND o.request_id = ?",
                                resend,
                                rejected))
                .isEqualTo(1);
        // naming something that was not rejected, or unknown, or not an id, is refused
        MvcResult notRejected =
                call(
                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                        "reprocess_of=" + resend,
                        travel(id(), employee(24), lastMonth(), "100"));
        assertThat(notRejected.getResponse().getStatus()).isEqualTo(422);
        assertThat(
                        call(
                                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                                        "reprocess_of=unknown",
                                        travel(id(), employee(24), lastMonth(), "100"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(422);
        assertThat(
                        call(
                                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                                        "reprocess_of=a%27b",
                                        travel(id(), employee(24), lastMonth(), "100"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(422);
    }

    @Test
    void SRS_INT_27_aSeparatedEmployeeIsStillStagedAndTheOwningModuleDecides() throws Exception {
        String emp = employee(25);
        jdbc.update("UPDATE employee SET employment_status = 'SEPARATED' WHERE emp_num = ?", emp);
        String request = id();
        MvcResult r =
                call(
                        HrmsApi.REQUEST_OTA,
                        null,
                        "{\"request_id\":" + request + ",\"emp_num\":" + emp + "}");
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE request_id = ?", request))
                .isEqualTo(1);
    }

    @Test
    void SRS_INT_32_oneBadRequestAmongGoodOnesIsRejectedWithItsReasonAndEveryWindowBalances()
            throws Exception {
        String emp = employee(26);
        String a = id();
        String b = id();
        String c = id();
        String body =
                "[{\"request_id\":\""
                        + a
                        + "\",\"emp_num\":\""
                        + emp
                        + "\",\"pay_code\":\"211\",\"percent\":\"6\"},"
                        + "{\"request_id\":\""
                        + b
                        + "\",\"emp_num\":\""
                        + emp
                        + "\",\"percent\":\"-1\"},"
                        + "{\"request_id\":\""
                        + c
                        + "\",\"emp_num\":\""
                        + emp
                        + "\",\"pay_code\":\"208\"}]";
        MvcResult r = call(HrmsApi.POST_CAFETERIA_ALLOWANCE, null, body);
        assertThat(r.getResponse().getStatus()).isEqualTo(207);
        JsonNode answer = read(r);
        assertThat(answer.path("received").asInt()).isEqualTo(3);
        assertThat(answer.path("accepted").asInt()).isEqualTo(2);
        assertThat(answer.path("rejected").asInt()).isEqualTo(1);
        assertThat(answer.path("results").get(0).path("status").asString()).isEqualTo("Received");
        assertThat(answer.path("results").get(1).path("status").asString()).isEqualTo("Rejected");
        assertThat(answer.path("results").get(1).path("reason").asString()).contains("percent");
        assertThat(answer.path("results").get(2).path("status").asString()).isEqualTo("Received");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM intf_staging_txn WHERE txn_type ="
                                        + " 'CAFETERIA_SELECTION' AND request_id IN (?, ?, ?)",
                                a,
                                b,
                                c))
                .isEqualTo(2);
        for (String key : new String[] {a, b, c}) {
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM interface_run WHERE batch_reference = ?"
                                        + " AND received_count = accepted_count + rejected_count"
                                        + " AND completed_at IS NOT NULL",
                                    key))
                    .isEqualTo(1);
        }
        // a body of requests for an employee nobody knows: every one rejected, each with its reason
        MvcResult none =
                call(
                        HrmsApi.POST_CAFETERIA_ALLOWANCE,
                        null,
                        "[{\"request_id\":\""
                                + id()
                                + "\",\"emp_num\":\"99999999\"},{\"request_id\":\""
                                + id()
                                + "\",\"emp_num\":\"99999998\"}]");
        assertThat(read(none).path("rejected").asInt()).isEqualTo(2);
    }

    @Test
    void SRS_INT_12_anEmptyBodyAndOtherMethodsAreRefusedWithoutDetail() throws Exception {
        String path = BASE + "/request-ota";
        MvcResult empty =
                mvc.perform(authed(post(path)).contentType(MediaType.APPLICATION_JSON).content(""))
                        .andReturn();
        assertThat(empty.getResponse().getStatus()).isEqualTo(400);
        MvcResult head =
                mvc.perform(
                                authed(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.head(path)))
                        .andReturn();
        MvcResult options =
                mvc.perform(
                                authed(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.options(path)))
                        .andReturn();
        MvcResult trace =
                mvc.perform(
                                authed(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.request(
                                                org.springframework.http.HttpMethod.TRACE, path)))
                        .andReturn();
        MvcResult patch =
                mvc.perform(
                                authed(
                                                org.springframework.test.web.servlet.request
                                                        .MockMvcRequestBuilders.patch(path))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andReturn();
        MvcResult delete =
                mvc.perform(
                                authed(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.delete(path)))
                        .andReturn();
        for (MvcResult r : new MvcResult[] {head, trace, patch, delete}) {
            assertThat(r.getResponse().getStatus()).isIn(400, 403, 404, 405);
        }
        assertThat(options.getResponse().getStatus()).isIn(200, 403, 404, 405);
        assertThat(options.getResponse().getContentAsString()).isEmpty();
        MvcResult doubleSlash =
                mvc.perform(
                                post(BASE + "//request-ota")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andReturn();
        assertThat(doubleSlash.getResponse().getStatus())
                .as("a path spelled differently needs the same credentials")
                .isEqualTo(401);
        MvcResult anonymous =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .options(path))
                        .andReturn();
        assertThat(anonymous.getResponse().getStatus())
                .as("no credential, no answer about the route")
                .isIn(401, 403, 405);
        for (MvcResult r :
                new MvcResult[] {empty, head, options, trace, patch, delete, anonymous}) {
            assertThat(r.getResponse().getContentAsString())
                    .doesNotContain("Exception")
                    .doesNotContain("at in.gov")
                    .doesNotContain("org.springframework")
                    .doesNotContain(KEY)
                    .doesNotContain(SECRET);
        }
    }

    @Test
    void SRS_INT_1_pathsAreExactTrailingSlashCaseAndTraversalDoNotReachAnotherHandler()
            throws Exception {
        String body = "{\"request_id\":" + id() + ",\"emp_num\":" + employee(27) + "}";
        for (String path :
                new String[] {
                    BASE + "/request-ota/",
                    BASE + "/Request-OTA",
                    "/HRMS/request-ota",
                    BASE + "/request-ota;x=1",
                    BASE + "/../v1/integration/summary",
                    BASE + "/request-ota%2f..%2f",
                    BASE + "/request-ota/..",
                    BASE + "/%2e%2e/request-ota"
                }) {
            MvcResult r =
                    mvc.perform(
                                    authed(post(path))
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(body))
                            .andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isIn(400, 401, 403, 404);
        }
        assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE payload = ?", body)).isZero();
    }

    @Test
    void SRS_INT_4_theKeyOrTheTokenSentTwiceIsRefused() throws Exception {
        String body = "{\"request_id\":" + id() + ",\"emp_num\":" + employee(27) + "}";
        MvcResult twoKeys =
                mvc.perform(
                                post(BASE + "/request-ota")
                                        .header("api-key", KEY)
                                        .header("api-key", KEY)
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andReturn();
        assertThat(twoKeys.getResponse().getStatus()).isEqualTo(401);
        MvcResult twoTokens =
                mvc.perform(
                                post(BASE + "/request-ota")
                                        .header("api-key", KEY)
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andReturn();
        assertThat(twoTokens.getResponse().getStatus()).isEqualTo(401);
        // alg=none style: an unsigned token with a valid-looking body
        String unsigned =
                java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString((CLIENT + "|0|9999999999|x").getBytes())
                        + ".";
        MvcResult none =
                mvc.perform(
                                post(BASE + "/request-ota")
                                        .header("api-key", KEY)
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unsigned)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andReturn();
        assertThat(none.getResponse().getStatus()).isEqualTo(401);
        // a token whose signature byte was changed
        String tampered =
                token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
        MvcResult changed =
                mvc.perform(
                                post(BASE + "/request-ota")
                                        .header("api-key", KEY)
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andReturn();
        assertThat(changed.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void SRS_INT_4_neitherSideAcceptsTheOthersCredentials() throws Exception {
        // HRMS key and token on the screen API: no session, no access.
        MvcResult screen = mvc.perform(authed(get("/v1/integration/summary"))).andReturn();
        assertThat(screen.getResponse().getStatus()).isEqualTo(401);
        MvcResult write =
                mvc.perform(
                                authed(post("/v1/integration/exceptions/1/write-off"))
                                        .header("Sec-Fetch-Site", "cross-site")
                                        .header("Origin", "https://evil.example")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"reason\":\"x\"}"))
                        .andReturn();
        assertThat(write.getResponse().getStatus()).isIn(401, 403);
        // a browser session and its CSRF pair on an HRMS route: nothing
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        MvcResult asUser =
                mvc.perform(
                                userPost(
                                        admin,
                                        BASE + "/request-ota",
                                        java.util.Map.of("request_id", "1", "emp_num", "1")))
                        .andReturn();
        assertThat(asUser.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void SRS_INT_12_unlistedFieldsAreKeptAsReceivedNeverAppliedAndMarkupIsNeverEchoed()
            throws Exception {
        String request = id();
        String emp = employee(28);
        long tasks = count("SELECT COUNT(*) FROM hrms_change_task");
        String markup = "<script>alert(1)</script>";
        String body =
                "{\"request_id\":"
                        + request
                        + ",\"emp_num\":"
                        + emp
                        + ",\"remarks\":\""
                        + markup
                        + " '; DROP TABLE employee;"
                        + " --\",\"surprise_field\":\"kept\",\"net_amt\":\"1e2\",\"attribute3\":null}";
        MvcResult r = call(HrmsApi.REQUEST_OTA, null, body);
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        assertThat(r.getResponse().getContentAsString())
                .doesNotContain(markup)
                .doesNotContain("surprise_field");
        assertThat(stagedPayload("EMP_OTA", request)).contains("surprise_field").contains(markup);
        MvcResult status =
                call(HrmsApi.GET_OTA_STATUS, "request_id=" + request + "&emp_ota_id=1", null);
        assertThat(status.getResponse().getContentAsString())
                .doesNotContain(markup)
                .doesNotContain("DROP");
        assertThat(read(status).path("amount").asString()).isEqualTo("100");
        assertThat(count("SELECT COUNT(*) FROM hrms_change_task")).isEqualTo(tasks);
        assertThat(count("SELECT COUNT(*) FROM employee")).isPositive();
    }

    @Test
    void SRS_INT_26_aPaidOrAppliedRequestCanOnlyHaveItsCancellationRecorded() throws Exception {
        String request = id();
        String emp = employee(29);
        assertThat(
                        call(
                                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                                        null,
                                        travel(request, emp, lastMonth(), "100"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        MvcResult cancel =
                call(
                        HrmsApi.CANCEL_PAY_TRAVEL_ALLOWANCE,
                        null,
                        "{\"request_id\":" + request + ",\"reim_allow_id\":5}");
        assertThat(cancel.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(cancel).path("status").asString()).isEqualTo("Cancellation requested");
        assertThat(read(cancel).path("staging_status").asString()).isEqualTo("APPLIED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT staging_status FROM intf_staging_txn WHERE txn_type ="
                                        + " 'TRAVEL_REIMB' AND request_id = ?",
                                String.class,
                                request))
                .isEqualTo("APPLIED");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM hrms_change_task WHERE hrms_request_id = ?",
                                "TRAVEL_REIMB:" + request))
                .isEqualTo(1);
    }

    @Test
    void SRS_INT_16_anExpiredInterfaceConfigurationRefusesTheCallAndStagesNothing()
            throws Exception {
        long id =
                jdbc.queryForObject(
                        "SELECT interface_id FROM interface_registry WHERE interface_code ="
                                + " 'HRMS_LTC_ENCASH'",
                        Long.class);
        jdbc.update(
                "UPDATE interface_config SET effective_to = ? WHERE interface_id = ?",
                Date.valueOf(LocalDate.now(IST).minusDays(1)),
                id);
        cacheChanged(jdbc, InterfaceSettingsService.REGION);
        try {
            String request = id();
            MvcResult r =
                    call(
                            HrmsApi.REQUEST_LTC_ENCASH,
                            null,
                            "{\"request_id\":" + request + ",\"emp_num\":" + employee(19) + "}");
            assertThat(r.getResponse().getStatus()).isEqualTo(503);
            assertThat(read(r).path("errorCode").asString()).isEqualTo("INT-503-NOT-CONFIGURED");
            assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE request_id = ?", request))
                    .isZero();
        } finally {
            jdbc.update(
                    "UPDATE interface_config SET effective_to = NULL WHERE interface_id = ?", id);
            cacheChanged(jdbc, InterfaceSettingsService.REGION);
        }
    }

    @Test
    void SRS_INT_12_identicalRequestsAtOnceLeaveOneRecordAndDifferentOnesAreAllKept()
            throws Exception {
        String emp = employee(31);
        String same = id();
        String body = "{\"request_id\":" + same + ",\"emp_num\":" + emp + ",\"net_amt\":5}";
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            java.util.List<java.util.concurrent.Future<Integer>> same6 =
                    new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                same6.add(
                        pool.submit(
                                () ->
                                        call(HrmsApi.REQUEST_OTA, null, body)
                                                .getResponse()
                                                .getStatus()));
            }
            for (java.util.concurrent.Future<Integer> f : same6) {
                assertThat(f.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(202);
            }
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM intf_staging_txn WHERE txn_type ="
                                            + " 'EMP_OTA' AND request_id = ?",
                                    same))
                    .as("one staging row")
                    .isEqualTo(1);
            java.util.List<String> ids = new ArrayList<>();
            java.util.List<java.util.concurrent.Future<Integer>> different = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String other = id() + i;
                ids.add(other);
                different.add(
                        pool.submit(
                                () ->
                                        call(
                                                        HrmsApi.REQUEST_OTA,
                                                        null,
                                                        "{\"request_id\":"
                                                                + other
                                                                + ",\"emp_num\":"
                                                                + emp
                                                                + "}")
                                                .getResponse()
                                                .getStatus()));
            }
            for (java.util.concurrent.Future<Integer> f : different) {
                assertThat(f.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(202);
            }
            for (String other : ids) {
                assertThat(
                                count(
                                        "SELECT COUNT(*) FROM intf_staging_txn WHERE request_id ="
                                                + " ?",
                                        other))
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void R_PII_aRefusedCallLogsNeitherTheKeyTheTokenNorThePassword() throws Exception {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            String wrongKey = "wrong-key-" + SecureTokens.randomToken(16);
            String wrongPassword = "wrong-pw-" + SecureTokens.randomToken(16);
            mvc.perform(
                            post(BASE + "/request-ota")
                                    .header("api-key", wrongKey)
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andReturn();
            exchange(wrongPassword);
            call(
                    HrmsApi.REQUEST_OTA,
                    null,
                    "{\"request_id\":" + id() + ",\"emp_num\":" + employee(19) + "}");
            for (var event : logs.list) {
                String line = event.getFormattedMessage() + " " + event.getThrowableProxy();
                assertThat(line)
                        .doesNotContain(wrongKey)
                        .doesNotContain(wrongPassword)
                        .doesNotContain(KEY)
                        .doesNotContain(PASSWORD)
                        .doesNotContain(SECRET)
                        .doesNotContain(token);
            }
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM interface_exception WHERE"
                                            + " rejection_reason LIKE '%' || ? || '%' OR"
                                            + " rejection_reason LIKE '%' || ? || '%'",
                                    wrongKey, wrongPassword))
                    .isZero();
        } finally {
            root.detachAppender(logs);
        }
    }

    // ---------------------------------------------------------------- X-03 HRMS API view
    // (read-only)

    @Test
    void SRS_INT_17_theHrmsApiViewListsEveryApiWithItsConfigurationAndActivityAndNoSecret()
            throws Exception {
        call(
                HrmsApi.REQUEST_OTA,
                null,
                "{\"request_id\":" + id() + ",\"emp_num\":" + employee(20) + "}");
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        MvcResult all =
                mvc.perform(userGet(admin, "/v1/integration/hrms-apis").param("size", "100"))
                        .andReturn();
        assertThat(all.getResponse().getStatus()).isEqualTo(200);
        JsonNode page = read(all);
        assertThat(page.path("total").asInt()).isEqualTo(HrmsApi.values().length);
        assertThat(page.path("size").asInt()).isEqualTo(100);
        String text = all.getResponse().getContentAsString();
        assertThat(text)
                .doesNotContain(KEY)
                .doesNotContain(SECRET)
                .doesNotContain(PASSWORD)
                .doesNotContain(token)
                .doesNotContain("http://")
                .doesNotContain("https://");
        JsonNode ota = null;
        JsonNode buyback = null;
        for (JsonNode item : page.path("items")) {
            if (item.path("code").asString().equals("request_ota")) {
                ota = item;
            }
            if (item.path("code").asString().equals("get_request_laptop_buyback")) {
                buyback = item;
            }
        }
        assertThat(ota).isNotNull();
        assertThat(ota.path("path").asString()).isEqualTo(BASE + "/request-ota");
        assertThat(ota.path("configured").asBoolean()).isTrue();
        assertThat(ota.path("direction").asString()).isEqualTo("INBOUND");
        assertThat(ota.path("method").asString()).isEqualTo("POST");
        assertThat(ota.path("selectionClass").asString()).isEqualTo("REQUIRED");
        assertThat(ota.path("interfaceCode").asString()).isEqualTo("HRMS_EMP_OTA");
        // The values in force for it (demo V077_44, or the class fixture where that is absent).
        assertThat(ota.path("retryCount").asInt())
                .isEqualTo(
                        jdbc.queryForObject(
                                "SELECT c.retry_count FROM interface_config c JOIN"
                                        + " interface_registry r ON r.interface_id = c.interface_id"
                                        + " WHERE r.interface_code = 'HRMS_EMP_OTA'",
                                Integer.class));
        assertThat(ota.path("timeoutSeconds").asInt())
                .isEqualTo(
                        jdbc.queryForObject(
                                "SELECT c.timeout_seconds FROM interface_config c JOIN"
                                        + " interface_registry r ON r.interface_id = c.interface_id"
                                        + " WHERE r.interface_code = 'HRMS_EMP_OTA'",
                                Integer.class));
        assertThat(ota.path("received").asLong()).isPositive();
        assertThat(ota.path("lastStatus").asString()).isIn("OK", "PARTIAL");
        assertThat(ota.path("specSignedOff").asBoolean()).isFalse();
        assertThat(buyback.path("configured").asBoolean()).isFalse();
        assertThat(buyback.path("path").isNull()).isTrue();
        // filters and paging, server side
        assertThat(
                        read(mvc.perform(
                                                userGet(admin, "/v1/integration/hrms-apis")
                                                        .param("configured", "false"))
                                        .andReturn())
                                .path("total")
                                .asInt())
                .isEqualTo(UNSET.size());
        assertThat(
                        read(mvc.perform(
                                                userGet(admin, "/v1/integration/hrms-apis")
                                                        .param("direction", "OUTBOUND"))
                                        .andReturn())
                                .path("total")
                                .asInt())
                .isZero();
        assertThat(
                        read(mvc.perform(
                                                userGet(admin, "/v1/integration/hrms-apis")
                                                        .param("class", "RULE_PENDING"))
                                        .andReturn())
                                .path("total")
                                .asInt())
                .as("class B of docs/integration/HRMS_POSTMAN_API_SELECTION.md (B=26)")
                .isEqualTo(26);
        JsonNode first =
                read(
                        mvc.perform(userGet(admin, "/v1/integration/hrms-apis").param("size", "10"))
                                .andReturn());
        assertThat(first.path("items").size()).isEqualTo(10);
        assertThat(
                        read(mvc.perform(
                                                userGet(admin, "/v1/integration/hrms-apis")
                                                        .param("size", "10")
                                                        .param("page", "5"))
                                        .andReturn())
                                .path("items")
                                .size())
                .as("the last page holds what is left after five full pages")
                .isEqualTo(HrmsApi.values().length - 50);
        assertThat(
                        mvc.perform(userGet(admin, "/v1/integration/hrms-apis").param("class", "X"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        mvc.perform(
                                        userGet(admin, "/v1/integration/hrms-apis")
                                                .param("status", "BAD"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        mvc.perform(userGet(admin, "/v1/integration/hrms-apis").param("days", "0"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }

    @Test
    void SRS_INT_17_theHrmsApiViewIsReadOnlyAndNeedsTheScreenPermission() throws Exception {
        assertThat(
                        mvc.perform(get("/v1/integration/hrms-apis"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isIn(401, 403);
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        MvcResult post =
                mvc.perform(userPost(admin, "/v1/integration/hrms-apis", java.util.Map.of()))
                        .andReturn();
        assertThat(post.getResponse().getStatus()).isIn(403, 404, 405);
        MvcResult put =
                mvc.perform(userPut(admin, "/v1/integration/hrms-apis", java.util.Map.of()))
                        .andReturn();
        assertThat(put.getResponse().getStatus()).isIn(403, 404, 405);
    }
}
