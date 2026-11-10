package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 14 phases 14a / 14b (plan docs/plan/14-eseva.md): the member's eSeva submission recorded
 * in the Self-Service Submission Log and following its owning module (SRS-17-3, SRS-17-18); the log
 * for payroll staff of the member's office only, never for a member; a support preview recorded as
 * a closed STAFF session with its audit entry, masked and read-only (SRS-17-6, SRS-17-8,
 * SRS-17-10), refused out of scope with a security event (SRS-17-9); the privacy notice
 * acknowledged once (SRS-SEC-9). Member 104882 of Delhi (DH001).
 */
class EsevaIT extends ApiTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.eseva.orchestration.NotificationDispatch dispatch;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private Session member() throws Exception {
        return login("emp.rsharma@fci-demo.test", null, null);
    }

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private long self() {
        return jdbc.queryForObject(
                "SELECT employee_id FROM app_user WHERE login_id = 'emp.rsharma@fci-demo.test'",
                Long.class);
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    @Test
    void SRS_17_18_aMembersSubmissionIsLoggedForTheStaffOfTheOfficeOnly() throws Exception {
        long self = self();
        // SRS-17-12: FCI's wording of the status e-mail (a fixture: none is seeded in production).
        jdbc.update(
                "INSERT INTO notification_template (template_code, channel, subject_en, body_en,"
                    + " effective_from) SELECT 'ESEVA_STATUS', 'EMAIL', 'FCI eSeva - {name}', 'Your"
                    + " {type} {reference} is {status}.', DATE '2026-04-01' FROM dual WHERE NOT"
                    + " EXISTS (SELECT 1 FROM notification_template WHERE template_code ="
                    + " 'ESEVA_STATUS' AND channel = 'EMAIL')");
        HRMS_VALUES.put(self + "|EPS_NUMBER", "EPS-NA-" + (System.nanoTime() % 100000));
        Map<String, Object> body = new HashMap<>();
        body.put("fieldCode", "EPS_NUMBER");
        body.put("effectiveDate", LocalDate.now(IST).minusDays(3).toString());
        Session member = member();
        MvcResult raised =
                mvc.perform(userPost(member, "/v1/hrms-update-requests/mine", body)).andReturn();
        assertThat(status(raised)).as(raised.getResponse().getContentAsString()).isEqualTo(201);
        long request = body(raised).get("id").asLong();
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT submission_type, submission_status, hrms_update_request_id FROM"
                                + " ess_submission WHERE reference_entity_name ="
                                + " 'hrms_update_request' AND reference_entity_id = ?",
                        request);
        assertThat(row.get("SUBMISSION_TYPE")).isEqualTo("HRMS_UPDATE_REQUEST");
        assertThat(row.get("SUBMISSION_STATUS"))
                .as("the I-21 task opened or linked")
                .isEqualTo("ROUTED");
        assertThat(((Number) row.get("HRMS_UPDATE_REQUEST_ID")).longValue()).isEqualTo(request);

        JsonNode log =
                body(
                        mvc.perform(userGet(maker(), "/v1/eseva/submissions?eis=104882&size=50"))
                                .andReturn());
        assertThat(log.toString())
                .contains("HRMS_UPDATE_REQUEST")
                .contains("hrms_update_request #" + request);
        assertThat(status(mvc.perform(userGet(member, "/v1/eseva/submissions")).andReturn()))
                .as("members never see the log")
                .isEqualTo(403);
        JsonNode chennai =
                body(
                        mvc.perform(
                                        userGet(
                                                login(
                                                        "maker.chennai@fci-demo.test",
                                                        "PAYROLL_MAKER",
                                                        "CH001"),
                                                "/v1/eseva/submissions?eis=104882"))
                                .andReturn());
        assertThat(chennai.path("total").asLong()).as("another office's member").isZero();

        // SRS-17-12: the member is told in eSeva and by e-mail (queued, then sent by the batch).
        JsonNode notices =
                body(mvc.perform(userGet(member, "/v1/eseva/notifications/mine")).andReturn());
        assertThat(notices.toString()).contains("ESEVA_SUBMITTED").contains("ESEVA_ROUTED");
        long notice = notices.path("items").get(0).path("id").asLong();
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        member,
                                                        "/v1/eseva/notifications/mine/"
                                                                + notice
                                                                + "/reads",
                                                        Map.of()))
                                        .andReturn()))
                .isEqualTo(204);
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        member,
                                                        "/v1/eseva/notifications/mine/999999999/reads",
                                                        Map.of()))
                                        .andReturn()))
                .isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification WHERE recipient_employee_id = ?"
                                        + " AND channel = 'EMAIL' AND related_entity_name ="
                                        + " 'ess_submission' AND delivery_status = 'QUEUED'",
                                Integer.class,
                                self))
                .isPositive();
        dispatch.run(java.time.Instant.now());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification WHERE recipient_employee_id = ?"
                                        + " AND channel = 'EMAIL' AND delivery_status = 'SENT'",
                                Integer.class,
                                self))
                .isPositive();
    }

    @Test
    void SRS_17_8_aSupportPreviewIsRecordedMaskedAndReadOnlyAndRefusedOutOfScope()
            throws Exception {
        Map<String, Object> body = Map.of("eis", "104882", "reason", "IT: helpdesk ticket 42");
        MvcResult r =
                mvc.perform(userPost(maker(), "/v1/eseva/support-previews", body)).andReturn();
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode preview = body(r);
        assertThat(preview.get("submitEnabled").asBoolean()).isFalse();
        // BR-SEC-001 (SRS l.754, l.802; BMAD TR-035): the last 4 characters of the PAN only.
        String pan =
                jdbc.queryForObject(
                        "SELECT pan_number FROM employee WHERE emp_num = '104882'", String.class);
        assertThat(preview.at("/member/panMasked").asString())
                .isEqualTo("******" + pan.strip().substring(6));
        long session = preview.get("sessionId").asLong();
        Map<String, Object> s =
                jdbc.queryForMap(
                        "SELECT session_owner, submit_actions_enabled, access_reason, closed_at"
                                + " FROM ess_session WHERE session_id = ?",
                        session);
        assertThat(s.get("SESSION_OWNER")).isEqualTo("STAFF");
        assertThat(s.get("SUBMIT_ACTIONS_ENABLED")).isEqualTo("N");
        assertThat(s.get("ACCESS_REASON")).isEqualTo("IT: helpdesk ticket 42");
        assertThat(s.get("CLOSED_AT")).as("FRS l.6958: open until closed").isNull();
        // FRS V0.5 l.6958 (US-GAP-261): the user closes the preview; the closing time is kept,
        // a second close or another user's close reads as not found.
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        maker(),
                                                        "/v1/eseva/support-previews/"
                                                                + session
                                                                + "/closures",
                                                        Map.of()))
                                        .andReturn()))
                .isEqualTo(204);
        Map<String, Object> closed =
                jdbc.queryForMap(
                        "SELECT closed_at, closed_explicitly FROM ess_session WHERE session_id = ?",
                        session);
        assertThat(closed.get("CLOSED_AT")).isNotNull();
        assertThat(closed.get("CLOSED_EXPLICITLY")).isEqualTo("Y");
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        maker(),
                                                        "/v1/eseva/support-previews/"
                                                                + session
                                                                + "/closures",
                                                        Map.of()))
                                        .andReturn()))
                .as("closed once")
                .isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name = 'ess_session'"
                                        + " AND entity_key = ?",
                                Integer.class,
                                String.valueOf(session)))
                .as("SRS-17-8: shown on X-04 (opened and closed)")
                .isEqualTo(2);

        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        int events =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM security_event WHERE event_type ="
                                + " 'OUT_OF_SCOPE_ACCESS'",
                        Integer.class);
        assertThat(
                        status(
                                mvc.perform(userPost(chennai, "/v1/eseva/support-previews", body))
                                        .andReturn()))
                .as("SRS-17-9: out of scope reads as not found")
                .isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM security_event WHERE event_type ="
                                        + " 'OUT_OF_SCOPE_ACCESS'",
                                Integer.class))
                .isGreaterThan(events);
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        maker(),
                                                        "/v1/eseva/support-previews",
                                                        Map.of("eis", "104882", "reason", " ")))
                                        .andReturn()))
                .isEqualTo(400);
        assertThat(
                        status(
                                mvc.perform(userPost(member(), "/v1/eseva/support-previews", body))
                                        .andReturn()))
                .as("a member never previews")
                .isEqualTo(403);
    }

    @Test
    void SRS_SEC_9_thePrivacyNoticeIsAcknowledgedOnce() throws Exception {
        Session member = member();
        JsonNode first =
                body(mvc.perform(userGet(member, "/v1/eseva/privacy-notice/mine")).andReturn());
        MvcResult ack =
                mvc.perform(
                                userPost(
                                        member,
                                        "/v1/eseva/privacy-notice/mine/acknowledgements",
                                        Map.of()))
                        .andReturn();
        assertThat(status(ack)).as(ack.getResponse().getContentAsString()).isEqualTo(200);
        mvc.perform(userPost(member, "/v1/eseva/privacy-notice/mine/acknowledgements", Map.of()));
        assertThat(
                        body(mvc.perform(userGet(member, "/v1/eseva/privacy-notice/mine"))
                                        .andReturn())
                                .get("acknowledged")
                                .asBoolean())
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'eseva_privacy_notice' AND entity_key = ?",
                                Integer.class,
                                String.valueOf(self())))
                .as(first.toString())
                .isOne();
        assertThat(
                        status(
                                mvc.perform(userGet(maker(), "/v1/eseva/privacy-notice/mine"))
                                        .andReturn()))
                .as("staff have no own eSeva record")
                .isEqualTo(403);
    }
}
