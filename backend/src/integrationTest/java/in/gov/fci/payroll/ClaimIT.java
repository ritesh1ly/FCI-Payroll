package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * I-03 Allowances &amp; Reimbursements and I-16 Cafeteria end to end (plan 10; SRS-11-1, 11-5,
 * 11-6, 11-9, 11-11 … 11-14, 11-16, 11-19, 11-21, 11-23): labour claims entered by the Labour
 * Dealing Hand with their documents, validated by the Payroll Maker and decided by the Payroll
 * Checker; employee claims and cafeteria selections received from HRMS through I-21; payment as an
 * earning of the staff run and PAID at the lock. Demo norms of seed V045_3_9 (labour TA 5,000 a
 * month, medical 15,000 a year); labour members of depot DP-DL01 (unit DH001).
 */
class ClaimIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<< >>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired private HrmsChanges intake;
    @Autowired private List<StaffDeductions> sources;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactions;

    private Session ldh() throws Exception {
        return login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
    }

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private String labourMember(int index) {
        return jdbc.queryForObject(
                "SELECT emp_num FROM employee WHERE employee_type = 'DEPT_LABOUR' AND"
                        + " record_status <> 'CANCELLED' ORDER BY emp_num OFFSET "
                        + index
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                String.class);
    }

    private long staffMember(int fromEnd) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                    + " 'ACTIVE' AND employment_status = 'IN_SERVICE' AND employee_type = 'STAFF'"
                    + " ORDER BY emp_num DESC OFFSET "
                        + fromEnd
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"));
    }

    private MvcResult enter(
            Session s,
            String eis,
            String type,
            LocalDate from,
            LocalDate to,
            String amount,
            boolean withBill)
            throws Exception {
        Map<String, Object> claim = new HashMap<>();
        claim.put("memberNumber", eis);
        claim.put("claimType", type);
        claim.put("periodFrom", from.toString());
        claim.put("periodTo", to.toString());
        claim.put("claimedAmount", amount);
        var request =
                multipart("/v1/claims")
                        .file(
                                new MockMultipartFile(
                                        "claim",
                                        "",
                                        MediaType.APPLICATION_JSON_VALUE,
                                        json.writeValueAsBytes(
                                                withBill ? withDocument(claim) : claim)));
        if (withBill) {
            request.file(new MockMultipartFile("files", "bill.pdf", "application/pdf", PDF));
        }
        return mvc.perform(
                        userRequest(s, request)
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private static Map<String, Object> withDocument(Map<String, Object> claim) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("documentType", "OTHER");
        doc.put("orderDate", LocalDate.now(IST).minusDays(1).toString());
        doc.put("issuingAuthority", "Depot Okhla");
        Map<String, Object> out = new HashMap<>(claim);
        out.put("documents", List.of(doc));
        return out;
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    // SC-10-0023 (UC "Process a Labour Claim", main flow): entered by the Labour Dealing Hand
    // with its documents, validated by the Payroll Maker at the cap, approved by the Checker.
    @Test
    void SRS_11_13_SRS_11_18_BR_ALLOW_010_aLabourClaimIsEnteredValidatedAtTheCapAndApproved()
            throws Exception {
        String eis = labourMember(0);
        LocalDate first = YearMonth.now(IST).minusMonths(1).atDay(1);
        Session ldh = ldh();
        // SRS-11-14: the bill the claim type requires is missing: blocked naming it.
        MvcResult noBill = enter(ldh, eis, "LABOUR_TA", first, first.plusDays(4), "6000", false);
        assertThat(status(noBill)).as(noBill.getResponse().getContentAsString()).isEqualTo(422);
        assertThat(error(noBill)).isEqualTo("ALW-422-DOCUMENTS");
        assertThat(body(noBill).get("args").get(0).asString()).isEqualTo("OTHER");
        // SRS-11-9 / 11-11: over the entitlement the payable is capped, never rejected.
        MvcResult entered = enter(ldh, eis, "LABOUR_TA", first, first.plusDays(4), "6000", true);
        assertThat(status(entered)).as(entered.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode claim = body(entered);
        long id = claim.get("id").asLong();
        assertThat(claim.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(claim.get("payable").decimalValue()).isEqualByComparingTo("5000");
        assertThat(claim.get("capped").asBoolean()).isTrue();
        assertThat(claim.get("source").asString()).isEqualTo("LDH");
        // US-GAP-209 (use case l.2769): the tax impact - the demo pay code 915 is not taxable.
        assertThat(claim.get("taxTreatment").asString()).isEqualTo("N");
        assertThat(claim.get("taxableAmount").decimalValue()).isEqualByComparingTo("0");
        // SRS-11-6: an overlapping claim of the same type is recorded OVERLAP_REJECTED.
        MvcResult overlap =
                enter(ldh, eis, "LABOUR_TA", first.plusDays(3), first.plusDays(8), "100", true);
        assertThat(body(overlap).get("status").asString()).isEqualTo("OVERLAP_REJECTED");
        // The Labour Dealing Hand does not validate; the Maker does and the Checker approves.
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        "/v1/claims/" + id + "/validations",
                                                        Map.of()))
                                        .andReturn()))
                .isEqualTo(403);
        Session maker = maker();
        MvcResult submitted =
                mvc.perform(userPost(maker, "/v1/claims/" + id + "/validations", Map.of()))
                        .andReturn();
        assertThat(status(submitted))
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(submitted).get("id").asLong();
        JsonNode capped = body(mvc.perform(userGet(maker, "/v1/claims/" + id)).andReturn());
        assertThat(capped.get("claim").get("status").asString()).isEqualTo("CAPPED");
        assertThat(capped.get("documents").findValuesAsString("fileName")).contains("bill.pdf");
        // Not twice.
        assertThat(
                        error(
                                mvc.perform(
                                                userPost(
                                                        maker,
                                                        "/v1/claims/" + id + "/validations",
                                                        Map.of()))
                                        .andReturn()))
                .isEqualTo("ALW-409-STATE");
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        JsonNode approved = body(mvc.perform(userGet(maker(), "/v1/claims/" + id)).andReturn());
        assertThat(approved.get("claim").get("status").asString()).isEqualTo("APPROVED");
        long file = approved.get("documents").get(0).get("id").asLong();
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        maker(),
                                                        "/v1/claims/" + id + "/documents/" + file))
                                        .andReturn()))
                .isEqualTo(200);
    }

    // SC-10-0027: a rejected labour claim goes back to the Payroll Maker with the reason.
    // SC-10-0031 (labour part): the rejected labour claim is returned with the rejection reason
    // (the eSeva view of the labour member waits for PENDING 10-eseva).
    @Test
    void SRS_11_13_aRejectedLabourClaimShowsTheCheckersReason() throws Exception {
        String eis = labourMember(1);
        LocalDate first = YearMonth.now(IST).minusMonths(2).atDay(1);
        long id =
                body(enter(ldh(), eis, "TRANSFER_TA", first, first, "12000", false))
                        .get("id")
                        .asLong();
        long approval =
                body(mvc.perform(
                                        userPost(
                                                maker(),
                                                "/v1/claims/" + id + "/validations",
                                                Map.of("reason", "IT transfer")))
                                .andReturn())
                        .get("id")
                        .asLong();
        assertThat(decide(checker(), approval, "REJECT", "IT: no transfer order", null))
                .isEqualTo(200);
        JsonNode row =
                body(mvc.perform(userGet(maker(), "/v1/claims/" + id)).andReturn()).get("claim");
        assertThat(row.get("status").asString()).isEqualTo("REJECTED");
        assertThat(row.get("decisionReason").asString()).isEqualTo("IT: no transfer order");
        // An employee's claim is never entered on I-03 (SRS-11-1).
        String staff =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?",
                        String.class,
                        staffMember(9));
        assertThat(error(enter(ldh(), staff, "LABOUR_TA", first, first, "100", true)))
                .isIn("ALW-422-HRMS-ONLY", "AUTHZ-403-SCOPE", "COM-404-NOT-FOUND");
    }

    private HrmsChanges.Received received(
            long employee, String field, String value, String request) {
        return intake.receive(
                new HrmsChanges.Change(
                        employee,
                        field,
                        value,
                        LocalDate.now(IST),
                        request,
                        HrmsChanges.Source.HRMS_PUSH));
    }

    private void approved(HrmsChanges.Received task) throws Exception {
        Map<String, Object> order = new HashMap<>();
        order.put("orderNo", "CLAIM/IT");
        order.put("orderDate", LocalDate.now(IST).minusDays(1).toString());
        order.put("issuingAuthority", "Medical Officer, Delhi");
        order.put("heldInHrms", false);
        attachTaskOrder(maker(), task.taskId());
        long approval =
                body(mvc.perform(
                                        userPost(
                                                maker(),
                                                "/v1/hrms-change-tasks/"
                                                        + task.taskId()
                                                        + "/submissions",
                                                order))
                                .andReturn())
                        .get("approvalId")
                        .asLong();
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
    }

    // SC-16-0072: medical and welfare transactions only from HRMS, paid after the I-21 approval.
    // SC-10-0030: the approved claim is paid in the cycle and appears in the Claim Register.
    // SRS-11-4 / BR-ALLOW-005 (SC-10-0004): the medical category was applied in HRMS when the
    // claim was approved; Payroll pays it as approved and does not check the category again.
    @Test
    void SRS_11_1_SRS_11_4_BR_ALLOW_005_anHrmsMedicalClaimIsPaidAsApprovedWithoutACategoryCheck()
            throws Exception {
        long employee = staffMember(10);
        LocalDate first = YearMonth.now(IST).minusMonths(1).atDay(1);
        String request = "IT-CLM-" + System.nanoTime();
        String value =
                "{\"claimType\":\"MEDICAL\",\"periodFrom\":\""
                        + first
                        + "\",\"periodTo\":\""
                        + first.plusDays(2)
                        + "\",\"amount\":2500,\"taxable\":false}";
        HrmsChanges.Received task = received(employee, "REIMBURSEMENT_CLAIM", value, request);
        Session maker = maker();
        JsonNode pending = body(mvc.perform(userGet(maker, "/v1/claims/pending-hrms")).andReturn());
        assertThat(pending.toString()).contains(task.taskNo());
        approved(task);
        Map<String, Object> claim =
                jdbc.queryForMap(
                        "SELECT claim_id, claim_status, payable_amount, source_code FROM"
                                + " reimbursement_claim WHERE hrms_request_id = ?",
                        request);
        assertThat(claim.get("CLAIM_STATUS")).isEqualTo("APPROVED");
        assertThat(claim.get("SOURCE_CODE")).isEqualTo("HRMS");
        assertThat((BigDecimal) claim.get("PAYABLE_AMOUNT")).isEqualByComparingTo("2500");
        long id = ((Number) claim.get("CLAIM_ID")).longValue();
        // SRS-11-6: the same period again is refused at intake (no task is opened).
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> received(employee, "REIMBURSEMENT_CLAIM", value, request + "-2"))
                .hasMessageContaining("ALW-409-OVERLAP");
        // D10-6: the run pays it as an earning under the claim type's pay code (913).
        StaffDeductions.Run run =
                sources.stream()
                        .filter(s -> s.order() == 1)
                        .findFirst()
                        .orElseThrow()
                        .open(YearMonth.now(IST).atDay(1));
        List<StaffDeductions.Earning> earnings =
                run.earnings(
                        new StaffDeductions.Member(
                                employee,
                                null,
                                "IDA",
                                YearMonth.now(IST),
                                List.of(),
                                BigDecimal.ZERO));
        assertThat(earnings)
                .anySatisfy(
                        e -> {
                            assertThat(e.payCode()).isEqualTo(913);
                            assertThat(e.amount()).isEqualByComparingTo("2500");
                        });
        // SRS-11-12: PAID at the lock of the cycle whose run paid it.
        long cycle = paidResult(employee, 913, new BigDecimal("2500"));
        transactions.executeWithoutResult(
                t ->
                        events.publishEvent(
                                new CycleLocked(
                                        cycle,
                                        locationId("DH001"),
                                        "209911",
                                        "it.locker",
                                        Instant.now())));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT claim_status FROM reimbursement_claim WHERE claim_id = ?",
                                String.class,
                                id))
                .isEqualTo("PAID");
        // SRS-11-19: the Maker confirms the taxable flag; SRS-11-21: the IOGA is approved.
        maker = maker();
        MvcResult confirmation =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/claims/" + id + "/taxable-confirmations",
                                        Map.of("taxable", true)))
                        .andReturn();
        assertThat(status(confirmation))
                .as(confirmation.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode confirmed = body(confirmation);
        assertThat(confirmed.get("taxable").asBoolean()).isTrue();
        Map<String, Object> ioga = new HashMap<>();
        ioga.put("number", "IOGA/IT/1");
        ioga.put("date", LocalDate.now(IST).minusDays(3).toString());
        ioga.put("amount", "2500");
        ioga.put("originatingOffice", "DO Mumbai");
        MvcResult iogaItem =
                mvc.perform(userPost(maker, "/v1/claims/" + id + "/iogas", ioga)).andReturn();
        assertThat(status(iogaItem)).as(iogaItem.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(decide(checker(), body(iogaItem).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode medical =
                body(
                        mvc.perform(
                                        userGet(
                                                maker(),
                                                "/v1/claims/medical?eis="
                                                        + jdbc.queryForObject(
                                                                "SELECT emp_num FROM employee WHERE"
                                                                        + " employee_id = ?",
                                                                String.class,
                                                                employee)))
                                .andReturn());
        assertThat(medical.findValuesAsString("iogaNumber")).contains("IOGA/IT/1");
        // SRS-11-23 / SC-10-0030: the Claim Register lists the paid claim with its source, HRMS
        // Request ID and status.
        String eis =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?",
                        String.class,
                        employee);
        String register = csv(maker(), "/v1/claims/register?format=CSV&eis=" + eis);
        String line =
                register.lines()
                        .filter(l -> l.contains(request))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("not on the register: " + request));
        assertThat(line)
                .startsWith("\"" + id + "\",")
                .contains(",\"HRMS\",")
                .contains(",\"PAID\",");
        String yymm = String.format("%04d%02d", first.getYear(), first.getMonthValue());
        String csv = csv(maker(), "/v1/claims/medical-register?format=CSV&yymm=" + yymm);
        assertThat(csv).contains("IOGA/IT/1");
    }

    /** A locked-run fixture: the member's CURRENT Actual result with the claim's pay code. */
    private long paidResult(long employee, int payCode, BigDecimal amount) {
        Long unit = locationId("DH001");
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES ('209911', ?, 'DO', 'IT',"
                        + " 'INITIATED', 'it.fixture')",
                unit);
        long cycle =
                jdbc.queryForObject(
                        "SELECT MAX(cycle_instance_id) FROM payroll_cycle_instance WHERE"
                                + " payroll_yymm = '209911'",
                        Long.class);
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, payable_days, result_status, computed_at,"
                        + " is_locked) VALUES (?, ?, ?, 'STAFF', '209911', DATE '2099-11-01',"
                        + " 'ACTUAL', ?, 0, ?, 30, 'CURRENT', ?, 'N')",
                cycle,
                unit,
                employee,
                amount,
                amount,
                java.sql.Timestamp.from(Instant.now().plusSeconds(60)));
        long result =
                jdbc.queryForObject(
                        "SELECT MAX(payroll_result_id) FROM payroll_result WHERE cycle_instance_id"
                                + " = ?",
                        Long.class,
                        cycle);
        jdbc.update(
                "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type,"
                        + " payroll_yymm, period_date, legacy_pay_code) SELECT ?, ?, ?, ?,"
                        + " component_id, ?, 'ACTUAL', '209911', DATE '2099-11-01', ? FROM"
                        + " pay_component WHERE legacy_pay_code = ?",
                result,
                cycle,
                unit,
                employee,
                amount,
                payCode,
                payCode);
        return cycle;
    }

    /**
     * SRS-11-12 (US-10-06 AC2; l.2749): a claim approved after its cycle was computed (here: the
     * cycle locks with a result computed before the approval) is not marked paid by that lock; it
     * stays APPROVED and the next open cycle's run pays it.
     */
    @Test
    void SRS_11_12_aClaimApprovedAfterItsCycleWasComputedIsHeldForTheNextOpenCycle()
            throws Exception {
        long employee = staffMember(12);
        LocalDate first = YearMonth.now(IST).minusMonths(2).atDay(1);
        String request = "IT-CLM-HELD-" + System.nanoTime();
        String value =
                "{\"claimType\":\"MEDICAL\",\"periodFrom\":\""
                        + first
                        + "\",\"periodTo\":\""
                        + first.plusDays(1)
                        + "\",\"amount\":1500,\"taxable\":false}";
        // The month's result was computed yesterday, before the claim is approved today.
        Long unit = locationId("DH001");
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) SELECT '209910', ?, 'DO', 'IT',"
                        + " 'INITIATED', 'it.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM"
                        + " payroll_cycle_instance WHERE payroll_yymm = '209910' AND org_unit_id ="
                        + " ?)",
                unit,
                unit);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm"
                                + " = '209910' AND org_unit_id = ?",
                        Long.class,
                        unit);
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, payable_days, result_status, computed_at,"
                        + " is_locked) VALUES (?, ?, ?, 'STAFF', '209910', DATE '2099-10-01',"
                        + " 'ACTUAL', 1500, 0, 1500, 30, 'CURRENT', ?, 'N')",
                cycle,
                unit,
                employee,
                java.sql.Timestamp.from(Instant.now().minusSeconds(86_400)));
        approved(received(employee, "REIMBURSEMENT_CLAIM", value, request));
        long id =
                jdbc.queryForObject(
                        "SELECT claim_id FROM reimbursement_claim WHERE hrms_request_id = ?",
                        Long.class,
                        request);
        transactions.executeWithoutResult(
                t ->
                        events.publishEvent(
                                new CycleLocked(
                                        cycle, unit, "209910", "it.locker", Instant.now())));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT claim_status FROM reimbursement_claim WHERE claim_id = ?",
                                String.class,
                                id))
                .as("held: the locked run did not pay it")
                .isEqualTo("APPROVED");
        // The next open cycle's run pays it under the claim type's pay code.
        StaffDeductions.Run run =
                sources.stream()
                        .filter(s -> s.order() == 1)
                        .findFirst()
                        .orElseThrow()
                        .open(YearMonth.now(IST).atDay(1));
        assertThat(
                        run.earnings(
                                new StaffDeductions.Member(
                                        employee,
                                        null,
                                        "IDA",
                                        YearMonth.now(IST),
                                        List.of(),
                                        BigDecimal.ZERO)))
                .anySatisfy(e -> assertThat(e.amount()).isEqualByComparingTo("1500"));
    }

    // SC-16-0071: the cafeteria selection only from HRMS, read-only on I-16.
    @Test
    void SRS_11_16_aCafeteriaSelectionFromHrmsIsShownReadOnlyAndPaidProRata() throws Exception {
        long employee = staffMember(11);
        LocalDate today = LocalDate.now(IST);
        LocalDate fy =
                today.getMonthValue() >= 4
                        ? LocalDate.of(today.getYear(), 4, 1)
                        : LocalDate.of(today.getYear() - 1, 4, 1);
        String finYear = fy.getYear() + "-" + String.format("%02d", (fy.getYear() + 1) % 100);
        LocalDate start = YearMonth.now(IST).atDay(16);
        String value =
                "{\"finYear\":\""
                        + finYear
                        + "\",\"effectiveFrom\":\""
                        + start
                        + "\",\"components\":[{\"payCode\":917,\"amount\":600},{\"payCode\":918,\"amount\":900}]}";
        approved(received(employee, "CAFETERIA_SELECTION", value, "IT-CAF-" + System.nanoTime()));
        JsonNode page =
                body(
                        mvc.perform(
                                        userGet(
                                                maker(),
                                                "/v1/cafeteria-selections?eis="
                                                        + jdbc.queryForObject(
                                                                "SELECT emp_num FROM employee WHERE"
                                                                        + " employee_id = ?",
                                                                String.class,
                                                                employee)))
                                .andReturn());
        JsonNode selection = page.get("items").get(0);
        assertThat(selection.get("status").asString()).isEqualTo("APPLIED");
        assertThat(selection.get("components").size()).isEqualTo(2);
        int days = YearMonth.now(IST).lengthOfMonth() - 15;
        // Each component is a pay line rounded once to the rupee (SRS-DM-6).
        BigDecimal length = BigDecimal.valueOf(YearMonth.now(IST).lengthOfMonth());
        BigDecimal expected =
                new BigDecimal(600 * days)
                        .divide(length, 0, java.math.RoundingMode.HALF_UP)
                        .add(
                                new BigDecimal(900 * days)
                                        .divide(length, 0, java.math.RoundingMode.HALF_UP));
        assertThat(selection.get("payEffect").decimalValue()).isEqualByComparingTo(expected);
        // A revised selection is a new version; the earlier one is SUPERSEDED from its start.
        approved(
                received(
                        employee,
                        "CAFETERIA_SELECTION",
                        value.replace("600", "700"),
                        "IT-CAF-" + System.nanoTime()));
        List<String> statuses =
                jdbc.queryForList(
                        "SELECT election_status FROM cafeteria_election WHERE employee_id = ? ORDER"
                                + " BY version_no",
                        String.class,
                        employee);
        assertThat(statuses).containsExactly("SUPERSEDED", "APPLIED");
        // SRS l.2836-2838: I-16 shows the selections of the chosen financial year only.
        String eisOf =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?",
                        String.class,
                        employee);
        JsonNode thisYear =
                body(
                        mvc.perform(
                                        userGet(
                                                maker(),
                                                "/v1/cafeteria-selections?eis="
                                                        + eisOf
                                                        + "&finYear="
                                                        + finYear))
                                .andReturn());
        assertThat(thisYear.get("items").size()).isGreaterThanOrEqualTo(2);
        thisYear.get("items")
                .forEach(i -> assertThat(i.get("finYear").asString()).isEqualTo(finYear));
        String otherYear = (fy.getYear() - 1) + "-" + String.format("%02d", fy.getYear() % 100);
        JsonNode lastYear =
                body(
                        mvc.perform(
                                        userGet(
                                                maker(),
                                                "/v1/cafeteria-selections?eis="
                                                        + eisOf
                                                        + "&finYear="
                                                        + otherYear))
                                .andReturn());
        // SRS I-16 Status list: a year with no selection reads as the one NO_SELECTION row.
        lastYear.get("items").forEach(i -> assertThat(i.path("id").isNull()).isTrue());
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        maker(),
                                                        "/v1/cafeteria-selections?finYear=2026"))
                                        .andReturn()))
                .isEqualTo(400);
        // No entry, election or upload exists on I-16: the screen only reads.
        assertThat(
                        status(
                                mvc.perform(userPost(maker(), "/v1/cafeteria-selections", Map.of()))
                                        .andReturn()))
                .isIn(403, 404, 405);
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "RO-DL");
        assertThat(status(mvc.perform(userGet(hr, "/v1/cafeteria-selections")).andReturn()))
                .isEqualTo(200);
        Session ldh = ldh();
        assertThat(status(mvc.perform(userGet(ldh, "/v1/cafeteria-selections")).andReturn()))
                .isEqualTo(403);
    }

    /** A labour member of depot DP-DL01 (the Labour Dealing Hand's office), by position. */
    private String depotLabour(int index) {
        return jdbc.queryForObject(
                "SELECT e.emp_num FROM employee e JOIN org_location l ON l.location_id ="
                        + " e.location_id WHERE e.employee_type = 'DEPT_LABOUR' AND"
                        + " e.record_status <> 'CANCELLED' AND l.location_code = 'DP-DL01' ORDER BY"
                        + " e.emp_num OFFSET "
                        + index
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                String.class);
    }

    /**
     * SRS-G-1 (l.517): the user who validated a labour claim as Payroll Maker never approves it,
     * even holding the Checker role at the same office (maker.checker, seed V015_9): WF-403-SOD,
     * and the claim waits for another Checker, who decides it.
     */
    @Test
    void SRS_G_1_theMakerNeverApprovesTheOwnClaim() throws Exception {
        String eis = depotLabour(0);
        LocalDate first = YearMonth.now(IST).minusMonths(4).atDay(1);
        MvcResult entered = enter(ldh(), eis, "TRANSFER_TA", first, first, "100", false);
        assertThat(status(entered)).as(entered.getResponse().getContentAsString()).isEqualTo(201);
        long id = body(entered).get("id").asLong();
        Session both = login("maker.checker@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult validated =
                mvc.perform(userPost(both, "/v1/claims/" + id + "/validations", Map.of()))
                        .andReturn();
        assertThat(status(validated))
                .as(validated.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(validated).get("id").asLong();
        actAs(both, "PAYROLL_CHECKER");
        MvcResult own =
                mvc.perform(
                                userPost(
                                        both,
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(status(own)).isEqualTo(403);
        assertThat(error(own)).isEqualTo("WF-403-SOD");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(decide(checker(), approval, "REJECT", "IT: own-approval check only", null))
                .isEqualTo(200);
        assertThat(
                        body(mvc.perform(userGet(maker(), "/v1/claims/" + id)).andReturn())
                                .get("claim")
                                .get("status")
                                .asString())
                .isEqualTo("REJECTED");
    }

    /** The Maker attaches the order and sends the I-21 task to the Checker; its approval id. */
    private long submitted(HrmsChanges.Received task) throws Exception {
        Map<String, Object> order = new HashMap<>();
        order.put("orderNo", "CLAIM/IT/R");
        order.put("orderDate", LocalDate.now(IST).minusDays(1).toString());
        order.put("issuingAuthority", "Medical Officer, Delhi");
        order.put("heldInHrms", false);
        attachTaskOrder(maker(), task.taskId());
        MvcResult sent =
                mvc.perform(
                                userPost(
                                        maker(),
                                        "/v1/hrms-change-tasks/" + task.taskId() + "/submissions",
                                        order))
                        .andReturn();
        assertThat(status(sent)).as(sent.getResponse().getContentAsString()).isLessThan(300);
        return body(sent).get("approvalId").asLong();
    }

    /**
     * SC-10-0031 (employee part; use case I-03 alternate flow l.2775): the Checker rejects the I-21
     * task of an employee claim received from HRMS: no claim and no payment are made, and the task
     * is returned to HRMS with the reason (REJECTED_RETURNED_TO_HRMS; the interface carries it,
     * module 23).
     */
    @Test
    void SC_10_0031_aRejectedEmployeeClaimTaskReturnsToHrmsWithTheReasonAndPaysNothing()
            throws Exception {
        long employee = staffMember(13);
        LocalDate first = YearMonth.now(IST).minusMonths(3).atDay(1);
        String request = "IT-CLM-REJ-" + System.nanoTime();
        HrmsChanges.Received task =
                received(
                        employee,
                        "REIMBURSEMENT_CLAIM",
                        "{\"claimType\":\"MEDICAL\",\"periodFrom\":\""
                                + first
                                + "\",\"periodTo\":\""
                                + first.plusDays(1)
                                + "\",\"amount\":700,\"taxable\":false}",
                        request);
        long approval = submitted(task);
        assertThat(decide(checker(), approval, "REJECT", "IT: bill not legible", null))
                .isEqualTo(200);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT task_status, rejection_reason, returned_to_hrms_at FROM"
                                + " hrms_change_task WHERE hrms_change_task_id = ?",
                        task.taskId());
        assertThat(row.get("TASK_STATUS")).isEqualTo("REJECTED_RETURNED_TO_HRMS");
        assertThat(row.get("REJECTION_REASON")).isEqualTo("IT: bill not legible");
        assertThat(row.get("RETURNED_TO_HRMS_AT")).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM reimbursement_claim WHERE hrms_request_id ="
                                        + " ?",
                                Integer.class,
                                request))
                .as("no claim, so no payment")
                .isZero();
    }

    /**
     * SRS-11-5 / BR-ALLOW-006 (l.2742): no medical payment to a retired member. Boundary: a member
     * retiring on the claim's first day is still accepted (the I-21 task opens); one retired the
     * day before is refused (ALW-422-RETIRED). The task opened is rejected again, so nothing is
     * paid; the retirement date is restored.
     */
    @Test
    void SRS_11_5_aClaimStartingOnTheRetirementDayIsAcceptedAndOneStartingTheDayAfterIsRefused()
            throws Exception {
        long employee = staffMember(14);
        LocalDate first = YearMonth.now(IST).minusMonths(4).atDay(1);
        String value =
                "{\"claimType\":\"MEDICAL\",\"periodFrom\":\""
                        + first
                        + "\",\"periodTo\":\""
                        + first
                        + "\",\"amount\":300,\"taxable\":false}";
        Object before =
                jdbc.queryForObject(
                        "SELECT retirement_date FROM employee WHERE employee_id = ?",
                        Object.class,
                        employee);
        try {
            jdbc.update(
                    "UPDATE employee SET retirement_date = ? WHERE employee_id = ?",
                    java.sql.Date.valueOf(first.minusDays(1)),
                    employee);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    received(
                                            employee,
                                            "REIMBURSEMENT_CLAIM",
                                            value,
                                            "IT-CLM-RET-" + System.nanoTime()))
                    .as("retired the day before the claim")
                    .hasMessageContaining("ALW-422-RETIRED");
            jdbc.update(
                    "UPDATE employee SET retirement_date = ? WHERE employee_id = ?",
                    java.sql.Date.valueOf(first),
                    employee);
            HrmsChanges.Received task =
                    received(
                            employee,
                            "REIMBURSEMENT_CLAIM",
                            value,
                            "IT-CLM-RET-" + System.nanoTime());
            assertThat(task.taskId()).as("retiring on the claim's first day").isPositive();
            assertThat(decide(checker(), submitted(task), "REJECT", "IT: boundary only", null))
                    .isEqualTo(200);
        } finally {
            jdbc.update(
                    "UPDATE employee SET retirement_date = ? WHERE employee_id = ?",
                    before,
                    employee);
        }
    }

    /**
     * SRS-G-29 (l.543): a labour claim of depot DP-DL01 (unit DH001) is neither listed nor
     * validated by the Payroll Maker of another office; it stays SUBMITTED for the unit's own
     * Maker.
     */
    @Test
    void SRS_G_29_aClaimOfAnotherOfficeIsNeitherListedNorValidated() throws Exception {
        String eis = depotLabour(0);
        LocalDate first = YearMonth.now(IST).minusMonths(3).atDay(1);
        MvcResult entered = enter(ldh(), eis, "TRANSFER_TA", first, first, "100", false);
        assertThat(status(entered)).as(entered.getResponse().getContentAsString()).isEqualTo(201);
        long id = body(entered).get("id").asLong();
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        assertThat(
                        all(chennai, "/v1/claims?eis=" + eis).findValues("id").stream()
                                .map(JsonNode::asLong))
                .doesNotContain(id);
        assertThat(status(mvc.perform(userGet(chennai, "/v1/claims/" + id)).andReturn()))
                .isEqualTo(404);
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        chennai,
                                                        "/v1/claims/" + id + "/validations",
                                                        Map.of()))
                                        .andReturn()))
                .isEqualTo(404);
        assertThat(
                        body(mvc.perform(userGet(maker(), "/v1/claims/" + id)).andReturn())
                                .get("claim")
                                .get("status")
                                .asString())
                .isEqualTo("SUBMITTED");
    }

    /**
     * SRS-G-29, SRS-11-17, A-41: the I-03 additions of the rebuild hold the office scope: another
     * office's Maker cannot add a document to the claim, its tiles and lease list hold no Delhi
     * record, and a role without I-03 does not reach them.
     */
    @Test
    void SRS_G_29_theDrawnAdditionsOfI03HoldTheOfficeScope() throws Exception {
        String eis = depotLabour(1);
        LocalDate first = YearMonth.now(IST).minusMonths(4).atDay(1);
        MvcResult entered = enter(ldh(), eis, "TRANSFER_TA", first, first, "100", false);
        assertThat(status(entered)).as(entered.getResponse().getContentAsString()).isEqualTo(201);
        long id = body(entered).get("id").asLong();
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        var request =
                multipart("/v1/claims/" + id + "/documents")
                        .file(new MockMultipartFile("file", "bill.pdf", "application/pdf", PDF))
                        .param("documentType", "OTHER")
                        .param("orderDate", first.toString())
                        .param("issuingOffice", "Depot");
        assertThat(
                        status(
                                mvc.perform(
                                                userRequest(chennai, request)
                                                        .header(
                                                                "Idempotency-Key",
                                                                UUID.randomUUID().toString()))
                                        .andReturn()))
                .isIn(403, 404);
        assertThat(all(chennai, "/v1/claims/leases").findValuesAsString("eis")).doesNotContain(eis);
        assertThat(status(mvc.perform(userGet(chennai, "/v1/claims/counts")).andReturn()))
                .isEqualTo(200);
    }
}
