package in.gov.fci.payroll.platform.export;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** Test helper (OD-144): what a PDF says and how many pages it has, as PDFBox reads it. */
public final class PdfText {

    private PdfText() {}

    /**
     * The text of a PDF, page by page, every run of white space as one space (a cell that wraps in
     * its column reads as one phrase).
     */
    public static String text(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d).replaceAll("\\s+", " ");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The pages of a PDF. */
    public static int pages(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return d.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
