package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Group g5 gap fixes for modules 20-23 (S\GROUP_WORK_g5.md; SRS-2-7, SRS-2-13, SRS-2-27, SRS-2-28,
 * SRS-2-30, SRS-2-47, SRS-INT-27; BR-LABM-010): view-only roles on M-02, a pending amendment that
 * changes nothing until approved, the maker who cannot approve their own change, an ended
 * membership or gang refused in every labour payroll, attached labour on the attached gang's slip
 * for the attached days only, and no labour data from HRMS. Fixture gangs and labour of its own at
 * Depot Okhla (codes LG5...), never a member linked to a demo user.
 */
class LabourGapsIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long okhla() {
        return id("SELECT location_id FROM org_location WHERE location_code = 'DP-DL01'");
    }

    /** A fixture handling gang of Depot Okhla of the payroll type, active a year back. */
    private long gang(String code, String payrollType, LocalDate today) {
        Integer there =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang WHERE gang_code = ?",
                        Integer.class,
                        code);
        if (there == null || there == 0) {
            jdbc.update(
                    "INSERT INTO labour_gang (gang_code, gang_name, depot_location_id,"
                        + " payroll_type, gang_type, gang_status, effective_from) VALUES (?, ?, ?,"
                        + " ?, 'HANDLING', 'ACTIVE', ?)",
                    code,
                    "Fixture " + code,
                    okhla(),
                    payrollType,
                    Date.valueOf(today.minusYears(1)));
        }
        return id("SELECT gang_id FROM labour_gang WHERE gang_code = ?", code);
    }

    /** A fixture labour of Depot Okhla of the payroll type (no membership). */
    private long labour(String code, String pan, String payrollType) {
        Integer there =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM employee WHERE emp_num = ?", Integer.class, code);
        if (there == null || there == 0) {
            jdbc.update(
                    "INSERT INTO employee (emp_num, first_name, last_name, birth_date, gender,"
                        + " comp_joining_date, employee_type, emp_lbr_flag, pan_number,"
                        + " designation_id, location_id, depot_location_id, provident_fund_type,"
                        + " parent_zone_id, employment_status, record_status, record_origin,"
                        + " mobile_number) SELECT ?, 'Fixture', 'Gfive', DATE '1990-01-15', 'M',"
                        + " DATE '2015-07-01', ?, 'Y', ?, d.designation_id, ?, ?, 'NONE',"
                        + " l.zone_id, 'IN_SERVICE', 'ACTIVE', 'ENTERED', '9000055001' FROM"
                        + " org_designation d JOIN org_location l ON l.location_id = ? WHERE"
                        + " d.designation_code = 'HL'",
                    code,
                    payrollType,
                    pan,
                    okhla(),
                    okhla(),
                    okhla());
            long employee = id("SELECT employee_id FROM employee WHERE emp_num = ?", code);
            jdbc.update(
                    "UPDATE employee SET bank_account_number = '0000095100', bank_branch_id ="
                            + " (SELECT branch_id FROM bank_branch WHERE ifsc_code = 'SBIN0DEMO01')"
                            + " WHERE employee_id = ?",
                    employee);
            jdbc.update(
                    "INSERT INTO labour_profile (employee_id, labour_number, payroll_type,"
                        + " depot_location_id, pf_applicable_flag, labour_status, effective_from)"
                        + " VALUES (?, ?, ?, ?, 'N', 'ACTIVE', DATE '2015-07-01')",
                    employee,
                    code,
                    payrollType,
                    okhla());
        }
        return id("SELECT employee_id FROM employee WHERE emp_num = ?", code);
    }

    private void member(long gang, long employee, LocalDate from, LocalDate to) {
        jdbc.update(
                "INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role,"
                        + " effective_from, effective_to) VALUES (?, ?, 'HANDLING', ?, ?)",
                gang,
                employee,
                Date.valueOf(from),
                to == null ? null : Date.valueOf(to));
    }

    private void mark(long employee, long gang, String engine, LocalDate day) {
        jdbc.update(
                "INSERT INTO labour_attendance (employee_id, gang_id, depot_location_id,"
                        + " attendance_date, attendance_code_id, attendance_code, engine_code,"
                        + " source_code, capture_mode) SELECT ?, ?, ?, ?, attendance_code_id, 'W',"
                        + " ?, 'ENT', 'MANUAL' FROM attendance_code_master WHERE engine_code = ?"
                        + " AND attendance_code = 'W'",
                employee,
                gang,
                okhla(),
                Date.valueOf(day),
                engine,
                engine);
    }

    private Map<String, Object> slip(LocalDate day, long... participants) {
        Map<String, Object> row = new HashMap<>();
        row.put(
                "operationId",
                id("SELECT operation_id FROM operation_master WHERE operation_code = 'W2P'"));
        row.put(
                "bagTypeId",
                id("SELECT bag_type_id FROM bag_type WHERE bag_type_code = 'KATTA50'"));
        row.put("bags", 100);
        row.put("stackHeight", 10);
        row.put("start", "09:00");
        row.put("end", "12:00");
        return Map.of(
                "date",
                day.toString(),
                "continueWork",
                false,
                "rows",
                List.of(row),
                "participants",
                java.util.Arrays.stream(participants).boxed().toList());
    }

    private MvcResult save(long gang, Map<String, Object> slip) throws Exception {
        return mvc.perform(userPost(ldh(), "/v1/labour/gangs/" + gang + "/work-slips", slip))
                .andReturn();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static String said(MvcResult r) throws Exception {
        return status(r) + " " + r.getResponse().getContentAsString();
    }

    private Session ldh() throws Exception {
        return login("ldh.depot@fci-demo.test", "LABOUR_DEALING_HAND", "DP-DL01");
    }

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private Map<String, Object> newMember(String first, String pan) {
        Map<String, Object> m = new HashMap<>();
        m.put("payrollType", "DEPT_LABOUR");
        m.put("firstName", first);
        m.put("lastName", "Demo");
        m.put("gender", "M");
        m.put("birthDate", "1985-06-15");
        m.put("joiningDate", "2010-07-01");
        m.put(
                "designationId",
                id("SELECT designation_id FROM org_designation WHERE designation_code = 'HL'"));
        m.put("depotLocationId", okhla());
        m.put("panNumber", pan);
        m.put("ifsc", "SBIN0DEMO01");
        m.put("bankAccount", "0000095001");
        m.put("mobile", "9000055002");
        m.put("pfApplicable", false);
        m.put("reason", "Appointment order 5g");
        return m;
    }

    /**
     * US-20-01 AC6 (FRS RBAC l.3402-3407): the Labour Dealing Hand and the Payroll Maker view the
     * Labour Master and never create or amend it; the LDH sees only the labour of its depots
     * (OD-91: its "own gangs" are the gangs of its depot scope).
     */
    @Test
    void SRS_2_30_theLabourDealingHandAndThePayrollMakerOnlyView() throws Exception {
        long x = labour("LG5001", "DMOPG5001A", "DEPT_LABOUR");
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        for (Session viewer : List.of(ldh(), maker)) {
            assertThat(
                            status(
                                    mvc.perform(
                                                    userPost(
                                                            viewer,
                                                            "/v1/labour/members",
                                                            newMember("Viewonly", "DMOPG5009Z")))
                                            .andReturn()))
                    .isEqualTo(403);
            assertThat(
                            status(
                                    mvc.perform(
                                                    userPost(
                                                            viewer,
                                                            "/v1/labour/members/"
                                                                    + x
                                                                    + "/amendments",
                                                            Map.of(
                                                                    "mobile",
                                                                    "9000055003",
                                                                    "reason",
                                                                    "Viewer")))
                                            .andReturn()))
                    .isEqualTo(403);
            MvcResult list =
                    mvc.perform(userGet(viewer, "/v1/labour/members?text=LG5001")).andReturn();
            assertThat(status(list)).as(said(list)).isEqualTo(200);
            assertThat(body(list).get("total").asLong())
                    .as("the labour is in the viewer's scope")
                    .isEqualTo(1);
        }
        MvcResult own = mvc.perform(userGet(ldh(), "/v1/labour/members?text=LG5001")).andReturn();
        assertThat(body(own).get("total").asLong()).as("Okhla is the LDH's depot").isEqualTo(1);
        // US-GAP-327 (FRS RBAC l.3407, OD-91): the LDH never sees a labour or a gang of another
        // depot (Depot Khanna's L10501, under another DO).
        MvcResult khannaLabour =
                mvc.perform(userGet(ldh(), "/v1/labour/members?text=L10501")).andReturn();
        assertThat(status(khannaLabour)).as(said(khannaLabour)).isEqualTo(200);
        assertThat(body(khannaLabour).get("total").asLong()).as("another depot's labour").isZero();
        long khanna = id("SELECT location_id FROM org_location WHERE location_code = 'DP-PB01'");
        MvcResult khannaGangs =
                mvc.perform(userGet(ldh(), "/v1/labour/gangs?depot=" + khanna)).andReturn();
        assertThat(
                        status(khannaGangs) == 200
                                ? body(khannaGangs).get("total").asLong()
                                : status(khannaGangs))
                .as("another depot's gangs: none listed, or refused")
                .isIn(0L, 403L, 404L);
        Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
        MvcResult other =
                mvc.perform(userGet(chennai, "/v1/labour/members?text=LG5001")).andReturn();
        assertThat(status(other)).as(said(other)).isEqualTo(200);
        assertThat(body(other).get("total").asLong())
                .as("an Okhla labour is outside the Chennai office")
                .isZero();
    }

    /**
     * SRS-2-30 "keep the old value in force until approval" (US-20-01 AC5) and BR-LABM-010: the
     * Establishment / HR maker who amends cannot approve; while the amendment waits the record is
     * unchanged; a rejection leaves it so.
     */
    @Test
    void BR_LABM_010_SRS_2_30_aPendingAmendmentChangesNothingAndItsMakerCannotApproveIt()
            throws Exception {
        long x = labour("LG5002", "DMOPG5002B", "DEPT_LABOUR");
        Session hr = hr();
        MvcResult sent =
                mvc.perform(
                                userPost(
                                        hr,
                                        "/v1/labour/members/" + x + "/amendments",
                                        Map.of("mobile", "9000055004", "reason", "New number")))
                        .andReturn();
        assertThat(status(sent)).as(said(sent)).isEqualTo(202);
        long approval = body(sent).get("id").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT mobile_number FROM employee WHERE employee_id = ?",
                                String.class,
                                x))
                .as("the old value stays in force")
                .isEqualTo("9000055001");
        assertThat(decide(hr, approval, "APPROVE", null, null))
                .as("the maker never decides their own change")
                .isEqualTo(403);
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(decide(checker, approval, "REJECT", "Not this number", null)).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT mobile_number FROM employee WHERE employee_id = ?",
                                String.class,
                                x))
                .isEqualTo("9000055001");
    }

    /**
     * SRS-2-13 (the validations apply alike to Departmental, DPS and NWNP labour) and SRS-2-28
     * (every entry is validated against the active labour master; keyed entries here, the Anna
     * Darpan and biometric exception list waits for module 23, PENDING LBR-20b-2): a labour whose
     * membership ended (a separation or transfer ends it, SRS l.4975) is refused on a later
     * work-slip in each payroll, while the last day it belonged is accepted.
     */
    @Test
    void SRS_2_13_SRS_2_28_anEndedMemberIsRefusedOnLaterSlipsInEveryLabourPayroll()
            throws Exception {
        YearMonth month = YearMonth.now(IST).minusMonths(3);
        LocalDate last = month.atDay(10);
        int n = 0;
        for (String type : List.of("DEPT_LABOUR", "DPS_LABOUR", "NWNP_LABOUR")) {
            n++;
            long g = gang("DP-DL01-G5E" + n, type, LocalDate.now(IST));
            long y = labour("LG501" + n, "DMOPG501" + n + "C", type);
            long z = labour("LG502" + n, "DMOPG502" + n + "D", type);
            member(g, y, month.atDay(1), last);
            member(g, z, month.atDay(1), null);
            if (!"NWNP_LABOUR".equals(type)) {
                mark(y, g, type, last);
                mark(z, g, type, last);
                mark(z, g, type, last.plusDays(1));
            }
            MvcResult later = save(g, slip(last.plusDays(1), y, z));
            assertThat(said(later)).as(type).contains("NOT_A_MEMBER");
            MvcResult onTheDay = save(g, slip(last, z));
            assertThat(status(onTheDay)).as(type + " " + said(onTheDay)).isEqualTo(201);
        }
    }

    /**
     * SRS-2-7 / FRS §8 Scenario 5 (US-GAP-326): an end-dated gang takes no work-slip after it ends.
     */
    @Test
    void SRS_2_7_anEndedGangTakesNoSlipAfterItsEndDate() throws Exception {
        YearMonth month = YearMonth.now(IST).minusMonths(3);
        long g = gang("DP-DL01-G5X", "NWNP_LABOUR", LocalDate.now(IST));
        long w = labour("LG5031", "DMOPG5031E", "NWNP_LABOUR");
        member(g, w, month.atDay(1), null);
        LocalDate end = month.atDay(15);
        jdbc.update(
                "UPDATE labour_gang SET gang_status = 'END_DATED', effective_to = ? WHERE gang_id ="
                        + " ?",
                Date.valueOf(end),
                g);
        assertThat(said(save(g, slip(end.plusDays(1), w)))).contains("GANG_NOT_ACTIVE");
        MvcResult joined =
                mvc.perform(
                                userPost(
                                        hr(),
                                        "/v1/labour/gangs/" + g + "/members",
                                        Map.of(
                                                "employeeId",
                                                labour("LG5032", "DMOPG5032F", "NWNP_LABOUR"),
                                                "from",
                                                LocalDate.now(IST).toString(),
                                                "reason",
                                                "Join")))
                        .andReturn();
        assertThat(said(joined)).contains("GANG_NOT_ACTIVE");
    }

    /**
     * SRS-2-47 / I-02 l.4632 (US-20-18 AC4): a labour attached to gang B for days d1 to d2 is on
     * B's work-slips for those days only and on its home gang A's before and after.
     */
    @Test
    void SRS_2_47_anAttachedLabourIsOnTheAttachedGangsSlipOnlyForTheAttachedDays()
            throws Exception {
        YearMonth month = YearMonth.now(IST).minusMonths(3);
        LocalDate d1 = month.atDay(20);
        LocalDate d2 = month.atDay(21);
        long a = gang("DP-DL01-G5A", "DEPT_LABOUR", LocalDate.now(IST));
        long b = gang("DP-DL01-G5B", "DEPT_LABOUR", LocalDate.now(IST));
        long x = labour("LG5041", "DMOPG5041G", "DEPT_LABOUR");
        member(a, x, month.atDay(1), d1.minusDays(1));
        member(b, x, d1, d2);
        member(a, x, d2.plusDays(1), null);
        for (LocalDate day : List.of(d1.minusDays(1), d1, d2, d2.plusDays(1))) {
            mark(x, day.isBefore(d1) || day.isAfter(d2) ? a : b, "DEPT_LABOUR", day);
        }
        assertThat(status(save(b, slip(d1, x)))).isEqualTo(201);
        assertThat(status(save(b, slip(d2, x)))).isEqualTo(201);
        assertThat(said(save(a, slip(d1, x)))).as("away from A").contains("NOT_A_MEMBER");
        assertThat(said(save(b, slip(d2.plusDays(1), x)))).as("back in A").contains("NOT_A_MEMBER");
        assertThat(said(save(b, slip(d1.minusDays(1), x)))).contains("NOT_A_MEMBER");
        assertThat(status(save(a, slip(d2.plusDays(1), x)))).isEqualTo(201);
    }

    /**
     * SRS-INT-27 / SRS-2-27 (US-23-27 AC1, SC-23-0006): no interface carries labour data from HRMS;
     * labour attendance, work-slips, leave and claims are captured in Payroll.
     */
    @Test
    void SRS_INT_27_SRS_2_27_noInterfaceCarriesLabourData() {
        Integer labourInterfaces =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interface_registry WHERE counterparty_system = 'HRMS'"
                                + " AND (UPPER(interface_code) LIKE '%LABOUR%' OR"
                                + " UPPER(COALESCE(target_module, ' ')) LIKE '%LABOUR%' OR"
                                + " UPPER(COALESCE(staging_object, ' ')) LIKE '%LBR%')",
                        Integer.class);
        assertThat(labourInterfaces).isZero();
        Integer hrms =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interface_registry WHERE counterparty_system ="
                                + " 'HRMS'",
                        Integer.class);
        assertThat(hrms).as("the HRMS interfaces are registered").isPositive();
    }

    /**
     * SRS-2-6 (between gangs, depots and Divisional Offices; SRS l.4937 "the destination gang or
     * Divisional Office"), OD-88: Establishment / HR of Delhi finds a receiving gang of the payroll
     * type at a Punjab depot; the lookup is refused to a role that cannot start a transfer, and it
     * offers only active handling gangs of that payroll type.
     */
    @Test
    void SRS_2_6_aTransferTargetMayBeAGangOfAnotherDivisionalOffice() throws Exception {
        long khanna = id("SELECT location_id FROM org_location WHERE location_code = 'DP-PB01'");
        Integer there =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang WHERE gang_code = 'DP-PB01-G5T'",
                        Integer.class);
        if (there == null || there == 0) {
            jdbc.update(
                    "INSERT INTO labour_gang (gang_code, gang_name, depot_location_id,"
                            + " payroll_type, gang_type, gang_status, effective_from) VALUES"
                            + " ('DP-PB01-G5T', 'Khanna Fixture G5T', ?, 'DEPT_LABOUR', 'HANDLING',"
                            + " 'ACTIVE', ?)",
                    khanna,
                    Date.valueOf(LocalDate.now(IST).minusYears(1)));
        }
        MvcResult found =
                mvc.perform(
                                userGet(
                                        hr(),
                                        "/v1/labour/transfer-targets?payrollType=DEPT_LABOUR&text=G5T"))
                        .andReturn();
        assertThat(status(found)).as(said(found)).isEqualTo(200);
        assertThat(found.getResponse().getContentAsString()).contains("DP-PB01-G5T");
        MvcResult nwnp =
                mvc.perform(
                                userGet(
                                        hr(),
                                        "/v1/labour/transfer-targets?payrollType=NWNP_LABOUR&text=G5T"))
                        .andReturn();
        assertThat(body(nwnp).get("total").asLong()).as("another payroll type").isZero();
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        maker,
                                                        "/v1/labour/transfer-targets?payrollType=DEPT_LABOUR"))
                                        .andReturn()))
                .isEqualTo(403);
        assertThat(status(mvc.perform(userGet(hr(), "/v1/labour/transfer-targets")).andReturn()))
                .as("the payroll type is required")
                .isEqualTo(400);
        // The members of the other office's gang stay in its scope.
        long gangId = id("SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-PB01-G5T'");
        assertThat(
                        status(
                                mvc.perform(
                                                userGet(
                                                        hr(),
                                                        "/v1/labour/gangs/" + gangId + "/members"))
                                        .andReturn()))
                .isIn(403, 404);
    }

    /**
     * SC-20-0134 / SRS-2-48 l.4403 (US-20-05; OD-90): a day saved on I-01 with fewer present than
     * the Sanctioned Strength raises the X-05 GANG_SHORTFALL to the Depot Manager (gang, absent
     * members, number short); the Gang Strength Report shows it; only the Depot Manager closes it
     * with a reason, and a day saved again with the gap filled closes it too.
     */
    @Test
    void SRS_2_48_aShortDayRaisesTheAlertTheReportShowsItAndTheDepotManagerClosesIt()
            throws Exception {
        YearMonth month = YearMonth.now(IST).minusMonths(3);
        LocalDate day = month.atDay(25);
        long g = gang("DP-DL01-G5S", "DEPT_LABOUR", LocalDate.now(IST));
        long a = labour("LG5051", "DMOPG5051H", "DEPT_LABOUR");
        long b = labour("LG5052", "DMOPG5052J", "DEPT_LABOUR");
        member(g, a, month.atDay(1), null);
        member(g, b, month.atDay(1), null);
        jdbc.update(
                "INSERT INTO labour_gang_strength (gang_id, sanctioned_strength, effective_from,"
                    + " changed_by) SELECT ?, 2, ?, 'it' FROM dual WHERE NOT EXISTS (SELECT 1 FROM"
                    + " labour_gang_strength WHERE gang_id = ?)",
                g,
                Date.valueOf(month.atDay(1)),
                g);
        MvcResult saved =
                mvc.perform(
                                userPost(
                                        ldh(),
                                        "/v1/labour/gangs/" + g + "/attendance-days",
                                        Map.of(
                                                "date",
                                                day.toString(),
                                                "marks",
                                                List.of(
                                                        Map.of("employeeId", a, "code", "W"),
                                                        Map.of("employeeId", b, "code", "A")))))
                        .andReturn();
        assertThat(status(saved)).as(said(saved)).isEqualTo(200);
        String key = "gang " + g + " " + day;
        assertThat(
                        jdbc.queryForObject(
                                "SELECT detail FROM payroll_exception WHERE exception_code ="
                                    + " 'GANG_SHORTFALL' AND field_name = ? AND exception_status ="
                                    + " 'OPEN'",
                                String.class,
                                key))
                .contains("present 1 of Sanctioned Strength 2, short 1")
                .contains("absent: LG5052");
        Session dm = login("dm.okhla@fci-demo.test", "DEPOT_MANAGER", "DP-DL01");
        MvcResult report =
                mvc.perform(
                                userGet(
                                        dm,
                                        "/v1/labour/gangs/"
                                                + g
                                                + "/strength-report?month="
                                                + month))
                        .andReturn();
        assertThat(status(report)).as(said(report)).isEqualTo(200);
        tools.jackson.databind.JsonNode first = body(report).get("days").get(0);
        assertThat(first.get("shortBy").asInt()).isEqualTo(1);
        assertThat(first.get("alertOpen").asBoolean()).isTrue();
        String close = "/v1/labour/gangs/" + g + "/shortfalls/" + day + "/closures";
        assertThat(status(mvc.perform(userPost(ldh(), close, Map.of("reason", "x"))).andReturn()))
                .as("only the Depot Manager closes it")
                .isEqualTo(403);
        MvcResult closed =
                mvc.perform(userPost(dm, close, Map.of("reason", "Rake late; pool labour sent")))
                        .andReturn();
        assertThat(status(closed)).as(said(closed)).isEqualTo(200);
        assertThat(said(mvc.perform(userPost(dm, close, Map.of("reason", "again"))).andReturn()))
                .contains("NO_OPEN_SHORTFALL");
        // Saved again short, then with the gap filled: the new alert closes by itself.
        mvc.perform(
                        userPost(
                                ldh(),
                                "/v1/labour/gangs/" + g + "/attendance-days",
                                Map.of(
                                        "date",
                                        day.plusDays(1).toString(),
                                        "marks",
                                        List.of(
                                                Map.of("employeeId", a, "code", "W"),
                                                Map.of("employeeId", b, "code", "A")))))
                .andReturn();
        MvcResult filled =
                mvc.perform(
                                userPost(
                                        ldh(),
                                        "/v1/labour/gangs/" + g + "/attendance-days",
                                        Map.of(
                                                "date",
                                                day.plusDays(1).toString(),
                                                "marks",
                                                List.of(
                                                        Map.of("employeeId", a, "code", "W"),
                                                        Map.of("employeeId", b, "code", "W")))))
                        .andReturn();
        assertThat(status(filled)).as(said(filled)).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_exception WHERE exception_code ="
                                    + " 'GANG_SHORTFALL' AND field_name = ? AND exception_status ="
                                    + " 'OPEN'",
                                Integer.class,
                                "gang " + g + " " + day.plusDays(1)))
                .isZero();
    }
}
