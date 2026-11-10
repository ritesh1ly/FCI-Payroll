package in.gov.fci.payroll.configuration.web;

import static in.gov.fci.payroll.ControllerKit.from;
import static in.gov.fci.payroll.ControllerKit.pdfView;
import static in.gov.fci.payroll.ControllerKit.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.configuration.orchestration.AccountingMasterService;
import in.gov.fci.payroll.configuration.orchestration.RateScreenService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * M-04 rate version orders (OD-121; SRS-G-30): an order copy is stored against the version with
 * what the form sent (201); it is listed back and downloaded as an attachment from the caller's
 * address; an unreadable upload stores nothing.
 */
class MasterScreensControllerCoverageGapsTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final RateScreenService rates = mock(RateScreenService.class);
    private final MasterScreensController controller =
            new MasterScreensController(
                    contexts,
                    rates,
                    mock(AccountingMasterService.class),
                    new ControllerKit().exports,
                    mock(Paging.class));

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
    }

    @Test
    void SRS_G_30_aRateOrderIsStoredAsSentListedAndDownloaded() throws Exception {
        DocumentStore.AttachmentView stored = pdfView();
        var file = upload((byte) 9);
        LocalDate day = LocalDate.of(2026, 7, 1);
        when(rates.attachOrder(
                        eq(context),
                        eq(14L),
                        eq("DA_ORDER"),
                        eq("DPE/1"),
                        eq(day),
                        eq("DPE"),
                        eq("order.pdf"),
                        argThat(b -> b.length == 1 && b[0] == 9)))
                .thenReturn(stored);
        when(rates.orders(context, 14L)).thenReturn(List.of(stored));
        var content = new DocumentStore.Content(stored, new byte[] {9});
        var request = from("10.0.0.8");
        when(rates.file(context, "10.0.0.8", 14L, 6L)).thenReturn(content);

        ResponseEntity<DocumentStore.AttachmentView> r =
                controller.order(14L, "DA_ORDER", "DPE/1", day, "DPE", file);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).isSameAs(stored);
        assertThat(ControllerKit.noStore(r)).isTrue();
        assertThat(controller.orders(14L).getBody()).containsExactly(stored);
        ResponseEntity<byte[]> download = controller.file(14L, 6L, request);
        assertThat(download.getBody()).containsExactly(9);
        assertThat(ControllerKit.pdfAttachment(download)).isTrue();
    }

    @Test
    void SRS_G_30_anUnreadableUploadStoresNothing() throws Exception {
        var file = ControllerKit.unreadable();

        assertThatThrownBy(
                        () ->
                                controller.order(
                                        14L,
                                        "DA_ORDER",
                                        null,
                                        LocalDate.of(2026, 7, 1),
                                        null,
                                        file))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("disk");
        verify(rates, never()).attachOrder(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
