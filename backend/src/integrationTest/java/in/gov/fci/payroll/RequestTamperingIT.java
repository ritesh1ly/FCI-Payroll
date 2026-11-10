package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Request tampering (OWASP A01 / A05, ASVS V5 and V13): a path with traversal, encoded separators,
 * a path parameter, a backslash or a control character is refused before any handler runs (Spring
 * Security's strict firewall); a verb cannot be overridden by a header or a form field; a JSON body
 * sent as text without the CSRF token is refused; an unsafe verb nobody serves is refused; a forged
 * Host header never reaches a Location header. None of them answers 5xx.
 */
class RequestTamperingIT extends ApiTestSupport {

    private static final List<String> TAMPERED_PATHS =
            List.of(
                    "/v1/masters/circulars/../../admin/parameters",
                    "/v1/masters/circulars/..%2F..%2Fadmin%2Fparameters",
                    "/v1/masters/circulars/%2e%2e/%2e%2e/admin/parameters",
                    "/v1/masters/circulars/1/document;.pdf",
                    "/v1/masters/circulars/1%5Cdocument",
                    "/v1/masters/circulars/1/document%00.txt",
                    "/v1/masters/circulars/1/document%0d%0aX-Injected:1",
                    "/v1/admin/audit/export?format=CSV&from=..%2F..%2Fetc%2Fpasswd");

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    @Test
    void OWASP_A01_aTamperedPathIsRefusedBeforeAnyHandlerRunsAndNeverAnswers5xx() throws Exception {
        Session maker = maker();
        for (String path : TAMPERED_PATHS) {
            int status = mvc.perform(as(maker, get(path))).andReturn().getResponse().getStatus();
            assertThat(status).as(path).isIn(400, 403, 404);
        }
    }

    /**
     * A doubled slash is collapsed to the canonical path (the strict firewall refuses only the
     * encoded form): it must reach exactly what the canonical path reaches, with the same
     * authorisation, never something else. An eSeva member is refused either way.
     */
    @Test
    void OWASP_A01_aDoubledSlashReachesOnlyWhatTheCanonicalPathReaches() throws Exception {
        Session maker = maker();
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        for (String[] pair :
                List.of(
                        new String[] {
                            "/v1/masters/circulars//1/document", "/v1/masters/circulars/1/document"
                        },
                        new String[] {"/v1/admin//roles", "/v1/admin/roles"},
                        new String[] {
                            "/v1//masters/net-pay-floors", "/v1/masters/net-pay-floors"
                        })) {
            int doubled =
                    mvc.perform(userGet(maker, pair[0])).andReturn().getResponse().getStatus();
            int canonical =
                    mvc.perform(userGet(maker, pair[1])).andReturn().getResponse().getStatus();
            assertThat(doubled).as(pair[0]).isEqualTo(canonical).isLessThan(500);
            int asMember =
                    mvc.perform(userGet(member, pair[0])).andReturn().getResponse().getStatus();
            assertThat(asMember).as("eSeva " + pair[0]).isIn(400, 403, 404);
        }
        // The encoded form is refused outright by the firewall.
        assertThat(
                        mvc.perform(userGet(maker, "/v1/masters/circulars%2F%2F1/document"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isIn(400, 403, 404);
    }

    @Test
    void OWASP_A05_aVerbIsNeverOverriddenByAHeaderOrAFormField() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        // An override header on a read: still a GET, still answered as the list.
        MvcResult list =
                mvc.perform(
                                userGet(admin, "/v1/admin/roles")
                                        .header("X-HTTP-Method-Override", "DELETE"))
                        .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        // A form field override on a write: treated as the POST it is (refused as such), never as
        // a DELETE (no endpoint serves one, so 405 would betray that the override was honoured).
        MvcResult form =
                mvc.perform(
                                withCsrf(
                                        as(admin, post("/v1/admin/roles/1"))
                                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                                .param("_method", "DELETE")))
                        .andReturn();
        assertThat(form.getResponse().getStatus()).isNotEqualTo(405).isLessThan(500);
        // An unsafe verb nobody serves: rejected at the firewall (400, e.g. TRACE), refused (403),
        // no such route (404) or method not allowed (405) — never carried out, never 5xx.
        for (HttpMethod method : List.of(HttpMethod.DELETE, HttpMethod.TRACE)) {
            int status =
                    mvc.perform(withCsrf(as(admin, request(method, "/v1/admin/roles/1"))))
                            .andReturn()
                            .getResponse()
                            .getStatus();
            assertThat(status).as(method.name()).isIn(400, 403, 404, 405);
        }
    }

    @Test
    void OWASP_A01_aJsonBodySentAsTextWithoutTheTokenIsRefusedAndAForgedHostIsNeverEchoed()
            throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        String body = json.writeValueAsString(Map.of("reason", "x"));
        // text/plain is a "simple" request a cross-site form can send: the token is still required.
        int status =
                mvc.perform(
                                as(admin, post("/v1/admin/sessions/1/end"))
                                        .contentType(MediaType.TEXT_PLAIN)
                                        .content(body))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(status).isEqualTo(403);
        // A forged Host header: no absolute URL built from it reaches the client.
        MvcResult forged =
                mvc.perform(
                                userGet(admin, "/v1/admin/roles")
                                        .header("Host", "evil.example")
                                        .header("X-Forwarded-Host", "evil.example"))
                        .andReturn();
        assertThat(forged.getResponse().getStatus()).isEqualTo(200);
        assertThat(forged.getResponse().getContentAsString()).doesNotContain("evil.example");
        assertThat(forged.getResponse().getHeaderNames())
                .noneMatch(h -> forged.getResponse().getHeader(h).contains("evil.example"));
    }
}
