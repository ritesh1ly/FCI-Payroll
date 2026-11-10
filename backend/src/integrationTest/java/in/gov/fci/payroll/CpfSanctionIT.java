package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The CPF withdrawal chain end to end (SRS-6-4, UC-05; SRS-6-14, SRS-6-26, SRS-6-37, SRS-6-38,
 * SRS-12-9; plan 07 D-14): the member raises it on E-01, the Unit CPF Administrator (DH001)
 * verifies it, the North Zone CPF Division recommends it, the CPF Sanctioning Authority sanctions
 * it with OTP. Member 104882 (emp.rsharma) has the demo UAN / KYC of seed V036_9.
 *
 * <p>The MGW basis (OQ-3) and the pay (SRS-6-24) are not configured, so the test purposes take the
 * one limit the seeds can compute - a fraction of the balance - as their configuration.
 */
class CpfSanctionIT extends ApiTestSupport {

    private static final String EIS = "104882";
    private static final byte[] PDF = "%PDF-1.7 certificate".getBytes(StandardCharsets.US_ASCII);
    private static final String TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

    private Session member() throws Exception {
        return login("emp.rsharma@fci-demo.test", null, null);
    }

    private Session unit() throws Exception {
        return login("cpf.unit.delhi@fci-demo.test", "UNIT_CPF_ADMIN", "DH001");
    }

    private Session division() throws Exception {
        return login("cpf.north@fci-demo.test", "CPF_DIVISION", "NA11");
    }

    private Session authority() throws Exception {
        return login("cpf.sanction.north@fci-demo.test", "CPF_SANCTIONING_AUTHORITY", "NA11");
    }

    private long memberId() {
        return jdbc.queryForObject(
                "SELECT e.employee_id FROM employee e JOIN org_zone z ON z.zone_id ="
                        + " e.parent_zone_id WHERE e.emp_num = ? AND z.zone_code = 'NA11'",
                Long.class,
                EIS);
    }

    /** An approved purpose with a mandatory certificate (proposed and approved on M-19). */
    private void purpose(String code, String category, String limit, boolean property)
            throws Exception {
        purpose(code, category, limit, property, false);
    }

    private void purpose(
            String code, String category, String limit, boolean property, boolean noCasteDispute)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "IT sanction purpose " + code);
        body.put("category", category);
        body.put("limitRuleKey", limit);
        body.put("noCasteDispute", noCasteDispute);
        body.put("propertyDetails", property);
        body.put("effectiveFrom", TODAY);
        body.put("confirmed", true);
        body.put(
                "documents",
                List.of(
                        Map.of(
                                "documentType",
                                "CERTIFICATE",
                                "mandatory",
                                true,
                                "tier",
                                "UNIT_CPF_ADMIN")));
        body.put("reason", "IT: purpose for the sanction chain");
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        MvcResult proposed =
                mvc.perform(userPost(admin, "/v1/cpf/withdrawal-purposes", body)).andReturn();
        assertThat(proposed.getResponse().getStatus())
                .as(proposed.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(
                        decide(
                                login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001"),
                                body(proposed).path("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
    }

    private MvcResult request(
            Session s, String purpose, String amount, String address, boolean document)
            throws Exception {
        Map<String, Object> form = new HashMap<>();
        form.put("purposeCode", purpose);
        form.put("amount", amount);
        if (address != null) {
            form.put("propertyAddress", address);
            form.put("propertyDescription", "Two rooms, ground floor");
        }
        MockMultipartHttpServletRequestBuilder r = multipart("/v1/cpf/sanctions");
        if (document) {
            // The authority carries a comma on purpose: it must stay one value.
            form.put(
                    "documents",
                    List.of(
                            Map.of(
                                    "documentType", "CERTIFICATE",
                                    "reference", "CERT/IT/1",
                                    "documentDate", "2026-09-01",
                                    "issuingAuthority", "Registrar, Delhi")));
            r.file(new MockMultipartFile("file", "certificate.pdf", "application/pdf", PDF));
        }
        r.file(
                new MockMultipartFile(
                        "request",
                        "",
                        MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(form)));
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private MvcResult tier(Session s, long id, String step, String decision, String remark)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("decision", decision);
        body.put("remark", remark);
        return mvc.perform(userPost(s, "/v1/cpf/sanctions/" + id + "/" + step, body)).andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    /** A balance the test controls: the member's latest ledger year (far in the future). */
    private void ledger(String employee, String employer, String vpf) {
        int set =
                jdbc.update(
                        "UPDATE employee_pf_header SET closing_balance_employee = ?,"
                                + " closing_balance_employer = ?, closing_balance_vpf = ? WHERE"
                                + " employee_id = ? AND fin_year = '2398-99'",
                        new BigDecimal(employee),
                        new BigDecimal(employer),
                        new BigDecimal(vpf),
                        memberId());
        if (set > 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO employee_pf_header (employee_id, fin_year, zone_id,"
                    + " opening_balance_employee, opening_balance_employer, opening_balance_vpf,"
                    + " closing_balance_employee, closing_balance_employer, closing_balance_vpf,"
                    + " opening_balance_source) VALUES (?, '2398-99', (SELECT zone_id FROM org_zone"
                    + " WHERE zone_code = 'NA11'), ?, ?, ?, ?, ?, ?, 'MIGRATION')",
                memberId(),
                new BigDecimal(employee),
                new BigDecimal(employer),
                new BigDecimal(vpf),
                new BigDecimal(employee),
                new BigDecimal(employer),
                new BigDecimal(vpf));
    }

    @Test
    void SRS_6_4_threeTiersSanctionACappedAdvanceWithItsNumberAndNoSecondAdvanceMeanwhile()
            throws Exception {
        // Another test may have left the member a sanctioned advance: it is paid, so a new one is
        // allowed. OPEN-QUESTION: Q-07-4 (BMAD TR-018) - whether a paid advance still being repaid
        // is "active" (BR-CPF-030) is FCI's; the fixture only clears the way for this test.
        jdbc.update(
                "UPDATE pf_sanction SET workflow_stage = 'PAID' WHERE employee_id = ? AND"
                        + " sanction_type = 'ADVANCE' AND workflow_stage NOT IN"
                        + " ('PAID','REJECTED','RETURNED','CANCELLED')",
                memberId());
        purpose("IT_SANCTION_ADV", "ADVANCE", "CPF.LIMIT.BALANCE.BEFORE_RETIREMENT", false);
        ledger("10000.00", "6000.00", "2000.00");

        // SRS-6-14: submission is blocked naming the missing checklist document.
        assertThat(code(request(member(), "IT_SANCTION_ADV", "50000", null, false)))
                .isEqualTo("CPF-422-DOCUMENTS");
        MvcResult raised = request(member(), "IT_SANCTION_ADV", "50000", null, true);
        assertThat(raised.getResponse().getStatus())
                .as(raised.getResponse().getContentAsString())
                .isEqualTo(201);
        long id = body(raised).path("id").asLong();
        assertThat(body(raised).path("stage").asString()).isEqualTo("UNIT_VERIFICATION");
        assertThat(body(raised).path("documents")).hasSize(1);
        // The member sees it on E-01.
        assertThat(
                        body(mvc.perform(userGet(member(), "/v1/cpf/sanctions/mine")).andReturn())
                                .findValues("id")
                                .stream()
                                .map(JsonNode::asLong))
                .contains(id);

        // Tier 1: the Unit CPF Administrator forwards; the CPF Division cannot act at tier 1.
        assertThat(tier(division(), id, "verify", "FORWARD", null).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(tier(division(), id, "recommend", "FORWARD", null).getResponse().getStatus())
                .isEqualTo(409);
        MvcResult forwarded =
                tier(unit(), id, "verify", "FORWARD", "UAN, KYC and certificate verified");
        assertThat(forwarded.getResponse().getStatus())
                .as(forwarded.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(forwarded).path("stage").asString()).isEqualTo("ZONAL_HQ_VALIDATION");

        // Tier 2: the ceiling is the least of the purpose's limit and the advance cap 0.5 x
        // (employee + VPF). The purpose here takes the 0.9 x balance limit only because the MGW
        // basis is unseeded (OQ-3); SRS-6-2's advance ceiling of 12 x MGW is
        // SRS_6_2_anAdvanceIsTheLeastOfItsMgwMultipleAndHalfTheOwnShare (BMAD TR-015).
        MvcResult recommended = tier(division(), id, "recommend", "FORWARD", "Eligible; capped");
        assertThat(recommended.getResponse().getStatus())
                .as(recommended.getResponse().getContentAsString())
                .isEqualTo(202);
        JsonNode r = body(recommended);
        BigDecimal ceiling =
                new BigDecimal("18000.00")
                        .multiply(new BigDecimal("0.9"))
                        .min(new BigDecimal("12000.00").multiply(new BigDecimal("0.5")))
                        .setScale(2, RoundingMode.DOWN);
        assertThat(r.path("stage").asString()).isEqualTo("COMPETENT_AUTHORITY");
        assertThat(r.path("ceiling").decimalValue()).isEqualByComparingTo(ceiling);
        assertThat(r.path("sanctioned").decimalValue()).isEqualByComparingTo(ceiling);
        assertThat(r.path("capped").asBoolean()).isTrue();

        // Tier 3: the CPF Sanctioning Authority sanctions with OTP; the number is
        // month/year/zone/sequence.
        long approval = r.path("approvalId").asLong();
        assertThat(decide(authority(), approval, "APPROVE", null, null)).isEqualTo(428);
        assertThat(decide(authority(), approval, "APPROVE", null, "CPF_SANCTION_FINAL_PAYMENT"))
                .isEqualTo(200);
        JsonNode sanctioned =
                body(mvc.perform(userGet(division(), "/v1/cpf/sanctions/" + id)).andReturn());
        assertThat(sanctioned.path("stage").asString()).isEqualTo("SANCTIONED");
        LocalDate today = LocalDate.parse(TODAY);
        assertThat(sanctioned.path("sanctionNumber").asString())
                .matches(
                        String.format(
                                "%02d/%d/NA11/\\d{5}", today.getMonthValue(), today.getYear()));
        assertThat(sanctioned.path("tiers").findValuesAsString("action"))
                .containsExactly("FORWARDED", "RECOMMENDED", "SANCTIONED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT otp_challenge_id FROM cpf_sanction_tier WHERE sanction_id ="
                                        + " ? AND tier_no = 3",
                                Long.class,
                                id))
                .isNotNull();
        // SRS-6-37: no new advance while this one is sanctioned and not paid; the register lists
        // it.
        assertThat(code(request(member(), "IT_SANCTION_ADV", "100", null, true)))
                .isEqualTo("CPF-409-ACTIVE-ADVANCE");
        assertThat(
                        all(division(), "/v1/cpf/sanctions?stage=SANCTIONED")
                                .findValues("id")
                                .stream()
                                .map(JsonNode::asLong))
                .contains(id);
    }

    /** Tier 1 attaches one document to the request (SRS-6-14). */
    private MvcResult attachAtUnit(Session s, long id, String type) throws Exception {
        MockMultipartHttpServletRequestBuilder r =
                multipart("/v1/cpf/sanctions/" + id + "/documents");
        r.file(new MockMultipartFile("file", "certificate.pdf", "application/pdf", PDF));
        r.file(
                new MockMultipartFile(
                        "document",
                        "",
                        MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(
                                Map.of(
                                        "documentType", type,
                                        "documentDate", "2026-09-01",
                                        "issuingAuthority", "Tehsildar, Delhi"))));
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    @Test
    void SRS_6_14_theUnitAttachesTheNoCasteDisputeCertificateWhereThePurposeNeedsIt()
            throws Exception {
        purpose("IT_SANCTION_NCD", "PART_FINAL", "CPF.LIMIT.MGW.HOUSE", false, true);
        MvcResult raised = request(member(), "IT_SANCTION_NCD", "40000", null, true);
        assertThat(raised.getResponse().getStatus())
                .as(raised.getResponse().getContentAsString())
                .isEqualTo(201);
        long id = body(raised).path("id").asLong();
        // US-GAP-28: not forwarded without the certificate, which the error names.
        MvcResult blocked = tier(unit(), id, "verify", "FORWARD", null);
        assertThat(code(blocked)).isEqualTo("CPF-422-DOCUMENTS");
        assertThat(blocked.getResponse().getContentAsString()).contains("NO_CASTE_DISPUTE");
        // Only that certificate, and only by the unit.
        assertThat(code(attachAtUnit(unit(), id, "CERTIFICATE"))).isEqualTo("CPF-422-DOCUMENTS");
        assertThat(attachAtUnit(division(), id, "NO_CASTE_DISPUTE").getResponse().getStatus())
                .isEqualTo(403);
        MvcResult attached = attachAtUnit(unit(), id, "NO_CASTE_DISPUTE");
        assertThat(attached.getResponse().getStatus())
                .as(attached.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(attached).path("documents")).hasSize(2);
        assertThat(body(tier(unit(), id, "verify", "FORWARD", null)).path("stage").asString())
                .isEqualTo("ZONAL_HQ_VALIDATION");
        // Leaves the chain so no other test counts it.
        tier(division(), id, "recommend", "RETURN", "IT: done");
        mvc.perform(
                userPost(
                        member(),
                        "/v1/cpf/sanctions/" + id + "/withdraw",
                        Map.of("reason", "IT: done")));
    }

    @Test
    void SRS_12_9_aReturnedRequestStartsANewPassAndAnUnconfiguredLimitStopsAtTier2()
            throws Exception {
        purpose("IT_SANCTION_HOUSE", "PART_FINAL", "CPF.LIMIT.MGW.HOUSE", true);
        // BR-CPF-039: the house purpose needs the property details.
        assertThat(code(request(member(), "IT_SANCTION_HOUSE", "90000", null, true)))
                .isEqualTo("CPF-422-PROPERTY");
        long id =
                body(request(
                                member(),
                                "IT_SANCTION_HOUSE",
                                "90000",
                                "Plot 4, Sector 9, Delhi",
                                true))
                        .path("id")
                        .asLong();

        // Tier 1 returns it to the member with the reason; a return without one is refused.
        assertThat(tier(unit(), id, "verify", "RETURN", " ").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(
                        body(tier(unit(), id, "verify", "RETURN", "Sale deed unreadable"))
                                .path("stage")
                                .asString())
                .isEqualTo("RETURNED");
        // The member resubmits: a new pass, so the same unit administrator acts again.
        MvcResult again =
                mvc.perform(
                                userPost(
                                        member(),
                                        "/v1/cpf/sanctions/" + id + "/resubmit",
                                        Map.of(
                                                "amount",
                                                "85000",
                                                "propertyAddress",
                                                "Plot 4, Sector 9, Delhi",
                                                "propertyDescription",
                                                "Two rooms")))
                        .andReturn();
        assertThat(again.getResponse().getStatus())
                .as(again.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(tier(unit(), id, "verify", "FORWARD", null)).path("stage").asString())
                .isEqualTo("ZONAL_HQ_VALIDATION");

        // D-15: the MGW basis is not configured (OQ-3): tier 2 cannot work out the ceiling.
        assertThat(code(tier(division(), id, "recommend", "FORWARD", null)))
                .isEqualTo("CPF-422-LIMIT-NOT-CONFIGURED");
        // Tier 2 returns it to the unit; the member then withdraws it (a status, never a delete).
        assertThat(
                        body(tier(division(), id, "recommend", "RETURN", "Await the MGW basis"))
                                .path("stage")
                                .asString())
                .isEqualTo("UNIT_VERIFICATION");
        MvcResult withdrawn =
                mvc.perform(
                                userPost(
                                        member(),
                                        "/v1/cpf/sanctions/" + id + "/withdraw",
                                        Map.of("reason", "No longer needed")))
                        .andReturn();
        assertThat(body(withdrawn).path("stage").asString()).isEqualTo("CANCELLED");
        assertThat(code(tier(unit(), id, "verify", "FORWARD", null)))
                .isEqualTo("CPF-409-SANCTION-STAGE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM cpf_claim_history h JOIN cpf_claim c ON"
                                        + " c.claim_id = h.claim_id WHERE c.sanction_id = ?",
                                Integer.class,
                                id))
                .isGreaterThanOrEqualTo(5);
    }

    private int sspRequests() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM cpf_ssp_sync_log WHERE employee_id = ? AND sync_trigger ="
                        + " 'REQUEST' AND sync_status = 'SKIPPED'",
                Integer.class,
                memberId());
    }

    /**
     * US-GAP-164 / 165 / 167 (SRS l.2260-2261; FRS V0.5 §13.4 Steps 2-3, Exception Handling, Audit
     * Process): a tier-1 return goes to the member with its reason, a tier-2 return to the unit, a
     * tier-2 rejection ends the request with its reason; the member sees both reasons on E-01, the
     * return and the rejection are queued for the CPF SSP (SKIPPED: no interface yet), and every
     * action audits the purpose category and the documents on the request.
     */
    @Test
    void SRS_6_4_aReturnOrATier2RejectionCarriesItsReasonToTheMemberAndTheCpfSsp()
            throws Exception {
        purpose("IT_SANCTION_REJ", "PART_FINAL", "CPF.LIMIT.MGW.HOUSE", false);
        long id =
                body(request(member(), "IT_SANCTION_REJ", "40000", null, true)).path("id").asLong();
        int before = sspRequests();
        // Only tier 2 rejects.
        assertThat(code(tier(unit(), id, "verify", "REJECT", "Not eligible")))
                .isEqualTo("CPF-422-TIER1-REJECT");
        assertThat(
                        tier(unit(), id, "verify", "RETURN", "Certificate unreadable")
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(sspRequests()).isEqualTo(before + 1);
        assertThat(
                        mvc.perform(
                                        userPost(
                                                member(),
                                                "/v1/cpf/sanctions/" + id + "/resubmit",
                                                Map.of("amount", "40000")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(tier(unit(), id, "verify", "FORWARD", null).getResponse().getStatus())
                .isEqualTo(200);
        // A rejection without its reason is refused by name and changes nothing; with it the
        // request ends.
        MvcResult noReason = tier(division(), id, "recommend", "REJECT", " ");
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(noReason)).isEqualTo("WF-400-REASON");
        assertThat(
                        body(mvc.perform(userGet(division(), "/v1/cpf/sanctions/" + id))
                                        .andReturn())
                                .path("stage")
                                .asString())
                .isEqualTo("ZONAL_HQ_VALIDATION");
        MvcResult rejected =
                tier(division(), id, "recommend", "REJECT", "Fewer than 5 years of membership");
        assertThat(rejected.getResponse().getStatus())
                .as(rejected.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(body(rejected).path("stage").asString()).isEqualTo("REJECTED");
        assertThat(sspRequests()).isEqualTo(before + 2);
        JsonNode mine = body(mvc.perform(userGet(member(), "/v1/cpf/sanctions/mine")).andReturn());
        JsonNode own = null;
        for (JsonNode n : mine) {
            if (n.path("id").asLong() == id) {
                own = n;
            }
        }
        assertThat(own).isNotNull();
        assertThat(own.path("tiers").findValuesAsString("remark"))
                .contains("Certificate unreadable", "Fewer than 5 years of membership");
        // Nothing more happens to a rejected request.
        assertThat(code(tier(division(), id, "recommend", "FORWARD", null)))
                .isEqualTo("CPF-409-SANCTION-STAGE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name = 'pf_sanction'"
                                        + " AND entity_key = ? AND field_name = 'purpose_category'"
                                        + " AND new_value = 'PART_FINAL'",
                                Integer.class,
                                String.valueOf(id)))
                .isGreaterThanOrEqualTo(3);
    }

    /**
     * UC-05: "If ledger recomputation would make the balance negative, payment is blocked for
     * manual review": a balance that fell after tier 2 ends at PAYMENT_BLOCKED, never SANCTIONED.
     */
    @Test
    void SRS_6_4_aSanctionTheBalanceCannotCarryIsBlockedForReview() throws Exception {
        jdbc.update(
                "UPDATE pf_sanction SET workflow_stage = 'PAID' WHERE employee_id = ? AND"
                        + " sanction_type = 'ADVANCE' AND workflow_stage NOT IN"
                        + " ('PAID','REJECTED','RETURNED','CANCELLED')",
                memberId());
        purpose("IT_SANCTION_BLOCK", "ADVANCE", "CPF.LIMIT.BALANCE.BEFORE_RETIREMENT", false);
        ledger("10000.00", "6000.00", "2000.00");
        long id =
                body(request(member(), "IT_SANCTION_BLOCK", "3000", null, true))
                        .path("id")
                        .asLong();
        assertThat(tier(unit(), id, "verify", "FORWARD", null).getResponse().getStatus())
                .isEqualTo(200);
        MvcResult recommended =
                tier(division(), id, "recommend", "FORWARD", "Eligible within the limit");
        assertThat(recommended.getResponse().getStatus())
                .as(recommended.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(recommended).path("approvalId").asLong();
        // The balance falls before the Competent Authority decides.
        ledger("100.00", "50.00", "0.00");
        try {
            assertThat(decide(authority(), approval, "APPROVE", null, "CPF_SANCTION_FINAL_PAYMENT"))
                    .isEqualTo(200);
            JsonNode after =
                    body(mvc.perform(userGet(division(), "/v1/cpf/sanctions/" + id)).andReturn());
            assertThat(after.path("stage").asString()).isEqualTo("PAYMENT_BLOCKED");
            assertThat(after.path("sanctionNumber").asString()).isNotBlank();
        } finally {
            ledger("10000.00", "6000.00", "2000.00");
        }
    }

    /**
     * Wave D review 9 (UC-05, SRS-G-13): a request the member withdraws while it waits for the
     * Competent Authority leaves that inbox in the same transaction: its approval item is
     * WITHDRAWN, the inbox task COMPLETED, and an approval of it afterwards is refused
     * (WF-409-STATE).
     */
    @Test
    void UC_05_aRequestWithdrawnAtTier3LeavesTheInboxAndCanNoLongerBeSanctioned() throws Exception {
        jdbc.update(
                "UPDATE pf_sanction SET workflow_stage = 'PAID' WHERE employee_id = ? AND"
                        + " sanction_type = 'ADVANCE' AND workflow_stage NOT IN"
                        + " ('PAID','REJECTED','RETURNED','CANCELLED')",
                memberId());
        purpose("IT_SANCTION_WD3", "ADVANCE", "CPF.LIMIT.BALANCE.BEFORE_RETIREMENT", false);
        ledger("10000.00", "6000.00", "2000.00");
        long id =
                body(request(member(), "IT_SANCTION_WD3", "3000", null, true)).path("id").asLong();
        assertThat(tier(unit(), id, "verify", "FORWARD", null).getResponse().getStatus())
                .isEqualTo(200);
        MvcResult recommended = tier(division(), id, "recommend", "FORWARD", "Eligible");
        assertThat(recommended.getResponse().getStatus())
                .as(recommended.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(recommended).path("approvalId").asLong();
        assertThat(
                        body(mvc.perform(
                                                userPost(
                                                        member(),
                                                        "/v1/cpf/sanctions/" + id + "/withdraw",
                                                        Map.of("reason", "No longer needed")))
                                        .andReturn())
                                .path("stage")
                                .asString())
                .isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("WITHDRAWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT item_status FROM workflow_inbox_item WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE approval_id = ? AND"
                                        + " new_value = 'WITHDRAWN'",
                                Long.class,
                                approval))
                .isEqualTo(1L);
        assertThat(decide(authority(), approval, "APPROVE", null, "CPF_SANCTION_FINAL_PAYMENT"))
                .isEqualTo(409);
    }

    @Test
    void SRS_22_21_aRequestIsSeenOnlyByItsMemberAndTheTiersInScope() throws Exception {
        purpose("IT_SANCTION_SCOPE", "ADVANCE", "CPF.LIMIT.BALANCE.BEFORE_RETIREMENT", false);
        jdbc.update(
                "UPDATE pf_sanction SET workflow_stage = 'PAID' WHERE employee_id = ? AND"
                        + " sanction_type = 'ADVANCE' AND workflow_stage NOT IN"
                        + " ('PAID','REJECTED','RETURNED','CANCELLED')",
                memberId());
        long id =
                body(request(member(), "IT_SANCTION_SCOPE", "1000", null, true))
                        .path("id")
                        .asLong();
        // The CPF Division of another zone neither lists nor opens it; another member cannot
        // withdraw it.
        Session west = login("rbac.cpf.west@fci-demo.test", "CPF_DIVISION", "ZO-W");
        assertThat(all(west, "/v1/cpf/sanctions").findValues("id").stream().map(JsonNode::asLong))
                .doesNotContain(id);
        assertThat(
                        mvc.perform(userGet(west, "/v1/cpf/sanctions/" + id))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        // Another member (emp.mverma must change the password first, AuthenticationFlowIT).
        Session other = login("rbac.staff.eseva@fci-demo.test", "EMPLOYEE_ESEVA", null);
        MvcResult foreign =
                mvc.perform(
                                userPost(
                                        other,
                                        "/v1/cpf/sanctions/" + id + "/withdraw",
                                        Map.of("reason", "x")))
                        .andReturn();
        assertThat(foreign.getResponse().getStatus())
                .as(foreign.getResponse().getContentAsString())
                .isEqualTo(404);
        // OWASP A01: nor does another member read its document, which its own member does.
        long fileId =
                body(mvc.perform(userGet(division(), "/v1/cpf/sanctions/" + id)).andReturn())
                        .path("documents")
                        .path(0)
                        .path("id")
                        .asLong();
        String file = "/v1/cpf/sanctions/" + id + "/documents/" + fileId;
        // ... and cannot tell it from a request that does not exist (W-5, no existence oracle).
        assertThat(mvc.perform(userGet(other, file)).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(userGet(member(), file)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        // A member holds no I-04: the register is refused.
        assertThat(
                        mvc.perform(userGet(member(), "/v1/cpf/sanctions"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        // A forged stage filter or page size is refused.
        assertThat(
                        mvc.perform(userGet(division(), "/v1/cpf/sanctions?stage=PAID_TWICE"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        // The member withdraws it before the sanction.
        assertThat(
                        body(mvc.perform(
                                                userPost(
                                                        member(),
                                                        "/v1/cpf/sanctions/" + id + "/withdraw",
                                                        Map.of("reason", "Raised in error")))
                                        .andReturn())
                                .path("stage")
                                .asString())
                .isEqualTo("CANCELLED");
    }

    /**
     * BR-CPF-010, SRS-6-55: the part-final count includes the member's request still in the chain,
     * so two requests at once cannot both be sanctioned past the limit of six; a withdrawn request
     * no longer counts. A member of its own (coverage seed V037_9) with five part-finals paid.
     */
    @Test
    void BR_CPF_010_aPartFinalStillInTheChainCountsSoTheLimitCannotBeExceeded() throws Exception {
        purpose("IT_SANCTION_PFCOUNT", "PART_FINAL", "CPF.LIMIT.MGW.HOUSE", false);
        String login = "eseva.ld001@fci-demo.test";
        Long employee =
                jdbc.queryForObject(
                        "SELECT employee_id FROM app_user WHERE login_id = ?", Long.class, login);
        // Five years' membership (BR-CPF-009) and five part-finals already paid.
        jdbc.update(
                "UPDATE employee SET eps_join_date = DATE '2000-07-01' WHERE employee_id = ?",
                employee);
        for (int i = 0; i < 5; i++) {
            jdbc.update(
                    "INSERT INTO pf_sanction (employee_id, sanction_type, requested_amount,"
                            + " sanctioned_amount, sanction_date, workflow_stage) VALUES (?,"
                            + " 'PART_FINAL', 100, 100, DATE '2020-01-01', 'PAID')",
                    employee);
        }
        Session own = login(login, null, null);
        MvcResult sixth = request(own, "IT_SANCTION_PFCOUNT", "1000", null, true);
        assertThat(sixth.getResponse().getStatus())
                .as(sixth.getResponse().getContentAsString())
                .isEqualTo(201);
        // The sixth is only in the chain, yet a seventh is refused now, not at tier 3.
        assertThat(code(request(own, "IT_SANCTION_PFCOUNT", "1000", null, true)))
                .isEqualTo("CPF-422-WITHDRAWAL-COUNT");
        // Withdrawn, it no longer counts: a new request goes.
        MvcResult withdrawn =
                mvc.perform(
                                userPost(
                                        own,
                                        "/v1/cpf/sanctions/"
                                                + body(sixth).path("id").asLong()
                                                + "/withdraw",
                                        Map.of("reason", "Raised twice by mistake")))
                        .andReturn();
        assertThat(withdrawn.getResponse().getStatus())
                .as(withdrawn.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(withdrawn).path("stage").asString()).isEqualTo("CANCELLED");
        MvcResult again = request(own, "IT_SANCTION_PFCOUNT", "1000", null, true);
        assertThat(again.getResponse().getStatus())
                .as(again.getResponse().getContentAsString())
                .isEqualTo(201);
    }

    /**
     * SRS-6-37 / BR-CPF-030 (one active advance), BR-CPF-010 (US-GAP-182, concurrent case): the
     * member sends the same advance twice at once (two tabs, a double click). The requests are
     * checked one after the other on the member's row lock, so exactly one is raised and the other
     * is refused CPF-409-ACTIVE-ADVANCE; only one advance is in the chain afterwards.
     */
    @Test
    void BR_CPF_010_SRS_6_37_twoConcurrentAdvanceRequestsForOneMemberOpenOnlyOne()
            throws Exception {
        noOpenAdvance();
        purpose("IT_SANCTION_RACE", "ADVANCE", "CPF.LIMIT.BALANCE.BEFORE_RETIREMENT", false);
        ledger("10000.00", "6000.00", "2000.00");
        Session own = member();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> both = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                both.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return request(own, "IT_SANCTION_RACE", "1000", null, true);
                                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (Future<MvcResult> f : both) {
                MvcResult r = f.get(60, TimeUnit.SECONDS);
                statuses.add(r.getResponse().getStatus());
                if (r.getResponse().getStatus() != 201) {
                    codes.add(code(r));
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(codes).containsExactly("CPF-409-ACTIVE-ADVANCE");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM pf_sanction WHERE employee_id = ? AND"
                                            + " sanction_type = 'ADVANCE' AND workflow_stage NOT"
                                            + " IN ('PAID','REJECTED','RETURNED','CANCELLED')",
                                    Integer.class,
                                    memberId()))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
            noOpenAdvance();
        }
    }

    /**
     * SRS-SEC-4 / R-PII (I-04 field table: the UAN the unit verifies and the Aadhaar "as its last
     * four"): neither the request's detail, the register row nor the member's own E-01 list carries
     * the member's full Aadhaar, PAN or bank account; the Aadhaar shows as XXXX-XXXX-nnnn only.
     */
    @Test
    void SRS_SEC_4_aSanctionShowsTheAadhaarAsItsLastFourAndNeverThePanOrBankAccount()
            throws Exception {
        noOpenAdvance();
        purpose("IT_SANCTION_PII", "ADVANCE", "CPF.LIMIT.BALANCE.BEFORE_RETIREMENT", false);
        ledger("10000.00", "6000.00", "2000.00");
        try {
            MvcResult raised = request(member(), "IT_SANCTION_PII", "1000", null, true);
            assertThat(raised.getResponse().getStatus())
                    .as(raised.getResponse().getContentAsString())
                    .isEqualTo(201);
            long id = body(raised).path("id").asLong();
            Map<String, Object> pii =
                    jdbc.queryForMap(
                            "SELECT aadhaar_number, pan_number, bank_account_number FROM employee"
                                    + " WHERE employee_id = ?",
                            memberId());
            String detail =
                    mvc.perform(userGet(unit(), "/v1/cpf/sanctions/" + id))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            String register = all(unit(), "/v1/cpf/sanctions?stage=UNIT_VERIFICATION").toString();
            String mine =
                    mvc.perform(userGet(member(), "/v1/cpf/sanctions/mine"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            String aadhaar = (String) pii.get("AADHAAR_NUMBER");
            assertThat(aadhaar).as("the demo member has an Aadhaar (seed V036_9)").isNotBlank();
            String digits = aadhaar.replaceAll("\\D", "");
            for (String text : List.of(detail, register, mine)) {
                assertThat(text).doesNotContain(digits).doesNotContain(aadhaar);
                for (String other : new String[] {"PAN_NUMBER", "BANK_ACCOUNT_NUMBER"}) {
                    Object value = pii.get(other);
                    if (value != null && !value.toString().isBlank()) {
                        assertThat(text).as(other).doesNotContain(value.toString());
                    }
                }
            }
            assertThat(
                            body(mvc.perform(userGet(unit(), "/v1/cpf/sanctions/" + id))
                                            .andReturn())
                                    .path("aadhaarMasked")
                                    .asString())
                    .isEqualTo("XXXX-XXXX-" + digits.substring(digits.length() - 4));
        } finally {
            noOpenAdvance();
        }
    }

    /** A test-only version of CPF.MGW (OQ-3: unseeded) in force today; its id. */
    private long mgw(String value) {
        jdbc.update(
                "INSERT INTO rule_version (rule_id, version_no, effective_from, effective_to,"
                        + " scalar_value, approval_state, is_blocked, created_by) SELECT d.rule_id,"
                        + " (SELECT COALESCE(MAX(v.version_no), 0) + 1 FROM rule_version v WHERE"
                        + " v.rule_id = d.rule_id), ?, ?, ?, 'APPROVED', 'N', 'it' FROM"
                        + " rule_definition d WHERE d.rule_key = 'CPF.MGW'",
                LocalDate.parse(TODAY),
                LocalDate.parse(TODAY),
                new BigDecimal(value));
        rulesChanged();
        return jdbc.queryForObject(
                "SELECT MAX(v.rule_version_id) FROM rule_version v JOIN rule_definition d ON"
                        + " d.rule_id = v.rule_id WHERE d.rule_key = 'CPF.MGW' AND v.created_by ="
                        + " 'it'",
                Long.class);
    }

    /**
     * SRS-6-2 (l.2080; BMAD TR-015): an advance for marriage is limited to 12 x MGW and capped at
     * 0.5 x (employee + VPF), the lesser governing, truncated to the paisa; above it the request is
     * capped, never rejected (SRS-6-26). Both sides: 12 x MGW above the cap (the cap governs) and
     * below it (12 x MGW governs). The MGW is a test-only version, removed afterwards (OQ-3).
     */
    @Test
    void SRS_6_2_anAdvanceIsTheLeastOfItsMgwMultipleAndHalfTheOwnShare() throws Exception {
        noOpenAdvance();
        purpose("IT_SANCTION_MARRIAGE", "ADVANCE", "CPF.LIMIT.MGW.MARRIAGE", false);
        ledger("10000.00", "6000.00", "2000.00");
        BigDecimal cap =
                new BigDecimal("12000.00")
                        .multiply(new BigDecimal("0.5"))
                        .setScale(2, RoundingMode.DOWN);
        Long version = null;
        try {
            // 12 x 1,000.10 = 12,001.20 is above the cap of 6,000.00: the cap governs.
            version = mgw("1000.10");
            JsonNode high = toTier3("IT_SANCTION_MARRIAGE");
            assertThat(high.path("ceiling").decimalValue()).isEqualByComparingTo(cap);
            assertThat(high.path("sanctioned").decimalValue()).isEqualByComparingTo(cap);
            assertThat(high.path("capped").asBoolean()).isTrue();
            withdraw(high.path("id").asLong());
            // 12 x 400.05 = 4,800.60 is below the cap: the MGW multiple governs.
            jdbc.update(
                    "UPDATE rule_version SET scalar_value = 400.05 WHERE rule_version_id = ?",
                    version);
            rulesChanged();
            JsonNode low = toTier3("IT_SANCTION_MARRIAGE");
            assertThat(low.path("ceiling").decimalValue()).isEqualByComparingTo("4800.60");
            assertThat(low.path("sanctioned").decimalValue()).isEqualByComparingTo("4800.60");
            assertThat(low.path("capped").asBoolean()).isTrue();
            withdraw(low.path("id").asLong());
        } finally {
            if (version != null) {
                try {
                    jdbc.update("DELETE FROM rule_version WHERE rule_version_id = ?", version);
                } catch (org.springframework.dao.DataIntegrityViolationException referenced) {
                    // Kept by a reference: out of force for every later test instead.
                    jdbc.update(
                            "UPDATE rule_version SET approval_state = 'REJECTED' WHERE"
                                    + " rule_version_id = ?",
                            version);
                }
                rulesChanged();
            }
            noOpenAdvance();
        }
    }

    private void withdraw(long id) throws Exception {
        MvcResult w =
                mvc.perform(
                                userPost(
                                        member(),
                                        "/v1/cpf/sanctions/" + id + "/withdraw",
                                        Map.of("reason", "IT: ceiling only")))
                        .andReturn();
        assertThat(w.getResponse().getStatus())
                .as(w.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    /** SRS I-04 field table "Supporting Documents": up to 5 files; a sixth is refused by name. */
    @Test
    void SRS_6_14_aRequestCarriesAtMostFiveDocuments() throws Exception {
        purpose("IT_SANCTION_DOCS", "ADVANCE", "CPF.LIMIT.BALANCE.BEFORE_RETIREMENT", false);
        List<Map<String, Object>> particulars = new java.util.ArrayList<>();
        MockMultipartHttpServletRequestBuilder r = multipart("/v1/cpf/sanctions");
        for (int i = 0; i < 6; i++) {
            particulars.add(
                    Map.of(
                            "documentType", "CERTIFICATE",
                            "reference", "CERT/IT/" + i,
                            "documentDate", "2026-09-01",
                            "issuingAuthority", "Registrar, Delhi"));
            r.file(
                    new MockMultipartFile(
                            "file", "certificate-" + i + ".pdf", "application/pdf", PDF));
        }
        Map<String, Object> form =
                Map.of(
                        "purposeCode",
                        "IT_SANCTION_DOCS",
                        "amount",
                        "100",
                        "documents",
                        particulars);
        r.file(
                new MockMultipartFile(
                        "request",
                        "",
                        MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(form)));
        MvcResult refused =
                mvc.perform(
                                userRequest(member(), r)
                                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                        .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(refused)).isEqualTo("CPF-422-DOCUMENT-COUNT");
        assertThat(body(refused).path("args").path(0).asInt()).isEqualTo(5);
    }

    /** Puts the paid / closed state on every open advance of the member, so a new one may start. */
    private void noOpenAdvance() {
        jdbc.update(
                "UPDATE pf_sanction SET workflow_stage = 'PAID' WHERE employee_id = ? AND"
                        + " sanction_type = 'ADVANCE' AND workflow_stage NOT IN"
                        + " ('PAID','REJECTED','RETURNED','CANCELLED')",
                memberId());
    }

    /** Raises an advance and takes it through tiers 1 and 2; answers the tier-2 body. */
    private JsonNode toTier3(String purpose) throws Exception {
        MvcResult raised = request(member(), purpose, "50000", null, true);
        assertThat(raised.getResponse().getStatus())
                .as(raised.getResponse().getContentAsString())
                .isEqualTo(201);
        long id = body(raised).path("id").asLong();
        assertThat(tier(unit(), id, "verify", "FORWARD", "verified").getResponse().getStatus())
                .isEqualTo(200);
        MvcResult recommended = tier(division(), id, "recommend", "FORWARD", "Eligible");
        assertThat(recommended.getResponse().getStatus())
                .as(recommended.getResponse().getContentAsString())
                .isEqualTo(202);
        return body(recommended);
    }

    /**
     * SRS-6-31 / BR-CPF-024 (SC-07-0022, SC-07-0085): a member on deputation (an approved
     * DEPUTATION_OUT received from HRMS, no return) applies through the same three tiers; the
     * ceiling, the advance cap and the one-active-advance rule are those of any member, and the
     * advance sanctioned during the deputation is the one I-09 recovers (BR-CPF-021).
     */
    @Test
    void SRS_6_31_BR_CPF_024_aDeputedMembersAdvanceRunsTheThreeTiersUnderTheSameRules()
            throws Exception {
        noOpenAdvance();
        purpose("IT_SANCTION_DEPUTED", "ADVANCE", "CPF.LIMIT.BALANCE.BEFORE_RETIREMENT", false);
        ledger("10000.00", "6000.00", "2000.00");
        LocalDate today = LocalDate.parse(TODAY);
        jdbc.update(
                "INSERT INTO employee_lifecycle_event (employee_id, event_type, event_date,"
                        + " reason, order_reference, event_source, created_by) VALUES (?,"
                        + " 'DEPUTATION_OUT', ?, 'IT deputation', 'IT/DEP/104882', 'HRMS',"
                        + " 'seed.sysadmin')",
                memberId(),
                java.sql.Date.valueOf(today.minusMonths(2).withDayOfMonth(1)));
        try {
            JsonNode r = toTier3("IT_SANCTION_DEPUTED");
            // The same ceiling as for a member in service: least of 0.9 x balance and 0.5 x
            // (employee + VPF).
            BigDecimal ceiling =
                    new BigDecimal("18000.00")
                            .multiply(new BigDecimal("0.9"))
                            .min(new BigDecimal("12000.00").multiply(new BigDecimal("0.5")))
                            .setScale(2, RoundingMode.DOWN);
            assertThat(r.path("ceiling").decimalValue()).isEqualByComparingTo(ceiling);
            assertThat(r.path("capped").asBoolean()).isTrue();
            assertThat(
                            decide(
                                    authority(),
                                    r.path("approvalId").asLong(),
                                    "APPROVE",
                                    null,
                                    "CPF_SANCTION_FINAL_PAYMENT"))
                    .isEqualTo(200);
            long id = r.path("id").asLong();
            // SRS-6-37 unchanged: no second advance while this one is not paid.
            assertThat(code(request(member(), "IT_SANCTION_DEPUTED", "100", null, true)))
                    .isEqualTo("CPF-409-ACTIVE-ADVANCE");
            // BR-CPF-021: I-09 offers the advance for the borrowing organisation's instalments.
            JsonNode onI09 =
                    body(
                            mvc.perform(
                                            userGet(
                                                    login(
                                                            "cpf.manager.north@fci-demo.test",
                                                            "CPF_MANAGER",
                                                            "NA11"),
                                                    "/v1/cpf/deputations/" + EIS))
                                    .andReturn());
            assertThat(
                            onI09.path("advances").findValues("sanctionId").stream()
                                    .map(JsonNode::asLong))
                    .contains(id);
        } finally {
            jdbc.update(
                    "DELETE FROM employee_lifecycle_event WHERE employee_id = ? AND"
                            + " order_reference = 'IT/DEP/104882'",
                    memberId());
            noOpenAdvance();
        }
    }

    /**
     * SRS I-04 "Withdrawal Count" (l.2279; SRS-6-4 tier 2, BR-CPF-010): the detail of a request
     * lists the member's other withdrawals that count - sanctioned or still in the chain - with
     * type and date, never the request itself nor a rejected or cancelled one; a register row
     * carries none.
     */
    @Test
    void SRS_6_4_BR_CPF_010_theDetailListsTheMembersOtherWithdrawalsThatCount() throws Exception {
        noOpenAdvance();
        purpose("IT_SANCTION_COUNT", "ADVANCE", "CPF.LIMIT.BALANCE.BEFORE_RETIREMENT", false);
        ledger("10000.00", "6000.00", "2000.00");
        MvcResult raised = request(member(), "IT_SANCTION_COUNT", "100", null, true);
        assertThat(raised.getResponse().getStatus())
                .as(raised.getResponse().getContentAsString())
                .isEqualTo(201);
        long id = body(raised).path("id").asLong();
        JsonNode detail = body(mvc.perform(userGet(unit(), "/v1/cpf/sanctions/" + id)).andReturn());
        JsonNode earlier = detail.path("earlierWithdrawals");
        assertThat(earlier.isArray()).isTrue();
        List<Long> ids = earlier.findValues("id").stream().map(JsonNode::asLong).toList();
        assertThat(ids).doesNotContain(id);
        assertThat(earlier.findValuesAsString("stage")).doesNotContain("REJECTED", "CANCELLED");
        List<Long> counted =
                jdbc.queryForList(
                        "SELECT sanction_id FROM pf_sanction WHERE employee_id = ? AND"
                                + " sanction_id <> ? AND workflow_stage NOT IN"
                                + " ('REJECTED','CANCELLED')",
                        Long.class,
                        memberId(),
                        id);
        assertThat(ids).containsExactlyInAnyOrderElementsOf(counted);
        // The register does not carry the list.
        JsonNode register = all(unit(), "/v1/cpf/sanctions?stage=UNIT_VERIFICATION");
        assertThat(register.findValues("earlierWithdrawals").stream().allMatch(JsonNode::isNull))
                .isTrue();
        noOpenAdvance();
    }

    /**
     * SRS-6-4 / SRS-G-21 / SRS-NF-4 (US-07-18 AC1): the tier-3 sanction is an OTP-gated decision.
     * Without a code it answers 428, with a wrong code 400; either way the request stays with the
     * Competent Authority, unnumbered and without a tier-3 row. The right code then sanctions it.
     */
    @Test
    void SRS_6_4_SRS_G_21_SRS_NF_4_aTier3SanctionWithoutOrWithAWrongOtpChangesNothing()
            throws Exception {
        noOpenAdvance();
        purpose("IT_SANCTION_OTP", "ADVANCE", "CPF.LIMIT.BALANCE.BEFORE_RETIREMENT", false);
        ledger("10000.00", "6000.00", "2000.00");
        JsonNode r = toTier3("IT_SANCTION_OTP");
        long id = r.path("id").asLong();
        long approval = r.path("approvalId").asLong();
        Session authority = authority();

        Map<String, Object> body = new HashMap<>();
        body.put("decision", "APPROVE");
        MvcResult first =
                mvc.perform(userPost(authority, "/v1/approvals/" + approval + "/decision", body))
                        .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(428);
        long challenge = body(first).get("args").get(0).asLong();
        body.put("otp", Map.of("challengeId", challenge, "code", "000000"));
        MvcResult wrong =
                mvc.perform(userPost(authority, "/v1/approvals/" + approval + "/decision", body))
                        .andReturn();
        // A one-in-a-million chance that 000000 is the real code: then the sanction stands.
        if (wrong.getResponse().getStatus() != 200) {
            assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
            assertThat(code(wrong)).isEqualTo("AUTH-400-OTP");
            JsonNode unchanged =
                    body(mvc.perform(userGet(division(), "/v1/cpf/sanctions/" + id)).andReturn());
            assertThat(unchanged.path("stage").asString()).isEqualTo("COMPETENT_AUTHORITY");
            assertThat(
                            unchanged.path("sanctionNumber").isNull()
                                    || unchanged.path("sanctionNumber").isMissingNode())
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM cpf_sanction_tier WHERE sanction_id = ?"
                                            + " AND tier_no = 3",
                                    Integer.class,
                                    id))
                    .isZero();
            assertThat(decide(authority, approval, "APPROVE", null, "CPF_SANCTION_FINAL_PAYMENT"))
                    .isEqualTo(200);
        }
        assertThat(
                        body(mvc.perform(userGet(division(), "/v1/cpf/sanctions/" + id))
                                        .andReturn())
                                .path("stage")
                                .asString())
                .isEqualTo("SANCTIONED");
        noOpenAdvance();
    }
}
