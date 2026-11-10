package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.iam.security.AccessOffenceResolver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * OD-130 (SRS-G-25 records the refused call; the enforcement is the owner's, FCI security to
 * verify): a forged call logs the user out, the third suspends, sign-in is refused, the
 * Administrator reads the record and reinstates with a reason, and the count starts again. Every
 * other suite runs with {@code fci.security.offence.enabled = N} (application-it.yaml); this class
 * turns it on in a context of its own.
 */
@TestPropertySource(
        properties = {
            "fci.security.offence.enabled=Y",
            "fci.security.offence.logout-on-offence=Y",
            "fci.security.offence.suspend-after=3",
            "fci.it.isolated=security-offence"
        })
class SecurityOffenceIT extends ApiTestSupport {

    /** Each test its own demo user: the event table is append-only, the context its own. */
    private static final String FORGER = "finance.delhi@fci-demo.test";

    private static final String OUTSIDER = "maker.delhi@fci-demo.test";
    private static final String REPEATER = "finance.mumbai@fci-demo.test";
    private static final String RACER = "hr.delhi@fci-demo.test";

    /** Holds M-01 VIEW: the refusals below are not about the right. */
    private static final String CLEAN = "ddo.delhi@fci-demo.test";

    private static final String ADMIN = "sysadmin@fci-demo.test";

    private long userId(String login) {
        return jdbc.queryForObject(
                "SELECT user_id FROM app_user WHERE login_id = ?", Long.class, login);
    }

    private int events(String login, String type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM security_event WHERE acting_user = ? AND event_type = ?",
                Integer.class,
                login,
                type);
    }

    private String status(String login) {
        return jdbc.queryForObject(
                "SELECT user_status FROM app_user WHERE login_id = ?", String.class, login);
    }

    /** One forged call: only the System Administrator holds A-01. */
    private MvcResult forged(Session session) throws Exception {
        return mvc.perform(userGet(session, "/v1/admin/users")).andReturn();
    }

    private void offend(String login, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(forged(login(login, null, null)).getResponse().getStatus()).isEqualTo(403);
        }
    }

    @Test
    void SRS_G_25_OD_130_aForgedCallIsRefusedRecordedAndEndsTheSession() throws Exception {
        Session maker = login(FORGER, null, null);
        assertThat(mvc.perform(userGet(maker, "/auth/me")).andReturn().getResponse().getStatus())
                .isEqualTo(200);

        MvcResult refused = forged(maker);

        // The refusal stays what it is, and says the session ended.
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(refused).path("errorCode").asString()).isEqualTo("AUTHZ-403-PERMISSION");
        assertThat(refused.getResponse().getHeader(AccessOffenceResolver.SESSION_ENDED_HEADER))
                .isEqualTo("AUTH-401-OFFENCE");
        Map<String, Object> event =
                jdbc.queryForMap(
                        "SELECT target_key, detail FROM security_event WHERE acting_user = ? AND"
                                + " event_type = 'UNAUTHORIZED_ACCESS'",
                        FORGER);
        assertThat(event.get("TARGET_KEY")).isEqualTo("GET /v1/admin/users");
        assertThat((String) event.get("DETAIL"))
                .contains("path=/v1/admin/users")
                .contains("screen=A-01");

        // The next call is the 401 that says why.
        MvcResult next = mvc.perform(userGet(maker, "/auth/me")).andReturn();
        assertThat(next.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(next).path("errorCode").asString()).isEqualTo("AUTH-401-OFFENCE");
    }

    @Test
    void OD_130_aRecordOutsideTheScopeStaysA404AndStillEndsTheSession() throws Exception {
        String elsewhere =
                jdbc.queryForObject(
                        "SELECT e.emp_num FROM employee e JOIN org_location o ON o.location_id ="
                                + " e.location_id WHERE o.location_code = 'MR001' FETCH"
                                + " FIRST 1 ROWS ONLY",
                        String.class);
        Session maker = login(OUTSIDER, null, null);

        MvcResult outside = mvc.perform(userGet(maker, "/v1/employees/" + elsewhere)).andReturn();
        MvcResult missing =
                mvc.perform(userGet(login(OUTSIDER, null, null), "/v1/employees/NOSUCHEIS"))
                        .andReturn();

        assertThat(outside.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(outside).path("errorCode").asString())
                .isEqualTo(body(missing).path("errorCode").asString());
        assertThat(outside.getResponse().getHeader(AccessOffenceResolver.SESSION_ENDED_HEADER))
                .isNull();
        // The missing record is no offence; the record outside the scope is one.
        assertThat(events(OUTSIDER, "UNAUTHORIZED_ACCESS")).isEqualTo(1);
        assertThat(
                        body(mvc.perform(userGet(maker, "/auth/me")).andReturn())
                                .path("errorCode")
                                .asString())
                .isEqualTo("AUTH-401-OFFENCE");
    }

    @Test
    void OD_130_theThirdOffenceSuspendsSignInIsRefusedAndTheAdministratorReinstates()
            throws Exception {
        offend(REPEATER, 3);

        assertThat(status(REPEATER)).isEqualTo("SUSPENDED");
        assertThat(events(REPEATER, "UNAUTHORIZED_ACCESS")).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT detail FROM security_event WHERE event_type ="
                                        + " 'ACCOUNT_SUSPENDED_OFFENCE' AND target_key = ?",
                                String.class,
                                String.valueOf(userId(REPEATER))))
                .contains("3 unauthorized access attempts")
                .contains("GET /v1/admin/users");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM audit_event WHERE entity_name = 'app_user' AND"
                                        + " entity_key = ? AND new_value = 'SUSPENDED' ORDER BY"
                                        + " changed_at DESC FETCH FIRST 1 ROWS ONLY",
                                String.class,
                                String.valueOf(userId(REPEATER))))
                .contains("unauthorized access attempts");

        // Sign-in answers the existing code of a suspended account.
        MvcResult refused = loginRaw(REPEATER, DEMO_PASSWORD, null, null);
        assertThat(body(refused).path("errorCode").asString()).isEqualTo("AUTH-403-SUSPENDED");

        // The Administrator reads the record, and reinstates only with a reason.
        Session admin = login(ADMIN, "SYSTEM_ADMINISTRATOR", "HQ001");
        JsonNode record =
                body(
                        mvc.perform(
                                        userGet(
                                                admin,
                                                "/v1/admin/users/"
                                                        + userId(REPEATER)
                                                        + "/offences"))
                                .andReturn());
        assertThat(texts(record.path("items"), "type"))
                .contains("UNAUTHORIZED_ACCESS", "ACCOUNT_SUSPENDED_OFFENCE");
        String status = "/v1/admin/users/" + userId(REPEATER) + "/status";
        assertThat(
                        mvc.perform(userPost(admin, status, Map.of("status", "ACTIVE")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        mvc.perform(
                                        userPost(
                                                admin,
                                                status,
                                                Map.of(
                                                        "status",
                                                        "ACTIVE",
                                                        "note",
                                                        "Reviewed with the security officer")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(status(REPEATER)).isEqualTo("ACTIVE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM audit_event WHERE entity_name = 'app_user' AND"
                                        + " entity_key = ? AND new_value = 'ACTIVE' ORDER BY"
                                        + " changed_at DESC FETCH FIRST 1 ROWS ONLY",
                                String.class,
                                String.valueOf(userId(REPEATER))))
                .isEqualTo("Reviewed with the security officer");

        // The count starts again: two more offences do not suspend, the third would.
        offend(REPEATER, 2);
        assertThat(status(REPEATER)).isEqualTo("ACTIVE");
        offend(REPEATER, 1);
        assertThat(status(REPEATER)).isEqualTo("SUSPENDED");
    }

    @Test
    void OD_130_refusedCallsOfOneSessionAtOnceAreOneOffenceOnTheRealRowLock() throws Exception {
        Session one = login(RACER, null, null);
        CompletableFuture<MvcResult> first = forgedAsync(one);
        CompletableFuture<MvcResult> second = forgedAsync(one);

        List<Integer> statuses =
                List.of(
                        first.join().getResponse().getStatus(),
                        second.join().getResponse().getStatus());

        // Each is refused: 403, or 401 when the other ended the session before this one started.
        // Never a 500: nothing is written to the HTTP session both calls share.
        assertThat(statuses).allMatch(s -> s == 403 || s == 401);
        assertThat(events(RACER, "UNAUTHORIZED_ACCESS")).isEqualTo(1);
        assertThat(events(RACER, "UNAUTHORIZED_ACCESS_NOTED"))
                .isEqualTo((int) statuses.stream().filter(s -> s == 403).count() - 1);
    }

    private CompletableFuture<MvcResult> forgedAsync(Session session) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return forged(session);
                    } catch (Exception failed) {
                        throw new IllegalStateException(failed);
                    }
                });
    }

    @Test
    void OD_130_aRefusalThatIsNoOffenceChangesNothing() throws Exception {
        Session maker = login(CLEAN, null, null);

        // A validation refusal, on an endpoint this role may use.
        assertThat(
                        mvc.perform(userGet(maker, "/v1/employees?size=99999"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        // No CSRF token: refused by the filter chain, before any access rule.
        assertThat(
                        mvc.perform(
                                        as(
                                                maker,
                                                org.springframework.test.web.servlet.request
                                                        .MockMvcRequestBuilders.post(
                                                        "/v1/admin/users")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);

        assertThat(events(CLEAN, "UNAUTHORIZED_ACCESS")).isZero();
        assertThat(mvc.perform(userGet(maker, "/auth/me")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void OD_130_anAddressBarVisitIsLoggedNoOffence() throws Exception {
        Session maker = login(CLEAN, null, null);

        MvcResult told =
                mvc.perform(userPost(maker, "/auth/denied-screen", Map.of("screen", "A-01")))
                        .andReturn();

        assertThat(told.getResponse().getStatus()).isEqualTo(204);
        assertThat(events(CLEAN, "UNAUTHORIZED_ACCESS")).isZero();
        assertThat(mvc.perform(userGet(maker, "/auth/me")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }
}
