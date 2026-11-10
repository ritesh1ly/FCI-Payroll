package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SRS-INT-1, SRS-INT-4, SRS-INT-6, SRS-INT-12, SRS-INT-31, SRS-INT-35; R-INT: the whole test
 * catalogue of the FCI HRMS mock server ({@code api-test-cases.json}) run through Payroll's {@link
 * HrmsGatewayService} with the real {@link HrmsClient}, each classified {@link HrmsReply} compared
 * with the case's expectation. The cases are stateful and ordered, so the mock is reset once at the
 * start and the cases run in the order of the file (JUnit dynamic tests). Then a few scenarios
 * Payroll needs that the catalogue lacks (API disabled and re-enabled, ten concurrent submits of
 * one request number, a 503 with Retry-After, a 401 that does not go away, a revoked token, a
 * server that is too slow).
 *
 * <p>Rules of the comparison:
 *
 * <ul>
 *   <li>2xx and success: ACCEPTED, plus the checks of {@code expect.json} on what the reply keeps
 *       (status, message, error_code, data, errors); an empty or truncated 200 is UNREADABLE; a 200
 *       with an error in the body is FAILED_IN_BODY.
 *   <li>4xx: REJECTED with the same HTTP status and {@code error_code}.
 *   <li>5xx and I/O faults: {@code FciException INT-502-CALL-FAILED} after the retries.
 *   <li>A call Payroll refuses before sending (INVALID_REQUEST) is a valid result when the mock
 *       expected a 400 for the same fault; the problems must be listed. Where the refusal came from
 *       the shape of the value only (a numeric text, an object for an array, an unknown field), the
 *       call is repeated in the shape the contract allows, and that must give the expected answer
 *       too.
 *   <li>What the gateway cannot express by design (custom headers, raw bodies, other paths, other
 *       methods) is skipped, with the reason, and listed in the report.
 * </ul>
 *
 * Runs only when the environment names the mock; the report is written to {@code
 * build/reports/hrms-scenarios.txt}:
 *
 * <pre>
 *   HRMS_MOCK_URL=http://localhost:8080  HRMS_MOCK_CASES=&lt;path of api-test-cases.json&gt;
 *   HRMS_MOCK_ADMIN_KEY=...  HRMS_MOCK_API_KEY=...  HRMS_MOCK_USER=...  HRMS_MOCK_PASSWORD=...
 *   ./gradlew :backend:test --tests '*HrmsMockScenarioLiveTest'
 * </pre>
 *
 * Keys and logins come from the environment only and are never written to a file.
 */
@EnabledIfEnvironmentVariable(named = "HRMS_MOCK_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HrmsMockScenarioLiveTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CALL_FAILED = "INT-502-CALL-FAILED";
    private static final String NOT_CONFIGURED = "INT-503-HRMS-API-NOT-CONFIGURED";
    private static final long RUN = 100_000 + (System.currentTimeMillis() / 1000) % 800_000;
    private static final Pattern INDEX = Pattern.compile("(.+)\\[(\\d+)]");
    private static final Set<String> VISIBLE =
            Set.of("status", "status_code", "error_code", "message", "data", "errors");
    private static final Set<String> HELD =
            Set.of(
                    "request-direct-payroll",
                    "get-pension-slip",
                    "cpf-generation",
                    "get-basic-syncup-status",
                    "get-payroll-statust");
    private static final String TRIGGER_GROUP = "Per-API 400 / 500 by trigger value";

    private enum Status {
        PASSED,
        COERCED,
        REFUSED,
        SKIPPED,
        FAILED
    }

    private record Rec(String group, String name, Status status, String detail) {}

    private record Got(HrmsReply reply, FciException failure) {
        String describe() {
            return failure != null
                    ? "FciException " + failure.getMessage()
                    : reply.outcome()
                            + " HTTP "
                            + reply.httpStatus()
                            + (reply.errorCode() == null ? "" : " " + reply.errorCode());
        }
    }

    private final List<HrmsClient> clients = new ArrayList<>();
    private final List<Rec> recs = Collections.synchronizedList(new ArrayList<>());
    private final HrmsContractCatalog contract = HrmsContractCatalog.load();
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
    private final Map<String, HrmsApi> byPath = new HashMap<>();
    private int unchecked;
    private JsonNode cases;
    private HrmsGatewayService main;

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.strip();
    }

    @BeforeAll
    void start() throws Exception {
        for (HrmsApi api : HrmsApi.values()) {
            byPath.put(api.collectionPath().substring(1), api);
        }
        String file = env("HRMS_MOCK_CASES");
        assertThat(file).as("HRMS_MOCK_CASES").isNotEmpty();
        cases = json(Files.readString(Path.of(file), StandardCharsets.UTF_8)).get("cases");
        HttpResponse<String> reset = admin("POST", "/mock-admin/reset", null);
        assertThat(reset.statusCode()).as("reset of the mock: " + reset.body()).isEqualTo(200);
        main = gateway(env("HRMS_MOCK_USER"), env("HRMS_MOCK_PASSWORD"), 1, 5, 5);
    }

    @AfterAll
    void finish() throws IOException {
        report();
        clients.forEach(HrmsClient::close);
    }

    // ------------------------------------------------------------------ gateway and admin

    private HrmsGatewayService gateway(
            String user, String password, int retries, int timeoutSeconds, int concurrent) {
        Map<String, ApiUrl> urls = new HashMap<>();
        Map<String, Limit> limits = new HashMap<>();
        limits.put("connection_test", new Limit(2, 1000));
        for (HrmsApi api : HrmsApi.values()) {
            urls.put(api.code(), new ApiUrl(api.collectionPath(), "OUTBOUND"));
            limits.put(api.code(), new Limit(concurrent, 5000));
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
                                50, 100, 5, 30, 60, 1, env("HRMS_MOCK_API_KEY"), null, null, null),
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
        return new HrmsGatewayService(client, contract);
    }

    private HttpResponse<String> admin(String method, String path, String body)
            throws IOException, InterruptedException {
        String base = env("HRMS_MOCK_URL");
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String key = env("HRMS_MOCK_ADMIN_KEY");
        assertThat(key).as("HRMS_MOCK_ADMIN_KEY").isNotEmpty();
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("X-Admin-Key", key)
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static Got invoke(
            HrmsGatewayService g,
            HrmsApi api,
            Map<String, String> query,
            JsonNode body,
            String key) {
        try {
            return new Got(g.call(api, query, body, key), null);
        } catch (FciException e) {
            return new Got(null, e);
        }
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    // ------------------------------------------------------------------ the catalogue

    @TestFactory
    @Order(1)
    Stream<DynamicTest> catalogue() throws IOException {
        return IntStream.range(0, cases.size())
                .mapToObj(
                        i -> {
                            JsonNode c = cases.get(i);
                            String title =
                                    "%03d %s | %s"
                                            .formatted(
                                                    i,
                                                    c.get("group").asString(),
                                                    c.get("name").asString());
                            return DynamicTest.dynamicTest(title, () -> runCase(i, c));
                        });
    }

    private void runCase(int index, JsonNode c) {
        String group = c.get("group").asString();
        String name = c.get("name").asString();
        Rec rec;
        try {
            rec = execute(index, c);
        } catch (RuntimeException | AssertionError e) {
            rec = new Rec(group, name, Status.FAILED, "exception: " + cut(String.valueOf(e)));
        }
        recs.add(rec);
        if (rec.status() == Status.SKIPPED) {
            Assumptions.assumeTrue(false, rec.detail());
        }
        if (rec.status() == Status.FAILED) {
            throw new AssertionError(rec.detail());
        }
    }

    private static Rec skipped(JsonNode c, String reason) {
        return new Rec(c.get("group").asString(), c.get("name").asString(), Status.SKIPPED, reason);
    }

    /** Why the gateway cannot express the case by design, or null when it can. */
    private String unsupported(JsonNode c, String path, String method) {
        if (c.path("liveOnly").asBoolean(false)) {
            return "liveOnly: a URL only real Tomcat rejects; the gateway encodes every"
                    + " parameter";
        }
        if (path.startsWith("mock-admin")) {
            return "mock admin API: outside the HRMS contract";
        }
        if (c.has("requestContentType")) {
            return "Content-Type variant: the gateway always sends application/json";
        }
        JsonNode headers = c.get("headers");
        if (headers != null) {
            for (String h : headers.propertyNames()) {
                String why = headerReason(h, headers.get(h), path);
                if (why != null) {
                    return why;
                }
            }
        }
        if (c.has("rawBody") && !c.get("rawBody").asString().isEmpty()) {
            return "raw / malformed body: the gateway serialises a JSON tree and cannot send"
                    + " malformed JSON";
        }
        return null;
    }

    private static String headerReason(String header, JsonNode value, String path) {
        String h = header.toLowerCase(java.util.Locale.ROOT);
        if (h.equals("authorization")) {
            if (value.isNull() && (path.isEmpty() || path.equals("token"))) {
                return null; // the contract sends no bearer to welcome and token: same thing
            }
            return "Authorization variant: the gateway sends only its own bearer token";
        }
        if (h.equals("api-key")) {
            return "api-key variant: the gateway sends only its configured key, where the"
                    + " contract says";
        }
        if (h.equals("x-mock-scenario")) {
            return "X-Mock-Scenario header: the gateway cannot add request headers";
        }
        if (h.equals("origin") || h.startsWith("access-control-")) {
            return "CORS header / preflight: a browser concern, not a gateway call";
        }
        if (h.equals("x-admin-key")) {
            return "mock admin API: outside the HRMS contract";
        }
        return "custom request header " + header + ": the gateway cannot add request headers";
    }

    private Rec execute(int index, JsonNode c) {
        String group = c.get("group").asString();
        String name = c.get("name").asString();
        JsonNode exp = c.get("expect");
        int want = exp.get("status").asInt();
        String method = c.get("method").asString();
        String full = c.get("path").asString();
        int qm = full.indexOf('?');
        String path = qm < 0 ? full : full.substring(0, qm);
        String why = unsupported(c, path, method);
        if (why != null) {
            return skipped(c, why);
        }
        Map<String, String> query = parseQuery(qm < 0 ? "" : full.substring(qm + 1));
        JsonNode body = c.has("body") ? c.get("body") : null;
        String key = "SC-" + RUN + "-" + index;
        String note = "";

        if (path.isEmpty() && method.equals("GET")) {
            Got got = welcome();
            return result(group, name, check(exp, got), "", got);
        }
        HrmsApi api = byPath.get(path);
        if (path.equals("get-payroll-statust") && group.equals("Fault injection")) {
            // the fault triggers apply to every API: use one Payroll has (medical claim status)
            api = HrmsApi.GET_MEDICAL_CLAIM_STATUS;
            query = q("emp_num", query.getOrDefault("emp_num", "1"), "payment_id", "1");
            note = "path substituted by get-medical-claim-status";
        }
        if (api == null) {
            if (HELD.contains(path)) {
                return skipped(c, "API not in HrmsApi (not required / held): " + path);
            }
            return skipped(c, "path outside the configured APIs (unknown or case variant)");
        }
        if (!api.method().equalsIgnoreCase(method)) {
            return skipped(
                    c,
                    "method variant: the gateway always uses the contract method ("
                            + api.method()
                            + ")");
        }
        if (api == HrmsApi.TOKEN) {
            return tokenCase(group, name, exp, want, body, key);
        }
        if ("PAYROLL_IN_PROCESS".equals(exp.path("error_code").asString(""))
                && query.containsKey("yymm")) {
            // the catalogue is written for the business date 2026-10-28; the mock follows today
            query.put("yymm", YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM")));
            note = "yymm = the current month (catalogue dated 2026-10-28)";
        }
        Got got = invoke(main, api, query, body, key);
        List<String> problems = check(exp, got);
        if (got.reply() != null && got.reply().outcome() == Outcome.INVALID_REQUEST) {
            return refusedBeforeSend(group, name, exp, want, api, query, body, key, got);
        }
        return result(group, name, problems, note, got);
    }

    /** The reply is INVALID_REQUEST: valid for a 400; the contract-shaped repeat must also pass. */
    private Rec refusedBeforeSend(
            String group,
            String name,
            JsonNode exp,
            int want,
            HrmsApi api,
            Map<String, String> query,
            JsonNode body,
            String key,
            Got got) {
        List<String> problems = new ArrayList<>();
        HrmsReply refusal = got.reply();
        if (refusal.problems().isEmpty()) {
            problems.add("INVALID_REQUEST without problems");
        }
        if (refusal.attempts() != 0) {
            problems.add("INVALID_REQUEST but " + refusal.attempts() + " call(s) were made");
        }
        // a 400 for a fault of the value itself (impossible date, object for an array) is the
        // answer; only where the value is a carrier (triggers) or the mock expects success is the
        // call repeated in the shape the contract allows
        boolean repeat = want != 400 || group.equals(TRIGGER_GROUP);
        JsonNode shaped = repeat ? coerce(api, body) : body;
        boolean changed = shaped != null && !shaped.equals(body);
        Got again = null;
        if (changed) {
            again = invoke(main, api, query, shaped, key + "-c");
            if (again.reply() != null && again.reply().outcome() == Outcome.INVALID_REQUEST) {
                changed = false; // still refused: only the refusal stands
            } else {
                problems.addAll(check(exp, again));
            }
        }
        if (!changed && want != 400) {
            problems.add(
                    "the mock expected HTTP "
                            + want
                            + " but Payroll refused the call before sending: "
                            + refusal.problems());
        }
        if (!problems.isEmpty()) {
            return new Rec(group, name, Status.FAILED, cut(String.join("; ", problems)));
        }
        String defects = cut(String.join(" | ", refusal.problems()));
        if (want == 400) {
            return new Rec(
                    group,
                    name,
                    Status.REFUSED,
                    "refused before sending: "
                            + defects
                            + (changed
                                    ? " (contract-shaped repeat: " + again.describe() + ")"
                                    : ""));
        }
        return new Rec(
                group,
                name,
                Status.COERCED,
                "sent after shaping to the contract (" + defects + "): " + again.describe());
    }

    private static Rec result(
            String group, String name, List<String> problems, String note, Got got) {
        if (!problems.isEmpty()) {
            return new Rec(group, name, Status.FAILED, cut(String.join("; ", problems)));
        }
        return new Rec(
                group, name, Status.PASSED, (note.isEmpty() ? "" : note + "; ") + got.describe());
    }

    private Got welcome() {
        try {
            return new Got(main.welcome(), null);
        } catch (FciException e) {
            return new Got(null, e);
        }
    }

    private static Map<String, String> parseQuery(String qs) {
        Map<String, String> m = new LinkedHashMap<>();
        if (qs.isEmpty()) {
            return m;
        }
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            m.put(
                    URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    /** POST token: the direct call, and a gateway that logs in with the case's credentials. */
    private Rec tokenCase(
            String group, String name, JsonNode exp, int want, JsonNode body, String key) {
        List<String> problems = new ArrayList<>();
        Got direct = invoke(main, HrmsApi.TOKEN, null, body, key);
        if (direct.reply() != null && direct.reply().outcome() == Outcome.INVALID_REQUEST) {
            return refusedBeforeSend(
                    group, name, exp, want, HrmsApi.TOKEN, null, body, key, direct);
        }
        problems.addAll(check(exp, direct));
        String login = "direct call";
        if (!group.equals("Postman samples - auth") && body != null && body.isObject()) {
            String user = body.path("username").asString("");
            String password = body.path("password").asString("");
            HrmsGatewayService g = gateway(user, password, want >= 500 ? 1 : 0, 5, 5);
            Got viaLogin =
                    invoke(
                            g,
                            HrmsApi.GET_OTA_STATUS,
                            q("request_id", "4517", "emp_ota_id", "1230"),
                            null,
                            key + "-l");
            login = "login gateway: " + viaLogin.describe();
            if (user.isBlank() || password.isBlank()) {
                if (viaLogin.failure() == null
                        || !NOT_CONFIGURED.equals(viaLogin.failure().getMessage())) {
                    problems.add(
                            "no login configured: expected "
                                    + NOT_CONFIGURED
                                    + ", got "
                                    + viaLogin.describe());
                }
            } else if (want >= 200 && want < 300) {
                if (viaLogin.reply() == null || viaLogin.reply().outcome() != Outcome.ACCEPTED) {
                    problems.add("login gateway: expected ACCEPTED, got " + viaLogin.describe());
                }
            } else if (viaLogin.failure() == null
                    || !CALL_FAILED.equals(viaLogin.failure().getMessage())) {
                problems.add(
                        "login gateway: expected " + CALL_FAILED + ", got " + viaLogin.describe());
            }
        }
        return result(group, name, problems, login, direct);
    }

    // ------------------------------------------------------------------ comparison

    private List<String> check(JsonNode exp, Got got) {
        List<String> problems = new ArrayList<>();
        int want = exp.get("status").asInt();
        if (want >= 500) {
            if (got.failure() == null) {
                problems.add(
                        "expected " + CALL_FAILED + " after the retries, got " + got.describe());
            } else if (!CALL_FAILED.equals(got.failure().getMessage())) {
                problems.add("expected " + CALL_FAILED + ", got " + got.describe());
            }
            return problems;
        }
        if (got.failure() != null) {
            problems.add("unexpected " + got.describe());
            return problems;
        }
        HrmsReply r = got.reply();
        if (want >= 200 && want < 300) {
            Outcome outcome = outcomeFor2xx(exp);
            if (r.outcome() != outcome) {
                problems.add("expected " + outcome + ", got " + got.describe());
                return problems;
            }
            if (r.httpStatus() != want) {
                problems.add("expected HTTP " + want + ", got " + r.httpStatus());
            }
            if (outcome == Outcome.FAILED_IN_BODY && exp.has("error_code")) {
                expectCode(exp, r, problems);
            }
        } else {
            if (r.outcome() != Outcome.REJECTED || r.httpStatus() != want) {
                problems.add("expected REJECTED HTTP " + want + ", got " + got.describe());
                return problems;
            }
            if (exp.has("error_code")) {
                expectCode(exp, r, problems);
            }
        }
        JsonNode paths = exp.get("json");
        if (paths != null) {
            JsonNode envelope = envelope(r);
            for (String p : paths.propertyNames()) {
                String root = p.split("[.\\[]")[0];
                if (!VISIBLE.contains(root)) {
                    unchecked++;
                    continue;
                }
                JsonNode actual = at(envelope, p);
                if (!matches(actual, paths.get(p))) {
                    problems.add(
                            p
                                    + ": expected "
                                    + paths.get(p)
                                    + ", got "
                                    + cut(String.valueOf(actual)));
                }
            }
        }
        return problems;
    }

    private static void expectCode(JsonNode exp, HrmsReply r, List<String> problems) {
        if (!exp.get("error_code").asString().equals(r.errorCode())) {
            problems.add(
                    "expected error_code "
                            + exp.get("error_code").asString()
                            + ", got "
                            + r.errorCode());
        }
    }

    private static Outcome outcomeFor2xx(JsonNode exp) {
        boolean bodyOnly =
                (exp.has("bodyEquals") || exp.has("bodyContains"))
                        && !exp.has("json")
                        && !exp.has("error_code");
        if (bodyOnly) {
            return Outcome.UNREADABLE; // empty or truncated JSON
        }
        return exp.has("error_code") ? Outcome.FAILED_IN_BODY : Outcome.ACCEPTED;
    }

    /** What the reply keeps of the HRMS envelope, in the shape of the envelope. */
    private static JsonNode envelope(HrmsReply r) {
        ObjectNode env = JSON.createObjectNode();
        env.put("status", r.outcome() == Outcome.ACCEPTED ? "success" : "error");
        env.put("status_code", r.httpStatus());
        if (r.errorCode() != null) {
            env.put("error_code", r.errorCode());
        }
        if (r.message() != null) {
            env.put("message", r.message());
        }
        if (r.data() != null) {
            env.set("data", r.data());
        } else {
            env.putNull("data");
        }
        ArrayNode errors = env.putArray("errors");
        for (String p : r.problems()) {
            int sep = p.indexOf(": ");
            ObjectNode e = errors.addObject();
            if (sep >= 0) {
                e.put("field", p.substring(0, sep));
                e.put("message", p.substring(sep + 2));
            } else {
                e.put("message", p);
            }
        }
        return env;
    }

    private static JsonNode at(JsonNode root, String path) {
        JsonNode cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null || cur.isNull()) {
                return null;
            }
            Matcher m = INDEX.matcher(part);
            if (m.matches()) {
                cur = cur.get(m.group(1));
                cur = cur == null ? null : cur.get(Integer.parseInt(m.group(2)));
            } else {
                cur = cur.get(part);
            }
        }
        return cur;
    }

    private static boolean matches(JsonNode actual, JsonNode expected) {
        boolean absent = actual == null || actual.isNull();
        if (expected.isNull()) {
            return absent;
        }
        if (absent) {
            return false;
        }
        if (expected.isString()) {
            String e = expected.asString();
            if (e.equals("*")) {
                return true;
            }
            if (e.startsWith("~")) {
                return actual.asString().contains(e.substring(1));
            }
            return actual.asString().equals(e);
        }
        if (expected.isNumber()) {
            try {
                return new BigDecimal(actual.asString().strip()).compareTo(expected.decimalValue())
                        == 0;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        if (expected.isBoolean()) {
            return actual.isBoolean() && actual.asBoolean() == expected.asBoolean();
        }
        return actual.equals(expected);
    }

    /** The same body in the shape the contract allows (array / number / text, known fields). */
    private JsonNode coerce(HrmsApi api, JsonNode body) {
        var entry = contract.of(api.code()).orElse(null);
        if (entry == null || body == null) {
            return body;
        }
        JsonNode b = body;
        if (entry.arrayBody() && b.isObject()) {
            b = JSON.createArrayNode().add(b);
        }
        if (b.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            if (b.isEmpty()) {
                out.addObject(); // the trigger travels in the query: one empty record
            }
            for (JsonNode r : b) {
                out.add(coerceRecord(entry, r));
            }
            return out;
        }
        return coerceRecord(entry, b);
    }

    private static JsonNode coerceRecord(HrmsContractCatalog.Entry entry, JsonNode r) {
        if (!r.isObject()) {
            return r;
        }
        ObjectNode out = JSON.createObjectNode();
        for (String n : r.propertyNames()) {
            HrmsContractCatalog.Field field =
                    entry.fields().stream()
                            .filter(f -> f.name().equals(n))
                            .findFirst()
                            .orElse(null);
            if (field == null) {
                continue; // not in the contract: Payroll never sends it
            }
            JsonNode v = r.get(n);
            if (field.kind().equals("number") && v.isString() && !v.asString().isBlank()) {
                try {
                    out.put(n, new BigDecimal(v.asString().strip()));
                    continue;
                } catch (NumberFormatException ex) {
                    // stays as it is
                }
            }
            if (field.kind().equals("string") && v.isNumber()) {
                out.put(n, v.asString());
                continue;
            }
            out.set(n, v);
        }
        return out;
    }

    // ------------------------------------------------------------------ scenarios the catalogue
    // lacks

    @TestFactory
    @Order(2)
    Stream<DynamicTest> payrollScenarios() {
        return Stream.of(
                extra("API disabled answers 503 then recovers when enabled", this::disabledApi),
                extra("503 with Retry-After is retried and then a failed call", this::retryAfter),
                extra(
                        "ten concurrent submits of one request number keep one record",
                        this::concurrent),
                extra("a 401 that does not go away costs one token renewal", this::persistent401),
                extra("a revoked token is replaced on the next call", this::revokedToken),
                extra("a server slower than the limit ends in a failed call", this::slowServer));
    }

    private DynamicTest extra(String name, Callable<String> body) {
        return extra("Extras", name, body);
    }

    private DynamicTest extra(String group, String name, Callable<String> body) {
        return DynamicTest.dynamicTest(
                name,
                () -> {
                    Rec rec;
                    try {
                        String detail = body.call();
                        rec =
                                detail.startsWith("SKIP ")
                                        ? new Rec(
                                                "Extras", name, Status.SKIPPED, detail.substring(5))
                                        : new Rec(group, name, Status.PASSED, detail);
                    } catch (Throwable t) {
                        rec = new Rec(group, name, Status.FAILED, cut(String.valueOf(t)));
                        recs.add(rec);
                        throw t;
                    }
                    recs.add(rec);
                    if (rec.status() == Status.SKIPPED) {
                        Assumptions.assumeTrue(false, rec.detail());
                    }
                });
    }

    /** The submit flows of the catalogue, for the people in Payroll's own demo data. */
    @TestFactory
    @Order(3)
    Stream<DynamicTest> payrollEmployees() {
        String[] flows = {
            "OTA",
            "Request Mobile Reimbursement",
            "Request Briefcase Reimbursement",
            "Request Tele Data Reimbursement",
            "Request Laptop Reimbursement",
            "Medical Claim",
            "Leave Encashment (idempotent replay of seeded 370825)",
            "LTC Encash"
        };
        List<DynamicTest> tests = new ArrayList<>();
        int n = 0;
        for (long emp : new long[] {104882L, 105220L}) {
            for (String flow : flows) {
                int k = ++n;
                long employee = emp;
                tests.add(
                        extra(
                                "Payroll employees",
                                flow + " for " + emp,
                                () -> payrollFlow(flow, employee, k)));
            }
        }
        return tests.stream();
    }

    private String payrollFlow(String flow, long emp, int k) {
        JsonNode sample = null;
        for (JsonNode c : cases) {
            if (c.get("name").asString().equals(flow)) {
                sample = c;
                break;
            }
        }
        assertThat(sample).as("catalogue case " + flow).isNotNull();
        HrmsApi api = byPath.get(sample.get("path").asString().split("\\?")[0]);
        ObjectNode body = ((ObjectNode) sample.get("body")).deepCopy();
        body.put("request_id", RUN * 100 + k);
        body.put("emp_num", emp);
        if (body.has("bill_number")) {
            body.put("bill_number", RUN * 100 + k);
        }
        JsonNode sent = coerce(api, body);
        HrmsReply r = main.submit(api, null, sent, "PE-" + RUN + "-" + k);
        assertThat(r.outcome())
                .as(
                        flow
                                + " for "
                                + emp
                                + ": "
                                + r.errorCode()
                                + " "
                                + r.message()
                                + " "
                                + r.problems())
                .isEqualTo(Outcome.ACCEPTED);
        assertThat(r.duplicate()).isFalse();
        return "ACCEPTED " + r.data().path("status").asString("");
    }

    private String disabledApi() throws Exception {
        Map<String, String> status = q("request_id", "4517", "emp_ota_id", "1230");
        assertThat(
                        admin("PUT", "/mock-admin/endpoints/OTA_STATUS/enabled?value=false", null)
                                .statusCode())
                .isEqualTo(200);
        String during;
        try {
            Got got = invoke(main, HrmsApi.GET_OTA_STATUS, status, null, "DIS-" + RUN);
            assertThat(got.failure()).as("503 API_DISABLED is a failed call").isNotNull();
            assertThat(got.failure().getMessage()).isEqualTo(CALL_FAILED);
            during = got.describe();
        } finally {
            admin("PUT", "/mock-admin/endpoints/OTA_STATUS/enabled?value=true", null);
        }
        HrmsReply again = main.status(HrmsApi.GET_OTA_STATUS, status);
        assertThat(again.outcome()).isEqualTo(Outcome.ACCEPTED);
        return "disabled: " + during + "; enabled again: " + again.outcome();
    }

    private String retryAfter() {
        HrmsGatewayService g = gateway(env("HRMS_MOCK_USER"), env("HRMS_MOCK_PASSWORD"), 2, 5, 5);
        long started = System.nanoTime();
        Got got =
                invoke(
                        g,
                        HrmsApi.GET_MEDICAL_CLAIM_STATUS,
                        q("emp_num", "999503", "payment_id", "1"),
                        null,
                        null);
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        assertThat(got.failure()).isNotNull();
        assertThat(got.failure().getMessage()).isEqualTo(CALL_FAILED);
        assertThat(got.failure().getMessageArgs()[1]).as("attempts = 1 + 2 retries").isEqualTo(3);
        assertThat(seconds)
                .as("Payroll uses its own back-off, not the 120 s of Retry-After")
                .isLessThan(20);
        return "3 attempts, " + seconds + " s";
    }

    private String concurrent() throws Exception {
        HrmsGatewayService g = gateway(env("HRMS_MOCK_USER"), env("HRMS_MOCK_PASSWORD"), 0, 10, 20);
        long emp = 105220L;
        long id = RUN * 10 + 7;
        JsonNode body = json(otaBody(id, emp, "2602"));
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HrmsReply>> replies = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                replies.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    return g.submit(HrmsApi.REQUEST_OTA, null, body, "CC-" + id);
                                }));
            }
            go.countDown();
            int fresh = 0;
            int duplicate = 0;
            for (Future<HrmsReply> f : replies) {
                HrmsReply r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.outcome()).as(String.valueOf(r)).isEqualTo(Outcome.ACCEPTED);
                if (r.duplicate()) {
                    duplicate++;
                } else {
                    fresh++;
                }
            }
            HttpResponse<String> held =
                    admin("GET", "/mock-admin/requests?module=OTA&emp_num=" + emp, null);
            long records = 0;
            for (JsonNode row : json(held.body()).get("data")) {
                if (String.valueOf(id).equals(row.path("request_id").asString(""))) {
                    records++;
                }
            }
            assertThat(records).as("records held for request " + id).isEqualTo(1);
            assertThat(fresh).as("replies that created the record").isEqualTo(1);
            assertThat(duplicate).as("replies marked duplicate").isEqualTo(threads - 1);
            return "1 record, 1 new + " + duplicate + " duplicate";
        } finally {
            pool.shutdownNow();
        }
    }

    private long lastCallId() throws Exception {
        long max = 0;
        for (JsonNode row :
                json(admin("GET", "/mock-admin/calls?limit=1", null).body()).get("data")) {
            max = Math.max(max, row.path("id").asLong());
        }
        return max;
    }

    private long tokenCallsSince(long id) throws Exception {
        long n = 0;
        for (JsonNode row :
                json(admin("GET", "/mock-admin/calls?limit=500", null).body()).get("data")) {
            if (row.path("id").asLong() > id && "/token".equals(row.path("uri").asString(""))) {
                n++;
            }
        }
        return n;
    }

    private String persistent401() throws Exception {
        String match = String.valueOf(RUN % 1_000_000 + 7_000_000);
        String scenario =
                "{\"scenario_code\":\"PAYROLL_401_"
                        + RUN
                        + "\",\"api_code\":\"OTA_STATUS\",\"match_field\":\"request_id\","
                        + "\"match_value\":\""
                        + match
                        + "\",\"http_status\":401,\"response_body\":\"{\\\"status\\\":\\\"error\\\","
                        + "\\\"status_code\\\":401,\\\"error_code\\\":\\\"TOKEN_REVOKED\\\",\\\"message\\\":\\\"token"
                        + " revoked\\\"}\"}";
        HttpResponse<String> added = admin("POST", "/mock-admin/scenarios", scenario);
        assertThat(added.statusCode()).isEqualTo(200);
        long scenarioId = json(added.body()).get("data").get("id").asLong();
        try {
            HrmsGatewayService g =
                    gateway(env("HRMS_MOCK_USER"), env("HRMS_MOCK_PASSWORD"), 1, 5, 5);
            long before = lastCallId();
            HrmsReply r =
                    g.status(HrmsApi.GET_OTA_STATUS, q("request_id", match, "emp_ota_id", "1"));
            assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
            assertThat(r.httpStatus()).isEqualTo(401);
            assertThat(r.errorCode()).isEqualTo("TOKEN_REVOKED");
            assertThat(tokenCallsSince(before)).as("one login, one renewal, no loop").isEqualTo(2);
            return "REJECTED 401 after exactly 2 token calls";
        } finally {
            admin("DELETE", "/mock-admin/scenarios/" + scenarioId, null);
        }
    }

    private String revokedToken() throws Exception {
        HrmsGatewayService g = gateway(env("HRMS_MOCK_USER"), env("HRMS_MOCK_PASSWORD"), 1, 5, 5);
        Map<String, String> status = q("request_id", "4517", "emp_ota_id", "1230");
        assertThat(g.status(HrmsApi.GET_OTA_STATUS, status).outcome()).isEqualTo(Outcome.ACCEPTED);
        HttpResponse<String> revoked =
                admin("PUT", "/mock-admin/tokens/revoke?username=" + env("HRMS_MOCK_USER"), null);
        if (revoked.statusCode() != 200) {
            return "SKIP this mock build has no PUT /mock-admin/tokens/revoke (HTTP "
                    + revoked.statusCode()
                    + "); requested in docs/PAYROLL-SCENARIO-REQUESTS.md";
        }
        long before = lastCallId();
        HrmsReply r = g.status(HrmsApi.GET_OTA_STATUS, status);
        assertThat(r.outcome()).as("the call after the revocation").isEqualTo(Outcome.ACCEPTED);
        assertThat(tokenCallsSince(before)).as("one new login").isEqualTo(1);
        return "ACCEPTED after one new login";
    }

    private String slowServer() {
        HrmsGatewayService g = gateway(env("HRMS_MOCK_USER"), env("HRMS_MOCK_PASSWORD"), 0, 2, 5);
        long started = System.nanoTime();
        Got got =
                invoke(
                        g,
                        HrmsApi.GET_MEDICAL_CLAIM_STATUS,
                        q("emp_num", "999504", "payment_id", "1"),
                        null,
                        null);
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        assertThat(got.failure()).isNotNull();
        assertThat(got.failure().getMessage()).isEqualTo(CALL_FAILED);
        assertThat(seconds).as("the 30 s wait of the mock is cut at 2 s").isLessThan(15);
        return "failed after " + seconds + " s";
    }

    private static String otaBody(long requestId, long emp, String yymm) {
        return "{\"request_id\":"
                + requestId
                + ",\"emp_num\":"
                + emp
                + ",\"gross_amount\":100,\"it_ded\":1,\"othr_ded\":2,\"net_amt\":100,\"pay_mode\":1,\"remarks\":\"payroll"
                + " scenario\",\"ciel_flag\":\"A\",\"ota_yymm\":"
                + yymm
                + ",\"otahrs\":12,\"otarate\":10}";
    }

    // ------------------------------------------------------------------ report

    private static String cut(String s) {
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= 400 ? one : one.substring(0, 400) + "...";
    }

    private void report() throws IOException {
        Map<String, List<Rec>> byGroup = new LinkedHashMap<>();
        synchronized (recs) {
            for (Rec r : recs) {
                byGroup.computeIfAbsent(r.group(), k -> new ArrayList<>()).add(r);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HRMS mock scenario run through Payroll's HrmsGateway\n");
        sb.append("mock: ")
                .append(env("HRMS_MOCK_URL"))
                .append("   run: ")
                .append(RUN)
                .append("\n\n");
        int[] total = new int[Status.values().length];
        sb.append(
                String.format(
                        "%-45s %5s %7s %8s %8s %8s %7s%n",
                        "group", "total", "passed", "coerced", "refused", "skipped", "FAILED"));
        for (Map.Entry<String, List<Rec>> e : byGroup.entrySet()) {
            int[] n = new int[Status.values().length];
            e.getValue().forEach(r -> n[r.status().ordinal()]++);
            for (int i = 0; i < n.length; i++) {
                total[i] += n[i];
            }
            sb.append(line(e.getKey(), e.getValue().size(), n));
        }
        sb.append(line("TOTAL", recs.size(), total));
        sb.append("\npassed  = executed through the gateway and equal to the expectation\n");
        sb.append(
                "coerced = Payroll refused the literal body (shape), the contract-shaped body"
                        + " passed\n");
        sb.append(
                "refused = Payroll refused before sending, mock expected 400 for the same fault\n");
        sb.append("sub-expectations not visible in HrmsReply (headers, details, timestamps): ")
                .append(unchecked)
                .append(" not checked\n");
        for (Status s :
                new Status[] {Status.FAILED, Status.SKIPPED, Status.REFUSED, Status.COERCED}) {
            sb.append("\n== ").append(s).append(" ==\n");
            for (Map.Entry<String, List<Rec>> e : byGroup.entrySet()) {
                List<Rec> hit = e.getValue().stream().filter(r -> r.status() == s).toList();
                if (hit.isEmpty()) {
                    continue;
                }
                sb.append("[").append(e.getKey()).append("]\n");
                if (s == Status.SKIPPED) {
                    Map<String, List<String>> reasons = new LinkedHashMap<>();
                    hit.forEach(
                            r ->
                                    reasons.computeIfAbsent(r.detail(), k -> new ArrayList<>())
                                            .add(r.name()));
                    reasons.forEach(
                            (why, names) -> {
                                sb.append("  ")
                                        .append(why)
                                        .append(" (")
                                        .append(names.size())
                                        .append(")\n");
                                names.forEach(nm -> sb.append("      ").append(nm).append("\n"));
                            });
                } else {
                    hit.forEach(
                            r ->
                                    sb.append("  ")
                                            .append(r.name())
                                            .append("  ->  ")
                                            .append(r.detail())
                                            .append("\n"));
                }
            }
        }
        Path out = Path.of("build", "reports", "hrms-scenarios.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String line(String group, int count, int[] n) {
        return String.format(
                "%-45s %5d %7d %8d %8d %8d %7d%n",
                cut(group).length() > 45 ? group.substring(0, 45) : group,
                count,
                n[Status.PASSED.ordinal()],
                n[Status.COERCED.ordinal()],
                n[Status.REFUSED.ordinal()],
                n[Status.SKIPPED.ordinal()],
                n[Status.FAILED.ordinal()]);
    }
}
