package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * X-06 Payroll Query Management end to end (SRS-G-11; SRS v0.5 use case "Raise and Resolve a
 * Payroll Query"; DDL {@code payroll_query}, {@code chk_query_checker}). Each test raises its own
 * query against an ACTUAL payroll result of a member at Delhi HQ (DH001) created by {@link
 * #payrollIds()}.
 */
class PayrollQueryIT extends ApiTestSupport {

    private static final String MAKER = "maker.delhi@fci-demo.test";
    private static final String CHECKER = "ddo.delhi@fci-demo.test";

    /**
     * Payroll IDs (payroll_result_id) of a staff member at DH001 (105220) and of the eSeva user
     * (104882, emp.rsharma).
     */
    private long staffResult;

    private long esevaResult;

    @BeforeEach
    void payrollIds() {
        staffResult =
                actualResult(
                        jdbc.queryForObject(
                                "SELECT employee_id FROM employee WHERE emp_num = '105220'",
                                Long.class));
        esevaResult =
                actualResult(
                        jdbc.queryForObject(
                                "SELECT employee_id FROM app_user WHERE login_id ="
                                        + " 'emp.rsharma@fci-demo.test'",
                                Long.class));
    }

    /**
     * An ACTUAL payroll result of {@code employeeId} in a fixed past period (2024-01) of the
     * employee's unit, created once: the demo seed has no run of record until a cycle is computed.
     */
    private long actualResult(Long employeeId) {
        Long unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = ?",
                        Long.class,
                        employeeId);
        Long cycle =
                jdbc
                        .query(
                                "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE"
                                        + " payroll_yymm = '202401' AND org_unit_id = ?",
                                (rs, i) -> rs.getLong(1),
                                unit)
                        .stream()
                        .findFirst()
                        .orElseGet(
                                () -> {
                                    jdbc.update(
                                            "INSERT INTO payroll_cycle_instance (payroll_yymm,"
                                                + " org_unit_id, org_unit_level, fin_year,"
                                                + " cycle_status, trigger_type, initiated_by)"
                                                + " VALUES ('202401', ?, 'DO', '2023-24', 'LOCKED',"
                                                + " 'ON_DEMAND', 'it.fixture')",
                                            unit);
                                    return jdbc.queryForObject(
                                            "SELECT cycle_instance_id FROM payroll_cycle_instance"
                                                + " WHERE payroll_yymm = '202401' AND org_unit_id ="
                                                + " ?",
                                            Long.class,
                                            unit);
                                });
        return jdbc
                .query(
                        "SELECT payroll_result_id FROM payroll_result WHERE cycle_instance_id = ?"
                                + " AND employee_id = ?",
                        (rs, i) -> rs.getLong(1),
                        cycle,
                        employeeId)
                .stream()
                .findFirst()
                .orElseGet(
                        () -> {
                            jdbc.update(
                                    "INSERT INTO payroll_result (cycle_instance_id, org_unit_id,"
                                        + " employee_id, payroll_type, payroll_yymm, period_date,"
                                        + " run_type, gross_earnings, total_deductions, net_pay,"
                                        + " payable_days, result_status, is_locked) VALUES (?, ?,"
                                        + " ?, 'STAFF', '202401', DATE '2024-01-01', 'ACTUAL',"
                                        + " 50000, 5000, 45000, 31, 'CURRENT', 'Y')",
                                    cycle,
                                    unit,
                                    employeeId);
                            return jdbc.queryForObject(
                                    "SELECT payroll_result_id FROM payroll_result WHERE"
                                            + " cycle_instance_id = ? AND employee_id = ?",
                                    Long.class,
                                    cycle,
                                    employeeId);
                        });
    }

    private JsonNode raise(
            Session session, long payrollId, String text, boolean computed, int expected)
            throws Exception {
        return body(
                mvc.perform(
                                userPost(
                                        session,
                                        "/v1/queries",
                                        Map.of(
                                                "payrollId",
                                                payrollId,
                                                "queryText",
                                                text,
                                                "concernsComputedAmount",
                                                computed)))
                        .andExpect(status().is(expected))
                        .andReturn());
    }

    private void respond(Session session, long id, boolean computed, int expected)
            throws Exception {
        mvc.perform(
                        userPost(
                                session,
                                "/v1/queries/" + id + "/respond",
                                Map.of(
                                        "response",
                                        "DA arrear of Dec paid in Jan",
                                        "concernsComputedAmount",
                                        computed)))
                .andExpect(status().is(expected));
    }

    @Test
    void SRS_G_11_aMakerRaisesAQueryAgainstAPayrollIdAndItOpensWithAReference() throws Exception {
        JsonNode q =
                raise(
                        login(MAKER, "PAYROLL_MAKER", "DH001"),
                        staffResult,
                        "Why is HRA lower in Jan?",
                        false,
                        201);
        assertThat(q.get("status").asString()).isEqualTo("OPEN");
        assertThat(q.get("reference").asString()).matches("QRY-\\d{8}");
        assertThat(q.get("eis").asString()).isEqualTo("105220");
        assertThat(q.get("payrollYymm").asString()).isEqualTo("202401");
        assertThat(q.get("channel").asString()).isEqualTo("STAFF");
    }

    @Test
    void SRS_G_11_aQueryAboutAComputedAmountIsConfirmedByAnotherCheckerAndThenClosed()
            throws Exception {
        Session maker = login(MAKER, "PAYROLL_MAKER", "DH001");
        long id =
                raise(maker, staffResult, "Net pay differs from slip", true, 201)
                        .get("id")
                        .asLong();
        respond(maker, id, true, 200);

        // The Maker cannot close a computed-amount query: a Checker confirms it.
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/queries/" + id + "/resolve",
                                Map.of("resolution", "explained")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("QRY-403-CHECKER"));

        Session checker = login(CHECKER, "PAYROLL_CHECKER", "DH001");
        JsonNode closed =
                body(
                        mvc.perform(
                                        userPost(
                                                checker,
                                                "/v1/queries/" + id + "/resolve",
                                                Map.of(
                                                        "resolution",
                                                        "Confirmed against the run of record")))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("resolvedBy").asString()).isEqualTo(CHECKER);
        // Every status change is in the audit trail (raised, responded, resolved, closed), and
        // the response as a field change with its value (SRS-20-2).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'payroll_query' AND entity_key = ? AND field_name ="
                                        + " 'query_status'",
                                Integer.class,
                                String.valueOf(id)))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT new_value FROM audit_event WHERE entity_name ="
                                        + " 'payroll_query' AND entity_key = ? AND field_name ="
                                        + " 'maker_response'",
                                String.class,
                                String.valueOf(id)))
                .isEqualTo("DA arrear of Dec paid in Jan");
    }

    @Test
    void SRS_22_5_theMakerCannotClearTheComputedAmountFlagAndEveryChangeIsAudited()
            throws Exception {
        Session maker = login(MAKER, "PAYROLL_MAKER", "DH001");
        long raised = raise(maker, staffResult, "CCA looks wrong", true, 201).get("id").asLong();
        // Raised as about a computed amount: answering it as not one is refused.
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/queries/" + raised + "/respond",
                                Map.of(
                                        "response",
                                        "Not a computation question",
                                        "concernsComputedAmount",
                                        false)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("QRY-422-COMPUTED"));

        // The Maker may mark a query as about a computed amount; the change is audited, and the
        // Checker then confirms it.
        long other = raise(maker, staffResult, "Why is HRA lower?", false, 201).get("id").asLong();
        respond(maker, other, true, 200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT old_value || '>' || new_value FROM audit_event WHERE"
                                        + " entity_name = 'payroll_query' AND entity_key = ? AND"
                                        + " field_name = 'concerns_computed_amount'",
                                String.class,
                                String.valueOf(other)))
                .isEqualTo("N>Y");
        // A second response keeps the first one as the old value.
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/queries/" + other + "/respond",
                                Map.of(
                                        "response",
                                        "HRA follows the city class of the new posting",
                                        "concernsComputedAmount",
                                        true)))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForList(
                                "SELECT old_value FROM audit_event WHERE entity_name ="
                                        + " 'payroll_query' AND entity_key = ? AND field_name ="
                                        + " 'maker_response' ORDER BY audit_id",
                                String.class,
                                String.valueOf(other)))
                .containsExactly(null, "DA arrear of Dec paid in Jan");
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/queries/" + other + "/resolve",
                                Map.of("resolution", "explained")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("QRY-403-CHECKER"));
    }

    @Test
    void chk_query_checker_theCheckerWhoRespondedCannotConfirm() throws Exception {
        // maker.checker holds both roles at DH001 (seed V015_9): responds as Maker, then as
        // Checker.
        Session both = login("maker.checker@fci-demo.test", "PAYROLL_MAKER", "DH001");
        long id = raise(both, staffResult, "Deduction looks wrong", true, 201).get("id").asLong();
        respond(both, id, true, 200);
        actAs(both, "PAYROLL_CHECKER");
        mvc.perform(
                        userPost(
                                both,
                                "/v1/queries/" + id + "/resolve",
                                Map.of("resolution", "self-confirmed")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("QRY-403-SOD"));
    }

    @Test
    void aQueryNotAboutAComputedAmountIsClosedByTheMakerWhoAnsweredIt() throws Exception {
        Session maker = login(MAKER, "PAYROLL_MAKER", "DH001");
        long id =
                raise(maker, staffResult, "Where do I see my slip?", false, 201).get("id").asLong();
        // Response required first: an OPEN query cannot be resolved.
        mvc.perform(userPost(maker, "/v1/queries/" + id + "/resolve", Map.of("resolution", "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("QRY-409-STATUS"));
        respond(maker, id, false, 200);
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/queries/" + id + "/resolve",
                                Map.of("resolution", "Slip is on E-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        // Closed is final.
        respond(maker, id, false, 409);
    }

    @Test
    void SRS_G_11_aMemberRaisesOnlyOnTheirOwnRecordAndSeesOnlyTheirOwnQueries() throws Exception {
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        JsonNode own = raise(member, esevaResult, "My CCA is missing", false, 201);
        assertThat(own.get("channel").asString()).isEqualTo("ESEVA");
        // Another office's record reads as unknown (404, owner 2026-10-24; OWASP A01).
        raise(member, staffResult, "someone else's slip", false, 404);
        JsonNode mine = all(member, "/v1/queries");
        assertThat(texts(mine, "eis"))
                .isNotEmpty()
                .allMatch(e -> e.equals(own.get("eis").asString()));
        // A member never responds or resolves.
        respond(member, own.get("id").asLong(), false, 403);
        mvc.perform(
                        userPost(
                                member,
                                "/v1/queries/" + own.get("id").asLong() + "/resolve",
                                Map.of("resolution", "Settled by myself")))
                .andExpect(status().isForbidden());
        // The own list filters by status: the open query is not among the closed ones.
        JsonNode closed = all(member, "/v1/queries?status=CLOSED");
        assertThat(texts(closed, "reference")).doesNotContain(own.get("reference").asString());
        JsonNode open = all(member, "/v1/queries?status=OPEN");
        assertThat(texts(open, "reference")).contains(own.get("reference").asString());
    }

    @Test
    void SRS_G_13_aMemberGrantedTheExportStillExportsOnlyTheirOwnQueries() throws Exception {
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        String own =
                raise(member, esevaResult, "Export check, own " + System.nanoTime(), false, 201)
                        .get("reference")
                        .asString();
        String others =
                raise(
                                login(MAKER, "PAYROLL_MAKER", "DH001"),
                                staffResult,
                                "Export check, another member " + System.nanoTime(),
                                true,
                                201)
                        .get("reference")
                        .asString();
        // Access is configuration (R-RBAC): an administrator may grant eSeva X-06 EXPORT.
        long role =
                jdbc.queryForObject(
                        "SELECT role_id FROM app_role WHERE role_code = 'EMPLOYEE_ESEVA'",
                        Long.class);
        long export =
                jdbc.queryForObject(
                        "SELECT permission_id FROM app_permission WHERE screen_code = 'X-06' AND"
                                + " action_code = 'EXPORT'",
                        Long.class);
        jdbc.update(
                "INSERT INTO app_role_permission (role_id, permission_id, effective_from) VALUES"
                        + " (?, ?, CURRENT_DATE - 1)",
                role,
                export);
        accessContexts.evictAll();
        try {
            String csv = csv(member, "/v1/queries/export");
            assertThat(csv).startsWith("\"Query reference\"").contains(own).doesNotContain(others);
            assertThat(csv(member, "/v1/queries/export?status=CLOSED")).doesNotContain(own);
        } finally {
            // Revoked as the matrix revokes: end-dated, never deleted.
            jdbc.update(
                    "UPDATE app_role_permission SET effective_to = CURRENT_DATE - 1 WHERE role_id ="
                            + " ? AND permission_id = ?",
                    role,
                    export);
            accessContexts.evictAll();
        }
        mvc.perform(userGet(member, "/v1/queries/export")).andExpect(status().isForbidden());
    }

    @Test
    void scopeRoleAndInputAreEnforced() throws Exception {
        // Another office's maker: the DH001 member is out of scope, so unknown (404).
        raise(
                login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001"),
                staffResult,
                "x",
                false,
                404);
        Session maker = login(MAKER, "PAYROLL_MAKER", "DH001");
        // Unknown Payroll ID, and text the US7ASCII database cannot hold.
        assertThat(raise(maker, 987654321L, "x", false, 422).get("errorCode").asString())
                .isEqualTo("QRY-422-PAYROLL-ID");
        assertThat(
                        raise(maker, staffResult, "वेतन कम क्यों है", false, 400)
                                .get("errorCode")
                                .asString())
                .isEqualTo("COM-400-VALIDATION");
        // The Checker does not respond (X-06 EDIT is the Maker's), and nobody deletes a query.
        long id = raise(maker, staffResult, "Arrear missing", false, 201).get("id").asLong();
        respond(login(CHECKER, "PAYROLL_CHECKER", "DH001"), id, false, 403);
        int deleted =
                mvc.perform(userRequest(maker, delete("/v1/queries/" + id)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(deleted).isIn(403, 404, 405);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_query WHERE query_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(1);
    }

    /**
     * X-06 field table (SRS v0.5 l.836): the Payroll ID is mandatory, Numeric (12). A query without
     * one, with a null one, or with 13 digits is refused as a validation error and adds no
     * payroll_query row (BMAD TR-001); 12 digits pass validation and reach the lookup.
     */
    @Test
    void SRS_G_11_queryNeedsAPayrollIdOfAtMostTwelveDigits() throws Exception {
        Session maker = login(MAKER, "PAYROLL_MAKER", "DH001");
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM payroll_query", Integer.class);
        Map<String, Object> nullId = new HashMap<>();
        nullId.put("payrollId", null);
        nullId.put("queryText", "x");
        nullId.put("concernsComputedAmount", false);
        for (Object request :
                List.of(
                        Map.of("queryText", "x", "concernsComputedAmount", false),
                        nullId,
                        Map.of(
                                "payrollId",
                                1_234_567_890_123L,
                                "queryText",
                                "x",
                                "concernsComputedAmount",
                                false))) {
            assertThat(
                            body(mvc.perform(userPost(maker, "/v1/queries", request))
                                            .andExpect(status().isBadRequest())
                                            .andReturn())
                                    .get("errorCode")
                                    .asString())
                    .isEqualTo("COM-400-VALIDATION");
        }
        // The 12-digit boundary is a valid Payroll ID: it reaches the lookup (no such record).
        assertThat(raise(maker, 999_999_999_999L, "x", false, 422).get("errorCode").asString())
                .isEqualTo("QRY-422-PAYROLL-ID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payroll_query", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void SRS_G_11_staffSeeTheQueriesOfTheirOfficeFilteredByStatus() throws Exception {
        Session maker = login(MAKER, "PAYROLL_MAKER", "DH001");
        long open =
                raise(maker, staffResult, "Leave encashment missing", false, 201)
                        .get("id")
                        .asLong();
        long answered = raise(maker, staffResult, "DA arrear", false, 201).get("id").asLong();
        respond(maker, answered, false, 200);

        JsonNode all = all(maker, "/v1/queries");
        assertThat(texts(all, "id")).contains(String.valueOf(open), String.valueOf(answered));
        JsonNode inProgress = all(maker, "/v1/queries?status=IN_PROGRESS");
        assertThat(texts(inProgress, "status")).containsOnly("IN_PROGRESS");
        assertThat(texts(inProgress, "id"))
                .contains(String.valueOf(answered))
                .doesNotContain(String.valueOf(open));

        mvc.perform(userGet(maker, "/v1/queries/" + open))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.eis").value("105220"));
        // Another office sees neither the list entry nor the query itself.
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        JsonNode theirs = all(kolkata, "/v1/queries");
        assertThat(texts(theirs, "id")).doesNotContain(String.valueOf(open));
        int seen =
                mvc.perform(userGet(kolkata, "/v1/queries/" + open))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(seen).isIn(403, 404);
        // An unknown status value is a bad request, not an empty list.
        mvc.perform(userGet(maker, "/v1/queries?status=PARKED")).andExpect(status().isBadRequest());
    }

    @Test
    void SRS_G_11_theLifecycleOnlyMovesForward() throws Exception {
        Session maker = login(MAKER, "PAYROLL_MAKER", "DH001");
        long id = raise(maker, staffResult, "Night shift allowance", false, 201).get("id").asLong();
        // Open -> Resolved skips In Progress: refused, the Maker answers first.
        mvc.perform(userPost(maker, "/v1/queries/" + id + "/resolve", Map.of("resolution", "done")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("QRY-409-STATUS"));
        respond(maker, id, false, 200);
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/queries/" + id + "/resolve",
                                Map.of("resolution", "Explained")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        // Closed: no further response.
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/queries/" + id + "/respond",
                                Map.of("response", "again", "concernsComputedAmount", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("QRY-409-STATUS"));
    }

    @Test
    void SRS_G_13_theQueryListExportsAsCsvByStatusButNotForAMember() throws Exception {
        Session maker = login(MAKER, "PAYROLL_MAKER", "DH001");
        String reference =
                raise(maker, staffResult, "=HYPERLINK(\"x\") export check", false, 201)
                        .get("reference")
                        .asString();
        String csv = csv(maker, "/v1/queries/export?status=OPEN");
        assertThat(csv).startsWith("\"Query reference\",\"Payroll ID\"").contains(reference);
        // CSV injection: a query text starting with '=' is exported as text, not a formula.
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\") export check\"");
        // A member views the own queries (X-06 VIEW) but holds no EXPORT.
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        mvc.perform(userGet(member, "/v1/queries/export")).andExpect(status().isForbidden());
    }

    /**
     * SRS-G-11, SRS-NF-3: the register's tiles count each status over the whole scope (not one
     * page); a member's eSeva counts cover the own queries only. Seed V035_9: 120 queries at the
     * Chennai DO, every tenth In Progress.
     */
    @Test
    void SRS_G_11_theStatusTilesCountTheWholeScopeAndAMemberSeesOnlyTheOwn() throws Exception {
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        JsonNode counts =
                body(
                        mvc.perform(userGet(chennai, "/v1/queries/counts"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(counts.path("OPEN").asLong()).isGreaterThanOrEqualTo(108);
        assertThat(counts.path("IN_PROGRESS").asLong()).isGreaterThanOrEqualTo(12);
        long listed = all(chennai, "/v1/queries").size();
        long counted = 0;
        for (JsonNode n : counts) {
            counted += n.asLong();
        }
        assertThat(counted).isEqualTo(listed);
        // A member counts the own queries only.
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        JsonNode own =
                body(
                        mvc.perform(userGet(member, "/v1/queries/counts"))
                                .andExpect(status().isOk())
                                .andReturn());
        long ownTotal = 0;
        for (JsonNode n : own) {
            ownTotal += n.asLong();
        }
        assertThat(ownTotal).isEqualTo(all(member, "/v1/queries").size());
    }

    /**
     * SRS-G-11, SRS-NF-3: DDL payroll_query.payroll_result_ref is nullable; such queries (seed
     * V035_9 at the Chennai DO) are listed page by page without a Payroll ID, never a failed list.
     */
    @Test
    void SRS_G_11_aQueryWithoutAPayrollIdIsListedAndTheLongListPages() throws Exception {
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        JsonNode first =
                body(
                        mvc.perform(userGet(chennai, "/v1/queries?size=50"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(first.get("total").asLong()).isGreaterThan(100);
        assertThat(first.get("items")).hasSize(50);
        JsonNode all = all(chennai, "/v1/queries");
        assertThat(all).hasSize((int) first.get("total").asLong());
        JsonNode demo = null;
        for (JsonNode q : all) {
            if (q.get("queryText").asString().startsWith("Night shift allowance")) {
                demo = q;
                break;
            }
        }
        assertThat(demo).isNotNull();
        assertThat(demo.get("payrollId").isNull()).isTrue();
        assertThat(demo.get("payrollYymm").isNull()).isTrue();
        assertThat(demo.get("eis").asString()).startsWith("309");
    }
}
