package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.ControllerKit.from;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.payroll.orchestration.PayslipPdfService;
import in.gov.fci.payroll.payroll.orchestration.PayslipRegisterService;
import in.gov.fci.payroll.payroll.orchestration.PayslipService;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * E-01 and O-01 payslips (SRS-17-1, SRS-17-6, SRS l.3381; OD-144, OD-149): the register, tiles,
 * periods and a member's slip are the month's and level's and never cached; the Maker's bulk
 * generation answers 201; the support preview's month and a single chosen month download as PDF,
 * and a repeat download of the same bytes answers 304 with no body; the finished bulk ZIP downloads
 * as an attachment.
 */
class PayslipControllerCoverageGapsTest {

    private static final PageSpec PAGE = new PageSpec(0, 20);

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PayslipService payslips = mock(PayslipService.class);
    private final PayslipRegisterService register = mock(PayslipRegisterService.class);
    private final PayslipPdfService pdfs = mock(PayslipPdfService.class);
    private final Paging paging = mock(Paging.class);
    private final ControllerKit kit = new ControllerKit();
    private final PayslipController controller =
            new PayslipController(contexts, payslips, register, paging, pdfs, kit.exports);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
        when(paging.resolve(0, 20)).thenReturn(PAGE);
    }

    @AfterEach
    void noRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void SRS_17_1_theRegisterTilesPeriodsAndSlipsAreTheMonthsAndNeverCached() {
        @SuppressWarnings("unchecked")
        Paged<PayslipRegisterService.Row> rows = mock(Paged.class);
        when(register.register(context, "202610", PayslipRegisterService.Level.STAFF, "asha", PAGE))
                .thenReturn(rows);
        PayslipRegisterService.Summary tiles = mock(PayslipRegisterService.Summary.class);
        when(register.summary(context, "202610", null)).thenReturn(tiles);
        List<PayslipRegisterService.Period> periods =
                List.of(new PayslipRegisterService.Period("202610", true, true));
        when(register.periods(context)).thenReturn(periods);
        PayslipService.Payslip staff = mock(PayslipService.Payslip.class);
        when(payslips.ofStaff(context, "104882", "202610")).thenReturn(staff);
        PayslipService.Payslip preview = mock(PayslipService.Payslip.class);
        when(payslips.ofPreview(context, 7L, "202610")).thenReturn(preview);

        List<ResponseEntity<?>> answers =
                List.of(
                        controller.register(
                                "202610", PayslipRegisterService.Level.STAFF, "asha", 0, 20),
                        controller.summary("202610", null),
                        controller.periods(),
                        controller.member("104882", "202610"),
                        controller.preview(7L, "202610"));

        assertThat(answers)
                .extracting(r -> (Object) r.getBody())
                .containsExactly(rows, tiles, periods, staff, preview);
        assertThat(answers).allMatch(ControllerKit::noStore);
    }

    @Test
    void SRS_17_1_theMakersBulkGenerationAnswers201() {
        PayslipRegisterService.Generated done =
                new PayslipRegisterService.Generated("202610", 40, 2);
        when(register.generate(context, "202610", PayslipRegisterService.Level.LABOUR_DEPOT))
                .thenReturn(done);

        var r =
                controller.generate(
                        new PayslipController.RunBody(
                                "202610", PayslipRegisterService.Level.LABOUR_DEPOT));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).isEqualTo(done);
        assertThat(r.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void SRS_17_6_theSupportPreviewsMonthDownloadsAsPdf() {
        when(pdfs.ofPreview(context, 7L, "202610")).thenReturn(new byte[] {37, 80, 68});

        ResponseEntity<byte[]> r = controller.previewPdf(7L, "202610");

        assertThat(r.getBody()).containsExactly(37, 80, 68);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(r.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("payslip-202610.pdf");
        assertThat(kit.delivered).hasSize(1);
    }

    @Test
    void OD_149_aRepeatDownloadOfTheSameMonthAnswers304WithNoBody() throws Exception {
        when(pdfs.memberMonths(context, List.of("202610"))).thenReturn(List.of("202610"));
        when(pdfs.mine(context, "202610")).thenReturn(new byte[] {1, 2, 3});
        var first = controller.mineZip("202610");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        first.getBody().writeTo(body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body.toByteArray()).containsExactly(1, 2, 3);
        String etag = first.getHeaders().getETag();

        MockHttpServletRequest again = new MockHttpServletRequest();
        again.addHeader(HttpHeaders.IF_NONE_MATCH, etag);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(again));
        var repeat = controller.mineZip("202610");
        ByteArrayOutputStream none = new ByteArrayOutputStream();
        repeat.getBody().writeTo(none);

        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(none.toByteArray()).isEmpty();
        assertThat(kit.delivered).hasSize(1);
    }

    @Test
    void OD_149_theFinishedBulkZipDownloadsAsAnAttachment() {
        var request = from("10.0.0.1");
        when(pdfs.bulkFile(context, "10.0.0.1", 81L))
                .thenReturn(
                        new PayslipPdfService.Download("payslips-202610", new byte[] {80, 75}, 3));

        ResponseEntity<byte[]> r = controller.bulkFile(81L, request);

        assertThat(r.getBody()).containsExactly(80, 75);
        assertThat(r.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("payslips-202610.zip");
        assertThat(kit.delivered).hasSize(1);
    }
}
