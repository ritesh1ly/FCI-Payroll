package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.business.PayDataException;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The member's CPF and VPF as staff deductions (plan 16 phase 16e-2; SRS-6-1, SRS-6-7, SRS-1-14,
 * SRS-6-59): CPF.RATE.EMPLOYEE % of the CPF wage base (Basic + DA in the demo) under the CPF pay
 * code, before PT; the VPF subscription of the year under the VPF code; nothing for an NPS member;
 * a CPF member without a CPF code, and employee + VPF above the VPF maximum, are refused. Demo
 * rates (12 %, 100 %) and codes (904, 905), not FCI's.
 */
class CpfContributionDeductionIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private StaffDeductionRunner deductions;
    @Autowired private PayConfiguration configuration;

    private Map<String, Object> member(String where) {
        return jdbc.queryForMap(
                "SELECT e.employee_id, e.location_id FROM employee e JOIN org_location l ON"
                        + " l.location_id = e.location_id WHERE e.record_status = 'ACTIVE' AND "
                        + where
                        + " ORDER BY e.emp_num DESC FETCH FIRST 1 ROWS ONLY");
    }

    private PayRules.Outcome apply(Map<String, Object> m, String basic, String da, String hra) {
        YearMonth month = YearMonth.now(IST);
        PayConfiguration.Snapshot config = configuration.staffConfiguration(month.atDay(1));
        List<PayRules.Line> lines =
                List.of(
                        line(PayRules.BASIC, basic, config),
                        line(PayRules.DA, da, config),
                        line(PayRules.HRA, hra, config));
        BigDecimal gross = new BigDecimal(basic).add(new BigDecimal(da)).add(new BigDecimal(hra));
        return deductions
                .open(month)
                .apply(
                        new PayRules.Outcome(
                                PayRules.COMPUTED, null, lines, gross, BigDecimal.ZERO, gross),
                        ((Number) m.get("EMPLOYEE_ID")).longValue(),
                        ((Number) m.get("LOCATION_ID")).longValue(),
                        "IDA",
                        config);
    }

    private static PayRules.Line line(int code, String amount, PayConfiguration.Snapshot config) {
        return new PayRules.Line(
                code, config.componentIds().get(code), new BigDecimal(amount), null, null);
    }

    private static BigDecimal amount(PayRules.Outcome o, int code) {
        return o.lines().stream()
                .filter(l -> l.payCode() == code)
                .map(PayRules.Line::amount)
                .findFirst()
                .orElse(null);
    }

    @Test
    void SRS_6_1_cpfIsTheRateOfTheWageBaseAndPtFollows() {
        Map<String, Object> chennai =
                member(
                        "e.provident_fund_type = 'CPF' AND e.cpf_code IS NOT NULL AND l.state_code"
                                + " = 'TN'");
        PayRules.Outcome o = apply(chennai, "30000", "15000", "6000");
        assertThat(amount(o, 904)).as("12 % of Basic + DA, signed").isEqualByComparingTo("-5400");
        assertThat(amount(o, 901)).as("then PT").isEqualByComparingTo("-208");
        assertThat(o.lines()).extracting(PayRules.Line::payCode).containsSubsequence(904, 901);
        assertThat(o.net()).isEqualByComparingTo("45392");

        Map<String, Object> nps = member("e.provident_fund_type = 'NPS' AND l.state_code = 'DL'");
        assertThat(apply(nps, "30000", "15000", "6000").deductions())
                .as("an NPS member has no CPF (Delhi: no PT)")
                .isEqualByComparingTo("0");
    }

    @Test
    void SRS_6_59_withoutACpfCodeTheMemberIsRefused() {
        Map<String, Object> noCode = member("e.provident_fund_type = 'CPF' AND e.cpf_code IS NULL");
        assertThatThrownBy(() -> apply(noCode, "30000", "15000", "6000"))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("no CPF code");
    }

    /**
     * SRS-6-1 (l.2079, "Apply VPF at the member's declared amount") and the M-19 VPF maximum
     * (l.2347, "Total member contribution may not exceed the prescribed limit"). BMAD TR-016: the
     * EPS / 1.16 % split of SRS-6-7 is CpfLockIT
     * .SRS_6_7_theLockBooksTheMonthWithTheEmployerShareAndItsPensionSplit.
     */
    @Test
    void SRS_6_1_vpfIsTheDeclaredSubscriptionWithinTheMaximum() {
        Map<String, Object> delhi =
                member(
                        "e.provident_fund_type = 'CPF' AND e.cpf_code IS NOT NULL AND l.state_code"
                                + " = 'DL'");
        long id = ((Number) delhi.get("EMPLOYEE_ID")).longValue();
        YearMonth month = YearMonth.now(IST);
        int start = month.getMonthValue() >= 4 ? month.getYear() : month.getYear() - 1;
        String finYear = String.format("%d-%02d", start, (start + 1) % 100);
        // The member's ledger of the year (a demo ledger may already hold it, seed V077_28): the
        // subscription is set to 2,500 on it.
        jdbc.update(
                "INSERT INTO employee_pf_header (employee_id, fin_year, fund_type, zone_id,"
                    + " opening_balance_source, vcpf_subscription_amt) SELECT ?, ?, 'CPF', (SELECT"
                    + " MIN(zone_id) FROM org_zone), 'SYSTEM_COMPUTED', 2500 FROM dual WHERE NOT"
                    + " EXISTS (SELECT 1 FROM employee_pf_header WHERE employee_id = ? AND fin_year"
                    + " = ? AND fund_type = 'CPF')",
                id,
                finYear,
                id,
                finYear);
        jdbc.update(
                "UPDATE employee_pf_header SET vcpf_subscription_amt = 2500 WHERE employee_id = ?"
                        + " AND fin_year = ? AND fund_type = 'CPF'",
                id,
                finYear);
        PayRules.Outcome o = apply(delhi, "30000", "15000", "6000");
        assertThat(amount(o, 904)).isEqualByComparingTo("-5400");
        assertThat(amount(o, 905)).isEqualByComparingTo("-2500");
        assertThat(o.deductions()).isEqualByComparingTo("7900");

        jdbc.update(
                "UPDATE employee_pf_header SET vcpf_subscription_amt = 40000 WHERE employee_id = ?"
                        + " AND fin_year = ?",
                id,
                finYear);
        assertThatThrownBy(() -> apply(delhi, "30000", "15000", "6000"))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("exceed the VPF maximum");
    }
}
