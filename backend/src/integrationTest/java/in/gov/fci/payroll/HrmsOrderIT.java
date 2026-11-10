package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.employee.api.HrmsChanges;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * I-21 one order for many staff and the registers (plan 16 phase 16b; SRS-1-67, SRS-1-68, SRS-1-30,
 * SRS-1-71, SRS-1-22): the Maker enters one order for several tasks of one paying unit; the Checker
 * approves them all in one action (one OTP when a Basic Pay change is among them); an order of one
 * task, of two units, or by a role without the right is refused; the registers count and export the
 * tasks masked; a DOB task approved by the Checker shows as waiting for HQ Establishment. Employees
 * are Delhi HQ (DH001) members past those of {@code HrmsChangeIT}.
 */
class HrmsOrderIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private HrmsChanges intake;

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private long employee(String office, int offset) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                        + " 'ACTIVE' ORDER BY emp_num OFFSET "
                        + offset
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId(office));
    }

    private HrmsChanges.Received receive(long employeeId, String field, String value) {
        return intake.receive(
                new HrmsChanges.Change(
                        employeeId,
                        field,
                        value,
                        LocalDate.now(IST),
                        "IT-" + System.nanoTime(),
                        HrmsChanges.Source.HRMS_PUSH));
    }

    private MvcResult order(Session s, List<Long> taskIds) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", "EST/2026/TR-31");
        body.put("orderDate", LocalDate.now(IST).minusDays(2).toString());
        body.put("issuingAuthority", "General Manager (P), Delhi");
        body.put("subject", "Transfer order");
        body.put("taskIds", taskIds);
        // SRS-1-67 / SRS-G-31: the one copy of the order, stored first and named by the order.
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder r =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/v1/hrms-change-orders/copies");
        r.file(
                new org.springframework.mock.web.MockMultipartFile(
                        "file",
                        "order.pdf",
                        "application/pdf",
                        "%PDF-1.7 bulk order"
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        r.param("documentType", "OTHER")
                .param("reference", "EST/2026/TR-31")
                .param("orderDate", LocalDate.now(IST).minusDays(2).toString())
                .param("issuingAuthority", "General Manager (P), Delhi");
        MvcResult stored =
                mvc.perform(
                                userRequest(s, r)
                                        .header(
                                                "Idempotency-Key",
                                                java.util.UUID.randomUUID().toString()))
                        .andReturn();
        if (stored.getResponse().getStatus() == 201) {
            body.put("copyAttachmentId", body(stored).get("attachmentId").asLong());
            body.put("copyProvisionalKey", body(stored).get("provisionalKey").asString());
        }
        return mvc.perform(userPost(s, "/v1/hrms-change-orders", body)).andReturn();
    }

    /**
     * A real-format PAN for the member: the 5th character is the surname's initial
     * (SeedDataFormatIT).
     */
    private String pan(long employeeId, String head, String tail) {
        String last =
                jdbc.queryForObject(
                        "SELECT COALESCE(last_name, first_name) FROM employee WHERE employee_id ="
                                + " ?",
                        String.class,
                        employeeId);
        return head + Character.toUpperCase(last.charAt(0)) + tail;
    }

    private String status(long taskId) {
        return jdbc.queryForObject(
                "SELECT task_status FROM hrms_change_task WHERE hrms_change_task_id = ?",
                String.class,
                taskId);
    }

    /** A Basic the member's scale holds (SRS-13-23); any amount when no stages are configured. */
    private String stageOf(long employeeId) {
        java.math.BigDecimal stage =
                jdbc.queryForObject(
                        "SELECT MIN(s.stage_basic) FROM pay_scale_band_stage s JOIN"
                                + " pay_scale_band_version v ON v.band_version_id ="
                                + " s.band_version_id JOIN employee e ON e.band_id = v.band_id"
                                + " WHERE e.employee_id = ? AND v.effective_to IS NULL",
                        java.math.BigDecimal.class,
                        employeeId);
        return stage == null ? "58400" : stage.toPlainString();
    }

    /**
     * SRS-1-67: two PAN changes and a Basic Pay change in one order: one approval item; the Maker
     * cannot approve it; the Checker approves with the one OTP and all three apply; the order
     * records the Checker and the challenge.
     */
    // SC-01-0060: where the transaction class allows, several items are approved together.
    // SC-16-0158: one order for many staff, entered once and linked to every task.
    // SC-04-0030: one transfer order for many staff, recorded once.
    @Test
    void SRS_1_67_oneOrderForManyStaffIsApprovedInOneActionWithOneOtp() throws Exception {
        String panB = pan(employee("DH001", 14), "LMNP", "5678R");
        HrmsChanges.Received a =
                receive(employee("DH001", 13), "PAN", pan(employee("DH001", 13), "LMNP", "1234Q"));
        HrmsChanges.Received b = receive(employee("DH001", 14), "PAN", panB);
        HrmsChanges.Received c =
                receive(employee("DH001", 15), "BASIC_PAY", stageOf(employee("DH001", 15)));
        Session maker = maker();
        MvcResult created = order(maker, List.of(a.taskId(), b.taskId(), c.taskId()));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(202);
        JsonNode body = body(created);
        assertThat(body.get("orderRef").asString()).startsWith("HCO-");
        assertThat(body.get("tasks").asInt()).isEqualTo(3);
        long approval = body.get("approvalId").asLong();
        long orderId = body.get("orderId").asLong();
        assertThat(status(a.taskId())).isEqualTo("PENDING_CHECKER");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT otp_required FROM hrms_change_task_order WHERE"
                                        + " task_order_id = ?",
                                String.class,
                                orderId))
                .isEqualTo("Y");
        assertThat(order(maker, List.of(a.taskId(), b.taskId())).getResponse().getStatus())
                .as("tasks already with the Checker")
                .isEqualTo(409);

        JsonNode list =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/hrms-change-tasks?status=PENDING_CHECKER&size=100"))
                                .andReturn());
        assertThat(list.toString()).contains(body.get("orderRef").asString());

        assertThat(decide(maker, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(checker(), approval, "APPROVE", null, "INCREMENT_DATE_BASIC_CORRECTION"))
                .isEqualTo(200);
        for (long id : List.of(a.taskId(), b.taskId(), c.taskId())) {
            assertThat(status(id)).isEqualTo("APPROVED");
        }
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT order_status, approved_by, otp_challenge_id FROM"
                                + " hrms_change_task_order WHERE task_order_id = ?",
                        orderId);
        assertThat(row.get("ORDER_STATUS")).isEqualTo("APPROVED");
        assertThat(row.get("APPROVED_BY")).isEqualTo("ddo.delhi@fci-demo.test");
        assertThat(row.get("OTP_CHALLENGE_ID")).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pan_number FROM employee WHERE employee_id = ?",
                                String.class,
                                employee("DH001", 14)))
                .isEqualTo(panB);
    }

    /**
     * SRS-1-67: a returned order sends its tasks back to the Maker and is open again; a rejected
     * order returns them to HRMS. An order of one task, of two paying units, or by Finance is
     * refused.
     */
    @Test
    void SRS_1_67_aReturnedOrRejectedOrderAndTheRefusals() throws Exception {
        HrmsChanges.Received a = receive(employee("DH001", 16), "UAN", "100444555666");
        HrmsChanges.Received b = receive(employee("DH001", 17), "UAN", "100444555777");
        Session maker = maker();
        assertThat(order(maker, List.of(a.taskId())).getResponse().getStatus()).isEqualTo(400);
        HrmsChanges.Received other = receive(employee("KL001", 3), "UAN", "100444555888");
        assertThat(
                        body(order(maker, List.of(a.taskId(), other.taskId())))
                                .path("errorCode")
                                .asString())
                .isEqualTo("HRC-422-ORDER");
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        assertThat(order(finance, List.of(a.taskId(), b.taskId())).getResponse().getStatus())
                .isEqualTo(403);

        JsonNode first = body(order(maker, List.of(a.taskId(), b.taskId())));
        assertThat(
                        decide(
                                checker(),
                                first.get("approvalId").asLong(),
                                "RETURN",
                                "Attach the copy",
                                null))
                .isEqualTo(200);
        assertThat(status(a.taskId())).isEqualTo("RETURNED_TO_MAKER");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT order_status FROM hrms_change_task_order WHERE"
                                        + " task_order_id = ?",
                                String.class,
                                first.get("orderId").asLong()))
                .isEqualTo("OPEN");

        JsonNode second = body(order(maker, List.of(a.taskId(), b.taskId())));
        // SRS-G-29 / SRS-G-31: a Checker of another office neither approves nor rejects the order
        // (US-GAP-138); the order stays pending for the unit's own Checker.
        Session kolkata = login("ddo.kolkata@fci-demo.test", "PAYROLL_CHECKER", "KL001");
        assertThat(decide(kolkata, second.get("approvalId").asLong(), "APPROVE", null, null))
                .isIn(403, 404);
        assertThat(status(a.taskId())).isNotEqualTo("APPROVED");
        assertThat(
                        decide(
                                checker(),
                                second.get("approvalId").asLong(),
                                "REJECT",
                                "Not our order",
                                null))
                .isEqualTo(200);
        assertThat(status(b.taskId())).isEqualTo("REJECTED_RETURNED_TO_HRMS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT order_status FROM hrms_change_task_order WHERE"
                                        + " task_order_id = ?",
                                String.class,
                                second.get("orderId").asLong()))
                .isEqualTo("REJECTED");
    }

    /**
     * SRS-1-30 / SRS-1-71 / SRS-1-22: the counts by status and field; the HRMS Change Register
     * masks the PAN; the Name / DOB register carries the DOB task with its recalculated dates; the
     * DOB task the Checker approved waits for HQ Establishment and shows so; Finance (no I-21
     * EXPORT) cannot export.
     */
    @Test
    void SRS_1_30_theRegistersCountAndExportTheTasks() throws Exception {
        HrmsChanges.Received pan = receive(employee("DH001", 18), "PAN", "RSTPV2468W");
        HrmsChanges.Received dob = receive(employee("DH001", 19), "DATE_OF_BIRTH", "1962-08-14");
        Session maker = maker();
        JsonNode counts =
                body(
                        mvc.perform(userGet(maker, "/v1/hrms-change-tasks/counts?nameDob=true"))
                                .andReturn());
        assertThat(counts.get("total").asLong()).isPositive();
        assertThat(counts.get("byField").toString())
                .contains("DATE_OF_BIRTH")
                .doesNotContain("\"PAN\"");
        assertThat(counts.get("byStatus").toString()).contains("PENDING_ORDER_DETAILS");

        String hrms = csv(maker, "/v1/hrms-change-tasks/export?format=CSV&field=PAN");
        assertThat(hrms)
                .startsWith("\"task_no\",\"hrms_request_id\"")
                .contains(pan.taskNo())
                .contains("******468W");
        assertThat(hrms).doesNotContain("RSTPV2468W");
        String nameDob = csv(maker, "/v1/hrms-change-tasks/export?format=CSV&register=NAME_DOB");
        assertThat(nameDob)
                .startsWith("\"request_ref\",\"eis\"")
                .contains(dob.taskNo())
                .contains("2022-08-31")
                .contains("2020-08-14");
        assertThat(nameDob).doesNotContain(pan.taskNo());

        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", "EST/2026/DOB-9");
        body.put("orderDate", LocalDate.now(IST).minusDays(1).toString());
        body.put("issuingAuthority", "General Manager (P), Delhi");
        body.put("heldInHrms", false);
        attachTaskOrder(maker, dob.taskId());
        long approval =
                body(mvc.perform(
                                        userPost(
                                                maker,
                                                "/v1/hrms-change-tasks/"
                                                        + dob.taskId()
                                                        + "/submissions",
                                                body))
                                .andReturn())
                        .get("approvalId")
                        .asLong();
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        JsonNode detail =
                body(
                        mvc.perform(userGet(maker, "/v1/hrms-change-tasks/" + dob.taskId()))
                                .andReturn());
        assertThat(detail.get("task").get("status").asString())
                .isEqualTo("PENDING_HQ_ESTABLISHMENT");
        assertThat(detail.get("task").get("overdue").asBoolean()).isFalse();
        JsonNode hq =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/hrms-change-tasks?status=PENDING_HQ_ESTABLISHMENT&size=100"))
                                .andReturn());
        assertThat(hq.toString()).contains(dob.taskNo());

        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        assertThat(download(finance, "/v1/hrms-change-tasks/export").getResponse().getStatus())
                .isEqualTo(403);
    }
}
