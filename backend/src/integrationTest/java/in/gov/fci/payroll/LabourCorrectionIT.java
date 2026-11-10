package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Module 20 phase 20e, labour name / date-of-birth correction (plan
 * docs/plan/20-labour-common-departmental.md; SRS-2-59, BR-LABM-003; D20-18): Establishment / HR of
 * Delhi corrects a fixture labour's name as per the service book and another's date of birth, each
 * with the page attached; the Payroll Checker approves and the person record changes. A corrected
 * date of birth recalculates the retirement date and the EPS cut-off; one whose new retirement date
 * has passed also needs Headquarters Establishment. Refused: no document, a reason outside the
 * list, a second correction while one is pending, a date of birth on or after joining, the same
 * value again.
 */
class LabourCorrectionIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<< >>\nendobj\n"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private long okhla() {
        return jdbc.queryForObject(
                "SELECT location_id FROM org_location WHERE location_code = 'DP-DL01'", Long.class);
    }

    private long labour(String code, String pan) {
        jdbc.update(
                "INSERT INTO employee (emp_num, first_name, last_name, birth_date, gender,"
                    + " comp_joining_date, employee_type, emp_lbr_flag, pan_number, designation_id,"
                    + " location_id, depot_location_id, provident_fund_type, parent_zone_id,"
                    + " retirement_date, employment_status, record_status, record_origin) SELECT ?,"
                    + " 'Ramkishan', 'Fixture', DATE '1990-01-15', 'M', DATE '2015-07-01',"
                    + " 'DEPT_LABOUR', 'Y', ?, d.designation_id, ?, ?, 'NONE', l.zone_id, DATE"
                    + " '2050-01-31', 'IN_SERVICE', 'ACTIVE', 'ENTERED' FROM org_designation d JOIN"
                    + " org_location l ON l.location_id = ? WHERE d.designation_code = 'HL'",
                code,
                pan,
                okhla(),
                okhla(),
                okhla());
        long employee =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, code);
        jdbc.update(
                "INSERT INTO labour_profile (employee_id, labour_number, payroll_type,"
                        + " depot_location_id, pf_applicable_flag, labour_status, effective_from)"
                        + " VALUES (?, ?, 'DEPT_LABOUR', ?, 'N', 'ACTIVE', DATE '2015-07-01')",
                employee,
                code,
                okhla());
        return employee;
    }

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private MvcResult correct(Session s, long employee, Map<String, String> fields, boolean file)
            throws Exception {
        var request =
                MockMvcRequestBuilders.multipart("/v1/labour/members/" + employee + "/corrections");
        LocalDate today = LocalDate.now(IST);
        request.param("orderDate", today.minusDays(1).toString())
                .param("issuingAuthority", "Area Manager, Delhi")
                .param("effectiveDate", today.toString());
        if (!fields.containsKey("reasonCode")) {
            request.param("reasonCode", "AS_PER_SERVICE_BOOK");
        }
        fields.forEach(request::param);
        if (file) {
            request.file(new MockMultipartFile("document", "page.pdf", "application/pdf", PDF));
        }
        var sent =
                userRequest(s, request)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString());
        return mvc.perform(sent).andReturn();
    }

    private String status(long employee, String type) {
        return jdbc.queryForObject(
                "SELECT request_status FROM identity_correction_request WHERE employee_id = ? AND"
                        + " correction_type = ? ORDER BY correction_id DESC FETCH FIRST 1 ROWS"
                        + " ONLY",
                String.class,
                employee,
                type);
    }

    private long approvalOf(MvcResult sent) throws Exception {
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        return body(sent).get("id").asLong();
    }

    // SC-20-0113: the name correction with the service-book page; approval as SRS-2-59.
    @Test
    void SRS_2_59_hrCorrectsTheNameAsPerTheServiceBookAndTheCheckerApproves() throws Exception {
        long ram = labour("LCR001", "DMOPF0001A");
        Session hr = hr();
        Map<String, String> name =
                Map.of("type", "NAME", "firstName", "Ram Kishan", "lastName", "Faujdar");
        assertThat(correct(hr, ram, name, false).getResponse().getStatus())
                .as("the service-book page is attached")
                .isEqualTo(400);
        MvcResult unlisted =
                correct(
                        hr,
                        ram,
                        Map.of(
                                "type", "NAME",
                                "firstName", "Ram Kishan",
                                "reasonCode", "TYPING_ERROR"),
                        true);
        assertThat(unlisted.getResponse().getContentAsString()).contains("REASON");

        long approval = approvalOf(correct(hr, ram, name, true));
        assertThat(status(ram, "NAME")).isEqualTo("SUBMITTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM document_attachment WHERE entity_name ="
                                        + " 'identity_correction_request' AND entity_key = (SELECT"
                                        + " CAST(MAX(correction_id) AS VARCHAR2(20)) FROM"
                                        + " identity_correction_request WHERE employee_id = ?)",
                                Integer.class,
                                ram))
                .isEqualTo(1);
        assertThat(correct(hr, ram, name, true).getResponse().getContentAsString())
                .as("one correction of a kind at a time")
                .contains("PENDING");
        assertThat(decide(hr, approval, "APPROVE", null, null))
                .as("never the maker")
                .isEqualTo(403);
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT first_name, last_name FROM employee WHERE employee_id = ?", ram);
        assertThat(row.get("FIRST_NAME")).isEqualTo("Ram Kishan");
        assertThat(row.get("LAST_NAME")).isEqualTo("Faujdar");
        assertThat(status(ram, "NAME")).isEqualTo("APPLIED");
        assertThat(correct(hr, ram, name, true).getResponse().getContentAsString())
                .as("the same name again")
                .contains("UNCHANGED");
    }

    // SC-20-0113: the date-of-birth correction; SRS-2-59 recalculates retirement and EPS.
    @Test
    void SRS_2_59_aDobCorrectionRecalculatesRetirementAndEpsAndAPastOneGoesToHq() throws Exception {
        long sita = labour("LCR002", "DMOPF0002A");
        Session hr = hr();
        assertThat(
                        correct(hr, sita, Map.of("type", "DOB", "birthDate", "2016-01-01"), true)
                                .getResponse()
                                .getContentAsString())
                .as("born before joining")
                .contains("BIRTH_DATE");
        long approval =
                approvalOf(
                        correct(hr, sita, Map.of("type", "DOB", "birthDate", "1991-03-10"), true));
        Map<String, Object> request =
                jdbc.queryForMap(
                        "SELECT recalculated_retirement_date, recalculated_eps_cutoff_date,"
                                + " higher_authority_review, downstream_impact_flag FROM"
                                + " identity_correction_request WHERE approval_id = ?",
                        approval);
        assertThat(request.get("RECALCULATED_RETIREMENT_DATE").toString()).startsWith("2051-03-31");
        assertThat(request.get("RECALCULATED_EPS_CUTOFF_DATE").toString()).startsWith("2049-03-10");
        assertThat(request.get("HIGHER_AUTHORITY_REVIEW")).isEqualTo("N");
        assertThat(request.get("DOWNSTREAM_IMPACT_FLAG")).isEqualTo("Y");
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        Map<String, Object> person =
                jdbc.queryForMap(
                        "SELECT birth_date, retirement_date FROM employee WHERE employee_id = ?",
                        sita);
        assertThat(person.get("BIRTH_DATE").toString()).startsWith("1991-03-10");
        assertThat(person.get("RETIREMENT_DATE").toString()).startsWith("2051-03-31");

        long gopal = labour("LCR003", "DMOPF0003A");
        long past =
                approvalOf(
                        correct(hr, gopal, Map.of("type", "DOB", "birthDate", "1960-05-10"), true));
        assertThat(status(gopal, "DOB")).isEqualTo("PENDING_HQ_ESTT");
        assertThat(decide(checker(), past, "APPROVE", null, null)).isEqualTo(200);
        assertThat(status(gopal, "DOB"))
                .as("waits for HQ Establishment")
                .isEqualTo("PENDING_HQ_ESTT");
        MvcResult hq =
                mvc.perform(
                                userPost(
                                        login("hr.hq@fci-demo.test", "ESTABLISHMENT_HR", "HQ001"),
                                        "/v1/approvals/" + past + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(hq.getResponse().getStatus())
                .as(hq.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(status(gopal, "DOB")).isEqualTo("APPLIED");
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT retirement_date FROM employee WHERE employee_id ="
                                                + " ?",
                                        Object.class,
                                        gopal)
                                .toString())
                .startsWith("2020-05-31");
    }
}
