package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import in.gov.fci.payroll.platform.notification.SmsSender;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Base class of the API integration tests: the whole online application on a private in-memory H2
 * database migrated by Flyway with the demo seed (docs/rbac/SEED_USERS.md), MockMvc, and a
 * capturing notification sender (OTP codes).
 *
 * <p>Requests follow the browser protocol exactly: the server session is the {@code FCISESSION}
 * cookie (Spring Session JDBC, ADR-0009), which rotates on sign-in, scope switch and password
 * change (tracked here on every response); every unsafe call double-submits a CSRF token as the
 * {@code XSRF-TOKEN} cookie and the {@code X-XSRF-TOKEN} header, as the SPA does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "it", "online"})
@Import(ApiTestSupport.TestBeans.class)
public abstract class ApiTestSupport {

    /** The one demo password of every seeded user (bcrypt in the seed). */
    public static final String DEMO_PASSWORD = "Fci@Demo2026";

    /** Last OTP per "email|gated action". */
    public static final Map<String, String> OTP_CODES = new ConcurrentHashMap<>();

    /** OTP notices the capturing senders received: {@code <e-mail>|<purpose>} -> count. */
    public static final Map<String, Integer> OTP_ALERTS = new ConcurrentHashMap<>();

    public static final Map<String, Integer> OTP_VERIFIED = new ConcurrentHashMap<>();

    /**
     * What the stand-in HRMS holds (SRS-1-70; module 23 not built): {@code <employeeId>|<field>} ->
     * value; {@link #HRMS_DOWN} as the value makes the read fail; no entry means HRMS holds none.
     */
    public static final Map<String, String> HRMS_VALUES = new ConcurrentHashMap<>();

    public static final String HRMS_DOWN = "!DOWN";

    /**
     * The stand-in adapter of interface {@link #ADAPTED} (module 23 has no real adapter yet):
     * "test" -> the Test connection outcome, "reprocess" -> {@link #HRMS_DOWN} to fail a reprocess.
     */
    public static final Map<String, String> ADAPTER = new ConcurrentHashMap<>();

    public static final String ADAPTED = "HRMS_LTC_ENCASH";

    /** SMS texts per mobile number. */
    public static final Map<String, java.util.List<String>> SMS = new ConcurrentHashMap<>();

    /** The newest OTP sent by SMS per {@code mobile|purpose} (in memory only; LoginOtpIT). */
    public static final Map<String, String> SMS_OTP = new ConcurrentHashMap<>();

    /** The stand-in undoable transactions: key -> cycle instance id (plan 05 phase 05e). */
    public static final Map<String, Long> UNDOABLE = new ConcurrentHashMap<>();

    /** The stand-in transactions undone: key -> the authorising administrator. */
    public static final Map<String, String> UNDONE = new ConcurrentHashMap<>();

    /** The IT profile serves a Secure cookie, so it carries the __Host- prefix (SessionConfig). */
    protected static final String SESSION_COOKIE = "__Host-FCISESSION";

    private static final String TRACKED = ApiTestSupport.class.getName() + ".session";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected AccessContextService accessContexts;
    @Autowired protected OrgDirectory orgDirectory;

    /** When the running test began (database clock), for {@link #priorLocked}. */
    private java.sql.Timestamp testStarted;

    @org.junit.jupiter.api.BeforeEach
    void markTestStart() {
        testStarted =
                jdbc.queryForObject("SELECT CURRENT_TIMESTAMP FROM dual", java.sql.Timestamp.class);
    }

    /**
     * A fixture wrote rules, versions, bands or the grammar with SQL (not through an approved M-08
     * decision): the rule engine's cache (region RULE) reloads on its next read (the IT profile
     * checks the region versions on every read).
     */
    protected void rulesChanged() {
        rulesChanged(jdbc);
    }

    static void rulesChanged(JdbcTemplate jdbc) {
        cacheChanged(jdbc, "RULE");
    }

    /**
     * The resource lock of the demo user agm.north@fci-demo.test, shared by the classes of this
     * context that sign it in or change its access groups (classes of a fork run concurrently).
     */
    public static final String AGM_NORTH = "demo-user:agm.north";

    /** A fixture wrote the data of a cache region with SQL: the region reloads on its next read. */
    static void cacheChanged(JdbcTemplate jdbc, String region) {
        if (jdbc.update(
                        "UPDATE cache_region SET version_no = version_no + 1 WHERE region_code = ?",
                        region)
                == 0) {
            jdbc.update(
                    "INSERT INTO cache_region (region_code, version_no, changed_at, changed_by)"
                            + " VALUES (?, 1, CURRENT_TIMESTAMP, 'it')",
                    region);
        }
    }

    private static final Pattern INITIATE =
            Pattern.compile("^/v1/cycles/(\\d{4}-\\d{2})/initiate$");

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        NotificationSender capturingNotificationSender() {
            return new NotificationSender() {
                @Override
                public void sendOtp(
                        String email,
                        String displayName,
                        String purpose,
                        String code,
                        int validMinutes) {
                    OTP_CODES.put(email + "|" + purpose, code);
                }

                @Override
                public void sendAccountLocked(String email, String displayName) {
                    // not needed by the tests
                }

                @Override
                public void sendOtpFailureAlert(
                        String email, String displayName, String purpose, int attempts) {
                    OTP_ALERTS.merge(email + "|" + purpose, 1, Integer::sum);
                }

                @Override
                public void sendOtpVerified(String email, String displayName, String purpose) {
                    OTP_VERIFIED.merge(email + "|" + purpose, 1, Integer::sum);
                }
            };
        }

        /** The stand-in adapter of {@link #ADAPTED}, scripted by {@link #ADAPTER}. */
        @Bean
        in.gov.fci.payroll.integration.api.InterfaceAdapter itInterfaceAdapter() {
            return new in.gov.fci.payroll.integration.api.InterfaceAdapter() {
                @Override
                public String interfaceCode() {
                    return ADAPTED;
                }

                @Override
                public String testConnection() {
                    String outcome = ADAPTER.getOrDefault("test", "REACHABLE");
                    if (HRMS_DOWN.equals(outcome)) {
                        throw new IllegalStateException("adapter down");
                    }
                    return outcome;
                }

                @Override
                public void reprocess(long stagingId, String txnType, String payload) {
                    if (HRMS_DOWN.equals(ADAPTER.get("reprocess"))) {
                        throw new IllegalStateException("source still wrong");
                    }
                }
            };
        }

        /** The stand-in HRMS record read, scripted by {@link #HRMS_VALUES}. */
        @Bean
        @org.springframework.context.annotation.Primary
        in.gov.fci.payroll.employee.api.HrmsRecords itHrmsRecords() {
            return (employeeId, fieldCode) -> {
                String value = HRMS_VALUES.get(employeeId + "|" + fieldCode);
                if (HRMS_DOWN.equals(value)) {
                    throw new in.gov.fci.payroll.employee.api.HrmsRecords.Unavailable(
                            "stand-in HRMS down", null);
                }
                return java.util.Optional.ofNullable(value)
                        .map(v -> new in.gov.fci.payroll.employee.api.HrmsRecords.Fetched(v, null));
            };
        }

        /**
         * A stand-in kind of undoable transaction (plan 05 D-10: no module registers one yet), so
         * the controlled undo is proved end to end: a test puts a key and its cycle into {@link
         * #UNDOABLE}; the undo moves it to {@link #UNDONE}.
         */
        @Bean
        in.gov.fci.payroll.payroll.api.UndoableTransaction itUndoableTransaction() {
            return new in.gov.fci.payroll.payroll.api.UndoableTransaction() {
                @Override
                public String entity() {
                    return "it_payment";
                }

                @Override
                public java.util.Optional<Target> find(String key) {
                    return java.util.Optional.ofNullable(UNDOABLE.get(key))
                            .map(cycle -> new Target(cycle, "COMPUTED", "IT payment " + key));
                }

                @Override
                public void undo(String key, String reason, String decidedBy, long approvalId) {
                    UNDONE.put(key, decidedBy);
                    UNDOABLE.remove(key);
                }
            };
        }

        @Bean
        @Primary
        SmsSender capturingSmsSender() {
            return new SmsSender() {
                @Override
                public void send(String mobile, String text) {
                    SMS.computeIfAbsent(
                                    mobile, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                            .add(text);
                }

                /**
                 * The newest code per mobile, so an IT can complete an OTP sign-in (LoginOtpIT).
                 */
                @Override
                public void sendOtp(String mobile, String purpose, String code, int minutes) {
                    SMS_OTP.put(mobile + "|" + purpose, code);
                }
            };
        }

        /** Follows the session cookie of the {@link Session} a request was made for. */
        @Bean
        MockMvcBuilderCustomizer sessionCookieTracking() {
            return builder ->
                    builder.alwaysDo(
                            result -> {
                                Object tracked = result.getRequest().getAttribute(TRACKED);
                                Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
                                if (tracked instanceof Session session && cookie != null) {
                                    session.cookie =
                                            cookie.getMaxAge() == 0 ? null : cookie.getValue();
                                }
                            });
        }
    }

    // ------------------------------------------------------------------ browser session

    /**
     * A browser: its session cookie (null before sign-in or after sign-out) and what sign-in said.
     */
    public static final class Session {
        volatile String cookie;
        private final String loginId;
        private final boolean passwordChangeRequired;
        private final String landing;

        Session(String cookie, String loginId, boolean passwordChangeRequired, String landing) {
            this.cookie = cookie;
            this.loginId = loginId;
            this.passwordChangeRequired = passwordChangeRequired;
            this.landing = landing;
        }

        public String cookie() {
            return cookie;
        }

        public String loginId() {
            return loginId;
        }

        public boolean passwordChangeRequired() {
            return passwordChangeRequired;
        }

        public String landing() {
            return landing;
        }
    }

    /** A request carrying the CSRF double-submit pair (cookie + header). */
    protected static <B extends AbstractMockHttpServletRequestBuilder<B>> B withCsrf(B request) {
        String token = UUID.randomUUID().toString();
        return request.cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token);
    }

    /**
     * Attaches a session (its cookie and tracking) to any request builder, without a CSRF token.
     */
    protected static <B extends AbstractMockHttpServletRequestBuilder<B>> B as(
            Session session, B request) {
        request.requestAttr(TRACKED, session);
        if (session.cookie != null) {
            request.cookie(new Cookie(SESSION_COOKIE, session.cookie));
        }
        return request;
    }

    protected MockHttpServletRequestBuilder anonymousPost(String path, Object body)
            throws Exception {
        return withCsrf(post(path))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    protected MvcResult loginRaw(String loginId, String password, String role, String locationCode)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("loginId", loginId);
        request.put("password", password);
        if (role != null) {
            request.put("role", role);
        }
        if (locationCode != null) {
            request.put("location", locationCode);
        }
        return mvc.perform(anonymousPost("/auth/login", request)).andReturn();
    }

    /** The browser session a successful sign-in established. */
    protected static Session sessionOf(MvcResult result, String loginId, JsonNode body) {
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        if (cookie == null) {
            throw new IllegalStateException("Sign-in set no " + SESSION_COOKIE + " cookie");
        }
        return new Session(
                cookie.getValue(),
                loginId,
                body.path("passwordChangeRequired").asBoolean(),
                body.path("landingScreen").asString(null));
    }

    /**
     * Signs in with the demo password and starts in {@code role} at {@code locationCode} (either
     * may be null).
     */
    protected Session login(String loginId, String role, String locationCode) throws Exception {
        Session session = loginWithoutStepUp(loginId, role, locationCode);
        confirmStepUp(loginId);
        return session;
    }

    /** As {@link #login}, but the OTP step-up of sensitive screens is still due (StepUpIT). */
    protected Session loginWithoutStepUp(String loginId, String role, String locationCode)
            throws Exception {
        MvcResult result = loginRaw(loginId, DEMO_PASSWORD, role, locationCode);
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException(
                    "Login failed for "
                            + loginId
                            + ": "
                            + result.getResponse().getContentAsString());
        }
        return sessionOf(result, loginId, body(result));
    }

    /**
     * Marks the user's open session as confirmed by an OTP just now, as POST /auth/step-up would
     * (SRS-G-48, Module 02 D-2). Every IT signs in the shared demo users many times an hour: real
     * step-ups would exceed the OTP issuance cap ({@code fci.api.otp.max-per-hour}, at most 20).
     * The step-up itself is tested in StepUpIT.
     */
    protected void confirmStepUp(String loginId) {
        jdbc.update(
                "UPDATE app_session SET step_up_at = CURRENT_TIMESTAMP WHERE ended_at IS NULL"
                        + " AND user_id = (SELECT user_id FROM app_user WHERE login_id = ?)",
                loginId);
        accessContexts.evictAll();
    }

    protected MockHttpServletRequestBuilder userGet(Session session, String path) {
        return as(session, get(path));
    }

    protected MockHttpServletRequestBuilder userPost(Session session, String path, Object body)
            throws Exception {
        Matcher initiate = INITIATE.matcher(path);
        if (initiate.matches()) {
            try {
                priorLocked(session, YearMonth.parse(initiate.group(1)));
            } catch (java.time.format.DateTimeParseException notAMonth) {
                // An invalid month (2026-13) is the server's to refuse; no fixture for it.
            }
        }
        return as(session, withCsrf(post(path)))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    /**
     * Q-05-2 test isolation (SRS-15-33, plan 05 D-25): initiation needs the prior period of the
     * cycle unit locked (or no earlier period at all). The suites initiate past months of a few
     * shared units in any order, so an initiation through {@link #userPost} first records that
     * prior period as a LOCKED fixture when it has no instance yet (a month before the current
     * period only: the current month is never fixed by a fixture). A prior month an earlier test
     * left open is moved forward to LOCKED; one the running test opened is kept, so the rule
     * refuses and the clash shows. A month a test initiates that an earlier test's fixture holds is
     * first cleared of that fixture row (it has no children), so the suites can initiate
     * neighbouring months in any order. The rule itself is CyclePriorPeriodIT, which initiates
     * without this fixture.
     */
    protected void priorLocked(Session session, YearMonth period) {
        YearMonth prior = period.minusMonths(1);
        Long office =
                jdbc
                        .query(
                                "SELECT s.active_location_id FROM app_session s JOIN app_user u ON"
                                        + " u.user_id = s.user_id WHERE u.login_id = ? AND"
                                        + " s.ended_at IS NULL ORDER BY s.started_at DESC",
                                (rs, i) -> rs.getLong(1),
                                session.loginId())
                        .stream()
                        .findFirst()
                        .orElse(null);
        Optional<OrgLocation> unit = orgDirectory.cycleUnitOf(office);
        if (unit.isEmpty()) {
            return;
        }
        jdbc.update(
                "DELETE FROM payroll_cycle_instance WHERE payroll_yymm = ? AND org_unit_id = ? AND"
                        + " initiated_by = 'it.prior.fixture'",
                CycleKeys.yymm(period),
                unit.get().getId());
        if (!prior.isBefore(YearMonth.now(ZoneId.of("Asia/Kolkata")))) {
            return;
        }
        String yymm = CycleKeys.yymm(prior);
        Integer present =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                                + " org_unit_id = ?",
                        Integer.class,
                        yymm,
                        unit.get().getId());
        if (present != null && present > 0) {
            // A prior month an earlier, finished test initiated and left open is stale: it is moved
            // forward to LOCKED (the forward-only trigger admits it). One the running test opened
            // is kept, so a clash inside a test still shows.
            jdbc.update(
                    "UPDATE payroll_cycle_instance SET cycle_status = 'LOCKED', locked_by ="
                            + " 'it.prior.fixture', locked_at = CURRENT_TIMESTAMP WHERE"
                            + " payroll_yymm = ? AND org_unit_id = ? AND cycle_status <> 'LOCKED'"
                            + " AND initiated_at < ?",
                    yymm,
                    unit.get().getId(),
                    testStarted);
            return;
        }
        Integer earlier =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm < ? AND"
                                + " org_unit_id = ?",
                        Integer.class,
                        yymm,
                        unit.get().getId());
        if (earlier == null || earlier == 0) {
            // The unit has no earlier cycle at all, which the rule admits: no fixture needed.
            return;
        }
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, calendar_id, cycle_status, trigger_type, initiated_by)"
                        + " VALUES (?, ?, ?, ?, (SELECT MIN(calendar_id) FROM payroll_calendar"
                        + " WHERE payroll_yymm = ?), 'LOCKED', 'ON_DEMAND', 'it.prior.fixture')",
                yymm,
                unit.get().getId(),
                CycleKeys.level(unit.get().getType()),
                CycleKeys.finYear(prior),
                yymm);
    }

    /**
     * Clears the Q-05-2 prior-period fixtures ({@link #priorLocked}) of a month, for a suite that
     * works in a fixed month without initiating it (an off-cycle capture refuses a locked month).
     */
    protected void clearPriorFixtures(String yymm) {
        jdbc.update(
                "DELETE FROM payroll_cycle_instance WHERE payroll_yymm = ? AND initiated_by ="
                        + " 'it.prior.fixture'",
                yymm);
    }

    protected MockHttpServletRequestBuilder userPut(Session session, String path, Object body)
            throws Exception {
        return as(session, withCsrf(put(path)))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    /** A user request builder for any method, already carrying the session and the CSRF pair. */
    protected <B extends AbstractMockHttpServletRequestBuilder<B>> B userRequest(
            Session session, B request) {
        return as(session, withCsrf(request));
    }

    /**
     * Switches the session to the first of its own scopes acting in {@code role} (from
     * /auth/scopes).
     */
    protected void actAs(Session session, String role) throws Exception {
        JsonNode scopes = body(mvc.perform(userGet(session, "/auth/scopes")).andReturn());
        for (JsonNode scope : scopes) {
            if (role.equals(scope.path("role").asString())) {
                Map<String, Object> request = new HashMap<>();
                request.put("role", role);
                if (!scope.path("location").isNull()) {
                    request.put("location", scope.path("location").asString());
                }
                int status =
                        mvc.perform(userPost(session, "/auth/switch-scope", request))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                if (status != 204) {
                    throw new IllegalStateException("Switch to " + role + " refused: " + status);
                }
                return;
            }
        }
        throw new IllegalStateException(session.loginId() + " holds no scope with role " + role);
    }

    /**
     * Proposes an M-01 Payroll allowance flag for the employee (SRS-1-11, route M01_FLAG): the
     * maker-checker item the module 01 / 02 tests raise now that the HRMS-owned change request is
     * retired (plan 16 D16-19). Takes a flag with nothing pending and no version from today,
     * preferring one never set, and proposes the other value from today (IST).
     */
    protected MvcResult proposeFlag(Session maker, String eis, String reason) throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        JsonNode flags =
                body(mvc.perform(userGet(maker, "/v1/employees/" + eis + "/allowance-flags"))
                                .andReturn())
                        .path("flags");
        JsonNode chosen = null;
        for (JsonNode f : flags) {
            boolean free =
                    !f.path("pending").asBoolean()
                            && !today.toString().equals(f.path("since").asString(""));
            if (free && (chosen == null || f.path("since").isNull())) {
                chosen = f;
                if (f.path("since").isNull()) {
                    break;
                }
            }
        }
        Map<String, Object> body = new HashMap<>();
        body.put("flagCode", chosen == null ? "NPA" : chosen.path("code").asString());
        body.put(
                "value", chosen != null && "Y".equals(chosen.path("value").asString()) ? "N" : "Y");
        body.put("effectiveFrom", today.toString());
        body.put("reason", reason);
        return mvc.perform(flagPost(maker, eis, body, true)).andReturn();
    }

    /**
     * An M-01 allowance flag proposal as the screen sends it (SRS-1-26): the proposal as the {@code
     * flag} part, the supporting document's particulars and, when {@code document}, its file.
     */
    protected org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
            flagPost(Session s, String eis, Map<String, Object> flag, boolean document)
                    throws Exception {
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder r =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/v1/employees/" + eis + "/allowance-flags");
        r.file(
                new org.springframework.mock.web.MockMultipartFile(
                        "flag",
                        "",
                        MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(flag)));
        if (document) {
            r.file(
                    new org.springframework.mock.web.MockMultipartFile(
                            "file",
                            "order.pdf",
                            "application/pdf",
                            "%PDF-1.7 flag order"
                                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        }
        r.param("documentType", "OTHER")
                .param("orderDate", LocalDate.now(ZoneId.of("Asia/Kolkata")).toString())
                .param("issuingAuthority", "Establishment, Delhi");
        return userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString());
    }

    /**
     * SRS-1-28 / SRS-G-31 (US-GAP-5): the Maker attaches the order copy of an I-21 task, as the
     * screen does before it sends the task with an individual order; the status is returned (201,
     * or the refusal of a session that may not).
     */
    protected int attachTaskOrder(Session s, long taskId) throws Exception {
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder r =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/v1/hrms-change-tasks/" + taskId + "/documents");
        r.file(
                new org.springframework.mock.web.MockMultipartFile(
                        "file",
                        "order.pdf",
                        "application/pdf",
                        "%PDF-1.7 I-21 order"
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        r.param("documentType", "OTHER")
                .param("orderDate", LocalDate.now(ZoneId.of("Asia/Kolkata")).toString())
                .param("issuingAuthority", "General Manager (P), Delhi");
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /**
     * Decides an approval item as the Checker. When the action is OTP-gated at this level the first
     * call answers 428 with the challenge id; the helper then answers with the code the capturing
     * notification sender received ({@code <registered e-mail>|<otpAction>}).
     *
     * @return the final HTTP status
     */
    protected int decide(
            Session checker, long approvalId, String decision, String reason, String otpAction)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("decision", decision);
        if (reason != null) {
            body.put("reason", reason);
        }
        MvcResult first =
                mvc.perform(userPost(checker, "/v1/approvals/" + approvalId + "/decision", body))
                        .andReturn();
        if (first.getResponse().getStatus() != 428 || otpAction == null) {
            return first.getResponse().getStatus();
        }
        long challenge = body(first).get("args").get(0).asLong();
        String email =
                jdbc.queryForObject(
                        "SELECT registered_email FROM app_user WHERE login_id = ?",
                        String.class,
                        checker.loginId());
        body.put(
                "otp",
                Map.of("challengeId", challenge, "code", OTP_CODES.get(email + "|" + otpAction)));
        return mvc.perform(userPost(checker, "/v1/approvals/" + approvalId + "/decision", body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /**
     * SRS-G-22 / R-OTP: a decision answered with the right code after its challenge's validity ran
     * out. The first request issues the challenge (428); the fixture ends its validity at the issue
     * instant (the clock is not the test's), then the code the capturing sender received is sent.
     * Returns the answer to the late code and the challenge id.
     */
    protected Map.Entry<MvcResult, Long> decideAfterOtpExpiry(
            Session checker, long approvalId, String decision, String reason, String otpAction)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("decision", decision);
        if (reason != null) {
            body.put("reason", reason);
        }
        MvcResult first =
                mvc.perform(userPost(checker, "/v1/approvals/" + approvalId + "/decision", body))
                        .andReturn();
        if (first.getResponse().getStatus() != 428) {
            throw new AssertionError(
                    "expected an OTP challenge, got " + first.getResponse().getStatus());
        }
        long challenge = body(first).get("args").get(0).asLong();
        // The validity ends at the issue instant (expiry = issued_at + validity_seconds).
        jdbc.update(
                "UPDATE otp_challenge SET validity_seconds = 0 WHERE otp_challenge_id = ?",
                challenge);
        String email =
                jdbc.queryForObject(
                        "SELECT registered_email FROM app_user WHERE login_id = ?",
                        String.class,
                        checker.loginId());
        body.put(
                "otp",
                Map.of("challengeId", challenge, "code", OTP_CODES.get(email + "|" + otpAction)));
        MvcResult late =
                mvc.perform(userPost(checker, "/v1/approvals/" + approvalId + "/decision", body))
                        .andReturn();
        return Map.entry(late, challenge);
    }

    /**
     * Sends a maker submission; when the route asks the maker for an OTP (428) it answers with the
     * code the capturing sender received for {@code otpAction}. {@code method} is POST or PUT.
     */
    protected MvcResult submitWithOtp(
            Session session, String method, String path, Map<String, Object> body, String otpAction)
            throws Exception {
        MvcResult first =
                mvc.perform(
                                "PUT".equals(method)
                                        ? userPut(session, path, body)
                                        : userPost(session, path, body))
                        .andReturn();
        if (first.getResponse().getStatus() != 428) {
            return first;
        }
        String email =
                jdbc.queryForObject(
                        "SELECT registered_email FROM app_user WHERE login_id = ?",
                        String.class,
                        session.loginId());
        Map<String, Object> again = new HashMap<>(body);
        again.put(
                "otp",
                Map.of(
                        "challengeId",
                        body(first).get("args").get(0).asLong(),
                        "code",
                        OTP_CODES.get(email + "|" + otpAction)));
        return mvc.perform(
                        "PUT".equals(method)
                                ? userPut(session, path, again)
                                : userPost(session, path, again))
                .andReturn();
    }

    /**
     * A list export (SRS-G-13): the download streams on the export pool, so MockMvc starts it
     * asynchronously and the body arrives with the async dispatch. A refusal (403, 422) answers at
     * once, without starting the stream.
     */
    protected MvcResult download(Session session, String path) throws Exception {
        MvcResult started = mvc.perform(userGet(session, path)).andReturn();
        if (!started.getRequest().isAsyncStarted()) {
            return started;
        }
        return mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .asyncDispatch(started))
                .andReturn();
    }

    /** A CSV export's text: 200, the UTF-8 byte-order mark first (stripped here). */
    protected String csv(Session session, String path) throws Exception {
        MvcResult result = download(session, path);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                .as(path)
                .isEqualTo(200);
        String text =
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(text).startsWith("\uFEFF");
        return text.substring(1);
    }

    /** The id of the office with this code (org_location.location_code). */
    protected Long locationId(String code) {
        return jdbc.queryForObject(
                "SELECT location_id FROM org_location WHERE location_code = ?", Long.class, code);
    }

    /** Every value of {@code field} anywhere in {@code node}, as text. */
    protected static List<String> texts(JsonNode node, String field) {
        return node.findValuesAsString(field);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /**
     * The whole list when {@code view} is one page of it (a bare {@code Paged}: items, page, size,
     * total), else {@code view} itself: lets a test helper read paged and unpaged answers alike.
     */
    protected JsonNode pagedOrWhole(Session session, String path, JsonNode view) throws Exception {
        boolean bare =
                view.size() == 4
                        && view.has("items")
                        && view.has("page")
                        && view.has("size")
                        && view.has("total");
        return bare ? all(session, path) : view;
    }

    /**
     * Every row of a paged list endpoint (docs/plan/X-pagination.md), page by page at 100 rows, as
     * one array: the tests share one database, so a list may hold more than one page. {@code path}
     * may already carry a query string.
     */
    protected ArrayNode all(Session session, String path) throws Exception {
        ArrayNode rows = json.createArrayNode();
        String join = path.contains("?") ? "&" : "?";
        for (int page = 0; ; page++) {
            MvcResult result =
                    mvc.perform(userGet(session, path + join + "page=" + page + "&size=100"))
                            .andReturn();
            assertThat(result.getResponse().getStatus())
                    .as(path + " " + result.getResponse().getContentAsString())
                    .isEqualTo(200);
            JsonNode view = body(result);
            view.path("items").forEach(rows::add);
            if ((long) (page + 1) * 100 >= view.path("total").asLong()) {
                return rows;
            }
        }
    }
}
