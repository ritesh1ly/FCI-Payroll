package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * Edge cases of the RBAC / tampering catalogue (docs/quality/rbac-security-scenarios-2026-11-04.md)
 * that the role x gate matrix does not reach on its own: restricted access modes, one acting role
 * at a time, public-prefix and password-change prefix tricks, OPTIONS, type confusion in ids,
 * parameters an eSeva member adds, export formats and the management endpoints. Each test names its
 * RBAC-SEC ids and the OWASP ASVS item.
 */
class SecurityEdgeCasesIT extends ApiTestSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private Set<String> held(Session s) throws Exception {
        Set<String> out = new TreeSet<>();
        body(mvc.perform(userGet(s, "/auth/me")).andReturn())
                .path("permissions")
                .forEach(p -> out.add(p.asString()));
        return out;
    }

    /**
     * RBAC-SEC-006 (SRS-22-29, ASVS V4.1.3): a REPORTS_ONLY group confers only the report screens'
     * VIEW and EXPORT of its role, whatever else the role holds; a transaction screen is refused.
     */
    @Test
    void SRS_22_2_aReportsOnlyGroupHoldsReportViewAndExportOnly() throws Exception {
        Session reports =
                login("rbac.reports.punjab@fci-demo.test", "REPORT_ADMINISTRATOR", "RO-PB");
        Set<String> held = held(reports);
        assertThat(held).isNotEmpty();
        assertThat(held).allSatisfy(p -> assertThat(p).matches("^R-\\d{2}:(VIEW|EXPORT)$"));
        assertThat(status(mvc.perform(userGet(reports, "/v1/inbox")).andReturn())).isEqualTo(403);
    }

    /**
     * RBAC-SEC-092 (SRS-22-2, OQ-L11; ASVS V4.1.3): a user with two groups at one office acts in
     * one role at a time: acting as Finance, the session holds the Finance grants only, not the
     * union with Establishment / HR.
     */
    @Test
    void SRS_22_2_oneActingRoleAtATimeNeverTheUnionOfTheUsersGroups() throws Exception {
        Session finance = login("rbac.hr.finance@fci-demo.test", "FINANCE", "LD001");
        Set<String> asFinance = held(finance);
        Session hr = login("rbac.hr.finance@fci-demo.test", "ESTABLISHMENT_HR", "LD001");
        Set<String> asHr = held(hr);
        Set<String> hrOnly = new TreeSet<>(asHr);
        hrOnly.removeAll(asFinance);
        assertThat(hrOnly).as("HR grants Finance lacks").isNotEmpty();
        finance = login("rbac.hr.finance@fci-demo.test", "FINANCE", "LD001");
        assertThat(held(finance)).doesNotContainAnyElementsOf(hrOnly);
    }

    /**
     * RBAC-SEC-017 / 018 (WSTG-ATHZ-01, ASVS V4.1.1): a public or allowed prefix cannot carry a
     * request to a protected path - anonymous through /auth/login and /i18n, and a session that
     * must change its password through /auth/me.
     */
    @Test
    void SRS_G_48_aPublicOrAllowedPrefixNeverReachesAProtectedPath() throws Exception {
        for (String path :
                List.of(
                        "/auth/login;x=/../v1/inbox",
                        "/i18n/../v1/inbox",
                        "/i18n%2F..%2Fv1%2Finbox",
                        "/i18n/%2e%2e/v1/inbox",
                        "/auth/csrf/../../v1/inbox")) {
            int s = status(mvc.perform(get(path)).andReturn());
            assertThat(s).as(path).isIn(400, 401, 403, 404);
        }
        Session mustChange = login("rbac.must.change@fci-demo.test", null, null);
        for (String path :
                List.of(
                        "/auth/me/../../v1/employees",
                        "/i18n/../v1/inbox",
                        "/menu;/v1/inbox",
                        "/auth/me;/../v1/employees",
                        "/v1/employees")) {
            int s = status(mvc.perform(userGet(mustChange, path)).andReturn());
            assertThat(s).as(path).isIn(400, 403, 404);
        }
    }

    /**
     * RBAC-SEC-019 / 020 (ASVS V14.5.1, V5.1.3): OPTIONS on a gated path answers no CORS grant and
     * no data; an approval id that is over-long, negative, exponent-written or hexadecimal is a
     * 4xx, never a 5xx and never a decision.
     */
    @Test
    void SRS_G_25_optionsAndTypeConfusedIdsAreRefusedCleanly() throws Exception {
        Session maker = login("pm.dh001@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult opt =
                mvc.perform(
                                as(maker, options("/v1/admin/users"))
                                        .header("Origin", "https://evil.example")
                                        .header("Access-Control-Request-Method", "GET"))
                        .andReturn();
        assertThat(opt.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(opt.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
        assertThat(opt.getResponse().getContentAsString()).doesNotContain("loginId");
        Session checker = login("pc.dh001@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        for (String id : List.of("99999999999999999999", "-1", "0", "1e3", "0x10", "1.5", "%20")) {
            MvcResult r =
                    mvc.perform(
                                    userPost(
                                            checker,
                                            "/v1/approvals/" + id + "/decision",
                                            Map.of("decision", "APPROVE", "reason", "type")))
                            .andReturn();
            assertThat(status(r)).as(id).isBetween(400, 499);
        }
    }

    /**
     * RBAC-SEC-025 / 027 (ASVS V4.2.1, API1:2023): an eSeva member's own-record endpoints ignore an
     * added employee id; a staff session that also holds an eSeva grant gets the staff role's
     * answer in the staff scope (the eSeva permissions belong to the eSeva scope only).
     */
    @Test
    void SRS_G_35_ownRecordEndpointsIgnoreAnAddedIdAndBelongToTheEsevaScope() throws Exception {
        Session member = login("eseva.dh001@fci-demo.test", null, null);
        Long other =
                jdbc.queryForObject(
                        "SELECT MIN(employee_id) FROM employee WHERE employee_id <> (SELECT"
                                + " employee_id FROM app_user WHERE login_id ="
                                + " 'eseva.dh001@fci-demo.test')",
                        Long.class);
        for (String path :
                List.of(
                        "/v1/cpf/statements/mine",
                        "/v1/payslips/mine",
                        "/v1/tax/form16-certificates/mine",
                        "/v1/leave-balances/mine")) {
            MvcResult own = mvc.perform(userGet(member, path)).andReturn();
            MvcResult forged =
                    mvc.perform(
                                    userGet(
                                            member,
                                            path
                                                    + "?employeeId="
                                                    + other
                                                    + "&employeeNumber=1&eis=106620"))
                            .andReturn();
            assertThat(status(forged)).as(path).isEqualTo(status(own));
            if (status(own) == 200) {
                assertThat(stripVolatile(forged.getResponse().getContentAsString()))
                        .as(path)
                        .isEqualTo(stripVolatile(own.getResponse().getContentAsString()));
            }
        }
        Session staff = login("rbac.staff.eseva@fci-demo.test", "PAYROLL_MAKER", "LD001");
        Set<String> held = held(staff);
        // OD-135: staff hold E-01 VIEW for the support preview, never the member's own right.
        assertThat(held).contains("E-01:VIEW").doesNotContain("E-01:SELF", "E-01:CREATE");
        for (String mine :
                List.of(
                        "/v1/eseva/me",
                        "/v1/eseva/overview/mine",
                        "/v1/payslips/mine",
                        "/v1/cpf/statements/mine",
                        "/v1/cpf/vpf-changes/mine",
                        "/v1/cpf/sanctions/mine",
                        "/v1/tax/declarations/mine",
                        "/v1/tax/form16-certificates/mine",
                        "/v1/leave-balances/mine",
                        "/v1/eseva/notifications/mine",
                        "/v1/eseva/privacy-notice/mine",
                        "/v1/hrms-update-requests/mine")) {
            assertThat(status(mvc.perform(userGet(staff, mine)).andReturn()))
                    .as(mine)
                    .isIn(403, 404);
        }
    }

    /** SRS-17-6, OD-135: E-01 VIEW lets an eSeva member open the screen, never a preview. */
    @Test
    void SRS_17_6_anEsevaMemberCannotOpenASupportPreviewOrReadAnotherSession() throws Exception {
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        int status =
                mvc.perform(
                                userPost(
                                        member,
                                        "/v1/eseva/support-previews",
                                        Map.of("eis", "105220", "reason", "support")))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(status).isEqualTo(403);
        Session again = login("emp.rsharma@fci-demo.test", null, null);
        assertThat(
                        status(
                                mvc.perform(userGet(again, "/v1/eseva/support-previews/1/overview"))
                                        .andReturn()))
                .isEqualTo(404);
        assertThat(
                        status(
                                mvc.perform(userGet(again, "/v1/payslips/previews/1/202601"))
                                        .andReturn()))
                .isEqualTo(404);
    }

    /** SRS-17-9 (RBAC-SEC-028): a foreign record reads as not found and is a security event. */
    @Test
    void SRS_17_9_aForeignUpdateRequestIsNotFoundAndWritesASecurityEvent() throws Exception {
        Long theirs =
                jdbc.queryForObject(
                        "SELECT MIN(r.hrms_update_request_id) FROM hrms_update_request r WHERE"
                                + " r.employee_id <> (SELECT u.employee_id FROM app_user u WHERE"
                                + " u.login_id = 'emp.rsharma@fci-demo.test')",
                        Long.class);
        assertThat(theirs).isNotNull();
        long before = securityEvents(theirs);
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        String path = "/v1/hrms-update-requests/mine/" + theirs + "/documents";
        assertThat(status(mvc.perform(userGet(member, path)).andReturn())).isEqualTo(404);
        assertThat(securityEvents(theirs)).isEqualTo(before + 1);
    }

    /** SRS-17-9 (RBAC-SEC-125): the member's rows answer whatever key the request adds. */
    @Test
    void SRS_17_9_anExtraEntityKeyIsIgnored() throws Exception {
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        String plain = "/v1/eseva/overview/mine";
        String own =
                mvc.perform(userGet(member, plain)).andReturn().getResponse().getContentAsString();
        String forged =
                mvc.perform(userGet(member, plain + "?entityKey=1&employeeId=2"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(stripVolatile(forged)).isEqualTo(stripVolatile(own));
    }

    private long securityEvents(long key) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM security_event WHERE target_entity = 'hrms_update_request'"
                        + " AND target_key = ?",
                Long.class,
                String.valueOf(key));
    }

    private static String stripVolatile(String text) {
        return text.replaceAll("\"(correlationId|timestamp|generatedAt)\"\\s*:\\s*\"[^\"]*\"", "");
    }

    /**
     * RBAC-SEC-115 (ASVS V5.1.3): an export format outside its list (a format not offered, a path,
     * lower case) is refused at once, before any byte is streamed - for every export the caller
     * holds. The list is CSV, XLSX and PDF (SRS-G-13: "PDF and Excel export on every list";
     * OD-144).
     */
    @Test
    void SRS_G_13_anExportFormatOutsideItsListIsRefused() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        Session maker = login("pm.dh001@fci-demo.test", "PAYROLL_MAKER", "DH001");
        List<String> accepted = new ArrayList<>();
        int judged = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e :
                mappings.getHandlerMethods().entrySet()) {
            Set<RequestMethod> m = e.getKey().getMethodsCondition().getMethods();
            boolean takesFormat =
                    java.util.Arrays.stream(e.getValue().getMethodParameters())
                            .anyMatch(
                                    p -> {
                                        var rp =
                                                p.getParameterAnnotation(
                                                        org.springframework.web.bind.annotation
                                                                .RequestParam.class);
                                        return rp != null
                                                && ("format".equals(rp.value())
                                                        || "format".equals(rp.name())
                                                        || "format".equals(p.getParameterName()));
                                    });
            if (!(m.isEmpty() || m.contains(RequestMethod.GET)) || !takesFormat) {
                continue;
            }
            for (String pattern : e.getKey().getPatternValues()) {
                if (pattern.contains("{")) {
                    continue;
                }
                for (Session s : List.of(admin, maker)) {
                    for (String format :
                            List.of("DOCX", "pdf", "..%2F..%2Fx", "exe", "CSV%0d%0aX:1")) {
                        MvcResult r =
                                mvc.perform(userGet(s, pattern + "?format=" + format)).andReturn();
                        if (status(r) == 403) {
                            continue; // not held: the matrix covers it
                        }
                        judged++;
                        if (status(r) < 400
                                || status(r) >= 500
                                || r.getRequest().isAsyncStarted()) {
                            accepted.add(pattern + "?format=" + format + " -> " + status(r));
                        }
                    }
                }
            }
        }
        System.out.println("RBAC-MATRIX export format checks = " + judged);
        assertThat(judged).isPositive();
        assertThat(accepted).isEmpty();
    }

    /**
     * RBAC-SEC-163 / 164 (ASVS V14.3.3, A05): the management endpoints are not served on the API
     * port at all: no environment, heap dump, metrics or chaos endpoint answers, and health shows
     * no details.
     */
    @Test
    void SRS_SEC_11_managementEndpointsAreNotServedOnTheApiPort() throws Exception {
        for (String path :
                List.of(
                        "/actuator/env",
                        "/actuator/heapdump",
                        "/actuator/metrics",
                        "/actuator/prometheus",
                        "/actuator/chaosmonkey",
                        "/actuator/configprops",
                        "/actuator/loggers")) {
            MvcResult r = mvc.perform(get(path)).andReturn();
            assertThat(status(r)).as(path).isIn(401, 403, 404);
            assertThat(r.getResponse().getContentAsString()).doesNotContain("propertySources");
        }
        MvcResult health = mvc.perform(get("/actuator/health")).andReturn();
        if (status(health) == 200) {
            JsonNode node = json.readTree(health.getResponse().getContentAsString());
            assertThat(node.has("components")).isFalse();
            assertThat(node.has("details")).isFalse();
        }
    }
}
