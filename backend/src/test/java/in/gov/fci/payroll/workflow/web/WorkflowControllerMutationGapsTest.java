package in.gov.fci.payroll.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.AttachmentPresence;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Otp;
import in.gov.fci.payroll.workflow.api.Submitted;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.orchestration.ActionService;
import in.gov.fci.payroll.workflow.orchestration.ApprovalService;
import in.gov.fci.payroll.workflow.orchestration.BulkApprovalService;
import in.gov.fci.payroll.workflow.orchestration.InboxOverviewService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Controller wiring of W-01 / W-02 (SRS-G-13, SRS-G-31, SRS-G-48): every route returns what its
 * service returned, with the arguments it was given, and nothing is cached.
 */
class WorkflowControllerMutationGapsTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final ActionService actions = mock(ActionService.class);
    private final Paging paging = mock(Paging.class);
    private final BulkApprovalService bulk = mock(BulkApprovalService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final InboxOverviewService overview = mock(InboxOverviewService.class);
    private final ExportStreaming exports = mock(ExportStreaming.class);
    private final WorkflowController controller =
            new WorkflowController(contexts, approvals, actions, paging, bulk, overview, exports);

    private final PageSpec spec = new PageSpec(1, 20);

    WorkflowControllerMutationGapsTest() {
        when(contexts.current()).thenReturn(context);
        when(paging.resolve(1, 20)).thenReturn(spec);
    }

    private static void assertNoStore(ResponseEntity<?> response) {
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void SRS_G_13_theInboxReturnsTheServicePageResolvedByPaging() {
        Paged<ApprovalService.ItemView> page = new Paged<>(List.of(), 1, 20, 41);
        when(approvals.inbox(context, spec)).thenReturn(page);
        ResponseEntity<Paged<ApprovalService.ItemView>> response = controller.inbox(1, 20);
        assertThat(response.getBody()).isSameAs(page);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertNoStore(response);
    }

    @Test
    void SRS_G_13_mySubmissionsReturnsTheServicePageResolvedByPaging() {
        Paged<ApprovalService.ItemView> page = new Paged<>(List.of(), 1, 20, 3);
        when(approvals.submissions(context, spec)).thenReturn(page);
        ResponseEntity<Paged<ApprovalService.ItemView>> response = controller.submissions(1, 20);
        assertThat(response.getBody()).isSameAs(page);
        assertNoStore(response);
    }

    @Test
    void SRS_G_13_aDecisionAnswersWithTheItemIdAndStatusAndPassesTheOtpProof() {
        ApprovalItem item = mock(ApprovalItem.class);
        when(item.getId()).thenReturn(77L);
        when(item.getStatus()).thenReturn(ApprovalStatus.APPROVED);
        when(approvals.decide(
                        org.mockito.ArgumentMatchers.eq(context),
                        org.mockito.ArgumentMatchers.eq(77L),
                        org.mockito.ArgumentMatchers.eq(ApprovalService.Decision.APPROVE),
                        org.mockito.ArgumentMatchers.eq("ok"),
                        org.mockito.ArgumentMatchers.any(OtpProof.class)))
                .thenReturn(item);
        ResponseEntity<Submitted> response =
                controller.decide(
                        77L,
                        new WorkflowController.DecisionRequest(
                                ApprovalService.Decision.APPROVE, "ok", new Otp(5L, "123456")));
        assertThat(response.getBody()).isEqualTo(new Submitted(77L, "APPROVED"));
        assertNoStore(response);
    }

    @Test
    void SRS_G_13_aDecisionWithoutOtpPassesNoProof() {
        ApprovalItem item = mock(ApprovalItem.class);
        when(item.getId()).thenReturn(78L);
        when(item.getStatus()).thenReturn(ApprovalStatus.REJECTED);
        when(approvals.decide(context, 78L, ApprovalService.Decision.REJECT, "no", null))
                .thenReturn(item);
        assertThat(
                        controller
                                .decide(
                                        78L,
                                        new WorkflowController.DecisionRequest(
                                                ApprovalService.Decision.REJECT, "no", null))
                                .getBody())
                .isEqualTo(new Submitted(78L, "REJECTED"));
    }

    @Test
    void SRS_G_13_bulkApproveReturnsTheServiceOutcome() {
        BulkApprovalService.Outcome outcome =
                new BulkApprovalService.Outcome("BULK-1", List.of(1L, 2L), List.of());
        when(bulk.approve(context, List.of(1L, 2L), "all fine")).thenReturn(outcome);
        ResponseEntity<BulkApprovalService.Outcome> response =
                controller.bulkApprove(
                        new WorkflowController.BulkRequest(List.of(1L, 2L), "all fine"));
        assertThat(response.getBody()).isSameAs(outcome);
        assertNoStore(response);
    }

    @Test
    void SRS_G_31_documentsReturnsTheListOfTheItemsRecord() {
        List<AttachmentPresence.AttachedFile> files =
                List.of(
                        new AttachmentPresence.AttachedFile(
                                9L, "order.pdf", "Order", "RO/14", null, "HQ", "maker", null));
        when(approvals.documents(context, 5L)).thenReturn(files);
        ResponseEntity<List<AttachmentPresence.AttachedFile>> response = controller.documents(5L);
        assertThat(response.getBody()).isSameAs(files);
        assertNoStore(response);
    }

    @Test
    void SRS_G_31_aDocumentIsServedAsAnAttachmentNeverInlineAndNeverSniffed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        byte[] bytes = {1, 2, 3};
        when(approvals.document(context, "10.1.2.3", 5L, 9L))
                .thenReturn(
                        new AttachmentPresence.FileContent("order.pdf", "application/pdf", bytes));
        ResponseEntity<byte[]> response = controller.document(5L, 9L, (HttpServletRequest) request);
        assertThat(response.getBody()).isSameAs(bytes);
        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment")
                .contains("order.pdf");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getContentType().toString()).isEqualTo("application/pdf");
        assertThat(headers.getCacheControl()).contains("no-store");
    }

    private WorkflowController.ActionRequest actionBody(Otp otp) {
        return new WorkflowController.ActionRequest("M-10", "SUBMIT", 4L, "2026-11", "why", otp);
    }

    @Test
    void SRS_G_48_aGovernedActionThatWentToTheQueueIsAcceptedWith202() {
        ActionService.ActionResult result =
                new ActionService.ActionResult(ActionService.Outcome.SUBMITTED, 31L);
        when(actions.perform(
                        org.mockito.ArgumentMatchers.eq(context),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(result);
        ResponseEntity<ActionService.ActionResult> response =
                controller.action(actionBody(new Otp(5L, "123456")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(result);

        ArgumentCaptor<ActionService.ActionRequest> sent =
                ArgumentCaptor.forClass(ActionService.ActionRequest.class);
        verify(actions).perform(org.mockito.ArgumentMatchers.eq(context), sent.capture());
        assertThat(sent.getValue().screenCode()).isEqualTo("M-10");
        assertThat(sent.getValue().actionCode()).isEqualTo("SUBMIT");
        assertThat(sent.getValue().locationId()).isEqualTo(4L);
        assertThat(sent.getValue().entityKey()).isEqualTo("2026-11");
        assertThat(sent.getValue().reason()).isEqualTo("why");
        assertThat(sent.getValue().otp()).isEqualTo(new OtpProof(5L, "123456"));
    }

    @Test
    void SRS_G_48_aGovernedActionRecordedAtOnceIsAnswered200WithoutOtpProof() {
        ActionService.ActionResult result =
                new ActionService.ActionResult(ActionService.Outcome.RECORDED, null);
        when(actions.perform(
                        org.mockito.ArgumentMatchers.eq(context),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(result);
        ResponseEntity<ActionService.ActionResult> response = controller.action(actionBody(null));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(result);
        ArgumentCaptor<ActionService.ActionRequest> sent =
                ArgumentCaptor.forClass(ActionService.ActionRequest.class);
        verify(actions).perform(org.mockito.ArgumentMatchers.eq(context), sent.capture());
        assertThat(sent.getValue().otp()).isNull();
    }
}
