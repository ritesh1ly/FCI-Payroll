package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.payroll.api.StaffDeductions;
import in.gov.fci.payroll.payroll.api.StatutoryPayCodes;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.TaxableEarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Income tax (TDS) as a deduction of the staff payroll (plan 16 phase 16e-2; SRS-7-3, SRS-7-1,
 * SRS-7-13, SRS-1-6, D16-27): the projected annual tax of the year, with this month at the taxable
 * earnings just computed, less the TDS of the locked months, spread over the months not locked;
 * recovered under the STATUTORY_DEDUCTION pay code TDS.
 *
 * <ul>
 *   <li>no regime chosen (SRS-7-1): no TDS this month and payroll continues, as module 08 decided
 *       (the X-05 entry is its monthly computation's; the tax is trued up over the remaining
 *       months, SRS-7-3);
 *   <li>no valid PAN (SRS-7-13): the higher rate, computed by module 08;
 *   <li>rates not configured, or anything else that stops the forecast: the member is refused.
 * </ul>
 */
@Component
public class TdsDeduction implements StaffDeductions {

    /** After CPF / VPF (10, 20), before PT (40) in the order of recovery (SRS-1-6). */
    static final int ORDER = 30;

    private final TaxComputationService tax;
    private final JdbcTemplate jdbc;

    public TdsDeduction(TaxComputationService tax, JdbcTemplate jdbc) {
        this.tax = tax;
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return ORDER;
    }

    /** Gate G5 (SRS-15-18, SRS l.1524): this source is the income tax / TDS sub-process. */
    @Override
    public java.util.Optional<String> statutoryProcess() {
        return java.util.Optional.of("INCOME_TAX");
    }

    @Override
    public Run open(LocalDate asOf) {
        TaxComputationService.Run rates = tax.ratesOn(asOf);
        Optional<Integer> payCode = StatutoryPayCodes.of(jdbc, "TDS", asOf);
        // Plan 18 phase 18b (SRS-9-21): the employer NPS earning line (the NPS module's
        // contribution) is income with its matching exemption, passed apart from the gross.
        Optional<Integer> employerNps = StatutoryPayCodes.of(jdbc, "NPS_EMPLOYER_EARNING", asOf);
        Map<Integer, Boolean> taxable = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        jdbc.query(
                "SELECT legacy_pay_code, taxable_flag, component_name FROM pay_component",
                rs -> {
                    taxable.put(rs.getInt(1), !"N".equals(rs.getString(2)));
                    names.put(rs.getInt(1), rs.getString(3));
                });
        YearMonth month = YearMonth.from(asOf);
        String versions =
                rates.get().hash()
                        + "|tds="
                        + payCode.orElse(null)
                        + "|nps="
                        + employerNps.orElse(null);
        return new Run() {
            @Override
            public String versions() {
                return versions;
            }

            @Override
            public List<Line> deductions(Member member) {
                BigDecimal nps =
                        member.earnings().stream()
                                .filter(e -> isEmployerNps(employerNps, e))
                                .map(Earning::amount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                List<Earning> earnings =
                        member.earnings().stream()
                                .filter(e -> e.amount().signum() > 0)
                                .filter(e -> !isEmployerNps(employerNps, e))
                                .filter(e -> taxable.getOrDefault(e.payCode(), true))
                                .toList();
                BigDecimal gross =
                        earnings.stream()
                                .map(Earning::amount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                List<TaxableEarnings.Line> lines =
                        earnings.stream()
                                .map(
                                        e ->
                                                new TaxableEarnings.Line(
                                                        names.getOrDefault(
                                                                e.payCode(),
                                                                String.valueOf(e.payCode())),
                                                        e.amount()))
                                .toList();
                TaxComputationService.EngineTds tds =
                        tax.engineTds(member.employeeId(), month, gross, nps, lines, rates);
                if (tds.regimeMissing()
                        && tds.reasons().stream()
                                .allMatch(r -> r.startsWith(TaxErrorCode.REGIME_REQUIRED.code()))) {
                    return List.of();
                }
                if (tds.tds().isEmpty()) {
                    throw new NotComputable("Income tax: " + String.join(", ", tds.reasons()));
                }
                BigDecimal amount = tds.tds().get();
                if (amount.signum() <= 0) {
                    return List.of();
                }
                int code =
                        payCode.orElseThrow(
                                () ->
                                        new NotComputable(
                                                "Income tax: pay code not configured (M-16"
                                                        + " STATUTORY_DEDUCTION TDS)"));
                return List.of(new Line(code, amount, null, gross));
            }
        };
    }

    private static boolean isEmployerNps(Optional<Integer> code, Earning earning) {
        return code.isPresent() && earning.payCode() == code.get();
    }
}
