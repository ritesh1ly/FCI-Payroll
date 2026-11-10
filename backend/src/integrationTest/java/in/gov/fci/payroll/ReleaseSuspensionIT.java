package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.payroll.api.ReleaseSuspensions;
import in.gov.fci.payroll.payroll.api.ReleaseSuspensions.Channel;
import in.gov.fci.payroll.platform.error.FciException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * O-02 emergency stop of payment release (plan 05 phase 05h; SRS-15-35, FRS BR-SEC-007 (SRS label
 * BR-SEC-006, CLR-43); D-15, D-16): Finance at Headquarters suspends at once, for all units or a
 * named one and a channel, with a reason; every release it covers is blocked; the resumption is
 * requested by Finance at HQ and approved by the Payroll Checker at HQ; both audited. Assertions
 * name this test's suspensions only (other ITs share the database).
 */
class ReleaseSuspensionIT extends ApiTestSupport {

    @Autowired private ReleaseSuspensions releases;

    private Session finance() throws Exception {
        return login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
    }

    private MvcResult suspend(Session s, String scope, String unit, String channel)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("scope", scope);
        if (unit != null) {
            body.put("unitCode", unit);
        }
        body.put("channel", channel);
        body.put("reason", "Bank file mismatch under review");
        return mvc.perform(userPost(s, "/v1/release-suspensions", body)).andReturn();
    }

    private MvcResult resume(Session s, long id) throws Exception {
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/release-suspensions/" + id + "/resumptions",
                                Map.of("reason", "Reconciled")))
                .andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    /**
     * SRS-15-35: a unit suspension blocks the named unit's release on its channel only; it is
     * resumed only on the HQ Checker's approval, and once resumed the release goes ahead.
     */
    // SRS-SEC-10: Headquarters stops the release for all units or one, with a reason; resumed on
    // approval.
    // SC-05-0077: a suspended release is blocked until the resumption is approved.
    @Test
    void SRS_15_35_aSuspensionBlocksTheReleaseItCoversUntilItsResumptionIsApproved()
            throws Exception {
        Session finance = finance();
        MvcResult created = suspend(finance, "UNIT", "DH001", "BANK_FILE");
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        long id = body(created).get("id").asLong();
        long delhi = locationId("DH001");
        assertThat(releases.blocking(delhi, Channel.BANK_FILE)).contains(id);
        assertThat(releases.blocking(delhi, Channel.EBS_AP_POSTING)).doesNotContain(id);
        assertThat(releases.blocking(locationId("KL001"), Channel.BANK_FILE)).doesNotContain(id);
        assertThatThrownBy(() -> releases.requireNotSuspended(delhi, Channel.BANK_FILE))
                .isInstanceOf(FciException.class);

        JsonNode listed =
                body(mvc.perform(userGet(finance, "/v1/release-suspensions?size=100")).andReturn());
        assertThat(listed.toString()).contains("Bank file mismatch under review", "DH001");
        // R-RBAC data scope (security review 2026-10-31): another office's viewer does not see
        // Delhi's unit suspension.
        JsonNode mumbai =
                body(
                        mvc.perform(
                                        userGet(
                                                login(
                                                        "finance.mumbai@fci-demo.test",
                                                        "FINANCE",
                                                        "MR001"),
                                                "/v1/release-suspensions?size=100"))
                                .andReturn());
        assertThat(mumbai.findValues("id").stream().map(JsonNode::asLong).toList())
                .doesNotContain(id);

        MvcResult asked = resume(finance, id);
        assertThat(asked.getResponse().getStatus()).isEqualTo(202);
        long approvalId = body(asked).get("id").asLong();
        assertThat(code(resume(finance, id))).isEqualTo("SUS-409-STATE");
        assertThat(releases.blocking(delhi, Channel.BANK_FILE)).as("still suspended").contains(id);
        assertThat(decide(finance, approvalId, "APPROVE", null, null)).isEqualTo(403);
        assertThat(
                        decide(
                                login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001"),
                                approvalId,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(releases.blocking(delhi, Channel.BANK_FILE)).doesNotContain(id);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT resumed_by, resume_reason, resume_approval_id FROM"
                                + " disb_release_suspension WHERE suspension_id = ?",
                        id);
        assertThat(row)
                .containsEntry("RESUMED_BY", "checker.hq@fci-demo.test")
                .containsEntry("RESUME_REASON", "Reconciled");
        assertThat(((Number) row.get("RESUME_APPROVAL_ID")).longValue()).isEqualTo(approvalId);
        MvcResult again = resume(finance, id);
        assertThat(code(again)).isEqualTo("SUS-409-STATE");
        assertThat(body(again).get("args").get(0).asString()).isEqualTo("RESUMED");
    }

    /**
     * SRS-15-35: only Finance at Headquarters suspends; a suspension of all units covers every unit
     * and channel; the scope names a cycle unit only when it is a unit suspension.
     */
    @Test
    void SRS_15_35_onlyFinanceAtHqSuspendsAndAnAllUnitsSuspensionCoversEveryRelease()
            throws Exception {
        assertThat(
                        suspend(
                                        login("finance.mumbai@fci-demo.test", "FINANCE", "MR001"),
                                        "ALL_UNITS",
                                        null,
                                        "ALL")
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        assertThat(
                        suspend(
                                        login(
                                                "maker.delhi@fci-demo.test",
                                                "PAYROLL_MAKER",
                                                "DH001"),
                                        "ALL_UNITS",
                                        null,
                                        "ALL")
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        Session finance = finance();
        assertThat(code(suspend(finance, "UNIT", null, "ALL"))).isEqualTo("SUS-422-SCOPE");
        assertThat(code(suspend(finance, "ALL_UNITS", "DH001", "ALL"))).isEqualTo("SUS-422-SCOPE");
        assertThat(code(suspend(finance, "UNIT", "DP-WB01", "ALL"))).isEqualTo("SUS-422-SCOPE");

        long all = body(suspend(finance, "ALL_UNITS", null, "ALL")).get("id").asLong();
        assertThat(releases.blocking(locationId("KL001"), Channel.APPS_REMITTANCE)).contains(all);
        assertThat(releases.blocking(locationId("CH001"), Channel.EBS_AP_POSTING)).contains(all);
        // Resumed at the end so that other tests' releases are not held by it.
        long approval = body(resume(finance, all)).get("id").asLong();
        assertThat(
                        decide(
                                login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(releases.blocking(locationId("KL001"), Channel.APPS_REMITTANCE))
                .doesNotContain(all);
        // Nothing covers it any more: the release goes ahead; a page past the last is empty.
        releases.requireNotSuspended(locationId("KL001"), Channel.APPS_REMITTANCE);
        JsonNode past =
                body(
                        mvc.perform(userGet(finance, "/v1/release-suspensions?page=999&size=10"))
                                .andReturn());
        assertThat(past.get("items")).isEmpty();
    }
}
