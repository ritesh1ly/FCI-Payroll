package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * X-07 HRMS Fallback Upload end to end (SRS-G-46, SRS-EH-11, BR-INT-011; DDL hrms_fallback_*): HQ
 * enables under maker-checker, the office Maker uploads the HRMS export (placeholder layout of the
 * demo profile, D-2), the Checker of the unit approves the accepted records with OTP. Each test
 * uses its own payroll month so tests never share an enablement.
 */
class FallbackUploadIT extends ApiTestSupport {

    private static final String HEADER_A =
            "EMP_NO,FROM_DATE,TO_DATE,PAY_DAYS,LWP_DAYS,HALF_PAY_DAYS\r\n";

    /** HQ proposes, the HQ Checker approves; returns the OPEN enablement's id. */
    private long enable(String yymm, String type, List<String> offices) throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        long approval =
                body(mvc.perform(
                                        userPost(
                                                admin,
                                                "/v1/hrms-fallback/enablements",
                                                Map.of(
                                                        "type",
                                                        type,
                                                        "payrollYymm",
                                                        yymm,
                                                        "offices",
                                                        offices,
                                                        "reason",
                                                        "HRMS down at cut-off")))
                                .andExpect(status().isAccepted())
                                .andReturn())
                        .get("approvalId")
                        .asLong();
        assertThat(
                        decide(
                                login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        JsonNode list = all(admin, "/v1/hrms-fallback/enablements");
        for (JsonNode e : list) {
            if (e.get("payrollYymm").asString().equals(yymm)
                    && e.get("type").asString().equals(type)) {
                assertThat(e.get("status").asString()).isEqualTo("OPEN");
                assertThat(e.get("reference").asString()).matches("FBE-\\d{4}-\\d{3}");
                return e.get("id").asLong();
            }
        }
        throw new AssertionError("enablement not created");
    }

    private ResultActions upload(Session maker, long enablementId, String csv) throws Exception {
        return mvc.perform(
                userRequest(
                                maker,
                                multipart("/v1/hrms-fallback/uploads")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "hrms-export.csv",
                                                        "text/csv",
                                                        csv.getBytes(StandardCharsets.US_ASCII)))
                                        .param("enablementId", String.valueOf(enablementId)))
                        .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    @Test
    // SC-23-0050 / SRS-INT-19: HRMS data comes only through the interface or the two X-07
    // fallback uploads, approved at the unit with OTP (SRS-G-46).
    void SRS_G_46_theAcceptedRecordsAreApprovedByTheUnitCheckerWithOtp() throws Exception {
        long enablement = enable("202402", "ATTENDANCE_LEAVE", List.of("DH001"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode up =
                body(
                        upload(
                                        maker,
                                        enablement,
                                        HEADER_A
                                                + "105220,01-02-2024,29-02-2024,27,2,0\r\n" // accepted (2024 is a leap year)
                                                + "999999,01-02-2024,29-02-2024,29,0,0\r\n" // not
                                                // on
                                                // the
                                                // Payroll master
                                                + "105220,10-02-2024,12-02-2024,3,0,0\r\n") // overlaps the first record
                                .andExpect(status().isCreated())
                                .andReturn());
        assertThat(up.get("status").asString()).isEqualTo("VALIDATED");
        assertThat(up.get("recordCount").asInt()).isEqualTo(3);
        assertThat(up.get("acceptedCount").asInt()).isEqualTo(1);
        assertThat(up.get("rejectedCount").asInt()).isEqualTo(2);
        long id = up.get("id").asLong();

        JsonNode lines = all(maker, "/v1/hrms-fallback/uploads/" + id + "/lines");
        assertThat(texts(lines, "validationStatus"))
                .containsExactly("ACCEPTED", "REJECTED", "REJECTED");
        assertThat(lines.get(1).get("rejectionReason").asString()).startsWith("EMPLOYEE_NOT_FOUND");
        assertThat(lines.get(2).get("rejectionReason").asString()).startsWith("OVERLAP");
        // The source file is kept read-only with the upload.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_attachment_id FROM hrms_fallback_upload WHERE"
                                        + " fallback_upload_id = ?",
                                Long.class,
                                id))
                .isNotNull();

        JsonNode submitted =
                body(
                        mvc.perform(
                                        userPost(
                                                maker,
                                                "/v1/hrms-fallback/uploads/" + id + "/submit",
                                                Map.of()))
                                .andExpect(status().isAccepted())
                                .andReturn());
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        long approval =
                jdbc.queryForObject(
                        "SELECT approval_id FROM hrms_fallback_upload WHERE fallback_upload_id = ?",
                        Long.class,
                        id);

        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        // Without the OTP the decision is challenged (428), with it approved.
        assertThat(decide(checker, approval, "APPROVE", null, null)).isEqualTo(428);
        assertThat(decide(checker, approval, "APPROVE", null, "HRMS_FALLBACK_UPLOAD_APPROVAL"))
                .isEqualTo(200);

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT upload_status, checker_user, otp_challenge_id, recon_status"
                                + " FROM hrms_fallback_upload WHERE fallback_upload_id = ?",
                        id);
        assertThat(row.get("UPLOAD_STATUS")).isEqualTo("APPROVED");
        assertThat(row.get("CHECKER_USER")).isEqualTo("ddo.delhi@fci-demo.test");
        assertThat(row.get("OTP_CHALLENGE_ID")).isNotNull();
        assertThat(row.get("RECON_STATUS")).isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT enablement_status FROM hrms_fallback_enablement WHERE"
                                        + " enablement_id = ?",
                                String.class,
                                enablement))
                .as("its only office is approved")
                .isEqualTo("APPROVED");
        // SRS-INT-22 (US-GAP-63): X-03 lists it per cycle and office, read-only.
        JsonNode listed = null;
        for (JsonNode r : all(maker, "/v1/integration/hrms-fallback-uploads")) {
            if (r.path("enablementId").asLong() == enablement) {
                listed = r;
            }
        }
        assertThat(listed).as("the enablement on X-03").isNotNull();
        assertThat(listed.path("payrollYymm").asString()).isEqualTo("202402");
        assertThat(listed.path("officeCode").asString()).isEqualTo("DH001");
        assertThat(listed.path("enablementStatus").asString()).isEqualTo("APPROVED");
        assertThat(listed.path("uploadStatus").asString()).isEqualTo("APPROVED");
        assertThat(listed.path("reconStatus").asString()).isEqualTo("PENDING");
        // Another office's session does not see Delhi's row.
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        assertThat(all(chennai, "/v1/integration/hrms-fallback-uploads").findValues("enablementId"))
                .noneMatch(v -> v.asLong() == enablement);
        // Without X-03 the list is refused.
        assertThat(
                        mvc.perform(
                                        userGet(
                                                login("emp.rsharma@fci-demo.test", null, null),
                                                "/v1/integration/hrms-fallback-uploads"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }

    @Test
    void SRS_EH_11_anOfficeTheEnablementDoesNotNameIsRefusedNamingTheHrmsInterface()
            throws Exception {
        long enablement = enable("202403", "ATTENDANCE_LEAVE", List.of("DH001"));
        // SRS-INT-22 (static UI audit #34): an office is offered only the enablements naming it.
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        JsonNode offered =
                body(
                        mvc.perform(userGet(kolkata, "/v1/hrms-fallback/enablements/live"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(offered.findValues("id").stream().map(JsonNode::asLong))
                .doesNotContain(enablement);
        Session delhi = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        body(
                                        mvc.perform(
                                                        userGet(
                                                                delhi,
                                                                "/v1/hrms-fallback/enablements/live"))
                                                .andReturn())
                                .findValues("id")
                                .stream()
                                .map(JsonNode::asLong))
                .contains(enablement);
        upload(kolkata, enablement, HEADER_A + "1,01-03-2024,31-03-2024,31,0,0")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("HFB-409-NOT-ENABLED"));
    }

    @Test
    void SRS_G_46_aFileNotInTheExportFormatIsRejectedAndNothingIsStored() throws Exception {
        long enablement = enable("202404", "ATTENDANCE_LEAVE", List.of("DH001"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        upload(maker, enablement, "NAME,AMOUNT\r\nx,1\r\n")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("HFB-422-FORMAT"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hrms_fallback_upload WHERE enablement_id = ?",
                                Integer.class,
                                enablement))
                .isZero();
    }

    /**
     * SRS-G-22 (l.538) / R-OTP, gated action HRMS_FALLBACK_UPLOAD_APPROVAL: the Checker's right
     * code entered after the challenge's validity ran out is refused (AUTH-400-OTP), the outcome
     * EXPIRED is kept and the upload still waits; a fresh challenge then approves it.
     */
    @Test
    void SRS_G_22_anExpiredOtpOnHrmsFallbackUploadApprovalIsRefused() throws Exception {
        long enablement = enable("202501", "ATTENDANCE_LEAVE", List.of("DH001"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        long id =
                body(upload(
                                        maker,
                                        enablement,
                                        HEADER_A + "105220,01-01-2025,31-01-2025,31,0,0" + "\r\n")
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asLong();
        submit(maker, id).andExpect(status().isAccepted());
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        String otp = "HRMS_FALLBACK_UPLOAD_APPROVAL";
        Map.Entry<org.springframework.test.web.servlet.MvcResult, Long> late =
                decideAfterOtpExpiry(checker, approvalOf(id), "APPROVE", null, otp);
        assertThat(late.getKey().getResponse().getStatus()).isEqualTo(400);
        assertThat(body(late.getKey()).get("errorCode").asString()).isEqualTo("AUTH-400-OTP");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM otp_challenge WHERE otp_challenge_id = ?",
                                String.class,
                                late.getValue()))
                .isEqualTo("EXPIRED");
        assertThat(uploadStatus(id)).isEqualTo("PENDING_APPROVAL");
        assertThat(decide(checker, approvalOf(id), "APPROVE", null, otp)).isEqualTo(200);
        assertThat(uploadStatus(id)).isEqualTo("APPROVED");
    }

    /**
     * R-ASCII (CLAUDE.md; X-07 l.849): the file's contents are outside the request guard. A source
     * file holding a byte above 127 is refused whole by the stored-file check (DOC-422-TYPE,
     * nothing recorded; OD-98); a printable file whose order number holds a control character (a
     * TAB, which the file check allows as a separator) has that record rejected with its reason
     * (UNREADABLE, the field named), the other record of the file is accepted, and what is kept of
     * the rejected line is printable ASCII only.
     */
    @Test
    void R_ASCII_aNonAsciiCellInAnX07UploadIsRejectedPerRecord() throws Exception {
        long enablement = enable("202502", "SUSPENSION_ABSCONDING", List.of("DH001"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        byte[] csv =
                ("EMP_NO,EVENT,EFFECTIVE_DATE,ORDER_NO,ORDER_DATE,ISSUED_BY\r\n"
                                + "105220,SUSPENSION,06-02-2025,RO/DL/81,05-02-2025,RO Delhi\r\n"
                                + "105220,ABSCONDING,07-02-2025,RO/DL/8X,05-02-2025,RO Delhi\r\n")
                        .getBytes(StandardCharsets.US_ASCII);
        int at = new String(csv, StandardCharsets.US_ASCII).lastIndexOf("8X") + 1;
        // The X of the second order number becomes the byte 0xE9 (e-acute in ISO 8859-1): the
        // whole file is refused before any record is read, and no upload is recorded.
        byte[] nonAscii = csv.clone();
        nonAscii[at] = (byte) 0xE9;
        int uploadsBefore =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM hrms_fallback_upload WHERE enablement_id = ?",
                        Integer.class,
                        enablement);
        mvc.perform(
                        userRequest(
                                        maker,
                                        multipart("/v1/hrms-fallback/uploads")
                                                .file(
                                                        new MockMultipartFile(
                                                                "file",
                                                                "hrms-export.csv",
                                                                "text/csv",
                                                                nonAscii))
                                                .param("enablementId", String.valueOf(enablement)))
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("DOC-422-TYPE"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hrms_fallback_upload WHERE enablement_id = ?",
                                Integer.class,
                                enablement))
                .isEqualTo(uploadsBefore);
        // A TAB inside the order number (in place of its last slash) passes the file check but
        // not the cell check; a TAB at either end would be trimmed as white space.
        csv[at - 2] = (byte) 0x09;
        JsonNode up =
                body(
                        mvc.perform(
                                        userRequest(
                                                        maker,
                                                        multipart("/v1/hrms-fallback/uploads")
                                                                .file(
                                                                        new MockMultipartFile(
                                                                                "file",
                                                                                "hrms-export.csv",
                                                                                "text/csv",
                                                                                csv))
                                                                .param(
                                                                        "enablementId",
                                                                        String.valueOf(enablement)))
                                                .header(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString()))
                                .andExpect(status().isCreated())
                                .andReturn());
        assertThat(up.get("acceptedCount").asInt()).isEqualTo(1);
        assertThat(up.get("rejectedCount").asInt()).isEqualTo(1);
        Map<String, Object> rejected =
                jdbc.queryForMap(
                        "SELECT order_no, raw_record, rejection_reason FROM"
                                + " hrms_fallback_upload_line WHERE fallback_upload_id = ? AND"
                                + " validation_status = 'REJECTED'",
                        up.get("id").asLong());
        assertThat(rejected.get("REJECTION_REASON"))
                .isEqualTo("UNREADABLE: the value of orderNo could not be read");
        assertThat(rejected.get("ORDER_NO")).isNull();
        assertThat((String) rejected.get("RAW_RECORD")).contains("RO/DL?8X").matches("[ -~]*");
    }

    private String uploadStatus(long id) {
        return jdbc.queryForObject(
                "SELECT upload_status FROM hrms_fallback_upload WHERE fallback_upload_id = ?",
                String.class,
                id);
    }

    @Test
    void BR_INT_011_fallbackBNeedsTheOrderCopyBeforeSubmission() throws Exception {
        long enablement = enable("202405", "SUSPENSION_ABSCONDING", List.of("DH001"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        long id =
                body(upload(
                                        maker,
                                        enablement,
                                        "EMP_NO,EVENT,EFFECTIVE_DATE,ORDER_NO,ORDER_DATE,ISSUED_BY\r\n"
                                            + "105220,SUSPENSION,06-05-2024,RO/DL/77,05-05-2024,RO"
                                            + " Delhi\r\n")
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asLong();
        mvc.perform(userPost(maker, "/v1/hrms-fallback/uploads/" + id + "/submit", Map.of()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("HFB-422-ORDER"));
        mvc.perform(
                        userRequest(
                                        maker,
                                        multipart("/v1/hrms-fallback/uploads/" + id + "/order")
                                                .file(
                                                        new MockMultipartFile(
                                                                "file",
                                                                "order.pdf",
                                                                "application/pdf",
                                                                "%PDF-1.7 order"
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .US_ASCII)))
                                                .param("documentType", "SANCTION_ORDER")
                                                .param("reference", "RO/DL/77")
                                                .param("orderDate", "2024-05-05")
                                                .param("issuingAuthority", "RO Delhi"))
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
        // The order copy is listed and downloaded through the upload (owner "<upload>_order").
        JsonNode orders =
                body(
                        mvc.perform(userGet(maker, "/v1/hrms-fallback/uploads/" + id + "/orders"))
                                .andExpect(status().isOk())
                                .andReturn());
        long order = orders.get(0).get("id").asLong();
        mvc.perform(userGet(maker, "/v1/hrms-fallback/uploads/" + id + "/files/" + order))
                .andExpect(status().isOk());
        mvc.perform(userPost(maker, "/v1/hrms-fallback/uploads/" + id + "/submit", Map.of()))
                .andExpect(status().isAccepted());
    }

    @Test
    void aReplacedUploadIsSupersededAndAnUnsubmittedOneCanBeCancelledNeverDeleted()
            throws Exception {
        long enablement = enable("202406", "ATTENDANCE_LEAVE", List.of("DH001"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        String csv = HEADER_A + "105220,01-06-2024,30-06-2024,30,0,0\r\n";
        long first = body(upload(maker, enablement, csv).andReturn()).get("id").asLong();
        long second = body(upload(maker, enablement, csv).andReturn()).get("id").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT upload_status FROM hrms_fallback_upload WHERE"
                                        + " fallback_upload_id = ?",
                                String.class,
                                first))
                .isEqualTo("SUPERSEDED");
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/hrms-fallback/uploads/" + second + "/cancel",
                                Map.of("reason", "Wrong office file")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hrms_fallback_upload WHERE enablement_id = ?",
                                Integer.class,
                                enablement))
                .isEqualTo(2);
    }

    @Test
    void theEnablementIsMakerCheckerAndRefusedForALockedCycle() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        // A Payroll Maker cannot enable; only HQ (X-07 CREATE).
        mvc.perform(
                        userPost(
                                login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001"),
                                "/v1/hrms-fallback/enablements",
                                Map.of(
                                        "type",
                                        "ATTENDANCE_LEAVE",
                                        "payrollYymm",
                                        "202407",
                                        "offices",
                                        List.of("DH001"),
                                        "reason",
                                        "x")))
                .andExpect(status().isForbidden());
        // A locked cycle (DH001 2023-12 is locked here) cannot be enabled.
        Long unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DH001'",
                        Long.class);
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = '202312'"
                                + " AND org_unit_id = ?",
                        Integer.class,
                        unit)
                == 0) {
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by) VALUES ('202312',"
                        + " ?, 'DO', '2023-24', 'LOCKED', 'ON_DEMAND', 'it.fixture')",
                    unit);
        }
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/hrms-fallback/enablements",
                                Map.of(
                                        "type",
                                        "ATTENDANCE_LEAVE",
                                        "payrollYymm",
                                        "202312",
                                        "offices",
                                        List.of("DH001"),
                                        "reason",
                                        "late")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("HFB-409-LOCKED"));
        // A third fallback type does not exist (BR-INT-011).
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/hrms-fallback/enablements",
                                Map.of(
                                        "type",
                                        "PROMOTION",
                                        "payrollYymm",
                                        "202407",
                                        "offices",
                                        List.of("DH001"),
                                        "reason",
                                        "x")))
                .andExpect(status().isBadRequest());
    }

    private long approvalOf(long uploadId) {
        return jdbc.queryForObject(
                "SELECT approval_id FROM hrms_fallback_upload WHERE fallback_upload_id = ?",
                Long.class,
                uploadId);
    }

    private ResultActions submit(Session maker, long uploadId) throws Exception {
        return mvc.perform(
                userPost(maker, "/v1/hrms-fallback/uploads/" + uploadId + "/submit", Map.of()));
    }

    @Test
    void SRS_G_46_aReturnedUploadIsResubmittedARejectedOneRecordsTheReason() throws Exception {
        long enablement = enable("202408", "ATTENDANCE_LEAVE", List.of("DH001"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        String csv = HEADER_A + "105220,01-08-2024,31-08-2024,31,0,0\r\n";
        long id =
                body(upload(maker, enablement, csv).andExpect(status().isCreated()).andReturn())
                        .get("id")
                        .asLong();
        submit(maker, id).andExpect(status().isAccepted());
        // While the Checker decides: no second submission, no replacement upload.
        submit(maker, id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("HFB-409-STATUS"));
        upload(maker, enablement, csv)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("HFB-409-STATUS"));

        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        String otp = "HRMS_FALLBACK_UPLOAD_APPROVAL";
        assertThat(decide(checker, approvalOf(id), "RETURN", "Wrong pay days", otp)).isEqualTo(200);
        Map<String, Object> returned =
                jdbc.queryForMap(
                        "SELECT upload_status, decision_reason FROM hrms_fallback_upload WHERE"
                                + " fallback_upload_id = ?",
                        id);
        assertThat(returned.get("UPLOAD_STATUS")).isEqualTo("RETURNED");
        assertThat(returned.get("DECISION_REASON")).isEqualTo("Wrong pay days");

        submit(maker, id).andExpect(status().isAccepted());
        assertThat(decide(checker, approvalOf(id), "REJECT", "Not an HRMS outage", otp))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT upload_status FROM hrms_fallback_upload WHERE"
                                        + " fallback_upload_id = ?",
                                String.class,
                                id))
                .isEqualTo("REJECTED");
        // Nothing approved: the enablement still waits for its office.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT enablement_status FROM hrms_fallback_enablement WHERE"
                                        + " enablement_id = ?",
                                String.class,
                                enablement))
                .isEqualTo("UPLOADED");

        // The kept source file is downloadable; the upload is listed.
        long source =
                jdbc.queryForObject(
                        "SELECT source_attachment_id FROM hrms_fallback_upload WHERE"
                                + " fallback_upload_id = ?",
                        Long.class,
                        id);
        mvc.perform(userGet(maker, "/v1/hrms-fallback/uploads/" + id + "/files/" + source))
                .andExpect(status().isOk());
        JsonNode uploads = all(maker, "/v1/hrms-fallback/uploads");
        assertThat(texts(uploads, "id")).contains(String.valueOf(id));
    }

    @Test
    void SRS_EH_11_anEnablementIsWithdrawnWithAReasonUntilAnUploadIsApproved() throws Exception {
        long enablement = enable("202409", "ATTENDANCE_LEAVE", List.of("DH001"));
        // After enable(): one administrator session at a time (single-session login).
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/hrms-fallback/enablements/" + enablement + "/withdraw",
                                Map.of("reason", "HRMS is back")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.withdrawnReason").value("HRMS is back"));
        // Withdrawn: no upload, no second withdrawal.
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        upload(maker, enablement, HEADER_A + "105220,01-09-2024,30-09-2024,30,0,0\r\n")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("HFB-409-NOT-ENABLED"));
        mvc.perform(
                        userPost(
                                admin,
                                "/v1/hrms-fallback/enablements/" + enablement + "/withdraw",
                                Map.of("reason", "again")))
                .andExpect(status().isConflict());

        // Once an upload is approved the enablement can no longer be withdrawn.
        long approved = enable("202410", "ATTENDANCE_LEAVE", List.of("DH001", "MR001"));
        long id =
                body(upload(maker, approved, HEADER_A + "105220,01-10-2024,31-10-2024,31,0,0\r\n")
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asLong();
        submit(maker, id).andExpect(status().isAccepted());
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                approvalOf(id),
                                "APPROVE",
                                null,
                                "HRMS_FALLBACK_UPLOAD_APPROVAL"))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT enablement_status FROM hrms_fallback_enablement WHERE"
                                        + " enablement_id = ?",
                                String.class,
                                approved))
                .as("MR001 has not uploaded yet")
                .isEqualTo("UPLOADED");
        mvc.perform(
                        userPost(
                                login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001"),
                                "/v1/hrms-fallback/enablements/" + approved + "/withdraw",
                                Map.of("reason", "too late")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("HFB-409-STATUS"));
    }

    @Test
    void SRS_G_13_theUploadListExportsAsCsvForPayrollAndFinanceNotHr() throws Exception {
        long enablement = enable("202411", "ATTENDANCE_LEAVE", List.of("DH001"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        String batch =
                body(upload(maker, enablement, HEADER_A + "105220,01-11-2024,30-11-2024,30,0,0\r\n")
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("batchNo")
                        .asString();
        String csv = csv(maker, "/v1/hrms-fallback/uploads/export");
        assertThat(csv).startsWith("\"Batch\",\"Fallback\"").contains(batch);
        // Establishment / HR views X-07 but exports nothing (V025_10).
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        mvc.perform(userGet(hr, "/v1/hrms-fallback/uploads/export"))
                .andExpect(status().isForbidden());
    }

    /**
     * SRS-G-46, SRS-NF-3: the screen's tiles count every upload in the scope, not one page; the
     * upload form offers only the enablements still open; a type A upload carries no order copy.
     */
    @Test
    void SRS_G_46_theTilesCountTheWholeScopeAndOnlyOpenEnablementsAreOffered() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode before =
                body(mvc.perform(userGet(maker, "/v1/hrms-fallback/summary")).andReturn());
        long enablement = enable("202412", "ATTENDANCE_LEAVE", List.of("DH001"));
        JsonNode live =
                body(
                        mvc.perform(userGet(maker, "/v1/hrms-fallback/enablements/live"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(live.findValuesAsString("status")).containsOnly("OPEN", "UPLOADED");
        assertThat(live.findValues("id").stream().map(JsonNode::asLong)).contains(enablement);

        long id =
                body(upload(maker, enablement, HEADER_A + "105220,01-12-2024,31-12-2024,31,0,0\r\n")
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asLong();
        assertThat(
                        body(
                                mvc.perform(
                                                userGet(
                                                        maker,
                                                        "/v1/hrms-fallback/uploads/"
                                                                + id
                                                                + "/orders"))
                                        .andExpect(status().isOk())
                                        .andReturn()))
                .isEmpty();
        submit(maker, id).andExpect(status().isAccepted());
        JsonNode pending =
                body(mvc.perform(userGet(maker, "/v1/hrms-fallback/summary")).andReturn());
        assertThat(pending.get("awaitingApproval").asLong())
                .isEqualTo(before.get("awaitingApproval").asLong() + 1);

        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(
                        decide(
                                checker,
                                approvalOf(id),
                                "APPROVE",
                                null,
                                "HRMS_FALLBACK_UPLOAD_APPROVAL"))
                .isEqualTo(200);
        JsonNode approved =
                body(mvc.perform(userGet(maker, "/v1/hrms-fallback/summary")).andReturn());
        assertThat(approved.get("awaitingApproval").asLong())
                .isEqualTo(before.get("awaitingApproval").asLong());
        assertThat(approved.get("reconciliationPending").asLong())
                .isEqualTo(before.get("reconciliationPending").asLong() + 1);
        // Its only office approved, the enablement is no longer offered for an upload.
        JsonNode after =
                body(mvc.perform(userGet(maker, "/v1/hrms-fallback/enablements/live")).andReturn());
        assertThat(after.findValues("id").stream().map(JsonNode::asLong))
                .doesNotContain(enablement);
    }
}
