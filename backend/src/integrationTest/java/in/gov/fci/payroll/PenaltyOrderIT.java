package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * I-06 penalty / recovery order entry end to end (plan 12 phase 12b; SRS-14-1, SRS-14-7, SRS-14-10,
 * SRS-14-12, SRS-14-15, PN-17): the Payroll Maker of Chennai records a recovery order with its
 * copy, the Payroll Checker approves it and the order is written with its Penalty ID and schedule;
 * a recovery below the net pay takes the Regional Office's second approval; the ceiling lengthens a
 * breaching recovery; the refusals. Demo ceiling 50 % (V042_3_9) and recovery head 912 (V044_2_9);
 * each test gives its own member a latest computed pay.
 */
class PenaltyOrderIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final byte[] PDF = "%PDF-1.7 order".getBytes(StandardCharsets.US_ASCII);

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.penalty.api.PenaltyClearance clearance;

    private Session maker() throws Exception {
        return login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
    }

    private Session checker() throws Exception {
        return login("ddo.chennai@fci-demo.test", "PAYROLL_CHECKER", "CH001");
    }

    /** A Chennai member (from the end of the list) with a latest computed pay. */
    private String member(int fromEnd, String gross, String deductions) {
        Map<String, Object> m =
                jdbc.queryForMap(
                        "SELECT employee_id, emp_num FROM employee WHERE location_id = ? AND"
                                + " record_status = 'ACTIVE' AND employment_status = 'IN_SERVICE'"
                                + " ORDER BY emp_num DESC OFFSET "
                                + fromEnd
                                + " ROWS FETCH NEXT 1 ROWS ONLY",
                        locationId("CH001"));
        long id = ((Number) m.get("EMPLOYEE_ID")).longValue();
        YearMonth month = YearMonth.now(IST).minusMonths(60 + fromEnd);
        String yymm = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        int start = month.getMonthValue() >= 4 ? month.getYear() : month.getYear() - 1;
        long unit = locationId("CH001");
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) SELECT ?, ?, 'DO', ?, 'VERIFIED',"
                        + " 'it.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM"
                        + " payroll_cycle_instance WHERE payroll_yymm = ? AND org_unit_id = ?)",
                yymm,
                unit,
                String.format("%d-%02d", start, (start + 1) % 100),
                yymm,
                unit);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        yymm,
                        unit);
        BigDecimal g = new BigDecimal(gross);
        BigDecimal d = new BigDecimal(deductions);
        // Far back, so it is the member's only result unless the member was computed since.
        jdbc.update(
                "UPDATE payroll_result SET result_status = 'SUPERSEDED' WHERE employee_id = ?", id);
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, result_status, is_locked) VALUES (?, ?, ?,"
                        + " 'STAFF', ?, ?, 'ACTUAL', ?, ?, ?, 'CURRENT', 'N')",
                cycle,
                unit,
                id,
                yymm,
                java.sql.Date.valueOf(month.atDay(1)),
                g,
                d,
                g.subtract(d));
        return (String) m.get("EMP_NUM");
    }

    private static String open() {
        YearMonth m = YearMonth.now(IST).plusMonths(1);
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private static Map<String, Object> order(
            String eis, String nature, String amount, int n, String start) {
        Map<String, Object> o = new HashMap<>();
        o.put("eis", eis);
        o.put("nature", nature);
        o.put("orderReference", "CH/REC/" + UUID.randomUUID().toString().substring(0, 6));
        o.put("effectiveDate", LocalDate.now(IST).toString());
        o.put("amount", new BigDecimal(amount));
        o.put("instalments", n);
        o.put("startYymm", start);
        return o;
    }

    private MvcResult submit(Session s, Map<String, Object> order, byte[] file) throws Exception {
        MockMultipartHttpServletRequestBuilder r = multipart("/v1/penalties");
        r.file(
                new MockMultipartFile(
                        "order",
                        "",
                        MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(order)));
        r.file(new MockMultipartFile("file", "order.pdf", "application/pdf", file));
        r.param("documentType", "OTHER")
                .param("reference", "ORD/IT/1")
                .param("orderDate", LocalDate.now(IST).toString())
                .param("issuingAuthority", "Area Manager Chennai");
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private JsonNode preview(Session s, Map<String, Object> order) throws Exception {
        MvcResult r = mvc.perform(userPost(s, "/v1/penalties/previews", order)).andReturn();
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(r);
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private long approval(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(202);
        return body(r).path("id").asLong();
    }

    // SC-12-0021 (UC I-06 main flow): capture with the order copy, preview, the Checker's approval,
    // the Penalty ID and the schedule month by month.
    @Test
    void SRS_14_15_anApprovedOrderGetsItsPenaltyIdAndItsScheduleMonthByMonth() throws Exception {
        String eis = member(0, "40000", "5000");
        Session maker = maker();
        Map<String, Object> o = order(eis, "RECOVERY", "10000", 3, open());
        JsonNode p = preview(maker, o);
        assertThat(p.path("schedule")).hasSize(3);
        assertThat(p.path("schedule").get(2).path("amount").decimalValue())
                .isEqualByComparingTo("3334");
        assertThat(p.path("adjusted").asBoolean()).isFalse();
        assertThat(p.path("belowFloor").asBoolean()).isFalse();
        long approval = approval(submit(maker, o, PDF));
        assertThat(decide(maker, approval, "APPROVE", null, null))
                .as("never the maker")
                .isEqualTo(403);
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT o.order_id, o.penalty_ref, o.order_status, o.penalty_source FROM"
                                + " penalty_order o WHERE o.approval_id = ?",
                        approval);
        assertThat((String) row.get("PENALTY_REF")).matches("PEN-\\d{4}-\\d{5}");
        assertThat(row.get("ORDER_STATUS")).isEqualTo("ACTIVE");
        assertThat(row.get("PENALTY_SOURCE")).isEqualTo("ENT");
        List<BigDecimal> scheduled =
                jdbc.queryForList(
                        "SELECT scheduled_amount FROM penalty_recovery_schedule WHERE order_id = ?"
                                + " ORDER BY payroll_yymm",
                        BigDecimal.class,
                        row.get("ORDER_ID"));
        assertThat(scheduled).hasSize(3);
        assertThat(scheduled.get(0)).isEqualByComparingTo("3333");
        JsonNode list =
                body(mvc.perform(userGet(checker(), "/v1/penalties?eis=" + eis)).andReturn());
        assertThat(list.path("items").get(0).path("outstanding").decimalValue())
                .isEqualByComparingTo("10000");
        JsonNode detail =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/penalties/"
                                                        + ((Number) row.get("ORDER_ID"))
                                                                .longValue()))
                                .andReturn());
        assertThat(detail.path("schedule")).hasSize(3);
        String report =
                csv(
                        checker(),
                        "/v1/penalties/report?from=" + open() + "&to=" + open() + "&format=CSV");
        assertThat(report).as("SRS-14-19").contains((String) row.get("PENALTY_REF"), "RECOVERY");
    }

    // SC-12-0022: a recovery that takes net pay below the floor is flagged for its approval.
    @Test
    void SRS_14_10_aRecoveryBelowTheFloorTakesTheRegionalOfficesSecondApproval() throws Exception {
        String eis = member(1, "40000", "5000");
        Session maker = maker();
        // An approved order first: its approval stands as the approval of an M-14 floor of 34,000
        // in force in one month far ahead only, so no other test meets it.
        long first = approval(submit(maker, order(eis, "RECOVERY", "300", 1, open()), PDF));
        assertThat(decide(checker(), first, "APPROVE", null, null)).isEqualTo(200);
        YearMonth far = YearMonth.now(IST).plusMonths(130);
        jdbc.update(
                "INSERT INTO net_pay_floor_rule (payroll_type, floor_basis, floor_value,"
                    + " is_confirmed, effective_from, effective_to, approval_id) VALUES ('STAFF',"
                    + " 'ABSOLUTE_AMOUNT', 34000, 'Y', ?, ?, ?)",
                java.sql.Date.valueOf(far.atDay(1)),
                java.sql.Date.valueOf(far.atEndOfMonth()),
                first);
        String start = String.format("%04d%02d", far.getYear(), far.getMonthValue());
        Map<String, Object> o = order(eis, "RECOVERY", "2000", 1, start);
        JsonNode p = preview(maker, o);
        assertThat(p.path("belowFloor").asBoolean()).isTrue();
        assertThat(p.path("floor").decimalValue()).isEqualByComparingTo("34000");
        long approval = approval(submit(maker, o, PDF));
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM penalty_order WHERE approval_id = ?",
                                Integer.class,
                                approval))
                .as("not applied on one approval")
                .isZero();
        Session region = login("pc.rotn@fci-demo.test", "PAYROLL_CHECKER", "RO-TN");
        assertThat(decide(region, approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM penalty_order WHERE approval_id = ?",
                                Integer.class,
                                approval))
                .isOne();
        Map<String, Object> override =
                jdbc.queryForMap(
                        "SELECT computed_net_pay, floor_amount FROM net_pay_floor_override WHERE"
                                + " approval_id = ?",
                        approval);
        assertThat((BigDecimal) override.get("COMPUTED_NET_PAY")).isEqualByComparingTo("33000");
        assertThat((BigDecimal) override.get("FLOOR_AMOUNT")).isEqualByComparingTo("34000");
    }

    /**
     * SRS l.2995 (US-12-08 AC4, SC-12-0022): at a below-floor breach the Maker may defer the
     * recovery to F&amp;F instead: the order goes the ordinary route (one Checker, OD-75), nothing
     * is recovered in service (every instalment DEFERRED, the order PAUSED_FNF, no floor override)
     * and the whole balance is the settlement's. A deferral without a breach is refused. SRS-14-18:
     * the preview shows the Basic after a recovery (unchanged).
     */
    @Test
    void SRS_14_10_aBelowFloorOrderIsDeferredToFnfInsteadOfBreachingTheFloor() throws Exception {
        String eis = member(6, "40000", "5000");
        long employee =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
        Session maker = maker();
        long first = approval(submit(maker, order(eis, "RECOVERY", "300", 1, open()), PDF));
        assertThat(decide(checker(), first, "APPROVE", null, null)).isEqualTo(200);
        YearMonth far = YearMonth.now(IST).plusMonths(140);
        jdbc.update(
                "INSERT INTO net_pay_floor_rule (payroll_type, floor_basis, floor_value,"
                    + " is_confirmed, effective_from, effective_to, approval_id) VALUES ('STAFF',"
                    + " 'ABSOLUTE_AMOUNT', 34000, 'Y', ?, ?, ?)",
                java.sql.Date.valueOf(far.atDay(1)),
                java.sql.Date.valueOf(far.atEndOfMonth()),
                first);
        String start = String.format("%04d%02d", far.getYear(), far.getMonthValue());

        // No breach, no deferral.
        Map<String, Object> small = order(eis, "RECOVERY", "100", 1, start);
        small.put("deferToFnf", true);
        MvcResult refused =
                mvc.perform(userPost(maker, "/v1/penalties/previews", small)).andReturn();
        assertThat(error(refused)).isEqualTo("PEN-422-ORDER");

        Map<String, Object> o = order(eis, "RECOVERY", "2000", 1, start);
        o.put("deferToFnf", true);
        JsonNode p = preview(maker, o);
        assertThat(p.path("belowFloor").asBoolean()).isTrue();
        assertThat(p.path("deferredToFnf").asBoolean()).isTrue();
        assertThat(p.path("proposedBasic").decimalValue())
                .isEqualByComparingTo(p.path("currentBasic").decimalValue());
        long approval = approval(submit(maker, o, PDF));
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT order_id, penalty_ref, order_status FROM penalty_order WHERE"
                                + " approval_id = ?",
                        approval);
        assertThat(row.get("ORDER_STATUS"))
                .as("one approval: the ordinary route")
                .isEqualTo("PAUSED_FNF");
        long orderId = ((Number) row.get("ORDER_ID")).longValue();
        assertThat(
                        jdbc.queryForList(
                                "SELECT schedule_status FROM penalty_recovery_schedule WHERE"
                                        + " order_id = ?",
                                String.class,
                                orderId))
                .isNotEmpty()
                .containsOnly("DEFERRED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM net_pay_floor_override WHERE approval_id = ?",
                                Integer.class,
                                approval))
                .isZero();
        assertThat(clearance.outstanding(employee))
                .anySatisfy(
                        x -> {
                            assertThat(x.penaltyRef()).isEqualTo(row.get("PENALTY_REF"));
                            assertThat(x.amount()).isEqualByComparingTo("2000");
                        });
    }

    @Test
    void SRS_14_7_aBreachingInstalmentIsLengthenedUnderTheCeilingAndFlagged() throws Exception {
        String eis = member(2, "10000", "4000");
        JsonNode p = preview(maker(), order(eis, "RECOVERY", "6000", 2, open()));
        assertThat(p.path("adjusted").asBoolean()).isTrue();
        assertThat(p.path("instalments").asInt()).isEqualTo(6);
        assertThat(p.path("schedule").get(0).path("amount").decimalValue())
                .isEqualByComparingTo("1000");
        String full = member(3, "8000", "4000");
        MvcResult none =
                mvc.perform(
                                userPost(
                                        maker(),
                                        "/v1/penalties/previews",
                                        order(full, "RECOVERY", "100", 1, open())))
                        .andReturn();
        assertThat(error(none)).isEqualTo("PEN-422-CEILING");
    }

    /**
     * SRS-14-15 and the use case's main flow (l.2990; US-GAP-229): the Penalty ID is assigned when
     * the Maker records the order, shown on the waiting request and kept by the order written on
     * approval. SRS-14-11 (US-GAP-236, plan 12 OD-59): a member who separates while the order waits
     * for the Checker still has it written on approval, and its balance is the Full &amp; Final
     * Settlement's to recover.
     */
    @Test
    void SRS_14_15_thePenaltyIdIsGivenWhenRecordedAndASeparationWhilePendingGoesToFnf()
            throws Exception {
        String eis = member(5, "40000", "5000");
        Session maker = maker();
        long approval = approval(submit(maker, order(eis, "RECOVERY", "600", 2, open()), PDF));
        String key =
                jdbc.queryForObject(
                        "SELECT entity_key FROM approval_queue WHERE approval_id = ?",
                        String.class,
                        approval);
        String ref = key.substring(key.indexOf('|') + 1);
        assertThat(ref).matches("PEN-\\d{4}-\\d{5}");
        assertThat(
                        body(mvc.perform(userGet(maker, "/v1/penalties/requests?size=100"))
                                        .andReturn())
                                .toString())
                .as("shown before the decision")
                .contains("\"penaltyRef\":\"" + ref + "\"");
        long employee =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
        jdbc.update(
                "UPDATE employee SET separation_date = ? WHERE employee_id = ?",
                java.sql.Date.valueOf(LocalDate.now(IST)),
                employee);
        try {
            assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
            Map<String, Object> row =
                    jdbc.queryForMap(
                            "SELECT penalty_ref, order_status FROM penalty_order WHERE"
                                    + " approval_id = ?",
                            approval);
            assertThat(row.get("PENALTY_REF")).isEqualTo(ref);
            assertThat(row.get("ORDER_STATUS")).isEqualTo("ACTIVE");
            assertThat(clearance.outstanding(employee))
                    .as("the balance is the settlement's")
                    .anySatisfy(
                            o -> {
                                assertThat(o.penaltyRef()).isEqualTo(ref);
                                assertThat(o.amount()).isEqualByComparingTo("600");
                            });
        } finally {
            jdbc.update(
                    "UPDATE employee SET separation_date = NULL WHERE employee_id = ?", employee);
        }
        long second = approval(submit(maker, order(eis, "RECOVERY", "300", 1, open()), PDF));
        String next =
                jdbc.queryForObject(
                        "SELECT entity_key FROM approval_queue WHERE approval_id = ?",
                        String.class,
                        second);
        assertThat(next.substring(next.indexOf('|') + 1)).as("unique").isNotEqualTo(ref);
    }

    // SC-12-0025 (a returned order goes back to the Maker with the reason), SC-12-0032 (the member
    // must be an active member of the payroll).
    @Test
    void SRS_14_1_whatIsRefusedAndAReturnStaysListedWithItsReason() throws Exception {
        String eis = member(4, "40000", "5000");
        Session maker = maker();
        assertThat(error(submit(maker, order(eis, "INCREMENT_STOP", "100", 1, open()), PDF)))
                .isEqualTo("PEN-422-HRMS-ONLY");
        assertThat(error(submit(maker, order(eis, "RECOVERY", "100", 1, open()), new byte[0])))
                .isEqualTo("PEN-422-ORDER-COPY");
        assertThat(error(submit(maker, order(eis, "RECOVERY", "100", 1, "200001"), PDF)))
                .isEqualTo("PEN-422-ORDER");
        assertThat(error(submit(maker, order(eis, "UNKNOWN", "100", 1, open()), PDF)))
                .isEqualTo("PEN-422-ORDER");
        assertThat(
                        submit(checker(), order(eis, "RECOVERY", "100", 1, open()), PDF)
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(
                        submit(kolkata, order(eis, "RECOVERY", "100", 1, open()), PDF)
                                .getResponse()
                                .getStatus())
                .as("another unit's member")
                .isIn(403, 404);

        long approval = approval(submit(maker, order(eis, "RECOVERY", "900", 3, open()), PDF));
        assertThat(decide(checker(), approval, "RETURN", "IT: amount differs from the order", null))
                .isEqualTo(200);
        JsonNode requests = body(mvc.perform(userGet(maker, "/v1/penalties/requests")).andReturn());
        assertThat(requests.toString()).contains("IT: amount differs from the order", "RETURNED");
    }

    /**
     * SRS-G-1 (l.517), R-MC: the user who submitted a penalty order as Payroll Maker never approves
     * it, even holding the Checker role of the same office (maker.checker, seed V015_9, DH001):
     * WF-403-SOD, no order is recorded, and the item waits for another Checker. A Delhi member
     * linked to no demo user, with a far-back computed pay of its own (removed afterwards).
     */
    @Test
    void SRS_G_1_theMakerNeverApprovesTheOwnPenaltyOrder() throws Exception {
        long unit = locationId("DH001");
        Map<String, Object> m =
                jdbc.queryForMap(
                        "SELECT e.employee_id, e.emp_num FROM employee e WHERE e.location_id = ?"
                                + " AND e.record_status = 'ACTIVE' AND e.employment_status ="
                                + " 'IN_SERVICE' AND NOT EXISTS (SELECT 1 FROM app_user u WHERE"
                                + " u.employee_id = e.employee_id) ORDER BY e.emp_num DESC OFFSET"
                                + " 5 ROWS FETCH NEXT 1 ROWS ONLY",
                        unit);
        long id = ((Number) m.get("EMPLOYEE_ID")).longValue();
        String eis = (String) m.get("EMP_NUM");
        YearMonth month = YearMonth.now(IST).minusMonths(75);
        String yymm = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        int start = month.getMonthValue() >= 4 ? month.getYear() : month.getYear() - 1;
        int cycles =
                jdbc.update(
                        "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id,"
                                + " org_unit_level, fin_year, cycle_status, initiated_by) SELECT ?,"
                                + " ?, 'DO', ?, 'VERIFIED', 'it.fixture' FROM dual WHERE NOT EXISTS"
                                + " (SELECT 1 FROM payroll_cycle_instance WHERE payroll_yymm = ?"
                                + " AND org_unit_id = ?)",
                        yymm,
                        unit,
                        String.format("%d-%02d", start, (start + 1) % 100),
                        yymm,
                        unit);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        yymm,
                        unit);
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, result_status, is_locked) VALUES (?, ?, ?,"
                        + " 'STAFF', ?, ?, 'ACTUAL', 40000, 5000, 35000, 'CURRENT', 'N')",
                cycle,
                unit,
                id,
                yymm,
                java.sql.Date.valueOf(month.atDay(1)));
        Long approval = null;
        try {
            Session both = login("maker.checker@fci-demo.test", "PAYROLL_MAKER", "DH001");
            approval = approval(submit(both, order(eis, "RECOVERY", "100", 1, open()), PDF));
            actAs(both, "PAYROLL_CHECKER");
            MvcResult own =
                    mvc.perform(
                                    userPost(
                                            both,
                                            "/v1/approvals/" + approval + "/decision",
                                            Map.of("decision", "APPROVE")))
                            .andReturn();
            assertThat(own.getResponse().getStatus())
                    .as(own.getResponse().getContentAsString())
                    .isEqualTo(403);
            assertThat(error(own)).isEqualTo("WF-403-SOD");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM approval_queue WHERE approval_id = ?",
                                    String.class,
                                    approval))
                    .isEqualTo("PENDING_APPROVAL");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM penalty_order WHERE approval_id = ?",
                                    Integer.class,
                                    approval))
                    .as("no order before another Checker approves")
                    .isZero();
        } finally {
            if (approval != null) {
                assertThat(
                                decide(
                                        login(
                                                "ddo.delhi@fci-demo.test",
                                                "PAYROLL_CHECKER",
                                                "DH001"),
                                        approval,
                                        "REJECT",
                                        "IT: own-approval check only",
                                        null))
                        .isEqualTo(200);
            }
            jdbc.update(
                    "DELETE FROM payroll_result WHERE employee_id = ? AND cycle_instance_id = ?",
                    id,
                    cycle);
            if (cycles > 0) {
                jdbc.update(
                        "DELETE FROM payroll_cycle_instance WHERE cycle_instance_id = ?", cycle);
            }
        }
    }
}
