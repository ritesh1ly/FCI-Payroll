package in.gov.fci.payroll.platform.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * CSV written as it is produced (RFC 4180, the formula-injection guard of {@link Csv}): only the
 * current line is in memory. UTF-8 with a byte-order mark, so Excel opens Hindi names correctly.
 */
public final class CsvSink implements ExportSink {

    /** The UTF-8 byte-order mark: Excel reads the file as UTF-8 only with it. */
    static final String BOM = "﻿";

    private final Writer out;
    private long rows;
    private boolean headerWritten;
    private boolean started;

    public CsvSink(OutputStream stream) {
        this.out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
    }

    @Override
    public void header(String... titles) throws IOException {
        if (headerWritten) {
            throw new IllegalStateException("header written twice");
        }
        headerWritten = true;
        start();
        out.write(Csv.line((Object[]) titles));
    }

    /** SRS-18-12 (c): each report header line as a one-cell line before the column titles. */
    @Override
    public void title(String... lines) throws IOException {
        if (headerWritten) {
            throw new IllegalStateException("title after header");
        }
        start();
        for (String line : lines) {
            out.write(Csv.line(line));
        }
    }

    private void start() throws IOException {
        if (!started) {
            started = true;
            out.write(BOM);
        }
    }

    @Override
    public void row(Object... cells) throws IOException {
        if (!headerWritten) {
            throw new IllegalStateException("row before header");
        }
        out.write(Csv.line(cells));
        rows++;
    }

    @Override
    public long rows() {
        return rows;
    }

    @Override
    public void close() throws IOException {
        // Flush only: the response stream belongs to the servlet container.
        out.flush();
    }
}
