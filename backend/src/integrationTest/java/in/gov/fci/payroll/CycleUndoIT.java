package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * C-02 controlled undo before EBS posting and the C-01 Gate Override & Undo / Reversal Register
 * (plan 05 phase 05e; SRS-15-3, SRS-15-10, SRS-15-17, BR-CYC-006, SRS-G-21; plan 05 D-10, D-11).
 * The undoable kind is the stand-in {@code it_payment} of {@link ApiTestSupport} (no module
 * registers one yet). At Chennai (CH001), period 2 months back (no other IT uses it there).
 */
class CycleUndoIT extends ApiTestSupport {

    private static final YearMonth PERIOD =
            YearMonth.from(LocalDate.now(ZoneId.of("Asia/Kolkata"))).minusMonths(2);
    private static final String UNDO_OTP = "UNDO_TRANSACTION";

    private Session maker() throws Exception {
        return login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
    }

    private Session checker() throws Exception {
        return login("ddo.chennai@fci-demo.test", "PAYROLL_CHECKER", "CH001");
    }

    private long instance(Session maker) throws Exception {
        MvcResult r =
                mvc.perform(userPost(maker, "/v1/cycles/" + PERIOD + "/initiate", Map.of()))
                        .andReturn();
        assertThat(r.getResponse().getStatus()).isIn(200, 409);
        return jdbc.queryForObject(
                "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?",
                Long.class,
                PERIOD.toString().replace("-", ""),
                locationId("CH001"));
    }

    private MvcResult ask(Session s, String entity, String key) throws Exception {
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/cycles/" + PERIOD + "/undo-requests",
                                Map.of(
                                        "transactionEntity", entity,
                                        "transactionKey", key,
                                        "reason", "Entered twice by mistake")))
                .andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    /**
     * SRS-15-3 / SRS-15-10: the Maker requests, the unit's Payroll Checker verifies, the System
     * Administrator at HQ authorises with OTP; the module undoes it and the register records the
     * UNDO with every party and the OTP. A kind no module defines, a key of another cycle, a cycle
     * posted to EBS, the checker asking: refused.
     */
    @Test
    void SRS_15_3_anUndoIsVerifiedByTheCheckerAndAuthorisedWithOtpByTheAdministrator()
            throws Exception {
        Session maker = maker();
        long cycleId = instance(maker);
        UNDOABLE.put("P-1", cycleId);
        UNDOABLE.put("P-OTHER", cycleId + 100_000);

        assertThat(code(ask(maker, "mdp_transaction", "1"))).isEqualTo("CYC-422-UNDO-TYPE");
        assertThat(ask(maker, "it_payment", "P-OTHER").getResponse().getStatus()).isEqualTo(404);
        assertThat(ask(maker, "it_payment", "NO-SUCH").getResponse().getStatus()).isEqualTo(404);
        assertThat(ask(maker, "IT PAYMENT", "P-1").getResponse().getStatus()).isEqualTo(400);
        assertThat(ask(checker(), "it_payment", "P-1").getResponse().getStatus()).isEqualTo(403);
        // A period whose cycle was never initiated has nothing to undo.
        assertThat(
                        mvc.perform(
                                        userPost(
                                                maker,
                                                "/v1/cycles/"
                                                        + PERIOD.minusMonths(1)
                                                        + "/undo-requests",
                                                Map.of(
                                                        "transactionEntity", "it_payment",
                                                        "transactionKey", "P-1",
                                                        "reason", "x")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);

        jdbc.update(
                "UPDATE payroll_cycle_instance SET posted_to_ebs_flag = 'Y' WHERE"
                        + " cycle_instance_id = ?",
                cycleId);
        assertThat(code(ask(maker, "it_payment", "P-1"))).isEqualTo("CYC-409-POSTED");
        jdbc.update(
                "UPDATE payroll_cycle_instance SET posted_to_ebs_flag = 'N' WHERE"
                        + " cycle_instance_id = ?",
                cycleId);

        MvcResult asked = ask(maker, "it_payment", "P-1");
        assertThat(asked.getResponse().getStatus())
                .as(asked.getResponse().getContentAsString())
                .isEqualTo(202);
        long approvalId = body(asked).get("approvalId").asLong();

        assertThat(decide(checker(), approvalId, "APPROVE", null, null)).isEqualTo(200);
        assertThat(UNDONE).doesNotContainKey("P-1");
        // Posted meanwhile: the authorisation is refused, nothing is undone.
        jdbc.update(
                "UPDATE payroll_cycle_instance SET posted_to_ebs_flag = 'Y' WHERE"
                        + " cycle_instance_id = ?",
                cycleId);
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        assertThat(decide(admin, approvalId, "APPROVE", null, UNDO_OTP)).isEqualTo(409);
        assertThat(UNDONE).doesNotContainKey("P-1");
        jdbc.update(
                "UPDATE payroll_cycle_instance SET posted_to_ebs_flag = 'N' WHERE"
                        + " cycle_instance_id = ?",
                cycleId);

        assertThat(decide(admin, approvalId, "APPROVE", null, UNDO_OTP)).isEqualTo(200);
        assertThat(UNDONE).containsEntry("P-1", "sysadmin@fci-demo.test");
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT register_type, transaction_entity, stage_at_undo, reason,"
                                + " requested_by, checked_by, authorised_by, otp_challenge_id,"
                                + " payroll_yymm FROM undo_reversal_register WHERE approval_id = ?",
                        approvalId);
        assertThat(row)
                .containsEntry("REGISTER_TYPE", "UNDO")
                .containsEntry("TRANSACTION_ENTITY", "it_payment")
                .containsEntry("STAGE_AT_UNDO", "COMPUTED")
                .containsEntry("REASON", "Entered twice by mistake")
                .containsEntry("REQUESTED_BY", "maker.chennai@fci-demo.test")
                .containsEntry("CHECKED_BY", "ddo.chennai@fci-demo.test")
                .containsEntry("AUTHORISED_BY", "sysadmin@fci-demo.test")
                .containsEntry("PAYROLL_YYMM", PERIOD.toString().replace("-", ""));
        assertThat(row.get("OTP_CHALLENGE_ID")).isNotNull();
    }

    /**
     * SRS-15-17: the register lists the unit's gate overrides and undos, newest first, paged; the
     * Checker exports it, the Maker sees it but does not export; another unit sees none of it.
     */
    // SC-05-0105: the Gate Override & Undo / Reversal Register.
    @Test
    void SRS_15_17_theRegisterListsTheUnitsOverridesAndUndosAndIsExported() throws Exception {
        Session maker = maker();
        long cycleId = instance(maker);
        for (int i = 0; i < 3; i++) {
            jdbc.update(
                    "INSERT INTO undo_reversal_register (cycle_instance_id, register_type,"
                            + " transaction_entity, transaction_key, payroll_yymm, stage_at_undo,"
                            + " reason, requested_by, authorised_by, executed_at) VALUES (?,"
                            + " 'GATE_OVERRIDE', 'payroll_cycle_instance', ?, ?, 'VERIFIED',"
                            + " 'Statutory return pending', 'it.maker', 'it.checker',"
                            + " DATEADD('MINUTE', ?, CURRENT_TIMESTAMP))",
                    cycleId,
                    "G" + (2 + i),
                    PERIOD.toString().replace("-", ""),
                    i);
        }
        MvcResult page =
                mvc.perform(userGet(maker, "/v1/cycle-register-entries?page=0&size=10"))
                        .andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        JsonNode items = body(page).get("items");
        assertThat(body(page).get("total").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(items.size()).isGreaterThanOrEqualTo(3);
        assertThat(items.get(0).get("key").asString()).isEqualTo("G4");
        assertThat(items.get(0).get("type").asString()).isEqualTo("GATE_OVERRIDE");
        assertThat(items.get(0).get("reason").asString()).isEqualTo("Statutory return pending");

        assertThat(download(maker, "/v1/cycle-register-entries/export").getResponse().getStatus())
                .isEqualTo(403);
        String csv = csv(checker(), "/v1/cycle-register-entries/export");
        assertThat(csv.lines().findFirst().orElseThrow()).startsWith("\"txn\",\"type\",\"period\"");
        assertThat(csv).contains("GATE_OVERRIDE", "Statutory return pending", "G2", "G3", "G4");

        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        JsonNode theirs =
                body(mvc.perform(userGet(kolkata, "/v1/cycle-register-entries")).andReturn());
        for (JsonNode item : theirs.get("items")) {
            assertThat(item.get("reason").asString()).isNotEqualTo("Statutory return pending");
        }
    }
}
