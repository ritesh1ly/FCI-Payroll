package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Without the demo seed (a real first deployment): V009 creates the one System Administrator, whose
 * temporary password must be changed before anything else works.
 */
@ActiveProfiles(
        value = {"it", "online"},
        inheritProfiles = false)
@TestPropertySource(
        properties = {
            "fci.api.bootstrap.sysadmin-login-id=First.Admin@FCI.gov.in",
            "fci.api.bootstrap.admin-initial-password=Bootstrap#Sysadmin2026"
        })
class BootstrapIT extends ApiTestSupport {

    @Test
    void theFirstDeploymentHasExactlyOneSystemAdministratorWhoMustChangeThePassword()
            throws Exception {
        Session admin = loginAs("first.admin@fci.gov.in", "Bootstrap#Sysadmin2026");
        assertThat(admin.passwordChangeRequired()).isTrue();

        mvc.perform(userGet(admin, "/v1/admin/users"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH-403-PASSWORD-CHANGE"));
        mvc.perform(userGet(admin, "/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("first.admin@fci.gov.in"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));

        // No demo data on a real deployment.
        assertThat(
                        loginRaw("sysadmin@fci-demo.test", DEMO_PASSWORD, null, null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class))
                .isEqualTo(1);
    }

    private Session loginAs(String loginId, String password) throws Exception {
        var result = loginRaw(loginId, password, null, null);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return sessionOf(result, loginId, body(result));
    }
}
