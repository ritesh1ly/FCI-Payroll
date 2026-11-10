package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.payroll.api.StaffEarnings;
import in.gov.fci.payroll.tax.business.EarningsYear;
import in.gov.fci.payroll.tax.business.TaxableEarnings;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The taxable earnings of a member's year from the staff payroll results (plan 16 phase 16e-1; plan
 * 08 D-4 closed for staff): the months April to March of {@code finYear} from {@link
 * StaffEarnings}, assembled by {@link EarningsYear}. Labour engines (20-22) add their results when
 * built.
 */
@Component
public class PayrollTaxableEarnings implements TaxableEarnings {

    private final StaffEarnings earnings;

    public PayrollTaxableEarnings(StaffEarnings earnings) {
        this.earnings = earnings;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Year> year(Long employeeId, String finYear) {
        int start = Integer.parseInt(finYear.substring(0, 4));
        List<EarningsYear.Result> results =
                earnings.months(employeeId, start + "04", (start + 1) + "03").stream()
                        .map(
                                m ->
                                        new EarningsYear.Result(
                                                m.yymm(),
                                                m.locked(),
                                                m.taxableGross(),
                                                m.lines().stream()
                                                        .filter(StaffEarnings.Line::taxable)
                                                        .map(
                                                                l ->
                                                                        new TaxableEarnings.Line(
                                                                                l.name(),
                                                                                l.amount()))
                                                        .toList(),
                                                m.tdsDeducted(),
                                                m.employerNps()))
                        .toList();
        return EarningsYear.of(finYear, results);
    }
}
