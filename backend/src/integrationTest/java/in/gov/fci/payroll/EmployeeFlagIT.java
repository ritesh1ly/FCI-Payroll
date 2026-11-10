package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * M-01 Payroll allowance flags (plan 16 phase 16d-2; SRS-1-4, SRS-1-11; BR-EMPM-004 / 005 / 006):
 * Establishment/HR proposes a flag from a date with a reason; the Payroll Checker approves on W-01
 * and the version is written with the Maker and the approval; a second proposal of the flag waits;
 * the Payroll Maker, the proposer as approver, an employee of another office and invalid proposals
 * are refused. The member is a Delhi HQ (DH001) employee near the end of the list.
 */
class EmployeeFlagIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named;

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private String eis(String office, int fromEnd) {
        return jdbc.queryForObject(
                "SELECT emp_num FROM employee WHERE location_id = ? AND record_status = 'ACTIVE'"
                        + " ORDER BY emp_num DESC OFFSET "
                        + fromEnd
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                String.class,
                locationId(office));
    }

    private static Map<String, Object> proposal(
            String flag, String value, LocalDate from, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("flagCode", flag);
        body.put("value", value);
        body.put("effectiveFrom", from.toString());
        body.put("reason", reason);
        return body;
    }

    private MvcResult propose(Session s, String eis, Map<String, Object> body) throws Exception {
        return mvc.perform(flagPost(s, eis, body, true)).andReturn();
    }

    private JsonNode flag(Session s, String eis, String code) throws Exception {
        JsonNode flags =
                body(
                        mvc.perform(userGet(s, "/v1/employees/" + eis + "/allowance-flags"))
                                .andReturn());
        for (JsonNode f : flags.get("flags")) {
            if (code.equals(f.get("code").asString())) {
                return f;
            }
        }
        throw new AssertionError(code + " not in " + flags);
    }

    /**
     * SRS-1-11: proposed, waiting (a second proposal refused), approved by the Payroll Checker: the
     * version is written with the Maker, the reason and the approval, and shows from its date.
     */
    @Test
    void SRS_1_11_establishmentProposesAFlagAndThePayrollCheckerApprovesIt() throws Exception {
        String eis = eis("DH001", 2);
        LocalDate from = YearMonth.now(IST).atDay(1);
        Session hr = hr();
        MvcResult sent = propose(hr, eis, proposal("NPA", "Y", from, "Medical officer posted"));
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(sent).get("approvalId").asLong();
        assertThat(flag(hr, eis, "NPA").get("pending").asBoolean()).isTrue();
        assertThat(flag(hr, eis, "NPA").get("value").asString()).isEqualTo("N");
        assertThat(
                        body(propose(hr, eis, proposal("NPA", "Y", from.plusDays(1), "again")))
                                .path("errorCode")
                                .asString())
                .isEqualTo("EMP-409-FLAG-PENDING");

        assertThat(decide(hr, approval, "APPROVE", null, null))
                .as("the proposer cannot approve")
                .isEqualTo(403);
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT f.flag_value, f.changed_by, f.approval_id, f.reason FROM"
                                + " employee_eligibility_flag f JOIN employee e ON e.employee_id ="
                                + " f.employee_id WHERE e.emp_num = ? AND f.flag_code = 'NPA'",
                        eis);
        assertThat(row.get("FLAG_VALUE")).isEqualTo("Y");
        assertThat(row.get("CHANGED_BY")).isEqualTo("hr.delhi@fci-demo.test");
        assertThat(((Number) row.get("APPROVAL_ID")).longValue()).isEqualTo(approval);
        JsonNode npa = flag(hr, eis, "NPA");
        assertThat(npa.get("value").asString()).isEqualTo("Y");
        assertThat(npa.get("since").asString()).isEqualTo(from.toString());
        assertThat(npa.get("pending").asBoolean()).isFalse();
        JsonNode history =
                body(
                        mvc.perform(
                                        userGet(
                                                hr,
                                                "/v1/employees/"
                                                        + eis
                                                        + "/allowance-flags/history"))
                                .andReturn());
        assertThat(history.toString()).contains("Medical officer posted");
        assertThat(
                        body(propose(hr, eis, proposal("NPA", "Y", from.plusMonths(1), "same")))
                                .path("errorCode")
                                .asString())
                .as("no change")
                .isEqualTo("EMP-422-FLAG");
    }

    /**
     * UC allowance flags verify (SC-16-0099; SRS-1-11, SRS-1-4): the approved change is used from
     * its effective date in the next computation - the staff engine's reader of the month before
     * does not carry the flag, the month it starts from does - and nothing is used before the
     * approval.
     */
    @Test
    void SC_16_0099_anApprovedFlagChangeIsUsedFromItsEffectiveDateInTheNextRun() throws Exception {
        String eis = eis("DH001", 7);
        long member =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
        LocalDate thisMonth = YearMonth.now(IST).atDay(1);
        LocalDate next = thisMonth.plusMonths(1);
        MvcResult sent =
                propose(hr(), eis, proposal("NPA", "Y", next, "Medical officer from next month"));
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(flagsRead(member, next)).as("not before the approval").doesNotContain("NPA");
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                body(sent).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(flagsRead(member, thisMonth)).as("the month before").doesNotContain("NPA");
        assertThat(flagsRead(member, next)).as("from its effective date").contains("NPA");
        assertThat(flagsRead(member, next.plusMonths(1))).contains("NPA");
    }

    /** The eligibility flags the staff engine's reader gives the member for the month. */
    private String flagsRead(long member, LocalDate monthStart) {
        return in.gov.fci.payroll.payroll.batch.computation.MemberReader.member(
                                named, member, monthStart)
                        .orElseThrow()
                        .allowanceFlags()
                + "";
    }

    /**
     * BR-EMPM-005 / 006, SRS-1-11: an unknown flag, a date before joining, the same date twice, a
     * blank reason (400) are refused; the Payroll Maker has no FLAG right; another office's
     * employee is outside the scope.
     */
    @Test
    void BR_EMPM_006_invalidProposalsAndTheWrongRoleOrOfficeAreRefused() throws Exception {
        String eis = eis("DH001", 3);
        Session hr = hr();
        LocalDate joined =
                jdbc.queryForObject(
                                "SELECT comp_joining_date FROM employee WHERE emp_num = ?",
                                java.sql.Date.class,
                                eis)
                        .toLocalDate();
        assertThat(
                        body(propose(hr, eis, proposal("BONUS_X", "Y", joined, "x")))
                                .path("args")
                                .toString())
                .contains("UNKNOWN_FLAG");
        assertThat(
                        body(propose(hr, eis, proposal("NPA", "Y", joined.minusDays(1), "x")))
                                .path("args")
                                .toString())
                .contains("BEFORE_JOINING");
        assertThat(propose(hr, eis, proposal("NPA", "Y", joined, " ")).getResponse().getStatus())
                .isEqualTo(422);
        Map<String, Object> blank = proposal("NPA", "Y", joined, "x");
        blank.remove("reason");
        assertThat(propose(hr, eis, blank).getResponse().getStatus()).isEqualTo(400);
        assertThat(
                        propose(
                                        login(
                                                "maker.delhi@fci-demo.test",
                                                "PAYROLL_MAKER",
                                                "DH001"),
                                        eis,
                                        proposal("NPA", "Y", joined, "x"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        assertThat(
                        propose(hr, eis("KL001", 0), proposal("NPA", "Y", LocalDate.now(IST), "x"))
                                .getResponse()
                                .getStatus())
                .as("outside the region: the record is not found for this user (audited)")
                .isEqualTo(404);
    }

    /**
     * SRS-1-79 (US-16-04 AC3): Basic Pay and Personal Pay are read-only on M-01, set only through
     * I-05 / I-21: no M-01 write takes them - neither as an allowance or control flag nor as a
     * write of the employee record (no such endpoint) - and the record is unchanged.
     */
    @Test
    void SRS_1_79_basicAndPersonalPayAreNeverSetOnM01() throws Exception {
        String eis = eis("DH001", 4);
        Session hr = hr();
        LocalDate today = LocalDate.now(IST);
        java.math.BigDecimal basic =
                jdbc.queryForObject(
                        "SELECT current_basic FROM employee WHERE emp_num = ?",
                        java.math.BigDecimal.class,
                        eis);
        for (String field : new String[] {"BASIC_PAY", "PERSONAL_PAY", "CURRENT_BASIC"}) {
            assertThat(
                            body(propose(hr, eis, proposal(field, "Y", today, "x")))
                                    .path("args")
                                    .toString())
                    .as(field)
                    .contains("UNKNOWN_FLAG");
        }
        Map<String, Object> write = Map.of("currentBasic", 99999, "personalPay", 500);
        assertThat(
                        mvc.perform(userPut(hr, "/v1/employees/" + eis, write))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(405);
        assertThat(
                        mvc.perform(
                                        userRequest(
                                                hr,
                                                org.springframework.test.web.servlet.request
                                                        .MockMvcRequestBuilders.patch(
                                                                "/v1/employees/" + eis)
                                                        .contentType(
                                                                org.springframework.http.MediaType
                                                                        .APPLICATION_JSON)
                                                        .content(json.writeValueAsString(write))))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(405);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_basic FROM employee WHERE emp_num = ?",
                                java.math.BigDecimal.class,
                                eis))
                .isEqualTo(basic);
    }

    /** SRS-1-26: a proposal without its supporting document is refused and creates nothing. */
    @Test
    void SRS_1_26_aProposalNeedsItsSupportingDocument() throws Exception {
        String eis = eis("DH001", 4);
        Session hr = hr();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM approval_queue", Integer.class);
        MvcResult sent =
                mvc.perform(
                                flagPost(
                                        hr,
                                        eis,
                                        proposal(
                                                "NPA", "Y", YearMonth.now(IST).atDay(1), "No file"),
                                        false))
                        .andReturn();
        assertThat(sent.getResponse().getStatus()).as("the file part is required").isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_queue", Integer.class))
                .isEqualTo(before);
    }

    /**
     * SRS-1-26 (FRS §7.5): the Checker's item carries the current and the requested value, and the
     * supporting document is stored with the proposal's record.
     */
    @Test
    void SRS_1_26_theCheckerSeesCurrentAndRequestedValueAndTheDocument() throws Exception {
        String eis = eis("DH001", 5);
        LocalDate from = YearMonth.now(IST).atDay(1);
        Session hr = hr();
        MvcResult sent = propose(hr, eis, proposal("WASHING", "Y", from, "Order 7/2026"));
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(sent).get("approvalId").asLong();
        Map<String, Object> item =
                jdbc.queryForMap(
                        "SELECT entity_key, payload, decision_reason FROM approval_queue WHERE"
                                + " approval_id = ?",
                        approval);
        assertThat((String) item.get("DECISION_REASON"))
                .contains("Allowance flag WASHING N -> Y from " + from);
        JsonNode payload = json.readTree((String) item.get("PAYLOAD"));
        assertThat(payload.get("currentValue").asString()).isEqualTo("N");
        assertThat(payload.get("value").asString()).isEqualTo("Y");
        long attachment = payload.get("attachmentId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM document_attachment WHERE attachment_id = ?"
                                        + " AND entity_key = ?",
                                Integer.class,
                                attachment,
                                item.get("ENTITY_KEY")))
                .isEqualTo(1);
    }

    /**
     * SRS-1-26 / SRS-21-11: an approved flag effective before the current month raises one arrear
     * event for the locked months; the version itself is written as usual.
     */
    @Test
    void SRS_1_26_aBackDatedFlagIsSettledForTheLockedMonthsByAnArrearEvent() throws Exception {
        String eis = eis("DH001", 6);
        LocalDate from = YearMonth.now(IST).minusMonths(2).atDay(1);
        Session hr = hr();
        MvcResult sent = propose(hr, eis, proposal("CONVEYANCE", "Y", from, "Back-dated order"));
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(sent).get("approvalId").asLong();
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        Map<String, Object> event =
                jdbc.queryForMap(
                        "SELECT a.source_module, a.effective_from, a.source_key FROM arrear_event a"
                                + " JOIN arrear_event_member m ON m.arrear_event_id ="
                                + " a.arrear_event_id JOIN employee e ON e.employee_id ="
                                + " m.employee_id WHERE e.emp_num = ? AND a.source_module ="
                                + " 'EMPLOYEE_FLAG'",
                        eis);
        assertThat(event.get("SOURCE_KEY")).isEqualTo(eis + "|CONVEYANCE|" + from);
        assertThat(String.valueOf(event.get("EFFECTIVE_FROM"))).startsWith(from.toString());
    }

    /**
     * BR-EMPM-001: no Employee Master record is created through M-01 - only the HRMS interface
     * creates one. No role finds a create endpoint (POST /v1/employees answers 405 for the
     * Establishment / HR maker, the Payroll Maker and the System Administrator) and no row appears.
     */
    @Test
    void BR_EMPM_001_noEmployeeRecordIsCreatedOnM01ByAnyRole() throws Exception {
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM employee", Long.class);
        Map<String, Object> record =
                Map.of(
                        "empNum", "799999",
                        "firstName", "Manual",
                        "lastName", "Entry",
                        "panNumber", "AAAPM9999M");
        for (Session s :
                java.util.List.of(
                        hr(),
                        login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001"),
                        login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001"))) {
            assertThat(
                            mvc.perform(userPost(s, "/v1/employees", record))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(405);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee", Long.class))
                .isEqualTo(before);
    }
}
