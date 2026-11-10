package in.gov.fci.payroll.platform.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * The one PDF writer of the application (OD-144, LIB-1; SRS-G-13, SRS-G-51: PDF and Excel outputs):
 * Apache PDFBox, server-side, A4, the two standard Helvetica faces (nothing embedded, nothing
 * fetched). A document is composed top to bottom: a heading, sub-lines, a grid of labelled values,
 * a band, tables whose cells wrap and whose heading repeats on each new page, notes. Every page
 * carries "Page n of m" and the document's footer line, unless the footer is null (a prescribed
 * form). Nothing time-dependent is written (no creation date), so the same content always gives the
 * same text (SRS O-01 l.3392: reproducible on re-generation).
 *
 * <p>The text is what the caller passes, already masked as the screen shows it (R-PII: PAN and bank
 * account by their last four characters); the writer adds no data. The standard fonts carry the
 * Latin-1 set only (R-ASCII: the data is printable ASCII): a dash or rupee sign is written as its
 * ASCII form and any other character outside the set as "?", so a document never fails on one
 * character. Hindi output needs an embedded Devanagari font (PENDING LIB-1-HI).
 *
 * <p>Pages are buffered in a temporary file, not in memory, so a long list export holds only the
 * page being written ({@link IOUtils#createTempFileOnlyStreamCache()}).
 */
public final class PdfDocument implements AutoCloseable {

    static final float MARGIN = 36f;
    static final float SIZE = 9f;
    static final float LEADING = 11.5f;
    static final float PAD = 3f;
    private static final float FOOTER = 24f;

    private final PDDocument document;
    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final PDRectangle size;
    private final String footer;
    private PDPageContentStream content;
    private float y;

    /** The open table: its heading, repeated on a new page, and its column layout. */
    private String[] tableHeadings;

    private float[] tableWidths;
    private boolean[] tableNumeric;

    private PdfDocument(PDRectangle size, String title, String footer) {
        this.size = size;
        this.footer = footer;
        this.document = new PDDocument(IOUtils.createTempFileOnlyStreamCache());
        PDDocumentInformation info = document.getDocumentInformation();
        info.setTitle(safe(title));
        info.setProducer("FCILekha");
        // Reproducible (SRS O-01 l.3392, ETag): the file identifier is derived from the title,
        // never from the clock; no creation date is written. identify() narrows it per record.
        identify(title);
        newPage();
    }

    /**
     * Fixes the file identifier from {@code key} (a member and period, a certificate), so the same
     * content gives the same bytes every time and its ETag holds. The key is hashed, never written.
     */
    public PdfDocument identify(String key) {
        long id = 1125899906842597L;
        String k = key == null ? "" : key;
        for (int i = 0; i < k.length(); i++) {
            id = 31 * id + k.charAt(i);
        }
        document.setDocumentId(id);
        return this;
    }

    /** An A4 portrait document (a payslip, a certificate, a statement). */
    public static PdfDocument portrait(String title, String footer) {
        return new PdfDocument(PDRectangle.A4, title, footer);
    }

    /** An A4 landscape document (a list or report of many columns). */
    public static PdfDocument landscape(String title, String footer) {
        return new PdfDocument(
                new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()),
                title,
                footer);
    }

    /** The width between the margins. */
    public float width() {
        return size.getWidth() - 2 * MARGIN;
    }

    /** The document's heading, centred and bold. */
    public PdfDocument heading(String text) {
        endTable();
        need(LEADING * 2);
        centred(text, bold, 13f);
        y -= 6f;
        return this;
    }

    /** A centred sub-line under the heading. */
    public PdfDocument subtitle(String text) {
        endTable();
        need(LEADING);
        centred(text, regular, SIZE + 1);
        return this;
    }

    /**
     * A grid of labelled values, two pairs to a line ({@code pairs}: label, value, label, value
     * ...); a null value is printed as a dash.
     */
    public PdfDocument pairs(String... pairs) {
        endTable();
        y -= 4f;
        float w = width();
        float[] widths = {w * 0.18f, w * 0.32f, w * 0.18f, w * 0.32f};
        for (int i = 0; i < pairs.length; i += 4) {
            String[] cells = new String[4];
            for (int c = 0; c < 4; c++) {
                int at = i + c;
                cells[c] = at < pairs.length ? orDash(pairs[at]) : "";
            }
            if (i + 2 >= pairs.length) {
                cells[2] = "";
                cells[3] = "";
            }
            row(cells, widths, new boolean[4], new boolean[] {true, false, true, false}, true);
        }
        return this;
    }

    /** A full-width shaded band (a section title or the net pay line). */
    public PdfDocument band(String text) {
        endTable();
        y -= 6f;
        need(LEADING + 2 * PAD);
        float h = LEADING + 2 * PAD;
        try {
            content.setNonStrokingColor(0.9f, 0.92f, 0.96f);
            content.addRect(MARGIN, y - h, width(), h);
            content.fill();
            content.setNonStrokingColor(0f, 0f, 0f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        text(MARGIN + PAD, y - PAD - SIZE, text, bold, SIZE);
        y -= h;
        return this;
    }

    /**
     * Starts a table: the headings (bold, repeated at the top of each new page), the relative
     * column widths and which columns are amounts (right-aligned).
     */
    public PdfDocument table(String[] headings, float[] relative, boolean[] numeric) {
        endTable();
        float total = 0;
        for (float r : relative) {
            total += r;
        }
        float[] widths = new float[relative.length];
        for (int i = 0; i < relative.length; i++) {
            widths[i] = width() * relative[i] / total;
        }
        tableHeadings = headings.clone();
        tableWidths = widths;
        tableNumeric = numeric.clone();
        y -= 4f;
        headingRow();
        return this;
    }

    /** One row of the open table; {@code strong} prints it bold (a total). */
    public PdfDocument tableRow(boolean strong, String... cells) {
        return tableRow(strong, tableNumeric, cells);
    }

    /**
     * One row whose cells are right-aligned where {@code numeric} says (a list export's numbers).
     */
    public PdfDocument tableRow(boolean strong, boolean[] numeric, String... cells) {
        if (tableHeadings == null) {
            throw new IllegalStateException("tableRow before table");
        }
        String[] padded = new String[tableWidths.length];
        for (int i = 0; i < padded.length; i++) {
            padded[i] = i < cells.length && cells[i] != null ? cells[i] : "";
        }
        boolean[] boldCells = new boolean[padded.length];
        java.util.Arrays.fill(boldCells, strong);
        if (!fits(padded, tableWidths)) {
            newPage();
            headingRow();
        }
        boolean[] right = new boolean[padded.length];
        for (int i = 0; i < right.length && numeric != null && i < numeric.length; i++) {
            right[i] = numeric[i];
        }
        row(padded, tableWidths, right, boldCells, true);
        return this;
    }

    /** A paragraph of small text (a note, the basis of a figure). */
    public PdfDocument note(String text) {
        endTable();
        y -= 4f;
        for (String line : wrap(safe(text), regular, SIZE - 1, width())) {
            need(LEADING);
            text(MARGIN, y - SIZE, line, regular, SIZE - 1);
            y -= LEADING - 1;
        }
        return this;
    }

    /** Starts a new page (one payslip per page in a bulk file). */
    public PdfDocument pageBreak() {
        endTable();
        newPage();
        return this;
    }

    /** Pages written so far. */
    public int pages() {
        return document.getNumberOfPages();
    }

    /** Writes the page numbers and the document to {@code out}; the document is closed. */
    public void writeTo(OutputStream out) throws IOException {
        try {
            content.close();
            content = null;
            int total = document.getNumberOfPages();
            // A prescribed form (Form 16) is printed without any line of ours: no footer.
            for (int i = 0; footer != null && i < total; i++) {
                PDPage page = document.getPage(i);
                try (PDPageContentStream s =
                        new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true)) {
                    String left = safe(footer);
                    String right = "Page " + (i + 1) + " of " + total;
                    s.beginText();
                    s.setFont(regular, SIZE - 2);
                    s.newLineAtOffset(MARGIN, FOOTER - 10f);
                    s.showText(left);
                    s.endText();
                    s.beginText();
                    s.setFont(regular, SIZE - 2);
                    s.newLineAtOffset(
                            size.getWidth() - MARGIN - textWidth(right, regular, SIZE - 2),
                            FOOTER - 10f);
                    s.showText(right);
                    s.endText();
                }
            }
            document.save(out);
        } finally {
            document.close();
        }
    }

    /** The finished document as bytes (a single document download). */
    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writeTo(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    @Override
    public void close() throws IOException {
        document.close();
    }

    // ---------------------------------------------------------------- formatting helpers

    /**
     * An amount as FCI prints it: Indian digit grouping, two decimals ({@code 1,27,350.00}); null
     * is a dash. The value is shown as stored (R-MONEY: no rounding here; a stored amount has two
     * decimals, a longer scale is shown in full).
     */
    public static String amount(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        BigDecimal shown = value.scale() < 2 ? value.setScale(2) : value;
        String plain = shown.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot);
        StringBuilder grouped = new StringBuilder();
        int n = whole.length();
        for (int i = 0; i < n; i++) {
            grouped.append(whole.charAt(i));
            int left = n - 1 - i;
            if (left > 0 && (left == 3 || (left > 3 && (left - 3) % 2 == 0))) {
                grouped.append(',');
            }
        }
        return (shown.signum() < 0 ? "-" : "") + grouped + fraction;
    }

    /**
     * The text as the standard fonts can print it: dashes, quotes, the rupee sign and bullets in
     * their ASCII form; control characters dropped; anything else outside Latin-1 as "?".
     */
    public static String safe(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '–', '—', '−' -> out.append('-');
                case '‘', '’' -> out.append('\'');
                case '“', '”' -> out.append('"');
                case '₹' -> out.append("Rs.");
                case '•', '·' -> out.append('*');
                case '\t', '\n', '\r' -> out.append(' ');
                default -> {
                    if (c >= 0x20 && c <= 0x7E || c >= 0xA0 && c <= 0xFF) {
                        out.append(c);
                    } else if (c >= 0x0900 && c <= 0x097F) {
                        // A run of Devanagari (a Hindi label) is marked once, never as "????".
                        int end = i;
                        while (end + 1 < text.length()
                                && (text.charAt(end + 1) >= 0x0900 && text.charAt(end + 1) <= 0x097F
                                        || text.charAt(end + 1) == ' '
                                                && end + 2 < text.length()
                                                && text.charAt(end + 2) >= 0x0900
                                                && text.charAt(end + 2) <= 0x097F)) {
                            end++;
                        }
                        out.append("[Hindi]");
                        i = end;
                    } else if (!Character.isISOControl(c)) {
                        out.append('?');
                    }
                }
            }
        }
        return out.toString();
    }

    private static final String[] ONES = {
        "",
        "One",
        "Two",
        "Three",
        "Four",
        "Five",
        "Six",
        "Seven",
        "Eight",
        "Nine",
        "Ten",
        "Eleven",
        "Twelve",
        "Thirteen",
        "Fourteen",
        "Fifteen",
        "Sixteen",
        "Seventeen",
        "Eighteen",
        "Nineteen"
    };
    private static final String[] TENS = {
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    /**
     * An amount in words as the payslip prints it (wireframe v6.1 O-01 / E-01: "Rupees Ninety One
     * Thousand Nine Hundred and Sixty Three only"), in the Indian system (crore, lakh, thousand);
     * paise when there are any; a negative amount is prefixed "Minus". Null is a dash.
     */
    public static String rupeesInWords(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        BigDecimal abs = value.abs().setScale(2, java.math.RoundingMode.DOWN);
        long rupees = abs.longValue();
        int paise = abs.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String words = rupees == 0 ? "Zero" : indian(rupees);
        String text = "Rupees " + words + (paise > 0 ? " and " + below100(paise) + " Paise" : "");
        return (value.signum() < 0 ? "Minus " : "") + text + " only";
    }

    private static String indian(long n) {
        StringBuilder out = new StringBuilder();
        long crore = n / 10_000_000;
        long rest = n % 10_000_000;
        if (crore > 0) {
            out.append(indian(crore)).append(" Crore");
        }
        long[] parts = {rest / 100_000, rest % 100_000 / 1000, rest % 1000 / 100, rest % 100};
        String[] names = {" Lakh", " Thousand", " Hundred", ""};
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] == 0) {
                continue;
            }
            if (i == 3 && !out.isEmpty()) {
                out.append(" and");
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(below100((int) parts[i])).append(names[i]);
        }
        return out.toString();
    }

    private static String below100(int n) {
        if (n < 20) {
            return ONES[n];
        }
        return TENS[n / 10] + (n % 10 == 0 ? "" : " " + ONES[n % 10]);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    // ---------------------------------------------------------------- layout

    private void endTable() {
        tableHeadings = null;
        tableWidths = null;
        tableNumeric = null;
    }

    private void headingRow() {
        if (java.util.Arrays.stream(tableHeadings).allMatch(h -> h == null || h.isBlank())) {
            // A table without titles (a form's identification block) has no heading row.
            return;
        }
        boolean[] all = new boolean[tableHeadings.length];
        java.util.Arrays.fill(all, true);
        if (!fits(tableHeadings, tableWidths)) {
            newPage();
        }
        row(tableHeadings, tableWidths, tableNumeric, all, true);
    }

    private boolean fits(String[] cells, float[] widths) {
        return y - height(cells, widths) >= MARGIN + FOOTER;
    }

    private float height(String[] cells, float[] widths) {
        int lines = 1;
        for (int i = 0; i < cells.length; i++) {
            lines = Math.max(lines, wrap(safe(cells[i]), bold, SIZE, widths[i] - 2 * PAD).size());
        }
        return lines * LEADING + 2 * PAD;
    }

    private void row(
            String[] cells, float[] widths, boolean[] numeric, boolean[] strong, boolean grid) {
        float h = height(cells, widths);
        need(h);
        float x = MARGIN;
        try {
            for (int i = 0; i < cells.length; i++) {
                PDType1Font font = strong[i] ? bold : regular;
                List<String> lines = wrap(safe(cells[i]), font, SIZE, widths[i] - 2 * PAD);
                float ly = y - PAD - SIZE;
                for (String line : lines) {
                    float lx =
                            numeric[i]
                                    ? x + widths[i] - PAD - textWidth(line, font, SIZE)
                                    : x + PAD;
                    text(lx, ly, line, font, SIZE);
                    ly -= LEADING;
                }
                if (grid) {
                    content.setLineWidth(0.4f);
                    content.setStrokingColor(0.6f, 0.6f, 0.6f);
                    content.addRect(x, y - h, widths[i], h);
                    content.stroke();
                }
                x += widths[i];
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        y -= h;
    }

    private void need(float h) {
        if (y - h < MARGIN + FOOTER) {
            newPage();
        }
    }

    private void newPage() {
        try {
            if (content != null) {
                content.close();
            }
            PDPage page = new PDPage(size);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = size.getHeight() - MARGIN;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void centred(String text, PDType1Font font, float fontSize) {
        String safe = safe(text);
        for (String line : wrap(safe, font, fontSize, width())) {
            float w = textWidth(line, font, fontSize);
            text(MARGIN + (width() - w) / 2, y - fontSize, line, font, fontSize);
            y -= fontSize + 4f;
        }
    }

    private void text(float x, float baseline, String text, PDType1Font font, float fontSize) {
        try {
            content.beginText();
            content.setFont(font, fontSize);
            content.newLineAtOffset(x, baseline);
            content.showText(safe(text));
            content.endText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static float textWidth(String text, PDType1Font font, float fontSize) {
        try {
            return font.getStringWidth(safe(text)) / 1000f * fontSize;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The lines of {@code text} within {@code max} points: by words, a long word by characters. */
    static List<String> wrap(String text, PDType1Font font, float fontSize, float max) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (textWidth(candidate, font, fontSize) <= max) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            String rest = word;
            while (textWidth(rest, font, fontSize) > max && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && textWidth(rest.substring(0, cut), font, fontSize) > max) {
                    cut--;
                }
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        lines.add(line.toString());
        return lines;
    }
}
