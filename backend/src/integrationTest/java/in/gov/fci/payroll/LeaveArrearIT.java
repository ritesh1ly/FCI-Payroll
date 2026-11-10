package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 13 phase 13d (plan docs/plan/13-arrears-retro-wage-revision.md): I-24 Leave Encashment
 * Arrear. The Payroll Maker runs the arrear for one member against an Active revision order: the
 * encashment dated after the order's effective date is recalculated on the revised pay, month by
 * month (SRS-8-23); the Maker submits the record, the Payroll Checker approves (SRS-8-24); a second
 * run never pays twice; the Checker cannot run, Finance cannot submit, a record already approved is
 * not submitted again; the LE Arrear Invoice Report downloads.
 */
class LeaveArrearIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long member() {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND employment_status ="
                        + " 'IN_SERVICE' AND record_status = 'ACTIVE' ORDER BY emp_num OFFSET 8"
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"));
    }

    private long circular(String status) {
        String number = "IT/LEA/" + System.nanoTime();
        // An Active circular carries its order copy (chk_circular_order_doc).
        jdbc.update(
                "INSERT INTO document_attachment (entity_name, entity_key, file_name,"
                        + " storage_provider, storage_object_key, contains_pii, uploaded_by) VALUES"
                        + " ('circular_order', ?, 'it.pdf', 'ORACLE_SECUREFILE', ?, 'N', 'it')",
                number,
                "it/circular/" + number);
        jdbc.update(
                "INSERT INTO circular_order (circular_number, circular_date, subject,"
                        + " category_code, applies_all_offices, effective_from,"
                        + " document_attachment_id, circular_status, created_by) SELECT ?, ?, 'DA"
                        + " revision (IT)', 'DA', 'Y', ?, attachment_id, ?, 'it.fixture' FROM"
                        + " document_attachment WHERE storage_object_key = ?",
                number,
                java.sql.Date.valueOf(LocalDate.now(IST)),
                java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)),
                status,
                "it/circular/" + number);
        return jdbc.queryForObject(
                "SELECT circular_order_id FROM circular_order WHERE circular_number = ?",
                Long.class,
                number);
    }

    private Map<String, Object> run(Session s, String eis, long circular, LocalDate effective)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("eis", eis);
        body.put("circularId", circular);
        body.put("revisionEffective", effective.toString());
        body.put("reason", "DA revision order (IT)");
        MvcResult r =
                mvc.perform(userPost(s, "/v1/leave-encashment-arrears/runs", body)).andReturn();
        Map<String, Object> out = new HashMap<>();
        out.put("status", r.getResponse().getStatus());
        out.put("body", r.getResponse().getContentAsString().isEmpty() ? null : body(r));
        return out;
    }

    // SC-13-0043 (only a further difference is paid), SC-13-0050 (the encashable days as sanctioned
    // in HRMS). SC-13-0042: UC Process a Leave Encashment Arrear (I-24), main flow.
    @Test
    void SRS_8_23_theEncashmentIsRecalculatedOnTheRevisedPaySubmittedApprovedAndNeverPaidTwice()
            throws Exception {
        long employeeId = member();
        String eis =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?",
                        String.class,
                        employeeId);
        // Within the demo rates (from 2026-04) and before this month.
        LocalDate demo = LocalDate.of(2026, 4, 12);
        LocalDate twoBack = LocalDate.now(IST).minusMonths(2).withDayOfMonth(12);
        LocalDate day = twoBack.isAfter(demo) ? twoBack : demo;
        jdbc.update(
                "INSERT INTO leave_encashment (employee_id, leave_type_code, encashment_reason,"
                        + " period_from, period_to, days_encashed, valuation_basis, rate_per_day,"
                        + " amount_paid, was_capped) VALUES (?, 'EL', 'IN_SERVICE', ?, ?, 10, 'IT"
                        + " fixture', 100, 1000, 'N')",
                employeeId,
                java.sql.Date.valueOf(day),
                java.sql.Date.valueOf(day));
        long order = circular("ACTIVE");
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");

        assertThat(run(checker, eis, order, day.withDayOfMonth(1)).get("status"))
                .as("only the Payroll Maker runs")
                .isEqualTo(403);
        assertThat(
                        (Integer)
                                run(maker, eis, circular("WITHDRAWN"), day.withDayOfMonth(1))
                                        .get("status"))
                .as("an order that is not Active")
                .isBetween(400, 499);

        Map<String, Object> first = run(maker, eis, order, day.withDayOfMonth(1));
        assertThat(first.get("status")).as(String.valueOf(first.get("body"))).isEqualTo(200);
        JsonNode result = (JsonNode) first.get("body");
        assertThat(result.path("records").asInt()).as(result.toString()).isEqualTo(1);
        JsonNode rows =
                all(maker, "/v1/leave-encashment-arrears?eis=" + eis + "&status=CALCULATED");
        JsonNode record = rows.get(0);
        BigDecimal paid = record.path("paid").decimalValue();
        BigDecimal due = record.path("due").decimalValue();
        assertThat(paid).isEqualByComparingTo("1000");
        assertThat(record.path("arrear").decimalValue()).isEqualByComparingTo(due.subtract(paid));
        assertThat(record.path("revisedBasic").decimalValue()).isPositive();
        assertThat(record.path("recordNumber").asString()).startsWith("LEA-");

        // A second run before submission replaces the record, never adds to it.
        assertThat(run(maker, eis, order, day.withDayOfMonth(1)).get("status")).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM leave_encashment_arrear WHERE employee_id = ?"
                                        + " AND le_status = 'CANCELLED'",
                                Integer.class,
                                employeeId))
                .isEqualTo(1);
        long id =
                all(maker, "/v1/leave-encashment-arrears?eis=" + eis + "&status=CALCULATED")
                        .get(0)
                        .path("id")
                        .asLong();

        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        Map<String, Object> submission = Map.of("ids", List.of(id), "remark", "Order (IT)");
        assertThat(
                        mvc.perform(
                                        userPost(
                                                finance,
                                                "/v1/leave-encashment-arrears/submissions",
                                                submission))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        MvcResult submitted =
                mvc.perform(userPost(maker, "/v1/leave-encashment-arrears/submissions", submission))
                        .andReturn();
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(submitted).get("approvalId").asLong();
        assertThat(decide(maker, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(checker, approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT le_status FROM leave_encashment_arrear WHERE le_arrear_id ="
                                        + " ?",
                                String.class,
                                id))
                .isEqualTo("APPROVED");
        assertThat(
                        body(mvc.perform(
                                                userPost(
                                                        maker,
                                                        "/v1/leave-encashment-arrears/submissions",
                                                        submission))
                                        .andReturn())
                                .path("errorCode")
                                .asString())
                .isEqualTo("LEA-409-STATE");

        // BR-ARR-LE-003: the approved arrear counts as paid: nothing further to pay.
        JsonNode again = (JsonNode) run(maker, eis, order, day.withDayOfMonth(1)).get("body");
        assertThat(again.path("records").asInt()).isZero();

        // SC-13-0045 (l.3126): each arrear is paid once, traced to its revision order, and the
        // report carries its invoice columns (US-GAP-246: not raised until module 23 sends it).
        Map<String, Object> approved =
                jdbc.queryForMap(
                        "SELECT a.record_number, c.circular_number FROM leave_encashment_arrear a"
                                + " JOIN circular_order c ON c.circular_order_id ="
                                + " a.circular_order_id WHERE a.le_arrear_id = ?",
                        id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM leave_encashment_arrear WHERE employee_id = ?"
                                        + " AND le_status = 'APPROVED'",
                                Integer.class,
                                employeeId))
                .as("paid once")
                .isEqualTo(1);
        String report = csv(maker, "/v1/leave-encashment-arrears/report?format=CSV&eis=" + eis);
        assertThat(report.lines().findFirst().orElseThrow())
                .contains("order")
                .contains("invoice_status")
                .contains("invoice_sent_to_apps");
        List<String> lines =
                report.lines()
                        .filter(l -> l.contains((String) approved.get("RECORD_NUMBER")))
                        .toList();
        assertThat(lines).as(report).hasSize(1);
        assertThat(lines.getFirst())
                .contains(eis)
                .contains((String) approved.get("CIRCULAR_NUMBER"))
                .contains("APPROVED")
                .contains("NOT_RAISED");
    }
}
