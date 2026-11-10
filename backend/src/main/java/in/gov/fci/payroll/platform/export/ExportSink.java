package in.gov.fci.payroll.platform.export;

import java.io.IOException;

/**
 * Where a list export writes, row by row, straight to the download (SRS-G-13): nothing is held in
 * memory beyond the current row (CSV) or a small window (Excel). One header, then rows in the order
 * they are given; {@link #close()} completes the file.
 */
public interface ExportSink extends AutoCloseable {

    /** The column titles; written once, before any row. */
    void header(String... titles) throws IOException;

    /**
     * SRS-18-12 (c): the report header above the column titles (report name, period, scope, run
     * date-time and user), one line each; only before {@link #header}. A sink without a header area
     * writes nothing. Title lines are not rows.
     */
    default void title(String... lines) throws IOException {
        // A list export has no report header.
    }

    /** One row, its cells in header order (fewer cells leave the rest empty; null is empty). */
    void row(Object... cells) throws IOException;

    /** Rows written so far (the header not counted). */
    long rows();

    /** Completes the file (an Excel workbook is written out here). */
    @Override
    void close() throws IOException;

    /**
     * The completeness check of every export: the rows written must be exactly the rows the query
     * counted in the same snapshot. A short file is never delivered as if complete.
     */
    static void requireComplete(long expected, ExportSink sink) {
        if (sink.rows() != expected) {
            throw new ExportIncompleteException(expected, sink.rows());
        }
    }
}
