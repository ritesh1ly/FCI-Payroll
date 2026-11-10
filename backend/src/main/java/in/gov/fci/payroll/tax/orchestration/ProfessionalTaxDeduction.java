package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.payroll.api.StaffDeductions;
import in.gov.fci.payroll.payroll.api.StatutoryPayCodes;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Professional Tax as a deduction of the staff payroll (plan 16 phase 16e-2; SRS-1-6, SRS-7-4,
 * BR-TAX-006): the slab of the office's state on the month's taxable gross (the earnings whose pay
 * code is taxable), recovered under the pay code set for PT on the M-16 list STATUTORY_DEDUCTION. A
 * run pins the rules, the PT state master and the pay codes of its day once.
 *
 * <p>Refused, never assumed (R-RULE): the state not in the PT master, its slab not approved, or the
 * pay code not set ("not configured"); a state where PT does not apply deducts nothing.
 */
@Component
public class ProfessionalTaxDeduction implements StaffDeductions {

    /** After CPF / VPF (10, 20) and TDS (30) in the order of recovery (SRS-1-6). */
    static final int ORDER = 40;

    static final String CODE = "PT";

    private final TaxComputationService tax;
    private final JdbcTemplate jdbc;

    public ProfessionalTaxDeduction(TaxComputationService tax, JdbcTemplate jdbc) {
        this.tax = tax;
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return ORDER;
    }

    /** Gate G5 (SRS-15-18, SRS l.1524): this source is the Professional Tax sub-process. */
    @Override
    public java.util.Optional<String> statutoryProcess() {
        return java.util.Optional.of("PROFESSIONAL_TAX");
    }

    @Override
    public Run open(LocalDate asOf) {
        TaxComputationService.Run rates = tax.ratesOn(asOf);
        Optional<Integer> payCode = StatutoryPayCodes.of(jdbc, CODE, asOf);
        Map<Integer, Boolean> taxable = new HashMap<>();
        jdbc.query(
                "SELECT legacy_pay_code, taxable_flag FROM pay_component",
                rs -> {
                    taxable.put(rs.getInt(1), !"N".equals(rs.getString(2)));
                });
        Map<String, Object> states = new TreeMap<>();
        jdbc.query(
                "SELECT state_code, pt_applicable_flag, slab_rule_key, effective_from FROM"
                        + " pt_state_master WHERE effective_from <= ?",
                rs -> {
                    states.put(rs.getString(1), rs.getString(2) + rs.getString(3) + rs.getDate(4));
                },
                Date.valueOf(asOf));
        String versions = rates.get().hash() + "|pt=" + states + "|code=" + payCode.orElse(null);
        return new Run() {
            @Override
            public String versions() {
                return versions;
            }

            @Override
            public List<Line> deductions(Member member) {
                BigDecimal gross =
                        member.earnings().stream()
                                .filter(e -> e.amount().signum() > 0)
                                .filter(e -> taxable.getOrDefault(e.payCode(), true))
                                .map(Earning::amount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal pt;
                try {
                    pt =
                            tax.professionalTax(
                                            member.locationId(), Optional.of(gross), asOf, rates)
                                    .orElse(BigDecimal.ZERO);
                } catch (FciException e) {
                    throw new NotComputable(
                            "Professional Tax: "
                                    + e.getErrorCode().code()
                                    + " "
                                    + java.util.Arrays.toString(e.getMessageArgs()));
                }
                if (pt.signum() == 0) {
                    return List.of();
                }
                int code =
                        payCode.orElseThrow(
                                () ->
                                        new NotComputable(
                                                "Professional Tax: pay code not configured (M-16"
                                                        + " STATUTORY_DEDUCTION PT)"));
                return List.of(new Line(code, pt, null, gross));
            }
        };
    }
}
