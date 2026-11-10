package in.gov.fci.payroll.platform.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** OD-144: the PDF writer (amounts, words, the printable text) and the PDF list export. */
class PdfDocumentTest {

    private static String text(byte[] pdf) {
        return PdfText.text(pdf);
    }

    private static int pages(byte[] pdf) {
        return PdfText.pages(pdf);
    }

    @Test
    void OD_144_amountsAreGroupedTheIndianWayWithTwoDecimalsAsStored() {
        assertThat(PdfDocument.amount(new BigDecimal("127350"))).isEqualTo("1,27,350.00");
        assertThat(PdfDocument.amount(new BigDecimal("12345678.5"))).isEqualTo("1,23,45,678.50");
        assertThat(PdfDocument.amount(new BigDecimal("999.99"))).isEqualTo("999.99");
        assertThat(PdfDocument.amount(new BigDecimal("1000"))).isEqualTo("1,000.00");
        assertThat(PdfDocument.amount(new BigDecimal("-4790.00"))).isEqualTo("-4,790.00");
        assertThat(PdfDocument.amount(BigDecimal.ZERO)).isEqualTo("0.00");
        assertThat(PdfDocument.amount(null)).isEqualTo("-");
        // A longer scale is shown in full, never rounded here (R-MONEY).
        assertThat(PdfDocument.amount(new BigDecimal("10.125"))).isEqualTo("10.125");
    }

    @Test
    void OD_144_theNetPayInWordsFollowsTheIndianSystem() {
        assertThat(PdfDocument.rupeesInWords(new BigDecimal("91963")))
                .isEqualTo("Rupees Ninety One Thousand Nine Hundred and Sixty Three only");
        assertThat(PdfDocument.rupeesInWords(new BigDecimal("180363.00")))
                .isEqualTo("Rupees One Lakh Eighty Thousand Three Hundred and Sixty Three only");
        assertThat(PdfDocument.rupeesInWords(new BigDecimal("12000000.50")))
                .isEqualTo("Rupees One Crore Twenty Lakh and Fifty Paise only");
        assertThat(PdfDocument.rupeesInWords(new BigDecimal("100")))
                .isEqualTo("Rupees One Hundred only");
        assertThat(PdfDocument.rupeesInWords(new BigDecimal("15")))
                .isEqualTo("Rupees Fifteen only");
        assertThat(PdfDocument.rupeesInWords(BigDecimal.ZERO)).isEqualTo("Rupees Zero only");
        assertThat(PdfDocument.rupeesInWords(new BigDecimal("-20")))
                .isEqualTo("Minus Rupees Twenty only");
        assertThat(PdfDocument.rupeesInWords(null)).isEqualTo("-");
    }

    @Test
    void R_ASCII_theTextIsPrintableByTheStandardFontsAndHindiIsMarkedOnce() {
        assertThat(PdfDocument.safe("Pay — ₹ 5 • “x” ‘y’")).isEqualTo("Pay - Rs. 5 * \"x\" 'y'");
        assertThat(PdfDocument.safe("Name नाम कुल end")).isEqualTo("Name [Hindi] end");
        assertThat(PdfDocument.safe("a\tb\nc\u0007d中")).isEqualTo("a b cd?");
        assertThat(PdfDocument.safe(null)).isEmpty();
        assertThat(PdfDocument.safe("café")).isEqualTo("café");
    }

    @Test
    void OD_144_aDocumentWrapsLongCellsRepeatsTheHeadingAndNumbersItsPages() {
        PdfDocument pdf = PdfDocument.portrait("T", "Footer line");
        pdf.heading("HEADING").subtitle("Sub").pairs("Label", "Value", "Other", null);
        pdf.band("BAND");
        pdf.table(new String[] {"Col A", "Col B"}, new float[] {1, 1}, new boolean[] {false, true});
        for (int i = 0; i < 120; i++) {
            pdf.tableRow(
                    false,
                    "Row " + i + " " + "word ".repeat(30),
                    PdfDocument.amount(BigDecimal.TEN));
        }
        pdf.note("A note");
        byte[] bytes = pdf.toBytes();
        String text = text(bytes);
        int pages = pages(bytes);
        assertThat(pages).isGreaterThan(1);
        assertThat(text).contains("HEADING", "Sub", "Label", "Value", "BAND", "Row 119", "A note");
        assertThat(text).contains("Page 1 of " + pages, "Footer line");
        // The heading of the table is printed again on each page it runs over.
        assertThat(text.split("Col A", -1).length - 1).isEqualTo(pages);
    }

    @Test
    void OD_144_aPrescribedFormIsPrintedWithoutAnyLineOfOurs() {
        byte[] bytes = PdfDocument.portrait("Form", null).heading("FORM").toBytes();
        assertThat(text(bytes)).doesNotContain("Page 1");
    }

    @Test
    void SRS_O01_l3392_theSameContentGivesTheSameText() {
        java.util.function.Supplier<byte[]> one =
                () -> PdfDocument.portrait("T", "F").heading("H").pairs("A", "1").toBytes();
        assertThat(text(one.get())).isEqualTo(text(one.get()));
    }

    @Test
    void tableRowBeforeTableIsAProgrammingError() {
        PdfDocument pdf = PdfDocument.portrait("T", null);
        assertThatThrownBy(() -> pdf.tableRow(false, "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void SRS_G_13_aListExportsAsPdfWithItsReportHeaderAndColumns() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportSink sink = ExportFormat.PDF.open(out, "Off-cycle cases")) {
            sink.title("Off-cycle cases", "Period 2026-10", "Scope DO Delhi");
            sink.header("EIS", "Amount", "Date", "Note");
            sink.row("104882", new BigDecimal("127350.00"), LocalDate.of(2026, 10, 31), null);
            sink.row("104883", 7L);
            assertThat(sink.rows()).isEqualTo(2);
            assertThatThrownBy(() -> sink.title("late")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> sink.header("again"))
                    .isInstanceOf(IllegalStateException.class);
        }
        String text = text(out.toByteArray());
        assertThat(text)
                .contains("Off-cycle cases", "Period 2026-10", "Scope DO Delhi", "EIS", "Amount")
                .contains("104882", "1,27,350.00", "31-10-2026", "104883", "7");
        assertThat(ExportFormat.PDF.extension()).isEqualTo("pdf");
        assertThat(ExportFormat.PDF.mediaType()).isEqualTo("application/pdf");
    }

    @Test
    void SRS_G_13_aRowBeforeTheHeaderIsRefusedAndAnEmptyListIsStillAPage() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfSink sink = new PdfSink(out, "Empty");
        assertThatThrownBy(() -> sink.row("x")).isInstanceOf(IllegalStateException.class);
        sink.close();
        assertThat(text(out.toByteArray())).contains("No rows.");
        assertThat(PdfSink.cell(java.time.Instant.parse("2026-11-09T05:00:00Z")))
                .isEqualTo("2026-11-09T05:00:00Z");
    }
}
