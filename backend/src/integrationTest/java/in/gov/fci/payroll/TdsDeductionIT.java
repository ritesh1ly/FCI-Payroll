package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.business.PayDataException;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Income tax (TDS) as a staff deduction (plan 16 phase 16e-2; SRS-7-3, SRS-7-1, SRS-1-6, D16-27):
 * on a year with the test rates of {@code TaxYearFixtures} (41 years back, used by no other test),
 * a member without a regime has no TDS and payroll continues; with a regime the month's TDS is
 * recovered under the TDS pay code; a year without rates and an unset pay code refuse the member.
 * The member is an NPS member at Delhi (no CPF, no PT); the pay code 9903 is a fixture.
 */
class TdsDeductionIT extends ApiTestSupport {

    private static final String YEAR = TaxYearFixtures.yearBack(41);

    @Autowired private StaffDeductionRunner deductions;
    @Autowired private PayConfiguration configuration;

    private long member() {
        return jdbc.queryForObject(
                "SELECT MAX(e.employee_id) FROM employee e JOIN org_location l ON l.location_id ="
                    + " e.location_id WHERE e.provident_fund_type = 'NPS' AND l.state_code = 'DL'"
                    + " AND e.record_status = 'ACTIVE'",
                Long.class);
    }

    private long location(long employee) {
        return jdbc.queryForObject(
                "SELECT location_id FROM employee WHERE employee_id = ?", Long.class, employee);
    }

    /** One month's earnings (a fixture) netted by the sources opened for {@code month}. */
    private PayRules.Outcome apply(long employee, YearMonth month) {
        PayConfiguration.Snapshot config =
                configuration.staffConfiguration(LocalDate.now(TaxYearFixtures.INDIA));
        PayRules.Line basic =
                new PayRules.Line(
                        PayRules.BASIC,
                        config.componentIds().get(PayRules.BASIC),
                        new BigDecimal("100000"),
                        null,
                        null);
        PayRules.Outcome computed =
                new PayRules.Outcome(
                        PayRules.COMPUTED,
                        null,
                        List.of(basic),
                        basic.amount(),
                        BigDecimal.ZERO,
                        basic.amount());
        return deductions.open(month).apply(computed, employee, location(employee), "IDA", config);
    }

    /**
     * The TDS pay code for the test. The list's values start 2026-04-01 and the fixture year is
     * long before, so the code is dated back for the test and restored after.
     */
    private void code(Integer code) {
        jdbc.update(
                "UPDATE reference_value SET numeric_attribute = ?, effective_from = ? WHERE"
                        + " value_code = 'TDS' AND list_id = (SELECT list_id FROM reference_list"
                        + " WHERE list_code = 'STATUTORY_DEDUCTION')",
                code,
                java.sql.Date.valueOf(code == null ? "2026-04-01" : "1900-04-01"));
    }

    @Test
    void SRS_7_3_theMonthsTdsIsRecoveredOnlyWithARegimeAndItsPayCode() {
        jdbc.update(
                "INSERT INTO pay_component (legacy_pay_code, component_name, component_type,"
                        + " payslip_group, source_code, taxable_flag, in_cpf_base_flag,"
                        + " effective_from) SELECT 9903, 'Income tax (IT code)', 'DEDUCTION',"
                        + " 'DEDUCTIONS', 'RULE', 'N', 'N', DATE '2026-04-01' FROM dual WHERE NOT"
                        + " EXISTS (SELECT 1 FROM pay_component WHERE legacy_pay_code = 9903)");
        TaxYearFixtures.rates(jdbc, YEAR);
        long employee = member();
        YearMonth april = YearMonth.from(TaxYearFixtures.april(YEAR));
        code(9903);
        try {
            PayRules.Outcome noRegime = apply(employee, april);
            assertThat(noRegime.deductions())
                    .as("SRS-7-1: no regime, no TDS; payroll continues")
                    .isEqualByComparingTo("0");

            TaxYearFixtures.regime(jdbc, employee, YEAR, "O");
            PayRules.Outcome taxed = apply(employee, april);
            PayRules.Line tds =
                    taxed.lines().stream()
                            .filter(l -> l.payCode() == 9903)
                            .findFirst()
                            .orElseThrow();
            assertThat(tds.amount()).as("SRS-DM-6: signed").isNegative();
            assertThat(tds.basis()).isEqualByComparingTo("100000");
            assertThat(taxed.net())
                    .isEqualByComparingTo(new BigDecimal("100000").add(tds.amount()));

            YearMonth unconfigured =
                    YearMonth.from(TaxYearFixtures.april(TaxYearFixtures.yearBack(43)));
            TaxYearFixtures.regime(jdbc, employee, TaxYearFixtures.yearBack(43), "O");
            assertThatThrownBy(() -> apply(employee, unconfigured))
                    .as("a year without rates")
                    .isInstanceOf(PayDataException.class)
                    .hasMessageContaining("Income tax");

            code(null);
            assertThatThrownBy(() -> apply(employee, april))
                    .isInstanceOf(PayDataException.class)
                    .hasMessageContaining("pay code not configured");
        } finally {
            code(null);
        }
    }
}
