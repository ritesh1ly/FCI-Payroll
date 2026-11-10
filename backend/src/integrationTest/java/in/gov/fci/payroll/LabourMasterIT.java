package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 20 phase 20a (plan docs/plan/20-labour-common-departmental.md; SRS-2-30, SRS-2-1,
 * SRS-2-46, SRS-2-48; BR-LABM-001; D20-1): Establishment / HR of Delhi records two labour members
 * of Depot Okhla on M-02, the Payroll Checker approves them on W-01 and they get labour codes; HR
 * forms a handling gang of them on M-09 (one Sardar, one Mandal) and changes its Sanctioned
 * Strength, each approved by the Checker. Refused: the Payroll Maker (view only), another office's
 * depot, an invalid entry, a PAN already held, a gang without a Mandal, a member already in a gang,
 * a name taken in the depot, HR deciding its own change.
 */
class LabourMasterIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long location(String code) {
        return id("SELECT location_id FROM org_location WHERE location_code = ?", code);
    }

    private long designation(String code) {
        return id("SELECT designation_id FROM org_designation WHERE designation_code = ?", code);
    }

    private static Map<String, Object> member(
            String first, String pan, String account, long designation, long depot) {
        Map<String, Object> m = new HashMap<>();
        m.put("payrollType", "DEPT_LABOUR");
        m.put("firstName", first);
        m.put("lastName", "Demo");
        m.put("gender", "M");
        m.put("birthDate", "1985-06-15");
        m.put("joiningDate", "2010-07-01");
        m.put("designationId", designation);
        m.put("depotLocationId", depot);
        m.put("panNumber", pan);
        m.put("ifsc", "SBIN0DEMO01");
        m.put("bankAccount", account);
        m.put("mobile", "9000030101");
        m.put("pfApplicable", false);
        m.put("reason", "Appointment order 20a");
        return m;
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String errorCode(MvcResult r) throws Exception {
        String code = body(r).path("errorCode").asString();
        return code.isEmpty() ? status(r) + " " + r.getResponse().getContentAsString() : code;
    }

    /** Submits, then the Delhi Payroll Checker approves; answers the approval id. */
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

    private long created(Session hr, Map<String, Object> request) throws Exception {
        long approval = approved(hr, "/v1/labour/members", request);
        String key =
                jdbc.queryForObject(
                        "SELECT entity_key FROM approval_queue WHERE approval_id = ?",
                        String.class,
                        approval);
        assertThat(key).startsWith("NEW-");
        return id(
                "SELECT e.employee_id FROM employee e JOIN labour_profile p ON p.employee_id ="
                        + " e.employee_id WHERE e.pan_number = ?",
                request.get("panNumber"));
    }

    // SC-20-0108 (Maintain Labour Master, main flow: an editable field changed with its reason,
    // approved by the Checker), SC-20-0114 (verify: the approved change is the record in force for
    // the next cycle; the computation part waits for C-03, PENDING LBR-20d-3), SC-20-0130 (Maintain
    // Gang / Shed Structure, main flow), SC-20-0135 (verify: the effective-dated gang is available
    // to attendance and work-slip).
    @Test
    void SRS_2_30_hrRecordsLabourAndFormsAGangTheCheckerApproves() throws Exception {
        long okhla = location("DP-DL01");
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");

        long sardar =
                created(
                        hr,
                        member("Balwant", "DMOPD2001A", "0000092001", designation("SDR"), okhla));
        long mandal =
                created(
                        hr,
                        member("Charan", "DMOPD2002B", "0000092002", designation("MDL"), okhla));
        Map<String, Object> profile =
                jdbc.queryForMap(
                        "SELECT p.labour_number, p.payroll_type, p.labour_status, e.emp_num,"
                            + " e.emp_lbr_flag, e.employee_type, e.cpf_code, e.location_id FROM"
                            + " labour_profile p JOIN employee e ON e.employee_id = p.employee_id"
                            + " WHERE p.employee_id = ?",
                        sardar);
        assertThat((String) profile.get("LABOUR_NUMBER")).matches("DPT-\\d{6}");
        assertThat(profile.get("EMP_NUM")).isEqualTo(profile.get("LABOUR_NUMBER"));
        assertThat(profile.get("EMP_LBR_FLAG")).isEqualTo("Y");
        assertThat(profile.get("EMPLOYEE_TYPE")).isEqualTo("DEPT_LABOUR");
        assertThat(profile.get("LABOUR_STATUS")).isEqualTo("ACTIVE");
        assertThat(profile.get("CPF_CODE")).as("the labour CPF code stays on the profile").isNull();
        assertThat(((Number) profile.get("LOCATION_ID")).longValue())
                .isEqualTo(location("DP-DL01"));

        JsonNode page =
                body(
                        mvc.perform(userGet(hr, "/v1/labour/members?text=BALWANT&size=10"))
                                .andReturn());
        assertThat(page.get("total").asLong()).isEqualTo(1);
        JsonNode row = page.get("items").get(0);
        assertThat(row.get("labourCode").asString()).isEqualTo(profile.get("LABOUR_NUMBER"));
        // BR-SEC-001 (SRS l.754): the last four of the PAN only (BMAD TR-035 follow-up).
        assertThat(row.get("panMasked").asString()).isEqualTo("******001A");

        Map<String, Object> amended =
                member("Balwant", "DMOPD2001A", "0000092001", designation("SDR"), okhla);
        amended.put("mobile", "9000030199");
        amended.put("casualLabour", true);
        amended.put("reason", "Mobile number corrected");
        // SRS-2-59: an amendment never carries the name or the date of birth (the correction does);
        // nor the payroll type, designation or depot.
        assertThat(
                        mvc.perform(
                                        userPost(
                                                hr,
                                                "/v1/labour/members/" + sardar + "/amendments",
                                                amended))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .contains("UnknownProperty");
        for (String kept :
                List.of(
                        "payrollType",
                        "firstName",
                        "lastName",
                        "birthDate",
                        "designationId",
                        "depotLocationId")) {
            amended.remove(kept);
        }
        approved(hr, "/v1/labour/members/" + sardar + "/amendments", amended);
        Map<String, Object> after =
                jdbc.queryForMap(
                        "SELECT mobile_number, first_name, birth_date, casual_labour_flag FROM"
                                + " employee WHERE employee_id = ?",
                        sardar);
        assertThat(after.get("MOBILE_NUMBER")).isEqualTo("9000030199");
        // SC-20-0001 / SRS-G-55: the Casual Labour indicator on the labour master record.
        assertThat(after.get("CASUAL_LABOUR_FLAG")).isEqualTo("Y");
        assertThat(after.get("FIRST_NAME")).isEqualTo("Balwant");
        assertThat(
                        body(mvc.perform(userGet(hr, "/v1/labour/members?text=BALWANT&size=10"))
                                        .andReturn())
                                .get("items")
                                .get(0)
                                .get("casualLabour")
                                .asBoolean())
                .isTrue();

        LocalDate today = LocalDate.now(IST);
        Map<String, Object> gang = new HashMap<>();
        gang.put("depotLocationId", okhla);
        gang.put("payrollType", "DEPT_LABOUR");
        gang.put("gangName", "Okhla Gang 20A");
        gang.put("sanctionedStrength", 4);
        gang.put("effectiveFrom", today.toString());
        gang.put("reason", "New gang for shed 2");
        gang.put(
                "members",
                List.of(
                        Map.of("employeeId", sardar, "role", "SARDAR"),
                        Map.of("employeeId", mandal, "role", "MANDAL")));
        approved(hr, "/v1/labour/gangs", gang);
        long gangId =
                id(
                        "SELECT gang_id FROM labour_gang WHERE gang_name = 'Okhla Gang 20A' AND"
                                + " depot_location_id = ?",
                        okhla);
        JsonNode members =
                body(
                        mvc.perform(userGet(hr, "/v1/labour/gangs/" + gangId + "/members"))
                                .andReturn());
        assertThat(members).hasSize(2);
        // SC-20-0135: from its effective date the approved gang takes attendance and its
        // work-slip register opens for the Labour Dealing Hand of the depot.
        Session ldh = login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
        MvcResult marked =
                mvc.perform(
                                userPost(
                                        ldh,
                                        "/v1/labour/gangs/" + gangId + "/attendance-days",
                                        Map.of(
                                                "date",
                                                today.toString(),
                                                "marks",
                                                List.of(
                                                        Map.of("employeeId", sardar, "code", "W"),
                                                        Map.of(
                                                                "employeeId",
                                                                mandal,
                                                                "code",
                                                                "W")))))
                        .andReturn();
        assertThat(status(marked)).as(marked.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        ldh,
                                                        "/v1/labour/gangs/"
                                                                + gangId
                                                                + "/work-slips?month="
                                                                + java.time.YearMonth.from(today)))
                                        .andReturn()))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ancillary_pool WHERE depot_location_id = ?"
                                        + " AND pool_status = 'ACTIVE'",
                                Integer.class,
                                okhla))
                .as("one Ancillary Pool per depot")
                .isEqualTo(1);
        JsonNode gangs =
                body(mvc.perform(userGet(hr, "/v1/labour/gangs?depot=" + okhla)).andReturn());
        assertThat(gangs.get("total").asLong()).isGreaterThanOrEqualTo(3);

        approved(
                hr,
                "/v1/labour/gangs/" + gangId + "/sanctioned-strengths",
                Map.of(
                        "strength",
                        6,
                        "effectiveFrom",
                        today.plusDays(1).toString(),
                        "reason",
                        "Second shift"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sanctioned_strength FROM labour_gang_strength WHERE gang_id"
                                        + " = ? AND effective_to IS NULL",
                                Integer.class,
                                gangId))
                .isEqualTo(6);
    }

    @Test
    // SRS-3-11: the PAN is unique across labour records (a PAN already held is refused).
    void SRS_2_30_SRS_3_11_onlyHrOfTheOfficeRecordsAValidUniqueEntry() throws Exception {
        long okhla = location("DP-DL01");
        long hl = designation("HL");
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");

        assertThat(status(mvc.perform(userGet(maker, "/v1/labour/members")).andReturn()))
                .isEqualTo(200);
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        maker,
                                                        "/v1/labour/members",
                                                        member(
                                                                "Dev",
                                                                "DMOPD2003C",
                                                                "0000092003",
                                                                hl,
                                                                okhla)))
                                        .andReturn()))
                .as("the Payroll Maker views only")
                .isEqualTo(403);
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        hr,
                                                        "/v1/labour/members",
                                                        member(
                                                                "Dev",
                                                                "DMOPD2003C",
                                                                "0000092003",
                                                                hl,
                                                                location("DP-MH01"))))
                                        .andReturn()))
                .as("another office's depot")
                .isEqualTo(403);
        Map<String, Object> young = member("Dev", "DMOPD2003C", "0000092003", hl, okhla);
        young.put("birthDate", "2011-01-01");
        assertThat(errorCode(mvc.perform(userPost(hr, "/v1/labour/members", young)).andReturn()))
                .isEqualTo("LBR-422-ENTRY");
        assertThat(
                        errorCode(
                                mvc.perform(
                                                userPost(
                                                        hr,
                                                        "/v1/labour/members",
                                                        member(
                                                                "Dev",
                                                                jdbc.queryForObject(
                                                                        "SELECT pan_number FROM"
                                                                            + " employee WHERE"
                                                                            + " emp_num = 'L10002'",
                                                                        String.class),
                                                                "0000092003",
                                                                hl,
                                                                okhla)))
                                        .andReturn()))
                .as("a PAN already held")
                .isEqualTo("LBR-409-DUPLICATE");

        MvcResult made =
                mvc.perform(
                                userPost(
                                        hr,
                                        "/v1/labour/members",
                                        member("Dev", "DMOPD2003C", "0000092003", hl, okhla)))
                        .andReturn();
        assertThat(status(made)).isEqualTo(202);
        assertThat(decide(hr, body(made).get("id").asLong(), "APPROVE", null, null))
                .as("HR never decides its own change")
                .isEqualTo(403);
    }

    @Test
    void SRS_2_1_aGangNeedsOneSardarOneMandalFreeMembersAndAFreeName() throws Exception {
        long okhla = location("DP-DL01");
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        long sardar = id("SELECT employee_id FROM employee WHERE emp_num = 'L10701'");
        long handling = id("SELECT employee_id FROM employee WHERE emp_num = 'L10501'");
        Map<String, Object> gang = new HashMap<>();
        gang.put("depotLocationId", okhla);
        gang.put("payrollType", "DEPT_LABOUR");
        gang.put("gangName", "Okhla Gang 20B");
        gang.put("effectiveFrom", LocalDate.now(IST).toString());
        gang.put("reason", "New gang");
        gang.put(
                "members",
                List.of(
                        Map.of("employeeId", sardar, "role", "SARDAR"),
                        Map.of("employeeId", handling, "role", "HANDLING")));
        // BR-LABM-011 / M-09 l.4498: a handling gang has its Sanctioned Strength.
        assertThat(body(mvc.perform(userPost(hr, "/v1/labour/gangs", gang)).andReturn()).toString())
                .contains("STRENGTH");
        gang.put("sanctionedStrength", 6);
        MvcResult noMandal = mvc.perform(userPost(hr, "/v1/labour/gangs", gang)).andReturn();
        assertThat(errorCode(noMandal)).isEqualTo("LBR-422-GANG");

        gang.put(
                "members",
                List.of(
                        Map.of("employeeId", sardar, "role", "SARDAR"),
                        Map.of("employeeId", handling, "role", "MANDAL")));
        MvcResult busy = mvc.perform(userPost(hr, "/v1/labour/gangs", gang)).andReturn();
        assertThat(errorCode(busy)).as("the Sardar is in Okhla Gang 1").isEqualTo("LBR-422-GANG");
        assertThat(body(busy).toString()).contains("MEMBER_IN_ANOTHER_GANG");

        gang.put("gangName", "okhla gang 1");
        assertThat(body(mvc.perform(userPost(hr, "/v1/labour/gangs", gang)).andReturn()).toString())
                .contains("NAME_TAKEN");
    }

    /** SRS NWNP Master Search (SRS-2-40): worker code, name, gang and depot, within the scope. */
    @Test
    void SRS_2_40_membersAreSearchedByGangAndDepotWithinTheScope() throws Exception {
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        org.springframework.test.web.servlet.MvcResult found =
                mvc.perform(
                                userGet(
                                        hr,
                                        "/v1/labour/members?gang=gang&depot="
                                                + location("DP-DL01")
                                                + "&size=100"))
                        .andReturn();
        assertThat(found.getResponse().getStatus())
                .as(found.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode gang = body(found);
        assertThat(gang.findValuesAsString("labourCode"))
                .contains("L10701", "L10702", "L10002", "L10003");
        JsonNode byCode =
                body(
                        mvc.perform(userGet(hr, "/v1/labour/members?gang=DP-DL01-G002&size=100"))
                                .andReturn());
        assertThat(byCode.findValuesAsString("labourCode"))
                .contains("L10701", "L10702", "L10002", "L10003")
                .doesNotContain("L20701", "L30701");
        long okhla = location("DP-DL01");
        JsonNode depot =
                body(
                        mvc.perform(
                                        userGet(
                                                hr,
                                                "/v1/labour/members?depot="
                                                        + okhla
                                                        + "&gang=DP-DL01-G002&text=L10701&size=100"))
                                .andReturn());
        assertThat(depot.findValuesAsString("labourCode")).containsExactly("L10701");
        assertThat(
                        body(mvc.perform(userGet(hr, "/v1/labour/members?gang=NOSUCHGANG"))
                                        .andReturn())
                                .get("total")
                                .asLong())
                .isZero();
        // Another office's depot never widens the scope.
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(
                        body(mvc.perform(userGet(kolkata, "/v1/labour/members?depot=" + okhla))
                                        .andReturn())
                                .get("total")
                                .asLong())
                .isZero();
        assertThat(
                        mvc.perform(userGet(hr, "/v1/labour/members?depot=0"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }
}
