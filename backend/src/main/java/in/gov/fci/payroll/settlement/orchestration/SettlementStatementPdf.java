package in.gov.fci.payroll.settlement.orchestration;

import in.gov.fci.payroll.platform.export.PdfDocument;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * The settlement statement of one case as a PDF (OD-144, LIB-1; SRS-10-12, SRS-10-13, SRS-10-29;
 * screen O-05, its Statement dialog): the rows and figures the screen shows (frontend {@code
 * settlementRules.statementRows}): pending salary and arrears, leave encashment, ex-gratia, the
 * payroll dues less the recoveries adjusted against them, the payroll net payable, gratuity less a
 * recovery against it, and the CPF final payment for information. No bank account, PAN or other
 * restricted field is printed (R-PII). A component not computed is a dash.
 *
 * <p>OPEN-QUESTION (PENDING O05-14): FCI's settlement statement layout is not supplied; this is the
 * on-screen statement. The Final Settlement Report (SRS-10-32) stays PENDING O05-19.
 */
public final class SettlementStatementPdf {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-uuuu");

    private SettlementStatementPdf() {}

    public static byte[] of(SettlementViews.CaseDetail d) {
        SettlementViews.CaseRow c = d.summary();
        PdfDocument pdf =
                PdfDocument.portrait(
                        "Settlement statement " + c.caseReference(),
                        "FCILekha - settlement statement " + c.caseReference());
        pdf.identify("settlement|" + c.caseId());
        pdf.heading("FOOD CORPORATION OF INDIA");
        pdf.subtitle("Final settlement statement - case " + c.caseReference());
        pdf.pairs(
                "EIS No.",
                c.eis(),
                "Name",
                c.name(),
                "Office",
                c.office(),
                "Payroll type",
                c.payrollType(),
                "Separation",
                c.separationType(),
                "Separation date",
                c.separationDate() == null ? null : DAY.format(c.separationDate()),
                "Stage",
                c.stage(),
                "Status",
                c.status());
        pdf.band("SETTLEMENT COMPONENTS");
        pdf.table(
                new String[] {
                    "Component", "Amount (Rs.)", "Recovery against (Rs.)", "Paid through"
                },
                new float[] {3.2f, 1.4f, 1.6f, 2.4f},
                new boolean[] {false, true, true, false});
        BigDecimal fromPayroll =
                orZero(d.totalRecoveries()).subtract(orZero(d.recoveredFromGratuity()));
        BigDecimal pending =
                d.pendingSalary() == null && d.arrears() == null
                        ? null
                        : orZero(d.pendingSalary()).add(orZero(d.arrears()));
        pdf.tableRow(
                false,
                "Pending salary + arrears",
                PdfDocument.amount(pending),
                "-",
                "Payroll net settlement");
        pdf.tableRow(
                false,
                "Leave encashment (EL + HPL)",
                PdfDocument.amount(d.leaveEncashment()),
                "-",
                "Payroll net settlement");
        pdf.tableRow(
                false,
                "Ex-gratia",
                PdfDocument.amount(d.exGratia()),
                "-",
                "Payroll net settlement");
        pdf.tableRow(
                true,
                "Payroll dues",
                PdfDocument.amount(c.payrollDues()),
                fromPayroll.signum() > 0 ? "(" + PdfDocument.amount(fromPayroll) + ")" : "-",
                "");
        pdf.tableRow(
                true,
                "Payroll net payable",
                PdfDocument.amount(c.netPayable()),
                "",
                "Payroll net settlement");
        BigDecimal fromGratuity = orZero(d.recoveredFromGratuity());
        pdf.tableRow(
                false,
                "Gratuity",
                PdfDocument.amount(d.gratuity()),
                fromGratuity.signum() > 0 ? "- " + PdfDocument.amount(fromGratuity) : "-",
                "Gratuity payment advice");
        pdf.tableRow(
                false,
                "CPF final payment (information only)",
                d.cpfSanctionNumber() != null
                        ? "Linked - " + d.cpfSanctionNumber()
                        : PdfDocument.amount(d.cpfAmount()),
                "-",
                "CPF final payment on sanction (CPF module)");
        if (!d.recoveries().isEmpty()) {
            pdf.band("RECOVERIES");
            pdf.table(
                    new String[] {"Type", "Reference", "Period", "Amount (Rs.)", "Recovered from"},
                    new float[] {2f, 2.4f, 1f, 1.4f, 1.6f},
                    new boolean[] {false, false, false, true, false});
            for (SettlementViews.Recovery r : d.recoveries()) {
                pdf.tableRow(
                        false,
                        r.type(),
                        r.reference() == null ? "" : r.reference().replaceFirst("^\\d+ ", ""),
                        r.period(),
                        PdfDocument.amount(r.amount()),
                        r.source());
            }
        }
        if (d.netRecovery()) {
            pdf.note(
                    "The net payable is negative: the balance is recovered from the member (net"
                            + " recovery).");
        }
        if (d.benefitsHeld()) {
            pdf.note("Benefits are held for this case.");
        }
        return pdf.toBytes();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
