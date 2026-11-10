package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.gov.fci.payroll.configuration.api.AllowanceConfiguration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The dated masters under maker-checker (R-RULE, R-MC): M-04 staff rate tables, M-22 special
 * compensatory and other rates, M-23 other parameters and M-24 accounting (GL) mapping. A version
 * is proposed at Headquarters by the Rule Administrator (M-04, M-22, M-23) or Finance (M-24,
 * SRS-G-7), in force only once the Payroll Checker at Headquarters approves it (the SRS use cases
 * and screen inventory; route versions of V033_3); the version it succeeds is end-dated the day
 * before; nothing is ever deleted. Dates are far in the future so the seeded versions in force are
 * untouched.
 */
class MastersIT extends ApiTestSupport {

    @Autowired private AllowanceConfiguration allowances;

    private Session admin() throws Exception {
        return login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
    }

    private Session approver() throws Exception {
        return login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
    }

    /** The M-24 maker: Finance at Headquarters (SRS-G-7, SRS-22-7). */
    private Session finance() throws Exception {
        return login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
    }

    private static Map<String, Object> line(String rateCode, String value, String unit) {
        Map<String, Object> l = new HashMap<>();
        l.put("rateCode", rateCode);
        l.put("rateValue", value);
        l.put("unit", unit);
        // M-04 field table: a staff rate line names its pay pattern.
        l.put("payPattern", "IDA");
        return l;
    }

    /** A version linked to an Active M-25 circular (SRS-1-62: a staff rate revision needs it). */
    private Map<String, Object> rateVersion(String from, List<Map<String, Object>> lines) {
        Long circular =
                jdbc.queryForObject(
                        "SELECT MIN(circular_order_id) FROM circular_order WHERE circular_status ="
                                + " 'ACTIVE'",
                        Long.class);
        Map<String, Object> body = new HashMap<>();
        body.put("effectiveFrom", from);
        body.put("lines", lines);
        body.put("reason", "IT: circular 2091");
        body.put("circularOrderId", circular);
        return body;
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private static int code(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String errorCode(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private JsonNode get(Session s, String path) throws Exception {
        return pagedOrWhole(
                s,
                path,
                body(mvc.perform(userGet(s, path)).andExpect(status().isOk()).andReturn()));
    }

    // ---------------------------------------------------------------- the drawn screens (OD-121)

    /** SRS-1-62, SRS-11-15, SRS-G-7: the reads the wireframe draws, each behind its own gate. */
    @Test
    void SRS_G_54_theDrawnReadsAnswerForTheScreenAndNoOneElse() throws Exception {
        Session admin = admin();
        assertThat(texts(get(admin, "/v1/masters/staff-rates/dearness-versions"), "code"))
                .contains("DA_IDA", "DA_CDA");
        assertThat(texts(get(admin, "/v1/masters/staff-rates/city-classes"), "cityClass"))
                .containsExactly("X", "Y", "Z", "DOUBLE");
        assertThat(texts(get(admin, "/v1/masters/special-rates?pattern=CDA"), "code"))
                .contains("HILL_CDA")
                .doesNotContain("HILL_IDA");
        assertThat(texts(get(admin, "/v1/masters/special-rates?group=OTHER_RATE_MASTER"), "code"))
                .contains("SIXTH_PAY_IDA")
                .doesNotContain("HILL_IDA");
        assertThat(texts(get(admin, "/v1/masters/accounting"), "code"))
                .containsExactly(
                        "NATURAL_ACCOUNT",
                        "AGENCY",
                        "CLASSIFICATION",
                        "COST_CENTRE",
                        "BUDGET_BILL_STATUS",
                        "BANK",
                        "CITY_TYPE");
        for (String code :
                List.of(
                        "NATURAL_ACCOUNT",
                        "AGENCY",
                        "CLASSIFICATION",
                        "COST_CENTRE",
                        "BUDGET_BILL_STATUS",
                        "BANK",
                        "CITY_TYPE")) {
            assertThat(
                            code(
                                    mvc.perform(
                                                    userGet(
                                                            admin,
                                                            "/v1/masters/accounting/"
                                                                    + code
                                                                    + "/records?page=0&size=10"))
                                            .andReturn()))
                    .as(code)
                    .isEqualTo(200);
        }
        assertThat(
                        code(
                                mvc.perform(
                                                userGet(
                                                        admin,
                                                        "/v1/masters/accounting/NO_SUCH/records"))
                                        .andReturn()))
                .isEqualTo(404);
        // A bad filter value is refused at the door.
        assertThat(
                        code(
                                mvc.perform(userGet(admin, "/v1/masters/special-rates?pattern=FND"))
                                        .andReturn()))
                .isEqualTo(400);
    }

    /** SRS M-23 table (FRS 2.3, R-SRC): no version of the HRMS-owned MHS row. */
    @Test
    void SRS_M23_anHrmsOwnedParameterIsRefused() throws Exception {
        MvcResult refused =
                post(
                        admin(),
                        "/v1/masters/other-parameters/MHS_CONTRIBUTION/versions",
                        parameter("2096-01-01", Map.of("numericValue", "100")));
        assertThat(code(refused)).isEqualTo(422);
        assertThat(errorCode(refused)).isEqualTo("MST-422-HRMS-OWNED");
    }

    // ---------------------------------------------------------------- M-04

    // SC-16-0086: an Active M-25 circular is linked before submission (SRS-1-62).
    @Test
    void SRS_1_62_aStaffRateRevisionWithoutAnActiveCircularIsRefused() throws Exception {
        Session admin = admin();
        Map<String, Object> none =
                new HashMap<>(rateVersion("2095-01-01", List.of(line("HRA_X", "29", "PERCENT"))));
        none.remove("circularOrderId");
        // Wave D review 6: no circular is a required field, not a circular "null".
        assertThat(errorCode(post(admin, "/v1/masters/staff-rates/HRA/versions", none)))
                .isEqualTo("MST-422-CIRCULAR-REQUIRED");
        none.put("circularOrderId", 987_654_321L);
        assertThat(errorCode(post(admin, "/v1/masters/staff-rates/HRA/versions", none)))
                .as("an unknown circular")
                .isEqualTo("MST-409-CIRCULAR-STATE");
    }

    /**
     * M-04 field table (SRS l.3653): a staff rate line without its pay pattern is refused; an M-22
     * line may leave it blank (that field table is silent), so only the staff screen asks.
     */
    @Test
    void M04_fieldTable_aStaffRateLineWithoutItsPayPatternIsRefused() throws Exception {
        Session admin = admin();
        Map<String, Object> blank = line("HRA_X", "29", "PERCENT");
        blank.remove("payPattern");
        MvcResult missing =
                post(
                        admin,
                        "/v1/masters/staff-rates/HRA/versions",
                        rateVersion("2096-01-01", List.of(blank)));
        assertThat(code(missing)).isEqualTo(422);
        assertThat(errorCode(missing)).isEqualTo("MST-422-LINES");
        blank.put("payPattern", "");
        assertThat(
                        errorCode(
                                post(
                                        admin,
                                        "/v1/masters/staff-rates/HRA/versions",
                                        rateVersion("2096-01-01", List.of(blank)))))
                .as("an empty pattern is no pattern")
                .isEqualTo("MST-422-LINES");
        // A blank pattern never reaches the rule: the request's own validation refuses it first.
        blank.put("payPattern", " ");
        assertThat(
                        errorCode(
                                post(
                                        admin,
                                        "/v1/masters/staff-rates/HRA/versions",
                                        rateVersion("2096-01-01", List.of(blank)))))
                .isEqualTo("COM-400-VALIDATION");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rate_set WHERE rate_set_code = 'HRA'"
                                        + " AND effective_from = DATE '2096-01-01'",
                                Integer.class))
                .isZero();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.configuration.api.PayConfiguration payConfiguration;

    // SRS-1-3 (US-GAP-41): "Hold HRA rates for CDA-pattern employees under their own rule."
    @Test
    void SRS_1_3_theCdaHraRuleIsItsOwnM04RateSetReadOnlyForCdaStaff() throws Exception {
        Session admin = admin();
        JsonNode cda = find(get(admin, "/v1/masters/staff-rates"), "code", "HRA_CDA");
        assertThat(cda.isMissingNode()).isFalse();
        Map<String, Object> x = line("HRA_PCT", "26", "PERCENT");
        x.put("payPattern", "CDA");
        x.put("cityClass", "X");
        MvcResult proposed =
                post(
                        admin,
                        "/v1/masters/staff-rates/HRA_CDA/versions",
                        rateVersion("2093-01-01", List.of(x)));
        assertThat(code(proposed)).as(proposed.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(
                        decide(
                                approver(),
                                body(proposed).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        java.time.LocalDate day = java.time.LocalDate.of(2093, 2, 1);
        in.gov.fci.payroll.configuration.api.PayConfiguration.Snapshot snapshot =
                payConfiguration.staffConfiguration(day);
        assertThat(snapshot.hra("CDA", "X"))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("26"));
        assertThat(snapshot.hra("FND", "X"))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("26"));
        assertThat(snapshot.hra("IDA", "X"))
                .as("the IDA rule is not the CDA one")
                .isNotEqualTo(snapshot.hra("CDA", "X"));
        assertThat(snapshot.rateSetVersions()).containsKey("HRA_CDA");
    }

    // SRS-16-2: the staff DA / HRA / CCA rate tables by city class are maintained as dated masters.
    // SC-16-0079: the Maintain Staff Rate Tables main flow (propose, approve, in force).
    @Test
    void M_04_aStaffRateVersionIsInForceOnlyOnceApprovedAndEndsItsPredecessor() throws Exception {
        Session admin = admin();
        JsonNode list = get(admin, "/v1/masters/staff-rates");
        assertThat(texts(list, "code")).contains("DA_IDA", "DA_CDA", "HRA", "CCA");

        MvcResult first =
                post(
                        admin,
                        "/v1/masters/staff-rates/HRA/versions",
                        rateVersion("2091-01-01", List.of(line("HRA_X", "30", "PERCENT"))));
        assertThat(code(first)).isEqualTo(202);
        JsonNode v1 = body(first);
        assertThat(v1.get("state").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(v1.get("lines")).hasSize(1);
        // Pending: shown as the proposal, not in force.
        JsonNode hra = find(get(admin, "/v1/masters/staff-rates"), "code", "HRA");
        assertThat(hra.path("pending").path("effectiveFrom").asString()).isEqualTo("2091-01-01");

        // The proposer cannot approve the own proposal (segregation of duties).
        assertThat(decide(admin, v1.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(403);
        assertThat(decide(approver(), v1.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);

        // A later version: same date refused (not after the approved one), then a free date.
        MvcResult early =
                post(
                        admin,
                        "/v1/masters/staff-rates/HRA/versions",
                        rateVersion("2091-01-01", List.of(line("HRA_X", "27", "PERCENT"))));
        assertThat(code(early)).isEqualTo(422);
        assertThat(errorCode(early)).isEqualTo("MST-422-DATE");
        JsonNode v2 =
                body(
                        post(
                                admin,
                                "/v1/masters/staff-rates/HRA/versions",
                                rateVersion(
                                        "2092-04-01", List.of(line("HRA_X", "27", "PERCENT")))));
        // A second proposal on the same date as the pending one is refused.
        MvcResult taken =
                post(
                        admin,
                        "/v1/masters/staff-rates/HRA/versions",
                        rateVersion("2092-04-01", List.of(line("HRA_X", "24", "PERCENT"))));
        assertThat(code(taken)).isEqualTo(409);
        assertThat(errorCode(taken)).isEqualTo("MST-409-DATE");
        assertThat(decide(approver(), v2.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);

        JsonNode history = get(admin, "/v1/masters/staff-rates/HRA/versions");
        JsonNode newest = history.get(0);
        JsonNode previous = find(history, "effectiveFrom", "2091-01-01");
        assertThat(newest.get("effectiveFrom").asString()).isEqualTo("2092-04-01");
        assertThat(newest.get("state").asString()).isEqualTo("APPROVED");
        assertThat(previous.get("state").asString()).isEqualTo("APPROVED");
        assertThat(previous.get("effectiveTo").asString()).isEqualTo("2092-03-31");
        assertThat(newest.get("versionNo").asInt())
                .isEqualTo(previous.get("versionNo").asInt() + 1);
    }

    @Test
    void M_04_aRejectedVersionStaysOnRecordAndFreesNothingInForce() throws Exception {
        Session admin = admin();
        JsonNode v =
                body(
                        post(
                                admin,
                                "/v1/masters/staff-rates/CCA/versions",
                                rateVersion("2093-01-01", List.of(line("CCA_A", "900", "INR")))));
        assertThat(
                        decide(
                                approver(),
                                v.get("approvalId").asLong(),
                                "REJECT",
                                "Circular not issued",
                                null))
                .isEqualTo(200);
        JsonNode rejected =
                find(
                        get(admin, "/v1/masters/staff-rates/CCA/versions"),
                        "id",
                        v.get("id").asString());
        assertThat(rejected.get("state").asString()).isEqualTo("REJECTED");
        // The rejected date stays taken (versions are never deleted).
        MvcResult again =
                post(
                        admin,
                        "/v1/masters/staff-rates/CCA/versions",
                        rateVersion("2093-01-01", List.of(line("CCA_A", "800", "INR"))));
        assertThat(errorCode(again)).isEqualTo("MST-409-DATE");
    }

    @Test
    void M_04_invalidLinesAreRefusedBeforeAnyApproval() throws Exception {
        Session admin = admin();
        String path = "/v1/masters/staff-rates/DA_CDA/versions";
        // A percentage above 100.
        assertThat(
                        errorCode(
                                post(
                                        admin,
                                        path,
                                        rateVersion(
                                                "2094-01-01",
                                                List.of(line("DA", "101", "PERCENT"))))))
                .isEqualTo("MST-422-LINES");
        // A negative rate.
        assertThat(
                        errorCode(
                                post(
                                        admin,
                                        path,
                                        rateVersion(
                                                "2094-01-01", List.of(line("DA", "-1", "INR"))))))
                .isEqualTo("MST-422-LINES");
        // The same key twice.
        assertThat(
                        errorCode(
                                post(
                                        admin,
                                        path,
                                        rateVersion(
                                                "2094-01-01",
                                                List.of(
                                                        line("DA", "10", "PERCENT"),
                                                        line("DA", "11", "PERCENT"))))))
                .isEqualTo("MST-422-LINES");
        // A slab whose upper bound is not above its lower bound.
        Map<String, Object> slab = line("DA", "10", "PERCENT");
        slab.put("basicSlabFrom", "50000");
        slab.put("basicSlabTo", "50000");
        assertThat(errorCode(post(admin, path, rateVersion("2094-01-01", List.of(slab)))))
                .isEqualTo("MST-422-LINES");
        // Bean validation: no lines, an unknown city class, a malformed rate code.
        assertThat(code(post(admin, path, rateVersion("2094-01-01", List.of())))).isEqualTo(400);
        Map<String, Object> city = line("DA", "10", "PERCENT");
        city.put("cityClass", "Q");
        assertThat(code(post(admin, path, rateVersion("2094-01-01", List.of(city)))))
                .isEqualTo(400);
        assertThat(
                        code(
                                post(
                                        admin,
                                        path,
                                        rateVersion(
                                                "2094-01-01", List.of(line("da", "1", "INR"))))))
                .isEqualTo(400);
        // Nothing was proposed.
        assertThat(texts(get(admin, "/v1/masters/staff-rates/DA_CDA/versions"), "effectiveFrom"))
                .doesNotContain("2094-01-01");
    }

    @Test
    void M_04_M_22_aMasterIsMaintainedOnItsOwnScreenOnly() throws Exception {
        Session admin = admin();
        MvcResult unknown =
                post(
                        admin,
                        "/v1/masters/staff-rates/NO_SUCH_RATE/versions",
                        rateVersion("2095-01-01", List.of(line("XX", "1", "INR"))));
        assertThat(code(unknown)).isEqualTo(404);
        assertThat(errorCode(unknown)).isEqualTo("MST-404-CATALOGUE");
        // A special rate is not on the staff screen, and the other way round.
        assertThat(
                        code(
                                mvc.perform(
                                                userGet(
                                                        admin,
                                                        "/v1/masters/staff-rates/BAD_CLIMATE_IDA/versions"))
                                        .andReturn()))
                .isEqualTo(404);
        assertThat(
                        code(
                                mvc.perform(
                                                userGet(
                                                        admin,
                                                        "/v1/masters/special-rates/HRA/versions"))
                                        .andReturn()))
                .isEqualTo(404);
    }

    // ---------------------------------------------------------------- M-22

    @Test
    void M_22_aSpecialRateIsProposedAndApproved() throws Exception {
        Session admin = admin();
        assertThat(texts(get(admin, "/v1/masters/special-rates"), "code"))
                .contains("BAD_CLIMATE_IDA");
        JsonNode v =
                body(
                        post(
                                admin,
                                "/v1/masters/special-rates/BAD_CLIMATE_IDA/versions",
                                rateVersion(
                                        "2091-07-01",
                                        List.of(line("BAD_CLIMATE", "1200", "INR")))));
        assertThat(decide(approver(), v.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode newest = get(admin, "/v1/masters/special-rates/BAD_CLIMATE_IDA/versions").get(0);
        assertThat(newest.get("state").asString()).isEqualTo("APPROVED");
        assertThat(newest.get("lines").get(0).get("rateValue").decimalValue())
                .isEqualByComparingTo("1200");
    }

    // SRS l.2819 / SRS-G-38 (US-GAP-211): an M-22 rate change is linked to its Active circular.
    @Test
    void SRS_11_15_anM22RateChangeWithoutAnActiveCircularIsRefused() throws Exception {
        Session admin = admin();
        Map<String, Object> none =
                new HashMap<>(
                        rateVersion("2096-01-01", List.of(line("BAD_CLIMATE", "1300", "INR"))));
        none.remove("circularOrderId");
        assertThat(
                        errorCode(
                                post(
                                        admin,
                                        "/v1/masters/special-rates/BAD_CLIMATE_IDA/versions",
                                        none)))
                .isEqualTo("MST-422-CIRCULAR-REQUIRED");
        none.put("circularOrderId", 987_654_321L);
        assertThat(
                        errorCode(
                                post(
                                        admin,
                                        "/v1/masters/special-rates/BAD_CLIMATE_IDA/versions",
                                        none)))
                .as("an unknown circular")
                .isEqualTo("MST-409-CIRCULAR-STATE");
    }

    // ---------------------------------------------------------------- M-23

    private static Map<String, Object> parameter(String from, Map<String, Object> values) {
        Map<String, Object> p = new HashMap<>(values);
        p.put("effectiveFrom", from);
        p.put("confirmed", true);
        p.put("reason", "IT: parameter");
        return p;
    }

    // SC-01-0008: platform parameters are dated settings in their master, never constants in code.
    // SC-01-0072 (UC main flow l.693): captured with its date and reason, approved by the Checker.
    @Test
    void M_23_anOtherParameterVersionEndsItsPredecessorOnceApproved() throws Exception {
        Session admin = admin();
        assertThat(texts(get(admin, "/v1/masters/other-parameters"), "code"))
                .contains("DEPUTATION_PCT_WITHIN");
        String path = "/v1/masters/other-parameters/DEPUTATION_PCT_WITHIN/versions";
        JsonNode v1 =
                body(post(admin, path, parameter("2091-01-01", Map.of("percentValue", "10"))));
        assertThat(v1.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(decide(approver(), v1.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode v2 =
                body(post(admin, path, parameter("2092-01-01", Map.of("percentValue", "12.5"))));
        assertThat(decide(approver(), v2.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode history = get(admin, path);
        assertThat(find(history, "effectiveFrom", "2091-01-01").get("effectiveTo").asString())
                .isEqualTo("2091-12-31");
        assertThat(history.get(0).get("percentValue").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(history.get(0).get("status").asString()).isEqualTo("APPROVED");

        // A rejected proposal changes nothing in force.
        JsonNode v3 =
                body(post(admin, path, parameter("2093-01-01", Map.of("percentValue", "15"))));
        assertThat(
                        decide(
                                approver(),
                                v3.get("approvalId").asLong(),
                                "REJECT",
                                "Not notified",
                                null))
                .isEqualTo(200);
        assertThat(
                        find(get(admin, path), "effectiveFrom", "2092-01-01")
                                .get("effectiveTo")
                                .isNull())
                .isTrue();

        // SRS-G-2: the next approved version ends only the version in force; the closed first
        // version keeps its own end and the rejected one is never revived or ended.
        JsonNode v4 =
                body(post(admin, path, parameter("2094-01-01", Map.of("percentValue", "16"))));
        assertThat(decide(approver(), v4.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode after = get(admin, path);
        assertThat(find(after, "effectiveFrom", "2092-01-01").get("effectiveTo").asString())
                .isEqualTo("2093-12-31");
        assertThat(find(after, "effectiveFrom", "2091-01-01").get("effectiveTo").asString())
                .isEqualTo("2091-12-31");
        assertThat(find(after, "effectiveFrom", "2093-01-01").get("status").asString())
                .isEqualTo("REJECTED");
        assertThat(find(after, "effectiveFrom", "2093-01-01").get("effectiveTo").isNull()).isTrue();
    }

    /**
     * SC-01-0074 (UC postcondition l.699; SRS-19-3 l.1859): a platform parameter is versioned, and
     * a period computed later resolves the version that was in force for that period. Two approved
     * NPA_PCT versions (2097 and 2098): the computation's configuration read for a day of each year
     * (as the engine reads it, {@code AllowanceConfiguration.on}) takes that year's percent, on
     * both sides of the change day; a pending proposal is never read.
     */
    @Test
    void SC_01_0074_aRecomputedPeriodResolvesThePlatformParameterVersionInForce() throws Exception {
        Session admin = admin();
        String path = "/v1/masters/other-parameters/NPA_PCT/versions";
        for (String[] v : new String[][] {{"2097-01-01", "20"}, {"2098-01-01", "22.5"}}) {
            JsonNode proposed =
                    body(post(admin, path, parameter(v[0], Map.of("percentValue", v[1]))));
            assertThat(proposed.get("status").asString()).isEqualTo("PENDING_APPROVAL");
            assertThat(
                            decide(
                                    approver(),
                                    proposed.get("approvalId").asLong(),
                                    "APPROVE",
                                    null,
                                    null))
                    .isEqualTo(200);
            admin = admin();
        }
        // A third version still waiting for the Checker governs nothing.
        JsonNode pending =
                body(post(admin, path, parameter("2099-01-01", Map.of("percentValue", "30"))));
        assertThat(pending.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        for (String[] at :
                new String[][] {
                    {"2097-06-30", "20"},
                    {"2097-12-31", "20"},
                    {"2098-01-01", "22.5"},
                    {"2099-03-01", "22.5"}
                }) {
            assertThat(allowances.on(java.time.LocalDate.parse(at[0])).npaPercent())
                    .as(at[0])
                    .isEqualByComparingTo(at[1]);
        }
        assertThat(
                        decide(
                                approver(),
                                pending.get("approvalId").asLong(),
                                "REJECT",
                                "IT: clean-up",
                                null))
                .isEqualTo(200);
    }

    @Test
    void M_23_aParameterNeedsAValueAndSaneBounds() throws Exception {
        Session admin = admin();
        String path = "/v1/masters/other-parameters/DEPUTATION_PCT_WITHIN/versions";
        assertThat(errorCode(post(admin, path, parameter("2096-01-01", Map.of()))))
                .isEqualTo("COM-400-VALIDATION");
        assertThat(
                        errorCode(
                                post(
                                        admin,
                                        path,
                                        parameter("2096-01-01", Map.of("percentValue", "100.01")))))
                .isEqualTo("COM-400-VALIDATION");
        assertThat(
                        errorCode(
                                post(
                                        admin,
                                        path,
                                        parameter("2096-01-01", Map.of("numericValue", "-5")))))
                .isEqualTo("COM-400-VALIDATION");
        assertThat(
                        errorCode(
                                post(
                                        admin,
                                        path,
                                        parameter("2096-01-01", Map.of("ceilingValue", "-1")))))
                .isEqualTo("COM-400-VALIDATION");
        assertThat(
                        code(
                                post(
                                        admin,
                                        path,
                                        parameter("2096-01-01", Map.of("windowFromTime", "9:00")))))
                .isEqualTo(400);
        assertThat(code(post(admin, path, parameter("2096-01-01", Map.of("instalmentCount", 0)))))
                .isEqualTo(400);
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/masters/other-parameters/NO_SUCH/versions",
                                        parameter("2096-01-01", Map.of("numericValue", "1")))))
                .isEqualTo(404);
        // A window and an instalment count are values in their own right.
        assertThat(
                        code(
                                post(
                                        admin,
                                        path,
                                        parameter(
                                                "2096-01-01",
                                                Map.of(
                                                        "windowFromTime",
                                                        "09:00",
                                                        "windowToTime",
                                                        "17:30",
                                                        "instalmentCount",
                                                        12)))))
                .isEqualTo(202);
    }

    // ---------------------------------------------------------------- M-24

    @Test
    void M_24_aGlMappingIsProposedApprovedAndSucceeded() throws Exception {
        Session admin = finance();
        int payCode =
                jdbc.queryForObject(
                        "SELECT MIN(legacy_pay_code) FROM pay_component WHERE is_retired = 'N'",
                        Integer.class);
        JsonNode rows = get(admin, "/v1/masters/gl-mappings");
        assertThat(texts(rows, "payCode")).contains(String.valueOf(payCode));
        String path = "/v1/masters/gl-mappings/" + payCode + "/versions";
        JsonNode v1 =
                body(
                        post(
                                admin,
                                path,
                                Map.of(
                                        "effectiveFrom",
                                        "2091-01-01",
                                        "naturalAccount",
                                        "4101-01",
                                        "reason",
                                        "IT")));
        assertThat(v1.get("naturalAccount").asString()).isEqualTo("4101-01");
        assertThat(decide(approver(), v1.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode v2 =
                body(
                        post(
                                admin,
                                path,
                                Map.of(
                                        "effectiveFrom",
                                        "2092-01-01",
                                        "naturalAccount",
                                        "4101-02",
                                        "reason",
                                        "IT")));
        assertThat(decide(approver(), v2.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode history = get(admin, path);
        assertThat(history.get(0).get("naturalAccount").asString()).isEqualTo("4101-02");
        assertThat(find(history, "effectiveFrom", "2091-01-01").get("effectiveTo").asString())
                .isEqualTo("2091-12-31");
        // Pending proposals show on the list.
        body(
                post(
                        admin,
                        path,
                        Map.of(
                                "effectiveFrom",
                                "2093-01-01",
                                "naturalAccount",
                                "4101-03",
                                "reason",
                                "IT")));
        JsonNode row =
                find(get(admin, "/v1/masters/gl-mappings"), "payCode", String.valueOf(payCode));
        assertThat(row.path("pending").path("naturalAccount").asString()).isEqualTo("4101-03");
    }

    @Test
    void M_24_aMalformedAccountUnknownOrRetiredComponentIsRefused() throws Exception {
        Session admin = finance();
        Integer payCode =
                jdbc.queryForObject(
                        "SELECT MIN(legacy_pay_code) FROM pay_component WHERE is_retired = 'N'",
                        Integer.class);
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/masters/gl-mappings/" + payCode + "/versions",
                                        Map.of(
                                                "effectiveFrom",
                                                "2097-01-01",
                                                "naturalAccount",
                                                "acct",
                                                "reason",
                                                "IT"))))
                .isEqualTo(400);
        MvcResult unknown =
                post(
                        admin,
                        "/v1/masters/gl-mappings/999998/versions",
                        Map.of(
                                "effectiveFrom",
                                "2097-01-01",
                                "naturalAccount",
                                "4101-01",
                                "reason",
                                "IT"));
        assertThat(errorCode(unknown)).isEqualTo("MST-404-CATALOGUE");
        Integer retired =
                jdbc.queryForObject(
                        "SELECT MIN(legacy_pay_code) FROM pay_component WHERE is_retired = 'Y'",
                        Integer.class);
        if (retired != null) {
            assertThat(
                            errorCode(
                                    post(
                                            admin,
                                            "/v1/masters/gl-mappings/" + retired + "/versions",
                                            Map.of(
                                                    "effectiveFrom",
                                                    "2097-01-01",
                                                    "naturalAccount",
                                                    "4101-01",
                                                    "reason",
                                                    "IT"))))
                    .isEqualTo("COM-400-VALIDATION");
        }
    }

    // ---------------------------------------------------------------- the SRS routes (V033_3)

    // SRS-16-17: a master's maker and approver are set by Access Group configuration.
    @Test
    void
            SRS_G_7_SRS_G_24_onlyFinanceAtHqProposesAnAccountingMappingAndOnlyThePayrollCheckerApproves()
                    throws Exception {
        int payCode =
                jdbc.queryForObject(
                        "SELECT MAX(legacy_pay_code) FROM pay_component WHERE is_retired = 'N'",
                        Integer.class);
        String path = "/v1/masters/gl-mappings/" + payCode + "/versions";
        Map<String, Object> mapping =
                Map.of("effectiveFrom", "2093-01-01", "naturalAccount", "4101-07", "reason", "IT");
        // The Rule Administrator no longer proposes on M-24 (its CREATE ended): Finance is the
        // maker.
        assertThat(errorCode(post(admin(), path, mapping))).isEqualTo("AUTHZ-403-PERMISSION");
        // Finance away from Headquarters: the route is maintained at HQ only.
        Session mumbai = login("finance.mumbai@fci-demo.test", "FINANCE", "MR001");
        assertThat(errorCode(post(mumbai, path, mapping))).isEqualTo("AUTHZ-403-HQ-ONLY");

        long approvalId = body(post(finance(), path, mapping)).get("approvalId").asLong();
        // The Rule Approver holds no level of the route: refused on the role, not on identity.
        Session ruleApprover = login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
        assertThat(decide(ruleApprover, approvalId, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(approver(), approvalId, "APPROVE", null, null)).isEqualTo(200);
        JsonNode row =
                find(
                        get(approver(), "/v1/masters/gl-mappings"),
                        "payCode",
                        String.valueOf(payCode));
        assertThat(row.path("pending").isNull()).as("nothing left pending").isTrue();
    }

    @Test
    void SRS_G_1_aRateVersionIsApprovedByThePayrollCheckerNeverTheRuleApprover() throws Exception {
        MvcResult proposed =
                post(
                        admin(),
                        "/v1/masters/staff-rates/HRA/versions",
                        rateVersion("2094-01-01", List.of(line("HRA_X", "1", "PERCENT"))));
        assertThat(code(proposed)).isEqualTo(202);
        long approvalId = body(proposed).get("approvalId").asLong();
        Session ruleApprover = login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
        assertThat(decide(ruleApprover, approvalId, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(approver(), approvalId, "REJECT", "IT: route check only", null))
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------- access

    @Test
    void noMasterVersionIsProposedByPayrollStaffOrTheApprover() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        // Payroll staff may read the rates in force (RBAC configuration) but never propose one.
        assertThat(
                        code(
                                post(
                                        maker,
                                        "/v1/masters/other-parameters/DEPUTATION_PCT_WITHIN/versions",
                                        parameter("2098-01-01", Map.of("percentValue", "1")))))
                .isEqualTo(403);
        assertThat(
                        code(
                                post(
                                        maker,
                                        "/v1/masters/gl-mappings/101/versions",
                                        Map.of(
                                                "effectiveFrom",
                                                "2098-01-01",
                                                "naturalAccount",
                                                "4101-01",
                                                "reason",
                                                "IT"))))
                .isEqualTo(403);
        assertThat(
                        code(
                                post(
                                        maker,
                                        "/v1/masters/staff-rates/HRA/versions",
                                        rateVersion(
                                                "2098-01-01",
                                                List.of(line("HRA_X", "1", "PERCENT"))))))
                .isEqualTo(403);
        Session approver = approver();
        mvc.perform(userGet(approver, "/v1/masters/staff-rates"))
                .andExpect(jsonPath("$.items").isArray());
        assertThat(
                        code(
                                post(
                                        approver,
                                        "/v1/masters/staff-rates/HRA/versions",
                                        rateVersion(
                                                "2098-01-01",
                                                List.of(line("HRA_X", "1", "PERCENT"))))))
                .isEqualTo(403);
    }

    /** docs/plan/X-pagination.md: a master list is served one page at a time, sizes as offered. */
    @Test
    void SRS_NF_3_aMasterListIsServedAPageAtATimeAndAForgedSizeIsRefused() throws Exception {
        Session admin = admin();
        JsonNode first =
                body(
                        mvc.perform(userGet(admin, "/v1/masters/staff-rates?page=0&size=10"))
                                .andExpect(status().isOk())
                                .andReturn());
        long total = first.get("total").asLong();
        assertThat(first.get("size").asInt()).isEqualTo(10);
        assertThat(first.get("items").size()).isEqualTo((int) Math.min(10, total));
        assertThat(all(admin, "/v1/masters/staff-rates")).hasSize((int) total);
        // Past the last page: no rows, the true total.
        JsonNode past =
                body(
                        mvc.perform(userGet(admin, "/v1/masters/staff-rates?page=999&size=10"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(past.get("items").isEmpty()).isTrue();
        assertThat(past.get("total").asLong()).isEqualTo(total);
        for (String forged : List.of("size=25", "size=1000", "size=0", "page=-1")) {
            mvc.perform(userGet(admin, "/v1/masters/staff-rates?" + forged))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("COM-400-PAGE"));
        }
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (n.path(field).asString().equals(value)) {
                return n;
            }
        }
        throw new AssertionError("no element with " + field + " = " + value);
    }
}
