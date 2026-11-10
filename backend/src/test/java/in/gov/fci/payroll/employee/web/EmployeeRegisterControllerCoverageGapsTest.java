package in.gov.fci.payroll.employee.web;

import static in.gov.fci.payroll.ControllerKit.from;
import static in.gov.fci.payroll.ControllerKit.pdfView;
import static in.gov.fci.payroll.ControllerKit.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.orchestration.EmployeeRegisters;
import in.gov.fci.payroll.employee.orchestration.HrmsChangeService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * M-01 record orders and the I-17 export (OD-121; SRS-G-30, SRS-1-63; SRS l.3869): an order copy is
 * stored with what the form sent (201) and downloaded as an attachment from the caller's address;
 * an unreadable upload stores nothing; the I-17 export is the Suspension / Revocation Report only
 * when asked for, and carries what the register writes.
 */
class EmployeeRegisterControllerCoverageGapsTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final EmployeeRegisters registers = mock(EmployeeRegisters.class);
    private final EmployeeRegisterController controller =
            new EmployeeRegisterController(
                    contexts, registers, new ControllerKit().exports, mock(Paging.class));

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
    }

    @Test
    void SRS_G_30_aRecordOrderIsStoredAsSentAndDownloaded() throws Exception {
        DocumentStore.AttachmentView stored = pdfView();
        var file = upload((byte) 5);
        when(registers.attachOrder(eq(context), eq("104882"), any())).thenReturn(stored);
        var content = new DocumentStore.Content(stored, new byte[] {5});
        var request = from("10.0.0.5");
        when(registers.order(context, "10.0.0.5", "104882", 3L)).thenReturn(content);
        LocalDate day = LocalDate.of(2026, 11, 3);

        ResponseEntity<DocumentStore.AttachmentView> r =
                controller.attach("104882", "SALARY_RELEASE", "SR-1", day, "RO Delhi", file);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).isSameAs(stored);
        assertThat(ControllerKit.noStore(r)).isTrue();
        ArgumentCaptor<HrmsChangeService.OrderCopy> sent =
                ArgumentCaptor.forClass(HrmsChangeService.OrderCopy.class);
        verify(registers).attachOrder(eq(context), eq("104882"), sent.capture());
        HrmsChangeService.OrderCopy copy = sent.getValue();
        assertThat(copy.documentType()).isEqualTo("SALARY_RELEASE");
        assertThat(copy.reference()).isEqualTo("SR-1");
        assertThat(copy.orderDate()).isEqualTo(day);
        assertThat(copy.issuingAuthority()).isEqualTo("RO Delhi");
        assertThat(copy.fileName()).isEqualTo("order.pdf");
        assertThat(copy.content()).containsExactly(5);
        ResponseEntity<byte[]> download = controller.document("104882", 3L, request);
        assertThat(download.getBody()).containsExactly(5);
        assertThat(ControllerKit.pdfAttachment(download)).isTrue();
    }

    @Test
    void SRS_G_30_anUnreadableUploadStoresNothing() throws Exception {
        var file = ControllerKit.unreadable();

        assertThatThrownBy(
                        () ->
                                controller.attach(
                                        "104882",
                                        "SALARY_RELEASE",
                                        null,
                                        LocalDate.of(2026, 11, 3),
                                        null,
                                        file))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("disk");
        verify(registers, never()).attachOrder(any(), anyString(), any());
    }

    @Test
    void SRS_1_63_theExportIsTheSuspensionReportOnlyWhenAskedFor() throws Exception {
        doAnswer(
                        call -> {
                            ExportSink sink = call.getArgument(2);
                            sink.header("Event");
                            sink.row(((Boolean) call.getArgument(1)) ? "SUSPENSION" : "TRANSFER");
                            return null;
                        })
                .when(registers)
                .exportEvents(eq(context), anyBoolean(), any());

        var report = controller.exportEvents(true, ExportFormat.CSV);
        var events = controller.exportEvents(false, ExportFormat.CSV);

        assertThat(report.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("suspension-revocation-report.csv");
        assertThat(ControllerKit.body(report)).contains("SUSPENSION");
        assertThat(events.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("life-cycle-events.csv");
        assertThat(ControllerKit.body(events)).contains("TRANSFER").doesNotContain("SUSPENSION");
    }
}
