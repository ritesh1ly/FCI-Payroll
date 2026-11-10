package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * I-20 Off-Cycle / Exceptional Salary Processing end to end (SRS-G-10, SRS-G-16, SRS-G-37; DDL
 * {@code off_cycle_payment}). The member is staff 105220 at Delhi HQ (DH001); every test uses its
 * own salary month so the duplicate-payment check of one test never sees another's case.
 */
class OffCycleIT extends ApiTestSupport {

    private static final String MAKER = "maker.delhi@fci-demo.test";
    private static final String CHECKER = "ddo.delhi@fci-demo.test";
    private static final String EIS = "105220";

    private Session maker() throws Exception {
        return login(MAKER, "PAYROLL_MAKER", "DH001");
    }

    private ResultActions capture(Session s, String type, String month) throws Exception {
        return capture(s, EIS, type, month, "HQ/PAY/" + month);
    }

    private ResultActions capture(
            Session s, String eis, String type, String month, String approvalReference)
            throws Exception {
        if (month.matches("\\d{6}")) {
            clearPriorFixtures(month);
        }
        return mvc.perform(
                userPost(
                        s,
                        "/v1/off-cycle",
                        Map.of(
                                "eis",
                                eis,
                                "caseType",
                                type,
                                "salaryYymm",
                                month,
                                "approvalReference",
                                approvalReference)));
    }

    private long approvalOf(Session s, long id) throws Exception {
        return get(s, id).get("approvalId").asLong();
    }

    private long captured(Session s, String month) throws Exception {
        return body(capture(s, "LATE_JOINER", month).andExpect(status().isCreated()).andReturn())
                .get("id")
                .asLong();
    }

    private void attachOrder(Session s, long id) throws Exception {
        mvc.perform(
                        userRequest(
                                        s,
                                        multipart("/v1/off-cycle/" + id + "/order")
                                                .file(
                                                        new MockMultipartFile(
                                                                "file",
                                                                "sanction.pdf",
                                                                "application/pdf",
                                                                "%PDF-1.7 sanction"
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .US_ASCII)))
                                                .param("documentType", "SANCTION_ORDER")
                                                .param("reference", "HQ/PAY/17")
                                                .param("orderDate", "2026-10-01")
                                                .param("issuingAuthority", "GM (Pers) HQ"))
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
    }

    /** The case's amount as stored (the value the checker is shown). */
    private java.math.BigDecimal approvedAmount(long id) {
        return jdbc.queryForObject(
                "SELECT amount FROM off_cycle_payment WHERE off_cycle_id = ?",
                java.math.BigDecimal.class,
                id);
    }

    private ResultActions submit(Session s, long id) throws Exception {
        return mvc.perform(userPost(s, "/v1/off-cycle/" + id + "/submit", Map.of()));
    }

    private JsonNode get(Session s, long id) throws Exception {
        return body(
                mvc.perform(userGet(s, "/v1/off-cycle/" + id))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    /** A cycle of the member's unit for {@code month} in {@code status}, created once. */
    private void cycle(String month, String cycleStatus) {
        Long unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE emp_num = ?", Long.class, EIS);
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                                + " org_unit_id = ?",
                        Integer.class,
                        month,
                        unit);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id,"
                            + " org_unit_level, fin_year, cycle_status, trigger_type, initiated_by)"
                            + " VALUES (?, ?, 'DO', '2026-27', ?, 'ON_DEMAND', 'it.fixture')",
                    month,
                    unit,
                    cycleStatus);
        } else {
            // Insert-if-absent plus update: another IT may have opened the same month of the unit
            // in another state, and this class must not depend on the order the suite runs in.
            jdbc.update(
                    "UPDATE payroll_cycle_instance SET cycle_status = ?, row_version = row_version"
                        + " + 1 WHERE payroll_yymm = ? AND org_unit_id = ? AND cycle_status <> ?",
                    cycleStatus,
                    month,
                    unit,
                    cycleStatus);
        }
    }

    @Test
    void SRS_G_10_anApprovedCaseIsExcludedFromTheRegularRunAndCannotBePaidTwice() throws Exception {
        Session maker = maker();
        JsonNode c =
                body(
                        capture(maker, "LATE_JOINER", "202609")
                                .andExpect(status().isCreated())
                                .andReturn());
        long id = c.get("id").asLong();
        assertThat(c.get("reference").asString()).matches("OFC-\\d{8}");
        assertThat(c.get("status").asString()).isEqualTo("DRAFT");
        assertThat(c.get("duplicateCheck").asString()).isEqualTo("PASS");
        assertThat(c.get("amount").decimalValue()).isPositive(); // computed, never typed
        assertThat(c.get("processingYymm").asString()).isNotEqualTo("202609");

        // SRS-G-16: no submission without the approval note / order.
        submit(maker, id)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("OFC-422-ORDER"));
        // SRS-20-27 (US-GAP-25): the blocked submission is in the audit trail, kept although the
        // submission rolled back.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'off_cycle_payment' AND entity_key = ? AND field_name"
                                        + " = 'SUBMISSION_BLOCKED' AND new_value ="
                                        + " 'ATTACHMENT_MISSING' AND changed_by = ?",
                                Integer.class,
                                String.valueOf(id),
                                maker.loginId()))
                .isEqualTo(1);
        attachOrder(maker, id);
        JsonNode pending = body(submit(maker, id).andExpect(status().isAccepted()).andReturn());
        assertThat(pending.get("status").asString()).isEqualTo("PENDING_CHECKER");

        // Not cancellable nor editable while the Checker decides.
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/off-cycle/" + id + "/cancel",
                                Map.of("reason", "changed mind")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OFC-409-STATUS"));

        Session checker = login(CHECKER, "PAYROLL_CHECKER", "DH001");
        // SRS-22-4: the checker sees the case's amount as its value / impact.
        JsonNode inboxItem = null;
        for (JsonNode i : all(checker, "/v1/inbox")) {
            if (i.get("approvalId").asLong() == pending.get("approvalId").asLong()) {
                inboxItem = i;
            }
        }
        assertThat(inboxItem).isNotNull();
        assertThat(inboxItem.get("impactValue").decimalValue())
                .isEqualByComparingTo(approvedAmount(id));
        // SRS-G-31 "View order": the checker opens the case's order from the inbox; a session of
        // another office the item does not wait for gets nothing.
        long approvalId = pending.get("approvalId").asLong();
        JsonNode documents =
                body(
                        mvc.perform(userGet(checker, "/v1/approvals/" + approvalId + "/documents"))
                                .andReturn());
        assertThat(documents).hasSize(1);
        MvcResult order =
                mvc.perform(
                                userGet(
                                        checker,
                                        "/v1/approvals/"
                                                + approvalId
                                                + "/documents/"
                                                + documents.get(0).get("id").asLong()))
                        .andReturn();
        assertThat(order.getResponse().getStatus()).isEqualTo(200);
        assertThat(order.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(
                        mvc.perform(
                                        userGet(
                                                login(
                                                        "maker.kolkata@fci-demo.test",
                                                        "PAYROLL_MAKER",
                                                        "KL001"),
                                                "/v1/approvals/" + approvalId + "/documents"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isIn(403, 404);
        assertThat(decide(checker, approvalId, "APPROVE", null, null)).isEqualTo(200);
        JsonNode approved = get(maker, id);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("excludedFromYymm").asString()).isEqualTo("202609");
        assertThat(approved.get("invoiceStatus").asString()).isEqualTo("NOT_RAISED");

        // A second case for the same month fails the duplicate check and is Blocked.
        JsonNode second =
                body(capture(maker, "OTHER", "202609").andExpect(status().isCreated()).andReturn());
        assertThat(second.get("status").asString()).isEqualTo("BLOCKED");
        assertThat(second.get("duplicateCheck").asString()).startsWith("FAIL: ");
        long secondId = second.get("id").asLong();
        attachOrder(maker, secondId);
        submit(maker, secondId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OFC-409-DUPLICATE"));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'off_cycle_payment' AND entity_key = ?",
                                Integer.class,
                                approved.get("reference").asString()))
                .isGreaterThanOrEqualTo(3); // captured, submitted, approved
    }

    @Test
    void SRS_G_10_aMonthAlreadyPaidByTheRegularRunIsBlocked() throws Exception {
        cycle("202608", "VERIFIED");
        Long employee =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, EIS);
        Long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " '202608' AND org_unit_id = (SELECT location_id FROM employee"
                                + " WHERE emp_num = ?)",
                        Long.class,
                        EIS);
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, payable_days, result_status, is_locked)"
                        + " SELECT ?, location_id, employee_id, 'STAFF', '202608', DATE"
                        + " '2026-08-01', 'ACTUAL', 50000, 5000, 45000, 31, 'CURRENT', 'N' FROM"
                        + " employee WHERE employee_id = ?",
                cycle,
                employee);
        JsonNode c =
                body(
                        capture(maker(), "LATE_JOINER", "202608")
                                .andExpect(status().isCreated())
                                .andReturn());
        assertThat(c.get("status").asString()).isEqualTo("BLOCKED");
        assertThat(c.get("duplicateCheck").asString()).contains("regular run");
    }

    @Test
    void SRS_G_10_aMonthEndLockedSalaryMonthIsPaidThroughI23() throws Exception {
        cycle("202607", "LOCKED");
        capture(maker(), "LATE_JOINER", "202607")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OFC-409-LOCKED"));
    }

    @Test
    void SRS_G_10_aFutureMonthOrAnUnknownCaseTypeIsRefused() throws Exception {
        Session maker = maker();
        capture(maker, "LATE_JOINER", "209912")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("OFC-422-INPUT"));
        capture(maker, "BONUS", "202606").andExpect(status().isBadRequest());
    }

    @Test
    void SRS_G_37_aDraftIsCancelledWithAReasonNeverDeleted() throws Exception {
        Session maker = maker();
        long id = captured(maker, "202606");
        attachOrder(maker, id);
        JsonNode cancelled =
                body(
                        mvc.perform(
                                        userPost(
                                                maker,
                                                "/v1/off-cycle/" + id + "/cancel",
                                                Map.of("reason", "Raised in error")))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("orders").asInt()).isZero(); // the order is cancelled with it
        assertThat(
                        jdbc.queryForObject(
                                "SELECT record_status FROM off_cycle_payment WHERE off_cycle_id ="
                                        + " ?",
                                String.class,
                                id))
                .isEqualTo("CANCELLED");
        mvc.perform(userPost(maker, "/v1/off-cycle/" + id + "/cancel", Map.of("reason", "again")))
                .andExpect(status().isConflict());
        // A cancelled case no longer counts against the month.
        JsonNode again =
                body(
                        capture(maker, "LATE_JOINER", "202606")
                                .andExpect(status().isCreated())
                                .andReturn());
        assertThat(again.get("duplicateCheck").asString()).isEqualTo("PASS");
    }

    @Test
    void SRS_G_10_theDrawnFilterListsTheCasesOfOneCaseTypeOnly() throws Exception {
        // I-20 Filter (Wireframes v6.1, OD-121): the case type of OFF_CYCLE_CASE_TYPE narrows the
        // register.
        Session maker = maker();
        // A month with its rates seeded and never locked here (the helper clears earlier cases of
        // it).
        long late = captured(maker, "202605");
        JsonNode lateOnly =
                body(
                        mvc.perform(userGet(maker, "/v1/off-cycle?caseType=LATE_JOINER&size=100"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(lateOnly.get("items").findValuesAsString("caseType"))
                .allMatch("LATE_JOINER"::equals)
                .isNotEmpty();
        assertThat(lateOnly.get("items").findValues("id").stream().map(JsonNode::asLong))
                .contains(late);
        JsonNode death =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/off-cycle?caseType=DEATH_IN_SERVICE&size=100"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(death.get("items").findValues("id").stream().map(JsonNode::asLong))
                .doesNotContain(late);
        // A type outside the pattern is refused before any query (400); no filter lists every type.
        mvc.perform(userGet(maker, "/v1/off-cycle?caseType=late;drop"))
                .andExpect(status().isBadRequest());
        assertThat(
                        body(mvc.perform(userGet(maker, "/v1/off-cycle?size=100")).andReturn())
                                .get("items")
                                .findValues("id")
                                .stream()
                                .map(JsonNode::asLong))
                .contains(late);
    }

    @Test
    void onlyThePayrollMakerCapturesAndTheCheckerOnlyViews() throws Exception {
        Session checker = login(CHECKER, "PAYROLL_CHECKER", "DH001");
        capture(checker, "LATE_JOINER", "202605").andExpect(status().isForbidden());
        mvc.perform(userGet(checker, "/v1/off-cycle")).andExpect(status().isOk());
        Session admin = login("sysadmin@fci-demo.test", null, null);
        // OD-125 (Wireframes v6.1 role access, V078_4): I-20 is drawn for the Administrator (read
        // only); WF-071 for FCI.
        mvc.perform(userGet(admin, "/v1/off-cycle")).andExpect(status().isOk());
    }

    @Test
    void SRS_G_10_aReturnedCaseIsCorrectedAndResubmittedARejectedOneIsFinal() throws Exception {
        Session maker = maker();
        Session checker = login(CHECKER, "PAYROLL_CHECKER", "DH001");
        long id = captured(maker, "202604");
        attachOrder(maker, id);
        submit(maker, id).andExpect(status().isAccepted());
        assertThat(decide(checker, approvalOf(maker, id), "RETURN", "Attach the GM order", null))
                .isEqualTo(200);
        assertThat(get(maker, id).get("status").asString()).isEqualTo("RETURNED");

        // Returned: the Maker may add the missing order and resubmit.
        attachOrder(maker, id);
        assertThat(get(maker, id).get("orders").asInt()).isEqualTo(2);
        submit(maker, id).andExpect(status().isAccepted());
        assertThat(decide(checker, approvalOf(maker, id), "REJECT", "Not an off-cycle case", null))
                .isEqualTo(200);
        JsonNode rejected = get(maker, id);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("excludedFromYymm").isNull()).isTrue();

        // Rejected is final: no more orders or submissions; the case is cancelled instead.
        submit(maker, id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OFC-409-STATUS"));
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/off-cycle/" + id + "/cancel",
                                Map.of("reason", "Rejected by the Checker")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void SRS_G_10_theCheckIsRerunAtApprovalAndAFailureBlocksTheApproval() throws Exception {
        String eis = "104882";
        cycle("202609", "VERIFIED");
        Session maker = maker();
        long id =
                body(capture(maker, eis, "LATE_JOINER", "202609", "HQ/PAY/RECHECK")
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asLong();
        attachOrder(maker, id);
        submit(maker, id).andExpect(status().isAccepted());

        // While the case waits, the regular run pays the month.
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, payable_days, result_status, is_locked)"
                        + " SELECT c.cycle_instance_id, e.location_id, e.employee_id, 'STAFF',"
                        + " '202609', DATE '2026-09-01', 'ACTUAL', 50000, 5000, 45000, 30,"
                        + " 'CURRENT', 'N' FROM employee e JOIN payroll_cycle_instance c ON"
                        + " c.org_unit_id = e.location_id AND c.payroll_yymm = '202609' WHERE"
                        + " e.emp_num = ?",
                eis);

        Session checker = login(CHECKER, "PAYROLL_CHECKER", "DH001");
        assertThat(decide(checker, approvalOf(maker, id), "APPROVE", null, null)).isEqualTo(409);
        JsonNode stillPending = get(maker, id);
        assertThat(stillPending.get("status").asString()).isEqualTo("PENDING_CHECKER");
        assertThat(stillPending.get("excludedFromYymm").isNull()).isTrue();
    }

    @Test
    void SRS_G_10_unknownMembersAndUnreadableReferencesAreRefused() throws Exception {
        Session maker = maker();
        capture(maker, "999999", "LATE_JOINER", "202604", "HQ/PAY/1")
                .andExpect(status().isNotFound());
        // R-ASCII: a reference the US7ASCII database cannot hold is refused at the boundary.
        capture(maker, EIS, "LATE_JOINER", "202604", "HQ/PAY/ह")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COM-400-VALIDATION"));
        capture(maker, EIS, "LATE_JOINER", "2026-04", "HQ/PAY/1")
                .andExpect(status().isBadRequest());
    }

    @Test
    void SRS_G_10_aMakerOutsideTheMembersOfficeAndEsevaUsersSeeNothing() throws Exception {
        Session delhi = maker();
        long id = captured(delhi, "202610");
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        // Another office's member and record read as unknown (404, owner 2026-10-24; OWASP A01).
        capture(kolkata, "LATE_JOINER", "202610").andExpect(status().isNotFound());
        mvc.perform(userGet(kolkata, "/v1/off-cycle/" + id)).andExpect(status().isNotFound());
        JsonNode list = all(kolkata, "/v1/off-cycle");
        assertThat(texts(list, "reference")).doesNotContain(String.format("OFC-%08d", id));

        Session eseva = login("emp.rsharma@fci-demo.test", null, null);
        mvc.perform(userGet(eseva, "/v1/off-cycle")).andExpect(status().isForbidden());
    }

    @Test
    void SRS_G_30_theOrderIsListedAndDownloadedAsAnAttachment() throws Exception {
        Session maker = maker();
        long id =
                body(capture(maker, "104882", "OTHER", "202610", "HQ/PAY/FILE")
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asLong();
        attachOrder(maker, id);
        long fileId =
                jdbc.queryForObject(
                        "SELECT MAX(attachment_id) FROM document_attachment WHERE entity_name ="
                                + " 'off_cycle_payment' AND entity_key = ?",
                        Long.class,
                        String.valueOf(id));
        mvc.perform(userGet(maker, "/v1/off-cycle/" + id + "/files/" + fileId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        JsonNode list = all(maker, "/v1/off-cycle");
        assertThat(texts(list, "reference")).contains(String.format("OFC-%08d", id));
    }

    @Test
    void SRS_G_13_theCaseListExportsAsCsvForTheRolesThatMayExport() throws Exception {
        Session maker = maker();
        String reference =
                body(capture(maker, "104882", "OTHER", "202605", "HQ/PAY/EXPORT")
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("reference")
                        .asString();
        org.springframework.test.web.servlet.MvcResult csv =
                download(maker, "/v1/off-cycle/export");
        assertThat(csv.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        String text = csv(maker, "/v1/off-cycle/export");
        assertThat(text).startsWith("\"Case reference\",\"EIS\"").contains(reference);
        // An eSeva member views no case list, so exports none.
        Session eseva = login("emp.rsharma@fci-demo.test", null, null);
        mvc.perform(userGet(eseva, "/v1/off-cycle/export")).andExpect(status().isForbidden());
    }
}
