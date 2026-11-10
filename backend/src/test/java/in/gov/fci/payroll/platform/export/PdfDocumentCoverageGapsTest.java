package in.gov.fci.payroll.platform.export;

import static in.gov.fci.payroll.platform.export.PdfText.pages;
import static in.gov.fci.payroll.platform.export.PdfText.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * OD-144 generated documents at their edges (R-ASCII, SRS-G-13): a missing identification key is
 * the empty key (same bytes), a different key different bytes; the page count is known before the
 * document is written; a closed document cannot be written; a short or empty row is padded with
 * blanks; a table without titles prints no heading row; a heading that does not fit the rest of the
 * page starts a new one; a word wider than its column is cut by characters, even one character at a
 * time; a Hindi run ends at a character outside the Devanagari block.
 */
class PdfDocumentCoverageGapsTest {

    private static byte[] identified(String key) {
        return PdfDocument.portrait("T", "F").identify(key).heading("H").toBytes();
    }

    @Test
    void OD_144_aMissingKeyIsTheEmptyKeyAndAnotherKeyGivesOtherBytes() {
        assertThat(identified(null)).isEqualTo(identified(""));
        assertThat(identified("104882-202610")).isNotEqualTo(identified(""));
        assertThat(identified("104882-202610")).isEqualTo(identified("104882-202610"));
    }

    @Test
    void OD_144_thePageCountIsKnownBeforeWritingAndAClosedDocumentCannotBeWritten()
            throws Exception {
        PdfDocument pdf = PdfDocument.portrait("T", "F").heading("ONE");
        assertThat(pdf.pages()).isEqualTo(1);
        pdf.pageBreak().heading("TWO");
        assertThat(pdf.pages()).isEqualTo(2);

        pdf.close();

        assertThatThrownBy(pdf::toBytes).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void SRS_G_13_aShortRowIsPaddedAndANullCellIsBlank() {
        PdfDocument pdf = PdfDocument.portrait("T", null);
        pdf.table(new String[] {"Code", "Name", "Amount"}, new float[] {1, 2, 1}, new boolean[3]);
        pdf.tableRow(false, (boolean[]) null, "ONLY");
        pdf.tableRow(true, "A1", null, "10.00");

        String text = text(pdf.toBytes());

        assertThat(text).contains("Code", "Name", "Amount", "ONLY", "A1", "10.00");
        assertThat(text).doesNotContain("null");
    }

    @Test
    void OD_144_aTableWithoutTitlesPrintsNoHeadingRow() {
        PdfDocument titled = PdfDocument.portrait("T", null);
        titled.table(new String[] {"Label", "Value"}, new float[] {1, 1}, new boolean[2]);
        titled.tableRow(false, "Name", "Asha");
        PdfDocument untitled = PdfDocument.portrait("T", null);
        untitled.table(new String[] {"", null}, new float[] {1, 1}, new boolean[2]);
        untitled.tableRow(false, "Name", "Asha");

        String withTitles = text(titled.toBytes());
        assertThat(withTitles).contains("Label", "Asha");
        String plain = text(untitled.toBytes());
        assertThat(plain).contains("Name", "Asha").doesNotContain("Label");
        assertThat(plain.length()).isLessThan(withTitles.length());
    }

    @Test
    void OD_144_aHeadingThatDoesNotFitTheRestOfThePageStartsANewOne() {
        int notes = 0;
        PdfDocument probe = PdfDocument.portrait("T", null);
        while (probe.pages() == 1) {
            probe.note("line " + notes);
            notes++;
        }
        PdfDocument pdf = PdfDocument.portrait("T", null);
        for (int i = 0; i < notes - 1; i++) {
            pdf.note("line " + i);
        }
        assertThat(pdf.pages()).isEqualTo(1);
        String tall = "heading ".repeat(120).strip();

        pdf.table(new String[] {tall, "B"}, new float[] {1, 1}, new boolean[2]);

        assertThat(pdf.pages()).isEqualTo(2);
        byte[] bytes = pdf.toBytes();
        assertThat(pages(bytes)).isEqualTo(2);
        assertThat(text(bytes)).contains("line " + (notes - 2));
    }

    @Test
    void OD_144_aWordWiderThanItsColumnIsCutByCharactersDownToOne() {
        PdfDocument pdf = PdfDocument.portrait("T", null);
        pdf.table(new String[] {"A", "B"}, new float[] {0.002f, 1}, new boolean[2]);
        pdf.tableRow(false, "WW", "short " + "X".repeat(300));

        String text = text(pdf.toBytes());

        assertThat(text.chars().filter(c -> c == 'X').count()).isEqualTo(300);
        assertThat(text.chars().filter(c -> c == 'W').count()).isEqualTo(2);
        assertThat(text).contains("short");
    }

    @Test
    void R_ASCII_aHindiRunEndsAtTheFirstCharacterOutsideDevanagari() {
        assertThat(PdfDocument.safe("क ঀ")).isEqualTo("[Hindi] ?");
        assertThat(PdfDocument.safe("नाम ")).isEqualTo("[Hindi] ");
        assertThat(PdfDocument.safe("कࣿ")).isEqualTo("[Hindi]?");
        assertThat(PdfDocument.safe("क中")).isEqualTo("[Hindi]?");
        assertThat(PdfDocument.safe("क ख")).isEqualTo("[Hindi]");
    }

    @Test
    void OD_144_aBlankValueIsADashAndShortAlignmentsLeaveTheRestLeftAligned() {
        PdfDocument pdf = PdfDocument.portrait("T", null);
        pdf.pairs("EIS", "  ", "Name", "Asha");
        pdf.table(new String[] {"A", "B", "C"}, new float[] {1, 1, 1}, new boolean[3]);
        pdf.tableRow(false, new boolean[] {true}, "10.00", "left", "also");

        String text = text(pdf.toBytes());

        assertThat(text).contains("EIS", "-", "Name", "Asha", "10.00", "left", "also");
    }
}
