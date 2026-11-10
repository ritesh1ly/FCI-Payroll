package in.gov.fci.payroll.platform.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.util.DefaultTempFileCreationStrategy;
import org.apache.poi.util.TempFile;
import org.apache.poi.util.TempFileCreationStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/** SRS-G-13 and SRS-18-12 (c): gaps of the Excel writer found by mutation testing. */
@ResourceLock("poi-temp-file-strategy")
class XlsxSinkMutationGapsTest {

    /** Records the temporary files created on the calling thread, delegating the creation. */
    private static final class RecordingStrategy implements TempFileCreationStrategy {
        private final TempFileCreationStrategy delegate = new DefaultTempFileCreationStrategy();
        private final Thread owner = Thread.currentThread();
        final List<File> mine = new ArrayList<>();

        @Override
        public File createTempFile(String prefix, String suffix) throws IOException {
            File file = delegate.createTempFile(prefix, suffix);
            if (Thread.currentThread() == owner) {
                synchronized (mine) {
                    mine.add(file);
                }
            }
            return file;
        }

        @Override
        public File createTempDirectory(String prefix) throws IOException {
            return delegate.createTempDirectory(prefix);
        }
    }

    /** A stream that remembers whether it was flushed. */
    private static final class FlushSpy extends ByteArrayOutputStream {
        boolean flushed;

        @Override
        public void flush() throws IOException {
            flushed = true;
            super.flush();
        }
    }

    @Test
    void SRS_G_13_closeFlushesTheOutputStream() throws IOException {
        FlushSpy out = new FlushSpy();
        XlsxSink sink = new XlsxSink(out, "Flush");
        sink.header("EIS");
        sink.row("104882");
        sink.close();
        assertThat(out.flushed).isTrue();
        assertThat(out.size()).isPositive();
    }

    @Test
    void SRS_G_13_closeDeletesTheTemporaryFileOfFlushedRows() throws IOException {
        RecordingStrategy strategy = new RecordingStrategy();
        TempFile.setTempFileCreationStrategy(strategy);
        try {
            XlsxSink sink = new XlsxSink(new ByteArrayOutputStream(), "Temp");
            sink.header("EIS");
            for (int i = 0; i < 250; i++) {
                sink.row("10" + i);
            }
            assertThat(strategy.mine).isNotEmpty();
            assertThat(strategy.mine).allMatch(File::exists);
            sink.close();
            assertThat(strategy.mine).noneMatch(File::exists);
        } finally {
            TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy());
        }
    }

    @Test
    void SRS_18_12_aTitleAfterAHeaderOnTheFirstRowIsRefused() throws IOException {
        try (XlsxSink sink = new XlsxSink(new ByteArrayOutputStream(), "Titles")) {
            sink.header("EIS");
            assertThatThrownBy(() -> sink.title("Late title"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("title after header");
        }
    }
}
