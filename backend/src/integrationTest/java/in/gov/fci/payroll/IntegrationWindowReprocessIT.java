package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.integration.api.InterfaceIntake.Inbound;
import in.gov.fci.payroll.integration.api.InterfaceIntake.Staged;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * X-03 Reprocess Failed for one exchange window (X-03 l.5172; SRS-INT-33; PENDING G5-96): the
 * System Administrator alone (a Payroll Maker is refused, the Approvals-free action has no maker /
 * checker), the reason mandatory, every open item of the window reprocessed through the adapter
 * under one reason and audited with the window; a failing item keeps the whole window open; a
 * window with nothing open, an unknown window and an interface without an adapter are refused, the
 * last one audited.
 */
class IntegrationWindowReprocessIT extends ApiTestSupport {

    @Autowired private InterfaceIntake intake;
    @Autowired private TransactionTemplate transactions;

    private Session admin() throws Exception {
        return login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
    }

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        return transactions.execute(s -> work.get());
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    /** A window of the interface with two rejected records; answers the run id. */
    private long failedWindow(String code) {
        long run = tx(() -> intake.open(code, "IT-window-" + UUID.randomUUID(), null, null));
        for (int i = 0; i < 2; i++) {
            String key = "IT-" + UUID.randomUUID();
            Staged staged =
                    tx(
                            () ->
                                    intake.stage(
                                            run,
                                            new Inbound(
                                                    ADAPTED.equals(code)
                                                            ? "LTC_ENCASH"
                                                            : "EMP_TRANSFER",
                                                    key,
                                                    key,
                                                    null,
                                                    "{\"demo\":true}",
                                                    null)));
            tx(
                    () -> {
                        intake.rejected(staged.stagingId(), "Encashment days above the balance");
                        return null;
                    });
        }
        return run;
    }

    private MvcResult post(Session s, long run, Object body) throws Exception {
        return mvc.perform(userPost(s, "/v1/integration/windows/" + run + "/reprocess", body))
                .andReturn();
    }

    private long open(long run) {
        return count(
                "SELECT COUNT(*) FROM interface_exception WHERE run_id = ? AND remediation_status"
                        + " = 'OPEN'",
                run);
    }

    @Test
    void SRS_INT_33_theAdministratorReprocessesEveryFailedRecordOfAWindowWithOneReason()
            throws Exception {
        long run = failedWindow(ADAPTED);
        assertThat(open(run)).isEqualTo(2);
        Session admin = admin();
        JsonNode windows =
                body(
                        mvc.perform(userGet(admin, "/v1/integration/windows?interface=" + ADAPTED))
                                .andReturn());
        assertThat(windows.get("items").get(0).get("adapter").asBoolean()).isTrue();

        Map<String, String> reason = Map.of("reason", "Corrected at source (window IT)");
        try {
            ADAPTER.put("reprocess", HRMS_DOWN);
            MvcResult failed = post(admin, run, reason);
            assertThat(failed.getResponse().getStatus()).isEqualTo(502);
            assertThat(open(run)).as("all or nothing: the window stays open").isEqualTo(2);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                            + " 'interface_run' AND entity_key = ? AND new_value"
                                            + " LIKE 'FAILED AT %'",
                                    String.valueOf(run)))
                    .as("the failed window is audited")
                    .isEqualTo(1);
        } finally {
            ADAPTER.clear();
        }

        JsonNode done = body(post(admin, run, reason));
        assertThat(done.get("runId").asLong()).isEqualTo(run);
        assertThat(done.get("interfaceCode").asString()).isEqualTo(ADAPTED);
        assertThat(done.get("reprocessed")).hasSize(2);
        assertThat(open(run)).isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM interface_exception WHERE run_id = ? AND"
                                        + " remediation_status = 'REPROCESSED'",
                                run))
                .isEqualTo(2);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'interface_run' AND entity_key = ? AND new_value ="
                                        + " 'REPROCESSED 2' AND reason = 'Corrected at source"
                                        + " (window IT)'",
                                String.valueOf(run)))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event e WHERE e.entity_name ="
                                        + " 'interface_exception' AND e.new_value = 'REPROCESSED'"
                                        + " AND e.entity_key IN (SELECT CAST(x.exception_id AS"
                                        + " VARCHAR(20)) FROM interface_exception x WHERE x.run_id"
                                        + " = ?)",
                                run))
                .as("each item audited as the per-item path does")
                .isEqualTo(2);

        // Nothing open any more: refused.
        MvcResult again = post(admin, run, reason);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(again).get("errorCode").asString()).isEqualTo("INT-409-WINDOW");
    }

    @Test
    void SRS_INT_33_reprocessFailedIsRefusedToAnyoneButTheAdministratorAndNeedsAReason()
            throws Exception {
        long run = failedWindow(ADAPTED);
        Map<String, String> reason = Map.of("reason", "Corrected at source");
        assertThat(post(maker(), run, reason).getResponse().getStatus())
                .as("X-03 EDIT is the Administrator's alone")
                .isEqualTo(403);
        Session admin = admin();
        for (Object blank :
                new Object[] {Map.of(), Map.of("reason", ""), Map.of("reason", "   ")}) {
            int status = post(admin, run, blank).getResponse().getStatus();
            assertThat(status).as("reason %s", blank).isIn(400, 422);
        }
        assertThat(open(run)).as("nothing changed").isEqualTo(2);
        MvcResult unknown = post(admin, 999_999_999L, reason);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(unknown).get("errorCode").asString()).isEqualTo("INT-409-WINDOW");
    }

    @Test
    void SRS_INT_33_aWindowOfAnInterfaceWithoutAnAdapterIsRefusedAndAudited() throws Exception {
        long run = failedWindow("HRMS_EMP_TRANSFER");
        Session admin = admin();
        JsonNode windows =
                body(
                        mvc.perform(
                                        userGet(
                                                admin,
                                                "/v1/integration/windows?interface=HRMS_EMP_TRANSFER"))
                                .andReturn());
        assertThat(windows.get("items").get(0).get("adapter").asBoolean()).isFalse();
        MvcResult refused = post(admin, run, Map.of("reason", "Corrected at source"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(refused).get("errorCode").asString()).isEqualTo("INT-409-NO-ADAPTER");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'interface_run' AND entity_key = ? AND new_value ="
                                        + " 'NO_ADAPTER'",
                                String.valueOf(run)))
                .isEqualTo(1);
        assertThat(open(run)).isEqualTo(2);
    }
}
