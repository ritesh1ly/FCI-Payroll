package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The lines of Form No. 16 Part B (Annexure) from the member's actual year (SRS-7-5, SRS-7-30:
 * "exactly in the prescribed format"; owner 2026-11-09). The form is the Annexure substituted in
 * Appendix II of the Income-tax Rules, 1962 by CBDT Notification 36/2019 (12-04-2019), with the
 * section 115BAC line inserted by Notification 15/2021 (11-03-2021; structure checked against the
 * Income-tax (26th Amendment) Rules 2021, Notification 99/2021, which reproduces Annexure-I). Item
 * numbers and wording follow the form; every value comes from the computation of the year ({@link
 * TaxComputationService.Forecast}, the same one Part B is generated from), so items 1 to 12 add up
 * to the computed total taxable income and items 13 to 19 to the computed tax.
 *
 * <p>Mapping (nothing invented; what the computation does not hold is 0.00, as the form prints a
 * nil item): 1(a) the taxable earnings of the year plus the employer's NPS contribution (SRS-7-27:
 * income with its matching 80CCD(2) deduction); 1(b), 1(c) and 1(e) 0.00 (Form 12BA perquisites and
 * a previous employer's salary are not held); 2 the allowed section 10 claims by clause, the
 * transport rebate among "any other exemption"; 4 the allowed section 16 claims (none is computed
 * today); 7(a) the allowed 24(b) interest as a loss from house property; 7(b) the approved other
 * income; 10 the allowed Chapter VI-A claims by section, any other section under 10(k); 13 the tax
 * before surcharge and cess; 14 and 18 0.00 (rebate u/s 87A and relief u/s 89 are not computed
 * separately). OPEN-QUESTION (PENDING LIB-1-F16): FCI to confirm these mappings and whether the
 * 2023 amendment of the 115BAC line ("opting out of taxation u/s 115BAC(1A)") applies to the years
 * printed.
 */
public final class Form16PartB {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /** One numbered line: {@code amount} null for a heading line without a figure. */
    public record Line(String item, String text, BigDecimal amount) {}

    /** One Chapter VI-A line: gross, qualifying (only where the form has it) and deductible. */
    public record ViaLine(
            String item,
            String text,
            BigDecimal gross,
            BigDecimal qualifying,
            BigDecimal deductible) {}

    /**
     * The form's figures: the 115BAC answer, items 1 to 9, item 10 (Chapter VI-A), items 11 to 19.
     */
    public record Sheet(
            boolean opting115Bac,
            List<Line> salary,
            List<ViaLine> chapterVia,
            List<Line> tax,
            BigDecimal totalTaxableIncome,
            BigDecimal netTaxPayable) {}

    private Form16PartB() {}

    /** The sheet of an actual year with its computation; empty when the year was not computed. */
    public static Optional<Sheet> of(TaxComputationService.Forecast f) {
        if (f.result().isEmpty() || f.earnings().isEmpty()) {
            return Optional.empty();
        }
        var in = f.inputs();
        var y = f.earnings().get();
        var r = f.result().get();
        BigDecimal nps = y.employerNps();

        // Section 10 exemptions by clause (item 2).
        BigDecimal travel = allowed(in, "10(5)");
        BigDecimal gratuity = allowed(in, "10(10)");
        BigDecimal pension = allowed(in, "10(10A)");
        BigDecimal leave = allowed(in, "10(10AA)");
        BigDecimal hra = allowed(in, "10(13A)");
        List<Line> otherExemptions = new ArrayList<>();
        BigDecimal other10 = BigDecimal.ZERO;
        for (var d : in.deductions()) {
            String code = d.sectionCode();
            if (code.startsWith("10(") && !NAMED_10.contains(code)) {
                otherExemptions.add(new Line("", "Section " + code, money(d.allowed())));
                other10 = other10.add(d.allowed());
            }
        }
        if (in.transportRebate() != null && in.transportRebate().signum() != 0) {
            otherExemptions.add(new Line("", "Transport allowance", money(in.transportRebate())));
            other10 = other10.add(in.transportRebate());
        }
        BigDecimal exempt = travel.add(gratuity).add(pension).add(leave).add(hra).add(other10);

        BigDecimal salary17 = y.gross().add(nps);
        BigDecimal total1 = salary17;
        BigDecimal current = total1.subtract(exempt);
        BigDecimal standard = allowed(in, "16(ia)");
        BigDecimal entertainment = allowed(in, "16(ii)");
        BigDecimal employmentTax = allowed(in, "16(iii)");
        BigDecimal sec16 = standard.add(entertainment).add(employmentTax);
        BigDecimal salaries = current.subtract(sec16);
        BigDecimal houseProperty = allowed(in, "24(b)").negate();
        BigDecimal otherSources = in.totalOtherIncome();
        BigDecimal otherIncome = houseProperty.add(otherSources);
        BigDecimal grossTotal = salaries.add(otherIncome);

        List<Line> salaryLines = new ArrayList<>();
        salaryLines.add(new Line("1.", "Gross Salary", null));
        salaryLines.add(
                new Line(
                        "(a)",
                        "Salary as per provisions contained in section 17(1)",
                        money(salary17)));
        salaryLines.add(
                new Line(
                        "(b)",
                        "Value of perquisites under section 17(2) (as per Form No. 12BA, wherever"
                                + " applicable)",
                        ZERO));
        salaryLines.add(
                new Line(
                        "(c)",
                        "Profits in lieu of salary under section 17(3) (as per Form No. 12BA,"
                                + " wherever applicable)",
                        ZERO));
        salaryLines.add(new Line("(d)", "Total", money(total1)));
        salaryLines.add(
                new Line(
                        "(e)",
                        "Reported total amount of salary received from other employer(s)",
                        ZERO));
        salaryLines.add(
                new Line("2.", "Less: Allowances to the extent exempt under section 10", null));
        salaryLines.add(
                new Line(
                        "(a)",
                        "Travel concession or assistance under section 10(5)",
                        money(travel)));
        salaryLines.add(
                new Line(
                        "(b)",
                        "Death-cum-retirement gratuity under section 10(10)",
                        money(gratuity)));
        salaryLines.add(
                new Line("(c)", "Commuted value of pension under section 10(10A)", money(pension)));
        salaryLines.add(
                new Line(
                        "(d)",
                        "Cash equivalent of leave salary encashment under section 10(10AA)",
                        money(leave)));
        salaryLines.add(new Line("(e)", "House rent allowance under section 10(13A)", money(hra)));
        salaryLines.add(
                new Line(
                        "(f)",
                        "Amount of any other exemption under section 10 [note: Clause wise details"
                                + " of exemption under section 10]",
                        otherExemptions.isEmpty() ? ZERO : null));
        salaryLines.addAll(otherExemptions);
        salaryLines.add(
                new Line(
                        "(g)",
                        "Total amount of any other exemption under section 10",
                        money(other10)));
        salaryLines.add(
                new Line(
                        "(h)",
                        "Total amount of exemption claimed under section 10"
                                + " [2(a)+2(b)+2(c)+2(d)+2(e)+2(g)]",
                        money(exempt)));
        salaryLines.add(
                new Line(
                        "3.",
                        "Total amount of salary received from current employer [1(d)-2(h)]",
                        money(current)));
        salaryLines.add(new Line("4.", "Less: Deductions under section 16", null));
        salaryLines.add(
                new Line("(a)", "Standard deduction under section 16(ia)", money(standard)));
        salaryLines.add(
                new Line(
                        "(b)",
                        "Entertainment allowance under section 16(ii)",
                        money(entertainment)));
        salaryLines.add(
                new Line("(c)", "Tax on employment under section 16(iii)", money(employmentTax)));
        salaryLines.add(
                new Line(
                        "5.",
                        "Total amount of deductions under section 16 [4(a)+4(b)+4(c)]",
                        money(sec16)));
        salaryLines.add(
                new Line(
                        "6.",
                        "Income chargeable under the head \"Salaries\" [(3+1(e)-5]",
                        money(salaries)));
        salaryLines.add(
                new Line(
                        "7.",
                        "Add: Any other income reported by the employee under as per section"
                                + " 192 (2B)",
                        null));
        salaryLines.add(
                new Line(
                        "(a)",
                        "Income (or admissible loss) from house property reported by employee"
                                + " offered for TDS",
                        money(houseProperty)));
        salaryLines.add(
                new Line(
                        "(b)",
                        "Income under the head Other Sources offered for TDS",
                        money(otherSources)));
        salaryLines.add(
                new Line(
                        "8.",
                        "Total amount of other income reported by the employee [7(a)+7(b)]",
                        money(otherIncome)));
        salaryLines.add(new Line("9.", "Gross total income (6+8)", money(grossTotal)));

        // Item 10: Chapter VI-A.
        List<ViaLine> via = new ArrayList<>();
        BigDecimal[] c80c = claim(in, "80C");
        BigDecimal[] c80ccc = claim(in, "80CCC");
        BigDecimal[] c80ccd1 = claim(in, "80CCD(1)");
        via.add(
                two(
                        "(a)",
                        "Deduction in respect of life insurance premia, contributions to provident"
                                + " fund etc. under section 80C",
                        c80c));
        via.add(
                two(
                        "(b)",
                        "Deduction in respect of contribution to certain pension funds under"
                                + " section 80CCC",
                        c80ccc));
        via.add(
                two(
                        "(c)",
                        "Deduction in respect of contribution by taxpayer to pension scheme under"
                                + " section 80CCD (1)",
                        c80ccd1));
        BigDecimal[] d = sum(c80c, c80ccc, c80ccd1);
        via.add(two("(d)", "Total deduction under section 80C, 80CCC and 80CCD(1)", d));
        BigDecimal[] e = claim(in, "80CCD(1B)");
        via.add(
                two(
                        "(e)",
                        "Deductions in respect of amount paid/deposited to notified pension scheme"
                                + " under section 80CCD (1B)",
                        e));
        BigDecimal[] employer = claim(in, "80CCD(2)");
        BigDecimal[] fEmployer = {employer[0].add(nps), employer[1].add(nps)};
        via.add(
                two(
                        "(f)",
                        "Deduction in respect of contribution by Employer to pension scheme under"
                                + " section 80CCD (2)",
                        fEmployer));
        BigDecimal[] g = claim(in, "80D");
        via.add(two("(g)", "Deduction in respect of health insurance premia under section 80D", g));
        BigDecimal[] h = claim(in, "80E");
        via.add(
                two(
                        "(h)",
                        "Deduction in respect of interest on loan taken for higher education under"
                                + " section 80E",
                        h));
        BigDecimal[] i = claim(in, "80G");
        via.add(
                new ViaLine(
                        "(i)",
                        "Total Deduction in respect of donations to certain funds, charitable"
                                + " institutions, etc. under section 80G",
                        money(i[0]),
                        money(i[1]),
                        money(i[1])));
        BigDecimal[] j = claim(in, "80TTA");
        via.add(
                two(
                        "(j)",
                        "Deduction in respect of interest on deposits in savings account under"
                                + " section 80TTA",
                        j));
        via.add(
                new ViaLine(
                        "(k)",
                        "Amount deductible under any other provision(s) of Chapter VI-A",
                        null,
                        null,
                        null));
        BigDecimal otherVia = BigDecimal.ZERO;
        for (var x : in.deductions()) {
            String code = x.sectionCode();
            if (!code.startsWith("10(")
                    && !code.startsWith("16(")
                    && !"24(b)".equals(code)
                    && !NAMED_VIA.contains(code)) {
                via.add(
                        new ViaLine(
                                "",
                                "section " + code,
                                money(x.claimed()),
                                money(x.allowed()),
                                money(x.allowed())));
                otherVia = otherVia.add(x.allowed());
            }
        }
        via.add(
                new ViaLine(
                        "(l)",
                        "Total of amount deductible under any other provision(s) of Chapter VI-A",
                        null,
                        null,
                        money(otherVia)));
        BigDecimal aggregate =
                d[1].add(e[1])
                        .add(fEmployer[1])
                        .add(g[1])
                        .add(h[1])
                        .add(i[1])
                        .add(j[1])
                        .add(otherVia);

        BigDecimal surcharge = r.surcharge();
        BigDecimal cess = r.cess();
        BigDecimal taxOnTotal = r.annualTax().subtract(surcharge).subtract(cess);
        BigDecimal rebate = ZERO;
        BigDecimal payable = taxOnTotal.add(surcharge).add(cess).subtract(rebate);
        BigDecimal relief = ZERO;
        BigDecimal net = payable.subtract(relief);
        List<Line> tax = new ArrayList<>();
        tax.add(
                new Line(
                        "11.",
                        "Aggregate of deductible amount under Chapter VI-A"
                                + " [10(d)+10(e)+10(f)+10(g)+10(h)+10(i)+10(j)+10(l)]",
                        money(aggregate)));
        tax.add(new Line("12.", "Total taxable income (9-11)", money(r.taxableIncome())));
        tax.add(new Line("13.", "Tax on total income", money(taxOnTotal)));
        tax.add(new Line("14.", "Rebate under section 87A, if applicable", rebate));
        tax.add(new Line("15.", "Surcharge, wherever applicable", money(surcharge)));
        tax.add(new Line("16.", "Health and education cess", money(cess)));
        tax.add(new Line("17.", "Tax payable (13+15+16-14)", money(payable)));
        tax.add(new Line("18.", "Less: Relief under section 89 (attach details)", relief));
        tax.add(new Line("19.", "Net tax payable (17-18)", money(net)));
        return Optional.of(
                new Sheet(
                        in.regime() == Regime.NEW,
                        salaryLines,
                        via,
                        tax,
                        money(r.taxableIncome()),
                        money(net)));
    }

    private static final java.util.Set<String> NAMED_10 =
            java.util.Set.of("10(5)", "10(10)", "10(10A)", "10(10AA)", "10(13A)");
    private static final java.util.Set<String> NAMED_VIA =
            java.util.Set.of(
                    "80C",
                    "80CCC",
                    "80CCD(1)",
                    "80CCD(1B)",
                    "80CCD(2)",
                    "80D",
                    "80E",
                    "80G",
                    "80TTA");

    /** The allowed amount of a section (0 when not claimed). */
    private static BigDecimal allowed(TaxComputationService.Inputs in, String code) {
        return claim(in, code)[1];
    }

    /** {claimed, allowed} of a section (0 when not claimed). */
    private static BigDecimal[] claim(TaxComputationService.Inputs in, String code) {
        BigDecimal claimed = BigDecimal.ZERO;
        BigDecimal allowed = BigDecimal.ZERO;
        for (var x : in.deductions()) {
            if (code.equals(x.sectionCode())) {
                claimed = claimed.add(x.claimed() == null ? BigDecimal.ZERO : x.claimed());
                allowed = allowed.add(x.allowed() == null ? BigDecimal.ZERO : x.allowed());
            }
        }
        return new BigDecimal[] {claimed, allowed};
    }

    private static BigDecimal[] sum(BigDecimal[]... claims) {
        BigDecimal claimed = BigDecimal.ZERO;
        BigDecimal allowed = BigDecimal.ZERO;
        for (BigDecimal[] c : claims) {
            claimed = claimed.add(c[0]);
            allowed = allowed.add(c[1]);
        }
        return new BigDecimal[] {claimed, allowed};
    }

    /** A line of the form's two columns (gross and deductible; no qualifying column). */
    private static ViaLine two(String item, String text, BigDecimal[] c) {
        return new ViaLine(item, text, money(c[0]), null, money(c[1]));
    }

    /** Two decimals, as the stored amounts (an amount already in paise keeps its value). */
    static BigDecimal money(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        return v.scale() < 2 ? v.setScale(2) : v;
    }
}
