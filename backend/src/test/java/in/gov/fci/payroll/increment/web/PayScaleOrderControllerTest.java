package in.gov.fci.payroll.increment.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.increment.orchestration.PayScaleOrders;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;

/**
 * The M-03 order card's endpoints (SRS-G-30, R-RBAC): each carries its gate, a stored copy is 201
 * with its bytes, an unreadable upload stores nothing, a download is an attachment that the browser
 * never renders inline.
 */
class PayScaleOrderControllerTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private final PayScaleOrders orders = mock(PayScaleOrders.class);
    private final PayScaleOrderController controller =
            new PayScaleOrderController(mock(CurrentAccess.class), orders);

    private static DocumentStore.AttachmentView view() {
        return new DocumentStore.AttachmentView(
                7L,
                "pay_scale_band",
                "E-4",
                "CIRCULAR",
                "Circular",
                "FCI/EST/SCALE/E-4",
                TODAY,
                "Headquarters",
                "order.pdf",
                "application/pdf",
                1L,
                "0",
                "ACTIVE",
                "ra@x",
                Instant.EPOCH);
    }

    private static String gate(String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = PayScaleOrderController.class.getMethod(name, parameters);
        return method.getAnnotation(PreAuthorize.class).value();
    }

    @Test
    void R_RBAC_everyEndpointCarriesItsGate() throws NoSuchMethodException {
        assertThat(gate("orders", String.class)).isEqualTo("@authz.gate('M-03','VIEW')");
        assertThat(
                        gate(
                                "attach",
                                String.class,
                                String.class,
                                String.class,
                                LocalDate.class,
                                String.class,
                                MultipartFile.class))
                .isEqualTo("@authz.gate('M-03','CREATE')");
        assertThat(
                        gate(
                                "file",
                                String.class,
                                long.class,
                                jakarta.servlet.http.HttpServletRequest.class))
                .isEqualTo("@authz.gate('M-03','VIEW')");
    }

    @Test
    void SRS_G_30_aCopyIsStoredWithItsBytesAnd201() {
        when(orders.attach(any(), eq("E-4"), any())).thenReturn(view());
        ResponseEntity<DocumentStore.AttachmentView> response =
                controller.attach(
                        "E-4",
                        "CIRCULAR",
                        "FCI/EST/SCALE/E-4",
                        TODAY,
                        "Headquarters",
                        new MockMultipartFile(
                                "file", "order.pdf", "application/pdf", new byte[] {1}));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ArgumentCaptor<DocumentStore.NewAttachment> stored =
                ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        verify(orders).attach(any(), eq("E-4"), stored.capture());
        assertThat(stored.getValue().content()).containsExactly(1);
        assertThat(stored.getValue().fileName()).isEqualTo("order.pdf");
    }

    @Test
    void SRS_G_30_aCopyThatCannotBeReadStoresNothing() throws IOException {
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getBytes()).thenThrow(new IOException("disk gone"));
        assertThatThrownBy(
                        () ->
                                controller.attach(
                                        "E-4", "CIRCULAR", "R", TODAY, "Headquarters", broken))
                .isInstanceOf(UncheckedIOException.class);
        verifyNoInteractions(orders);
    }

    @Test
    void SRS_G_30_aDownloadIsAnAttachmentAndNeverSniffed() {
        when(orders.file(any(), eq("10.0.0.1"), eq("E-4"), eq(7L)))
                .thenReturn(new DocumentStore.Content(view(), new byte[] {1}));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        ResponseEntity<byte[]> response = controller.file("E-4", 7L, request);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).containsExactly(1);
    }

    @Test
    void SRS_G_30_theCopiesOfAScaleAreReturnedUncached() {
        when(orders.orders(any(), eq("E-4"))).thenReturn(java.util.List.of(view()));
        assertThat(controller.orders("E-4").getBody()).hasSize(1);
    }
}
