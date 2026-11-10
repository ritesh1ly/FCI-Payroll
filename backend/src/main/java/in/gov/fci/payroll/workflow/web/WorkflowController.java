package in.gov.fci.payroll.workflow.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Otp;
import in.gov.fci.payroll.workflow.api.Submitted;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.orchestration.ActionService;
import in.gov.fci.payroll.workflow.orchestration.ApprovalService;
import in.gov.fci.payroll.workflow.orchestration.BulkApprovalService;
import in.gov.fci.payroll.workflow.orchestration.InboxOverviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Workflow endpoints: the approval inbox (W-01), my submissions (W-02), the checker's decision and
 * the governed wireframe actions. Each has a screen-level {@code @authz.gate} and the record-level
 * check in its service.
 */
@RestController
@Profile("online")
public class WorkflowController {

    /** SRS-G-13: the items to approve together (at most one inbox page). */
    public record BulkRequest(
            @jakarta.validation.constraints.NotEmpty @Size(max = BulkApprovalService.MAX_ITEMS)
                    java.util.List<@NotNull Long> approvalIds,
            @Size(max = 500) @PrintableAscii String reason) {}

    public record DecisionRequest(
            @NotNull ApprovalService.Decision decision,
            @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record ActionRequest(
            @NotBlank @Pattern(regexp = "^[A-Z]-[0-9]{2}$") String screen,
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,20}$") String action,
            Long locationId,
            @Size(max = 100) @PrintableAscii String entityKey,
            @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    private final CurrentAccess contexts;
    private final ApprovalService approvals;
    private final ActionService actions;
    private final Paging paging;
    private final BulkApprovalService bulk;
    private final InboxOverviewService overview;
    private final ExportStreaming exports;

    public WorkflowController(
            CurrentAccess contexts,
            ApprovalService approvals,
            ActionService actions,
            Paging paging,
            BulkApprovalService bulk,
            InboxOverviewService overview,
            ExportStreaming exports) {
        this.overview = overview;
        this.exports = exports;
        this.bulk = bulk;
        this.paging = paging;
        this.contexts = contexts;
        this.approvals = approvals;
        this.actions = actions;
    }

    @GetMapping("/v1/inbox")
    @PreAuthorize("@authz.gate('W-01','VIEW')")
    public ResponseEntity<Paged<ApprovalService.ItemView>> inbox(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(approvals.inbox(contexts.current(), paging.resolve(page, size)));
    }

    /** W-01 as drawn (OD-121): the tiles and the module filter over the whole scope. */
    @GetMapping("/v1/inbox/summary")
    @PreAuthorize("@authz.gate('W-01','VIEW')")
    public ResponseEntity<InboxOverviewService.Summary> inboxSummary() {
        return noStore(overview.summary(contexts.current()));
    }

    /** SRS-G-13: the open queue as a download (CSV or XLSX), complete or refused. */
    @GetMapping("/v1/inbox/export")
    @PreAuthorize("@authz.gate('W-01','EXPORT')")
    public ResponseEntity<StreamingResponseBody> inboxExport(
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        var context = contexts.current();
        exports.checkSize(overview.exportSize(context));
        return exports.stream("approval-inbox", format, sink -> overview.export(context, sink));
    }

    @GetMapping("/v1/submissions")
    @PreAuthorize("@authz.gate('W-02','VIEW')")
    public ResponseEntity<Paged<ApprovalService.ItemView>> submissions(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(approvals.submissions(contexts.current(), paging.resolve(page, size)));
    }

    /** The checker's decision; screen and scope are checked against the item's own module. */
    @PostMapping("/v1/approvals/{id}/decision")
    @PreAuthorize("@authz.gate('W-01','APPROVE')")
    public ResponseEntity<Submitted> decide(
            @PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        ApprovalItem item =
                approvals.decide(
                        contexts.current(),
                        id,
                        request.decision(),
                        request.reason(),
                        Otp.proof(request.otp()));
        return noStore(new Submitted(item.getId(), item.getStatus().name()));
    }

    /** SRS-G-13 / UC-07 A2 (US-GAP-21): several items approved together, each on its own. */
    @PostMapping("/v1/approvals/bulk-approvals")
    @PreAuthorize("@authz.gate('W-01','APPROVE')")
    public ResponseEntity<BulkApprovalService.Outcome> bulkApprove(
            @Valid @RequestBody BulkRequest request) {
        return noStore(bulk.approve(contexts.current(), request.approvalIds(), request.reason()));
    }

    /**
     * SRS-G-31 "View order": the documents of an item's record (W-01 / W-02). Gated like every
     * route of the screen it serves, step-up included (SRS-G-48; security review 2026-11-04, W-4:
     * {@code can() or can()} skipped the OTP confirmation of the sensitive W-01).
     */
    @GetMapping("/v1/approvals/{id}/documents")
    @PreAuthorize("@authz.gateEither('W-01','VIEW','W-02','VIEW')")
    public ResponseEntity<
                    java.util.List<
                            in.gov.fci.payroll.platform.approval.AttachmentPresence.AttachedFile>>
            documents(@PathVariable Long id) {
        return noStore(approvals.documents(contexts.current(), id));
    }

    /** One document of the item's record, served as an attachment (never rendered inline). */
    @GetMapping("/v1/approvals/{id}/documents/{fileId}")
    @PreAuthorize("@authz.gateEither('W-01','VIEW','W-02','VIEW')")
    public ResponseEntity<byte[]> document(
            @PathVariable Long id,
            @PathVariable long fileId,
            jakarta.servlet.http.HttpServletRequest request) {
        in.gov.fci.payroll.platform.approval.AttachmentPresence.FileContent c =
                approvals.document(contexts.current(), request.getRemoteAddr(), id, fileId);
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(c.fileName())
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(org.springframework.http.MediaType.parseMediaType(c.contentType()))
                .body(c.bytes());
    }

    /** A governed wireframe button (screen_action_inventory). */
    @PostMapping("/v1/actions")
    @PreAuthorize("@authz.gate(#request.screen(), #request.action())")
    public ResponseEntity<ActionService.ActionResult> action(
            @Valid @RequestBody ActionRequest request) {
        ActionService.ActionResult result =
                actions.perform(
                        contexts.current(),
                        new ActionService.ActionRequest(
                                request.screen(),
                                request.action(),
                                request.locationId(),
                                request.entityKey(),
                                request.reason(),
                                Otp.proof(request.otp())));
        return ResponseEntity.status(
                        result.outcome() == ActionService.Outcome.SUBMITTED
                                ? HttpStatus.ACCEPTED
                                : HttpStatus.OK)
                .body(result);
    }
}
