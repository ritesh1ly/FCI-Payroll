package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.platform.export.PdfDocument;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The payslip as a PDF (OD-144, LIB-1; SRS-1-10, SRS-17-1, SRS-17-7, SRS-18-23; wireframe v6.1
 * {@code docPayslip}: O-01 and E-01). Exactly the figures of {@link PayslipService.Payslip}, which
 * already masks PAN and bank account (R-PII); the writer adds nothing. A month before the lock is
 * marked PROVISIONAL (SRS-18-23). One payslip per page; a bulk file has one page per member.
 *
 * <p>OPEN-QUESTION (PENDING 16-payslip-delivery): the prescribed layout is a report definition
 * carried forward from the current payslip (FRS §6.21.4) and is not supplied; the wireframe's lines
 * that {@code Payslip} does not hold (CPF code, days paid, CPF balance, loan outstanding, projected
 * taxable income, TDS to date) are not printed.
 */
public final class PayslipPdf {

    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH);

    private PayslipPdf() {}

    /** One payslip. */
    public static byte[] of(PayslipService.Payslip slip) {
        return of(List.of(slip));
    }

    /** One page per payslip, in the order given (the O-01 bulk PDF). */
    public static byte[] of(List<PayslipService.Payslip> slips) {
        String title = slips.size() == 1 ? "Pay Slip " + slips.getFirst().yymm() : "Pay Slips";
        PdfDocument pdf = PdfDocument.portrait(title, FOOTER);
        // SRS O-01 l.3392: a fixed identifier per member and period, so the bytes repeat.
        pdf.identify(
                "payslip|"
                        + String.join(
                                ",", slips.stream().map(x -> x.eis() + "/" + x.yymm()).toList()));
        boolean first = true;
        for (PayslipService.Payslip slip : slips) {
            append(pdf, slip, first);
            first = false;
        }
        return pdf.toBytes();
    }

    /** The footer of every payslip page. */
    public static final String FOOTER =
            "FCILekha - produced from the committed run only; PAN and bank account masked";

    /**
     * Writes one payslip into {@code pdf} (a streamed bulk file): on its own page, after a page
     * break unless it is the first; a payslip of many lines runs on to further pages.
     */
    public static void append(PdfDocument pdf, PayslipService.Payslip slip, boolean first) {
        if (!first) {
            pdf.pageBreak();
        }
        page(pdf, slip);
    }

    /** The month as printed: "JULY 2026". */
    static String month(String yymm) {
        return YearMonth.parse(yymm, DateTimeFormatter.ofPattern("uuuuMM"))
                .format(MONTH)
                .toUpperCase(Locale.ENGLISH);
    }

    private static void page(PdfDocument pdf, PayslipService.Payslip s) {
        pdf.heading("FOOD CORPORATION OF INDIA");
        pdf.subtitle(
                "Pay Slip for the month of "
                        + month(s.yymm())
                        + (s.provisional() ? " (PROVISIONAL)" : "")
                        + (s.office() == null ? "" : " - " + s.office()));
        pdf.pairs(
                "EIS No.",
                s.eis(),
                "Name",
                s.name(),
                "Designation",
                s.designation(),
                "Pay pattern",
                s.payPattern(),
                "PAN",
                s.panMasked(),
                "Bank A/c",
                s.bankAccountMasked(),
                "PRAN",
                s.pran(),
                "Layout",
                "STAFF".equals(s.layoutVariant()) ? "Staff" : "Labour");
        pdf.band("EARNINGS AND DEDUCTIONS");
        pdf.table(
                new String[] {"Earnings", "Amount (Rs.)", "Deductions", "Amount (Rs.)"},
                new float[] {3f, 1.3f, 3f, 1.3f},
                new boolean[] {false, true, false, true});
        int lines = Math.max(s.earnings().size(), s.deductions().size());
        for (int i = 0; i < lines; i++) {
            PayslipService.Line e = i < s.earnings().size() ? s.earnings().get(i) : null;
            PayslipService.Line d = i < s.deductions().size() ? s.deductions().get(i) : null;
            pdf.tableRow(
                    false,
                    e == null ? "" : e.name(),
                    e == null ? "" : PdfDocument.amount(e.amount()),
                    d == null ? "" : d.name(),
                    d == null ? "" : PdfDocument.amount(d.amount()));
        }
        pdf.tableRow(
                true,
                "Gross Earnings",
                PdfDocument.amount(s.gross()),
                "Total Deductions",
                PdfDocument.amount(s.totalDeductions()));
        pdf.band(
                "NET PAY: Rs. "
                        + PdfDocument.amount(s.net())
                        + "  ("
                        + PdfDocument.rupeesInWords(s.net())
                        + ")");
        pdf.note(
                s.provisional()
                        ? "Provisional - the month is not yet locked. The final payslip is"
                                + " published to eSeva after the month-end lock."
                        : "Produced from the locked payroll of the month.");
    }
}
