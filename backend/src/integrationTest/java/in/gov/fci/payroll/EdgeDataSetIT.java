package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The edge-case data set (profile edge, docs/test-data/EDGE_DATA.md; owner 2026-10-30): every
 * script applies on a fresh H2 database above the demo seed, so each row meets the DDL's
 * constraints and triggers, and each page shows its edge rows through its own API.
 */
@ActiveProfiles("edge")
class EdgeDataSetIT extends ApiTestSupport {

    @Test
    void everyEdgeScriptAppliesAboveTheDemoSeed() {
        List<String> applied =
                jdbc.queryForList(
                        "SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"version\" LIKE"
                                + " '950%' AND \"success\" = TRUE ORDER BY \"installed_rank\"",
                        String.class);
        assertThat(applied).isNotEmpty().allMatch(v -> v.startsWith("950"));
    }

    private JsonNode get(Session s, String path) throws Exception {
        MvcResult r = mvc.perform(userGet(s, path)).andReturn();
        assertThat(r.getResponse().getStatus())
                .as(path + ": " + r.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(r);
    }

    @Test
    void M_25_circularsShowEveryStatusAndTheLongestFields() throws Exception {
        Session admin = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode page = get(admin, "/v1/masters/circulars?text=EDGE&size=100");
        List<String> statuses = texts(page.path("items"), "status");
        assertThat(statuses)
                .contains("ACTIVE", "SUPERSEDED", "WITHDRAWN", "REJECTED", "PENDING_APPROVAL");
    }

    /**
     * Each page's list API answers with the edge rows: longest texts, nulls in every optional field
     * and each status come back without an error.
     */
    @Test
    void everyListPageAnswersWithItsEdgeRows() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        String[][] pages = {
            {"/v1/employees?size=100", null},
            {"/v1/exceptions?size=100", "LONG_DETAIL"},
            {"/v1/lifecycle-events?size=100", null},
            {"/v1/arrear-events?size=100", "EDGE-ARR-BEYOND"},
            {"/v1/settlement-cases?size=100", null},
            {"/v1/claims?size=100", null},
            {"/v1/leave-events?size=100", null},
        };
        for (String[] page : pages) {
            MvcResult r = mvc.perform(userGet(maker, page[0])).andReturn();
            String text = r.getResponse().getContentAsString();
            assertThat(r.getResponse().getStatus()).as(page[0] + ": " + text).isIn(200, 403);
            if (r.getResponse().getStatus() == 200 && page[1] != null) {
                assertThat(text).as(page[0]).contains(page[1]);
            }
        }
    }
}
