package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 15 (plan docs/plan/15-mis-reporting.md; SRS-18-8, SRS-18-10, SRS-18-18, SRS-18-23,
 * SRS-18-24, SRS-18-28): the catalogue with each entry produced on O-04, on its own screen, or
 * Blocked with the reason; the Component-wise Summary of a locked month from committed results,
 * reconciled to the posted payroll, provisional before the lock and halted on a mismatch, each
 * export logged; an M-12 version proposed by the Report Administrator and approved by the Rule
 * Approver ends the version before. Months far ahead of today: no other IT computes them.
 */
class ReportIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private long member(int offset) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                        + " 'ACTIVE' ORDER BY emp_num OFFSET ? ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"),
                offset);
    }

    /** A DH001 cycle of the month with one ACTUAL result: Basic as the earning, one deduction. */
    private void result(
            YearMonth month, String status, long employee, String gross, String deduction)
            throws Exception {
        result(month, status, employee, gross, deduction, "ACTUAL", "CURRENT");
    }

    /** As above, of the given run type and result status (SRS-18-5: only committed ones count). */
    private void result(
            YearMonth month,
            String status,
            long employee,
            String gross,
            String deduction,
            String runType,
            String resultStatus)
            throws Exception {
        String yymm = yymm(month);
        long unit = locationId("DH001");
        String find =
                "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?";
        List<Long> cycles = jdbc.queryForList(find, Long.class, yymm, unit);
        if (cycles.isEmpty()) {
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                            + " fin_year, cycle_status, trigger_type, initiated_by) VALUES (?, ?,"
                            + " 'DO', '2026-27', ?, 'ON_DEMAND', 'it.fixture')",
                    yymm,
                    unit,
                    status);
            cycles = jdbc.queryForList(find, Long.class, yymm, unit);
        }
        long cycle = cycles.getFirst();
        Date first = Date.valueOf(month.atDay(1));
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, result_status) VALUES (?, ?, ?, 'STAFF', ?,"
                        + " ?, ?, ?, ?, ?, ?)",
                cycle,
                unit,
                employee,
                yymm,
                first,
                runType,
                new BigDecimal(gross),
                new BigDecimal(deduction),
                new BigDecimal(gross).subtract(new BigDecimal(deduction)),
                resultStatus);
        long result =
                jdbc.queryForObject(
                        "SELECT MAX(payroll_result_id) FROM payroll_result WHERE employee_id = ?"
                                + " AND payroll_yymm = ?",
                        Long.class,
                        employee,
                        yymm);
        component(
                result,
                cycle,
                unit,
                employee,
                yymm,
                first,
                "component_type = 'EARNING' AND legacy_pay_code = 101",
                gross,
                runType);
        component(
                result,
                cycle,
                unit,
                employee,
                yymm,
                first,
                "component_type = 'DEDUCTION'",
                deduction,
                runType);
    }

    private void component(
            long result,
            long cycle,
            long unit,
            long employee,
            String yymm,
            Date first,
            String which,
            String amount,
            String runType) {
        jdbc.update(
                "INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id,"
                        + " org_unit_id, employee_id, component_id, amount, run_type, payroll_yymm,"
                        + " period_date) SELECT ?, ?, ?, ?, MIN(component_id), ?, ?, ?, ? FROM"
                        + " pay_component WHERE "
                        + which,
                result,
                cycle,
                unit,
                employee,
                new BigDecimal(amount),
                runType,
                yymm,
                first);
    }

    /**
     * The catalogue entries of SRS-18-3 (audit and cancelled-entry reports), SRS-18-4 (Last Pay
     * Certificate and the invoice output set), SRS-18-13 (column-level labour reports) and
     * SRS-18-19 (remittances through the agency mapping) are catalogued (V051_2, V066_1, V068_1)
     * and Blocked with the reason they wait for - never produced on an assumed layout; SRS-18-11:
     * the report output store of the builder is written by the one report produced here.
     */
    @Test
    void SRS_18_3_SRS_18_4_SRS_18_13_SRS_18_19_eachCataloguedEntryWaitsWithItsReason()
            throws Exception {
        Map<String, JsonNode> entries = new java.util.HashMap<>();
        all(maker(), "/v1/reports/catalogue")
                .forEach(e -> entries.put(e.path("key").asString(), e));
        for (String key :
                java.util.List.of(
                        "CANCELLED_ENTRY_HISTORY",
                        "PAY_CODE_WISE_ENTRIES",
                        "INSTALMENT_RECOVERY_LOGIC",
                        "LAST_PAY_CERTIFICATE",
                        "INCENTIVE_INVOICE",
                        "REMITTANCE_INVOICE",
                        "REMITTANCE_REPORT",
                        "LABOUR_WAGE_DETAIL")) {
            assertThat(entries).as(key).containsKey(key);
            JsonNode e = entries.get(key);
            assertThat(e.path("status").asString()).as(key).isIn("BLOCKED", "PRODUCED_ELSEWHERE");
            if ("BLOCKED".equals(e.path("status").asString())) {
                assertThat(e.path("blockedReason").asString()).as(key).isNotBlank();
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.columns WHERE"
                                        + " LOWER(table_name) = 'report_output'",
                                Integer.class))
                .as("SRS-18-11: the report output store exists")
                .isPositive();
    }

    /**
     * O-04 (SRS l.3471, l.3501): the screen's default month is the latest month locked in the
     * session's offices; another office's lock is not read; a Payroll Maker without O-04 VIEW is
     * refused (an Establishment / HR desk).
     */
    @Test
    void SRS_l3501_theDefaultPeriodIsTheLatestLockedMonthInScope() throws Exception {
        Session maker = maker();
        String expected =
                jdbc.queryForObject(
                        "SELECT MAX(i.payroll_yymm) FROM payroll_cycle_instance i JOIN org_location"
                                + " l ON l.location_id = i.org_unit_id WHERE i.cycle_status ="
                                + " 'LOCKED' AND l.location_code = 'DH001'",
                        String.class);
        JsonNode latest =
                body(
                        mvc.perform(userGet(maker, "/v1/reports/component-summary/latest-locked"))
                                .andReturn());
        if (expected == null) {
            assertThat(latest.path("yymm").isNull()).isTrue();
        } else {
            assertThat(latest.path("yymm").asString()).isEqualTo(expected);
        }
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        assertThat(
                        // OD-125 (Wireframes v6.1 role access, V078_4): the MIS summary is drawn
                        // for Establishment / HR (read only); WF-071 for FCI.
                        mvc.perform(userGet(hr, "/v1/reports/component-summary/latest-locked"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    @Test
    void SRS_18_24_theCatalogueShowsWhereEachEntryIsProducedOrWhyItIsBlocked() throws Exception {
        Session maker = maker();
        // More than one page since the Annexure A.11 entries (US-GAP-11): every page is read.
        Map<String, JsonNode> entries = new java.util.HashMap<>();
        all(maker, "/v1/reports/catalogue").forEach(e -> entries.put(e.path("key").asString(), e));
        assertThat(entries.get("COMPONENT_WISE_SUMMARY").path("status").asString())
                .isEqualTo("AVAILABLE");
        assertThat(entries.get("NPS_DEDUCTION_REPORT").path("status").asString())
                .isEqualTo("PRODUCED_ELSEWHERE");
        assertThat(entries.get("NPS_DEDUCTION_REPORT").path("producingScreen").asString())
                .isEqualTo("I-14");
        // SRS l.2503 (US-GAP-191): the CPF / PF Contribution Statement follows the current
        // system's layout, not supplied: Blocked, never generated on an assumed layout.
        assertThat(entries.get("CPF_CONTRIBUTION_STATEMENT").path("status").asString())
                .isEqualTo("BLOCKED");
        assertThat(entries.get("CPF_CONTRIBUTION_STATEMENT").path("blockedReason").asString())
                .contains("SRS-18-16");
        JsonNode scr = entries.get("SALARY_CONTROL_REGISTER");
        assertThat(scr.path("status").asString()).isEqualTo("BLOCKED");
        assertThat(scr.path("blockedReason").asString()).contains("SRS-18-16");
        // SRS-18-25 (US-GAP-11): the A.11 entries, on their screen or Blocked with the reason.
        assertThat(entries.get("PENALTY_REPORT").path("status").asString())
                .isEqualTo("PRODUCED_ELSEWHERE");
        assertThat(entries.get("PENALTY_REPORT").path("producingScreen").asString())
                .isEqualTo("I-06");
        assertThat(entries.get("HRMS_FALLBACK_RECONCILIATION").path("status").asString())
                .isEqualTo("BLOCKED");
        assertThat(entries.get("NO_DUE_CERTIFICATE").path("blockedReason").asString())
                .contains("OQ-12");
        // US-GAP-84: a labour entry names what it waits for, never a build module.
        assertThat(entries.get("LABOUR_WAGE_DETAIL").path("blockedReason").asString())
                .contains("labour wage computation", "OQ-12");
        assertThat(entries.get("ATTENDANCE_SUMMARY_DPS").path("blockedReason").asString())
                .isEqualTo("Layout not supplied: current-system layout (SRS-18-16, OQ-12)");
        assertThat(entries.values())
                .allSatisfy(
                        e ->
                                assertThat(e.path("blockedReason").asString())
                                        .as(e.path("key").asString())
                                        .doesNotContainIgnoringCase("module"));
        // The family filter returns that family's entries only, and all of them.
        JsonNode operational = all(maker, "/v1/reports/catalogue?family=Operational");
        assertThat(operational).isNotEmpty();
        assertThat(operational.findValuesAsString("family")).containsOnly("Operational");
        assertThat(operational.size())
                .isEqualTo(
                        (int)
                                entries.values().stream()
                                        .filter(
                                                e ->
                                                        "Operational"
                                                                .equals(
                                                                        e.path("family")
                                                                                .asString()))
                                        .count());
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        assertThat(
                        mvc.perform(userGet(member, "/v1/reports/catalogue"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }

    @Test
    void SRS_18_1_SRS_18_28_theSummaryOfALockedMonthReconcilesAndEveryExportIsLogged()
            throws Exception {
        YearMonth month = YearMonth.now(IST).plusYears(15);
        result(month, "LOCKED", member(3), "1000", "100");
        Session maker = maker();
        JsonNode s =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/reports/component-summary?yymm="
                                                        + yymm(month)
                                                        + "&level=UNIT"))
                                .andReturn());
        assertThat(s.path("provisional").asBoolean()).isFalse();
        assertThat(s.path("controlTotalsMatch").asBoolean()).isTrue();
        assertThat(s.path("earnings").decimalValue()).isEqualByComparingTo("1000");
        assertThat(s.path("deductions").decimalValue()).isEqualByComparingTo("100");
        assertThat(s.path("lines").toString()).contains("DH001");
        // SC-15-0065: the scope is the user's offices; Chennai's maker sees no Delhi line.
        JsonNode other =
                body(
                        mvc.perform(
                                        userGet(
                                                login(
                                                        "maker.chennai@fci-demo.test",
                                                        "PAYROLL_MAKER",
                                                        "CH001"),
                                                "/v1/reports/component-summary?yymm="
                                                        + yymm(month)
                                                        + "&level=UNIT"))
                                .andReturn());
        assertThat(other.path("lines").toString()).doesNotContain("DH001");
        int logged = jdbc.queryForObject("SELECT COUNT(*) FROM report_export_log", Integer.class);
        MvcResult file =
                download(
                        maker,
                        "/v1/reports/component-summary/export?yymm="
                                + yymm(month)
                                + "&level=ZONE&format=CSV");
        assertThat(file.getResponse().getStatus()).isEqualTo(200);
        assertThat(file.getResponse().getContentAsString()).contains("FINAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_export_log", Integer.class))
                .as("SRS-18-18: the export is logged")
                .isEqualTo(logged + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_provisional FROM report_output WHERE report_key ="
                                        + " 'COMPONENT_WISE_SUMMARY' AND payroll_yymm = ? ORDER BY"
                                        + " report_output_id DESC FETCH FIRST 1 ROWS ONLY",
                                String.class,
                                yymm(month)))
                .isEqualTo("N");
    }

    /**
     * SRS-18-5: the summary reads committed results only: a simulation's rows and a superseded or
     * quarantined result of the month are never counted, and the control totals still match.
     */
    @Test
    void SRS_18_5_aSimulationOrANonCurrentResultIsLeftOutOfTheSummary() throws Exception {
        YearMonth month = YearMonth.now(IST).plusYears(15).plusMonths(5);
        result(month, "LOCKED", member(3), "1000", "100");
        result(month, "LOCKED", member(5), "7000", "700", "SIMULATION", "CURRENT");
        result(month, "LOCKED", member(6), "9000", "900", "ACTUAL", "SUPERSEDED");
        result(month, "LOCKED", member(7), "3000", "300", "ACTUAL", "QUARANTINED");
        JsonNode s =
                body(
                        mvc.perform(
                                        userGet(
                                                maker(),
                                                "/v1/reports/component-summary?yymm="
                                                        + yymm(month)
                                                        + "&level=UNIT"))
                                .andReturn());
        assertThat(s.path("earnings").decimalValue()).isEqualByComparingTo("1000");
        assertThat(s.path("deductions").decimalValue()).isEqualByComparingTo("100");
        assertThat(s.path("controlTotalsMatch").asBoolean()).isTrue();
    }

    // SC-15-0061: a report before the lock is provisional and watermarked.
    @Test
    void SRS_18_23_anUnlockedMonthIsProvisionalAndAMismatchHaltsTheRelease() throws Exception {
        YearMonth open = YearMonth.now(IST).plusYears(15).plusMonths(1);
        result(open, "INITIATED", member(4), "500", "50");
        Session maker = maker();
        JsonNode s =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/reports/component-summary?yymm="
                                                        + yymm(open)
                                                        + "&level=ALL"))
                                .andReturn());
        assertThat(s.path("provisional").asBoolean()).isTrue();
        // A posted result whose components do not add up to its gross: the release is halted.
        jdbc.update(
                "UPDATE payroll_result SET gross_earnings = gross_earnings + 1 WHERE payroll_yymm ="
                        + " ? AND org_unit_id = ?",
                yymm(open),
                locationId("DH001"));
        String export =
                "/v1/reports/component-summary/export?yymm="
                        + yymm(open)
                        + "&level=UNIT&format=CSV";
        // SRS-18-9 M-12 Period-Lock (US-GAP-36): the summary's version needs the month locked
        // (FRS Annexure A.4 "Lock: Yes"), so the unlocked month is Blocked, not exported.
        MvcResult blocked = mvc.perform(userGet(maker, export)).andReturn();
        assertThat(body(blocked).path("errorCode").asString()).isEqualTo("REP-409-BLOCKED");
        assertThat(body(blocked).toString()).contains("locked");
        jdbc.update(
                "UPDATE payroll_cycle_instance SET cycle_status = 'LOCKED' WHERE payroll_yymm = ?"
                        + " AND org_unit_id = ?",
                yymm(open),
                locationId("DH001"));
        MvcResult halted = mvc.perform(userGet(maker, export)).andReturn();
        assertThat(body(halted).path("errorCode").asString()).isEqualTo("REP-409-CONTROL-TOTALS");
        assertThat(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/reports/component-summary?yymm=202613&level=UNIT"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }

    // SC-15-0035: a new effective version supersedes the earlier one, which is kept.
    /**
     * BR-MIS-003, SRS-18-10 (US-15-01 AC2): the Report Administrator never approves their own M-12
     * version. In the proposing role the decision is not even offered; given the approving role
     * too, the proposer is refused on identity (separation of duties) and the version stays pending
     * until another Rule Approver decides it.
     */
    @Test
    void BR_MIS_003_theReportAdministratorNeverApprovesTheirOwnVersion() throws Exception {
        Session admin = login("report.admin@fci-demo.test", "REPORT_ADMINISTRATOR", "HQ001");
        LocalDate from = LocalDate.now(IST).plusDays(400 + (int) (System.nanoTime() % 300));
        MvcResult proposed =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/report-definitions/MEDICAL_REGISTER/versions",
                                        Map.of(
                                                "storeMode",
                                                "REGENERATE",
                                                "blocked",
                                                true,
                                                "blockedReason",
                                                "IT: layout still awaited from FCI",
                                                "periodLockRequired",
                                                false,
                                                "effectiveFrom",
                                                from.toString())))
                        .andReturn();
        assertThat(proposed.getResponse().getStatus())
                .as(proposed.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(proposed).path("id").asLong();
        MvcResult own =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/approvals/" + approval + "/decision",
                                        Map.of("decision", "APPROVE")))
                        .andReturn();
        assertThat(own.getResponse().getStatus()).isEqualTo(403);
        LocalDate yesterday = LocalDate.now(IST).minusDays(1);
        jdbc.update(
                "INSERT INTO app_user_access_group (user_id, access_group_id, effective_from,"
                        + " assigned_by, approval_id) SELECT u.user_id, g.access_group_id, ?,"
                        + " 'it.fixture', g.approval_id FROM app_user u, access_group g WHERE"
                        + " u.login_id = 'report.admin@fci-demo.test' AND g.group_code ="
                        + " 'AG-RULEAPR-HQ'",
                Date.valueOf(yesterday));
        try {
            Session both = login("report.admin@fci-demo.test", "RULE_APPROVER", "HQ001");
            MvcResult refused =
                    mvc.perform(
                                    userPost(
                                            both,
                                            "/v1/approvals/" + approval + "/decision",
                                            Map.of("decision", "APPROVE")))
                            .andReturn();
            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(refused).path("errorCode").asString())
                    .as("refused on identity, not on the role")
                    .isEqualTo("WF-403-SOD");
        } finally {
            jdbc.update(
                    "UPDATE app_user_access_group SET revoked_at = CURRENT_TIMESTAMP, revoke_cause"
                        + " = 'ADMIN', revoke_reason = 'IT fixture ended' WHERE assigned_by ="
                        + " 'it.fixture' AND user_id = (SELECT user_id FROM app_user WHERE login_id"
                        + " = 'report.admin@fci-demo.test')");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .as("still waiting for another Rule Approver")
                .startsWith("PENDING");
        assertThat(
                        decide(
                                login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
    }

    // SC-15-0024: UC Maintain Report Definition Catalogue (M-12), main flow.
    @Test
    void SRS_18_10_aNewVersionIsProposedApprovedAndEndsTheVersionBefore() throws Exception {
        Session admin = login("report.admin@fci-demo.test", "REPORT_ADMINISTRATOR", "HQ001");
        LocalDate from = LocalDate.now(IST).plusDays(40 + (int) (System.nanoTime() % 300));
        Map<String, Object> body =
                Map.of(
                        "storeMode",
                        "REGENERATE",
                        "blocked",
                        true,
                        "blockedReason",
                        "IT: layout still awaited from FCI",
                        "periodLockRequired",
                        false,
                        "effectiveFrom",
                        from.toString());
        MvcResult proposed =
                mvc.perform(userPost(admin, "/v1/report-definitions/LEAVE_RECOVERY/versions", body))
                        .andReturn();
        assertThat(proposed.getResponse().getStatus())
                .as(proposed.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(proposed).path("id").asLong();
        assertThat(
                        body(mvc.perform(
                                                userPost(
                                                        admin,
                                                        "/v1/report-definitions/LEAVE_RECOVERY/versions",
                                                        body))
                                        .andReturn())
                                .path("errorCode")
                                .asString())
                .as("one proposal at a time")
                .isEqualTo("REP-422-VERSION");
        assertThat(
                        decide(
                                login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001"),
                                approval,
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        JsonNode versions =
                body(
                        mvc.perform(
                                        userGet(
                                                admin,
                                                "/v1/report-definitions/LEAVE_RECOVERY/versions"))
                                .andReturn());
        assertThat(versions.get(0).path("approvalState").asString()).isEqualTo("APPROVED");
        assertThat(versions.get(0).path("effectiveFrom").asString()).isEqualTo(from.toString());
        assertThat(versions.get(1).path("effectiveTo").asString())
                .isEqualTo(from.minusDays(1).toString());
        assertThat(
                        mvc.perform(
                                        userPost(
                                                maker(),
                                                "/v1/report-definitions/LEAVE_RECOVERY/versions",
                                                body))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("a Payroll Maker never proposes")
                .isEqualTo(403);
    }

    /**
     * SRS-18-12 (a), (c), (d), SRS-G-40 (US-GAP-37): the O-04 parameters narrow the summary
     * (payroll type, member status, an office of the user's scope); the export carries the report
     * header and the sub-totals, in Hindi when asked; an office outside the scope is refused.
     */
    @Test
    void SRS_18_12_theParametersNarrowTheSummaryAndTheExportFollowsTheReportStandard()
            throws Exception {
        YearMonth month = YearMonth.now(IST).plusYears(15).plusMonths(7);
        result(month, "LOCKED", member(3), "1000", "100");
        Session maker = maker();
        String base = "/v1/reports/component-summary?yymm=" + yymm(month) + "&level=UNIT";
        assertThat(
                        body(mvc.perform(userGet(maker, base + "&payrollType=STAFF")).andReturn())
                                .path("earnings")
                                .decimalValue())
                .isEqualByComparingTo("1000");
        assertThat(
                        body(mvc.perform(userGet(maker, base + "&payrollType=DPS_LABOUR"))
                                        .andReturn())
                                .path("lines"))
                .isEmpty();
        assertThat(
                        body(mvc.perform(userGet(maker, base + "&memberStatus=SEPARATED"))
                                        .andReturn())
                                .path("lines"))
                .as("the member is not separated")
                .isEmpty();
        assertThat(
                        body(mvc.perform(userGet(maker, base + "&memberStatus=CURRENT"))
                                        .andReturn())
                                .path("lines"))
                .isNotEmpty();
        assertThat(
                        body(mvc.perform(userGet(maker, base + "&officeId=" + locationId("DH001")))
                                        .andReturn())
                                .path("lines")
                                .toString())
                .contains("DH001");
        assertThat(
                        mvc.perform(userGet(maker, base + "&officeId=" + locationId("CH001")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("an office outside the Access Group scope")
                .isEqualTo(403);
        assertThat(
                        mvc.perform(userGet(maker, base + "&memberStatus=RETIRED"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        JsonNode options =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/reports/component-summary/options?yymm="
                                                        + yymm(month)))
                                .andReturn());
        assertThat(options.path("payrollTypes").toString()).contains("STAFF");

        String csv =
                csv(
                        maker,
                        "/v1/reports/component-summary/export?yymm="
                                + yymm(month)
                                + "&level=UNIT&payrollType=STAFF&format=CSV");
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).contains("Component-wise Summary");
        assertThat(lines[1])
                .contains(String.format("%02d-%04d", month.getMonthValue(), month.getYear()));
        assertThat(lines[2]).contains("Payroll type STAFF");
        assertThat(lines[3]).contains("IST");
        assertThat(lines[4]).contains("maker.delhi");
        assertThat(csv).contains("Sub-total Earnings").contains("TOTAL");
        String hindi =
                csv(
                        maker,
                        "/v1/reports/component-summary/export?yymm="
                                + yymm(month)
                                + "&level=UNIT&lang=hi&format=CSV");
        assertThat(hindi.split("\r\n")[0]).isEqualTo("\"घटक-वार सारांश\"");
    }

    /**
     * SRS l.5440 (UI audit S8): M-12 is viewed by the roles that hold M-12 - the Rule Approver
     * reads the definitions on /v1/report-definitions although it has no O-04, whose own catalogue
     * stays refused to it; a role with neither is refused both; a search term over its length is
     * refused.
     */
    @Test
    void SRS_18_9_theRuleApproverReadsTheDefinitionCatalogueUnderM12() throws Exception {
        Session approver = login("rule.approver@fci-demo.test", "RULE_APPROVER", "HQ001");
        mvc.perform(userGet(approver, "/v1/report-definitions?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mvc.perform(userGet(approver, "/v1/reports/catalogue?page=0&size=20"))
                .andExpect(status().isForbidden());
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        mvc.perform(userGet(member, "/v1/report-definitions")).andExpect(status().isForbidden());
        mvc.perform(userGet(approver, "/v1/report-definitions?text=" + "x".repeat(61)))
                .andExpect(status().isBadRequest());
    }

    /**
     * SRS-18-23 (US-GAP-271): the E-Seva report is available from the start of the month-end lock
     * (provisional until the lock) and the E-Seva Arrear report only after the lock - catalogued
     * so, and Blocked on the ECR file format FCI has not supplied (OQ-23): never produced before.
     */
    @Test
    void SRS_18_23_theESevaAndESevaArrearReportsAreFromTheLockAndBlockedOnTheEcrFormat()
            throws Exception {
        Map<String, JsonNode> entries = new java.util.HashMap<>();
        all(maker(), "/v1/reports/catalogue")
                .forEach(e -> entries.put(e.path("key").asString(), e));
        JsonNode eseva = entries.get("FCI_ESEVA_REPORT");
        JsonNode arrear = entries.get("FCI_ESEVA_ARREAR_REPORT");
        assertThat(eseva).isNotNull();
        assertThat(arrear).isNotNull();
        assertThat(eseva.path("lockRequirement").asString()).isEqualTo("FROM_LOCK_PROVISIONAL");
        assertThat(arrear.path("lockRequirement").asString()).isEqualTo("POST_LOCK");
        for (JsonNode e : List.of(eseva, arrear)) {
            assertThat(e.path("status").asString()).isEqualTo("BLOCKED");
            assertThat(e.path("blockedReason").asString()).contains("OQ-23");
            assertThat(e.path("producingScreen").asString()).isEqualTo("O-07");
        }
    }

    /**
     * SC-15-0062 (l.3486), SC-15-0057: an O-04 report of a locked month generated again for the
     * same closed period reproduces it - the same summary, the same lines and totals in the export
     * (only the run date-time of the header differs) - and each run is kept (report_output).
     * SC-15-0026 (l.3355): each run records the catalogue version in force for the period.
     */
    @Test
    void SC_15_0062_SC_15_0026_aLockedMonthsReportRegeneratedIsIdenticalOnTheVersionInForce()
            throws Exception {
        YearMonth month = YearMonth.now(IST).plusYears(16).plusMonths(2);
        result(month, "LOCKED", member(5), "2000", "150");
        Session maker = maker();
        String view = "/v1/reports/component-summary?yymm=" + yymm(month) + "&level=UNIT";
        String first =
                mvc.perform(userGet(maker, view)).andReturn().getResponse().getContentAsString();
        String second =
                mvc.perform(userGet(maker, view)).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        String export =
                "/v1/reports/component-summary/export?yymm="
                        + yymm(month)
                        + "&level=UNIT&format=CSV";
        String a = csv(maker, export);
        String b = csv(maker, export);
        assertThat(columns(a)).as("the lines and totals reproduce").isEqualTo(columns(b));
        assertThat(columns(a)).contains("FINAL").contains("TOTAL");
        Long expected =
                jdbc.queryForObject(
                        "SELECT v.report_version_id FROM report_definition_version v JOIN"
                                + " report_definition d ON d.report_def_id = v.report_def_id WHERE"
                                + " d.report_key = 'COMPONENT_WISE_SUMMARY' AND v.approval_state ="
                                + " 'APPROVED' AND v.is_blocked = 'N' AND v.effective_from <= ? AND"
                                + " (v.effective_to IS NULL OR v.effective_to >= ?) ORDER BY"
                                + " v.effective_from DESC FETCH FIRST 1 ROWS ONLY",
                        Long.class,
                        Date.valueOf(month.atDay(1)),
                        Date.valueOf(month.atDay(1)));
        List<Map<String, Object>> runs =
                jdbc.queryForList(
                        "SELECT report_version_id, is_provisional, parameter_hash FROM"
                                + " report_output WHERE report_key = 'COMPONENT_WISE_SUMMARY' AND"
                                + " payroll_yymm = ? ORDER BY report_output_id",
                        yymm(month));
        assertThat(runs).as("each run is kept: two views and two exports").hasSize(4);
        assertThat(runs)
                .allSatisfy(
                        r -> {
                            assertThat(((Number) r.get("REPORT_VERSION_ID")).longValue())
                                    .isEqualTo(expected);
                            assertThat(r.get("IS_PROVISIONAL")).isEqualTo("N");
                        });
        assertThat(runs.stream().map(r -> r.get("PARAMETER_HASH")).distinct().count())
                .as("the same parameters every time")
                .isOne();
    }

    /** The CSV from its column-title row on: the header above it carries the run time. */
    private static String columns(String csv) {
        int at = csv.indexOf("\r\n\"status\"");
        if (at < 0) {
            at = csv.indexOf("\r\nstatus");
        }
        return at < 0 ? csv : csv.substring(at);
    }
}
