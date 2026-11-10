package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * I-23 Supplementary Payment (Post-Lock) end to end with the shipped configuration: statutory
 * deductions are not configured (decision D-5), so a pending salary is recorded but cannot be
 * submitted (SRS-G-33, SRS-G-34, BR-CYC-010; DDL {@code supplementary_payment}). The approval path
 * is {@link SupplementaryApprovalIT}. Every test pays 2026-07 (month-end locked) to its own Delhi
 * member, so no test's duplicate check sees another's request.
 */
class SupplementaryIT extends ApiTestSupport {

    static final String MAKER = "maker.delhi@fci-demo.test";
    static final String CHECKER = "ddo.delhi@fci-demo.test";
    static final String LOCKED = "202607";
    static final String UNLOCKED = "201904";

    /** A cycle of DH001's paying unit for {@code month} in {@code status}, created once. */
    static void cycle(
            org.springframework.jdbc.core.JdbcTemplate jdbc, String month, String status) {
        Long unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DH001'",
                        Long.class);
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                                + " org_unit_id = ?",
                        Integer.class,
                        month,
                        unit);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id,"
                            + " org_unit_level, fin_year, cycle_status, trigger_type, initiated_by)"
                            + " VALUES (?, ?, 'DO', '2026-27', ?, 'ON_DEMAND', 'it.fixture')",
                    month,
                    unit,
                    status);
        }
    }

    private Session maker() throws Exception {
        return login(MAKER, "PAYROLL_MAKER", "DH001");
    }

    static MockMultipartFile order() {
        return new MockMultipartFile(
                "file",
                "approval-note.pdf",
                "application/pdf",
                "%PDF-1.7 approval note".getBytes(StandardCharsets.US_ASCII));
    }

    ResultActions capture(Session s, String eis, String type, String period) throws Exception {
        return capture(s, eis, type, period, "2026-08-10", null);
    }

    ResultActions capture(
            Session s, String eis, String type, String period, String applied, String sanctioned)
            throws Exception {
        var request =
                multipart("/v1/supplementary")
                        .file(order())
                        .param("eis", eis)
                        .param("paymentType", type)
                        .param("forPeriodYymm", period)
                        .param("applicationDate", applied)
                        .param("sanctionOrderRef", "DH/SUP/" + eis)
                        .param("documentType", "SANCTION_ORDER")
                        .param("reference", "DH/SUP/" + eis)
                        .param("orderDate", "2026-08-12")
                        .param("issuingAuthority", "AGM (Pay) Delhi");
        if (sanctioned != null) {
            request.param("sanctionDate", sanctioned);
        }
        return mvc.perform(
                userRequest(s, request).header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private JsonNode get(Session s, long id) throws Exception {
        return body(
                mvc.perform(userGet(s, "/v1/supplementary/" + id))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    @Test
    void SRS_G_33_aPendingSalaryOfALockedMonthIsRecordedWithItsOrder() throws Exception {
        cycle(jdbc, LOCKED, "LOCKED");
        // A CPF member with a CPF code: 106004 has none and is refused since module 16 phase 16e-2.
        Session maker = maker();
        JsonNode p =
                body(
                        capture(maker, "105220", "SALARY_WAGE", LOCKED)
                                .andExpect(status().isCreated())
                                .andReturn());
        long id = p.get("id").asLong();
        assertThat(p.get("reference").asString()).matches("SUP-\\d{8}");
        assertThat(p.get("status").asString()).isEqualTo("DRAFT");
        assertThat(p.get("duplicateCheck").asString()).isEqualTo("PASSED");
        assertThat(p.get("amount").decimalValue()).isPositive(); // computed, never typed
        assertThat(p.get("invoiceStatus").asString()).isEqualTo("NOT_RAISED");

        // The order was stored before the row and adopted by it (chk_supp_locked_cycle).
        long order = p.get("orderAttachmentId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT entity_key FROM document_attachment WHERE attachment_id ="
                                        + " ?",
                                String.class,
                                order))
                .isEqualTo(String.valueOf(id));
        mvc.perform(userGet(maker, "/v1/supplementary/" + id + "/files/" + order))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment")));

        // D-5: the statutory deductions are not configured, so it cannot go to the Checker.
        mvc.perform(userPost(maker, "/v1/supplementary/" + id + "/submit", Map.of()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SUP-503-STATUTORY"));
        assertThat(get(maker, id).get("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    void SRS_G_34_theDrawnFilterListsTheRequestsOfOnePaymentTypeOnly() throws Exception {
        // I-23 Filter (Wireframes v6.1, OD-121): a payment type of SUPPLEMENTARY_PAYMENT_TYPE
        // narrows the list.
        Session maker = maker();
        JsonNode all = body(mvc.perform(userGet(maker, "/v1/supplementary?size=100")).andReturn());
        JsonNode salary =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/supplementary?paymentType=SALARY_WAGE&size=100"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(salary.get("items").findValuesAsString("paymentType"))
                .allMatch("SALARY_WAGE"::equals);
        long salaries =
                java.util.stream.StreamSupport.stream(all.get("items").spliterator(), false)
                        .filter(i -> "SALARY_WAGE".equals(i.get("paymentType").asString()))
                        .count();
        assertThat(salary.get("total").asLong()).isEqualTo(salaries);
        JsonNode recovery =
                body(
                        mvc.perform(
                                        userGet(
                                                maker,
                                                "/v1/supplementary?paymentType=RECOVERY&size=100"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(recovery.get("items").findValuesAsString("paymentType"))
                .allMatch("RECOVERY"::equals);
        // A type outside the pattern is refused before any query.
        mvc.perform(userGet(maker, "/v1/supplementary?paymentType=salary'--"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void BR_CYC_010_anUnlockedPeriodBelongsInItsRegularRun() throws Exception {
        capture(maker(), "106118", "SALARY_WAGE", "202605")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SUP-409-NOT-LOCKED"));
    }

    @Test
    void trg_supp_period_locked_theDatabaseRefusesAnUnlockedCycleToo() {
        // A month no suite initiates: the cycle fixtures of the neighbouring months (Q-05-2) lock
        // the months before a suite's own, so a fixed recent month may already be LOCKED.
        cycle(jdbc, UNLOCKED, "VERIFIED");
        Long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = (SELECT location_id FROM org_location"
                                + " WHERE location_code = 'DH001')",
                        Long.class,
                        UNLOCKED);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO supplementary_payment (employee_id,"
                                            + " cycle_instance_id, for_period_yymm, payment_type,"
                                            + " amount, application_date, order_attachment_id)"
                                            + " SELECT employee_id, ?, '201904', 'OTA', 100, DATE"
                                            + " '2026-08-01', 1 FROM employee WHERE emp_num ="
                                            + " '106118'",
                                        cycle))
                .hasMessageContaining("FCIPAY-SUP-002");
    }

    @Test
    void trg_supp_component_excluded_anExcludedPayCodeIsRefusedByTheDatabase() {
        cycle(jdbc, LOCKED, "LOCKED");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO supplementary_payment (employee_id,"
                                            + " cycle_instance_id, for_period_yymm, payment_type,"
                                            + " component_id, amount, application_date,"
                                            + " order_attachment_id) SELECT e.employee_id,"
                                            + " c.cycle_instance_id, '202607', 'RECOVERY', (SELECT"
                                            + " component_id FROM pay_component WHERE"
                                            + " legacy_pay_code = 504), -100, DATE '2026-08-01', 1"
                                            + " FROM employee e JOIN payroll_cycle_instance c ON"
                                            + " c.payroll_yymm = '202607' AND c.org_unit_id ="
                                            + " e.location_id WHERE e.emp_num = '106118'"))
                .hasMessageContaining("FCIPAY-SUP-001");
    }

    @Test
    void SRS_G_33_otherTypesAreRaisedByTheirOriginatingTransaction() throws Exception {
        cycle(jdbc, LOCKED, "LOCKED");
        capture(maker(), "106118", "OTA", LOCKED)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("SUP-422-SOURCE"));
    }

    /**
     * SRS-8-11 / BR-ARR-HRA-002: I-23 is not a manual path for an HRA arrear either: a salary
     * arrear is not captured on the screen, and the capture takes no pay code or amount (a pending
     * salary's amount is computed).
     */
    @Test
    void SRS_8_11_anHraArrearIsNeverKeyedOnI23() throws Exception {
        cycle(jdbc, LOCKED, "LOCKED");
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM supplementary_payment", Long.class);
        mvc.perform(
                        userRequest(
                                        maker(),
                                        multipart("/v1/supplementary")
                                                .file(order())
                                                .param("eis", "106118")
                                                .param("paymentType", "SALARY_ARREAR")
                                                .param("forPeriodYymm", LOCKED)
                                                .param("payCode", "121")
                                                .param("amount", "1500")
                                                .param("applicationDate", "2026-08-10")
                                                .param("documentType", "SANCTION_ORDER")
                                                .param("reference", "DH/SUP/HRA")
                                                .param("orderDate", "2026-08-12")
                                                .param("issuingAuthority", "AGM (Pay) Delhi"))
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("SUP-422-SOURCE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplementary_payment", Long.class))
                .isEqualTo(before);
    }

    @Test
    void SRS_G_34_aSecondRequestForTheSameSalaryIsBlocked() throws Exception {
        cycle(jdbc, LOCKED, "LOCKED");
        Session maker = maker();
        JsonNode first =
                body(
                        capture(maker, "106620", "SALARY_WAGE", LOCKED)
                                .andExpect(status().isCreated())
                                .andReturn());
        JsonNode second =
                body(
                        capture(maker, "106620", "SALARY_WAGE", LOCKED)
                                .andExpect(status().isCreated())
                                .andReturn());
        assertThat(second.get("status").asString()).isEqualTo("BLOCKED");
        assertThat(second.get("duplicateOf").asString())
                .isEqualTo(first.get("reference").asString());
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/supplementary/" + second.get("id").asLong() + "/submit",
                                Map.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SUP-409-DUPLICATE"));

        JsonNode tiles =
                body(
                        mvc.perform(userGet(maker, "/v1/supplementary/tiles"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(tiles.get("blockedDuplicates").asLong()).isPositive();
        assertThat(tiles.get("open").asLong()).isGreaterThanOrEqualTo(2);

        // Cancelling the first frees the period: a new request passes.
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/supplementary/" + first.get("id").asLong() + "/cancel",
                                Map.of("reason", "Raised twice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/supplementary/" + second.get("id").asLong() + "/cancel",
                                Map.of("reason", "Duplicate")))
                .andExpect(status().isOk());
        JsonNode third =
                body(
                        capture(maker, "106620", "SALARY_WAGE", LOCKED)
                                .andExpect(status().isCreated())
                                .andReturn());
        assertThat(third.get("duplicateCheck").asString()).isEqualTo("PASSED");
    }

    @Test
    void BR_CYC_010_aSalaryPaidByTheRegularRunOrAnOffCycleCaseIsBlocked() throws Exception {
        cycle(jdbc, LOCKED, "LOCKED");
        // 106731: the regular run of record paid July.
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, payable_days, result_status, is_locked)"
                        + " SELECT c.cycle_instance_id, e.location_id, e.employee_id, 'STAFF',"
                        + " '202607', DATE '2026-07-01', 'ACTUAL', 50000, 5000, 45000, 31,"
                        + " 'CURRENT', 'Y' FROM employee e JOIN payroll_cycle_instance c ON"
                        + " c.org_unit_id = e.location_id AND c.payroll_yymm = '202607' WHERE"
                        + " e.emp_num = '106731'");
        // 106845: an active I-20 case covers July.
        jdbc.update(
                "INSERT INTO off_cycle_payment (employee_id, case_type, salary_yymm,"
                        + " processing_yymm, amount, duplicate_check_run, record_status,"
                        + " invoice_status) SELECT employee_id, 'LATE_JOINER', '202607', '202610',"
                        + " 1000, 'N', 'ACTIVE', 'NOT_RAISED' FROM employee WHERE emp_num ="
                        + " '106845'");
        Session maker = maker();
        for (String eis : new String[] {"106731", "106845"}) {
            assertThat(
                            body(capture(maker, eis, "SALARY_WAGE", LOCKED)
                                            .andExpect(status().isCreated())
                                            .andReturn())
                                    .get("status")
                                    .asString())
                    .as(eis)
                    .isEqualTo("BLOCKED");
        }
    }

    @Test
    void chk_supp_sanction_after_appl_theSanctionFollowsTheApplication() throws Exception {
        cycle(jdbc, LOCKED, "LOCKED");
        capture(maker(), "106958", "SALARY_WAGE", LOCKED, "2026-08-10", "2026-08-10")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("SUP-422-SANCTION"));
    }

    @Test
    void SRS_G_33_onlyThePayrollMakerOfTheMembersOfficeRaisesAndAdminsSeeNothing()
            throws Exception {
        cycle(jdbc, LOCKED, "LOCKED");
        Session checker = login(CHECKER, "PAYROLL_CHECKER", "DH001");
        capture(checker, "107001", "SALARY_WAGE", LOCKED).andExpect(status().isForbidden());
        Session kolkata = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        // Another office's member reads as unknown (404); the Checker above has no I-23 CREATE
        // (403).
        capture(kolkata, "107001", "SALARY_WAGE", LOCKED).andExpect(status().isNotFound());
        capture(maker(), "999999", "SALARY_WAGE", LOCKED).andExpect(status().isNotFound());
        mvc.perform(userGet(checker, "/v1/supplementary")).andExpect(status().isOk());
        Session admin = login("sysadmin@fci-demo.test", null, null);
        // OD-125 (Wireframes v6.1 role access, V078_4): I-23 is drawn for the Administrator (read
        // only); WF-071 for FCI.
        mvc.perform(userGet(admin, "/v1/supplementary")).andExpect(status().isOk());
        Session eseva = login("emp.rsharma@fci-demo.test", null, null);
        mvc.perform(userGet(eseva, "/v1/supplementary")).andExpect(status().isForbidden());
    }

    @Test
    void SRS_G_37_aDraftIsCancelledWithItsOrderNeverDeleted() throws Exception {
        cycle(jdbc, LOCKED, "LOCKED");
        Session maker = maker();
        long id =
                body(capture(maker, "107001", "SALARY_WAGE", LOCKED)
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asLong();
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/supplementary/" + id + "/cancel",
                                Map.of("reason", "Paid by I-20 instead")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Paid by I-20 instead"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attachment_status FROM document_attachment WHERE"
                                    + " entity_name = 'supplementary_payment' AND entity_key = ?",
                                String.class,
                                String.valueOf(id)))
                .isEqualTo("CANCELLED");
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/supplementary/" + id + "/cancel",
                                Map.of("reason", "again")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SUP-409-STATUS"));
        mvc.perform(userPost(maker, "/v1/supplementary/" + id + "/submit", Map.of()))
                .andExpect(status().isConflict());
    }

    @Test
    void SRS_G_13_theRequestListExportsAsCsv() throws Exception {
        cycle(jdbc, LOCKED, "LOCKED");
        Session maker = maker();
        String reference =
                body(capture(maker, "107011", "SALARY_WAGE", LOCKED)
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("reference")
                        .asString();
        org.springframework.test.web.servlet.MvcResult csv =
                download(maker, "/v1/supplementary/export");
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv(maker, "/v1/supplementary/export"))
                .startsWith("\"Request reference\",\"EIS\"")
                .contains(reference);
        Session admin = login("sysadmin@fci-demo.test", null, null);
        mvc.perform(userGet(admin, "/v1/supplementary/export")).andExpect(status().isForbidden());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.general.api.SupplementaryPayments supplementary;

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.organisation.api.OrgDirectory orgs;

    /** The Payroll Maker of DH001's unit as a recovery-raising module calls the port (I-23). */
    private in.gov.fci.payroll.platform.security.AccessContext delhiMaker() {
        return delhiMaker(jdbc, orgs);
    }

    /**
     * The Payroll Maker of DH001's unit as a module that raises a payment calls the port (I-23):
     * shared with {@link SupplementaryApprovalIT}, whose payments other than a pending salary are
     * raised by their source module, never captured on the screen (SUP-422-SOURCE).
     */
    static in.gov.fci.payroll.platform.security.AccessContext delhiMaker(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            in.gov.fci.payroll.organisation.api.OrgDirectory orgs) {
        Long dh001 =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DH001'",
                        Long.class);
        java.util.Set<Long> scope = java.util.Set.copyOf(orgs.subtree(dh001));
        Long role =
                jdbc.queryForObject(
                        "SELECT role_id FROM app_role WHERE role_code = 'PAYROLL_MAKER'",
                        Long.class);
        in.gov.fci.payroll.platform.security.ActiveGroup group =
                new in.gov.fci.payroll.platform.security.ActiveGroup(
                        -dh001,
                        "AG-IT-SUPP",
                        "Supplementary IT",
                        role,
                        "PAYROLL_MAKER",
                        "Payroll Maker",
                        in.gov.fci.payroll.platform.security.ScopeType.DIVISION,
                        false,
                        scope,
                        java.util.Set.of(),
                        in.gov.fci.payroll.platform.security.AccessMode.FULL);
        return new in.gov.fci.payroll.platform.security.AccessContext(
                2L,
                MAKER,
                "Maker Delhi",
                "it",
                "PAYROLL_MAKER",
                dh001,
                null,
                java.util.List.of(group),
                Map.of(
                        -dh001,
                        java.util.Set.of(
                                in.gov.fci.payroll.platform.security.AccessContext.key(
                                        "I-23", "VIEW"),
                                in.gov.fci.payroll.platform.security.AccessContext.key(
                                        "I-23", "CREATE"))),
                java.util.Set.of(),
                java.util.Set.of(),
                scope);
    }

    /**
     * SRS-14-3 (l.2964, BMAD TR-030): a recovery under Court Recovery 366, FCI Society Recovery
     * 396, Bank Loan Recovery 397 or Life Insurance 504 is blocked on I-23 (SUP-422 excluded code,
     * nothing stored) and the attempt is logged: an audit row of supplementary_payment naming the
     * pay code survives the refused request.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {366, 396, 397, 504})
    void SRS_14_3_anExcludedRecoveryIsBlockedAndTheAttemptLogged(int code) {
        cycle(jdbc, LOCKED, "LOCKED");
        Long member =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = '106118'", Long.class);
        Long stored = jdbc.queryForObject("SELECT COUNT(*) FROM supplementary_payment", Long.class);
        Long logged =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                + " 'supplementary_payment' AND entity_key = ? AND field_name ="
                                + " 'pay_code' AND new_value = ?",
                        Long.class,
                        String.valueOf(member),
                        String.valueOf(code));
        in.gov.fci.payroll.general.api.SupplementaryPayments.NewPayment recovery =
                new in.gov.fci.payroll.general.api.SupplementaryPayments.NewPayment(
                        member,
                        LOCKED,
                        "RECOVERY",
                        code,
                        new java.math.BigDecimal("-100"),
                        java.time.LocalDate.of(2026, 8, 10),
                        null,
                        null,
                        new in.gov.fci.payroll.general.api.SupplementaryPayments.Order(
                                "SANCTION_ORDER",
                                "DH/SUP/EXCL",
                                java.time.LocalDate.of(2026, 8, 12),
                                "AGM (Pay) Delhi",
                                "order.pdf",
                                "%PDF-1.7 order".getBytes(StandardCharsets.US_ASCII)));
        // The demo M-07 holds 504 only (seed V025_9 flags whichever of the four exist): a code
        // missing from M-07 is refused as unknown, never paid; one present is the exclusion.
        boolean inM07 =
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM pay_component WHERE legacy_pay_code = ? AND"
                                        + " supp_excluded_flag = 'Y'",
                                Integer.class,
                                code)
                        > 0;
        assertThat(inM07 || code != 504).as("504 is an excluded M-07 code of the demo").isTrue();
        assertThatThrownBy(() -> supplementary.raise(delhiMaker(), recovery))
                .isInstanceOfSatisfying(
                        in.gov.fci.payroll.platform.error.FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(
                                                inM07
                                                        ? in.gov.fci.payroll.general.business
                                                                .GeneralErrorCode
                                                                .SUPPLEMENTARY_EXCLUDED_CODE
                                                        : in.gov.fci.payroll.general.business
                                                                .GeneralErrorCode
                                                                .SUPPLEMENTARY_INPUT));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplementary_payment", Long.class))
                .isEqualTo(stored);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'supplementary_payment' AND entity_key = ? AND"
                                        + " field_name = 'pay_code' AND new_value = ? AND"
                                        + " module_code = 'I-23'",
                                Long.class,
                                String.valueOf(member),
                                String.valueOf(code)))
                .isEqualTo(inM07 ? logged + 1 : logged);
    }
}
