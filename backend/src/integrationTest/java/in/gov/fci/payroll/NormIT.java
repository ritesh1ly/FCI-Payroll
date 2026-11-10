package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 20 phase 20d (plan docs/plan/20-labour-common-departmental.md; SRS-3-6, SRS-3-12; M-06 use
 * case "Maintain Depot Incentive Norms"): the Rule Administrator at Headquarters proposes Depot
 * Okhla's norm set with the SRS bag steps of both bag-weight bands, the Rule Approver approves it,
 * and a later set ends it the day before. Refused: bands with a gap, a set not after the depot's
 * latest, a second set while one waits, the Labour Dealing Hand proposing; the Labour Dealing Hand
 * sees the depot's sets.
 */
class NormIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long okhla() {
        return jdbc.queryForObject(
                "SELECT location_id FROM org_location WHERE location_code = 'DP-DL01'", Long.class);
    }

    private static List<Map<String, Object>> slabs() {
        List<Map<String, Object>> slabs = new ArrayList<>();
        Object[][] bands = {
            {"BAND_1", 1, 1, 30, 8},
            {"BAND_1", 2, 31, 60, 15},
            {"BAND_1", 3, 61, 90, 35},
            {"BAND_1", 4, 91, null, 50},
            {"BAND_2", 1, 1, 20, 8},
            {"BAND_2", 2, 21, 40, 15},
            {"BAND_2", 3, 41, 60, 35},
            {"BAND_2", 4, 61, null, 50}
        };
        for (Object[] b : bands) {
            Map<String, Object> s = new HashMap<>();
            s.put("table", b[0]);
            s.put("order", b[1]);
            s.put("from", b[2]);
            s.put("to", b[3]);
            s.put("ratePercent", b[4]);
            slabs.add(s);
        }
        return slabs;
    }

    /** The depot's union MoU, held on M-25 (SRS M-06 precondition). */
    private Long mou;

    private long circular() {
        String number = "IT/MOU/" + System.nanoTime();
        jdbc.update(
                "INSERT INTO document_attachment (entity_name, entity_key, file_name,"
                        + " storage_provider, storage_object_key, contains_pii, uploaded_by) VALUES"
                        + " ('circular_order', ?, 'it.pdf', 'ORACLE_SECUREFILE', ?, 'N', 'it')",
                number,
                "it/circular/" + number);
        jdbc.update(
                "INSERT INTO circular_order (circular_number, circular_date, subject,"
                    + " category_code, applies_all_offices, effective_from, document_attachment_id,"
                    + " circular_status, created_by) SELECT ?, ?, 'Union MoU (IT)', 'DA', 'Y', ?,"
                    + " attachment_id, 'ACTIVE', 'it.fixture' FROM document_attachment WHERE"
                    + " storage_object_key = ?",
                number,
                java.sql.Date.valueOf(LocalDate.now(IST)),
                java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)),
                "it/circular/" + number);
        return jdbc.queryForObject(
                "SELECT circular_order_id FROM circular_order WHERE circular_number = ?",
                Long.class,
                number);
    }

    private Map<String, Object> set(LocalDate from, List<Map<String, Object>> slabs) {
        Map<String, Object> body = new HashMap<>();
        body.put("depotLocationId", okhla());
        body.put("effectiveFrom", from.toString());
        body.put("handling", 250);
        body.put("height", 20);
        body.put("lead", 0);
        body.put("minLabourNumber", 10);
        body.put("minSlabWeight", 8.5);
        body.put("mouReference", "MoU Okhla 2026");
        body.put("mouCircularId", mou);
        body.put("slabs", slabs);
        body.put("reason", "Union MoU");
        return body;
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String errorCode(MvcResult r) throws Exception {
        String code = body(r).path("errorCode").asString();
        return code.isEmpty() ? status(r) + " " + r.getResponse().getContentAsString() : code;
    }

    // SC-06-0018: labour pay rules (norms and slabs) are dated rules, not program logic.
    // SC-20-0018 (Maintain Depot Incentive Norms, main flow: HQ enters the depot's norms and slab
    // bands with an effective date, the Rule Approver approves, the set is in force).
    @Test
    void SRS_3_12_hqProposesTheDepotNormsTheRuleApproverApprovesAndALaterSetEndsIt()
            throws Exception {
        Session admin = login("tax.ruleadmin@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        Session approver = login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
        LocalDate today = LocalDate.now(IST);
        // SRS M-06: the set links the depot's union MoU from M-25; without it, refused.
        Map<String, Object> noMou = set(today.plusDays(1), slabs());
        noMou.remove("mouCircularId");
        assertThat(
                        body(mvc.perform(userPost(admin, "/v1/labour/norm-sets", noMou))
                                        .andReturn())
                                .toString())
                .contains("MOU");
        mou = circular();
        Session reader = login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
        long before =
                body(mvc.perform(userGet(reader, "/v1/labour/norm-sets?depot=" + okhla()))
                                .andReturn())
                        .get("total")
                        .asLong();

        JsonNode depots =
                body(mvc.perform(userGet(admin, "/v1/labour/norm-sets/depots")).andReturn());
        assertThat(depots.toString()).contains("DP-DL01");

        List<Map<String, Object>> gap = slabs();
        gap.get(1).put("from", 32);
        assertThat(
                        errorCode(
                                mvc.perform(
                                                userPost(
                                                        admin,
                                                        "/v1/labour/norm-sets",
                                                        set(today, gap)))
                                        .andReturn()))
                .isEqualTo("LBR-422-NORM");

        MvcResult first =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/labour/norm-sets",
                                        set(today.plusDays(1), slabs())))
                        .andReturn();
        assertThat(status(first)).as(first.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(
                        body(mvc.perform(
                                                userPost(
                                                        admin,
                                                        "/v1/labour/norm-sets",
                                                        set(today.plusDays(9), slabs())))
                                        .andReturn())
                                .toString())
                .as("one set waits at a time")
                .contains("PENDING");
        assertThat(decide(admin, body(first).get("id").asLong(), "APPROVE", null, null))
                .as("the proposer never approves")
                .isEqualTo(403);
        assertThat(decide(approver, body(first).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);

        assertThat(
                        body(mvc.perform(
                                                userPost(
                                                        admin,
                                                        "/v1/labour/norm-sets",
                                                        set(today.plusDays(1), slabs())))
                                        .andReturn())
                                .toString())
                .contains("EFFECTIVE_BEFORE_LATEST");
        MvcResult second =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/labour/norm-sets",
                                        set(today.plusDays(10), slabs())))
                        .andReturn();
        assertThat(status(second)).isEqualTo(202);
        assertThat(decide(approver, body(second).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);

        Session ldh = login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
        JsonNode page =
                body(
                        mvc.perform(userGet(ldh, "/v1/labour/norm-sets?depot=" + okhla()))
                                .andReturn());
        assertThat(page.get("total").asLong()).isEqualTo(before + 2);
        JsonNode latest = page.get("items").get(0);
        assertThat(latest.get("effectiveFrom").asString()).isEqualTo(today.plusDays(10).toString());
        assertThat(latest.get("slabs")).hasSize(8);
        assertThat(latest.get("mouCircular").asString()).startsWith("IT/MOU/");
        assertThat(page.get("items").get(1).get("effectiveTo").asString())
                .as("the set before ends the day before")
                .isEqualTo(today.plusDays(9).toString());
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        ldh,
                                                        "/v1/labour/norm-sets",
                                                        set(today.plusDays(20), slabs())))
                                        .andReturn()))
                .as("depots view only")
                .isEqualTo(403);
    }
}
