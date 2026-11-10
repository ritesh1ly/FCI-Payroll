package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * BMAD UI review fixes of the labour screens (SRS-2-30, SRS-2-46, SRS-2-31, SRS-2-4; I-01 l.4541,
 * I-02 l.4621 / l.4632): an M-02 amendment of the bank account keeps the PAN, PF flag, CPF code and
 * UAN (absent fields are kept; the form opens with the record masked); the gang pickers search on
 * the server (Ancillary Pool, payroll type, code or name in any case) with no 100-gang cap; a
 * gang's members are read as of a date; the Ancillary Pool is marked on I-01 and booked on I-02
 * without bag fields. Refused: the Payroll Maker amending, an amendment that changes nothing,
 * another office's member, an unknown gang type, bag work on the pool.
 */
class LabourPickerAndAmendmentIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long location(String code) {
        return id("SELECT location_id FROM org_location WHERE location_code = ?", code);
    }

    private long member(String code) {
        return id("SELECT employee_id FROM employee WHERE emp_num = ?", code);
    }

    private long gang(String code) {
        return id("SELECT gang_id FROM labour_gang WHERE gang_code = ?", code);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String errorCode(MvcResult r) throws Exception {
        String code = body(r).path("errorCode").asString();
        return code.isEmpty() ? status(r) + " " + r.getResponse().getContentAsString() : code;
    }

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private long approved(Session hr, String path, Object request) throws Exception {
        MvcResult made = mvc.perform(userPost(hr, path, request)).andReturn();
        assertThat(status(made)).as(made.getResponse().getContentAsString()).isEqualTo(202);
        long approval = body(made).get("id").asLong();
        assertThat(
                        decide(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        return approval;
    }

    @Test
    void SRS_2_30_anAmendmentOfTheBankAccountKeepsPanPfCpfCodeAndUan() throws Exception {
        Session hr = hr();
        Map<String, Object> m = new HashMap<>();
        m.put("payrollType", "DEPT_LABOUR");
        m.put("firstName", "Gopal");
        m.put("lastName", "Demo");
        m.put("fatherName", "Hari Demo");
        m.put("gender", "M");
        m.put("birthDate", "1986-03-10");
        m.put("joiningDate", "2011-07-01");
        m.put(
                "designationId",
                id("SELECT designation_id FROM org_designation WHERE designation_code = 'HL'"));
        m.put("depotLocationId", location("DP-DL01"));
        m.put("panNumber", "DMOPD3001K");
        m.put("ifsc", "SBIN0DEMO01");
        m.put("bankAccount", "0000093001");
        m.put("mobile", "9000033001");
        m.put("pfApplicable", true);
        m.put("cpfCode", "DL/93001");
        m.put("uanNumber", "100200393001");
        m.put("reason", "Appointment order BMAD-1");
        approved(hr, "/v1/labour/members", m);
        long gopal = id("SELECT employee_id FROM employee WHERE pan_number = 'DMOPD3001K'");

        // R-PII: the form opens with the record as it stands, PAN, account and mobile masked.
        JsonNode detail = body(mvc.perform(userGet(hr, "/v1/labour/members/" + gopal)).andReturn());
        // BMAD TR-035 follow-up (BR-SEC-001): the last four of the PAN only.
        assertThat(detail.get("panMasked").asString()).isEqualTo("******001K");
        assertThat(detail.get("bankAccountMasked").asString()).endsWith("3001").startsWith("*");
        assertThat(detail.get("mobileMasked").asString()).endsWith("3001").startsWith("*");
        assertThat(detail.get("cpfCode").asString()).isEqualTo("DL/93001");
        assertThat(detail.get("uanNumber").asString()).isEqualTo("100200393001");
        assertThat(detail.get("pfApplicable").asBoolean()).isTrue();
        assertThat(detail.get("fatherName").asString()).isEqualTo("Hari Demo");
        assertThat(detail.toString()).doesNotContain("DMOPD3001K", "0000093001", "9000033001");

        // Wrapper fields: only the bank account and the reason are sent.
        Map<String, Object> amend =
                Map.of("bankAccount", "0000093999", "reason", "Account changed per bank letter");
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        maker,
                                                        "/v1/labour/members/"
                                                                + gopal
                                                                + "/amendments",
                                                        amend))
                                        .andReturn()))
                .as("the Payroll Maker views only")
                .isEqualTo(403);
        assertThat(
                        errorCode(
                                mvc.perform(
                                                userPost(
                                                        hr,
                                                        "/v1/labour/members/"
                                                                + gopal
                                                                + "/amendments",
                                                        Map.of(
                                                                "mobile",
                                                                "9000033001",
                                                                "reason",
                                                                "Nothing new")))
                                        .andReturn()))
                .as("the value in force again changes nothing")
                .isEqualTo("LBR-422-ENTRY");
        approved(hr, "/v1/labour/members/" + gopal + "/amendments", amend);

        Map<String, Object> after =
                jdbc.queryForMap(
                        "SELECT e.pan_number, e.father_name, e.gender, e.mobile_number,"
                                + " e.bank_account_number, p.pf_applicable_flag, p.cpf_code,"
                                + " p.uan_number FROM employee e JOIN labour_profile p ON"
                                + " p.employee_id = e.employee_id WHERE e.employee_id = ?",
                        gopal);
        assertThat(after.get("BANK_ACCOUNT_NUMBER")).isEqualTo("0000093999");
        assertThat(after.get("PAN_NUMBER")).isEqualTo("DMOPD3001K");
        assertThat(after.get("FATHER_NAME")).isEqualTo("Hari Demo");
        assertThat(after.get("GENDER")).isEqualTo("M");
        assertThat(after.get("MOBILE_NUMBER")).isEqualTo("9000033001");
        assertThat(after.get("PF_APPLICABLE_FLAG")).isEqualTo("Y");
        assertThat(after.get("CPF_CODE")).isEqualTo("DL/93001");
        assertThat(after.get("UAN_NUMBER")).isEqualTo("100200393001");

        // Another office's desk never reads the member.
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(status(mvc.perform(userGet(kolkata, "/v1/labour/members/" + gopal)).andReturn()))
                .isEqualTo(404);
        assertThat(status(mvc.perform(userGet(hr, "/v1/labour/members/0")).andReturn()))
                .isEqualTo(400);
    }

    @Test
    void I_01_theGangPickersSearchOnTheServerAndOfferTheAncillaryPool() throws Exception {
        Session ldh = login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
        JsonNode pool =
                body(
                        mvc.perform(userGet(ldh, "/v1/labour/gangs?gangType=ANCILLARY_POOL"))
                                .andReturn());
        assertThat(pool.findValuesAsString("gangCode")).contains("DP-DL01-G001");
        assertThat(pool.findValuesAsString("gangType")).containsOnly("ANCILLARY_POOL");
        // Code or name, any case.
        JsonNode byName =
                body(mvc.perform(userGet(ldh, "/v1/labour/gangs?text=okhla")).andReturn());
        assertThat(byName.findValuesAsString("gangCode")).contains("DP-DL01-G002");
        assertThat(byName.findValuesAsString("gangCode")).doesNotContain("DP-DL01-G001");
        JsonNode byCode =
                body(mvc.perform(userGet(ldh, "/v1/labour/gangs?text=dp-dl01-g00")).andReturn());
        assertThat(byCode.findValuesAsString("gangCode")).contains("DP-DL01-G001", "DP-DL01-G002");
        // SRS-2-31: a target gang of another payroll type is not offered.
        JsonNode dps =
                body(
                        mvc.perform(
                                        userGet(
                                                ldh,
                                                "/v1/labour/gangs?payrollType=DPS_LABOUR&status=ACTIVE"))
                                .andReturn());
        assertThat(dps.findValuesAsString("payrollType")).doesNotContain("DEPT_LABOUR");
        assertThat(
                        body(mvc.perform(userGet(ldh, "/v1/labour/gangs?text=NOSUCHGANG"))
                                        .andReturn())
                                .get("total")
                                .asLong())
                .isZero();
        assertThat(status(mvc.perform(userGet(ldh, "/v1/labour/gangs?gangType=POOL")).andReturn()))
                .isEqualTo(400);
        assertThat(status(mvc.perform(userGet(ldh, "/v1/labour/gangs?status=OPEN")).andReturn()))
                .isEqualTo(400);
        // Another office's desk sees none of Okhla's gangs.
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(
                        body(mvc.perform(userGet(kolkata, "/v1/labour/gangs?text=DP-DL01"))
                                        .andReturn())
                                .get("total")
                                .asLong())
                .isZero();
    }

    @Test
    void I_02_aGangsMembersAreReadAsOfTheSlipDate() throws Exception {
        Session ldh = login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
        long okhla = gang("DP-DL01-G002");
        LocalDate from =
                jdbc.queryForObject(
                                "SELECT effective_from FROM labour_gang WHERE gang_id = ?",
                                java.sql.Date.class,
                                okhla)
                        .toLocalDate();
        JsonNode today =
                body(
                        mvc.perform(userGet(ldh, "/v1/labour/gangs/" + okhla + "/members"))
                                .andReturn());
        assertThat(today.findValuesAsString("labourCode")).contains("L10701", "L10702");
        JsonNode onFirstDay =
                body(
                        mvc.perform(
                                        userGet(
                                                ldh,
                                                "/v1/labour/gangs/"
                                                        + okhla
                                                        + "/members?date="
                                                        + from))
                                .andReturn());
        assertThat(onFirstDay.findValuesAsString("labourCode")).contains("L10701", "L10702");
        JsonNode dayBefore =
                body(
                        mvc.perform(
                                        userGet(
                                                ldh,
                                                "/v1/labour/gangs/"
                                                        + okhla
                                                        + "/members?date="
                                                        + from.minusDays(1)))
                                .andReturn());
        assertThat(dayBefore.isArray()).isTrue();
        assertThat(dayBefore).as("nobody is a member before the gang formed").isEmpty();
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        ldh,
                                                        "/v1/labour/gangs/"
                                                                + okhla
                                                                + "/members?date=31-12-2025"))
                                        .andReturn()))
                .isEqualTo(400);
    }

    @Test
    void SRS_2_46_theAncillaryPoolIsMarkedAndBookedWithoutBagFields() throws Exception {
        Session ldh = login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
        long pool = gang("DP-DL01-G001");
        long kishan = member("L10703");
        LocalDate day = YearMonth.now(IST).minusMonths(4).atDay(9);
        MvcResult marked =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/gangs/" + pool + "/attendance-days",
                                        Map.of(
                                                "date",
                                                day.toString(),
                                                "marks",
                                                List.of(
                                                        Map.of(
                                                                "employeeId",
                                                                kishan,
                                                                "code",
                                                                "W")))))
                        .andReturn();
        assertThat(status(marked)).as(marked.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode grid =
                body(
                        mvc.perform(
                                        userGet(
                                                ldh,
                                                "/v1/labour/gangs/"
                                                        + pool
                                                        + "/attendance?month="
                                                        + YearMonth.from(day)))
                                .andReturn());
        assertThat(grid.get("sanctionedStrength").isNull()).as("the pool has no strength").isTrue();
        assertThat(grid.findValuesAsString("labourCode")).containsExactly("L10703");

        Map<String, Object> bagRow = new HashMap<>();
        bagRow.put(
                "operationId",
                id("SELECT operation_id FROM operation_master WHERE operation_code = 'W2P'"));
        bagRow.put(
                "bagTypeId",
                id("SELECT bag_type_id FROM bag_type WHERE bag_type_code = 'KATTA50'"));
        bagRow.put("bags", 100);
        bagRow.put("stackHeight", 10);
        bagRow.put("start", "09:00");
        bagRow.put("end", "11:00");
        MvcResult bags =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/gangs/" + pool + "/work-slips",
                                        Map.of(
                                                "date",
                                                day.toString(),
                                                "continueWork",
                                                false,
                                                "rows",
                                                List.of(bagRow),
                                                "participants",
                                                List.of(kishan))))
                        .andReturn();
        assertThat(errorCode(bags)).isEqualTo("LBR-422-WORK-SLIP");
        assertThat(bags.getResponse().getContentAsString()).contains("POOL_NO_BAGS");

        Map<String, Object> pbnw = new HashMap<>();
        pbnw.put(
                "operationId",
                id("SELECT operation_id FROM operation_master WHERE operation_code = 'PBNW'"));
        pbnw.put("start", "09:00");
        pbnw.put("end", "11:00");
        pbnw.put("pbnwReason", "RAKE_DELAYED");
        MvcResult saved =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/gangs/" + pool + "/work-slips",
                                        Map.of(
                                                "date",
                                                day.toString(),
                                                "continueWork",
                                                false,
                                                "rows",
                                                List.of(pbnw),
                                                "participants",
                                                List.of(kishan))))
                        .andReturn();
        assertThat(status(saved)).as(saved.getResponse().getContentAsString()).isEqualTo(201);
        // SRS-2-15: the saved rows carry their ids, so an amendment opens with them.
        JsonNode row = body(saved).get("rows").get(0);
        assertThat(row.get("operationId").asLong())
                .isEqualTo(
                        id(
                                "SELECT operation_id FROM operation_master WHERE operation_code ="
                                        + " 'PBNW'"));
        assertThat(row.get("bagTypeId").isNull()).isTrue();
    }
}
