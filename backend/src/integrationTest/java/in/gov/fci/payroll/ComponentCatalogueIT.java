package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * M-07 Component Catalogue end to end (plan 06 phase 06d; SRS-19-1, SRS-G-42, FAP-14): the Rule
 * Administrator defines, revises and retires a pay component; the Rule Approver approves; a code is
 * never reused; a revision adds payroll types only; the basis rule exists; one open change per
 * code; the payroll roles only read. Pay codes 971-979 and 988 are used by this class alone.
 */
class ComponentCatalogueIT extends ApiTestSupport {

    @Autowired private SystemParameters settings;
    @Autowired private in.gov.fci.payroll.configuration.api.PayConfiguration configuration;

    private LocalDate today;

    @BeforeEach
    void day() {
        today = settings.today();
    }

    private Session admin() throws Exception {
        return login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
    }

    private Session approver() throws Exception {
        return login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
    }

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private static Map<String, Object> component(int code, List<String> types, String basis) {
        Map<String, Object> b = new HashMap<>();
        b.put("payCode", code);
        b.put("name", "IT allowance " + code);
        b.put("type", "EARNING");
        b.put("payslipGroup", "ALLOWANCES");
        b.put("basisRuleKey", basis);
        b.put("source", "RULE");
        b.put("naturalAccount", "NA-4" + code);
        b.put("taxable", "Y");
        b.put("inCpfBase", false);
        b.put("payrollTypes", types);
        b.put("reason", "IT component");
        return b;
    }

    private Map<String, Object> component(int code, List<String> types) {
        // SRS l.1907: Driver / Basis is mandatory; a demo rule of the catalogue (V032_9).
        Map<String, Object> b = component(code, types, "CPF.SANCTION.LIMIT");
        b.put("effectiveFrom", today.toString());
        return b;
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private long submitted(MvcResult r) throws Exception {
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(202);
        return body(r).get("id").asLong();
    }

    private JsonNode row(Session s, int code) throws Exception {
        MvcResult r = mvc.perform(userGet(s, "/v1/pay-components?size=100&page=0")).andReturn();
        assertThat(status(r)).isEqualTo(200);
        int pages = (int) Math.ceil(body(r).get("total").asInt() / 100.0);
        for (int page = 0; page < pages; page++) {
            JsonNode items =
                    body(mvc.perform(userGet(s, "/v1/pay-components?size=100&page=" + page))
                                    .andReturn())
                            .get("items");
            for (JsonNode c : items) {
                if (c.get("payCode").asInt() == code) {
                    return c;
                }
            }
        }
        return null;
    }

    private List<String> engines(int code) {
        return jdbc.queryForList(
                "SELECT e.payroll_type FROM pay_component_engine e JOIN pay_component c ON"
                        + " c.component_id = e.component_id WHERE c.legacy_pay_code = ? ORDER BY 1",
                String.class,
                code);
    }

    /**
     * UC M-07 verify (SC-06-0023; SRS-G-42, SRS-19-1): an activated component is available to the
     * computation from its effective date - the staff engine's configuration for that day maps the
     * pay code (and its version enters the run's version-set hash) - and is not before the approval
     * nor before its effective date.
     */
    @Test
    void SC_06_0023_anActivatedComponentIsOfferedToTheEngineFromItsEffectiveDate()
            throws Exception {
        Session admin = admin();
        Map<String, Object> body = component(988, List.of("STAFF"));
        LocalDate from = today.plusDays(1);
        body.put("effectiveFrom", from.toString());
        long id = submitted(post(admin, "/v1/pay-components", body));
        var before = configuration.staffConfiguration(from);
        assertThat(before.componentIds()).as("not before the approval").doesNotContainKey(988);
        assertThat(decide(approver(), id, "APPROVE", null, null)).isEqualTo(200);
        var after = configuration.staffConfiguration(from);
        assertThat(after.componentIds()).containsKey(988);
        assertThat(after.versionSetHash()).isNotEqualTo(before.versionSetHash());
        assertThat(configuration.staffConfiguration(today).componentIds())
                .as("not before its effective date")
                .doesNotContainKey(988);
        assertThat(engines(988)).containsExactly("STAFF");
    }

    // SC-06-0022: a pay code duplicating an existing head is rejected.
    @Test
    void SRS_G_42_aComponentIsDefinedOnApprovalAndItsCodeIsNeverReused() throws Exception {
        Session admin = admin();
        long id =
                submitted(post(admin, "/v1/pay-components", component(971, List.of("DPS_LABOUR"))));
        // Nothing in the catalogue until approved (plan 06 D-7); a second proposal waits.
        assertThat(row(admin, 971)).isNull();
        MvcResult again = post(admin, "/v1/pay-components", component(971, List.of("DPS_LABOUR")));
        assertThat(status(again)).isEqualTo(409);
        assertThat(error(again)).isEqualTo("MST-409-COMPONENT-PENDING");
        assertThat(decide(approver(), id, "APPROVE", null, null)).isEqualTo(200);
        admin = admin();
        JsonNode c = row(admin, 971);
        assertThat(c.get("name").asString()).isEqualTo("IT allowance 971");
        assertThat(c.get("labourOnly").asBoolean()).isTrue();
        assertThat(c.get("pending").asBoolean()).isFalse();
        // FAP-14: a labour-only code stays off the staff engine.
        assertThat(engines(971)).containsExactly("DPS_LABOUR");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'pay_component' AND entity_key = '971' AND field_name"
                                        + " = 'component'",
                                Integer.class))
                .isOne();
        // SRS-G-42: the code is never reused.
        MvcResult reuse = post(admin, "/v1/pay-components", component(971, List.of("STAFF")));
        assertThat(status(reuse)).isEqualTo(409);
        assertThat(error(reuse)).isEqualTo("MST-409-COMPONENT-CODE");
        // A code on the retired list of the migration is never reused either.
        jdbc.update(
                "INSERT INTO retired_pay_code (legacy_pay_code, retirement_reason) VALUES (977,"
                        + " 'IT legacy code')");
        MvcResult listed = post(admin, "/v1/pay-components", component(977, List.of("STAFF")));
        assertThat(error(listed)).isEqualTo("MST-409-COMPONENT-CODE");
        // The class filter.
        JsonNode labour =
                body(mvc.perform(userGet(admin, "/v1/pay-components?class=LABOUR&size=100"))
                                .andReturn())
                        .get("items");
        assertThat(labour.findValuesAsString("labourOnly")).doesNotContain("false");
    }

    // SC-06-0030: at least one applicable payroll type.
    @Test
    void FAP_14_aRevisionAddsPayrollTypesAndNeverRemovesOne() throws Exception {
        Session admin = admin();
        long id = submitted(post(admin, "/v1/pay-components", component(972, List.of("STAFF"))));
        assertThat(decide(approver(), id, "APPROVE", null, null)).isEqualTo(200);
        admin = admin();
        MvcResult remove =
                post(
                        admin,
                        "/v1/pay-components/972/revisions",
                        component(972, List.of("DPS_LABOUR")));
        assertThat(status(remove)).isEqualTo(422);
        assertThat(error(remove)).isEqualTo("MST-422-COMPONENT-DEF");
        MvcResult mismatch =
                post(admin, "/v1/pay-components/973/revisions", component(972, List.of("STAFF")));
        assertThat(status(mismatch)).isEqualTo(400);
        Map<String, Object> revision = component(972, List.of("STAFF", "NWNP_LABOUR"));
        revision.put("name", "IT allowance revised");
        long rev = submitted(post(admin, "/v1/pay-components/972/revisions", revision));
        assertThat(decide(approver(), rev, "APPROVE", null, null)).isEqualTo(200);
        admin = admin();
        JsonNode c = row(admin, 972);
        assertThat(c.get("name").asString()).isEqualTo("IT allowance revised");
        assertThat(c.get("versionNo").asInt()).isEqualTo(2);
        assertThat(engines(972)).containsExactly("NWNP_LABOUR", "STAFF");
    }

    /**
     * SRS-LV-15 / BR-LEAVE-007 (US-GAP-298): "Subject to LWP" is an M-07 attribute set under
     * maker-checker; a revision that does not name it keeps it, one that does changes it.
     */
    @Test
    void SRS_LV_15_theComponentsSubjectToLwpAreMarkedOnM07() throws Exception {
        Session admin = admin();
        Map<String, Object> marked = component(985, List.of("STAFF"));
        marked.put("subjectToLwp", true);
        long id = submitted(post(admin, "/v1/pay-components", marked));
        assertThat(lwp(985)).as("nothing changes before the approval").isNull();
        assertThat(decide(approver(), id, "APPROVE", null, null)).isEqualTo(200);
        admin = admin();
        assertThat(row(admin, 985).get("subjectToLwp").asBoolean()).isTrue();
        assertThat(lwp(985)).isEqualTo("Y");
        Map<String, Object> silent = component(985, List.of("STAFF"));
        silent.put("name", "IT allowance renamed");
        long keep = submitted(post(admin, "/v1/pay-components/985/revisions", silent));
        assertThat(decide(approver(), keep, "APPROVE", null, null)).isEqualTo(200);
        assertThat(lwp(985)).as("a revision not naming it keeps it").isEqualTo("Y");
        Map<String, Object> unmark = component(985, List.of("STAFF"));
        unmark.put("subjectToLwp", false);
        long off = submitted(post(admin(), "/v1/pay-components/985/revisions", unmark));
        assertThat(decide(approver(), off, "APPROVE", null, null)).isEqualTo(200);
        assertThat(lwp(985)).isEqualTo("N");
        // A definition that does not name it is not subject to LWP.
        long plain =
                submitted(post(admin(), "/v1/pay-components", component(986, List.of("STAFF"))));
        assertThat(decide(approver(), plain, "APPROVE", null, null)).isEqualTo(200);
        assertThat(lwp(986)).isEqualTo("N");
    }

    private String lwp(int code) {
        return jdbc
                .queryForList(
                        "SELECT lwp_subject_flag FROM pay_component WHERE legacy_pay_code = ?",
                        String.class,
                        code)
                .stream()
                .findFirst()
                .orElse(null);
    }

    @Test
    void SRS_G_42_aCodeIsRetiredEndDatedAndNeverDeleted() throws Exception {
        Session admin = admin();
        long id = submitted(post(admin, "/v1/pay-components", component(974, List.of("STAFF"))));
        assertThat(decide(approver(), id, "APPROVE", null, null)).isEqualTo(200);
        admin = admin();
        Map<String, Object> future =
                Map.of("lastDay", today.plusDays(1).toString(), "reason", "IT");
        assertThat(error(post(admin, "/v1/pay-components/974/retirements", future)))
                .isEqualTo("MST-422-COMPONENT-DEF");
        Map<String, Object> before =
                Map.of("lastDay", today.minusDays(1).toString(), "reason", "IT");
        assertThat(error(post(admin, "/v1/pay-components/974/retirements", before)))
                .isEqualTo("MST-422-COMPONENT-DEF");
        long retire =
                submitted(
                        post(
                                admin,
                                "/v1/pay-components/974/retirements",
                                Map.of("lastDay", today.toString(), "reason", "IT merged")));
        assertThat(decide(approver(), retire, "APPROVE", null, null)).isEqualTo(200);
        admin = admin();
        JsonNode c = row(admin, 974);
        assertThat(c.get("retired").asBoolean()).isTrue();
        assertThat(c.get("effectiveTo").asString()).isEqualTo(today.toString());
        assertThat(engines(974)).containsExactly("STAFF");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT retirement_reason FROM retired_pay_code WHERE"
                                        + " legacy_pay_code = 974",
                                String.class))
                .isEqualTo("IT merged");
        // A retired code is not revised and not retired again.
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/pay-components/974/revisions",
                                        component(974, List.of("STAFF")))))
                .isEqualTo(422);
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/pay-components/974/retirements",
                                        Map.of("lastDay", today.toString(), "reason", "again"))))
                .isEqualTo(422);
        assertThat(
                        status(
                                post(
                                        admin,
                                        "/v1/pay-components/979/retirements",
                                        Map.of("lastDay", today.toString(), "reason", "x"))))
                .isEqualTo(404);
    }

    // SC-06-0021 (a basis that is no rule of the catalogue is rejected), SC-06-0026 / SC-06-0039
    // (the driver is resolved from the Rule Engine).
    @Test
    void SRS_19_1_theBasisIsARuleOfTheCatalogueAndItsUsesAreListed() throws Exception {
        Session admin = admin();
        Map<String, Object> unknown = component(975, List.of("STAFF"), "IT.NO.SUCH.RULE");
        unknown.put("effectiveFrom", today.toString());
        assertThat(error(post(admin, "/v1/pay-components", unknown)))
                .isEqualTo("MST-422-COMPONENT-DEF");
        String key = jdbc.queryForObject("SELECT MIN(rule_key) FROM rule_definition", String.class);
        Map<String, Object> known = component(975, List.of("STAFF"), key);
        known.put("effectiveFrom", today.toString());
        long id = submitted(post(admin, "/v1/pay-components", known));
        assertThat(decide(approver(), id, "APPROVE", null, null)).isEqualTo(200);
        admin = admin();
        assertThat(row(admin, 975).get("basisRuleKey").asString()).isEqualTo(key);
        // Where used: the penalty recovery list names its demo head 912 (V044_2_9).
        JsonNode uses =
                body(mvc.perform(userGet(admin, "/v1/pay-components/912/usages")).andReturn());
        assertThat(uses.findValuesAsString("consumerKey")).contains("PENALTY_RECOVERY");
        // SRS l.1887, M07-05: the rule the component names is registered at approval.
        assertThat(
                        body(mvc.perform(userGet(admin, "/v1/pay-components/975/usages"))
                                        .andReturn())
                                .findValuesAsString("consumerKey"))
                .containsExactly(key);
        // SRS l.1907, SC-06-0026: no driver, no component.
        Map<String, Object> none = component(978, List.of("STAFF"), null);
        none.put("effectiveFrom", today.toString());
        assertThat(status(post(admin, "/v1/pay-components", none))).isEqualTo(422);
    }

    @Test
    void SRS_19_1_onlyTheRuleAdministratorProposesAndNoOneApprovesTheOwnChange() throws Exception {
        Session maker = maker();
        // OD-125 (Wireframes v6.1 role access, V078_4): M-07 is drawn for the Payroll Maker (read
        // only; proposals stay with the Rule Administrator); WF-071 for FCI.
        assertThat(status(mvc.perform(userGet(maker, "/v1/pay-components")).andReturn()))
                .isEqualTo(200);
        assertThat(status(post(maker, "/v1/pay-components", component(976, List.of("STAFF")))))
                .isEqualTo(403);
        Session approver = approver();
        assertThat(status(mvc.perform(userGet(approver, "/v1/pay-components")).andReturn()))
                .isEqualTo(200);
        assertThat(status(post(approver, "/v1/pay-components", component(976, List.of("STAFF")))))
                .isEqualTo(403);
        Session admin = admin();
        Map<String, Object> bad = component(976, List.of());
        assertThat(status(post(admin, "/v1/pay-components", bad))).isEqualTo(400);
        Map<String, Object> hindi = component(976, List.of("STAFF"));
        hindi.put("name", "Bhatta भत्ता");
        assertThat(status(post(admin, "/v1/pay-components", hindi))).isEqualTo(400);
        long id = submitted(post(admin, "/v1/pay-components", component(976, List.of("STAFF"))));
        Session self = login("su.hq@fci-demo.test", "RULE_APPROVER", "HQ001");
        assertThat(decide(self, id, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(approver(), id, "REJECT", "IT: not needed", null)).isEqualTo(200);
        assertThat(row(admin(), 976)).isNull();
    }

    @Test
    void SRS_G_42_taxableInCpfBaseTypeAndAPayrollTypeAreMandatoryAndTheCodeIsOneToThreeDigits()
            throws Exception {
        Session admin = admin();
        // M-07 field table: Taxable, In CPF Base and Component Type are mandatory (l.1909-1916).
        for (String field : List.of("taxable", "inCpfBase", "type")) {
            Map<String, Object> missing = component(978, List.of("STAFF"));
            missing.remove(field);
            MvcResult r = post(admin, "/v1/pay-components", missing);
            assertThat(status(r)).as(field).isEqualTo(400);
            assertThat(error(r)).as(field).isEqualTo("COM-400-VALIDATION");
        }
        // Applicable Payroll Types: at least one (l.1911, FAP-14); the code is Numeric (3).
        Map<String, Object> noType = component(978, List.of("STAFF"));
        noType.remove("payrollTypes");
        MvcResult untyped = post(admin, "/v1/pay-components", noType);
        assertThat(status(untyped)).isEqualTo(400);
        assertThat(error(untyped)).isEqualTo("COM-400-VALIDATION");
        for (int code : List.of(0, 1000)) {
            MvcResult outOfRange =
                    post(admin, "/v1/pay-components", component(code, List.of("STAFF")));
            assertThat(status(outOfRange)).as(String.valueOf(code)).isEqualTo(400);
            assertThat(error(outOfRange)).as(String.valueOf(code)).isEqualTo("COM-400-VALIDATION");
        }
        assertThat(row(admin, 978)).isNull();
        // UC M-07: on approval the component appears in the catalogue with its source of value,
        // found by the Source filter that drives the field-source markers (l.1886).
        long id = submitted(post(admin, "/v1/pay-components", component(978, List.of("STAFF"))));
        JsonNode bySourceBefore =
                body(mvc.perform(userGet(admin, "/v1/pay-components?source=RULE&size=100"))
                                .andReturn())
                        .get("items");
        assertThat(bySourceBefore.findValues("payCode").stream().map(JsonNode::asInt))
                .doesNotContain(978);
        assertThat(decide(approver(), id, "APPROVE", null, null)).isEqualTo(200);
        admin = admin();
        JsonNode c = row(admin, 978);
        assertThat(c.get("source").asString()).isEqualTo("RULE");
        assertThat(c.get("taxable").asString()).isEqualTo("Y");
        assertThat(c.get("inCpfBase").asBoolean()).isFalse();
        assertThat(c.get("type").asString()).isEqualTo("EARNING");
        int total =
                body(mvc.perform(userGet(admin, "/v1/pay-components?source=RULE&size=10"))
                                .andReturn())
                        .get("total")
                        .asInt();
        boolean found = false;
        for (int page = 0; page * 100 < total && !found; page++) {
            JsonNode items =
                    body(mvc.perform(
                                            userGet(
                                                    admin,
                                                    "/v1/pay-components?source=RULE&size=100&page="
                                                            + page))
                                    .andReturn())
                            .get("items");
            found =
                    items.findValues("payCode").stream()
                            .map(JsonNode::asInt)
                            .anyMatch(v -> v == 978);
        }
        assertThat(found).isTrue();
        JsonNode other =
                body(mvc.perform(userGet(admin, "/v1/pay-components?source=HRMS&size=100"))
                                .andReturn())
                        .get("items");
        assertThat(other.findValues("payCode").stream().map(JsonNode::asInt)).doesNotContain(978);
    }
}
