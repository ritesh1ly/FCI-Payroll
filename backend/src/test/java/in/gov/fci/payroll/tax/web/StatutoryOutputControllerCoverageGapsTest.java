package in.gov.fci.payroll.tax.web;

import static in.gov.fci.payroll.ControllerKit.from;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.tax.orchestration.Form16Service;
import in.gov.fci.payroll.tax.orchestration.ReturnService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * C-10 Form 16 actions and files (SRS-7-9, SRS-7-5, SRS-NF-2; OD-144, OD-149): a reissue carries
 * its reason; the certificate PDF downloads under its own name, logged once; a bulk file is queued
 * (202), its status is the newest (204 when none was asked for) and the finished ZIP downloads as
 * an attachment; nothing here is cached.
 */
class StatutoryOutputControllerCoverageGapsTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final Form16Service form16 = mock(Form16Service.class);
    private final ControllerKit kit = new ControllerKit();
    private final StatutoryOutputController controller =
            new StatutoryOutputController(
                    contexts, form16, mock(ReturnService.class), mock(Paging.class), kit.exports);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
    }

    @Test
    void SRS_7_9_aReissueCarriesItsReasonAndIsNeverCached() {
        Form16Service.CertificateView view = mock(Form16Service.CertificateView.class);
        when(form16.reissue(context, 5L, "PAN corrected")).thenReturn(view);

        var r =
                controller.reissue(
                        5L, new StatutoryOutputController.ReasonRequest("PAN corrected"));

        assertThat(r.getBody()).isSameAs(view);
        assertThat(ControllerKit.noStore(r)).isTrue();
    }

    @Test
    void SRS_7_5_theCertificatePdfDownloadsUnderItsNameAndIsLogged() {
        var request = from("10.0.0.3");
        when(form16.certificatePdf(context, "10.0.0.3", 5L))
                .thenReturn(
                        new Form16Service.CertificatePdf(
                                "form16-104882-2025-26", new byte[] {37, 80}, 3, true));

        ResponseEntity<byte[]> r = controller.partB(5L, request);

        assertThat(r.getBody()).containsExactly(37, 80);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(r.getHeaders().getContentDisposition().getFilename())
                .startsWith("form16-104882-2025-26");
        assertThat(kit.delivered).hasSize(1);
    }

    @Test
    void SRS_NF_2_aBulkFileIsQueuedItsStatusIsTheNewestAndTheZipDownloads() {
        Form16Service.BulkFile queued = new Form16Service.BulkFile(81L, "QUEUED", 0, 0);
        when(form16.requestBulk(context, "2025-26")).thenReturn(queued);
        when(form16.bulkStatus(context, "2025-26")).thenReturn(Optional.of(queued));
        when(form16.bulkStatus(context, "2024-25")).thenReturn(Optional.empty());
        var request = from("10.0.0.3");
        when(form16.bulkFile(context, "10.0.0.3", 81L))
                .thenReturn(
                        new Form16Service.BulkDownload("form16-2025-26", new byte[] {80, 75}, 2));

        var asked = controller.requestBulk(new StatutoryOutputController.YearRequest("2025-26"));
        var status = controller.bulkStatus("2025-26");
        var none = controller.bulkStatus("2024-25");
        ResponseEntity<byte[]> zip = controller.bulkFile(81L, request);

        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(asked.getBody()).isEqualTo(queued);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).isEqualTo(queued);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(none.getBody()).isNull();
        assertThat(java.util.List.of(asked, status, none)).allMatch(ControllerKit::noStore);
        assertThat(zip.getBody()).containsExactly(80, 75);
        assertThat(zip.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("form16-2025-26.zip");
        assertThat(kit.delivered).hasSize(1);
    }
}
