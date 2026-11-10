package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import in.gov.fci.payroll.employee.api.HrmsChanges;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The refusals of I-03 / I-16 (plan 10; SRS-11-1, 11-5, 11-6, 11-9, 11-13, 11-16, R-RULE): every
 * entry, validation, maintenance and HRMS intake that cannot be applied is refused naming why, and
 * nothing is written. Members of depot DP-DL01 (labour) and Delhi (staff) near the start of the
 * list.
 */
class ClaimRefusalsIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private HrmsChanges intake;

    private Session ldh() throws Exception {
        return login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
    }

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private String labour(int index) {
        return jdbc.queryForObject(
                "SELECT e.emp_num FROM employee e JOIN org_location l ON l.location_id ="
                        + " e.location_id WHERE e.employee_type = 'DEPT_LABOUR' AND"
                        + " e.record_status <> 'CANCELLED' AND l.location_code = 'DP-DL01' ORDER BY"
                        + " e.emp_num OFFSET "
                        + index
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                String.class);
    }

    private long staff(int index) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                    + " 'ACTIVE' AND employment_status = 'IN_SERVICE' AND employee_type = 'STAFF'"
                    + " ORDER BY emp_num OFFSET "
                        + index
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"));
    }

    private MvcResult enter(Session s, Map<String, Object> claim) throws Exception {
        return mvc.perform(
                        userRequest(
                                        s,
                                        multipart("/v1/claims")
                                                .file(
                                                        new MockMultipartFile(
                                                                "claim",
                                                                "",
                                                                MediaType.APPLICATION_JSON_VALUE,
                                                                json.writeValueAsBytes(claim))))
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private static Map<String, Object> claim(
            String eis, String type, LocalDate from, LocalDate to, String amount) {
        Map<String, Object> c = new HashMap<>();
        c.put("memberNumber", eis);
        c.put("claimType", type);
        c.put("periodFrom", from.toString());
        c.put("periodTo", to.toString());
        c.put("claimedAmount", amount);
        return c;
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    @Test
    void SRS_11_13_aLabourClaimThatCannotBeEnteredIsRefusedNamingWhy() throws Exception {
        String eis = labour(2);
        LocalDate first = YearMonth.now(IST).minusMonths(5).atDay(1);
        Session ldh = ldh();
        // The period ends before it starts; an unknown type; an unknown member.
        assertThat(error(enter(ldh, claim(eis, "TRANSFER_TA", first.plusDays(2), first, "100"))))
                .isEqualTo("ALW-422-CLAIM");
        assertThat(error(enter(ldh, claim(eis, "NOT_A_TYPE", first, first, "100"))))
                .isEqualTo("ALW-422-CLAIM");
        assertThat(status(enter(ldh, claim("ZZ99999", "TRANSFER_TA", first, first, "100"))))
                .isEqualTo(404);
        // An employee claim type is never entered on I-03 (SRS-11-1).
        assertThat(error(enter(ldh, claim(eis, "MEDICAL", first, first, "100"))))
                .isEqualTo("ALW-422-CLAIM");
        // Bean validation: three decimals, a negative amount.
        assertThat(status(enter(ldh, claim(eis, "TRANSFER_TA", first, first, "1.255"))))
                .isEqualTo(400);
        assertThat(status(enter(ldh, claim(eis, "TRANSFER_TA", first, first, "-1"))))
                .isEqualTo(400);
        // R-RULE: before the norm's effective date there is no norm: not configured, never
        // unlimited.
        assertThat(
                        error(
                                enter(
                                        ldh,
                                        claim(
                                                eis,
                                                "TRANSFER_TA",
                                                LocalDate.of(2020, 1, 1),
                                                LocalDate.of(2020, 1, 1),
                                                "100"))))
                .isEqualTo("ALW-422-NOT-CONFIGURED");
        // A document part without its particulars (or the other way round) is a bad request.
        Map<String, Object> withDoc = claim(eis, "TRANSFER_TA", first, first, "100");
        Map<String, Object> doc = new HashMap<>();
        doc.put("documentType", "OTHER");
        doc.put("orderDate", first.toString());
        doc.put("issuingAuthority", "Depot Okhla");
        withDoc.put("documents", java.util.List.of(doc));
        assertThat(status(enter(ldh, withDoc))).isEqualTo(400);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM reimbursement_claim c JOIN employee e ON"
                                        + " e.employee_id = c.employee_id WHERE e.emp_num = ?",
                                Integer.class,
                                eis))
                .as("nothing written")
                .isZero();
        // The register and its filters: a bad claim type code and a bad month are refused.
        Session maker = maker();
        assertThat(status(mvc.perform(userGet(maker, "/v1/claims?claimType=bad")).andReturn()))
                .isEqualTo(400);
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        maker,
                                                        "/v1/claims/medical-register?yymm=202613"))
                                        .andReturn()))
                .isEqualTo(400);
        assertThat(status(mvc.perform(userGet(maker, "/v1/claims/99999999")).andReturn()))
                .isEqualTo(404);
        assertThat(
                        status(
                                mvc.perform(userGet(maker, "/v1/claims/types?memberType=EMPLOYEE"))
                                        .andReturn()))
                .isEqualTo(200);
    }

    /**
     * I-03 Employee / Labour Number "Active member within the user's scope" (SRS l.2796,
     * US-GAP-216): a separated labour member's claim is neither entered nor, if the member
     * separates after its entry, validated; nothing is written.
     */
    @Test
    void SRS_11_13_aClaimOfAMemberNoLongerActiveIsNeitherEnteredNorValidated() throws Exception {
        String eis = labour(4);
        LocalDate first = YearMonth.now(IST).minusMonths(7).atDay(1);
        Session ldh = ldh();
        long id =
                body(enter(ldh, claim(eis, "TRANSFER_TA", first, first, "100"))).get("id").asLong();
        try {
            jdbc.update(
                    "UPDATE employee SET employment_status = 'SEPARATED' WHERE emp_num = ?", eis);
            MvcResult entry =
                    enter(
                            ldh,
                            claim(
                                    eis,
                                    "TRANSFER_TA",
                                    first.plusDays(10),
                                    first.plusDays(10),
                                    "100"));
            assertThat(status(entry)).isEqualTo(422);
            assertThat(error(entry)).isEqualTo("ALW-422-INACTIVE");
            assertThat(body(entry).get("args").get(1).asString()).isEqualTo("SEPARATED");
            MvcResult validation =
                    mvc.perform(userPost(maker(), "/v1/claims/" + id + "/validations", Map.of()))
                            .andReturn();
            assertThat(error(validation)).isEqualTo("ALW-422-INACTIVE");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT c.claim_status FROM reimbursement_claim c WHERE"
                                            + " c.claim_id = ?",
                                    String.class,
                                    id))
                    .as("the entered claim waits as it was")
                    .isEqualTo("SUBMITTED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM reimbursement_claim c JOIN employee e ON"
                                            + " e.employee_id = c.employee_id WHERE e.emp_num = ?",
                                    Integer.class,
                                    eis))
                    .as("nothing more written")
                    .isEqualTo(1);
        } finally {
            jdbc.update(
                    "UPDATE employee SET employment_status = 'IN_SERVICE' WHERE emp_num = ?", eis);
        }
    }

    @Test
    void SRS_11_13_aClaimNotOfTheMakersStateOrTypeIsNotValidatedOrMaintained() throws Exception {
        String eis = labour(3);
        LocalDate first = YearMonth.now(IST).minusMonths(6).atDay(1);
        long id =
                body(enter(ldh(), claim(eis, "TRANSFER_TA", first, first, "100")))
                        .get("id")
                        .asLong();
        Session maker = maker();
        // A labour claim is not a medical claim: no taxable flag, no IOGA.
        assertThat(
                        error(
                                mvc.perform(
                                                userPost(
                                                        maker,
                                                        "/v1/claims/"
                                                                + id
                                                                + "/taxable-confirmations",
                                                        Map.of("taxable", true)))
                                        .andReturn()))
                .isEqualTo("ALW-409-STATE");
        Map<String, Object> ioga = new HashMap<>();
        ioga.put("number", "IOGA/R/1");
        ioga.put("date", LocalDate.now(IST).toString());
        ioga.put("amount", "10");
        ioga.put("originatingOffice", "DO Mumbai");
        assertThat(
                        error(
                                mvc.perform(userPost(maker, "/v1/claims/" + id + "/iogas", ioga))
                                        .andReturn()))
                .isEqualTo("ALW-409-STATE");
        // An HRMS claim is no claim until its I-21 task is approved.
        long employee = staff(2);
        String request = "IT-REF-" + System.nanoTime();
        intake.receive(
                new HrmsChanges.Change(
                        employee,
                        "REIMBURSEMENT_CLAIM",
                        "{\"claimType\":\"MEDICAL\",\"periodFrom\":\""
                                + first
                                + "\",\"periodTo\":\""
                                + first
                                + "\",\"amount\":\"100.50\",\"schemeType\":\"CGHS\"}",
                        LocalDate.now(IST),
                        request,
                        HrmsChanges.Source.HRMS_PUSH));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM reimbursement_claim WHERE hrms_request_id ="
                                        + " ?",
                                Integer.class,
                                request))
                .as("an HRMS claim is not a claim until its task is approved")
                .isZero();
        // Validation twice: the first moves it on, the second is refused; a missing claim is 404.
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        maker,
                                                        "/v1/claims/" + id + "/validations",
                                                        Map.of()))
                                        .andReturn()))
                .isEqualTo(202);
        assertThat(
                        error(
                                mvc.perform(
                                                userPost(
                                                        maker(),
                                                        "/v1/claims/" + id + "/validations",
                                                        Map.of()))
                                        .andReturn()))
                .isEqualTo("ALW-409-STATE");
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        maker(),
                                                        "/v1/claims/99999999/validations",
                                                        Map.of()))
                                        .andReturn()))
                .isEqualTo(404);
        assertThat(
                        status(
                                mvc.perform(userGet(maker(), "/v1/claims/medical?eis=bad-eis!"))
                                        .andReturn()))
                .isEqualTo(400);
    }

    private void refused(long employee, String field, String value, String code) {
        assertThatThrownBy(
                        () ->
                                intake.receive(
                                        new HrmsChanges.Change(
                                                employee,
                                                field,
                                                value,
                                                LocalDate.now(IST),
                                                "IT-REF-" + System.nanoTime(),
                                                HrmsChanges.Source.HRMS_PUSH)))
                .as(value)
                .hasMessageContaining(code);
    }

    @Test
    void SRS_11_1_anHrmsClaimThatCouldNotBePaidIsRefusedAtIntake() throws Exception {
        long employee = staff(3);
        LocalDate first = YearMonth.now(IST).minusMonths(2).atDay(1);
        String period = "\"periodFrom\":\"" + first + "\",\"periodTo\":\"" + first + "\"";
        refused(employee, "REIMBURSEMENT_CLAIM", "not json", "ALW-422-CLAIM");
        refused(employee, "REIMBURSEMENT_CLAIM", "[1]", "ALW-422-CLAIM");
        refused(
                employee,
                "REIMBURSEMENT_CLAIM",
                "{\"claimType\":\"bad\"," + period + ",\"amount\":1}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "REIMBURSEMENT_CLAIM",
                "{\"claimType\":\"MEDICAL\",\"periodFrom\":\"x\",\"amount\":1}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "REIMBURSEMENT_CLAIM",
                "{\"claimType\":\"MEDICAL\"," + period + ",\"amount\":\"abc\"}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "REIMBURSEMENT_CLAIM",
                "{\"claimType\":\"MEDICAL\"," + period + ",\"amount\":0}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "REIMBURSEMENT_CLAIM",
                "{\"claimType\":\"MEDICAL\","
                        + period
                        + ",\"amount\":1,\"schemeType\":\""
                        + "x".repeat(41)
                        + "\"}",
                "ALW-422-CLAIM");
        // A labour claim type, and an employee type whose norm is not confirmed (NEWSPAPER, demo).
        refused(
                employee,
                "REIMBURSEMENT_CLAIM",
                "{\"claimType\":\"LABOUR_TA\"," + period + ",\"amount\":1}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "REIMBURSEMENT_CLAIM",
                "{\"claimType\":\"NEWSPAPER\"," + period + ",\"amount\":1}",
                "ALW-422-NOT-CONFIGURED");
        // A labour member's claim is entered on I-03, not received from HRMS.
        long labourId =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?",
                        Long.class,
                        labour(2));
        refused(
                labourId,
                "REIMBURSEMENT_CLAIM",
                "{\"claimType\":\"MEDICAL\"," + period + ",\"amount\":1}",
                "ALW-422-CLAIM");
        // SRS-11-5: a retired member's medical claim is refused.
        long retired = staff(4);
        Object before =
                jdbc.queryForObject(
                        "SELECT retirement_date FROM employee WHERE employee_id = ?",
                        Object.class,
                        retired);
        jdbc.update(
                "UPDATE employee SET retirement_date = ? WHERE employee_id = ?",
                java.sql.Date.valueOf(first.minusDays(1)),
                retired);
        try {
            refused(
                    retired,
                    "REIMBURSEMENT_CLAIM",
                    "{\"claimType\":\"MEDICAL\"," + period + ",\"amount\":1}",
                    "ALW-422-RETIRED");
        } finally {
            jdbc.update(
                    "UPDATE employee SET retirement_date = ? WHERE employee_id = ?",
                    before,
                    retired);
        }
    }

    @Test
    void SRS_11_16_aCafeteriaSelectionThatCouldNotBeAppliedIsRefusedAtIntake() throws Exception {
        long employee = staff(5);
        LocalDate today = LocalDate.now(IST);
        int fy = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        String finYear = fy + "-" + String.format("%02d", (fy + 1) % 100);
        String start = "\"effectiveFrom\":\"" + today + "\"";
        String year = "\"finYear\":\"" + finYear + "\"";
        refused(employee, "CAFETERIA_SELECTION", "not json", "ALW-422-CLAIM");
        refused(employee, "CAFETERIA_SELECTION", "\"text\"", "ALW-422-CLAIM");
        refused(
                employee,
                "CAFETERIA_SELECTION",
                "{\"finYear\":\"2026\"," + start + "}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "CAFETERIA_SELECTION",
                "{" + year + ",\"effectiveFrom\":\"x\"}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "CAFETERIA_SELECTION",
                "{"
                        + year
                        + ",\"effectiveFrom\":\""
                        + LocalDate.of(fy + 2, 5, 1)
                        + "\",\"components\":[]}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "CAFETERIA_SELECTION",
                "{" + year + "," + start + ",\"components\":[]}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "CAFETERIA_SELECTION",
                "{" + year + "," + start + ",\"components\":[{\"payCode\":917,\"amount\":\"x\"}]}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "CAFETERIA_SELECTION",
                "{"
                        + year
                        + ","
                        + start
                        + ",\"components\":[{\"payCode\":917,\"amount\":1},{\"payCode\":917,\"amount\":2}]}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "CAFETERIA_SELECTION",
                "{" + year + "," + start + ",\"components\":[{\"payCode\":917,\"amount\":-1}]}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "CAFETERIA_SELECTION",
                "{" + year + "," + start + ",\"components\":[{\"payCode\":917,\"amount\":1.255}]}",
                "ALW-422-CLAIM");
        refused(
                employee,
                "CAFETERIA_SELECTION",
                "{" + year + "," + start + ",\"components\":[{\"payCode\":999,\"amount\":1}]}",
                "ALW-422-CLAIM");
        long labourId =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?",
                        Long.class,
                        labour(3));
        refused(
                labourId,
                "CAFETERIA_SELECTION",
                "{" + year + "," + start + ",\"components\":[{\"payCode\":917,\"amount\":1}]}",
                "ALW-422-CLAIM");
        Session maker = maker();
        assertThat(
                        status(
                                mvc.perform(userGet(maker, "/v1/cafeteria-selections?eis=ZZ99999"))
                                        .andReturn()))
                .isEqualTo(200);
        assertThat(
                        status(
                                mvc.perform(userGet(maker, "/v1/cafeteria-selections?eis=bad!"))
                                        .andReturn()))
                .isEqualTo(400);
    }
}
