package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * X-08 Sign-in, OTP &amp; Password as Wireframes v6.1 draws it (OD-121; SRS-G-21, FRS V0.5
 * §6.4.2.5): every role the pack draws opens it (V078_14); the OTP list is exactly the eighteen
 * gated actions, in their order, each with the screens of its active approval routes and the roles
 * that enter the OTP; sign-in and own password change belong to X-08 itself.
 */
class OtpCatalogueIT extends ApiTestSupport {

    @Test
    void SRS_G_21_theListIsTheEighteenGatedActionsWithTheirScreensAndRolesFromTheRoutes()
            throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode list =
                body(
                        mvc.perform(userGet(maker, "/v1/otp/gated-actions"))
                                .andExpect(status().isOk())
                                .andReturn());
        List<String> codes = new ArrayList<>();
        list.forEach(a -> codes.add(a.get("code").asString()));
        assertThat(codes)
                .containsExactlyElementsOf(
                        Arrays.stream(OtpGatedAction.values()).map(Enum::name).toList())
                .hasSize(18);
        // The Screen column as the pack draws it, row by row (Wireframes v6.1 X-08, OD-121).
        String[][] drawn = {
            {"X-08"},
            {"X-08", "E-01"},
            {"C-01", "D-01"},
            {"C-01"},
            {"I-05", "I-21"},
            {"O-02"},
            {"O-11"},
            {"O-11"},
            {"I-04"},
            {"I-22"},
            {"I-25"},
            {"C-01", "I-08"},
            {"O-05"},
            {"I-23"},
            {"I-04"},
            {"C-02"},
            {"X-01"},
            {"X-07"}
        };
        for (int i = 0; i < drawn.length; i++) {
            List<String> screens = new ArrayList<>();
            list.get(i).get("screens").forEach(s -> screens.add(s.asString()));
            assertThat(screens).as(codes.get(i)).containsExactly(drawn[i]);
        }
        // Sign-in and own password change have no route: every user enters them.
        assertThat(list.get(0).get("routedScreens")).isEmpty();
        assertThat(list.get(0).get("roles")).isEmpty();
        // Every routed screen is a route that asks for that code (from the configuration).
        for (JsonNode a : list) {
            String code = a.get("code").asString();
            for (JsonNode s : a.get("routedScreens")) {
                Long routes =
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM approval_workflow_action WHERE"
                                    + " otp_action_code = ? AND screen_code = ? AND action_status ="
                                    + " 'ACTIVE'",
                                Long.class,
                                code,
                                s.asString());
                assertThat(routes).as(code + " " + s.asString()).isPositive();
            }
        }
    }

    @Test
    void SRS_G_29_everyDrawnRoleOpensX08AndItsListAnUnknownSessionDoesNot() throws Exception {
        for (String[] user :
                new String[][] {
                    {"maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001"},
                    {"ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"},
                    {"hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001"},
                    {"finance.mumbai@fci-demo.test", "FINANCE", "MR001"},
                    {"sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001"},
                }) {
            Session s = login(user[0], user[1], user[2]);
            mvc.perform(userGet(s, "/v1/otp/gated-actions")).andExpect(status().isOk());
        }
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        mvc.perform(userGet(member, "/v1/otp/gated-actions")).andExpect(status().isOk());
        // Without a session: refused before the handler (401 or 403, as the security chain
        // answers).
        int anonymous =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/v1/otp/gated-actions"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(anonymous).isIn(401, 403);
    }
}
