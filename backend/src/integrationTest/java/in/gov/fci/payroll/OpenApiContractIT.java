package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The API contract: generated from the controllers (springdoc) and written to
 * docs/api/openapi.json, so a change of an endpoint shows up in review as a change of that file.
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiContractIT extends ApiTestSupport {

    @Test
    void theContractListsEveryEndpointAndIsExported() throws Exception {
        String body =
                mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode contract = json.readTree(body);
        JsonNode paths = contract.get("paths");
        for (String path :
                new String[] {
                    "/auth/csrf",
                    "/auth/login-options",
                    "/auth/login",
                    "/auth/logout",
                    "/auth/password/forgot",
                    "/auth/password/reset",
                    "/auth/password/change",
                    "/auth/me",
                    "/auth/scopes",
                    "/auth/switch-scope",
                    "/menu",
                    "/v1/me/actions",
                    "/v1/employees",
                    "/v1/lifecycle-events",
                    "/v1/transfers",
                    "/v1/reference/lists",
                    "/v1/employees/{eis}/allowance-flags",
                    "/v1/inbox",
                    "/v1/approvals/{id}/decision",
                    "/v1/actions",
                    "/v1/admin/users",
                    "/v1/admin/roles/grants",
                    "/v1/admin/groups",
                    "/v1/admin/assignments",
                    "/v1/admin/audit"
                }) {
            assertThat(paths.has(path)).as(path).isTrue();
        }
        Path target =
                Path.of(System.getProperty("user.dir"))
                        .resolveSibling("docs")
                        .resolve("api")
                        .resolve("openapi.json");
        Files.createDirectories(target.getParent());
        Files.writeString(
                target,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(contract) + "\n",
                StandardCharsets.UTF_8);
    }
}
