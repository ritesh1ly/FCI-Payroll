package in.gov.fci.payroll.platform.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** SRS-G-13: the CSV and Excel writers of every list export. */
class ExportSinkTest {

    private static void write(ExportSink sink) throws IOException {
        sink.header("EIS", "Name", "Amount", "Locked", "Note");
        sink.row("104882", "Usha Pillai", new BigDecimal("42000.50"), true, null);
        sink.row("104883", "=HYPERLINK(\"http://x\")", 7, false, "-1+2");
        sink.row("104884");
    }

    @Test
    void SRS_G_13_csvIsUtf8WithAByteOrderMarkQuotesEveryCellAndDefusesFormulas()
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CsvSink sink = new CsvSink(out)) {
            write(sink);
            assertThat(sink.rows()).isEqualTo(3);
        }
        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv)
                .startsWith("﻿\"EIS\",\"Name\",\"Amount\",\"Locked\",\"Note\"\r\n")
                .contains("\"104882\",\"Usha Pillai\",\"42000.50\",\"true\",\r\n")
                .contains("\"'=HYPERLINK(\"\"http://x\"\")\"")
                .contains("\"'-1+2\"")
                .endsWith("\"104884\"\r\n");
    }

    @Test
    void SRS_G_13_excelKeepsNumbersAsNumbersAndNeverWritesAFormula() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxSink sink = new XlsxSink(out, "Exceptions: worklist/2026 [October] long name")) {
            write(sink);
            assertThat(sink.rows()).isEqualTo(3);
        }
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getSheetName())
                    .hasSizeLessThanOrEqualTo(31)
                    .doesNotContain(":", "/", "[");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("EIS");
            assertThat(
                            book.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex())
                                    .getBold())
                    .isTrue();
            assertThat(sheet.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(42000.50);
            assertThat(sheet.getRow(1).getCell(3).getBooleanCellValue()).isTrue();
            assertThat(sheet.getRow(1).getCell(4).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(sheet.getRow(2).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).startsWith("=HYPERLINK");
            assertThat(sheet.getRow(3).getLastCellNum()).isEqualTo((short) 1);
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition())
                    .isEqualTo((short) 1);
        }
    }

    @Test
    void SRS_G_13_anExcelCellLongerThanExcelAllowsIsCutNotAFailure() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxSink sink = new XlsxSink(out, "Long")) {
            sink.header("Detail");
            sink.row("x".repeat(40_000));
        }
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(1).getCell(0).getStringCellValue())
                    .hasSize(XlsxSink.MAX_CELL);
        }
    }

    @Test
    void aRowBeforeTheHeaderOrASecondHeaderIsAProgrammingError() throws IOException {
        try (CsvSink csv = new CsvSink(new ByteArrayOutputStream())) {
            assertThatThrownBy(() -> csv.row("x")).isInstanceOf(IllegalStateException.class);
            csv.header("A");
            assertThatThrownBy(() -> csv.header("A")).isInstanceOf(IllegalStateException.class);
        }
        try (XlsxSink xlsx = new XlsxSink(new ByteArrayOutputStream(), "x")) {
            assertThatThrownBy(() -> xlsx.row("x")).isInstanceOf(IllegalStateException.class);
            xlsx.header("A");
            assertThatThrownBy(() -> xlsx.header("A")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void anEmptyExportIsItsHeaderOnly() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportSink sink = ExportFormat.XLSX.open(out, "Empty")) {
            sink.header("A", "B");
            assertThat(sink.rows()).isZero();
        }
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isZero();
        }
        assertThat(ExportFormat.CSV.open(new ByteArrayOutputStream(), "x"))
                .isInstanceOf(CsvSink.class);
    }

    @Test
    void sheetNamesAreMadeValidForExcel() {
        assertThat(XlsxSink.safeSheetName(null)).isEqualTo("Export");
        assertThat(XlsxSink.safeSheetName(" ?* ")).isEqualTo("Export");
        assertThat(XlsxSink.safeSheetName("a/b")).isEqualTo("a b");
        assertThat(XlsxSink.safeSheetName("x".repeat(40))).hasSize(31);
    }

    @Test
    void aWriteFailureStillReleasesTheWorkbooksTemporaryFile() {
        OutputStream broken =
                new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        throw new IOException("client went away");
                    }
                };
        XlsxSink sink = new XlsxSink(broken, "x");
        sink.header("A");
        sink.row("1");
        assertThatThrownBy(sink::close).isInstanceOf(IOException.class);
    }

    @Test
    void SRS_G_13_aShortExportIsAnErrorNeverAQuietlyShortFile() throws IOException {
        try (CsvSink sink = new CsvSink(new ByteArrayOutputStream())) {
            sink.header("A");
            sink.row("1");
            ExportSink.requireComplete(1, sink);
            assertThatThrownBy(() -> ExportSink.requireComplete(2, sink))
                    .isInstanceOf(ExportIncompleteException.class)
                    .hasMessageContaining("1 of 2");
        }
    }

    /** SRS-18-12 (c) (US-GAP-37): the report header lines come before the titles, never rows. */
    @Test
    void SRS_18_12_theReportHeaderLinesComeBeforeTheColumnTitlesAndAreNotRows() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CsvSink sink = new CsvSink(out)) {
            sink.title("Component-wise Summary", "Period: 10-2026");
            write(sink);
            assertThat(sink.rows()).isEqualTo(3);
            assertThatThrownBy(() -> sink.title("late")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith(
                        CsvSink.BOM
                                + "\"Component-wise Summary\"\r\n\"Period: 10-2026\"\r\n\"EIS\",")
                .containsOnlyOnce(CsvSink.BOM);

        ByteArrayOutputStream book = new ByteArrayOutputStream();
        try (XlsxSink sink = new XlsxSink(book, "Summary")) {
            assertThat(sink.rows()).isZero();
            sink.title("Component-wise Summary", "Period: 10-2026");
            assertThat(sink.rows()).isZero();
            assertThatThrownBy(() -> sink.row("x")).isInstanceOf(IllegalStateException.class);
            write(sink);
            assertThat(sink.rows()).isEqualTo(3);
            assertThatThrownBy(() -> sink.title("late")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> sink.header("again"))
                    .isInstanceOf(IllegalStateException.class);
        }
        try (XSSFWorkbook read = new XSSFWorkbook(new ByteArrayInputStream(book.toByteArray()))) {
            Sheet sheet = read.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("Component-wise Summary");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("Period: 10-2026");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("EIS");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("104882");
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition())
                    .isEqualTo((short) 3);
        }
    }

    /** A sink without a report header area ignores the title. */
    @Test
    void SRS_18_12_aSinkWithoutAHeaderAreaIgnoresTheTitle() throws IOException {
        ExportSink plain =
                new ExportSink() {
                    @Override
                    public void header(String... titles) {}

                    @Override
                    public void row(Object... cells) {}

                    @Override
                    public long rows() {
                        return 0;
                    }

                    @Override
                    public void close() {}
                };
        plain.title("ignored");
        assertThat(plain.rows()).isZero();
    }
}
