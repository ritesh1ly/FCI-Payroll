package in.gov.fci.payroll.employee.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.orchestration.EmployeeControlFlagService;
import in.gov.fci.payroll.employee.orchestration.EmployeeEventService;
import in.gov.fci.payroll.employee.orchestration.EmployeeFlagService;
import in.gov.fci.payroll.employee.orchestration.HrmsChangeRegister;
import in.gov.fci.payroll.employee.orchestration.HrmsChangeService;
import in.gov.fci.payroll.employee.orchestration.HrmsOrderService;
import in.gov.fci.payroll.employee.orchestration.HrmsUpdateRequestDocuments;
import in.gov.fci.payroll.employee.orchestration.HrmsUpdateRequestService;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * SRS-G-30 / SRS-G-31 / SRS-1-70: an order copy whose bytes cannot be read from the multipart
 * request is refused before the service is called, so no approval, attachment or audit row is half
 * written. Also pins the pass-through of the file bytes and the HrmsUpdateRequestController
 * handlers.
 */
class EmployeeUploadFailureTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1'};

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final Paging paging = mock(Paging.class);

    private static MultipartFile unreadable() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("order.pdf");
        when(file.getBytes()).thenThrow(new IOException("temp file gone"));
        return file;
    }

    private static MultipartFile readable() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("order.pdf");
        when(file.getBytes()).thenReturn(PDF);
        return file;
    }

    private static void assertUnreadable(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("temp file gone");
    }

    // ------------------------------------------------------------ M-01 allowance flags

    @Test
    void SRS_G_30_flagProposalWithUnreadableOrderIsRefusedBeforeTheServiceIsCalled()
            throws IOException {
        EmployeeFlagService service = mock(EmployeeFlagService.class);
        EmployeeFlagController controller = new EmployeeFlagController(contexts, service, paging);
        MultipartFile file = unreadable();
        EmployeeFlagController.ProposalBody body =
                new EmployeeFlagController.ProposalBody("HRA_FLAG", "Y", TODAY, null, "Order");

        assertUnreadable(
                () ->
                        controller.propose(
                                "900001", body, "TRANSFER_ORDER", "REF-1", TODAY, "FCI HQ", file));
        verifyNoInteractions(service);
    }

    @Test
    void SRS_G_30_flagProposalPassesTheFileNameAndBytesToTheService() throws IOException {
        EmployeeFlagService service = mock(EmployeeFlagService.class);
        when(service.propose(any(), eq("900001"), any(), any()))
                .thenReturn(new SubmittedApproval(7L, ApprovalStatus.PENDING_APPROVAL, null));
        EmployeeFlagController controller = new EmployeeFlagController(contexts, service, paging);
        EmployeeFlagController.ProposalBody body =
                new EmployeeFlagController.ProposalBody("HRA_FLAG", "N", TODAY, TODAY, "Order");

        ResponseEntity<EmployeeFlagController.Submitted> response =
                controller.propose(
                        "900001", body, "TRANSFER_ORDER", null, TODAY, "FCI HQ", readable());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody())
                .isEqualTo(new EmployeeFlagController.Submitted(7L, "PENDING_APPROVAL"));
        ArgumentCaptor<EmployeeFlagService.Document> document =
                ArgumentCaptor.forClass(EmployeeFlagService.Document.class);
        verify(service).propose(any(), eq("900001"), any(), document.capture());
        assertThat(document.getValue().content()).isEqualTo(PDF);
        assertThat(document.getValue().fileName()).isEqualTo("order.pdf");
        assertThat(document.getValue().reference()).isNull();
    }

    // ------------------------------------------------------------ I-21 HRMS change

    @Test
    void SRS_1_67_hrmsChangeOrderCopyWithUnreadableFileIsRefusedBeforeTheServiceIsCalled()
            throws IOException {
        HrmsChangeService service = mock(HrmsChangeService.class);
        HrmsChangeController controller =
                new HrmsChangeController(
                        contexts,
                        service,
                        mock(HrmsChangeRegister.class),
                        mock(ExportStreaming.class),
                        paging);
        MultipartFile file = unreadable();

        assertUnreadable(
                () -> controller.attach(5L, "TRANSFER_ORDER", null, TODAY, "FCI HQ", file));
        verifyNoInteractions(service);
    }

    @Test
    void SRS_1_67_hrmsChangeOrderCopyReadsTheBytesOnce() throws IOException {
        HrmsChangeService service = mock(HrmsChangeService.class);
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(service.attachOrder(any(), eq(5L), any())).thenReturn(view);
        HrmsChangeController controller =
                new HrmsChangeController(
                        contexts,
                        service,
                        mock(HrmsChangeRegister.class),
                        mock(ExportStreaming.class),
                        paging);
        MultipartFile file = readable();

        ResponseEntity<DocumentStore.AttachmentView> response =
                controller.attach(5L, "TRANSFER_ORDER", "R-2", TODAY, "FCI HQ", file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(view);
        ArgumentCaptor<HrmsChangeService.OrderCopy> copy =
                ArgumentCaptor.forClass(HrmsChangeService.OrderCopy.class);
        verify(service).attachOrder(any(), eq(5L), copy.capture());
        assertThat(copy.getValue().content()).isEqualTo(PDF);
        verify(file).getBytes();
    }

    // ------------------------------------------------------------ I-21 / E-01 update request

    @Test
    void SRS_1_70_updateRequestOrderCopyWithUnreadableFileIsRefusedForDeskAndMember()
            throws IOException {
        HrmsUpdateRequestDocuments documents = mock(HrmsUpdateRequestDocuments.class);
        HrmsUpdateRequestController controller =
                new HrmsUpdateRequestController(
                        contexts, mock(HrmsUpdateRequestService.class), documents, paging);
        MultipartFile file = unreadable();

        assertUnreadable(
                () -> controller.attach(3L, "TRANSFER_ORDER", null, TODAY, "FCI HQ", file));
        assertUnreadable(
                () -> controller.attachMine(3L, "TRANSFER_ORDER", null, TODAY, "FCI HQ", file));
        verifyNoInteractions(documents);
    }

    @Test
    void SRS_1_70_updateRequestOrderCopyIsAttachedForTheDeskAndForTheMemberWithTheirFlag()
            throws IOException {
        HrmsUpdateRequestDocuments documents = mock(HrmsUpdateRequestDocuments.class);
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(documents.attach(any(), eq(3L), anyBoolean(), any())).thenReturn(view);
        HrmsUpdateRequestController controller =
                new HrmsUpdateRequestController(
                        contexts, mock(HrmsUpdateRequestService.class), documents, paging);

        ResponseEntity<DocumentStore.AttachmentView> desk =
                controller.attach(3L, "TRANSFER_ORDER", "R-3", TODAY, "FCI HQ", readable());
        ResponseEntity<DocumentStore.AttachmentView> member =
                controller.attachMine(3L, "TRANSFER_ORDER", null, TODAY, "FCI HQ", readable());

        assertThat(desk.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(member.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(desk.getBody()).isSameAs(view);
        assertThat(member.getBody()).isSameAs(view);
        assertThat(desk.getHeaders().getCacheControl()).contains("no-store");
        ArgumentCaptor<HrmsUpdateRequestDocuments.Order> order =
                ArgumentCaptor.forClass(HrmsUpdateRequestDocuments.Order.class);
        verify(documents).attach(any(), eq(3L), eq(false), order.capture());
        verify(documents).attach(any(), eq(3L), eq(true), any());
        assertThat(order.getValue().content()).isEqualTo(PDF);
        assertThat(order.getValue().fileName()).isEqualTo("order.pdf");
        assertThat(order.getValue().reference()).isEqualTo("R-3");
    }

    @Test
    void SRS_1_70_updateRequestDocumentListsAreScopedToTheDeskOrTheMember() {
        HrmsUpdateRequestDocuments documents = mock(HrmsUpdateRequestDocuments.class);
        List<DocumentStore.AttachmentView> deskList =
                List.of(mock(DocumentStore.AttachmentView.class));
        List<DocumentStore.AttachmentView> mineList = List.of();
        when(documents.list(any(), eq(4L), eq(false))).thenReturn(deskList);
        when(documents.list(any(), eq(4L), eq(true))).thenReturn(mineList);
        HrmsUpdateRequestController controller =
                new HrmsUpdateRequestController(
                        contexts, mock(HrmsUpdateRequestService.class), documents, paging);

        assertThat(controller.documents(4L).getBody()).isSameAs(deskList);
        assertThat(controller.myDocuments(4L).getBody()).isSameAs(mineList);
        assertThat(controller.documents(4L).getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void SRS_1_70_updateRequestOrderDownloadIsAnAttachmentThatTheBrowserNeverSniffs() {
        HrmsUpdateRequestDocuments documents = mock(HrmsUpdateRequestDocuments.class);
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(view.fileName()).thenReturn("order.pdf");
        when(view.contentType()).thenReturn("application/pdf");
        when(documents.read(any(), eq("10.0.0.7"), eq(4L), eq(false), eq(9L)))
                .thenReturn(new DocumentStore.Content(view, PDF));
        when(documents.read(any(), eq("10.0.0.7"), eq(4L), eq(true), eq(9L)))
                .thenReturn(new DocumentStore.Content(view, PDF));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.7");
        HrmsUpdateRequestController controller =
                new HrmsUpdateRequestController(
                        contexts, mock(HrmsUpdateRequestService.class), documents, paging);

        for (ResponseEntity<byte[]> response :
                List.of(
                        controller.document(4L, 9L, request),
                        controller.myDocument(4L, 9L, request))) {
            assertThat(response.getBody()).isEqualTo(PDF);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .startsWith("attachment")
                    .contains("order.pdf");
            assertThat(response.getHeaders().getFirst("X-Content-Type-Options"))
                    .isEqualTo("nosniff");
            assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        }
    }

    @Test
    void SRS_1_70_updateRequestHandlersReturnTheServiceResultWithTheRightStatus() {
        HrmsUpdateRequestService requests = mock(HrmsUpdateRequestService.class);
        HrmsUpdateRequestController controller =
                new HrmsUpdateRequestController(
                        contexts, requests, mock(HrmsUpdateRequestDocuments.class), paging);
        HrmsUpdateRequestService.RequestView view =
                mock(HrmsUpdateRequestService.RequestView.class);
        List<HrmsUpdateRequestService.FieldOption> fields =
                List.of(new HrmsUpdateRequestService.FieldOption("BASIC_PAY", "Basic pay", "PAY"));
        Paged<HrmsUpdateRequestService.RequestView> page = new Paged<>(List.of(view), 0, 20, 1);
        PageSpec spec = new PageSpec(0, 20);
        when(paging.resolve(null, null)).thenReturn(spec);
        when(requests.fields()).thenReturn(fields);
        when(requests.raiseForEmployee(any(), any())).thenReturn(view);
        when(requests.raiseMine(any(), any())).thenReturn(view);
        when(requests.refetch(any(), eq(8L))).thenReturn(view);
        when(requests.page(any(), eq(spec))).thenReturn(page);
        when(requests.mine(any(), eq(spec))).thenReturn(page);
        HrmsUpdateRequestController.UpdateBody body =
                new HrmsUpdateRequestController.UpdateBody("900001", "BASIC_PAY", "HR-1", TODAY);

        assertThat(controller.fields().getBody()).isSameAs(fields);
        ResponseEntity<HrmsUpdateRequestService.RequestView> raised = controller.raise(body);
        assertThat(raised.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(raised.getBody()).isSameAs(view);
        assertThat(raised.getHeaders().getCacheControl()).contains("no-store");
        assertThat(controller.list(null, null).getBody()).isSameAs(page);
        assertThat(controller.refetch(8L).getBody()).isSameAs(view);
        assertThat(controller.mine(null, null).getBody()).isSameAs(page);
        ResponseEntity<HrmsUpdateRequestService.RequestView> mine = controller.raiseMine(body);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(mine.getBody()).isSameAs(view);

        // The employee is the session's own: the EIS of the body is never passed on (SRS-1-70).
        ArgumentCaptor<HrmsUpdateRequestService.NewRequest> own =
                ArgumentCaptor.forClass(HrmsUpdateRequestService.NewRequest.class);
        verify(requests).raiseMine(any(), own.capture());
        assertThat(own.getValue().eis()).isNull();
        assertThat(own.getValue().fieldCode()).isEqualTo("BASIC_PAY");
        ArgumentCaptor<HrmsUpdateRequestService.NewRequest> desk =
                ArgumentCaptor.forClass(HrmsUpdateRequestService.NewRequest.class);
        verify(requests).raiseForEmployee(any(), desk.capture());
        assertThat(desk.getValue().eis()).isEqualTo("900001");
        assertThat(desk.getValue().effectiveDate()).isEqualTo(TODAY);
    }

    @Test
    void SRS_1_70_updateRequestPagesRefuseAnOfferedSizeViolationFromPaging() {
        HrmsUpdateRequestService requests = mock(HrmsUpdateRequestService.class);
        HrmsUpdateRequestController controller =
                new HrmsUpdateRequestController(
                        contexts, requests, mock(HrmsUpdateRequestDocuments.class), paging);
        when(paging.resolve(any(), eq(7))).thenThrow(new IllegalArgumentException("size"));

        assertThatThrownBy(() -> controller.list(0, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.mine(0, 7))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(requests);
    }

    // ------------------------------------------------------------ M-11 lifecycle event

    @Test
    void SRS_16_18_transferOrderWithUnreadableFileIsRefusedBeforeTheServiceIsCalled()
            throws IOException {
        EmployeeEventService service = mock(EmployeeEventService.class);
        EmployeeEventController controller = new EmployeeEventController(contexts, service, paging);
        MultipartFile file = unreadable();

        assertUnreadable(
                () ->
                        controller.storeTransferOrder(
                                "900001", "TRANSFER_ORDER", null, TODAY, "FCI HQ", file));
        verifyNoInteractions(service);
    }

    @Test
    void SRS_16_18_transferOrderIsStoredWithItsBytes() throws IOException {
        EmployeeEventService service = mock(EmployeeEventService.class);
        EmployeeEventService.StoredOrder stored = new EmployeeEventService.StoredOrder(11L, "PROV");
        when(service.storeTransferOrder(any(), eq("900001"), any())).thenReturn(stored);
        EmployeeEventController controller = new EmployeeEventController(contexts, service, paging);

        ResponseEntity<EmployeeEventService.StoredOrder> response =
                controller.storeTransferOrder(
                        "900001", "TRANSFER_ORDER", "R-4", TODAY, "FCI HQ", readable());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(stored);
        ArgumentCaptor<EmployeeEventService.OrderCopy> copy =
                ArgumentCaptor.forClass(EmployeeEventService.OrderCopy.class);
        verify(service).storeTransferOrder(any(), eq("900001"), copy.capture());
        assertThat(copy.getValue().content()).isEqualTo(PDF);
    }

    // ------------------------------------------------------------ M-01 control flags

    private static EmployeeControlFlagController.ProposalBody holdBody() {
        return new EmployeeControlFlagController.ProposalBody("HOLD", "Y", TODAY, "Disciplinary");
    }

    @Test
    void SRS_G_32_controlFlagOrderWithUnreadableFileIsRefusedBeforeTheServiceIsCalled()
            throws IOException {
        EmployeeControlFlagService service = mock(EmployeeControlFlagService.class);
        EmployeeControlFlagController controller =
                new EmployeeControlFlagController(contexts, service);
        MultipartFile file = unreadable();

        assertUnreadable(
                () ->
                        controller.propose(
                                "900001",
                                holdBody(),
                                "SUSPENSION_ORDER",
                                null,
                                TODAY,
                                "FCI HQ",
                                file));
        verifyNoInteractions(service);
    }

    @Test
    void SRS_20_27_controlFlagWithoutAnOrderFileReachesTheServiceWithNullContent() {
        EmployeeControlFlagService service = mock(EmployeeControlFlagService.class);
        when(service.propose(any(), eq("900001"), any(), any()))
                .thenReturn(new SubmittedApproval(21L, ApprovalStatus.PENDING_APPROVAL, null));
        EmployeeControlFlagController controller =
                new EmployeeControlFlagController(contexts, service);

        ResponseEntity<EmployeeControlFlagController.Submitted> response =
                controller.propose(
                        "900001", holdBody(), "SUSPENSION_ORDER", null, TODAY, "FCI HQ", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ArgumentCaptor<EmployeeFlagService.Document> document =
                ArgumentCaptor.forClass(EmployeeFlagService.Document.class);
        verify(service).propose(any(), eq("900001"), any(), document.capture());
        assertThat(document.getValue().content()).isNull();
        assertThat(document.getValue().fileName()).isNull();
    }

    @Test
    void SRS_G_32_controlFlagOrderBytesAreSentToTheService() throws IOException {
        EmployeeControlFlagService service = mock(EmployeeControlFlagService.class);
        when(service.propose(any(), eq("900001"), any(), any()))
                .thenReturn(new SubmittedApproval(22L, ApprovalStatus.PENDING_APPROVAL, null));
        EmployeeControlFlagController controller =
                new EmployeeControlFlagController(contexts, service);

        controller.propose(
                "900001", holdBody(), "SUSPENSION_ORDER", "R-5", TODAY, "FCI HQ", readable());

        ArgumentCaptor<EmployeeFlagService.Document> document =
                ArgumentCaptor.forClass(EmployeeFlagService.Document.class);
        verify(service).propose(any(), eq("900001"), any(), document.capture());
        assertThat(document.getValue().content()).isEqualTo(PDF);
    }

    // ------------------------------------------------------------ I-21 order

    @Test
    void SRS_1_67_orderCopyWithUnreadableFileIsRefusedBeforeTheServiceIsCalled()
            throws IOException {
        HrmsOrderService service = mock(HrmsOrderService.class);
        HrmsOrderController controller = new HrmsOrderController(contexts, service);
        MultipartFile file = unreadable();

        assertUnreadable(() -> controller.storeCopy("TRANSFER_ORDER", null, TODAY, "FCI HQ", file));
        verifyNoInteractions(service);
    }

    @Test
    void SRS_1_67_orderCopyIsStoredWithItsBytes() throws IOException {
        HrmsOrderService service = mock(HrmsOrderService.class);
        HrmsOrderService.StoredCopy stored = new HrmsOrderService.StoredCopy(12L, "PROV");
        when(service.storeCopy(any(), any())).thenReturn(stored);
        HrmsOrderController controller = new HrmsOrderController(contexts, service);

        ResponseEntity<HrmsOrderService.StoredCopy> response =
                controller.storeCopy("TRANSFER_ORDER", "R-6", TODAY, "FCI HQ", readable());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(stored);
        ArgumentCaptor<HrmsChangeService.OrderCopy> copy =
                ArgumentCaptor.forClass(HrmsChangeService.OrderCopy.class);
        verify(service).storeCopy(any(), copy.capture());
        assertThat(copy.getValue().content()).isEqualTo(PDF);
        assertThat(copy.getValue().fileName()).isEqualTo("order.pdf");
    }
}
