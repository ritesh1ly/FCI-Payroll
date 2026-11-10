package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 13 phase 13e (plan docs/plan/13-arrears-retro-wage-revision.md): I-25 Wage Revision steps
 * 1 to 4 (SRS-8-25). The Rule Administrator at Headquarters defines a staff revision on its Active
 * order and simulates it (old pay, new pay, estimated arrear; no pay changed); the Payroll Checker
 * at Headquarters approves with the OTP WAGE_REVISION_HQ; never the definer; a unit user, a labour
 * revision and a selected scope are refused.
 */
class WageRevisionIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long circular() {
        return circular("WAGE_REVISION");
    }

    /** An Active all-office circular of {@code category} (CIRCULAR_CATEGORY). */
    private long circular(String category) {
        String number = "IT/WR/" + System.nanoTime();
        jdbc.update(
                "INSERT INTO document_attachment (entity_name, entity_key, file_name,"
                        + " storage_provider, storage_object_key, contains_pii, uploaded_by) VALUES"
                        + " ('circular_order', ?, 'it.pdf', 'ORACLE_SECUREFILE', ?, 'N', 'it')",
                number,
                "it/circular/" + number);
        jdbc.update(
                "INSERT INTO circular_order (circular_number, circular_date, subject,"
                    + " category_code, applies_all_offices, effective_from, document_attachment_id,"
                    + " circular_status, created_by) SELECT ?, ?, 'Wage revision (IT)', ?, 'Y', ?,"
                    + " attachment_id, 'ACTIVE', 'it.fixture' FROM document_attachment WHERE"
                    + " storage_object_key = ?",
                number,
                java.sql.Date.valueOf(LocalDate.now(IST)),
                category,
                java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)),
                "it/circular/" + number);
        return jdbc.queryForObject(
                "SELECT circular_order_id FROM circular_order WHERE circular_number = ?",
                Long.class,
                number);
    }

    private Map<String, Object> definition(long circular, List<String> types) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Pay revision (IT)");
        body.put("circularId", circular);
        body.put("payrollTypes", types);
        body.put("effectiveFrom", LocalDate.now(IST).minusMonths(2).withDayOfMonth(1).toString());
        body.put("fitment", "PERCENTAGE");
        body.put("value", 3);
        body.put("rounding", "NEXT_10");
        body.put("reason", "Order (IT)");
        return body;
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    /**
     * I-25 field table (SRS l.3164): the Revision Order is an Active circular of category Wage
     * Revision. A DA (or any other) circular is refused, so is a withdrawn Wage Revision order;
     * nothing is defined.
     */
    @Test
    void SRS_8_25_theRevisionOrderMustBeAnActiveCircularOfCategoryWageRevision() throws Exception {
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM wage_revision", Integer.class);
        MvcResult da =
                post(admin, "/v1/wage-revisions", definition(circular("DA"), List.of("STAFF_IDA")));
        assertThat(da.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(da)).isEqualTo("MST-422-CIRCULAR-CATEGORY");
        assertThat(body(da).path("args").path(1).asString()).isEqualTo("DA");
        assertThat(body(da).path("args").path(2).asString()).isEqualTo("WAGE_REVISION");
        long withdrawn = circular();
        jdbc.update(
                "UPDATE circular_order SET circular_status = 'WITHDRAWN' WHERE circular_order_id ="
                        + " ?",
                withdrawn);
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/wage-revisions",
                                        definition(withdrawn, List.of("STAFF_IDA")))))
                .as("an order no longer Active")
                .isEqualTo("MST-409-CIRCULAR-STATE");
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/wage-revisions",
                                        definition(987_654_321L, List.of("STAFF_IDA")))))
                .as("an unknown order")
                .isEqualTo("MST-409-CIRCULAR-STATE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wage_revision", Integer.class))
                .isEqualTo(before);
    }

    /**
     * SC-13-0062 (I-25 field table, Component Effective Dates: "One effective date per revised
     * component", mandatory; SRS-8-25 step 2): the date is held on the revised component's own row
     * (wage_revision_component.effective_from), so a component carries its date, never the revision
     * as a whole. For staff the revision revises the Basic only (the labour rate sets wait for the
     * labour engines, PENDING 13-i25): one component, one date. A definition without the date is
     * refused before anything is written.
     */
    @Test
    void SC_13_0062_eachRevisedComponentCarriesItsOwnMandatoryEffectiveDate() throws Exception {
        long order = circular();
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        long revisions = jdbc.queryForObject("SELECT COUNT(*) FROM wage_revision", Long.class);
        Map<String, Object> undated = definition(order, List.of("STAFF_IDA"));
        undated.remove("effectiveFrom");
        MvcResult refused = post(admin, "/v1/wage-revisions", undated);
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(refused)).isEqualTo("COM-400-VALIDATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wage_revision", Long.class))
                .as("nothing defined")
                .isEqualTo(revisions);

        Map<String, Object> dated = definition(order, List.of("STAFF_IDA"));
        LocalDate from = LocalDate.now(IST).minusMonths(3).withDayOfMonth(1);
        dated.put("effectiveFrom", from.toString());
        MvcResult defined = post(admin, "/v1/wage-revisions", dated);
        assertThat(defined.getResponse().getStatus())
                .as(defined.getResponse().getContentAsString())
                .isEqualTo(201);
        long id = body(defined).path("summary").path("id").asLong();
        List<Map<String, Object>> components =
                jdbc.queryForList(
                        "SELECT p.legacy_pay_code, c.effective_from FROM wage_revision_component c"
                                + " JOIN pay_component p ON p.component_id = c.component_id WHERE"
                                + " c.wage_revision_id = ?",
                        id);
        assertThat(components)
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(((Number) c.get("LEGACY_PAY_CODE")).intValue())
                                    .isEqualTo(101);
                            assertThat(c.get("EFFECTIVE_FROM").toString())
                                    .startsWith(from.toString());
                        });
    }

    /**
     * US-GAP-247 (SRS l.3145, SRS-8-25): the HQ Payroll Maker or Administrator defines the
     * revision; a Payroll Maker of a unit holds the right but is refused at its office
     * (WR-403-HQ-ONLY); the definer never approves the revision (SRS-G-1).
     */
    @Test
    void SRS_8_25_anHqPayrollMakerDefinesARevisionAnOfficeMakerIsRefused() throws Exception {
        long order = circular();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM app_role_permission rp JOIN app_role r ON"
                                        + " r.role_id = rp.role_id JOIN app_permission p ON"
                                        + " p.permission_id = rp.permission_id WHERE r.role_code ="
                                        + " 'PAYROLL_MAKER' AND p.screen_code = 'I-25' AND"
                                        + " p.action_code IN ('CREATE', 'EXPORT')",
                                Integer.class))
                .as("V077_29: the Payroll Maker defines and exports I-25")
                .isEqualTo(2);
        Session unit = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult refused =
                post(unit, "/v1/wage-revisions", definition(order, List.of("STAFF_IDA")));
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(refused)).as("only at Headquarters").isEqualTo("WR-403-HQ-ONLY");
    }

    // SC-13-0055: UC Define and Apply a Wage Revision (I-25), main flow (fixation in IncrementIT).
    @Test
    void SRS_8_25_headquartersDefinesSimulatesAndApprovesWithTheOtp() throws Exception {
        long order = circular();
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        Session checker = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        Session unit = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");

        assertThat(
                        post(unit, "/v1/wage-revisions", definition(order, List.of("STAFF_IDA")))
                                .getResponse()
                                .getStatus())
                .as("a unit desk defines nothing (WR-403-HQ-ONLY)")
                .isEqualTo(403);
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/wage-revisions",
                                        definition(order, List.of("DPS_LABOUR")))))
                .isEqualTo("WR-422-NOT-AVAILABLE");
        Map<String, Object> selected = definition(order, List.of("STAFF_IDA"));
        selected.put("scope", "SELECTED");
        assertThat(code(post(admin, "/v1/wage-revisions", selected)))
                .isEqualTo("WR-422-NOT-AVAILABLE");

        MvcResult defined =
                post(admin, "/v1/wage-revisions", definition(order, List.of("STAFF_IDA")));
        assertThat(defined.getResponse().getStatus())
                .as(defined.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode d = body(defined);
        long id = d.path("summary").path("id").asLong();
        assertThat(d.path("summary").path("status").asString()).isEqualTo("DEFINED");
        assertThat(d.path("steps")).hasSize(9);
        assertThat(
                        code(
                                post(
                                        admin,
                                        "/v1/wage-revisions/" + id + "/submissions",
                                        Map.of("remark", "x"))))
                .as("simulate first")
                .isEqualTo("WR-409-STATE");

        MvcResult simulated = post(admin, "/v1/wage-revisions/" + id + "/simulations", Map.of());
        assertThat(simulated.getResponse().getStatus())
                .as(simulated.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode s = body(simulated);
        assertThat(s.path("summary").path("status").asString()).isEqualTo("SIMULATED");
        assertThat(s.path("members").asLong()).isPositive();
        JsonNode member =
                body(mvc.perform(userGet(admin, "/v1/wage-revisions/" + id + "/members"))
                                .andReturn())
                        .path("items")
                        .get(0);
        assertThat(member.path("newPay").decimalValue())
                .isGreaterThan(member.path("oldPay").decimalValue());
        assertThat(member.path("newPay").decimalValue().remainder(java.math.BigDecimal.TEN))
                .as("rounded up to the next 10")
                .isZero();
        Long basicBefore =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM employee WHERE employee_id = (SELECT MIN(employee_id)"
                                + " FROM wage_revision_member WHERE wage_revision_id = ?) AND"
                                + " current_basic = (SELECT old_pay FROM wage_revision_member WHERE"
                                + " wage_revision_id = ? AND employee_id = (SELECT MIN(employee_id)"
                                + " FROM wage_revision_member WHERE wage_revision_id = ?))",
                        Long.class,
                        id,
                        id,
                        id);
        assertThat(basicBefore).as("the simulation changes no pay").isEqualTo(1L);

        MvcResult submitted =
                post(admin, "/v1/wage-revisions/" + id + "/submissions", Map.of("remark", "Order"));
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(submitted).get("approvalId").asLong();
        assertThat(decide(admin, approval, "APPROVE", null, null))
                .as("never the definer")
                .isEqualTo(403);
        // SRS-8-25 (BMAD TR-033): only Headquarters approves - a unit office's Payroll Checker is
        // refused, with or without the OTP, and the revision stays where it was.
        String before =
                jdbc.queryForObject(
                        "SELECT revision_status FROM wage_revision WHERE wage_revision_id = ?",
                        String.class,
                        id);
        Session unitChecker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decide(unitChecker, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(unitChecker, approval, "APPROVE", null, "WAGE_REVISION_HQ"))
                .isEqualTo(403);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision_status FROM wage_revision WHERE wage_revision_id"
                                        + " = ?",
                                String.class,
                                id))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(decide(checker, approval, "APPROVE", null, null)).as("OTP first").isEqualTo(428);
        assertThat(decide(checker, approval, "APPROVE", null, "WAGE_REVISION_HQ")).isEqualTo(200);
        JsonNode approved =
                body(mvc.perform(userGet(unit, "/v1/wage-revisions/" + id)).andReturn());
        assertThat(approved.path("summary").path("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.path("steps").get(3).path("status").asString()).isEqualTo("DONE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT otp_challenge_id FROM wage_revision WHERE wage_revision_id"
                                        + " = ?",
                                Long.class,
                                id))
                .isNotNull();
        assertThat(
                        body(mvc.perform(userGet(unit, "/v1/wage-revisions/" + id + "/members"))
                                        .andReturn())
                                .path("total")
                                .asLong())
                .as("a unit sees its own members only")
                .isLessThan(s.path("members").asLong());
        assertThat(
                        mvc.perform(
                                        userGet(
                                                admin,
                                                "/v1/wage-revisions/"
                                                        + id
                                                        + "/members/export?format=CSV"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(all(unit, "/v1/wage-revisions").findValuesAsString("code"))
                .anyMatch(c -> c.startsWith("WR-"));
    }
}
