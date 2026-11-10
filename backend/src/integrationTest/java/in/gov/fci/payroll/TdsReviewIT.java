package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * FRS V0.5 §14.4 Step 4 (US-08-07, US-GAP-29): the TDS cases the Payroll Checker reviews with the
 * cycle are listed on C-01 for the cycle's financial year and the session's offices: a member whose
 * declaration lost its provisional exemption at the proof deadline is flagged; another office's
 * checker does not see it; a role without C-01 is refused; a malformed period is refused.
 */
class TdsReviewIT extends ApiTestSupport {

    private static final String PATH = "/v1/tax/flagged-tds-cases?period=";

    @Test
    void SRS_7_14_aProofDeadlineReversalIsFlaggedForTheCheckerOfTheMembersOffice()
            throws Exception {
        Long employee =
                jdbc.queryForObject(
                        "SELECT MIN(e.employee_id) FROM employee e JOIN org_location l ON"
                                + " l.location_id = e.location_id WHERE l.location_code = 'KL001'"
                                + " AND e.employee_type = 'STAFF'",
                        Long.class);
        assertThat(employee).as("a Kolkata staff member").isNotNull();
        String eis =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?",
                        String.class,
                        employee);
        // A far-future financial year no other IT uses (2091-92 from April 2091).
        jdbc.update(
                "INSERT INTO it_declaration_submission (employee_id, fin_year, submission_no,"
                        + " submission_status, proof_deadline_reversed) VALUES (?, '2091-92', 7,"
                        + " 'APPROVED', 'Y')",
                employee);
        jdbc.update(
                "INSERT INTO it_declaration_submission (employee_id, fin_year, submission_no,"
                        + " submission_status, proof_deadline_reversed) VALUES (?, '2091-92', 8,"
                        + " 'APPROVED', 'N')",
                employee);

        Session checker = login("ddo.kolkata@fci-demo.test", "PAYROLL_CHECKER", "KL001");
        JsonNode page = body(mvc.perform(userGet(checker, PATH + "2091-05")).andReturn());
        assertThat(page.get("items")).hasSize(1);
        JsonNode flagged = page.get("items").get(0);
        assertThat(flagged.get("eis").asString()).isEqualTo(eis);
        assertThat(flagged.get("flag").asString()).isEqualTo("PROOF_DEADLINE_REVERSAL");
        assertThat(flagged.get("reference").asLong()).isEqualTo(7L);
        assertThat(flagged.get("office").asString()).startsWith("KL001 - ");
        // March 2092 is still the 2091-92 year; April 2092 is the next one.
        assertThat(body(mvc.perform(userGet(checker, PATH + "2092-03")).andReturn()).get("items"))
                .hasSize(1);
        assertThat(body(mvc.perform(userGet(checker, PATH + "2092-04")).andReturn()).get("items"))
                .isEmpty();

        Session delhi = login("maker.checker@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(body(mvc.perform(userGet(delhi, PATH + "2091-05")).andReturn()).get("items"))
                .as("another office's checker")
                .isEmpty();

        Session depot = login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
        assertThat(
                        mvc.perform(userGet(depot, PATH + "2091-05"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        Session again = login("ddo.kolkata@fci-demo.test", "PAYROLL_CHECKER", "KL001");
        assertThat(
                        mvc.perform(userGet(again, PATH + "2091-13"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }
}
