package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * I-09 CPF Adjustment / Recovery Adjustment end to end (plan 07 phase 07g; SRS-6-27 … 6-30,
 * BR-CPF-021 … 023): Manager (CPF) North records what the borrowing organisation remits for the
 * demo deputed member 871901 (seed V043_2_9: deputed three months back, a sanctioned advance of Rs
 * 60,000), AGM (CPF) North approves; the advance falls only on approval and never below zero, a
 * receipt reaches the ledger against its month. Each test uses its own months.
 */
class DeputationIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String EIS = "871901";
    private static final byte[] PDF = "%PDF-1.7 remittance".getBytes(StandardCharsets.US_ASCII);

    private Session manager() throws Exception {
        return login("cpf.manager.north@fci-demo.test", "CPF_MANAGER", "NA11");
    }

    private Session agm() throws Exception {
        return login("agm.cpf.north@fci-demo.test", "AGM_CPF", "NA11");
    }

    private static String month(int ahead) {
        YearMonth m = YearMonth.now(IST).plusMonths(ahead);
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private long org() {
        return jdbc.queryForObject(
                "SELECT borrowing_org_id FROM borrowing_organisation WHERE org_code = 'DEMO-PSU-1'",
                Long.class);
    }

    private long advance() {
        return jdbc.queryForObject(
                "SELECT sanction_id FROM pf_sanction WHERE system_sanction_number ="
                        + " 'DEMO-DEP-ADV-871901'",
                Long.class);
    }

    private MvcResult record(
            Session s,
            String eis,
            String type,
            String yymm,
            Map<String, String> amounts,
            byte[] file)
            throws Exception {
        MockMultipartHttpServletRequestBuilder r = multipart("/v1/cpf/deputation-entries");
        r.file(new MockMultipartFile("file", "remittance.pdf", "application/pdf", file));
        r.param("eis", eis)
                .param("borrowingOrgId", String.valueOf(org()))
                .param("type", type)
                .param("payrollYymm", yymm)
                .param("documentType", "OTHER")
                .param("reference", "REM/IT/1")
                .param("orderDate", "2026-10-01")
                .param("issuingAuthority", "Demo PSU accounts");
        amounts.forEach(r::param);
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private JsonNode created(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(201);
        return body(r);
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private BigDecimal outstanding(Session s) throws Exception {
        JsonNode m = body(mvc.perform(userGet(s, "/v1/cpf/deputations/" + EIS)).andReturn());
        for (JsonNode a : m.path("advances")) {
            if (a.path("sanctionId").asLong() == advance()) {
                return a.path("outstanding").decimalValue();
            }
        }
        throw new AssertionError("advance not listed");
    }

    /** Several evidence files on one request (the part "file" repeated). */
    private MvcResult withFiles(String path, Session s, int files, Map<String, String> params)
            throws Exception {
        MockMultipartHttpServletRequestBuilder r = multipart(path);
        for (int i = 0; i < files; i++) {
            r.file(new MockMultipartFile("file", "advice-" + i + ".pdf", "application/pdf", PDF));
        }
        r.param("documentType", "OTHER")
                .param("reference", "REM/IT/2")
                .param("orderDate", "2026-10-01")
                .param("issuingAuthority", "Demo PSU accounts");
        params.forEach(r::param);
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private MvcResult cancel(Session s, long entry, long file, String reason) throws Exception {
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/cpf/deputation-entries/"
                                        + entry
                                        + "/evidence/"
                                        + file
                                        + "/cancel",
                                reason == null ? Map.of() : Map.of("reason", reason)))
                .andReturn();
    }

    @Test
    void
            SRS_G_31_remittanceEvidenceTakesUpToFiveFilesIsAddedAfterSubmissionAndAWrongFileIsCancelled()
                    throws Exception {
        Session manager = manager();
        Map<String, String> entryParams =
                Map.of(
                        "eis",
                        EIS,
                        "borrowingOrgId",
                        String.valueOf(org()),
                        "type",
                        "EXCESS_REFUND",
                        "payrollYymm",
                        month(45),
                        "instalment",
                        "1");
        // Six files on the entry: refused, nothing recorded.
        MvcResult six = withFiles("/v1/cpf/deputation-entries", manager, 6, entryParams);
        assertThat(six.getResponse().getStatus()).isEqualTo(422);
        assertThat(error(six)).isEqualTo("CPF-422-EVIDENCE-FILES");
        JsonNode entry = created(withFiles("/v1/cpf/deputation-entries", manager, 2, entryParams));
        long id = entry.path("id").asLong();
        assertThat(entry.path("evidence")).hasSize(2);
        assertThat(entry.path("evidenceId").asLong())
                .isEqualTo(entry.path("evidence").get(0).path("id").asLong());
        String add = "/v1/cpf/deputation-entries/" + id + "/evidence";
        // Added after submission up to five; a sixth is refused.
        MvcResult three = withFiles(add, manager, 3, Map.of());
        assertThat(three.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(three)).hasSize(5);
        MvcResult sixth = withFiles(add, manager, 1, Map.of());
        assertThat(error(sixth)).isEqualTo("CPF-422-EVIDENCE-FILES");
        // The AGM (CPF) decides; it neither adds nor cancels evidence.
        Session agm = agm();
        assertThat(withFiles(add, agm, 1, Map.of()).getResponse().getStatus()).isEqualTo(403);
        long first = entry.path("evidenceId").asLong();
        assertThat(cancel(agm, id, first, "IT: wrong file").getResponse().getStatus())
                .isEqualTo(403);
        manager = manager();
        // A reason is required; a file of another entry is not found.
        assertThat(cancel(manager, id, first, " ").getResponse().getStatus()).isEqualTo(400);
        assertThat(cancel(manager, id, 987_654_321L, "IT: wrong file").getResponse().getStatus())
                .isEqualTo(404);
        // The wrong file is cancelled, never deleted; the entry points at the oldest file left.
        MvcResult cancelled = cancel(manager, id, first, "IT: wrong advice uploaded");
        assertThat(cancelled.getResponse().getStatus())
                .as(cancelled.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(cancelled)).hasSize(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attachment_status FROM document_attachment WHERE"
                                        + " attachment_id = ?",
                                String.class,
                                first))
                .isEqualTo("CANCELLED");
        long pointed =
                jdbc.queryForObject(
                        "SELECT evidence_attachment_id FROM cpf_deputation_recovery WHERE"
                                + " recovery_id = ?",
                        Long.class,
                        id);
        assertThat(pointed).isEqualTo(body(cancelled).get(0).path("id").asLong());
        assertThat(cancel(manager, id, first, "IT: again").getResponse().getStatus())
                .isEqualTo(404);
        // Evidence stays mandatory: the last active file is never cancelled.
        JsonNode left = body(cancelled);
        for (int i = 0; i < 3; i++) {
            assertThat(
                            cancel(manager, id, left.get(i).path("id").asLong(), "IT: trim")
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(200);
        }
        MvcResult last = cancel(manager, id, left.get(3).path("id").asLong(), "IT: last");
        assertThat(error(last)).isEqualTo("CPF-422-REMITTANCE-EVIDENCE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'cpf_deputation_recovery' AND entity_key = ? AND"
                                        + " field_name = 'attachment_status'",
                                Integer.class,
                                String.valueOf(id)))
                .isEqualTo(4);
        assertThat(decide(agm(), entry.path("approvalId").asLong(), "REJECT", "IT: cleanup", null))
                .isEqualTo(200);
    }

    // SC-07-0051 (main flow) and SC-07-0055 (verify: the outstanding advance balance shows the
    // instalment; the ledger part of a receipt is SRS_6_30 below; posting the instalment to the
    // ledger waits for Q-07-9 and the credit memo for module 23, PENDING 07g / 07g-apps).
    @Test
    void SRS_6_28_anInstalmentReducesTheAdvanceOnlyOnTheCheckersApproval() throws Exception {
        Session manager = manager();
        BigDecimal before = outstanding(manager);
        JsonNode entry =
                created(
                        record(
                                manager,
                                EIS,
                                "RECOVERY_INSTALMENT",
                                month(40),
                                Map.of(
                                        "sanctionId",
                                        String.valueOf(advance()),
                                        "instalment",
                                        "5000"),
                                PDF));
        assertThat(entry.path("status").asString()).isEqualTo("PENDING_CHECKER");
        assertThat(entry.path("evidenceId").asLong()).isPositive();
        // SRS-6-28: the order copy is downloaded from the entry, never another entry's file.
        String evidence = "/v1/cpf/deputation-entries/" + entry.path("id").asLong() + "/evidence/";
        MvcResult copy = download(manager, evidence + entry.path("evidenceId").asLong());
        assertThat(copy.getResponse().getStatus()).isEqualTo(200);
        assertThat(copy.getResponse().getHeader("Content-Disposition")).contains("attachment");
        assertThat(download(manager, evidence + 987_654_321L).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(outstanding(manager)).as("not on the Maker's save").isEqualByComparingTo(before);
        long approval = entry.path("approvalId").asLong();
        assertThat(decide(manager, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(agm(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(outstanding(manager))
                .isEqualByComparingTo(before.subtract(new BigDecimal("5000")));
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT entry_status, advance_outstanding, balance_after FROM"
                                + " cpf_deputation_recovery WHERE recovery_id = ?",
                        entry.path("id").asLong());
        assertThat(row.get("ENTRY_STATUS")).isEqualTo("APPROVED");
        assertThat((BigDecimal) row.get("ADVANCE_OUTSTANDING")).isEqualByComparingTo(before);
        assertThat((BigDecimal) row.get("BALANCE_AFTER"))
                .isEqualByComparingTo(before.subtract(new BigDecimal("5000")));
    }

    // SC-07-0053: the excess over the outstanding advance is recorded apart.
    @Test
    void SRS_6_29_anOverRemittanceIsRefusedAndTheExcessRecordedApart() throws Exception {
        Session manager = manager();
        BigDecimal outstanding = outstanding(manager);
        MvcResult over =
                record(
                        manager,
                        EIS,
                        "RECOVERY_INSTALMENT",
                        month(41),
                        Map.of(
                                "sanctionId",
                                String.valueOf(advance()),
                                "instalment",
                                outstanding.add(BigDecimal.ONE).toPlainString()),
                        PDF);
        assertThat(over.getResponse().getStatus()).isEqualTo(422);
        assertThat(error(over)).isEqualTo("CPF-422-OVER-REMITTANCE");
        JsonNode excess =
                created(
                        record(
                                manager,
                                EIS,
                                "EXCESS_REFUND",
                                month(41),
                                Map.of("instalment", "1"),
                                PDF));
        assertThat(decide(agm(), excess.path("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        assertThat(outstanding(manager)).as("never netted").isEqualByComparingTo(outstanding);
    }

    // SC-07-0055 (verify: the member's CPF ledger shows the contribution received).
    @Test
    void SRS_6_30_BR_CPF_023_aReceiptIsCreditedToTheLedgerAgainstItsMonthPartsApart()
            throws Exception {
        Session manager = manager();
        JsonNode member =
                body(mvc.perform(userGet(manager, "/v1/cpf/deputations/" + EIS)).andReturn());
        String first = member.path("monthsWithoutReceipt").get(0).asString();
        JsonNode receipt =
                created(
                        record(
                                manager,
                                EIS,
                                "CONTRIBUTION_RECEIPT",
                                first,
                                Map.of("employee", "1200", "employer", "1200", "vpf", "300.50"),
                                PDF));
        assertThat(decide(agm(), receipt.path("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        Map<String, Object> detail =
                jdbc.queryForMap(
                        "SELECT employee_contribution, employer_contribution, vpf_contribution FROM"
                                + " employee_pf_detail d JOIN employee e ON e.employee_id ="
                                + " d.employee_id WHERE e.emp_num = ? AND d.payroll_yymm = ? AND"
                                + " d.payment_type = 'DEPUTATION_RECEIPT'",
                        EIS,
                        first);
        assertThat((BigDecimal) detail.get("EMPLOYEE_CONTRIBUTION")).isEqualByComparingTo("1200");
        assertThat((BigDecimal) detail.get("EMPLOYER_CONTRIBUTION")).isEqualByComparingTo("1200");
        assertThat((BigDecimal) detail.get("VPF_CONTRIBUTION")).isEqualByComparingTo("300.50");
        JsonNode after =
                body(mvc.perform(userGet(manager, "/v1/cpf/deputations/" + EIS)).andReturn());
        assertThat(after.path("monthsWithoutReceipt").toString()).doesNotContain(first);
    }

    // SC-07-0052: a deputation adjustment that would make a CPF head negative is refused with its
    // error.
    @Test
    void SRS_6_27_whatIsRefusedAndAReturnedEntryIsCorrected() throws Exception {
        Session manager = manager();
        String m = month(43);
        MvcResult notDeputed =
                record(manager, "104882", "EXCESS_REFUND", m, Map.of("instalment", "1"), PDF);
        assertThat(error(notDeputed)).isEqualTo("CPF-422-NOT-DEPUTED");
        MvcResult noEvidence =
                record(manager, EIS, "EXCESS_REFUND", m, Map.of("instalment", "1"), new byte[0]);
        assertThat(error(noEvidence)).isEqualTo("CPF-422-REMITTANCE-EVIDENCE");
        MvcResult early =
                record(manager, EIS, "EXCESS_REFUND", month(-12), Map.of("instalment", "1"), PDF);
        assertThat(error(early)).isEqualTo("CPF-422-DEPUTATION-ENTRY");
        MvcResult noAdvance =
                record(manager, EIS, "RECOVERY_INSTALMENT", m, Map.of("instalment", "1"), PDF);
        assertThat(error(noAdvance)).isEqualTo("CPF-422-DEPUTATION-ADVANCE");

        JsonNode entry =
                created(record(manager, EIS, "EXCESS_REFUND", m, Map.of("instalment", "10"), PDF));
        MvcResult twice = record(manager, EIS, "EXCESS_REFUND", m, Map.of("instalment", "10"), PDF);
        assertThat(twice.getResponse().getStatus()).isEqualTo(409);
        assertThat(error(twice)).isEqualTo("CPF-409-DEPUTATION-DUPLICATE");
        assertThat(
                        decide(
                                agm(),
                                entry.path("approvalId").asLong(),
                                "RETURN",
                                "IT: wrong amount",
                                null))
                .isEqualTo(200);
        JsonNode again =
                created(record(manager, EIS, "EXCESS_REFUND", m, Map.of("instalment", "12"), PDF));
        assertThat(again.path("id").asLong())
                .as("the month's row is reused")
                .isEqualTo(entry.path("id").asLong());
        assertThat(again.path("instalment").decimalValue()).isEqualByComparingTo("12");
        assertThat(again.path("approvalId").asLong())
                .isNotEqualTo(entry.path("approvalId").asLong());
    }

    @Test
    void SRS_6_27_onlyTheCpfManagerOfTheZoneRecordsAndOrganisationsAreAdded() throws Exception {
        Session agm = agm();
        assertThat(
                        record(agm, EIS, "EXCESS_REFUND", month(44), Map.of("instalment", "1"), PDF)
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        Session payroll = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        // OD-125 (Wireframes v6.1 role access, V078_4): I-09 is drawn for the
                        // Payroll Maker (read only; the CPF Manager records); WF-071 for FCI.
                        mvc.perform(userGet(payroll, "/v1/cpf/deputations/" + EIS))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        String kolkata =
                jdbc.queryForObject(
                        "SELECT e.emp_num FROM employee e JOIN org_location l ON l.location_id ="
                            + " e.location_id WHERE l.location_code = 'KL001' FETCH FIRST 1 ROWS"
                            + " ONLY",
                        String.class);
        assertThat(
                        mvc.perform(userGet(manager(), "/v1/cpf/deputations/" + kolkata))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("outside the zone reads as missing")
                .isEqualTo(404);

        String code = "IT-ORG-" + (System.nanoTime() % 100000);
        Session manager = manager();
        MvcResult added =
                mvc.perform(
                                userPost(
                                        manager,
                                        "/v1/cpf/borrowing-organisations",
                                        Map.of("code", code, "name", "IT Borrowing Org")))
                        .andReturn();
        assertThat(added.getResponse().getStatus()).isEqualTo(201);
        MvcResult again =
                mvc.perform(
                                userPost(
                                        manager,
                                        "/v1/cpf/borrowing-organisations",
                                        Map.of("code", code, "name", "IT Borrowing Org")))
                        .andReturn();
        assertThat(error(again)).isEqualTo("CPF-422-BORROWING-ORG");
        assertThat(
                        mvc.perform(
                                        userPost(
                                                agm,
                                                "/v1/cpf/borrowing-organisations",
                                                Map.of("code", code + "X", "name", "x")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        JsonNode list =
                body(mvc.perform(userGet(agm, "/v1/cpf/borrowing-organisations")).andReturn());
        assertThat(list.toString()).contains(code, "DEMO-PSU-1");
        JsonNode register =
                body(
                        mvc.perform(userGet(agm, "/v1/cpf/deputation-entries?page=0&size=20"))
                                .andReturn());
        assertThat(register.path("total").asLong()).isPositive();
    }
}
