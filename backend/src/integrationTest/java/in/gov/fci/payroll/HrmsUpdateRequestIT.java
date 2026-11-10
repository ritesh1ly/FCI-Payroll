package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * HRMS Update Request (plan 16 phase 16c; SRS-1-70): raised by the Payroll Maker on I-21 or by the
 * employee in eSeva; Payroll reads the record from HRMS (the scripted stand-in {@code
 * ApiTestSupport.HRMS_VALUES}): a different value opens the I-21 task, a pending change is linked,
 * the same value closes the request as not yet in HRMS, a failed read is fetched again. The request
 * never changes a value. Employees are Delhi HQ (DH001) members past those of the other I-21 ITs.
 */
class HrmsUpdateRequestIT extends ApiTestSupport {

    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<< >>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Map<String, Object> employee(String office, int offset) {
        return jdbc.queryForMap(
                "SELECT employee_id, emp_num, pan_number, uan_number FROM employee WHERE"
                        + " location_id = ? AND record_status = 'ACTIVE' ORDER BY emp_num OFFSET "
                        + offset
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                locationId(office));
    }

    private static long id(Map<String, Object> employee) {
        return ((Number) employee.get("EMPLOYEE_ID")).longValue();
    }

    private static Map<String, Object> body(String eis, String field) {
        Map<String, Object> body = new HashMap<>();
        if (eis != null) {
            body.put("eis", eis);
        }
        body.put("fieldCode", field);
        body.put("effectiveDate", LocalDate.now(IST).minusDays(5).toString());
        return body;
    }

    private MvcResult raise(Session s, String path, Map<String, Object> body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private JsonNode raised(Session s, Map<String, Object> body) throws Exception {
        MvcResult r = raise(s, "/v1/hrms-update-requests", body);
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(201);
        return body(r);
    }

    /**
     * SRS-1-70: HRMS holds a different PAN: the I-21 task opens (source UPDATE_REQUEST_FETCH) and
     * the PAN is unchanged; the same request again links that pending task; HRMS holding Payroll's
     * UAN closes the request as not yet in HRMS.
     */
    @Test
    // SRS-INT-25: the request fetches the HRMS record; it never changes a value by itself.
    void SRS_1_70_SRS_INT_25_aDifferentHrmsValueOpensTheTaskAndTheSameValueClosesTheRequest()
            throws Exception {
        Map<String, Object> member = employee("DH001", 25);
        String eis = (String) member.get("EMP_NUM");
        HRMS_VALUES.put(id(member) + "|PAN", "WXYPA1357B");
        Session maker = maker();

        JsonNode opened = raised(maker, body(eis, "PAN"));
        assertThat(opened.get("requestNo").asString()).startsWith("HUR-");
        assertThat(opened.get("status").asString()).isEqualTo("TASK_OPENED");
        assertThat(opened.get("fetchResult").asString()).isEqualTo("DIFFERS");
        String taskNo = opened.get("taskNo").asString();
        Map<String, Object> task =
                jdbc.queryForMap(
                        "SELECT task_source, new_value, task_status FROM hrms_change_task WHERE"
                                + " task_no = ?",
                        taskNo);
        assertThat(task.get("TASK_SOURCE")).isEqualTo("UPDATE_REQUEST_FETCH");
        assertThat(task.get("NEW_VALUE")).isEqualTo("WXYPA1357B");
        assertThat(task.get("TASK_STATUS")).isEqualTo("PENDING_ORDER_DETAILS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pan_number FROM employee WHERE employee_id = ?",
                                String.class,
                                id(member)))
                .as("a request never changes a value")
                .isEqualTo(member.get("PAN_NUMBER"));

        JsonNode linked = raised(maker, body(eis, "PAN"));
        assertThat(linked.get("status").asString()).isEqualTo("LINKED_EXISTING");
        assertThat(linked.get("taskNo").asString()).isEqualTo(taskNo);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hrms_change_task t JOIN employee e ON"
                                        + " e.employee_id = t.employee_id WHERE e.emp_num = ? AND"
                                        + " t.new_value = 'WXYPA1357B'",
                                Long.class,
                                eis))
                .as("never opened twice")
                .isEqualTo(1);

        // HRMS holds Payroll's UAN (or, as Payroll, none): no change either way
        if (member.get("UAN_NUMBER") != null) {
            HRMS_VALUES.put(id(member) + "|UAN", (String) member.get("UAN_NUMBER"));
        }
        JsonNode same = raised(maker, body(eis, "UAN"));
        assertThat(same.get("status").asString()).isEqualTo("NOT_YET_IN_HRMS");
        assertThat(same.get("messageCode").asString()).isEqualTo("HRMS_NOT_YET_UPDATED");
        assertThat(same.get("taskNo").isNull()).isTrue();
        // SRS-17-15: each request and its outcome is in the HRMS Change Register, a request
        // closed as not yet in HRMS too; the Name / DOB register carries tasks only.
        String register = csv(maker, "/v1/hrms-change-tasks/export?format=CSV&eis=" + eis);
        assertThat(register.split("\r\n")[0]).endsWith(",\"update_request_no\",\"fetch_result\"");
        assertThat(register)
                .contains(
                        "\"" + same.get("requestNo").asString() + "\",\"NO_CHANGE\"",
                        "\"" + opened.get("requestNo").asString() + "\",\"DIFFERS\"")
                .contains("\"NOT_YET_IN_HRMS\"", "\"LINKED_EXISTING\"");
        assertThat(
                        csv(
                                maker,
                                "/v1/hrms-change-tasks/export?format=CSV&register=NAME_DOB&eis="
                                        + eis))
                .doesNotContain(same.get("requestNo").asString());

        JsonNode list =
                body(mvc.perform(userGet(maker, "/v1/hrms-update-requests?size=100")).andReturn());
        assertThat(list.toString()).contains(opened.get("requestNo").asString());
        JsonNode fields =
                body(mvc.perform(userGet(maker, "/v1/hrms-update-requests/fields")).andReturn());
        assertThat(fields.toString()).contains("\"PAN\"").doesNotContain("\"GENDER\"");
    }

    /**
     * SRS-1-70: HRMS cannot be read: FETCH_FAILED, fetched again by the Maker once HRMS answers
     * (the task opens), and not again after that; a field without pay effect, an unknown employee,
     * a role without the right and an employee of another unit are refused.
     */
    @Test
    void SRS_1_70_aFailedFetchIsFetchedAgainAndTheRefusals() throws Exception {
        Map<String, Object> member = employee("DH001", 26);
        String eis = (String) member.get("EMP_NUM");
        HRMS_VALUES.put(id(member) + "|BASIC_PAY", HRMS_DOWN);
        Session maker = maker();
        JsonNode failed = raised(maker, body(eis, "BASIC_PAY"));
        assertThat(failed.get("status").asString()).isEqualTo("FETCH_FAILED");
        assertThat(failed.get("messageCode").asString()).isEqualTo("HRMS_FETCH_FAILED");
        long id = failed.get("id").asLong();

        HRMS_VALUES.put(id(member) + "|BASIC_PAY", "64300");
        MvcResult again =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/hrms-update-requests/" + id + "/fetches",
                                        Map.of()))
                        .andReturn();
        assertThat(again.getResponse().getStatus())
                .as(again.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(again).get("status").asString()).isEqualTo("TASK_OPENED");
        assertThat(
                        mvc.perform(
                                        userPost(
                                                maker,
                                                "/v1/hrms-update-requests/" + id + "/fetches",
                                                Map.of()))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(409);

        assertThat(
                        body(raise(maker, "/v1/hrms-update-requests", body(eis, "GENDER")))
                                .path("errorCode")
                                .asString())
                .isEqualTo("HRC-422-REQUEST");
        assertThat(
                        body(raise(maker, "/v1/hrms-update-requests", body("NO-SUCH-9", "PAN")))
                                .path("errorCode")
                                .asString())
                .isEqualTo("HRC-422-REQUEST");
        Map<String, Object> noDate = body(eis, "PAN");
        noDate.remove("effectiveDate");
        assertThat(raise(maker, "/v1/hrms-update-requests", noDate).getResponse().getStatus())
                .isEqualTo(400);
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        assertThat(
                        raise(finance, "/v1/hrms-update-requests", body(eis, "PAN"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        String other = (String) employee("KL001", 4).get("EMP_NUM");
        assertThat(
                        raise(maker, "/v1/hrms-update-requests", body(other, "PAN"))
                                .getResponse()
                                .getStatus())
                .as("an employee of another unit")
                .isEqualTo(403);
        assertThat(
                        raise(maker, "/v1/hrms-update-requests/mine", body(null, "PAN"))
                                .getResponse()
                                .getStatus())
                .as("the Maker has no eSeva record")
                .isEqualTo(403);
    }

    /** SRS-1-70: the employee raises the request in eSeva and sees it among their own requests. */
    // SC-14-0017: UC E-01 main flow (raise an HRMS update request). SC-14-0025: the member tracks
    // the status of the submission, with its linked I-21 task's status.
    @Test
    void SRS_1_70_SRS_17_14_theEmployeeRaisesTheRequestInEseva() throws Exception {
        Long self =
                jdbc.queryForObject(
                        "SELECT employee_id FROM app_user WHERE login_id ="
                                + " 'emp.rsharma@fci-demo.test'",
                        Long.class);
        HRMS_VALUES.put(self + "|EPS_NUMBER", "EPS-NA-55021");
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        assertThat(
                        mvc.perform(userGet(member, "/v1/hrms-update-requests/fields"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("the employee reads the fields without I-21")
                .isEqualTo(200);
        MvcResult r = raise(member, "/v1/hrms-update-requests/mine", body(null, "EPS_NUMBER"));
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode made = body(r);
        assertThat(made.get("channel").asString()).isEqualTo("ESEVA");
        assertThat(made.get("status").asString()).isIn("TASK_OPENED", "LINKED_EXISTING");
        JsonNode mine =
                body(mvc.perform(userGet(member, "/v1/hrms-update-requests/mine")).andReturn());
        assertThat(mine.toString()).contains(made.get("requestNo").asString());
        JsonNode tracked = null;
        for (JsonNode row : all(member, "/v1/hrms-update-requests/mine")) {
            if (row.path("requestNo").asString().equals(made.get("requestNo").asString())) {
                tracked = row;
            }
        }
        assertThat(tracked).as("SC-14-0025: the submission is tracked").isNotNull();
        assertThat(tracked.path("status").asString()).isEqualTo(made.get("status").asString());
        assertThat(tracked.path("channel").asString()).isEqualTo("ESEVA");
        assertThat(tracked.path("raisedAt").asString()).isNotBlank();
        java.util.List<String> linked =
                jdbc.queryForList(
                        "SELECT t.task_status FROM hrms_change_task t JOIN hrms_update_request r ON"
                            + " r.linked_task_id = t.hrms_change_task_id WHERE r.request_no = ?",
                        String.class,
                        made.get("requestNo").asString());
        if (linked.isEmpty()) {
            assertThat(tracked.path("taskStatus").isNull()).isTrue();
        } else {
            assertThat(tracked.path("taskStatus").asString())
                    .as("the linked I-21 task's status")
                    .isEqualTo(linked.getFirst());
        }
        assertThat(
                        raise(member, "/v1/hrms-update-requests", body("104882", "PAN"))
                                .getResponse()
                                .getStatus())
                .as("no Maker right in eSeva")
                .isEqualTo(403);
        // R-ASCII: an eSeva request in Devanagari is refused by the server (Hindi only in labels).
        long before =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM hrms_update_request WHERE employee_id = ?",
                        Long.class,
                        self);
        assertThat(
                        raise(
                                        member,
                                        "/v1/hrms-update-requests/mine",
                                        body(null, "\u092A\u0948\u0928"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hrms_update_request WHERE employee_id = ?",
                                Long.class,
                                self))
                .isEqualTo(before);
        // SRS-1-70 "may attach the order": the member attaches the copy to the own request.
        long id = made.get("id").asLong();
        MvcResult attached =
                mvc.perform(
                                userRequest(
                                        member,
                                        multipart(
                                                        "/v1/hrms-update-requests/mine/"
                                                                + id
                                                                + "/documents")
                                                .file(
                                                        new MockMultipartFile(
                                                                "file",
                                                                "order.pdf",
                                                                "application/pdf",
                                                                PDF))
                                                .param("documentType", "OTHER")
                                                .param("orderDate", "2026-10-01")
                                                .param("issuingAuthority", "IT Zonal Office")
                                                .header(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString())))
                        .andReturn();
        assertThat(attached.getResponse().getStatus())
                .as(attached.getResponse().getContentAsString())
                .isEqualTo(201);
        long file = body(attached).get("id").asLong();
        JsonNode copies =
                body(
                        mvc.perform(
                                        userGet(
                                                member,
                                                "/v1/hrms-update-requests/mine/"
                                                        + id
                                                        + "/documents"))
                                .andReturn());
        assertThat(copies.findValuesAsString("fileName")).containsExactly("order.pdf");
        MvcResult download =
                mvc.perform(
                                userGet(
                                        member,
                                        "/v1/hrms-update-requests/mine/"
                                                + id
                                                + "/documents/"
                                                + file))
                        .andReturn();
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getHeader("Content-Disposition")).contains("attachment");
        // Another request (or none) of the member's: not found; the desk path needs I-21.
        assertThat(
                        mvc.perform(
                                        userGet(
                                                member,
                                                "/v1/hrms-update-requests/mine/99999999/documents"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(
                        mvc.perform(
                                        userGet(
                                                member,
                                                "/v1/hrms-update-requests/" + id + "/documents"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        // The finance desk at HQ has no I-21 REQUEST in the member's unit: not found.
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        int financeStatus =
                mvc.perform(userGet(finance, "/v1/hrms-update-requests/" + id + "/documents"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(financeStatus).isIn(403, 404);
    }

    /**
     * SRS-1-70: the I-21 desk attaches the order copy to a request of its unit, lists it and
     * downloads it as an attachment (never rendered inline); an unknown request has no documents.
     */
    @Test
    void SRS_1_70_theDeskAttachesListsAndDownloadsTheOrderCopy() throws Exception {
        Map<String, Object> member = employee("DH001", 27);
        HRMS_VALUES.put(id(member) + "|PAN", "WXYPB2468C");
        Session maker = maker();
        long id = raised(maker, body((String) member.get("EMP_NUM"), "PAN")).get("id").asLong();
        String documents = "/v1/hrms-update-requests/" + id + "/documents";
        MvcResult attached =
                mvc.perform(
                                userRequest(
                                        maker,
                                        multipart(documents)
                                                .file(
                                                        new MockMultipartFile(
                                                                "file",
                                                                "pan-order.pdf",
                                                                "application/pdf",
                                                                PDF))
                                                .param("documentType", "OTHER")
                                                .param("reference", "ZO/EST/2026/77")
                                                .param("orderDate", "2026-10-01")
                                                .param("issuingAuthority", "IT Zonal Office")
                                                .header(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString())))
                        .andReturn();
        assertThat(attached.getResponse().getStatus())
                .as(attached.getResponse().getContentAsString())
                .isEqualTo(201);
        long file = body(attached).get("id").asLong();
        JsonNode copies = body(mvc.perform(userGet(maker, documents)).andReturn());
        assertThat(copies.findValuesAsString("fileName")).containsExactly("pan-order.pdf");
        MvcResult download = mvc.perform(userGet(maker, documents + "/" + file)).andReturn();
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getHeader("Content-Disposition")).contains("attachment");
        assertThat(
                        mvc.perform(userGet(maker, "/v1/hrms-update-requests/99999999/documents"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
    }
}
