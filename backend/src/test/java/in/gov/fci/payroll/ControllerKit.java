package in.gov.fci.payroll;

import in.gov.fci.payroll.platform.export.ExportProperties;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Unit-test kit for the thin web controllers: a real {@link ExportStreaming} (size limit {@value
 * #MAX_ROWS} rows; the delivery events it publishes are kept in {@link #delivered}) and a runner
 * that writes a streamed download into memory, so a controller test proves the service's export is
 * the body that is sent (SRS-G-13).
 */
public final class ControllerKit {

    /** The row limit of {@link #exports}. */
    public static final int MAX_ROWS = 1000;

    /** The events the exports published (one per completed download). */
    public final List<Object> delivered = new ArrayList<>();

    /** The export streaming the controllers use. */
    public final ExportStreaming exports =
            new ExportStreaming(
                    new ExportProperties(4, 20, Duration.ofMinutes(10), MAX_ROWS, 10),
                    delivered::add,
                    new StaticListableBeanFactory().getBeanProvider(CurrentAccess.class));

    /** Runs the streamed body of a download and answers what it wrote. */
    public static String body(ResponseEntity<StreamingResponseBody> response) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** True when the response is never stored by a cache (Cache-Control: no-store). */
    public static boolean noStore(ResponseEntity<?> response) {
        String cache = response.getHeaders().getCacheControl();
        return cache != null && cache.contains("no-store");
    }

    /** A stored PDF order copy named {@code order.pdf}. */
    public static in.gov.fci.payroll.document.api.DocumentStore.AttachmentView pdfView() {
        in.gov.fci.payroll.document.api.DocumentStore.AttachmentView v =
                org.mockito.Mockito.mock(
                        in.gov.fci.payroll.document.api.DocumentStore.AttachmentView.class);
        org.mockito.Mockito.when(v.fileName()).thenReturn("order.pdf");
        org.mockito.Mockito.when(v.contentType()).thenReturn("application/pdf");
        return v;
    }

    /** An uploaded file {@code order.pdf} holding {@code bytes}. */
    public static org.springframework.web.multipart.MultipartFile upload(byte... bytes)
            throws IOException {
        org.springframework.web.multipart.MultipartFile file =
                org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
        org.mockito.Mockito.when(file.getOriginalFilename()).thenReturn("order.pdf");
        org.mockito.Mockito.when(file.getBytes()).thenReturn(bytes);
        return file;
    }

    /** An uploaded file that cannot be read ("disk"). */
    public static org.springframework.web.multipart.MultipartFile unreadable() throws IOException {
        org.springframework.web.multipart.MultipartFile file =
                org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
        org.mockito.Mockito.when(file.getBytes()).thenThrow(new IOException("disk"));
        return file;
    }

    /** A request from {@code ip}. */
    public static jakarta.servlet.http.HttpServletRequest from(String ip) {
        jakarta.servlet.http.HttpServletRequest request =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getRemoteAddr()).thenReturn(ip);
        return request;
    }

    /**
     * True when the download is {@code order.pdf} as an attachment (never rendered inline), typed
     * PDF, nosniff and never cached.
     */
    public static boolean pdfAttachment(ResponseEntity<byte[]> r) {
        var headers = r.getHeaders();
        return headers.getContentDisposition().isAttachment()
                && "order.pdf".equals(headers.getContentDisposition().getFilename())
                && "nosniff".equals(headers.getFirst("X-Content-Type-Options"))
                && org.springframework.http.MediaType.APPLICATION_PDF.equals(
                        headers.getContentType())
                && noStore(r);
    }
}
