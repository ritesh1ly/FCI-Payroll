package in.gov.fci.payroll.platform.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A bulk download as a ZIP of single PDFs (OD-149, owner 2026-11-09: "bulk download will be zipped,
 * single month PDF"), written as it is produced: each entry is added as soon as its PDF is
 * rendered, so at most one document is held in memory. Reproducible (SRS O-01 l.3392): the entries
 * are added in the caller's fixed order and every entry carries the same timestamp, taken from the
 * locked data (the month or the year end), never from the clock, so the same request gives the same
 * bytes. Entry names are reduced to safe ASCII (letters, digits, dot, dash, underscore; no folder,
 * no {@code ..}); a duplicate name is refused as a programming error.
 *
 * <p>A document that cannot be rendered is left out and named in {@value #MANIFEST} (its entry name
 * and the error class only, never personal data), written last, so one failure never fails the
 * whole file.
 */
public final class ZipBundle implements AutoCloseable {

    /** The entry that lists the documents left out. */
    public static final String MANIFEST = "manifest.txt";

    private final ZipOutputStream zip;
    private final LocalDateTime at;
    private final Set<String> names = new LinkedHashSet<>();
    private final List<String> failed = new ArrayList<>();
    private int added;

    /**
     * @param out where the ZIP goes (the response or a temporary file)
     * @param at the timestamp of every entry, from the locked data
     */
    public ZipBundle(OutputStream out, LocalDateTime at) {
        this.zip = new ZipOutputStream(out, StandardCharsets.US_ASCII);
        this.zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
        this.at = at;
    }

    /** The entry name a file is stored under: safe ASCII, no path, bounded. */
    public static String entryName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "-");
        cleaned = cleaned.replace("..", "-");
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(cleaned.length() - 120);
        }
        return cleaned.isEmpty() ? "document" : cleaned;
    }

    /** Adds one document. */
    public void add(String name, byte[] content) throws IOException {
        String entry = entryName(name);
        if (!names.add(entry)) {
            throw new IllegalStateException("duplicate entry " + entry);
        }
        ZipEntry e = new ZipEntry(entry);
        e.setTimeLocal(at);
        zip.putNextEntry(e);
        zip.write(content);
        zip.closeEntry();
        added++;
    }

    /** Records a document that could not be rendered (named in the manifest, never added). */
    public void leftOut(String name, String error) {
        failed.add(entryName(name) + "\t" + entryName(error));
    }

    /** Documents added so far. */
    public int added() {
        return added;
    }

    /** Documents left out so far. */
    public int leftOut() {
        return failed.size();
    }

    /** Writes the manifest when a document was left out, and completes the ZIP. */
    @Override
    public void close() throws IOException {
        if (!failed.isEmpty()) {
            ZipEntry e = new ZipEntry(MANIFEST);
            e.setTimeLocal(at);
            zip.putNextEntry(e);
            StringBuilder text =
                    new StringBuilder(
                            "Documents not produced (" + failed.size() + "); see the job log.\n");
            for (String line : failed) {
                text.append(line).append('\n');
            }
            zip.write(text.toString().getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
        }
        zip.close();
    }
}
