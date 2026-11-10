package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.loans.business.LoanRules;
import in.gov.fci.payroll.loans.orchestration.LoanLedgerEffect;
import in.gov.fci.payroll.loans.orchestration.LoanWriter;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Loan recovery and the ledger (plan 09 phase 09d; SRS-12-3, SRS-12-5, SRS-12-23, SRS-12-24,
 * SRS-12-26; D09-15, D09-16): the month's instalment is a deduction under the type's recovery head
 * (demo 910 / 911); at the lock the ledger row is written and the outstanding reduced, the last
 * instalment closes the loan; a member separated before the month stops the loan; a transfer moves
 * the outstanding through IOGA and the next ledger row names the previous unit. Loans start in
 * months far ahead so no other test's cycle meets them; DH001 members near the end of the list.
 */
class LoanRecoveryIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private LoanWriter writer;
    @Autowired private LoanLedgerEffect ledger;
    @Autowired private StaffDeductionRunner deductions;
    @Autowired private PayConfiguration configuration;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private in.gov.fci.payroll.organisation.api.OrgDirectory orgDirectory;

    private long member(int fromEnd) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                    + " 'ACTIVE' AND employment_status = 'IN_SERVICE' ORDER BY emp_num DESC OFFSET "
                        + fromEnd
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"));
    }

    private static YearMonth ahead(int months) {
        return YearMonth.now(IST).plusMonths(months);
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private long loan(
            long employee, String type, String amount, int n, String instalment, YearMonth start) {
        long typeId =
                jdbc.queryForObject(
                        "SELECT loan_type_id FROM loan_type_master WHERE loan_type_code = ?",
                        Long.class,
                        type);
        LoanRules.Terms t =
                new LoanRules.Terms(
                        new BigDecimal(amount),
                        n,
                        new BigDecimal(instalment),
                        null,
                        null,
                        0,
                        null,
                        yymm(start));
        return new TransactionTemplate(transactionManager)
                .execute(
                        s ->
                                writer.create(
                                                employee,
                                                typeId,
                                                type,
                                                t,
                                                LoanRules.schedule(t),
                                                LocalDate.now(IST),
                                                LoanWriter.Source.HRMS,
                                                "IT-REC-" + System.nanoTime(),
                                                null,
                                                null,
                                                "it.maker",
                                                "it.checker",
                                                Instant.now())
                                        .loanId());
    }

    /** A cycle of the month for DH001 with a current Actual result of the member; its id. */
    private long computed(long employee, YearMonth month) {
        long unit = locationId("DH001");
        String m = yymm(month);
        int start = month.getMonthValue() >= 4 ? month.getYear() : month.getYear() - 1;
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) SELECT ?, ?, 'DO', ?, 'VERIFIED',"
                        + " 'it.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM"
                        + " payroll_cycle_instance WHERE payroll_yymm = ? AND org_unit_id = ?)",
                m,
                unit,
                String.format("%d-%02d", start, (start + 1) % 100),
                m,
                unit);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        m,
                        unit);
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                    + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                    + " total_deductions, net_pay, result_status, is_locked) SELECT ?, ?, ?,"
                    + " 'STAFF', ?, ?, 'ACTUAL', 40000, 5000, 35000, 'CURRENT', 'N' FROM dual WHERE"
                    + " NOT EXISTS (SELECT 1 FROM payroll_result WHERE employee_id = ? AND"
                    + " payroll_yymm = ?)",
                cycle,
                unit,
                employee,
                m,
                java.sql.Date.valueOf(month.atDay(1)),
                employee,
                m);
        return cycle;
    }

    private void locked(long cycle, YearMonth month) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        s ->
                                ledger.on(
                                        new CycleLocked(
                                                cycle,
                                                locationId("DH001"),
                                                yymm(month),
                                                "it.checker",
                                                Instant.now())));
    }

    private Map<String, Object> header(long loanId) {
        return jdbc.queryForMap(
                "SELECT loan_status, principal_outstanding, paid_instalments FROM loan_header WHERE"
                        + " loan_id = ?",
                loanId);
    }

    // SC-09-0031: the loan ledger reflects the sanction, recovery progress and outstanding balance.
    @Test
    void SRS_12_3_theInstalmentIsDeductedAndTheLedgerClosesTheLoanWhenRecovered() throws Exception {
        long employee = member(14);
        YearMonth first = ahead(50);
        long loanId = loan(employee, "PLI_ADVANCE", "5000", 2, "2500", first);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode next = next(maker, loanId);
        assertThat(next.path("number").asInt())
                .as("SRS-12-23: the tracker names the first instalment before any recovery")
                .isEqualTo(1);
        assertThat(next.path("payrollYymm").asString()).isEqualTo(yymm(first));
        PayConfiguration.Snapshot config = configuration.staffConfiguration(first.atDay(1));
        BigDecimal basic = new BigDecimal("30000");
        PayRules.Outcome o =
                deductions
                        .open(first)
                        .apply(
                                new PayRules.Outcome(
                                        PayRules.COMPUTED,
                                        null,
                                        List.of(
                                                new PayRules.Line(
                                                        PayRules.BASIC,
                                                        config.componentIds().get(PayRules.BASIC),
                                                        basic,
                                                        null,
                                                        null)),
                                        basic,
                                        BigDecimal.ZERO,
                                        basic),
                                employee,
                                locationId("DH001"),
                                "IDA",
                                config);
        assertThat(
                        o.lines().stream()
                                .filter(l -> l.payCode() == 911)
                                .map(PayRules.Line::amount)
                                .findFirst())
                .as("SRS-12-24: under the PLI Advance head")
                .contains(new BigDecimal("-2500.00"));

        locked(computed(employee, first), first);
        Map<String, Object> after = header(loanId);
        assertThat((BigDecimal) after.get("PRINCIPAL_OUTSTANDING")).isEqualByComparingTo("2500");
        assertThat(((Number) after.get("PAID_INSTALMENTS")).intValue()).isEqualTo(1);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT opening_principal, principal_paid, closing_principal FROM"
                                + " loan_monthly_balance WHERE loan_id = ? AND payroll_yymm = ?",
                        loanId,
                        yymm(first));
        assertThat((BigDecimal) row.get("OPENING_PRINCIPAL")).isEqualByComparingTo("5000");
        assertThat((BigDecimal) row.get("CLOSING_PRINCIPAL")).isEqualByComparingTo("2500");

        locked(computed(employee, first), first);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM loan_monthly_balance WHERE loan_id = ?",
                                Long.class,
                                loanId))
                .as("a second lock event of the month writes nothing twice")
                .isEqualTo(1L);
        // SC-09-0031: the loan as I-04 shows it reflects the sanction, the recovery progress and
        // the outstanding balance.
        JsonNode shown =
                body(mvc.perform(userGet(maker, "/v1/loans/" + loanId)).andReturn()).path("loan");
        assertThat(shown.path("sanctioned").decimalValue()).isEqualByComparingTo("5000");
        assertThat(shown.path("paidInstalments").asInt()).isEqualTo(1);
        assertThat(shown.path("principalInstalments").asInt()).isEqualTo(2);
        assertThat(shown.path("principalOutstanding").decimalValue()).isEqualByComparingTo("2500");
        assertThat(shown.path("status").asString()).isEqualTo("ACTIVE");
        next = next(maker, loanId);
        assertThat(next.path("number").asInt())
                .as("SRS-12-23: after the first recovery the next instalment is the second")
                .isEqualTo(2);
        assertThat(next.path("payrollYymm").asString()).isEqualTo(yymm(first.plusMonths(1)));
        assertThat(next.path("principal").decimalValue()).isEqualByComparingTo("2500");

        YearMonth second = first.plusMonths(1);
        locked(computed(employee, second), second);
        assertThat(header(loanId).get("LOAN_STATUS"))
                .as("SRS-12-23: closed when recovered")
                .isEqualTo("CLOSED");
        JsonNode none = next(maker, loanId);
        assertThat(none.isNull() || none.isMissingNode())
                .as("SRS-12-23: a closed loan has no next instalment")
                .isTrue();
    }

    /** The tracker's next instalment of the loan on I-04 (SRS-12-23). */
    private JsonNode next(Session session, long loanId) throws Exception {
        return body(mvc.perform(userGet(session, "/v1/loans/" + loanId)).andReturn())
                .path("nextInstalment");
    }

    @Test
    void SRS_12_5_aMemberSeparatedBeforeTheMonthStopsTheLoanAndANotComputedMemberIsNotRecovered() {
        long separated = member(15);
        YearMonth month = ahead(60);
        long loanId = loan(separated, "FESTIVAL_ADVANCE", "4000", 2, "2000", month);
        jdbc.update(
                "UPDATE employee SET separation_date = ? WHERE employee_id = ?",
                java.sql.Date.valueOf(month.minusMonths(1).atDay(15)),
                separated);
        try {
            long cycle = computed(member(16), month);
            locked(cycle, month);
            assertThat(header(loanId).get("LOAN_STATUS")).isEqualTo("STOPPED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM loan_event WHERE loan_id = ? AND"
                                            + " event_type = 'STOP'",
                                    Long.class,
                                    loanId))
                    .isEqualTo(1L);
        } finally {
            jdbc.update(
                    "UPDATE employee SET separation_date = NULL WHERE employee_id = ?", separated);
        }
        long notComputed = member(17);
        long other = loan(notComputed, "FESTIVAL_ADVANCE", "4000", 2, "2000", month);
        locked(
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        yymm(month),
                        locationId("DH001")),
                month);
        assertThat((BigDecimal) header(other).get("PRINCIPAL_OUTSTANDING"))
                .as("no result in the cycle: nothing recovered")
                .isEqualByComparingTo("4000");
    }

    @Test
    void SRS_12_26_aTransferMovesTheOutstandingThroughIogaWithANarration() throws Exception {
        long employee = member(18);
        YearMonth month = ahead(70);
        long loanId = loan(employee, "FESTIVAL_ADVANCE", "6000", 3, "2000", month);
        long from = locationId("CH001");
        ledger.on(
                new EmployeeEvents.EmployeeTransferred(
                        employee,
                        1L,
                        from,
                        locationId("DH001"),
                        LocalDate.now(IST),
                        "TR/IT/1",
                        "it.checker"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settlement_amount FROM loan_event WHERE loan_id = ? AND"
                                        + " event_type = 'UNIT_TRANSFER'",
                                BigDecimal.class,
                                loanId))
                .isEqualByComparingTo("6000");
        locked(computed(employee, month), month);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT transfer_narration FROM loan_monthly_balance WHERE loan_id"
                                        + " = ?",
                                String.class,
                                loanId))
                .startsWith("IOGA from ")
                .contains("6000");
        String number =
                jdbc.queryForObject(
                        "SELECT loan_number FROM loan_header WHERE loan_id = ?",
                        String.class,
                        loanId);
        String report =
                csv(
                        login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001"),
                        "/v1/loans/report?from="
                                + yymm(month)
                                + "&to="
                                + yymm(month)
                                + "&format=CSV");
        assertThat(report)
                .as("SRS-12-26: the report reconciles the transfer")
                .contains(number, "IOGA from ");
    }

    /**
     * US-GAP-205 (SRS-12-5, BR-LOAN-006): a member separated within the month (on its first day or
     * later) still has that month's instalment recovered in the month's pay; from the next month
     * the loan is stopped and the balance goes to Full &amp; Final. One separated the day before
     * the month has nothing recovered in it.
     */
    @Test
    void SRS_12_5_aSeparationWithinTheMonthRecoversThatMonthAndStopsTheNext() {
        long employee = member(24);
        YearMonth month = ahead(90);
        long loanId = loan(employee, "FESTIVAL_ADVANCE", "6000", 3, "2000", month);
        try {
            for (LocalDate day : List.of(month.atDay(1), month.atDay(15), month.atEndOfMonth())) {
                separate(employee, day);
                assertThat(instalment(employee, month))
                        .as("separated on " + day)
                        .contains(new BigDecimal("-2000.00"));
            }
            separate(employee, month.minusMonths(1).atEndOfMonth());
            assertThat(instalment(employee, month)).as("separated before the month").isEmpty();

            separate(employee, month.atDay(15));
            locked(computed(employee, month), month);
            Map<String, Object> after = header(loanId);
            assertThat(after.get("LOAN_STATUS")).isEqualTo("ACTIVE");
            assertThat((BigDecimal) after.get("PRINCIPAL_OUTSTANDING"))
                    .isEqualByComparingTo("4000");
            YearMonth next = month.plusMonths(1);
            locked(computed(member(25), next), next);
            assertThat(header(loanId).get("LOAN_STATUS"))
                    .as("the month after the separation stops it")
                    .isEqualTo("STOPPED");
        } finally {
            jdbc.update(
                    "UPDATE employee SET separation_date = NULL WHERE employee_id = ?", employee);
        }
    }

    /**
     * US-GAP-207 (SRS-12-26): after a transfer within a month with an instalment due, the old
     * unit's lock recovers nothing for the member and the new unit's lock recovers the instalment
     * once, its ledger row naming the previous unit.
     */
    @Test
    void SRS_12_26_aMidMonthTransferRecoversTheInstalmentOnceInTheNewUnit() {
        long employee = member(26);
        YearMonth month = ahead(95);
        long loanId = loan(employee, "FESTIVAL_ADVANCE", "6000", 3, "2000", month);
        ledger.on(
                new EmployeeEvents.EmployeeTransferred(
                        employee,
                        1L,
                        locationId("CH001"),
                        locationId("DH001"),
                        month.atDay(15),
                        "TR/IT/207B",
                        "it.checker"));
        long oldUnit = org(locationId("CH001"));
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        s ->
                                ledger.on(
                                        new CycleLocked(
                                                0L,
                                                oldUnit,
                                                yymm(month),
                                                "it.checker",
                                                Instant.now())));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM loan_monthly_balance WHERE loan_id = ?",
                                Long.class,
                                loanId))
                .as("the old unit's lock recovers nothing")
                .isZero();
        locked(computed(employee, month), month);
        locked(computed(employee, month), month);
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT principal_paid, transfer_narration FROM loan_monthly_balance WHERE"
                                + " loan_id = ?",
                        loanId);
        assertThat(rows).hasSize(1);
        assertThat((BigDecimal) rows.get(0).get("PRINCIPAL_PAID")).isEqualByComparingTo("2000");
        assertThat((String) rows.get(0).get("TRANSFER_NARRATION")).startsWith("IOGA from ");
    }

    private void separate(long employee, LocalDate day) {
        jdbc.update(
                "UPDATE employee SET separation_date = ? WHERE employee_id = ?",
                java.sql.Date.valueOf(day),
                employee);
    }

    private long org(long location) {
        return orgDirectory.cycleUnitOf(location).orElseThrow().getId();
    }

    /** The member's loan recovery line of the month (FESTIVAL_ADVANCE, demo head 910). */
    private java.util.Optional<BigDecimal> instalment(long employee, YearMonth month) {
        PayConfiguration.Snapshot config = configuration.staffConfiguration(month.atDay(1));
        BigDecimal basic = new BigDecimal("30000");
        PayRules.Outcome o =
                deductions
                        .open(month)
                        .apply(
                                new PayRules.Outcome(
                                        PayRules.COMPUTED,
                                        null,
                                        List.of(
                                                new PayRules.Line(
                                                        PayRules.BASIC,
                                                        config.componentIds().get(PayRules.BASIC),
                                                        basic,
                                                        null,
                                                        null)),
                                        basic,
                                        BigDecimal.ZERO,
                                        basic),
                                employee,
                                locationId("DH001"),
                                "IDA",
                                config);
        return o.lines().stream()
                .filter(l -> l.payCode() == 910)
                .map(PayRules.Line::amount)
                .findFirst();
    }
}
