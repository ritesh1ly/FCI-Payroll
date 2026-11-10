package in.gov.fci.payroll.employee.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.orchestration.HrmsUpdateRequestDocuments;
import in.gov.fci.payroll.employee.orchestration.HrmsUpdateRequestService;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

/**
 * SRS-1-70: the I-21 / E-01 update-request handlers pass the caller's own access context and every
 * particular of the request on to the service, and answer what it returned. Companion of {@code
 * EmployeeUploadFailureTest}, which pins the return values and headers.
 */
class HrmsUpdateRequestControllerMutationGapsTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final HrmsUpdateRequestService requests = mock(HrmsUpdateRequestService.class);
    private final HrmsUpdateRequestDocuments documents = mock(HrmsUpdateRequestDocuments.class);
    private final Paging paging = mock(Paging.class);
    private final HrmsUpdateRequestController controller =
            new HrmsUpdateRequestController(contexts, requests, documents, paging);

    HrmsUpdateRequestControllerMutationGapsTest() {
        when(contexts.current()).thenReturn(context);
    }

    @Test
    void SRS_1_70_theDeskOrderCopyCarriesEveryParticularAndIsNotTheMembers() {
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(documents.attach(any(), eq(11L), eq(false), any())).thenReturn(view);
        MockMultipartFile file =
                new MockMultipartFile("file", "tr-order.pdf", "application/pdf", new byte[] {1, 2});

        ResponseEntity<DocumentStore.AttachmentView> response =
                controller.attach(11L, "TRANSFER_ORDER", "TO/2026/14", TODAY, "FCI HQ", file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(view);
        ArgumentCaptor<HrmsUpdateRequestDocuments.Order> order =
                ArgumentCaptor.forClass(HrmsUpdateRequestDocuments.Order.class);
        verify(documents).attach(eq(context), eq(11L), eq(false), order.capture());
        assertThat(order.getValue().documentType()).isEqualTo("TRANSFER_ORDER");
        assertThat(order.getValue().reference()).isEqualTo("TO/2026/14");
        assertThat(order.getValue().orderDate()).isEqualTo(TODAY);
        assertThat(order.getValue().issuingAuthority()).isEqualTo("FCI HQ");
        assertThat(order.getValue().fileName()).isEqualTo("tr-order.pdf");
        assertThat(order.getValue().content()).containsExactly(1, 2);
    }

    @Test
    void SRS_1_70_theMembersOrderCopyIsFlaggedMine() {
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(documents.attach(any(), eq(12L), eq(true), any())).thenReturn(view);
        MockMultipartFile file =
                new MockMultipartFile("file", "o.pdf", "application/pdf", new byte[] {9});
        ResponseEntity<DocumentStore.AttachmentView> response =
                controller.attachMine(12L, "PAY_ORDER", null, TODAY, "FCI RO", file);
        assertThat(response.getBody()).isSameAs(view);
        verify(documents).attach(eq(context), eq(12L), eq(true), any());
    }

    @Test
    void SRS_1_70_theListsAndRefetchUseTheCallersContextAndTheRequestsId() {
        List<DocumentStore.AttachmentView> deskDocs = List.of();
        when(documents.list(context, 5L, false)).thenReturn(deskDocs);
        when(documents.list(context, 6L, true)).thenReturn(List.of());
        assertThat(controller.documents(5L).getBody()).isSameAs(deskDocs);
        assertThat(controller.myDocuments(6L).getBody()).isEmpty();

        HrmsUpdateRequestService.RequestView view =
                mock(HrmsUpdateRequestService.RequestView.class);
        when(requests.refetch(context, 8L)).thenReturn(view);
        assertThat(controller.refetch(8L).getBody()).isSameAs(view);
    }

    @Test
    void SRS_1_70_thePagesAreResolvedFromTheRequestedPageAndSize() {
        PageSpec spec = new PageSpec(2, 50);
        when(paging.resolve(2, 50)).thenReturn(spec);
        Paged<HrmsUpdateRequestService.RequestView> deskPage = new Paged<>(List.of(), 2, 50, 101);
        Paged<HrmsUpdateRequestService.RequestView> minePage = new Paged<>(List.of(), 2, 50, 3);
        when(requests.page(context, spec)).thenReturn(deskPage);
        when(requests.mine(context, spec)).thenReturn(minePage);
        assertThat(controller.list(2, 50).getBody()).isSameAs(deskPage);
        assertThat(controller.mine(2, 50).getBody()).isSameAs(minePage);
    }

    @Test
    void SRS_1_70_theMembersOwnRequestPassesTheFieldReferenceAndDateButNeverAnEis() {
        HrmsUpdateRequestService.RequestView view =
                mock(HrmsUpdateRequestService.RequestView.class);
        when(requests.raiseMine(eq(context), any())).thenReturn(view);
        controller.raiseMine(
                new HrmsUpdateRequestController.UpdateBody("900001", "GRADE_PAY", "HR-77", TODAY));
        ArgumentCaptor<HrmsUpdateRequestService.NewRequest> sent =
                ArgumentCaptor.forClass(HrmsUpdateRequestService.NewRequest.class);
        verify(requests).raiseMine(eq(context), sent.capture());
        assertThat(sent.getValue())
                .isEqualTo(
                        new HrmsUpdateRequestService.NewRequest(null, "GRADE_PAY", "HR-77", TODAY));
    }

    @Test
    void SRS_1_70_theDesksRequestPassesEveryFieldOfTheBody() {
        HrmsUpdateRequestService.RequestView view =
                mock(HrmsUpdateRequestService.RequestView.class);
        when(requests.raiseForEmployee(eq(context), any())).thenReturn(view);
        controller.raise(
                new HrmsUpdateRequestController.UpdateBody("900001", "GRADE_PAY", "HR-77", TODAY));
        verify(requests)
                .raiseForEmployee(
                        context,
                        new HrmsUpdateRequestService.NewRequest(
                                "900001", "GRADE_PAY", "HR-77", TODAY));
    }
}
