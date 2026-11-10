package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * I-04 loan sanction by the Payroll Maker (plan 09 phase 09b; SRS-12-1, SRS-12-6, SRS-12-7,
 * SRS-12-8, SRS-12-11 … 12-14, SRS-12-17, SRS-12-18) on the demo limits (V042_3_9: deduction
 * ceiling 50 %, Festival Advance 25,000 in at most 10, one open at a time). The member's pay is a
 * fixture result (gross 40,000, deductions 5,000) of March 1998.
 */
class LoanSanctionIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper JSON = new ObjectMapper();

    private String eis;
    private String start;

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    @BeforeEach
    void member() {
        member("", 7);
    }

    /**
     * Picks the test's member ({@code eis}): a DH001 member in service with no loan and no loan
     * request, the {@code offset}-th from the end of the list that also meets {@code criterion},
     * with a locked March 1998 result (gross 40,000, deductions 5,000) as its pay.
     */
    private void member(String criterion, int offset) {
        Map<String, Object> m =
                jdbc.queryForMap(
                        "SELECT e.employee_id, e.emp_num, e.location_id FROM employee e JOIN"
                                + " org_location l ON l.location_id = e.location_id WHERE"
                                + " l.location_code = 'DH001' AND e.employment_status ="
                                + " 'IN_SERVICE' AND e.record_status = 'ACTIVE' AND NOT EXISTS"
                                + " (SELECT 1 FROM loan_header h WHERE h.employee_id ="
                                + " e.employee_id) AND NOT EXISTS (SELECT 1 FROM approval_queue q"
                                + " WHERE q.entity_name = 'loan_request' AND q.entity_key LIKE"
                                + " CONCAT(CAST(e.employee_id AS VARCHAR(20)), '|%'))"
                                + criterion
                                + " ORDER BY e.emp_num DESC OFFSET "
                                + offset
                                + " ROWS FETCH NEXT 1 ROWS ONLY");
        eis = (String) m.get("EMP_NUM");
        long id = ((Number) m.get("EMPLOYEE_ID")).longValue();
        long unit = locationId("DH001");
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                    + " fin_year, cycle_status, initiated_by) SELECT '199803', ?, 'DO', '1997-98',"
                    + " 'COMPUTED', 'it.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM"
                    + " payroll_cycle_instance WHERE payroll_yymm = '199803' AND org_unit_id = ?)",
                unit,
                unit);
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                    + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                    + " total_deductions, net_pay, result_status, is_locked) SELECT (SELECT"
                    + " cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '199803'"
                    + " AND org_unit_id = ?), ?, ?, 'STAFF', '199803', DATE '1998-03-01', 'ACTUAL',"
                    + " 40000, 5000, 35000, 'CURRENT', 'Y' FROM dual WHERE NOT EXISTS (SELECT 1"
                    + " FROM payroll_result WHERE employee_id = ? AND payroll_yymm = '199803')",
                unit,
                unit,
                id,
                id);
        YearMonth next = YearMonth.now(IST).plusMonths(1);
        start = String.format("%04d%02d", next.getYear(), next.getMonthValue());
    }

    private Map<String, Object> body(String eis, String amount, int n, String instalment) {
        Map<String, Object> b = new HashMap<>();
        b.put("eis", eis);
        b.put("loanType", "FESTIVAL_ADVANCE");
        b.put("sanctioned", new java.math.BigDecimal(amount));
        b.put("principalInstalments", n);
        b.put("instalment", new java.math.BigDecimal(instalment));
        b.put("interestInstalments", 0);
        b.put("startYymm", start);
        return b;
    }

    private MvcResult preview(Session s, Map<String, Object> b) throws Exception {
        return mvc.perform(userPost(s, "/v1/loans/sanctions/previews", b)).andReturn();
    }

    private MvcResult submit(Session s, Map<String, Object> b, boolean withFile) throws Exception {
        var request =
                multipart("/v1/loans/sanctions")
                        .file(
                                new MockMultipartFile(
                                        "sanction",
                                        "",
                                        "application/json",
                                        JSON.writeValueAsBytes(b)))
                        .file(
                                new MockMultipartFile(
                                        "file",
                                        "sanction-order.pdf",
                                        "application/pdf",
                                        withFile
                                                ? "%PDF-1.7 sanction order"
                                                        .getBytes(StandardCharsets.US_ASCII)
                                                : new byte[0]))
                        .param("documentType", "SANCTION_ORDER")
                        .param("reference", "DH/FA/" + eis)
                        .param("orderDate", "2026-08-12")
                        .param("issuingAuthority", "AGM (Pay) Delhi");
        return mvc.perform(
                        userRequest(s, request)
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private static String code(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString()).path("errorCode").asString();
    }

    @Test
    void SRS_12_11_thePreviewShowsTheScheduleTheRoomAndReducesABreachingInstalment()
            throws Exception {
        Session maker = maker();
        MvcResult ok = preview(maker, body(eis, "20000", 4, "5000"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode p = body(ok);
        assertThat(p.path("schedule")).hasSize(4);
        assertThat(p.path("maxNewSanction").decimalValue()).isEqualByComparingTo("500000");
        assertThat(p.path("reduced").asBoolean()).isFalse();

        JsonNode reduced = body(preview(maker, body(eis, "20000", 1, "20000")));
        assertThat(reduced.path("reduced").asBoolean())
                .as("SRS-12-14: room 40,000 x 50 % - 5,000 = 15,000")
                .isTrue();
        assertThat(reduced.path("terms").path("instalment").decimalValue())
                .isEqualByComparingTo("15000");
        assertThat(reduced.path("terms").path("principalInstalments").asInt()).isEqualTo(2);
    }

    @Test
    void SRS_12_12_theTypeLimitsAndTermsAreChecked() throws Exception {
        Session maker = maker();
        assertThat(code(preview(maker, body(eis, "30000", 6, "5000"))))
                .isEqualTo("LOAN-422-CEILING");
        assertThat(code(preview(maker, body(eis, "22000", 11, "2000"))))
                .isEqualTo("LOAN-422-CEILING");
        assertThat(code(preview(maker, body(eis, "20000", 4, "4000"))))
                .isEqualTo("LOAN-422-SANCTION");
        Map<String, Object> interest = body(eis, "20000", 4, "5000");
        interest.put("interestAmount", new java.math.BigDecimal("500"));
        interest.put("interestInstalments", 1);
        interest.put("interestInstalment", new java.math.BigDecimal("500"));
        assertThat(code(preview(maker, interest))).isEqualTo("LOAN-422-SANCTION");
        Map<String, Object> early = body(eis, "20000", 4, "5000");
        early.put("startYymm", "202001");
        assertThat(code(preview(maker, early))).isEqualTo("LOAN-422-SANCTION");
    }

    // SC-09-0029: a returned sanction request records the reason and goes back to the Payroll
    // Maker. SC-09-0026 (UC "Sanction and Recover a Loan", main flow: the Maker sets up the
    // sanction against its order, the Checker approves it) and SC-09-0033 (a returned request is
    // visible with its reason, separate from the sanctioned loans).
    @Test
    void SRS_12_8_aSanctionIsApprovedAgainstItsOrderAndAnOpenRequestMustBeReviewed()
            throws Exception {
        Session maker = maker();
        assertThat(code(submit(maker, body(eis, "20000", 4, "5000"), false)))
                .as("SRS-12-18: the order is mandatory")
                .isEqualTo("LOAN-422-SANCTION");
        MvcResult first = submit(maker, body(eis, "20000", 4, "5000"), true);
        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        long approval = body(first).path("id").asLong();

        MvcResult again = submit(maker, body(eis, "10000", 2, "5000"), true);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("LOAN-409-OPEN-REQUEST");
        Map<String, Object> reviewed = body(eis, "10000", 2, "5000");
        reviewed.put("reviewedOpenRequest", true);
        MvcResult second = submit(maker, reviewed, true);
        assertThat(second.getResponse().getStatus()).isEqualTo(202);
        long secondApproval = body(second).path("id").asLong();

        assertThat(decide(maker, approval, "APPROVE", null, null))
                .as("a Maker never approves the own item")
                .isNotEqualTo(200);
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decide(checker, approval, "APPROVE", null, null)).isEqualTo(200);
        Map<String, Object> loan =
                jdbc.queryForMap(
                        "SELECT h.loan_id, h.loan_number, h.loan_status, h.principal_outstanding,"
                                + " h.loan_source FROM loan_header h WHERE h.approval_id = ?",
                        approval);
        assertThat(loan.get("LOAN_STATUS")).isEqualTo("ACTIVE");
        assertThat((String) loan.get("LOAN_NUMBER")).startsWith("LN-");
        assertThat(((Number) loan.get("PRINCIPAL_OUTSTANDING")).intValue()).isEqualTo(20000);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM loan_recovery_schedule WHERE loan_id = ?",
                                Long.class,
                                loan.get("LOAN_ID")))
                .isEqualTo(4L);

        assertThat(
                        decide(
                                checker,
                                secondApproval,
                                "RETURN",
                                "IT: amount differs from the order",
                                null))
                .isEqualTo(200);
        JsonNode requests =
                body(mvc.perform(userGet(maker, "/v1/loans/requests?size=100")).andReturn());
        JsonNode returned = null;
        for (JsonNode r : requests.path("items")) {
            if (r.path("approvalId").asLong() == secondApproval) {
                returned = r;
            }
        }
        assertThat(returned).as("SRS-12-7: kept with its reason").isNotNull();
        assertThat(returned.path("status").asString()).isEqualTo("RETURNED");
        assertThat(returned.path("returnReason").asString()).contains("differs");
        // US-GAP-198 (SRS-12-7): the Payroll Maker is notified with the reason.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification n JOIN app_user u ON u.user_id ="
                                    + " n.recipient_user_id WHERE u.login_id ="
                                    + " 'maker.delhi@fci-demo.test' AND n.notification_type ="
                                    + " 'LOAN_REQUEST_RETURNED' AND n.related_entity_key = ? AND"
                                    + " n.message LIKE '%differs%'",
                                Long.class, String.valueOf(secondApproval)))
                .isEqualTo(1L);

        assertThat(code(preview(maker, body(eis, "4000", 1, "4000"))))
                .as("one Festival Advance open at a time")
                .isEqualTo("LOAN-422-CEILING");
        JsonNode loans = body(mvc.perform(userGet(maker, "/v1/loans?eis=" + eis)).andReturn());
        assertThat(loans.path("items").get(0).path("loanType").asString())
                .isEqualTo("FESTIVAL_ADVANCE");
        // SC-09-0033: the returned request is not among the member's loans: only the sanctioned
        // one is listed there.
        assertThat(loans.path("items")).hasSize(1);
        assertThat(loans.path("items").get(0).path("id").asLong())
                .isEqualTo(((Number) loan.get("LOAN_ID")).longValue());
        JsonNode detail =
                body(mvc.perform(userGet(maker, "/v1/loans/" + loan.get("LOAN_ID"))).andReturn());
        assertThat(detail.path("versions").get(0).path("reason").asString()).isEqualTo("ORIGINAL");
        assertThat(detail.path("versions").get(0).path("lines")).hasSize(4);
    }

    @Test
    void SRS_12_17_aRoleWithoutTheRightOrAnotherUnitsMemberIsRefused() throws Exception {
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        assertThat(preview(hr, body(eis, "20000", 4, "5000")).getResponse().getStatus())
                .isEqualTo(403);
        String chennai =
                jdbc.queryForObject(
                        "SELECT MAX(e.emp_num) FROM employee e JOIN org_location l ON l.location_id"
                                + " = e.location_id WHERE l.location_code = 'CH001'",
                        String.class);
        assertThat(preview(maker(), body(chennai, "20000", 4, "5000")).getResponse().getStatus())
                .as("another unit's member is not revealed")
                .isEqualTo(404);
        JsonNode types = body(mvc.perform(userGet(maker(), "/v1/loans/types")).andReturn());
        assertThat(texts(types, "code"))
                .contains("FESTIVAL_ADVANCE", "PLI_ADVANCE")
                .doesNotContain("DPS_TEMP_ADVANCE");
    }

    /**
     * E2E-20 step 3 / SRS-12-13 / SRS-12-14 (e2e l.411-412): a sanction whose instalment breaches
     * the deduction ceiling (room 40,000 x 50 % - 5,000 = 15,000) is never rejected for it: it goes
     * to the Checker with the instalment reduced and the tenure extended, and the approved loan
     * runs on that schedule. An instalment equal to the room is not reduced; one rupee more is.
     */
    @Test
    void E2E_20_SRS_12_14_aCeilingBreachReducesTheInstalmentAndExtendsTheTenureNeverRejects()
            throws Exception {
        // The room is taken from the member's latest pay: a member another suite has computed
        // (CycleIT, SimulationIT, ...) would have a later result than the 1998 fixture, so this
        // test takes a member whose only result is the fixture (order independence).
        member(
                " AND NOT EXISTS (SELECT 1 FROM payroll_result r WHERE r.employee_id ="
                        + " e.employee_id AND r.payroll_yymm <> '199803')",
                0);
        Session maker = maker();
        JsonNode equal = body(preview(maker, body(eis, "15000", 1, "15000")));
        assertThat(equal.path("reduced").asBoolean()).as("equal to the room").isFalse();
        assertThat(equal.path("terms").path("principalInstalments").asInt()).isEqualTo(1);
        JsonNode oneOver = body(preview(maker, body(eis, "15001", 1, "15001")));
        assertThat(oneOver.path("reduced").asBoolean()).as("one rupee over the room").isTrue();
        assertThat(oneOver.path("terms").path("instalment").decimalValue())
                .isEqualByComparingTo("15000");
        assertThat(oneOver.path("terms").path("principalInstalments").asInt()).isEqualTo(2);

        MvcResult sent = submit(maker, body(eis, "20000", 1, "20000"), true);
        assertThat(sent.getResponse().getStatus())
                .as("SRS-12-14: never rejected only for the ceiling")
                .isEqualTo(202);
        long approval = body(sent).path("id").asLong();
        JsonNode payload =
                JSON.readTree(
                        jdbc.queryForObject(
                                "SELECT payload FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval));
        assertThat(payload.path("reduced").asBoolean()).isTrue();
        assertThat(payload.path("principalInstalments").asInt()).isEqualTo(2);
        assertThat(payload.path("instalment").decimalValue()).isEqualByComparingTo("15000");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decide(checker, approval, "APPROVE", null, null)).isEqualTo(200);
        long loanId =
                jdbc.queryForObject(
                        "SELECT loan_id FROM loan_header WHERE approval_id = ?",
                        Long.class,
                        approval);
        java.util.List<java.math.BigDecimal> lines =
                jdbc.queryForList(
                        "SELECT l.principal_amount FROM loan_schedule_version v JOIN"
                                + " loan_recovery_schedule l ON l.schedule_version_id ="
                                + " v.schedule_version_id WHERE v.loan_id = ? AND v.superseded_at"
                                + " IS NULL ORDER BY l.instalment_number",
                        java.math.BigDecimal.class,
                        loanId);
        assertThat(lines).hasSize(2);
        assertThat(lines)
                .as("SRS-12-14: no instalment of the schedule in force breaches the ceiling")
                .allMatch(a -> a.compareTo(new java.math.BigDecimal("15000")) <= 0);
        assertThat(lines.stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .as("SRS-12-6: the principal instalments add up to the sanction")
                .isEqualByComparingTo("20000");
    }

    /**
     * SRS-G-29 (l.543): a loan and a sanction request of a Delhi member are neither listed, opened
     * nor decided from another office; the request stays with the Delhi Checker.
     */
    @Test
    void SRS_G_29_aLoanOfAnotherOfficeIsNeitherListedNorDecided() throws Exception {
        Session maker = maker();
        MvcResult sent = submit(maker, body(eis, "8000", 2, "4000"), true);
        assertThat(sent.getResponse().getStatus()).isEqualTo(202);
        long approval = body(sent).path("id").asLong();
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        assertThat(
                        body(
                                        mvc.perform(userGet(chennai, "/v1/loans/requests?size=100"))
                                                .andReturn())
                                .path("items")
                                .findValues("approvalId")
                                .stream()
                                .map(JsonNode::asLong))
                .doesNotContain(approval);
        Session chennaiChecker = login("ddo.chennai@fci-demo.test", "PAYROLL_CHECKER", "CH001");
        assertThat(decide(chennaiChecker, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("PENDING_APPROVAL");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decide(checker, approval, "APPROVE", null, null)).isEqualTo(200);
        long loanId =
                jdbc.queryForObject(
                        "SELECT loan_id FROM loan_header WHERE approval_id = ?",
                        Long.class,
                        approval);
        assertThat(
                        body(mvc.perform(userGet(chennai, "/v1/loans?eis=" + eis)).andReturn())
                                .path("items"))
                .isEmpty();
        assertThat(
                        mvc.perform(userGet(chennai, "/v1/loans/" + loanId))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(
                        mvc.perform(userGet(maker, "/v1/loans/" + loanId))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }
}
