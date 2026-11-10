package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * C-02 cycle lease (plan 05 phase 05c; SRS-15-28, BR-CYC-015; DDL {@code cycle_lease}, {@code
 * cycle_lease_log}): while one action holds an instance, a freeze or a lock request on it is
 * refused naming the holder and since when; a lease whose heartbeat stopped is taken over and
 * logged EXPIRED; a stuck lease is force-released by the Payroll Checker with the OTP {@code
 * CYCLE_UNFREEZE_STUCK_RELEASE} (SRS-G-21) on the Maker's request, and only the lease the request
 * named. Each test uses its own past period of Kolkata (KL001); a lease row stands in for a running
 * computation.
 */
class CycleLeaseIT extends ApiTestSupport {

    private static final YearMonth CURRENT =
            YearMonth.from(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    private static final String FREEZE_OTP = "CYCLE_FREEZE_LOCK";
    private static final String RELEASE_OTP = "CYCLE_UNFREEZE_STUCK_RELEASE";
    private static final String RUN_HOLDER = "batch.compute@fci-demo.test";

    private Session maker() throws Exception {
        return login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
    }

    private Session checker() throws Exception {
        return login("ddo.kolkata@fci-demo.test", "PAYROLL_CHECKER", "KL001");
    }

    /**
     * An INITIATED instance of {@code period} at KL001, by {@code maker} (a new sign-in would end
     * the caller's session).
     */
    private long initiated(Session maker, YearMonth period) throws Exception {
        MvcResult r = post(maker, "/v1/cycles/" + period + "/initiate", Map.of());
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
        return jdbc.queryForObject(
                "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?",
                Long.class,
                period.toString().replace("-", ""),
                locationId("KL001"));
    }

    /** A lease held on the instance, its heartbeat {@code beatAgo} old. */
    private void lease(long cycleId, Duration beatAgo) {
        Timestamp at = Timestamp.from(Instant.now().minus(beatAgo).truncatedTo(ChronoUnit.MILLIS));
        jdbc.update(
                "INSERT INTO cycle_lease (cycle_instance_id, lease_action, held_by, acquired_at,"
                        + " heartbeat_at) VALUES (?, 'COMPUTE', ?, ?, ?)",
                cycleId,
                RUN_HOLDER,
                at,
                at);
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    /** Posts a cycle step; answers a 428 OTP challenge with the code the sender received. */
    private MvcResult withOtp(Session s, String path, Map<String, Object> body) throws Exception {
        MvcResult first = post(s, path, body);
        if (first.getResponse().getStatus() != 428) {
            return first;
        }
        String email =
                jdbc.queryForObject(
                        "SELECT registered_email FROM app_user WHERE login_id = ?",
                        String.class,
                        s.loginId());
        Map<String, Object> again = new HashMap<>(body);
        again.put(
                "otp",
                Map.of(
                        "challengeId",
                        body(first).get("args").get(0).asLong(),
                        "code",
                        OTP_CODES.get(email + "|" + FREEZE_OTP)));
        return post(s, path, again);
    }

    private JsonNode cycle(Session s, YearMonth period) throws Exception {
        MvcResult r = mvc.perform(userGet(s, "/v1/cycles/" + period)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return body(r);
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private MvcResult askRelease(Session s, YearMonth period, String reason) throws Exception {
        return post(s, "/v1/cycles/" + period + "/lease-releases", Map.of("reason", reason));
    }

    private List<String> events(long cycleId) {
        return jdbc.queryForList(
                "SELECT event_type FROM cycle_lease_log WHERE cycle_instance_id = ? ORDER BY"
                        + " lease_log_id",
                String.class,
                cycleId);
    }

    private int audited(long cycleId, String outcome) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE entity_name = 'cycle_lease' AND entity_key"
                        + " = ? AND new_value = ?",
                Integer.class,
                String.valueOf(cycleId),
                outcome);
    }

    /**
     * SRS-15-28 / BR-CYC-015: a held lease refuses the freeze and the lock request, naming the
     * action and the holder; the screen shows it; another instance of the same unit is not blocked,
     * and a freeze holds the lease for its own transaction only.
     */
    @Test
    void SRS_15_28_aHeldLeaseRefusesFreezeAndLockNamingTheHolder() throws Exception {
        YearMonth period = CURRENT.minusMonths(6);
        Session maker = maker();
        long id = initiated(maker, period);
        lease(id, Duration.ofSeconds(5));

        JsonNode shown = cycle(maker, period).get("lease");
        assertThat(shown.get("action").asString()).isEqualTo("COMPUTE");
        assertThat(shown.get("heldBy").asString()).isEqualTo(RUN_HOLDER);
        assertThat(shown.get("expired").asBoolean()).isFalse();

        MvcResult frozen = withOtp(maker, "/v1/cycles/" + period + "/freeze", Map.of());
        assertThat(frozen.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(frozen)).isEqualTo("CYC-409-LEASE");
        assertThat(frozen.getResponse().getContentAsString()).contains("COMPUTE", RUN_HOLDER);
        assertThat(cycle(maker, period).get("status").asString()).isEqualTo("INITIATED");

        // The lock request: the instance moved on to VERIFIED by SQL, the lease still held.
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'VERIFIED' WHERE"
                        + " cycle_instance_id = ?",
                id);
        MvcResult lock =
                withOtp(maker, "/v1/cycles/" + period + "/lock", Map.of("reason", "Month end"));
        assertThat(code(lock)).isEqualTo("CYC-409-LEASE");
        assertThat(cycle(maker, period).get("lockApprovalId").isNull()).isTrue();

        // The lease is per instance: another period of the same unit freezes.
        YearMonth other = CURRENT.minusMonths(7);
        long otherId = initiated(maker, other);
        MvcResult free = withOtp(maker, "/v1/cycles/" + other + "/freeze", Map.of());
        assertThat(free.getResponse().getStatus())
                .as(free.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(events(otherId)).containsExactly("ACQUIRED", "RELEASED");
        assertThat(cycle(maker, other).get("lease").isNull()).isTrue();
    }

    /** Plan 05 D-7: a lease whose heartbeat stopped is taken over by the next action. */
    @Test
    void BR_CYC_015_anExpiredLeaseIsTakenOverAndLoggedExpired() throws Exception {
        YearMonth period = CURRENT.minusMonths(8);
        Session maker = maker();
        long id = initiated(maker, period);
        lease(id, Duration.ofMinutes(30));
        assertThat(cycle(maker, period).get("lease").get("expired").asBoolean()).isTrue();

        MvcResult frozen = withOtp(maker, "/v1/cycles/" + period + "/freeze", Map.of());
        assertThat(frozen.getResponse().getStatus())
                .as(frozen.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(events(id)).containsExactly("EXPIRED", "ACQUIRED", "RELEASED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT held_by FROM cycle_lease_log WHERE cycle_instance_id = ?"
                                        + " AND event_type = 'EXPIRED'",
                                String.class,
                                id))
                .isEqualTo(RUN_HOLDER);
        assertThat(cycle(maker, period).get("status").asString()).isEqualTo("FROZEN");
    }

    /**
     * SRS-15-28, SRS-G-21: the Maker asks for the force-release with a reason; the Checker does not
     * ask and the Maker does not decide; the Checker approves with the OTP; the lease named in the
     * request is released and logged FORCE_RELEASED with the reason, decider, approval and OTP.
     */
    @Test
    void SRS_15_28_aStuckLeaseIsForceReleasedByTheCheckerWithOtp() throws Exception {
        YearMonth period = CURRENT.minusMonths(9);
        Session maker = maker();
        long id = initiated(maker, period);

        // Nothing holds the lease: there is nothing to release.
        MvcResult none = askRelease(maker, period, "Run stuck");
        assertThat(code(none)).isEqualTo("CYC-409-STATE");
        assertThat(body(none).get("args").get(0).asString()).isEqualTo("NOT_LEASED");

        lease(id, Duration.ofSeconds(5));
        assertThat(askRelease(maker, period, " ").getResponse().getStatus()).isEqualTo(400);
        assertThat(askRelease(checker(), period, "Run stuck").getResponse().getStatus())
                .isEqualTo(403);

        MvcResult asked = askRelease(maker, period, "Run stuck since noon");
        assertThat(asked.getResponse().getStatus())
                .as(asked.getResponse().getContentAsString())
                .isEqualTo(202);
        long approvalId = body(asked).get("leaseReleaseApprovalId").asLong();
        assertThat(approvalId).isPositive();
        assertThat(events(id)).isEmpty();

        // Maker-checker: the requester never decides their own request.
        assertThat(decide(maker, approvalId, "APPROVE", null, RELEASE_OTP)).isEqualTo(403);
        assertThat(cycle(maker, period).get("lease").isNull()).isFalse();
        assertThat(decide(checker(), approvalId, "APPROVE", null, RELEASE_OTP)).isEqualTo(200);

        assertThat(cycle(maker, period).get("lease").isNull()).isTrue();
        Map<String, Object> log =
                jdbc.queryForMap(
                        "SELECT held_by, released_by, force_release_reason, approval_id,"
                                + " otp_challenge_id FROM cycle_lease_log WHERE"
                                + " cycle_instance_id = ? AND event_type = 'FORCE_RELEASED'",
                        id);
        assertThat(log.get("HELD_BY")).isEqualTo(RUN_HOLDER);
        assertThat(log.get("RELEASED_BY")).isEqualTo("ddo.kolkata@fci-demo.test");
        assertThat(log.get("FORCE_RELEASE_REASON")).isEqualTo("Run stuck since noon");
        assertThat(((Number) log.get("APPROVAL_ID")).longValue()).isEqualTo(approvalId);
        assertThat(log.get("OTP_CHALLENGE_ID")).isNotNull();
        assertThat(audited(id, "FORCE_RELEASED")).isEqualTo(1);
    }

    /**
     * SRS-15-28: the release names the lease held when it was asked; a lease taken since then (a
     * new run) is left alone when the request is approved.
     */
    @Test
    void SRS_15_28_aNewerLeaseIsNotReleasedByAnOlderRequest() throws Exception {
        YearMonth period = CURRENT.minusMonths(4);
        Session maker = maker();
        long id = initiated(maker, period);
        lease(id, Duration.ofSeconds(30));
        MvcResult asked = askRelease(maker, period, "Run stuck");
        assertThat(asked.getResponse().getStatus()).isEqualTo(202);
        long approvalId = body(asked).get("leaseReleaseApprovalId").asLong();

        // The stuck run ends and a new one takes the lease before the checker decides.
        jdbc.update("DELETE FROM cycle_lease WHERE cycle_instance_id = ?", id);
        lease(id, Duration.ofSeconds(1));

        assertThat(decide(checker(), approvalId, "APPROVE", null, RELEASE_OTP)).isEqualTo(200);
        assertThat(cycle(maker, period).get("lease").isNull()).isFalse();
        assertThat(events(id)).doesNotContain("FORCE_RELEASED");
        assertThat(audited(id, "ALREADY_RELEASED")).isEqualTo(1);
    }
}
