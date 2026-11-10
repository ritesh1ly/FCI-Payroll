package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.business.PayRules;
import in.gov.fci.payroll.penalty.orchestration.PenaltyLockEffect;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Penalty recovery in the staff payroll and at the lock (plan 12 phase 12d; SRS-14-11, SRS-1-6):
 * the month's instalment is deducted under the recovery head after the loans; at the lock a
 * computed member's month is RECOVERED and the order closes once nothing is left; a member
 * separated before the month pauses the order for Full &amp; Final; a member not computed keeps the
 * balance as DEFERRED. Orders are fixtures in months far ahead so no other test meets them; demo
 * recovery head 912 (V044_2_9).
 */
class PenaltyRecoveryIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private StaffDeductionRunner deductions;
    @Autowired private PayConfiguration configuration;
    @Autowired private PenaltyLockEffect effect;
    @Autowired private PlatformTransactionManager transactionManager;

    private long member(int fromEnd) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                        + " 'ACTIVE' AND employment_status = 'IN_SERVICE' AND (provident_fund_type"
                        + " <> 'CPF' OR (cpf_code IS NOT NULL AND parent_zone_id IS NOT NULL))"
                        + " ORDER BY emp_num DESC"
                        + " OFFSET "
                        + fromEnd
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("CH001"));
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    /** An ACTIVE order of {@code amounts} month by month from {@code first}; its id. */
    private long order(long employee, YearMonth first, String... amounts) {
        String ref = "PEN-IT-" + System.nanoTime() % 1_000_000_000L;
        jdbc.update(
                "INSERT INTO penalty_order (penalty_ref, employee_id, order_reference,"
                        + " penalty_nature, total_recovery_amount, instalment_count, period_from,"
                        + " imposed_date, order_status) VALUES (?, ?, 'IT/ORDER', 'RECOVERY', ?, ?,"
                        + " ?, ?, 'ACTIVE')",
                ref.substring(0, Math.min(20, ref.length())),
                employee,
                new BigDecimal("0").add(sum(amounts)),
                amounts.length,
                Date.valueOf(first.atDay(1)),
                Date.valueOf(LocalDate.now(IST)));
        long id =
                jdbc.queryForObject(
                        "SELECT order_id FROM penalty_order WHERE penalty_ref = ?",
                        Long.class,
                        ref.substring(0, Math.min(20, ref.length())));
        YearMonth m = first;
        for (String a : amounts) {
            jdbc.update(
                    "INSERT INTO penalty_recovery_schedule (order_id, payroll_yymm,"
                            + " scheduled_amount) VALUES (?, ?, ?)",
                    id,
                    yymm(m),
                    new BigDecimal(a));
            m = m.plusMonths(1);
        }
        return id;
    }

    private static BigDecimal sum(String... amounts) {
        BigDecimal s = BigDecimal.ZERO;
        for (String a : amounts) {
            s = s.add(new BigDecimal(a));
        }
        return s;
    }

    /** A VERIFIED cycle of the month for CH001, with a result for each of {@code computed}. */
    private long cycle(YearMonth month, long... computed) {
        long unit = locationId("CH001");
        int start = month.getMonthValue() >= 4 ? month.getYear() : month.getYear() - 1;
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) VALUES (?, ?, 'DO', ?,"
                        + " 'VERIFIED', 'it.fixture')",
                yymm(month),
                unit,
                String.format("%d-%02d", start, (start + 1) % 100));
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        yymm(month),
                        unit);
        for (long employee : computed) {
            jdbc.update(
                    "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                            + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                            + " total_deductions, net_pay, result_status, is_locked) VALUES (?, ?,"
                            + " ?, 'STAFF', ?, ?, 'ACTUAL', 40000, 5000, 35000, 'CURRENT', 'N')",
                    cycle,
                    unit,
                    employee,
                    yymm(month),
                    Date.valueOf(month.atDay(1)));
        }
        return cycle;
    }

    private void lock(long cycle, YearMonth month) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        s ->
                                effect.on(
                                        new CycleLocked(
                                                cycle,
                                                locationId("CH001"),
                                                yymm(month),
                                                "it.checker",
                                                Instant.now())));
    }

    private List<String> statuses(long order) {
        return jdbc.queryForList(
                "SELECT schedule_status FROM penalty_recovery_schedule WHERE order_id = ? ORDER BY"
                        + " payroll_yymm",
                String.class,
                order);
    }

    private String orderStatus(long order) {
        return jdbc.queryForObject(
                "SELECT order_status FROM penalty_order WHERE order_id = ?", String.class, order);
    }

    /** The member's staff deductions of {@code month} on a Basic of 40,000 (CH001, IDA). */
    private PayRules.Outcome computed(long employee, YearMonth month) {
        PayConfiguration.Snapshot config = configuration.staffConfiguration(month.atDay(1));
        return deductions
                .open(month)
                .apply(
                        new PayRules.Outcome(
                                PayRules.COMPUTED,
                                null,
                                List.of(
                                        new PayRules.Line(
                                                PayRules.BASIC,
                                                config.componentIds().get(PayRules.BASIC),
                                                new BigDecimal("40000"),
                                                null,
                                                null)),
                                new BigDecimal("40000"),
                                BigDecimal.ZERO,
                                new BigDecimal("40000")),
                        employee,
                        locationId("CH001"),
                        "IDA",
                        config);
    }

    // SC-12-0026 (UC I-06 verify): the recovery is on the member's payroll result under its head
    // for each scheduled period, with that period's scheduled amount, and nothing once recovered.
    @Test
    void SRS_14_11_theInstalmentIsDeductedAfterTheLoansAndTheOrderClosesWhenRecovered() {
        long employee = member(10);
        YearMonth first = YearMonth.now(IST).plusMonths(80);
        long order = order(employee, first, "1500", "1600");
        PayConfiguration.Snapshot config = configuration.staffConfiguration(first.atDay(1));
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
                                                        new BigDecimal("40000"),
                                                        null,
                                                        null)),
                                        new BigDecimal("40000"),
                                        BigDecimal.ZERO,
                                        new BigDecimal("40000")),
                                employee,
                                locationId("CH001"),
                                "IDA",
                                config);
        assertThat(o.lines())
                .filteredOn(l -> l.payCode() == 912)
                .singleElement()
                .satisfies(l -> assertThat(l.amount()).isEqualByComparingTo("-1500"));

        lock(cycle(first, employee), first);
        assertThat(statuses(order)).containsExactly("RECOVERED", "PENDING");
        assertThat(orderStatus(order)).isEqualTo("ACTIVE");
        // The second scheduled period carries its own amount, once.
        assertThat(computed(employee, first.plusMonths(1)).lines())
                .filteredOn(l -> l.payCode() == 912)
                .singleElement()
                .satisfies(l -> assertThat(l.amount()).isEqualByComparingTo("-1600"));
        lock(cycle(first.plusMonths(1), employee), first.plusMonths(1));
        assertThat(statuses(order)).containsExactly("RECOVERED", "RECOVERED");
        assertThat(orderStatus(order)).as("auto-close").isEqualTo("COMPLETED");
        // Recovered in full: a later period carries no recovery line.
        assertThat(computed(employee, first.plusMonths(2)).lines())
                .noneMatch(l -> l.payCode() == 912);
    }

    // SC-12-0024: a Full & Final Settlement pauses the recovery and hands it to the case.
    @Test
    void SRS_14_11_aSeparatedMemberPausesForFnfAndANotComputedMonthIsDeferred() {
        long separated = member(11);
        long absent = member(12);
        YearMonth month = YearMonth.now(IST).plusMonths(90);
        long paused = order(separated, month, "500", "500");
        long deferred = order(absent, month, "700");
        jdbc.update(
                "UPDATE employee SET separation_date = ? WHERE employee_id = ?",
                Date.valueOf(month.minusMonths(1).atDay(15)),
                separated);
        try {
            lock(cycle(month), month);
        } finally {
            jdbc.update(
                    "UPDATE employee SET separation_date = NULL WHERE employee_id = ?", separated);
        }
        assertThat(orderStatus(paused)).isEqualTo("PAUSED_FNF");
        assertThat(statuses(paused)).containsExactly("DEFERRED", "DEFERRED");
        assertThat(statuses(deferred)).containsExactly("DEFERRED");
        assertThat(orderStatus(deferred)).isEqualTo("ACTIVE");
    }
}
