package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import in.gov.fci.payroll.tax.orchestration.DeclarationService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * I-07 / E-01 income-tax declarations (docs/plan/08-income-tax.md phase 08c; SRS-7-1, SRS-7-14,
 * SRS-7-15, SRS-7-16, SRS-7-24, SRS-17-2; BR-TAX-001, BR-TAX-002, BR-TAX-014): the member submits
 * in eSeva with a cap shown, the Payroll Maker of the paying unit verifies or returns, a different
 * Payroll Checker approves and the regime and rent are frozen, a reset opens the regime again, and
 * the refusals. The window is an approved version of TAX.DECLARATION.WINDOW written as the go-live
 * seeding writes it; each test uses a member of its own.
 */
class DeclarationIT extends ApiTestSupport {

    private static final byte[] PDF = "%PDF-1.7 proof".getBytes(StandardCharsets.US_ASCII);
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final String YEAR =
            in.gov.fci.payroll.tax.business.DeclarationRules.finYearOf(TODAY);

    @Autowired private DeclarationService declarations;

    @BeforeEach
    void configured() {
        // The year's windows: the demo seed's (V039_9) while in force, else a version of the
        // test's.
        for (String key : List.of("TAX.DECLARATION.WINDOW", "TAX.PROOF.WINDOW")) {
            if (inForce(key) == 0) {
                jdbc.update(
                        "INSERT INTO rule_version (rule_id, version_no, effective_from,"
                            + " effective_to, formula_expression, approval_state, is_blocked,"
                            + " created_by) SELECT d.rule_id, (SELECT COALESCE(MAX(v.version_no),"
                            + " 0) + 1 FROM rule_version v WHERE v.rule_id = d.rule_id), ?, ?, '1 >"
                            + " 0', 'APPROVED', 'N', 'it' FROM rule_definition d WHERE d.rule_key ="
                            + " ?",
                        TODAY.minusDays(30),
                        TODAY.plusDays(30),
                        key);
                rulesChanged();
            }
        }
        section("801IT", "IT life insurance", "150000.00", "OLD");
        section("802IT", "IT health insurance", "25000.00", "BOTH");
        section("803IT", "IT rent paid", null, "OLD");
    }

    private int inForce(String key) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM rule_version v JOIN rule_definition d ON d.rule_id ="
                    + " v.rule_id WHERE d.rule_key = ? AND v.approval_state = 'APPROVED' AND"
                    + " v.effective_from <= ? AND (v.effective_to IS NULL OR v.effective_to >= ?)",
                Integer.class,
                key,
                TODAY,
                TODAY);
    }

    /** The last day proofs are accepted: the end of the proof window in force today. */
    private String proofDeadline() {
        return jdbc.queryForObject(
                        "SELECT v.effective_to FROM rule_version v JOIN rule_definition d ON"
                                + " d.rule_id = v.rule_id WHERE d.rule_key = 'TAX.PROOF.WINDOW' AND"
                                + " v.approval_state = 'APPROVED' AND v.effective_from <= ? AND"
                                + " (v.effective_to IS NULL OR v.effective_to >= ?)",
                        java.sql.Date.class,
                        TODAY,
                        TODAY)
                .toString();
    }

    private void section(String code, String description, String limit, String regime) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM it_section_master WHERE section_code = ?",
                        Integer.class,
                        code)
                > 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO it_section_master (section_code, section_description,"
                    + " max_deduction_amount, regime_applicability, effective_from) VALUES (?, ?,"
                    + " ?, ?, ?)",
                code,
                description,
                limit == null ? null : new java.math.BigDecimal(limit),
                regime,
                TODAY.minusDays(30));
    }

    private Session member(String login) throws Exception {
        return login(login, null, null);
    }

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session checker() throws Exception {
        return login("maker.checker@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private String officeOf(String login) {
        return jdbc.queryForObject(
                "SELECT l.location_code FROM app_user u JOIN employee e ON e.employee_id ="
                    + " u.employee_id JOIN org_location l ON l.location_id = e.location_id WHERE"
                    + " u.login_id = ?",
                String.class,
                login);
    }

    private long employeeOf(String login) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM app_user WHERE login_id = ?", Long.class, login);
    }

    private static Map<String, Object> line(
            String kind, String section, String amount, boolean proof) {
        Map<String, Object> l = new HashMap<>();
        l.put("kind", kind);
        l.put("sectionCode", section);
        l.put("declared", amount);
        if (proof) {
            l.put(
                    "proof",
                    Map.of(
                            "documentType", "CERTIFICATE",
                            "documentDate", TODAY.minusDays(1).toString(),
                            "issuingAuthority", "Insurer, Delhi"));
        }
        return l;
    }

    private MvcResult submit(Session s, String regime, List<Map<String, Object>> lines, int files)
            throws Exception {
        Map<String, Object> form = new HashMap<>();
        form.put("regime", regime);
        form.put("lines", lines);
        MockMultipartHttpServletRequestBuilder r = multipart("/v1/tax/declarations/mine");
        for (int i = 0; i < files; i++) {
            r.file(new MockMultipartFile("file", "proof" + i + ".pdf", "application/pdf", PDF));
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

    private MvcResult verify(Session s, long id, JsonNode round, String... amounts)
            throws Exception {
        List<Map<String, Object>> lines = new ArrayList<>();
        int i = 0;
        for (JsonNode l : round.path("lines")) {
            if (!l.path("frozen").asBoolean() && i < amounts.length) {
                lines.add(Map.of("lineId", l.path("id").asLong(), "verified", amounts[i++]));
            }
        }
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/tax/declarations/" + id + "/verify",
                                Map.of("lines", lines)))
                .andReturn();
    }

    private JsonNode mine(Session s) throws Exception {
        return body(mvc.perform(userGet(s, "/v1/tax/declarations/mine")).andReturn());
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    /** SC-08-0038 (I-07 main flow): declared, capped, verified, approved by another, frozen. */
    @Test
    void SRS_7_24_aRoundIsCappedVerifiedApprovedFrozenAndReplacedByTheNextRound() throws Exception {
        String login = "emp.rsharma@fci-demo.test";
        assertThat(officeOf(login)).isEqualTo("DH001");
        Session member = member(login);
        JsonNode form = mine(member);
        assertThat(form.path("finYear").asString()).isEqualTo(YEAR);
        assertThat(form.path("windowOpen").asBoolean()).isTrue();
        assertThat(form.path("proofsUntil").asString()).isEqualTo(proofDeadline());

        // SRS-7-16: 160,000 under a 150,000 limit is capped and shown, not refused.
        MvcResult first =
                submit(
                        member,
                        "OLD",
                        List.of(
                                line("INVESTMENT", "801IT", "160000", true),
                                line("INVESTMENT", "802IT", "20000", false),
                                line("HRA_RENT", "803IT", "120000", true)),
                        2);
        assertThat(status(first)).as(first.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode round = body(first);
        long id = round.path("id").asLong();
        JsonNode capped = round.path("lines").get(0);
        assertThat(capped.path("allowed").decimalValue()).isEqualByComparingTo("150000");
        assertThat(capped.path("capped").asBoolean()).isTrue();
        assertThat(capped.path("proofStatus").asString()).isEqualTo("PROOF_SUBMITTED");
        assertThat(round.path("lines").get(1).path("proofStatus").asString()).isEqualTo("DECLARED");
        // A round at the Maker may be replaced, but not once it is at the Checker.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM document_attachment WHERE entity_name ="
                                        + " 'it_declaration_submission' AND entity_key = ?",
                                Integer.class,
                                String.valueOf(id)))
                .isEqualTo(2);

        Session maker = maker();
        JsonNode page =
                body(
                        mvc.perform(userGet(maker, "/v1/tax/declarations?status=SUBMITTED"))
                                .andReturn());
        boolean listed = false;
        for (JsonNode r : page.path("items")) {
            listed |= r.path("id").asLong() == id;
        }
        assertThat(listed).isTrue();
        // Verified above the allowed amount, or with a line missing: refused, by name.
        assertThat(error(verify(maker, id, round, "150000.01", "20000", "120000")))
                .isEqualTo("TAX-422-VERIFIED-AMOUNT");
        assertThat(error(verify(maker, id, round, "150000", "20000")))
                .isEqualTo("TAX-422-VERIFIED-AMOUNT");
        MvcResult verified = verify(maker, id, round, "150000", "20000", "120000");
        assertThat(status(verified)).as(verified.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(body(verified).path("status").asString()).isEqualTo("VERIFIED");
        long approval =
                jdbc.queryForObject(
                        "SELECT approval_id FROM it_declaration_submission WHERE submission_id = ?",
                        Long.class,
                        id);
        assertThat(error(submit(member, "OLD", List.of(), 0)))
                .isEqualTo("TAX-409-DECLARATION-PENDING");
        // The Maker never decides the item; the Checker of the unit does.
        assertThat(decide(maker, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);

        JsonNode after = mine(member);
        assertThat(after.path("regime").asString()).isEqualTo("OLD");
        assertThat(after.path("regimeFrozen").asBoolean()).isTrue();
        assertThat(after.path("rounds").get(0).path("status").asString()).isEqualTo("APPROVED");

        // BR-TAX-001 / SRS-7-15: the regime and the rent are frozen for the year.
        assertThat(error(submit(member, "NEW", List.of(), 0))).isEqualTo("TAX-409-REGIME-FROZEN");
        assertThat(error(submit(member, "OLD", List.of(line("HRA_RENT", "803IT", "1", false)), 0)))
                .isEqualTo("TAX-409-RENT-FROZEN");
        // A later round (a mid-year revision) carries the frozen rent line forward as approved.
        MvcResult second =
                submit(member, null, List.of(line("INVESTMENT", "801IT", "50000", false)), 0);
        assertThat(status(second)).as(second.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode next = body(second);
        assertThat(next.path("number").asInt()).isEqualTo(2);
        assertThat(next.path("lines")).hasSize(2);
        JsonNode rent = next.path("lines").get(1);
        assertThat(rent.path("frozen").asBoolean()).isTrue();
        assertThat(rent.path("verified").decimalValue()).isEqualByComparingTo("120000");
        // The approved round stays until the new one is approved (TDS reads the approved one).
        assertThat(mine(member).path("rounds").get(1).path("status").asString())
                .isEqualTo("APPROVED");
        long secondId = next.path("id").asLong();
        assertThat(status(verify(maker, secondId, next, "50000"))).isEqualTo(202);
        long secondApproval =
                jdbc.queryForObject(
                        "SELECT approval_id FROM it_declaration_submission WHERE submission_id = ?",
                        Long.class,
                        secondId);
        assertThat(decide(checker(), secondApproval, "APPROVE", null, null)).isEqualTo(200);
        JsonNode rounds = mine(member).path("rounds");
        assertThat(rounds.get(0).path("status").asString()).isEqualTo("APPROVED");
        assertThat(rounds.get(1).path("status").asString()).isEqualTo("SUPERSEDED");

        // The member reads the proof of the frozen rent line through the later round.
        long proof = rent.path("proofId").asLong();
        assertThat(
                        download(member, "/v1/tax/declarations/" + secondId + "/proofs/" + proof)
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);

        // FRS 6.14.6.1: a reset proposed by the Maker and approved by the Checker opens the regime.
        MvcResult reset =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/tax/regimes/reset",
                                        Map.of(
                                                "employeeId",
                                                employeeOf(login),
                                                "finYear",
                                                YEAR,
                                                "reason",
                                                "IT: wrong regime chosen")))
                        .andReturn();
        assertThat(status(reset)).as(reset.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(
                        decide(
                                checker(),
                                body(reset).path("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(mine(member).path("regimeFrozen").asBoolean()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reset_reason FROM it_regime_detail WHERE employee_id = ?"
                                        + " AND fin_year = ?",
                                String.class,
                                employeeOf(login),
                                YEAR))
                .isEqualTo("IT: wrong regime chosen");
        // SRS-7-14: the proof window in force when the rounds came is still open, so the line
        // without proof keeps its exemption.
        declarations.reverseUnprovedLines(YEAR, "it");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM it_declaration WHERE employee_id = ? AND"
                                        + " fin_year = ? AND proof_status = 'REVERSED'",
                                Integer.class,
                                employeeOf(login),
                                YEAR))
                .isZero();
    }

    @Test
    void SRS_7_24_aReturnedRoundCarriesTheReasonAndTheMemberSubmitsAgain() throws Exception {
        // tax.new@ (demo V039_9): a new-regime member of DH001 whose round waits for the Maker.
        String login = "tax.new@fci-demo.test";
        assertThat(officeOf(login)).isEqualTo("DH001");
        Session member = member(login);
        MvcResult first =
                submit(member, "NEW", List.of(line("INVESTMENT", "802IT", "30000", false)), 0);
        assertThat(status(first)).as(first.getResponse().getContentAsString()).isEqualTo(201);
        long id = body(first).path("id").asLong();
        // Capped at the BOTH-regime limit of 25,000; the waiting demo round was replaced.
        assertThat(body(first).path("lines").get(0).path("allowed").decimalValue())
                .isEqualByComparingTo("25000");
        assertThat(mine(member).path("rounds").get(1).path("status").asString())
                .isEqualTo("SUPERSEDED");
        Session maker = maker();
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        maker,
                                                        "/v1/tax/declarations/" + id + "/return",
                                                        Map.of()))
                                        .andReturn()))
                .isEqualTo(400);
        MvcResult returned =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/tax/declarations/" + id + "/return",
                                        Map.of("reason", "IT: proof does not match")))
                        .andReturn();
        assertThat(body(returned).path("status").asString()).isEqualTo("RETURNED");
        assertThat(mine(member).path("rounds").get(0).path("returnReason").asString())
                .isEqualTo("IT: proof does not match");
        // A returned round is not verified; another office reads it as missing.
        assertThat(error(verify(maker, id, body(returned), "1")))
                .isEqualTo("TAX-409-DECLARATION-STATE");
        MvcResult own = mvc.perform(userGet(maker, "/v1/tax/declarations/" + id)).andReturn();
        assertThat(status(own)).isEqualTo(200);
        assertThat(body(own).path("returnReason").asString()).isEqualTo("IT: proof does not match");
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(status(mvc.perform(userGet(kolkata, "/v1/tax/declarations/" + id)).andReturn()))
                .isEqualTo(404);
        MvcResult again =
                submit(member, "NEW", List.of(line("INVESTMENT", "802IT", "25000", false)), 0);
        assertThat(status(again)).isEqualTo(201);
        assertThat(body(again).path("number").asInt()).isEqualTo(3);
    }

    /**
     * SRS-NF-26 (l.473; SRS-7-24, BR-TAX-014): two Payroll Makers of the office open the same
     * round; the first verifies it, so the second's save, made against the round as it was, is
     * refused by name - neither a return nor a second verification - and the round stays as the
     * first Maker left it. tax.nopan@ (870003, DH001) has no round in the demo.
     */
    @Test
    void SRS_NF_26_aDeclarationReviewSavedAgainstAStaleRoundIsRefused() throws Exception {
        String login = "tax.nopan@fci-demo.test";
        assertThat(officeOf(login)).isEqualTo("DH001");
        MvcResult raised =
                submit(
                        member(login),
                        "OLD",
                        List.of(line("INVESTMENT", "802IT", "10000", false)),
                        0);
        assertThat(status(raised)).as(raised.getResponse().getContentAsString()).isEqualTo(201);
        long id = body(raised).path("id").asLong();
        Session first = maker();
        Session second = login("tax.maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode stale =
                body(mvc.perform(userGet(second, "/v1/tax/declarations/" + id)).andReturn());
        assertThat(stale.path("status").asString()).isEqualTo("SUBMITTED");
        JsonNode seen = body(mvc.perform(userGet(first, "/v1/tax/declarations/" + id)).andReturn());
        MvcResult verified = verify(first, id, seen, "10000");
        assertThat(status(verified)).as(verified.getResponse().getContentAsString()).isEqualTo(202);
        long approval =
                jdbc.queryForObject(
                        "SELECT approval_id FROM it_declaration_submission WHERE submission_id = ?",
                        Long.class,
                        id);
        try {
            MvcResult returned =
                    mvc.perform(
                                    userPost(
                                            second,
                                            "/v1/tax/declarations/" + id + "/return",
                                            Map.of("reason", "IT: proof missing")))
                            .andReturn();
            assertThat(status(returned)).isEqualTo(409);
            assertThat(error(returned)).isEqualTo("TAX-409-DECLARATION-STATE");
            MvcResult again = verify(second, id, stale, "5000");
            assertThat(status(again)).isEqualTo(409);
            assertThat(error(again)).isEqualTo("TAX-409-DECLARATION-STATE");
            JsonNode now =
                    body(mvc.perform(userGet(second, "/v1/tax/declarations/" + id)).andReturn());
            assertThat(now.path("status").asString()).isEqualTo("VERIFIED");
            assertThat(now.path("verifiedBy").asString()).isEqualTo("maker.delhi@fci-demo.test");
            assertThat(
                            now.path("returnReason").isNull()
                                    || now.path("returnReason").isMissingNode())
                    .isTrue();
            assertThat(now.path("lines").get(0).path("verified").decimalValue())
                    .isEqualByComparingTo("10000");
        } finally {
            // Nothing left waiting at the Checker for the member.
            decide(checker(), approval, "REJECT", "IT: stale-save test round", null);
        }
    }

    @Test
    void SRS_7_20_eachRefusalIsNamed() throws Exception {
        Session member = member("eseva.ld001@fci-demo.test");
        assertThat(error(submit(member, null, List.of(), 0))).isEqualTo("TAX-422-REGIME-REQUIRED");
        // 801IT applies under the old regime only; 899IT is not in the master.
        assertThat(
                        error(
                                submit(
                                        member,
                                        "NEW",
                                        List.of(line("INVESTMENT", "801IT", "1", false)),
                                        0)))
                .isEqualTo("TAX-422-DECLARATION-SECTION");
        assertThat(
                        error(
                                submit(
                                        member,
                                        "OLD",
                                        List.of(line("INVESTMENT", "899IT", "1", false)),
                                        0)))
                .isEqualTo("TAX-422-DECLARATION-SECTION");
        assertThat(
                        error(
                                submit(
                                        member,
                                        "OLD",
                                        List.of(
                                                line("INVESTMENT", "801IT", "1", false),
                                                line("INVESTMENT", "801IT", "2", false)),
                                        0)))
                .isEqualTo("TAX-422-DECLARATION-DUPLICATE");
        // The request boundary: no amount above zero, paise beyond two places, a file without a
        // line naming it.
        assertThat(
                        status(
                                submit(
                                        member,
                                        "OLD",
                                        List.of(line("INVESTMENT", "801IT", "0", false)),
                                        0)))
                .isEqualTo(400);
        assertThat(
                        status(
                                submit(
                                        member,
                                        "OLD",
                                        List.of(line("INVESTMENT", "801IT", "1.005", false)),
                                        0)))
                .isEqualTo(400);
        assertThat(
                        status(
                                submit(
                                        member,
                                        "OLD",
                                        List.of(line("INVESTMENT", "801IT", "1", false)),
                                        1)))
                .isEqualTo(400);
        assertThat(mine(member).path("rounds")).isEmpty();
    }

    @Test
    void SRS_22_21_whoMayDoWhat() throws Exception {
        // Finance reads I-07 and neither returns nor resets.
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        assertThat(status(mvc.perform(userGet(finance, "/v1/tax/declarations")).andReturn()))
                .isEqualTo(200);
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        finance,
                                                        "/v1/tax/declarations/1/return",
                                                        Map.of("reason", "IT")))
                                        .andReturn()))
                .isEqualTo(403);
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        finance,
                                                        "/v1/tax/regimes/reset",
                                                        Map.of(
                                                                "employeeId",
                                                                1,
                                                                "finYear",
                                                                YEAR,
                                                                "reason",
                                                                "IT")))
                                        .andReturn()))
                .isEqualTo(403);
        // A member reads only the own declarations, never the I-07 register.
        Session member = member("emp.rsharma@fci-demo.test");
        assertThat(status(mvc.perform(userGet(member, "/v1/tax/declarations")).andReturn()))
                .isEqualTo(403);
        // The Payroll Maker has no eSeva record of a member's own.
        assertThat(status(mvc.perform(userGet(maker(), "/v1/tax/declarations/mine")).andReturn()))
                .isIn(403, 404);
    }
}
