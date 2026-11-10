package in.gov.fci.payroll.tax.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.tax.orchestration.TaxScreenViews;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * I-07, C-10 and C-11 reads and the I-07 order card (OD-121; SRS-7-1, SRS-7-7, SRS-7-9, SRS-G-30;
 * BR-TAX-010): the year, quarter, status, office and page reach the views unchanged and the answers
 * are never cached; an order copy is stored with the file's name and bytes and answers 201; a
 * stored copy downloads as an attachment, never rendered inline; an unreadable upload stores
 * nothing.
 */
class TaxScreenControllerCoverageGapsTest {

    private static final PageSpec PAGE = new PageSpec(2, 10);

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final TaxScreenViews views = mock(TaxScreenViews.class);
    private final Paging paging = mock(Paging.class);
    private final TaxScreenController controller = new TaxScreenController(contexts, views, paging);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
        when(paging.resolve(2, 10)).thenReturn(PAGE);
    }

    @SuppressWarnings("unchecked")
    private static <T> Paged<T> paged() {
        return mock(Paged.class);
    }

    private static DocumentStore.AttachmentView view(String name, String type) {
        DocumentStore.AttachmentView v = mock(DocumentStore.AttachmentView.class);
        when(v.fileName()).thenReturn(name);
        when(v.contentType()).thenReturn(type);
        return v;
    }

    @Test
    void SRS_7_1_theDeclarationCardAndRegisterAreTheYearsAndNeverCached() {
        TaxScreenViews.DeclarationSummary summary = mock(TaxScreenViews.DeclarationSummary.class);
        when(views.declarationSummary(context, "2026-27")).thenReturn(summary);
        Paged<TaxScreenViews.DeclarationLine> members = paged();
        when(views.declarationMembers(context, "2026-27", PAGE)).thenReturn(members);

        ResponseEntity<?> s = controller.declarations("2026-27");
        ResponseEntity<?> m = controller.declarationMembers("2026-27", 2, 10);

        assertThat(s.getBody()).isSameAs(summary);
        assertThat(m.getBody()).isSameAs(members);
        assertThat(List.of(s, m)).allMatch(ControllerKit::noStore);
    }

    @Test
    void SRS_7_9_theCertificateChainAndRegisterAndTheQuarterPassTheirFilters() {
        TaxScreenViews.CertificateSummary chain = mock(TaxScreenViews.CertificateSummary.class);
        when(views.certificateSummary(context, "2025-26")).thenReturn(chain);
        Paged<TaxScreenViews.CertificateLine> lines = paged();
        when(views.certificateLines(context, "2025-26", "RELEASED", 300L, PAGE)).thenReturn(lines);
        TaxScreenViews.ReturnSummary quarter = mock(TaxScreenViews.ReturnSummary.class);
        when(views.returnSummary(context, "2026-27", "Q3")).thenReturn(quarter);

        ResponseEntity<?> c = controller.certificates("2025-26");
        ResponseEntity<?> l = controller.certificateLines("2025-26", "RELEASED", 300L, 2, 10);
        ResponseEntity<?> q = controller.returns("2026-27", "Q3");

        assertThat(c.getBody()).isSameAs(chain);
        assertThat(l.getBody()).isSameAs(lines);
        assertThat(q.getBody()).isSameAs(quarter);
        assertThat(List.of(c, l, q)).allMatch(ControllerKit::noStore);
    }

    @Test
    void SRS_G_30_anOrderCopyIsStoredWithItsNameAndBytesAndListedBack() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("order.pdf");
        when(file.getBytes()).thenReturn(new byte[] {7});
        DocumentStore.AttachmentView stored = view("order.pdf", "application/pdf");
        LocalDate day = LocalDate.of(2026, 11, 1);
        when(views.attachOrder(
                        org.mockito.ArgumentMatchers.eq(context),
                        org.mockito.ArgumentMatchers.eq(11L),
                        org.mockito.ArgumentMatchers.eq("CIRCULAR"),
                        org.mockito.ArgumentMatchers.eq("R-1"),
                        org.mockito.ArgumentMatchers.eq(day),
                        org.mockito.ArgumentMatchers.eq("RO Delhi"),
                        org.mockito.ArgumentMatchers.eq("order.pdf"),
                        org.mockito.ArgumentMatchers.argThat(b -> b.length == 1 && b[0] == 7)))
                .thenReturn(stored);
        when(views.orders(context, 11L)).thenReturn(List.of(stored));

        ResponseEntity<DocumentStore.AttachmentView> created =
                controller.attachOrder(11L, "CIRCULAR", "R-1", day, "RO Delhi", file);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isSameAs(stored);
        assertThat(ControllerKit.noStore(created)).isTrue();
        assertThat(controller.orders(11L).getBody()).containsExactly(stored);
    }

    @Test
    void SRS_G_30_anUnreadableUploadStoresNothing() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getBytes()).thenThrow(new IOException("disk"));

        assertThatThrownBy(
                        () ->
                                controller.attachOrder(
                                        11L,
                                        "CIRCULAR",
                                        null,
                                        LocalDate.of(2026, 11, 1),
                                        null,
                                        file))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("disk");
        verify(views, never()).attachOrder(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void SRS_G_30_aStoredCopyDownloadsAsAnAttachmentFromTheCallersAddress() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");
        DocumentStore.Content content =
                new DocumentStore.Content(view("order.pdf", "application/pdf"), new byte[] {1, 2});
        when(views.orderFile(context, "10.0.0.9", 11L, 5L)).thenReturn(content);

        ResponseEntity<byte[]> r = controller.orderFile(11L, 5L, request);

        assertThat(r.getBody()).containsExactly(1, 2);
        assertThat(r.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(r.getHeaders().getContentDisposition().getFilename()).isEqualTo("order.pdf");
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(ControllerKit.noStore(r)).isTrue();
    }
}
