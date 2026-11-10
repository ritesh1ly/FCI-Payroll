package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * I-17 life-cycle events and M-11 transfers (target DDL v4.0 S03; SRS-G-36, SRS-G-53, SRS-G-1,
 * SRS-1-63): I-17 is read-only, no event is keyed in Payroll. A transfer is recorded by
 * Establishment/HR, the unit's Payroll Checker approves, and only then does the employee change:
 * the office (and a reissued staff number, the old one kept in history). Refusals carry their
 * reason; a pending change blocks a second one; the Payroll Maker only views.
 */
class EmployeeEventIT extends ApiTestSupport {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    /**
     * This test's own Delhi members (790001 ...): events and transfers suspend, separate and move
     * them, so they must never be another test's member. A list of other tests' members to avoid
     * broke whenever a seed added employees; a pool of its own cannot collide.
     */
    private static final int OWN_MEMBERS = 12;

    private static final AtomicInteger NEXT = new AtomicInteger();

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    /**
     * Creates the own members once per database (idempotent): clones of a Delhi employee with
     * identifiers of their own, in the real formats (SeedDataFormatIT) - PAN AAAPA9999A with the
     * surname's initial fifth, mobile 6-9 and nine digits, a 9-18 digit account, e-mail on .test.
     */
    @BeforeEach
    void ownMembers() {
        for (int i = 1; i <= OWN_MEMBERS; i++) {
            String num = String.format("79%04d", i);
            jdbc.update(
                    "INSERT INTO employee (emp_num, first_name, last_name, birth_date, gender,"
                        + " comp_joining_date, employee_type, emp_lbr_flag, pan_number,"
                        + " designation_id, grade_id, location_id, depot_location_id,"
                        + " department_id, band_id, pay_pattern, pay_pattern_source,"
                        + " provident_fund_type, cpf_code, cpf_code_origin, parent_zone_id,"
                        + " employment_status, record_status, current_basic, mobile_number,"
                        + " email_address, bank_account_number, bank_branch_id, record_origin)"
                        + " SELECT ?, first_name, last_name, birth_date, gender, comp_joining_date,"
                        + " employee_type, emp_lbr_flag, ?, designation_id, grade_id, location_id,"
                        + " depot_location_id, department_id, band_id, pay_pattern,"
                        + " pay_pattern_source, provident_fund_type, ?, cpf_code_origin,"
                        + " parent_zone_id, 'IN_SERVICE', 'ACTIVE', current_basic, ?, ?, ?,"
                        + " bank_branch_id, record_origin FROM employee e WHERE e.emp_num ="
                        + " '105220' AND e.location_id = (SELECT location_id FROM org_location"
                        + " WHERE location_code = 'DH001') AND NOT EXISTS (SELECT 1 FROM employee x"
                        + " WHERE x.pan_number = ?)",
                    num,
                    String.format("DMOPB%04dE", 7900 + i),
                    "CPF/" + num,
                    String.format("979000%04d", i),
                    "it.event." + num + "@fci-demo.test",
                    String.format("79000%05d", i),
                    // A transfer reissues the staff number; the PAN is the permanent identity.
                    String.format("DMOPB%04dE", 7900 + i));
        }
    }

    /** One of the own Delhi members still in service at its office (one per call). */
    private String member() {
        List<String> free =
                jdbc.queryForList(
                        "SELECT e.emp_num FROM employee e JOIN org_location l ON l.location_id ="
                            + " e.location_id WHERE l.location_code = 'DH001' AND e.emp_num LIKE"
                            + " '79%' AND e.employment_status = 'IN_SERVICE' AND e.record_status ="
                            + " 'ACTIVE' ORDER BY e.emp_num",
                        String.class);
        assertThat(free).as("own members in service at DH001").isNotEmpty();
        return free.get(NEXT.getAndIncrement() % free.size());
    }

    private static Map<String, Object> event(String type, LocalDate date) {
        Map<String, Object> e = new HashMap<>();
        e.put("eventType", type);
        e.put("eventDate", date.toString());
        e.put("reason", "IT " + type);
        return e;
    }

    private MvcResult record(Session s, String eis, Map<String, Object> body) throws Exception {
        return mvc.perform(userPost(s, "/v1/employees/" + eis + "/lifecycle-events", body))
                .andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private String employmentStatus(String eis) {
        return jdbc.queryForObject(
                "SELECT employment_status FROM employee WHERE emp_num = ?", String.class, eis);
    }

    /**
     * SRS-1-63; I-17 l.3871-3875 ("all are read-only on I-17", "No event is keyed in Payroll"): no
     * role records a life-cycle event of any type, not the Establishment / HR maker who once could,
     * not the Payroll Checker, not the Payroll Maker; nothing is recorded and the employee is
     * unchanged. The read side still answers in scope.
     */
    @Test
    void SRS_1_63_noLifeCycleEventIsKeyedInPayrollByAnyRole() throws Exception {
        String eis = member();
        Session hr = hr();
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        int before = all(hr, "/v1/employees/" + eis + "/lifecycle-events").size();
        for (String type :
                java.util.List.of(
                        "SALARY_STOP",
                        "HOLD",
                        "SALARY_RELEASE",
                        "DEPUTATION_OUT",
                        "DEPUTATION_IN",
                        "SUSPENSION",
                        "REVOCATION",
                        "SEPARATION")) {
            for (Session s : java.util.List.of(hr, checker(), maker)) {
                assertThat(record(s, eis, event(type, TODAY)).getResponse().getStatus())
                        .as(type)
                        .isEqualTo(405);
            }
        }
        assertThat(all(hr, "/v1/employees/" + eis + "/lifecycle-events").size())
                .as("nothing was recorded")
                .isEqualTo(before);
        assertThat(employmentStatus(eis)).isEqualTo("IN_SERVICE");
        mvc.perform(userGet(maker, "/v1/employees/" + eis + "/lifecycle-events"))
                .andExpect(status().isOk());
        mvc.perform(userGet(maker, "/v1/lifecycle-events")).andExpect(status().isOk());
    }

    /**
     * SRS-1-63 (keep the data intact): an event keyed in Payroll before I-17 became read-only is
     * still listed as it was, with its source, and an unknown member has no history.
     */
    @Test
    void SRS_1_63_anEventKeyedBeforeStaysListedAsItWas() throws Exception {
        String eis = member();
        Long employeeId =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
        jdbc.update(
                "INSERT INTO employee_lifecycle_event (employee_id, event_type, event_date,"
                        + " reason, event_source, created_by) VALUES (?, 'HOLD', ?, 'IT legacy"
                        + " hold', 'PAYROLL', 'hr.delhi')",
                employeeId,
                java.sql.Date.valueOf(TODAY));
        JsonNode history = all(hr(), "/v1/employees/" + eis + "/lifecycle-events");
        assertThat(texts(history, "eventType")).contains("HOLD");
        assertThat(texts(history, "source")).contains("PAYROLL");
        assertThat(texts(history, "reason")).contains("IT legacy hold");
        assertThat(
                        mvc.perform(userGet(hr(), "/v1/employees/NOPE999/lifecycle-events"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
    }

    /** SRS-G-25: an office outside the session's scope sees no member's events. */
    @Test
    void SRS_1_63_anotherOfficesMemberIsNotShown() throws Exception {
        String kolkata =
                jdbc.queryForObject(
                        "SELECT MIN(e.emp_num) FROM employee e JOIN org_location l ON l.location_id"
                                + " = e.location_id WHERE l.location_code = 'KL001'",
                        String.class);
        assertThat(
                        mvc.perform(userGet(hr(), "/v1/employees/" + kolkata + "/lifecycle-events"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isIn(403, 404);
    }

    // ---------------------------------------------------------------- M-11

    private static Map<String, Object> transfer(String to, LocalDate date) {
        Map<String, Object> t = new HashMap<>();
        t.put("toLocation", to);
        t.put("transferDate", date.toString());
        t.put("relievingDate", date.toString());
        t.put("joiningDate", date.toString());
        t.put("joiningTime", "10:00");
        t.put("orderReference", "RO/TR/" + System.nanoTime() % 100000);
        // HRMS holds the order letter: its reference only (SRS-G-31); the copy flow is below.
        t.put("orderHeldInHrms", true);
        t.put("splitWage", false);
        t.put("reason", "IT transfer");
        return t;
    }

    private MvcResult move(Session s, String eis, Map<String, Object> body) throws Exception {
        return mvc.perform(userPost(s, "/v1/employees/" + eis + "/transfers", body)).andReturn();
    }

    /** Stores a transfer order copy as the screen does; returns {attachmentId, provisionalKey}. */
    private JsonNode storeOrder(Session s, String eis) throws Exception {
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder r =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/v1/employees/" + eis + "/transfer-orders");
        r.file(
                new org.springframework.mock.web.MockMultipartFile(
                        "file",
                        "transfer.pdf",
                        "application/pdf",
                        "%PDF-1.7 transfer order"
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        r.param("documentType", "OTHER")
                .param("reference", "RO/TR/77")
                .param("orderDate", TODAY.toString())
                .param("issuingAuthority", "GM (P), Delhi");
        MvcResult stored =
                mvc.perform(
                                userRequest(s, r)
                                        .header(
                                                "Idempotency-Key",
                                                java.util.UUID.randomUUID().toString()))
                        .andReturn();
        assertThat(stored.getResponse().getStatus())
                .as(stored.getResponse().getContentAsString())
                .isEqualTo(201);
        return body(stored);
    }

    /**
     * SRS-16-18, SRS-G-31, M-11 UC alternate flow (US-GAP-26): a transfer is not recorded without
     * its order reference, nor, unless HRMS holds the order, without a copy this user stored (the
     * block is audited); with the copy it goes ahead and the copy is the transfer's. M-11 field
     * table (US-GAP-27): an inactive destination is refused and not offered.
     */
    @Test
    void SRS_16_18_aTransferNeedsItsOrderCopyAndAnActiveDestination() throws Exception {
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        String eis = member();
        Map<String, Object> body = transfer("MR001", TODAY);
        body.put("orderHeldInHrms", false);
        body.remove("orderReference");
        assertThat(code(move(hr, eis, body))).isEqualTo("EMP-422-TRANSFER-ORDER");
        body.put("orderReference", "RO/TR/77");
        MvcResult bare = move(hr, eis, body);
        assertThat(code(bare)).isEqualTo("EMP-422-TRANSFER-ORDER");
        assertThat(body(bare).get("args").get(0).asString()).isEqualTo("COPY_MISSING");
        // The flag is optional: a request without it (callers written before it existed) reads as
        // "HRMS does not hold the order", not as a malformed request.
        Map<String, Object> unsaid = new HashMap<>(body);
        unsaid.remove("orderHeldInHrms");
        MvcResult silent = move(hr, eis, unsaid);
        assertThat(code(silent)).isEqualTo("EMP-422-TRANSFER-ORDER");
        assertThat(body(silent).get("args").get(0).asString()).isEqualTo("COPY_MISSING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'employee_transfer' AND entity_key = ? AND field_name"
                                        + " = 'SUBMISSION_BLOCKED'",
                                Integer.class,
                                eis))
                .isGreaterThanOrEqualTo(2);
        // A copy stored by another user is not this user's.
        JsonNode other = storeOrder(hr, eis);
        jdbc.update(
                "UPDATE document_attachment SET uploaded_by = 'someone.else' WHERE attachment_id ="
                        + " ?",
                other.get("attachmentId").asLong());
        body.put("orderAttachmentId", other.get("attachmentId").asLong());
        body.put("orderProvisionalKey", other.get("provisionalKey").asString());
        assertThat(code(move(hr, eis, body))).isEqualTo("EMP-422-TRANSFER-ORDER");
        JsonNode own = storeOrder(hr, eis);
        body.put("orderAttachmentId", own.get("attachmentId").asLong());
        body.put("orderProvisionalKey", own.get("provisionalKey").asString());
        MvcResult recorded = move(hr, eis, body);
        assertThat(recorded.getResponse().getStatus())
                .as(recorded.getResponse().getContentAsString())
                .isEqualTo(202);
        String owner =
                jdbc.queryForObject(
                        "SELECT entity_key FROM document_attachment WHERE attachment_id = ?",
                        String.class,
                        own.get("attachmentId").asLong());
        assertThat(owner).isEqualTo(String.valueOf(body(recorded).get("id").asLong()));
    }

    /**
     * SRS-G-31 (l.545) with SRS-G-1 / SRS-G-29: the Checker of another office never decides a
     * transfer order of this office. The Kolkata Checker's approval is refused (403 or the item
     * reads as unknown), nothing moves and the item still waits; the office's own Checker rejects
     * it (clean-up), which keeps the member where it was.
     */
    @Test
    void SRS_G_31_aTransferOrderCheckerOfAnotherOfficeIsRefused() throws Exception {
        Session hr = hr();
        String eis = member();
        MvcResult recorded = move(hr, eis, transfer("MR001", TODAY));
        assertThat(recorded.getResponse().getStatus())
                .as(recorded.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(recorded).get("approvalId").asLong();
        Session kolkata = login("tax.checker.kolkata@fci-demo.test", "PAYROLL_CHECKER", "KL001");
        MvcResult refused =
                mvc.perform(
                                userPost(
                                        kolkata,
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(refused.getResponse().getStatus()).isIn(403, 404);
        assertThat(code(refused)).isIn("AUTHZ-403-SCOPE", "COM-404-NOT-FOUND");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT l.location_code FROM employee e JOIN org_location l ON"
                                        + " l.location_id = e.location_id WHERE e.emp_num = ?",
                                String.class,
                                eis))
                .as("nothing moved")
                .isEqualTo("DH001");
        assertThat(decide(checker(), approval, "REJECT", "IT: another office tried", null))
                .isEqualTo(200);
    }

    // SRS-16-5: the staff-number history row; BR-FNF-007: a transfer opens no settlement;
    // BR-EMPM-003: the PAN is unchanged by the transfer.
    @Test
    void M_11_anApprovedTransferMovesTheEmployeeAndReissuesTheStaffNumber() throws Exception {
        // BR-EMPM-012: the staff-number change keeps the PAN-linked person record (tenure,
        // gratuity, seniority).
        Session hr = hr();
        String eis = member();
        JsonNode destinations =
                body(
                        mvc.perform(userGet(hr, "/v1/transfers/destinations"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(texts(destinations, "code")).contains("MR001", "DH001");
        String newNumber = "T" + (System.nanoTime() % 100000);
        String pan =
                jdbc.queryForObject(
                        "SELECT pan_number FROM employee WHERE emp_num = ?", String.class, eis);
        Map<String, Object> body = transfer("MR001", TODAY);
        body.put("newStaffNumber", newNumber);
        MvcResult recorded = move(hr, eis, body);
        assertThat(recorded.getResponse().getStatus()).isEqualTo(202);
        JsonNode view = body(recorded);
        // One pending transfer at a time.
        assertThat(code(move(hr, eis, transfer("KL001", TODAY)))).isEqualTo("WF-409-PENDING");
        assertThat(decide(checker(), view.get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);

        Map<String, Object> moved =
                jdbc.queryForMap(
                        "SELECT e.emp_num, l.location_code FROM employee e JOIN org_location l ON"
                                + " l.location_id = e.location_id WHERE e.emp_num = ?",
                        newNumber);
        assertThat(moved.get("LOCATION_CODE")).isEqualTo("MR001");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM employee_number_history WHERE old_emp_num = ?"
                                        + " AND new_emp_num = ?",
                                Long.class,
                                eis,
                                newNumber))
                .as("old number kept in history")
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pan_number FROM employee WHERE emp_num = ?",
                                String.class,
                                newNumber))
                .as("BR-EMPM-003: the PAN is the person's, unchanged by the transfer")
                .isEqualTo(pan);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM settlement_case c JOIN employee e ON"
                                        + " e.employee_id = c.employee_id WHERE e.emp_num = ?",
                                Long.class,
                                newNumber))
                .as("BR-FNF-007: a transfer is not a separation")
                .isZero();
        JsonNode recent =
                body(
                        mvc.perform(userGet(hr, "/v1/transfers"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(texts(recent, "toLocation").toString()).contains("MR001");
    }

    /**
     * NEW-G1-07 (US-01-26 AC2, SC-01-0050): SRS-G-53 "the previous unit keeps read-only access to
     * the period served there". Before the Checker approves, the member is still the origin's own
     * (no transferred-out mark) and the destination does not see it; after approval the origin
     * finds the member by its staff number (not in the list without text) and opens the record
     * read-only, marked with the transfer date, but records nothing on it; another office still
     * sees nothing.
     */
    @Test
    void SRS_G_53_thePreviousUnitKeepsReadOnlyAccessAfterTheTransfer() throws Exception {
        Session hr = hr();
        String eis = member();
        String newNumber = "P" + (System.nanoTime() % 100000);
        Map<String, Object> body = transfer("MR001", TODAY);
        body.put("newStaffNumber", newNumber);
        MvcResult recorded = move(hr, eis, body);
        assertThat(recorded.getResponse().getStatus()).isEqualTo(202);
        JsonNode pending = body(mvc.perform(userGet(hr, "/v1/employees/" + eis)).andReturn());
        assertThat(pending.path("transferredOut").isNull())
                .as("pending: still the origin's own member")
                .isTrue();
        assertThat(
                        decide(
                                checker(),
                                body(recorded).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);

        MvcResult read = mvc.perform(userGet(hr, "/v1/employees/" + newNumber)).andReturn();
        assertThat(read.getResponse().getStatus())
                .as(read.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(read).path("transferredOut").asString()).isEqualTo(TODAY.toString());
        JsonNode found =
                body(
                        mvc.perform(userGet(hr, "/v1/employees?q=" + newNumber))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(texts(found.get("items"), "eis")).containsExactly(newNumber);
        JsonNode list =
                body(
                        mvc.perform(userGet(hr, "/v1/employees?size=100&q="))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(texts(list.get("items"), "eis"))
                .as("without text the list is the present posting's")
                .doesNotContain(newNumber);
        // SRS-G-53: no write by the previous unit (an M-01 allowance-flag proposal stands for any).
        assertThat(
                        code(
                                mvc.perform(
                                                flagPost(
                                                        hr,
                                                        newNumber,
                                                        Map.of(
                                                                "flagCode",
                                                                "NPA",
                                                                "value",
                                                                "Y",
                                                                "effectiveFrom",
                                                                TODAY.toString(),
                                                                "reason",
                                                                "IT previous unit"),
                                                        true))
                                        .andReturn()))
                .as("read-only: no change by the previous unit")
                .isIn("AUTHZ-403-SCOPE", "COM-404-NOT-FOUND", "AUTHZ-403-PERMISSION");

        Session other = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        assertThat(
                        mvc.perform(userGet(other, "/v1/employees/" + newNumber))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("an office the member never served")
                .isIn(403, 404);
    }

    /**
     * UC M-11 alternate flow (SRS l.1328): "If the Payroll Checker rejects the task, the old values
     * stay" - office, staff number, no staff-number history row. M-11 field table (SRS l.1352): the
     * LPC Reference is System, read-only, so a request naming one is refused (R-SRC).
     */
    @Test
    void SRS_16_18_aRejectedTransferKeepsTheOldValuesAndTheLpcReferenceIsNeverKeyed()
            throws Exception {
        Session hr = hr();
        String eis = member();
        Map<String, Object> keyed = transfer("MR001", TODAY);
        keyed.put("lpcReference", "LPC/IT/1");
        assertThat(move(hr, eis, keyed).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> body = transfer("MR001", TODAY);
        body.put("newStaffNumber", String.format("R%05d", System.nanoTime() % 100000));
        MvcResult recorded = move(hr, eis, body);
        assertThat(recorded.getResponse().getStatus())
                .as(recorded.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(body(recorded).get("lpcReference").isNull()).isTrue();
        assertThat(
                        decide(
                                checker(),
                                body(recorded).get("approvalId").asLong(),
                                "REJECT",
                                "IT: order not issued",
                                null))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT l.location_code FROM employee e JOIN org_location l ON"
                                        + " l.location_id = e.location_id WHERE e.emp_num = ?",
                                String.class,
                                eis))
                .isEqualTo("DH001");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM employee_number_history WHERE old_emp_num ="
                                        + " ?",
                                Long.class,
                                eis))
                .isZero();
        // Another transfer may be recorded once the rejected one is decided.
        MvcResult again = move(hr, eis, transfer("MR001", TODAY));
        assertThat(again.getResponse().getStatus())
                .as(again.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(
                        decide(
                                checker(),
                                body(again).get("approvalId").asLong(),
                                "REJECT",
                                "IT: cleanup",
                                null))
                .isEqualTo(200);
    }

    /** A result of the member at its Delhi unit for {@code yymm} (a cycle instance of its own). */
    private void result(String eis, String yymm, String runType) {
        Long unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DH001'",
                        Long.class);
        Long employee =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) SELECT ?, ?, 'DO', 'IT',"
                        + " 'INITIATED', 'it.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM"
                        + " payroll_cycle_instance WHERE payroll_yymm = ? AND org_unit_id = ?)",
                yymm,
                unit,
                yymm,
                unit);
        Long cycle =
                jdbc.queryForObject(
                        "SELECT MIN(cycle_instance_id) FROM payroll_cycle_instance WHERE"
                                + " payroll_yymm = ? AND org_unit_id = ?",
                        Long.class,
                        yymm,
                        unit);
        LocalDate period =
                LocalDate.of(
                        Integer.parseInt(yymm.substring(0, 4)),
                        Integer.parseInt(yymm.substring(4)),
                        1);
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, payable_days, result_status, computed_at,"
                        + " is_locked) VALUES (?, ?, ?, 'STAFF', ?, ?, ?, 100, 0, 100, 30,"
                        + " 'CURRENT', ?, 'N')",
                cycle,
                unit,
                employee,
                yymm,
                java.sql.Date.valueOf(period),
                runType,
                java.sql.Timestamp.from(java.time.Instant.now()));
    }

    /**
     * UC M-11 main flow (SRS l.1321, l.1323): no computation exists at the origin after the
     * transfer date - checked when the transfer is recorded and again when it is approved; a
     * simulation is never a computation (BR-CYC-007).
     */
    @Test
    void SRS_16_18_aComputationAtTheOriginAfterTheTransferDateBlocksTheTransfer() throws Exception {
        Session hr = hr();
        String eis = member();
        // A simulation of a later month does not count.
        result(eis, "209806", "SIMULATION");
        MvcResult recorded = move(hr, eis, transfer("MR001", TODAY));
        assertThat(recorded.getResponse().getStatus())
                .as(recorded.getResponse().getContentAsString())
                .isEqualTo(202);
        // A computation of a later month appears before the decision: the approval is refused.
        result(eis, "209806", "ACTUAL");
        long approval = body(recorded).get("approvalId").asLong();
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(409);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT l.location_code FROM employee e JOIN org_location l ON"
                                        + " l.location_id = e.location_id WHERE e.emp_num = ?",
                                String.class,
                                eis))
                .isEqualTo("DH001");
        assertThat(decide(checker(), approval, "REJECT", "IT: origin not clear", null))
                .isEqualTo(200);
        // A new transfer is refused at once, naming the computation's period.
        MvcResult refused = move(hr, eis, transfer("MR001", TODAY));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("EMP-409-TRANSFER-ORIGIN");
        assertThat(body(refused).get("args").get(0).asString()).isEqualTo("2098-06-01");
    }

    @Test
    void M_11_transfersThatBreakARuleAreRefused() throws Exception {
        Session hr = hr();
        String eis = member();
        assertThat(code(move(hr, eis, transfer("DH001", TODAY))))
                .as("to the same office")
                .isEqualTo("COM-400-VALIDATION");
        assertThat(code(move(hr, eis, transfer("NOWHERE", TODAY)))).isEqualTo("COM-400-VALIDATION");
        assertThat(code(move(hr, eis, transfer("MR001", TODAY.plusDays(3)))))
                .isEqualTo("EMP-422-FUTURE");
        Map<String, Object> joining = transfer("MR001", TODAY);
        joining.put("joiningDate", TODAY.minusDays(1).toString());
        assertThat(code(move(hr, eis, joining))).isEqualTo("COM-400-VALIDATION");
        Map<String, Object> split = transfer("MR001", TODAY);
        split.put("splitWage", true);
        split.put("fromUnitDays", 20);
        split.put("toUnitDays", 20);
        assertThat(code(move(hr, eis, split))).isEqualTo("COM-400-VALIDATION");
        Map<String, Object> taken = transfer("MR001", TODAY);
        taken.put("newStaffNumber", "105220");
        assertThat(code(move(hr, eis, taken))).isEqualTo("EMP-409-NUMBER");
        // A record with a change awaiting approval is not transferred meanwhile (seed: 105461).
        assertThat(code(move(hr, "105461", transfer("MR001", TODAY)))).isEqualTo("WF-409-PENDING");
        // A separated member is never transferred (the ITs share one database: restore it).
        jdbc.update("UPDATE employee SET employment_status = 'SEPARATED' WHERE emp_num = ?", eis);
        try {
            assertThat(code(move(hr, eis, transfer("MR001", TODAY)))).isEqualTo("EMP-409-EVENT");
        } finally {
            jdbc.update(
                    "UPDATE employee SET employment_status = 'IN_SERVICE' WHERE emp_num = ?", eis);
        }
        Map<String, Object> badTime = transfer("MR001", TODAY);
        badTime.put("joiningTime", "25:00");
        assertThat(code(move(hr, eis, badTime))).isEqualTo("COM-400-VALIDATION");
        Map<String, Object> noReason = transfer("MR001", TODAY);
        noReason.remove("reason");
        assertThat(move(hr, eis, noReason).getResponse().getStatus()).isEqualTo(400);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(move(maker, eis, transfer("MR001", TODAY)).getResponse().getStatus())
                .isEqualTo(403);
        mvc.perform(userGet(maker, "/v1/employees/" + eis + "/transfers"))
                .andExpect(status().isOk());
        assertThat(
                        mvc.perform(userGet(maker, "/v1/transfers/destinations"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }
}
