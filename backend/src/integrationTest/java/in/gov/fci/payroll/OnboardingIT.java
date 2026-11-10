package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.employee.api.HrmsNewEmployees;
import in.gov.fci.payroll.employee.api.HrmsNewEmployees.NewEmployee;
import in.gov.fci.payroll.employee.api.HrmsNewEmployees.Outcome;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Onboarding of a new staff record (plan 16 D16-34 … D16-37; SRS-1-11, SRS-1-12, SRS-1-13,
 * SRS-1-16, SRS-1-17; UC Onboard New Employee/Staff Record): HRMS sends a new employee of DO Delhi;
 * it is created in Payroll Setup Pending with its pay pattern and provident fund type derived; a
 * second record of the same person (PAN, Aadhaar) is held, never created. Establishment / HR
 * submits it, the Payroll Checker returns it (back in Payroll Setup Pending, HR told the reason),
 * HR submits again and the Checker approves it: Active.
 */
class OnboardingIT extends ApiTestSupport {

    private static final AtomicInteger NEXT = new AtomicInteger(7100);

    @Autowired private HrmsNewEmployees intake;

    @Autowired private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named;

    /** A dummy Aadhaar in the real format: 12 digits, the last the Verhoeff check digit. */
    private static String aadhaar(int n) {
        String stem = "9" + String.format("%010d", n);
        for (int d = 0; d <= 9; d++) {
            if (SeedDataFormatIT.validAadhaar(stem + d)) {
                return stem + d;
            }
        }
        throw new IllegalStateException(stem);
    }

    private static NewEmployee employee(
            int n, String pan, String aadhaar, String band, String pf, String posting) {
        return new NewEmployee(
                "ONB" + n,
                "Kavita",
                "Onboard",
                null,
                "F",
                LocalDate.of(1994, 3, 12),
                LocalDate.now().minusDays(3),
                "AM",
                band,
                posting,
                pan,
                aadhaar,
                "30" + String.format("%09d", n),
                "SBIN0DEMO01",
                null,
                pf,
                "9811" + String.format("%06d", n),
                null);
    }

    /** A dummy PAN in the real format: P as the fourth letter, the surname's initial fifth. */
    private static String pan(int n) {
        return "DMOPO" + String.format("%04d", n % 10_000) + "K";
    }

    private Map<String, Object> record(long employeeId) {
        return jdbc.queryForMap(
                "SELECT emp_num, record_status, pay_pattern, pay_pattern_source,"
                        + " provident_fund_type, record_origin, employee_type FROM employee WHERE"
                        + " employee_id = ?",
                employeeId);
    }

    // SRS-16-3, BR-EMPM-003: the PAN is unique across employees: a second record of it is held.
    // SC-16-0012, SC-16-0150 (a match on PAN or Aadhaar is held, never created), SC-16-0051 (the
    // HRMS-sourced fields filled), SC-16-0052 (the Aadhaar kept for the duplicate check),
    // SC-16-0015 / SC-16-0016 (pay pattern and provident fund type derived), SC-16-0153 (a record
    // received again after the interface resumes is not created twice).
    // SC-04-0003: the PAN is unique across employees, the person's permanent identity.
    @Test
    void SRS_1_13_aNewRecordIsCreatedInSetupPendingAndASecondOfTheSamePersonIsHeld() {
        int n = NEXT.incrementAndGet();
        NewEmployee first = employee(n, pan(n), aadhaar(n), "E-2", null, "DH001");
        HrmsNewEmployees.Received created = intake.receive(first);
        assertThat(created.outcome()).isEqualTo(Outcome.CREATED);
        Map<String, Object> r = record(created.employeeId());
        assertThat(r.get("RECORD_STATUS")).isEqualTo("PAYROLL_SETUP_PENDING");
        assertThat(r.get("PAY_PATTERN")).isEqualTo("IDA");
        assertThat(r.get("PAY_PATTERN_SOURCE")).isEqualTo("DERIVED");
        assertThat(r.get("PROVIDENT_FUND_TYPE")).as("IDA is a CPF member").isEqualTo("CPF");
        assertThat(r.get("RECORD_ORIGIN")).isEqualTo("INTERFACE");
        assertThat(r.get("EMPLOYEE_TYPE")).isEqualTo("STAFF");

        HrmsNewEmployees.Received again = intake.receive(first);
        assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_RECEIVED);
        assertThat(again.employeeId()).isEqualTo(created.employeeId());

        int m = NEXT.incrementAndGet();
        HrmsNewEmployees.Received samePan =
                intake.receive(employee(m, pan(n), aadhaar(m), "E-2", null, "DH001"));
        assertThat(samePan.outcome()).isEqualTo(Outcome.HELD_DUPLICATE);
        assertThat(samePan.matchedEis()).isEqualTo("ONB" + n);
        HrmsNewEmployees.Received sameAadhaar =
                intake.receive(employee(m, pan(m), aadhaar(n), "E-2", null, "DH001"));
        assertThat(sameAadhaar.outcome()).isEqualTo(Outcome.HELD_DUPLICATE);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM employee WHERE emp_num = ?",
                                Integer.class,
                                "ONB" + m))
                .as("a held record is not created")
                .isZero();
    }

    // SC-16-0016: a CDA member is GPF or NPS as HRMS holds it; SC-16-0051: a field M-01 needs is
    // required.
    @Test
    void SRS_1_17_aCdaMemberNeedsItsFundTypeAndAnUnknownPostingOrBadPanIsHeld() {
        int n = NEXT.incrementAndGet();
        assertThat(intake.receive(employee(n, pan(n), aadhaar(n), "L-7", null, "DH001")).defect())
                .isEqualTo("PROVIDENT_FUND_TYPE");
        assertThat(intake.receive(employee(n, pan(n), aadhaar(n), "E-2", null, "XX999")).defect())
                .isEqualTo("POSTING");
        assertThat(intake.receive(employee(n, "BADPAN", aadhaar(n), "E-2", null, "DH001")).defect())
                .isEqualTo("PAN");
        assertThat(intake.receive(employee(n, pan(n), aadhaar(n), "Z-99", null, "DH001")).defect())
                .isEqualTo("PAY_SCALE");
        HrmsNewEmployees.Received cda =
                intake.receive(employee(n, pan(n), aadhaar(n), "L-7", "NPS", "DH001"));
        assertThat(cda.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(record(cda.employeeId()).get("PROVIDENT_FUND_TYPE")).isEqualTo("NPS");
        assertThat(record(cda.employeeId()).get("PAY_PATTERN")).isEqualTo("CDA");
    }

    private MvcResult submit(Session s, String eis, String cda, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("cdaStaffCode", cda);
        body.put("reason", reason);
        return mvc.perform(userPost(s, "/v1/employees/" + eis + "/onboarding-submissions", body))
                .andReturn();
    }

    private String status(String eis) {
        return jdbc.queryForObject(
                "SELECT record_status FROM employee WHERE emp_num = ?", String.class, eis);
    }

    // SC-16-0147, SC-16-0149: a returned record is back in Payroll Setup Pending with the reason,
    // and Establishment / HR is told; SRS-1-11: approved, the record is Active. SC-16-0148 (the
    // onboarding main flow) and SC-16-0154 (the life-cycle status shows on the M-01 Status filter).
    @Test
    void SRS_1_12_hrSubmitsTheCheckerReturnsItAndThenApprovesTheResubmission() throws Exception {
        int n = NEXT.incrementAndGet();
        String eis = "ONB" + n;
        assertThat(intake.receive(employee(n, pan(n), aadhaar(n), "E-2", null, "DH001")).outcome())
                .isEqualTo(Outcome.CREATED);
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(m01(maker, eis, "PAYROLL_SETUP_PENDING")).contains(eis);
        assertThat(m01(maker, eis, "ACTIVE")).doesNotContain(eis);
        assertThat(readForComputation(eis)).as("not computed while set-up is pending").isFalse();
        assertThat(submit(maker, eis, null, "Ready").getResponse().getStatus())
                .as("only Establishment / HR submits")
                .isEqualTo(403);
        assertThat(code(submit(hr, eis, "CDA/1", "Ready")))
                .as("an IDA member has no CDA Staff Code")
                .isEqualTo("EMP-422-ONBOARD");

        MvcResult sent = submit(hr, eis, null, "Flags set");
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(status(eis)).isEqualTo("CHANGE_PENDING");
        assertThat(code(submit(hr, eis, null, "Again"))).isEqualTo("EMP-409-ONBOARD");
        long approval = body(sent).get("approvalId").asLong();
        MvcResult own =
                mvc.perform(
                                userPost(
                                        hr,
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(own.getResponse().getStatus()).as("never the maker").isEqualTo(403);
        assertThat(code(own)).isEqualTo("WF-403-SOD");
        assertThat(status(eis)).as("still waiting for the Checker").isEqualTo("CHANGE_PENDING");
        assertThat(decide(checker, approval, "RETURN", "Washing flag missing", null))
                .isEqualTo(200);
        assertThat(status(eis)).isEqualTo("PAYROLL_SETUP_PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification n JOIN app_user u ON u.user_id ="
                                        + " n.recipient_user_id WHERE u.login_id = ? AND"
                                        + " n.notification_type = 'ONBOARDING_RETURNED' AND"
                                        + " n.related_entity_key = ? AND n.message LIKE ?",
                                Integer.class,
                                "hr.delhi@fci-demo.test",
                                eis,
                                "%Washing flag missing%"))
                .as("Establishment / HR is told the reason")
                .isEqualTo(1);

        MvcResult again = submit(hr, eis, null, "Washing flag set");
        assertThat(again.getResponse().getStatus()).isEqualTo(202);
        assertThat(decide(checker, body(again).get("approvalId").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        assertThat(status(eis)).isEqualTo("ACTIVE");
        // The M-01 status line of an approved record is its data line (Active, or PAN / CPF code
        // missing or bank invalid until HRMS sends them: Employee.displayStatus), never set-up.
        assertThat(m01(maker, eis, "PAYROLL_SETUP_PENDING")).doesNotContain(eis);
        String line = statusLine(maker, eis);
        assertThat(line).isIn("ACTIVE", "PAN_CPF_MISSING", "BANK_INVALID");
        assertThat(m01(maker, eis, line)).contains(eis);
        // SC-16-0155: the approved, Active record is available to computation (the staff engine's
        // reader takes it) and to allowance-eligibility maintenance (M-01 lists its flags).
        assertThat(readForComputation(eis)).isTrue();
        assertThat(
                        mvc.perform(userGet(hr, "/v1/employees/" + eis + "/allowance-flags"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(code(submit(hr, eis, null, "Late"))).isEqualTo("EMP-409-ONBOARD");
    }

    /** Whether the staff engine's reader takes the member for this month (MemberReader). */
    private boolean readForComputation(String eis) {
        Map<String, Object> m =
                jdbc.queryForMap(
                        "SELECT employee_id, location_id FROM employee WHERE emp_num = ?", eis);
        long id = ((Number) m.get("EMPLOYEE_ID")).longValue();
        return !in.gov.fci.payroll.payroll.batch.computation.MemberReader.page(
                        named,
                        java.util.List.of(((Number) m.get("LOCATION_ID")).longValue()),
                        java.util.List.of(id),
                        java.time.YearMonth.now(java.time.ZoneId.of("Asia/Kolkata")).atDay(1),
                        0,
                        10)
                .isEmpty();
    }

    /** The M-01 status line the list shows for the member (no Status filter). */
    private String statusLine(Session s, String eis) throws Exception {
        for (JsonNode row :
                body(mvc.perform(userGet(s, "/v1/employees?q=" + eis)).andReturn()).path("items")) {
            if (eis.equals(row.path("eis").asString())) {
                return row.path("status").asString();
            }
        }
        throw new AssertionError(eis + " not on M-01");
    }

    /** The EIS numbers the M-01 list shows for the search text under the Status filter. */
    private java.util.List<String> m01(Session s, String eis, String statusLine) throws Exception {
        MvcResult r =
                mvc.perform(userGet(s, "/v1/employees?q=" + eis + "&status=" + statusLine))
                        .andReturn();
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(r).path("items").findValuesAsString("eis");
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }
}
