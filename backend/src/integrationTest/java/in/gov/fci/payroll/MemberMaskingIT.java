package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * SRS-SEC-4 (l.482; BR-SEC-001, R-PII): PAN, bank account and Aadhaar are shown masked in every
 * M-01 answer. The register (every page of the maker's office) names no such field at all, and no
 * raw value of any of its members appears anywhere in it; the dossier shows each one masked and
 * never the full value, for every member of the office that holds them. M-01 has no export of its
 * own; the exports of the screens that list members are covered by their ITs.
 */
class MemberMaskingIT extends ApiTestSupport {

    @Test
    void SRS_SEC_4_panBankAndAadhaarAreMaskedInEveryM01ListAndDossier() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        ArrayNode rows = all(maker, "/v1/employees");
        assertThat(rows).isNotEmpty();
        String register = rows.toString();
        for (String field :
                List.of(
                        "pan",
                        "panNumber",
                        "bankAccount",
                        "bankAccountNumber",
                        "aadhaar",
                        "aadhaarNumber",
                        "panMasked",
                        "bankAccountMasked",
                        "aadhaarMasked")) {
            assertThat(texts(rows, field)).as("the register carries no " + field).isEmpty();
        }

        int checked = 0;
        for (JsonNode row : rows) {
            String eis = row.get("eis").asString();
            Map<String, Object> raw =
                    jdbc.queryForList(
                                    "SELECT e.pan_number, e.bank_account_number, e.aadhaar_number"
                                            + " FROM employee e JOIN org_location l ON"
                                            + " l.location_id = e.location_id WHERE e.emp_num = ?"
                                            + " AND l.location_code IN ('DH001', 'DP-DL01')",
                                    eis)
                            .get(0);
            String pan = (String) raw.get("PAN_NUMBER");
            String bank = (String) raw.get("BANK_ACCOUNT_NUMBER");
            String aadhaar = (String) raw.get("AADHAAR_NUMBER");
            for (String value : new String[] {pan, bank, aadhaar}) {
                if (value != null && value.length() >= 6) {
                    assertThat(register)
                            .as(eis + ": no raw value in the register")
                            .doesNotContain(value);
                }
            }
            if (pan == null && bank == null && aadhaar == null) {
                continue;
            }
            MvcResult read = mvc.perform(userGet(maker, "/v1/employees/" + eis)).andReturn();
            assertThat(read.getResponse().getStatus()).as(eis).isEqualTo(200);
            String dossier = read.getResponse().getContentAsString();
            JsonNode d = body(read);
            if (pan != null && pan.length() >= 6) {
                assertThat(dossier).as(eis + " PAN").doesNotContain(pan);
                assertThat(d.get("panMasked").asString()).as(eis + " PAN").contains("*");
            }
            if (bank != null && bank.length() >= 6) {
                assertThat(dossier).as(eis + " bank account").doesNotContain(bank);
                assertThat(d.get("bankAccountMasked").asString())
                        .as(eis + " bank account")
                        .startsWith("*")
                        .endsWith(bank.substring(bank.length() - 4));
            }
            if (aadhaar != null && aadhaar.replaceAll("[^0-9]", "").length() >= 12) {
                String digits = aadhaar.replaceAll("[^0-9]", "");
                assertThat(dossier).as(eis + " Aadhaar").doesNotContain(digits);
                assertThat(d.get("aadhaarMasked").asString())
                        .as(eis + " Aadhaar: the last four only")
                        .isEqualTo("XXXX-XXXX-" + digits.substring(8));
            }
            checked++;
        }
        assertThat(checked).as("members holding PII were checked").isPositive();
    }
}
