package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The demo rows of seed V077_30 (group g4, UI audit "no data after fetch"): each list of modules
 * 11, 13, 14, 15 and 17 shows rows for its demo user in scope, through the screens' own reads.
 */
class DemoG4SeedIT extends ApiTestSupport {

    /** O-04 (SRS l.3501): the RO-PB desk opens on the locked month and its summary reconciles. */
    @Test
    void SRS_18_28_theDemoLockedMonthIsTheDefaultOfO04AndReconciles() throws Exception {
        Session maker = login("pm.ropb@fci-demo.test", "PAYROLL_MAKER", "RO-PB");
        JsonNode latest =
                body(
                        mvc.perform(userGet(maker, "/v1/reports/component-summary/latest-locked"))
                                .andReturn());
        String yymm = latest.path("yymm").asString();
        assertThat(yymm).matches("\\d{6}");
        JsonNode summary =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/reports/component-summary?yymm="
                                                        + yymm
                                                        + "&level=UNIT"))
                                .andReturn());
        assertThat(summary.path("lines").size()).isPositive();
        assertThat(summary.path("provisional").asBoolean()).isFalse();
        assertThat(summary.path("controlTotalsMatch").asBoolean()).isTrue();
    }

    /**
     * O-05, I-25 and E-02: the computed case, the defined revision and the member's submissions.
     */
    @Test
    void SRS_10_11_SRS_8_25_SRS_17_18_theDemoCaseRevisionAndSubmissionsAreListed()
            throws Exception {
        Session ropb = login("pm.ropb@fci-demo.test", "PAYROLL_MAKER", "RO-PB");
        assertThat(all(ropb, "/v1/settlement-cases").findValuesAsString("caseReference"))
                .contains("FNF-DEMO-00002");
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(all(maker, "/v1/wage-revisions").findValuesAsString("code"))
                .contains("WR-DEMO-01");
        assertThat(all(maker, "/v1/eseva/submissions?eis=104882").size()).isGreaterThanOrEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification n JOIN employee e ON"
                                        + " e.employee_id = n.recipient_employee_id WHERE"
                                        + " e.emp_num = '104882' AND n.channel = 'IN_APP' AND"
                                        + " n.related_entity_name = 'ess_submission'",
                                Integer.class))
                .isGreaterThanOrEqualTo(2);
    }
}
