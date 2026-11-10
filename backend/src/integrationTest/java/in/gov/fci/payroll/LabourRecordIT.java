package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * Module 22 phase 22c (plan docs/plan/22-nwnp-labour.md D22-7, D22-8; NWNP Reinstate Details,
 * Nominee &amp; Dependent Details; BR-FNF-014): Establishment / HR reinstates a separated NWNP
 * labour with the order attached and the Payroll Checker approves, after which the member is active
 * and in service; HR records the member's nominees (shares adding to 100) and dependents, the
 * Payroll Checker approves, and a later set replaces them, the earlier ones kept and ended.
 * Refused: an active member reinstated, a date not after the last day, no order, a second request
 * while one waits, shares not adding to 100, an unknown relationship, a Payroll Maker.
 *
 * <p>Demo data V060_9 (L30701 ... L30704), V061_9 (relationships).
 */
class LabourRecordIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<< >>\nendobj\n"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private long member(String code) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, code);
    }

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private MvcResult reinstate(Session s, long employee, LocalDate date, boolean file)
            throws Exception {
        var request =
                MockMvcRequestBuilders.multipart(
                        "/v1/labour/members/" + employee + "/reinstatements");
        request.param("date", date.toString())
                .param("orderReference", "EST/RI/7")
                .param("orderDate", LocalDate.now(IST).minusDays(1).toString())
                .param("issuingAuthority", "Area Manager, Delhi")
                .param("reason", "Order of the appellate authority");
        if (file) {
            request.file(new MockMultipartFile("document", "order.pdf", "application/pdf", PDF));
        }
        return mvc.perform(
                        userRequest(s, request)
                                .header("Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andReturn();
    }

    private String defect(MvcResult r) throws Exception {
        return body(r).get("args").get(0).asString();
    }

    // SC-22-0040, SC-22-0041
    @Test
    void reinstateDetails_aSeparatedLabourIsReinstatedWithItsOrderAndTheChecker() throws Exception {
        long l3 = member("L30703");
        LocalDate today = LocalDate.now(IST);
        Session hr = hr();
        assertThat(defect(reinstate(hr, l3, today, true)))
                .as("an active member")
                .isEqualTo("STATUS");
        LocalDate lastDay = today.minusDays(20);
        jdbc.update(
                "UPDATE labour_profile SET labour_status = 'SEPARATED', effective_to = ? WHERE"
                        + " employee_id = ?",
                java.sql.Date.valueOf(lastDay),
                l3);
        jdbc.update(
                "UPDATE employee SET employment_status = 'SEPARATED' WHERE employee_id = ?", l3);
        try {
            assertThat(defect(reinstate(hr, l3, lastDay, true))).isEqualTo("DATE");
            assertThat(reinstate(hr, l3, today, false).getResponse().getStatus())
                    .as("the order is a required part")
                    .isEqualTo(400);
            assertThat(
                            reinstate(
                                            login(
                                                    "maker.delhi@fci-demo.test",
                                                    "PAYROLL_MAKER",
                                                    "DH001"),
                                            l3,
                                            today,
                                            true)
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(403);
            MvcResult sent = reinstate(hr, l3, today.minusDays(5), true);
            assertThat(sent.getResponse().getStatus())
                    .as(sent.getResponse().getContentAsString())
                    .isEqualTo(202);
            assertThat(defect(reinstate(hr, l3, today, true))).isEqualTo("PENDING");
            assertThat(decide(checker(), body(sent).get("id").asLong(), "APPROVE", null, null))
                    .isEqualTo(200);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT labour_status FROM labour_profile WHERE employee_id ="
                                            + " ?",
                                    String.class,
                                    l3))
                    .isEqualTo("ACTIVE");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT employment_status FROM employee WHERE employee_id = ?",
                                    String.class,
                                    l3))
                    .isEqualTo("IN_SERVICE");
        } finally {
            jdbc.update(
                    "UPDATE labour_profile SET labour_status = 'ACTIVE', effective_to = NULL WHERE"
                            + " employee_id = ?",
                    l3);
            jdbc.update(
                    "UPDATE employee SET employment_status = 'IN_SERVICE' WHERE employee_id = ?",
                    l3);
        }
    }

    private Map<String, Object> person(String name, String relationship, Object share) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        p.put("relationship", relationship);
        p.put("birthDate", "1990-01-01");
        if (share != null) {
            p.put("share", share);
        }
        return p;
    }

    private MvcResult nominate(
            Session s, long employee, List<Map<String, Object>> nominees, String reason)
            throws Exception {
        return mvc.perform(
                        userPost(
                                s,
                                "/v1/labour/members/" + employee + "/nominations",
                                Map.of(
                                        "nominees",
                                        nominees,
                                        "dependents",
                                        List.of(person("Kiran Lodhi", "SON", null)),
                                        "reason",
                                        reason)))
                .andReturn();
    }

    // SC-22-0042, SC-22-0043
    @Test
    void nomineeDetails_theNomineesAddTo100AndALaterSetReplacesThem() throws Exception {
        long l2 = member("L30702");
        Session hr = hr();
        assertThat(
                        defect(
                                nominate(
                                        hr,
                                        l2,
                                        List.of(
                                                person("Sita Lodhi", "SPOUSE", 60),
                                                person("Kiran Lodhi", "SON", 30)),
                                        "Form 2")))
                .isEqualTo("SHARE_TOTAL");
        assertThat(defect(nominate(hr, l2, List.of(person("Sita Lodhi", "COUSIN", 100)), "Form 2")))
                .isEqualTo("RELATIONSHIP");
        MvcResult first =
                nominate(
                        hr,
                        l2,
                        List.of(
                                person("Sita Lodhi", "SPOUSE", 60),
                                person("Kiran Lodhi", "SON", 40)),
                        "Form 2");
        assertThat(first.getResponse().getStatus())
                .as(first.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(defect(nominate(hr, l2, List.of(person("Sita Lodhi", "SPOUSE", 100)), "Again")))
                .isEqualTo("PENDING");
        Session checker = checker();
        assertThat(decide(checker, body(first).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode in =
                body(
                        mvc.perform(userGet(hr, "/v1/labour/members/" + l2 + "/nominations"))
                                .andReturn());
        assertThat(in.get("nominees")).hasSize(2);
        assertThat(in.get("dependents")).hasSize(1);
        assertThat(in.get("pending").asBoolean()).isFalse();

        MvcResult second =
                nominate(hr, l2, List.of(person("Sita Lodhi", "SPOUSE", 100)), "Revised Form 2");
        assertThat(decide(checker, body(second).get("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        JsonNode now =
                body(
                        mvc.perform(userGet(hr, "/v1/labour/members/" + l2 + "/nominations"))
                                .andReturn());
        assertThat(now.get("nominees")).hasSize(1);
        assertThat(now.get("nominees").get(0).get("share").decimalValue())
                .isEqualByComparingTo("100");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM pf_nominee WHERE employee_id = ? AND"
                                        + " effective_to IS NOT NULL",
                                Integer.class,
                                l2))
                .as("the earlier nominees are kept, ended")
                .isGreaterThanOrEqualTo(2);
    }
}
