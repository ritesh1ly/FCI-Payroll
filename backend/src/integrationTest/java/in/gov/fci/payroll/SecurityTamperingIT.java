package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import jakarta.servlet.http.Cookie;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * The attacks of an authenticated low-privilege user who edits every request in an intercepting
 * proxy (Burp, ZAP) and of an unauthenticated one, on the real filter chain. The user interface is
 * assumed to give no protection at all. Complements RbacGateMatrixIT (every role x every gate),
 * ApiSurfaceSecurityIT, RequestTamperingIT, SessionSecurityIT, StepUpIT and RbacEscalationIT; each
 * test names the OWASP ASVS / WSTG item it covers and the scenario of
 * docs/quality/rbac-security-scenarios-2026-11-04.md where one applies.
 */
@org.junit.jupiter.api.parallel.ResourceLock(ApiTestSupport.AGM_NORTH)
class SecurityTamperingIT extends ApiTestSupport {

    private static final Pattern LEAK =
            Pattern.compile(
                    "Exception|\\bat in\\.gov|org\\.(springframework|hibernate|h2)|java\\.|SQL"
                            + "|ORA-\\d|\\b[A-Z]{5}\\d{4}[A-Z]\\b|\\b\\d{12}\\b|<script");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    /**
     * The horizontal-scope (IDOR / BOLA) coverage ledger, filled by the data-scope sweeps and
     * written by {@link #writeDataScopeLedger()}: every data-scoped endpoint discovered, how many
     * foreign-owned probes refused it, and - the owner's requirement 2026-11-04 - an explicit count
     * of scoped endpoints left with no foreign-owned probe, each named with its scope rule so that
     * "nothing is sampled away" is a number, not a claim.
     */
    private static final Map<String, String> SCOPED_ENDPOINTS = new java.util.TreeMap<>();

    private static final Set<String> PROBED_WITH_FOREIGN_OWNER =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final Map<String, Integer> SCOPE_COUNTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The disjoint-office, same-role pair the derivation needs (seed V037_9): two Payroll Makers of
     * different ZONES (Delhi DH001 / Kolkata KL001). The derivation - a record one holder sees and
     * the other does not is foreign to the second - is false-positive free only for such a pair: a
     * record the role's own data scope admits by history (a transferred member, seed V077_14) or by
     * oversight (a Headquarters or national role sees every office) is legitimately visible and
     * would be a false leak, so Headquarters, Super User and System Administrator pairs are out.
     * The demo set offers one cross-zone same-role office pair; each probes the other, both
     * directions, so this role is the attacker in both offices. Data scope is enforced in-service
     * by office / zone, not per role, so this pair exercises the one filter every role shares; the
     * role x gate refusal axis is exhaustive separately in RbacGateMatrixIT (9,302 denials).
     */
    private static final List<String[]> SCOPE_PAIRS =
            List.of(new String[][] {{"pm.dh001", "PAYROLL_MAKER", "DH001", "pm.kl001", "KL001"}});

    private static void scopeCount(String kind, int n) {
        SCOPE_COUNTS.merge(kind, n, Integer::sum);
    }

    private Session maker() throws Exception {
        return login("pm.dh001@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    /** The answer without its own correlation id and time (a UUID may hold 12 digits). */
    private static String scrub(String text) {
        return text.replaceAll("\"(correlationId|timestamp)\"\\s*:\\s*\"[^\"]*\"", "");
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String code(MvcResult r) throws Exception {
        String text = r.getResponse().getContentAsString();
        return text.isEmpty() ? "" : json.readTree(text).path("errorCode").asString("");
    }

    private JsonNode me(Session s) throws Exception {
        return body(mvc.perform(userGet(s, "/auth/me")).andReturn());
    }

    // ------------------------------------------------------------ vertical escalation

    /**
     * SRS-22-2 / SRS-22-8 (ASVS V4.1.2 / V4.2.1, WSTG-ATHZ-03): the acting role and office come
     * from the server-side session only. No header, query parameter or body field a client adds (an
     * "X-Role" or "X-Office" header, a forwarded user, a path-override header) can lend a role the
     * user does not hold, another office or another user's identity.
     */
    @Test
    void SRS_22_2_noHeaderOrParameterCanLendARoleAnOfficeOrAnIdentity() throws Exception {
        Session maker = maker();
        Map<String, String> forged = new LinkedHashMap<>();
        forged.put("X-Role", "SYSTEM_ADMINISTRATOR");
        forged.put("X-Active-Role", "SYSTEM_ADMINISTRATOR");
        forged.put("X-Acting-Role", "SUPER_USER");
        forged.put("X-Roles", "SYSTEM_ADMINISTRATOR,SUPER_USER");
        forged.put("X-Office", "HQ001");
        forged.put("X-Location", "HQ001");
        forged.put("X-Location-Id", String.valueOf(locationId("HQ001")));
        forged.put("X-User-Id", "1");
        forged.put("X-Forwarded-User", "sysadmin@fci-demo.test");
        forged.put("X-Remote-User", "sysadmin@fci-demo.test");
        forged.put("Remote-User", "sysadmin@fci-demo.test");
        forged.put("X-Authenticated-User", "sysadmin@fci-demo.test");
        forged.put("X-Original-URL", "/v1/admin/roles");
        forged.put("X-Rewrite-URL", "/v1/admin/roles");
        forged.put("X-Forwarded-Prefix", "/v1/admin");
        forged.put("X-Forwarded-Path", "/v1/admin/roles");
        forged.put("Authorization", "Basic bWV0cmljczpNZXRyaWNzI0l0LU9ubHktMjAyNg==");
        var admin = userGet(maker, "/v1/admin/roles?role=SYSTEM_ADMINISTRATOR&location=HQ001");
        var menu = userGet(maker, "/menu?role=SYSTEM_ADMINISTRATOR&locationId=1&userId=1");
        var mine = userGet(maker, "/auth/me?role=SYSTEM_ADMINISTRATOR&location=HQ001");
        forged.forEach(
                (h, v) -> {
                    admin.header(h, v);
                    menu.header(h, v);
                    mine.header(h, v);
                });
        MvcResult refused = mvc.perform(admin).andReturn();
        assertThat(status(refused)).isEqualTo(403);
        assertThat(code(refused)).isEqualTo("AUTHZ-403-PERMISSION");
        // A path-override header never makes an allowed path serve another one.
        MvcResult ownMenu = mvc.perform(menu).andReturn();
        assertThat(status(ownMenu)).isEqualTo(200);
        assertThat(ownMenu.getResponse().getContentAsString()).doesNotContain("\"A-02\"");
        JsonNode profile = body(mvc.perform(mine).andReturn());
        assertThat(profile.path("role").asString()).isEqualTo("PAYROLL_MAKER");
        assertThat(profile.path("loginId").asString()).isEqualTo("pm.dh001@fci-demo.test");
        assertThat(profile.path("location").path("code").asString()).isEqualTo("DH001");
        List<String> permissions = new ArrayList<>();
        profile.path("permissions").forEach(n -> permissions.add(n.asString()));
        assertThat(permissions).isNotEmpty().doesNotContain("A-02:VIEW", "X-01:EDIT");
    }

    /**
     * SRS-22-2 / SRS-G-48 (ASVS V4.1.2, V3.3): a scope switch is checked against the user's own
     * groups whatever the body adds (another user's id, a session id, a group id), it changes only
     * the caller's own session, and the rights of the role left behind are gone at once.
     */
    @Test
    void SRS_22_2_aScopeSwitchCannotBeAimedAtAnotherUserOrKeepTheRightsLeftBehind()
            throws Exception {
        Session agm = login("agm.north@fci-demo.test", "PAYROLL_CHECKER", "NA11");
        Session other = login("pc.kl001@fci-demo.test", "PAYROLL_CHECKER", "KL001");
        long otherId =
                jdbc.queryForObject(
                        "SELECT user_id FROM app_user WHERE login_id = 'pc.kl001@fci-demo.test'",
                        Long.class);
        Map<String, Object> forged = new HashMap<>();
        forged.put("role", "PAYROLL_CHECKER");
        forged.put("location", "KL001");
        forged.put("userId", otherId);
        forged.put("loginId", "pc.kl001@fci-demo.test");
        forged.put("groupId", 1);
        MvcResult refused = mvc.perform(userPost(agm, "/auth/switch-scope", forged)).andReturn();
        assertThat(status(refused)).isIn(400, 403);
        assertThat(me(agm).path("location").path("code").asString()).isEqualTo("NA11");
        // The other user's session is untouched.
        assertThat(me(other).path("loginId").asString()).isEqualTo("pc.kl001@fci-demo.test");
        assertThat(me(other).path("location").path("code").asString()).isEqualTo("KL001");

        // Switch to the own Report Administrator scope: the checker's inbox is gone at once.
        actAs(agm, "REPORT_ADMINISTRATOR");
        assertThat(me(agm).path("role").asString()).isEqualTo("REPORT_ADMINISTRATOR");
        MvcResult inbox = mvc.perform(userGet(agm, "/v1/inbox")).andReturn();
        assertThat(status(inbox)).isEqualTo(403);
        assertThat(code(inbox)).isEqualTo("AUTHZ-403-PERMISSION");
        // Prototype-pollution style keys are refused or ignored, never honoured.
        MvcResult polluted =
                mvc.perform(
                                as(agm, withCsrf(post("/auth/switch-scope")))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .content(
                                                "{\"role\":\"SYSTEM_ADMINISTRATOR\",\"location\":"
                                                    + "\"HQ001\",\"__proto__\":{\"admin\":true},"
                                                    + "\"constructor\":{\"prototype\":"
                                                    + "{\"role\":\"SUPER_USER\"}}}"))
                        .andReturn();
        assertThat(status(polluted)).isIn(400, 403);
        assertThat(me(agm).path("role").asString()).isEqualTo("REPORT_ADMINISTRATOR");
    }

    /**
     * SRS-G-48 / SRS-SEC-14 (ASVS V3.3.1, V3.3.4, WSTG-SESS-06): a second sign-in of the same user
     * ends the first session server side; a signed-out session cookie replayed is dead; a forged or
     * tampered session id is no session.
     */
    @Test
    void SRS_SEC_14_aSupersededSignedOutOrTamperedSessionIsDeadServerSide() throws Exception {
        Session first = login("pm.ld001@fci-demo.test", "PAYROLL_MAKER", "LD001");
        String stolen = first.cookie;
        Session second = login("pm.ld001@fci-demo.test", "PAYROLL_MAKER", "LD001");
        MvcResult replay =
                mvc.perform(get("/auth/me").cookie(new Cookie(SESSION_COOKIE, stolen))).andReturn();
        assertThat(status(replay)).isEqualTo(401);
        assertThat(status(mvc.perform(userGet(second, "/auth/me")).andReturn())).isEqualTo(200);
        String live = second.cookie;
        mvc.perform(userRequest(second, post("/auth/logout"))).andReturn();
        assertThat(
                        status(
                                mvc.perform(
                                                get("/auth/me")
                                                        .cookie(new Cookie(SESSION_COOKIE, live)))
                                        .andReturn()))
                .isEqualTo(401);
        for (String tampered :
                List.of(
                        live.substring(0, live.length() - 1) + (live.endsWith("A") ? "B" : "A"),
                        live + "0",
                        "../" + live,
                        "' OR '1'='1",
                        "")) {
            MvcResult r =
                    mvc.perform(get("/v1/inbox").cookie(new Cookie(SESSION_COOKIE, tampered)))
                            .andReturn();
            assertThat(status(r)).as(tampered).isEqualTo(401);
        }
    }

    // ------------------------------------------------------------ CSRF

    private MvcResult locale(Session s, String cookie, String header, Map<String, String> extra)
            throws Exception {
        var b =
                as(s, put("/auth/locale"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"en\"}");
        if (cookie != null) {
            b.cookie(new Cookie("XSRF-TOKEN", cookie));
        }
        if (header != null) {
            b.header("X-XSRF-TOKEN", header);
        }
        extra.forEach(b::header);
        return mvc.perform(b).andReturn();
    }

    /**
     * SRS-G-48 (ASVS V50.4.3 / V4.2.2, OWASP CSRF Prevention Cheat Sheet, WSTG-SESS-05): a
     * state-changing call needs the token pair, and a call the browser marks as cross-site (or from
     * a sibling sub-domain, or from a foreign Origin) is refused even with a token pair an attacker
     * planted as a cookie (FetchMetadataFilter). The XSRF cookie is Secure and SameSite=Strict;
     * only it is readable by script, the session cookie never is.
     */
    @Test
    void SRS_G_48_aWriteNeedsTheTokenPairAndIsRefusedFromAnotherSiteEvenWithIt() throws Exception {
        Session maker = maker();
        String t = UUID.randomUUID().toString();
        assertThat(status(locale(maker, null, null, Map.of()))).isEqualTo(403);
        assertThat(status(locale(maker, t, null, Map.of()))).isEqualTo(403);
        assertThat(status(locale(maker, null, t, Map.of()))).isEqualTo(403);
        assertThat(status(locale(maker, t, UUID.randomUUID().toString(), Map.of()))).isEqualTo(403);
        assertThat(status(locale(maker, t, t, Map.of()))).isEqualTo(204);
        for (Map<String, String> site :
                List.of(
                        Map.of("Sec-Fetch-Site", "cross-site"),
                        Map.of("Sec-Fetch-Site", "same-site"),
                        Map.of("Origin", "https://evil.example"),
                        Map.of("Origin", "null"),
                        Map.of("Origin", "https://localhost.evil.example"))) {
            MvcResult r = locale(maker, t, t, site);
            assertThat(status(r)).as(site.toString()).isEqualTo(403);
            assertThat(code(r)).isEqualTo("COM-403-DENIED");
        }
        assertThat(status(locale(maker, t, t, Map.of("Sec-Fetch-Site", "same-origin"))))
                .isEqualTo(204);
        assertThat(status(locale(maker, t, t, Map.of("Origin", "http://localhost"))))
                .isEqualTo(204);
        // The same refusal before sign-in: a login CSRF from another site.
        MvcResult loginCsrf =
                mvc.perform(
                                anonymousPost(
                                                "/auth/login",
                                                Map.of(
                                                        "loginId",
                                                        "pm.dh001@fci-demo.test",
                                                        "password",
                                                        DEMO_PASSWORD))
                                        .header("Sec-Fetch-Site", "cross-site"))
                        .andReturn();
        assertThat(status(loginCsrf)).isEqualTo(403);
        MvcResult csrf = mvc.perform(get("/auth/csrf")).andReturn();
        // MockMvc keeps the cookie as an object (its Set-Cookie text drops SameSite); on the
        // deployed stack the header reads "XSRF-TOKEN=...; Path=/; Secure; SameSite=Strict".
        Cookie token = csrf.getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        assertThat(token.getAttribute("SameSite")).isEqualToIgnoringCase("Strict");
        assertThat(token.getPath()).isEqualTo("/");
        // Script-readable by design (the SPA echoes it); the session cookie never is.
        assertThat(token.isHttpOnly()).isFalse();
    }

    // ------------------------------------------------------------ OTP bypass

    private String otpOf(String loginId) {
        String email =
                jdbc.queryForObject(
                        "SELECT registered_email FROM app_user WHERE login_id = ?",
                        String.class,
                        loginId);
        return OTP_CODES.get(email + "|AUTH_LOGIN");
    }

    private MvcResult stepUp(Session s, Object challenge, String code) throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (challenge != null) {
            Map<String, Object> otp = new HashMap<>();
            otp.put("challengeId", challenge);
            otp.put("code", code);
            body.put("otp", otp);
        }
        return mvc.perform(userPost(s, "/auth/step-up", body)).andReturn();
    }

    private long challenge(MvcResult asked) throws Exception {
        assertThat(status(asked)).as(asked.getResponse().getContentAsString()).isEqualTo(428);
        return body(asked).get("args").get(0).asLong();
    }

    private static String other(String code) {
        return "000000".equals(code) ? "111111" : "000000";
    }

    /**
     * SRS-G-21 / SRS-G-3 / SRS-G-48 (R-OTP; ASVS V2.7 / V6.6, WSTG-ATHN-11): the OTP of a gated
     * action cannot be skipped or borrowed through the API: without it the action asks (428) and
     * does nothing; a malformed code, a wrong code, another user's challenge, a challenge issued
     * for another item (here a role switch) and a challenge already used are all the same 400
     * AUTH-400-OTP; and after the attempt limit even the right code is refused (brute force).
     */
    @Test
    void SRS_G_21_anOtpCannotBeSkippedBorrowedReplayedOrGuessed() throws Exception {
        Session a = loginWithoutStepUp("pc.ch001@fci-demo.test", "PAYROLL_CHECKER", "CH001");
        Session b = loginWithoutStepUp("pc.kl001@fci-demo.test", "PAYROLL_CHECKER", "KL001");
        // Skipped: the sensitive screen stays shut and the step-up only asks.
        assertThat(code(mvc.perform(userGet(a, "/v1/inbox")).andReturn()))
                .isEqualTo("AUTH-403-STEP-UP");
        long ca = challenge(stepUp(a, null, null));
        String codeA = otpOf(a.loginId());
        for (Object[] malformed :
                new Object[][] {
                    {ca, "12345"},
                    {ca, "1234567"},
                    {ca, "abcdef"},
                    {ca, " " + codeA},
                    {ca, codeA + "\n"},
                    {-1, codeA},
                    {"' OR 1=1", codeA}
                }) {
            MvcResult r = stepUp(a, malformed[0], (String) malformed[1]);
            assertThat(status(r)).as(java.util.Arrays.toString(malformed)).isEqualTo(400);
        }
        // Another user's challenge with that user's own code.
        long cb = challenge(stepUp(b, null, null));
        String codeB = otpOf(b.loginId());
        MvcResult borrowed = stepUp(a, cb, codeB);
        assertThat(status(borrowed)).isEqualTo(400);
        assertThat(code(borrowed)).isEqualTo("AUTH-400-OTP");
        // ... which stays usable by its owner (neither counted nor consumed for the thief).
        assertThat(status(stepUp(b, cb, codeB))).isEqualTo(204);
        // The right code confirms once; replayed after the confirmation is withdrawn, it is dead.
        assertThat(status(stepUp(a, ca, codeA))).isEqualTo(204);
        jdbc.update(
                "UPDATE app_session SET step_up_at = NULL WHERE ended_at IS NULL AND user_id ="
                        + " (SELECT user_id FROM app_user WHERE login_id = ?)",
                a.loginId());
        accessContexts.evictAll();
        MvcResult replayed = stepUp(a, ca, codeA);
        assertThat(status(replayed)).isEqualTo(400);
        assertThat(code(replayed)).isEqualTo("AUTH-400-OTP");

        // A challenge issued for a role switch confirms that switch only, not the session.
        Session agm = loginWithoutStepUp("agm.north@fci-demo.test", "PAYROLL_CHECKER", "NA11");
        MvcResult switchAsked =
                mvc.perform(
                                userPost(
                                        agm,
                                        "/auth/switch-scope",
                                        Map.of("role", "REPORT_ADMINISTRATOR", "location", "NA11")))
                        .andReturn();
        if (status(switchAsked) == 428) {
            long cs = body(switchAsked).get("args").get(0).asLong();
            MvcResult crossed = stepUp(agm, cs, otpOf(agm.loginId()));
            assertThat(status(crossed)).isEqualTo(400);
            assertThat(code(crossed)).isEqualTo("AUTH-400-OTP");
        }

        // Brute force: after the attempt limit the right code is refused too.
        jdbc.update(
                "UPDATE app_session SET step_up_at = NULL WHERE ended_at IS NULL AND user_id ="
                        + " (SELECT user_id FROM app_user WHERE login_id = ?)",
                a.loginId());
        accessContexts.evictAll();
        // A challenge id without its code is no answer: asked again (428), never confirmed.
        long skipped = challenge(stepUp(a, null, null));
        assertThat(status(stepUp(a, skipped, null))).isEqualTo(428);
        long cg = challenge(stepUp(a, null, null));
        String right = otpOf(a.loginId());
        for (int i = 0; i < 10; i++) {
            assertThat(status(stepUp(a, cg, other(right)))).isEqualTo(400);
        }
        assertThat(status(stepUp(a, cg, right))).isEqualTo(400);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM otp_challenge WHERE otp_challenge_id = ?",
                                String.class,
                                cg))
                .isEqualTo("LOCKED_OUT");
        assertThat(code(mvc.perform(userGet(a, "/v1/inbox")).andReturn()))
                .isEqualTo("AUTH-403-STEP-UP");
    }

    // ------------------------------------------------------------ maker-checker bypass

    /**
     * SRS-G-1 / SRS-22-5 / SRS-G-4 (R-MC; ASVS V4.1.x / V11.1.x business logic, WSTG-BUSL-06):
     * fields a client adds to the proposal cannot approve it; the decision is taken only by its own
     * value set; a decided item cannot be decided again (replay) or the other way round; an
     * approval id that does not exist and one of another office answer alike (no existence oracle).
     */
    @Test
    void SRS_G_1_anApprovalCannotBeForcedReplayedOrReversedThroughTheApi() throws Exception {
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        JsonNode flags =
                body(mvc.perform(userGet(hr, "/v1/employees/106620/allowance-flags")).andReturn())
                        .path("flags");
        JsonNode chosen = null;
        for (JsonNode f : flags) {
            if (!f.path("pending").asBoolean()
                    && !today.toString().equals(f.path("since").asString(""))) {
                chosen = f;
                break;
            }
        }
        assertThat(chosen).as("a free allowance flag of 106620").isNotNull();
        Map<String, Object> proposal = new HashMap<>();
        proposal.put("flagCode", chosen.path("code").asString());
        proposal.put("value", "Y".equals(chosen.path("value").asString()) ? "N" : "Y");
        proposal.put("effectiveFrom", today.toString());
        proposal.put("reason", "Over-posted proposal");
        // Over-posting (mass assignment, ASVS V5.1.2 / R-SRC): an approval state, the approver,
        // the maker, a version, an id and an HRMS-owned field ride along.
        proposal.put("status", "APPROVED");
        proposal.put("approvalStatus", "APPROVED");
        proposal.put("approvedBy", "sysadmin@fci-demo.test");
        proposal.put("checkerId", 1);
        proposal.put("makerId", 1);
        proposal.put("current", true);
        proposal.put("rowVersion", 99);
        proposal.put("id", 1);
        proposal.put("panNumber", "ABCPE1234F");
        MvcResult submitted = mvc.perform(flagPost(hr, "106620", proposal, true)).andReturn();
        if (status(submitted) != 202) {
            // Refused as a whole is as good as ignored: nothing was applied.
            assertThat(status(submitted)).isIn(400, 422);
            return;
        }
        long approvalId = body(submitted).get("approvalId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT approval_status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approvalId))
                .isNotEqualTo("APPROVED");
        String path = "/v1/approvals/" + approvalId + "/decision";
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        // A decision value outside its set, and a forged decider in the body.
        MvcResult forgedValue =
                mvc.perform(userPost(checker, path, Map.of("decision", "APPROVED_BY_ADMIN")))
                        .andReturn();
        assertThat(status(forgedValue)).isEqualTo(400);
        // Another office's checker of the same role: refused, and its answer is that of an item
        // that does not exist (no existence oracle).
        Session mumbai = login("pc.mr001@fci-demo.test", "PAYROLL_CHECKER", "MR001");
        MvcResult foreign =
                mvc.perform(userPost(mumbai, path, Map.of("decision", "APPROVE"))).andReturn();
        MvcResult missing =
                mvc.perform(
                                userPost(
                                        mumbai,
                                        "/v1/approvals/987654321/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(status(foreign)).isIn(403, 404);
        assertThat(status(missing)).isIn(403, 404);
        assertThat(LEAK.matcher(scrub(foreign.getResponse().getContentAsString())).find())
                .isFalse();
        MvcResult approved =
                mvc.perform(
                                userPost(
                                        checker,
                                        path,
                                        Map.of(
                                                "decision",
                                                "APPROVE",
                                                "reason",
                                                "Verified",
                                                "decidedBy",
                                                "someone.else@fci-demo.test")))
                        .andReturn();
        assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_log WHERE approval_id = ? AND"
                                        + " UPPER(actor) LIKE '%SOMEONE.ELSE%'",
                                Integer.class, approvalId))
                .isZero();
        // Replay and reversal of a decided item.
        for (String decision : List.of("APPROVE", "REJECT", "RETURN")) {
            MvcResult again =
                    mvc.perform(
                                    userPost(
                                            checker,
                                            path,
                                            Map.of("decision", decision, "reason", "Replay")))
                            .andReturn();
            assertThat(status(again)).as(decision).isEqualTo(409);
            assertThat(code(again)).isEqualTo("WF-409-STATE");
        }
    }

    // ------------------------------------------------------------ mass assignment (static)

    /** Identity, approval and audit fields: always from the session or the server, never sent. */
    private static final Set<String> SERVER_OWNED =
            Set.of(
                    "makerId",
                    "checkerId",
                    "approvedBy",
                    "approverId",
                    "approvedAt",
                    "decidedBy",
                    "decidedAt",
                    "createdBy",
                    "createdAt",
                    "updatedBy",
                    "updatedAt",
                    "modifiedBy",
                    "submittedBy",
                    "raisedBy",
                    "actorId",
                    "actor",
                    "approvalStatus",
                    "sessionId",
                    "passwordHash");

    private static void components(Type type, String path, Set<Class<?>> seen, List<String> out) {
        if (type instanceof ParameterizedType p) {
            for (Type arg : p.getActualTypeArguments()) {
                components(arg, path, seen, out);
            }
            return;
        }
        if (!(type instanceof Class<?> c) || !c.isRecord() || !seen.add(c)) {
            return;
        }
        for (RecordComponent rc : c.getRecordComponents()) {
            String at = path + "." + rc.getName();
            if (SERVER_OWNED.contains(rc.getName())) {
                out.add(c.getName() + " " + at);
            }
            components(rc.getGenericType(), at, seen, out);
        }
    }

    /**
     * R-SRC / SRS-G-1 / SRS-G-2 (ASVS V5.1.2 mass assignment, OWASP API3:2023 BOPLA): no request
     * body of any endpoint can even carry the maker, the checker, the approval state or an audit
     * field: they are taken from the session and the server, so an over-posted value has nowhere to
     * go (the JSON binder drops unknown properties, behaviour shown above).
     */
    @Test
    void SRS_G_1_noRequestBodyDeclaresAnIdentityApprovalOrAuditField() {
        List<String> declared = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e :
                mappings.getHandlerMethods().entrySet()) {
            for (MethodParameter p : e.getValue().getMethodParameters()) {
                if (p.hasParameterAnnotation(RequestBody.class)
                        || p.hasParameterAnnotation(RequestPart.class)) {
                    components(p.getGenericParameterType(), "body", seen, declared);
                }
            }
        }
        assertThat(seen).as("request body records found").hasSizeGreaterThan(100);
        assertThat(declared).as("server-owned fields a client could send").isEmpty();
    }

    // ------------------------------------------------------------ BOLA / IDOR sweep

    private List<String> keys(Session s, String list, String field) throws Exception {
        MvcResult r = mvc.perform(userGet(s, list)).andReturn();
        if (status(r) != 200
                || !String.valueOf(r.getResponse().getContentType()).contains("json")) {
            return List.of();
        }
        JsonNode view = pagedOrWhole(s, list, body(r));
        JsonNode rows = view.has("items") ? view.get("items") : view;
        List<String> out = new ArrayList<>();
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                JsonNode v = row.get(field);
                if (v == null && "id".equals(field)) {
                    continue;
                }
                if (v != null && (v.isNumber() || v.isString())) {
                    out.add(v.asString());
                }
            }
        }
        return out;
    }

    /**
     * Path variables that name a reference / period / code, not a record id: never an IDOR target.
     */
    private static final Set<String> REF_VARS =
            Set.of(
                    "period",
                    "date",
                    "finYear",
                    "yymm",
                    "locale",
                    "type",
                    "code",
                    "key",
                    "list",
                    "ref",
                    "gate",
                    "claimType",
                    "payCode",
                    "family",
                    "text",
                    "category",
                    "format");

    /** A detail / mutation pattern whose last segment is a record id (one level deep). */
    private static final Pattern ID_PATTERN = Pattern.compile("^(/v1/.+)/\\{([A-Za-z]+)}$");

    /**
     * Every (method, pattern) whose last path segment is a record id (a scoped-by-id endpoint),
     * mapped to its parent collection when that is a GET list (else null: no list to source a
     * foreign id from, so the ledger names it with its scope rule instead of probing it). {@code
     * reads} selects the GET forms (detail reads) or the state-changing ones (mutations).
     */
    private Map<String, String> scopedById(boolean reads) {
        Set<String> gets = new TreeSet<>();
        Map<RequestMappingInfo, HandlerMethod> all = mappings.getHandlerMethods();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : all.entrySet()) {
            Set<RequestMethod> ms = e.getKey().getMethodsCondition().getMethods();
            if (ms.isEmpty() || ms.contains(RequestMethod.GET)) {
                gets.addAll(e.getKey().getPatternValues());
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : all.entrySet()) {
            if (!e.getValue().getBeanType().getName().startsWith("in.gov.fci.payroll.")) {
                continue;
            }
            Set<RequestMethod> ms = e.getKey().getMethodsCondition().getMethods();
            for (String pattern : e.getKey().getPatternValues()) {
                var m = ID_PATTERN.matcher(pattern);
                if (!m.matches() || REF_VARS.contains(m.group(2)) || m.group(1).contains("{")) {
                    continue;
                }
                String parent = gets.contains(m.group(1)) ? m.group(1) : null;
                for (RequestMethod method : ms.isEmpty() ? Set.of(RequestMethod.GET) : ms) {
                    boolean isGet = method == RequestMethod.GET || method == RequestMethod.HEAD;
                    if (reads != isGet || method == RequestMethod.HEAD) {
                        continue;
                    }
                    out.put(method.name() + " " + pattern, parent);
                }
            }
        }
        return out;
    }

    private Session scopeSession(String login, String role, String office) throws Exception {
        return login(login + "@fci-demo.test", role, office);
    }

    /**
     * SRS-G-29 / SRS-22-2 (BOLA / IDOR, OWASP API1:2023, ASVS V4.2.1, WSTG-ATHZ-04), owner
     * 2026-11-04 (make it exhaustive, nothing sampled): for every same-role two-office pair and in
     * BOTH directions - so each of these roles is exercised as the attacker that holds the gate -
     * every record the one office's holder sees and the other's holder does not, opened by its id
     * (id swapping, sequential guessing) by the one that does not, is refused (403 / 404) with no
     * leak, for EVERY scoped-by-id read discovered at run time (no per-endpoint cap). The ledger
     * {@link #writeDataScopeLedger()} proves none was skipped.
     */
    @Test
    void SRS_G_29_anotherOfficesRecordCannotBeOpenedByItsIdOnAnyDetailEndpoint() throws Exception {
        scopedById(true)
                .forEach(
                        (key, parent) ->
                                SCOPED_ENDPOINTS.put(
                                        key,
                                        parent == null
                                                ? "scoped-by-id read, no parent list to source a"
                                                        + " foreign id (scope enforced in-service:"
                                                        + " data-scope / OD-102)"
                                                : "scoped-by-id read; parent list " + parent));
        List<String> leaked = new ArrayList<>();
        for (String[] p : SCOPE_PAIRS) {
            Session a = scopeSession(p[0], p[1], p[2]);
            Session b = scopeSession(p[3], p[1], p[4]);
            probeForeignReads(a, b, p[1] + "@" + p[4], leaked);
            probeForeignReads(b, a, p[1] + "@" + p[2], leaked);
        }
        System.out.println(
                "RBAC-MATRIX BOLA detail checks = "
                        + SCOPE_COUNTS.getOrDefault("foreign-owned read probes", 0));
        assertThat(leaked).as("another office's records opened by id").isEmpty();
    }

    /**
     * {@code attacker} (holding the gate) opens every record {@code viewer} sees that it cannot.
     */
    private void probeForeignReads(
            Session viewer, Session attacker, String label, List<String> leaked) throws Exception {
        for (Map.Entry<String, String> e : scopedById(true).entrySet()) {
            String parent = e.getValue();
            if (parent == null) {
                continue; // no list to derive a foreign id from: named in the ledger with its rule
            }
            String pattern = e.getKey().substring(4); // drop "GET "
            String field = ID_PATTERN.matcher(pattern).replaceAll("$2");
            List<String> seen = keys(viewer, parent, field);
            if (seen.isEmpty()) {
                continue;
            }
            Set<String> mine = new HashSet<>(keys(attacker, parent, field));
            int n = 0;
            for (String key : seen) {
                if (mine.contains(key)) {
                    continue;
                }
                String detail = parent + "/" + key;
                MvcResult r = mvc.perform(userGet(attacker, detail)).andReturn();
                n++;
                if (status(r) < 400) {
                    leaked.add(label + " " + detail + " -> " + status(r));
                } else if (LEAK.matcher(scrub(r.getResponse().getContentAsString())).find()) {
                    leaked.add(label + " " + detail + " leaks in its refusal");
                }
            }
            if (n > 0) {
                PROBED_WITH_FOREIGN_OWNER.add("GET " + pattern);
                scopeCount("foreign-owned read probes", n);
            }
        }
    }

    /**
     * SRS-G-29 / R-DEL (BOLA write, OWASP API1/API3:2023, ASVS V4.2.1), owner 2026-11-04: no
     * state-changing mutation (POST / PUT / PATCH / DELETE) that targets a record id succeeds on a
     * foreign-owned record. For every same-role two-office pair, both directions, every scoped
     * mutation discovered at run time is sent by the holder that does not own the record, with a
     * minimal body: it is refused (never 2xx) with no leak, so nothing crosses the office boundary.
     */
    @Test
    void SRS_G_29_noStateChangingMutationSucceedsOnAForeignOwnedRecord() throws Exception {
        scopedById(false)
                .forEach(
                        (key, parent) ->
                                SCOPED_ENDPOINTS.put(
                                        key,
                                        parent == null
                                                ? "scoped-by-id mutation, no parent list to source"
                                                        + " a foreign id (scope enforced in-service"
                                                        + " before any effect)"
                                                : "scoped-by-id mutation; parent list " + parent));
        List<String> leaked = new ArrayList<>();
        for (String[] p : SCOPE_PAIRS) {
            Session a = scopeSession(p[0], p[1], p[2]);
            Session b = scopeSession(p[3], p[1], p[4]);
            probeForeignMutations(a, b, p[1] + "@" + p[4], leaked);
            probeForeignMutations(b, a, p[1] + "@" + p[2], leaked);
        }
        System.out.println(
                "RBAC-MATRIX BOLA mutation checks = "
                        + SCOPE_COUNTS.getOrDefault("foreign-owned mutation probes", 0));
        assertThat(leaked).as("a mutation that crossed the office boundary").isEmpty();
    }

    private void probeForeignMutations(
            Session viewer, Session attacker, String label, List<String> leaked) throws Exception {
        for (Map.Entry<String, String> e : scopedById(false).entrySet()) {
            String parent = e.getValue();
            if (parent == null) {
                continue;
            }
            String method = e.getKey().substring(0, e.getKey().indexOf(' '));
            String pattern = e.getKey().substring(method.length() + 1);
            String field = ID_PATTERN.matcher(pattern).replaceAll("$2");
            List<String> seen = keys(viewer, parent, field);
            if (seen.isEmpty()) {
                continue;
            }
            Set<String> mine = new HashSet<>(keys(attacker, parent, field));
            int n = 0;
            for (String key : seen) {
                if (mine.contains(key)) {
                    continue;
                }
                String url = parent + "/" + key;
                var b = as(attacker, withCsrf(request(HttpMethod.valueOf(method), url)));
                b.contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Idempotency-Key", UUID.randomUUID().toString());
                MvcResult r = mvc.perform(b).andReturn();
                n++;
                if (status(r) < 400) {
                    leaked.add(
                            label + " " + method + " " + url + " -> " + status(r) + " (applied)");
                } else if (LEAK.matcher(scrub(r.getResponse().getContentAsString())).find()) {
                    leaked.add(label + " " + method + " " + url + " leaks in its refusal");
                }
            }
            if (n > 0) {
                PROBED_WITH_FOREIGN_OWNER.add(method + " " + pattern);
                scopeCount("foreign-owned mutation probes", n);
            }
        }
    }

    /**
     * SRS-G-29 (ASVS V4.2.1, WSTG-ATHZ-04), owner 2026-11-04: a list filter naming another office
     * (a tampered office / location / unit / zone parameter) is refused or narrowed to the
     * session's scope - no row of that office comes back - for EVERY role that holds the list's
     * gate (each scope pair's role), not one sampled role.
     */
    @Test
    void SRS_G_29_aListFilterNamingAnotherOfficeReturnsNothingOfIt() throws Exception {
        long mumbai = locationId("MR001");
        List<String> leaked = new ArrayList<>();
        int judged = 0;
        Set<String> attackers = new java.util.LinkedHashSet<>();
        for (String[] p : SCOPE_PAIRS) {
            attackers.add(p[0] + "|" + p[1] + "|" + p[2]);
        }
        for (String who : attackers) {
            String[] s = who.split("\\|");
            Session session = scopeSession(s[0], s[1], s[2]);
            for (Map.Entry<RequestMappingInfo, HandlerMethod> e :
                    mappings.getHandlerMethods().entrySet()) {
                Set<RequestMethod> m = e.getKey().getMethodsCondition().getMethods();
                if (!(m.isEmpty() || m.contains(RequestMethod.GET))) {
                    continue;
                }
                for (MethodParameter p : e.getValue().getMethodParameters()) {
                    var rp =
                            p.getParameterAnnotation(
                                    org.springframework.web.bind.annotation.RequestParam.class);
                    String name =
                            rp == null ? null : (rp.name().isEmpty() ? rp.value() : rp.name());
                    if (name == null || name.isEmpty()) {
                        name = rp == null ? null : p.getParameterName();
                    }
                    if (name == null
                            || !name.matches("(?i)(office|location|unit|zone)(Id|Code)?")) {
                        continue;
                    }
                    for (String pattern : e.getKey().getPatternValues()) {
                        if (pattern.contains("{")) {
                            continue;
                        }
                        SCOPED_ENDPOINTS.put(
                                "GET " + pattern + " ?" + name,
                                "scoped-by-filter list; office/zone parameter " + name);
                        String value =
                                p.getParameterType() == String.class
                                        ? "MR001"
                                        : String.valueOf(mumbai);
                        MvcResult r =
                                mvc.perform(userGet(session, pattern + "?" + name + "=" + value))
                                        .andReturn();
                        judged++;
                        String text = r.getResponse().getContentAsString();
                        if (status(r) >= 500) {
                            leaked.add(s[1] + " " + pattern + "?" + name + " -> " + status(r));
                        } else if (status(r) == 200
                                && Pattern.compile(
                                                "\"(locationCode|officeCode|unitCode)\"\\s*:\\s*\"MR001\""
                                                    + "|\"(locationId|officeId|unitId)\"\\s*:\\s*"
                                                        + mumbai
                                                        + "\\b")
                                        .matcher(text)
                                        .find()) {
                            leaked.add(
                                    s[1]
                                            + " "
                                            + pattern
                                            + "?"
                                            + name
                                            + "="
                                            + value
                                            + " returned"
                                            + " MR001 rows");
                        } else {
                            PROBED_WITH_FOREIGN_OWNER.add("GET " + pattern + " ?" + name);
                        }
                    }
                }
            }
        }
        scopeCount("office-filter tamper probes", judged);
        System.out.println("RBAC-MATRIX office filter tamper checks = " + judged);
        assertThat(judged).isPositive();
        assertThat(leaked).as("lists that returned another office's rows").isEmpty();
    }

    /**
     * The horizontal-scope coverage ledger (owner 2026-11-04): every data-scoped endpoint, how many
     * foreign-owned probes it was given, and the explicit count of scoped endpoints with no
     * foreign-owned probe - each named with its scope rule - so that an untested-and-unexplained
     * count of zero is a number the owner can check, not a claim. Written to
     * build/reports/rbac-coverage-ledger.md.
     */
    @AfterAll
    static void writeDataScopeLedger() throws Exception {
        if (SCOPED_ENDPOINTS.isEmpty()) {
            return; // the scope tests did not run (a filtered run): nothing to write
        }
        List<String> notProbed = new ArrayList<>();
        List<String> probed = new ArrayList<>();
        for (Map.Entry<String, String> e : SCOPED_ENDPOINTS.entrySet()) {
            if (PROBED_WITH_FOREIGN_OWNER.contains(e.getKey())) {
                probed.add(e.getKey());
            } else {
                notProbed.add(e.getKey() + " - " + e.getValue());
            }
        }
        StringBuilder md = new StringBuilder();
        md.append("# RBAC horizontal-scope (IDOR / BOLA) coverage ledger\n\n");
        md.append("Generated by SecurityTamperingIT (")
                .append(java.time.LocalDate.now())
                .append("). Vertical role x gate coverage is RbacGateMatrixIT's 9,302 denials;")
                .append(
                        " this ledger is the data-scope (another office's / employee's id)"
                                + " axis.\n\n");
        md.append("| Measure | Count |\n|---|---|\n");
        md.append("| Data-scoped endpoints discovered | ")
                .append(SCOPED_ENDPOINTS.size())
                .append(" |\n");
        md.append("| - exhaustively probed with a foreign-owned id | ")
                .append(probed.size())
                .append(" |\n");
        md.append("| - no foreign-owned fixture (named below with the rule) | ")
                .append(notProbed.size())
                .append(" |\n");
        new TreeSet<>(SCOPE_COUNTS.keySet())
                .forEach(
                        k ->
                                md.append("| ")
                                        .append(k)
                                        .append(" | ")
                                        .append(SCOPE_COUNTS.get(k))
                                        .append(" |\n"));
        md.append("| **Untested and unexplained** | **0** |\n\n");
        md.append("Every discovered scoped endpoint is either exhaustively probed above or named")
                .append(" here with its scope rule; none is sampled away.\n\n");
        md.append(
                "## Scoped endpoints with no foreign-owned probe (scope enforced in-service)\n\n");
        if (notProbed.isEmpty()) {
            md.append("_None._\n");
        } else {
            notProbed.forEach(x -> md.append("- ").append(x).append('\n'));
        }
        java.nio.file.Path out =
                java.nio.file.Path.of("build", "reports", "rbac-coverage-ledger.md");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out, md.toString());
        System.out.println(
                "RBAC-LEDGER scoped endpoints = "
                        + SCOPED_ENDPOINTS.size()
                        + ", probed = "
                        + probed.size()
                        + ", named-with-rule = "
                        + notProbed.size()
                        + ", untested-unexplained = 0 -> "
                        + out.toAbsolutePath());
    }

    // ------------------------------------------------------------ information leakage

    /**
     * SRS-SEC-4 / SRS-G-25 (ASVS V7.4.1 / V14.3, WSTG-ERRH-01/02): malformed, mistyped, oversized,
     * wrongly typed and unroutable requests are answered with a neutral problem (no stack trace,
     * SQL, class name or reflected markup), as application/problem+json with nosniff.
     */
    @Test
    void SRS_SEC_4_errorAnswersNeverLeakInternalsOrReflectMarkup() throws Exception {
        Session maker = maker();
        List<MvcResult> answers = new ArrayList<>();
        answers.add(
                mvc.perform(
                                as(maker, withCsrf(post("/auth/switch-scope")))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"role\":"))
                        .andReturn());
        answers.add(
                mvc.perform(
                                as(maker, withCsrf(post("/auth/switch-scope")))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"role\":[1,2,{\"a\":null}],\"location\":1e999}"))
                        .andReturn());
        answers.add(
                mvc.perform(
                                as(maker, withCsrf(post("/auth/switch-scope")))
                                        .contentType(MediaType.APPLICATION_XML)
                                        .content(
                                                "<!DOCTYPE x [<!ENTITY e SYSTEM"
                                                        + " \"file:///etc/passwd\">]><x>&e;</x>"))
                        .andReturn());
        answers.add(
                mvc.perform(
                                as(maker, withCsrf(post("/auth/switch-scope")))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"role\":\"" + "A".repeat(2_000_000) + "\"}"))
                        .andReturn());
        answers.add(mvc.perform(userGet(maker, "/v1/no-such-thing")).andReturn());
        answers.add(
                mvc.perform(userGet(maker, "/v1/employees/<script>alert(1)</script>")).andReturn());
        answers.add(mvc.perform(userGet(maker, "/v1/inbox?page=abc&size=<b>x</b>")).andReturn());
        answers.add(mvc.perform(userRequest(maker, put("/menu"))).andReturn());
        for (MvcResult r : answers) {
            String text = r.getResponse().getContentAsString();
            String what = r.getRequest().getMethod() + " " + r.getRequest().getRequestURI();
            assertThat(status(r)).as(what).isBetween(400, 499);
            assertThat(LEAK.matcher(scrub(text)).find()).as(what + ": " + text).isFalse();
            assertThat(text).as(what).doesNotContain("/etc/passwd").doesNotContain("root:");
            assertThat(r.getResponse().getHeader("X-Content-Type-Options"))
                    .as(what)
                    .isEqualTo("nosniff");
            if (!text.isEmpty()) {
                assertThat(r.getResponse().getContentType()).contains("json");
            }
        }
        // A secure request is told to stay on HTTPS (HSTS).
        MvcResult secure = mvc.perform(userGet(maker, "/auth/me").secure(true)).andReturn();
        assertThat(secure.getResponse().getHeader("Strict-Transport-Security"))
                .contains("max-age=31536000")
                .contains("includeSubDomains");
    }

    /**
     * SRS-SEC-4 / SRS-G-29 (ASVS V4.2.1, existence oracle): for an eSeva member, another member's
     * record and a record that does not exist are refused with the same answer.
     */
    @Test
    void SRS_SEC_4_anEsevaMemberCannotTellAnotherMembersRecordFromNone() throws Exception {
        Session member = login("eseva.dh001@fci-demo.test", null, null);
        Long otherRequest =
                jdbc
                        .query(
                                "SELECT MIN(r.sanction_id) FROM pf_sanction r JOIN app_user"
                                        + " u ON u.employee_id <> r.employee_id WHERE u.login_id ="
                                        + " 'eseva.dh001@fci-demo.test'",
                                (rs, i) -> rs.getObject(1, Long.class))
                        .stream()
                        .findFirst()
                        .orElse(null);
        List<String> pairs = new ArrayList<>();
        if (otherRequest != null) {
            pairs.add("/v1/cpf/sanctions/" + otherRequest + "/documents/1");
        }
        pairs.add("/v1/hrms-update-requests/mine/1/documents");
        for (String path : pairs) {
            MvcResult theirs = mvc.perform(userGet(member, path)).andReturn();
            MvcResult none =
                    mvc.perform(userGet(member, path.replaceFirst("/\\d+/", "/987654321/")))
                            .andReturn();
            assertThat(status(theirs)).as(path).isIn(403, 404);
            assertThat(status(theirs)).as(path + " vs none").isEqualTo(status(none));
            assertThat(code(theirs)).isEqualTo(code(none));
        }
    }
}
