package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * M-01 Control flags (SRS-1-7, SRS-1-11; FRS §7.8.1 Scenario 4; US-GAP-4): Establishment/HR sets or
 * clears Hold and Stop-Payment from a date with a reason and the order; the Payroll Checker
 * approves and the flag is written and audited; a second change of the flag waits; a later date
 * (CLR-17), a date before joining, a change without the order (audited, SRS-20-27), the Payroll
 * Maker, the proposer as approver and an employee of another office are refused. The member is a
 * Delhi HQ (DH001) employee near the end of the list; the flag is cleared again at the end.
 */
class ControlFlagIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
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

    private MvcResult propose(
            Session s, String eis, String flag, String value, LocalDate from, boolean document)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("flagCode", flag);
        body.put("value", value);
        body.put("effectiveFrom", from.toString());
        body.put("reason", "Salary release order");
        MockMultipartHttpServletRequestBuilder r =
                MockMvcRequestBuilders.multipart("/v1/employees/" + eis + "/control-flags");
        r.file(
                new MockMultipartFile(
                        "flag",
                        "",
                        MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(body)));
        if (document) {
            r.file(
                    new MockMultipartFile(
                            "file",
                            "order.pdf",
                            "application/pdf",
                            "%PDF-1.7 hold order"
                                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        }
        r.param("documentType", "OTHER")
                .param("orderDate", LocalDate.now(IST).toString())
                .param("issuingAuthority", "Establishment, Delhi");
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private String column(String eis, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM employee WHERE emp_num = ?", String.class, eis);
    }

    @Test
    void SRS_1_7_establishmentSetsAndClearsAHoldThatThePayrollCheckerApproves() throws Exception {
        String eis = eis("DH001", 5);
        LocalDate today = LocalDate.now(IST);
        Session hr = hr();
        MvcResult sent = propose(hr, eis, "HOLD", "Y", today, true);
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(sent).get("approvalId").asLong();
        JsonNode flags =
                body(
                        mvc.perform(userGet(hr, "/v1/employees/" + eis + "/control-flags"))
                                .andReturn());
        assertThat(flags.toString()).contains("\"code\":\"HOLD\",\"value\":\"N\",\"pending\":true");
        assertThat(code(propose(hr, eis, "HOLD", "Y", today, true)))
                .isEqualTo("EMP-409-CONTROL-PENDING");
        assertThat(decide(hr, approval, "APPROVE", null, null))
                .as("the proposer cannot approve")
                .isEqualTo(403);
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(column(eis, "hold_flag")).isEqualTo("Y");
        Map<String, Object> audit =
                jdbc.queryForMap(
                        "SELECT old_value, new_value, approval_id, changed_by FROM audit_event"
                            + " WHERE entity_name = 'employee' AND entity_key = ? AND field_name ="
                            + " 'hold_flag' ORDER BY audit_id DESC FETCH FIRST 1 ROWS ONLY",
                        eis);
        assertThat(audit)
                .containsEntry("OLD_VALUE", "N")
                .containsEntry("NEW_VALUE", "Y")
                .containsEntry("CHANGED_BY", "ddo.delhi@fci-demo.test");
        assertThat(((Number) audit.get("APPROVAL_ID")).longValue()).isEqualTo(approval);
        // Setting it again is no change; clearing it is.
        assertThat(code(propose(hr, eis, "HOLD", "Y", today, true))).isEqualTo("EMP-422-CONTROL");
        long clear = body(propose(hr, eis, "HOLD", "N", today, true)).get("approvalId").asLong();
        assertThat(decide(checker(), clear, "APPROVE", null, null)).isEqualTo(200);
        assertThat(column(eis, "hold_flag")).isEqualTo("N");
    }

    @Test
    void SRS_1_77_aSeparatedRecordTakesNoHoldOrStopPayment() throws Exception {
        // EIS 109990 (demo): separated, Delhi HQ.
        MvcResult hold = propose(hr(), "109990", "HOLD", "Y", LocalDate.now(IST), true);
        assertThat(code(hold)).isEqualTo("EMP-422-CONTROL");
        assertThat(hold.getResponse().getContentAsString()).contains("SEPARATED");
        assertThat(column("109990", "hold_flag")).isNotEqualTo("Y");
    }

    @Test
    void SRS_1_7_invalidChangesAndTheWrongRoleOrOfficeAreRefused() throws Exception {
        String eis = eis("DH001", 6);
        LocalDate today = LocalDate.now(IST);
        Session hr = hr();
        // CLR-17: the flag has no date of its own; a later date is not taken.
        assertThat(code(propose(hr, eis, "STOP_PAYMENT", "Y", today.plusDays(1), true)))
                .isEqualTo("EMP-422-CONTROL");
        LocalDate joined =
                jdbc.queryForObject(
                        "SELECT comp_joining_date FROM employee WHERE emp_num = ?",
                        LocalDate.class,
                        eis);
        if (joined != null) {
            assertThat(code(propose(hr, eis, "STOP_PAYMENT", "Y", joined.minusDays(1), true)))
                    .isEqualTo("EMP-422-CONTROL");
        }
        // SRS-G-32: the order is required; the blocked submission is audited (SRS-20-27).
        assertThat(code(propose(hr, eis, "STOP_PAYMENT", "Y", today, false)))
                .isEqualTo("EMP-422-CONTROL");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'employee_control_flag' AND entity_key = ? AND"
                                        + " field_name = 'SUBMISSION_BLOCKED'",
                                Integer.class,
                                eis + "|STOP_PAYMENT"))
                .isPositive();
        // Absconding is HRMS-owned: not a control flag here.
        assertThat(propose(hr, eis, "ABSCONDING", "Y", today, true).getResponse().getStatus())
                .isEqualTo(400);
        // The Payroll Maker has no FLAG right; another office's employee is outside the scope.
        assertThat(
                        propose(
                                        login(
                                                "maker.delhi@fci-demo.test",
                                                "PAYROLL_MAKER",
                                                "DH001"),
                                        eis,
                                        "STOP_PAYMENT",
                                        "Y",
                                        today,
                                        true)
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        String elsewhere = eis("KL001", 1);
        assertThat(
                        propose(hr, elsewhere, "STOP_PAYMENT", "Y", today, true)
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(column(eis, "stop_payment_flag")).isEqualTo("N");
    }
}
