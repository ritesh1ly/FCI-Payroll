package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * SRS-G-21 with OTP verification switched off ({@code fci.api.otp.required = N}, project owner,
 * 2026-10-23: development and local machines only; UAT and production refuse it at start-up): a
 * critical activity goes through without asking for a code, and the record that keeps its OTP still
 * names a challenge, closed as confirmed. Every other IT runs with the default Y and proves the OTP
 * itself. Own context: the setting changes the whole application.
 */
@TestPropertySource(properties = {"fci.api.otp.required=N", "fci.it.isolated=otp-off"})
class OtpVerificationOffIT extends ApiTestSupport {

    /** A month no other test of this isolated context uses, inside the demo calendar. */
    private static final YearMonth PERIOD =
            YearMonth.now(ZoneId.of("Asia/Kolkata")).minusMonths(11);

    @Test
    void SRS_G_21_theScreensAreToldTheOtpIsOffAndASensitiveSessionIsConfirmedWithoutACode()
            throws Exception {
        Session admin =
                loginWithoutStepUp("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        JsonNode me = body(mvc.perform(userGet(admin, "/auth/me")).andReturn());
        assertThat(me.path("otpRequired").asBoolean()).isFalse();
        assertThat(me.path("stepUpRequired").asBoolean()).isTrue();

        MvcResult stepUp = mvc.perform(userPost(admin, "/auth/step-up", Map.of())).andReturn();
        assertThat(stepUp.getResponse().getStatus()).isBetween(200, 204);
        me = body(mvc.perform(userGet(admin, "/auth/me")).andReturn());
        assertThat(me.path("stepUpRequired").asBoolean()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM otp_challenge WHERE challenged_user ="
                                    + " 'sysadmin@fci-demo.test' AND gated_action = 'AUTH_LOGIN'"
                                    + " ORDER BY otp_challenge_id DESC FETCH FIRST 1 ROWS ONLY",
                                String.class))
                .isEqualTo("SUCCESS");
    }

    @Test
    void SRS_G_21_aGatedApprovalActionIsSubmittedWithoutACodeAndRecordsItsChallenge()
            throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        Map<String, Object> group = new HashMap<>();
        group.put("code", "AG-IT-OTPOFF");
        group.put("name", "IT group without OTP");
        group.put(
                "roleId",
                jdbc.queryForObject(
                        "SELECT role_id FROM app_role WHERE role_code = 'FINANCE'", Long.class));
        group.put("scopeType", "ZONE");
        group.put("consolidated", false);
        Map<String, Object> scope = new HashMap<>();
        scope.put(
                "zoneId",
                jdbc.queryForObject(
                        "SELECT zone_id FROM org_zone WHERE zone_code = 'NA11'", Long.class));
        scope.put("locationId", null);
        group.put("scopes", List.of(scope));
        group.put("reason", "IT access group, OTP off");

        // ROLE_ACCESS_GROUP_CHANGE asks for an OTP when it is on (AccessAdminIT): here no 428.
        MvcResult submitted = mvc.perform(userPost(admin, "/v1/admin/groups", group)).andReturn();
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString())
                .isBetween(200, 202);
        Long challenge =
                jdbc.queryForObject(
                        "SELECT otp_challenge_id FROM approval_queue WHERE entity_key ="
                            + " 'AG-IT-OTPOFF' ORDER BY approval_id DESC FETCH FIRST 1 ROWS ONLY",
                        Long.class);
        assertThat(challenge).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM otp_challenge WHERE otp_challenge_id = ?",
                                String.class,
                                challenge))
                .isEqualTo("SUCCESS");
    }

    /** SRS-15-1 freeze (CYCLE_FREEZE_LOCK) with OTP verification off: frozen without a code. */
    @Test
    void SRS_G_21_theCycleIsFrozenWithoutACodeAndKeepsItsChallenge() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        String period = PERIOD.toString();
        assertThat(
                        mvc.perform(userPost(maker, "/v1/cycles/" + period + "/initiate", Map.of()))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        MvcResult frozen =
                mvc.perform(userPost(maker, "/v1/cycles/" + period + "/freeze", Map.of()))
                        .andReturn();
        assertThat(frozen.getResponse().getStatus())
                .as(frozen.getResponse().getContentAsString())
                .isEqualTo(200);
        Long challenge =
                jdbc.queryForObject(
                        "SELECT c.freeze_otp_challenge_id FROM payroll_cycle_instance c JOIN"
                                + " org_location l ON l.location_id = c.org_unit_id WHERE"
                                + " c.payroll_yymm = ? AND l.location_code = 'DH001'",
                        Long.class,
                        period.replace("-", ""));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM otp_challenge WHERE otp_challenge_id = ?",
                                String.class,
                                challenge))
                .isEqualTo("SUCCESS");
    }
}
