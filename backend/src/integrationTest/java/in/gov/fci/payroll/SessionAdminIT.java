package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 02 F-2, session and account administration on A-01 (SRS-G-49, SRS-G-50, SRS-22-13): an
 * administrator ends a user's open sessions or suspends a group of accounts during a security
 * incident, each with a recorded reason and a security event; the login record shows each sign-in;
 * a zone administrator acts only on users of its own offices. Accounts are suspended only after
 * being created here, so the shared demo users stay untouched.
 */
class SessionAdminIT extends ApiTestSupport {

    private static final String TEMP = "Temp#Session-2027";

    private Session admin() throws Exception {
        return login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
    }

    private long userId(String login) {
        return jdbc.queryForObject(
                "SELECT user_id FROM app_user WHERE login_id = ?", Long.class, login);
    }

    private long events(String type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM security_event WHERE event_type = ?", Long.class, type);
    }

    private int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String code(MvcResult result) throws Exception {
        return body(result).path("errorCode").asString();
    }

    private long newUser(Session admin) throws Exception {
        String login =
                "incident." + UUID.randomUUID().toString().substring(0, 8) + "@fci-demo.test";
        JsonNode created =
                body(
                        mvc.perform(
                                        userPost(
                                                admin,
                                                "/v1/admin/users",
                                                Map.of(
                                                        "loginId",
                                                        login,
                                                        "displayName",
                                                        "Incident Test",
                                                        "category",
                                                        "OPERATIONS",
                                                        "temporaryPassword",
                                                        TEMP)))
                                .andReturn());
        return created.get("id").asLong();
    }

    private String userStatus(long id) {
        return jdbc.queryForObject(
                "SELECT user_status FROM app_user WHERE user_id = ?", String.class, id);
    }

    @Test
    void SRS_G_49_anAdministratorEndsAUsersSessionsAtOnceWithAReasonTheAccountStaysActive()
            throws Exception {
        Session user = login("rbac.maker.ludhiana@fci-demo.test", null, null);
        assertThat(status(mvc.perform(userGet(user, "/auth/me")).andReturn())).isEqualTo(200);
        long target = userId("rbac.maker.ludhiana@fci-demo.test");
        long before = events("SESSION_TERMINATED");
        Session admin = admin();

        MvcResult noReason =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/admin/users/" + target + "/terminate-session",
                                        Map.of("reason", " ")))
                        .andReturn();
        assertThat(status(noReason)).isEqualTo(400);
        assertThat(status(mvc.perform(userGet(user, "/auth/me")).andReturn())).isEqualTo(200);

        MvcResult ended =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/admin/users/" + target + "/terminate-session",
                                        Map.of("reason", "Incident 42: shared terminal")))
                        .andReturn();
        assertThat(status(ended)).isEqualTo(200);
        assertThat(body(ended).get("sessions").asInt()).isGreaterThanOrEqualTo(1);
        // The open session is refused on its next request; the account itself is unchanged.
        assertThat(status(mvc.perform(userGet(user, "/auth/me")).andReturn())).isEqualTo(401);
        assertThat(userStatus(target)).isEqualTo("ACTIVE");
        assertThat(events("SESSION_TERMINATED")).isEqualTo(before + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name = 'app_user'"
                                    + " AND entity_key = ? AND field_name = 'SESSION'  AND reason ="
                                    + " 'Incident 42: shared terminal'",
                                Long.class,
                                String.valueOf(target)))
                .isEqualTo(1);
        // The user signs in again as usual.
        Session again = login("rbac.maker.ludhiana@fci-demo.test", null, null);
        assertThat(status(mvc.perform(userGet(again, "/auth/me")).andReturn())).isEqualTo(200);
    }

    @Test
    void SRS_G_49_anAdministratorCannotEndTheOwnSessionAndAMakerCannotEndAnyone() throws Exception {
        Session admin = admin();
        long self = userId("sysadmin@fci-demo.test");
        MvcResult own =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/admin/users/" + self + "/terminate-session",
                                        Map.of("reason", "x")))
                        .andReturn();
        assertThat(status(own)).isEqualTo(403);
        assertThat(code(own)).isEqualTo("ADM-403-SELF");

        Session maker = login("maker.delhi@fci-demo.test", null, null);
        long other = userId("rbac.maker.ludhiana@fci-demo.test");
        for (MvcResult refused :
                List.of(
                        mvc.perform(
                                        userPost(
                                                maker,
                                                "/v1/admin/users/" + other + "/terminate-session",
                                                Map.of("reason", "x")))
                                .andReturn(),
                        mvc.perform(
                                        userPost(
                                                maker,
                                                "/v1/admin/users/suspend",
                                                Map.of("userIds", List.of(other), "reason", "x")))
                                .andReturn(),
                        mvc.perform(userGet(maker, "/v1/admin/users/" + other + "/logins"))
                                .andReturn())) {
            assertThat(status(refused)).isEqualTo(403);
        }
    }

    @Test
    void SRS_22_13_aZoneAdministratorEndsSessionsInItsZoneOnlyAndIsRefusedBeyond()
            throws Exception {
        Session north = login("rbac.admin.north@fci-demo.test", "SYSTEM_ADMINISTRATOR", "NA11");
        long outside = userId("finance.mumbai@fci-demo.test");
        Session mumbai = login("finance.mumbai@fci-demo.test", null, null);
        long before = events("OUT_OF_SCOPE_ACCESS");
        for (MvcResult refused :
                List.of(
                        mvc.perform(
                                        userPost(
                                                north,
                                                "/v1/admin/users/" + outside + "/terminate-session",
                                                Map.of("reason", "x")))
                                .andReturn(),
                        mvc.perform(userGet(north, "/v1/admin/users/" + outside + "/logins"))
                                .andReturn())) {
            // A user of another zone is unknown to this administrator (404).
            assertThat(status(refused)).isEqualTo(404);
        }
        assertThat(events("OUT_OF_SCOPE_ACCESS")).isGreaterThanOrEqualTo(before + 2);
        // Mumbai's session was not touched.
        assertThat(status(mvc.perform(userGet(mumbai, "/auth/me")).andReturn())).isEqualTo(200);

        long inside = userId("rbac.maker.ludhiana@fci-demo.test");
        login("rbac.maker.ludhiana@fci-demo.test", null, null);
        MvcResult ended =
                mvc.perform(
                                userPost(
                                        north,
                                        "/v1/admin/users/" + inside + "/terminate-session",
                                        Map.of("reason", "Zone incident")))
                        .andReturn();
        assertThat(status(ended)).isEqualTo(200);
    }

    @Test
    void SRS_G_49_aGroupOfAccountsIsSuspendedWithOneReasonAlreadySuspendedOnesAreSkipped()
            throws Exception {
        Session admin = admin();
        long first = newUser(admin);
        long second = newUser(admin);
        long third = newUser(admin);
        // The third account is suspended already (with its own reason).
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        admin,
                                                        "/v1/admin/users/" + third + "/status",
                                                        Map.of(
                                                                "status",
                                                                "SUSPENDED",
                                                                "note",
                                                                "Earlier")))
                                        .andReturn()))
                .isEqualTo(200);
        long before = events("ACCOUNT_SUSPENDED");

        MvcResult noReason =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/admin/users/suspend",
                                        Map.of("userIds", List.of(first, second), "reason", "")))
                        .andReturn();
        assertThat(status(noReason)).isEqualTo(400);
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        admin,
                                                        "/v1/admin/users/suspend",
                                                        Map.of(
                                                                "userIds", List.of(), "reason",
                                                                "x")))
                                        .andReturn()))
                .isEqualTo(400);

        MvcResult done =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/admin/users/suspend",
                                        Map.of(
                                                "userIds",
                                                List.of(first, second, third, first),
                                                "reason",
                                                "Incident 43: credential leak")))
                        .andReturn();
        assertThat(status(done)).isEqualTo(200);
        assertThat(body(done).get("suspended").size()).isEqualTo(2);
        assertThat(body(done).get("skipped").get(0).asLong()).isEqualTo(third);
        assertThat(userStatus(first)).isEqualTo("SUSPENDED");
        assertThat(userStatus(second)).isEqualTo("SUSPENDED");
        assertThat(events("ACCOUNT_SUSPENDED")).isEqualTo(before + 2);
    }

    @Test
    void SRS_G_49_oneAccountOutsideTheScopeOrTheAdministratorsOwnRefusesTheWholeGroup()
            throws Exception {
        Session admin = admin();
        long fresh = newUser(admin);
        long self = userId("sysadmin@fci-demo.test");
        MvcResult withSelf =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/admin/users/suspend",
                                        Map.of("userIds", List.of(fresh, self), "reason", "x")))
                        .andReturn();
        assertThat(status(withSelf)).isEqualTo(403);
        assertThat(userStatus(fresh)).isEqualTo("ACTIVE");

        Session north = login("rbac.admin.north@fci-demo.test", "SYSTEM_ADMINISTRATOR", "NA11");
        MvcResult beyond =
                mvc.perform(
                                userPost(
                                        north,
                                        "/v1/admin/users/suspend",
                                        Map.of(
                                                "userIds",
                                                List.of(userId("finance.mumbai@fci-demo.test")),
                                                "reason",
                                                "x")))
                        .andReturn();
        assertThat(status(beyond)).isEqualTo(404);
        assertThat(userStatus(userId("finance.mumbai@fci-demo.test"))).isEqualTo("ACTIVE");

        List<Long> tooMany = new java.util.ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            tooMany.add(i);
        }
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        admin,
                                                        "/v1/admin/users/suspend",
                                                        Map.of("userIds", tooMany, "reason", "x")))
                                        .andReturn()))
                .isEqualTo(400);
    }

    @Test
    void SRS_G_50_theLoginRecordShowsEachSignInWithAddressDeviceAndHowItEnded() throws Exception {
        Session user = login("rbac.maker.ludhiana@fci-demo.test", null, null);
        assertThat(status(mvc.perform(userGet(user, "/auth/me")).andReturn())).isEqualTo(200);
        Session admin = admin();
        long target = userId("rbac.maker.ludhiana@fci-demo.test");
        JsonNode record = all(admin, "/v1/admin/users/" + target + "/logins");
        assertThat(record.isArray()).isTrue();
        assertThat(record.size()).isBetween(1, 20);
        JsonNode latest = record.get(0);
        assertThat(latest.get("open").asBoolean()).isTrue();
        assertThat(latest.get("startedAt").asString()).isNotBlank();
        assertThat(latest.get("device").asString()).isNotBlank();
        // Newest first.
        for (int i = 1; i < record.size(); i++) {
            assertThat(record.get(i - 1).get("startedAt").asString())
                    .isGreaterThanOrEqualTo(record.get(i).get("startedAt").asString());
        }
    }
}
