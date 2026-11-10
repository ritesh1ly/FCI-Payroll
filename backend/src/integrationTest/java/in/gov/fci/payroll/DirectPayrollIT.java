package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * I-19 Maintain Direct Payroll (plan 05 phase 05g; SRS-15-2, SRS-15-19, SRS-15-22, SRS-G-1,
 * SRS-G-37, BR-CYC-018): entries in the MDP window of a locked period, for a member of the locked
 * run and a permitted head (demo list: 348, 901, 902, 903), deduction only; the Payroll Checker
 * decides every capture, amendment and cancellation; an approved entry is never edited in place;
 * nothing is deleted; the register is the MDP Audit Report. At Chennai (CH001), three months back
 * (no other IT uses it there): a locked cycle with locked results is a fixture standing in for the
 * lock.
 */
class DirectPayrollIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final YearMonth PERIOD = YearMonth.from(LocalDate.now(IST)).minusMonths(3);
    private static final String YYMM = PERIOD.toString().replace("-", "");

    private List<String> members;

    private Session maker() throws Exception {
        return login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
    }

    private Session checker() throws Exception {
        return login("ddo.chennai@fci-demo.test", "PAYROLL_CHECKER", "CH001");
    }

    /** The locked cycle of the period with three locked results, and its window open today. */
    @BeforeEach
    void lockedPeriod() {
        Long cycle =
                jdbc
                        .queryForList(
                                "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE"
                                        + " payroll_yymm = ? AND org_unit_id = ?",
                                Long.class,
                                YYMM,
                                locationId("CH001"))
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (cycle == null) {
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by, locked_by) VALUES"
                        + " (?, ?, 'DO', ?, 'LOCKED', 'ON_DEMAND', 'it.maker', 'it.checker')",
                    YYMM,
                    locationId("CH001"),
                    finYear(PERIOD));
            cycle =
                    jdbc.queryForObject(
                            "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE"
                                    + " payroll_yymm = ? AND org_unit_id = ?",
                            Long.class,
                            YYMM,
                            locationId("CH001"));
            List<Long> ids =
                    jdbc.queryForList(
                            "SELECT employee_id FROM employee ORDER BY emp_num OFFSET 60 ROWS FETCH"
                                    + " NEXT 3 ROWS ONLY",
                            Long.class);
            for (Long id : ids) {
                jdbc.update(
                        "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                                + " payroll_type, payroll_yymm, period_date, run_type,"
                                + " gross_earnings, total_deductions, net_pay, result_status,"
                                + " is_locked) VALUES (?, ?, ?, 'STAFF', ?, ?, 'ACTUAL', 30000, 0,"
                                + " 30000, 'CURRENT', 'Y')",
                        cycle,
                        locationId("CH001"),
                        id,
                        YYMM,
                        Date.valueOf(PERIOD.atDay(1)));
            }
        }
        LocalDate today = LocalDate.now(IST);
        window(today.minusDays(1), today.plusDays(1));
        members =
                jdbc.queryForList(
                        "SELECT e.emp_num FROM payroll_result r JOIN employee e ON e.employee_id ="
                                + " r.employee_id WHERE r.cycle_instance_id = ? ORDER BY e.emp_num",
                        String.class,
                        cycle);
    }

    private void window(LocalDate from, LocalDate to) {
        jdbc.update(
                "UPDATE payroll_calendar SET mdp_window_from = ?, mdp_window_to = ? WHERE"
                        + " payroll_yymm = ?",
                Date.valueOf(from),
                Date.valueOf(to),
                YYMM);
    }

    private static String finYear(YearMonth m) {
        int start = m.getMonthValue() >= 4 ? m.getYear() : m.getYear() - 1;
        return start + "-" + String.valueOf(start + 1).substring(2);
    }

    private MvcResult capture(Session s, String eis, int payCode, String amount) throws Exception {
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/mdp-transactions",
                                Map.of(
                                        "period",
                                        PERIOD.toString(),
                                        "eis",
                                        eis,
                                        "payCode",
                                        payCode,
                                        "amount",
                                        new BigDecimal(amount),
                                        "reason",
                                        "PT short-deducted in " + PERIOD)))
                .andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private JsonNode register(Session s, String query) throws Exception {
        MvcResult r = mvc.perform(userGet(s, "/v1/mdp-transactions?" + query)).andReturn();
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(r);
    }

    private String status(long mdpId) {
        return jdbc.queryForObject(
                "SELECT mdp_status FROM mdp_transaction WHERE mdp_id = ?", String.class, mdpId);
    }

    private long mdpOf(long approvalId) {
        return jdbc.queryForObject(
                "SELECT CAST(entity_key AS BIGINT) FROM approval_queue WHERE approval_id = ?",
                Long.class,
                approvalId);
    }

    /**
     * SRS-15-19 / BR-CYC-018: only the permitted heads, a deduction, a member of the locked run, in
     * the window of a locked period, captured by the Maker; the Checker decides.
     */
    @Test
    void SRS_15_19_aDeductionOfAPermittedHeadIsCapturedAndApprovedInTheWindow() throws Exception {
        Session maker = maker();
        String member = members.get(0);
        JsonNode heads =
                body(mvc.perform(userGet(maker, "/v1/mdp-transactions/heads")).andReturn());
        assertThat(texts(heads, "name"))
                .contains("Labour Welfare Fund", "Professional Tax (demo code)");
        assertThat(
                        body(mvc.perform(userGet(maker, "/v1/mdp-transactions/open-periods"))
                                        .andReturn())
                                .toString())
                .contains(PERIOD.toString());

        assertThat(code(capture(maker, member, 101, "200.00"))).isEqualTo("MDP-422-HEAD");
        // SRS-LV-22 (BMAD TR-048): a leave recovery is never entered by hand - an arrear pay code
        // (M-16 ARREAR_PAY_CODE, under which recoveries are taken) is no I-19 head, and arrear
        // events have no
        // create route: they come only from a leave (or other source) event.
        java.math.BigDecimal recoveryCode =
                jdbc.queryForObject(
                        "SELECT MIN(v.numeric_attribute) FROM reference_value v JOIN"
                                + " reference_list l ON l.list_id = v.list_id WHERE l.list_code ="
                                + " 'ARREAR_PAY_CODE'",
                        java.math.BigDecimal.class);
        long leaveEvents =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM arrear_event WHERE source_module = 'LEAVE'",
                        Long.class);
        assertThat(code(capture(maker, member, recoveryCode.intValueExact(), "200.00")))
                .isEqualTo("MDP-422-HEAD");
        assertThat(
                        mvc.perform(
                                        userPost(
                                                maker,
                                                "/v1/arrear-events",
                                                Map.of(
                                                        "sourceModule",
                                                        "LEAVE",
                                                        "eis",
                                                        member,
                                                        "amount",
                                                        -200)))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                // The drawn I-08 "New Retro Event" exists (OD-121) but takes no keyed amount:
                // refused.
                .as("no manual arrear event")
                .isIn(400, 405);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM arrear_event WHERE source_module = 'LEAVE'",
                                Long.class))
                .isEqualTo(leaveEvents);
        MvcResult credit = capture(maker, member, 901, "-200.00");
        assertThat(code(credit)).isEqualTo("MDP-422-AMOUNT");
        assertThat(body(credit).get("args").get(0).asString()).isEqualTo("NOT_A_DEDUCTION");
        assertThat(code(capture(maker, "000000", 901, "200.00"))).isEqualTo("MDP-422-MEMBER");
        assertThat(capture(checker(), member, 901, "200.00").getResponse().getStatus())
                .isEqualTo(403);
        LocalDate today = LocalDate.now(IST);
        window(today.minusDays(5), today.minusDays(1));
        assertThat(code(capture(maker, member, 901, "200.00"))).isEqualTo("MDP-409-WINDOW");
        window(today.minusDays(1), today.plusDays(1));

        MvcResult asked = capture(maker, member, 901, "200.00");
        assertThat(asked.getResponse().getStatus())
                .as(asked.getResponse().getContentAsString())
                .isEqualTo(202);
        long approvalId = body(asked).get("approvalId").asLong();
        long mdpId = mdpOf(approvalId);
        assertThat(status(mdpId)).isEqualTo("ENTERED");
        JsonNode pending = register(maker, "eis=" + member + "&status=ENTERED");
        assertThat(pending.get("items").get(0).get("pendingApprovalId").asLong())
                .isEqualTo(approvalId);
        assertThat(pending.get("items").get(0).get("reference").asString())
                .isEqualTo("MDP-" + YYMM + "-" + mdpId);

        assertThat(decide(maker, approvalId, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(checker(), approvalId, "APPROVE", null, null)).isEqualTo(200);
        JsonNode approved = register(maker, "period=" + PERIOD + "&payCode=901&eis=" + member);
        JsonNode entry = approved.get("items").get(0);
        assertThat(entry.get("status").asString()).isEqualTo("APPROVED");
        assertThat(entry.get("checkedBy").asString()).isEqualTo("ddo.chennai@fci-demo.test");
        assertThat(entry.get("enteredBy").asString()).isEqualTo("maker.chennai@fci-demo.test");

        // The MDP Audit Report: the checker exports it; Finance views; the maker's export too.
        String csv = csv(checker(), "/v1/mdp-transactions/export?period=" + PERIOD);
        assertThat(csv)
                .contains(
                        "MDP-" + YYMM + "-" + mdpId,
                        member,
                        "APPROVED",
                        "ddo.chennai@fci-demo.test");
    }

    /**
     * SRS-19-8 (US-06-17 AC3): pay code 340 (PF Arrear) stays in the component catalogue but is not
     * offered on I-19 and a capture of it is refused as a head that is not permitted.
     */
    @Test
    void SRS_19_8_payCode340StaysInTheCatalogueButIsNeverOfferedOnI19() throws Exception {
        jdbc.update(
                "INSERT INTO pay_component (legacy_pay_code, component_name, component_type,"
                        + " payslip_group, source_code, taxable_flag, in_cpf_base_flag,"
                        + " effective_from) SELECT 340, 'PF Arrear', 'DEDUCTION', 'DEDUCTIONS',"
                        + " 'RULE', 'N', 'N', DATE '2026-04-01' FROM dual WHERE NOT EXISTS (SELECT"
                        + " 1 FROM pay_component p WHERE p.legacy_pay_code = 340)");
        Session maker = maker();
        JsonNode heads =
                body(mvc.perform(userGet(maker, "/v1/mdp-transactions/heads")).andReturn());
        assertThat(heads.valueStream().map(h -> h.get("payCode").asInt())).doesNotContain(340);
        LocalDate today = LocalDate.now(IST);
        window(today.minusDays(1), today.plusDays(1));
        assertThat(code(capture(maker, members.get(0), 340, "200.00"))).isEqualTo("MDP-422-HEAD");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM pay_component WHERE legacy_pay_code = 340",
                                Integer.class))
                .as("kept in the catalogue")
                .isEqualTo(1);
    }

    /**
     * SRS-G-1 / SRS-G-37 / SRS-G-33: an approved entry is amended or cancelled through the Checker,
     * never edited in place; nothing changes while a decision waits; a cancelled entry is final; a
     * rejected capture is cancelled.
     */
    @Test
    void SRS_G_37_anApprovedEntryIsAmendedByANewEntryAndCancelledThroughTheChecker()
            throws Exception {
        Session maker = maker();
        String member = members.get(1);
        long first = body(capture(maker, member, 348, "150.00")).get("approvalId").asLong();
        long mdpId = mdpOf(first);
        assertThat(decide(checker(), first, "APPROVE", null, null)).isEqualTo(200);

        MvcResult amend =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/mdp-transactions/" + mdpId + "/amendments",
                                        Map.of(
                                                "amount",
                                                new BigDecimal("175.00"),
                                                "reason",
                                                "Correct rate")))
                        .andReturn();
        assertThat(amend.getResponse().getStatus())
                .as(amend.getResponse().getContentAsString())
                .isEqualTo(202);
        MvcResult twice =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/mdp-transactions/" + mdpId + "/cancellations",
                                        Map.of("reason", "Meanwhile")))
                        .andReturn();
        assertThat(code(twice)).isEqualTo("MDP-409-STATE");
        assertThat(body(twice).get("args").get(0).asString()).isEqualTo("PENDING");
        assertThat(status(mdpId)).as("never edited in place").isEqualTo("APPROVED");
        assertThat(decide(checker(), body(amend).get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        assertThat(status(mdpId)).isEqualTo("CANCELLED");
        Map<String, Object> amended =
                jdbc.queryForMap(
                        "SELECT mdp_id, amount, mdp_status FROM mdp_transaction WHERE mdp_id ="
                                + " (SELECT MAX(mdp_id) FROM mdp_transaction WHERE employee_id ="
                                + " (SELECT employee_id FROM employee WHERE emp_num = ?))",
                        member);
        assertThat(amended.get("MDP_STATUS")).isEqualTo("APPROVED");
        assertThat((BigDecimal) amended.get("AMOUNT")).isEqualByComparingTo("175.00");
        long newId = ((Number) amended.get("MDP_ID")).longValue();

        // A cancelled entry is final.
        assertThat(
                        code(
                                mvc.perform(
                                                userPost(
                                                        maker,
                                                        "/v1/mdp-transactions/"
                                                                + mdpId
                                                                + "/cancellations",
                                                        Map.of("reason", "Again")))
                                        .andReturn()))
                .isEqualTo("MDP-409-STATE");
        // The new entry is cancelled through the Checker.
        MvcResult cancel =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/mdp-transactions/" + newId + "/cancellations",
                                        Map.of("reason", "Recovered elsewhere")))
                        .andReturn();
        assertThat(cancel.getResponse().getStatus()).isEqualTo(202);
        assertThat(
                        decide(
                                checker(),
                                body(cancel).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(status(newId)).isEqualTo("CANCELLED");
        // Both stay on the register with the cancelled filter (nothing is deleted).
        assertThat(register(maker, "status=CANCELLED&eis=" + member).get("total").asLong())
                .isGreaterThanOrEqualTo(2);

        // A rejected capture is final: cancelled.
        long rejected =
                body(capture(maker, members.get(2), 902, "50.00")).get("approvalId").asLong();
        assertThat(decide(checker(), rejected, "REJECT", "Not due", null)).isEqualTo(200);
        assertThat(status(mdpOf(rejected))).isEqualTo("CANCELLED");
    }

    /** SRS-15-19: a register page past the last entry is empty. */
    @Test
    void SRS_15_19_aRegisterPagePastTheLastEntryIsEmpty() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode past =
                body(
                        mvc.perform(userGet(maker, "/v1/mdp-transactions?page=999&size=10"))
                                .andReturn());
        assertThat(past.get("items")).isEmpty();
    }

    /**
     * SRS-G-1 / SRS-15-19: an entry the Checker returned is corrected in place by the Maker (not
     * yet approved, so no new entry): it becomes AMENDED with its new amount and goes back to the
     * Checker, who approves it.
     */
    @Test
    void SRS_G_1_aReturnedEntryIsCorrectedInPlaceAndApproved() throws Exception {
        Session maker = maker();
        String member = members.get(2);
        long asked = body(capture(maker, member, 348, "60.00")).get("approvalId").asLong();
        long mdpId = mdpOf(asked);
        assertThat(decide(checker(), asked, "RETURN", "Order copy missing", null)).isEqualTo(200);
        assertThat(status(mdpId)).as("a return changes nothing").isEqualTo("ENTERED");

        MvcResult corrected =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/mdp-transactions/" + mdpId + "/amendments",
                                        Map.of(
                                                "amount",
                                                new BigDecimal("65.00"),
                                                "reason",
                                                "Order attached")))
                        .andReturn();
        assertThat(corrected.getResponse().getStatus())
                .as(corrected.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(status(mdpId)).isEqualTo("AMENDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT amount FROM mdp_transaction WHERE mdp_id = ?",
                                BigDecimal.class,
                                mdpId))
                .isEqualByComparingTo("65.00");
        JsonNode listed = register(maker, "status=AMENDED");
        assertThat(texts(listed.get("items"), "reference")).contains("MDP-" + YYMM + "-" + mdpId);
        long again = body(corrected).get("approvalId").asLong();
        assertThat(decide(checker(), again, "APPROVE", null, null)).isEqualTo(200);
        assertThat(status(mdpId)).isEqualTo("APPROVED");
    }

    @Test
    void SRS_15_19_theTilesCountTheWindowsEntriesHeadsAndValueAndFollowACapture() throws Exception {
        Session maker = maker();
        JsonNode before =
                body(
                        mvc.perform(userGet(maker, "/v1/mdp-transactions/summary"))
                                .andExpect(
                                        org.springframework.test.web.servlet.result
                                                .MockMvcResultMatchers.status()
                                                .isOk())
                                .andReturn());
        assertThat(before.get("windowOpen").asBoolean()).isTrue();
        assertThat(before.get("heads").asInt())
                .isEqualTo(
                        body(mvc.perform(userGet(maker, "/v1/mdp-transactions/heads")).andReturn())
                                .size());
        // The window's period is the unit's newest open one; the test's period is open, so it is
        // that or newer.
        assertThat(before.get("period").asString()).isGreaterThanOrEqualTo(PERIOD.toString());
        if (PERIOD.toString().equals(before.get("period").asString())) {
            JsonNode heads =
                    body(mvc.perform(userGet(maker, "/v1/mdp-transactions/heads")).andReturn());
            int payCode = heads.get(0).get("payCode").asInt();
            assertThat(capture(maker, members.get(1), payCode, "30.00").getResponse().getStatus())
                    .isEqualTo(202);
            JsonNode after =
                    body(mvc.perform(userGet(maker, "/v1/mdp-transactions/summary")).andReturn());
            assertThat(after.get("pending").asLong()).isEqualTo(before.get("pending").asLong() + 1);
            assertThat(after.get("value").decimalValue())
                    .isEqualByComparingTo(
                            before.get("value").decimalValue().add(new BigDecimal("30.00")));
            assertThat(after.get("approved").asLong()).isEqualTo(before.get("approved").asLong());
        }
    }

    @Test
    void SRS_15_2_aClosedWindowReadsClosedOnItsNewestLockedPeriod() throws Exception {
        LocalDate today = LocalDate.now(IST);
        // Every window open today is closed for the check and restored exactly afterwards (shared
        // database).
        List<Map<String, Object>> open =
                jdbc.queryForList(
                        "SELECT payroll_yymm, mdp_window_from, mdp_window_to FROM payroll_calendar"
                                + " WHERE mdp_window_to >= ?",
                        Date.valueOf(today));
        jdbc.update(
                "UPDATE payroll_calendar SET mdp_window_from = ?, mdp_window_to = ? WHERE"
                        + " mdp_window_to >= ?",
                Date.valueOf(today.minusDays(20)),
                Date.valueOf(today.minusDays(10)),
                Date.valueOf(today));
        try {
            JsonNode summary =
                    body(
                            mvc.perform(userGet(maker(), "/v1/mdp-transactions/summary"))
                                    .andExpect(
                                            org.springframework.test.web.servlet.result
                                                    .MockMvcResultMatchers.status()
                                                    .isOk())
                                    .andReturn());
            assertThat(summary.get("windowOpen").asBoolean()).isFalse();
            assertThat(summary.get("period").isNull()).isFalse();
        } finally {
            for (Map<String, Object> row : open) {
                jdbc.update(
                        "UPDATE payroll_calendar SET mdp_window_from = ?, mdp_window_to = ? WHERE"
                                + " payroll_yymm = ?",
                        row.get("MDP_WINDOW_FROM"),
                        row.get("MDP_WINDOW_TO"),
                        row.get("PAYROLL_YYMM"));
            }
        }
    }

    @Test
    void SRS_G_29_establishmentHrHasNoMdpAccessAsTheWireframeDraws() throws Exception {
        // OD-125 / V078_5: the pack gives I-19 to Maker, Checker, Finance and Administrator only.
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        mvc.perform(userGet(hr, "/v1/mdp-transactions/summary"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isForbidden());
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        mvc.perform(userGet(checker, "/v1/mdp-transactions/summary"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk());
    }

    /** The Maker's upload of the drawn I-19 order card (the MDP authority order). */
    private MvcResult attach(Session s, long mdpId, String orderDate) throws Exception {
        return mvc.perform(
                        userRequest(
                                        s,
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.multipart(
                                                        "/v1/mdp-transactions/"
                                                                + mdpId
                                                                + "/documents")
                                                .file(
                                                        new org.springframework.mock.web
                                                                .MockMultipartFile(
                                                                "file",
                                                                "mdp_authority.pdf",
                                                                "application/pdf",
                                                                "%PDF-1.7 MDP authority"
                                                                        .getBytes(
                                                                                java.nio.charset
                                                                                        .StandardCharsets
                                                                                        .US_ASCII)))
                                                .param("documentType", "SANCTION_ORDER")
                                                .param("reference", "FCI/EST/2026/118")
                                                .param("orderDate", orderDate)
                                                .param("issuingOffice", "RO Chennai"))
                                .header("Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andReturn();
    }

    @Test
    void SRS_G_30_theMakerAttachesTheMdpAuthorityOrderToAnOpenEntryWhichIsListedAndServed()
            throws Exception {
        Session maker = maker();
        int payCode =
                body(mvc.perform(userGet(maker, "/v1/mdp-transactions/heads")).andReturn())
                        .get(0)
                        .get("payCode")
                        .asInt();
        MvcResult captured = capture(maker, members.get(2), payCode, "40.00");
        assertThat(captured.getResponse().getStatus()).isEqualTo(202);
        long mdpId = mdpOf(body(captured).get("approvalId").asLong());
        String today = LocalDate.now(IST).toString();
        MvcResult stored = attach(maker, mdpId, today);
        assertThat(stored.getResponse().getStatus())
                .as(stored.getResponse().getContentAsString())
                .isEqualTo(201);
        long fileId = body(stored).get("id").asLong();
        JsonNode list =
                body(
                        mvc.perform(userGet(maker, "/v1/mdp-transactions/" + mdpId + "/documents"))
                                .andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("entityName").asString()).isEqualTo("mdp_transaction");
        MvcResult file =
                mvc.perform(
                                userGet(
                                        maker,
                                        "/v1/mdp-transactions/" + mdpId + "/documents/" + fileId))
                        .andReturn();
        assertThat(file.getResponse().getStatus()).isEqualTo(200);
        assertThat(file.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(file.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        // A future order date is refused by validation (400); nothing more is stored.
        assertThat(
                        attach(maker, mdpId, LocalDate.now(IST).plusDays(1).toString())
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        // The Checker views the order but may not attach one (no I-19 CREATE).
        Session checker = checker();
        assertThat(attach(checker, mdpId, today).getResponse().getStatus()).isEqualTo(403);
        assertThat(
                        mvc.perform(
                                        userGet(
                                                checker,
                                                "/v1/mdp-transactions/" + mdpId + "/documents"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        // Another unit's Maker: the entry is not found (no existence leak), nor its file.
        Session delhi = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(attach(delhi, mdpId, today).getResponse().getStatus()).isEqualTo(404);
        assertThat(
                        mvc.perform(
                                        userGet(
                                                delhi,
                                                "/v1/mdp-transactions/"
                                                        + mdpId
                                                        + "/documents/"
                                                        + fileId))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(
                        mvc.perform(userGet(maker, "/v1/mdp-transactions/999999999/documents"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        // Once decided (approved), the entry takes no more order (409).
        assertThat(
                        decide(
                                checker,
                                body(captured).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(attach(maker, mdpId, today).getResponse().getStatus()).isEqualTo(409);
        assertThat(
                        body(
                                mvc.perform(
                                                userGet(
                                                        maker,
                                                        "/v1/mdp-transactions/"
                                                                + mdpId
                                                                + "/documents"))
                                        .andReturn()))
                .hasSize(1);
    }
}
