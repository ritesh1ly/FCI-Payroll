package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.integration.api.InterfaceCalls;
import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.integration.api.InterfaceIntake.Completed;
import in.gov.fci.payroll.integration.api.InterfaceIntake.Inbound;
import in.gov.fci.payroll.integration.api.InterfaceIntake.Outcome;
import in.gov.fci.payroll.integration.api.InterfaceIntake.Staged;
import in.gov.fci.payroll.integration.orchestration.InterfaceSettingsService;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Module 23 phases 23a / 23b (plan docs/plan/23-integrations.md D23-3 … D23-7; SRS-INT-6,
 * SRS-INT-11, SRS-INT-12, SRS-INT-16, SRS-INT-17, SRS-INT-31 … SRS-INT-33): the exchange envelope
 * with idempotent staging, per-record rejection to the exception queue and X-05, the count check at
 * completion, the resend linked to its rejection; the resilient call refused while not configured,
 * retried, opening its breaker at the threshold and refusing while open; X-03 for its viewers, its
 * Administrator-only actions with a mandatory reason, audited also when refused.
 */
class IntegrationIT extends ApiTestSupport {

    @Autowired private InterfaceIntake intake;
    @Autowired private InterfaceCalls calls;
    @Autowired private TransactionTemplate transactions;

    private static final String HRMS = "HRMS_EMP_TRANSFER";

    private Session admin() throws Exception {
        return login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
    }

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private Inbound record(String key, String reprocessOf) {
        return new Inbound("EMP_TRANSFER", key, key, null, "{\"demo\":true}", reprocessOf);
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        return transactions.execute(s -> work.get());
    }

    // SRS-INT-11, SRS-INT-12, SRS-INT-31, SRS-INT-32
    @Test
    void SRS_INT_32_theDrawnTablesSplitHrmsFromEnterpriseAndFilterByStatusAndSearch()
            throws Exception {
        // X-03 as Wireframes v6.1 draws it (OD-121): "HRMS -> Payroll interfaces" and "Enterprise
        // interfaces".
        Session maker = maker();
        JsonNode hrms =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/integration/interfaces?scope=HRMS&size=100"))
                                .andExpect(
                                        org.springframework.test.web.servlet.result
                                                .MockMvcResultMatchers.status()
                                                .isOk())
                                .andReturn());
        assertThat(hrms.get("items").findValuesAsString("counterparty"))
                .isNotEmpty()
                .allMatch("HRMS"::equals);
        JsonNode enterprise =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/integration/interfaces?scope=ENTERPRISE&size=100"))
                                .andExpect(
                                        org.springframework.test.web.servlet.result
                                                .MockMvcResultMatchers.status()
                                                .isOk())
                                .andReturn());
        assertThat(enterprise.get("items").findValuesAsString("counterparty"))
                .isNotEmpty()
                .noneMatch("HRMS"::equals);
        JsonNode all =
                body(
                        mvc.perform(userGet(maker, "/v1/integration/interfaces?size=100"))
                                .andReturn());
        assertThat(hrms.get("total").asLong() + enterprise.get("total").asLong())
                .isEqualTo(all.get("total").asLong());
        // The drawn status filter keeps the interfaces whose latest window ended so.
        for (String st : new String[] {"OK", "PARTIAL", "FAILED"}) {
            JsonNode of =
                    body(
                            mvc.perform(
                                            userGet(
                                                    maker,
                                                    "/v1/integration/interfaces?status="
                                                            + st
                                                            + "&size=100"))
                                    .andExpect(
                                            org.springframework.test.web.servlet.result
                                                    .MockMvcResultMatchers.status()
                                                    .isOk())
                                    .andReturn());
            assertThat(of.get("items").findValuesAsString("status")).allMatch(st::equals);
        }
        // The search matches code or name in any case; a value outside the lists is refused.
        String code = hrms.get("items").get(0).get("code").asString();
        JsonNode found =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/integration/interfaces?q="
                                                        + code.toLowerCase()
                                                                .substring(
                                                                        0,
                                                                        Math.min(
                                                                                6, code.length()))))
                                .andExpect(
                                        org.springframework.test.web.servlet.result
                                                .MockMvcResultMatchers.status()
                                                .isOk())
                                .andReturn());
        assertThat(found.get("items").findValuesAsString("code")).contains(code);
        mvc.perform(userGet(maker, "/v1/integration/interfaces?scope=OTHER"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        mvc.perform(userGet(maker, "/v1/integration/interfaces?status=DONE"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
    }

    @Test
    void SRS_INT_12_aWindowStagesOnceRejectsWithAReasonAndBalances() {
        String a = "IT-" + UUID.randomUUID();
        String b = "IT-" + UUID.randomUUID();
        long run = tx(() -> intake.open(HRMS, "IT-" + a, Instant.now(), Instant.now()));
        Staged first = tx(() -> intake.stage(run, record(a, null)));
        Staged again = tx(() -> intake.stage(run, record(a, null)));
        Staged second = tx(() -> intake.stage(run, record(b, null)));
        assertThat(first.outcome()).isEqualTo(Outcome.STAGED);
        assertThat(again).isEqualTo(new Staged(first.stagingId(), Outcome.DUPLICATE));
        assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE request_id = ?", a))
                .as("never staged twice")
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM intf_staging_event WHERE staging_id = ? AND"
                                        + " event_type = 'DUPLICATE_REJECTED'",
                                first.stagingId()))
                .isEqualTo(1);
        tx(
                () -> {
                    intake.applied(first.stagingId(), "employee_transfer", "T-1");
                    intake.rejected(second.stagingId(), "Transfer date before joining (IT)");
                    return null;
                });
        Completed done = tx(() -> intake.complete(run, BigDecimal.TEN));
        assertThat(done).isEqualTo(new Completed(run, "PARTIAL", 3, 1, 2));
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM interface_exception WHERE staging_id = ? AND"
                                        + " remediation_status = 'OPEN' AND rejection_reason ="
                                        + " 'Transfer date before joining (IT)'",
                                second.stagingId()))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM payroll_exception WHERE exception_code ="
                                        + " 'INTEGRATION_FAILURE' AND field_name = ? AND"
                                        + " exception_status = 'OPEN'",
                                HRMS))
                .as("the Integration Failure raised at once on X-05")
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM interface_acknowledgement WHERE run_id = ?"
                                        + " AND ack_status = 'PENDING'",
                                run))
                .isEqualTo(1);
        // A completed window takes no more records; an applied record cannot be rejected.
        assertThatThrownBy(() -> tx(() -> intake.stage(run, record("IT-late", null))))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(
                        () ->
                                tx(
                                        () -> {
                                            intake.rejected(first.stagingId(), "late");
                                            return null;
                                        }))
                .isInstanceOf(FciException.class);

        // SRS-INT-11 / D23-5: the resend under a new key names the rejected one.
        long resend = tx(() -> intake.open(HRMS, "IT-resend", null, null));
        String c = "IT-" + UUID.randomUUID();
        Staged corrected = tx(() -> intake.stage(resend, record(c, b)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reprocess_of_staging_id FROM intf_staging_txn WHERE"
                                        + " staging_id = ?",
                                Long.class,
                                corrected.stagingId()))
                .isEqualTo(second.stagingId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT remediation_status FROM interface_exception WHERE"
                                        + " staging_id = ?",
                                String.class,
                                second.stagingId()))
                .isEqualTo("REPROCESSED");
        assertThatThrownBy(() -> tx(() -> intake.stage(resend, record("IT-x", "IT-never-seen"))))
                .as("a resend must name a rejected key")
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> tx(() -> intake.stage(resend, record(" ", null))))
                .as("a record needs its source key")
                .isInstanceOf(FciException.class);
    }

    // SRS-INT-32: received != accepted + rejected is a FAILED window.
    @Test
    // BR-INT-009: the failure raises the Integration Failure Report at once, not at the next cycle.
    void SRS_INT_32_BR_INT_009_aWindowThatDoesNotBalanceIsFailedAndRaised() {
        long run = tx(() -> intake.open("HRMS_LEAVE_INFO", "IT-unbalanced", null, null));
        Completed done = tx(() -> intake.completeCounts(run, 10, 7, 2, "IT pull"));
        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT failure_detail FROM interface_run WHERE run_id = ?",
                                String.class,
                                run))
                .contains("records lost");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM interface_exception WHERE run_id = ? AND"
                                        + " staging_id IS NULL",
                                run))
                .isEqualTo(1);
        // BMAD TR-060: the Integration Failure Report entry on X-05, one open per interface.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM payroll_exception WHERE exception_code ="
                                        + " 'INTEGRATION_FAILURE' AND field_name ="
                                        + " 'HRMS_LEAVE_INFO' AND exception_status = 'OPEN'"))
                .as("raised at once on X-05")
                .isEqualTo(1);
        // BMAD TR-062: accepted + rejected above received (counted twice) is Failed end to end.
        long over = tx(() -> intake.open("HRMS_LEAVE_INFO", "IT-over-counted", null, null));
        Completed twice = tx(() -> intake.completeCounts(over, 3, 3, 1, "IT"));
        assertThat(twice.status()).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT failure_detail FROM interface_run WHERE run_id = ?",
                                String.class,
                                over))
                .contains("Received 3", "accepted 3", "rejected 1");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM interface_exception WHERE run_id = ? AND"
                                        + " staging_id IS NULL",
                                over))
                .as("one window-level item")
                .isEqualTo(1);
        long ok = tx(() -> intake.open("HRMS_LEAVE_INFO", "IT-balanced", null, null));
        assertThat(tx(() -> intake.completeCounts(ok, 3, 3, 0, "IT pull")).status())
                .isEqualTo("OK");
        assertThatThrownBy(() -> tx(() -> intake.open("NO_SUCH_INTERFACE", "x", null, null)))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-404-INTERFACE");
    }

    // SRS-INT-6, SRS-INT-16, D23-3, D23-8
    @Test
    void SRS_INT_16_SRS_EH_9_aCallIsRefusedUnconfiguredRetriedAndPausedAtItsThreshold() {
        assertThatThrownBy(() -> calls.call("EPFO_ECR", () -> "never"))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-503-NOT-CONFIGURED");
        long tax =
                jdbc.queryForObject(
                        "SELECT interface_id FROM interface_registry WHERE interface_code ="
                                + " 'TAX_RETURN'",
                        Long.class);
        jdbc.update(
                "INSERT INTO interface_config (interface_id, retry_count, backoff_seconds,"
                    + " backoff_strategy, timeout_seconds, circuit_breaker_required,"
                    + " circuit_breaker_threshold, effective_from, is_confirmed) VALUES (?, 1, 2,"
                    + " 'FIXED', 1, 'Y', 2, ?, 'N')",
                tax,
                Date.valueOf(LocalDate.of(2020, 4, 1)));
        cacheChanged(jdbc, InterfaceSettingsService.REGION);
        try {
            assertThat(calls.call("TAX_RETURN", () -> "ack")).isEqualTo("ack");
            AtomicInteger attempts = new AtomicInteger();
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(
                                () ->
                                        calls.call(
                                                "TAX_RETURN",
                                                () -> {
                                                    attempts.incrementAndGet();
                                                    throw new IllegalStateException("IT down");
                                                }))
                        .isInstanceOf(FciException.class)
                        .hasMessage("INT-502-CALL-FAILED");
            }
            assertThat(attempts.get()).as("retry count 1: two attempts per call").isEqualTo(4);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT breaker_state FROM interface_breaker_state WHERE"
                                            + " interface_id = ?",
                                    String.class,
                                    tax))
                    .isEqualTo("OPEN");
            assertThatThrownBy(() -> calls.call("TAX_RETURN", () -> "ack"))
                    .as("the breaker refuses until its probe time")
                    .isInstanceOf(FciException.class)
                    .hasMessage("INT-503-CIRCUIT-OPEN");
            // At the probe time one call goes through and closes the breaker.
            jdbc.update(
                    "UPDATE interface_breaker_state SET next_probe_at = ? WHERE interface_id = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                    tax);
            assertThat(calls.call("TAX_RETURN", () -> "ack")).isEqualTo("ack");
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM interface_breaker_state WHERE"
                                            + " interface_id = ? AND breaker_state = 'CLOSED' AND"
                                            + " consecutive_failures = 0",
                                    tax))
                    .isEqualTo(1);
            // The time limit counts as a failure.
            assertThatThrownBy(
                            () ->
                                    calls.call(
                                            "TAX_RETURN",
                                            () -> {
                                                Thread.sleep(3_000);
                                                return "late";
                                            }))
                    .isInstanceOf(FciException.class)
                    .hasMessage("INT-502-CALL-FAILED");
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM interface_exception WHERE interface_id ="
                                            + " ? AND rejection_reason LIKE 'Call failed after 2"
                                            + " attempt(s)%'",
                                    tax))
                    .isGreaterThanOrEqualTo(3);
        } finally {
            jdbc.update("DELETE FROM interface_config WHERE interface_id = ?", tax);
            cacheChanged(jdbc, InterfaceSettingsService.REGION);
            jdbc.update("DELETE FROM interface_breaker_state WHERE interface_id = ?", tax);
        }
    }

    /**
     * SRS-INT-16 (US-GAP-345, the US-GAP-62 regression): every X-03 viewer reads each interface's
     * resilience parameters read-only from the VIEW list - retry count, back-off and its strategy,
     * timeout, breaker threshold, acknowledgement timeout, in force from and confirmed - while the
     * Contract read stays X-03 EDIT (the Administrator's, SRS-INT-33); an interface without
     * settings in force lists none (not configured).
     */
    @Test
    void SRS_INT_16_aViewerReadsTheResilienceParametersWhileTheContractStaysTheAdministrators()
            throws Exception {
        Session maker = maker();
        // The demo settings configure every seeded HRMS interface (V077_44): this registry row has
        // none in force. Test-only fixture, removed so that no other test counts it.
        jdbc.update(
                "INSERT INTO interface_registry (interface_code, interface_name,"
                        + " counterparty_system, direction) VALUES ('IT_NOT_CONFIGURED',"
                        + " 'Interface without settings', 'HRMS', 'INBOUND')");
        JsonNode list;
        try {
            list =
                    body(
                            mvc.perform(
                                            userGet(
                                                    maker,
                                                    "/v1/integration/interfaces"
                                                            + "?counterparty=HRMS&size=100"))
                                    .andReturn());
        } finally {
            jdbc.update(
                    "DELETE FROM interface_registry WHERE interface_code = 'IT_NOT_CONFIGURED'");
        }
        JsonNode bulk = null;
        JsonNode unconfigured = null;
        for (JsonNode row : list.get("items")) {
            if ("HRMS_BULK_RECONCILE".equals(row.get("code").asString())) {
                bulk = row;
            } else if (row.get("settings").isNull()) {
                unconfigured = row;
            }
        }
        assertThat(bulk).isNotNull();
        JsonNode settings = bulk.get("settings");
        assertThat(settings.get("interfaceCode").asString()).isEqualTo("HRMS_BULK_RECONCILE");
        // The values in force (demo V062_9: 2 retries 30 s apart FIXED, 30 s limit, breaker after
        // 5, acknowledgement in 300 s, from 2020-04-01, not confirmed), not merely present.
        assertThat(settings.get("retryCount").asInt()).isEqualTo(2);
        assertThat(settings.get("backoffSeconds").asInt()).isEqualTo(30);
        assertThat(settings.get("backoffStrategy").asString()).isEqualTo("FIXED");
        assertThat(settings.get("timeoutSeconds").asInt()).isEqualTo(30);
        assertThat(settings.get("circuitBreakerRequired").asBoolean()).isTrue();
        assertThat(settings.get("circuitBreakerThreshold").asInt()).isEqualTo(5);
        assertThat(settings.get("ackTimeoutSeconds").asInt()).isEqualTo(300);
        assertThat(settings.get("effectiveFrom").asString()).isEqualTo("2020-04-01");
        assertThat(settings.get("confirmed").asBoolean()).isFalse();
        assertThat(unconfigured).as("an interface not configured lists no settings").isNotNull();
        assertThat(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/integration/interfaces/HRMS_BULK_RECONCILE"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("the Contract stays X-03 EDIT")
                .isEqualTo(403);
    }

    // SRS-INT-33: X-03 for its viewers; the actions are the Administrator's.
    // SC-23-0001 (a specification row per interface, none signed off), SC-23-0038 (no NPS CRA).
    @Test
    void SRS_INT_33_x03ReadsForItsViewersAndActsOnlyForTheAdministrator() throws Exception {
        Session maker = maker();
        JsonNode list =
                body(
                        mvc.perform(userGet(maker, "/v1/integration/interfaces?size=100"))
                                .andReturn());
        assertThat(list.get("total").asLong()).isGreaterThanOrEqualTo(36);
        JsonNode bulk = null;
        for (JsonNode row : list.get("items")) {
            if ("HRMS_BULK_RECONCILE".equals(row.get("code").asString())) {
                bulk = row;
            }
        }
        assertThat(bulk).isNotNull();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM interface_registry WHERE interface_code LIKE"
                                        + " '%NPS%' OR interface_name LIKE '%NPS%' OR"
                                        + " interface_name LIKE '%nnuity%'"))
                .as("SRS-INT-41: no NPS CRA or annuity provider interface")
                .isZero();
        assertThat(bulk.get("settings").get("retryCount").asInt()).isEqualTo(2);
        assertThat(bulk.get("specSignedOff").asBoolean()).isFalse();
        // SRS-INT-33 (US-GAP-62): the contract is the Administrator's alone, each use audited.
        assertThat(
                        mvc.perform(userGet(maker, "/v1/integration/interfaces/EPFO_ECR"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        Session admin = admin();
        long audited =
                count(
                        "SELECT COUNT(*) FROM audit_event WHERE entity_name = 'interface_registry'"
                                + " AND entity_key = 'EPFO_ECR' AND field_name = 'contract'");
        JsonNode epfo =
                body(
                        mvc.perform(userGet(admin, "/v1/integration/interfaces/EPFO_ECR"))
                                .andReturn());
        assertThat(epfo.get("row").get("settings").isNull()).isTrue();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'interface_registry' AND entity_key = 'EPFO_ECR' AND"
                                        + " field_name = 'contract'"))
                .isEqualTo(audited + 1);
        assertThat(
                        mvc.perform(userGet(admin, "/v1/integration/interfaces/NO_SUCH"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        JsonNode summary = body(mvc.perform(userGet(maker, "/v1/integration/summary")).andReturn());
        assertThat(summary.get("notConfigured").asLong()).isGreaterThan(0);
        assertThat(
                        body(mvc.perform(
                                                userGet(
                                                        maker,
                                                        "/v1/integration/windows?interface=HRMS_EMP_OTA"))
                                        .andReturn())
                                .get("total")
                                .asLong())
                .isGreaterThanOrEqualTo(1);
        // FRS V0.5 Integration Audit Trail: the Payroll Maker exports the envelope log.
        String trail =
                csv(maker, "/v1/integration/windows/export?format=CSV&interface=HRMS_EMP_OTA");
        assertThat(trail)
                .startsWith("\"run_id\",\"interface\",\"direction\",\"batch_reference\"")
                .contains("\"HRMS_EMP_OTA\"");
        assertThat(download(admin, "/v1/integration/windows/export").getResponse().getStatus())
                .as("the Administrator views; the Payroll Maker exports")
                .isEqualTo(403);
        assertThat(
                        mvc.perform(
                                        userGet(
                                                login(
                                                        "hr.delhi@fci-demo.test",
                                                        "ESTABLISHMENT_HR",
                                                        "DH001"),
                                                "/v1/integration/interfaces"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("a role without X-03")
                .isEqualTo(403);

        long demo =
                jdbc.queryForObject(
                        "SELECT e.exception_id FROM interface_exception e JOIN intf_staging_txn s"
                                + " ON s.staging_id = e.staging_id WHERE s.request_id ="
                                + " 'DEMO-OTA-0002'",
                        Long.class);
        Map<String, String> reason = Map.of("reason", "Corrected at source (IT)");
        assertThat(status(maker, "/v1/integration/exceptions/" + demo + "/write-off", reason))
                .as("a viewer cannot write off")
                .isEqualTo(403);
        assertThat(
                        status(
                                maker,
                                "/v1/integration/interfaces/HRMS_EMP_OTA/test-connection",
                                Map.of()))
                .isEqualTo(403);

        assertThat(
                        status(
                                admin,
                                "/v1/integration/exceptions/" + demo + "/write-off",
                                Map.of("reason", " ")))
                .as("the reason is mandatory")
                .isEqualTo(400);
        MvcResult refused =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/integration/exceptions/" + demo + "/reprocess",
                                        reason))
                        .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(refused).get("errorCode").asString()).isEqualTo("INT-409-NO-ADAPTER");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'interface_exception' AND entity_key = ? AND"
                                        + " new_value = 'NO_ADAPTER'",
                                String.valueOf(demo)))
                .as("a refused reprocess is audited")
                .isGreaterThanOrEqualTo(1);
        long connectionAudits = connectionAudits("HRMS_EMP_OTA", "NO_ADAPTER");
        JsonNode tested =
                body(
                        mvc.perform(
                                        userPost(
                                                admin,
                                                "/v1/integration/interfaces/HRMS_EMP_OTA/test-connection",
                                                Map.of()))
                                .andReturn());
        assertThat(tested.get("outcome").asString()).isEqualTo("NO_ADAPTER");
        // BMAD TR-061: every Test connection is audited with the interface and the outcome.
        assertThat(connectionAudits("HRMS_EMP_OTA", "NO_ADAPTER")).isEqualTo(connectionAudits + 1);

        // BMAD TR-061: a fresh rejected record per run (no reliance on the demo item still open).
        String key = "IT-" + UUID.randomUUID();
        long run = tx(() -> intake.open("HRMS_EMP_OTA", "IT-write-off", null, null));
        Staged staged =
                tx(
                        () ->
                                intake.stage(
                                        run,
                                        new Inbound(
                                                "EMP_OTA", key, key, null, "{\"it\":true}", null)));
        tx(
                () -> {
                    intake.rejected(staged.stagingId(), "OTA hours above the cap (IT)");
                    return null;
                });
        long fresh =
                jdbc.queryForObject(
                        "SELECT exception_id FROM interface_exception WHERE staging_id = ?",
                        Long.class,
                        staged.stagingId());
        // BMAD TR-063 (SRS-INT-31): a reprocess without a reason, or a blank one, is refused; the
        // item stays open and nothing is audited for it.
        assertThat(
                        status(
                                admin,
                                "/v1/integration/exceptions/" + fresh + "/reprocess",
                                Map.of("reason", " ")))
                .as("a blank reason")
                .isEqualTo(400);
        assertThat(status(admin, "/v1/integration/exceptions/" + fresh + "/reprocess", Map.of()))
                .as("no reason")
                .isEqualTo(400);
        assertThat(remediation(fresh)).isEqualTo("OPEN");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'interface_exception' AND entity_key = ?",
                                String.valueOf(fresh)))
                .isZero();

        JsonNode off =
                body(
                        mvc.perform(
                                        userPost(
                                                admin,
                                                "/v1/integration/exceptions/"
                                                        + fresh
                                                        + "/write-off",
                                                reason))
                                .andReturn());
        assertThat(off.get("status").asString()).isEqualTo("WRITTEN_OFF");
        assertThat(off.get("writtenOffBy").asString()).isEqualTo("sysadmin@fci-demo.test");
        assertThat(remediation(fresh)).isEqualTo("WRITTEN_OFF");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'interface_exception' AND entity_key = ? AND"
                                        + " field_name = 'write_off' AND new_value ="
                                        + " 'WRITTEN_OFF' AND reason = ? AND changed_by ="
                                        + " 'sysadmin@fci-demo.test'",
                                String.valueOf(fresh),
                                "Corrected at source (IT)"))
                .as("the write-off is audited with its reason and user")
                .isEqualTo(1);
        assertThat(status(admin, "/v1/integration/exceptions/" + fresh + "/write-off", reason))
                .as("a resolved exception is not changed again")
                .isEqualTo(409);
    }

    private String remediation(long exceptionId) {
        return jdbc.queryForObject(
                "SELECT remediation_status FROM interface_exception WHERE exception_id = ?",
                String.class,
                exceptionId);
    }

    private long connectionAudits(String code, String outcome) {
        return count(
                "SELECT COUNT(*) FROM audit_event WHERE entity_name = 'interface_registry' AND"
                        + " entity_key = ? AND field_name = 'test_connection' AND new_value = ?"
                        + " AND changed_by = 'sysadmin@fci-demo.test'",
                code,
                outcome);
    }

    private int status(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn().getResponse().getStatus();
    }

    // SRS-INT-31, SRS-INT-33 through an adapter (the IT stand-in of ApiTestSupport.ADAPTED)
    @Test
    void SRS_INT_31_theAdministratorReprocessesThroughTheAdapterAuditedEitherWay()
            throws Exception {
        String key = "IT-" + UUID.randomUUID();
        long run = tx(() -> intake.open(ADAPTED, "IT-adapter", null, null));
        Staged staged =
                tx(
                        () ->
                                intake.stage(
                                        run,
                                        new Inbound(
                                                "LTC_ENCASH",
                                                key,
                                                key,
                                                null,
                                                "{\"demo\":true}",
                                                null)));
        tx(
                () -> {
                    intake.rejected(staged.stagingId(), "Encashment days above the balance (IT)");
                    return null;
                });
        long item =
                jdbc.queryForObject(
                        "SELECT exception_id FROM interface_exception WHERE staging_id = ?",
                        Long.class,
                        staged.stagingId());
        Session admin = admin();
        JsonNode queue =
                body(
                        mvc.perform(
                                        userGet(
                                                admin,
                                                "/v1/integration/exceptions?interface=" + ADAPTED))
                                .andReturn());
        assertThat(queue.get("items").get(0).get("adapter").asBoolean()).isTrue();
        Map<String, String> reason = Map.of("reason", "Corrected at source (IT)");
        try {
            ADAPTER.put("reprocess", HRMS_DOWN);
            MvcResult failed =
                    mvc.perform(
                                    userPost(
                                            admin,
                                            "/v1/integration/exceptions/" + item + "/reprocess",
                                            reason))
                            .andReturn();
            assertThat(failed.getResponse().getStatus()).isEqualTo(502);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'interface_exception' AND entity_key = ? AND new_value"
                                        + " LIKE 'FAILED:%'",
                                    String.valueOf(item)))
                    .as("a failed reprocess is audited with its outcome")
                    .isEqualTo(1);
            ADAPTER.remove("reprocess");
            JsonNode done =
                    body(
                            mvc.perform(
                                            userPost(
                                                    admin,
                                                    "/v1/integration/exceptions/"
                                                            + item
                                                            + "/reprocess",
                                                    reason))
                                    .andReturn());
            assertThat(done.get("status").asString()).isEqualTo("REPROCESSED");
            // BMAD TR-061: a successful reprocess is audited with its outcome and reason.
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'interface_exception' AND entity_key = ? AND new_value"
                                        + " = 'REPROCESSED' AND reason = 'Corrected at source"
                                        + " (IT)'",
                                    String.valueOf(item)))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT staging_status FROM intf_staging_txn WHERE staging_id ="
                                            + " ?",
                                    String.class,
                                    staged.stagingId()))
                    .isEqualTo("REPROCESSED");
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM payroll_exception WHERE exception_code ="
                                            + " 'INTEGRATION_FAILURE' AND field_name = ? AND"
                                            + " exception_status = 'OPEN'",
                                    ADAPTED))
                    .as("no open item left: the X-05 entry is settled")
                    .isZero();

            ADAPTER.put("test", "REACHABLE in 12 ms");
            long reachable = connectionAudits(ADAPTED, "REACHABLE in 12 ms");
            assertThat(testConnection(admin).get("outcome").asString())
                    .isEqualTo("REACHABLE in 12 ms");
            assertThat(connectionAudits(ADAPTED, "REACHABLE in 12 ms"))
                    .as("BMAD TR-061: a reachable test is audited")
                    .isEqualTo(reachable + 1);
            ADAPTER.put("test", HRMS_DOWN);
            String down = "FAILED: IllegalStateException: adapter down";
            long failedTests = connectionAudits(ADAPTED, down);
            assertThat(testConnection(admin).get("outcome").asString()).isEqualTo(down);
            assertThat(connectionAudits(ADAPTED, down))
                    .as("BMAD TR-061: a failed test is audited with its outcome")
                    .isEqualTo(failedTests + 1);
        } finally {
            ADAPTER.clear();
        }

        // A window-level item has no record to reprocess, adapter or not.
        long window = tx(() -> intake.open(ADAPTED, "IT-window", null, null));
        tx(() -> intake.completeCounts(window, 2, 1, 0, "IT"));
        long windowItem =
                jdbc.queryForObject(
                        "SELECT exception_id FROM interface_exception WHERE run_id = ?",
                        Long.class,
                        window);
        assertThat(status(admin, "/v1/integration/exceptions/" + windowItem + "/reprocess", reason))
                .isEqualTo(409);
        assertThat(status(admin, "/v1/integration/exceptions/999999999/write-off", reason))
                .as("an unknown item")
                .isEqualTo(409);
    }

    // SRS-INT-6: a probe that fails opens the breaker again.
    @Test
    void SRS_INT_6_aFailedProbeOpensTheBreakerAgain() {
        long epfo =
                jdbc.queryForObject(
                        "SELECT interface_id FROM interface_registry WHERE interface_code ="
                                + " 'EPFO_ECR'",
                        Long.class);
        jdbc.update(
                "INSERT INTO interface_config (interface_id, retry_count, backoff_seconds,"
                    + " backoff_strategy, timeout_seconds, circuit_breaker_required,"
                    + " circuit_breaker_threshold, effective_from, is_confirmed) VALUES (?, 3, 1,"
                    + " 'EXPONENTIAL', 1, 'N', NULL, ?, 'N')",
                epfo,
                Date.valueOf(LocalDate.of(2020, 4, 1)));
        cacheChanged(jdbc, InterfaceSettingsService.REGION);
        try {
            jdbc.update(
                    "INSERT INTO interface_breaker_state (interface_id, breaker_state,"
                            + " consecutive_failures, opened_at, next_probe_at, updated_at) VALUES"
                            + " (?, 'OPEN', 4, ?, ?, ?)",
                    epfo,
                    java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                    java.sql.Timestamp.from(Instant.now()));
            AtomicInteger attempts = new AtomicInteger();
            assertThatThrownBy(
                            () ->
                                    calls.call(
                                            "EPFO_ECR",
                                            () -> {
                                                attempts.incrementAndGet();
                                                throw new IllegalStateException("IT still down");
                                            }))
                    .isInstanceOf(FciException.class)
                    .hasMessage("INT-502-CALL-FAILED");
            assertThat(attempts.get()).as("the probe is one attempt, no retries").isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT breaker_state FROM interface_breaker_state WHERE"
                                            + " interface_id = ?",
                                    String.class,
                                    epfo))
                    .as("no breaker required, but a failed probe re-opens it")
                    .isEqualTo("OPEN");
        } finally {
            jdbc.update("DELETE FROM interface_config WHERE interface_id = ?", epfo);
            cacheChanged(jdbc, InterfaceSettingsService.REGION);
            jdbc.update("DELETE FROM interface_breaker_state WHERE interface_id = ?", epfo);
        }
    }

    /**
     * SRS-INT-6 (l.5152; US-GAP-351): an open breaker whose probe time has come lets the call
     * through as the probe; a probe that answers closes the breaker and clears the failure count,
     * and the next call runs normally.
     */
    @Test
    void SRS_INT_6_aSuccessfulProbeClosesTheBreaker() {
        long epfo =
                jdbc.queryForObject(
                        "SELECT interface_id FROM interface_registry WHERE interface_code ="
                                + " 'EPFO_ECR'",
                        Long.class);
        jdbc.update(
                "INSERT INTO interface_config (interface_id, retry_count, backoff_seconds,"
                    + " backoff_strategy, timeout_seconds, circuit_breaker_required,"
                    + " circuit_breaker_threshold, effective_from, is_confirmed) VALUES (?, 2, 1,"
                    + " 'FIXED', 1, 'Y', 3, ?, 'N')",
                epfo,
                Date.valueOf(LocalDate.of(2020, 4, 1)));
        cacheChanged(jdbc, InterfaceSettingsService.REGION);
        try {
            jdbc.update(
                    "INSERT INTO interface_breaker_state (interface_id, breaker_state,"
                            + " consecutive_failures, opened_at, next_probe_at, updated_at) VALUES"
                            + " (?, 'OPEN', 3, ?, ?, ?)",
                    epfo,
                    java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                    java.sql.Timestamp.from(Instant.now()));
            AtomicInteger attempts = new AtomicInteger();
            assertThat(
                            calls.call(
                                    "EPFO_ECR",
                                    () -> {
                                        attempts.incrementAndGet();
                                        return "ack";
                                    }))
                    .isEqualTo("ack");
            assertThat(attempts.get()).as("the probe is one attempt").isEqualTo(1);
            Map<String, Object> state =
                    jdbc.queryForMap(
                            "SELECT breaker_state, consecutive_failures, next_probe_at FROM"
                                    + " interface_breaker_state WHERE interface_id = ?",
                            epfo);
            assertThat(state.get("BREAKER_STATE")).isEqualTo("CLOSED");
            assertThat(((Number) state.get("CONSECUTIVE_FAILURES")).intValue()).isZero();
            assertThat(state.get("NEXT_PROBE_AT")).isNull();
            assertThat(calls.call("EPFO_ECR", () -> "next"))
                    .as("a closed breaker lets the next call through")
                    .isEqualTo("next");
        } finally {
            jdbc.update("DELETE FROM interface_config WHERE interface_id = ?", epfo);
            cacheChanged(jdbc, InterfaceSettingsService.REGION);
            jdbc.update("DELETE FROM interface_breaker_state WHERE interface_id = ?", epfo);
        }
    }

    /**
     * SRS-INT-5 (l.5148; BR-INT-004): an inbound record is de-duplicated on its source key (for
     * HRMS, the HRMS Request ID) against the staging table, also when HRMS replays it in a later
     * exchange window: the replay is reported DUPLICATE against the first staging row, which is
     * never staged twice.
     */
    @Test
    void SRS_INT_5_aReplayedHrmsRequestIdIsDeduplicatedInALaterWindow() {
        String key = "IT-REPLAY-" + UUID.randomUUID();
        long first = tx(() -> intake.open(HRMS, "IT-replay-1", null, null));
        Staged staged = tx(() -> intake.stage(first, record(key, null)));
        assertThat(staged.outcome()).isEqualTo(Outcome.STAGED);
        long later = tx(() -> intake.open(HRMS, "IT-replay-2", null, null));
        Staged replay = tx(() -> intake.stage(later, record(key, null)));
        assertThat(replay).isEqualTo(new Staged(staged.stagingId(), Outcome.DUPLICATE));
        assertThat(count("SELECT COUNT(*) FROM intf_staging_txn WHERE hrms_request_id = ?", key))
                .as("never staged twice")
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM intf_staging_event WHERE staging_id = ? AND"
                                        + " event_type = 'DUPLICATE_REJECTED'",
                                staged.stagingId()))
                .isEqualTo(1);
    }

    private JsonNode testConnection(Session admin) throws Exception {
        return body(
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/integration/interfaces/"
                                                + ADAPTED
                                                + "/test-connection",
                                        Map.of()))
                        .andReturn());
    }
}
