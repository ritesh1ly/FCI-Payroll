package in.gov.fci.payroll.platform.export;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

/**
 * A list or report export as PDF (SRS-G-13: "PDF and Excel export on every list"; SRS-G-51,
 * SRS-18-12 (c): the report header above the columns; OD-144): A4 landscape, the title lines first,
 * then the column titles repeated on every page, one table row per row. Numbers are right-aligned
 * and amounts printed with Indian grouping; a long cell wraps within its column. The rows are
 * written to pages buffered in a temporary file ({@link PdfDocument}); the file is written out on
 * close.
 */
public final class PdfSink implements ExportSink {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-uuuu");

    private final OutputStream out;
    private final PdfDocument pdf;
    private boolean header;
    private boolean titled;
    private int columns;
    private long rows;

    public PdfSink(OutputStream out, String title) {
        this.out = out;
        this.pdf = PdfDocument.landscape(title, "FCILekha - " + PdfDocument.safe(title));
    }

    @Override
    public void title(String... lines) {
        if (header) {
            throw new IllegalStateException("title after header");
        }
        for (String line : lines) {
            if (!titled) {
                pdf.heading(line);
                titled = true;
            } else {
                pdf.subtitle(line);
            }
        }
    }

    @Override
    public void header(String... titles) {
        if (header) {
            throw new IllegalStateException("header written twice");
        }
        header = true;
        columns = titles.length;
        float[] widths = new float[columns];
        java.util.Arrays.fill(widths, 1f);
        // Numeric columns are known only from the rows: right-aligned per cell (see row()).
        pdf.table(titles, widths, new boolean[columns]);
    }

    @Override
    public void row(Object... cells) {
        if (!header) {
            throw new IllegalStateException("row before header");
        }
        String[] text = new String[columns];
        boolean[] numeric = new boolean[columns];
        for (int i = 0; i < columns; i++) {
            Object value = i < cells.length ? cells[i] : null;
            text[i] = cell(value);
            numeric[i] = value instanceof Number;
        }
        pdf.tableRow(false, numeric, text);
        rows++;
    }

    /** A cell as printed: an amount grouped, a date dd-MM-yyyy, null empty. */
    static String cell(Object value) {
        return switch (value) {
            case null -> "";
            case BigDecimal amount -> PdfDocument.amount(amount);
            case LocalDate date -> DATE.format(date);
            case TemporalAccessor time -> time.toString();
            default -> String.valueOf(value);
        };
    }

    @Override
    public long rows() {
        return rows;
    }

    @Override
    public void close() throws IOException {
        if (!header) {
            // An export always has its column titles; an empty list still gives a valid page.
            pdf.note("No rows.");
        }
        pdf.writeTo(out);
    }
}
