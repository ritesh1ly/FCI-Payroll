package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.employee.api.EmployeeDirectory.PanStatus;
import in.gov.fci.payroll.tax.business.TaxCalculator;
import in.gov.fci.payroll.tax.business.TaxableEarnings;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * SRS-7-5, SRS-7-30 (owner 2026-11-09): Form 16 Part B (Annexure) from a worked example: every item
 * of the prescribed form with its value from the computation of the year, items 1 to 12 adding up
 * to the computed total taxable income, and the 115BAC line both ways.
 *
 * <p>The worked year: taxable earnings 12,00,000 and employer NPS 60,000 (1(a) 12,60,000); HRA
 * 10(13A) 1,00,000, section 10(14) 2,000 and the transport rebate 19,200 exempt (2(h) 1,21,200);
 * 24(b) interest 2,00,000 (7(a) -2,00,000) and other income 40,000; 80C 1,50,000 of 1,60,000
 * claimed, 80D 25,000, 80GG 60,000 under 10(k), 80CCD(2) the employer's 60,000. Total taxable
 * income 6,83,800; tax 25,000 and cess 1,000.
 */
class Form16PartBTest {

    static BigDecimal rs(String value) {
        return new BigDecimal(value);
    }

    /** The worked year of the class comment, under {@code regime}. */
    static TaxComputationService.Forecast worked(Regime regime) {
        TaxComputationService.Inputs inputs =
                new TaxComputationService.Inputs(
                        regime,
                        PanStatus.VALID,
                        List.of(
                                new TaxComputationService.DeductionView(
                                        "10(13A)", rs("120000.00"), null, rs("100000.00")),
                                new TaxComputationService.DeductionView(
                                        "10(14)", rs("2000.00"), null, rs("2000.00")),
                                new TaxComputationService.DeductionView(
                                        "24(b)", rs("200000.00"), rs("200000.00"), rs("200000.00")),
                                new TaxComputationService.DeductionView(
                                        "80C", rs("160000.00"), rs("150000.00"), rs("150000.00")),
                                new TaxComputationService.DeductionView(
                                        "80D", rs("30000.00"), rs("25000.00"), rs("25000.00")),
                                new TaxComputationService.DeductionView(
                                        "80GG", rs("60000.00"), null, rs("60000.00"))),
                        rs("19200.00"),
                        List.of(
                                new TaxComputationService.IncomeView(
                                        "Interest", rs("40000.00"), rs("0.00"))));
        TaxableEarnings.Year year =
                new TaxableEarnings.Year(
                        List.of(
                                new TaxableEarnings.Month(
                                        "202603",
                                        true,
                                        rs("1200000.00"),
                                        rs("26000.00"),
                                        rs("60000.00"),
                                        List.of())));
        TaxCalculator.Result result =
                new TaxCalculator.Result(
                        rs("683800.00"),
                        rs("25000.00"),
                        rs("0.00"),
                        rs("1000.00"),
                        rs("26000.00"),
                        false,
                        rs("0"));
        return new TaxComputationService.Forecast(
                9L,
                "870001",
                "M",
                "IN_SERVICE",
                "2025-26",
                "ACTUAL",
                inputs,
                Optional.of(year),
                Optional.of(result),
                Optional.of(BigDecimal.ZERO),
                List.of());
    }

    private static Map<String, BigDecimal> byText(List<Form16PartB.Line> lines) {
        return lines.stream()
                .filter(l -> l.amount() != null)
                .collect(
                        Collectors.toMap(
                                Form16PartB.Line::text, Form16PartB.Line::amount, (a, b) -> a));
    }

    @Test
    void SRS_7_30_everyPartBItemCarriesItsValueFromTheComputedYear() {
        Form16PartB.Sheet s = Form16PartB.of(worked(Regime.OLD)).orElseThrow();
        Map<String, BigDecimal> salary = byText(s.salary());
        assertThat(salary)
                .containsEntry(
                        "Salary as per provisions contained in section 17(1)", rs("1260000.00"))
                .containsEntry(
                        "Value of perquisites under section 17(2) (as per Form No. 12BA, wherever"
                                + " applicable)",
                        rs("0.00"))
                .containsEntry("Total", rs("1260000.00"))
                .containsEntry(
                        "Reported total amount of salary received from other employer(s)",
                        rs("0.00"))
                .containsEntry("Travel concession or assistance under section 10(5)", rs("0.00"))
                .containsEntry("House rent allowance under section 10(13A)", rs("100000.00"))
                .containsEntry("Section 10(14)", rs("2000.00"))
                .containsEntry("Transport allowance", rs("19200.00"))
                .containsEntry(
                        "Total amount of any other exemption under section 10", rs("21200.00"))
                .containsEntry(
                        "Total amount of exemption claimed under section 10"
                                + " [2(a)+2(b)+2(c)+2(d)+2(e)+2(g)]",
                        rs("121200.00"))
                .containsEntry(
                        "Total amount of salary received from current employer [1(d)-2(h)]",
                        rs("1138800.00"))
                .containsEntry("Standard deduction under section 16(ia)", rs("0.00"))
                .containsEntry("Tax on employment under section 16(iii)", rs("0.00"))
                .containsEntry(
                        "Income chargeable under the head \"Salaries\" [(3+1(e)-5]",
                        rs("1138800.00"))
                .containsEntry(
                        "Income (or admissible loss) from house property reported by employee"
                                + " offered for TDS",
                        rs("-200000.00"))
                .containsEntry(
                        "Income under the head Other Sources offered for TDS", rs("40000.00"))
                .containsEntry(
                        "Total amount of other income reported by the employee [7(a)+7(b)]",
                        rs("-160000.00"))
                .containsEntry("Gross total income (6+8)", rs("978800.00"));
        Map<String, Form16PartB.ViaLine> via =
                s.chapterVia().stream()
                        .collect(
                                Collectors.toMap(
                                        Form16PartB.ViaLine::item,
                                        Function.identity(),
                                        (a, b) -> a));
        assertThat(via.get("(a)").gross()).isEqualByComparingTo("160000");
        assertThat(via.get("(a)").deductible()).isEqualByComparingTo("150000");
        assertThat(via.get("(a)").qualifying()).isNull();
        assertThat(via.get("(d)").deductible()).isEqualByComparingTo("150000");
        assertThat(via.get("(f)").deductible()).isEqualByComparingTo("60000");
        assertThat(via.get("(g)").deductible()).isEqualByComparingTo("25000");
        assertThat(via.get("(i)").qualifying()).isEqualByComparingTo("0");
        assertThat(via.get("(l)").deductible()).isEqualByComparingTo("60000");
        assertThat(s.chapterVia())
                .anySatisfy(
                        l -> {
                            assertThat(l.text()).isEqualTo("section 80GG");
                            assertThat(l.deductible()).isEqualByComparingTo("60000");
                        });
        Map<String, BigDecimal> tax = byText(s.tax());
        assertThat(tax)
                .containsEntry(
                        "Aggregate of deductible amount under Chapter VI-A"
                                + " [10(d)+10(e)+10(f)+10(g)+10(h)+10(i)+10(j)+10(l)]",
                        rs("295000.00"))
                .containsEntry("Total taxable income (9-11)", rs("683800.00"))
                .containsEntry("Tax on total income", rs("25000.00"))
                .containsEntry("Rebate under section 87A, if applicable", rs("0.00"))
                .containsEntry("Surcharge, wherever applicable", rs("0.00"))
                .containsEntry("Health and education cess", rs("1000.00"))
                .containsEntry("Tax payable (13+15+16-14)", rs("26000.00"))
                .containsEntry("Less: Relief under section 89 (attach details)", rs("0.00"))
                .containsEntry("Net tax payable (17-18)", rs("26000.00"));
        assertThat(s.totalTaxableIncome()).isEqualByComparingTo("683800");
        assertThat(s.netTaxPayable()).isEqualByComparingTo("26000");
    }

    @Test
    void SRS_7_30_items9Less11EqualTheComputedTotalTaxableIncome() {
        Form16PartB.Sheet s = Form16PartB.of(worked(Regime.OLD)).orElseThrow();
        BigDecimal gross = byText(s.salary()).get("Gross total income (6+8)");
        BigDecimal aggregate = s.tax().getFirst().amount();
        assertThat(gross.subtract(aggregate)).isEqualByComparingTo(s.totalTaxableIncome());
    }

    @Test
    void SRS_7_30_the115BacLineReadsYesForTheNewRegimeAndNoForTheOld() {
        assertThat(Form16PartB.of(worked(Regime.NEW)).orElseThrow().opting115Bac()).isTrue();
        assertThat(Form16PartB.of(worked(Regime.OLD)).orElseThrow().opting115Bac()).isFalse();
    }

    @Test
    void SRS_7_19_aYearNotComputedHasNoSheet() {
        TaxComputationService.Forecast f = worked(Regime.OLD);
        TaxComputationService.Forecast none =
                new TaxComputationService.Forecast(
                        f.employeeId(),
                        f.eis(),
                        f.name(),
                        f.employmentStatus(),
                        f.finYear(),
                        "PROJECTED",
                        f.inputs(),
                        f.earnings(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of("TAX-422-REGIME-REQUIRED"));
        assertThat(Form16PartB.of(none)).isEmpty();
    }

    @Test
    void SRS_7_30_aYearWithoutClaimsPrintsEveryItemAsNil() {
        TaxComputationService.Forecast f = worked(Regime.NEW);
        TaxComputationService.Inputs bare =
                new TaxComputationService.Inputs(
                        Regime.NEW, PanStatus.VALID, List.of(), BigDecimal.ZERO, List.of());
        TaxComputationService.Forecast plain =
                new TaxComputationService.Forecast(
                        f.employeeId(),
                        f.eis(),
                        f.name(),
                        f.employmentStatus(),
                        f.finYear(),
                        f.view(),
                        bare,
                        f.earnings(),
                        f.result(),
                        f.yearEndDifference(),
                        List.of());
        Form16PartB.Sheet s = Form16PartB.of(plain).orElseThrow();
        assertThat(byText(s.salary()))
                .containsEntry(
                        "Amount of any other exemption under section 10 [note: Clause wise details"
                                + " of exemption under section 10]",
                        rs("0.00"))
                .containsEntry("Total amount of any other exemption under section 10", rs("0.00"));
        assertThat(s.chapterVia()).noneMatch(l -> l.text().startsWith("section "));
        assertThat(Form16PartB.money(new BigDecimal("5"))).isEqualByComparingTo("5.00");
        assertThat(Form16PartB.money(null)).isEqualByComparingTo("0.00");
    }

    @Test
    void SRS_7_5_aMemberWithNoTaxPrintsEveryTaxItemAsNil() {
        TaxComputationService.Forecast f = worked(Regime.NEW);
        TaxComputationService.Forecast nil =
                new TaxComputationService.Forecast(
                        f.employeeId(),
                        f.eis(),
                        f.name(),
                        f.employmentStatus(),
                        f.finYear(),
                        f.view(),
                        f.inputs(),
                        f.earnings(),
                        Optional.of(
                                new TaxCalculator.Result(
                                        rs("683800.00"),
                                        rs("0.00"),
                                        rs("0.00"),
                                        rs("0.00"),
                                        rs("0.00"),
                                        false,
                                        rs("0"))),
                        Optional.of(BigDecimal.ZERO),
                        List.of());
        Form16PartB.Sheet s = Form16PartB.of(nil).orElseThrow();
        assertThat(byText(s.tax()))
                .containsEntry("Tax on total income", rs("0.00"))
                .containsEntry("Tax payable (13+15+16-14)", rs("0.00"))
                .containsEntry("Net tax payable (17-18)", rs("0.00"));
        assertThat(s.netTaxPayable()).isEqualByComparingTo("0");
    }

    @Test
    void SRS_7_19_aHigherRateForAMissingPanIsCarriedIntoTheTaxOnTotalIncome() {
        TaxComputationService.Forecast f = worked(Regime.OLD);
        TaxComputationService.Forecast noPan =
                new TaxComputationService.Forecast(
                        f.employeeId(),
                        f.eis(),
                        f.name(),
                        f.employmentStatus(),
                        f.finYear(),
                        f.view(),
                        f.inputs(),
                        f.earnings(),
                        Optional.of(
                                new TaxCalculator.Result(
                                        rs("683800.00"),
                                        rs("25000.00"),
                                        rs("0.00"),
                                        rs("1000.00"),
                                        rs("136760.00"),
                                        true,
                                        rs("0"))),
                        Optional.of(BigDecimal.ZERO),
                        List.of());
        Form16PartB.Sheet s = Form16PartB.of(noPan).orElseThrow();
        // 17 = 13 + 15 + 16 - 14 still holds with the section 206AA rate.
        assertThat(byText(s.tax()))
                .containsEntry("Tax on total income", rs("135760.00"))
                .containsEntry("Tax payable (13+15+16-14)", rs("136760.00"));
    }
}
