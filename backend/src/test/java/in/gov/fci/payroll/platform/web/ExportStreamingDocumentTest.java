package in.gov.fci.payroll.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.export.ExportDelivered;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.export.ExportProperties;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * OD-144, SRS-20-24, SRS O-01 l.3392: a document download carries a safe attachment name, nosniff,
 * a private no-cache policy and a strong ETag of its bytes; a repeat download with the same ETag is
 * 304 without a body and is not logged again; a streamed bulk document is logged once written.
 */
class ExportStreamingDocumentTest {

    private final List<Object> published = new ArrayList<>();
    private final ExportStreaming exports =
            new ExportStreaming(
                    new ExportProperties(4, 20, Duration.ofMinutes(10), 3, 10),
                    published::add,
                    new StaticListableBeanFactory().getBeanProvider(CurrentAccess.class));

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void request(String ifNoneMatch) {
        MockHttpServletRequest r =
                new MockHttpServletRequest("GET", "/v1/payslips/mine/202609/pdf");
        if (ifNoneMatch != null) {
            r.addHeader(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));
    }

    @Test
    void OD_144_aDocumentIsAnAttachmentWithSafeHeadersAndAStrongEtag() {
        request(null);
        byte[] pdf = {'%', 'P', 'D', 'F', '-', '1'};
        ResponseEntity<byte[]> r = exports.document("payslip-104882-202609", pdf, 1);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeaders().getCacheControl()).contains("no-cache").contains("private");
        assertThat(r.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("payslip-104882-202609.pdf");
        assertThat(r.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(r.getHeaders().getETag()).isEqualTo(ExportStreaming.etag(pdf)).startsWith("\"");
        assertThat(r.getHeaders().getContentLength()).isEqualTo(6);
        assertThat(published)
                .singleElement()
                .isInstanceOfSatisfying(
                        ExportDelivered.class,
                        d -> {
                            assertThat(d.format()).isEqualTo(ExportFormat.PDF);
                            assertThat(d.rows()).isEqualTo(1);
                        });
    }

    @Test
    void OD_144_theSameBytesAreAnswered304WithoutABodyAndNotLoggedAgain() {
        byte[] pdf = {'%', 'P', 'D', 'F', '-', '2'};
        request(ExportStreaming.etag(pdf));
        ResponseEntity<byte[]> r = exports.document("payslip", pdf, 1);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(r.getBody()).isNull();
        assertThat(r.getHeaders().getETag()).isEqualTo(ExportStreaming.etag(pdf));
        assertThat(published).isEmpty();
        // Another ETag (the document changed: a reissue) downloads in full.
        request("\"other\"");
        assertThat(exports.document("payslip", pdf, 1).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void OD_144_aFileNameIsReducedToSafeCharactersAndBounded() {
        assertThat(ExportStreaming.safeName("payslip-DL/04 12\r\nX: y"))
                .isEqualTo("payslip-DL-04-12--X--y");
        assertThat(ExportStreaming.safeName(null)).isEqualTo("document");
        assertThat(ExportStreaming.safeName("")).isEqualTo("document");
        assertThat(ExportStreaming.safeName("a".repeat(150))).hasSize(100);
        assertThat(ExportStreaming.etag(new byte[] {1}))
                .isNotEqualTo(ExportStreaming.etag(new byte[] {2}));
    }

    @Test
    void OD_149_aZipIsWrittenAsProducedNeverCachedAndLoggedWithItsFiles() throws Exception {
        request(null);
        ResponseEntity<StreamingResponseBody> r =
                exports.streamZip(
                        "payslips-202601-202603",
                        java.time.LocalDateTime.of(2026, 3, 1, 0, 0),
                        zip -> {
                            zip.add("payslip-202601.pdf", new byte[] {1});
                            zip.add("payslip-202602.pdf", new byte[] {2});
                        });
        assertThat(r.getHeaders().getContentType()).isEqualTo(ExportStreaming.ZIP);
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(r.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("payslips-202601-202603.zip");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        r.getBody().writeTo(out);
        try (java.util.zip.ZipInputStream in =
                new java.util.zip.ZipInputStream(
                        new java.io.ByteArrayInputStream(out.toByteArray()))) {
            assertThat(in.getNextEntry().getName()).isEqualTo("payslip-202601.pdf");
            assertThat(in.getNextEntry().getName()).isEqualTo("payslip-202602.pdf");
            assertThat(in.getNextEntry()).isNull();
        }
        assertThat(published)
                .singleElement()
                .isInstanceOfSatisfying(
                        ExportDelivered.class, d -> assertThat(d.rows()).isEqualTo(2));
    }

    @Test
    void OD_149_aZipThatFailsWhileWrittenIsNotLogged() {
        request(null);
        ResponseEntity<StreamingResponseBody> r =
                exports.streamZip(
                        "payslips",
                        java.time.LocalDateTime.of(2026, 1, 1, 0, 0),
                        zip -> {
                            throw new IllegalStateException("read failed");
                        });
        assertThatThrownBy(() -> r.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(published).isEmpty();
    }

    @Test
    void OD_149_aJobsZipIsSentAsAnAttachmentAndLogged() {
        request(null);
        byte[] zip = {'P', 'K', 3, 4};
        ResponseEntity<byte[]> r = exports.archive("payslips-202609", zip, 40);
        assertThat(r.getHeaders().getContentType()).isEqualTo(ExportStreaming.ZIP);
        assertThat(r.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("payslips-202609.zip");
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeaders().getContentLength()).isEqualTo(4);
        assertThat(published)
                .singleElement()
                .isInstanceOfSatisfying(
                        ExportDelivered.class, d -> assertThat(d.rows()).isEqualTo(40));
    }
}
