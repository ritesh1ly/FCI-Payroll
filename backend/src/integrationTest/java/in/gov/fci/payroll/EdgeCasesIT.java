package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.leave.api.LeaveReceipts;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Edge cases of modules 19, 11 and 13 (plans 19, 11, 13): every refusal is a named error, never a
 * partial change - leave data from HRMS with a defect is held (SRS-LV-13, SRS-LV-24), an M-20
 * version for an unknown type, an inactive pay code or a date already held is refused (SRS-LV-14),
 * a settlement case out of stage or unknown is refused (SRS-10-8), and an arrear event rejected by
 * the Checker is not merged, its details reversed (SRS-8-14).
 */
class EdgeCasesIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private LeaveReceipts receipts;

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    @Test
    void SRS_LV_24_leaveDataWithADefectIsRefusedOrHeldNeverApplied() {
        receipts.receiveType(
                "hrms.it", new LeaveReceipts.LeaveType("EL", "Earned Leave", true, false));
        assertThatThrownBy(
                        () ->
                                receipts.receiveBalance(
                                        "hrms.it",
                                        new LeaveReceipts.Balance(
                                                "999999",
                                                "EL",
                                                null,
                                                BigDecimal.TEN,
                                                LocalDate.now(IST))))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(
                        () ->
                                receipts.receiveBalance(
                                        "hrms.it",
                                        new LeaveReceipts.Balance(
                                                "106118",
                                                "NOPE",
                                                null,
                                                BigDecimal.TEN,
                                                LocalDate.now(IST))))
                .isInstanceOf(FciException.class);
        // The same balance twice records it once.
        LeaveReceipts.Balance balance =
                new LeaveReceipts.Balance(
                        "106118", "EL", null, new BigDecimal("41"), LocalDate.now(IST));
        receipts.receiveBalance("hrms.it", balance);
        receipts.receiveBalance("hrms.it", balance);

        LocalDate day = LocalDate.now(IST).minusDays(2);
        assertThat(
                        receipts.receiveEvent(
                                        "hrms.it",
                                        new LeaveReceipts.Event(
                                                "106118",
                                                "EL",
                                                day,
                                                day,
                                                BigDecimal.ONE,
                                                false,
                                                "BADéREF",
                                                "IT-" + System.nanoTime(),
                                                "DGM",
                                                LeaveReceipts.EventType.ADDITION,
                                                null))
                                .defect())
                .isEqualTo("SANCTION_REF");
        assertThat(
                        receipts.receiveEvent(
                                        "hrms.it",
                                        new LeaveReceipts.Event(
                                                "106118",
                                                " ",
                                                day,
                                                day,
                                                BigDecimal.ONE,
                                                false,
                                                "SAN/IT/" + System.nanoTime(),
                                                "IT-" + System.nanoTime(),
                                                "DGM",
                                                LeaveReceipts.EventType.CANCELLATION,
                                                "SAN/OLD/1"))
                                .outcome())
                .isEqualTo(LeaveReceipts.Outcome.HELD);
        assertThat(
                        receipts.receiveEncashment(
                                        "hrms.it",
                                        new LeaveReceipts.EncashmentSanction(
                                                "999999",
                                                "EL",
                                                BigDecimal.TEN,
                                                day,
                                                "SAN/IT/1",
                                                "IT-" + System.nanoTime(),
                                                false))
                                .defect())
                .isEqualTo("MEMBER");
        assertThat(
                        receipts.receiveEncashment(
                                        "hrms.it",
                                        new LeaveReceipts.EncashmentSanction(
                                                "106118",
                                                "EL",
                                                BigDecimal.TEN,
                                                day,
                                                " ",
                                                "IT-" + System.nanoTime(),
                                                false))
                                .defect())
                .isEqualTo("NO_SANCTION_REF");
        assertThat(
                        receipts.receiveEncashment(
                                        "hrms.it",
                                        new LeaveReceipts.EncashmentSanction(
                                                "106118",
                                                "EL",
                                                BigDecimal.TEN,
                                                null,
                                                "SAN/IT/2",
                                                "IT-" + System.nanoTime(),
                                                false))
                                .defect())
                .isEqualTo("DATE");
    }

    /**
     * SRS-LV-13 / LV-34: a leave type with a defect is refused, a renamed type is audited; an event
     * without a leave type is held; a cancellation carries its earlier sanction to the I-21 task;
     * an encashment sanction reference is printable and at most 50 characters, its leave type
     * known.
     */
    @Test
    void SRS_LV_34_typesEventsAndEncashmentSanctionsAreCheckedBeforeAnyTask() {
        assertThatThrownBy(
                        () ->
                                receipts.receiveType(
                                        "hrms.it",
                                        new LeaveReceipts.LeaveType(" ", "x", true, false)))
                .isInstanceOf(FciException.class);
        receipts.receiveType(
                "hrms.it", new LeaveReceipts.LeaveType("EL", "Earned Leave (IT)", true, true));
        receipts.receiveType(
                "hrms.it", new LeaveReceipts.LeaveType("EL", "Earned Leave", true, false));
        LocalDate day = LocalDate.now(IST).minusDays(3);
        assertThat(
                        receipts.receiveEvent(
                                        "hrms.it",
                                        new LeaveReceipts.Event(
                                                "106118",
                                                null,
                                                day,
                                                day,
                                                BigDecimal.ONE,
                                                false,
                                                "SAN/IT/" + System.nanoTime(),
                                                null,
                                                "DGM",
                                                LeaveReceipts.EventType.ADDITION,
                                                null))
                                .defect())
                .isEqualTo("LEAVE_TYPE");
        String sanction = "SAN/IT/C" + System.nanoTime();
        LeaveReceipts.Received cancelled =
                receipts.receiveEvent(
                        "hrms.it",
                        new LeaveReceipts.Event(
                                "106118",
                                "EL",
                                day,
                                day,
                                BigDecimal.ONE,
                                false,
                                sanction,
                                null,
                                "DGM",
                                LeaveReceipts.EventType.CANCELLATION,
                                " SAN/OLD/1 "));
        assertThat(cancelled.outcome()).isEqualTo(LeaveReceipts.Outcome.TASK_OPENED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT new_value FROM hrms_change_task WHERE hrms_change_task_id ="
                                        + " ?",
                                String.class,
                                cancelled.taskId()))
                .contains("\"earlierSanctionRef\":\"SAN/OLD/1\"");
        for (String bad : new String[] {"S".repeat(51), "SAN/Ω/1"}) {
            assertThat(
                            receipts.receiveEncashment(
                                            "hrms.it",
                                            new LeaveReceipts.EncashmentSanction(
                                                    "106118",
                                                    "EL",
                                                    BigDecimal.ONE,
                                                    day,
                                                    bad,
                                                    null,
                                                    false))
                                    .defect())
                    .isEqualTo("SANCTION_REF");
        }
        assertThat(
                        receipts.receiveEncashment(
                                        "hrms.it",
                                        new LeaveReceipts.EncashmentSanction(
                                                "106118",
                                                null,
                                                BigDecimal.ONE,
                                                day,
                                                "SAN/IT/E1",
                                                null,
                                                false))
                                .defect())
                .isEqualTo("LEAVE_TYPE");
    }

    @Test
    void SRS_LV_14_anM20VersionForAnUnknownTypeAnInactivePayCodeOrAHeldDateIsRefused()
            throws Exception {
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        JsonNode all = all(admin, "/v1/leave-pay-parameters");
        assertThat(all).isNotEmpty();
        assertThat(
                        all(admin, "/v1/leave-pay-parameters?code=PAY_EFFECT")
                                .findValuesAsString("code"))
                .containsOnly("PAY_EFFECT");
        Map<String, Object> body = new HashMap<>();
        body.put("code", "PAY_EFFECT");
        body.put("leaveType", "ZZ");
        body.put("text", "NO_EFFECT");
        body.put("effectiveFrom", LocalDate.now(IST).plusYears(3).toString());
        body.put("reason", "Edge (IT)");
        assertThat(code(post(admin, "/v1/leave-pay-parameters", body)))
                .isEqualTo("LV-422-PARAMETER");
        Map<String, Object> codes = new HashMap<>(body);
        codes.put("code", "ENCASH_PAY_CODES");
        codes.remove("leaveType");
        codes.put("text", "184+99991");
        assertThat(code(post(admin, "/v1/leave-pay-parameters", codes)))
                .isIn("LV-422-PARAMETER", "LV-409-PARAMETER-PENDING");
        Map<String, Object> held = new HashMap<>(body);
        held.put("leaveType", "EL");
        held.put("effectiveFrom", "2026-04-01");
        assertThat(code(post(admin, "/v1/leave-pay-parameters", held)))
                .isIn("LV-409-PARAMETER-DATE", "LV-409-PARAMETER-PENDING");
        Map<String, Object> noDate = new HashMap<>(held);
        noDate.remove("effectiveFrom");
        assertThat(post(admin, "/v1/leave-pay-parameters", noDate).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void SRS_10_8_aCaseOutOfStageOrUnknownIsRefused() throws Exception {
        long id =
                jdbc.queryForObject(
                        "SELECT settlement_case_id FROM settlement_case WHERE case_reference ="
                                + " 'FNF-DEMO-00001'",
                        Long.class);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        String base = "/v1/settlement-cases/";
        assertThat(code(post(maker, base + id + "/submissions", Map.of())))
                .as("not computed")
                .isIn("FNF-409-STAGE", "FNF-409-CLEARANCE");
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        assertThat(code(post(finance, base + id + "/releases", Map.of())))
                .isEqualTo("FNF-409-STAGE");
        assertThat(
                        code(
                                post(
                                        maker,
                                        base + id + "/exclusions",
                                        Map.of("clearanceType", "NO_SUCH", "reason", "x"))))
                .isNotBlank();
        assertThat(
                        mvc.perform(userGet(maker, base + "999999999"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(post(maker, base + "999999999/computations", Map.of()).getResponse().getStatus())
                .isEqualTo(404);
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        Map<String, Object> negative = new HashMap<>();
        negative.put("dues", -1);
        negative.put("remark", "x");
        assertThat(post(hr, base + id + "/no-dues", negative).getResponse().getStatus())
                .isBetween(400, 499);
        assertThat(
                        all(maker, "/v1/settlement-cases?eis=109990")
                                .findValuesAsString("caseReference"))
                .contains("FNF-DEMO-00001");
        assertThat(body(mvc.perform(userGet(maker, base + "awaiting")).andReturn()).isArray())
                .isTrue();
    }

    @Test
    void SRS_8_14_aRejectedEventIsNotMergedAndAFilteredRegisterReadsIt() throws Exception {
        YearMonth month = YearMonth.now(IST).minusMonths(4);
        String yymm = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        String reference = "IT HRA reject " + System.nanoTime();
        jdbc.update(
                "INSERT INTO arrear_event (event_type_id, event_reference, effective_from,"
                        + " scope_payroll_type, scope_location_id, scope_from_yymm, scope_to_yymm,"
                        + " source_module, event_status, raised_by) SELECT event_type_id, ?, ?,"
                        + " 'STAFF', ?, ?, ?, 'RATE_CHANGE', 'COMPUTED', 'it.arrear' FROM"
                        + " arrear_event_type WHERE event_code = 'HRA_REVISION'",
                reference,
                java.sql.Date.valueOf(month.atDay(1)),
                locationId("DH001"),
                yymm,
                yymm);
        long event =
                jdbc.queryForObject(
                        "SELECT arrear_event_id FROM arrear_event WHERE event_reference = ?",
                        Long.class,
                        reference);
        jdbc.update(
                "INSERT INTO arrear_detail (arrear_event_id, employee_id, component_id,"
                    + " differential_yymm, arrear_amount, period_date) SELECT ?,"
                    + " MIN(e.employee_id), (SELECT component_id FROM pay_component WHERE"
                    + " legacy_pay_code = 121), ?, -250, ? FROM employee e WHERE e.location_id = ?",
                event,
                yymm,
                java.sql.Date.valueOf(month.atDay(1)),
                locationId("DH001"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        all(maker, "/v1/arrear-events?eventCode=HRA_REVISION&status=COMPUTED")
                                .findValuesAsString("reference"))
                .contains(reference);
        assertThat(
                        all(maker, "/v1/arrear-events/" + event + "/details")
                                .get(0)
                                .path("arrear")
                                .decimalValue())
                .isEqualByComparingTo("-250");
        long approval =
                body(post(
                                maker,
                                "/v1/arrear-events/" + event + "/submissions",
                                Map.of("remark", "HRA (IT)")))
                        .get("approvalId")
                        .asLong();
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                approval,
                                "REJECT",
                                "Wrong order (IT)",
                                null))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT event_status FROM arrear_event WHERE arrear_event_id = ?",
                                String.class,
                                event))
                .isEqualTo("REJECTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT arrear_status FROM arrear_detail WHERE arrear_event_id = ?",
                                String.class,
                                event))
                .isEqualTo("REVERSED");
        assertThat(
                        code(
                                post(
                                        maker,
                                        "/v1/arrear-events/" + event + "/submissions",
                                        Map.of("remark", "again"))))
                .isEqualTo("ARR-409-STATE");
        Session hq = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        assertThat(all(hq, "/v1/arrear-events?status=REJECTED").findValuesAsString("reference"))
                .as("Headquarters reads every office")
                .contains(reference);
        assertThat(
                        mvc.perform(
                                        userGet(
                                                hq,
                                                "/v1/arrear-events/register?format=CSV&status=REJECTED&eventCode=HRA_REVISION"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }
}
