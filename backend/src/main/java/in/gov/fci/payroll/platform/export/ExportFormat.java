package in.gov.fci.payroll.platform.export;

import java.io.OutputStream;

/**
 * The formats a list export is offered in (SRS-G-13: "export on every list"; the user chooses on
 * screen; SRS-G-51: the basic reports in Excel and PDF). PDF since OD-144 (LIB-1): Apache PDFBox.
 */
public enum ExportFormat {
    CSV("csv", "text/csv;charset=UTF-8"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf", "application/pdf");

    private final String extension;
    private final String mediaType;

    ExportFormat(String extension, String mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    public String extension() {
        return extension;
    }

    public String mediaType() {
        return mediaType;
    }

    /** A sink writing this format to {@code out}; {@code title} names the sheet or the PDF. */
    public ExportSink open(OutputStream out, String title) {
        return switch (this) {
            case XLSX -> new XlsxSink(out, title);
            case PDF -> new PdfSink(out, title);
            case CSV -> new CsvSink(out);
        };
    }
}
