package in.gov.fci.payroll.platform.web;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportDelivered;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.export.ExportProperties;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.SensitiveDataMasker;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Large list downloads (SRS-G-13), the one way every export is sent:
 *
 * <ol>
 *   <li>The controller first asks the service for the row count and calls {@link #checkSize}: a
 *       list larger than {@code fci.export.max-rows} is refused with 422 before any byte is sent
 *       (never cut short).
 *   <li>{@link #stream} answers with a {@link StreamingResponseBody}: Spring MVC runs it on the
 *       bounded export pool ({@code ExportAsyncConfig}), not on a request thread; a full pool and
 *       queue answer 503.
 *   <li>The service writes row by row into the {@link ExportSink} inside one read-only snapshot
 *       transaction and checks the rows written against the rows it counted in that snapshot. An
 *       error while streaming aborts the download, so a short file is never delivered.
 *   <li>A complete file is logged before it is closed ({@link ExportDelivered}: requester, scope,
 *       time; SRS-20-24; and the request's parameters, SRS-22-29); a log that cannot be written
 *       aborts the download too.
 * </ol>
 */
@Component
public class ExportStreaming {

    /** Writes the rows of one export into the sink (on an export thread). */
    @FunctionalInterface
    public interface ExportJob {
        void write(ExportSink sink) throws IOException;
    }

    private final ExportProperties properties;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<CurrentAccess> access;

    public ExportStreaming(
            ExportProperties properties,
            ApplicationEventPublisher events,
            ObjectProvider<CurrentAccess> access) {
        this.properties = properties;
        this.events = events;
        this.access = access;
    }

    /** Refuses (422) a list of more rows than one download may carry, before streaming starts. */
    public void checkSize(long rows) {
        if (rows > properties.maxRows()) {
            throw new FciException(
                    CommonErrorCode.EXPORT_TOO_LARGE,
                    new Object[] {rows, properties.maxRows()},
                    List.of(),
                    null);
        }
    }

    /** The download: {@code <baseName>.csv|.xlsx}, never cached, written by {@code job}. */
    public ResponseEntity<StreamingResponseBody> stream(
            String baseName, ExportFormat format, ExportJob job) {
        ExportFormat chosen = format == null ? ExportFormat.CSV : format;
        // The requester is read here, on the request thread; the body runs on an export thread.
        AccessContext requester = requester();
        String parameters = parameters();
        StreamingResponseBody body =
                out -> {
                    try (ExportSink sink = chosen.open(out, baseName)) {
                        job.write(sink);
                        events.publishEvent(
                                delivered(baseName, chosen, sink.rows(), requester, parameters));
                    }
                };
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(baseName + "." + chosen.extension())
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType(chosen.mediaType()))
                .body(body);
    }

    /** Writes the documents of one streamed ZIP (on an export thread). */
    @FunctionalInterface
    public interface ZipJob {
        void write(in.gov.fci.payroll.platform.export.ZipBundle zip) throws IOException;
    }

    /**
     * A single generated document (OD-144: a payslip, a Form 16 certificate, a settlement
     * statement) as {@code <baseName>.pdf}: {@code Cache-Control: private, no-cache} with a strong
     * {@code ETag} of the bytes (a locked payslip or released certificate is immutable and renders
     * to the same bytes, so a repeat download is answered 304 without a body), {@code nosniff}, and
     * a safe attachment name. A 200 is logged like every export (SRS-20-24, SRS-22-29) before it is
     * sent; a log that cannot be written refuses the download. {@code pages} counts as the rows of
     * the log entry.
     */
    public ResponseEntity<byte[]> document(String baseName, byte[] pdf, long pages) {
        String etag = etag(pdf);
        String name = safeName(baseName);
        CacheControl cache = CacheControl.noCache().cachePrivate();
        if (etag.equals(ifNoneMatch())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(cache)
                    .header("X-Content-Type-Options", "nosniff")
                    .build();
        }
        events.publishEvent(delivered(name, ExportFormat.PDF, pages, requester(), parameters()));
        return ResponseEntity.ok()
                .cacheControl(cache)
                .eTag(etag)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(name))
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }

    /**
     * A bulk download (OD-149: "bulk download will be zipped, single month PDF") as {@code
     * <baseName>.zip}, written entry by entry as each PDF is rendered, on the bounded export pool
     * (never a request thread) with at most one document in memory; never cached; logged once
     * complete with the documents written. An error while writing aborts the download, so a short
     * file is never delivered as complete; a single document that fails is left out and listed in
     * the ZIP's manifest by the job.
     *
     * @param at the timestamp of every entry, from the locked data (reproducible bytes)
     */
    public ResponseEntity<StreamingResponseBody> streamZip(
            String baseName, java.time.LocalDateTime at, ZipJob job) {
        AccessContext requester = requester();
        String parameters = parameters();
        String name = safeName(baseName);
        StreamingResponseBody body =
                out -> {
                    in.gov.fci.payroll.platform.export.ZipBundle zip =
                            new in.gov.fci.payroll.platform.export.ZipBundle(out, at);
                    job.write(zip);
                    events.publishEvent(
                            delivered(name, ExportFormat.PDF, zip.added(), requester, parameters));
                    zip.close();
                };
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, zipAttachment(name))
                .header("X-Content-Type-Options", "nosniff")
                .contentType(ZIP)
                .body(body);
    }

    /**
     * A ZIP a background job produced (OD-149: the O-01 and C-10 bulk downloads), sent to its
     * requester: never cached, nosniff, a safe name; logged as an export.
     */
    public ResponseEntity<byte[]> archive(String baseName, byte[] zip, long files) {
        String name = safeName(baseName);
        events.publishEvent(delivered(name, ExportFormat.PDF, files, requester(), parameters()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, zipAttachment(name))
                .header("X-Content-Type-Options", "nosniff")
                .contentType(ZIP)
                .contentLength(zip.length)
                .body(zip);
    }

    /** {@code application/zip}. */
    public static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private static String zipAttachment(String name) {
        return ContentDisposition.attachment().filename(name + ".zip").build().toString();
    }

    /** A strong ETag of the bytes (SHA-256, quoted). */
    static String etag(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return "\"" + java.util.HexFormat.of().formatHex(digest) + "\"";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A file name of letters, digits, dot, dash and underscore only (no header injection). */
    static String safeName(String baseName) {
        String cleaned = baseName == null ? "" : baseName.replaceAll("[^A-Za-z0-9._-]", "-");
        cleaned = cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
        return cleaned.isEmpty() ? "document" : cleaned;
    }

    private static String attachment(String name) {
        return ContentDisposition.attachment()
                .filename(name + "." + ExportFormat.PDF.extension())
                .build()
                .toString();
    }

    private static String ifNoneMatch() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes r) {
            return r.getRequest().getHeader(HttpHeaders.IF_NONE_MATCH);
        }
        return null;
    }

    private AccessContext requester() {
        CurrentAccess current = access.getIfAvailable();
        if (current == null) {
            return null;
        }
        try {
            return current.current();
        } catch (RuntimeException noSession) {
            return null;
        }
    }

    /** The longest parameter text logged; the audit row's value holds 4000 characters. */
    static final int MAX_PARAMETERS = 2000;

    /** The filters of the request being answered (read on the request thread), or null. */
    private static String parameters() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes r) {
            return parameters(r.getRequest().getParameterMap());
        }
        return null;
    }

    /**
     * SRS-22-29: "Log every report download with user, report, parameters and time". The query and
     * form parameters, sorted by name, masked like a log line (R-PII: a PAN or Aadhaar typed as a
     * filter never reaches the trail in full) and capped at {@link #MAX_PARAMETERS}.
     */
    static String parameters(Map<String, String[]> given) {
        String text =
                new TreeMap<>(given)
                        .entrySet().stream()
                                .map(p -> p.getKey() + "=" + String.join(",", p.getValue()))
                                .collect(Collectors.joining("&"));
        String masked = SensitiveDataMasker.mask(text);
        return masked.length() <= MAX_PARAMETERS ? masked : masked.substring(0, MAX_PARAMETERS);
    }

    static ExportDelivered delivered(
            String name,
            ExportFormat format,
            long rows,
            AccessContext requester,
            String parameters) {
        if (requester == null) {
            return new ExportDelivered(name, format, rows, null, null, null, parameters);
        }
        String scope =
                requester.groups() == null
                        ? null
                        : requester.groups().stream()
                                .map(ActiveGroup::code)
                                .collect(Collectors.joining(","));
        return new ExportDelivered(
                name, format, rows, requester.loginId(), requester.locationId(), scope, parameters);
    }
}
