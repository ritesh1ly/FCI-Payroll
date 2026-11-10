package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.gov.fci.payroll.general.business.StatutoryDeductions;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;

/**
 * I-23 approval with OTP (SRS-G-21 SUPPLEMENTARY_POST_LOCK, SRS-G-33, SRS-G-34, {@code
 * chk_supp_post_lock_otp}, {@code chk_supp_dup_passed}). The statutory deduction rules of modules
 * 07 / 08 are stood in for by a zero-deduction bean so the flow can be driven end to end; the
 * shipped "not configured" behaviour is {@link SupplementaryIT}'s.
 */
class SupplementaryApprovalIT extends ApiTestSupport {

    private static final String OTP = "SUPPLEMENTARY_POST_LOCK";

    @TestConfiguration
    static class DeductionsConfigured {
        @Bean
        @Primary
        StatutoryDeductions zeroDeductions() {
            return payment -> Optional.of(BigDecimal.ZERO);
        }
    }

    private Session maker() throws Exception {
        return login(SupplementaryIT.MAKER, "PAYROLL_MAKER", "DH001");
    }

    private Session checker() throws Exception {
        return login(SupplementaryIT.CHECKER, "PAYROLL_CHECKER", "DH001");
    }

    /** Records and submits a pending salary of locked July for {@code eis}; returns it. */
    private JsonNode submitted(Session maker, String eis) throws Exception {
        return submitted(maker, eis, "SALARY_WAGE");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.general.api.SupplementaryPayments supplementary;

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.organisation.api.OrgDirectory orgs;

    /**
     * Records and submits a {@code type} payment of locked July for {@code eis}; returns it. A
     * pending salary is captured on I-23; any other type (an OTA) is raised by the module that
     * computes it through {@code SupplementaryPayments.raise} (the screen refuses it with
     * SUP-422-SOURCE), with its order copy, then submitted on I-23 by the same Maker.
     */
    private JsonNode submitted(Session maker, String eis, String type) throws Exception {
        SupplementaryIT.cycle(jdbc, SupplementaryIT.LOCKED, "LOCKED");
        if (!"SALARY_WAGE".equals(type)) {
            long raised =
                    supplementary.raise(
                            SupplementaryIT.delhiMaker(jdbc, orgs),
                            new in.gov.fci.payroll.general.api.SupplementaryPayments.NewPayment(
                                    jdbc.queryForObject(
                                            "SELECT employee_id FROM employee WHERE emp_num = ?",
                                            Long.class,
                                            eis),
                                    SupplementaryIT.LOCKED,
                                    type,
                                    null,
                                    new BigDecimal("1500"),
                                    java.time.LocalDate.of(2026, 8, 10),
                                    null,
                                    null,
                                    new in.gov.fci.payroll.general.api.SupplementaryPayments.Order(
                                            "SANCTION_ORDER",
                                            "DH/SUP/" + type + "/" + eis,
                                            java.time.LocalDate.of(2026, 8, 12),
                                            "AGM (Pay) Delhi",
                                            "order.pdf",
                                            "%PDF-1.7 order"
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .US_ASCII))));
            return body(
                    mvc.perform(
                                    userPost(
                                            maker,
                                            "/v1/supplementary/" + raised + "/submit",
                                            Map.of()))
                            .andExpect(status().isAccepted())
                            .andReturn());
        }
        long id =
                body(mvc.perform(
                                        userRequest(
                                                        maker,
                                                        org.springframework.test.web.servlet.request
                                                                .MockMvcRequestBuilders.multipart(
                                                                        "/v1/supplementary")
                                                                .file(SupplementaryIT.order())
                                                                .param("eis", eis)
                                                                .param("paymentType", type)
                                                                .param(
                                                                        "forPeriodYymm",
                                                                        SupplementaryIT.LOCKED)
                                                                .param(
                                                                        "applicationDate",
                                                                        "2026-08-10")
                                                                .param(
                                                                        "documentType",
                                                                        "SANCTION_ORDER")
                                                                .param(
                                                                        "reference",
                                                                        "DH/SUP/" + type + "/"
                                                                                + eis)
                                                                .param("orderDate", "2026-08-12")
                                                                .param(
                                                                        "issuingAuthority",
                                                                        "AGM (Pay) Delhi"))
                                                .header(
                                                        "Idempotency-Key",
                                                        java.util.UUID.randomUUID().toString()))
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asLong();
        return body(
                mvc.perform(userPost(maker, "/v1/supplementary/" + id + "/submit", Map.of()))
                        .andExpect(status().isAccepted())
                        .andReturn());
    }

    private JsonNode get(Session s, long id) throws Exception {
        return body(
                mvc.perform(userGet(s, "/v1/supplementary/" + id))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    // SC-01-0092 (I-23 main flow l.773): recorded with its order copy, submitted, approved with
    // OTP.
    @Test
    void SRS_G_21_theCheckerApprovesWithOtpAndTheChallengeIsKept() throws Exception {
        Session maker = maker();
        JsonNode p = submitted(maker, "107112");
        long id = p.get("id").asLong();
        assertThat(p.get("status").asString()).isEqualTo("PENDING_CHECKER");

        // Not cancellable while the Checker decides.
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/supplementary/" + id + "/cancel",
                                Map.of("reason", "changed")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SUP-409-STATUS"));

        Session checker = checker();
        long approval = p.get("approvalId").asLong();
        // Without the OTP the decision is refused (428, OTP required); with it, approved.
        assertThat(decide(checker, approval, "APPROVE", null, null)).isEqualTo(428);
        assertThat(decide(checker, approval, "APPROVE", null, OTP)).isEqualTo(200);
        assertThat(get(maker, id).get("status").asString()).isEqualTo("APPROVED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT otp_challenge_id FROM supplementary_payment WHERE"
                                        + " payment_id = ?",
                                Long.class,
                                id))
                .isNotNull();

        // Approved but not yet invoiced to APPS: still cancellable with a reason (SRS-G-37).
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/supplementary/" + id + "/cancel",
                                Map.of("reason", "Order withdrawn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // SC-01-0096: a returned supplementary request is resubmitted; a rejected one is final.
    @Test
    void SRS_G_33_SRS_DM_14_aReturnedRequestIsResubmittedARejectedOneIsFinal() throws Exception {
        Session maker = maker();
        JsonNode p = submitted(maker, "107213");
        long id = p.get("id").asLong();
        Session checker = checker();
        assertThat(
                        decide(
                                checker,
                                p.get("approvalId").asLong(),
                                "RETURN",
                                "Attach the sanction",
                                OTP))
                .isEqualTo(200);
        assertThat(get(maker, id).get("status").asString()).isEqualTo("RETURNED");

        JsonNode again =
                body(
                        mvc.perform(
                                        userPost(
                                                maker,
                                                "/v1/supplementary/" + id + "/submit",
                                                Map.of()))
                                .andExpect(status().isAccepted())
                                .andReturn());
        assertThat(decide(checker, again.get("approvalId").asLong(), "REJECT", "Not payable", OTP))
                .isEqualTo(200);
        assertThat(get(maker, id).get("status").asString()).isEqualTo("REJECTED");
        mvc.perform(userPost(maker, "/v1/supplementary/" + id + "/submit", Map.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SUP-409-STATUS"));
    }

    @Test
    void SRS_G_34_aDuplicateFoundAtApprovalBlocksItForEveryone() throws Exception {
        Session maker = maker();
        JsonNode p = submitted(maker, "107304");
        long id = p.get("id").asLong();
        // While the request waits, the period is found paid by the regular run of record.
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, payable_days, result_status, is_locked)"
                        + " SELECT c.cycle_instance_id, e.location_id, e.employee_id, 'STAFF',"
                        + " '202607', DATE '2026-07-01', 'ACTUAL', 50000, 5000, 45000, 31,"
                        + " 'CURRENT', 'Y' FROM employee e JOIN payroll_cycle_instance c ON"
                        + " c.org_unit_id = e.location_id AND c.payroll_yymm = '202607' WHERE"
                        + " e.emp_num = '107304'");
        assertThat(decide(checker(), p.get("approvalId").asLong(), "APPROVE", null, OTP))
                .isEqualTo(409);
        JsonNode still = get(maker, id);
        assertThat(still.get("status").asString()).isEqualTo("PENDING_CHECKER");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT otp_challenge_id FROM supplementary_payment WHERE"
                                        + " payment_id = ?",
                                Long.class,
                                id))
                .isNull();
    }

    /**
     * SC-01-0099 (I-23 postcondition l.789; SRS-G-33 l.547 "Never reopen the locked result",
     * BR-CYC-017): approving a supplementary payment leaves every locked result of the period as it
     * was (no row added, changed or unlocked), while the payment itself keeps its duplicate-check
     * result, its order copy, the OTP challenge of the approval and its audit trail linked to the
     * approval.
     */
    @Test
    void SC_01_0099_supplementaryApprovalLeavesTheLockedPeriodResultUnchanged() throws Exception {
        SupplementaryIT.cycle(jdbc, SupplementaryIT.LOCKED, "LOCKED");
        // A locked result of a colleague in the same period stands for "the locked result".
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, payable_days, result_status, is_locked)"
                        + " SELECT c.cycle_instance_id, e.location_id, e.employee_id, 'STAFF',"
                        + " '202607', DATE '2026-07-01', 'ACTUAL', 61000, 6100, 54900, 31,"
                        + " 'CURRENT', 'Y' FROM employee e JOIN payroll_cycle_instance c ON"
                        + " c.org_unit_id = e.location_id AND c.payroll_yymm = '202607' WHERE"
                        + " e.emp_num = '105461' AND NOT EXISTS (SELECT 1 FROM payroll_result r"
                        + " WHERE r.employee_id = e.employee_id AND r.payroll_yymm = '202607')");
        List<Map<String, Object>> before = lockedPeriod();
        assertThat(before).as("the period holds a locked result").isNotEmpty();

        Session maker = maker();
        JsonNode p = submitted(maker, "107112", "OTA");
        long id = p.get("id").asLong();
        assertThat(lockedPeriod()).as("unchanged by the submission").isEqualTo(before);
        assertThat(decide(checker(), p.get("approvalId").asLong(), "APPROVE", null, OTP))
                .isEqualTo(200);
        assertThat(get(maker, id).get("status").asString()).isEqualTo("APPROVED");

        assertThat(lockedPeriod())
                .as("the locked result of the period is unchanged")
                .isEqualTo(before);
        Map<String, Object> kept =
                jdbc.queryForMap(
                        "SELECT duplicate_check_status, order_attachment_id, otp_challenge_id"
                                + " FROM supplementary_payment WHERE payment_id = ?",
                        id);
        assertThat(kept.get("DUPLICATE_CHECK_STATUS")).isEqualTo("PASSED");
        assertThat(kept.get("ORDER_ATTACHMENT_ID")).as("the order copy").isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM otp_challenge WHERE" + " otp_challenge_id = ?",
                                String.class,
                                kept.get("OTP_CHALLENGE_ID")))
                .as("the OTP outcome")
                .isEqualTo("SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'supplementary_payment' AND entity_key = ? AND"
                                        + " approval_id = ?",
                                Integer.class,
                                String.format("SUP-%08d", id),
                                p.get("approvalId").asLong()))
                .as("the approval is in the payment's audit trail")
                .isPositive();
    }

    /** Every result row of DH001's July 2026, with the values a reopening would change. */
    private List<Map<String, Object>> lockedPeriod() {
        return jdbc.queryForList(
                "SELECT r.payroll_result_id, r.employee_id, r.run_type, r.gross_earnings,"
                        + " r.total_deductions, r.net_pay, r.result_status, r.is_locked FROM"
                        + " payroll_result r JOIN org_location l ON l.location_id = r.org_unit_id"
                        + " WHERE l.location_code = 'DH001' AND r.payroll_yymm = '202607' ORDER BY"
                        + " r.payroll_result_id");
    }

    /**
     * SRS-G-22 (l.538) / R-OTP, gated action SUPPLEMENTARY_POST_LOCK: the right code entered after
     * the challenge's validity ran out is refused (AUTH-400-OTP), the outcome EXPIRED is kept, and
     * nothing is decided; a fresh challenge then approves.
     */
    @Test
    void SRS_G_22_anExpiredOtpOnSupplementaryPostLockApprovalIsRefused() throws Exception {
        Session maker = maker();
        JsonNode p = submitted(maker, "107405", "OTA");
        long id = p.get("id").asLong();
        Session checker = checker();
        Map.Entry<org.springframework.test.web.servlet.MvcResult, Long> late =
                decideAfterOtpExpiry(checker, p.get("approvalId").asLong(), "APPROVE", null, OTP);
        assertThat(late.getKey().getResponse().getStatus()).isEqualTo(400);
        assertThat(body(late.getKey()).get("errorCode").asString()).isEqualTo("AUTH-400-OTP");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM otp_challenge WHERE otp_challenge_id = ?",
                                String.class,
                                late.getValue()))
                .isEqualTo("EXPIRED");
        assertThat(get(maker, id).get("status").asString()).isEqualTo("PENDING_CHECKER");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT otp_challenge_id FROM supplementary_payment WHERE"
                                        + " payment_id = ?",
                                Long.class,
                                id))
                .as("no challenge kept on the payment")
                .isNull();
        assertThat(decide(checker, p.get("approvalId").asLong(), "APPROVE", null, OTP))
                .isEqualTo(200);
        assertThat(get(maker, id).get("status").asString()).isEqualTo("APPROVED");
    }

    /**
     * SC-01-0145 (X-08 postcondition l.901; DDL otp_challenge comment, R-OTP "never store the
     * code"): every OTP outcome is recorded and the code never is. Wrong codes on the Checker's
     * SUPPLEMENTARY_POST_LOCK challenge are counted until it is LOCKED_OUT, which writes an
     * OTP_LOCKOUT security event for the Checker; a fresh challenge then approves (SUCCESS). No
     * column of either challenge, of the payment's audit rows or of the security event holds the
     * code that was sent.
     */
    @Test
    void SC_01_0145_X08_everyOtpOutcomeIsAuditedWithoutTheCodeAndALockOutWritesASecurityEvent()
            throws Exception {
        Session maker = maker();
        JsonNode p = submitted(maker, "107213", "OTA");
        long approval = p.get("approvalId").asLong();
        Session checker = checker();
        String email =
                jdbc.queryForObject(
                        "SELECT registered_email FROM app_user WHERE login_id = ?",
                        String.class,
                        checker.loginId());
        org.springframework.test.web.servlet.MvcResult first =
                mvc.perform(
                                userPost(
                                        checker,
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(428);
        long locked = body(first).get("args").get(0).asLong();
        String sent = OTP_CODES.get(email + "|" + OTP);
        String wrong =
                (sent.charAt(0) == '9' ? "0" : String.valueOf(sent.charAt(0) - '0' + 1))
                        + sent.substring(1);
        int before = lockouts(checker.loginId(), locked);
        for (int attempt = 0; attempt < 10 && !"LOCKED_OUT".equals(outcome(locked)); attempt++) {
            org.springframework.test.web.servlet.MvcResult refused =
                    mvc.perform(
                                    userPost(
                                            checker,
                                            "/v1/approvals/" + approval + "/decision",
                                            Map.of(
                                                    "decision",
                                                    "APPROVE",
                                                    "otp",
                                                    Map.of("challengeId", locked, "code", wrong))))
                            .andReturn();
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errorCode").asString()).isEqualTo("AUTH-400-OTP");
        }
        assertThat(outcome(locked)).isEqualTo("LOCKED_OUT");
        assertThat(lockouts(checker.loginId(), locked))
                .as("one OTP_LOCKOUT event")
                .isEqualTo(before + 1);
        // Locked out: even the right code is refused on that challenge.
        assertThat(
                        mvc.perform(
                                        userPost(
                                                checker,
                                                "/v1/approvals/" + approval + "/decision",
                                                Map.of(
                                                        "decision",
                                                        "APPROVE",
                                                        "otp",
                                                        Map.of(
                                                                "challengeId",
                                                                locked,
                                                                "code",
                                                                sent))))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(get(maker, p.get("id").asLong()).get("status").asString())
                .isEqualTo("PENDING_CHECKER");

        assertThat(decide(checker, approval, "APPROVE", null, OTP)).isEqualTo(200);
        String approvedWith = OTP_CODES.get(email + "|" + OTP);
        Long success =
                jdbc.queryForObject(
                        "SELECT otp_challenge_id FROM supplementary_payment WHERE payment_id = ?",
                        Long.class,
                        p.get("id").asLong());
        assertThat(outcome(success)).isEqualTo("SUCCESS");

        String stored =
                jdbc.queryForList(
                                        "SELECT * FROM otp_challenge WHERE otp_challenge_id IN (?,"
                                                + " ?)",
                                        locked,
                                        success)
                                .toString()
                        + jdbc.queryForList(
                                        "SELECT * FROM audit_event WHERE entity_name ="
                                                + " 'supplementary_payment' AND entity_key = ?",
                                        String.format("SUP-%08d", p.get("id").asLong()))
                                .toString()
                        + jdbc.queryForList(
                                        "SELECT * FROM security_event WHERE target_entity ="
                                                + " 'otp_challenge' AND target_key = ?",
                                        String.valueOf(locked))
                                .toString();
        assertThat(stored).doesNotContain(sent).doesNotContain(approvedWith);
    }

    private String outcome(long challenge) {
        return jdbc.queryForObject(
                "SELECT outcome FROM otp_challenge WHERE otp_challenge_id = ?",
                String.class,
                challenge);
    }

    private int lockouts(String login, long challenge) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM security_event WHERE event_type = 'OTP_LOCKOUT' AND"
                        + " acting_user = ? AND target_key = ?",
                Integer.class,
                login,
                String.valueOf(challenge));
    }

    @Test
    void SRS_G_33_theRequestListShowsOnlyTheMakersOffice() throws Exception {
        Session maker = maker();
        JsonNode p = submitted(maker, "107405");
        JsonNode list = all(maker, "/v1/supplementary");
        assertThat(texts(list, "reference")).contains(p.get("reference").asString());
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        JsonNode theirs = all(kolkata, "/v1/supplementary");
        assertThat(texts(theirs, "reference")).doesNotContain(p.get("reference").asString());
        // Another office's record reads as unknown (404, owner 2026-10-24; OWASP A01).
        mvc.perform(userGet(kolkata, "/v1/supplementary/" + p.get("id").asLong()))
                .andExpect(status().isNotFound());
        JsonNode tiles =
                body(
                        mvc.perform(userGet(maker, "/v1/supplementary/tiles"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(tiles.get("pendingChecker").asLong()).isPositive();
    }
}
