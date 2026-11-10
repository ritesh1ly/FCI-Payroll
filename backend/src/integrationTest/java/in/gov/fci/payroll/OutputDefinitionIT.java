package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * M-17 Statutory Output Definitions (docs/plan/08-income-tax.md phase 08f; SRS M-17, BR-TAX-013,
 * SRS-7-5, SRS-7-8, SRS-7-30): the catalogue of the statutory outputs, seeded without a version and
 * so blocked; an effective-dated version proposed by the Rule Administrator at Headquarters and
 * approved by a different Rule Approver (route M17_VERSION); a later version ends the earlier one.
 * Uses FORM_12BA only (Form16IT and ReturnIT version their own outputs); the accounts are the tax
 * Rule Administrator and Approver of the hierarchy seed V039_11_9.
 */
class OutputDefinitionIT extends ApiTestSupport {

    private static final String OUTPUT = "FORM_12BA";

    private Session admin() throws Exception {
        return login("tax.ruleadmin@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
    }

    private Session approver() throws Exception {
        return login("tax.ruleapprover@fci-demo.test", "RULE_APPROVER", "HQ001");
    }

    private String payCode() {
        return String.valueOf(
                jdbc.queryForObject(
                        "SELECT MIN(legacy_pay_code) FROM pay_component", Integer.class));
    }

    /**
     * A field of the field-source model. Nothing seeds the register yet (tracker PENDING: the
     * field-source model is the configuration module's), so the test registers the PAN, an HRMS
     * field, once.
     */
    private String fieldSource() {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM field_source_registry WHERE entity_name = 'employee'"
                                + " AND field_name = 'pan_number'",
                        Integer.class)
                == 0) {
            jdbc.update(
                    "INSERT INTO field_source_registry (entity_name, field_name, source_code,"
                            + " owning_system, correction_screen, is_editable) VALUES ('employee',"
                            + " 'pan_number', 'HRMS', 'HRMS', 'I-21', 'N')");
        }
        return "employee.pan_number";
    }

    private Map<String, Object> version(LocalDate from, List<Map<String, Object>> fields) {
        Map<String, Object> v = new HashMap<>();
        v.put("effectiveFrom", from.toString());
        v.put("layoutFormat", "PDF");
        v.put("layoutNote", "Layout supplied by FCI (IT)");
        v.put("periodLockRequired", true);
        v.put("fields", fields);
        v.put(
                "controls",
                List.of(
                        Map.of(
                                "label", "TDS",
                                "reconcilesAgainst", "POSTED_PAYROLL",
                                "tolerance", "0.00",
                                "releaseGate", true)));
        v.put("reason", "IT: layout of the year");
        return v;
    }

    private List<Map<String, Object>> fields() {
        return List.of(
                field("Basic pay", "COMPONENT", payCode()),
                field("Source field", "FIELD_SOURCE", fieldSource()),
                field("Form", "LITERAL", "Form 12BA"),
                field("Total", "AGGREGATE", "SUM"));
    }

    private static Map<String, Object> field(String label, String binding, String reference) {
        Map<String, Object> f = new HashMap<>();
        f.put("label", label);
        f.put("binding", binding);
        f.put("reference", reference);
        return f;
    }

    private MvcResult propose(Session s, String key, Map<String, Object> body) throws Exception {
        return mvc.perform(userPost(s, "/v1/tax/output-definitions/" + key + "/versions", body))
                .andReturn();
    }

    private JsonNode output(Session s, String key) throws Exception {
        MvcResult r = mvc.perform(userGet(s, "/v1/tax/output-definitions")).andReturn();
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
        for (JsonNode o : body(r)) {
            if (key.equals(o.path("key").asString())) {
                return o;
            }
        }
        throw new AssertionError(key + " is not in the catalogue");
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    /** Proposed by the Administrator and approved by the Approver; the approval id. */
    private long approved(Session admin, LocalDate from) throws Exception {
        MvcResult p = propose(admin, OUTPUT, version(from, fields()));
        assertThat(p.getResponse().getStatus())
                .as(p.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(p).path("approvalId").asLong();
        assertThat(decide(approver(), approval, "APPROVE", "IT: layout checked", null))
                .isEqualTo(200);
        return approval;
    }

    @Test
    void BR_MIS_002_SRS_7_6_theCatalogueIsTheStatutoryOutputsAndOneWithoutAVersionIsBlocked()
            throws Exception {
        Session admin = admin();
        JsonNode all = body(mvc.perform(userGet(admin, "/v1/tax/output-definitions")).andReturn());
        assertThat(texts(all, "key"))
                .contains(
                        "FORM16_PART_B",
                        "FORM_12BA",
                        "FORM_12BB",
                        "FORM_10E",
                        "FORM_24Q",
                        "FORM_24Q_CORRECTION",
                        "IT_TDS_STATEMENT",
                        "PT_SCHEDULE");
        // No test versions these two: blocked, never generated on an assumed layout.
        // SRS-7-6: Form 10E (Section 89(1) relief) is catalogued and, with no layout supplied,
        // blocked like the other unversioned outputs (PENDING 08i).
        for (String key : List.of("IT_TDS_STATEMENT", "PT_SCHEDULE", "FORM_10E")) {
            JsonNode o = output(admin, key);
            assertThat(o.path("state").asString()).isEqualTo("BLOCKED");
            assertThat(o.path("versions")).isEmpty();
        }
        // The payroll desk reads it; the member's eSeva does not.
        // The maker reads the same catalogue with the same blocked state (not a 200 alone).
        Session maker = login("tax.maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult read = mvc.perform(userGet(maker, "/v1/tax/output-definitions")).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(texts(body(read), "key")).containsExactlyInAnyOrderElementsOf(texts(all, "key"));
        assertThat(output(maker, "FORM_10E").path("state").asString()).isEqualTo("BLOCKED");
        Session member = login("tax.old@fci-demo.test", "EMPLOYEE_ESEVA", null);
        MvcResult refused = mvc.perform(userGet(member, "/v1/tax/output-definitions")).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(error(refused)).isEqualTo("AUTHZ-403-PERMISSION");
    }

    /**
     * SC-08-0031 (M-17 main flow): the Rule Administrator proposes an effective-dated version, a
     * different Rule Approver approves it, and a later version ends the earlier one.
     */
    @Test
    void BR_TAX_013_aVersionIsApprovedByADifferentRuleApproverAndALaterOneEndsIt()
            throws Exception {
        LocalDate first = TaxYearFixtures.april(TaxYearFixtures.yearBack(30));
        LocalDate second = TaxYearFixtures.april(TaxYearFixtures.yearBack(29));
        Session admin = admin();
        MvcResult p = propose(admin, OUTPUT, version(first, fields()));
        assertThat(p.getResponse().getStatus())
                .as(p.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(p).path("approvalId").asLong();
        // One proposal at a time; listed as waiting on the catalogue.
        assertThat(propose(admin, OUTPUT, version(first, fields())).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(
                        output(admin, OUTPUT)
                                .path("pendingApprovals")
                                .valueStream()
                                .map(JsonNode::asLong))
                .contains(approval);
        // The Administrator never decides; the Approver does.
        assertThat(decide(admin, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(approver(), approval, "APPROVE", "IT: layout checked", null))
                .isEqualTo(200);
        JsonNode o = output(admin, OUTPUT);
        assertThat(o.path("state").asString()).isEqualTo("ACTIVE");
        JsonNode v1 = o.path("versions").get(0);
        assertThat(v1.path("number").asInt()).isEqualTo(1);
        assertThat(v1.path("effectiveFrom").asString()).isEqualTo(first.toString());
        assertThat(v1.path("fields")).hasSize(4);
        assertThat(texts(v1.path("fields"), "reference")).contains(payCode(), fieldSource(), "SUM");
        assertThat(v1.path("controls").get(0).path("reconcilesAgainst").asString())
                .isEqualTo("POSTED_PAYROLL");
        // Never on or before the latest version's start (SRS M-17: periods keep their version).
        assertThat(error(propose(admin, OUTPUT, version(first, fields()))))
                .isEqualTo("TAX-409-OUTPUT-ORDER");
        approved(admin, second);
        JsonNode both = output(admin, OUTPUT).path("versions");
        assertThat(both).hasSize(2);
        assertThat(both.get(0).path("number").asInt()).isEqualTo(2);
        assertThat(both.get(1).path("effectiveTo").asString())
                .isEqualTo(second.minusDays(1).toString());
        assertThat(both.get(1).path("inForce").asBoolean()).isFalse();
        assertThat(both.get(0).path("inForce").asBoolean()).isTrue();
    }

    @Test
    void BR_TAX_013_everyFieldBindsToTheCatalogueOrTheFieldSourceModel() throws Exception {
        Session admin = admin();
        LocalDate later = TaxYearFixtures.april(TaxYearFixtures.yearBack(-5));
        assertThat(
                        error(
                                propose(
                                        admin,
                                        OUTPUT,
                                        version(later, List.of(field("Pay", "COMPONENT", "0"))))))
                .isEqualTo("TAX-422-OUTPUT-BINDING");
        assertThat(
                        error(
                                propose(
                                        admin,
                                        OUTPUT,
                                        version(
                                                later,
                                                List.of(
                                                        field(
                                                                "PAN",
                                                                "FIELD_SOURCE",
                                                                "employee.no_such_field"))))))
                .isEqualTo("TAX-422-OUTPUT-BINDING");
        assertThat(
                        error(
                                propose(
                                        admin,
                                        OUTPUT,
                                        version(
                                                later,
                                                List.of(field("Total", "AGGREGATE", "AVG"))))))
                .isEqualTo("TAX-422-OUTPUT-BINDING");
        // The request boundary: a layout outside the DDL's, no field at all.
        Map<String, Object> docx = version(later, fields());
        docx.put("layoutFormat", "DOCX");
        assertThat(propose(admin, OUTPUT, docx).getResponse().getStatus()).isEqualTo(400);
        assertThat(propose(admin, OUTPUT, version(later, List.of())).getResponse().getStatus())
                .isEqualTo(400);
        // An output outside the catalogue; a proposal from the payroll desk.
        assertThat(propose(admin, "FORM_99", version(later, fields())).getResponse().getStatus())
                .isEqualTo(404);
        Session maker = login("tax.maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(propose(maker, OUTPUT, version(later, fields())).getResponse().getStatus())
                .isEqualTo(403);
    }
}
