package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 22 phase 22b (plan docs/plan/22-nwnp-labour.md D22-5, D22-6; SRS-5-1, SRS-5-2, SRS-5-3):
 * the Rule Administrator proposes Depot Okhla's NWNP ASOR rate set, every NWNP operation's Piece of
 * Rate with its Unit of Measure and every NWNP designation's MGW; the Rule Approver approves it;
 * the next set ends it the day before; refused: a missing MGW, a rate without a known unit, a date
 * not after the latest, a second proposal while one waits, a Labour Dealing Hand proposing.
 *
 * <p>Demo data V060_9 (NWNP designations), V061_9 (NWNP operations W2P / T2P / PBNW, units).
 */
class NwnpRateIT extends ApiTestSupport {

    /**
     * SRS-G-1: the refusal of a maker deciding their own item; the Rule Administrator holds no
     * approve permission on the route, so the refusal is the permission one.
     */
    private static final String OWN_ITEM_REFUSAL = "AUTHZ-403-PERMISSION";

    private Session admin() throws Exception {
        return login("tax.ruleadmin@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
    }

    private Session approver() throws Exception {
        return login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
    }

    private JsonNode options(Session s) throws Exception {
        return body(mvc.perform(userGet(s, "/v1/labour/nwnp-rate-sets/options")).andReturn());
    }

    private long okhla() {
        return jdbc.queryForObject(
                "SELECT location_id FROM org_location WHERE location_code = 'DP-DL01'", Long.class);
    }

    private Map<String, Object> set(JsonNode o, LocalDate from, String unit, int skipMgw) {
        List<Map<String, Object>> lines = new ArrayList<>();
        o.get("operations")
                .forEach(
                        op ->
                                lines.add(
                                        Map.of(
                                                "operationCode",
                                                op.get("code").asString(),
                                                "pieceOfRate",
                                                12.5,
                                                "unitOfMeasure",
                                                unit)));
        List<Map<String, Object>> mgw = new ArrayList<>();
        o.get("designations")
                .forEach(d -> mgw.add(Map.of("designationId", d.get("id").asLong(), "mgw", 700)));
        Map<String, Object> b = new HashMap<>();
        b.put("depotLocationId", okhla());
        b.put("version", "ASOR");
        b.put("effectiveFrom", from.toString());
        b.put("lines", lines);
        b.put("mgw", mgw.subList(0, mgw.size() - skipMgw));
        b.put("reason", "IT: NWNP ASOR " + from);
        return b;
    }

    private MvcResult propose(Session s, Map<String, Object> body) throws Exception {
        return mvc.perform(userPost(s, "/v1/labour/nwnp-rate-sets", body)).andReturn();
    }

    private String defect(MvcResult r) throws Exception {
        return body(r).get("args").get(0).asString();
    }

    // SC-22-0002, SC-22-0003, SC-22-0014, SC-22-0015, SC-22-0016, SC-22-0017, SC-22-0020
    @Test
    // SRS-5-11: the designation-wise MGW is held in the ASOR set (each NWNP designation's MGW);
    // SRS-G-1 (US-22-02 AC5): the Rule Administrator never approves their own set.
    void SRS_5_2_SRS_5_11_SRS_G_1_anNwnpRateSetIsProposedApprovedAndTheNextEndsIt()
            throws Exception {
        Session admin = admin();
        JsonNode o = options(admin);
        assertThat(o.get("operations")).hasSize(3);
        assertThat(o.get("designations")).hasSize(4);
        assertThat(o.get("unitsOfMeasure").valueStream().map(u -> u.get("code").asString()))
                .contains("PER_100_BAGS", "PER_HOUR");

        LocalDate first = LocalDate.of(2026, 5, 1);
        assertThat(defect(propose(admin, set(o, first, "PER_100_BAGS", 1)))).isEqualTo("RATES");
        assertThat(defect(propose(admin, set(o, first, "PER_TONNE", 0))))
                .isEqualTo("UNIT_OF_MEASURE");
        assertThat(
                        propose(
                                        login(
                                                "ldh.depot@fci-demo.test",
                                                "LABOUR_DEALING_HAND",
                                                "DP-DL01"),
                                        set(o, first, "PER_100_BAGS", 0))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);

        MvcResult sent = propose(admin, set(o, first, "PER_100_BAGS", 0));
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(defect(propose(admin, set(o, first.plusMonths(1), "PER_HOUR", 0))))
                .as("one proposal waits at a time")
                .isEqualTo("PENDING");
        // BMAD TR-058 (SRS-G-1 l.517): the maker's own decision is refused (403 and its code),
        // and the set still waits for the Rule Approver.
        long approval = body(sent).get("id").asLong();
        MvcResult own =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(own.getResponse().getStatus())
                .as("the maker never approves their own set")
                .isEqualTo(403);
        assertThat(body(own).path("errorCode").asString()).isEqualTo(OWN_ITEM_REFUSAL);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(decide(approver(), approval, "APPROVE", null, null)).isEqualTo(200);

        // SRS-5-2 (l.4847): an effective date earlier than the latest is refused. BMAD TR-057: a
        // set dated the same day as the latest is not stated by the source (CLR-143); the unit test
        // LabourRateRulesTest marks the as-built refusal.
        assertThat(defect(propose(admin, set(o, first.minusDays(1), "PER_HOUR", 0))))
                .isEqualTo("EFFECTIVE_BEFORE_LATEST");
        LocalDate second = first.plusMonths(3);
        MvcResult next = propose(admin, set(o, second, "PER_HOUR", 0));
        assertThat(next.getResponse().getStatus()).isEqualTo(202);
        assertThat(decide(approver(), body(next).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);

        JsonNode page =
                body(mvc.perform(userGet(admin, "/v1/labour/nwnp-rate-sets?size=50")).andReturn())
                        .get("items");
        JsonNode older = null;
        JsonNode newer = null;
        for (JsonNode s : page) {
            if (first.toString().equals(s.get("effectiveFrom").asString())) {
                older = s;
            }
            if (second.toString().equals(s.get("effectiveFrom").asString())) {
                newer = s;
            }
        }
        assertThat(older).isNotNull();
        assertThat(newer).isNotNull();
        assertThat(older.get("effectiveTo").asString()).isEqualTo(second.minusDays(1).toString());
        assertThat(newer.get("approvalState").asString()).isEqualTo("APPROVED");
        assertThat(newer.get("version").asString()).isEqualTo("ASOR");
        assertThat(newer.get("lines")).hasSize(3);
        assertThat(newer.get("lines").get(0).get("unitOfMeasure").asString()).isEqualTo("PER_HOUR");
        assertThat(newer.get("mgw")).hasSize(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM nwnp_sor_rate WHERE effective_to = ?",
                                Integer.class,
                                java.sql.Date.valueOf(second.minusDays(1))))
                .as("the lines of the set before end with it")
                .isEqualTo(3);
    }
}
