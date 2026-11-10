package in.gov.fci.payroll.increment.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.increment.orchestration.IncrementReportService;
import in.gov.fci.payroll.increment.orchestration.IncrementRunService;
import in.gov.fci.payroll.increment.orchestration.PayFixationService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * SRS-13-18 / SRS-G-31 (US-GAP-10): the order copy is stored before the case names it. A copy that
 * cannot be read is an I/O failure and nothing is stored; a readable one is stored with its bytes.
 */
class IncrementUploadFailureTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private final PayFixationService cases = mock(PayFixationService.class);
    private final IncrementController controller =
            new IncrementController(
                    mock(CurrentAccess.class),
                    mock(IncrementRunService.class),
                    cases,
                    mock(IncrementReportService.class),
                    mock(in.gov.fci.payroll.increment.orchestration.IncrementWorklistService.class),
                    mock(Paging.class),
                    mock(ExportStreaming.class));

    @Test
    void SRS_13_18_anOrderCopyThatCannotBeReadIsAnIoFailureAndNothingIsStored() throws IOException {
        IOException cause = new IOException("disk gone");
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getBytes()).thenThrow(cause);

        assertThatThrownBy(
                        () ->
                                controller.storeOrder(
                                        "870001", "ORDER", "REF-1", TODAY, "FCI HQ", broken))
                .isInstanceOf(UncheckedIOException.class)
                .hasCause(cause);

        verifyNoInteractions(cases);
    }

    @Test
    void SRS_13_18_aReadableOrderCopyIsStoredWithItsBytesAndAnswers201() {
        MultipartFile pdf =
                new MockMultipartFile("file", "order.pdf", "application/pdf", new byte[] {9, 8});
        ArgumentCaptor<PayFixationService.OrderCopy> sent =
                ArgumentCaptor.forClass(PayFixationService.OrderCopy.class);

        var response = controller.storeOrder("870001", "ORDER", null, TODAY, "FCI HQ", pdf);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(cases).storeOrder(any(), eq("870001"), sent.capture());
        assertThat(sent.getValue().content()).containsExactly(9, 8);
        assertThat(sent.getValue().reference()).isNull();
    }
}
