package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.platform.export.PdfDocument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Form No. 16 Part B (Annexure) as a PDF (OD-144, LIB-1; SRS-7-5, SRS-7-9, SRS-7-23, SRS-7-30;
 * screens C-10 and E-01). The form's own text, items and columns ({@link Form16PartB}: CBDT
 * Notification 36/2019 with the 115BAC line of Notification 15/2021), under the form's heading and
 * its identification block, and closed by the form's Verification. Nothing outside the form is
 * printed: no watermark, note or generation time, so the same certificate always prints the same
 * text. The employee's PAN is printed in full, as the form prescribes (SRS-7-23; PENDING F16-3).
 * What the system does not hold (address of employer and employee, PAN and TAN of the deductor, CIT
 * (TDS), the signatory) is left blank for the authorised signatory, who signs outside the system
 * (FRS Annexure A.8).
 *
 * <p>Part A is never generated (SRS-7-9): the Part A uploaded from TRACES is placed before Part B
 * by {@link #merge}. Whether FCI issues this Part B or the TRACES one is PENDING F16-1.
 */
public final class Form16Pdf {

    private Form16Pdf() {}

    /** Part B alone. */
    public static byte[] of(Form16Service.PartBDocument document) {
        Form16Service.CertificateView c = document.certificate();
        Form16PartB.Sheet s = document.sheet();
        int start = Integer.parseInt(c.finYear().substring(0, 4));
        PdfDocument pdf = PdfDocument.portrait("Form No. 16 Part B " + c.finYear(), null);
        pdf.identify("form16|" + c.id() + "|" + c.partBGeneratedAt());
        pdf.heading("FORM NO. 16");
        pdf.subtitle("[See rule 31(1)(a)]");
        pdf.subtitle("PART B");
        pdf.subtitle(
                "Certificate under section 203 of the Income-tax Act, 1961 for tax deducted at"
                    + " source on salary paid to an employee under section 192 or pension/interest"
                    + " income of specified senior citizen under section 194P");
        pdf.table(new String[] {"", ""}, new float[] {3f, 4f}, new boolean[] {false, false});
        String[][] header = {
            {
                "Name and address of the Employer/Specified Bank",
                document.employer() == null
                        ? "Food Corporation of India"
                        : "Food Corporation of India, " + document.employer()
            },
            {"Name and address of the Employee/Specified senior citizen", c.name()},
            {"PAN of the Deductor", ""},
            {"TAN of the Deductor", ""},
            {"PAN of the Employee/Specified senior citizen", document.pan()},
            {"Employee Reference No. provided by the Employer (If available)", c.eis()},
            {"CIT (TDS)", ""},
            {"Assessment Year", (start + 1) + "-" + String.valueOf(start + 2).substring(2)},
            {"Period with the Employer", "From 01-04-" + start + " To 31-03-" + (start + 1)}
        };
        for (String[] line : header) {
            pdf.tableRow(false, line[0], line[1]);
        }
        pdf.band("PART B (Annexure)");
        pdf.subtitle("Details of Salary Paid and any other income and tax deducted");
        pdf.table(
                new String[] {"", "Whether opting for taxation u/s 115BAC?", "Yes/No"},
                new float[] {0.6f, 6.4f, 1.6f},
                new boolean[] {false, false, true});
        pdf.tableRow(false, "", "", s.opting115Bac() ? "Yes" : "No");
        pdf.table(
                new String[] {"", "", "Rs."},
                new float[] {0.6f, 6.4f, 1.6f},
                new boolean[] {false, false, true});
        for (Form16PartB.Line line : s.salary()) {
            pdf.tableRow(false, line.item(), line.text(), shown(line.amount()));
        }
        pdf.tableRow(true, "10.", "Deductions under Chapter VI-A", "");
        pdf.table(
                new String[] {"", "", "Gross Amount", "Qualifying Amount", "Deductible Amount"},
                new float[] {0.6f, 4.4f, 1.2f, 1.2f, 1.2f},
                new boolean[] {false, false, true, true, true});
        for (Form16PartB.ViaLine line : s.chapterVia()) {
            pdf.tableRow(
                    false,
                    line.item(),
                    line.text(),
                    shown(line.gross()),
                    shown(line.qualifying()),
                    shown(line.deductible()));
        }
        pdf.table(
                new String[] {"", "", "Rs."},
                new float[] {0.6f, 6.4f, 1.6f},
                new boolean[] {false, false, true});
        for (Form16PartB.Line line : s.tax()) {
            pdf.tableRow(false, line.item(), line.text(), shown(line.amount()));
        }
        pdf.band("Verification");
        pdf.note(
                "I, ............................, son/daughter of ............................"
                        + " working in the capacity of ............................ (designation)"
                        + " do hereby certify that the information given above is true, complete"
                        + " and correct and is based on the books of account, documents, TDS"
                        + " statements, and other available records.");
        pdf.pairs(
                "Place",
                "",
                "(Signature of person responsible for deduction of tax)",
                "",
                "Date",
                "",
                "Full Name",
                "");
        return pdf.toBytes();
    }

    private static String shown(BigDecimal amount) {
        return amount == null ? "" : PdfDocument.amount(amount);
    }

    /** One certificate file and what went into it. */
    public record Merged(byte[] pdf, int pages, boolean partAIncluded) {}

    /**
     * SRS-7-9: Part A (as received, when it is a PDF) first, then Part B. A Part A that is not a
     * PDF (an image upload) cannot be merged page by page: the Part B alone is returned and the
     * Part A stays downloadable on its own.
     */
    public static Merged merge(byte[] partA, byte[] partB) {
        try (PDDocument b = Loader.loadPDF(partB)) {
            if (partA == null || !isPdf(partA)) {
                return new Merged(partB, b.getNumberOfPages(), false);
            }
            try (PDDocument a = Loader.loadPDF(partA)) {
                PDFMergerUtility merger = new PDFMergerUtility();
                merger.appendDocument(a, b);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                a.save(out);
                return new Merged(out.toByteArray(), a.getNumberOfPages(), true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final java.util.regex.Pattern PAN =
            java.util.regex.Pattern.compile("(?<![A-Z0-9])[A-Z]{5}[0-9]{4}[A-Z](?![A-Z0-9])");

    /**
     * The PANs a Part A names in its text (the employee's and the deductor's on a TRACES Part A);
     * empty for a file that is not a PDF or whose text cannot be read (a scan).
     */
    static java.util.Set<String> pansIn(byte[] partA) {
        if (partA == null || !isPdf(partA)) {
            return java.util.Set.of();
        }
        try (PDDocument d = Loader.loadPDF(partA)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(d);
            java.util.Set<String> found = new java.util.TreeSet<>();
            java.util.regex.Matcher m = PAN.matcher(text);
            while (m.find()) {
                found.add(m.group());
            }
            return found;
        } catch (IOException unreadable) {
            return java.util.Set.of();
        }
    }

    static boolean isPdf(byte[] bytes) {
        return bytes.length >= 5
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F'
                && bytes[4] == '-';
    }
}
