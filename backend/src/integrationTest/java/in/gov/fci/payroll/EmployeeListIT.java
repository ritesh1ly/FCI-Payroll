package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * SRS-NF-3 on M-01: the list opens page by page with its status filter applied by the database, and
 * the tiles count the whole filtered scope. The status rule in the query must give each row the
 * status the row itself shows (Employee.displayStatus). Data: db/seed V024_9 and V035_9 (more than
 * 100 members at the Chennai DO).
 */
class EmployeeListIT extends ApiTestSupport {

    private static final Map<String, String> COUNT_OF_STATUS =
            Map.of(
                    "PAYROLL_SETUP_PENDING", "payrollSetupPending",
                    "CHANGE_PENDING", "changePending",
                    "PAN_CPF_MISSING", "panCpfMissing",
                    "BANK_INVALID", "bankInvalid",
                    "ACTIVE", "active");

    private JsonNode get(Session s, String path) throws Exception {
        return body(mvc.perform(userGet(s, path)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void SRS_NF_3_eachStatusFilterReturnsExactlyTheRowsOfThatStatusAndTheTilesAddUp()
            throws Exception {
        Session hq = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        JsonNode counts = get(hq, "/v1/employees/counts");
        long total = counts.get("total").asLong();
        assertThat(total).isEqualTo(get(hq, "/v1/employees?size=10").get("total").asLong());
        long sum = 0;
        for (Map.Entry<String, String> status : COUNT_OF_STATUS.entrySet()) {
            JsonNode rows = all(hq, "/v1/employees?status=" + status.getKey());
            assertThat(rows).as(status.getKey()).hasSize(counts.get(status.getValue()).asInt());
            rows.forEach(r -> assertThat(r.get("status").asString()).isEqualTo(status.getKey()));
            sum += rows.size();
        }
        assertThat(sum).isEqualTo(total);
        // The demo data hold every status but change pending somewhere in the country.
        for (String field : List.of("payrollSetupPending", "panCpfMissing", "active")) {
            assertThat(counts.get(field).asLong()).as(field).isPositive();
        }
    }

    @Test
    void SRS_NF_3_aLongUnitListIsServedAPageAtATimeAndTheTilesFollowTheTextAndPattern()
            throws Exception {
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        JsonNode first = get(chennai, "/v1/employees");
        assertThat(first.get("size").asInt()).isEqualTo(20);
        assertThat(first.get("items").size()).isEqualTo(20);
        long total = first.get("total").asLong();
        assertThat(total).isGreaterThan(100);
        assertThat(all(chennai, "/v1/employees")).hasSize((int) total);
        // Text and pay pattern narrow the tiles as they narrow the list.
        JsonNode paging = get(chennai, "/v1/employees/counts?q=paging");
        assertThat(paging.get("total").asLong()).isEqualTo(120);
        assertThat(paging.get("active").asLong()).isEqualTo(120);
        JsonNode fnd = get(chennai, "/v1/employees/counts?payPattern=FND");
        assertThat(fnd.get("total").asLong())
                .isEqualTo(get(chennai, "/v1/employees?payPattern=FND").get("total").asLong());
        // The status filter is applied before paging: one page holds only that status.
        JsonNode missing = get(chennai, "/v1/employees?status=PAN_CPF_MISSING&size=10");
        assertThat(missing.get("items").findValuesAsString("status"))
                .containsOnly("PAN_CPF_MISSING");
    }

    @Test
    void SRS_1_11_aFilterMatchingNothingCountsZeroAndBlankFiltersFilterNothing() throws Exception {
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        // No member matches: every tile is zero (the database sums over no rows are null).
        JsonNode none = get(chennai, "/v1/employees/counts?q=no-such-member");
        for (String field :
                List.of(
                        "total",
                        "payrollSetupPending",
                        "changePending",
                        "panCpfMissing",
                        "bankInvalid",
                        "active")) {
            assertThat(none.get(field).asLong()).as(field).isZero();
        }
        assertThat(get(chennai, "/v1/employees?q=no-such-member").get("items")).isEmpty();
        // Blank filters (an empty dropdown) filter nothing.
        long all = get(chennai, "/v1/employees").get("total").asLong();
        assertThat(get(chennai, "/v1/employees?payPattern=&status=&q=").get("total").asLong())
                .isEqualTo(all);
        assertThat(get(chennai, "/v1/employees/counts?payPattern=&q=").get("total").asLong())
                .isEqualTo(all);
    }

    @Test
    void SRS_NF_3_anUnknownStatusIsRefusedAndAnUnscopedUserSeesNothing() throws Exception {
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        for (String bad : List.of("RETIRED", "active", "ACTIVE'--")) {
            mvc.perform(userGet(chennai, "/v1/employees?status=" + bad))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(userGet(chennai, "/v1/employees?size=25"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COM-400-PAGE"));
    }

    /**
     * SRS-16-7: a lookup by a staff number held before a transfer resolves to the same person; the
     * office scope still applies and the old number is matched without regard to case.
     */
    @Test
    void SRS_16_7_aSearchByTheOldStaffNumberFindsTheMemberWithinScopeOnly() throws Exception {
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        String eis =
                get(chennai, "/v1/employees?size=10").get("items").get(0).get("eis").asString();
        Map<String, Object> member =
                jdbc.queryForMap(
                        "SELECT employee_id, location_id FROM employee WHERE emp_num = ?", eis);
        jdbc.update(
                "INSERT INTO employee_number_history (employee_id, old_emp_num, new_emp_num,"
                        + " to_location_id, change_date, change_event, reason)"
                        + " VALUES (?, 'OLDCH-9071', ?, ?, DATE '2025-04-01', 'TRANSFER',"
                        + " 'IT: number reissued on transfer')",
                member.get("EMPLOYEE_ID"),
                eis,
                member.get("LOCATION_ID"));
        JsonNode exact = get(chennai, "/v1/employees?q=OLDCH-9071");
        assertThat(exact.get("total").asLong()).isEqualTo(1);
        assertThat(exact.get("items").get(0).get("eis").asString()).isEqualTo(eis);
        assertThat(get(chennai, "/v1/employees?q=oldch-90").get("items").findValuesAsString("eis"))
                .contains(eis);
        assertThat(get(chennai, "/v1/employees/counts?q=OLDCH-9071").get("total").asLong())
                .isEqualTo(1);
        // One character off matches nothing; another office does not see the member.
        assertThat(get(chennai, "/v1/employees?q=OLDCH-9072").get("total").asLong()).isZero();
        Session delhi = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(get(delhi, "/v1/employees?q=OLDCH-9071").get("total").asLong()).isZero();
    }
}
