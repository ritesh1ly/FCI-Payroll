package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The application starts on H2 (Oracle mode): target DDL migrated, schema validated, seed loaded.
 */
class ApplicationSmokeIT extends ApiTestSupport {

    @Autowired AccessRepositories.Roles roles;
    @Autowired AccessRepositories.Permissions permissions;
    @Autowired Flyway flyway;

    /**
     * OD-141 / OD-142: the demo-evaluation seeds (locked demo months of DH001 / CH001, the test
     * office DT001, demo statutory rates) stay out of the suites: the placeholder binds under its
     * own name (application-it.yaml) and no row of theirs is loaded.
     */
    @Test
    void OD_141_demoEvaluationSeedsStayOutOfTheSuites() {
        assertThat(flyway.getConfiguration().getPlaceholders())
                .containsEntry("demo_evaluation", "N");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM org_location WHERE location_code = 'DT001'",
                                Integer.class))
                .isZero();
    }

    @Test
    void schemaValidatesAndSeedDataIsLoaded() {
        assertThat(roles.findAllByOrderByCode())
                .extracting("code")
                .contains(
                        "PAYROLL_MAKER",
                        "PAYROLL_CHECKER",
                        "ESTABLISHMENT_HR",
                        "SYSTEM_ADMINISTRATOR",
                        "EMPLOYEE_ESEVA");
        assertThat(permissions.findAllByOrderByScreenCodeAscActionCodeAsc()).isNotEmpty();
    }

    @Test
    void loginOptionsArePublicAndOnlySayWhetherThisIsADemo() throws Exception {
        JsonNode options =
                body(
                        mvc.perform(get("/auth/login-options"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(options.get("demo").asBoolean()).as("profile demo").isTrue();
        assertThat(options.get("otpAtLogin").asBoolean())
                .as("the IT context signs in without the OTP step (LoginOtpIT has it on)")
                .isFalse();
        assertThat(options.get("mobileOtp").asBoolean())
                .as("no sign-in OTP in this context: no mobile sign-in either")
                .isFalse();
        assertThat(options.size())
                .as("no personas or offices before sign-in: the demo flag and the OTP options only")
                .isEqualTo(3);
    }

    @Test
    void healthIsPublicAndUnknownPathsAreDenied() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/somewhere")).andExpect(status().isForbidden());
    }

    @Test
    void metricsAcceptTheGeneratedPbkdf2CredentialAndRejectOthersWith401() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(httpBasic("metrics", "Metrics#It-Only-2026")))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus").with(httpBasic("metrics", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void apiRequiresASessionAndReturnsProblemDetails() throws Exception {
        mvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("COM-401-AUTH"))
                .andExpect(jsonPath("$.messageKey").exists())
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
