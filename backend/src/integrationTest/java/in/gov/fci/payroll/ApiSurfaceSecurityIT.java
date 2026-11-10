package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The whole API surface against the three unauthorised callers of OWASP A01 / A05 (broken access
 * control, missing CSRF protection), discovered from the running application rather than listed by
 * hand, so a new endpoint is covered the day it is added:
 *
 * <ul>
 *   <li>no session: every endpoint but the public sign-in ones answers 401 / 403;
 *   <li>an eSeva member (the least privileged session): no staff endpoint answers 2xx; the few an
 *       eSeva member may use are listed, so a new one fails here until it is reviewed;
 *   <li>a System Administrator without the CSRF token: every state-changing call answers 403.
 * </ul>
 *
 * No endpoint may answer 5xx to any of them.
 */
class ApiSurfaceSecurityIT extends ApiTestSupport {

    /** Public by design (SecurityConfig.PUBLIC_PATHS, the UI labels). */
    private static final Set<String> PUBLIC =
            Set.of(
                    "GET /auth/csrf",
                    "GET /auth/login-options",
                    "POST /auth/login",
                    "POST /auth/logout",
                    "POST /auth/password/forgot",
                    "POST /auth/password/reset",
                    "POST /auth/locale",
                    "GET /auth/locale",
                    "GET /i18n/{locale}",
                    "GET /i18n");

    /** What an eSeva member may reach: the own record, own queries, own session and the UI data. */
    private static final List<Pattern> ESEVA_ALLOWED =
            List.of(
                    // own session, menu, scopes and actions
                    Pattern.compile("^(GET|POST) /auth/.*"),
                    Pattern.compile("^GET /menu$"),
                    Pattern.compile("^GET /v1/me/.*"),
                    // UI labels, dropdown values, the order document types (no personal data)
                    Pattern.compile("^GET /i18n.*"),
                    Pattern.compile("^GET /v1/reference/.*"),
                    Pattern.compile("^GET /v1/document-types$"),
                    Pattern.compile("^GET /v1/periods$"),
                    // X-08 (every user, the member included, as the wireframe draws it): the
                    // OTP-gated actions
                    // with their screens and roles - configuration, no personal data
                    Pattern.compile("^GET /v1/otp/gated-actions$"),
                    // E-01 the own record; X-06 the own queries (PayrollQueryService filters by the
                    // member's employee id)
                    Pattern.compile("^GET /v1/eseva/me$"),
                    // E-01 the own overview (MemberOverviewService takes the member from the
                    // session; SRS-17-1, SRS-17-17)
                    Pattern.compile("^GET /v1/eseva/overview/mine$"),
                    // E-01 VIEW opens the menu for a member; the preview reads refuse: only the
                    // staff user's own open session answers (SRS-17-6, OD-135)
                    Pattern.compile("^GET /v1/eseva/support-previews/\\{id\\}/overview$"),
                    Pattern.compile("^GET /v1/payslips/previews/\\{session\\}/\\{yymm\\}(/pdf)?$"),
                    Pattern.compile("^POST /v1/eseva/support-previews(/\\{id\\}/closures)?$"),
                    // E-01 the own privacy notice (PrivacyNoticeService resolves the member from
                    // the session; SRS-SEC-9)
                    Pattern.compile(
                            "^(GET|POST) /v1/eseva/privacy-notice/mine(/acknowledgements)?$"),
                    // E-01 the own VPF subscription change (VpfChangeService resolves the member
                    // from the session; BR-ESEVA-006)
                    Pattern.compile("^(GET|POST) /v1/cpf/vpf-changes/mine$"),
                    // E-01 the own notices (MemberNotifications reads and marks the member's own)
                    Pattern.compile("^GET /v1/eseva/notifications/mine$"),
                    // E-01 the own e-mailed payslips and Form 16 (DocumentMails reads the
                    // member's own only; OD-148, SRS-17-12)
                    Pattern.compile("^GET /v1/eseva/document-mails/mine$"),
                    // E-01 the own leave balance (SRS-LV-32) and CPF statement (SRS-17-17)
                    Pattern.compile("^GET /v1/leave-balances/mine$"),
                    Pattern.compile("^GET /v1/cpf/statements/mine$"),
                    Pattern.compile("^POST /v1/eseva/notifications/mine/\\{id}/reads$"),
                    Pattern.compile("^(GET|POST) /v1/queries.*"),
                    // E-01 the own CPF requests (CpfSanctionService resolves the member from the
                    // session; another member's id is 404)
                    Pattern.compile("^GET /v1/cpf/sanctions/(mine|purposes)$"),
                    // the own request's documents (the store ties the file to the request; any
                    // other member's request needs I-04 VIEW in scope)
                    Pattern.compile("^GET /v1/cpf/sanctions/\\{id}/documents/\\{fileId}$"),
                    Pattern.compile("^POST /v1/cpf/sanctions(/\\{id}/(withdraw|resubmit))?$"),
                    // E-01 the own income-tax declarations (DeclarationService resolves the member
                    // from the session) and the proofs of the own rounds (another member's needs
                    // I-07 VIEW in scope)
                    Pattern.compile("^(GET|POST) /v1/tax/declarations/mine$"),
                    Pattern.compile("^GET /v1/tax/declarations/\\{id}/proofs/\\{proofId}$"),
                    // E-01 the own released Form 16 certificates (Form16Service resolves the member
                    // from the session; SRS-7-9)
                    Pattern.compile("^GET /v1/tax/form16-certificates/mine$"),
                    // ... and the Part A of the own released certificate (SRS-7-9, SRS-17-1,
                    // BR-ESEVA-004: Form16Service.partA serves the session's member once
                    // released; any other member's needs C-10 VIEW in scope, proved by
                    // OWASP_A01_anEsevaMemberReadsOnlyTheOwnReleasedForm16)
                    Pattern.compile("^GET /v1/tax/form16-certificates/\\{id}/part-a$"),
                    // ... and the certificate PDF (OD-144), under the same rule as Part A
                    // (Form16Service.certificatePdf: the own released one, else C-10 VIEW)
                    Pattern.compile("^GET /v1/tax/form16-certificates/\\{id}/part-b$"),
                    // E-01 the own HRMS Update Requests (HrmsUpdateRequestService resolves the
                    // member from the session; SRS-1-70) and the list of fields with pay effect
                    // they name (configuration, no personal data)
                    Pattern.compile("^(GET|POST) /v1/hrms-update-requests/mine$"),
                    Pattern.compile("^GET /v1/hrms-update-requests/fields$"),
                    // ... and the order copies of an own request (HrmsUpdateRequestDocuments: any
                    // other member's request is 404; SRS-1-70)
                    Pattern.compile("^(GET|POST) /v1/hrms-update-requests/mine/\\{id}/documents$"),
                    Pattern.compile(
                            "^GET /v1/hrms-update-requests/mine/\\{id}/documents/\\{fileId}$"),
                    // E-01 the own payslips of locked months (PayslipService resolves the member
                    // from the session; SRS-17-1)
                    Pattern.compile(
                            "^GET /v1/payslips/mine(/\\{yymm}(/pdf)?|/zip|/bulk-settings)?$"));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private record Endpoint(String method, String pattern, String url) {
        String key() {
            return method + " " + pattern;
        }
    }

    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z]+)(?::[^}]*)?}");

    /** A value each path variable accepts, so a call reaches the gate rather than a 404. */
    private static String sample(String variable) {
        return switch (variable) {
            case "period" -> "2026-10";
            case "gate" -> "G2";
            case "type" -> "STAFF";
            case "claimType" -> "LABOUR_TA";
            case "payCode" -> "101";
            case "locale" -> "en";
            case "entity" -> "employee";
            case "code", "key", "list", "listCode", "valueCode", "action", "actionCode", "screen" ->
                    "X1";
            default -> "1";
        };
    }

    private List<Endpoint> endpoints() {
        List<Endpoint> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e :
                mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<String> patterns = info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (pattern.startsWith("/error") || pattern.startsWith("/v3/api-docs")) {
                    continue;
                }
                Matcher m = VARIABLE.matcher(pattern);
                StringBuilder url = new StringBuilder();
                while (m.find()) {
                    m.appendReplacement(url, sample(m.group(1)));
                }
                m.appendTail(url);
                for (RequestMethod method :
                        methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    out.add(new Endpoint(method.name(), pattern, url.toString()));
                }
            }
        }
        return out;
    }

    private MvcResult call(Endpoint e, Cookie session, boolean csrf) throws Exception {
        var builder = request(HttpMethod.valueOf(e.method()), e.url());
        if (session != null) {
            builder.cookie(session);
        }
        if (csrf) {
            builder = withCsrf(builder);
        }
        if (!"GET".equals(e.method())) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .header("Idempotency-Key", java.util.UUID.randomUUID().toString());
        }
        return mvc.perform(builder).andReturn();
    }

    @Test
    void OWASP_A01_withoutASessionOnlyTheSignInEndpointsAnswer() throws Exception {
        List<String> open = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<Endpoint> all = endpoints();
        assertThat(all).hasSizeGreaterThan(100);
        for (Endpoint e : all) {
            int status = call(e, null, true).getResponse().getStatus();
            if (status >= 500) {
                errors.add(e.key() + " -> " + status);
            } else if (status < 400 && !PUBLIC.contains(e.key())) {
                open.add(e.key() + " -> " + status);
            }
        }
        assertThat(errors).as("5xx without a session").isEmpty();
        assertThat(open).as("answered without a session").isEmpty();
    }

    @Test
    void OWASP_A01_anEsevaMemberReachesNoStaffEndpoint() throws Exception {
        Cookie session =
                new Cookie(SESSION_COOKIE, login("emp.rsharma@fci-demo.test", null, null).cookie());
        Set<String> reached = new TreeSet<>();
        List<String> errors = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (e.pattern().equals("/auth/logout") || e.pattern().startsWith("/auth/password")) {
                continue; // would end or change the session under test
            }
            int status = call(e, session, true).getResponse().getStatus();
            if (status >= 500) {
                errors.add(e.key() + " -> " + status);
            } else if (status < 400) {
                String key = e.key();
                if (ESEVA_ALLOWED.stream().noneMatch(p -> p.matcher(key).matches())) {
                    reached.add(key + " -> " + status);
                }
            }
        }
        assertThat(errors).as("5xx for an eSeva member").isEmpty();
        assertThat(reached).as("staff endpoints an eSeva member reached").isEmpty();
    }

    /**
     * The allow-list admits the Part A path for any id, so the object check stands here: the member
     * reads the own released certificate and is refused another member's (OWASP A01, IDOR; SRS-7-9,
     * SRS-17-1; the member is strictly self-scoped).
     */
    @Test
    void OWASP_A01_anEsevaMemberReadsOnlyTheOwnReleasedForm16() throws Exception {
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        String released =
                "SELECT MIN(f.form16_id) FROM form16 f JOIN employee e ON e.employee_id ="
                        + " f.employee_id WHERE e.emp_num = ? AND f.release_status = 'RELEASED'";
        Long own = jdbc.queryForObject(released, Long.class, "104882");
        Long others = jdbc.queryForObject(released, Long.class, "880622");
        assertThat(own).isNotNull();
        assertThat(others).isNotNull();
        assertThat(partAStatus(member, own)).isEqualTo(200);
        assertThat(partAStatus(member, others)).isEqualTo(403);
    }

    private int partAStatus(Session session, long id) throws Exception {
        return mvc.perform(userGet(session, "/v1/tax/form16-certificates/" + id + "/part-a"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /**
     * OWASP A05 (configuration): NGINX caps request bodies at 1 MB (nginx.conf) and allows up to 11
     * MB only on the upload location of conf.d/fci-payroll.conf. Every endpoint that takes a file
     * must match that location, or files over 1 MB are refused with 413 before the backend sees
     * them (found 2026-10-22: no upload location existed). A new upload endpoint fails here until
     * it is added.
     */
    // R-DEL (CLAUDE.md), SC-01-0067: no endpoint deletes a business record; a record ends by its
    // status or a new version.
    @Test
    void R_DEL_noEndpointAnswersTheDeleteMethod() {
        List<String> deleting = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e :
                mappings.getHandlerMethods().entrySet()) {
            // A handler that only refuses the write (the audit trail: AUD-405-IMMUTABLE) deletes
            // nothing.
            if (e.getKey().getMethodsCondition().getMethods().contains(RequestMethod.DELETE)
                    && !e.getValue().getMethod().getName().startsWith("refuse")) {
                deleting.add(e.getKey().toString());
            }
        }
        assertThat(deleting).as("DELETE endpoints").isEmpty();
    }

    @Test
    void OWASP_A05_everyUploadEndpointIsGivenItsFileSizeByNginx() throws Exception {
        String conf =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of(
                                "..", "config", "nginx", "conf.d", "fci-payroll.conf"));
        Matcher location =
                Pattern.compile("location ~ (\\^/api/v1/\\S+\\$) \\{\\s*client_max_body_size 11m;")
                        .matcher(conf);
        assertThat(location.find()).as("the 11 MB upload location in fci-payroll.conf").isTrue();
        Pattern uploads = Pattern.compile(location.group(1));
        List<String> uploadPaths = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e :
                mappings.getHandlerMethods().entrySet()) {
            boolean takesFile =
                    java.util.Arrays.stream(e.getValue().getMethodParameters())
                            .anyMatch(
                                    p ->
                                            org.springframework.web.multipart.MultipartFile.class
                                                    .isAssignableFrom(p.getParameterType()));
            if (!takesFile) {
                continue;
            }
            for (String pattern : e.getKey().getPatternValues()) {
                Matcher m = VARIABLE.matcher(pattern);
                StringBuilder url = new StringBuilder("/api");
                while (m.find()) {
                    m.appendReplacement(url, sample(m.group(1)));
                }
                m.appendTail(url);
                uploadPaths.add(url.toString());
            }
        }
        assertThat(uploadPaths).as("upload endpoints found").hasSizeGreaterThanOrEqualTo(6);
        assertThat(uploadPaths)
                .as("upload endpoints NGINX limits to 1 MB")
                .allSatisfy(p -> assertThat(uploads.matcher(p).matches()).as(p).isTrue());
    }

    @Test
    void OWASP_A01_noStateChangeIsAcceptedWithoutTheCsrfToken() throws Exception {
        Cookie session =
                new Cookie(
                        SESSION_COOKIE,
                        login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001").cookie());
        List<String> accepted = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if ("GET".equals(e.method()) || PUBLIC.contains(e.key())) {
                continue;
            }
            int status = call(e, session, false).getResponse().getStatus();
            if (status != 403) {
                accepted.add(e.key() + " -> " + status);
            }
        }
        assertThat(accepted).as("state changes without the CSRF token").isEmpty();
    }
}
