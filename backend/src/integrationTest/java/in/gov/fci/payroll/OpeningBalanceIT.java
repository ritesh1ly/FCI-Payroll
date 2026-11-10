package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * I-22 PF / CPF Opening Balance Correction end to end (docs/plan/07-cpf.md phase 07d; SRS-6-43,
 * SRS-6-44, SRS-6-33, BR-CPF-025 … 027): Manager (CPF) raises, AGM (CPF) checks with OTP, the
 * Headquarters CPF approver approves with OTP, three different users; the ledger moves by the
 * difference and every later year carries it; the refusals of SRS-6-44. Each test corrects its own
 * far-future year of a North Zone member, so the ledgers never meet.
 */
class OpeningBalanceIT extends ApiTestSupport {

    private static final String OTP = "CPF_OPENING_BALANCE_CORRECTION";
    private static final String EIS = "104882";
    private static final byte[] PDF = "%PDF-1.7 evidence".getBytes(StandardCharsets.US_ASCII);

    private Session manager() throws Exception {
        return login("cpf.manager.north@fci-demo.test", "CPF_MANAGER", "NA11");
    }

    private Session agm() throws Exception {
        return login("agm.cpf.north@fci-demo.test", "AGM_CPF", "NA11");
    }

    private Session hq() throws Exception {
        return login("cpf.approver.hq@fci-demo.test", "HQ_CPF_APPROVER", "HQ001");
    }

    private long member(String eis) {
        return jdbc.queryForObject(
                "SELECT e.employee_id FROM employee e JOIN org_zone z ON z.zone_id ="
                        + " e.parent_zone_id WHERE e.emp_num = ? AND z.zone_code = 'NA11'",
                Long.class,
                eis);
    }

    /** A migrated ledger of {@code year} (and, when given, the next year carried from it). */
    private void ledger(String year, String employee, String employer, String vpf, String next) {
        long id = member(EIS);
        String sql =
                "INSERT INTO employee_pf_header (employee_id, fin_year, zone_id,"
                    + " opening_balance_employee, opening_balance_employer, opening_balance_vpf,"
                    + " closing_balance_employee, closing_balance_employer, closing_balance_vpf,"
                    + " opening_balance_source) VALUES (?, ?, (SELECT zone_id FROM org_zone WHERE"
                    + " zone_code = 'NA11'), ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(
                sql,
                id,
                year,
                d(employee),
                d(employer),
                d(vpf),
                d(employee),
                d(employer),
                d(vpf),
                "MIGRATION");
        if (next != null) {
            jdbc.update(
                    sql,
                    id,
                    next,
                    d(employee),
                    d(employer),
                    d(vpf),
                    d(employee),
                    d(employer),
                    d(vpf),
                    "SYSTEM_COMPUTED");
        }
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private MvcResult raise(
            Session s,
            String eis,
            String asAt,
            Map<String, String> parts,
            String reason,
            String remarks,
            boolean file)
            throws Exception {
        MockMultipartHttpServletRequestBuilder r = multipart("/v1/cpf/opening-balance-corrections");
        if (file) {
            r.file(new MockMultipartFile("file", "reconciliation.pdf", "application/pdf", PDF));
        }
        r.param("eis", eis)
                .param("asAtDate", asAt)
                .param("reasonCode", reason)
                .param("interestEffectiveDate", asAt)
                .param("remarks", remarks)
                .param("documentType", "OTHER")
                .param("reference", "REC/IT/1")
                .param("orderDate", "2026-10-01")
                .param("issuingAuthority", "CPF Division NZ");
        parts.forEach(r::param);
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private MvcResult raise(Session s, String asAt, Map<String, String> parts) throws Exception {
        return raise(
                s,
                EIS,
                asAt,
                parts,
                "CLERICAL_ERROR",
                "IT: clerical error in the migrated balance",
                true);
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private Map<String, Object> header(String year) {
        return jdbc.queryForMap(
                "SELECT * FROM employee_pf_header WHERE employee_id = ? AND fin_year = ?",
                member(EIS),
                year);
    }

    private List<Map<String, Object>> rows(String ref) {
        return jdbc.queryForList(
                "SELECT * FROM opening_balance_correction WHERE correction_ref = ? ORDER BY"
                        + " correction_id",
                ref);
    }

    @Test
    void SRS_6_43_threeDifferentUsersEachTierWithOtpAndTheLedgerMovesByTheDifference()
            throws Exception {
        ledger("2080-81", "1000.00", "500.00", "0", "2081-82");
        Session manager = manager();
        JsonNode current =
                body(
                        mvc.perform(
                                        userGet(
                                                manager,
                                                "/v1/cpf/opening-balances?eis="
                                                        + EIS
                                                        + "&asAt=2080-04-01"))
                                .andReturn());
        assertThat(current.path("opening").path("employee").decimalValue())
                .isEqualByComparingTo("1000");
        assertThat(current.path("pending").asBoolean()).isFalse();

        MvcResult raised =
                raise(manager, "2080-04-01", Map.of("employee", "1100.00", "employer", "500.00"));
        assertThat(raised.getResponse().getStatus())
                .as(raised.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode view = body(raised);
        String ref = view.path("ref").asString();
        assertThat(ref).matches("OB-\\d{4}-\\d{6}");
        assertThat(view.path("status").asString()).isEqualTo("PENDING_AGM_CPF");
        assertThat(view.path("parts")).hasSize(1);
        long approval = view.path("approvalId").asLong();

        // The maker never decides the own correction; the AGM decides with OTP.
        assertThat(decide(manager(), approval, "APPROVE", null, OTP)).isEqualTo(403);
        assertThat(decide(agm(), approval, "APPROVE", null, OTP)).isEqualTo(200);
        Map<String, Object> checked = rows(ref).get(0);
        assertThat(checked.get("CORRECTION_STATUS")).isEqualTo("PENDING_HQ_CPF");
        assertThat(checked.get("CHECKER_USER")).isEqualTo("agm.cpf.north@fci-demo.test");
        assertThat(checked.get("OTP_CHALLENGE_ID")).isNotNull();
        // Nothing is applied before the Headquarters CPF approver.
        assertThat((BigDecimal) header("2080-81").get("CLOSING_BALANCE_EMPLOYEE"))
                .isEqualByComparingTo("1000");

        assertThat(decide(hq(), approval, "APPROVE", "IT: verified at HQ", OTP)).isEqualTo(200);
        Map<String, Object> applied = rows(ref).get(0);
        assertThat(applied.get("CORRECTION_STATUS")).isEqualTo("APPLIED");
        assertThat(applied.get("HQ_APPROVER_USER")).isEqualTo("cpf.approver.hq@fci-demo.test");
        assertThat(applied.get("HQ_OTP_CHALLENGE_ID")).isNotNull();
        assertThat((BigDecimal) applied.get("DIFFERENCE_AMOUNT")).isEqualByComparingTo("100");
        assertThat((BigDecimal) applied.get("INTEREST_EFFECT_AMOUNT")).isEqualByComparingTo("0");
        // The established opening stays; the closing moves; the later year carries the difference.
        Map<String, Object> year = header("2080-81");
        assertThat((BigDecimal) year.get("OPENING_BALANCE_EMPLOYEE")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) year.get("CLOSING_BALANCE_EMPLOYEE")).isEqualByComparingTo("1100");
        Map<String, Object> next = header("2081-82");
        assertThat((BigDecimal) next.get("OPENING_BALANCE_EMPLOYEE")).isEqualByComparingTo("1100");
        assertThat((BigDecimal) next.get("CLOSING_BALANCE_EMPLOYEE")).isEqualByComparingTo("1100");
        // The screen now shows the corrected opening; the change is audited with old and new.
        current =
                body(
                        mvc.perform(
                                        userGet(
                                                manager(),
                                                "/v1/cpf/opening-balances?eis="
                                                        + EIS
                                                        + "&asAt=2080-04-01"))
                                .andReturn());
        assertThat(current.path("opening").path("employee").decimalValue())
                .isEqualByComparingTo("1100");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT old_value FROM audit_event WHERE entity_name ="
                                        + " 'opening_balance_correction' AND entity_key = ? AND"
                                        + " field_name = 'opening_balance_employee'",
                                String.class,
                                ref))
                .isEqualTo("1000");
        // SRS-6-44: queued for the CPF SSP (no interface yet: SKIPPED with the reason).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sync_status FROM cpf_ssp_sync_log WHERE employee_id = ? AND"
                                    + " sync_trigger = 'OB_CORRECTION' ORDER BY sync_id DESC FETCH"
                                    + " FIRST 1 ROWS ONLY",
                                String.class,
                                member(EIS)))
                .isEqualTo("SKIPPED");
        // The register lists it for the CPF Division, with its evidence.
        Session division = login("cpf.north@fci-demo.test", "CPF_DIVISION", "NA11");
        JsonNode register = all(division, "/v1/cpf/opening-balance-corrections");
        JsonNode mine = null;
        for (JsonNode c : register) {
            if (ref.equals(c.path("ref").asString())) {
                mine = c;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(
                        mvc.perform(
                                        userGet(
                                                division,
                                                "/v1/cpf/opening-balance-corrections/"
                                                        + ref
                                                        + "/evidence/"
                                                        + mine.path("evidenceId").asLong()))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        // SRS-22-21 (data scope): the CPF Division of another zone neither lists the correction nor
        // opens its evidence; an unknown reference is simply not found.
        Session west = login("rbac.cpf.west@fci-demo.test", "CPF_DIVISION", "ZO-W");
        assertThat(all(west, "/v1/cpf/opening-balance-corrections").findValuesAsString("ref"))
                .doesNotContain(ref);
        String evidence = "/evidence/" + mine.path("evidenceId").asLong();
        assertThat(
                        mvc.perform(
                                        userGet(
                                                west,
                                                "/v1/cpf/opening-balance-corrections/"
                                                        + ref
                                                        + evidence))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(
                        mvc.perform(
                                        userGet(
                                                division,
                                                "/v1/cpf/opening-balance-corrections/OB-1999-999999"
                                                        + evidence))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
    }

    @Test
    void SRS_6_44_eachRefusalIsNamedAndOnlyOneCorrectionIsOpenAtATime() throws Exception {
        ledger("2082-83", "1000.00", "500.00", "10.00", null);
        Session manager = manager();
        Map<String, String> refused = new HashMap<>();
        refused.put("same", error(raise(manager, "2082-04-01", Map.of("employee", "1000.00"))));
        refused.put("negative", error(raise(manager, "2082-04-01", Map.of("vpf", "-0.01"))));
        refused.put(
                "reason",
                error(
                        raise(
                                manager,
                                EIS,
                                "2082-04-01",
                                Map.of("vpf", "5"),
                                "COVID_19",
                                "IT",
                                true)));
        refused.put("noLedger", error(raise(manager, "2070-04-01", Map.of("vpf", "5"))));
        assertThat(refused)
                .containsEntry("same", "CPF-422-NO-CHANGE")
                .containsEntry("negative", "CPF-422-NEGATIVE")
                .containsEntry("reason", "CPF-422-REASON-CODE")
                .containsEntry("noLedger", "CPF-422-NO-LEDGER");
        // The request boundary: no evidence file, text the US7ASCII database cannot hold.
        assertThat(
                        raise(
                                        manager,
                                        EIS,
                                        "2082-04-01",
                                        Map.of("vpf", "5"),
                                        "CLERICAL_ERROR",
                                        "IT",
                                        false)
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        raise(
                                        manager,
                                        EIS,
                                        "2082-04-01",
                                        Map.of("vpf", "5"),
                                        "CLERICAL_ERROR",
                                        "त्रुटि",
                                        true)
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);

        MvcResult first = raise(manager, "2082-04-01", Map.of("vpf", "15.00"));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(error(raise(manager, "2082-06-01", Map.of("vpf", "20.00"))))
                .isEqualTo("CPF-409-CORRECTION-PENDING");
        // Returned by the AGM: nothing applies, and the member may be corrected again.
        assertThat(
                        decide(
                                agm(),
                                body(first).path("approvalId").asLong(),
                                "RETURN",
                                "IT: wrong evidence",
                                null))
                .isEqualTo(200);
        Map<String, Object> returned = rows(body(first).path("ref").asString()).get(0);
        assertThat(returned.get("CORRECTION_STATUS")).isEqualTo("RETURNED");
        assertThat(returned.get("STATUS_REASON")).isEqualTo("IT: wrong evidence");
        assertThat((BigDecimal) header("2082-83").get("CLOSING_BALANCE_VPF"))
                .isEqualByComparingTo("10");
        MvcResult again = raise(manager(), "2082-04-01", Map.of("vpf", "15.00"));
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(decide(agm(), body(again).path("approvalId").asLong(), "APPROVE", null, OTP))
                .isEqualTo(200);
        assertThat(
                        decide(
                                hq(),
                                body(again).path("approvalId").asLong(),
                                "REJECT",
                                "IT: not justified",
                                OTP))
                .isEqualTo(200);
        assertThat(rows(body(again).path("ref").asString()).get(0).get("CORRECTION_STATUS"))
                .isEqualTo("REJECTED");

        // A member whose CPF final settlement is sanctioned is never corrected.
        jdbc.update(
                "UPDATE employee_pf_header SET member_status = 'FINALLY_SETTLED' WHERE employee_id"
                        + " = ? AND fin_year = '2082-83'",
                member(EIS));
        assertThat(error(raise(manager(), "2082-04-01", Map.of("vpf", "15.00"))))
                .isEqualTo("CPF-409-SETTLED");
        jdbc.update(
                "UPDATE employee_pf_header SET member_status = 'ACTIVE' WHERE employee_id = ? AND"
                        + " fin_year = '2082-83'",
                member(EIS));
    }

    @Test
    void SRS_6_44_aLoweringAfterASanctionedWithdrawalGoesToTheCpfDivisionNotTheApprovers()
            throws Exception {
        ledger("2084-85", "1000.00", "500.00", "0", null);
        jdbc.update(
                "INSERT INTO pf_sanction (employee_id, sanction_type, requested_amount,"
                        + " sanctioned_amount, sanction_date, workflow_stage) VALUES (?, 'ADVANCE',"
                        + " 200, 200, DATE '2084-09-01', 'SANCTIONED')",
                member(EIS));
        Session manager = manager();
        MvcResult lowered = raise(manager, "2084-04-01", Map.of("employee", "900.00"));
        assertThat(lowered.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(lowered).path("status").asString()).isEqualTo("BLOCKED_CPF_DIVISION");
        assertThat(body(lowered).path("approvalId").isNull()).isTrue();
        assertThat(body(lowered).path("statusReason").asString()).contains("CPF Division");
        // Blocked counts as open: no second correction until the CPF Division has reviewed it.
        assertThat(error(raise(manager(), "2084-04-01", Map.of("employer", "600.00"))))
                .isEqualTo("CPF-409-CORRECTION-PENDING");
    }

    /**
     * US-GAP-173 (SRS I-22 field table l.2240-2241): the Effective Date for Interest and the
     * Remarks are mandatory and the Remarks hold at most 1,000 characters: 1,000 are accepted,
     * 1,001 refused.
     */
    @Test
    void SRS_6_43_theInterestDateAndRemarksAreMandatoryAndRemarksHoldAThousandCharacters()
            throws Exception {
        ledger("2088-89", "1000.00", "500.00", "0", null);
        Session manager = manager();
        MockMultipartHttpServletRequestBuilder noDate =
                multipart("/v1/cpf/opening-balance-corrections");
        noDate.file(new MockMultipartFile("file", "reconciliation.pdf", "application/pdf", PDF));
        noDate.param("eis", EIS)
                .param("asAtDate", "2088-04-01")
                .param("employee", "1001")
                .param("reasonCode", "CLERICAL_ERROR")
                .param("remarks", "IT: no interest date")
                .param("documentType", "OTHER")
                .param("orderDate", "2026-10-01")
                .param("issuingAuthority", "CPF Division NZ");
        MvcResult undated =
                mvc.perform(
                                userRequest(manager, noDate)
                                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                        .andReturn();
        assertThat(undated.getResponse().getStatus())
                .as(undated.getResponse().getContentAsString())
                .isEqualTo(400);
        // A missing part is a malformed request, not a business refusal.
        assertThat(error(undated)).isEqualTo("COM-400-MALFORMED");
        for (String remarks : List.of("", "   ", "r".repeat(1001))) {
            MvcResult refused =
                    raise(
                            manager,
                            EIS,
                            "2088-04-01",
                            Map.of("employee", "1001"),
                            "CLERICAL_ERROR",
                            remarks,
                            true);
            assertThat(refused.getResponse().getStatus())
                    .as("remarks of %d characters", remarks.length())
                    .isEqualTo(400);
            assertThat(error(refused))
                    .as("remarks of %d characters", remarks.length())
                    .isEqualTo("COM-400-VALIDATION");
            assertThat(body(refused).path("violations").findValuesAsString("field"))
                    .contains("remarks");
        }
        // Nothing was raised by the refusals.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM opening_balance_correction WHERE"
                                        + " employee_id = ? AND fin_year = '2088-89'",
                                Integer.class,
                                member(EIS)))
                .isZero();
        MvcResult longest =
                raise(
                        manager,
                        EIS,
                        "2088-04-01",
                        Map.of("employee", "1001"),
                        "CLERICAL_ERROR",
                        "r".repeat(1000),
                        true);
        assertThat(longest.getResponse().getStatus())
                .as(longest.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(body(longest).path("remarks").asString()).hasSize(1000);
        // Returned so that the year holds no open correction.
        assertThat(
                        decide(
                                agm(),
                                body(longest).path("approvalId").asLong(),
                                "RETURN",
                                "IT: done",
                                null))
                .isEqualTo(200);
    }

    /**
     * SRS-G-22 / R-OTP (CPF_OPENING_BALANCE_CORRECTION, SRS-6-43): the AGM (CPF) answering with the
     * right code after the challenge's validity ran out is refused AUTH-400-OTP; the challenge is
     * EXPIRED, the correction stays with the AGM and the ledger is not moved. A fresh code then
     * passes the correction to the Headquarters CPF approver.
     */
    @Test
    void SRS_G_22_anOpeningBalanceCorrectionCheckedWithAnExpiredOtpChangesNothing()
            throws Exception {
        ledger("2085-86", "1000.00", "500.00", "0", null);
        MvcResult raised = raise(manager(), "2085-04-01", Map.of("employee", "1200.00"));
        assertThat(raised.getResponse().getStatus())
                .as(raised.getResponse().getContentAsString())
                .isEqualTo(201);
        String ref = body(raised).path("ref").asString();
        long approval = body(raised).path("approvalId").asLong();
        Session agm = agm();
        Map.Entry<MvcResult, Long> late = decideAfterOtpExpiry(agm, approval, "APPROVE", null, OTP);
        assertThat(late.getKey().getResponse().getStatus()).isEqualTo(400);
        assertThat(error(late.getKey())).isEqualTo("AUTH-400-OTP");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM otp_challenge WHERE otp_challenge_id = ?",
                                String.class,
                                late.getValue()))
                .isEqualTo("EXPIRED");
        Map<String, Object> waiting = rows(ref).get(0);
        assertThat(waiting.get("CORRECTION_STATUS")).isEqualTo("PENDING_AGM_CPF");
        assertThat(waiting.get("CHECKER_USER")).isNull();
        assertThat(waiting.get("OTP_CHALLENGE_ID")).isNull();
        assertThat((BigDecimal) header("2085-86").get("CLOSING_BALANCE_EMPLOYEE"))
                .isEqualByComparingTo("1000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("PENDING_APPROVAL");
        // A fresh challenge and code: the AGM's check now passes to Headquarters.
        assertThat(decide(agm, approval, "APPROVE", null, OTP)).isEqualTo(200);
        assertThat(rows(ref).get(0).get("CORRECTION_STATUS")).isEqualTo("PENDING_HQ_CPF");
        // Returned at Headquarters so that the year holds no open correction.
        assertThat(decide(hq(), approval, "RETURN", "IT: done", null)).isEqualTo(200);
        assertThat((BigDecimal) header("2085-86").get("CLOSING_BALANCE_EMPLOYEE"))
                .isEqualByComparingTo("1000");
    }

    @Test
    void SRS_22_21_onlyManagerCpfRaisesAndOnlyForMembersInScope() throws Exception {
        ledger("2086-87", "1000.00", "0", "0", null);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(raise(maker, "2086-04-01", Map.of("employee", "1001")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(
                        // OD-125 (Wireframes v6.1 role access, V078_4): the opening-balance
                        // register is drawn for the Payroll Maker (read only); WF-071 for FCI.
                        mvc.perform(userGet(maker, "/v1/cpf/opening-balance-corrections"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        // The AGM reads but does not raise.
        assertThat(raise(agm(), "2086-04-01", Map.of("employee", "1001")).getResponse().getStatus())
                .isEqualTo(403);
        // A member outside the North Zone answers as unknown.
        String kolkata =
                jdbc.queryForObject(
                        "SELECT MIN(e.emp_num) FROM employee e JOIN org_location l ON l.location_id"
                                + " = e.location_id WHERE l.location_code = 'KL001'",
                        String.class);
        MvcResult outside =
                raise(
                        manager(),
                        kolkata,
                        "2086-04-01",
                        Map.of("employee", "1"),
                        "CLERICAL_ERROR",
                        "IT",
                        true);
        assertThat(outside.getResponse().getStatus()).isEqualTo(404);
        assertThat(
                        mvc.perform(
                                        userGet(
                                                manager(),
                                                "/v1/cpf/opening-balances?eis="
                                                        + kolkata
                                                        + "&asAt=2086-04-01"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
    }

    private MvcResult lookup(Session s, String query) throws Exception {
        return mvc.perform(userGet(s, "/v1/cpf/opening-balances?" + query + "&asAt=2080-04-01"))
                .andReturn();
    }

    /**
     * SRS I-22 field table "Member (CPF Code / UAN)" (l.2234; SC-07-0074): the member is found by
     * EIS, CPF code or UAN - exactly one of them - and the screen shows the UAN read-only; an
     * unknown or malformed value, two values or none are refused.
     */
    @Test
    void SRS_l2234_aMemberIsFoundByCpfCodeOrUanAndTheUanIsShown() throws Exception {
        Session manager = manager();
        String cpfCode =
                jdbc.queryForObject(
                        "SELECT cpf_code FROM employee WHERE emp_num = ?", String.class, EIS);
        String uan =
                jdbc.queryForObject(
                        "SELECT uan_number FROM employee WHERE emp_num = ?", String.class, EIS);
        JsonNode byEis = body(lookup(manager, "eis=" + EIS));
        assertThat(byEis.path("uan").asString()).isEqualTo(uan);
        JsonNode byCode = body(lookup(manager, "cpfCode=" + cpfCode));
        assertThat(byCode.path("eis").asString()).isEqualTo(EIS);
        assertThat(byCode.path("cpfCode").asString()).isEqualTo(cpfCode);
        JsonNode byUan = body(lookup(manager, "uan=" + uan));
        assertThat(byUan.path("eis").asString()).isEqualTo(EIS);
        assertThat(byUan.path("uan").asString()).isEqualTo(uan);

        assertThat(lookup(manager, "uan=999999999999").getResponse().getStatus()).isEqualTo(404);
        assertThat(lookup(manager, "cpfCode=NO-SUCH-CODE").getResponse().getStatus())
                .isEqualTo(404);
        assertThat(lookup(manager, "uan=12345").getResponse().getStatus()).isEqualTo(400);
        assertThat(lookup(manager, "eis=" + EIS + "&uan=" + uan).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(
                        mvc.perform(userGet(manager, "/v1/cpf/opening-balances?asAt=2080-04-01"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        // Out of scope answers as not found, as by EIS: a member of another zone.
        List<String> elsewhere =
                jdbc.queryForList(
                        "SELECT e.uan_number FROM employee e JOIN org_zone z ON z.zone_id ="
                                + " e.parent_zone_id WHERE z.zone_code <> 'NA11' AND e.uan_number"
                                + " IS NOT NULL AND (SELECT COUNT(*) FROM employee x WHERE"
                                + " x.uan_number = e.uan_number) = 1 FETCH FIRST 1 ROWS ONLY",
                        String.class);
        if (!elsewhere.isEmpty() && elsewhere.get(0).matches("[0-9]{12}")) {
            assertThat(lookup(manager, "uan=" + elsewhere.get(0)).getResponse().getStatus())
                    .isEqualTo(404);
        }
    }
}
