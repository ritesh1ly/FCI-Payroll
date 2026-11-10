package in.gov.fci.payroll.platform.export;

import java.io.IOException;
import java.io.OutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Excel (.xlsx) written as it is produced: POI's streaming workbook keeps {@value #WINDOW} rows in
 * memory and flushes the rest to a temporary file, deleted on close. Every value is written as a
 * value, never as a formula (no formula injection); numbers stay numbers; the header is bold and
 * frozen. More rows than one sheet holds is a programming error: callers refuse such exports before
 * streaming ({@link ExportProperties#maxRows()}).
 */
public final class XlsxSink implements ExportSink {

    /** Rows kept in memory while writing. */
    static final int WINDOW = 100;

    /** Excel's own cell limit; longer text is cut (an export never fails on one long cell). */
    static final int MAX_CELL = 32_767;

    /** Excel's own row limit, header included. */
    static final int MAX_ROWS = 1_048_576;

    private final OutputStream out;
    private final SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW);
    private final Sheet sheet;
    private int next;

    /** The row of the column titles; -1 until written. */
    private int headerRow = -1;

    public XlsxSink(OutputStream out, String sheetName) {
        this.out = out;
        this.sheet = workbook.createSheet(safeSheetName(sheetName));
    }

    @Override
    public void header(String... titles) {
        if (headerRow >= 0) {
            throw new IllegalStateException("header written twice");
        }
        headerRow = next;
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        Row header = sheet.createRow(next++);
        for (int c = 0; c < titles.length; c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(titles[c]);
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, headerRow + 1);
    }

    /** SRS-18-12 (c): each report header line in the first cell of a row above the titles. */
    @Override
    public void title(String... lines) {
        if (headerRow >= 0) {
            throw new IllegalStateException("title after header");
        }
        for (String line : lines) {
            set(sheet.createRow(next++).createCell(0), line);
        }
    }

    @Override
    public void row(Object... cells) {
        if (headerRow < 0) {
            throw new IllegalStateException("row before header");
        }
        if (next >= MAX_ROWS) {
            throw new IllegalStateException("more rows than an Excel sheet holds");
        }
        Row row = sheet.createRow(next++);
        for (int c = 0; c < cells.length; c++) {
            set(row.createCell(c), cells[c]);
        }
    }

    @Override
    public long rows() {
        return headerRow < 0 ? 0 : next - headerRow - 1L;
    }

    @Override
    public void close() throws IOException {
        try {
            workbook.write(out);
            out.flush();
        } finally {
            // Deletes the temporary file of flushed rows, also when writing failed.
            workbook.close();
        }
    }

    private static void set(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean flag) {
            cell.setCellValue(flag);
        } else {
            String text = String.valueOf(value);
            cell.setCellValue(text.length() > MAX_CELL ? text.substring(0, MAX_CELL) : text);
        }
    }

    /** Excel sheet names: at most 31 characters, none of {@code : \ / ? * [ ]}. */
    static String safeSheetName(String name) {
        String clean = name == null ? "" : name.replaceAll("[:\\\\/?*\\[\\]]", " ").strip();
        if (clean.isEmpty()) {
            return "Export";
        }
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }
}
