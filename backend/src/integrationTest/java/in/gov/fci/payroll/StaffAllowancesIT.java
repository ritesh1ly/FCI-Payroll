package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.api.AllowanceConfiguration;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.api.MemberPaymentHeld;
import in.gov.fci.payroll.payroll.api.MembersPaymentReleased;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The flag-driven staff allowances and the payment hold against the database (plan 16 phase 16d;
 * SRS-1-4, SRS-1-7, SRS-1-41): the M-01 flags in force at the period start reach the member; NPA %,
 * ceiling and pay code come from M-23 / M-16 approved versions and enter the run's hash; a member
 * flagged Stop-Payment is computed with the payment held, and the hold is raised once on X-05. The
 * configuration rows are fixtures (not FCI values) on codes no demo member is flagged for.
 */
class StaffAllowancesIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private PayConfiguration configuration;
    @Autowired private AllowanceConfiguration allowances;
    @Autowired private NamedParameterJdbcTemplate named;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactions;

    private long member(int offset) {
        return jdbc.queryForObject(
                // An in-service demo member of the seed (not one another IT created or separated
                // meanwhile), so the class does not depend on the order the suite runs in.
                "SELECT employee_id FROM employee e WHERE location_id = ? AND record_status ="
                        + " 'ACTIVE' AND emp_lbr_flag = 'N' AND employment_status = 'IN_SERVICE'"
                        + " AND current_basic IS NOT NULL AND EXISTS (SELECT 1 FROM"
                        + " employee_eligibility_flag f WHERE f.employee_id = e.employee_id AND"
                        + " f.changed_by = 'demo.seed') ORDER BY emp_num DESC OFFSET "
                        + offset
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"));
    }

    /**
     * SRS-1-4: the NPA flag, % (demo 25), a ceiling and its pay code configured: the member's NPA
     * is the % of Basic at most the ceiling and enters the DA base; configuring it changes the
     * hash; a flag ended before the period does not apply.
     */
    // SC-06-0028: the eligibility flag decides whether the component applies to the member.
    @Test
    void SRS_1_4_aFlaggedMemberIsPaidNpaFromTheApprovedConfiguration() {
        LocalDate start = YearMonth.now(IST).atDay(1);
        String before = configuration.staffConfiguration(start).versionSetHash();
        jdbc.update(
                "INSERT INTO pay_component (legacy_pay_code, component_name, component_type,"
                        + " payslip_group, source_code, taxable_flag, in_cpf_base_flag,"
                        + " effective_from) VALUES (9901, 'Non-Practising Allowance (IT code)',"
                        + " 'EARNING', 'ALLOWANCES', 'RULE', 'Y', 'Y', DATE '2026-04-01')");
        jdbc.update(
                "UPDATE reference_value SET numeric_attribute = 9901 WHERE value_code = 'NPA' AND"
                        + " list_id = (SELECT list_id FROM reference_list WHERE list_code ="
                        + " 'ELIGIBILITY_FLAG')");
        jdbc.update(
                "INSERT INTO other_parameter (parameter_code, parameter_name, ceiling_value,"
                        + " payroll_type_scope, version_no, effective_from, is_confirmed,"
                        + " approval_id) VALUES ('NPA_CEILING', 'NPA ceiling (IT)', 4000, 'STAFF',"
                        + " 90, DATE '2026-04-01', 'N', (SELECT MIN(approval_id) FROM"
                        + " approval_queue WHERE entity_name = 'SEED_BASELINE'))");
        AllowanceConfiguration.Allowances now = allowances.on(start);
        assertThat(now.payCode("NPA")).contains(9901);
        assertThat(now.payCode("CCA")).contains(131);
        assertThat(now.npaPercent()).isEqualByComparingTo("25");
        assertThat(now.npaCeiling()).isEqualByComparingTo("4000");
        PayConfiguration.Snapshot snapshot = configuration.staffConfiguration(start);
        assertThat(snapshot.versionSetHash())
                .as("a configured allowance enters the hash")
                .isNotEqualTo(before);

        long employee = member(0);
        // The demo CCA flag of every active demo employee (seed V040_6_9), inserted when absent: a
        // member another IT created later has none, and the class must not depend on the order.
        jdbc.update(
                "INSERT INTO employee_eligibility_flag (employee_id, flag_code, flag_value,"
                    + " effective_from, reason, changed_by) SELECT ?, 'CCA', 'Y', DATE"
                    + " '2026-04-01', 'IT: demo CCA', 'it' FROM dual WHERE NOT EXISTS (SELECT 1"
                    + " FROM employee_eligibility_flag f WHERE f.employee_id = ? AND f.flag_code ="
                    + " 'CCA')",
                employee,
                employee);
        jdbc.update(
                "INSERT INTO employee_eligibility_flag (employee_id, flag_code, flag_value,"
                    + " effective_from, reason, changed_by) VALUES (?, 'NPA', 'Y', ?, 'IT', 'it')",
                employee,
                java.sql.Date.valueOf(start.minusMonths(2)));
        jdbc.update(
                "INSERT INTO employee_eligibility_flag (employee_id, flag_code, flag_value,"
                    + " effective_from, effective_to, reason, changed_by) VALUES (?, 'TRANSPORT',"
                    + " 'Y', ?, ?, 'IT ended', 'it')",
                employee,
                java.sql.Date.valueOf(start.minusMonths(3)),
                java.sql.Date.valueOf(start.minusDays(1)));
        MemberReader.MemberRow row = MemberReader.member(named, employee, start).orElseThrow();
        PayRules.Member m = row.member();
        assertThat(m.allowanceFlags()).containsExactlyInAnyOrder("CCA", "NPA");
        PayRules.Outcome o = PayRules.compute(m, snapshot);
        PayRules.Line npa =
                o.lines().stream().filter(l -> l.payCode() == 9901).findFirst().orElseThrow();
        assertThat(npa.amount()).isLessThanOrEqualTo(new java.math.BigDecimal("4000"));
        PayRules.Line da =
                o.lines().stream()
                        .filter(l -> l.payCode() == PayRules.DA)
                        .findFirst()
                        .orElseThrow();
        assertThat(da.basis()).isEqualByComparingTo(row.basic().add(npa.amount()));

        jdbc.update(
                "INSERT INTO employee_eligibility_flag (employee_id, flag_code, flag_value,"
                        + " effective_from, reason, changed_by) VALUES (?, 'NPA', 'N', ?, 'IT"
                        + " withdrawn', 'it')",
                employee,
                java.sql.Date.valueOf(start.minusMonths(1)));
        assertThat(
                        MemberReader.member(named, employee, start)
                                .orElseThrow()
                                .member()
                                .allowanceFlags())
                .as("the latest version of the flag is N")
                .containsExactly("CCA");
    }

    /**
     * SRS-1-7 / SRS-1-41: a member flagged Stop-Payment is computed with the payment held; the hold
     * is raised on X-05 once per cycle and member; a HOLD event reads as Salary Withheld.
     */
    // SC-16-0089: a Hold / Absconding / Stop-Payment member is computed with the payment
    // suppressed.
    @Test
    void SRS_1_7_aHeldPaymentIsComputedAndRaisedOnceOnTheWorklist() {
        long employee = member(1);
        jdbc.update("UPDATE employee SET stop_payment_flag = 'Y' WHERE employee_id = ?", employee);
        LocalDate start = YearMonth.now(IST).atDay(1);
        MemberReader.MemberRow row = MemberReader.member(named, employee, start).orElseThrow();
        assertThat(row.paymentFlag()).isEqualTo("STOP_PAYMENT_FLAG");
        PayRules.Outcome o =
                PayRules.compute(row.member(), configuration.staffConfiguration(start));
        assertThat(o.status()).isEqualTo(PayRules.COMPUTED);
        assertThat(o.paymentHold()).isEqualTo("STOP_PAYMENT_FLAG");
        jdbc.update("UPDATE employee SET stop_payment_flag = 'N' WHERE employee_id = ?", employee);

        Long cycle =
                jdbc.queryForObject(
                        "SELECT MIN(cycle_instance_id) FROM payroll_cycle_instance", Long.class);
        if (cycle == null) {
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                            + " fin_year, cycle_status, initiated_by) VALUES ('209912', ?, 'DO',"
                            + " 'IT', 'INITIATED', 'it.fixture')",
                    locationId("DH001"));
            cycle =
                    jdbc.queryForObject(
                            "SELECT MIN(cycle_instance_id) FROM payroll_cycle_instance",
                            Long.class);
        }
        long cycleId = cycle;
        for (int i = 0; i < 2; i++) {
            transactions.executeWithoutResult(
                    t ->
                            events.publishEvent(
                                    new MemberPaymentHeld(cycleId, employee, "STOP_PAYMENT_FLAG")));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_exception WHERE exception_code ="
                                    + " 'PAYMENT_HELD' AND employee_id = ? AND cycle_instance_id ="
                                    + " ? AND field_name = 'stop_payment_flag' AND exception_status"
                                    + " = 'OPEN'",
                                Long.class,
                                employee,
                                cycle))
                .isEqualTo(1);
        // The hold ended: a later Actual run computes the member without it and X-05 is cleared.
        transactions.executeWithoutResult(
                t ->
                        events.publishEvent(
                                new MembersPaymentReleased(cycleId, List.of(employee, -1L))));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_exception WHERE exception_code ="
                                    + " 'PAYMENT_HELD' AND employee_id = ? AND cycle_instance_id ="
                                    + " ? AND exception_status = 'OPEN'",
                                Long.class,
                                employee,
                                cycle))
                .isZero();
        PayRules.Member withheld =
                new MemberReader.MemberRow(
                                employee,
                                1L,
                                row.basic(),
                                "IDA",
                                "X",
                                "IN_SERVICE",
                                null,
                                "HOLD",
                                "",
                                null)
                        .member();
        assertThat(withheld.salaryStopped()).isFalse();
        assertThat(withheld.paymentHold()).isEqualTo("SALARY_WITHHELD");
        assertThat(withheld.allowanceFlags()).isEmpty();
    }
}
