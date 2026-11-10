package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleEngine.Context;
import in.gov.fci.payroll.configuration.api.RuleVersionChanged;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * M-08 Rule / Parameter Umbrella Catalogue end to end (docs/plan/06-rule-engine.md phase 06c):
 * access per SRS-19-14, the Rule Administrator → Rule Approver route with no self-approval
 * (SRS-19-4, KB RE-5), effective dating without overlap (SRS-19-12, RE-1, RE-2), the value each
 * rule type takes (SRS-19-1, SRS-19-5, SRS-19-15), the mandatory Active circular (SRS-19-17), an
 * end date without successor (SRS-19-6, RE-4) and the pending view across M-08 / M-14 / M-15
 * (SRS-19-21, RE-14). Rules are created per test with unique keys, so nothing seeded is touched.
 */
@RecordApplicationEvents
class RuleCatalogueIT extends ApiTestSupport {

    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<< >>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired private RuleEngine engine;
    @Autowired private ApplicationEvents events;
    @Autowired private SystemParameters settings;

    private LocalDate today;

    @BeforeEach
    void day() {
        today = settings.today();
    }

    // ---------------------------------------------------------------- sessions

    private Session ruleAdmin() throws Exception {
        return login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
    }

    /** The same person in the Rule Approver role: may never approve the own proposal. */
    private Session ruleAdminAsApprover() throws Exception {
        return login("su.hq@fci-demo.test", "RULE_APPROVER", "HQ001");
    }

    private Session ruleApprover() throws Exception {
        return login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
    }

    private Session hqChecker() throws Exception {
        return login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
    }

    private Session finance() throws Exception {
        return login("finance.mumbai@fci-demo.test", "FINANCE", "MR001");
    }

    private Session delhiMaker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session delhiChecker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "RO-DL");
    }

    // ---------------------------------------------------------------- fixtures

    private static String unique(String prefix) {
        return prefix + "." + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private long rule(String key, String type, String domain) {
        return rule(key, type, domain, null, true);
    }

    private long rule(String key, String type, String domain, Long office, boolean active) {
        jdbc.update(
                "INSERT INTO rule_definition (rule_key, rule_type_code, rule_domain, rule_name,"
                        + " org_scope_location_id, owning_module, order_required, active_flag)"
                        + " VALUES (?, ?, ?, ?, ?, 'RULES', 'N', ?)",
                key,
                type,
                domain,
                "IT " + key,
                office,
                active ? "Y" : "N");
        rulesChanged();
        return jdbc.queryForObject(
                "SELECT rule_id FROM rule_definition WHERE rule_key = ?", Long.class, key);
    }

    /** A row as a migration may leave it (M-08 itself stores approved versions only, D-7). */
    private void legacyVersion(long ruleId, int no, LocalDate from, String state) {
        jdbc.update(
                "INSERT INTO rule_version (rule_id, version_no, effective_from, scalar_value,"
                    + " approval_state, is_blocked, created_by) VALUES (?, ?, ?, 1, ?, 'N', 'it')",
                ruleId,
                no,
                from,
                state);
        rulesChanged();
    }

    /** An approved version written as the go-live seeding writes it (RE-12). */
    private void approvedVersion(long ruleId, int no, LocalDate from, LocalDate to, String value) {
        jdbc.update(
                "INSERT INTO rule_version (rule_id, version_no, effective_from, effective_to,"
                        + " scalar_value, approval_state, is_blocked, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, 'APPROVED', 'N', 'it')",
                ruleId,
                no,
                from,
                to,
                new BigDecimal(value));
        rulesChanged();
    }

    /** An Active circular: recorded by the Delhi maker, approved by the Delhi checker. */
    private long activeCircular() throws Exception {
        Map<String, Object> c = new HashMap<>();
        c.put("number", unique("IT/RULE"));
        c.put("date", "2026-10-01");
        c.put("issuingAuthority", "HQ_PERSONNEL");
        c.put("subject", "IT: revision of a rule");
        c.put("category", "LABOUR_NORM");
        c.put("payrollTypes", List.of("STAFF"));
        c.put("appliesAllOffices", true);
        c.put("effectiveFrom", "2026-10-01");
        c.put("reason", "IT: new order");
        MvcResult r =
                mvc.perform(
                                userRequest(
                                                delhiMaker(),
                                                multipart("/v1/masters/circulars")
                                                        .file(
                                                                new MockMultipartFile(
                                                                        "file",
                                                                        "order.pdf",
                                                                        "application/pdf",
                                                                        PDF))
                                                        .file(
                                                                new MockMultipartFile(
                                                                        "circular",
                                                                        "",
                                                                        MediaType
                                                                                .APPLICATION_JSON_VALUE,
                                                                        json.writeValueAsBytes(c))))
                                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                        .andReturn();
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(
                        decide(
                                delhiChecker(),
                                body(r).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        return body(r).get("id").asLong();
    }

    private static Map<String, Object> scalar(LocalDate from, Object value, long circular) {
        Map<String, Object> b = new HashMap<>();
        b.put("effectiveFrom", from.toString());
        b.put("value", value);
        b.put("circularId", circular);
        b.put("reason", "IT: as per the order");
        return b;
    }

    private static Map<String, Object> formula(LocalDate from, String formula, long circular) {
        Map<String, Object> b = new HashMap<>();
        b.put("effectiveFrom", from.toString());
        b.put("formula", formula);
        b.put("circularId", circular);
        b.put("reason", "IT: as per the order");
        return b;
    }

    private static Map<String, Object> band(int order, Object from, Object to, Object rate) {
        Map<String, Object> b = new HashMap<>();
        b.put("order", order);
        b.put("from", from);
        b.put("to", to);
        b.put("rateOrAmount", rate);
        return b;
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private MvcResult getRaw(Session s, String path) throws Exception {
        return mvc.perform(userGet(s, path)).andReturn();
    }

    private JsonNode get(Session s, String path) throws Exception {
        MvcResult r = getRaw(s, path);
        assertThat(status(r)).as(path).isEqualTo(200);
        return pagedOrWhole(s, path, body(r));
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private JsonNode row(Session s, String key) throws Exception {
        for (JsonNode r : get(s, "/v1/rules").get("rules")) {
            if (key.equals(r.get("key").asString())) {
                return r;
            }
        }
        throw new AssertionError(key + " not listed");
    }

    private long proposeOk(Session s, String key, Map<String, Object> body) throws Exception {
        MvcResult r = post(s, "/v1/rules/" + key + "/versions", body);
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(202);
        return body(r).get("approvalId").asLong();
    }

    // ---------------------------------------------------------------- SRS-19-14 access

    @Test
    void SRS_19_14_theCheckerAndFinanceReadTheCatalogueAndTheMakerAndHrHaveNoAccess()
            throws Exception {
        String key = unique("IT.ACCESS.RATE");
        rule(key, "RATE", "COMPUTATION");
        long circular = activeCircular();
        // No access: Payroll Maker and Establishment / HR, on every endpoint.
        for (Session none : List.of(delhiMaker(), hr())) {
            for (String path :
                    List.of(
                            "/v1/rules",
                            "/v1/rules/pending",
                            "/v1/rules/vocabulary",
                            "/v1/rules/" + key + "/versions")) {
                assertThat(status(getRaw(none, path)))
                        .as(none.loginId() + " " + path)
                        .isEqualTo(403);
            }
            assertThat(
                            status(
                                    post(
                                            none,
                                            "/v1/rules/" + key + "/versions",
                                            scalar(today, 1, circular))))
                    .isEqualTo(403);
        }
        // Read only: the Payroll Checker and Finance read, and never propose or end.
        for (Session reader : List.of(hqChecker(), finance())) {
            assertThat(row(reader, key).get("type").asString()).isEqualTo("RATE");
            assertThat(get(reader, "/v1/rules/vocabulary").get("functions").has("ROUND")).isTrue();
            assertThat(
                            status(
                                    post(
                                            reader,
                                            "/v1/rules/" + key + "/versions",
                                            scalar(today, 1, circular))))
                    .isEqualTo(403);
            Map<String, Object> end = new HashMap<>();
            end.put("endOn", today.toString());
            end.put("reason", "IT");
            assertThat(status(post(reader, "/v1/rules/" + key + "/end", end))).isEqualTo(403);
        }
        // The Rule Approver reads, and never proposes (no CREATE / EDIT on M-08).
        assertThat(row(ruleApprover(), key).get("maintained").asBoolean()).isTrue();
        assertThat(
                        status(
                                post(
                                        ruleApprover(),
                                        "/v1/rules/" + key + "/versions",
                                        scalar(today, 1, circular))))
                .isEqualTo(403);
    }

    @Test
    void SRS_19_1_aKeyM08DoesNotListAnswersNotFoundAndATamperedKeyIsRefused() throws Exception {
        Session admin = ruleAdmin();
        long circular = activeCircular();
        // Unknown, an A-07 setting of the SECURITY domain, or a SETTING rule of the COMPUTATION
        // domain (A-07's payroll settings): not found, alike, so nothing reveals which exists.
        String security =
                jdbc.queryForObject(
                        "SELECT MIN(rule_key) FROM rule_definition WHERE rule_type_code ="
                                + " 'SETTING' AND rule_domain = 'SECURITY'",
                        String.class);
        String payrollSetting = unique("IT.PAYROLL.SETTING");
        rule(payrollSetting, "SETTING", "COMPUTATION");
        assertThat(security).isNotNull();
        for (String key : List.of(unique("IT.NOWHERE"), security, payrollSetting)) {
            assertThat(status(getRaw(admin, "/v1/rules/" + key + "/versions")))
                    .as(key)
                    .isEqualTo(404);
            assertThat(
                            status(
                                    post(
                                            admin,
                                            "/v1/rules/" + key + "/versions",
                                            scalar(today, 1, circular))))
                    .as(key)
                    .isEqualTo(404);
        }
        assertThat(texts(get(admin, "/v1/rules").get("rules"), "type")).doesNotContain("SETTING");
        // A key outside the pattern never reaches the service.
        for (String tampered : List.of("..%2F..%2Fetc", "A%20B", "%3Cscript%3E")) {
            assertThat(status(getRaw(admin, "/v1/rules/" + tampered + "/versions")))
                    .as(tampered)
                    .isIn(400, 404);
        }
    }

    // ---------------------------------------------------------------- SRS-19-4 route

    @Test
    void SRS_19_4_aVersionIsInForceOnlyOnceADifferentRuleApproverApprovesItFromItsStatedDate()
            throws Exception {
        String key = unique("IT.DA.RATE");
        rule(key, "RATE", "COMPUTATION");
        long circular = activeCircular();
        Session admin = ruleAdmin();
        // The first version of a rule that has none: not configured until then (R-RULE).
        assertThat(row(admin, key).get("inForce").isNull()).isTrue();
        LocalDate back = today.minusDays(20);
        long approval = proposeOk(admin, key, scalar(back, "53.00", circular));
        JsonNode pending = row(admin, key).get("pending");
        assertThat(pending.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.get("versionNo").asInt()).isEqualTo(1);
        assertThat(pending.get("value").decimalValue()).isEqualByComparingTo("53");
        assertThat(pending.get("approvalId").asLong()).isEqualTo(approval);
        // Nothing is written before the decision (plan 06 D-7), and nothing is in force.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rule_version v JOIN rule_definition d ON"
                                        + " d.rule_id = v.rule_id WHERE d.rule_key = ?",
                                Integer.class,
                                key))
                .isZero();
        // One pending change per rule.
        MvcResult second =
                post(admin, "/v1/rules/" + key + "/versions", scalar(today, "54", circular));
        assertThat(status(second)).isEqualTo(409);
        assertThat(error(second)).isEqualTo("WF-409-PENDING");
        // No self-approval (SRS-G-1, RE-5): the proposer holding Rule Approver too is refused.
        assertThat(decide(ruleAdminAsApprover(), approval, "APPROVE", null, null)).isEqualTo(403);
        // One session per user: signing in as Rule Approver ended the Rule Administrator session.
        assertThat(status(getRaw(admin, "/v1/rules"))).isEqualTo(401);
        admin = ruleAdmin();
        // Not the Payroll Checker either: the route names the Rule Approver.
        assertThat(decide(hqChecker(), approval, "APPROVE", null, null)).isEqualTo(403);
        events.clear();
        assertThat(decide(ruleApprover(), approval, "APPROVE", null, null)).isEqualTo(200);
        // In force from its stated, back-dated start (SC-06-0049), not from the approval day.
        JsonNode inForce = row(admin, key).get("inForce");
        assertThat(inForce.get("effectiveFrom").asString()).isEqualTo(back.toString());
        assertThat(inForce.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(inForce.get("createdBy").asString()).isEqualTo("su.hq@fci-demo.test");
        assertThat(engine.resolve(key, today, Context.unscoped()))
                .hasValueSatisfying(r -> assertThat(r.scalar()).isEqualByComparingTo("53"));
        // The circular is linked (SRS-19-17) and the change audited in the approval (SRS-20-2).
        long versionId = inForce.get("id").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM circular_order_link WHERE circular_order_id ="
                                        + " ? AND link_type = 'RULE_VERSION' AND linked_key = ? AND"
                                        + " screen_code = 'M-08'",
                                Integer.class,
                                circular,
                                String.valueOf(versionId)))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT new_value FROM audit_event WHERE entity_name ="
                                        + " 'rule_version' AND entity_key = ? AND field_name ="
                                        + " 'scalar_value' AND approval_id = ?",
                                String.class,
                                key,
                                approval))
                .isEqualTo("53");
        // The arrear engine's hook: a back-dated approval is retrospective (SRS-19-16).
        assertThat(events.stream(RuleVersionChanged.class))
                .anySatisfy(
                        e -> {
                            assertThat(e.ruleKey()).isEqualTo(key);
                            assertThat(e.kind()).isEqualTo(RuleVersionChanged.Kind.APPROVED);
                            assertThat(e.retrospective()).isTrue();
                            assertThat(e.versionNo()).isEqualTo(1);
                        });
        // History button / X-04: the M-08 record names its screen.
        JsonNode history = get(admin, "/v1/audit/history?entity=rule_version&key=" + key);
        assertThat(history.toString()).contains("53");
    }

    @Test
    void SRS_19_11_aReturnedProposalGoesBackToTheMakerWithItsReasonAndSimulationAndNoVersionRow()
            throws Exception {
        String key = unique("IT.CEILING");
        rule(key, "CEILING", "COMPUTATION");
        long circular = activeCircular();
        Session admin = ruleAdmin();
        LocalDate from = today.plusDays(30);
        long first = proposeOk(admin, key, scalar(from, "18000", circular));
        MvcResult simulated = post(admin, "/v1/rules/" + key + "/simulations", Map.of());
        assertThat(status(simulated))
                .as(simulated.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(decide(ruleApprover(), first, "RETURN", "IT: wrong amount", null))
                .isEqualTo(200);
        assertThat(row(admin, key).get("pending").isNull()).isTrue();
        // FRS §24.4 Step 3: returned to the Maker with the reason; the proposal is kept in its
        // approval item (plan 06 D-7) and the simulation with the proposal it tested (§24.9).
        Map<String, Object> item =
                jdbc.queryForMap(
                        "SELECT status, return_reason, submitted_by FROM approval_queue WHERE"
                                + " approval_id = ?",
                        first);
        assertThat(item.get("STATUS")).isEqualTo("RETURNED");
        assertThat(item.get("RETURN_REASON")).isEqualTo("IT: wrong amount");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                first))
                .contains("18000");
        assertThat(item.get("SUBMITTED_BY")).isEqualTo("su.hq@fci-demo.test");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rule_simulation WHERE simulation_id = ? AND"
                                        + " population_scope LIKE ?",
                                Integer.class,
                                body(simulated).get("id").asLong(),
                                "M08;" + first + ";" + key + ";%"))
                .isEqualTo(1);
        // No rule_version row: no Draft row is stored for an undecided proposal.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rule_version v JOIN rule_definition d ON"
                                        + " d.rule_id = v.rule_id WHERE d.rule_key = ?",
                                Integer.class,
                                key))
                .isZero();
        // uk_rule_version_eff would block the date for ever had the proposal been stored.
        long again = proposeOk(admin, key, scalar(from, "21000", circular));
        assertThat(decide(ruleApprover(), again, "APPROVE", null, null)).isEqualTo(200);
        JsonNode versions = get(admin, "/v1/rules/" + key + "/versions");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("status").asString()).isEqualTo("APPROVED_FUTURE");
        assertThat(versions.get(0).get("value").decimalValue()).isEqualByComparingTo("21000");
    }

    // ---------------------------------------------------------------- SRS-19-12 dates

    @Test
    void SRS_19_12_aSupersedingVersionEndsItsPredecessorAndAConflictingDateNamesTheVersion()
            throws Exception {
        String key = unique("IT.CPF.RATE");
        long id = rule(key, "RATE", "COMPUTATION");
        LocalDate first = today.minusDays(60);
        approvedVersion(id, 1, first, null, "12");
        long circular = activeCircular();
        Session admin = ruleAdmin();
        // On or before the start of the version in force: refused, naming version 1 and its start.
        for (LocalDate from : List.of(first, first.minusDays(1))) {
            MvcResult r =
                    post(admin, "/v1/rules/" + key + "/versions", scalar(from, "13", circular));
            assertThat(status(r)).as(from.toString()).isEqualTo(409);
            assertThat(error(r)).isEqualTo("RULE-409-OVERLAP");
            assertThat(body(r).get("args").get(0).asInt()).isEqualTo(1);
            assertThat(body(r).get("args").get(1).asString()).isEqualTo(first.toString());
        }
        // An effective-to before the effective-from (SRS-19-6).
        Map<String, Object> inverted = scalar(today, "13", circular);
        inverted.put("effectiveTo", today.minusDays(1).toString());
        MvcResult bad = post(admin, "/v1/rules/" + key + "/versions", inverted);
        assertThat(status(bad)).isEqualTo(422);
        assertThat(error(bad)).isEqualTo("RULE-422-VALUE");
        // The day after: accepted; on approval version 1 ends the day before.
        LocalDate second = today.minusDays(10);
        long approval = proposeOk(admin, key, scalar(second, "12.5", circular));
        assertThat(decide(ruleApprover(), approval, "APPROVE", null, null)).isEqualTo(200);
        JsonNode versions = get(admin, "/v1/rules/" + key + "/versions");
        assertThat(versions.get(0).get("versionNo").asInt()).isEqualTo(2);
        assertThat(versions.get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(versions.get(1).get("effectiveTo").asString())
                .isEqualTo(second.minusDays(1).toString());
        assertThat(versions.get(1).get("status").asString()).isEqualTo("SUPERSEDED");
        // FRS §24.9: the version records its author, its approver, its start and the version it
        // superseded; the seeded version 1 has no approval item and superseded none.
        assertThat(versions.get(0).get("createdBy").asString()).isEqualTo("su.hq@fci-demo.test");
        assertThat(versions.get(0).get("approvedBy").asString())
                .isEqualTo("rule.approver@fci-demo.test");
        assertThat(versions.get(0).get("effectiveFrom").asString()).isEqualTo(second.toString());
        assertThat(versions.get(0).get("supersedesVersionNo").asInt()).isEqualTo(1);
        assertThat(versions.get(1).get("approvedBy").isNull()).isTrue();
        assertThat(versions.get(1).get("supersedesVersionNo").isNull()).isTrue();
        assertThat(row(admin, key).get("inForce").get("supersedesVersionNo").asInt()).isEqualTo(1);
        // A closed period keeps the version effective then (SRS-19-13, RE-7).
        assertThat(engine.resolve(key, first.plusDays(1), Context.unscoped()))
                .hasValueSatisfying(r -> assertThat(r.versionNo()).isEqualTo(1));
        assertThat(engine.resolve(key, today, Context.unscoped()))
                .hasValueSatisfying(r -> assertThat(r.versionNo()).isEqualTo(2));
        // The END_DATE audit row of the version closed.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'rule_version' AND entity_key = ? AND change_type ="
                                        + " 'END_DATE' AND approval_id = ?",
                                Integer.class,
                                key,
                                approval))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- SRS-19-1 value shape

    @Test
    void SRS_19_5_aSlabVersionNeedsContiguousBandsAndEachTypeTakesOnlyItsValue() throws Exception {
        String slabKey = unique("IT.PT.SLAB");
        rule(slabKey, "SLAB", "COMPUTATION");
        String rateKey = unique("IT.HRA.RATE");
        rule(rateKey, "RATE", "COMPUTATION");
        long circular = activeCircular();
        Session admin = ruleAdmin();
        LocalDate from = today.plusDays(40);
        Map<String, Object> gap = new HashMap<>();
        gap.put("effectiveFrom", from.toString());
        gap.put("bands", List.of(band(1, 0, 10000, 0), band(2, 15000, null, 200)));
        gap.put("circularId", circular);
        gap.put("reason", "IT: PT slab");
        MvcResult r = post(admin, "/v1/rules/" + slabKey + "/versions", gap);
        assertThat(status(r)).isEqualTo(422);
        assertThat(error(r)).isEqualTo("RULE-422-SLAB");
        // A slab with a scalar value too; a rate with bands or a formula: the value is refused.
        Map<String, Object> slabWithValue = new HashMap<>(gap);
        slabWithValue.put("bands", List.of(band(1, 0, null, 0)));
        slabWithValue.put("value", 5);
        assertThat(error(post(admin, "/v1/rules/" + slabKey + "/versions", slabWithValue)))
                .isEqualTo("RULE-422-VALUE");
        Map<String, Object> rateWithFormula = scalar(from, "8", circular);
        rateWithFormula.put("formula", "1 + 1");
        assertThat(error(post(admin, "/v1/rules/" + rateKey + "/versions", rateWithFormula)))
                .isEqualTo("RULE-422-VALUE");
        // Numeric (12,2): three decimals are refused before the service.
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/rules/" + rateKey + "/versions",
                                        scalar(from, "8.125", circular))))
                .isEqualTo(400);
        // A contiguous set is accepted and shown in order.
        Map<String, Object> ok = new HashMap<>(gap);
        ok.put("bands", List.of(band(2, 10000, null, 200), band(1, 0, 10000, 0)));
        long approval = proposeOk(admin, slabKey, ok);
        JsonNode bands = row(admin, slabKey).get("pending").get("bands");
        assertThat(bands.get(0).get("order").asInt()).isEqualTo(1);
        assertThat(bands.get(1).get("to").isNull()).isTrue();
        assertThat(decide(ruleApprover(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(engine.band(slabKey, from, Context.unscoped(), null, new BigDecimal("10000")))
                .hasValueSatisfying(b -> assertThat(b.order()).isEqualTo(2));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rule_slab_band b JOIN rule_version v ON"
                                        + " v.rule_version_id = b.rule_version_id JOIN"
                                        + " rule_definition d ON d.rule_id = v.rule_id WHERE"
                                        + " d.rule_key = ?",
                                Integer.class,
                                slabKey))
                .isEqualTo(2);
    }

    @Test
    void SRS_19_15_aFormulaUsesOnlyTheGrammarAndYieldsWhatItsRuleTypeNeeds() throws Exception {
        String formulaKey = unique("IT.TA.FORMULA");
        rule(formulaKey, "FORMULA", "COMPUTATION");
        String eligibilityKey = unique("IT.TA.ELIGIBLE");
        rule(eligibilityKey, "ELIGIBILITY", "COMPUTATION");
        String variable = "IT_V_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbc.update(
                "INSERT INTO rule_expression_grammar (token_type, token_value, return_type,"
                        + " binds_to_entity, is_permitted) VALUES ('VARIABLE', ?, 'NUMBER',"
                        + " 'pay_component:IT', 'Y')",
                variable);
        rulesChanged();
        long circular = activeCircular();
        Session admin = ruleAdmin();
        LocalDate from = today.plusDays(50);
        Map<String, String> refused =
                Map.of(
                        "UNREGISTERED * 2",
                        "RULE-422-FORMULA",
                        variable + " > 1",
                        "RULE-422-FORMULA",
                        "POW(" + variable + ", 2)",
                        "RULE-422-FORMULA",
                        variable + " +",
                        "RULE-422-FORMULA");
        for (Map.Entry<String, String> e : refused.entrySet()) {
            MvcResult r =
                    post(
                            admin,
                            "/v1/rules/" + formulaKey + "/versions",
                            formula(from, e.getKey(), circular));
            assertThat(status(r)).as(e.getKey()).isEqualTo(422);
            assertThat(error(r)).as(e.getKey()).isEqualTo(e.getValue());
        }
        // R-ASCII: text the US7ASCII database cannot hold is refused at the boundary.
        MvcResult hindi =
                post(
                        admin,
                        "/v1/rules/" + formulaKey + "/versions",
                        formula(from, "मूल", circular));
        assertThat(status(hindi)).isEqualTo(400);
        assertThat(error(hindi)).isEqualTo("COM-400-VALIDATION");
        // An eligibility rule yields a condition, not a number.
        assertThat(
                        error(
                                post(
                                        admin,
                                        "/v1/rules/" + eligibilityKey + "/versions",
                                        formula(from, variable + " * 2", circular))))
                .isEqualTo("RULE-422-FORMULA");
        proposeOk(admin, formulaKey, formula(from, "ROUND(" + variable + " * 0.06, 0)", circular));
        long eligible =
                proposeOk(admin, eligibilityKey, formula(from, variable + " >= 18000", circular));
        assertThat(decide(ruleApprover(), eligible, "APPROVE", null, null)).isEqualTo(200);
        assertThat(
                        engine.condition(
                                eligibilityKey,
                                from,
                                Context.unscoped(),
                                Map.of(variable, new BigDecimal("18000"))))
                .contains(true);
        // The vocabulary lists the registered variable for the formula field.
        List<String> variables = new ArrayList<>();
        get(admin, "/v1/rules/vocabulary")
                .get("variables")
                .forEach(v -> variables.add(v.asString()));
        assertThat(variables).contains(variable);
    }

    // ---------------------------------------------------------------- SRS-19-17 circular

    @Test
    void SRS_19_17_everyVersionRestsOnAnActiveCircular() throws Exception {
        String key = unique("IT.WASHING.RATE");
        rule(key, "RATE", "COMPUTATION");
        Session admin = ruleAdmin();
        Map<String, Object> none = scalar(today.plusDays(5), "90", 1);
        none.remove("circularId");
        assertThat(status(post(admin, "/v1/rules/" + key + "/versions", none))).isEqualTo(400);
        MvcResult unknown =
                post(
                        admin,
                        "/v1/rules/" + key + "/versions",
                        scalar(today.plusDays(5), "90", 987654321L));
        assertThat(status(unknown)).isIn(409, 422);
        assertThat(error(unknown)).startsWith("MST-");
        // A rounding setting names an active ROUNDING rule.
        Map<String, Object> rounding = scalar(today.plusDays(5), "90", activeCircular());
        rounding.put("roundingRuleKey", unique("IT.NO.ROUNDING"));
        assertThat(error(post(admin, "/v1/rules/" + key + "/versions", rounding)))
                .isEqualTo("RULE-422-VALUE");
    }

    // ---------------------------------------------------------------- SRS-19-6 end date

    @Test
    void SRS_19_6_anEndDateWithoutSuccessorIsApprovedAndRecordedAsSuch() throws Exception {
        String key = unique("IT.SPECIAL.RATE");
        long id = rule(key, "RATE", "COMPUTATION");
        LocalDate start = today.minusDays(30);
        approvedVersion(id, 1, start, null, "100");
        Session admin = ruleAdmin();
        Map<String, Object> early = new HashMap<>();
        early.put("endOn", start.minusDays(1).toString());
        early.put("reason", "IT: scheme withdrawn");
        MvcResult r = post(admin, "/v1/rules/" + key + "/end", early);
        assertThat(status(r)).isEqualTo(422);
        assertThat(error(r)).isEqualTo("RULE-422-END");
        Map<String, Object> end = new HashMap<>(early);
        LocalDate last = today.plusDays(30);
        end.put("endOn", last.toString());
        MvcResult ok = post(admin, "/v1/rules/" + key + "/end", end);
        assertThat(status(ok)).isEqualTo(202);
        long approval = body(ok).get("approvalId").asLong();
        assertThat(row(admin, key).get("pendingEnd").get("endOn").asString())
                .isEqualTo(last.toString());
        // SRS-19-21: the Rule Catalogue Summary shows the version in force and the pending end.
        String summaryRow =
                java.util.Arrays.stream(csv(admin, "/v1/rules/export").split("\r\n"))
                        .filter(l -> l.contains("\"" + key + "\""))
                        .findFirst()
                        .orElseThrow();
        assertThat(summaryRow).contains("\"" + last + "\"");
        // One pending change per rule: a version cannot be proposed meanwhile.
        assertThat(
                        error(
                                post(
                                        admin,
                                        "/v1/rules/" + key + "/versions",
                                        scalar(today.plusDays(60), "1", activeCircular()))))
                .isEqualTo("WF-409-PENDING");
        // The pending end holds this rule only: a rule with nothing approved is refused for
        // having nothing to end, not for the other rule's pending change.
        String fresh = unique("IT.FRESH.RATE");
        rule(fresh, "RATE", "COMPUTATION");
        assertThat(error(post(admin, "/v1/rules/" + fresh + "/end", end)))
                .isEqualTo("RULE-422-VALUE");
        assertThat(decide(ruleAdminAsApprover(), approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(ruleApprover(), approval, "APPROVE", null, null)).isEqualTo(200);
        admin = ruleAdmin();
        JsonNode v1 = get(admin, "/v1/rules/" + key + "/versions").get(0);
        assertThat(v1.get("effectiveTo").asString()).isEqualTo(last.toString());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT new_value FROM audit_event WHERE entity_name ="
                                        + " 'rule_version' AND entity_key = ? AND field_name ="
                                        + " 'end_event' AND approval_id = ?",
                                String.class,
                                key,
                                approval))
                .isEqualTo("RULE_END_NO_SUCCESSOR");
        // After the end no version applies: "not configured", never a default.
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> engine.resolve(key, last.plusDays(1), Context.unscoped())))
                .isNotNull();
    }

    // ---------------------------------------------------------------- read-only definitions

    @Test
    void SRS_19_1_aDefinitionIsListedReadOnlyAndItsValueIsMaintainedByTheReportEngine()
            throws Exception {
        String key = unique("IT.FORM16.LAYOUT");
        rule(key, "STATUTORY_OUTPUT", "STATUTORY_OUTPUT");
        Session admin = ruleAdmin();
        JsonNode row = row(admin, key);
        assertThat(row.get("maintained").asBoolean()).isFalse();
        assertThat(row.get("valueShape").isNull()).isTrue();
        MvcResult r =
                post(admin, "/v1/rules/" + key + "/versions", scalar(today, 1, activeCircular()));
        assertThat(status(r)).isEqualTo(422);
        assertThat(error(r)).isEqualTo("RULE-422-READ-ONLY");
    }

    // ---------------------------------------------------------------- RE-14 umbrella view

    @Test
    void RE_14_theUmbrellaViewListsThePendingChangesOfM08M14AndM15() throws Exception {
        String key = unique("IT.UMBRELLA.RATE");
        rule(key, "RATE", "COMPUTATION");
        Session admin = ruleAdmin();
        long version = proposeOk(admin, key, scalar(today.plusDays(70), "7", activeCircular()));
        Map<String, Object> floor = new HashMap<>();
        floor.put("effectiveFrom", "2088-03-17");
        floor.put("floorBasis", "ABSOLUTE_AMOUNT");
        floor.put("floorValue", 1000);
        floor.put("confirmed", false);
        floor.put("reason", "IT: umbrella view");
        MvcResult m14 = post(admin, "/v1/masters/net-pay-floors/DEPT_LABOUR/versions", floor);
        assertThat(status(m14)).as(m14.getResponse().getContentAsString()).isEqualTo(202);
        long floorApproval = body(m14).get("approvalId").asLong();
        try {
            List<String> seen = new ArrayList<>();
            for (JsonNode p : get(hqChecker(), "/v1/rules/pending")) {
                seen.add(p.get("screen").asString() + " " + p.get("approvalId").asLong());
            }
            assertThat(seen).contains("M-08 " + version, "M-14 " + floorApproval);
            JsonNode summary = get(admin, "/v1/rules").get("summary");
            assertThat(summary.get("pendingRuleChanges").asInt()).isPositive();
            assertThat(summary.get("pendingOtherChanges").asInt()).isPositive();
            // SRS-19-21: the Pending Approval - Cross-Domain report on demand, one row per domain.
            assertThat(csv(admin, "/v1/rules/export")).contains("\"" + key + "\"");
            List<String> counts =
                    List.of(csv(ruleApprover(), "/v1/rules/pending/export").split("\r\n"));
            assertThat(counts.getFirst()).isEqualTo("\"screen\",\"change\",\"pending\",\"as_of\"");
            assertThat(counts).hasSize(6);
            assertThat(counts.get(1)).startsWith("\"M-08\",\"rule_version\",\"");
            assertThat(counts.get(1)).doesNotStartWith("\"M-08\",\"rule_version\",\"0\"");
            assertThat(counts.get(3)).doesNotStartWith("\"M-14\",\"net_pay_floor_rule\",\"0\"");
            assertThat(counts.get(5)).startsWith("\"M-15\",");
        } finally {
            // Leave nothing pending for the other M-14 tests.
            assertThat(decide(hqChecker(), floorApproval, "REJECT", "IT: cleanup", null))
                    .isEqualTo(200);
            assertThat(decide(ruleApprover(), version, "REJECT", "IT: cleanup", null))
                    .isEqualTo(200);
        }
    }

    @Test
    void SRS_19_21_theRuleCatalogueSummaryIsProducedOnDemandByTypeAndStatus() throws Exception {
        String key = unique("IT.SUMMARY.RATE");
        rule(key, "RATE", "COMPUTATION");
        String retired = unique("IT.SUMMARY.OFF");
        rule(retired, "RATE", "COMPUTATION", null, false);
        String text = csv(ruleApprover(), "/v1/rules/export");
        assertThat(text)
                .startsWith(
                        "\"rule_type\",\"rule_key\",\"name\",\"domain\",\"payroll_type_scope\"");
        String row =
                java.util.Arrays.stream(text.split("\r\n"))
                        .filter(l -> l.contains("\"" + key + "\""))
                        .findFirst()
                        .orElseThrow();
        // Active, no version in force yet (never filled with a guess, R-RULE).
        assertThat(row).startsWith("\"RATE\",\"" + key + "\",\"IT " + key + "\",\"COMPUTATION\"");
        assertThat(row).contains(",\"RULES\",\"Y\",,,,,,,\"0\",");
        assertThat(text).contains("\"" + retired + "\"");
        assertThat(csv(ruleAdmin(), "/v1/rules/export")).contains("\"" + key + "\"");
        // M-08 VIEW without EXPORT (Payroll Checker, Finance) and no access at all are refused.
        for (Session refused : List.of(hqChecker(), finance(), delhiMaker())) {
            for (String path : List.of("/v1/rules/export", "/v1/rules/pending/export")) {
                assertThat(download(refused, path).getResponse().getStatus())
                        .as(refused.loginId() + " " + path)
                        .isEqualTo(403);
            }
        }
    }

    // ---------------------------------------------------------------- edges of real data

    @Test
    void SRS_19_12_aBoundedVersionIsShortenedByItsSuccessorAndAClosedOneIsLeftAlone()
            throws Exception {
        String key = unique("IT.BOUNDED.RATE");
        long id = rule(key, "RATE", "COMPUTATION");
        // v1 closed long ago; v2 in force with an explicit last day after the new start.
        approvedVersion(id, 1, today.minusDays(200), today.minusDays(101), "10");
        approvedVersion(id, 2, today.minusDays(100), today.plusDays(100), "11");
        Session admin = ruleAdmin();
        long approval = proposeOk(admin, key, scalar(today, "12", activeCircular()));
        assertThat(decide(ruleApprover(), approval, "APPROVE", null, null)).isEqualTo(200);
        JsonNode versions = get(admin, "/v1/rules/" + key + "/versions");
        assertThat(versions.get(0).get("versionNo").asInt()).isEqualTo(3);
        // v2 now ends the day before v3; v1 keeps its own end (it never overlapped v3).
        assertThat(versions.get(1).get("effectiveTo").asString())
                .isEqualTo(today.minusDays(1).toString());
        assertThat(versions.get(2).get("effectiveTo").asString())
                .isEqualTo(today.minusDays(101).toString());
        // One END_DATE audit row: only v2 was closed by the approval.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'rule_version' AND entity_key = ? AND change_type ="
                                        + " 'END_DATE' AND approval_id = ?",
                                Integer.class,
                                key,
                                approval))
                .isEqualTo(1);
    }

    @Test
    void DB_06_1_aRejectedRowLeftByAMigrationHoldsItsDateAndIsNeverTheVersionEnded()
            throws Exception {
        String key = unique("IT.LEGACY.RATE");
        long id = rule(key, "RATE", "COMPUTATION");
        approvedVersion(id, 1, today.minusDays(40), null, "5");
        LocalDate held = today.plusDays(20);
        legacyVersion(id, 2, held, "REJECTED");
        Session admin = ruleAdmin();
        MvcResult r =
                post(admin, "/v1/rules/" + key + "/versions", scalar(held, "6", activeCircular()));
        assertThat(status(r)).isEqualTo(409);
        assertThat(error(r)).isEqualTo("MST-409-DATE");
        // The rejected v2 is listed with its state, before the approved v1 it never replaced.
        JsonNode versions = get(admin, "/v1/rules/" + key + "/versions");
        assertThat(versions.get(0).get("status").asString()).isEqualTo("REJECTED");
        assertThat(versions.get(1).get("status").asString()).isEqualTo("ACTIVE");
        // The end applies to v1, the latest approved version, not to the newer rejected row.
        Map<String, Object> end = new HashMap<>();
        end.put("endOn", today.plusDays(10).toString());
        end.put("reason", "IT: scheme closes");
        MvcResult ok = post(admin, "/v1/rules/" + key + "/end", end);
        assertThat(status(ok)).isEqualTo(202);
        assertThat(body(ok).get("versionNo").asInt()).isEqualTo(1);
        assertThat(
                        decide(
                                ruleApprover(),
                                body(ok).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(
                        get(ruleAdmin(), "/v1/rules/" + key + "/versions")
                                .get(1)
                                .get("effectiveTo")
                                .asString())
                .isEqualTo(today.plusDays(10).toString());
        // A later version is numbered after the rejected row and supersedes nothing: v1 has
        // ended, and the rejected v2 is never shortened or ended.
        LocalDate next = today.plusDays(30);
        long approval = proposeOk(ruleAdmin(), key, scalar(next, "7", activeCircular()));
        assertThat(decide(ruleApprover(), approval, "APPROVE", null, null)).isEqualTo(200);
        JsonNode after = get(ruleAdmin(), "/v1/rules/" + key + "/versions");
        assertThat(after.get(0).get("versionNo").asInt()).isEqualTo(3);
        assertThat(after.get(0).get("effectiveFrom").asString()).isEqualTo(next.toString());
        assertThat(after.get(1).get("status").asString()).isEqualTo("REJECTED");
        assertThat(after.get(1).get("effectiveTo").isNull()).isTrue();
        assertThat(after.get(2).get("effectiveTo").asString())
                .isEqualTo(today.plusDays(10).toString());
    }

    @Test
    void SRS_19_6_anEndOvertakenByANewerVersionIsRefusedAtApprovalAndChangesNothing()
            throws Exception {
        String key = unique("IT.OVERTAKEN.RATE");
        long id = rule(key, "RATE", "COMPUTATION");
        approvedVersion(id, 1, today.minusDays(30), null, "5");
        Session admin = ruleAdmin();
        Map<String, Object> end = new HashMap<>();
        end.put("endOn", today.plusDays(30).toString());
        end.put("reason", "IT: scheme closes");
        MvcResult ok = post(admin, "/v1/rules/" + key + "/end", end);
        assertThat(status(ok)).isEqualTo(202);
        long approval = body(ok).get("approvalId").asLong();
        // Meanwhile a migration closes v1 and loads v2 (not through M-08).
        jdbc.update(
                "UPDATE rule_version SET effective_to = ? WHERE rule_id = ? AND version_no = 1",
                today.plusDays(5),
                id);
        rulesChanged();
        approvedVersion(id, 2, today.plusDays(6), null, "6");
        // The approval re-checks under the lock: the version to end is no longer the latest.
        assertThat(decide(ruleApprover(), approval, "APPROVE", null, null)).isEqualTo(409);
        JsonNode versions = get(ruleAdmin(), "/v1/rules/" + key + "/versions");
        assertThat(versions.get(0).get("effectiveTo").isNull()).isTrue();
        assertThat(versions.get(1).get("effectiveTo").asString())
                .isEqualTo(today.plusDays(5).toString());
        // The item still awaits a decision; it is returned to leave nothing pending.
        assertThat(decide(ruleApprover(), approval, "RETURN", "IT: overtaken", null))
                .isEqualTo(200);
    }

    @Test
    void RE_10_anOfficeScopedRuleShowsItsOfficeAndARetiredRuleIsReadOnly() throws Exception {
        long hq = locationId("HQ001");
        String scoped = unique("IT.SITE.APPLICABILITY");
        rule(scoped, "APPLICABILITY", "COMPUTATION", hq, true);
        String retired = unique("IT.RETIRED.RATE");
        rule(retired, "RATE", "COMPUTATION", null, false);
        Session admin = ruleAdmin();
        JsonNode site = row(admin, scoped);
        assertThat(site.get("officeScopeId").asLong()).isEqualTo(hq);
        assertThat(site.get("officeScopeName").asString()).isNotBlank();
        JsonNode old = row(admin, retired);
        assertThat(old.get("active").asBoolean()).isFalse();
        assertThat(old.get("maintained").asBoolean()).isFalse();
        MvcResult r =
                post(
                        admin,
                        "/v1/rules/" + retired + "/versions",
                        scalar(today, "1", activeCircular()));
        assertThat(error(r)).isEqualTo("RULE-422-READ-ONLY");
        // A rule with no version: an empty history, not an error.
        assertThat(get(admin, "/v1/rules/" + scoped + "/versions")).isEmpty();
    }

    @Test
    void SRS_19_3_aRulesVersionsShowItsPendingProposalFirstAndNeverAnotherRules() throws Exception {
        String key = unique("IT.FIRST.SLAB");
        long id = rule(key, "SLAB", "COMPUTATION");
        jdbc.update(
                "INSERT INTO rule_version (rule_id, version_no, effective_from, approval_state,"
                        + " is_blocked, created_by) VALUES (?, 1, ?, 'APPROVED', 'N', 'it')",
                id,
                today.minusDays(90));
        rulesChanged();
        long v1 =
                jdbc.queryForObject(
                        "SELECT rule_version_id FROM rule_version WHERE rule_id = ?",
                        Long.class,
                        id);
        jdbc.update(
                "INSERT INTO rule_slab_band (rule_version_id, band_order, range_from, range_to,"
                        + " rate_or_amount) VALUES (?, 1, 0, NULL, 3)",
                v1);
        rulesChanged();
        String other = unique("IT.OTHER.RATE");
        rule(other, "RATE", "COMPUTATION");
        Session admin = ruleAdmin();
        long circular = activeCircular();
        Map<String, Object> slab = new HashMap<>();
        slab.put("effectiveFrom", today.plusDays(15).toString());
        slab.put("bands", List.of(band(1, 0, null, 4)));
        slab.put("circularId", circular);
        slab.put("roundingRuleKey", "");
        slab.put("reason", "IT: revised slab");
        long mine = proposeOk(admin, key, slab);
        long theirs = proposeOk(admin, other, scalar(today.plusDays(15), "2", circular));
        try {
            JsonNode versions = get(admin, "/v1/rules/" + key + "/versions");
            assertThat(versions).hasSize(2);
            JsonNode pending = versions.get(0);
            assertThat(pending.get("status").asString()).isEqualTo("PENDING_APPROVAL");
            assertThat(pending.get("id").isNull()).isTrue();
            assertThat(pending.get("versionNo").asInt()).isEqualTo(2);
            assertThat(pending.get("approvalId").asLong()).isEqualTo(mine);
            // An empty rounding setting is no setting.
            assertThat(pending.get("roundingRuleKey").isNull()).isTrue();
            // The approved version shows its stored bands.
            JsonNode approved = versions.get(1);
            assertThat(approved.get("bands").get(0).get("rateOrAmount").decimalValue())
                    .isEqualByComparingTo("3");
            assertThat(approved.get("bands").get(0).get("to").isNull()).isTrue();
        } finally {
            assertThat(decide(ruleApprover(), mine, "REJECT", "IT: cleanup", null)).isEqualTo(200);
            assertThat(decide(ruleApprover(), theirs, "REJECT", "IT: cleanup", null))
                    .isEqualTo(200);
        }
    }

    @Test
    void SRS_19_1_aRoundingSettingIsAnActiveRoundingRule() throws Exception {
        String rounding = unique("IT.ROUNDING");
        rule(rounding, "ROUNDING", "COMPUTATION");
        String key = unique("IT.ROUNDED.RATE");
        rule(key, "RATE", "COMPUTATION");
        Session admin = ruleAdmin();
        Map<String, Object> notRounding = scalar(today.plusDays(3), "9", activeCircular());
        notRounding.put("roundingRuleKey", key);
        assertThat(error(post(admin, "/v1/rules/" + key + "/versions", notRounding)))
                .isEqualTo("RULE-422-VALUE");
        Map<String, Object> ok = new HashMap<>(notRounding);
        ok.put("roundingRuleKey", rounding);
        long approval = proposeOk(admin, key, ok);
        assertThat(decide(ruleApprover(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(engine.resolve(key, today.plusDays(3), Context.unscoped()))
                .hasValueSatisfying(r -> assertThat(r.roundingRuleKey()).isEqualTo(rounding));
    }

    // ---------------------------------------------------------------- races, limits, tampering

    /**
     * Proposes on a fresh rule and returns its approval id (the rule key is returned in {@code
     * key}).
     */
    private long pendingProposal(String key, String type, Map<String, Object> body)
            throws Exception {
        rule(key, type, "COMPUTATION");
        return proposeOk(ruleAdmin(), key, body);
    }

    /** "status errorCode" of the Rule Approver's APPROVE that the server refuses. */
    private String refusedApproval(long approval) throws Exception {
        org.springframework.test.web.servlet.MvcResult r =
                mvc.perform(
                                userPost(
                                        ruleApprover(),
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        return r.getResponse().getStatus() + " " + body(r).path("errorCode").asString();
    }

    @Test
    void SRS_G_16_aCircularWithdrawnBeforeTheDecisionBlocksTheApproval() throws Exception {
        String key = unique("IT.RACE.CIRCULAR");
        long circular = activeCircular();
        long approval = pendingProposal(key, "RATE", scalar(today.plusDays(9), "1", circular));
        jdbc.update(
                "UPDATE circular_order SET circular_status = 'WITHDRAWN' WHERE circular_order_id ="
                        + " ?",
                circular);
        assertThat(refusedApproval(approval)).isEqualTo("409 MST-409-CIRCULAR-STATE");
        assertThat(
                        get(ruleAdmin(), "/v1/rules/" + key + "/versions")
                                .get(0)
                                .get("status")
                                .asString())
                .isEqualTo("PENDING_APPROVAL");
        assertThat(decide(ruleApprover(), approval, "RETURN", "IT: order withdrawn", null))
                .isEqualTo(200);
    }

    @Test
    void SRS_G_38_aCircularSupersededBetweenProposalAndDecisionBlocksTheApproval()
            throws Exception {
        String key = unique("IT.RACE.SUPERSEDED");
        long circular = activeCircular();
        long approval = pendingProposal(key, "RATE", scalar(today.plusDays(9), "1", circular));
        // A newer circular replaced it after the proposal (M-25 sets the old one SUPERSEDED).
        jdbc.update(
                "UPDATE circular_order SET circular_status = 'SUPERSEDED' WHERE circular_order_id"
                        + " = ?",
                circular);
        assertThat(refusedApproval(approval)).isEqualTo("409 MST-409-CIRCULAR-STATE");
        // Nothing is written: no version, no link to the superseded circular.
        assertThat(
                        get(ruleAdmin(), "/v1/rules/" + key + "/versions")
                                .get(0)
                                .get("status")
                                .asString())
                .isEqualTo("PENDING_APPROVAL");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM circular_order_link WHERE circular_order_id ="
                                        + " ? AND link_type = 'RULE_VERSION'",
                                Integer.class,
                                circular))
                .isZero();
        assertThat(decide(ruleApprover(), approval, "RETURN", "IT: order superseded", null))
                .isEqualTo(200);
    }

    @Test
    void SRS_19_15_aVariableRevokedBeforeTheDecisionBlocksTheApproval() throws Exception {
        String variable = "IT_R_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbc.update(
                "INSERT INTO rule_expression_grammar (token_type, token_value, return_type,"
                        + " binds_to_entity, is_permitted) VALUES ('VARIABLE', ?, 'NUMBER',"
                        + " 'pay_component:IT', 'Y')",
                variable);
        rulesChanged();
        String key = unique("IT.RACE.FORMULA");
        long approval =
                pendingProposal(
                        key,
                        "FORMULA",
                        formula(today.plusDays(9), variable + " * 2", activeCircular()));
        jdbc.update(
                "UPDATE rule_expression_grammar SET is_permitted = 'N' WHERE token_value = ?",
                variable);
        rulesChanged();
        assertThat(decide(ruleApprover(), approval, "APPROVE", null, null)).isEqualTo(422);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rule_version v JOIN rule_definition d ON"
                                        + " d.rule_id = v.rule_id WHERE d.rule_key = ?",
                                Integer.class,
                                key))
                .isZero();
        assertThat(decide(ruleApprover(), approval, "RETURN", "IT: grammar changed", null))
                .isEqualTo(200);
    }

    @Test
    void SRS_19_1_aRuleRetiredBeforeTheDecisionIsNotGivenAVersion() throws Exception {
        String key = unique("IT.RACE.RETIRED");
        long approval =
                pendingProposal(key, "RATE", scalar(today.plusDays(9), "1", activeCircular()));
        jdbc.update("UPDATE rule_definition SET active_flag = 'N' WHERE rule_key = ?", key);
        rulesChanged();
        assertThat(decide(ruleApprover(), approval, "APPROVE", null, null)).isEqualTo(422);
        assertThat(decide(ruleApprover(), approval, "RETURN", "IT: rule retired", null))
                .isEqualTo(200);
    }

    @Test
    void SRS_19_12_aDateTakenByAMigrationBeforeTheDecisionBlocksTheApproval() throws Exception {
        String key = unique("IT.RACE.DATE");
        LocalDate from = today.plusDays(9);
        long approval = pendingProposal(key, "RATE", scalar(from, "1", activeCircular()));
        long id =
                jdbc.queryForObject(
                        "SELECT rule_id FROM rule_definition WHERE rule_key = ?", Long.class, key);
        approvedVersion(id, 1, from.plusDays(1), null, "2");
        assertThat(decide(ruleApprover(), approval, "APPROVE", null, null)).isEqualTo(409);
        assertThat(
                        get(ruleAdmin(), "/v1/rules/" + key + "/versions")
                                .get(1)
                                .get("effectiveFrom")
                                .asString())
                .isEqualTo(from.plusDays(1).toString());
        assertThat(decide(ruleApprover(), approval, "RETURN", "IT: overtaken", null))
                .isEqualTo(200);
    }

    @Test
    void RE_2_twoProposalsForOneRuleAtOnceLeaveExactlyOnePending() throws Exception {
        String key = unique("IT.RACE.TWICE");
        rule(key, "RATE", "COMPUTATION");
        Session admin = ruleAdmin();
        long circular = activeCircular();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                LocalDate from = today.plusDays(20 + i);
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return status(
                                            post(
                                                    admin,
                                                    "/v1/rules/" + key + "/versions",
                                                    scalar(from, "1", circular)));
                                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (java.util.concurrent.Future<Integer> r : results) {
                statuses.add(r.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            // The rule row is locked first: the second proposal sees the first and is refused.
            assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        } finally {
            pool.shutdownNow();
        }
        JsonNode pending = row(ruleAdmin(), key).get("pending");
        assertThat(pending.isNull()).isFalse();
        assertThat(
                        decide(
                                ruleApprover(),
                                pending.get("approvalId").asLong(),
                                "REJECT",
                                "IT: cleanup",
                                null))
                .isEqualTo(200);
    }

    @Test
    void OWASP_A08_aClientCannotSetWhatTheServerAssigns() throws Exception {
        String key = unique("IT.MASS.RATE");
        rule(key, "RATE", "COMPUTATION");
        Session admin = ruleAdmin();
        long circular = activeCircular();
        for (String serverField :
                List.of(
                        "status",
                        "versionNo",
                        "createdBy",
                        "approvalId",
                        "id",
                        "approvalState",
                        "ruleKey")) {
            Map<String, Object> body = scalar(today.plusDays(9), "1", circular);
            // Unknown to the request: refused whatever its value (fail-on-unknown-properties).
            body.put(serverField, "APPROVED");
            assertThat(status(post(admin, "/v1/rules/" + key + "/versions", body)))
                    .as(serverField)
                    .isEqualTo(400);
        }
        assertThat(row(admin, key).get("pending").isNull()).isTrue();
    }

    @Test
    void SRS_19_15_theRequestLimitsHoldAtTheirExactBoundary() throws Exception {
        Session admin = ruleAdmin();
        long circular = activeCircular();
        LocalDate from = today.plusDays(11);
        // A formula of exactly 4,000 characters is accepted; one more is refused.
        String formulaKey = unique("IT.LIMIT.FORMULA");
        rule(formulaKey, "FORMULA", "COMPUTATION");
        String atLimit = "1" + " + 1".repeat(999);
        atLimit = atLimit + " ".repeat(4000 - atLimit.length());
        assertThat(atLimit).hasSize(4000);
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/rules/" + formulaKey + "/versions",
                                        formula(from, atLimit + " ", circular))))
                .isEqualTo(400);
        long f = proposeOk(admin, formulaKey, formula(from, atLimit, circular));
        // A reason of exactly 500 characters is accepted; one more is refused.
        String reasonKey = unique("IT.LIMIT.REASON");
        rule(reasonKey, "RATE", "COMPUTATION");
        Map<String, Object> longReason = scalar(from, "1", circular);
        longReason.put("reason", "r".repeat(501));
        assertThat(status(post(admin, "/v1/rules/" + reasonKey + "/versions", longReason)))
                .isEqualTo(400);
        longReason.put("reason", "r".repeat(500));
        long r = proposeOk(admin, reasonKey, longReason);
        // 200 contiguous bands are accepted; 201 are refused.
        String slabKey = unique("IT.LIMIT.SLAB");
        rule(slabKey, "SLAB", "COMPUTATION");
        Map<String, Object> slab = new HashMap<>();
        slab.put("effectiveFrom", from.toString());
        slab.put("circularId", circular);
        slab.put("reason", "IT: many bands");
        List<Map<String, Object>> bands = new ArrayList<>();
        for (int i = 1; i <= 201; i++) {
            bands.add(band(i, (i - 1) * 10, i == 201 ? null : i * 10, i));
        }
        slab.put("bands", bands);
        assertThat(status(post(admin, "/v1/rules/" + slabKey + "/versions", slab))).isEqualTo(400);
        List<Map<String, Object>> two00 = new ArrayList<>(bands.subList(0, 200));
        two00.set(199, band(200, 1990, null, 200));
        slab.put("bands", two00);
        long b = proposeOk(admin, slabKey, slab);
        // Band numbers are unique in the version across qualifiers (uk_rule_slab_band).
        String qualifiedKey = unique("IT.LIMIT.QUALIFIED");
        rule(qualifiedKey, "SLAB", "COMPUTATION");
        Map<String, Object> cityX = band(1, 0, null, 1);
        cityX.put("qualifier", "CITY-X");
        Map<String, Object> cityY = band(1, 0, null, 2);
        cityY.put("qualifier", "CITY-Y");
        slab.put("bands", List.of(cityX, cityY));
        MvcResult duplicate = post(admin, "/v1/rules/" + qualifiedKey + "/versions", slab);
        assertThat(status(duplicate)).isEqualTo(422);
        assertThat(error(duplicate)).isEqualTo("RULE-422-SLAB");
        for (long approval : List.of(f, r, b)) {
            assertThat(decide(ruleApprover(), approval, "REJECT", "IT: cleanup", null))
                    .isEqualTo(200);
        }
    }

    // ---------------------------------------------------------------- SRS-19-4 simulation (06e)

    @Test
    void SRS_19_4_SRS_NF_24_thePendingVersionIsSimulatedOnASampleAndKeptWithTheVersionItTested()
            throws Exception {
        String key = unique("IT.SIM.RATE");
        long id = rule(key, "RATE", "COMPUTATION");
        approvedVersion(id, 1, today.minusDays(30), null, "10");
        long circular = activeCircular();
        Session admin = ruleAdmin();
        // A rule M-08 lists read-only (a statutory-output definition) is never simulated.
        String layout = unique("IT.SIM.LAYOUT");
        rule(layout, "STATUTORY_OUTPUT", "STATUTORY_OUTPUT");
        MvcResult readOnly = post(admin, "/v1/rules/" + layout + "/simulations", Map.of());
        assertThat(error(readOnly)).isEqualTo("RULE-422-READ-ONLY");
        assertThat(error(post(admin, "/v1/rules/IT.NO.SUCH.RULE/simulations", Map.of())))
                .isEqualTo("COM-404-NOT-FOUND");
        // Nothing awaiting approval: nothing to simulate.
        MvcResult none = post(admin, "/v1/rules/" + key + "/simulations", Map.of());
        assertThat(status(none)).isEqualTo(409);
        assertThat(error(none)).isEqualTo("RULE-409-NO-PROPOSAL");
        long approval = proposeOk(admin, key, scalar(today, "12", circular));
        MvcResult run = post(admin, "/v1/rules/" + key + "/simulations", Map.of());
        assertThat(status(run)).as(run.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode sim = body(run);
        assertThat(sim.get("approvalId").asLong()).isEqualTo(approval);
        JsonNode impact = sim.get("impact");
        assertThat(sim.get("population").asLong()).isPositive();
        assertThat(impact.get("sampled").asLong()).isPositive();
        // An unscoped rule covers every member; each sees 10 now and 12 as proposed.
        assertThat(impact.get("inScope").asLong()).isEqualTo(impact.get("sampled").asLong());
        assertThat(impact.get("changed").asLong()).isEqualTo(impact.get("sampled").asLong());
        JsonNode group = impact.get("groups").get(0);
        assertThat(group.get("current").asString()).isEqualTo("10");
        assertThat(group.get("proposed").asString()).isEqualTo("12");
        long simulation = sim.get("id").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT results_retained FROM rule_simulation WHERE simulation_id"
                                        + " = ?",
                                String.class,
                                simulation))
                .isEqualTo("N");
        // A labour payroll type has no members yet; an unknown office is not found.
        JsonNode labour =
                body(
                        post(
                                admin,
                                "/v1/rules/" + key + "/simulations",
                                Map.of("payrollType", "DPS_LABOUR")));
        assertThat(labour.get("population").asLong()).isZero();
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/rules/" + key + "/simulations",
                                        Map.of("officeId", 99_999_999))))
                .isEqualTo(404);
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/rules/" + key + "/simulations",
                                        Map.of("payrollType", "CASUAL"))))
                .isEqualTo(400);
        JsonNode listed = get(admin, "/v1/rules/" + key + "/simulations");
        assertThat(listed.findValuesAsString("inputsChanged")).containsOnly("false");
        // The rule in force changes since: the run says its inputs changed.
        jdbc.update(
                "UPDATE rule_version SET scalar_value = 11 WHERE rule_id = ? AND version_no = 1",
                id);
        rulesChanged();
        listed = get(admin, "/v1/rules/" + key + "/simulations");
        assertThat(listed.findValuesAsString("inputsChanged")).contains("true");
        jdbc.update(
                "UPDATE rule_version SET scalar_value = 10 WHERE rule_id = ? AND version_no = 1",
                id);
        rulesChanged();
        // The readers see the runs but cannot run one; the payroll desk has no access.
        Session checker = hqChecker();
        assertThat(status(getRaw(checker, "/v1/rules/" + key + "/simulations"))).isEqualTo(200);
        assertThat(status(post(checker, "/v1/rules/" + key + "/simulations", Map.of())))
                .isEqualTo(403);
        assertThat(status(getRaw(checker, "/v1/rules/simulation-offices"))).isEqualTo(403);
        Session maker = delhiMaker();
        assertThat(status(post(maker, "/v1/rules/" + key + "/simulations", Map.of())))
                .isEqualTo(403);
        // The Rule Approver simulates too, then approves: the runs stay with the version (D-7).
        Session approver = ruleApprover();
        assertThat(status(getRaw(approver, "/v1/rules/simulation-offices"))).isEqualTo(200);
        assertThat(status(post(approver, "/v1/rules/" + key + "/simulations", Map.of())))
                .isEqualTo(201);
        assertThat(decide(approver, approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rule_simulation s JOIN rule_version v ON"
                                        + " v.rule_version_id = s.rule_version_id WHERE v.rule_id"
                                        + " = ? AND v.version_no = 2",
                                Integer.class,
                                id))
                .isEqualTo(3);
        assertThat(error(post(ruleAdmin(), "/v1/rules/" + key + "/simulations", Map.of())))
                .isEqualTo("RULE-409-NO-PROPOSAL");
    }

    /** SRS-19-4: a rule with no version in force is simulated too; "not configured" is shown. */
    @Test
    void SRS_19_4_aFirstVersionIsSimulatedAgainstNotConfigured() throws Exception {
        String key = unique("IT.SIM.FIRST");
        rule(key, "RATE", "COMPUTATION");
        long circular = activeCircular();
        Session admin = ruleAdmin();
        proposeOk(admin, key, scalar(today, "7", circular));
        MvcResult run = post(admin, "/v1/rules/" + key + "/simulations", Map.of());
        assertThat(status(run)).as(run.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode group = body(run).get("impact").get("groups").get(0);
        assertThat(group.get("current").asString()).isEqualTo("NOT_CONFIGURED");
        assertThat(group.get("proposed").asString()).isEqualTo("7");
        assertThat(group.get("changes").asBoolean()).isTrue();
    }
}
