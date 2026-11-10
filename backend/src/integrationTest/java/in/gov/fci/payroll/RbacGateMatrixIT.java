package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * The role-screen-action matrix of the API (SRS-22-1, SRS-22-8: roles and grants are configuration;
 * SRS-G-25: only the actions a role holds; deny by default), exhaustively and server side: the
 * browser is assumed to be under the attacker's control (an intercepting proxy edits any request,
 * hidden or disabled buttons protect nothing).
 *
 * <p>The oracle is the configuration itself, read at run time: {@code app_role_permission} of each
 * role (effective today) and the {@code @PreAuthorize} gate of every handler method, discovered
 * from the running application. A grant changed on A-02 changes the expectation; a new endpoint or
 * a new role is covered the day it is added (or fails here until it is).
 *
 * <ul>
 *   <li>every handler carries an {@code @authz} gate or is one of the listed open endpoints, each
 *       with its reason (OWASP ASVS V4.1.3 / V4.1.5, WSTG-ATHZ-02);
 *   <li>every gate names a (screen, action) of the permission catalogue {@code app_permission};
 *   <li>every role of {@code app_role} has a coverage user, whose session holds exactly the role's
 *       grants (no more: least privilege, ASVS V4.1.3);
 *   <li>each role x each gate it does not hold: 403 with the gate's problem code, BEFORE the
 *       request is read (no validation detail, no 404 existence oracle), a neutral problem body,
 *       and the same refusal for HEAD, a method-override header and a {@code _method} field (forced
 *       browsing and verb tampering, ASVS V4.2.1 / V13.1, WSTG-ATHZ-02, WSTG-CONF-06);
 *   <li>each role x each read it holds: never the permission refusal (the gate follows the
 *       configuration), the list paging allow-list enforced (SRS-NF-3) and SQL metacharacters in
 *       any query parameter never answered with 5xx or a database message (ASVS V5.3.4);
 *   <li>without a session: 401 on every gated endpoint;
 *   <li>path tricks (trailing slash, case, matrix and ;jsessionid parameters, dot segments, encoded
 *       and double-encoded slashes) never reach a gated handler (WSTG-ATHZ-01);
 *   <li>every answer carries the security headers (ASVS V14.4).
 * </ul>
 *
 * Every check is read-only or refused at the gate: nothing is changed.
 */
class RbacGateMatrixIT extends ApiTestSupport {

    /** One coverage user per role (seed V037_9 and the depot manager of V058_1). */
    private record Coverage(String login, String role, String office) {}

    private static final List<Coverage> COVERAGE =
            List.of(
                    new Coverage("pm.dh001", "PAYROLL_MAKER", "DH001"),
                    new Coverage("pc.dh001", "PAYROLL_CHECKER", "DH001"),
                    new Coverage("pc.hq001", "PAYROLL_CHECKER", "HQ001"),
                    new Coverage("hr.dh001", "ESTABLISHMENT_HR", "DH001"),
                    new Coverage("fin.dh001", "FINANCE", "DH001"),
                    new Coverage("ldh.dpdl01", "LABOUR_DEALING_HAND", "DP-DL01"),
                    new Coverage("dm.okhla", "DEPOT_MANAGER", "DP-DL01"),
                    new Coverage("ucpf.dh001", "UNIT_CPF_ADMIN", "DH001"),
                    new Coverage("cpfmgr.na11", "CPF_MANAGER", "NA11"),
                    new Coverage("cpfdiv.na11", "CPF_DIVISION", "NA11"),
                    new Coverage("agmcpf.na11", "AGM_CPF", "NA11"),
                    new Coverage("cpfsa.na11", "CPF_SANCTIONING_AUTHORITY", "NA11"),
                    new Coverage("hqcpf.hq001", "HQ_CPF_APPROVER", "HQ001"),
                    new Coverage("rpt.na11", "REPORT_ADMINISTRATOR", "NA11"),
                    new Coverage("su.hq001", "SUPER_USER", "HQ001"),
                    new Coverage("ruleadm.hq001", "RULE_ADMINISTRATOR", "HQ001"),
                    new Coverage("ruleapp.hq001", "RULE_APPROVER", "HQ001"),
                    new Coverage("stat.hq001", "STATUTORY_ADMINISTRATOR", "HQ001"),
                    new Coverage("pension.hq001", "PENSION_DIVISION_HQ", "HQ001"),
                    new Coverage("aud.hq001", "AUDITOR", "HQ001"),
                    new Coverage("sysadm.hq001", "SYSTEM_ADMINISTRATOR", "HQ001"),
                    new Coverage("sysadm.dh001", "SYSTEM_ADMINISTRATOR", "DH001"),
                    new Coverage("eseva.dh001", "EMPLOYEE_ESEVA", null));

    /**
     * The endpoints open to more than one gate, each for its reason: {@code Controller#method}, or
     * {@code Controller#*} for the whole controller. Anything else without an {@code @authz} gate
     * fails {@link #SRS_22_8_everyEndpointCarriesAnAuthzGateOrIsAListedOpenOne}.
     */
    private static final Map<String, String> OPEN =
            Map.ofEntries(
                    Map.entry(
                            "AuthController#*",
                            "sign-in, OTP, sign-out, password reset, language, working period:"
                                    + " before a role is chosen, or the session's own settings"),
                    Map.entry(
                            "MeController#*",
                            "the signed-in user's own profile, scopes, menu, actions, step-up and"
                                    + " scope switch (the switch is checked against the user's own"
                                    + " scopes)"),
                    Map.entry("I18nController#*", "UI labels (no data)"),
                    Map.entry(
                            "ReferenceController#*",
                            "dropdown values every screen needs (no personal data)"),
                    Map.entry(
                            "PeriodController#*",
                            "the payroll periods board of the session's own office"),
                    Map.entry(
                            "DocumentTypeController#*",
                            "the order document types of an upload form (no personal data)"),
                    Map.entry(
                            "RecordHistoryController#*",
                            "History button: VIEW on the record's own screen at its office, checked"
                                    + " by RecordHistoryService per record type"),
                    Map.entry("StaffNoticeController#mine", "the session's own staff notices"),
                    Map.entry(
                            "StaffNoticeController#read",
                            "marks one of the session's own staff notices read"),
                    Map.entry(
                            "WorkflowController#action",
                            "governed button: the gate is the (screen, action) the body names and"
                                    + " the one performed (ActionService), see"
                                    + " SRS_G_25_aGovernedButtonIs..."),
                    Map.entry(
                            "DemoOtpController#*",
                            "OD-146 demo OTP code viewer: the caller's own newest code only (no"
                                    + " user parameter); 404 unless a demo stack switched it on"
                                    + " (StartupGuards refuse the switch in UAT and production)"));

    /** The fields of a problem answer (ProblemDetailFactory): nothing else may appear. */
    private static final Set<String> PROBLEM_KEYS =
            Set.of(
                    "type",
                    "title",
                    "status",
                    "detail",
                    "instance",
                    "errorCode",
                    "messageKey",
                    "severity",
                    "actionKey",
                    "args",
                    "correlationId",
                    "timestamp",
                    "violations");

    /** Something a refusal must never show: stack, SQL, internal classes, PAN or Aadhaar. */
    private static final Pattern LEAK =
            Pattern.compile(
                    "Exception|\\bat in\\.gov|org\\.(springframework|hibernate|h2)|java\\.|SQL"
                            + "|ORA-\\d|\\b[A-Z]{5}\\d{4}[A-Z]\\b|\\b\\d{12}\\b");

    private static final Pattern GATE =
            Pattern.compile("^@authz\\.gate\\('([A-Z]-\\d{2})','([A-Z_]+)'\\)$");
    private static final Pattern EITHER =
            Pattern.compile(
                    "^@authz\\.gateEither\\('([A-Z]-\\d{2})',\\s*'([A-Z_]+)',\\s*'([A-Z]-\\d{2})',"
                            + "\\s*'([A-Z_]+)'\\)$");
    private static final Pattern CAN =
            Pattern.compile("^@authz\\.can\\('([A-Z]-\\d{2})','([A-Z_]+)'\\)$");
    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z]+)(?::[^}]*)?}");

    /** The checks made, by kind (printed for docs/rbac and the report). */
    private static final Map<String, Integer> COUNTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    /** A gated endpoint: one HTTP method of one pattern of a handler. */
    private record Gated(
            String method,
            String pattern,
            String url,
            Set<String> needs,
            String deniedCode,
            HandlerMethod handler) {
        String key() {
            return method + " " + pattern;
        }
    }

    private static void count(String kind, int n) {
        COUNTS.merge(kind, n, Integer::sum);
    }

    @AfterAll
    static void report() {
        new TreeSet<>(COUNTS.keySet())
                .forEach(k -> System.out.println("RBAC-MATRIX " + k + " = " + COUNTS.get(k)));
    }

    // ---------------------------------------------------------------- discovery

    private static String expressionOf(HandlerMethod handler) {
        PreAuthorize annotation =
                Optional.ofNullable(
                                AnnotatedElementUtils.findMergedAnnotation(
                                        handler.getMethod(), PreAuthorize.class))
                        .orElseGet(
                                () ->
                                        AnnotatedElementUtils.findMergedAnnotation(
                                                handler.getBeanType(), PreAuthorize.class));
        return annotation == null ? null : annotation.value().trim();
    }

    private static String openKey(HandlerMethod handler) {
        String type = handler.getBeanType().getSimpleName();
        String exact = type + "#" + handler.getMethod().getName();
        if (OPEN.containsKey(exact)) {
            return exact;
        }
        return OPEN.containsKey(type + "#*") ? type + "#*" : null;
    }

    /** A value each path variable accepts. */
    private static String sample(String variable) {
        return switch (variable) {
            case "period" -> "2026-10";
            case "date" -> "2026-10-01";
            case "finYear" -> "2026-27";
            case "yymm" -> "2610";
            case "gate" -> "G2";
            case "type" -> "STAFF";
            case "claimType" -> "LABOUR_TA";
            case "payCode" -> "101";
            case "locale" -> "en";
            case "eis" -> "106620";
            case "code", "key", "list", "ref" -> "X1";
            // A record id no other test creates: the matrix proves the gate, and a record that
            // another integration test left behind (id 1) would add a data-scope refusal instead.
            default -> "900000001";
        };
    }

    private static String url(String pattern) {
        Matcher m = VARIABLE.matcher(pattern);
        StringBuilder url = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(url, sample(m.group(1)));
        }
        m.appendTail(url);
        return url.toString();
    }

    private boolean ours(HandlerMethod handler) {
        return handler.getBeanType().getName().startsWith("in.gov.fci.payroll.");
    }

    /**
     * Every gated endpoint, and in {@code problems} every handler that is neither gated nor open.
     */
    private List<Gated> gated(List<String> problems, Set<String> openUsed) {
        List<Gated> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e :
                mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = e.getValue();
            if (!ours(handler)) {
                continue;
            }
            String where =
                    handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
            String expression = expressionOf(handler);
            Set<String> needs = null;
            final String code = "AUTHZ-403-PERMISSION";
            if (expression != null) {
                Matcher g = GATE.matcher(expression);
                Matcher either = EITHER.matcher(expression);
                Matcher can = CAN.matcher(expression);
                if (g.matches()) {
                    needs = Set.of(g.group(1) + ":" + g.group(2));
                } else if (either.matches()) {
                    needs =
                            Set.of(
                                    either.group(1) + ":" + either.group(2),
                                    either.group(3) + ":" + either.group(4));
                } else if (can.matches()) {
                    // RBAC-SEC-012 / W-11: can() answers false instead of the precise 403 and
                    // skips the OTP step-up of a sensitive screen: never an endpoint gate.
                    problems.add(where + " is gated by can(), which skips the step-up");
                    continue;
                }
            }
            if (needs == null) {
                String open = openKey(handler);
                if (open == null) {
                    problems.add(where + " has no @authz gate: " + expression);
                } else {
                    openUsed.add(open);
                    if (expression != null
                            && !expression.equals("permitAll()")
                            && !expression.equals("isAuthenticated()")
                            && !expression.startsWith("@authz.gate(#request.")) {
                        problems.add(where + " is open with an unexpected rule: " + expression);
                    }
                    if (expression == null) {
                        problems.add(where + " is open without any @PreAuthorize");
                    }
                }
                continue;
            }
            Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
            boolean multipart =
                    e.getKey().getConsumesCondition().getConsumableMediaTypes().stream()
                            .anyMatch(MediaType.MULTIPART_FORM_DATA::isCompatibleWith);
            for (String pattern : e.getKey().getPatternValues()) {
                if (multipart) {
                    MULTIPART.add(url(pattern));
                }
                for (RequestMethod method :
                        methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    out.add(new Gated(method.name(), pattern, url(pattern), needs, code, handler));
                }
            }
        }
        return out;
    }

    private List<Gated> gated() {
        List<String> problems = new ArrayList<>();
        List<Gated> all = gated(problems, new HashSet<>());
        assertThat(problems).as("handlers without a gate").isEmpty();
        return all;
    }

    // ---------------------------------------------------------------- the oracle

    private Set<String> grantsOf(String role) {
        return new TreeSet<>(
                jdbc.queryForList(
                        "SELECT p.screen_code || ':' || p.action_code FROM app_role_permission rp"
                                + " JOIN app_role r ON r.role_id = rp.role_id"
                                + " JOIN app_permission p ON p.permission_id = rp.permission_id"
                                + " WHERE r.role_code = ? AND rp.effective_from <= ?"
                                + " AND (rp.effective_to IS NULL OR rp.effective_to >= ?)",
                        String.class,
                        role,
                        today(),
                        today()));
    }

    /** The India business date the application decides by (AccessContextService.today). */
    private static java.time.LocalDate today() {
        return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
    }

    private Session signIn(Coverage c) throws Exception {
        accessContexts.evictAll();
        return login(
                c.login() + "@fci-demo.test", c.office() == null ? null : c.role(), c.office());
    }

    private Set<String> held(Session session) throws Exception {
        JsonNode me = body(mvc.perform(userGet(session, "/auth/me")).andReturn());
        Set<String> out = new TreeSet<>();
        me.path("permissions").forEach(p -> out.add(p.asString()));
        return out;
    }

    // ---------------------------------------------------------------- calls

    /** The endpoints that take a multipart upload: sent as one (else 415 before any gate). */
    private static final Set<String> MULTIPART = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private MockHttpServletRequestBuilder build(String method, String url, Session session) {
        boolean upload =
                !"GET".equals(method)
                        && !"HEAD".equals(method)
                        && MULTIPART.contains(url.replaceFirst("\\?.*$", ""));
        MockHttpServletRequestBuilder b = request(HttpMethod.valueOf(method), url);
        if (session != null) {
            b = as(session, b);
        }
        b = withCsrf(b);
        if (upload) {
            // An empty multipart body: enough to be routed to the handler (and so to its gate).
            b.contentType("multipart/form-data; boundary=fci")
                    .content("--fci--\r\n")
                    .header("Idempotency-Key", UUID.randomUUID().toString());
        } else if (!"GET".equals(method) && !"HEAD".equals(method)) {
            b.contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return b;
    }

    private MvcResult call(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn();
    }

    /**
     * The problem answer is neutral: only problem fields, the expected code, nothing internal or
     * personal, and the security headers.
     */
    private void neutral(String what, MvcResult result, int status, String code, List<String> bad)
            throws Exception {
        MockHttpServletResponse r = result.getResponse();
        if (r.getStatus() != status) {
            bad.add(what + " -> " + r.getStatus() + " " + abbreviate(r.getContentAsString()));
            return;
        }
        headers(what, r, bad);
        String text = r.getContentAsString();
        if (text.isEmpty()) {
            return; // HEAD
        }
        JsonNode node = json.readTree(text);
        Set<String> keys = new TreeSet<>(node.propertyNames());
        keys.removeAll(PROBLEM_KEYS);
        if (!keys.isEmpty()) {
            bad.add(what + " answered fields " + keys);
        }
        if (code != null && !code.equals(node.path("errorCode").asString())) {
            bad.add(what + " -> " + node.path("errorCode").asString() + " not " + code);
        }
        // The correlation id (a UUID) and the time are the answer's own: not judged as data.
        Matcher leak =
                LEAK.matcher(
                        text.replaceAll("\"(correlationId|timestamp)\"\\s*:\\s*\"[^\"]*\"", ""));
        if (leak.find()) {
            bad.add(what + " leaks '" + leak.group() + "'");
        }
    }

    /** ASVS V14.4: the security headers of every API answer. */
    private static void headers(String what, MockHttpServletResponse r, List<String> bad) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("X-Content-Type-Options", "nosniff");
        expected.put("X-Frame-Options", "DENY");
        expected.put("Referrer-Policy", "no-referrer");
        for (Map.Entry<String, String> h : expected.entrySet()) {
            if (!h.getValue().equals(r.getHeader(h.getKey()))) {
                bad.add(what + " header " + h.getKey() + "=" + r.getHeader(h.getKey()));
            }
        }
        String csp = r.getHeader("Content-Security-Policy");
        if (csp == null || !csp.contains("frame-ancestors 'none'")) {
            bad.add(what + " CSP " + csp);
        }
        // Personal and payroll data no-store; reference data (the office tree) may be kept by
        // the user's own browser if revalidated (private, no-cache): never by a shared cache.
        String cache = r.getHeader("Cache-Control");
        if (cache == null
                || !(cache.contains("no-store")
                        || (cache.contains("private") && cache.contains("no-cache")))) {
            bad.add(what + " Cache-Control " + cache);
        }
    }

    private static String abbreviate(String text) {
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void SRS_22_8_everyEndpointCarriesAnAuthzGateOrIsAListedOpenOne() {
        List<String> problems = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<Gated> all = gated(problems, used);
        assertThat(all).hasSizeGreaterThan(400);
        assertThat(problems).as("handlers without a gate").isEmpty();
        assertThat(used)
                .as("stale entries of OPEN")
                .containsExactlyInAnyOrderElementsOf(OPEN.keySet());
        count("gated endpoints (method x pattern)", all.size());
    }

    @Test
    void SRS_22_1_everyGateNamesAPermissionOfTheCatalogue() {
        Set<String> catalogue =
                new HashSet<>(
                        jdbc.queryForList(
                                "SELECT screen_code || ':' || action_code FROM app_permission",
                                String.class));
        Set<String> unknown = new TreeSet<>();
        for (Gated g : gated()) {
            for (String need : g.needs()) {
                if (!catalogue.contains(need)) {
                    unknown.add(need + " (" + g.key() + ")");
                }
            }
        }
        assertThat(unknown).as("gates naming no app_permission row").isEmpty();
    }

    @Test
    void SRS_22_1_everyRoleHasACoverageUserWhoseSessionHoldsExactlyItsGrants() throws Exception {
        Set<String> covered = new TreeSet<>();
        COVERAGE.forEach(c -> covered.add(c.role()));
        // Every role a user can act in: held through an assigned access group, or the eSeva
        // role every member holds directly. (A role proposed and approved by another test but
        // given to nobody cannot be acted in, so it is not an attack surface.)
        Set<String> actable =
                new TreeSet<>(
                        jdbc.queryForList(
                                "SELECT DISTINCT r.role_code FROM app_role r JOIN access_group g"
                                        + " ON g.role_id = r.role_id JOIN app_user_access_group ug"
                                        + " ON ug.access_group_id = g.access_group_id WHERE"
                                        + " ug.revoked_at IS NULL",
                                String.class));
        actable.add("EMPLOYEE_ESEVA");
        assertThat(actable).hasSizeGreaterThanOrEqualTo(21);
        assertThat(covered)
                .as("roles users act in without a coverage user here")
                .containsAll(actable);
        for (Coverage c : COVERAGE) {
            Session session = signIn(c);
            assertThat(held(session)).as(c.toString()).isEqualTo(grantsOf(c.role()));
        }
    }

    @Test
    void SRS_G_25_everyRoleIsRefusedEveryGateItDoesNotHoldBeforeTheRequestIsRead()
            throws Exception {
        List<Gated> all = gated();
        List<String> bad = new ArrayList<>();
        for (Coverage c : COVERAGE) {
            Session session = signIn(c);
            Set<String> held = held(session);
            Set<String> screens = new HashSet<>();
            held.forEach(p -> screens.add(p.substring(0, p.indexOf(':'))));
            for (Gated g : all) {
                if (g.needs().stream().anyMatch(held::contains)) {
                    continue;
                }
                String what = c.role() + "@" + c.office() + " " + g.key();
                neutral(what, call(build(g.method(), g.url(), session)), 403, g.deniedCode(), bad);
                count("denied role x endpoint", 1);
                boolean screenHeld =
                        g.needs().stream()
                                .anyMatch(n -> screens.contains(n.substring(0, n.indexOf(':'))));
                if (screenHeld) {
                    // An action of a screen the role does hold (e.g. EDIT next to VIEW).
                    count("denied action on a held screen", 1);
                }
                if ("GET".equals(g.method())) {
                    neutral(what + " HEAD", call(build("HEAD", g.url(), session)), 403, null, bad);
                    count("denied HEAD", 1);
                } else {
                    // Verb tampering: the override header and form field change nothing.
                    neutral(
                            what + " X-HTTP-Method-Override",
                            call(
                                    build(g.method(), g.url(), session)
                                            .header("X-HTTP-Method-Override", "GET")
                                            .header("X-HTTP-Method", "GET")
                                            .header("X-Method-Override", "GET")),
                            403,
                            g.deniedCode(),
                            bad);
                    neutral(
                            what + " _method",
                            call(build(g.method(), g.url() + "?_method=GET", session)),
                            403,
                            g.deniedCode(),
                            bad);
                    count("denied verb-tampered write", 2);
                }
            }
        }
        assertThat(bad).as("refusals that were not neutral 403s").isEmpty();
    }

    /** The query parameters of a handler: name -> declared type. */
    private static Map<String, Class<?>> queryParameters(HandlerMethod handler) {
        Map<String, Class<?>> out = new LinkedHashMap<>();
        for (MethodParameter p : handler.getMethodParameters()) {
            RequestParam rp = p.getParameterAnnotation(RequestParam.class);
            if (rp == null) {
                continue;
            }
            String name = rp.name().isEmpty() ? rp.value() : rp.name();
            if (name.isEmpty()) {
                name = p.getParameterName();
            }
            if (name != null) {
                out.put(name, p.getParameterType());
            }
        }
        return out;
    }

    @Test
    void SRS_G_25_everyRoleReachesEveryReadItHoldsAndItsParametersCannotBeForged()
            throws Exception {
        List<Gated> reads = gated().stream().filter(g -> "GET".equals(g.method())).toList();
        List<String> bad = new ArrayList<>();
        for (Coverage c : COVERAGE) {
            Session session = signIn(c);
            Set<String> held = held(session);
            for (Gated g : reads) {
                if (g.needs().stream().noneMatch(held::contains)) {
                    continue;
                }
                String what = c.role() + "@" + c.office() + " " + g.key();
                MvcResult result = call(build("GET", g.url(), session));
                MockHttpServletResponse r = result.getResponse();
                count("granted role x read", 1);
                if (r.getStatus() >= 500) {
                    bad.add(what + " -> " + r.getStatus());
                    continue;
                }
                String text = r.getContentAsString();
                if (r.getStatus() == 403 && text.contains("AUTHZ-403-PERMISSION")) {
                    bad.add(what + " refused although held: " + abbreviate(text));
                }
                if (r.getStatus() < 300
                        && r.getContentType() != null
                        && r.getContentType().contains("json")) {
                    headers(what, r, bad);
                }
                Map<String, Class<?>> params = queryParameters(g.handler());
                // SRS-NF-3: a size outside the offered series or a negative page is refused,
                // never clamped (a forged size cannot load a whole table).
                if (params.containsKey("size") && r.getStatus() == 200) {
                    for (String forged :
                            List.of(
                                    "size=1000",
                                    "size=101",
                                    "size=0",
                                    "size=-1",
                                    "page=-1",
                                    "size=99999999999",
                                    "page=1e9")) {
                        String sep = g.url().contains("?") ? "&" : "?";
                        int s =
                                call(build("GET", g.url() + sep + forged, session))
                                        .getResponse()
                                        .getStatus();
                        if (s != 400) {
                            bad.add(what + " ?" + forged + " -> " + s);
                        }
                        count("forged paging parameter", 1);
                    }
                }
                // ASVS V5.3.4: SQL / LIKE metacharacters in any text parameter are just text.
                for (Map.Entry<String, Class<?>> p : params.entrySet()) {
                    if (p.getValue() != String.class) {
                        continue;
                    }
                    for (String payload :
                            List.of(
                                    "' OR '1'='1",
                                    "1;DROP TABLE app_user--",
                                    "%' UNION SELECT" + " password_hash FROM app_user_credential--",
                                    "\\' AND" + " SLEEP(5)--")) {
                        String sep = g.url().contains("?") ? "&" : "?";
                        MvcResult injected =
                                call(
                                        build(
                                                "GET",
                                                g.url()
                                                        + sep
                                                        + p.getKey()
                                                        + "="
                                                        + java.net.URLEncoder.encode(
                                                                payload,
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8),
                                                session));
                        int s = injected.getResponse().getStatus();
                        String body = injected.getResponse().getContentAsString();
                        if (s >= 500
                                || body.contains("password_hash")
                                || body.contains("$2a$")
                                || Pattern.compile("SQL|ORA-\\d|Syntax error")
                                        .matcher(body)
                                        .find()) {
                            bad.add(what + " ?" + p.getKey() + "=" + payload + " -> " + s);
                        }
                        count("SQL metacharacters in a parameter", 1);
                    }
                }
            }
        }
        assertThat(bad).as("held reads").isEmpty();
    }

    @Test
    void SRS_22_8_withoutASessionEveryGatedEndpointAnswers401() throws Exception {
        List<String> bad = new ArrayList<>();
        for (Gated g : gated()) {
            neutral(
                    "anonymous " + g.key(),
                    call(build(g.method(), g.url(), null)),
                    401,
                    "COM-401-AUTH",
                    bad);
            count("anonymous x endpoint", 1);
            // A forged or stale session cookie is no session.
            MockHttpServletRequestBuilder forged =
                    build(g.method(), g.url(), null)
                            .cookie(new Cookie(SESSION_COOKIE, "Zm9yZ2VkLXNlc3Npb24taWQ"));
            int s = call(forged).getResponse().getStatus();
            if (s != 401) {
                bad.add("forged cookie " + g.key() + " -> " + s);
            }
            count("forged session cookie x endpoint", 1);
        }
        assertThat(bad).isEmpty();
    }

    /** Variants of a path that must never reach a handler the canonical path refuses. */
    private static List<String> tricks(String url) {
        int last = url.lastIndexOf('/');
        String head = url.substring(0, last);
        String tail = url.substring(last + 1);
        return List.of(
                url + "/",
                url + "//",
                url.toUpperCase(java.util.Locale.ROOT),
                head + "/" + tail + ";jsessionid=0123456789ABCDEF",
                head + ";x=1/" + tail,
                head + "/./" + tail,
                head + "/" + tail + "/..",
                head + "/xx/../" + tail,
                head + "%2F" + tail,
                head + "%252F" + tail,
                head + "/%2e/" + tail,
                url + "%00",
                url + "%0d%0aX-Injected:%201",
                url + ".json",
                url + "?" + "x=" + "%u002e");
    }

    @Test
    void SRS_22_8_pathTricksNeverReachAGatedHandlerTheCanonicalPathRefuses() throws Exception {
        List<Gated> reads = gated().stream().filter(g -> "GET".equals(g.method())).toList();
        List<String> bad = new ArrayList<>();
        for (Coverage c :
                COVERAGE.stream()
                        .filter(
                                c ->
                                        Set.of("EMPLOYEE_ESEVA", "PAYROLL_MAKER", "AUDITOR")
                                                .contains(c.role()))
                        .toList()) {
            Session session = signIn(c);
            Set<String> held = held(session);
            for (Gated g : reads) {
                if (g.needs().stream().anyMatch(held::contains)) {
                    continue;
                }
                for (String variant : tricks(g.url())) {
                    MvcResult r;
                    try {
                        r = call(build("GET", variant, session));
                    } catch (IllegalArgumentException unbuildable) {
                        continue; // MockMvc itself refuses the URI: no request is sent
                    }
                    int s = r.getResponse().getStatus();
                    String text = r.getResponse().getContentAsString();
                    boolean refused = s == 400 || s == 401 || s == 403 || s == 404 || s == 405;
                    if (!refused || r.getResponse().getHeader("X-Injected") != null) {
                        bad.add(c.role() + " " + variant + " -> " + s + " " + abbreviate(text));
                    }
                    count("path trick x denied read", 1);
                }
            }
        }
        assertThat(bad).as("path tricks that reached a handler").isEmpty();
    }

    /**
     * The one dynamic gate (WorkflowController#action): the gate is the screen and action the body
     * names, and exactly that action is performed (ActionService), so a forged body cannot perform
     * a button the role does not hold; and a held button aimed at another office (a tampered {@code
     * locationId}, BOLA) is refused for the office.
     */
    @Test
    void SRS_G_25_aGovernedButtonIsGatedByTheScreenAndActionItPerforms() throws Exception {
        Session maker = signIn(COVERAGE.get(0));
        Set<String> held = held(maker);
        List<Map<String, Object>> buttons =
                jdbc.queryForList("SELECT screen_code, action_code FROM screen_action_inventory");
        List<String> bad = new ArrayList<>();
        int scoped = 0;
        long mumbai = locationId("MR001");
        for (Map<String, Object> b : buttons) {
            String screen = String.valueOf(b.get("SCREEN_CODE"));
            String action = String.valueOf(b.get("ACTION_CODE"));
            if (!action.matches("^[A-Z_]{2,20}$")) {
                continue; // refused by the request's own pattern
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("screen", screen);
            body.put("action", action);
            body.put("reason", "forged button");
            if (held.contains(screen + ":" + action)) {
                body.put("locationId", mumbai);
                MvcResult r = mvc.perform(userPost(maker, "/v1/actions", body)).andReturn();
                int s = r.getResponse().getStatus();
                if (s < 400) {
                    bad.add(screen + ":" + action + " at MR001 -> " + s);
                }
                if (r.getResponse().getContentAsString().contains("AUTHZ-403-SCOPE")) {
                    scoped++;
                }
                count("held button aimed at another office", 1);
                continue;
            }
            MvcResult r = mvc.perform(userPost(maker, "/v1/actions", body)).andReturn();
            neutral("button " + screen + ":" + action, r, 403, "AUTHZ-403-PERMISSION", bad);
            count("forged governed button", 1);
        }
        assertThat(bad).as("buttons performed without the grant or outside the scope").isEmpty();
        assertThat(scoped).as("held buttons refused for the office").isPositive();
    }
}
